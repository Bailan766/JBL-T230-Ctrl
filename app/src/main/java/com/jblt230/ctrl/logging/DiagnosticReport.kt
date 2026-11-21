package com.jblt230.ctrl.logging

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.jblt230.ctrl.BuildConfig
import com.jblt230.ctrl.ble.LinkState
import com.jblt230.ctrl.bridge.TakeoverConfig
import com.jblt230.ctrl.data.UiState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DiagStatus(val tag: String) {
    PASS("PASS"),
    WARN("WARN"),
    FAIL("FAIL"),
    SKIP("SKIP")
}

data class DiagStep(
    val key: String,
    val label: String,
    val status: DiagStatus,
    val durationMs: Long = 0L,
    val detail: String,
    val rawHex: String = ""
)

/**
 * Pure JVM formatter for full diagnostic and protocol capture exports, plus Android
 * FileProvider share / clipboard helpers. MAC addresses remain redacted via [ProtocolLog].
 */
object DiagnosticReportFormatter {

    data class EnvironmentInfo(
        val generatedAt: String,
        val appVersion: String,
        val deviceModel: String,
        val androidRelease: String,
        val sdkInt: Int,
        val permissionsGranted: Boolean,
        val overlayGranted: Boolean,
        val takeoverEnabled: Boolean,
        val autoPopupOnConnect: Boolean,
        val keepLinkAlive: Boolean,
        val pairedName: String,
        val a2dpConnected: Boolean,
        val hfpConnected: Boolean,
        val systemBattery: Int?,
        val linkSummary: String,
        val discoveredServices: String
    )

    fun format(
        env: EnvironmentInfo,
        steps: List<DiagStep>,
        logs: List<LogEntry>
    ): String {
        val sb = StringBuilder(4096)
        sb.appendLine("=== JBL T230 Ctrl 诊断报告 ===")
        sb.appendLine("生成时间: ${env.generatedAt}")
        sb.appendLine("应用版本: ${env.appVersion}")
        sb.appendLine("设备型号: ${env.deviceModel} (Android ${env.androidRelease}, SDK ${env.sdkInt})")
        sb.appendLine()
        sb.appendLine("--- 1. 权限、系统蓝牙与接管配置 ---")
        sb.appendLine("蓝牙权限: ${if (env.permissionsGranted) "已授予" else "缺失"}")
        sb.appendLine("悬浮窗权限: ${if (env.overlayGranted) "已授予" else "未授权"}")
        sb.appendLine("后台接管: enabled=${env.takeoverEnabled}, autoPopup=${env.autoPopupOnConnect}, keepLinkAlive=${env.keepLinkAlive}")
        sb.appendLine("目标耳机: ${env.pairedName.ifBlank { "--" }} | A2DP=${env.a2dpConnected}, HFP=${env.hfpConnected}, SysBat=${env.systemBattery ?: "--"}%")
        sb.appendLine("GATT 链路: ${env.linkSummary}")
        if (env.discoveredServices.isNotBlank()) {
            sb.appendLine("GATT 服务列表: ${env.discoveredServices}")
        }
        sb.appendLine()

        val passCount = steps.count { it.status == DiagStatus.PASS }
        val skipCount = steps.count { it.status == DiagStatus.SKIP }
        val warnCount = steps.count { it.status == DiagStatus.WARN }
        val failCount = steps.count { it.status == DiagStatus.FAIL }
        sb.appendLine("--- 2. 一键全量自检结果 (通过=$passCount, 跳过=$skipCount, 警告=$warnCount, 失败=$failCount) ---")
        if (steps.isEmpty()) {
            sb.appendLine("（本次尚未运行一键自检）")
        } else {
            for (step in steps) {
                val hexPart = if (step.rawHex.isNotBlank()) " | hex=[${step.rawHex}]" else ""
                sb.appendLine("[${step.status.tag}] (${step.durationMs}ms) ${step.label}: ${step.detail}$hexPart")
            }
        }
        sb.appendLine()

        sb.appendLine("--- 3. 脱敏蓝牙协议帧日志 (${logs.size} 条) ---")
        if (logs.isEmpty()) {
            sb.appendLine("（暂无帧记录）")
        } else {
            for (e in logs) {
                val hex = if (e.frameHex.isNotBlank()) " ${e.frameHex}" else ""
                val note = if (e.note.isNotBlank()) " // ${e.note}" else ""
                sb.appendLine("${e.time} ${e.direction}$hex$note")
            }
        }
        return sb.toString()
    }
}

object DiagnosticExporter {

    fun collectEnv(
        state: UiState,
        cfg: TakeoverConfig,
        overlayGranted: Boolean,
        discoveredServices: String
    ): DiagnosticReportFormatter.EnvironmentInfo {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val linkText = when (val l = state.link) {
            LinkState.Idle -> "Idle"
            is LinkState.Connecting -> "Connecting"
            is LinkState.Discovering -> "Discovering"
            is LinkState.Ready -> "Ready (MTU ${l.mtu})"
            is LinkState.Failed -> "Failed: ${l.reason}"
            LinkState.Disconnected -> "Disconnected"
        }
        return DiagnosticReportFormatter.EnvironmentInfo(
            generatedAt = now,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            permissionsGranted = state.permissionsGranted,
            overlayGranted = overlayGranted,
            takeoverEnabled = cfg.enabled,
            autoPopupOnConnect = cfg.autoPopupOnConnect,
            keepLinkAlive = cfg.keepLinkAlive,
            pairedName = state.system.deviceName,
            a2dpConnected = state.system.a2dpConnected,
            hfpConnected = state.system.hfpConnected,
            systemBattery = state.system.systemBattery,
            linkSummary = linkText,
            discoveredServices = discoveredServices
        )
    }

    fun buildFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "jbl_t230_diag_$ts.txt"
    }

    fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("JBL T230 诊断日志", text))
    }

    fun shareAsFile(context: Context, reportText: String): Boolean = runCatching {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        // Keep only recent cached log files to avoid cache bloat
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
        val file = File(dir, buildFileName())
        file.writeText(reportText, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "JBL T230 Ctrl 诊断日志")
            putExtra(Intent.EXTRA_TEXT, "JBL T230 Ctrl 协议与真机自检报告（见附件 .txt）")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "导出诊断日志").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        true
    }.getOrDefault(false)
}
