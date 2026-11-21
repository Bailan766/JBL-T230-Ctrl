package com.jblt230.ctrl.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.jblt230.ctrl.MainActivity
import com.jblt230.ctrl.R
import com.jblt230.ctrl.ble.NoiseMode

/**
 * Native ColorOS 16 / Android 16 Fluid Cloud (Live Updates / Promoted Ongoing) notifier.
 *
 * Strictly complies with Android 16 Live Update qualification constraints:
 *  - Declares `POST_PROMOTED_NOTIFICATIONS`
 *  - Sets `setOngoing(true)`, valid `setContentTitle(...)`, and no custom RemoteViews / colorization
 *  - Uses `setShortCriticalText` (<= 7 chars), `android.requestPromotedOngoing = true`,
 *    and `Notification.ProgressStyle` on API 36+.
 */
object FluidCloudNotifier {

    private const val CHANNEL_ID = "jbl_fluid_cloud"
    private const val NOTIFICATION_ID = 2302
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    @Volatile
    var lastHasPromotableCharacteristics: Boolean? = null
        private set

    data class FluidCloudStatus(
        val sdkSupported: Boolean,
        val notificationsEnabled: Boolean,
        val canPostPromoted: Boolean,
        val lastPromotable: Boolean?
    )

    fun statusSummary(context: Context): FluidCloudStatus {
        val appCtx = context.applicationContext
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationsEnabled = nm.areNotificationsEnabled()
        val sdkSupported = Build.VERSION.SDK_INT >= 36
        val canPostPromoted = if (sdkSupported) {
            runCatching { nm.canPostPromotedNotifications() }.getOrDefault(false)
        } else {
            false
        }
        return FluidCloudStatus(
            sdkSupported = sdkSupported,
            notificationsEnabled = notificationsEnabled,
            canPostPromoted = canPostPromoted,
            lastPromotable = lastHasPromotableCharacteristics
        )
    }

    fun canPostPromoted(context: Context): Boolean {
        val st = statusSummary(context)
        return st.notificationsEnabled && (!st.sdkSupported || st.canPostPromoted)
    }

    fun openPromotionSettings(context: Context) {
        val ctx = context.applicationContext
        val intent = if (Build.VERSION.SDK_INT >= 36) {
            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            }
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val fallback = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
            .onFailure { runCatching { ctx.startActivity(fallback) } }
    }

    fun postOrUpdate(
        context: Context,
        snap: CachedDeviceSnapshot,
        deviceName: String,
        controlReady: Boolean
    ) {
        val appCtx = context.applicationContext
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(nm)

        val noise = runCatching { NoiseMode.valueOf(snap.noiseMode) }.getOrDefault(NoiseMode.OFF)
        val chip = BridgeContract.formatFluidCloudChip(
            left = snap.leftBattery,
            right = snap.rightBattery,
            leftCharging = snap.leftCharging,
            rightCharging = snap.rightCharging
        )
        val avgBat = BridgeContract.averageBudBattery(snap.leftBattery, snap.rightBattery, snap.caseBattery)
        val cleanName = deviceName.ifBlank { "JBL Tune 230NC" }
        val statusSuffix = if (controlReady) BridgeContract.noiseModeLabel(noise) else "同步中"
        val leftStr = BridgeContract.formatBatteryText(snap.leftBattery, snap.leftCharging)
        val rightStr = BridgeContract.formatBatteryText(snap.rightBattery, snap.rightCharging)
        val caseStr = BridgeContract.formatBatteryText(snap.caseBattery, snap.caseCharging)

        val openAppIntent = Intent(appCtx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentPi = PendingIntent.getActivity(
            appCtx,
            200,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_earbuds)
            .setContentTitle("$cleanName · $statusSuffix")
            .setContentText("左耳 $leftStr  ·  右耳 $rightStr  ·  盒 $caseStr")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(contentPi)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "切换降噪",
                    actionPendingIntent(appCtx, 201, BridgeContract.CMD_CYCLE_NOISE_MODE)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    "刷新电量",
                    actionPendingIntent(appCtx, 202, BridgeContract.CMD_CONNECT_AND_SYNC)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    "收起",
                    actionPendingIntent(appCtx, 203, BridgeContract.CMD_DISMISS_FLUID_CLOUD)
                ).build()
            )

        if (Build.VERSION.SDK_INT >= 36) {
            val segColor = if (avgBat <= 20) 0xFFEF4444.toInt() else 0xFF10B981.toInt()
            builder
                .setShortCriticalText(chip)
                .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
                .setStyle(
                    Notification.ProgressStyle()
                        .setProgress(avgBat)
                        .setStyledByProgress(true)
                        .addProgressSegment(Notification.ProgressStyle.Segment(100).setColor(segColor))
                )
        } else {
            builder.setStyle(
                Notification.BigTextStyle()
                    .bigText("左耳 $leftStr  ·  右耳 $rightStr  ·  充电盒 $caseStr")
            )
        }

        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= 36) {
            lastHasPromotableCharacteristics = runCatching {
                notification.hasPromotableCharacteristics()
            }.getOrNull()
        }
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    fun dismiss(context: Context) {
        val nm = context.applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "流体云实时状态",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "耳机连接时在 ColorOS 流体云与状态栏胶囊展示左右耳电量与降噪切换"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun actionPendingIntent(context: Context, reqCode: Int, cmd: String): PendingIntent {
        val intent = Intent(context, JblBridgeReceiver::class.java).apply {
            action = BridgeContract.ACTION_REMOTE_COMMAND
            putExtra(BridgeContract.EXTRA_CMD, cmd)
            putExtra(BridgeContract.EXTRA_TRIGGER_POPUP, false)
        }
        return PendingIntent.getBroadcast(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
