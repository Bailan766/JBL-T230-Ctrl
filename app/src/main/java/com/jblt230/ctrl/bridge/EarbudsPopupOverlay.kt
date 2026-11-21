package com.jblt230.ctrl.bridge

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.jblt230.ctrl.MainActivity
import com.jblt230.ctrl.ble.NoiseMode

/**
 * Root-free bottom popup overlay (`SYSTEM_ALERT_WINDOW`) with adaptive light/dark theme,
 * proper DPI scaling, swipe-down dismissal, and live L/R/Case + ANC controls.
 */
object EarbudsPopupOverlay {

    private const val AUTO_DISMISS_MS = 6_500L
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootContainer: FrameLayout? = null
    private var wm: WindowManager? = null
    private var currentSnapshot: CachedDeviceSnapshot? = null
    private var currentControlReady = false

    private val dismissRunnable = Runnable { animateAndDismiss() }

    private data class Palette(
        val cardBg: Int,
        val cardStroke: Int,
        val tileBg: Int,
        val handleBg: Int,
        val titleText: Int,
        val mutedText: Int,
        val accentText: Int,
        val activeChipBg: Int,
        val activeChipText: Int
    )

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

    private fun animateAndDismiss() {
        val view = rootContainer ?: return
        mainHandler.removeCallbacks(dismissRunnable)
        view.animate()
            .alpha(0f)
            .translationY(dp(view.context, 36).toFloat())
            .setDuration(180L)
            .withEndAction { dismiss() }
            .start()
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
        val palette = resolvePalette(ctx)
        val card = buildPopupCard(ctx, palette, snap, deviceName, controlReady, autoDismiss)
        attachSwipeToDismiss(ctx, card, autoDismiss)

        val existing = rootContainer
        if (existing != null) {
            existing.removeAllViews()
            existing.addView(card)
            runCatching { windowManager.updateViewLayout(existing, existing.layoutParams) }
        } else {
            val container = FrameLayout(ctx).apply {
                setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 24))
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
            container.translationY = dp(ctx, 32).toFloat()
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

    private fun attachSwipeToDismiss(ctx: Context, card: View, autoDismiss: Boolean) {
        val thresholdPx = dp(ctx, 44).toFloat()
        var downRawY = 0f
        var dragging = false
        card.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    dragging = false
                    mainHandler.removeCallbacks(dismissRunnable)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - downRawY).coerceAtLeast(0f)
                    if (dy > dp(ctx, 8)) {
                        dragging = true
                        v.translationY = dy
                        v.alpha = (1f - dy / dp(ctx, 160)).coerceIn(0.35f, 1f)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = (event.rawY - downRawY).coerceAtLeast(0f)
                    if (dragging && dy > thresholdPx) {
                        animateAndDismiss()
                        true
                    } else {
                        v.animate().translationY(0f).alpha(1f).setDuration(160L).start()
                        if (autoDismiss) mainHandler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
                        dragging
                    }
                }
                else -> false
            }
        }
    }

    private fun buildPopupCard(
        ctx: Context,
        palette: Palette,
        snap: CachedDeviceSnapshot,
        deviceName: String,
        controlReady: Boolean,
        autoDismiss: Boolean
    ): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(
                ctx = ctx,
                fillColor = palette.cardBg,
                cornerDp = 24f,
                strokeColor = palette.cardStroke,
                strokeDp = 1f
            )
            setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 16))
        }

        // Drag handle pill for intuitive swipe-down dismissal
        card.addView(View(ctx).apply {
            background = roundedBg(ctx, palette.handleBg, cornerDp = 3f)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(ctx, 10)
            }
        })

        // Header row: title + status + close button
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
            setTextColor(palette.titleText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        })
        titleCol.addView(TextView(ctx).apply {
            val fw = snap.fwVersion.takeIf { it.isNotBlank() }?.let { " · FW $it" } ?: ""
            text = if (controlReady) "GATT 已就绪$fw" else "正在同步实时电量…$fw"
            setTextColor(if (controlReady) palette.accentText else palette.mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        header.addView(titleCol)
        header.addView(TextView(ctx).apply {
            text = "✕"
            setTextColor(palette.mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4))
            setOnClickListener { animateAndDismiss() }
        })
        card.addView(header)

        // 3-column battery strip: Left / Right / Case
        val batRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 14), 0, dp(ctx, 14))
        }
        batRow.addView(buildBatteryTile(ctx, palette, "左耳 L", snap.leftBattery, snap.leftCharging))
        batRow.addView(spaceX(ctx, 8))
        batRow.addView(buildBatteryTile(ctx, palette, "右耳 R", snap.rightBattery, snap.rightCharging))
        batRow.addView(spaceX(ctx, 8))
        batRow.addView(buildBatteryTile(ctx, palette, "充电盒", snap.caseBattery, snap.caseCharging))
        card.addView(batRow)

        // Quick noise control row
        val activeNoise = runCatching { NoiseMode.valueOf(snap.noiseMode) }.getOrDefault(NoiseMode.OFF)
        val noiseRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val modes = listOf(
            NoiseMode.ANC to "降噪 ANC",
            NoiseMode.AMBIENT_AWARE to "环境感知",
            NoiseMode.OFF to "关闭"
        )
        modes.forEachIndexed { idx, (mode, label) ->
            if (idx > 0) noiseRow.addView(spaceX(ctx, 8))
            noiseRow.addView(buildActionChip(ctx, palette, label, activeNoise == mode) {
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

        // Footer actions
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 10), 0, 0)
        }
        footer.addView(buildActionChip(ctx, palette, "响铃找耳机", selected = false) {
            mainHandler.removeCallbacks(dismissRunnable)
            sendServiceCommand(ctx, BridgeContract.CMD_FIND_BUDS, findEnable = true)
        })
        footer.addView(spaceX(ctx, 8))
        footer.addView(buildActionChip(ctx, palette, "打开主控台", selected = true) {
            animateAndDismiss()
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
        palette: Palette,
        label: String,
        level: Int?,
        charging: Boolean
    ): LinearLayout {
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            background = roundedBg(ctx, fillColor = palette.tileBg, cornerDp = 16f)
            setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
        }
        tile.addView(TextView(ctx).apply {
            text = label
            setTextColor(palette.mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        tile.addView(TextView(ctx).apply {
            text = BridgeContract.formatBatteryText(level, charging)
            setTextColor(
                when {
                    level != null && level <= 20 -> Color.parseColor("#E55353")
                    charging -> palette.accentText
                    else -> palette.titleText
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
        palette: Palette,
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ): TextView = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        background = roundedBg(
            ctx = ctx,
            fillColor = if (selected) palette.activeChipBg else palette.tileBg,
            cornerDp = 12f
        )
        setTextColor(if (selected) palette.activeChipText else palette.titleText)
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

    private fun resolvePalette(ctx: Context): Palette {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) {
            Palette(
                cardBg = Color.parseColor("#FA121821"),
                cardStroke = Color.parseColor("#2A3A4E"),
                tileBg = Color.parseColor("#18FFFFFF"),
                handleBg = Color.parseColor("#44FFFFFF"),
                titleText = Color.WHITE,
                mutedText = Color.parseColor("#8FA3B8"),
                accentText = Color.parseColor("#2CE09B"),
                activeChipBg = Color.parseColor("#FF5722"),
                activeChipText = Color.WHITE
            )
        } else {
            Palette(
                cardBg = Color.parseColor("#FCF8FAFC"),
                cardStroke = Color.parseColor("#22151C26"),
                tileBg = Color.parseColor("#10151C26"),
                handleBg = Color.parseColor("#33151C26"),
                titleText = Color.parseColor("#101A28"),
                mutedText = Color.parseColor("#556677"),
                accentText = Color.parseColor("#0F7B58"),
                activeChipBg = Color.parseColor("#FF5722"),
                activeChipText = Color.WHITE
            )
        }
    }

    private fun roundedBg(
        ctx: Context,
        fillColor: Int,
        cornerDp: Float,
        strokeColor: Int? = null,
        strokeDp: Float = 0f
    ): GradientDrawable {
        val density = ctx.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerDp * density
            setColor(fillColor)
            if (strokeColor != null && strokeDp > 0f) {
                setStroke((strokeDp * density).toInt().coerceAtLeast(1), strokeColor)
            }
        }
    }

    private fun spaceX(ctx: Context, dpVal: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(ctx, dpVal), 1)
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}
