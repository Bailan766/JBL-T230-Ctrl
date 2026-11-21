package com.jblt230.ctrl.bridge

import android.content.Context
import com.jblt230.ctrl.ble.LinkState
import com.jblt230.ctrl.data.UiState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates foreground ViewModel vs background [JblBridgeService] ownership of
 * the single GATT control link, persists the latest snapshot, and updates the
 * root-free [EarbudsPopupOverlay].
 */
object ColorOsBridge {

    val foregroundLinkActive = AtomicBoolean(false)

    fun pushUiState(context: Context, state: UiState, triggerPopup: Boolean = false) {
        val prefs = ColorOsPrefs(context)
        val cached = prefs.loadSnapshot()
        val mac = state.system.deviceAddress.ifBlank { cached.mac }
        if (mac.isBlank() && !triggerPopup) return

        val snap = CachedDeviceSnapshot(
            mac = mac,
            leftBattery = state.battery?.left ?: state.system.systemBattery ?: cached.leftBattery,
            rightBattery = state.battery?.right ?: state.system.systemBattery ?: cached.rightBattery,
            caseBattery = state.battery?.case ?: cached.caseBattery,
            leftCharging = state.battery?.leftCharging ?: cached.leftCharging,
            rightCharging = state.battery?.rightCharging ?: cached.rightCharging,
            caseCharging = state.battery?.caseCharging ?: cached.caseCharging,
            noiseMode = state.noiseMode.name,
            eqPresetId = state.eqPresetId,
            fwVersion = state.deviceInfo?.firmwareVersion?.ifBlank { "" } ?: cached.fwVersion
        )
        pushRawSnapshot(
            context = context,
            snap = snap,
            deviceName = state.system.deviceName.ifBlank { "JBL TUNE230NC TWS" },
            controlReady = state.link is LinkState.Ready,
            triggerPopup = triggerPopup
        )
    }

    fun pushRawSnapshot(
        context: Context,
        snap: CachedDeviceSnapshot,
        deviceName: String = "JBL TUNE230NC TWS",
        controlReady: Boolean,
        triggerPopup: Boolean
    ) {
        val prefs = ColorOsPrefs(context)
        val cfg = prefs.loadConfig()
        if (snap.mac.isNotBlank()) {
            prefs.saveSnapshot(snap)
        }
        val shouldRenderPopup = cfg.enabled && (triggerPopup || EarbudsPopupOverlay.isShowing())
        if (shouldRenderPopup) {
            EarbudsPopupOverlay.showOrUpdate(
                context = context,
                snap = snap,
                deviceName = deviceName,
                controlReady = controlReady,
                autoDismiss = cfg.autoDismissPopup
            )
        }
    }
}
