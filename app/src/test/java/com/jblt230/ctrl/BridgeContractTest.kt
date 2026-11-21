package com.jblt230.ctrl

import com.jblt230.ctrl.ble.NoiseMode
import com.jblt230.ctrl.bridge.BridgeContract
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
}
