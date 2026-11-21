package com.jblt230.ctrl.bridge

import com.jblt230.ctrl.ble.NoiseMode

/**
 * Shared IPC contract and model mappings between `com.jblt230.ctrl` and the
 * LSPosed hooks running inside ColorOS `com.oplus.melody` / `jbl.stc.com`.
 *
 * Pure Kotlin helpers so mapping logic is unit-testable on a plain JVM.
 */
object BridgeContract {

    const val PKG_SELF = "com.jblt230.ctrl"
    const val PKG_MELODY = "com.oplus.melody"
    const val PKG_ACCESSORY = "com.heytap.accessory"
    const val PKG_WIRELESS_SETTINGS = "com.oplus.wirelesssettings"
    const val PKG_JBL_OFFICIAL = "jbl.stc.com"

    /** Broadcast sent by `com.jblt230.ctrl` -> received by Xposed hook inside `com.oplus.melody`. */
    const val ACTION_STATE_SYNC = "com.jblt230.ctrl.ACTION_STATE_SYNC"

    /** Broadcast sent by `com.oplus.melody` hook -> handled by `JblBridgeReceiver` -> `JblBridgeService`. */
    const val ACTION_REMOTE_COMMAND = "com.jblt230.ctrl.ACTION_REMOTE_COMMAND"

    // Command names for ACTION_REMOTE_COMMAND
    const val EXTRA_CMD = "cmd"
    const val CMD_CONNECT_AND_SYNC = "CONNECT_AND_SYNC"
    const val CMD_SET_NOISE_MODE = "SET_NOISE_MODE"
    const val CMD_SET_EQ_PRESET = "SET_EQ_PRESET"
    const val CMD_FIND_BUDS = "FIND_BUDS"

    // State / payload extras
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_MAC = "mac"
    const val EXTRA_DEVICE_NAME = "device_name"
    const val EXTRA_SPOOF_MODEL = "spoof_model"
    const val EXTRA_SPOOF_PRODUCT_ID = "spoof_product_id"
    const val EXTRA_SPOOF_COLOR = "spoof_color"
    const val EXTRA_CONTROL_READY = "control_ready"
    const val EXTRA_LEFT_BATTERY = "left_battery"
    const val EXTRA_RIGHT_BATTERY = "right_battery"
    const val EXTRA_CASE_BATTERY = "case_battery"
    const val EXTRA_LEFT_CHARGING = "left_charging"
    const val EXTRA_RIGHT_CHARGING = "right_charging"
    const val EXTRA_CASE_CHARGING = "case_charging"
    const val EXTRA_NOISE_MODE = "noise_mode"
    const val EXTRA_NOISE_INDEX = "noise_index"
    const val EXTRA_EQ_PRESET = "eq_preset"
    const val EXTRA_EQ_TYPE = "eq_type"
    const val EXTRA_FW_VERSION = "fw_version"
    const val EXTRA_TWS_CONNECTED = "tws_connected"
    const val EXTRA_BOX_OPEN = "box_open"
    const val EXTRA_TRIGGER_POPUP = "trigger_popup"
    const val EXTRA_FIND_LEFT = "find_left"
    const val EXTRA_FIND_RIGHT = "find_right"

    data class SpoofModelPreset(
        val id: String,
        val melodyName: String,
        val fallbackProductIdHex: String,
        val defaultColor: Int
    )

    val SPOOF_PRESETS = listOf(
        SpoofModelPreset("enco_x2", "OPPO Enco X2", "060414", 1),
        SpoofModelPreset("enco_free3", "OPPO Enco Free3", "060814", 1),
        SpoofModelPreset("enco_air3_pro", "OPPO Enco Air3 Pro", "030301", 1),
        SpoofModelPreset("enco_air2", "OPPO Enco Air2", "030101", 1)
    )

    fun presetByName(name: String?): SpoofModelPreset =
        SPOOF_PRESETS.firstOrNull { it.melodyName.equals(name, ignoreCase = true) }
            ?: SPOOF_PRESETS.first()

    /** Matches real JBL Tune 230NC classic and -LE names without matching unrelated audio gear. */
    fun isTargetJblName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val upper = name.uppercase()
        return upper.contains("TUNE230") || upper.contains("TUNE 230")
    }

    /**
     * Maps Harman [NoiseMode] to standard ColorOS Melody NoiseReduction index:
     * 0 = Off (关闭), 1 = Transparency (通透), 2 = Noise Cancellation (降噪).
     */
    fun noiseModeToColorOsIndex(mode: NoiseMode): Int = when (mode) {
        NoiseMode.OFF -> 0
        NoiseMode.AMBIENT_AWARE, NoiseMode.TALKTHRU -> 1
        NoiseMode.ANC -> 2
    }

    /** Reverse mapping from ColorOS Melody NoiseReduction index to JBL [NoiseMode]. */
    fun colorOsIndexToNoiseMode(index: Int): NoiseMode = when (index) {
        1 -> NoiseMode.AMBIENT_AWARE
        2, 3, 4 -> NoiseMode.ANC
        else -> NoiseMode.OFF
    }

    /** Parses either hex ("060414") or decimal product ID string safely. */
    fun parseProductIdInt(hexOrDec: String?, fallback: Int = 0x060414): Int {
        if (hexOrDec.isNullOrBlank()) return fallback
        val clean = hexOrDec.trim().removePrefix("0x").removePrefix("0X")
        return clean.toIntOrNull(16) ?: fallback
    }

    fun formatProductIdHex(productId: Int): String = "%06X".format(productId and 0xFFFFFF)

    /** Best-effort mapping between JBL preset ids and ColorOS Enco EQ type ints. */
    fun eqPresetToColorOsType(presetId: String): Int = when (presetId.lowercase()) {
        "jazz" -> 1
        "vocal" -> 2
        "bass" -> 3
        "club" -> 4
        "studio" -> 5
        "extreme_bass" -> 6
        "custom" -> 7
        else -> 0
    }

    fun colorOsTypeToEqPreset(type: Int): String = when (type) {
        1 -> "jazz"
        2 -> "vocal"
        3 -> "bass"
        4 -> "club"
        5 -> "studio"
        6 -> "extreme_bass"
        7 -> "custom"
        else -> "off"
    }
}
