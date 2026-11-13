package com.jblt230.ctrl.protocol

import android.content.Context
import org.json.JSONObject

/**
 * Levels of evidence behind a command. Nothing is treated as "working" until
 * it has been recorded from the user's own device and read back successfully.
 */
enum class Confidence { RECORDED, APK_STATIC, PRIOR_DOC, INFERRED }

data class CommandSpec(
    val name: String,
    val cmd: Int?,
    val direction: String,
    val frameHex: String?,
    val replyCmd: Int?,
    val readback: String?,
    val confidence: Confidence,
    val source: String,
    val enabled: Boolean,
    val dangerous: Boolean,
    val requiresResolution: Boolean,
    val requiresConfirmation: Boolean,
    val autoStopMs: Long,
    val note: String
)

data class GattConfig(
    val service: String,
    val notify: String,
    val write: String,
    val cccd: String,
    val mtu: Int
)

data class ScanConfig(
    val directConnectTimeoutMs: Long,
    val scanTimeoutMs: Long,
    val manufacturerId: Int,
    val serviceDataUuid: String,
    val nameSuffix: String
)

/** Loads the machine-readable command table shipped in assets/commands.json. */
class CommandTable private constructor(
    val commands: List<CommandSpec>,
    val blacklist: Set<Int>,
    val gatt: GattConfig,
    val scan: ScanConfig,
    val deviceName: String,
    val productIds: List<String>
) {

    private val byName = commands.associateBy { it.name }

    operator fun get(name: String): CommandSpec? = byName[name]

    /** Only enabled, non-dangerous commands with a concrete frame can be sent. */
    fun sendable(name: String): CommandSpec? = byName[name]?.takeIf {
        it.enabled && !it.dangerous && !it.requiresResolution && it.frameHex != null
    }

    val writableNames: List<String> = commands.filter { it.direction == "write" }.map { it.name }

    companion object {
        fun load(context: Context): CommandTable {
            val json = context.assets.open("commands.json").bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val g = root.getJSONObject("gatt")
            val s = root.getJSONObject("scan")
            val d = root.getJSONObject("device")

            val commands = root.getJSONArray("commands").let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    CommandSpec(
                        name = o.getString("name"),
                        cmd = o.optString("cmd").takeIf { it != "null" && it.isNotBlank() }
                            ?.toIntOrNull(16),
                        direction = o.getString("direction"),
                        frameHex = o.optString("frame").takeIf { it != "null" && it.isNotBlank() },
                        replyCmd = o.optString("reply_cmd").takeIf { it != "null" && it.isNotBlank() }
                            ?.toIntOrNull(16),
                        readback = o.optString("readback").takeIf { it != "null" && it.isNotBlank() },
                        confidence = runCatching {
                            Confidence.valueOf(o.getString("confidence"))
                        }.getOrDefault(Confidence.INFERRED),
                        source = o.optString("source"),
                        enabled = o.optBoolean("enabled", false),
                        dangerous = o.optBoolean("dangerous", false),
                        requiresResolution = o.optBoolean("requires_resolution", false),
                        requiresConfirmation = o.optBoolean("requires_confirmation", false),
                        autoStopMs = o.optLong("auto_stop_ms", 0L),
                        note = o.optString("note")
                    )
                }
            }

            val blacklist = root.getJSONArray("blacklist").let { arr ->
                (0 until arr.length()).mapNotNull { arr.getString(it).toIntOrNull(16) }.toSet()
            }

            return CommandTable(
                commands = commands,
                blacklist = blacklist,
                gatt = GattConfig(
                    service = g.getString("service"),
                    notify = g.getString("notify"),
                    write = g.getString("write"),
                    cccd = g.getString("cccd"),
                    mtu = g.getInt("mtu")
                ),
                scan = ScanConfig(
                    directConnectTimeoutMs = s.getLong("direct_connect_timeout_ms"),
                    scanTimeoutMs = s.getLong("scan_timeout_ms"),
                    manufacturerId = s.getInt("manufacturer_id"),
                    serviceDataUuid = s.getString("service_data_uuid"),
                    nameSuffix = s.getString("name_suffix")
                ),
                deviceName = d.getString("name"),
                productIds = d.getJSONArray("product_ids").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
            )
        }
    }
}
