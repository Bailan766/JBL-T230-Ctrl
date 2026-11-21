package com.jblt230.ctrl.bridge

import java.util.concurrent.TimeUnit

data class RootExecResult(
    val ok: Boolean,
    val exitCode: Int,
    val output: String
)

/**
 * IO-only root (`su`) helper for waking / restarting `com.oplus.melody` and bypassing Android 16
 * Background Activity Launch restrictions when displaying `DiscoveryDialogActivity` on user tap.
 *
 * Sanitization helpers are pure JVM-testable with zero Android framework dependencies.
 */
object RootMelodyWake {

    private val MAC_REGEX = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
    private val PID_REGEX = Regex("^[0-9A-F]{6}$")
    private val TOKEN_STRIP_REGEX = Regex("[^A-Za-z0-9_ ]")

    fun sanitizeMac(mac: String): String? {
        val clean = mac.trim().uppercase()
        return clean.takeIf { MAC_REGEX.matches(it) }
    }

    fun sanitizePidHex(pid: String): String? {
        val clean = pid.trim().removePrefix("0x").removePrefix("0X").uppercase()
        return clean.takeIf { PID_REGEX.matches(it) }
    }

    fun sanitizeToken(value: String, fallback: String): String {
        val filtered = TOKEN_STRIP_REGEX.replace(value, "").trim().take(40).trim()
        return filtered.ifBlank { fallback }
    }

    fun buildDiscoveryAmCommand(
        mac: String,
        pidHex: String,
        modelName: String,
        productType: String,
        color: Int
    ): String? {
        val safeMac = sanitizeMac(mac) ?: return null
        val safePid = sanitizePidHex(pidHex) ?: return null
        val safeModel = sanitizeToken(modelName, "OPPO Enco X2")
        val safeType = sanitizeToken(productType, "T1")
        return "am start -n com.oplus.melody/com.oplus.melody.app.discovery.DiscoveryDialogActivity " +
            "--es route_path /app/discovery " +
            "--es device_mac_info $safeMac " +
            "--es product_id $safePid " +
            "--es device_name '$safeModel' " +
            "--es product_type $safeType " +
            "--es product_color $color " +
            "--es route_from onForwardConnectedPopup " +
            "--es route_value 0 " +
            "--es route_value2 false"
    }

    fun buildDetailAmCommand(
        mac: String,
        pidHex: String,
        modelName: String,
        color: Int
    ): String? {
        val safeMac = sanitizeMac(mac) ?: return null
        val safePid = sanitizePidHex(pidHex) ?: return null
        val safeModel = sanitizeToken(modelName, "OPPO Enco X2")
        return "am start -n com.oplus.melody/com.oplus.melody.ui.component.detail.DetailMainActivity " +
            "--es device_mac_info $safeMac " +
            "--es product_id $safePid " +
            "--es device_name '$safeModel' " +
            "--es product_color $color"
    }

    fun wakeMelodyMainProcess(): RootExecResult =
        execSu(
            "am startservice -n com.oplus.melody/com.oplus.melody.alive.service.MelodyAliveService",
            timeoutMs = 3_000L
        )

    fun restartAndWakeMelody(): RootExecResult =
        execSu(
            "am force-stop com.oplus.melody && sleep 0.4 && " +
                "am startservice -n com.oplus.melody/com.oplus.melody.alive.service.MelodyAliveService",
            timeoutMs = 5_000L
        )

    fun launchDiscoveryActivityAsRoot(
        mac: String,
        pidHex: String,
        modelName: String,
        productType: String,
        color: Int
    ): RootExecResult {
        val cmd = buildDiscoveryAmCommand(mac, pidHex, modelName, productType, color)
            ?: return RootExecResult(ok = false, exitCode = -2, output = "无效的耳机 MAC 或 PID")
        return execSu(cmd, timeoutMs = 3_000L)
    }

    fun launchDetailActivityAsRoot(
        mac: String,
        pidHex: String,
        modelName: String,
        color: Int
    ): RootExecResult {
        val cmd = buildDetailAmCommand(mac, pidHex, modelName, color)
            ?: return RootExecResult(ok = false, exitCode = -2, output = "无效的耳机 MAC 或 PID")
        return execSu(cmd, timeoutMs = 3_000L)
    }

    private fun execSu(command: String, timeoutMs: Long): RootExecResult {
        var proc: Process? = null
        return try {
            proc = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return RootExecResult(ok = false, exitCode = -1, output = "su 执行超时 (${timeoutMs}ms)")
            }
            val code = proc.exitValue()
            val out = runCatching {
                proc.inputStream.bufferedReader().use { it.readText() }.trim()
            }.getOrDefault("")
            RootExecResult(ok = code == 0, exitCode = code, output = out)
        } catch (t: Throwable) {
            runCatching { proc?.destroyForcibly() }
            RootExecResult(ok = false, exitCode = -1, output = t.message ?: "su 不可用")
        }
    }
}
