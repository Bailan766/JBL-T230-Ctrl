package com.jblt230.ctrl.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val time: String,
    val direction: String,
    val frameHex: String,
    val note: String
)

/**
 * In-memory, redacted protocol log. Never persisted, never uploaded.
 * MAC addresses and other identifiers are replaced at insertion time.
 */
class ProtocolLog(private val capacity: Int = 300) {

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val macPattern = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
    private val longHexPattern = Regex("(?i)\\b[0-9a-f]{12,}\\b")

    fun redact(text: String): String = text
        .replace(macPattern, "<MAC>")
        .replace(longHexPattern) { m ->
            if (m.value.length > 80) "<LONG_HEX>" else m.value
        }

    fun add(direction: String, frameHex: String, note: String = "") {
        val entry = LogEntry(
            time = formatter.format(Date()),
            direction = direction,
            frameHex = redact(frameHex),
            note = redact(note)
        )
        _entries.value = (_entries.value + entry).takeLast(capacity)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
