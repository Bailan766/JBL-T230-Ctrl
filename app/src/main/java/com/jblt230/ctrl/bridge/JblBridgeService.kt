package com.jblt230.ctrl.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.jblt230.ctrl.R
import com.jblt230.ctrl.ble.BleScanner
import com.jblt230.ctrl.ble.CommandGuard
import com.jblt230.ctrl.ble.CommandQueue
import com.jblt230.ctrl.ble.EqCodec
import com.jblt230.ctrl.ble.FrameCodec
import com.jblt230.ctrl.ble.GattTransport
import com.jblt230.ctrl.ble.LinkState
import com.jblt230.ctrl.ble.NoiseMode
import com.jblt230.ctrl.ble.WriteResult
import com.jblt230.ctrl.logging.ProtocolLog
import com.jblt230.ctrl.protocol.CommandTable
import com.jblt230.ctrl.system.BatteryReflection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background GATT bridge for ColorOS LSPosed spoofing.
 *
 * Connects on demand when the JBL earbuds connect or when the hooked ColorOS Melody
 * UI issues an ANC / EQ / Find command, syncs real battery/status back to Melody,
 * and releases the single BLE control link afterwards unless `keepLinkAlive` is enabled.
 */
class JblBridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private lateinit var table: CommandTable
    private lateinit var log: ProtocolLog
    private lateinit var guard: CommandGuard
    private lateinit var transport: GattTransport
    private lateinit var queue: CommandQueue
    private lateinit var scanner: BleScanner
    private lateinit var prefs: TakeoverPrefs

    override fun onCreate() {
        super.onCreate()
        table = CommandTable.load(this)
        log = ProtocolLog()
        guard = CommandGuard(table.blacklist)
        transport = GattTransport(this, table.gatt, guard, log)
        queue = CommandQueue(transport, table, guard, log)
        scanner = BleScanner(table.scan)
        prefs = TakeoverPrefs(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("正在同步 JBL T230NC 控制链路…"))
        val cmd = intent?.getStringExtra(BridgeContract.EXTRA_CMD) ?: BridgeContract.CMD_CONNECT_AND_SYNC
        val triggerPopup = intent?.getBooleanExtra(BridgeContract.EXTRA_TRIGGER_POPUP, false) ?: false
        val noiseStr = intent?.getStringExtra(BridgeContract.EXTRA_NOISE_MODE)
        val eqPreset = intent?.getStringExtra(BridgeContract.EXTRA_EQ_PRESET)
        val findEnabled = intent?.getBooleanExtra(BridgeContract.EXTRA_FIND_LEFT, false) ?: false

        scope.launch {
            try {
                mutex.withLock {
                    handleCommand(cmd, triggerPopup, noiseStr, eqPreset, findEnabled)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Bridge command $cmd failed: ${e.message}", e)
            } finally {
                val cfg = prefs.loadConfig()
                if (!cfg.keepLinkAlive) {
                    runCatching { transport.release() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun handleCommand(
        cmd: String,
        triggerPopup: Boolean,
        noiseStr: String?,
        eqPreset: String?,
        findEnabled: Boolean
    ) {
        if (TakeoverBridge.foregroundLinkActive.get()) {
            // Foreground AppViewModel owns the sole GATT link: relay remote commands via broadcast.
            if (cmd != BridgeContract.CMD_CONNECT_AND_SYNC) {
                sendBroadcast(Intent(ACTION_INTERNAL_FOREGROUND_CMD).apply {
                    setPackage(packageName)
                    putExtra(BridgeContract.EXTRA_CMD, cmd)
                    putExtra(BridgeContract.EXTRA_NOISE_MODE, noiseStr)
                    putExtra(BridgeContract.EXTRA_EQ_PRESET, eqPreset)
                    putExtra(BridgeContract.EXTRA_FIND_LEFT, findEnabled)
                })
            }
            return
        }

        val ready = ensureConnected()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val classic = scanner.findClassicDevice(adapter, null)
        val mac = classic?.address ?: prefs.loadSnapshot().mac
        if (mac.isBlank()) return

        var snap = prefs.loadSnapshot().copy(mac = mac)
        if (!ready) {
            val sysLevel = classic?.let { BatteryReflection.read(it).level }
            if (sysLevel != null && snap.leftBattery == null) {
                snap = snap.copy(leftBattery = sysLevel, rightBattery = sysLevel)
            }
            TakeoverBridge.pushRawSnapshot(this, snap, controlReady = false, triggerPopup = triggerPopup)
            return
        }

        when (cmd) {
            BridgeContract.CMD_SET_NOISE_MODE -> {
                val target = runCatching { NoiseMode.valueOf(noiseStr ?: "OFF") }.getOrDefault(NoiseMode.OFF)
                if (writeNoiseMode(target)) snap = snap.copy(noiseMode = target.name)
            }
            BridgeContract.CMD_CYCLE_NOISE_MODE -> {
                val current = runCatching { NoiseMode.valueOf(snap.noiseMode) }.getOrDefault(NoiseMode.OFF)
                val target = BridgeContract.nextQuickNoiseMode(current)
                if (writeNoiseMode(target)) snap = snap.copy(noiseMode = target.name)
            }
            BridgeContract.CMD_SET_EQ_PRESET -> {
                val targetPreset = eqPreset ?: "off"
                val idx = EqCodec.presetIdToByte(targetPreset) ?: 0
                val frame = EqCodec.buildSetEqPresetFrame(idx)
                val res = queue.writeFrameAndVerify(
                    frameHex = FrameCodec.hex(frame),
                    replyCmd = 0x00,
                    readbackName = "eq_preset_status_read",
                    readFilter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x34 }
                ) { f -> FrameCodec.parseEqPresetStatus(f) == idx }
                if (res is WriteResult.Verified) {
                    snap = snap.copy(eqPresetId = targetPreset)
                }
            }
            BridgeContract.CMD_FIND_BUDS -> {
                val frame = FrameCodec.buildFindMyBuds(left = findEnabled, right = findEnabled)
                queue.writeFrameAndVerify(
                    frameHex = FrameCodec.hex(frame),
                    replyCmd = 0x00,
                    readbackName = "find_buds_read"
                ) { f ->
                    val s = FrameCodec.parseFindMyBuds(f) ?: return@writeFrameAndVerify false
                    s.leftBeeping == findEnabled && s.rightBeeping == findEnabled
                }
            }
        }

        // Perform handshake + full status/battery refresh
        val infoFrame = queue.read("device_info_read")
        val info = infoFrame?.let { FrameCodec.parseDeviceInfo(it) }
        queue.read("sync_app_status")

        val batFrame = queue.read("battery_read")
        val bat = batFrame?.let { FrameCodec.parseBattery(it) }
        val statusFrame = queue.read("status_read_all") { f ->
            f.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x30
        }
        val all = statusFrame?.let { FrameCodec.parseAllStatus(it) }
        val ancFrame = queue.read("anc_read") { f ->
            f.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x12
        }
        val noise = ancFrame?.let { FrameCodec.parseNoiseMode(it) } ?: all?.noiseMode

        val sysLevel = classic?.let { BatteryReflection.read(it).level }
        snap = snap.copy(
            leftBattery = bat?.left ?: snap.leftBattery ?: sysLevel,
            rightBattery = bat?.right ?: snap.rightBattery ?: sysLevel,
            caseBattery = bat?.case ?: snap.caseBattery,
            leftCharging = bat?.leftCharging ?: snap.leftCharging,
            rightCharging = bat?.rightCharging ?: snap.rightCharging,
            caseCharging = bat?.caseCharging ?: snap.caseCharging,
            noiseMode = noise?.name ?: snap.noiseMode,
            eqPresetId = all?.let { EqCodec.presetByteToId(it.eqPresetByte) } ?: snap.eqPresetId,
            fwVersion = info?.firmwareVersion?.ifBlank { snap.fwVersion } ?: snap.fwVersion
        )

        TakeoverBridge.pushRawSnapshot(
            context = this,
            snap = snap,
            deviceName = classic?.name ?: "JBL TUNE230NC TWS",
            controlReady = true,
            triggerPopup = triggerPopup
        )
        val summary = "L ${BridgeContract.formatBatteryText(snap.leftBattery)} · " +
            "R ${BridgeContract.formatBatteryText(snap.rightBattery)} · " +
            "盒 ${BridgeContract.formatBatteryText(snap.caseBattery)} · " +
            BridgeContract.noiseModeLabel(noise ?: NoiseMode.OFF)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(summary))
    }

    private suspend fun writeNoiseMode(target: NoiseMode): Boolean {
        val cmdName = when (target) {
            NoiseMode.OFF -> "noise_all_off"
            NoiseMode.ANC -> "anc_on"
            NoiseMode.AMBIENT_AWARE -> "ambient_aware_on"
            NoiseMode.TALKTHRU -> "talkthru_on"
        }
        val ancFilter: (com.jblt230.ctrl.ble.Frame) -> Boolean = { f ->
            f.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x12
        }
        val res = queue.writeAndVerify(cmdName, readFilter = ancFilter) {
            FrameCodec.parseNoiseMode(it) == target
        }
        return res is WriteResult.Verified
    }

    private suspend fun ensureConnected(): Boolean {
        if (transport.linkState.value is LinkState.Ready) return true
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        val classic = scanner.findClassicDevice(adapter, null) ?: return false
        val le = scanner.scanForLeSide(adapter)
        if (le != null && connectAndWait(le)) return true
        transport.release()
        return connectAndWait(classic)
    }

    private suspend fun connectAndWait(device: android.bluetooth.BluetoothDevice): Boolean {
        if (!transport.connect(device)) return false
        val state = withTimeoutOrNull(table.scan.directConnectTimeoutMs) {
            transport.linkState.first { it is LinkState.Ready || it is LinkState.Failed }
        }
        return state is LinkState.Ready
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "JBL T230 后台接管", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("JBL T230 Ctrl · 独立接管中")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        runCatching { transport.release() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "JblBridgeService"
        private const val CHANNEL_ID = "jbl_coloros_bridge"
        private const val NOTIFICATION_ID = 2301
        const val ACTION_INTERNAL_FOREGROUND_CMD = "com.jblt230.ctrl.ACTION_INTERNAL_FOREGROUND_CMD"
    }
}
