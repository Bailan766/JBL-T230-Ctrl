package com.jblt230.ctrl.bridge

import com.jblt230.ctrl.ble.NoiseMode

/**
 * Shared intent actions, package constants, and OPPO/ColorOS model mappings
 * for standalone takeover (`FluidCloudNotifier`, `EarbudsPopupOverlay`,
 * `JblBatteryWidgetProvider`) AND root LSPosed Melody (`com.oplus.melody`) spoofing.
 *
 * Pure Kotlin helpers remain JVM unit-testable.
 */
object BridgeContract {

    const val PKG_SELF = "com.jblt230.ctrl"
    const val PKG_MELODY = "com.oplus.melody"
    const val PKG_ACCESSORY = "com.heytap.accessory"
    const val PKG_WIRELESS_SETTINGS = "com.oplus.wirelesssettings"
    const val PKG_JBL_OFFICIAL = "jbl.stc.com"

    /** Broadcast action for invoking background GATT commands on [JblBridgeService]. */
    const val ACTION_REMOTE_COMMAND = "com.jblt230.ctrl.ACTION_REMOTE_COMMAND"

    /** App -> `com.oplus.melody` Xposed hook live state + popup/capsule trigger. */
    const val ACTION_MELODY_SYNC = "com.jblt230.ctrl.ACTION_MELODY_SYNC"

    /** `com.oplus.melody` Xposed hook -> app confirmation ACK that LSPosed hook is active. */
    const val ACTION_MELODY_HOOK_ACK = "com.jblt230.ctrl.ACTION_MELODY_HOOK_ACK"

    const val EXTRA_CMD = "cmd"
    const val CMD_CONNECT_AND_SYNC = "CONNECT_AND_SYNC"
    const val CMD_SET_NOISE_MODE = "SET_NOISE_MODE"
    const val CMD_CYCLE_NOISE_MODE = "CYCLE_NOISE_MODE"
    const val CMD_SET_EQ_PRESET = "SET_EQ_PRESET"
    const val CMD_FIND_BUDS = "FIND_BUDS"
    const val CMD_DISMISS_FLUID_CLOUD = "DISMISS_FLUID_CLOUD"

    const val EXTRA_MAC = "mac"
    const val EXTRA_NOISE_MODE = "noise_mode"
    const val EXTRA_EQ_PRESET = "eq_preset"
    const val EXTRA_TRIGGER_POPUP = "trigger_popup"
    const val EXTRA_FIND_LEFT = "find_left"
    const val EXTRA_FIND_RIGHT = "find_right"

    // Melody LSPosed sync extras
    const val EXTRA_MELODY_SPOOF_ENABLED = "melody_spoof_enabled"
    const val EXTRA_DEVICE_NAME = "device_name"
    const val EXTRA_SPOOF_MODEL = "spoof_model"
    const val EXTRA_SPOOF_PID = "spoof_pid"
    const val EXTRA_SPOOF_COLOR = "spoof_color"
    const val EXTRA_SPOOF_TYPE = "spoof_type"
    const val EXTRA_HOOK_PROCESS = "hook_process"
    const val EXTRA_HOOK_STAGE = "hook_stage"
    const val EXTRA_HOOK_STATUS = "hook_status"
    const val EXTRA_LEFT_BATTERY = "left_battery"
    const val EXTRA_RIGHT_BATTERY = "right_battery"
    const val EXTRA_CASE_BATTERY = "case_battery"
    const val EXTRA_LEFT_CHARGING = "left_charging"
    const val EXTRA_RIGHT_CHARGING = "right_charging"
    const val EXTRA_CASE_CHARGING = "case_charging"
    const val EXTRA_NOISE_INDEX = "noise_index"
    const val EXTRA_EQ_TYPE = "eq_type"
    const val EXTRA_TRIGGER_MELODY_POPUP = "trigger_melody_popup"
    const val EXTRA_TRIGGER_MELODY_CAPSULE = "trigger_melody_capsule"

    data class SpoofModelPreset(
        val id: String,
        val melodyName: String,
        val fallbackProductIdHex: String,
        val defaultColor: Int,
        val productType: String = "T1"
    )

    val SPOOF_PRESETS = listOf(
        SpoofModelPreset("enco_x2", "OPPO Enco X2", "063C10", 0, "T1"),
        SpoofModelPreset("enco_x3", "OPPO Enco X3", "067410", 0, "T1"),
        SpoofModelPreset("enco_air4_pro", "OPPO Enco Air4 Pro", "067C10", 0, "T1"),
        SpoofModelPreset("enco_free3", "OPPO Enco Free3", "066010", 0, "T2"),
        SpoofModelPreset("oneplus_buds_pro3", "OnePlus Buds Pro 3", "064014", 0, "T1"),
        SpoofModelPreset("realme_buds_air7_pro", "realme Buds Air7 Pro", "064C12", 0, "T1")
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

    fun formatBatteryText(level: Int?, charging: Boolean = false): String {
        val base = if (level != null && level in 0..100) "${level}%" else "--%"
        return if (charging && level != null && level in 0..100) "⚡$base" else base
    }

    /**
     * Compact `<= 7` char text tailored for Android 16 / ColorOS 16 Fluid Cloud status bar
     * capsule (`setShortCriticalText`) so the pill never overflows or truncates awkwardly.
     */
    fun formatFluidCloudChip(
        left: Int?,
        right: Int?,
        leftCharging: Boolean = false,
        rightCharging: Boolean = false
    ): String {
        val lValid = left?.takeIf { it in 0..100 }
        val rValid = right?.takeIf { it in 0..100 }
        val anyCharging = leftCharging || rightCharging
        return when {
            lValid != null && rValid != null -> {
                if (lValid == rValid) {
                    val prefix = if (anyCharging) "⚡" else ""
                    "$prefix${lValid}%"
                } else {
                    val pair = "L$lValid R$rValid"
                    if (pair.length <= 7) pair else "${minOf(lValid, rValid)}%"
                }
            }
            lValid != null -> "${if (leftCharging) "⚡" else ""}${lValid}%"
            rValid != null -> "${if (rightCharging) "⚡" else ""}${rValid}%"
            else -> "已连接"
        }
    }

    /** Returns `0..100` average bud charge level for Live Update progress bar track. */
    fun averageBudBattery(left: Int?, right: Int?, fallback: Int? = null): Int {
        val vals = listOfNotNull(
            left?.takeIf { it in 0..100 },
            right?.takeIf { it in 0..100 }
        )
        if (vals.isNotEmpty()) return vals.average().toInt().coerceIn(0, 100)
        return fallback?.takeIf { it in 0..100 } ?: 0
    }

    fun noiseModeLabel(mode: NoiseMode): String = when (mode) {
        NoiseMode.ANC -> "主动降噪"
        NoiseMode.AMBIENT_AWARE -> "环境感知"
        NoiseMode.TALKTHRU -> "自由通话"
        NoiseMode.OFF -> "关闭"
    }

    fun nextQuickNoiseMode(current: NoiseMode): NoiseMode = when (current) {
        NoiseMode.OFF -> NoiseMode.ANC
        NoiseMode.ANC -> NoiseMode.AMBIENT_AWARE
        NoiseMode.AMBIENT_AWARE, NoiseMode.TALKTHRU -> NoiseMode.OFF
    }

    /**
     * Maps Harman [NoiseMode] to ColorOS Melody NoiseReduction index:
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

    fun parseProductIdInt(hexOrDec: String?, fallback: Int = 0x063C10): Int {
        if (hexOrDec.isNullOrBlank()) return fallback
        val clean = hexOrDec.trim().removePrefix("0x").removePrefix("0X")
        return clean.toIntOrNull(16) ?: fallback
    }

    fun formatProductIdHex(productId: Int): String = "%06X".format(productId and 0xFFFFFF)

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
