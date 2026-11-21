package com.jblt230.ctrl.bridge

import android.content.Context
import android.content.Intent
import com.jblt230.ctrl.ble.LinkState
import com.jblt230.ctrl.ble.NoiseMode
import com.jblt230.ctrl.data.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates foreground ViewModel vs background [JblBridgeService] ownership of
 * the single GATT control link, persists the latest snapshot, broadcasts live state
 * into ColorOS `com.oplus.melody` via LSPosed hooks, and keeps Android 16 [FluidCloudNotifier],
 * [JblBatteryWidgetProvider], and optional [EarbudsPopupOverlay] synchronized.
 */
object TakeoverBridge {

    val foregroundLinkActive = AtomicBoolean(false)

    private val _melodyHookAlive = MutableStateFlow(false)
    val melodyHookAlive: StateFlow<Boolean> = _melodyHookAlive.asStateFlow()

    fun markMelodyHookAlive() {
        _melodyHookAlive.value = true
    }

    fun pushUiState(
        context: Context,
        state: UiState,
        triggerPopup: Boolean = false,
        forceOverlay: Boolean = false,
        triggerMelodyPopup: Boolean = false,
        triggerMelodyCapsule: Boolean = false
    ) {
        val prefs = TakeoverPrefs(context)
        val cached = prefs.loadSnapshot()
        val mac = state.system.deviceAddress.ifBlank { cached.mac }
        if (mac.isBlank() && !triggerPopup && !forceOverlay && !triggerMelodyPopup && !triggerMelodyCapsule) return

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
            deviceName = state.system.deviceName.ifBlank { "JBL Tune 230NC" },
            controlReady = state.link is LinkState.Ready,
            triggerPopup = triggerPopup,
            forceOverlay = forceOverlay,
            triggerMelodyPopup = triggerMelodyPopup,
            triggerMelodyCapsule = triggerMelodyCapsule
        )
    }

    fun pushRawSnapshot(
        context: Context,
        snap: CachedDeviceSnapshot,
        deviceName: String = "JBL Tune 230NC",
        controlReady: Boolean,
        triggerPopup: Boolean,
        forceOverlay: Boolean = false,
        triggerMelodyPopup: Boolean = false,
        triggerMelodyCapsule: Boolean = false
    ) {
        val prefs = TakeoverPrefs(context)
        val cfg = prefs.loadConfig()
        if (snap.mac.isNotBlank()) {
            prefs.saveSnapshot(snap)
        }
        JblBatteryWidgetProvider.updateAll(context)
        if (!cfg.enabled) return

        if (cfg.melodySpoofEnabled) {
            broadcastToMelodyHook(
                context = context,
                cfg = cfg,
                snap = snap,
                deviceName = deviceName,
                triggerMelodyPopup = triggerMelodyPopup,
                triggerMelodyCapsule = triggerMelodyCapsule
            )
        }

        if (cfg.useFluidCloud && (triggerPopup || controlReady)) {
            FluidCloudNotifier.postOrUpdate(
                context = context,
                snap = snap,
                deviceName = deviceName,
                controlReady = controlReady
            )
        }

        val shouldShowOverlay = forceOverlay ||
            (cfg.autoPopupOnConnect && triggerPopup) ||
            EarbudsPopupOverlay.isShowing()
        if (shouldShowOverlay) {
            EarbudsPopupOverlay.showOrUpdate(
                context = context,
                snap = snap,
                deviceName = deviceName,
                controlReady = controlReady,
                autoDismiss = cfg.autoDismissPopup
            )
        }
    }

    private fun broadcastToMelodyHook(
        context: Context,
        cfg: TakeoverConfig,
        snap: CachedDeviceSnapshot,
        deviceName: String,
        triggerMelodyPopup: Boolean,
        triggerMelodyCapsule: Boolean
    ) {
        val preset = BridgeContract.presetByName(cfg.spoofModelName)
        val noise = runCatching { NoiseMode.valueOf(snap.noiseMode) }.getOrDefault(NoiseMode.OFF)
        val intent = Intent(BridgeContract.ACTION_MELODY_SYNC).apply {
            setPackage(BridgeContract.PKG_MELODY)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            putExtra(BridgeContract.EXTRA_MELODY_SPOOF_ENABLED, cfg.melodySpoofEnabled)
            putExtra(BridgeContract.EXTRA_MAC, snap.mac)
            putExtra(BridgeContract.EXTRA_DEVICE_NAME, deviceName)
            putExtra(BridgeContract.EXTRA_SPOOF_MODEL, preset.melodyName)
            putExtra(BridgeContract.EXTRA_SPOOF_PID, preset.fallbackProductIdHex)
            putExtra(BridgeContract.EXTRA_SPOOF_COLOR, preset.defaultColor)
            putExtra(BridgeContract.EXTRA_LEFT_BATTERY, snap.leftBattery ?: -1)
            putExtra(BridgeContract.EXTRA_RIGHT_BATTERY, snap.rightBattery ?: -1)
            putExtra(BridgeContract.EXTRA_CASE_BATTERY, snap.caseBattery ?: -1)
            putExtra(BridgeContract.EXTRA_LEFT_CHARGING, snap.leftCharging)
            putExtra(BridgeContract.EXTRA_RIGHT_CHARGING, snap.rightCharging)
            putExtra(BridgeContract.EXTRA_CASE_CHARGING, snap.caseCharging)
            putExtra(BridgeContract.EXTRA_NOISE_INDEX, BridgeContract.noiseModeToColorOsIndex(noise))
            putExtra(BridgeContract.EXTRA_EQ_TYPE, BridgeContract.eqPresetToColorOsType(snap.eqPresetId))
            putExtra(BridgeContract.EXTRA_TRIGGER_MELODY_POPUP, triggerMelodyPopup)
            putExtra(BridgeContract.EXTRA_TRIGGER_MELODY_CAPSULE, triggerMelodyCapsule)
        }
        runCatching { context.applicationContext.sendBroadcast(intent) }
    }
}
