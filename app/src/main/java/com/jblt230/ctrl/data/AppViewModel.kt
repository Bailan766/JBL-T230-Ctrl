package com.jblt230.ctrl.data

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
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
import com.jblt230.ctrl.bridge.ColorOsBridge
import com.jblt230.ctrl.bridge.ColorOsPrefs
import com.jblt230.ctrl.bridge.ColorOsSpoofConfig
import com.jblt230.ctrl.bridge.JblBridgeService
import com.jblt230.ctrl.logging.ProtocolLog
import com.jblt230.ctrl.protocol.CommandTable
import com.jblt230.ctrl.protocol.Confidence
import com.jblt230.ctrl.system.SystemBaseline
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
    private val colorOsPrefs = ColorOsPrefs(app)

    private val _colorOsConfig = MutableStateFlow(colorOsPrefs.loadConfig())
    val colorOsConfig: StateFlow<ColorOsSpoofConfig> = _colorOsConfig

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
                BridgeContract.CMD_SET_EQ_PRESET -> {
                    val presetId = intent.getStringExtra(BridgeContract.EXTRA_EQ_PRESET) ?: "off"
                    val preset = eqPresets.firstOrNull { it.id == presetId }
                    if (preset != null) setEqPreset(preset)
                }
                BridgeContract.CMD_FIND_BUDS -> {
                    val enable = intent.getBooleanExtra(BridgeContract.EXTRA_FIND_LEFT, false)
                    if (enable) {
                        base.update { it.copy(findBudsPendingLeft = true, findBudsPendingRight = true) }
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
                message = "非官方互操作研究工具。所有固件级写入需经耳机回读一致后才标记为真实。"
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
                ColorOsBridge.pushUiState(getApplication(), s, triggerPopup = false)
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

    fun connect() {
        if (!state.value.permissionsGranted) {
            base.update { it.copy(message = "缺少附近设备权限，无法连接耳机") }
            return
        }
        viewModelScope.launch {
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
                ColorOsBridge.foregroundLinkActive.set(true)

                var ready: LinkState.Ready? = null
                base.update { it.copy(message = "扫描 JBL TUNE230NC TWS-LE 控制侧...") }
                val le = scanner.scanForLeSide(adapter)
                if (le != null) ready = connectAndWait(le)
                if (ready == null) {
                    transport.release()
                    base.update { it.copy(message = "LE 扫描未命中，回退直连 classic 地址...") }
                    ready = connectAndWait(classic)
                }
                if (ready == null) {
                    ColorOsBridge.foregroundLinkActive.set(false)
                    base.update {
                        it.copy(message = "未能建立控制链路。系统层状态仍可用；固件级功能保持未验证。")
                    }
                    return@launch
                }
                base.update { it.copy(message = "控制链路已建立，正在执行官方握手与全量状态回读...") }
                refreshAll()
            } catch (e: Throwable) {
                ColorOsBridge.foregroundLinkActive.set(false)
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
        ColorOsBridge.foregroundLinkActive.set(false)
        transport.release()
        base.update {
            it.copy(message = "已释放控制链路，官方 JBL app 现在可以连接（音频不受影响）。")
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            try {
                if (!state.value.controlReady) {
                    system.refresh()
                    return@launch
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
    }

    // -------------------------------------------------------- Handshake & Device Info

    private suspend fun readDeviceInfoAndHandshake() {
        val infoFrame = queue.read("device_info_read")
        val info = infoFrame?.let { FrameCodec.parseDeviceInfo(it) }
        // Send official sync_app_status handshake frame (AA 10 02 00 00)
        queue.read("sync_app_status")
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

        val subFrame = queue.read("anc_submode_read") { f ->
            f.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x02
        }
        val subMode = subFrame?.let { FrameCodec.parseAncSubMode(it) }

        val advFrame = queue.read("advance_anc_read") { f ->
            f.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x22
        }
        val adv = advFrame?.let { FrameCodec.parseAdvanceAnc(it) }

        base.update { s ->
            val resolvedMode = mode ?: s.noiseMode
            val status = if (mode != null) FeatureStatus.READ_ONLY else s.noiseStatus
            s.copy(
                noiseMode = resolvedMode,
                ancSubMode = subMode ?: s.ancSubMode,
                ambientAwareLevel = adv?.ambientAwareLevel?.coerceIn(1, 7) ?: s.ambientAwareLevel,
                noiseStatus = status,
                noiseDetail = if (mode != null)
                    "已从耳机读取：$resolvedMode（环境感知等级 ${adv?.ambientAwareLevel ?: s.ambientAwareLevel}）"
                else "0x91 未应答；可从全状态回读",
                features = s.features.withStatus("anc", status, "模式可读；写入后回读一致标为真实", mode != null)
            )
        }
    }

    fun setNoiseMode(mode: NoiseMode) {
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
                        noiseDetail = "下发后回读一致：${result.readback.hex}",
                        message = "降噪/环境音模式已生效并回读确认。",
                        features = it.features.withStatus("anc", FeatureStatus.REAL, "写入 + 回读一致", true)
                    )
                }
            } else {
                // Re-read in-ear & TWS status so we can give an accurate physical reason if rejected
                val inEar = queue.read("in_ear_read") {
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
                        inEar = inEar ?: it.inEar,
                        noiseStatus = if (result is WriteResult.Refused) FeatureStatus.DEGRADED else FeatureStatus.UNVERIFIED,
                        noiseDetail = fullDetail,
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
                it.copy(
                    activePresetByte = presetByte ?: custom.presetIndex,
                    eqPresetId = resolvedId,
                    eqGains = custom.gains,
                    eqStatus = FeatureStatus.READ_ONLY,
                    eqDetail = "已通过 0x42/0x43 多包回读耳机 10 段 EQ 曲线（preset=$resolvedId）",
                    features = it.features.withStatus("eq", FeatureStatus.READ_ONLY, "0x42/0x43 10 段曲线已读取；写入需回读比对", true)
                )
            }
            return
        }

        // Fallback to A2 read if 0x42/0x43 is unsupported
        val a2Frame = queue.read("eq_read_in_use")
        val a2Cat = a2Frame?.let { EqCodec.parseCategory(it) }
        base.update {
            val st = if (presetByte != null || a2Cat != null) FeatureStatus.READ_ONLY else FeatureStatus.UNVERIFIED
            it.copy(
                activePresetByte = presetByte ?: it.activePresetByte,
                eqPresetId = presetByte?.let { b -> EqCodec.presetByteToId(b) } ?: it.eqPresetId,
                eqStatus = st,
                eqDetail = when {
                    presetByte != null -> "已读取当前 EQ 预设号 0x%02X（0x43 曲线未返回）".format(presetByte)
                    a2Cat != null -> "A2 回复当前 EQ category=$a2Cat"
                    else -> "未收到 0x42/0x43 或 0xA2 EQ 回复"
                },
                features = it.features.withStatus("eq", st, it.eqDetail, st != FeatureStatus.UNVERIFIED)
            )
        }
    }

    fun setEqPreset(preset: EqPreset) {
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
        viewModelScope.launch {
            val presetByte = EqCodec.presetIdToByte(preset.id) ?: preset.cmdIndex
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
                val newStatus = if (verified) FeatureStatus.REAL else FeatureStatus.UNVERIFIED
                val detail = when {
                    curveResult.verified -> "EQ 预设 ${preset.label} 已下发并通过 0x42/0x43 回读一致"
                    presetResult is WriteResult.Verified -> "EQ 预设 ${preset.label} 已切换（0x40 -> 0x210134 回读一致）"
                    a2Verified -> "EQ 预设已通过 A2 写入并回读一致"
                    else -> "EQ 未确认生效：${curveResult.reason}"
                }
                it.copy(
                    eqPresetId = preset.id,
                    activePresetByte = presetByte,
                    eqGains = curveResult.readbackState?.gains ?: preset.gains,
                    eqStatus = newStatus,
                    eqDetail = detail,
                    message = if (verified) "EQ 预设「${preset.label}」已生效并回读确认。" else detail,
                    features = it.features.withStatus("eq", newStatus, detail, true)
                )
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
                    "自定义 10 段 EQ 已写入并通过 0x42/0x43 回读一致。"
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
                        gestureStatus = FeatureStatus.UNVERIFIED,
                        gestureDetail = result.reason,
                        message = result.reason
                    )
                    is WriteResult.Refused -> it.copy(message = result.reason)
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
                        smartAudioStatus = FeatureStatus.UNVERIFIED,
                        smartAudioDetail = result.reason,
                        message = result.reason
                    )
                    is WriteResult.Refused -> it.copy(message = result.reason)
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
                        voiceAwareStatus = FeatureStatus.UNVERIFIED,
                        voiceAwareDetail = result.reason,
                        message = result.reason
                    )
                    is WriteResult.Refused -> it.copy(message = result.reason)
                }
            }
        }
    }

    // -------------------------------------------------------- Wearing, Auto Play/Pause, Fit Test, Tips

    private suspend fun readWearingAndTips() {
        val inEarFrame = queue.read("in_ear_read") { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x41 }
        val inEar = inEarFrame?.let { FrameCodec.parseInEarStatus(it) }
        val appFrame = queue.read("auto_play_pause_read") { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x38 }
        val autoPlay = appFrame?.let { FrameCodec.parseAutoPlayPauseStatus(it) }
        val tipsFrame = queue.read("tips_type_read")
        val tips = tipsFrame?.let { FrameCodec.parseTipsType(it) }

        base.update { s ->
            val resolvedAuto = autoPlay ?: s.autoPlayPause
            val status = if (autoPlay != null || inEar != null) FeatureStatus.READ_ONLY else s.autoPlayPauseStatus
            s.copy(
                inEar = inEar ?: s.inEar,
                autoPlayPause = resolvedAuto,
                autoPlayPauseStatus = status,
                tipsType = tips ?: s.tipsType,
                tipsTypeStatus = if (tips != null) FeatureStatus.READ_ONLY else s.tipsTypeStatus,
                features = s.features.withStatus("wearing", status, "入耳与自动播放暂停已读取", true)
            )
        }
    }

    fun setAutoPlayPause(enabled: Boolean) {
        if (!state.value.controlReady) return
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
                        autoPlayPauseStatus = FeatureStatus.UNVERIFIED,
                        message = result.reason
                    )
                    is WriteResult.Refused -> it.copy(message = result.reason)
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
        val vpStatusFrame = queue.read("voice_prompt_status_read")
        val vpStatus = vpStatusFrame?.let { FrameCodec.parseVoicePrompt(it) }
        val vpLangFrame = queue.read("voice_prompt_lang_read")
        val vpLang = vpLangFrame?.let { FrameCodec.parseVoicePrompt(it) }
        val aiFrame = queue.read("multi_ai_read") { it.payload.firstOrNull()?.toInt()?.and(0xFF) == 0x35 }
        val ai = aiFrame?.let { FrameCodec.parseMultiAiStatus(it) }

        base.update { s ->
            val stAny = if (keepAnc != null || autoOff != null || vpStatus != null || ai != null)
                FeatureStatus.READ_ONLY else s.standbyStatus
            s.copy(
                standbyKeepAnc = keepAnc ?: s.standbyKeepAnc,
                standbyStatus = if (keepAnc != null) FeatureStatus.READ_ONLY else s.standbyStatus,
                autoOffEnabled = autoOff?.first ?: s.autoOffEnabled,
                autoOffMinutes = autoOff?.second ?: s.autoOffMinutes,
                autoOffStatus = if (autoOff != null) FeatureStatus.READ_ONLY else s.autoOffStatus,
                voicePromptToneOnly = vpStatus?.toneOnly ?: s.voicePromptToneOnly,
                voicePromptInfo = vpLang ?: vpStatus ?: s.voicePromptInfo,
                voicePromptStatus = if (vpStatus != null || vpLang != null) FeatureStatus.READ_ONLY else s.voicePromptStatus,
                multiAiMode = ai ?: s.multiAiMode,
                multiAiStatus = if (ai != null) FeatureStatus.READ_ONLY else s.multiAiStatus,
                features = s.features.withStatus("power_voice", stAny, "电源待机/关机、提示音与语音助手状态已读取", true)
            )
        }
    }

    fun setStandbyKeepAnc(keepAnc: Boolean) {
        if (!state.value.controlReady) return
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
                    is WriteResult.Unverified -> it.copy(standbyStatus = FeatureStatus.UNVERIFIED, message = result.reason)
                    is WriteResult.Refused -> it.copy(message = result.reason)
                }
            }
        }
    }

    fun setAutoPowerOff(enabled: Boolean, minutes: Int) {
        if (!state.value.controlReady) return
        val m = minutes.coerceIn(5, 120)
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
                    is WriteResult.Unverified -> it.copy(autoOffStatus = FeatureStatus.UNVERIFIED, message = result.reason)
                    is WriteResult.Refused -> it.copy(message = result.reason)
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
                    is WriteResult.Unverified -> it.copy(multiAiStatus = FeatureStatus.UNVERIFIED, message = result.reason)
                    is WriteResult.Refused -> it.copy(message = result.reason)
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

    fun openOfficialApp() {
        val context = getApplication<Application>()
        val launch = context.packageManager.getLaunchIntentForPackage("jbl.stc.com")
        val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=jbl.stc.com"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun updateColorOsConfig(transform: (ColorOsSpoofConfig) -> ColorOsSpoofConfig) {
        val next = transform(_colorOsConfig.value)
        _colorOsConfig.value = next
        colorOsPrefs.saveConfig(next)
        ColorOsBridge.pushUiState(getApplication(), state.value, triggerPopup = false)
    }

    fun syncToColorOs(triggerPopup: Boolean = false) {
        val current = state.value
        var mac = current.system.deviceAddress
        if (mac.isBlank()) {
            mac = runCatching {
                scanner.findClassicDevice(BluetoothAdapter.getDefaultAdapter(), null)?.address
            }.getOrNull().orEmpty()
            if (mac.isNotBlank()) system.start(mac)
        }
        val effective = if (mac.isNotBlank() && current.system.deviceAddress.isBlank()) {
            current.copy(system = current.system.copy(deviceAddress = mac, deviceName = "JBL TUNE230NC TWS"))
        } else {
            current
        }
        ColorOsBridge.pushUiState(getApplication(), effective, triggerPopup = triggerPopup)
        base.update {
            it.copy(
                message = if (triggerPopup) {
                    "已向 ColorOS 欢律发送开盖弹窗与实时状态同步（需在 LSPosed 中启用本模块）。"
                } else {
                    "已同步当前耳机状态到 ColorOS 欢律。"
                }
            )
        }
    }

    fun clearLog() = log.clear()

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
        ColorOsBridge.foregroundLinkActive.set(false)
        if (bridgeRelayRegistered) {
            runCatching { getApplication<Application>().unregisterReceiver(bridgeRelayReceiver) }
            bridgeRelayRegistered = false
        }
        transport.release()
        system.stop()
        super.onCleared()
    }
}
