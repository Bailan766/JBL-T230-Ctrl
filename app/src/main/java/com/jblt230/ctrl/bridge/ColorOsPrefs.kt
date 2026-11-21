package com.jblt230.ctrl.bridge

import android.content.Context

data class ColorOsSpoofConfig(
    val enabled: Boolean = true,
    val autoPopupOnConnect: Boolean = true,
    val autoDismissPopup: Boolean = true,
    val keepLinkAlive: Boolean = false
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

class ColorOsPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("coloros_spoofer", Context.MODE_PRIVATE)

    fun loadConfig(): ColorOsSpoofConfig = ColorOsSpoofConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        autoPopupOnConnect = prefs.getBoolean(KEY_AUTO_POPUP, true),
        autoDismissPopup = prefs.getBoolean(KEY_AUTO_DISMISS, true),
        keepLinkAlive = prefs.getBoolean(KEY_KEEP_ALIVE, false)
    )

    fun saveConfig(cfg: ColorOsSpoofConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, cfg.enabled)
            .putBoolean(KEY_AUTO_POPUP, cfg.autoPopupOnConnect)
            .putBoolean(KEY_AUTO_DISMISS, cfg.autoDismissPopup)
            .putBoolean(KEY_KEEP_ALIVE, cfg.keepLinkAlive)
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
        private const val KEY_AUTO_POPUP = "auto_popup"
        private const val KEY_AUTO_DISMISS = "auto_dismiss"
        private const val KEY_KEEP_ALIVE = "keep_alive"

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
