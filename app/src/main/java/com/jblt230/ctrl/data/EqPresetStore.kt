package com.jblt230.ctrl.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local-only EQ storage. Nothing here is written to the earbuds until the
 * command family has been resolved by a real-device capture.
 *
 * Gains are Float because the official 5.38.2 preset definitions for
 * TUNE230NC TWS use 0.5 dB steps (e.g. JAZZ = 2.5, VOCAL = 3.5).
 */
class EqPresetStore(context: Context) {

    private val prefs = context.getSharedPreferences("eq_local", Context.MODE_PRIVATE)

    data class EqData(val presetId: String, val gains: List<Float>)

    fun load(defaultGains: List<Float>): EqData {
        val id = prefs.getString(KEY_PRESET, "off") ?: "off"
        val raw = prefs.getString(KEY_GAINS, null) ?: return EqData(id, defaultGains)
        val gains = runCatching {
            val arr = JSONObject(raw).optJSONArray("gains")
            if (arr == null) defaultGains
            else List(arr.length()) { arr.getDouble(it).toFloat().coerceIn(-6f, 6f) }
        }.getOrDefault(defaultGains)
        return EqData(id, if (gains.size == 10) gains else defaultGains)
    }

    fun save(presetId: String, gains: List<Float>) {
        val arr = JSONArray()
        gains.forEach { arr.put(it.coerceIn(-6f, 6f).toDouble()) }
        prefs.edit()
            .putString(KEY_PRESET, presetId)
            .putString(KEY_GAINS, JSONObject().put("gains", arr).toString())
            .apply()
    }

    companion object {
        private const val KEY_PRESET = "preset_id"
        private const val KEY_GAINS = "gains"
    }
}
