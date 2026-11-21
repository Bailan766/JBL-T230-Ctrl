package com.jblt230.ctrl.bridge

import android.annotation.SuppressLint
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Low-latency background GATT bridge for ColorOS LSPosed integration, Fluid Cloud, and AppWidget.
 *
 * Optimizations in v2.1.0:
 *  - Caches discovered `-LE` MAC (`snap.leMac`) to skip repeated 2-4s LE scans.
 *  - Control commands (`CMD_SET_NOISE_MODE`, `CMD_SET_EQ_PRESET`, `CMD_FIND_BUDS`) take a
 *    direct ~80ms fast path without redundant full refreshes or 2.5s `sync_app_status` waits.
 *  - Keeps the GATT link warm when `keepLinkAlive` is enabled, or for a 30s idle window
 *    when disabled so consecutive taps in ColorOS Melody execute instantaneously.
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
    private var idleReleaseJob: Job? = null

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
        startForeground(NOTIFICATION_ID, buildNotification("正在同步 JBL Tune 230NC 控制链路…"))
        val cmd = intent?.getStringExtra(BridgeContract.EXTRA_CMD) ?: BridgeContract.CMD_CONNECT_AND_SYNC
        val triggerPopup = intent?.getBooleanExtra(BridgeContract.EXTRA_TRIGGER_POPUP, false) ?: false
        val noiseStr = intent?.getStringExtra(BridgeContract.EXTRA_NOISE_MODE)
        val eqPreset = intent?.getStringExtra(BridgeContract.EXTRA_EQ_PRESET)
        val findEnabled = intent?.getBooleanExtra(BridgeContract.EXTRA_FIND_LEFT, false) ?: false

        idleReleaseJob?.cancel()
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
                    idleReleaseJob?.cancel()
                    idleReleaseJob = scope.launch {
                        delay(IDLE_HOLD_MS)
                        mutex.withLock {
                            runCatching { transport.release() }
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf(startId)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleCommand(
        cmd: String,
        triggerPopup: Boolean,
        noiseStr: String?,
        eqPreset: String?,
        findEnabled: Boolean
    ) {
        if (TakeoverBridge.foregroundLinkActive.get()) {
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
        var snap = prefs.loadSnapshot()
        val mac = classic?.address ?: snap.mac
        if (mac.isBlank()) return
        if (snap.mac != mac) {
            snap = snap.copy(mac = mac)
        }

        if (!ready) {
            val sysLevel = classic?.let { BatteryReflection.read(it).level }
            if (sysLevel != null && snap.leftBattery == null && snap.rightBattery == null) {
                snap = snap.copy(leftBattery = sysLevel, rightBattery = sysLevel)
            }
            TakeoverBridge.pushRawSnapshot(this, snap, controlReady = false, triggerPopup = triggerPopup)
            return
        }

        // Fast control path: execute single command & push incremental update immediately.
        val isControlCmd = cmd == BridgeContract.CMD_SET_NOISE_MODE ||
            cmd == BridgeContract.CMD_CYCLE_NOISE_MODE ||
            cmd == BridgeContract.CMD_SET_EQ_PRESET ||
            cmd == BridgeContract.CMD_FIND_BUDS

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

        if (isControlCmd) {
            TakeoverBridge.pushRawSnapshot(
                context = this,
                snap = snap,
                deviceName = BridgeContract.cleanDisplayName(classic?.name),
                controlReady = true,
                triggerPopup = triggerPopup,
                triggerMelodyCapsule = false
            )
            updateForegroundNotification(snap)
            return
        }

        // Full initial / manual sync path (zero sync_app_status dead wait).
        val infoFrame = queue.read("device_info_read")
        val info = infoFrame?.let { FrameCodec.parseDeviceInfo(it) }
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
            leftBattery = if (bat != null) bat.left else (snap.leftBattery ?: sysLevel),
            rightBattery = if (bat != null) bat.right else (snap.rightBattery ?: sysLevel),
            caseBattery = if (bat != null) bat.case else snap.caseBattery,
            leftCharging = bat?.leftCharging ?: snap.leftCharging,
            rightCharging = bat?.rightCharging ?: snap.rightCharging,
            caseCharging = bat?.caseCharging ?: snap.caseCharging,
            noiseMode = noise?.name ?: snap.noiseMode,
            eqPresetId = all?.let { EqCodec.presetByteToId(it.eqPresetByte) } ?: snap.eqPresetId,
            fwVersion = info?.firmwareVersion?.ifBlank { snap.fwVersion } ?: snap.fwVersion
        )

        val cfg = prefs.loadConfig()
        TakeoverBridge.pushRawSnapshot(
            context = this,
            snap = snap,
            deviceName = BridgeContract.cleanDisplayName(classic?.name),
            controlReady = true,
            triggerPopup = triggerPopup,
            triggerMelodyCapsule = cfg.melodySpoofEnabled && cfg.melodyAutoCapsule
        )
        updateForegroundNotification(snap)
    }

    private fun updateForegroundNotification(snap: CachedDeviceSnapshot) {
        val noise = runCatching { NoiseMode.valueOf(snap.noiseMode) }.getOrDefault(NoiseMode.OFF)
        val summary = "L ${BridgeContract.formatBatteryText(snap.leftBattery)} · " +
            "R ${BridgeContract.formatBatteryText(snap.rightBattery)} · " +
            "盒 ${BridgeContract.formatBatteryText(snap.caseBattery)} · " +
            BridgeContract.noiseModeLabel(noise)
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
        val snap = prefs.loadSnapshot()

        // 1. Try cached -LE MAC directly (~200ms vs 2-4s scan)
        val cachedLeMac = snap.leMac
        if (cachedLeMac.isNotBlank()) {
            val cachedLeDev = runCatching { adapter.getRemoteDevice(cachedLeMac) }.getOrNull()
            if (cachedLeDev != null && connectAndWait(cachedLeDev)) {
                return true
            }
            transport.release()
        }

        // 2. Scan for -LE advertising side and persist its MAC
        val le = scanner.scanForLeSide(adapter)
        if (le != null) {
            prefs.saveSnapshot(prefs.loadSnapshot().copy(leMac = le.address))
            if (connectAndWait(le)) return true
            transport.release()
        }

        // 3. Fallback to bonded Classic MAC
        val classic = scanner.findClassicDevice(adapter, null) ?: return false
        return connectAndWait(classic)
    }

    private suspend fun connectAndWait(device: android.bluetooth.BluetoothDevice): Boolean {
        if (!transport.connect(device)) return false
        val link = withTimeoutOrNull(table.scan.directConnectTimeoutMs) {
            transport.linkState.first { it is LinkState.Ready || it is LinkState.Failed }
        }
        return link is LinkState.Ready
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
            .setContentTitle("JBL Tune 230NC · 后台控制已就绪")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        idleReleaseJob?.cancel()
        runCatching { transport.release() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "JblBridgeService"
        private const val CHANNEL_ID = "jbl_coloros_bridge"
        private const val NOTIFICATION_ID = 2301
        private const val IDLE_HOLD_MS = 30_000L
        const val ACTION_INTERNAL_FOREGROUND_CMD = "com.jblt230.ctrl.ACTION_INTERNAL_FOREGROUND_CMD"
    }
}
