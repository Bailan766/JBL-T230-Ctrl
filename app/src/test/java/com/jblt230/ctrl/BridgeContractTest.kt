package com.jblt230.ctrl

import com.jblt230.ctrl.ble.FrameCodec
import com.jblt230.ctrl.ble.NoiseMode
import com.jblt230.ctrl.bridge.BridgeContract
import com.jblt230.ctrl.logging.DiagStatus
import com.jblt230.ctrl.logging.DiagStep
import com.jblt230.ctrl.logging.DiagnosticReportFormatter
import com.jblt230.ctrl.logging.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
