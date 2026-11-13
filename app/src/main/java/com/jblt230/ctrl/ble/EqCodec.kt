package com.jblt230.ctrl.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class CustomEqTransfer(
    val headerFrame: ByteArray,
    val chunkFrames: List<ByteArray>,
    val footerFrame: ByteArray,
    val rawPayload: ByteArray
)

data class CustomEqState(
    val presetIndex: Int,
    val eqCategory: Int,
    val sampleRate: Int,
    val gains: List<Float>,
    val frequencies: List<Float>
)

/**
 * Harman/BES EQ codecs for JBL TUNE230NC TWS (`Tune230Control`).
 *
 * Verified from official jbl.stc.com 5.38.2 decompilation:
 *  - `Tune230Control` overrides `getEQSettings2` and `setEQSettings2` to route
 *    through `BesEQControl`, using the BES multi-part EQ protocol (`0x42` read,
 *    `0x43` multi-part reply + `AA 01 02 43 00` ACKs + `AA 04 43` fin, and
 *    `0x41` multi-part write + `AA 05 41` fin) plus `AA 40 01 <preset>` for
 *    built-in preset switching.
 *  - Legacy single-frame `0xA2` (`EQCmd`) is kept as a secondary fallback and
 *    for un-overridden test compatibility.
 */
object EqCodec {

    const val CMD_PRESET_SET = 0x40
    const val CMD_CUSTOM_WRITE = 0x41
    const val CMD_CUSTOM_READ = 0x42
    const val CMD_CUSTOM_RET = 0x43
    const val CMD_A2 = 0xA2

    const val CAT_DESIGN_EQ = 0
    const val CAT_GRAPHIC_EQ = 1
    const val CAT_TOTAL_EQ = 2
    const val CAT_PERSONIFY_EQ = 3

    const val PRESET_IDX_OFF = 0x00
    const val PRESET_IDX_JAZZ = 0x01
    const val PRESET_IDX_VOCAL = 0x02
    const val PRESET_IDX_BASS = 0x03
    const val PRESET_IDX_USER = 0x04
    const val PRESET_IDX_ROCK = 0x11
    const val PRESET_IDX_PIANO = 0x12
    const val PRESET_IDX_CLUB = 0x13
    const val PRESET_IDX_STUDIO = 0x14

    private const val SUB_SET = 0x00
    private const val SUB_SET_Q10 = 0x03
    private const val SUB_REQ = 0x01
    private const val SUB_RET = 0x02
    private const val SUB_RET_Q10 = 0x04
    private const val SAMPLE_RATE_KHZ = 48
    private const val A2_BAND_TYPE_PEAK = 1
    private const val BES_BAND_TYPE_PEAK = 2
    private const val DEFAULT_Q = 1f
    private const val BES_DEFAULT_Q = 2f
    private const val BAND_COUNT = 10
    private const val CHUNK_DATA_SIZE = 79

    val DEFAULT_FREQUENCIES = listOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    fun presetIdToByte(presetId: String): Int? = when (presetId.lowercase()) {
        "off" -> PRESET_IDX_OFF
        "jazz" -> PRESET_IDX_JAZZ
        "vocal" -> PRESET_IDX_VOCAL
        "bass" -> PRESET_IDX_BASS
        "custom", "user" -> PRESET_IDX_USER
        "rock" -> PRESET_IDX_ROCK
        "piano" -> PRESET_IDX_PIANO
        "club" -> PRESET_IDX_CLUB
        "studio" -> PRESET_IDX_STUDIO
        else -> null
    }

    fun presetByteToId(presetByte: Int): String? = when (presetByte and 0xFF) {
        PRESET_IDX_OFF -> "off"
        PRESET_IDX_JAZZ -> "jazz"
        PRESET_IDX_VOCAL -> "vocal"
        PRESET_IDX_BASS -> "bass"
        PRESET_IDX_USER -> "custom"
        PRESET_IDX_ROCK -> "rock"
        PRESET_IDX_PIANO -> "piano"
        PRESET_IDX_CLUB -> "club"
        PRESET_IDX_STUDIO -> "studio"
        else -> null
    }

    /** `AA 40 01 <presetByte>` built-in EQ preset switch. */
    fun buildSetEqPresetFrame(presetByte: Int): ByteArray =
        byteArrayOf(0xAA.toByte(), CMD_PRESET_SET.toByte(), 0x01, presetByte.toByte())

    /** `AA 42 01 <catOrdinal>` — request BES custom/graphic/total EQ (`0x43` multi-part reply). */
    fun buildGetCustomEqFrame(categoryOrdinal: Int = CAT_GRAPHIC_EQ): ByteArray =
        byteArrayOf(0xAA.toByte(), CMD_CUSTOM_READ.toByte(), 0x01, categoryOrdinal.toByte())

    /** `AA 01 02 43 00` — app ACK sent after receiving `AA 43 <len> <count>`. */
    fun buildCustomEqAppAckFrame(): ByteArray =
        byteArrayOf(0xAA.toByte(), 0x01, 0x02, CMD_CUSTOM_RET.toByte(), 0x00)

    /**
     * Builds the 179-byte payload + 5-frame sequence matching
     * `CmdGen.generateSetCustomEqCmd` + `EqCmdData.getListQueue()`:
     *   1. `AA 41 B3 03` (header: 179 bytes, 3 chunks)
     *   2. `00 <payload[0..78]>` (80B)
     *   3. `01 <payload[79..157]>` (80B)
     *   4. `02 <payload[158..178]>` (22B)
     *   5. `AA 05 41` (app fin ACK)
     */
    fun buildSetCustomEqTransfer(
        presetIndex: Int = PRESET_IDX_USER,
        gains: List<Float>,
        eqCategory: Int = CAT_GRAPHIC_EQ,
        frequencies: List<Int> = DEFAULT_FREQUENCIES,
        calibration: Float = 0f,
        leftGain: Float = 0f,
        rightGain: Float = 0f,
        bandType: Int = BES_BAND_TYPE_PEAK,
        qValue: Float = BES_DEFAULT_Q
    ): CustomEqTransfer {
        require(gains.size == BAND_COUNT) { "Need exactly 10 band gains" }
        require(frequencies.size == BAND_COUNT) { "Need exactly 10 band frequencies" }

        val payload = ByteArray(19 + BAND_COUNT * 16)
        var p = 0
        payload[p++] = presetIndex.toByte()
        payload[p++] = eqCategory.toByte()
        putFloat(payload, p, calibration); p += 4
        payload[p++] = SAMPLE_RATE_KHZ.toByte()
        putFloat(payload, p, leftGain); p += 4
        putFloat(payload, p, rightGain); p += 4
        putInt(payload, p, BAND_COUNT); p += 4
        for (i in 0 until BAND_COUNT) {
            putInt(payload, p, bandType); p += 4
            putFloat(payload, p, gains[i].coerceIn(-6f, 6f)); p += 4
            putFloat(payload, p, frequencies[i].toFloat()); p += 4
            putFloat(payload, p, qValue); p += 4
        }

        val chunkCount = (payload.size + CHUNK_DATA_SIZE - 1) / CHUNK_DATA_SIZE
        val header = byteArrayOf(
            0xAA.toByte(),
            CMD_CUSTOM_WRITE.toByte(),
            (payload.size and 0xFF).toByte(),
            (chunkCount and 0xFF).toByte()
        )
        val chunks = ArrayList<ByteArray>(chunkCount)
        for (idx in 0 until chunkCount) {
            val start = idx * CHUNK_DATA_SIZE
            val len = minOf(CHUNK_DATA_SIZE, payload.size - start)
            val chunk = ByteArray(len + 1)
            chunk[0] = idx.toByte()
            System.arraycopy(payload, start, chunk, 1, len)
            chunks.add(chunk)
        }
        val footer = byteArrayOf(0xAA.toByte(), 0x05, CMD_CUSTOM_WRITE.toByte())
        return CustomEqTransfer(header, chunks, footer, payload)
    }

    /** Extracts `(totalLen, chunkCount)` from an `AA 43 <len> <count>` start frame. */
    fun parseCustomEqHeader(frame: Frame): Pair<Int, Int>? {
        if (frame.cmd != CMD_CUSTOM_RET || frame.raw.size < 4) return null
        val totalLen = frame.raw[2].toInt() and 0xFF
        val count = frame.raw[3].toInt() and 0xFF
        return totalLen to count
    }

    /**
     * Decodes a reassembled BES `0x43` payload (accepts both the 175-byte read
     * layout in `HandleParse.parseCustomEQ` and the 179-byte layout with 4-byte
     * calibration in `CmdGen.generateSetCustomEqCmd`).
     */
    fun parseCustomEqPayload(payload: ByteArray): CustomEqState? {
        if (payload.size < 31) return null
        val presetIndex = payload[0].toInt() and 0xFF
        val eqCategory = payload[1].toInt() and 0xFF

        val countAt11 = getInt(payload, 11)
        val countAt15 = getInt(payload, 15)
        val (sampleRate, count, bandsOffset) = when {
            countAt11 in 1..15 && payload.size >= 15 + countAt11 * 16 -> {
                Triple(payload[2].toInt() and 0xFF, countAt11, 15)
            }
            countAt15 in 1..15 && payload.size >= 19 + countAt15 * 16 -> {
                Triple(payload[6].toInt() and 0xFF, countAt15, 19)
            }
            else -> return null
        }

        val gains = ArrayList<Float>(count)
        val freqs = ArrayList<Float>(count)
        for (i in 0 until count) {
            val base = bandsOffset + i * 16
            gains.add(getFloat(payload, base + 4))
            freqs.add(getFloat(payload, base + 8))
        }
        return CustomEqState(
            presetIndex = presetIndex,
            eqCategory = eqCategory,
            sampleRate = sampleRate,
            gains = gains,
            frequencies = freqs
        )
    }

    fun gainsMatch(expected: List<Float>, actual: List<Float>, toleranceDb: Float = 0.25f): Boolean {
        if (expected.size != actual.size || expected.isEmpty()) return false
        return expected.indices.all { abs(expected[it] - actual[it]) <= toleranceDb }
    }

    // ---- Legacy single-frame 0xA2 codec (kept as fallback + existing tests) ----

    /** `AA A2 02 01 <category>` — 1-byte length, built manually on purpose. */
    fun readFrame(category: Int): ByteArray =
        byteArrayOf(0xAA.toByte(), CMD_A2.toByte(), 0x02, SUB_REQ.toByte(), category.toByte())

    /** Category 255 = "EQ currently in use", 202 = design EQ, 230 = user curve. */
    fun readCurrentFrame(): ByteArray = readFrame(255)

    fun writeFrame(
        category: Int,
        gains: List<Float>,
        frequencies: List<Int> = DEFAULT_FREQUENCIES,
        calibration: Float = 0f,
        leftGain: Float = 0f,
        rightGain: Float = 0f,
        qTimesTen: Boolean = false
    ): ByteArray {
        require(gains.size == BAND_COUNT) { "Need exactly 10 band gains" }
        require(frequencies.size == BAND_COUNT) { "Need exactly 10 band frequencies" }

        val payload = ByteArray(16 + BAND_COUNT * 10)
        var p = 0
        payload[p++] = (if (qTimesTen) SUB_SET_Q10 else SUB_SET).toByte()
        payload[p++] = category.toByte()
        putFloat(payload, p, calibration); p += 4
        payload[p++] = SAMPLE_RATE_KHZ.toByte()
        putFloat(payload, p, leftGain); p += 4
        putFloat(payload, p, rightGain); p += 4
        payload[p++] = BAND_COUNT.toByte()
        for (i in 0 until BAND_COUNT) {
            payload[p++] = A2_BAND_TYPE_PEAK.toByte()
            putFloat(payload, p, gains[i]); p += 4
            putFloat(payload, p, frequencies[i].toFloat()); p += 4
            payload[p++] = (DEFAULT_Q * (if (qTimesTen) 10f else 1f)).toInt().toByte()
        }
        return FrameCodec.encode(CMD_A2, payload)
    }

    /** Parses the category out of an A2 reply (`AA A2 <len2> <sub> <category> ...`). */
    fun parseCategory(frame: Frame): Int? {
        if (frame.cmd != CMD_A2) return null
        val p = frame.payload
        if (p.size < 2) return null
        val sub = p[0].toInt() and 0xFF
        if (sub != SUB_RET && sub != SUB_RET_Q10) return null
        return p[1].toInt() and 0xFF
    }

    private fun putFloat(dst: ByteArray, offset: Int, value: Float) {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
        System.arraycopy(b, 0, dst, offset, 4)
    }

    private fun getFloat(src: ByteArray, offset: Int): Float =
        ByteBuffer.wrap(src, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float

    private fun putInt(dst: ByteArray, offset: Int, value: Int) {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        System.arraycopy(b, 0, dst, offset, 4)
    }

    private fun getInt(src: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(src, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}
