package com.jblt230.ctrl.bridge

import android.content.Context
import android.content.Intent
import com.jblt230.ctrl.ble.LinkState
import com.jblt230.ctrl.ble.NoiseMode
import com.jblt230.ctrl.data.UiState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates foreground ViewModel vs background [JblBridgeService] ownership of
 * the single GATT control link and broadcasts state snapshots to `com.oplus.melody`.
 */
object ColorOsBridge {

    val foregroundLinkActive = AtomicBoolean(false)

    fun pushUiState(context: Context, state: UiState, triggerPopup: Boolean = false) {
        val prefs = ColorOsPrefs(context)
        val cached = prefs.loadSnapshot()
        val mac = state.system.deviceAddress.ifBlank { cached.mac }
        if (mac.isBlank()) return

        val snap = CachedDeviceSnapshot(
            mac = mac,
            leftBattery = state.battery?.left ?: state.system.systemBattery ?: cached.leftBattery,
            rightBattery = state.battery?.right ?: state.system.systemBattery ?: cached.rightBattery,
            caseBattery = state.battery?.case ?: cached.caseBattery,
            leftCharging = state.battery?.leftCharging ?: false,
            rightCharging = state.battery?.rightCharging ?: false,
            caseCharging = state.battery?.caseCharging ?: false,
            noiseMode = state.noiseMode.name,
            eqPresetId = state.eqPresetId,
            fwVersion = state.deviceInfo?.firmwareVersion?.ifBlank { "" } ?: cached.fwVersion
        )
        pushRawSnapshot(
            context = context,
            snap = snap,
            deviceName = state.system.deviceName.ifBlank { "JBL TUNE230NC TWS" },
            controlReady = state.link is LinkState.Ready,
            twsConnected = state.twsConnected ?: true,
            triggerPopup = triggerPopup
        )
    }

    fun pushRawSnapshot(
        context: Context,
        snap: CachedDeviceSnapshot,
        deviceName: String = "JBL TUNE230NC TWS",
        controlReady: Boolean,
        twsConnected: Boolean = true,
        triggerPopup: Boolean
    ) {
        if (snap.mac.isBlank()) return
        val prefs = ColorOsPrefs(context)
        val cfg = prefs.loadConfig()
        prefs.saveSnapshot(snap)

        val preset = BridgeContract.presetByName(cfg.spoofModelName)
        val noiseMode = runCatching { NoiseMode.valueOf(snap.noiseMode) }.getOrDefault(NoiseMode.OFF)

        val intent = Intent(BridgeContract.ACTION_STATE_SYNC).apply {
            setPackage(BridgeContract.PKG_MELODY)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            putExtra(BridgeContract.EXTRA_ENABLED, cfg.enabled)
            putExtra(BridgeContract.EXTRA_MAC, snap.mac)
            putExtra(BridgeContract.EXTRA_DEVICE_NAME, deviceName)
            putExtra(BridgeContract.EXTRA_SPOOF_MODEL, preset.melodyName)
            putExtra(BridgeContract.EXTRA_SPOOF_PRODUCT_ID, preset.fallbackProductIdHex)
            putExtra(BridgeContract.EXTRA_SPOOF_COLOR, preset.defaultColor)
            putExtra(BridgeContract.EXTRA_CONTROL_READY, controlReady)
            putExtra(BridgeContract.EXTRA_LEFT_BATTERY, snap.leftBattery ?: -1)
            putExtra(BridgeContract.EXTRA_RIGHT_BATTERY, snap.rightBattery ?: -1)
            putExtra(BridgeContract.EXTRA_CASE_BATTERY, snap.caseBattery ?: -1)
            putExtra(BridgeContract.EXTRA_LEFT_CHARGING, snap.leftCharging)
            putExtra(BridgeContract.EXTRA_RIGHT_CHARGING, snap.rightCharging)
            putExtra(BridgeContract.EXTRA_CASE_CHARGING, snap.caseCharging)
            putExtra(BridgeContract.EXTRA_NOISE_MODE, noiseMode.name)
            putExtra(BridgeContract.EXTRA_NOISE_INDEX, BridgeContract.noiseModeToColorOsIndex(noiseMode))
            putExtra(BridgeContract.EXTRA_EQ_PRESET, snap.eqPresetId)
            putExtra(BridgeContract.EXTRA_EQ_TYPE, BridgeContract.eqPresetToColorOsType(snap.eqPresetId))
            putExtra(BridgeContract.EXTRA_FW_VERSION, snap.fwVersion)
            putExtra(BridgeContract.EXTRA_TWS_CONNECTED, twsConnected)
            putExtra(BridgeContract.EXTRA_BOX_OPEN, true)
            putExtra(BridgeContract.EXTRA_TRIGGER_POPUP, triggerPopup && cfg.enabled)
        }
        runCatching { context.applicationContext.sendBroadcast(intent) }
    }
}
