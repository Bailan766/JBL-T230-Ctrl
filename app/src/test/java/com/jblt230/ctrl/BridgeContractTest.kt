package com.jblt230.ctrl

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
            generatedAt = "2026-11-22 01:40:00",
            appVersion = "1.5.0 (8)",
            deviceModel = "realme RMX3888",
            androidRelease = "16",
            sdkInt = 36,
            permissionsGranted = true,
            overlayGranted = true,
            takeoverEnabled = true,
            autoPopupOnConnect = true,
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
            LogEntry("01:40:01.100", "→", "AA 25 00", "battery_read"),
            LogEntry("01:40:01.145", "←", "AA 25 06 00 5A 00 5A 00 50", "")
        )
        val text = DiagnosticReportFormatter.format(env, steps, logs)
        assertTrue(text.contains("通过=1, 警告=0, 失败=1"))
        assertTrue(text.contains("[PASS] (45ms) 三端电量 (0x25)"))
        assertTrue(text.contains("[FAIL] (1200ms) 多包 EQ: 分包超时"))
        assertTrue(text.contains("01:40:01.100 → AA 25 00 // battery_read"))
    }
}
