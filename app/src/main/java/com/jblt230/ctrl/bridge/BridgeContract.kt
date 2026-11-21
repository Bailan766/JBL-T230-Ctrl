package com.jblt230.ctrl.bridge

import com.jblt230.ctrl.ble.NoiseMode

/**
 * Shared intent actions, command identifiers, and helper formatters for standalone
 * background takeover (`JblBridgeReceiver` / `JblBridgeService` / `EarbudsPopupOverlay` /
 * `JblBatteryWidgetProvider`).
 *
 * Pure Kotlin helpers remain JVM unit-testable.
 */
object BridgeContract {

    const val PKG_SELF = "com.jblt230.ctrl"

    /** Broadcast action for invoking background GATT commands on [JblBridgeService]. */
    const val ACTION_REMOTE_COMMAND = "com.jblt230.ctrl.ACTION_REMOTE_COMMAND"

    const val EXTRA_CMD = "cmd"
    const val CMD_CONNECT_AND_SYNC = "CONNECT_AND_SYNC"
    const val CMD_SET_NOISE_MODE = "SET_NOISE_MODE"
    const val CMD_CYCLE_NOISE_MODE = "CYCLE_NOISE_MODE"
    const val CMD_SET_EQ_PRESET = "SET_EQ_PRESET"
    const val CMD_FIND_BUDS = "FIND_BUDS"

    const val EXTRA_MAC = "mac"
    const val EXTRA_NOISE_MODE = "noise_mode"
    const val EXTRA_EQ_PRESET = "eq_preset"
    const val EXTRA_TRIGGER_POPUP = "trigger_popup"
    const val EXTRA_FIND_LEFT = "find_left"
    const val EXTRA_FIND_RIGHT = "find_right"

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
}
