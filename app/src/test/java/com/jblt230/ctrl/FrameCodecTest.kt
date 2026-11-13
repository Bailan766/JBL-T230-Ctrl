package com.jblt230.ctrl

import com.jblt230.ctrl.ble.CommandGuard
import com.jblt230.ctrl.ble.EqCodec
import com.jblt230.ctrl.ble.FrameCodec
import com.jblt230.ctrl.ble.GestureFunctionGroup
import com.jblt230.ctrl.ble.NoiseMode
import com.jblt230.ctrl.ble.SmartAudioMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FrameCodecTest {

    @Test
    fun `ANC frame round trips`() {
        val bytes = FrameCodec.parseHex("AA910710010102000300")
        val frame = FrameCodec.decode(bytes)
        checkNotNull(frame)
        assertEquals(0x91, frame.cmd)
        assertEquals(7, frame.payload.size)
        assertEquals("AA910710010102000300", frame.hex)
        assertEquals(0x91, FrameCodec.expectedReplyCmd(frame.cmd))
    }

    @Test
    fun `encoded frame length field matches payload`() {
        val encoded = FrameCodec.encode(0x91, byteArrayOf(0x10, 0x01, 0x01))
        assertEquals("AA9103100101", FrameCodec.hex(encoded))
        val decoded = FrameCodec.decode(encoded)
        checkNotNull(decoded)
        assertEquals(3, decoded.payload.size)
    }

    @Test
    fun `command 06 uses two byte little endian length`() {
        val encoded = FrameCodec.encode(0x06, byteArrayOf(0x01, 0x1A, 0x1B, 0x1C))
        assertEquals("AA060400011A1B1C", FrameCodec.hex(encoded))
        val decoded = FrameCodec.decode(encoded)
        checkNotNull(decoded)
        assertArrayEquals(byteArrayOf(0x01, 0x1A, 0x1B, 0x1C), decoded.payload)
    }

    @Test
    fun `reply command mapping covers Tune230 handshake and EQ`() {
        assertEquals(0x12, FrameCodec.expectedReplyCmd(0x11))
        assertEquals(0x22, FrameCodec.expectedReplyCmd(0x21))
        assertEquals(0x25, FrameCodec.expectedReplyCmd(0x25))
        assertEquals(0x43, FrameCodec.expectedReplyCmd(0x42))
        assertEquals(0x83, FrameCodec.expectedReplyCmd(0x82))
    }

    @Test
    fun `fixed three byte fin and four byte multipart header decode`() {
        val finRead = FrameCodec.decode(FrameCodec.parseHex("AA0443"))
        checkNotNull(finRead)
        assertEquals(0x04, finRead.cmd)
        assertArrayEquals(byteArrayOf(0x43), finRead.payload)

        val finWrite = FrameCodec.decode(FrameCodec.parseHex("AA0541"))
        checkNotNull(finWrite)
        assertEquals(0x05, finWrite.cmd)
        assertArrayEquals(byteArrayOf(0x41), finWrite.payload)

        val startHeader = FrameCodec.decode(FrameCodec.parseHex("AA43AF03"))
        checkNotNull(startHeader)
        assertEquals(0x43, startHeader.cmd)
        assertEquals(175 to 3, EqCodec.parseCustomEqHeader(startHeader))
    }

    @Test
    fun `noise mode parsing covers 0x91 and 0x22 fallback subcmds`() {
        val anc = FrameCodec.frameFromHex("AA910712010102000300")
        assertEquals(NoiseMode.ANC, FrameCodec.parseNoiseMode(anc))
        val ambient = FrameCodec.frameFromHex("AA910712010002010300")
        assertEquals(NoiseMode.AMBIENT_AWARE, FrameCodec.parseNoiseMode(ambient))
        val talkthru = FrameCodec.frameFromHex("AA910712010002000301")
        assertEquals(NoiseMode.TALKTHRU, FrameCodec.parseNoiseMode(talkthru))
        val off = FrameCodec.frameFromHex("AA910712010002000300")
        assertEquals(NoiseMode.OFF, FrameCodec.parseNoiseMode(off))
        val sub13Off = FrameCodec.frameFromHex("AA910713010002000300")
        assertEquals(NoiseMode.OFF, FrameCodec.parseNoiseMode(sub13Off))

        assertEquals(NoiseMode.ANC, FrameCodec.parseNoiseMode(FrameCodec.frameFromHex("AA22023101")))
        assertEquals(NoiseMode.OFF, FrameCodec.parseNoiseMode(FrameCodec.frameFromHex("AA22023100")))
        assertEquals(NoiseMode.AMBIENT_AWARE, FrameCodec.parseNoiseMode(FrameCodec.frameFromHex("AA22023201")))
        assertEquals(NoiseMode.TALKTHRU, FrameCodec.parseNoiseMode(FrameCodec.frameFromHex("AA22023202")))
    }

    @Test
    fun `ambient aware level and anc submode build and parse`() {
        val levelFrame = FrameCodec.buildAdvanceAncLevel(6)
        assertEquals("AA910320A106", FrameCodec.hex(levelFrame))
        val reply = FrameCodec.frameFromHex("AA910522A1060101")
        val parsed = FrameCodec.parseAdvanceAnc(reply)
        checkNotNull(parsed)
        assertEquals(6, parsed.ambientAwareLevel)
        assertEquals(1, parsed.adaptiveAncState)

        assertEquals("AA91020002", FrameCodec.hex(FrameCodec.buildAncSubMode(2)))
        assertEquals(2, FrameCodec.parseAncSubMode(FrameCodec.frameFromHex("AA91020202")))
        assertNull(FrameCodec.parseAncSubMode(FrameCodec.frameFromHex("AA910202FF")))
    }

    @Test
    fun `battery parsing handles unknown values and millivolts`() {
        val frame = FrameCodec.frameFromHex("AA2507010000504B00FF")
        val reading = FrameCodec.parseBattery(frame)
        checkNotNull(reading)
        assertEquals(80, reading.left)
        assertEquals(75, reading.right)
        assertNull(reading.case)
        assertFalse(reading.leftCharging)
        assertFalse(reading.rightCharging)

        // With mV suffix & leftCharging=1: left 4120mV (18 10), right 4080mV (F0 0F), case 3900mV (3C 0F)
        val withMv = FrameCodec.frameFromHex("AA250D010100645A00501810F00F3C0F")
        val r2 = FrameCodec.parseBattery(withMv)
        checkNotNull(r2)
        assertTrue(r2.leftCharging)
        assertEquals(100, r2.left)
        assertEquals(90, r2.right)
        assertEquals(80, r2.case)
        assertEquals(4120, r2.leftMv)
        assertEquals(4080, r2.rightMv)
        assertEquals(3900, r2.caseMv)
    }

    @Test
    fun `device info, case version, serial number and all-status parsing`() {
        val dev = ByteArray(38)
        dev[0] = 0xAA.toByte()
        dev[1] = 0x12
        dev[2] = 35
        val nameBytes = "JBL TUNE230NC".toByteArray(Charsets.UTF_8)
        System.arraycopy(nameBytes, 0, dev, 3, nameBytes.size)
        dev[19] = 0x4B // PID LSB
        dev[20] = 0x20 // PID MSB -> 204B
        dev[21] = 0x05 // modelId
        dev[22] = 0x55 // battery 85%
        // MAC reversed at 23..28 -> 11:22:33:44:55:66
        dev[23] = 0x66; dev[24] = 0x55; dev[25] = 0x44; dev[26] = 0x33; dev[27] = 0x22; dev[28] = 0x11
        dev[29] = 5; dev[30] = 8; dev[31] = 0 // fw 5.8.0
        dev[37] = 1 // twsConnected
        val info = FrameCodec.parseDeviceInfo(FrameCodec.decode(dev)!!)
        checkNotNull(info)
        assertEquals("JBL TUNE230NC", info.deviceName)
        assertEquals("204B", info.productIdHex)
        assertEquals(5, info.modelId)
        assertEquals(85, info.batteryPercent)
        assertEquals("11:22:33:44:55:66", info.macAddress)
        assertEquals("5.8.0", info.firmwareVersion)
        assertEquals(true, info.twsConnected)

        assertEquals("1.4.2", FrameCodec.parseCaseVersion(FrameCodec.frameFromHex("AA260401010402")))
        assertEquals("SN123456", FrameCodec.parseSerialNumber(FrameCodec.frameFromHex("AA940902534E313233343536")))

        // 0x22 sub 0x30: anc=1, aa=0, autoOff(enabled+30=0x9E), eqPreset=0x03(BASS), multiAi=0x01, ..., autoPlayPause=1, tws=1
        val all = FrameCodec.parseAllStatus(FrameCodec.frameFromHex("AA220A3001009E030100000101"))
        checkNotNull(all)
        assertEquals(NoiseMode.ANC, all.noiseMode)
        assertTrue(all.autoOffEnabled)
        assertEquals(30, all.autoOffMinutes)
        assertEquals(0x03, all.eqPresetByte)
        assertEquals(0x01, all.multiAiByte)
        assertEquals(true, all.autoPlayPause)
        assertEquals(true, all.twsConnected)
    }

    @Test
    fun `ACK is never treated as success`() {
        val ack = FrameCodec.frameFromHex("AA00020100")
        assertTrue(FrameCodec.isAck(ack))
        assertEquals(0x01 to 0x00, FrameCodec.parseAck(ack))
        assertFalse(FrameCodec.isAck(FrameCodec.frameFromHex("AA910111")))
    }

    @Test
    fun `guard blocks destructive commands and validates EQ chunks`() {
        val guard = CommandGuard(setOf(0x95, 0x97, 0xF0, 0xAA, 0x13, 0x15))
        assertTrue(guard.isBlocked(FrameCodec.parseHex("AA950100")))
        assertFalse(guard.isBlocked(FrameCodec.parseHex("AA910111")))
        runCatching { guard.assertAllowed(FrameCodec.parseHex("AAF00100")) }
            .exceptionOrNull()
            .let { assertTrue(it is CommandGuard.BlockedCommandException) }

        val validChunk = ByteArray(80).apply { this[0] = 0x01 }
        guard.assertAllowedEqChunk(validChunk, 1)
        runCatching { guard.assertAllowedEqChunk(validChunk, 2) }
            .exceptionOrNull()
            .let { assertTrue(it is CommandGuard.BlockedCommandException) }
    }

    @Test
    fun `Tune230 0x40 EQ preset frame and mapping`() {
        assertEquals("AA400101", FrameCodec.hex(EqCodec.buildSetEqPresetFrame(EqCodec.PRESET_IDX_JAZZ)))
        assertEquals("AA400111", FrameCodec.hex(EqCodec.buildSetEqPresetFrame(EqCodec.PRESET_IDX_ROCK)))
        assertEquals(EqCodec.PRESET_IDX_STUDIO, EqCodec.presetIdToByte("studio"))
        assertEquals("studio", EqCodec.presetByteToId(0x14))
    }

    @Test
    fun `Tune230 0x41 0x42 0x43 custom EQ transfer and dual-layout parser`() {
        assertEquals("AA420101", FrameCodec.hex(EqCodec.buildGetCustomEqFrame(EqCodec.CAT_GRAPHIC_EQ)))
        assertEquals("AA01024300", FrameCodec.hex(EqCodec.buildCustomEqAppAckFrame()))

        val gains = listOf(4f, 2f, 1f, 2.5f, -1.5f, -1.5f, 0f, 1f, 2f, 4f)
        val transfer = EqCodec.buildSetCustomEqTransfer(
            presetIndex = EqCodec.PRESET_IDX_USER,
            gains = gains
        )
        assertEquals("AA41B303", FrameCodec.hex(transfer.headerFrame))
        assertEquals(179, transfer.rawPayload.size)
        assertEquals(3, transfer.chunkFrames.size)
        assertEquals(0, transfer.chunkFrames[0][0].toInt() and 0xFF)
        assertEquals(80, transfer.chunkFrames[0].size)
        assertEquals(1, transfer.chunkFrames[1][0].toInt() and 0xFF)
        assertEquals(80, transfer.chunkFrames[1].size)
        assertEquals(2, transfer.chunkFrames[2][0].toInt() and 0xFF)
        assertEquals(22, transfer.chunkFrames[2].size)
        assertEquals("AA0541", FrameCodec.hex(transfer.footerFrame))

        // 179-byte write layout parses back cleanly
        val parsedWrite = EqCodec.parseCustomEqPayload(transfer.rawPayload)
        checkNotNull(parsedWrite)
        assertEquals(EqCodec.PRESET_IDX_USER, parsedWrite.presetIndex)
        assertEquals(EqCodec.CAT_GRAPHIC_EQ, parsedWrite.eqCategory)
        assertTrue(EqCodec.gainsMatch(gains, parsedWrite.gains))

        // 175-byte read layout (HandleParse omits 4-byte calibration field) also parses cleanly
        val readPayload = ByteBuffer.allocate(175).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(EqCodec.PRESET_IDX_USER.toByte())
            put(EqCodec.CAT_GRAPHIC_EQ.toByte())
            put(48.toByte())
            putFloat(0f) // left
            putFloat(0f) // right
            putInt(10)
            for (i in 0 until 10) {
                putInt(2)
                putFloat(gains[i])
                putFloat(EqCodec.DEFAULT_FREQUENCIES[i].toFloat())
                putFloat(2f)
            }
        }.array()
        val parsedRead = EqCodec.parseCustomEqPayload(readPayload)
        checkNotNull(parsedRead)
        assertEquals(EqCodec.PRESET_IDX_USER, parsedRead.presetIndex)
        assertEquals(48, parsedRead.sampleRate)
        assertTrue(EqCodec.gainsMatch(gains, parsedRead.gains))
    }

    @Test
    fun `touch gestures parse recorded frame and build group write`() {
        val recorded = FrameCodec.frameFromHex("AA7711020609070408000CA109080A050B060EA1")
        val state = FrameCodec.parseGestures(recorded)
        checkNotNull(state)
        assertEquals(GestureFunctionGroup.AMBIENT, state.leftGroup)
        assertEquals(GestureFunctionGroup.PLAYBACK, state.rightGroup)
        assertTrue(state.leftHoldVoiceAssistant)
        assertTrue(state.rightHoldVoiceAssistant)

        val writeRightVol = FrameCodec.buildSetGestureGroup(
            isLeft = false,
            group = GestureFunctionGroup.VOLUME,
            holdVoiceAssistant = false
        )
        assertEquals("AA77090009010A020B000E00", FrameCodec.hex(writeRightVol))
        assertEquals("AA770300FF00", FrameCodec.hex(FrameCodec.buildResetGestures()))
    }

    @Test
    fun `smart audio, voiceaware, standby, autooff, tips, voiceprompt and findbuds`() {
        assertEquals(SmartAudioMode.VIDEO, FrameCodec.parseSmartSwitch(FrameCodec.frameFromHex("AA8306000000006400")))
        assertEquals(SmartAudioMode.AUDIO, FrameCodec.parseSmartSwitch(FrameCodec.frameFromHex("AA8306E8000000F401")))
        assertEquals(SmartAudioMode.NORMAL, FrameCodec.parseSmartSwitch(FrameCodec.frameFromHex("AA830680000000F401")))

        assertEquals("AA9803000201", FrameCodec.hex(FrameCodec.buildVoiceAware(2)))
        val va = FrameCodec.parseVoiceAware(FrameCodec.frameFromHex("AA9803020301"))
        checkNotNull(va)
        assertEquals(3, va.level)
        assertTrue(va.isOn)

        assertEquals("AA96050001000201", FrameCodec.hex(FrameCodec.buildStandbyMode(true)))
        assertEquals(true, FrameCodec.parseStandbyModeKeepAnc(FrameCodec.frameFromHex("AA96050201000201")))

        assertEquals("AA33019E", FrameCodec.hex(FrameCodec.buildAutoPowerOff(true, 30)))
        assertEquals("AA9C020002", FrameCodec.hex(FrameCodec.buildTipsType(2)))
        assertEquals(2, FrameCodec.parseTipsType(FrameCodec.frameFromHex("AA9C020202")))

        assertEquals("AA93020300", FrameCodec.hex(FrameCodec.buildVoicePromptStatus(true)))
        val vpStatus = FrameCodec.parseVoicePrompt(FrameCodec.frameFromHex("AA93020500"))
        assertEquals(true, vpStatus?.toneOnly)
        val vpLang = FrameCodec.parseVoicePrompt(FrameCodec.frameFromHex("AA93050201020304"))
        assertEquals("2.3.4", vpLang?.version)

        assertEquals("AA2303000100", FrameCodec.hex(FrameCodec.buildFindMyBuds(left = true, right = false)))
        assertEquals("AA360102", FrameCodec.hex(FrameCodec.buildFindMyBudsLegacy(left = false, right = true)))
        val find = FrameCodec.parseFindMyBuds(FrameCodec.frameFromHex("AA2303020101"))
        assertEquals(true, find?.leftBeeping)
        assertEquals(true, find?.rightBeeping)
    }

    @Test
    fun `EQ A2 read frame uses one byte length`() {
        assertEquals("AAA20201FF", FrameCodec.hex(EqCodec.readCurrentFrame()))
        assertEquals("AAA20201CA", FrameCodec.hex(EqCodec.readFrame(202)))
    }

    @Test
    fun `EQ A2 write frame uses two byte little endian length`() {
        val frame = EqCodec.writeFrame(1, listOf(4f, 2f, 1f, 2.5f, -1.5f, -1.5f, 0f, 1f, 2f, 4f))
        assertEquals(0xA2, frame[1].toInt() and 0xFF)
        assertEquals(0x74, frame[2].toInt() and 0xFF) // 116 payload bytes
        assertEquals(0x00, frame[3].toInt() and 0xFF)
        assertEquals(120, frame.size)
        assertEquals(0x00, frame[4].toInt() and 0xFF)  // subSetCmd
        assertEquals(0x01, frame[5].toInt() and 0xFF)  // category JAZZ
        assertEquals(48, frame[10].toInt() and 0xFF)   // sample rate
        // First band gain 4.0f as little-endian IEEE754 = 00 00 80 40
        assertEquals("00008040", FrameCodec.hex(frame.copyOfRange(21, 25)))
        // Inbound A2 frames are decoded with the 2-byte length rule.
        val decoded = FrameCodec.decode(frame)
        checkNotNull(decoded)
        assertEquals(116, decoded.payload.size)
    }
}
