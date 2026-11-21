package com.jblt230.ctrl.bridge

import android.content.Context

data class TakeoverConfig(
    val enabled: Boolean = true,
    val useFluidCloud: Boolean = true,
    val autoPopupOnConnect: Boolean = false,
    val autoDismissPopup: Boolean = true,
    val keepLinkAlive: Boolean = false,
    val melodySpoofEnabled: Boolean = true,
    val spoofModelName: String = "OPPO Enco X2",
    val melodyAutoPopup: Boolean = true,
    val melodyAutoCapsule: Boolean = true
)

data class CachedDeviceSnapshot(
    val mac: String = "",
    val leftBattery: Int? = null,
    val rightBattery: Int? = null,
    val caseBattery: Int? = null,
    val leftCharging: Boolean = false,
    val rightCharging: Boolean = false,
    val caseCharging: Boolean = false,
    val noiseMode: String = "OFF",
    val eqPresetId: String = "off",
    val fwVersion: String = ""
)

/**
 * Persists standalone takeover & ColorOS Melody LSPosed spoof preferences alongside
 * the most recent device telemetry snapshot.
 */
class TakeoverPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("coloros_spoofer", Context.MODE_PRIVATE)

    fun loadConfig(): TakeoverConfig = TakeoverConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        useFluidCloud = prefs.getBoolean(KEY_FLUID_CLOUD, true),
        autoPopupOnConnect = prefs.getBoolean(KEY_AUTO_POPUP, false),
        autoDismissPopup = prefs.getBoolean(KEY_AUTO_DISMISS, true),
        keepLinkAlive = prefs.getBoolean(KEY_KEEP_ALIVE, false),
        melodySpoofEnabled = prefs.getBoolean(KEY_MELODY_SPOOF, true),
        spoofModelName = prefs.getString(KEY_SPOOF_MODEL, "OPPO Enco X2") ?: "OPPO Enco X2",
        melodyAutoPopup = prefs.getBoolean(KEY_MELODY_POPUP, true),
        melodyAutoCapsule = prefs.getBoolean(KEY_MELODY_CAPSULE, true)
    )

    fun saveConfig(cfg: TakeoverConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, cfg.enabled)
            .putBoolean(KEY_FLUID_CLOUD, cfg.useFluidCloud)
            .putBoolean(KEY_AUTO_POPUP, cfg.autoPopupOnConnect)
            .putBoolean(KEY_AUTO_DISMISS, cfg.autoDismissPopup)
            .putBoolean(KEY_KEEP_ALIVE, cfg.keepLinkAlive)
            .putBoolean(KEY_MELODY_SPOOF, cfg.melodySpoofEnabled)
            .putString(KEY_SPOOF_MODEL, cfg.spoofModelName)
            .putBoolean(KEY_MELODY_POPUP, cfg.melodyAutoPopup)
            .putBoolean(KEY_MELODY_CAPSULE, cfg.melodyAutoCapsule)
            .apply()
    }

    fun loadSnapshot(): CachedDeviceSnapshot = CachedDeviceSnapshot(
        mac = prefs.getString(KEY_MAC, "") ?: "",
        leftBattery = prefs.getInt(KEY_LEFT_BAT, -1).takeIf { it in 0..100 },
        rightBattery = prefs.getInt(KEY_RIGHT_BAT, -1).takeIf { it in 0..100 },
        caseBattery = prefs.getInt(KEY_CASE_BAT, -1).takeIf { it in 0..100 },
        leftCharging = prefs.getBoolean(KEY_LEFT_CHG, false),
        rightCharging = prefs.getBoolean(KEY_RIGHT_CHG, false),
        caseCharging = prefs.getBoolean(KEY_CASE_CHG, false),
        noiseMode = prefs.getString(KEY_NOISE_MODE, "OFF") ?: "OFF",
        eqPresetId = prefs.getString(KEY_EQ_PRESET, "off") ?: "off",
        fwVersion = prefs.getString(KEY_FW_VERSION, "") ?: ""
    )

    fun saveSnapshot(snap: CachedDeviceSnapshot) {
        prefs.edit()
            .putString(KEY_MAC, snap.mac)
            .putInt(KEY_LEFT_BAT, snap.leftBattery ?: -1)
            .putInt(KEY_RIGHT_BAT, snap.rightBattery ?: -1)
            .putInt(KEY_CASE_BAT, snap.caseBattery ?: -1)
            .putBoolean(KEY_LEFT_CHG, snap.leftCharging)
            .putBoolean(KEY_RIGHT_CHG, snap.rightCharging)
            .putBoolean(KEY_CASE_CHG, snap.caseCharging)
            .putString(KEY_NOISE_MODE, snap.noiseMode)
            .putString(KEY_EQ_PRESET, snap.eqPresetId)
            .putString(KEY_FW_VERSION, snap.fwVersion)
            .apply()
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FLUID_CLOUD = "use_fluid_cloud"
        private const val KEY_AUTO_POPUP = "auto_popup_v2"
        private const val KEY_AUTO_DISMISS = "auto_dismiss"
        private const val KEY_KEEP_ALIVE = "keep_alive"
        private const val KEY_MELODY_SPOOF = "melody_spoof"
        private const val KEY_SPOOF_MODEL = "spoof_model"
        private const val KEY_MELODY_POPUP = "melody_popup"
        private const val KEY_MELODY_CAPSULE = "melody_capsule"

        private const val KEY_MAC = "snap_mac"
        private const val KEY_LEFT_BAT = "snap_left_bat"
        private const val KEY_RIGHT_BAT = "snap_right_bat"
        private const val KEY_CASE_BAT = "snap_case_bat"
        private const val KEY_LEFT_CHG = "snap_left_chg"
        private const val KEY_RIGHT_CHG = "snap_right_chg"
        private const val KEY_CASE_CHG = "snap_case_chg"
        private const val KEY_NOISE_MODE = "snap_noise_mode"
        private const val KEY_EQ_PRESET = "snap_eq_preset"
        private const val KEY_FW_VERSION = "snap_fw_version"
    }
}
