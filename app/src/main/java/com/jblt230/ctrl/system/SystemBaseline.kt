package com.jblt230.ctrl.system

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SystemInfo(
    val deviceName: String = "",
    val deviceAddress: String = "",
    val bonded: Boolean = false,
    val aclConnected: Boolean = false,
    val a2dpConnected: Boolean = false,
    val hfpConnected: Boolean = false,
    val musicVolume: Int = -1,
    val musicMaxVolume: Int = -1,
    val a2dpOn: Boolean = false,
    val outputDeviceTypes: List<String> = emptyList(),
    val systemBattery: Int? = null,
    val systemBatteryNote: String = "未读取",
    val metadataAvailable: Boolean = false
)

/**
 * Android standard-API baseline. This always works even when the private
 * protocol link cannot be established, and is explicitly labelled in the UI as
 * "system baseline" rather than pretending to be a firmware setting.
 */
@SuppressLint("MissingPermission")
class SystemBaseline(private val context: Context) {

    private val _info = MutableStateFlow(SystemInfo())
    val info: StateFlow<SystemInfo> = _info.asStateFlow()

    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()
    private val audio: AudioManager? get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var a2dpProxy: BluetoothA2dp? = null
    private var headsetProxy: BluetoothHeadset? = null
    private var targetMac: String = ""
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothAdapter.ACTION_STATE_CHANGED,
                ACTION_VOLUME_CHANGED,
                ACTION_BATTERY_LEVEL_CHANGED -> refresh()
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> refresh()
            }
        }
    }

    fun start(targetMac: String = "") {
        if (registered) return
        this.targetMac = targetMac
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(ACTION_VOLUME_CHANGED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            registered = true
        } catch (e: Throwable) {
            Log.w(TAG, "registerReceiver error: ${e.message}", e)
        }

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                @Suppress("UNCHECKED_CAST")
                when (profile) {
                    BluetoothProfile.A2DP -> a2dpProxy = proxy as? BluetoothA2dp
                    BluetoothProfile.HEADSET -> headsetProxy = proxy as? BluetoothHeadset
                }
                refresh()
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.A2DP) a2dpProxy = null
                if (profile == BluetoothProfile.HEADSET) headsetProxy = null
                refresh()
            }
        }
        runCatching { adapter?.getProfileProxy(context, listener, BluetoothProfile.A2DP) }
        runCatching { adapter?.getProfileProxy(context, listener, BluetoothProfile.HEADSET) }
        refresh()
    }

    fun setMusicVolume(value: Int) {
        audio?.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        refresh()
    }

    @SuppressLint("MissingPermission", "DiscouragedApi")
    fun refresh() {
        try {
            refreshInternal()
        } catch (e: Throwable) {
            Log.w(TAG, "refresh error: ${e.message}", e)
        }
    }

    @SuppressLint("MissingPermission", "DiscouragedApi")
    private fun refreshInternal() {
        val a = adapter
        val bonded = a?.bondedDevices.orEmpty()
        val device = bonded.firstOrNull { it.address.equals(targetMac, ignoreCase = true) }
            ?: bonded.firstOrNull { (it.name ?: "").contains("TUNE230", ignoreCase = true) }
            ?: bonded.firstOrNull { (it.name ?: "").contains("JBL", ignoreCase = true) }

        val a2dp = a2dpProxy?.connectedDevices.orEmpty()
        val hfp = headsetProxy?.connectedDevices.orEmpty()
        val am = audio
        val outputs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().mapNotNull { info ->
                if (info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) "蓝牙 A2DP" else null
            }.distinct()
        } else emptyList()

        val battery = device?.let { BatteryReflection.read(it) }

        // METADATA_UNTETHERED_* requires BLUETOOTH_PRIVILEGED / companion-device
        // privileges. A normal third-party app cannot read vendor-filled values,
        // so it is NOT used as a data source. We only record the attempt result.
        var metadataAvailable = false
        if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // getMetadata() and METADATA_UNTETHERED_* are hidden / privileged APIs
            // and absent from the public SDK stub. Reflect once, purely to record
            // whether they are reachable; the value is never used as a data source.
            metadataAvailable = runCatching {
                val key = BluetoothDevice::class.java
                    .getField("METADATA_UNTETHERED_LEFT_BATTERY").getInt(null)
                val method = BluetoothDevice::class.java
                    .getMethod("getMetadata", Int::class.javaPrimitiveType)
                method.invoke(device, key) != null
            }.getOrDefault(false)
        }

        _info.value = SystemInfo(
            deviceName = device?.name ?: "",
            deviceAddress = device?.address ?: "",
            bonded = device != null,
            aclConnected = a2dp.any { it.address == device?.address } ||
                hfp.any { it.address == device?.address },
            a2dpConnected = a2dp.isNotEmpty(),
            hfpConnected = hfp.isNotEmpty(),
            musicVolume = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1,
            musicMaxVolume = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1,
            a2dpOn = am?.isBluetoothA2dpOn == true,
            outputDeviceTypes = outputs,
            systemBattery = battery?.level,
            systemBatteryNote = battery?.reason ?: "未找到配对的 JBL 设备",
            metadataAvailable = metadataAvailable
        )
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
        a2dpProxy?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } }
        headsetProxy?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
        a2dpProxy = null
        headsetProxy = null
    }

    companion object {
        private const val TAG = "SystemBaseline"
        // Hidden framework constants, referenced by literal to stay compilable.
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val ACTION_BATTERY_LEVEL_CHANGED =
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    }
}
