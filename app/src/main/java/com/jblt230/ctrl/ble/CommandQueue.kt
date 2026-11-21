package com.jblt230.ctrl.ble

import android.util.Log
import com.jblt230.ctrl.logging.ProtocolLog
import com.jblt230.ctrl.protocol.CommandTable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream

sealed interface WriteResult {
    data class Verified(val readback: Frame, val reply: Frame?) : WriteResult
    data class Unverified(val reason: String) : WriteResult
    data class Refused(val reason: String) : WriteResult
}

data class CustomEqWriteResult(
    val verified: Boolean,
    val readbackState: CustomEqState?,
    val reason: String
)

/**
 * Serial single-command queue. The device accepts one command at a time.
 *
 * Contract: a write is NEVER reported as successful just because an ACK frame
 * arrived. It is only "Verified" after the corresponding read command returns
 * a value matching the expected state.
 */
class CommandQueue(
    private val transport: GattTransport,
    private val table: CommandTable,
    private val guard: CommandGuard,
    private val log: ProtocolLog,
    private val timeoutMs: Long = 1200L
) {
    private val mutex = Mutex()

    suspend fun read(
        name: String,
        timeoutOverrideMs: Long? = null,
        match: (Frame) -> Boolean = { true }
    ): Frame? = mutex.withLock {
        val spec = table[name] ?: return@withLock null
        if (!spec.enabled || spec.frameHex == null) {
            log.add("!", "", "$name 未启用（${spec.confidence}）")
            return@withLock null
        }
        guard.assertResolvable(spec.frameHex, spec.requiresResolution)
        val replyCmd = spec.replyCmd ?: spec.cmd
        var reply = sendAndAwait(spec.frameHex, replyCmd, match, timeoutOverrideMs = timeoutOverrideMs)
        // Core reads (no fast optional probe timeout override) get one clean retry after 80ms
        // in case a multi-part transfer or RF burst caused a transient miss.
        if (reply == null && timeoutOverrideMs == null) {
            delay(80L)
            reply = sendAndAwait(spec.frameHex, replyCmd, match, timeoutOverrideMs = null)
        }
        reply
    }

    /** Static command from commands.json. */
    suspend fun writeAndVerify(
        name: String,
        readFilter: (Frame) -> Boolean = { true },
        matches: (Frame) -> Boolean
    ): WriteResult = mutex.withLock {
        val spec = table[name]
            ?: return@withLock WriteResult.Refused("命令未定义：$name")
        if (!spec.enabled) {
            return@withLock WriteResult.Refused(spec.note.ifBlank { "该命令尚未通过真机验证，已禁用" })
        }
        if (spec.requiresResolution || spec.frameHex == null) {
            return@withLock WriteResult.Refused("协议命令族未裁决，禁止下发")
        }
        verify(spec.frameHex, spec.replyCmd ?: spec.cmd, spec.readback, readFilter, matches)
    }

    /** Dynamically built frame. Same guard + readback rules. */
    suspend fun writeFrameAndVerify(
        frameHex: String,
        replyCmd: Int?,
        readbackName: String,
        readFilter: (Frame) -> Boolean = { true },
        matches: (Frame) -> Boolean
    ): WriteResult = mutex.withLock {
        verify(frameHex, replyCmd, readbackName, readFilter, matches)
    }

    /**
     * Reads EQ via the BES 0x42 / 0x43 multi-part handshake:
     *   1. Send `AA 42 01 <cat>`
     *   2. Wait for `AA 43 <totalLen> <count>`
     *   3. Send `count + 1` frames of `AA 01 02 43 00`
     *   4. Collect `count` raw continuation chunks (`00..`, `01..`, `02..`) until `AA 04 43`
     */
    suspend fun readCustomEq(categoryOrdinal: Int = EqCodec.CAT_GRAPHIC_EQ): CustomEqState? =
        mutex.withLock { readCustomEqInternal(categoryOrdinal) }

    /**
     * Writes a 10-band EQ curve via the BES 0x41 5-frame sequence, waits for ACK,
     * then reads back via `0x42 / 0x43` and compares all 10 band gains.
     */
    suspend fun writeCustomEqAndVerify(
        presetIndex: Int,
        gains: List<Float>,
        eqCategory: Int = EqCodec.CAT_GRAPHIC_EQ
    ): CustomEqWriteResult = mutex.withLock {
        val transfer = EqCodec.buildSetCustomEqTransfer(
            presetIndex = presetIndex,
            gains = gains,
            eqCategory = eqCategory
        )
        val acked = coroutineScope {
            val startSeq = transport.currentSeq()
            val ackWaiter = async {
                withTimeoutOrNull(1800L) {
                    transport.incoming.firstOrNull { inc ->
                        val f = inc.frame ?: return@firstOrNull false
                        inc.seq > startSeq && f.cmd == 0x00 && FrameCodec.parseAck(f)?.first == EqCodec.CMD_CUSTOM_WRITE
                    }
                }
            }
            yield()

            val headerOk = try {
                transport.writeFrame(transfer.headerFrame)
            } catch (e: CommandGuard.BlockedCommandException) {
                ackWaiter.cancel()
                return@coroutineScope Result.failure<Boolean>(e)
            }
            if (!headerOk) {
                ackWaiter.cancel()
                return@coroutineScope Result.success(false)
            }

            for ((idx, chunk) in transfer.chunkFrames.withIndex()) {
                delay(50L)
                val ok = try {
                    transport.writeEqChunk(chunk, idx)
                } catch (e: CommandGuard.BlockedCommandException) {
                    ackWaiter.cancel()
                    return@coroutineScope Result.failure<Boolean>(e)
                }
                if (!ok) {
                    ackWaiter.cancel()
                    return@coroutineScope Result.success(false)
                }
            }
            delay(50L)
            val footerOk = try {
                transport.writeFrame(transfer.footerFrame)
            } catch (e: CommandGuard.BlockedCommandException) {
                ackWaiter.cancel()
                return@coroutineScope Result.failure<Boolean>(e)
            }
            if (!footerOk) {
                ackWaiter.cancel()
                return@coroutineScope Result.success(false)
            }
            val ack = ackWaiter.await()
            Result.success(ack != null)
        }

        if (acked.isFailure) {
            return@withLock CustomEqWriteResult(false, null, "EQ 命令被 CommandGuard 拦截")
        }

        // Wait briefly for DSP commit before reading back the curve
        delay(180L)
        val readback = readCustomEqInternal(eqCategory)
            ?: readCustomEqInternal(EqCodec.CAT_TOTAL_EQ)
            ?: return@withLock CustomEqWriteResult(false, null, "0x41 写入后 0x42 回读超时")

        val match = EqCodec.gainsMatch(gains, readback.gains, toleranceDb = 0.25f)
        if (match) {
            CustomEqWriteResult(true, readback, "0x41 写入并 0x42/0x43 回读一致")
        } else {
            CustomEqWriteResult(false, readback, "回读 10 段增益与目标不一致：${readback.gains}")
        }
    }

    private suspend fun readCustomEqInternal(categoryOrdinal: Int): CustomEqState? = coroutineScope {
        val reqFrame = EqCodec.buildGetCustomEqFrame(categoryOrdinal)
        val startSeq = transport.currentSeq()

        // Buffer all packets arrived after we send 0x42 so chunks immediately following AA 43 are never lost.
        val collected = ArrayList<Incoming>()
        val collector = async {
            withTimeoutOrNull(2600L) {
                transport.incoming
                    .takeWhile { inc ->
                        if (inc.seq <= startSeq) return@takeWhile true
                        val f = inc.frame
                        // Stop once we see AA 04 43 (RET_DEV_FIN_ACK for 0x43)
                        !(f != null && f.cmd == 0x04 && f.payload.firstOrNull()?.toInt()?.and(0xFF) == EqCodec.CMD_CUSTOM_RET)
                    }
                    .collect { inc ->
                        if (inc.seq > startSeq) collected.add(inc)
                    }
            }
        }
        yield()

        val sentReq = runCatching { transport.writeFrame(reqFrame) }.getOrDefault(false)
        if (!sentReq) {
            collector.cancel()
            return@coroutineScope null
        }

        // Wait up to 1200ms for AA 43 <len> <count> to arrive in `collected`
        var headerPair: Pair<Int, Int>? = null
        val waitStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - waitStart < 1200L) {
            val hFrame = collected.firstOrNull { it.frame?.cmd == EqCodec.CMD_CUSTOM_RET }?.frame
            if (hFrame != null) {
                headerPair = EqCodec.parseCustomEqHeader(hFrame)
                break
            }
            delay(25L)
        }
        if (headerPair == null) {
            collector.cancel()
            log.add("!", FrameCodec.hex(reqFrame), "等待 0x43 多包头超时")
            return@coroutineScope null
        }

        val (declaredLen, count) = headerPair
        val ackFrame = EqCodec.buildCustomEqAppAckFrame()
        // HandleParse line 1457 sends count + 1 ACKs
        repeat(count.coerceIn(1, 10) + 1) {
            delay(30L)
            runCatching { transport.writeFrame(ackFrame) }
        }

        // Await AA 04 43 completion (or collector timeout)
        withTimeoutOrNull(1800L) { collector.await() }
        collector.cancel()

        val chunks = collected
            .filter { it.frame == null && it.raw.size >= 2 && (it.raw[0].toInt() and 0xFF) in 0..15 }
            .sortedBy { it.raw[0].toInt() and 0xFF }

        if (chunks.isEmpty()) {
            log.add("!", FrameCodec.hex(reqFrame), "0x43 未收到分块载荷（declaredLen=$declaredLen, count=$count）")
            return@coroutineScope null
        }
        val out = ByteArrayOutputStream()
        chunks.forEach { c -> out.write(c.raw, 1, c.raw.size - 1) }
        val payload = out.toByteArray()
        val parsed = EqCodec.parseCustomEqPayload(payload)
        if (parsed == null) {
            log.add("!", FrameCodec.hex(payload), "0x43 载荷解析失败（len=${payload.size}）")
        }
        // Allow BES firmware to settle after AA 04 43 before the next queued GATT frame
        delay(160L)
        parsed
    }

    private suspend fun verify(
        frameHex: String,
        replyCmd: Int?,
        readbackName: String?,
        readFilter: (Frame) -> Boolean,
        matches: (Frame) -> Boolean
    ): WriteResult {
        val writeReply = try {
            sendAndAwait(frameHex, replyCmd, acceptAckAsWriteReply = true)
        } catch (e: CommandGuard.BlockedCommandException) {
            return WriteResult.Refused("命令被 CommandGuard 拦截：${e.message}")
        }
        if (writeReply == null) {
            log.add("!", frameHex, "写入无即时回复，改以回读判定")
        }
        if (readbackName.isNullOrBlank() || readbackName == "none") {
            return WriteResult.Unverified("该命令无回读通道，无法确认生效")
        }
        val readSpec = table[readbackName]
            ?: return WriteResult.Unverified("回读命令未定义：$readbackName")
        if (!readSpec.enabled || readSpec.frameHex == null) {
            return WriteResult.Unverified("回读命令未启用：$readbackName")
        }
        val readFrame = sendAndAwait(readSpec.frameHex, readSpec.replyCmd ?: readSpec.cmd, readFilter, acceptAckAsWriteReply = false)
            ?: return WriteResult.Unverified("回读超时，未确认生效")
        return if (matches(readFrame)) {
            WriteResult.Verified(readFrame, writeReply)
        } else {
            WriteResult.Unverified("回读值与期望不符：${readFrame.hex}")
        }
    }

    /** Sends one frame and waits for the matching reply. */
    private suspend fun sendAndAwait(
        frameHex: String,
        expectCmd: Int?,
        match: (Frame) -> Boolean = { true },
        acceptAckAsWriteReply: Boolean = false,
        timeoutOverrideMs: Long? = null
    ): Frame? = coroutineScope {
        val bytes = FrameCodec.parseHex(frameHex)
        val sentCmd = bytes[1].toInt() and 0xFF
        val targetCmd = expectCmd ?: FrameCodec.expectedReplyCmd(sentCmd)
        val startSeq = transport.currentSeq()

        val waiter = async {
            withTimeoutOrNull(timeoutOverrideMs ?: timeoutMs) {
                transport.incoming.firstOrNull { inc ->
                    val f = inc.frame ?: return@firstOrNull false
                    if (inc.seq <= startSeq) return@firstOrNull false
                    if (targetCmd == 0x00) {
                        f.cmd == 0x00 && FrameCodec.parseAck(f)?.first == sentCmd && match(f)
                    } else if (acceptAckAsWriteReply) {
                        (f.cmd == targetCmd && match(f)) ||
                            (f.cmd == 0x00 && FrameCodec.parseAck(f)?.first == sentCmd)
                    } else {
                        f.cmd == targetCmd && match(f)
                    }
                }
            }?.frame
        }
        yield()

        val sent = try {
            transport.writeFrame(bytes)
        } catch (e: CommandGuard.BlockedCommandException) {
            waiter.cancel()
            throw e
        }
        if (!sent) {
            log.add("!", frameHex, "GATT 写入失败或未就绪")
            waiter.cancel()
            return@coroutineScope null
        }
        val reply = try {
            waiter.await()
        } catch (e: Exception) {
            Log.w(TAG, "waiter failed", e)
            null
        }
        if (reply == null) log.add("!", frameHex, "等待 0x%02X 回复超时".format(targetCmd))
        reply
    }

    companion object {
        private const val TAG = "JblT230Ctrl/Queue"
    }
}
