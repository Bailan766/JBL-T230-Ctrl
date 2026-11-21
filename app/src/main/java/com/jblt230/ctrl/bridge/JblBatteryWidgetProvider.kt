package com.jblt230.ctrl.bridge

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.jblt230.ctrl.MainActivity
import com.jblt230.ctrl.R
import com.jblt230.ctrl.ble.NoiseMode

/**
 * Home-screen AppWidget showing cached/live L/R/Case battery and quick Noise Control / Sync actions.
 * Works as a reliable user-triggered fallback even when OEM battery savers freeze background broadcasts.
 */
class JblBatteryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildRemoteViews(context))
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val appCtx = context.applicationContext
            val manager = AppWidgetManager.getInstance(appCtx) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(appCtx, JblBatteryWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildRemoteViews(appCtx)
            for (id in ids) {
                manager.updateAppWidget(id, views)
            }
        }

        private fun buildRemoteViews(context: Context): RemoteViews {
            val snap = TakeoverPrefs(context).loadSnapshot()
            val noise = runCatching { NoiseMode.valueOf(snap.noiseMode) }.getOrDefault(NoiseMode.OFF)

            val views = RemoteViews(context.packageName, R.layout.widget_jbl_battery)
            val fw = snap.fwVersion.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
            views.setTextViewText(R.id.widget_title, "JBL TUNE230NC TWS$fw")
            views.setTextViewText(R.id.widget_noise_btn, "降噪：${BridgeContract.noiseModeLabel(noise)}")
            views.setTextViewText(
                R.id.widget_bat_left,
                BridgeContract.formatBatteryText(snap.leftBattery, snap.leftCharging)
            )
            views.setTextViewText(
                R.id.widget_bat_right,
                BridgeContract.formatBatteryText(snap.rightBattery, snap.rightCharging)
            )
            views.setTextViewText(
                R.id.widget_bat_case,
                BridgeContract.formatBatteryText(snap.caseBattery, snap.caseCharging)
            )

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context,
                    100,
                    openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            views.setOnClickPendingIntent(
                R.id.widget_noise_btn,
                commandPendingIntent(context, 101, BridgeContract.CMD_CYCLE_NOISE_MODE)
            )
            views.setOnClickPendingIntent(
                R.id.widget_sync_btn,
                commandPendingIntent(context, 102, BridgeContract.CMD_CONNECT_AND_SYNC)
            )
            return views
        }

        private fun commandPendingIntent(context: Context, reqCode: Int, cmd: String): PendingIntent {
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
}
