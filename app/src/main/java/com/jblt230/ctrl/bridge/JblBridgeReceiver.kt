package com.jblt230.ctrl.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jblt230.ctrl.system.BatteryReflection

/**
 * Listens for JBL Tune 230NC Bluetooth connection broadcasts (`ACL_CONNECTED`,
 * `A2DP`, `HFP`) on unrooted devices: immediately displays cached/system battery in
 * [EarbudsPopupOverlay] and starts [JblBridgeService] to pull real L/R/Case telemetry.
 */
class JblBridgeReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val prefs = ColorOsPrefs(context)
        val cfg = prefs.loadConfig()
        if (!cfg.enabled) return

        val action = intent.action ?: return
        when (action) {
            BridgeContract.ACTION_REMOTE_COMMAND -> {
                val cmd = intent.getStringExtra(BridgeContract.EXTRA_CMD) ?: BridgeContract.CMD_CONNECT_AND_SYNC
                val triggerPopup = intent.getBooleanExtra(BridgeContract.EXTRA_TRIGGER_POPUP, false)
                val svcIntent = Intent(context, JblBridgeService::class.java).apply {
                    putExtra(BridgeContract.EXTRA_CMD, cmd)
                    putExtra(BridgeContract.EXTRA_TRIGGER_POPUP, triggerPopup)
                    intent.getStringExtra(BridgeContract.EXTRA_NOISE_MODE)?.let {
                        putExtra(BridgeContract.EXTRA_NOISE_MODE, it)
                    }
                    intent.getStringExtra(BridgeContract.EXTRA_EQ_PRESET)?.let {
                        putExtra(BridgeContract.EXTRA_EQ_PRESET, it)
                    }
                    putExtra(BridgeContract.EXTRA_FIND_LEFT, intent.getBooleanExtra(BridgeContract.EXTRA_FIND_LEFT, false))
                }
                startBridgeService(context, svcIntent)
            }

            BluetoothDevice.ACTION_ACL_CONNECTED,
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED" -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED)
                if (action != BluetoothDevice.ACTION_ACL_CONNECTED && state != BluetoothProfile.STATE_CONNECTED) {
                    return
                }
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return

                val name = runCatching { device.name }.getOrNull()
                if (!BridgeContract.isTargetJblName(name)) return

                val now = System.currentTimeMillis()
                if (now - lastConnectTriggerMs < CONNECT_COOLDOWN_MS) return
                lastConnectTriggerMs = now

                Log.i(TAG, "JBL Tune 230NC connected ($name / $action), launching standalone takeover sync")

                if (cfg.autoPopupOnConnect && !ColorOsBridge.foregroundLinkActive.get()) {
                    val sysLevel = runCatching { BatteryReflection.read(device).level }.getOrNull()
                    val cached = prefs.loadSnapshot().copy(mac = device.address)
                    val initial = if (sysLevel != null && cached.leftBattery == null) {
                        cached.copy(leftBattery = sysLevel, rightBattery = sysLevel)
                    } else {
                        cached
                    }
                    ColorOsBridge.pushRawSnapshot(
                        context = context,
                        snap = initial,
                        deviceName = name ?: "JBL TUNE230NC TWS",
                        controlReady = false,
                        triggerPopup = true
                    )
                }

                val svcIntent = Intent(context, JblBridgeService::class.java).apply {
                    putExtra(BridgeContract.EXTRA_CMD, BridgeContract.CMD_CONNECT_AND_SYNC)
                    putExtra(BridgeContract.EXTRA_MAC, device.address)
                    putExtra(BridgeContract.EXTRA_TRIGGER_POPUP, cfg.autoPopupOnConnect)
                }
                startBridgeService(context, svcIntent)
            }
        }
    }

    private fun startBridgeService(context: Context, svcIntent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to start JblBridgeService from background: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "JblBridgeReceiver"
        private const val CONNECT_COOLDOWN_MS = 10_000L
        @Volatile
        private var lastConnectTriggerMs = 0L
    }
}
