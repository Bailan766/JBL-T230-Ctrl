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
    fun noiseModeColorOsIndexRoundTrips() {
        assertEquals(0, BridgeContract.noiseModeToColorOsIndex(NoiseMode.OFF))
        assertEquals(1, BridgeContract.noiseModeToColorOsIndex(NoiseMode.AMBIENT_AWARE))
        assertEquals(1, BridgeContract.noiseModeToColorOsIndex(NoiseMode.TALKTHRU))
        assertEquals(2, BridgeContract.noiseModeToColorOsIndex(NoiseMode.ANC))

        assertEquals(NoiseMode.OFF, BridgeContract.colorOsIndexToNoiseMode(0))
        assertEquals(NoiseMode.AMBIENT_AWARE, BridgeContract.colorOsIndexToNoiseMode(1))
        assertEquals(NoiseMode.ANC, BridgeContract.colorOsIndexToNoiseMode(2))
        assertEquals(NoiseMode.OFF, BridgeContract.colorOsIndexToNoiseMode(99))
    }

    @Test
    fun productIdHexParsingAndFormatting() {
        assertEquals(0x060414, BridgeContract.parseProductIdInt("060414"))
        assertEquals(0x060414, BridgeContract.parseProductIdInt("0x060414"))
        assertEquals(0x060814, BridgeContract.parseProductIdInt("invalid", fallback = 0x060814))
        assertEquals("060414", BridgeContract.formatProductIdHex(0x060414))
        assertEquals("030301", BridgeContract.formatProductIdHex(0x030301))
    }

    @Test
    fun eqPresetColorOsTypeRoundTrips() {
        val presets = listOf("off", "jazz", "vocal", "bass", "club", "studio", "extreme_bass", "custom")
        for (preset in presets) {
            val type = BridgeContract.eqPresetToColorOsType(preset)
            assertEquals(preset, BridgeContract.colorOsTypeToEqPreset(type))
        }
        assertEquals("off", BridgeContract.colorOsTypeToEqPreset(-1))
        assertEquals(0, BridgeContract.eqPresetToColorOsType("unknown"))
    }
}
