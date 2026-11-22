package com.jblt230.ctrl.data

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jblt230.ctrl.ble.BleScanner
import com.jblt230.ctrl.ble.CommandGuard
import com.jblt230.ctrl.ble.CommandQueue
import com.jblt230.ctrl.ble.EqCodec
import com.jblt230.ctrl.ble.FindMyBudsState
import com.jblt230.ctrl.ble.Frame
import com.jblt230.ctrl.ble.FrameCodec
import com.jblt230.ctrl.ble.GattTransport
import com.jblt230.ctrl.ble.GestureFunctionGroup
import com.jblt230.ctrl.ble.LinkState
import com.jblt230.ctrl.ble.NoiseMode
import com.jblt230.ctrl.ble.SmartAudioMode
import com.jblt230.ctrl.ble.WriteResult
import com.jblt230.ctrl.bridge.BridgeContract
import com.jblt230.ctrl.bridge.EarbudsPopupOverlay
import com.jblt230.ctrl.bridge.FluidCloudNotifier
import com.jblt230.ctrl.bridge.JblBridgeService
import com.jblt230.ctrl.bridge.RootMelodyWake
import com.jblt230.ctrl.bridge.TakeoverBridge
import com.jblt230.ctrl.bridge.TakeoverConfig
import com.jblt230.ctrl.bridge.TakeoverPrefs
import com.jblt230.ctrl.logging.DiagStatus
import com.jblt230.ctrl.logging.DiagStep
import com.jblt230.ctrl.logging.DiagnosticExporter
import com.jblt230.ctrl.logging.DiagnosticReportFormatter
import com.jblt230.ctrl.logging.ProtocolLog
import com.jblt230.ctrl.protocol.CommandTable
import com.jblt230.ctrl.protocol.Confidence
import com.jblt230.ctrl.system.SystemBaseline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class EqPreset(
    val id: String,
    val label: String,
    val category: Int,
    val cmdIndex: Int,
    val gains: List<Float>
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val table = CommandTable.load(app)
    private val log = ProtocolLog()
    private val guard = CommandGuard(table.blacklist)
    private val transport = GattTransport(app, table.gatt, guard, log)
    private val queue = CommandQueue(transport, table, guard, log)
    private val scanner = BleScanner(table.scan)
    private val system = SystemBaseline(app)
    private val eqStore = EqPresetStore(app)
    private val takeoverPrefs = TakeoverPrefs(app)

    private val _takeoverConfig = MutableStateFlow(takeoverPrefs.loadConfig())
    val takeoverConfig: StateFlow<TakeoverConfig> = _takeoverConfig

    private val _overlayGranted = MutableStateFlow(EarbudsPopupOverlay.canDrawOverlays(app))
    val overlayGranted: StateFlow<Boolean> = _overlayGranted

    private val _fluidCloudReady = MutableStateFlow(FluidCloudNotifier.canPostPromoted(app))
    val fluidCloudReady: StateFlow<Boolean> = _fluidCloudReady

    val melodyHookAlive: StateFlow<Boolean> = TakeoverBridge.melodyHookAlive
    val melodyHookStatus: StateFlow<String> = TakeoverBridge.melodyHookStatus

    private val _rootBusy = MutableStateFlow(false)
    val rootBusy: StateFlow<Boolean> = _rootBusy

    private val _diagSteps = MutableStateFlow<List<DiagStep>>(emptyList())
    val diagSteps: StateFlow<List<DiagStep>> = _diagSteps

    private val _diagRunning = MutableStateFlow(false)
    val diagRunning: StateFlow<Boolean> = _diagRunning

    private val _diagProgress = MutableStateFlow("")
    val diagProgress: StateFlow<String> = _diagProgress

    private var bridgeRelayRegistered = false
    private val bridgeRelayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != JblBridgeService.ACTION_INTERNAL_FOREGROUND_CMD) return
            when (intent.getStringExtra(BridgeContract.EXTRA_CMD)) {
                BridgeContract.CMD_SET_NOISE_MODE -> {
                    val mode = runCatching {
                        NoiseMode.valueOf(intent.getStringExtra(BridgeContract.EXTRA_NOISE_MODE) ?: "OFF")
                    }.getOrDefault(NoiseMode.OFF)
                    setNoiseMode(mode)
                }
                BridgeContract.CMD_CYCLE_NOISE_MODE -> {
                    setNoiseMode(BridgeContract.nextQuickNoiseMode(state.value.noiseMode))
                }
                BridgeContract.CMD_SET_EQ_PRESET -> {
                    val presetId = intent.getStringExtra(BridgeContract.EXTRA_EQ_PRESET) ?: "off"
                    val preset = eqPresets.firstOrNull { it.id == presetId }
                    if (preset != null) setEqPreset(preset)
                }
                BridgeContract.CMD_FIND_BUDS -> {
                    val findLeft = intent.getBooleanExtra(BridgeContract.EXTRA_FIND_LEFT, false)
                    val findRight = if (intent.hasExtra(BridgeContract.EXTRA_FIND_RIGHT)) {
                        intent.getBooleanExtra(BridgeContract.EXTRA_FIND_RIGHT, false)
                    } else {
                        findLeft
                    }
                    if (findLeft || findRight) {
                        base.update { it.copy(findBudsPendingLeft = findLeft, findBudsPendingRight = findRight) }
                        confirmFindMyBuds()
                    } else {
                        stopFindMyBuds()
                    }
                }
            }
        }
    }

    val logs: StateFlow<List<com.jblt230.ctrl.logging.LogEntry>> = log.entries
    val confidence: Map<String, Confidence> = table.commands.associate { it.name to it.confidence }
    val eqPresets: List<EqPreset> = loadEqPresets()
    val bandHz: List<Int> = loadBands()
    val blacklist: Set<Int> = table.blacklist
    val deviceLabel: String = table.deviceName
    val commandCount: Int = table.commands.size

    private var findBudsAutoStopJob: Job? = null
    private var refreshJob: Job? = null
    private var connectJob: Job? = null
    private var autoConnectedOnce = false

    private val base = MutableStateFlow(
        UiState(
            eqGains = eqPresets.firstOrNull { it.id == "off" }?.gains ?: List(10) { 0f },
            confidence = confidence
        )
    )

    val state: StateFlow<UiState> = combine(base, transport.linkState, system.info) { s, link, sys ->
        s.copy(link = link, system = sys)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, base.value)

    init {
        val saved = eqStore.load(base.value.eqGains)
        base.update {
            it.copy(
                eqPresetId = saved.presetId,
                eqGains = saved.gains,
                message = ""
            )
        }
        system.start()
        registerBridgeRelay()
        viewModelScope.launch {
            state.distinctUntilChanged { a, b ->
                a.link::class == b.link::class &&
                    a.battery == b.battery &&
                    a.noiseMode == b.noiseMode &&
                    a.eqPresetId == b.eqPresetId &&
                    a.system.deviceAddress == b.system.deviceAddress &&
                    a.system.systemBattery == b.system.systemBattery &&
                    a.deviceInfo?.firmwareVersion == b.deviceInfo?.firmwareVersion
            }.collect { s ->
                TakeoverBridge.pushUiState(getApplication(), s, triggerPopup = false)
            }
        }
    }

    private fun registerBridgeRelay() {
        if (bridgeRelayRegistered) return
        val appCtx = getApplication<Application>()
        val filter = IntentFilter(JblBridgeService.ACTION_INTERNAL_FOREGROUND_CMD)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(bridgeRelayReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appCtx.registerReceiver(bridgeRelayReceiver, filter)
            }
            bridgeRelayRegistered = true
        }
    }

    fun setPermissions(granted: Boolean) {
        base.update { it.copy(permissionsGranted = granted) }
    }

    fun autoConnectIfReady() {
        if (autoConnectedOnce || !state.value.permissionsGranted) return
        val link = state.value.link
        if (link is LinkState.Ready || link is LinkState.Connecting || link is LinkState.Discovering) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val classic = runCatching { scanner.findClassicDevice(adapter, null) }.getOrNull() ?: return
        system.start(classic.address)
        autoConnectedOnce = true
        connect()
    }

    fun connect() {
        if (!state.value.permissionsGranted) {
            base.update { it.copy(message = "缺少附近设备权限，无法连接耳机") }
            return
        }
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            try {
                base.update { it.copy(message = "正在查找已配对的 JBL TUNE230...") }
                val adapter = BluetoothAdapter.getDefaultAdapter()
                val classic = scanner.findClassicDevice(adapter, null)
                if (classic == null) {
                    base.update {
                        it.copy(message = "未找到已配对的 JBL TUNE230。请先在系统蓝牙设置里配对耳机。")
                    }
                    return@launch
                }
                system.start(classic.address)
                TakeoverBridge.foregroundLinkActive.set(true)
                runCatching {
                    val appCtx = getApplication<Application>()
                    appCtx.stopService(Intent(appCtx, JblBridgeService::class.java))
                }
                delay(250L)

                var ready: LinkState.Ready? = null
                val cachedLeMac = takeoverPrefs.loadSnapshot().leMac
                if (cachedLeMac.isNotBlank()) {
                    base.update { it.copy(message = "快速重连已知 JBL-LE 控制通道...") }
                    val cachedDev = runCatching { adapter.getRemoteDevice(cachedLeMac) }.getOrNull()
                    if (cachedDev != null) {
                        ready = connectAndWait(cachedDev)
                    }
                    if (ready == null) {
                        transport.release()
                        val snap = takeoverPrefs.loadSnapshot()
                        takeoverPrefs.saveSnapshot(snap.copy(leMac = ""))
                    }
                }
                if (ready == null) {
                    base.update { it.copy(message = "扫描 JBL TUNE230NC TWS-LE 控制侧...") }
                    val le = scanner.scanForLeSide(adapter)
                    if (le != null) {
                        val snap = takeoverPrefs.loadSnapshot()
                        takeoverPrefs.saveSnapshot(snap.copy(mac = classic.address, leMac = le.address))
                        ready = connectAndWait(le)
                    }
                }
                if (ready == null) {
                    transport.release()
                    base.update { it.copy(message = "LE 扫描未命中，回退直连 classic 地址...") }
                    ready = connectAndWait(classic)
                }
                if (ready == null) {
                    TakeoverBridge.foregroundLinkActive.set(false)
                    base.update {
                        it.copy(message = "未能建立控制链路。系统层状态仍可用；固件级功能保持未验证。")
                    }
                    return@launch
                }
                base.update { it.copy(message = "控制链路已建立，正在执行官方握手与全量状态回读...") }
                refreshAll()
            } catch (e: Throwable) {
                TakeoverBridge.foregroundLinkActive.set(false)
                base.update {
                    it.copy(
                        link = LinkState.Failed("连接异常: ${e.message ?: e.javaClass.simpleName}"),
                        message = "连接异常: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    private suspend fun connectAndWait(device: android.bluetooth.BluetoothDevice): LinkState.Ready? {
        if (!transport.connect(device)) return null
        val result = withTimeoutOrNull(table.scan.directConnectTimeoutMs) {
            transport.linkState.first { it is LinkState.Ready || it is LinkState.Failed }
        }
        return result as? LinkState.Ready
    }

    fun disconnect() {
        findBudsAutoStopJob?.cancel()
        refreshJob?.cancel()
        TakeoverBridge.foregroundLinkActive.set(false)
        transport.release()
        base.update {
            it.copy(message = "已释放控制链路，蓝牙音频 A2DP 不受影响。")
        }
    }

    fun refreshAll() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            refreshAllInternal()
        }
    }

    private suspend fun refreshAllInternal() {
        try {
            if (!state.value.controlReady) {
                system.refresh()
                return
            }
            readDeviceInfoAndHandshake()
            readAllStatus()
            readBattery()
            readNoiseState()
            readEqState()
            readGestures()
            readSmartAudioVideo()
            readVoiceAware()
            readWearingAndTips()
            readPowerAndVoicePrompts()
            readSecondaryDeviceMeta()
            system.refresh()
            base.update { it.copy(message = "全量状态回读完成。") }
        } catch (e: Throwable) {
            base.update { it.copy(message = "回读状态异常: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    // -------------------------------------------------------- Handshake & Device Info

    private suspend fun readDeviceInfoAndHandshake() {
        val infoFrame = queue.read("device_info_read")
        val info = infoFrame?.let { FrameCodec.parseDeviceInfo(it) }
        if (info != null) {
            base.update {
                it.copy(
                    deviceInfo = info,
                    twsConnected = info.twsConnected ?: it.twsConnected
                )
            }
        }
    }

    private suspend fun readAllStatus() {
        val frame = queue.read("status_read_all") { f ->
            f.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x30
        }
        val all = frame?.let { FrameCodec.parseAllStatus(it) } ?: return
        base.update { s ->
            val presetId = EqCodec.presetByteToId(all.eqPresetByte) ?: s.eqPresetId
            s.copy(
                noiseMode = all.noiseMode,
                autoOffEnabled = all.autoOffEnabled,
                autoOffMinutes = all.autoOffMinutes,
                autoOffStatus = FeatureStatus.READ_ONLY,
                activePresetByte = all.eqPresetByte,
                eqPresetId = presetId,
                multiAiMode = all.multiAiByte,
                multiAiStatus = FeatureStatus.READ_ONLY,
                autoPlayPause = all.autoPlayPause ?: s.autoPlayPause,
                autoPlayPauseStatus = if (all.autoPlayPause != null) FeatureStatus.READ_ONLY else s.autoPlayPauseStatus,
                twsConnected = all.twsConnected ?: s.twsConnected
            )
        }
    }

    private suspend fun readSecondaryDeviceMeta() {
        val caseFrame = queue.read("case_version_read")
        val caseVer = caseFrame?.let { FrameCodec.parseCaseVersion(it) }
        val snFrame = queue.read("serial_number_read")
        val sn = snFrame?.let { FrameCodec.parseSerialNumber(it) }
        val multiFrame = queue.read("multi_status_read")
        val callOn = multiFrame?.let { FrameCodec.parseMultiStatusCall(it) }
        base.update {
            it.copy(
                caseVersion = caseVer ?: it.caseVersion,
                serialNumber = sn ?: it.serialNumber,
                inCall = callOn ?: it.inCall
            )
        }
    }

    private suspend fun readBattery() {
        val frame = queue.read("battery_read")
        val reading = frame?.let { FrameCodec.parseBattery(it) }
        if (reading == null) {
            base.update {
                it.copy(
                    batteryStatus = FeatureStatus.UNVERIFIED,
                    batterySource = "0x25 无应答或格式不符；可查看系统层电量",
                    features = it.features.withStatus("battery", FeatureStatus.UNVERIFIED, "BLE 电量读取未响应", false)
                )
            }
            return
        }
        base.update {
            it.copy(
                battery = reading,
                batteryStatus = FeatureStatus.READ_ONLY,
                batterySource = "BLE 协议 0x25（真机读取 · 含电压）",
                features = it.features.withStatus("battery", FeatureStatus.READ_ONLY, "左/右/充电盒电量与设备信息已读取", false)
            )
        }
    }

    // -------------------------------------------------------- Noise Control (0x91 + 0x31/0x32/0x22)

    private suspend fun readNoiseState() {
        val modeFrame = queue.read("anc_read") { f ->
            f.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x12
        }
        val mode = modeFrame?.let { FrameCodec.parseNoiseMode(it) }

        base.update { s ->
            val resolvedMode = mode ?: s.noiseMode
            val status = if (mode != null) FeatureStatus.READ_ONLY else s.noiseStatus
            s.copy(
                noiseMode = resolvedMode,
                noiseStatus = status,
                noiseDetail = if (mode != null)
                    "当前模式：${resolvedMode.label}"
                else "暂未获取降噪状态",
                features = s.features.withStatus("anc", status, "模式可读；写入后回读一致标为真实", mode != null)
            )
        }
    }

    fun setNoiseMode(mode: NoiseMode) {
        val prevMode = base.value.noiseMode
        val prevStatus = base.value.noiseStatus
        val prevDetail = base.value.noiseDetail
        // Optimistic immediate UI update for crisp responsiveness
        base.update {
            it.copy(
                noiseMode = mode,
                noiseDetail = "正在下发：${mode.label}..."
            )
        }
        val commandName = when (mode) {
            NoiseMode.OFF -> "noise_all_off"
            NoiseMode.ANC -> "anc_on"
            NoiseMode.AMBIENT_AWARE -> "ambient_aware_on"
            NoiseMode.TALKTHRU -> "talkthru_on"
        }
        viewModelScope.launch {
            val ancFilter: (Frame) -> Boolean = { f ->
                f.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x12
            }
            var result = queue.writeAndVerify(commandName, readFilter = ancFilter) {
                FrameCodec.parseNoiseMode(it) == mode
            }

            // Secondary path: legacy 0x31 (ANC) / 0x32 (AA/TT) + 0x21 01 30 readback
            if (result !is WriteResult.Verified) {
                val legacyResult = writeNoiseViaLegacyCommands(mode)
                if (legacyResult is WriteResult.Verified) {
                    result = legacyResult
                }
            }

            if (result is WriteResult.Verified) {
                base.update {
                    it.copy(
                        noiseMode = mode,
                        noiseStatus = FeatureStatus.REAL,
                        noiseDetail = "已切换为 ${mode.label}（耳机已确认）",
                        message = "降噪/环境音已切换为 ${mode.label}。",
                        features = it.features.withStatus("anc", FeatureStatus.REAL, "写入 + 回读一致", true)
                    )
                }
            } else {
                // Re-read in-ear & TWS status so we can give an accurate physical reason if rejected
                val inEar = queue.read("in_ear_read", timeoutOverrideMs = 250L) {
                    it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x41
                }?.let { FrameCodec.parseInEarStatus(it) }
                readAllStatus()

                val st = base.value
                val hardwareHint = buildString {
                    if (st.twsConnected == false) append("TWS 未双耳互联（单耳在盒）；")
                    val lIn = inEar?.leftInEar ?: st.inEar?.leftInEar
                    val rIn = inEar?.rightInEar ?: st.inEar?.rightInEar
                    if (lIn == false || rIn == false) append("未检测到双耳佩戴（L=$lIn, R=$rIn）；")
                }
                val reason = when (result) {
                    is WriteResult.Unverified -> result.reason
                    is WriteResult.Refused -> result.reason
                    is WriteResult.Verified -> ""
                }
                val fullDetail = if (hardwareHint.isNotBlank()) "$reason（固件拦截：$hardwareHint 请戴稳双耳再试）" else reason
                base.update {
                    it.copy(
                        noiseMode = prevMode,
                        noiseStatus = if (result is WriteResult.Refused) FeatureStatus.DEGRADED else prevStatus,
                        inEar = inEar ?: it.inEar,
                        noiseDetail = fullDetail.ifBlank { prevDetail },
                        message = "模式未确认生效：$fullDetail"
                    )
                }
            }
        }
    }

    private suspend fun writeNoiseViaLegacyCommands(mode: NoiseMode): WriteResult {
        val filterAll: (Frame) -> Boolean = { f -> f.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x30 }
        return when (mode) {
            NoiseMode.OFF -> {
                queue.writeAndVerify("anc_legacy_off", readFilter = filterAll) {
                    FrameCodec.parseNoiseMode(it) == NoiseMode.OFF
                }
                queue.writeAndVerify("ambient_legacy_off", readFilter = filterAll) {
                    FrameCodec.parseNoiseMode(it) == NoiseMode.OFF
                }
            }
            NoiseMode.ANC -> queue.writeAndVerify("anc_legacy_on", readFilter = filterAll) {
                FrameCodec.parseNoiseMode(it) == NoiseMode.ANC
            }
            NoiseMode.AMBIENT_AWARE -> queue.writeAndVerify("ambient_legacy_aware", readFilter = filterAll) {
                FrameCodec.parseNoiseMode(it) == NoiseMode.AMBIENT_AWARE
            }
            NoiseMode.TALKTHRU -> queue.writeAndVerify("ambient_legacy_talkthru", readFilter = filterAll) {
                FrameCodec.parseNoiseMode(it) == NoiseMode.TALKTHRU
            }
        }
    }

    fun setAmbientAwareLevel(level: Int) {
        val clamped = level.coerceIn(1, 7)
        base.update { it.copy(ambientAwareLevel = clamped) }
        if (!state.value.controlReady) return
        viewModelScope.launch {
            val frame = FrameCodec.buildAdvanceAncLevel(clamped)
            val result = queue.writeFrameAndVerify(
                frameHex = FrameCodec.hex(frame),
                replyCmd = 0x00,
                readbackName = "advance_anc_read",
                readFilter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x22 }
            ) { f -> FrameCodec.parseAdvanceAnc(f)?.ambientAwareLevel == clamped }
            base.update {
                when (result) {
                    is WriteResult.Verified -> it.copy(
                        ambientAwareLevel = clamped,
                        noiseStatus = FeatureStatus.REAL,
                        noiseDetail = "环境感知等级已下发并回读确认：$clamped/7",
                        message = "环境感知等级已更新为 $clamped。"
                    )
                    is WriteResult.Unverified -> it.copy(
                        noiseDetail = "环境感知等级回读未匹配：${result.reason}",
                        message = result.reason
                    )
                    is WriteResult.Refused -> it.copy(message = result.reason)
                }
            }
        }
    }

    fun setAncSubMode(subMode: Int) {
        if (!state.value.controlReady) return
        viewModelScope.launch {
            val frame = FrameCodec.buildAncSubMode(subMode)
            val result = queue.writeFrameAndVerify(
                frameHex = FrameCodec.hex(frame),
                replyCmd = 0x00,
                readbackName = "anc_submode_read",
                readFilter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x02 }
            ) { f -> FrameCodec.parseAncSubMode(f) == subMode }
            base.update {
                when (result) {
                    is WriteResult.Verified -> it.copy(
                        ancSubMode = subMode,
                        noiseStatus = FeatureStatus.REAL,
                        noiseDetail = "环境声音控制开关已更新为 $subMode",
                        message = "环境声音子模式已写入并回读确认。"
                    )
                    is WriteResult.Unverified -> it.copy(message = result.reason)
                    is WriteResult.Refused -> it.copy(message = result.reason)
                }
            }
        }
    }

    // -------------------------------------------------------- EQ (0x40 Preset + 0x41/0x42/0x43 Custom)

    private suspend fun readEqState() {
        val presetFrame = queue.read("eq_preset_status_read") { f ->
            f.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x34
        }
        val presetByte = presetFrame?.let { FrameCodec.parseEqPresetStatus(it) }
        val custom = queue.readCustomEq(EqCodec.CAT_GRAPHIC_EQ)
            ?: queue.readCustomEq(EqCodec.CAT_TOTAL_EQ)

        if (custom != null) {
            val resolvedId = presetByte?.let { EqCodec.presetByteToId(it) }
                ?: EqCodec.presetByteToId(custom.presetIndex)
                ?: base.value.eqPresetId
            eqStore.save(resolvedId, custom.gains)
            base.update {
                val presetLabel = eqPresets.firstOrNull { p -> p.id == resolvedId }?.label ?: resolvedId
                it.copy(
                    activePresetByte = presetByte ?: custom.presetIndex,
                    eqPresetId = resolvedId,
                    eqGains = custom.gains,
                    eqStatus = FeatureStatus.READ_ONLY,
                    eqDetail = "已同步 10 段均衡曲线（$presetLabel）",
                    features = it.features.withStatus("eq", FeatureStatus.READ_ONLY, "10 段均衡曲线已同步", true)
                )
            }
            return
        }

        // Fallback to A2 read if 0x42/0x43 is unsupported
        val a2Frame = queue.read("eq_read_in_use")
        val a2Cat = a2Frame?.let { EqCodec.parseCategory(it) }
        base.update {
            val st = if (presetByte != null || a2Cat != null) FeatureStatus.READ_ONLY else FeatureStatus.UNVERIFIED
            val resolvedId = presetByte?.let { b -> EqCodec.presetByteToId(b) } ?: it.eqPresetId
            val presetLabel = eqPresets.firstOrNull { p -> p.id == resolvedId }?.label ?: "预设"
            it.copy(
                activePresetByte = presetByte ?: it.activePresetByte,
                eqPresetId = resolvedId,
                eqStatus = st,
                eqDetail = when {
                    presetByte != null || a2Cat != null -> "当前均衡器：$presetLabel"
                    else -> "未获取到均衡器状态"
                },
                features = it.features.withStatus("eq", st, it.eqDetail, st != FeatureStatus.UNVERIFIED)
            )
        }
    }

    fun setEqPreset(preset: EqPreset) {
        val prevPresetId = base.value.eqPresetId
        val prevGains = base.value.eqGains
        val prevByte = base.value.activePresetByte
        val prevStatus = base.value.eqStatus
        eqStore.save(preset.id, preset.gains)
        if (!state.value.controlReady) {
            base.update {
                it.copy(
                    eqPresetId = preset.id,
                    eqGains = preset.gains,
                    eqStatus = FeatureStatus.DEGRADED,
                    eqDetail = "已保存到本地；未建立控制链路，未下发到耳机",
                    message = "EQ 仅本地保存，未写入耳机。"
                )
            }
            return
        }
        // Optimistic immediate selection + smooth curve preview
        val presetByte = EqCodec.presetIdToByte(preset.id) ?: preset.cmdIndex
        base.update {
            it.copy(
                eqPresetId = preset.id,
                activePresetByte = presetByte,
                eqGains = preset.gains,
                eqDetail = "正在应用 EQ 预设「${preset.label}」..."
            )
        }
        viewModelScope.launch {
            val presetFrame = EqCodec.buildSetEqPresetFrame(presetByte)
            val presetResult = queue.writeFrameAndVerify(
                frameHex = FrameCodec.hex(presetFrame),
                replyCmd = 0x00,
                readbackName = "eq_preset_status_read",
                readFilter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x34 }
            ) { f -> FrameCodec.parseEqPresetStatus(f) == presetByte }

            // Official Tune230Control also writes the 10-band curve via 0x41 multi-part transfer
            val curveResult = queue.writeCustomEqAndVerify(
                presetIndex = presetByte,
                gains = preset.gains,
                eqCategory = EqCodec.CAT_GRAPHIC_EQ
            )

            // Secondary A2 fallback only if neither 0x40 nor 0x41 verified
            val a2Verified = if (presetResult !is WriteResult.Verified && !curveResult.verified) {
                val a2Frame = EqCodec.writeFrame(preset.category, preset.gains)
                queue.writeFrameAndVerify(
                    FrameCodec.hex(a2Frame), EqCodec.CMD_A2, "eq_read_in_use"
                ) { f -> EqCodec.parseCategory(f) == preset.category } is WriteResult.Verified
            } else false

            base.update {
                val verified = curveResult.verified || presetResult is WriteResult.Verified || a2Verified
                val newStatus = if (verified) FeatureStatus.REAL else prevStatus
                val detail = if (verified) {
                    "已应用「${preset.label}」音效（耳机已确认）"
                } else {
                    "EQ 未确认生效：${curveResult.reason}"
                }
                if (verified) {
                    it.copy(
                        eqPresetId = preset.id,
                        activePresetByte = presetByte,
                        eqGains = curveResult.readbackState?.gains ?: preset.gains,
                        eqStatus = newStatus,
                        eqDetail = detail,
                        message = "EQ 预设「${preset.label}」已生效并回读确认。",
                        features = it.features.withStatus("eq", newStatus, detail, true)
                    )
                } else {
                    it.copy(
                        eqPresetId = prevPresetId,
                        activePresetByte = prevByte,
                        eqGains = prevGains,
                        eqStatus = newStatus,
                        eqDetail = detail,
                        message = detail
                    )
                }
            }
        }
    }

    fun setCustomBand(index: Int, value: Float) {
        val gains = base.value.eqGains.toMutableList()
        if (index !in gains.indices) return
        val snapped = (Math.round(value * 2f) / 2f).coerceIn(-6f, 6f)
        gains[index] = snapped
        eqStore.save("custom", gains)
        base.update {
            it.copy(
                eqPresetId = "custom",
                eqGains = gains,
                eqStatus = FeatureStatus.UNVERIFIED,
                eqDetail = "已编辑 10 段曲线（待下发至耳机验证）"
            )
        }
    }

    fun applyCustomEq() {
        val gains = base.value.eqGains
        eqStore.save("custom", gains)
        if (!state.value.controlReady) {
            base.update {
                it.copy(
                    eqStatus = FeatureStatus.DEGRADED,
                    eqDetail = "已保存到本地；连接控制链路后可下发到耳机",
                    message = "请先连接控制链路再应用自定义 EQ。"
                )
            }
            return
        }
        viewModelScope.launch {
            base.update { it.copy(message = "正在通过 0x41 分块写入自定义 EQ 并回读...") }
            val result = queue.writeCustomEqAndVerify(
                presetIndex = EqCodec.PRESET_IDX_USER,
                gains = gains,
                eqCategory = EqCodec.CAT_GRAPHIC_EQ
            )
            // Also switch preset index to USER (0x04) via 0x40
            val switchUser = queue.writeFrameAndVerify(
                frameHex = FrameCodec.hex(EqCodec.buildSetEqPresetFrame(EqCodec.PRESET_IDX_USER)),
                replyCmd = 0x00,
                readbackName = "eq_preset_status_read",
                readFilter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x34 }
            ) { f -> FrameCodec.parseEqPresetStatus(f) == EqCodec.PRESET_IDX_USER }

            val ok = result.verified
            base.update {
                val st = if (ok) FeatureStatus.REAL else FeatureStatus.UNVERIFIED
                val msg = if (ok) {
                    "自定义 10 段均衡器已写入耳机并确认。"
                } else {
                    "自定义 EQ 未确认生效：${result.reason}" +
                        if (switchUser is WriteResult.Verified) "（预设槽已切换至 USER）" else ""
                }
                it.copy(
                    eqPresetId = "custom",
                    eqGains = if (ok && result.readbackState != null) result.readbackState.gains else gains,
                    eqStatus = st,
                    eqDetail = msg,
                    message = msg,
                    features = it.features.withStatus("eq", st, msg, true)
                )
            }
        }
    }

    // -------------------------------------------------------- Touch Gestures (0x77)

    private suspend fun readGestures() {
        val frame = queue.read("gesture_read_all")
        val gState = frame?.let { FrameCodec.parseGestures(it) }
        base.update {
            if (gState == null) {
                it.copy(
                    gestureStatus = if (frame != null) FeatureStatus.READ_ONLY else FeatureStatus.UNVERIFIED,
                    gestureDetail = if (frame != null) "手势表已读取（${frame.hex}）" else "0x77 无应答，保持未验证"
                )
            } else {
                it.copy(
                    gesturePairs = gState.pairs,
                    leftGestureGroup = gState.leftGroup,
                    rightGestureGroup = gState.rightGroup,
                    leftHoldVoiceAssistant = gState.leftHoldVoiceAssistant,
                    rightHoldVoiceAssistant = gState.rightHoldVoiceAssistant,
                    gestureStatus = FeatureStatus.READ_ONLY,
                    gestureDetail = "左耳：${gState.leftGroup.label} / 右耳：${gState.rightGroup.label}",
                    features = it.features.withStatus("gesture", FeatureStatus.READ_ONLY, "手势映射已读取，支持组别写入与重置", true)
                )
            }
        }
    }

    fun setGestureGroup(isLeft: Boolean, group: GestureFunctionGroup, holdVoiceAssistant: Boolean) {
        if (!state.value.controlReady) return
        val prevLeft = base.value.leftGestureGroup
        val prevRight = base.value.rightGestureGroup
        val prevLeftHold = base.value.leftHoldVoiceAssistant
        val prevRightHold = base.value.rightHoldVoiceAssistant
        base.update {
            if (isLeft) it.copy(leftGestureGroup = group, leftHoldVoiceAssistant = holdVoiceAssistant)
            else it.copy(rightGestureGroup = group, rightHoldVoiceAssistant = holdVoiceAssistant)
        }
        viewModelScope.launch {
            val frame = FrameCodec.buildSetGestureGroup(isLeft, group, holdVoiceAssistant)
            val result = queue.writeFrameAndVerify(
                frameHex = FrameCodec.hex(frame),
                replyCmd = 0x00,
                readbackName = "gesture_read_all"
            ) { f ->
                val parsed = FrameCodec.parseGestures(f) ?: return@writeFrameAndVerify false
                if (isLeft) parsed.leftGroup == group else parsed.rightGroup == group
            }
            base.update {
                when (result) {
                    is WriteResult.Verified -> {
                        val parsed = FrameCodec.parseGestures(result.readback)
                        it.copy(
                            gesturePairs = parsed?.pairs ?: it.gesturePairs,
                            leftGestureGroup = parsed?.leftGroup ?: it.leftGestureGroup,
                            rightGestureGroup = parsed?.rightGroup ?: it.rightGestureGroup,
                            leftHoldVoiceAssistant = parsed?.leftHoldVoiceAssistant ?: it.leftHoldVoiceAssistant,
                            rightHoldVoiceAssistant = parsed?.rightHoldVoiceAssistant ?: it.rightHoldVoiceAssistant,
                            gestureStatus = FeatureStatus.REAL,
                            gestureDetail = "手势修改已回读一致：${if (isLeft) "左耳" else "右耳"} -> ${group.label}",
                            message = "触控手势已写入并回读确认。",
                            features = it.features.withStatus("gesture", FeatureStatus.REAL, "手势写入 + 回读一致", true)
                        )
                    }
                    is WriteResult.Unverified -> it.copy(
                        leftGestureGroup = prevLeft,
                        rightGestureGroup = prevRight,
                        leftHoldVoiceAssistant = prevLeftHold,
                        rightHoldVoiceAssistant = prevRightHold,
                        gestureStatus = FeatureStatus.UNVERIFIED,
                        gestureDetail = result.reason,
                        message = result.reason
                    )
                    is WriteResult.Refused -> it.copy(
                        leftGestureGroup = prevLeft,
                        rightGestureGroup = prevRight,
                        leftHoldVoiceAssistant = prevLeftHold,
                        rightHoldVoiceAssistant = prevRightHold,
                        message = result.reason
                    )
                }
            }
        }
    }

    fun resetGestures() {
        if (!state.value.controlReady) return
        viewModelScope.launch {
            val frame = FrameCodec.buildResetGestures(0xFF)
            val result = queue.writeFrameAndVerify(
                frameHex = FrameCodec.hex(frame),
                replyCmd = 0x00,
                readbackName = "gesture_read_all"
            ) { f -> FrameCodec.parseGestures(f) != null }
            val parsed = (result as? WriteResult.Verified)?.readback?.let { FrameCodec.parseGestures(it) }
            base.update {
                if (parsed != null) {
                    it.copy(
                        gesturePairs = parsed.pairs,
                        leftGestureGroup = parsed.leftGroup,
                        rightGestureGroup = parsed.rightGroup,
                        leftHoldVoiceAssistant = parsed.leftHoldVoiceAssistant,
                        rightHoldVoiceAssistant = parsed.rightHoldVoiceAssistant,
                        gestureStatus = FeatureStatus.REAL,
                        gestureDetail = "已重置为出厂手势并回读：左=${parsed.leftGroup.label}, 右=${parsed.rightGroup.label}",
                        message = "触控手势已恢复默认。"
                    )
                } else {
                    it.copy(message = "重置手势后回读未确认。")
                }
            }
        }
    }

    // -------------------------------------------------------- Smart Audio & Video (0x81/0x82/0x83)

    private suspend fun readSmartAudioVideo() {
        val frame = queue.read("smart_av_read")
        val mode = frame?.let { FrameCodec.parseSmartSwitch(it) }
        base.update {
            if (mode == null) it.copy(smartAudioStatus = FeatureStatus.UNVERIFIED, smartAudioDetail = "0x82 无应答")
            else it.copy(
                smartAudioMode = mode,
                smartAudioStatus = FeatureStatus.READ_ONLY,
                smartAudioDetail = "当前模式：${mode.label}",
                features = it.features.withStatus("smart_av", FeatureStatus.READ_ONLY, "Smart A&V 可读（${mode.label}）", true)
            )
        }
    }

    fun setSmartAudioMode(mode: SmartAudioMode) {
        if (!state.value.controlReady) return
        val prevMode = base.value.smartAudioMode
        base.update { it.copy(smartAudioMode = mode) }
        viewModelScope.launch {
            val result = queue.writeAndVerify(mode.commandName) { f ->
                FrameCodec.parseSmartSwitch(f) == mode
            }
            base.update {
                when (result) {
                    is WriteResult.Verified -> it.copy(
                        smartAudioMode = mode,
                        smartAudioStatus = FeatureStatus.REAL,
                        smartAudioDetail = "已切换为 ${mode.label} 并回读一致",
                        message = "智能音视频已切换为 ${mode.label}。",
                        features = it.features.withStatus("smart_av", FeatureStatus.REAL, "Smart A&V 写入 + 回读一致", true)
                    )
                    is WriteResult.Unverified -> it.copy(
                        smartAudioMode = prevMode,
                        smartAudioStatus = FeatureStatus.UNVERIFIED,
                        smartAudioDetail = result.reason,
                        message = result.reason
                    )
                    is WriteResult.Refused -> it.copy(smartAudioMode = prevMode, message = result.reason)
                }
            }
        }
    }

    // -------------------------------------------------------- VoiceAware (0x98)

    private suspend fun readVoiceAware() {
        val frame = queue.read("voice_aware_read")
        val va = frame?.let { FrameCodec.parseVoiceAware(it) }
        base.update {
            if (va == null) it.copy(voiceAwareStatus = FeatureStatus.UNVERIFIED, voiceAwareDetail = "0x98 无应答")
            else it.copy(
                voiceAwareLevel = va.level,
                voiceAwareStatus = FeatureStatus.READ_ONLY,
                voiceAwareDetail = "当前等级：${voiceAwareLabel(va.level)}",
                features = it.features.withStatus("voice_aware", FeatureStatus.READ_ONLY, "VoiceAware 可读（${voiceAwareLabel(va.level)}）", true)
            )
        }
    }

    fun setVoiceAwareLevel(level: Int) {
        val target = level.coerceIn(0, 3)
        if (!state.value.controlReady) return
        val prevLevel = base.value.voiceAwareLevel
        base.update { it.copy(voiceAwareLevel = target) }
        viewModelScope.launch {
            var result = queue.writeFrameAndVerify(
                frameHex = FrameCodec.hex(FrameCodec.buildVoiceAware(target)),
                replyCmd = 0x00,
                readbackName = "voice_aware_read"
            ) { f -> FrameCodec.parseVoiceAware(f)?.level == target }

            if (result !is WriteResult.Verified) {
                result = queue.writeFrameAndVerify(
                    frameHex = FrameCodec.hex(FrameCodec.buildVoiceAwareLegacy(target)),
                    replyCmd = 0x00,
                    readbackName = "voice_aware_read"
                ) { f -> FrameCodec.parseVoiceAware(f)?.level == target }
            }
            base.update {
                when (result) {
                    is WriteResult.Verified -> it.copy(
                        voiceAwareLevel = target,
                        voiceAwareStatus = FeatureStatus.REAL,
                        voiceAwareDetail = "VoiceAware 已设置为「${voiceAwareLabel(target)}」并回读一致",
                        message = "VoiceAware 已更新并回读确认。",
                        features = it.features.withStatus("voice_aware", FeatureStatus.REAL, "VoiceAware 写入 + 回读一致", true)
                    )
                    is WriteResult.Unverified -> it.copy(
                        voiceAwareLevel = prevLevel,
                        voiceAwareStatus = FeatureStatus.UNVERIFIED,
                        voiceAwareDetail = result.reason,
                        message = result.reason
                    )
                    is WriteResult.Refused -> it.copy(voiceAwareLevel = prevLevel, message = result.reason)
                }
            }
        }
    }

    // -------------------------------------------------------- Wearing, Auto Play/Pause, Fit Test, Tips

    private suspend fun readWearingAndTips() {
        val appFrame = queue.read("auto_play_pause_read") {
            it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x38
        }
        val autoPlay = appFrame?.let { FrameCodec.parseAutoPlayPauseStatus(it) }

        base.update { s ->
            val resolvedAuto = autoPlay ?: s.autoPlayPause
            val status = if (autoPlay != null) FeatureStatus.READ_ONLY else s.autoPlayPauseStatus
            s.copy(
                autoPlayPause = resolvedAuto,
                autoPlayPauseStatus = status,
                features = s.features.withStatus("wearing", status, "自动播放暂停已读取", true)
            )
        }
    }

    fun setAutoPlayPause(enabled: Boolean) {
        if (!state.value.controlReady) return
        val prev = base.value.autoPlayPause
        base.update { it.copy(autoPlayPause = enabled) }
        viewModelScope.launch {
            val cmdName = if (enabled) "auto_play_pause_on" else "auto_play_pause_off"
            val result = queue.writeAndVerify(
                name = cmdName,
                readFilter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x38 }
            ) { f -> FrameCodec.parseAutoPlayPauseStatus(f) == enabled }

            base.update {
                when (result) {
                    is WriteResult.Verified -> it.copy(
                        autoPlayPause = enabled,
                        autoPlayPauseStatus = FeatureStatus.REAL,
                        message = "自动播放/暂停已设为 ${if (enabled) "开启" else "关闭"} 并回读确认。",
                        features = it.features.withStatus("wearing", FeatureStatus.REAL, "自动播放/暂停写入 + 回读一致", true)
                    )
                    is WriteResult.Unverified -> it.copy(
                        autoPlayPause = prev,
                        autoPlayPauseStatus = FeatureStatus.UNVERIFIED,
                        message = result.reason
                    )
                    is WriteResult.Refused -> it.copy(autoPlayPause = prev, message = result.reason)
                }
            }
        }
    }

    fun runSealingTest() {
        if (!state.value.controlReady) return
        viewModelScope.launch {
            base.update { it.copy(sealingDetail = "正在检测双耳贴合度，请保持佩戴静止...") }
            val frame = queue.read("sealing_test_start") {
                it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x42
            }
            val sealing = frame?.let { FrameCodec.parseSealingStatus(it) }
            base.update {
                if (sealing == null) {
                    it.copy(
                        sealingStatus = FeatureStatus.UNVERIFIED,
                        sealingDetail = "贴合度检测超时或固件未应答",
                        message = "贴合度检测未返回结果。"
                    )
                } else {
                    val lStr = if (sealing.leftGood) "良好" else "请调整耳塞(${sealing.leftRaw})"
                    val rStr = if (sealing.rightGood) "良好" else "请调整耳塞(${sealing.rightRaw})"
                    it.copy(
                        sealingState = sealing,
                        sealingStatus = FeatureStatus.REAL,
                        sealingDetail = "检测完成：左耳 $lStr ｜ 右耳 $rStr",
                        message = "佩戴贴合度检测已完成。"
                    )
                }
            }
        }
    }

    fun setTipsType(type: Int) {
        if (!state.value.controlReady) return
        viewModelScope.launch {
            val frame = FrameCodec.buildTipsType(type)
            val result = queue.writeFrameAndVerify(
                frameHex = FrameCodec.hex(frame),
                replyCmd = 0x00,
                readbackName = "tips_type_read"
            ) { f -> FrameCodec.parseTipsType(f) == type }
            base.update {
                when (result) {
                    is WriteResult.Verified -> it.copy(
                        tipsType = type,
                        tipsTypeStatus = FeatureStatus.REAL,
                        message = "耳塞类型已设为 #$type 并回读确认。"
                    )
                    is WriteResult.Unverified -> it.copy(tipsTypeStatus = FeatureStatus.UNVERIFIED, message = result.reason)
                    is WriteResult.Refused -> it.copy(message = result.reason)
                }
            }
        }
    }

    // -------------------------------------------------------- Power, Voice Prompts & Multi-AI

    private suspend fun readPowerAndVoicePrompts() {
        val standbyFrame = queue.read("standby_mode_read")
        val keepAnc = standbyFrame?.let { FrameCodec.parseStandbyModeKeepAnc(it) }
        val autoOffFrame = queue.read("auto_off_read") { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x33 }
        val autoOff = autoOffFrame?.let { FrameCodec.parseAutoOffStatus(it) }
        val vpLangFrame = queue.read("voice_prompt_lang_read")
        val vpLang = vpLangFrame?.let { FrameCodec.parseVoicePrompt(it) }
        val aiFrame = queue.read("multi_ai_read") { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x35 }
        val ai = aiFrame?.let { FrameCodec.parseMultiAiStatus(it) }

        base.update { s ->
            val stAny = if (keepAnc != null || autoOff != null || vpLang != null || ai != null)
                FeatureStatus.READ_ONLY else s.standbyStatus
            s.copy(
                standbyKeepAnc = keepAnc ?: s.standbyKeepAnc,
                standbyStatus = if (keepAnc != null) FeatureStatus.READ_ONLY else s.standbyStatus,
                autoOffEnabled = autoOff?.first ?: s.autoOffEnabled,
                autoOffMinutes = autoOff?.second ?: s.autoOffMinutes,
                autoOffStatus = if (autoOff != null) FeatureStatus.READ_ONLY else s.autoOffStatus,
                voicePromptInfo = vpLang ?: s.voicePromptInfo,
                voicePromptStatus = if (vpLang != null) FeatureStatus.READ_ONLY else s.voicePromptStatus,
                multiAiMode = ai ?: s.multiAiMode,
                multiAiStatus = if (ai != null) FeatureStatus.READ_ONLY else s.multiAiStatus,
                features = s.features.withStatus("power_voice", stAny, "电源待机/关机、提示音与语音助手状态已读取", true)
            )
        }
    }

    fun setStandbyKeepAnc(keepAnc: Boolean) {
        if (!state.value.controlReady) return
        val prev = base.value.standbyKeepAnc
        base.update { it.copy(standbyKeepAnc = keepAnc) }
        viewModelScope.launch {
            val frame = FrameCodec.buildStandbyMode(keepAnc)
            val result = queue.writeFrameAndVerify(
                frameHex = FrameCodec.hex(frame),
                replyCmd = 0x00,
                readbackName = "standby_mode_read"
            ) { f -> FrameCodec.parseStandbyModeKeepAnc(f) == keepAnc }
            base.update {
                when (result) {
                    is WriteResult.Verified -> it.copy(
                        standbyKeepAnc = keepAnc,
                        standbyStatus = FeatureStatus.REAL,
                        message = "自动待机策略已设为「${if (keepAnc) "保持降噪" else "普通待机"}」并回读一致。",
                        features = it.features.withStatus("power_voice", FeatureStatus.REAL, "自动待机写入 + 回读一致", true)
                    )
                    is WriteResult.Unverified -> it.copy(standbyKeepAnc = prev, standbyStatus = FeatureStatus.UNVERIFIED, message = result.reason)
                    is WriteResult.Refused -> it.copy(standbyKeepAnc = prev, message = result.reason)
                }
            }
        }
    }

    fun setAutoPowerOff(enabled: Boolean, minutes: Int) {
        if (!state.value.controlReady) return
        val m = minutes.coerceIn(5, 120)
        val prevEn = base.value.autoOffEnabled
        val prevMin = base.value.autoOffMinutes
        base.update { it.copy(autoOffEnabled = enabled, autoOffMinutes = if (enabled) m else it.autoOffMinutes) }
        viewModelScope.launch {
            val frame = FrameCodec.buildAutoPowerOff(enabled, m)
            val result = queue.writeFrameAndVerify(
                frameHex = FrameCodec.hex(frame),
                replyCmd = 0x00,
                readbackName = "auto_off_read",
                readFilter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x33 }
            ) { f ->
                val pair = FrameCodec.parseAutoOffStatus(f) ?: return@writeFrameAndVerify false
                pair.first == enabled && (!enabled || pair.second == m)
            }
            base.update {
                when (result) {
                    is WriteResult.Verified -> it.copy(
                        autoOffEnabled = enabled,
                        autoOffMinutes = if (enabled) m else it.autoOffMinutes,
                        autoOffStatus = FeatureStatus.REAL,
                        message = if (enabled) "自动关机已设为 ${m} 分钟并回读确认。" else "自动关机已关闭并回读确认。",
                        features = it.features.withStatus("power_voice", FeatureStatus.REAL, "自动关机写入 + 回读一致", true)
                    )
                    is WriteResult.Unverified -> it.copy(autoOffEnabled = prevEn, autoOffMinutes = prevMin, autoOffStatus = FeatureStatus.UNVERIFIED, message = result.reason)
                    is WriteResult.Refused -> it.copy(autoOffEnabled = prevEn, autoOffMinutes = prevMin, message = result.reason)
                }
            }
        }
    }

    fun setVoicePromptToneOnly(toneOnly: Boolean) {
        if (!state.value.controlReady) return
        viewModelScope.launch {
            val frame = FrameCodec.buildVoicePromptStatus(toneOnly)
            val result = queue.writeFrameAndVerify(
                frameHex = FrameCodec.hex(frame),
                replyCmd = 0x00,
                readbackName = "voice_prompt_status_read"
            ) { f -> FrameCodec.parseVoicePrompt(f)?.toneOnly == toneOnly }
            base.update {
                when (result) {
                    is WriteResult.Verified -> it.copy(
                        voicePromptToneOnly = toneOnly,
                        voicePromptStatus = FeatureStatus.REAL,
                        message = "提示音模式已设为「${if (toneOnly) "仅提示音 (Tone)" else "语音播报"}」并回读确认。"
                    )
                    is WriteResult.Unverified -> it.copy(voicePromptStatus = FeatureStatus.UNVERIFIED, message = result.reason)
                    is WriteResult.Refused -> it.copy(message = result.reason)
                }
            }
        }
    }

    fun setMultiAiMode(modeByte: Int) {
        if (!state.value.controlReady) return
        val prev = base.value.multiAiMode
        base.update { it.copy(multiAiMode = modeByte) }
        viewModelScope.launch {
            val frame = FrameCodec.buildMultiAi(modeByte)
            val result = queue.writeFrameAndVerify(
                frameHex = FrameCodec.hex(frame),
                replyCmd = 0x00,
                readbackName = "multi_ai_read",
                readFilter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x35 }
            ) { f -> FrameCodec.parseMultiAiStatus(f) == (modeByte and 0xFF) }
            base.update {
                when (result) {
                    is WriteResult.Verified -> it.copy(
                        multiAiMode = modeByte,
                        multiAiStatus = FeatureStatus.REAL,
                        message = "语音助手预设已更新并回读确认。"
                    )
                    is WriteResult.Unverified -> it.copy(multiAiMode = prev, multiAiStatus = FeatureStatus.UNVERIFIED, message = result.reason)
                    is WriteResult.Refused -> it.copy(multiAiMode = prev, message = result.reason)
                }
            }
        }
    }

    // -------------------------------------------------------- Find My Buds (Confirmation + 15s Auto-Stop)

    fun requestFindMyBuds(left: Boolean, right: Boolean) {
        if (!left && !right) {
            stopFindMyBuds()
            return
        }
        base.update {
            it.copy(
                findBudsConfirmPending = true,
                findBudsPendingLeft = left,
                findBudsPendingRight = right
            )
        }
    }

    fun dismissFindMyBudsConfirm() {
        base.update { it.copy(findBudsConfirmPending = false) }
    }

    fun confirmFindMyBuds() {
        val left = base.value.findBudsPendingLeft
        val right = base.value.findBudsPendingRight
        base.update { it.copy(findBudsConfirmPending = false) }
        if (!state.value.controlReady) return

        viewModelScope.launch {
            val primaryFrame = FrameCodec.buildFindMyBuds(left, right)
            var result = queue.writeFrameAndVerify(
                frameHex = FrameCodec.hex(primaryFrame),
                replyCmd = 0x00,
                readbackName = "find_buds_read"
            ) { f ->
                val s = FrameCodec.parseFindMyBuds(f) ?: return@writeFrameAndVerify false
                s.leftBeeping == left && s.rightBeeping == right
            }

            if (result !is WriteResult.Verified) {
                val legacyFrame = FrameCodec.buildFindMyBudsLegacy(left, right)
                val legacyRes = queue.writeFrameAndVerify(
                    frameHex = FrameCodec.hex(legacyFrame),
                    replyCmd = 0x00,
                    readbackName = "find_buds_legacy_read"
                ) { f ->
                    val s = FrameCodec.parseFindMyBuds(f) ?: return@writeFrameAndVerify false
                    s.leftBeeping == left && s.rightBeeping == right
                }
                if (legacyRes is WriteResult.Verified) result = legacyRes
            }

            val st = if (result is WriteResult.Verified) FeatureStatus.REAL else FeatureStatus.UNVERIFIED
            val targetStr = buildString {
                if (left) append("左耳 ")
                if (right) append("右耳")
            }.trim()
            base.update {
                it.copy(
                    findMyBuds = FindMyBudsState(left, right),
                    findBudsStatus = st,
                    findBudsDetail = if (result is WriteResult.Verified)
                        "正在蜂鸣（$targetStr），15 秒后自动停止"
                    else "已发送蜂鸣命令（$targetStr），等待状态确认",
                    message = "查找耳机已启动（$targetStr），15 秒后自动停止。",
                    features = it.features.withStatus("find", st, "已触发蜂鸣（带 15 秒自动停止）", true)
                )
            }

            findBudsAutoStopJob?.cancel()
            findBudsAutoStopJob = viewModelScope.launch {
                delay(15_000L)
                stopFindMyBuds()
            }
        }
    }

    fun stopFindMyBuds() {
        findBudsAutoStopJob?.cancel()
        if (!state.value.controlReady) {
            base.update { it.copy(findMyBuds = FindMyBudsState(false, false)) }
            return
        }
        viewModelScope.launch {
            val stopFrame = FrameCodec.buildFindMyBuds(left = false, right = false)
            var result = queue.writeFrameAndVerify(
                frameHex = FrameCodec.hex(stopFrame),
                replyCmd = 0x00,
                readbackName = "find_buds_read"
            ) { f ->
                val s = FrameCodec.parseFindMyBuds(f) ?: return@writeFrameAndVerify false
                !s.leftBeeping && !s.rightBeeping
            }
            if (result !is WriteResult.Verified) {
                val legacyStop = FrameCodec.buildFindMyBudsLegacy(left = false, right = false)
                val legacyRes = queue.writeFrameAndVerify(
                    frameHex = FrameCodec.hex(legacyStop),
                    replyCmd = 0x00,
                    readbackName = "find_buds_legacy_read"
                ) { f ->
                    val s = FrameCodec.parseFindMyBuds(f) ?: return@writeFrameAndVerify false
                    !s.leftBeeping && !s.rightBeeping
                }
                if (legacyRes is WriteResult.Verified) result = legacyRes
            }
            val st = if (result is WriteResult.Verified) FeatureStatus.REAL else FeatureStatus.UNVERIFIED
            base.update {
                it.copy(
                    findMyBuds = FindMyBudsState(false, false),
                    findBudsStatus = st,
                    findBudsDetail = if (result is WriteResult.Verified) "已停止蜂鸣并回读确认" else "已发送停止蜂鸣指令",
                    message = "查找耳机蜂鸣已停止。"
                )
            }
        }
    }

    // -------------------------------------------------------- Misc / System / Helpers

    fun setVolume(value: Int) {
        system.setMusicVolume(value)
        system.refresh()
    }

    fun refreshPermissionStates() {
        val appCtx = getApplication<Application>()
        _overlayGranted.value = EarbudsPopupOverlay.canDrawOverlays(appCtx)
        _fluidCloudReady.value = FluidCloudNotifier.canPostPromoted(appCtx)
    }

    fun openOverlaySettings() {
        val ctx = getApplication<Application>()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }

    fun openFluidCloudSettings() {
        FluidCloudNotifier.openPromotionSettings(getApplication())
    }

    fun dismissFluidCloud() {
        FluidCloudNotifier.dismissForSession(getApplication())
        base.update { it.copy(message = "已收起流体云胶囊") }
    }

    fun testFluidCloud() {
        val ctx = getApplication<Application>()
        refreshPermissionStates()
        val st = FluidCloudNotifier.statusSummary(ctx)
        if (!st.notificationsEnabled || (st.sdkSupported && !st.canPostPromoted)) {
            base.update { it.copy(message = "请先在系统通知管理中开启「实时通知 / 流体云」权限") }
            openFluidCloudSettings()
            return
        }
        FluidCloudNotifier.clearSessionDismissal()
        val effective = ensureMacSnapshotState()
        TakeoverBridge.pushUiState(
            context = ctx,
            state = effective,
            triggerPopup = true,
            forceOverlay = false,
            triggerMelodyCapsule = _takeoverConfig.value.melodySpoofEnabled
        )
        triggerBackgroundSyncIfOffline(triggerPopup = true)
        base.update { it.copy(message = "已推送 ColorOS 流体云胶囊与实时通知") }
    }

    fun pingMelodyHook() {
        if (!_takeoverConfig.value.melodySpoofEnabled) return
        val ctx = getApplication<Application>()
        TakeoverBridge.pushUiState(ctx, ensureMacSnapshotState(), triggerPopup = false)
    }

    fun restartAndWakeMelody() {
        if (_rootBusy.value) return
        _rootBusy.value = true
        val ctx = getApplication<Application>()
        TakeoverBridge.resetMelodyHookStatus("正在以 Root 权限重启并唤醒欢律…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = RootMelodyWake.restartAndWakeMelody()
                if (!res.ok) {
                    val reason = res.output.ifBlank { "未授予 Root 权限 (exit=${res.exitCode})" }
                    TakeoverBridge.resetMelodyHookStatus("Root 唤醒欢律失败：$reason")
                    base.update { it.copy(message = "Root 重启欢律失败：$reason") }
                    return@launch
                }
                delay(550L)
                val effective = ensureMacSnapshotState()
                TakeoverBridge.pushUiState(ctx, effective, triggerPopup = false)
                delay(500L)
                base.update {
                    it.copy(
                        message = if (TakeoverBridge.melodyHookAlive.value) {
                            "欢律已重启并握手成功：${TakeoverBridge.melodyHookStatus.value}"
                        } else {
                            "欢律已由 Root 拉起 (exit=0)，但未收到 LSPosed 握手；请确认已在 LSPosed 勾选 com.oplus.melody"
                        }
                    )
                }
            } finally {
                _rootBusy.value = false
            }
        }
    }

    fun triggerMelodyPopup() {
        val ctx = getApplication<Application>()
        val effective = ensureMacSnapshotState()
        val mac = effective.system.deviceAddress.ifBlank { takeoverPrefs.loadSnapshot().mac }
        if (mac.isBlank()) {
            base.update { it.copy(message = "未找到已配对 JBL Tune 230NC，请先在系统蓝牙配对或开启权限") }
            return
        }
        if (_rootBusy.value) return
        _rootBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var wakeOk = true
                var wakeOutput = ""
                if (!TakeoverBridge.melodyHookAlive.value) {
                    val wake = RootMelodyWake.wakeMelodyMainProcess()
                    wakeOk = wake.ok
                    wakeOutput = wake.output
                    if (wake.ok) {
                        delay(500L)
                    }
                }
                TakeoverBridge.pushUiState(
                    ctx,
                    effective,
                    triggerPopup = false,
                    triggerMelodyPopup = true,
                    triggerMelodyCapsule = false
                )
                triggerBackgroundSyncIfOffline(triggerPopup = false)
                delay(900L)

                if (TakeoverBridge.appInForeground.get()) {
                    if (TakeoverBridge.melodyHookAlive.value) {
                        val preset = BridgeContract.presetByName(_takeoverConfig.value.spoofModelName)
                        val rootLaunch = RootMelodyWake.launchDiscoveryActivityAsRoot(
                            mac = mac,
                            pidHex = preset.fallbackProductIdHex,
                            modelName = preset.melodyName,
                            productType = preset.productType,
                            color = preset.defaultColor
                        )
                        base.update {
                            it.copy(
                                message = if (rootLaunch.ok) {
                                    "已通过 Root 拉起欢律原生 3D 弹窗（${preset.melodyName}）"
                                } else {
                                    "${TakeoverBridge.melodyHookStatus.value}；后台弹框被系统拦截且 Root 失败：${rootLaunch.output}"
                                }
                            )
                        }
                    } else {
                        base.update {
                            it.copy(
                                message = if (wakeOk) {
                                    "已唤醒欢律，但 LSPosed Hook 未握手！请在 LSPosed 勾选「欢律(com.oplus.melody)」，再点「Root 重启欢律」（首次启用模块建议重启手机）"
                                } else {
                                    "未能唤醒欢律（${wakeOutput.ifBlank { "Root 权限未授予" }}），且未收到 LSPosed Hook 握手"
                                }
                            )
                        }
                    }
                } else {
                    base.update {
                        it.copy(message = "欢律原生 3D 弹窗已显示：${TakeoverBridge.melodyHookStatus.value}")
                    }
                }
            } finally {
                _rootBusy.value = false
            }
        }
    }

    fun triggerMelodyCapsule() {
        val ctx = getApplication<Application>()
        val effective = ensureMacSnapshotState()
        if (_rootBusy.value) return
        _rootBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!TakeoverBridge.melodyHookAlive.value) {
                    val wake = RootMelodyWake.wakeMelodyMainProcess()
                    if (wake.ok) {
                        delay(500L)
                    }
                }
                TakeoverBridge.pushUiState(
                    ctx,
                    effective,
                    triggerPopup = false,
                    triggerMelodyPopup = false,
                    triggerMelodyCapsule = true
                )
                triggerBackgroundSyncIfOffline(triggerPopup = false)
                delay(500L)
                base.update {
                    it.copy(
                        message = if (TakeoverBridge.melodyHookAlive.value) {
                            TakeoverBridge.melodyHookStatus.value
                        } else {
                            "未收到欢律 Hook 握手，请点「Root 重启欢律」或确认已在 LSPosed 勾选 com.oplus.melody"
                        }
                    )
                }
            } finally {
                _rootBusy.value = false
            }
        }
    }

    fun openMelodyDetail() {
        val ctx = getApplication<Application>()
        val effective = ensureMacSnapshotState()
        val mac = effective.system.deviceAddress.ifBlank { takeoverPrefs.loadSnapshot().mac }
        if (mac.isBlank()) {
            base.update { it.copy(message = "未找到已配对 JBL Tune 230NC，请先在系统蓝牙配对或开启权限") }
            return
        }
        if (_rootBusy.value) return
        _rootBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!TakeoverBridge.melodyHookAlive.value) {
                    val wake = RootMelodyWake.wakeMelodyMainProcess()
                    if (wake.ok) delay(450L)
                }
                TakeoverBridge.pushUiState(
                    ctx,
                    effective,
                    triggerPopup = false,
                    triggerMelodyPopup = false,
                    triggerMelodyCapsule = false
                )
                delay(180L)
                val preset = BridgeContract.presetByName(_takeoverConfig.value.spoofModelName)
                val rootLaunch = RootMelodyWake.launchDetailActivityAsRoot(
                    mac = mac,
                    pidHex = preset.fallbackProductIdHex,
                    modelName = preset.melodyName,
                    color = preset.defaultColor
                )
                if (!rootLaunch.ok) {
                    val intent = Intent().apply {
                        setClassName(
                            BridgeContract.PKG_MELODY,
                            "com.oplus.melody.ui.component.detail.DetailMainActivity"
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("device_mac_info", mac)
                        putExtra("product_id", preset.fallbackProductIdHex)
                        putExtra("device_name", preset.melodyName)
                        putExtra("product_color", preset.defaultColor.toString())
                    }
                    val ok = runCatching { ctx.startActivity(intent) }.isSuccess
                    base.update {
                        it.copy(
                            message = if (ok) "正在打开系统「${preset.melodyName}」耳机详情页…"
                            else "打开系统耳机页失败（请确认已勾选 LSPosed 作用域并重启欢律）"
                        )
                    }
                }
            } finally {
                _rootBusy.value = false
            }
        }
    }

    fun updateTakeoverConfig(transform: (TakeoverConfig) -> TakeoverConfig) {
        val prev = _takeoverConfig.value
        val next = transform(prev)
        _takeoverConfig.value = next
        takeoverPrefs.saveConfig(next)
        if (!next.useFluidCloud || !next.enabled) {
            FluidCloudNotifier.dismissForSession(getApplication())
        } else if (!prev.useFluidCloud && next.useFluidCloud) {
            FluidCloudNotifier.clearSessionDismissal()
        }
        TakeoverBridge.pushUiState(getApplication(), state.value, triggerPopup = false)
    }

    fun triggerTakeoverSync(triggerPopup: Boolean = false, forceOverlay: Boolean = false) {
        val ctx = getApplication<Application>()
        refreshPermissionStates()
        if (forceOverlay && !_overlayGranted.value) {
            base.update {
                it.copy(message = "请先开启“悬浮窗”权限，再预览传统弹窗卡片")
            }
            openOverlaySettings()
            return
        }
        val effective = ensureMacSnapshotState()
        TakeoverBridge.pushUiState(ctx, effective, triggerPopup = triggerPopup, forceOverlay = forceOverlay)
        triggerBackgroundSyncIfOffline(triggerPopup = triggerPopup)
        base.update {
            it.copy(
                message = when {
                    forceOverlay -> "已预览传统底部悬浮卡片（支持下拉关闭）"
                    triggerPopup -> "已推送至 ColorOS 流体云"
                    else -> "正在同步最新电量与降噪状态"
                }
            )
        }
    }

    private fun ensureMacSnapshotState(): UiState {
        val current = state.value
        var mac = current.system.deviceAddress
        if (mac.isBlank()) {
            mac = runCatching {
                scanner.findClassicDevice(BluetoothAdapter.getDefaultAdapter(), null)?.address
            }.getOrNull().orEmpty()
            if (mac.isNotBlank()) system.start(mac)
        }
        return if (mac.isNotBlank() && current.system.deviceAddress.isBlank()) {
            current.copy(system = current.system.copy(deviceAddress = mac, deviceName = "JBL Tune 230NC"))
        } else {
            current
        }
    }

    private fun triggerBackgroundSyncIfOffline(triggerPopup: Boolean) {
        val ctx = getApplication<Application>()
        if (!state.value.controlReady && !TakeoverBridge.foregroundLinkActive.get()) {
            val svcIntent = Intent(ctx, JblBridgeService::class.java).apply {
                putExtra(BridgeContract.EXTRA_CMD, BridgeContract.CMD_CONNECT_AND_SYNC)
                putExtra(BridgeContract.EXTRA_TRIGGER_POPUP, triggerPopup)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(svcIntent)
                } else {
                    ctx.startService(svcIntent)
                }
            }
        }
    }

    fun clearLog() = log.clear()

    fun buildDiagnosticReport(): String {
        refreshPermissionStates()
        val ctx = getApplication<Application>()
        val env = DiagnosticExporter.collectEnv(
            state = state.value,
            cfg = _takeoverConfig.value,
            overlayGranted = _overlayGranted.value,
            fluidStatus = FluidCloudNotifier.statusSummary(ctx),
            discoveredServices = transport.discoveredServicesSummary
        )
        return DiagnosticReportFormatter.format(env, _diagSteps.value, logs.value)
    }

    fun copyDiagnostics() {
        val ctx = getApplication<Application>()
        DiagnosticExporter.copyToClipboard(ctx, buildDiagnosticReport())
        base.update { it.copy(message = "完整诊断报告与帧日志已复制到剪贴板。") }
    }

    fun shareDiagnostics() {
        val ctx = getApplication<Application>()
        val ok = DiagnosticExporter.shareAsFile(ctx, buildDiagnosticReport())
        base.update {
            it.copy(
                message = if (ok) "正在拉起系统分享菜单导出 .txt 日志文件…" else "分享导出失败，请尝试复制或保存文件。"
            )
        }
    }

    fun setToastMessage(msg: String) {
        base.update { it.copy(message = msg) }
    }

    /**
     * Runs a comprehensive, non-destructive self-test across permissions, BLE GATT handshake,
     * every safe readback opcode, and a silent same-mode ANC roundtrip verification.
     */
    fun runSelfTest() {
        if (_diagRunning.value) return
        _diagRunning.value = true
        _diagSteps.value = emptyList()
        _diagProgress.value = "正在检查权限与蓝牙设备..."
        log.add("i", "", "开始一键全量协议自检 (v${com.jblt230.ctrl.BuildConfig.VERSION_NAME})")

        viewModelScope.launch {
            try {
                // Serialize against any in-flight refreshAll() so zero frames collide on the single GATT link.
                refreshJob?.join()

                fun appendStep(step: DiagStep) {
                    _diagSteps.value = _diagSteps.value + step
                }

                // 1. Permissions & paired device check
                refreshPermissionStates()
                val adapter = BluetoothAdapter.getDefaultAdapter()
                val classic = runCatching { scanner.findClassicDevice(adapter, null) }.getOrNull()
                if (!state.value.permissionsGranted) {
                    appendStep(
                        DiagStep(
                            key = "permissions",
                            label = "蓝牙权限与目标耳机",
                            status = DiagStatus.FAIL,
                            detail = "缺少 BLUETOOTH_CONNECT / SCAN 权限"
                        )
                    )
                    _diagProgress.value = "自检中止：缺少蓝牙权限"
                    return@launch
                }
                if (classic == null) {
                    appendStep(
                        DiagStep(
                            key = "paired",
                            label = "蓝牙权限与目标耳机",
                            status = DiagStatus.FAIL,
                            detail = "未在系统已配对列表找到 JBL TUNE230 设备"
                        )
                    )
                    _diagProgress.value = "自检中止：未发现已配对耳机"
                    return@launch
                }
                system.start(classic.address)
                appendStep(
                    DiagStep(
                        key = "paired",
                        label = "系统蓝牙与 ColorOS 基线",
                        status = DiagStatus.PASS,
                        detail = "已配对 ${BridgeContract.cleanDisplayName(classic.name)} · " +
                            "流体云=${if (_fluidCloudReady.value) "已授权" else "未授权"} · " +
                            "欢律Hook=${if (TakeoverBridge.melodyHookAlive.value) "已握手" else "待激活"}"
                    )
                )

                // 2. Ensure GATT ready
                if (!state.value.controlReady) {
                    _diagProgress.value = "正在连接 BLE GATT 控制链路..."
                    TakeoverBridge.foregroundLinkActive.set(true)
                    runCatching {
                        val appCtx = getApplication<Application>()
                        appCtx.stopService(Intent(appCtx, JblBridgeService::class.java))
                    }
                    delay(250L)
                    val t0 = System.currentTimeMillis()
                    var viaLabel = "Classic回退"
                    var ready: LinkState.Ready? = null
                    val cachedLeMac = takeoverPrefs.loadSnapshot().leMac
                    if (cachedLeMac.isNotBlank()) {
                        val cachedLe = runCatching { adapter.getRemoteDevice(cachedLeMac) }.getOrNull()
                        if (cachedLe != null) {
                            ready = connectAndWait(cachedLe)
                            if (ready != null) {
                                viaLabel = "缓存LE"
                            } else {
                                transport.release()
                            }
                        }
                    }
                    if (ready == null) {
                        val le = scanner.scanForLeSide(adapter)
                        if (le != null) {
                            val snapNow = takeoverPrefs.loadSnapshot()
                            if (snapNow.leMac != le.address) {
                                takeoverPrefs.saveSnapshot(snapNow.copy(leMac = le.address))
                            }
                            ready = connectAndWait(le)
                            if (ready != null) {
                                viaLabel = "扫描LE"
                            } else {
                                transport.release()
                            }
                        }
                    }
                    if (ready == null) {
                        ready = connectAndWait(classic)
                        if (ready != null) viaLabel = "Classic回退"
                    }
                    val dt = System.currentTimeMillis() - t0
                    if (ready == null) {
                        TakeoverBridge.foregroundLinkActive.set(false)
                        val failReason = (state.value.link as? LinkState.Failed)?.reason ?: "连接/发现超时"
                        appendStep(
                            DiagStep(
                                key = "gatt_link",
                                label = "BLE GATT 控制建链",
                                status = DiagStatus.FAIL,
                                durationMs = dt,
                                detail = "$failReason | 服务=${transport.discoveredServicesSummary.ifBlank { "无" }}"
                            )
                        )
                        _diagProgress.value = "自检中止：$failReason（可导出日志排查）"
                        return@launch
                    }
                    appendStep(
                        DiagStep(
                            key = "gatt_link",
                            label = "BLE GATT 控制建链",
                            status = DiagStatus.PASS,
                            durationMs = dt,
                            detail = "MTU=${ready.mtu}, via=$viaLabel"
                        )
                    )
                } else {
                    appendStep(
                        DiagStep(
                            key = "gatt_link",
                            label = "BLE GATT 控制建链",
                            status = DiagStatus.PASS,
                            detail = "复用当前在线链路"
                        )
                    )
                }

                // 3. Table-driven safe read probes
                data class ReadProbe(
                    val key: String,
                    val label: String,
                    val cmdName: String,
                    val optional: Boolean = false,
                    val skipReason: String = "",
                    val filter: (Frame) -> Boolean = { true },
                    val summarize: (Frame) -> String?
                )

                val probes = listOf(
                    ReadProbe("device_info", "设备信息 (0x21)", "device_info_read") { f ->
                        FrameCodec.parseDeviceInfo(f)?.let { "FW=${it.firmwareVersion}, PID=${it.productIdHex}, TWS=${it.twsConnected}" }
                    },
                    ReadProbe("battery", "三端电量 (0x25)", "battery_read") { f ->
                        FrameCodec.parseBattery(f)?.let { "L=${it.left}%, R=${it.right}%, 盒=${it.case}%" }
                    },
                    ReadProbe(
                        "all_status",
                        "全量状态合集 (0x30)",
                        "status_read_all",
                        filter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x30 }
                    ) { f ->
                        FrameCodec.parseAllStatus(f)?.let { "Noise=${it.noiseMode}, EQ=0x%02X".format(it.eqPresetByte) }
                    },
                    ReadProbe(
                        "anc",
                        "降噪状态 (0x12)",
                        "anc_read",
                        filter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x12 }
                    ) { f ->
                        FrameCodec.parseNoiseMode(f)?.let { "模式=${it.label}" }
                    },
                    ReadProbe(
                        "eq_preset",
                        "EQ 预设状态 (0x34)",
                        "eq_preset_status_read",
                        filter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x34 }
                    ) { f ->
                        FrameCodec.parseEqPresetStatus(f)?.let { "槽位=0x%02X".format(it) }
                    },
                    ReadProbe("gesture", "触控手势 (0x77)", "gesture_read_all") { f ->
                        FrameCodec.parseGestures(f)?.let { "左=${it.leftGroup.label}, 右=${it.rightGroup.label}" }
                    },
                    ReadProbe("smart_av", "智能音视频 (0x83)", "smart_av_read") { f ->
                        FrameCodec.parseSmartSwitch(f)?.let { "模式=${it.label}" }
                    },
                    ReadProbe("voice_aware", "通话自听 VoiceAware (0x98)", "voice_aware_read") { f ->
                        FrameCodec.parseVoiceAware(f)?.let { "等级=${it.level}" }
                    },
                    ReadProbe(
                        "auto_play",
                        "自动播放暂停 (0x38)",
                        "auto_play_pause_read",
                        filter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x38 }
                    ) { f ->
                        FrameCodec.parseAutoPlayPauseStatus(f)?.let { "开启=$it" }
                    },
                    ReadProbe(
                        "multi_ai",
                        "语音助手偏好 (0x35)",
                        "multi_ai_read",
                        filter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x35 }
                    ) { f ->
                        FrameCodec.parseMultiAiStatus(f)?.let { "mode=0x%02X".format(it) }
                    },
                    ReadProbe("standby", "待机保留ANC (0x96)", "standby_mode_read") { f ->
                        FrameCodec.parseStandbyModeKeepAnc(f)?.let { "保留=$it" }
                    },
                    ReadProbe(
                        "auto_off",
                        "自动关机 (0x33)",
                        "auto_off_read",
                        filter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x33 }
                    ) { f ->
                        FrameCodec.parseAutoOffStatus(f)?.let { (en, min) -> "开启=$en, ${min}m" }
                    },
                    ReadProbe("voice_lang", "语音包版本 (0x93 01)", "voice_prompt_lang_read") { f ->
                        FrameCodec.parseVoicePrompt(f)?.let { "lang=${it.languageId}, v=${it.version ?: "--"}" }
                    },
                    ReadProbe("multi_status", "复合通话状态 (0x9B)", "multi_status_read") { f ->
                        FrameCodec.parseMultiStatusCall(f)?.let { "通话中=$it" }
                    },
                    ReadProbe("serial", "序列号 (S/N)", "serial_number_read") { f ->
                        FrameCodec.parseSerialNumber(f)
                    },
                    ReadProbe("case_ver", "充电盒版本", "case_version_read") { f ->
                        FrameCodec.parseCaseVersion(f)
                    },
                    // Optional HW / firmware-dependent capability probes (not present on Tune 230NC FW 3.7.0)
                    ReadProbe(
                        "adv_anc",
                        "环境感知等级 (0x91 21)",
                        "advance_anc_read",
                        optional = true,
                        skipReason = "当前固件未开放 7 级环境感知调节",
                        filter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x22 }
                    ) { f ->
                        FrameCodec.parseAdvanceAnc(f)?.let { "等级=${it.ambientAwareLevel}/7" }
                    },
                    ReadProbe(
                        "anc_sub",
                        "ANC 子模式 (0x91 01)",
                        "anc_submode_read",
                        optional = true,
                        skipReason = "当前固件未开放 ANC 子模式接口",
                        filter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x02 }
                    ) { f ->
                        FrameCodec.parseAncSubMode(f)?.let { "subMode=$it" }
                    },
                    ReadProbe(
                        "in_ear",
                        "入耳传感器 (0x41)",
                        "in_ear_read",
                        optional = true,
                        skipReason = "该耳机硬件无光学入耳传感器",
                        filter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x41 }
                    ) { f ->
                        FrameCodec.parseInEarStatus(f)?.let { "L=${it.leftInEar}, R=${it.rightInEar}" }
                    },
                    ReadProbe(
                        "tips_type",
                        "耳塞尺寸标记 (0x9C)",
                        "tips_type_read",
                        optional = true,
                        skipReason = "当前固件未开放 0x9C 特性"
                    ) { f ->
                        FrameCodec.parseTipsType(f)?.let { "type=$it" }
                    },
                    ReadProbe(
                        "voice_tone",
                        "仅提示音状态 (0x93 04)",
                        "voice_prompt_status_read",
                        optional = true,
                        skipReason = "当前固件不支持 0x93 04 查询"
                    ) { f ->
                        FrameCodec.parseVoicePrompt(f)?.let { "仅提示音=${it.toneOnly}" }
                    }
                )

                var detectedNoise: NoiseMode? = null
                for (probe in probes) {
                    _diagProgress.value = "测试中：${probe.label}..."
                    val t0 = System.currentTimeMillis()
                    val timeout = if (probe.optional) 350L else null
                    val frame = runCatching { queue.read(probe.cmdName, timeoutOverrideMs = timeout, match = probe.filter) }.getOrNull()
                    val dt = System.currentTimeMillis() - t0
                    if (frame == null) {
                        appendStep(
                            DiagStep(
                                key = probe.key,
                                label = probe.label,
                                status = if (probe.optional) DiagStatus.SKIP else DiagStatus.FAIL,
                                durationMs = dt,
                                detail = if (probe.optional) probe.skipReason else "超时或未收到匹配回复"
                            )
                        )
                        continue
                    }
                    if (probe.key == "anc") {
                        detectedNoise = FrameCodec.parseNoiseMode(frame)
                    }
                    val parsed = probe.summarize(frame)
                    appendStep(
                        DiagStep(
                            key = probe.key,
                            label = probe.label,
                            status = if (parsed != null) DiagStatus.PASS else DiagStatus.WARN,
                            durationMs = dt,
                            detail = parsed ?: "收到回包但字段未匹配已知格式",
                            rawHex = frame.hex
                        )
                    )
                }

                // 4. Multi-part BES Custom EQ Read (0x42/0x43)
                _diagProgress.value = "测试中：多包 EQ 读取 (0x42/0x43)..."
                val eqT0 = System.currentTimeMillis()
                val customEq = runCatching { queue.readCustomEq(EqCodec.CAT_GRAPHIC_EQ) }.getOrNull()
                val eqDt = System.currentTimeMillis() - eqT0
                appendStep(
                    DiagStep(
                        key = "eq_custom",
                        label = "多包 EQ 回读 (0x42/0x43)",
                        status = if (customEq != null) DiagStatus.PASS else DiagStatus.WARN,
                        durationMs = eqDt,
                        detail = if (customEq != null) {
                            "slot=0x%02X, gains=%s".format(customEq.presetIndex, customEq.gains.joinToString(","))
                        } else {
                            "分包回读超时或当前处于预设模式"
                        }
                    )
                )

                // 5. Non-destructive bidirectional write + readback verification using current noise mode
                val safeTargetNoise = detectedNoise ?: state.value.noiseMode
                val writeCmdName = when (safeTargetNoise) {
                    NoiseMode.OFF -> "noise_all_off"
                    NoiseMode.ANC -> "anc_on"
                    NoiseMode.AMBIENT_AWARE -> "ambient_aware_on"
                    NoiseMode.TALKTHRU -> "talkthru_on"
                }
                _diagProgress.value = "测试中：降噪双向写回读闭环 (${safeTargetNoise.label})..."
                val wT0 = System.currentTimeMillis()
                val writeRes = runCatching {
                    queue.writeAndVerify(
                        name = writeCmdName,
                        readFilter = { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x12 }
                    ) { FrameCodec.parseNoiseMode(it) == safeTargetNoise }
                }.getOrElse { WriteResult.Unverified(it.message ?: "异常") }
                val wDt = System.currentTimeMillis() - wT0
                appendStep(
                    when (writeRes) {
                        is WriteResult.Verified -> DiagStep(
                            key = "noise_roundtrip",
                            label = "双向写入+回读闭环",
                            status = DiagStatus.PASS,
                            durationMs = wDt,
                            detail = "无感写入 ${safeTargetNoise.label} 并回读一致",
                            rawHex = writeRes.readback.hex
                        )
                        is WriteResult.Unverified -> DiagStep(
                            key = "noise_roundtrip",
                            label = "双向写入+回读闭环",
                            status = DiagStatus.FAIL,
                            durationMs = wDt,
                            detail = writeRes.reason
                        )
                        is WriteResult.Refused -> DiagStep(
                            key = "noise_roundtrip",
                            label = "双向写入+回读闭环",
                            status = DiagStatus.WARN,
                            durationMs = wDt,
                            detail = writeRes.reason
                        )
                    }
                )

                // 6. CommandGuard safety check (in-memory only, never sent over BLE)
                val guardBlocked = try {
                    guard.assertAllowed(byteArrayOf(0xAA.toByte(), 0xF0.toByte(), 0x00))
                    false
                } catch (_: SecurityException) {
                    true
                }
                appendStep(
                    DiagStep(
                        key = "guard_check",
                        label = "黑名单拦截校验 (CommandGuard)",
                        status = if (guardBlocked) DiagStatus.PASS else DiagStatus.FAIL,
                        detail = if (guardBlocked) "危险操作码 0xF0 已在发送前被拒绝" else "未拦截黑名单操作码"
                    )
                )

                refreshAll()
                val p = _diagSteps.value.count { it.status == DiagStatus.PASS }
                val w = _diagSteps.value.count { it.status == DiagStatus.WARN }
                val f = _diagSteps.value.count { it.status == DiagStatus.FAIL }
                val s = _diagSteps.value.count { it.status == DiagStatus.SKIP }
                val summary = "自检完成：通过 $p · 跳过(硬件不支持) $s · 警告 $w · 失败 $f"
                _diagProgress.value = summary
                base.update { it.copy(message = summary) }
            } catch (e: Throwable) {
                val msg = "自检异常：${e.message ?: e.javaClass.simpleName}"
                _diagSteps.value = _diagSteps.value + DiagStep("error", "异常中断", DiagStatus.FAIL, detail = msg)
                _diagProgress.value = msg
                base.update { it.copy(message = msg) }
            } finally {
                _diagRunning.value = false
            }
        }
    }

    private fun List<FeatureUi>.withStatus(
        key: String,
        status: FeatureStatus,
        detail: String,
        actionable: Boolean
    ): List<FeatureUi> = map {
        if (it.key == key) it.copy(status = status, detail = detail, actionable = actionable) else it
    }

    private fun voiceAwareLabel(level: Int): String = when (level) {
        0 -> "关闭"
        1 -> "低"
        2 -> "中"
        else -> "高"
    }

    private fun loadEqPresets(): List<EqPreset> = runCatching {
        val root = JSONObject(getApplication<Application>().assets.open("eq_presets.json")
            .bufferedReader().use { it.readText() })
        val arr = root.getJSONArray("presets")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val g = o.getJSONArray("gains")
            EqPreset(
                id = o.getString("id"),
                label = o.getString("label"),
                category = o.optInt("category", 230),
                cmdIndex = o.optInt("cmd_index", 0),
                gains = List(g.length()) { g.getDouble(it).toFloat() }
            )
        }
    }.getOrDefault(listOf(EqPreset("off", "Off", 0, 0, List(10) { 0f })))

    private fun loadBands(): List<Int> = runCatching {
        val root = JSONObject(getApplication<Application>().assets.open("eq_presets.json")
            .bufferedReader().use { it.readText() })
        val arr = root.getJSONArray("bands_hz")
        List(arr.length()) { arr.getInt(it) }
    }.getOrDefault(listOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000))

    override fun onCleared() {
        findBudsAutoStopJob?.cancel()
        TakeoverBridge.foregroundLinkActive.set(false)
        if (bridgeRelayRegistered) {
            runCatching { getApplication<Application>().unregisterReceiver(bridgeRelayReceiver) }
            bridgeRelayRegistered = false
        }
        transport.release()
        system.stop()
        super.onCleared()
    }
}
