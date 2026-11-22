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
 * into ColorOS `com.oplus.melody` via LSPosed hooks, and keeps Android 16 [FluidCloudNotifier]
 * and [JblBatteryWidgetProvider] synchronized.
 */
object TakeoverBridge {

    val foregroundLinkActive = AtomicBoolean(false)
    val appInForeground = AtomicBoolean(false)

    @Volatile
    private var lastWirelessSettingsSyncKey: String = ""

    private val _melodyHookAlive = MutableStateFlow(false)
    val melodyHookAlive: StateFlow<Boolean> = _melodyHookAlive.asStateFlow()

    private val _melodyHookStage = MutableStateFlow("waiting")
    val melodyHookStage: StateFlow<String> = _melodyHookStage.asStateFlow()

    private val _melodyHookStatus = MutableStateFlow("等待与欢律握手（可点 Root 重启欢律）")
    val melodyHookStatus: StateFlow<String> = _melodyHookStatus.asStateFlow()

    fun markMelodyHookAlive(
        stage: String = "ack",
        status: String = "欢律进程已实时握手"
    ) {
        _melodyHookAlive.value = true
        _melodyHookStage.value = stage
        if (status.isNotBlank()) {
            _melodyHookStatus.value = status
        }
    }

    fun resetMelodyHookStatus(status: String) {
        _melodyHookAlive.value = false
        _melodyHookStage.value = "restarting"
        _melodyHookStatus.value = status
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

        val bleBat = state.battery
        val leftBat = if (bleBat != null) bleBat.left else (state.system.systemBattery ?: cached.leftBattery)
        val rightBat = if (bleBat != null) bleBat.right else (state.system.systemBattery ?: cached.rightBattery)
        val caseBat = if (bleBat != null) bleBat.case else cached.caseBattery

        val snap = CachedDeviceSnapshot(
            mac = mac,
            leMac = cached.leMac,
            leftBattery = leftBat,
            rightBattery = rightBat,
            caseBattery = caseBat,
            leftCharging = bleBat?.leftCharging ?: cached.leftCharging,
            rightCharging = bleBat?.rightCharging ?: cached.rightCharging,
            caseCharging = bleBat?.caseCharging ?: cached.caseCharging,
            noiseMode = state.noiseMode.name,
            eqPresetId = state.eqPresetId,
            fwVersion = state.deviceInfo?.firmwareVersion?.ifBlank { "" } ?: cached.fwVersion
        )
        pushRawSnapshot(
            context = context,
            snap = snap,
            deviceName = BridgeContract.cleanDisplayName(state.system.deviceName),
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
        val cleanName = BridgeContract.cleanDisplayName(deviceName)
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
                deviceName = cleanName,
                triggerMelodyPopup = triggerMelodyPopup,
                triggerMelodyCapsule = triggerMelodyCapsule
            )
        }

        if (triggerPopup) {
            FluidCloudNotifier.clearSessionDismissal()
        }
        if (cfg.useFluidCloud && (triggerPopup || (controlReady && !FluidCloudNotifier.isDismissedForSession))) {
            FluidCloudNotifier.postOrUpdate(
                context = context,
                snap = snap,
                deviceName = cleanName,
                controlReady = controlReady
            )
        }

        if (forceOverlay || EarbudsPopupOverlay.isShowing()) {
            EarbudsPopupOverlay.showOrUpdate(
                context = context,
                snap = snap,
                deviceName = cleanName,
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
            putExtra(BridgeContract.EXTRA_FW_VERSION, snap.fwVersion)
            putExtra(BridgeContract.EXTRA_SPOOF_MODEL, preset.melodyName)
            putExtra(BridgeContract.EXTRA_SPOOF_PID, preset.fallbackProductIdHex)
            putExtra(BridgeContract.EXTRA_SPOOF_COLOR, preset.defaultColor)
            putExtra(BridgeContract.EXTRA_SPOOF_TYPE, preset.productType)
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

        val wsKey = "${cfg.melodySpoofEnabled}|${preset.fallbackProductIdHex}"
        if (wsKey != lastWirelessSettingsSyncKey) {
            lastWirelessSettingsSyncKey = wsKey
            val wsIntent = Intent(intent).apply {
                setPackage(BridgeContract.PKG_WIRELESS_SETTINGS)
                putExtra(BridgeContract.EXTRA_TRIGGER_MELODY_POPUP, false)
                putExtra(BridgeContract.EXTRA_TRIGGER_MELODY_CAPSULE, false)
            }
            runCatching { context.applicationContext.sendBroadcast(wsIntent) }
        }
    }
}
