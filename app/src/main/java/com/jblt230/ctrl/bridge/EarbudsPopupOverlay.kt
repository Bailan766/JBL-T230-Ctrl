package com.jblt230.ctrl.bridge

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.jblt230.ctrl.MainActivity
import com.jblt230.ctrl.ble.NoiseMode

/**
 * Root-free bottom popup overlay (uses Android's `SYSTEM_ALERT_WINDOW`) that appears
 * when JBL Tune 230NC earbuds connect, showing live L/R/Case battery and quick ANC controls.
 */
object EarbudsPopupOverlay {

    private const val AUTO_DISMISS_MS = 6_500L
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootContainer: FrameLayout? = null
    private var wm: WindowManager? = null
    private var currentSnapshot: CachedDeviceSnapshot? = null
    private var currentControlReady = false

    private val dismissRunnable = Runnable { dismiss() }

    fun isShowing(): Boolean = rootContainer != null

    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context.applicationContext)

    fun showOrUpdate(
        context: Context,
        snap: CachedDeviceSnapshot,
        deviceName: String,
        controlReady: Boolean,
        autoDismiss: Boolean
    ) {
        val appCtx = context.applicationContext
        if (!canDrawOverlays(appCtx)) return
        mainHandler.post {
            showOnMain(appCtx, snap, deviceName, controlReady, autoDismiss)
        }
    }

    fun dismiss() {
        mainHandler.removeCallbacks(dismissRunnable)
        val view = rootContainer ?: return
        runCatching { wm?.removeViewImmediate(view) }
        rootContainer = null
    }

    private fun showOnMain(
        ctx: Context,
        snap: CachedDeviceSnapshot,
        deviceName: String,
        controlReady: Boolean,
        autoDismiss: Boolean
    ) {
        currentSnapshot = snap
        currentControlReady = controlReady
        mainHandler.removeCallbacks(dismissRunnable)

        val windowManager = wm ?: (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
            wm = it
        }

        val card = buildPopupCard(ctx, snap, deviceName, controlReady, autoDismiss)
        val existing = rootContainer
        if (existing != null) {
            existing.removeAllViews()
            existing.addView(card)
            runCatching { windowManager.updateViewLayout(existing, existing.layoutParams) }
        } else {
            val container = FrameLayout(ctx).apply {
                setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 20))
                addView(card)
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            container.alpha = 0f
            container.translationY = dp(ctx, 28).toFloat()
            runCatching {
                windowManager.addView(container, lp)
                rootContainer = container
                container.animate().alpha(1f).translationY(0f).setDuration(220L).start()
            }
        }

        if (autoDismiss) {
            mainHandler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
        }
    }

    private fun buildPopupCard(
        ctx: Context,
        snap: CachedDeviceSnapshot,
        deviceName: String,
        controlReady: Boolean,
        autoDismiss: Boolean
    ): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(
                fillColor = Color.parseColor("#FA151C26"),
                cornerDp = 24f,
                strokeColor = Color.parseColor("#33FFFFFF"),
                strokeDp = 1f
            )
            setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 16))
        }

        // Header row: title + firmware/status badge + close button
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(ctx).apply {
            text = deviceName.ifBlank { "JBL TUNE230NC TWS" }
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        })
        titleCol.addView(TextView(ctx).apply {
            val fw = snap.fwVersion.takeIf { it.isNotBlank() }?.let { " · FW $it" } ?: ""
            text = if (controlReady) "GATT 已就绪$fw" else "正在同步实时电量…$fw"
            setTextColor(Color.parseColor(if (controlReady) "#7FE0C4" else "#9AB0C4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        header.addView(titleCol)
        header.addView(TextView(ctx).apply {
            text = "✕"
            setTextColor(Color.parseColor("#B0C0D0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4))
            setOnClickListener { dismiss() }
        })
        card.addView(header)

        // 3-column battery strip: Left / Right / Case
        val batRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 14), 0, dp(ctx, 14))
        }
        batRow.addView(buildBatteryTile(ctx, "左耳 L", snap.leftBattery, snap.leftCharging))
        batRow.addView(spaceX(ctx, 8))
        batRow.addView(buildBatteryTile(ctx, "右耳 R", snap.rightBattery, snap.rightCharging))
        batRow.addView(spaceX(ctx, 8))
        batRow.addView(buildBatteryTile(ctx, "充电盒", snap.caseBattery, snap.caseCharging))
        card.addView(batRow)

        // Quick noise control row: ANC / Ambient Aware / Off
        val activeNoise = runCatching { NoiseMode.valueOf(snap.noiseMode) }.getOrDefault(NoiseMode.OFF)
        val noiseRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val modes = listOf(
            NoiseMode.ANC to "降噪 ANC",
            NoiseMode.AMBIENT_AWARE to "环境感知",
            NoiseMode.OFF to "关闭"
        )
        modes.forEachIndexed { idx, (mode, label) ->
            if (idx > 0) noiseRow.addView(spaceX(ctx, 8))
            noiseRow.addView(buildActionChip(ctx, label, activeNoise == mode) {
                if (autoDismiss) {
                    mainHandler.removeCallbacks(dismissRunnable)
                    mainHandler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
                }
                val nextSnap = (currentSnapshot ?: snap).copy(noiseMode = mode.name)
                showOrUpdate(ctx, nextSnap, deviceName, currentControlReady, autoDismiss)
                sendServiceCommand(ctx, BridgeContract.CMD_SET_NOISE_MODE, mode.name)
            })
        }
        card.addView(noiseRow)

        // Footer actions: Find Buds + Open Console
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 10), 0, 0)
        }
        footer.addView(buildActionChip(ctx, "响铃找耳机", selected = false) {
            mainHandler.removeCallbacks(dismissRunnable)
            sendServiceCommand(ctx, BridgeContract.CMD_FIND_BUDS, findEnable = true)
        })
        footer.addView(spaceX(ctx, 8))
        footer.addView(buildActionChip(ctx, "打开主控台", selected = true) {
            dismiss()
            val launch = Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            runCatching { ctx.startActivity(launch) }
        })
        card.addView(footer)

        return card
    }

    private fun buildBatteryTile(
        ctx: Context,
        label: String,
        level: Int?,
        charging: Boolean
    ): LinearLayout {
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            background = roundedBg(
                fillColor = Color.parseColor("#1AFFFFFF"),
                cornerDp = 16f
            )
            setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
        }
        tile.addView(TextView(ctx).apply {
            text = label
            setTextColor(Color.parseColor("#9AB0C4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        tile.addView(TextView(ctx).apply {
            text = BridgeContract.formatBatteryText(level, charging)
            setTextColor(
                when {
                    level != null && level <= 20 -> Color.parseColor("#FF7A7A")
                    charging -> Color.parseColor("#7FE0C4")
                    else -> Color.WHITE
                }
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 6))
        })
        tile.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = level?.coerceIn(0, 100) ?: 0
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 4))
        })
        return tile
    }

    private fun buildActionChip(
        ctx: Context,
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ): TextView = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        background = roundedBg(
            fillColor = Color.parseColor(if (selected) "#1C7C7A" else "#1FFFFFFF"),
            cornerDp = 12f
        )
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setPadding(dp(ctx, 8), dp(ctx, 9), dp(ctx, 8), dp(ctx, 9))
        setOnClickListener { onClick() }
    }

    private fun sendServiceCommand(
        ctx: Context,
        cmd: String,
        noiseMode: String? = null,
        findEnable: Boolean = false
    ) {
        val intent = Intent(ctx, JblBridgeService::class.java).apply {
            putExtra(BridgeContract.EXTRA_CMD, cmd)
            if (noiseMode != null) putExtra(BridgeContract.EXTRA_NOISE_MODE, noiseMode)
            putExtra(BridgeContract.EXTRA_FIND_LEFT, findEnable)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    private fun roundedBg(
        fillColor: Int,
        cornerDp: Float,
        strokeColor: Int? = null,
        strokeDp: Float = 0f
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = cornerDp * 2.5f
        setColor(fillColor)
        if (strokeColor != null && strokeDp > 0f) {
            setStroke((strokeDp * 2.5f).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    private fun spaceX(ctx: Context, dpVal: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(ctx, dpVal), 1)
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}
