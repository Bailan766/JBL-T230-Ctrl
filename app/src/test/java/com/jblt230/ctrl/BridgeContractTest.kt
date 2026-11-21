package com.jblt230.ctrl

import com.jblt230.ctrl.ble.FrameCodec
import com.jblt230.ctrl.ble.NoiseMode
import com.jblt230.ctrl.bridge.BridgeContract
import com.jblt230.ctrl.bridge.RootMelodyWake
import com.jblt230.ctrl.logging.DiagStatus
import com.jblt230.ctrl.logging.DiagStep
import com.jblt230.ctrl.logging.DiagnosticReportFormatter
import com.jblt230.ctrl.logging.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeContractTest {

    @Test
    fun targetJblNameMatchingHandlesClassicLeAndUnrelated() {
        assertTrue(BridgeContract.isTargetJblName("JBL TUNE230NC TWS"))
        assertTrue(BridgeContract.isTargetJblName("JBL TUNE230NC TWS-LE"))
        assertTrue(BridgeContract.isTargetJblName("jbl tune 230nc"))
        assertFalse(BridgeContract.isTargetJblName("OPPO Enco X2"))
        assertFalse(BridgeContract.isTargetJblName(""))
        assertFalse(BridgeContract.isTargetJblName(null))
    }

    @Test
    fun batteryTextFormattingHandlesValidNullAndCharging() {
        assertEquals("95%", BridgeContract.formatBatteryText(95, charging = false))
        assertEquals("⚡80%", BridgeContract.formatBatteryText(80, charging = true))
        assertEquals("--%", BridgeContract.formatBatteryText(null, charging = false))
        assertEquals("--%", BridgeContract.formatBatteryText(-1, charging = true))
    }

    @Test
    fun fluidCloudChipAlwaysStaysCompactAndAccurate() {
        val equalChip = BridgeContract.formatFluidCloudChip(90, 90, leftCharging = false, rightCharging = false)
        assertEquals("90%", equalChip)
        assertTrue(equalChip.length <= 7)

        val equalCharging = BridgeContract.formatFluidCloudChip(85, 85, leftCharging = true, rightCharging = false)
        assertEquals("⚡85%", equalCharging)
        assertTrue(equalCharging.length <= 7)

        val asym = BridgeContract.formatFluidCloudChip(90, 70)
        assertEquals("L90 R70", asym)
        assertTrue(asym.length <= 7)

        // When one side is 100 and they differ, L100 R80 would be 8 chars; clamp gracefully to <= 7 chars.
        val hundredDiff = BridgeContract.formatFluidCloudChip(100, 80)
        assertEquals("80%", hundredDiff)
        assertTrue(hundredDiff.length <= 7)

        assertEquals("60%", BridgeContract.formatFluidCloudChip(60, null))
        assertEquals("已连接", BridgeContract.formatFluidCloudChip(null, null))
    }

    @Test
    fun averageBudBatteryHandlesSymmetricAsymmetricAndFallback() {
        assertEquals(80, BridgeContract.averageBudBattery(90, 70, 50))
        assertEquals(65, BridgeContract.averageBudBattery(null, 65, 50))
        assertEquals(50, BridgeContract.averageBudBattery(null, null, 50))
        assertEquals(0, BridgeContract.averageBudBattery(null, null, null))
    }

    @Test
    fun noiseModeLabelsAreHumanReadable() {
        assertEquals("主动降噪", BridgeContract.noiseModeLabel(NoiseMode.ANC))
        assertEquals("环境感知", BridgeContract.noiseModeLabel(NoiseMode.AMBIENT_AWARE))
        assertEquals("自由通话", BridgeContract.noiseModeLabel(NoiseMode.TALKTHRU))
        assertEquals("关闭", BridgeContract.noiseModeLabel(NoiseMode.OFF))
    }

    @Test
    fun quickNoiseModeCyclesCleanly() {
        assertEquals(NoiseMode.ANC, BridgeContract.nextQuickNoiseMode(NoiseMode.OFF))
        assertEquals(NoiseMode.AMBIENT_AWARE, BridgeContract.nextQuickNoiseMode(NoiseMode.ANC))
        assertEquals(NoiseMode.OFF, BridgeContract.nextQuickNoiseMode(NoiseMode.AMBIENT_AWARE))
        assertEquals(NoiseMode.OFF, BridgeContract.nextQuickNoiseMode(NoiseMode.TALKTHRU))
    }

    @Test
    fun diagnosticFormatterIncludesCountsStepsAndFrames() {
        val env = DiagnosticReportFormatter.EnvironmentInfo(
            generatedAt = "2026-11-22 04:15:00",
            appVersion = "2.0.0 (10)",
            deviceModel = "realme RMX3888",
            androidRelease = "16",
            sdkInt = 36,
            permissionsGranted = true,
            overlayGranted = false,
            fluidCloudPromotedEnabled = true,
            fluidCloudCharacteristicsOk = true,
            takeoverEnabled = true,
            autoPopupOnConnect = false,
            keepLinkAlive = false,
            pairedName = "JBL TUNE230NC TWS",
            a2dpConnected = true,
            hfpConnected = true,
            systemBattery = 90,
            linkSummary = "Ready (MTU 512)",
            discoveredServices = "65786365[65786365(p=16)]"
        )
        val steps = listOf(
            DiagStep("bat", "三端电量 (0x25)", DiagStatus.PASS, 45, "L=90%, R=90%, 盒=80%", "AA 25 06 00 5A 00 5A 00 50"),
            DiagStep("eq", "多包 EQ", DiagStatus.FAIL, 1200, "分包超时")
        )
        val logs = listOf(
            LogEntry("04:15:01.100", "→", "AA 25 00", "battery_read"),
            LogEntry("04:15:01.145", "←", "AA 25 06 00 5A 00 5A 00 50", "")
        )
        val text = DiagnosticReportFormatter.format(env, steps, logs)
        assertTrue(text.contains("流体云/LiveUpdate: promoted=true, promotableChars=true"))
        assertTrue(text.contains("通过=1, 跳过=0, 警告=0, 失败=1"))
        assertTrue(text.contains("[PASS] (45ms) 三端电量 (0x25)"))
        assertTrue(text.contains("[FAIL] (1200ms) 多包 EQ: 分包超时"))
        assertTrue(text.contains("04:15:01.100 → AA 25 00 // battery_read"))
    }

    @Test
    fun parsesTune230Fw370VoicePromptFrame() {
        val frame = FrameCodec.decode(FrameCodec.parseHex("AA93059300000004"))
        val vp = frame?.let { FrameCodec.parseVoicePrompt(it) }
        assertEquals(0, vp?.languageId)
        assertEquals("0.0.4", vp?.version)
    }

    @Test
    fun colorOsSpoofNoiseEqAndProductIdHelpersRoundTrip() {
        assertEquals(0, BridgeContract.noiseModeToColorOsIndex(NoiseMode.OFF))
        assertEquals(1, BridgeContract.noiseModeToColorOsIndex(NoiseMode.AMBIENT_AWARE))
        assertEquals(1, BridgeContract.noiseModeToColorOsIndex(NoiseMode.TALKTHRU))
        assertEquals(2, BridgeContract.noiseModeToColorOsIndex(NoiseMode.ANC))

        assertEquals(NoiseMode.OFF, BridgeContract.colorOsIndexToNoiseMode(0))
        assertEquals(NoiseMode.AMBIENT_AWARE, BridgeContract.colorOsIndexToNoiseMode(1))
        assertEquals(NoiseMode.ANC, BridgeContract.colorOsIndexToNoiseMode(2))

        assertEquals(0x060414, BridgeContract.parseProductIdInt("060414"))
        assertEquals("060814", BridgeContract.formatProductIdHex(0x060814))
        assertEquals("OPPO Enco X2", BridgeContract.presetByName("unknown").melodyName)

        for (p in listOf("off", "jazz", "vocal", "bass", "club", "studio", "extreme_bass", "custom")) {
            assertEquals(p, BridgeContract.colorOsTypeToEqPreset(BridgeContract.eqPresetToColorOsType(p)))
        }
    }

    @Test
    fun spoofPresetsMatchVerifiedMelodyWhitelistIdsAndColors() {
        val byName = BridgeContract.SPOOF_PRESETS.associateBy { it.melodyName }
        assertEquals("063C10", byName["OPPO Enco X2"]?.fallbackProductIdHex)
        assertEquals("067410", byName["OPPO Enco X3"]?.fallbackProductIdHex)
        assertEquals("067C10", byName["OPPO Enco Air4 Pro"]?.fallbackProductIdHex)
        assertEquals("066010", byName["OPPO Enco Free3"]?.fallbackProductIdHex)
        assertEquals("T2", byName["OPPO Enco Free3"]?.productType)
        assertEquals("064014", byName["OnePlus Buds Pro 3"]?.fallbackProductIdHex)
        assertEquals("064C12", byName["realme Buds Air7 Pro"]?.fallbackProductIdHex)
        assertTrue(BridgeContract.SPOOF_PRESETS.all { it.defaultColor == 0 })
        assertEquals(0x063C10, BridgeContract.parseProductIdInt(null))
    }

    @Test
    fun rootMelodyWakeSanitizesMacPidAndBuildsSafeDiscoveryCommand() {
        assertEquals("AA:BB:CC:11:22:33", RootMelodyWake.sanitizeMac(" aa:bb:cc:11:22:33 "))
        assertNull(RootMelodyWake.sanitizeMac("AA:BB:CC:11:22:33; rm -rf /"))
        assertNull(RootMelodyWake.sanitizeMac("invalid-mac"))

        assertEquals("063C10", RootMelodyWake.sanitizePidHex("0x063c10"))
        assertNull(RootMelodyWake.sanitizePidHex("063C10 && id"))

        val cmd = RootMelodyWake.buildDiscoveryAmCommand(
            mac = "aa:bb:cc:11:22:33",
            pidHex = "063c10",
            modelName = "OPPO Enco X2;whoami",
            productType = "T1",
            color = 0
        )
        assertNotNull(cmd)
        assertTrue(cmd!!.contains("--es route_from onForwardConnectedPopup"))
        assertTrue(cmd.contains("--es device_mac_info AA:BB:CC:11:22:33"))
        assertTrue(cmd.contains("--es product_id 063C10"))
        assertFalse(cmd.contains(";"))

        assertNull(
            RootMelodyWake.buildDiscoveryAmCommand(
                mac = "not-a-mac",
                pidHex = "063C10",
                modelName = "OPPO Enco X2",
                productType = "T1",
                color = 0
            )
        )
    }
}
