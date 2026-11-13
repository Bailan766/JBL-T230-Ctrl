package com.jblt230.ctrl.ble

import java.nio.charset.StandardCharsets

/** A decoded Harman/BES `AA <cmd> ...` frame. */
data class Frame(
    val cmd: Int,
    val payload: ByteArray,
    val raw: ByteArray
) {
    val hex: String get() = FrameCodec.hex(raw)
    val payloadHex: String get() = FrameCodec.hex(payload)

    override fun equals(other: Any?): Boolean =
        other is Frame && cmd == other.cmd && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * cmd + payload.contentHashCode()
}

object FrameCodec {
    const val HEADER = 0xAA

    /** Commands whose INBOUND/length-prefixed frames use a 2-byte little-endian length. */
    private val TWO_BYTE_LENGTH = setOf(0x06, 0xA2)

    /** Fixed-length 3-byte fin-ack frames: `AA 04 <cmd>` and `AA 05 <cmd>`. */
    private val FIXED_THREE_BYTE_CMDS = setOf(0x04, 0x05)

    /**
     * Transfer header frames `AA 41 <totalLen> <chunkCount>` and `AA 43 <totalLen> <chunkCount>`
     * are always 4 bytes even though byte[2] is the reassembled multi-part byte count, not
     * the length of this single packet.
     */
    private val MULTI_PART_HEADER_CMDS = setOf(0x41, 0x43)

    /** Commands whose reply uses a different command id. */
    private val REPLY_MAP = mapOf(0x11 to 0x12, 0x21 to 0x22, 0x42 to 0x43, 0x82 to 0x83)

    fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    fun parseHex(value: String): ByteArray {
        val clean = value.replace(" ", "").replace("-", "").trim()
        require(clean.length % 2 == 0) { "Odd-length hex: $value" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun frameFromHex(value: String): Frame = decode(parseHex(value))
        ?: error("Not a valid AA frame: $value")

    /** Builds a frame, choosing the correct length encoding automatically. */
    fun encode(cmd: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(payload.size <= 0xFFFF) { "Payload too large" }
        if (cmd in FIXED_THREE_BYTE_CMDS) {
            require(payload.size == 1) { "Fixed fin frame requires 1-byte target cmd" }
            return byteArrayOf(HEADER.toByte(), cmd.toByte(), payload[0])
        }
        return if (cmd in TWO_BYTE_LENGTH) {
            byteArrayOf(
                HEADER.toByte(), cmd.toByte(),
                (payload.size and 0xFF).toByte(), ((payload.size shr 8) and 0xFF).toByte()
            ) + payload
        } else {
            require(payload.size <= 0xFF) { "Payload too large for 1-byte length" }
            byteArrayOf(HEADER.toByte(), cmd.toByte(), payload.size.toByte()) + payload
        }
    }

    /** Parses one frame. Returns null when the buffer is not a complete AA frame. */
    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < 3) return null
        val start = bytes.indexOfFirst { (it.toInt() and 0xFF) == HEADER }
        if (start < 0) return null
        val buf = if (start == 0) bytes else bytes.copyOfRange(start, bytes.size)
        if (buf.size < 3) return null
        val cmd = buf[1].toInt() and 0xFF

        if (cmd in FIXED_THREE_BYTE_CMDS) {
            val raw = buf.copyOfRange(0, 3)
            return Frame(cmd, byteArrayOf(buf[2]), raw)
        }
        if (cmd in MULTI_PART_HEADER_CMDS) {
            if (buf.size < 4) return null
            val raw = buf.copyOfRange(0, 4)
            return Frame(cmd, byteArrayOf(buf[2], buf[3]), raw)
        }

        val (len, headerSize) = if (cmd in TWO_BYTE_LENGTH) {
            if (buf.size < 4) return null
            val n = (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8)
            n to 4
        } else {
            (buf[2].toInt() and 0xFF) to 3
        }
        if (len < 0 || buf.size < headerSize + len) return null
        val raw = buf.copyOfRange(0, headerSize + len)
        return Frame(cmd, buf.copyOfRange(headerSize, headerSize + len), raw)
    }

    fun expectedReplyCmd(cmd: Int): Int = REPLY_MAP[cmd] ?: cmd

    /** ACK frames (`AA 00 ...`) are recorded but never treated as success by themselves. */
    fun isAck(frame: Frame): Boolean = frame.cmd == 0x00

    /** Parses `AA 00 02 <ackedCmd> <status>` into `(ackedCmd, status)`. */
    fun parseAck(frame: Frame): Pair<Int, Int>? {
        if (frame.cmd != 0x00 || frame.payload.size < 2) return null
        return (frame.payload[0].toInt() and 0xFF) to (frame.payload[1].toInt() and 0xFF)
    }

    // --------------------------------------------------------- 0x12 Device Info

    fun parseDeviceInfo(frame: Frame): DeviceInfoState? {
        if (frame.cmd != 0x12 || frame.raw.size < 32) return null
        val b = frame.raw
        val name = String(b, 3, 16, StandardCharsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
        val pidHex = "%02X%02X".format(b[20].toInt() and 0xFF, b[19].toInt() and 0xFF)
        val mid = b[21].toInt() and 0xFF
        val batByte = b[22].toInt() and 0xFF
        val batPercent = (batByte and 0x7F).takeIf { it <= 100 }
        val mac = "%02X:%02X:%02X:%02X:%02X:%02X".format(
            b[28].toInt() and 0xFF,
            b[27].toInt() and 0xFF,
            b[26].toInt() and 0xFF,
            b[25].toInt() and 0xFF,
            b[24].toInt() and 0xFF,
            b[23].toInt() and 0xFF
        )
        val fw = "${b[29].toInt() and 0xFF}.${b[30].toInt() and 0xFF}.${b[31].toInt() and 0xFF}"
        val tws = if (b.size >= 38) (b[37].toInt() and 0xFF) != 0 else null
        return DeviceInfoState(
            deviceName = name,
            productIdHex = pidHex,
            modelId = mid,
            batteryPercent = batPercent,
            macAddress = mac,
            firmwareVersion = fw,
            twsConnected = tws
        )
    }

    // --------------------------------------------------------- 0x25 Battery

    /** Parses a 0x25 battery reply into (left, right, case) percentages & voltages. */
    fun parseBattery(frame: Frame): BatteryReading? {
        if (frame.cmd != 0x25) return null
        val p = frame.payload
        if (p.size < 7) return null
        fun level(i: Int): Int? = p[i].toInt().and(0xFF).takeIf { it <= 100 }
        fun mv(loIdx: Int): Int? = if (p.size >= loIdx + 2) {
            ((p[loIdx].toInt() and 0xFF) or ((p[loIdx + 1].toInt() and 0xFF) shl 8)).takeIf { it in 1500..6000 }
        } else null
        return BatteryReading(
            left = level(3),
            right = level(4),
            case = level(6),
            leftCharging = p[1].toInt() == 1,
            rightCharging = p[2].toInt() == 1,
            caseCharging = p[5].toInt() == 1,
            leftMv = mv(7),
            rightMv = mv(9),
            caseMv = mv(11)
        )
    }

    // --------------------------------------------------------- 0x26 Case Version / 0x94 SN / 0x9B Call

    fun parseCaseVersion(frame: Frame): String? {
        if (frame.cmd != 0x26 || frame.payload.size < 4) return null
        if ((frame.payload[0].toInt() and 0xFF) != 1) return null
        val p = frame.payload
        return "${p[1].toInt() and 0xFF}.${p[2].toInt() and 0xFF}.${p[3].toInt() and 0xFF}"
    }

    fun parseSerialNumber(frame: Frame): String? {
        if (frame.cmd != 0x94 || frame.payload.size < 2) return null
        if ((frame.payload[0].toInt() and 0xFF) != 2) return null
        return String(frame.payload, 1, frame.payload.size - 1, StandardCharsets.UTF_8)
            .trim { it <= ' ' || it == '\u0000' }
            .takeIf { it.isNotEmpty() }
    }

    fun parseMultiStatusCall(frame: Frame): Boolean? {
        if (frame.cmd != 0x9B || frame.raw.size < 6) return null
        return (frame.raw[5].toInt() and 0xFF) != 0
    }

    // --------------------------------------------------------- 0x22 Device Status (ALL & single)

    fun parseAllStatus(frame: Frame): AllStatusState? {
        if (frame.cmd != 0x22 || frame.payload.size < 5) return null
        val p = frame.payload
        if ((p[0].toInt() and 0xFF) != 0x30) return null
        val ancOn = (p[1].toInt() and 0xFF) != 0
        val aaMode = p[2].toInt() and 0xFF
        val noise = when {
            ancOn -> NoiseMode.ANC
            aaMode == 1 -> NoiseMode.AMBIENT_AWARE
            aaMode == 2 -> NoiseMode.TALKTHRU
            else -> NoiseMode.OFF
        }
        val autoOffRaw = p[3].toInt() and 0xFF
        val autoOffValid = p[3] != (-1).toByte()
        var autoOffEnabled = if (autoOffValid) ((autoOffRaw and 0x80) != 0) else false
        var autoOffMinutes = if (autoOffValid) (autoOffRaw and 0x7F) else 0
        val eqPresetByte = p[4].toInt() and 0xFF
        val multiAiByte = if (p.size >= 6) p[5].toInt() and 0xFF else 0
        val autoPlayPause = if (p.size >= 9) (p[8].toInt() and 0xFF) != 0 else null
        val tws = if (p.size >= 10) (p[9].toInt() and 0xFF) != 0 else null
        if (p.size >= 13) {
            val extLow = p[11].toInt() and 0xFF
            val extHigh = p[12].toInt() and 0xFF
            if (!(extLow == 0xFF && extHigh == 0xFF)) {
                autoOffEnabled = (p[10].toInt() and 0xFF) != 0
                autoOffMinutes = extLow or (extHigh shl 8)
            }
        }
        return AllStatusState(
            noiseMode = noise,
            autoOffEnabled = autoOffEnabled,
            autoOffMinutes = autoOffMinutes,
            eqPresetByte = eqPresetByte,
            multiAiByte = multiAiByte,
            autoPlayPause = autoPlayPause,
            twsConnected = tws
        )
    }

    fun parseAutoOffStatus(frame: Frame): Pair<Boolean, Int>? {
        if (frame.cmd != 0x22 || frame.payload.size < 2) return null
        val p = frame.payload
        val sub = p[0].toInt() and 0xFF
        if (sub == 0x30) return parseAllStatus(frame)?.let { it.autoOffEnabled to it.autoOffMinutes }
        if (sub != 0x33) return null
        return if (p.size >= 4) {
            val on = (p[1].toInt() and 0xFF) != 0
            val mins = (p[2].toInt() and 0xFF) or ((p[3].toInt() and 0xFF) shl 8)
            on to mins
        } else {
            val raw = p[1].toInt() and 0xFF
            ((raw and 0x80) != 0) to (raw and 0x7F)
        }
    }

    fun parseEqPresetStatus(frame: Frame): Int? {
        if (frame.cmd != 0x22 || frame.payload.size < 2) return null
        val sub = frame.payload[0].toInt() and 0xFF
        return when (sub) {
            0x30 -> parseAllStatus(frame)?.eqPresetByte
            0x34 -> frame.payload[1].toInt() and 0xFF
            else -> null
        }
    }

    fun parseMultiAiStatus(frame: Frame): Int? {
        if (frame.cmd != 0x22 || frame.payload.size < 2) return null
        return when (frame.payload[0].toInt() and 0xFF) {
            0x30 -> parseAllStatus(frame)?.multiAiByte
            0x35 -> frame.payload[1].toInt() and 0xFF
            else -> null
        }
    }

    fun parseAutoPlayPauseStatus(frame: Frame): Boolean? {
        if (frame.cmd != 0x22 || frame.payload.size < 2) return null
        return when (frame.payload[0].toInt() and 0xFF) {
            0x30 -> parseAllStatus(frame)?.autoPlayPause
            0x38 -> (frame.payload[1].toInt() and 0xFF) != 0
            else -> null
        }
    }

    fun parseInEarStatus(frame: Frame): InEarState? {
        if (frame.cmd != 0x22 || frame.payload.size < 3) return null
        if ((frame.payload[0].toInt() and 0xFF) != 0x41) return null
        return InEarState(
            leftInEar = (frame.payload[1].toInt() and 0xFF) != 0,
            rightInEar = (frame.payload[2].toInt() and 0xFF) != 0
        )
    }

    fun parseSealingStatus(frame: Frame): SealingState? {
        if (frame.cmd != 0x22 || frame.payload.size < 3) return null
        if ((frame.payload[0].toInt() and 0xFF) != 0x42) return null
        // Official HandleParse: 0 = GOOD fit, non-zero = adjust/poor
        return SealingState(
            leftRaw = frame.payload[1].toInt() and 0xFF,
            rightRaw = frame.payload[2].toInt() and 0xFF
        )
    }

    // --------------------------------------------------------- 0x91 / 0x22 ANC & Ambient

    /** Parses either `AA 91 <len> 12 ...` OR `AA 22 <len> 30/31/32 ...` into active NoiseMode. */
    fun parseNoiseMode(frame: Frame): NoiseMode? {
        if (frame.cmd == 0x22) {
            if (frame.payload.size < 2) return null
            val sub = frame.payload[0].toInt() and 0xFF
            return when (sub) {
                0x30 -> parseAllStatus(frame)?.noiseMode
                0x31 -> if ((frame.payload[1].toInt() and 0xFF) != 0) NoiseMode.ANC else NoiseMode.OFF
                0x32 -> when (frame.payload[1].toInt() and 0xFF) {
                    1 -> NoiseMode.AMBIENT_AWARE
                    2 -> NoiseMode.TALKTHRU
                    else -> NoiseMode.OFF
                }
                else -> null
            }
        }
        if (frame.cmd != 0x91 || frame.payload.isEmpty()) return null
        val sub = frame.payload[0].toInt() and 0xFF
        if (sub !in 0x10..0x13) return null
        if (sub == 0x13) return NoiseMode.OFF
        var i = 1
        while (i + 1 < frame.payload.size) {
            val mode = frame.payload[i].toInt() and 0xFF
            val value = frame.payload[i + 1].toInt() and 0xFF
            if (value != 0) {
                return when (mode) {
                    0x01 -> NoiseMode.ANC
                    0x02 -> NoiseMode.AMBIENT_AWARE
                    0x03 -> NoiseMode.TALKTHRU
                    else -> null
                }
            }
            i += 2
        }
        return NoiseMode.OFF
    }

    fun parseAncSubMode(frame: Frame): Int? {
        if (frame.cmd != 0x91 || frame.payload.size < 2) return null
        if ((frame.payload[0].toInt() and 0xFF) != 0x02) return null
        val v = frame.payload[1].toInt() and 0xFF
        return v.takeIf { it != 0xFF }
    }

    fun buildAncSubMode(subMode: Int): ByteArray =
        encode(0x91, byteArrayOf(0x00, subMode.toByte()))

    /** Parses `AA 91 <len> 22 (<type> <val>)...` for tag 0xA1 (Ambient Aware level 1..7). */
    fun parseAdvanceAnc(frame: Frame): AdvanceAncState? {
        if (frame.cmd != 0x91 || frame.payload.isEmpty()) return null
        if ((frame.payload[0].toInt() and 0xFF) != 0x22) return null
        var aaLevel: Int? = null
        var adaptiveAnc: Int? = null
        var i = 1
        while (i + 1 < frame.payload.size) {
            val tag = frame.payload[i].toInt() and 0xFF
            val value = frame.payload[i + 1].toInt() and 0xFF
            when (tag) {
                0xA1 -> aaLevel = value
                0x01 -> adaptiveAnc = value
            }
            i += 2
        }
        return AdvanceAncState(ambientAwareLevel = aaLevel, adaptiveAncState = adaptiveAnc)
    }

    fun buildAdvanceAncLevel(level: Int): ByteArray {
        val clamped = level.coerceIn(1, 7)
        return encode(0x91, byteArrayOf(0x20, 0xA1.toByte(), clamped.toByte()))
    }

    // --------------------------------------------------------- 0x77 Touch Gestures

    // Tune 230NC TWS GestureType ordinals: Left 0x06/0x07/0x08/0x0C, Right 0x09/0x0A/0x0B/0x0E
    private val LEFT_GESTURE_IDS = listOf(0x06, 0x07, 0x08, 0x0C)
    private val RIGHT_GESTURE_IDS = listOf(0x09, 0x0A, 0x0B, 0x0E)

    fun parseGestures(frame: Frame): GestureState? {
        if (frame.cmd != 0x77 || frame.payload.size < 3) return null
        if ((frame.payload[0].toInt() and 0xFF) != 0x02) return null
        val pairs = LinkedHashMap<Int, Int>()
        var i = 1
        while (i + 1 < frame.payload.size) {
            val gId = frame.payload[i].toInt() and 0xFF
            val aId = frame.payload[i + 1].toInt() and 0xFF
            pairs[gId] = aId
            i += 2
        }
        fun detectGroup(tap: Int?, dbl: Int?, trp: Int?): GestureFunctionGroup = when {
            tap in listOf(0x03, 0x04, 0x07, 0x09, 0x0B, 0x0C, 0x0D) ||
                dbl in listOf(0x03, 0x04, 0x07, 0x09, 0x0B, 0x0C, 0x0D) -> GestureFunctionGroup.AMBIENT
            tap == 0x08 || dbl == 0x05 || trp == 0x06 -> GestureFunctionGroup.PLAYBACK
            tap == 0x01 || dbl == 0x02 || tap == 0xAA -> GestureFunctionGroup.VOLUME
            else -> GestureFunctionGroup.OFF
        }
        val leftGroup = detectGroup(pairs[0x06], pairs[0x07], pairs[0x08])
        val rightGroup = detectGroup(pairs[0x09], pairs[0x0A], pairs[0x0B])
        val leftHoldVa = pairs[0x0C] in listOf(0xA1, 0xA4, 0xA6, 0xA8)
        val rightHoldVa = pairs[0x0E] in listOf(0xA1, 0xA4, 0xA6, 0xA8)
        return GestureState(
            pairs = pairs,
            leftGroup = leftGroup,
            rightGroup = rightGroup,
            leftHoldVoiceAssistant = leftHoldVa,
            rightHoldVoiceAssistant = rightHoldVa
        )
    }

    fun buildSetGestureGroup(
        isLeft: Boolean,
        group: GestureFunctionGroup,
        holdVoiceAssistant: Boolean = true
    ): ByteArray {
        val ids = if (isLeft) LEFT_GESTURE_IDS else RIGHT_GESTURE_IDS
        val actions = when (group) {
            GestureFunctionGroup.AMBIENT -> listOf(0x09, 0x04, 0x00)
            GestureFunctionGroup.PLAYBACK -> listOf(0x08, 0x05, 0x06)
            GestureFunctionGroup.VOLUME -> listOf(0x01, 0x02, 0x00)
            GestureFunctionGroup.OFF -> listOf(0x00, 0x00, 0x00)
        }
        val holdAction = if (holdVoiceAssistant) 0xA1 else 0x00
        val payload = byteArrayOf(
            0x00,
            ids[0].toByte(), actions[0].toByte(),
            ids[1].toByte(), actions[1].toByte(),
            ids[2].toByte(), actions[2].toByte(),
            ids[3].toByte(), holdAction.toByte()
        )
        return encode(0x77, payload)
    }

    fun buildResetGestures(scope: Int = 0xFF): ByteArray =
        encode(0x77, byteArrayOf(0x00, scope.toByte(), 0x00))

    // --------------------------------------------------------- 0x81 / 0x83 Smart Audio & Video

    fun parseSmartSwitch(frame: Frame): SmartAudioMode? {
        if (frame.cmd != 0x83 || frame.payload.size < 6) return null
        val p = frame.payload
        val aac = (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8)
        val latency = (p[4].toInt() and 0xFF) or ((p[5].toInt() and 0xFF) shl 8)
        return when {
            latency <= 160 -> SmartAudioMode.VIDEO
            aac >= 230 -> SmartAudioMode.AUDIO
            else -> SmartAudioMode.NORMAL
        }
    }

    // --------------------------------------------------------- 0x98 VoiceAware

    fun parseVoiceAware(frame: Frame): VoiceAwareState? {
        if (frame.cmd != 0x98 || frame.payload.size < 2) return null
        val sub = frame.payload[0].toInt() and 0xFF
        if (sub != 0x02 && sub != 0x00) return null
        val level = frame.payload[1].toInt() and 0xFF
        val isOn = if (frame.payload.size >= 3) (frame.payload[2].toInt() and 0xFF) != 0 else level != 0
        return VoiceAwareState(level = level.coerceIn(0, 3), isOn = isOn)
    }

    fun buildVoiceAware(level: Int): ByteArray {
        val clamped = level.coerceIn(0, 3)
        return encode(0x98, byteArrayOf(0x00, clamped.toByte(), if (clamped > 0) 0x01 else 0x00))
    }

    fun buildVoiceAwareLegacy(level: Int): ByteArray {
        val clamped = level.coerceIn(0, 3)
        return encode(0x98, byteArrayOf(0x00, clamped.toByte()))
    }

    // --------------------------------------------------------- 0x23 / 0x24 / 0x36 Find My Buds

    fun parseFindMyBuds(frame: Frame): FindMyBudsState? {
        return when (frame.cmd) {
            0x23 -> {
                if (frame.payload.size < 3) return null
                FindMyBudsState(
                    leftBeeping = (frame.payload[1].toInt() and 0xFF) == 1,
                    rightBeeping = (frame.payload[2].toInt() and 0xFF) == 1
                )
            }
            0x24 -> {
                if (frame.payload.isEmpty()) return null
                when (frame.payload[0].toInt() and 0xFF) {
                    0x01 -> FindMyBudsState(leftBeeping = true, rightBeeping = true)
                    0x02 -> FindMyBudsState(leftBeeping = false, rightBeeping = true)
                    0x03 -> FindMyBudsState(leftBeeping = true, rightBeeping = false)
                    else -> FindMyBudsState(leftBeeping = false, rightBeeping = false)
                }
            }
            else -> null
        }
    }

    fun buildFindMyBuds(left: Boolean, right: Boolean): ByteArray =
        encode(0x23, byteArrayOf(0x00, if (left) 0x01 else 0x00, if (right) 0x01 else 0x00))

    fun buildFindMyBudsLegacy(left: Boolean, right: Boolean): ByteArray {
        val code = when {
            left && right -> 0x01
            right -> 0x02
            left -> 0x03
            else -> 0x00
        }
        return encode(0x36, byteArrayOf(code.toByte()))
    }

    // --------------------------------------------------------- 0x96 Standby / 0x33 AutoOff / 0x9C Tips / 0x93 VoicePrompt / 0x34 MultiAI

    fun parseStandbyModeKeepAnc(frame: Frame): Boolean? {
        if (frame.cmd != 0x96 || frame.payload.size < 2) return null
        val p = frame.payload
        if ((p[0].toInt() and 0xFF) != 0x02) return null
        var i = 1
        var keepAnc = false
        while (i + 1 < p.size) {
            val modeId = p[i].toInt() and 0xFF
            val status = p[i + 1].toInt() and 0xFF
            if (modeId == 0x02 && status == 1) keepAnc = true
            i += 2
        }
        return keepAnc
    }

    fun buildStandbyMode(keepAnc: Boolean): ByteArray =
        encode(0x96, byteArrayOf(0x00, 0x01, if (keepAnc) 0x00 else 0x01, 0x02, if (keepAnc) 0x01 else 0x00))

    fun buildAutoPowerOff(enabled: Boolean, minutes: Int): ByteArray {
        val m = minutes.coerceIn(0, 127)
        val packed = ((if (enabled) 1 else 0) shl 7) or m
        return encode(0x33, byteArrayOf(packed.toByte()))
    }

    fun parseTipsType(frame: Frame): Int? {
        if (frame.cmd != 0x9C || frame.payload.size < 2) return null
        return frame.payload[1].toInt() and 0xFF
    }

    fun buildTipsType(type: Int): ByteArray =
        encode(0x9C, byteArrayOf(0x00, type.toByte()))

    fun parseVoicePrompt(frame: Frame): VoicePromptState? {
        if (frame.cmd != 0x93 || frame.payload.size < 2) return null
        return when (frame.payload[0].toInt() and 0xFF) {
            0x02 -> {
                if (frame.payload.size < 5) null else VoicePromptState(
                    languageId = frame.payload[1].toInt() and 0xFF,
                    version = "${frame.payload[2].toInt() and 0xFF}.${frame.payload[3].toInt() and 0xFF}.${frame.payload[4].toInt() and 0xFF}",
                    toneOnly = null
                )
            }
            0x05 -> VoicePromptState(
                languageId = null,
                version = null,
                toneOnly = (frame.payload[1].toInt() and 0xFF) == 0
            )
            else -> null
        }
    }

    fun buildVoicePromptStatus(toneOnly: Boolean): ByteArray =
        encode(0x93, byteArrayOf(0x03, if (toneOnly) 0x00 else 0x01))

    fun buildMultiAi(modeByte: Int): ByteArray =
        encode(0x34, byteArrayOf(modeByte.toByte()))
}

data class DeviceInfoState(
    val deviceName: String,
    val productIdHex: String,
    val modelId: Int,
    val batteryPercent: Int?,
    val macAddress: String,
    val firmwareVersion: String,
    val twsConnected: Boolean?
)

data class AllStatusState(
    val noiseMode: NoiseMode,
    val autoOffEnabled: Boolean,
    val autoOffMinutes: Int,
    val eqPresetByte: Int,
    val multiAiByte: Int,
    val autoPlayPause: Boolean?,
    val twsConnected: Boolean?
)

data class BatteryReading(
    val left: Int?,
    val right: Int?,
    val case: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
    val leftMv: Int? = null,
    val rightMv: Int? = null,
    val caseMv: Int? = null
)

data class AdvanceAncState(
    val ambientAwareLevel: Int?,
    val adaptiveAncState: Int?
)

data class InEarState(
    val leftInEar: Boolean,
    val rightInEar: Boolean
)

data class SealingState(
    val leftRaw: Int,
    val rightRaw: Int
) {
    val leftGood: Boolean get() = leftRaw == 0
    val rightGood: Boolean get() = rightRaw == 0
}

data class VoiceAwareState(
    val level: Int,
    val isOn: Boolean
)

data class FindMyBudsState(
    val leftBeeping: Boolean,
    val rightBeeping: Boolean
)

data class VoicePromptState(
    val languageId: Int?,
    val version: String?,
    val toneOnly: Boolean?
)

enum class NoiseMode(val label: String) {
    OFF("关闭"),
    ANC("主动降噪"),
    AMBIENT_AWARE("环境感知"),
    TALKTHRU("自由通话")
}

enum class GestureFunctionGroup(val label: String) {
    AMBIENT("环境声音控制"),
    PLAYBACK("播放控制"),
    VOLUME("音量控制"),
    OFF("关闭手势")
}

data class GestureState(
    val pairs: Map<Int, Int>,
    val leftGroup: GestureFunctionGroup,
    val rightGroup: GestureFunctionGroup,
    val leftHoldVoiceAssistant: Boolean,
    val rightHoldVoiceAssistant: Boolean
)

enum class SmartAudioMode(val label: String, val commandName: String) {
    NORMAL("正常模式", "smart_av_write_normal"),
    AUDIO("音频模式", "smart_av_write_audio"),
    VIDEO("视频模式", "smart_av_write_video")
}
