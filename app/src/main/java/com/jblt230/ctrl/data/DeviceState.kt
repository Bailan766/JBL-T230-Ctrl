package com.jblt230.ctrl.data

import com.jblt230.ctrl.ble.BatteryReading
import com.jblt230.ctrl.ble.DeviceInfoState
import com.jblt230.ctrl.ble.FindMyBudsState
import com.jblt230.ctrl.ble.GestureFunctionGroup
import com.jblt230.ctrl.ble.InEarState
import com.jblt230.ctrl.ble.LinkState
import com.jblt230.ctrl.ble.NoiseMode
import com.jblt230.ctrl.ble.SealingState
import com.jblt230.ctrl.ble.SmartAudioMode
import com.jblt230.ctrl.ble.VoicePromptState
import com.jblt230.ctrl.protocol.Confidence
import com.jblt230.ctrl.system.SystemInfo

/** Driven only by read-back results. NEVER hard-code REAL. */
enum class FeatureStatus(val label: String) {
    REAL("真实 · 已回读"),
    READ_ONLY("只读"),
    UNVERIFIED("未验证"),
    DEGRADED("降级")
}

data class FeatureUi(
    val key: String,
    val label: String,
    val status: FeatureStatus,
    val detail: String,
    val actionable: Boolean
)

data class UiState(
    val permissionsGranted: Boolean = false,
    val link: LinkState = LinkState.Idle,
    val system: SystemInfo = SystemInfo(),

    // Device Info & Hardware State (0x12 / 0x26 / 0x94 / 0x22 / 0x9B)
    val deviceInfo: DeviceInfoState? = null,
    val caseVersion: String? = null,
    val serialNumber: String? = null,
    val twsConnected: Boolean? = null,
    val inEar: InEarState? = null,
    val inCall: Boolean = false,

    // Battery (0x25)
    val battery: BatteryReading? = null,
    val batteryStatus: FeatureStatus = FeatureStatus.UNVERIFIED,
    val batterySource: String = "尚未读取",

    // Noise Control (0x91 + 0x21/0x31/0x32 fallback)
    val noiseMode: NoiseMode = NoiseMode.OFF,
    val ancSubMode: Int? = null,
    val ambientAwareLevel: Int = 4,
    val noiseStatus: FeatureStatus = FeatureStatus.UNVERIFIED,
    val noiseDetail: String = "尚未与耳机建立控制链路并回读",

    // EQ (0x40 + 0x41/0x42/0x43 + A2 fallback)
    val eqPresetId: String = "off",
    val activePresetByte: Int? = null,
    val eqGains: List<Float> = List(10) { 0f },
    val eqStatus: FeatureStatus = FeatureStatus.UNVERIFIED,
    val eqDetail: String = "尚未回读耳机当前 EQ 曲线",

    // Touch Gestures (0x77)
    val gesturePairs: Map<Int, Int> = emptyMap(),
    val leftGestureGroup: GestureFunctionGroup = GestureFunctionGroup.AMBIENT,
    val rightGestureGroup: GestureFunctionGroup = GestureFunctionGroup.PLAYBACK,
    val leftHoldVoiceAssistant: Boolean = true,
    val rightHoldVoiceAssistant: Boolean = true,
    val gestureStatus: FeatureStatus = FeatureStatus.UNVERIFIED,
    val gestureDetail: String = "尚未回读手势配置",

    // Smart Audio & Video (0x81 / 0x82 / 0x83)
    val smartAudioMode: SmartAudioMode = SmartAudioMode.NORMAL,
    val smartAudioStatus: FeatureStatus = FeatureStatus.UNVERIFIED,
    val smartAudioDetail: String = "尚未回读智能音视频状态",

    // VoiceAware (0x98)
    val voiceAwareLevel: Int = 0,
    val voiceAwareStatus: FeatureStatus = FeatureStatus.UNVERIFIED,
    val voiceAwareDetail: String = "尚未回读 VoiceAware 状态",

    // Wearing, Auto Play/Pause, Check My Best Fit & Tips (0x35 / 0x41 / 0x42 / 0x9C)
    val autoPlayPause: Boolean = false,
    val autoPlayPauseStatus: FeatureStatus = FeatureStatus.UNVERIFIED,
    val sealingState: SealingState? = null,
    val sealingStatus: FeatureStatus = FeatureStatus.UNVERIFIED,
    val sealingDetail: String = "请双耳佩戴后开始耳塞贴合度检测",
    val tipsType: Int = 1,
    val tipsTypeStatus: FeatureStatus = FeatureStatus.UNVERIFIED,

    // Power Saving, Voice Prompts & Multi-AI (0x96 / 0x33 / 0x93 / 0x34)
    val standbyKeepAnc: Boolean = false,
    val standbyStatus: FeatureStatus = FeatureStatus.UNVERIFIED,
    val autoOffEnabled: Boolean = false,
    val autoOffMinutes: Int = 30,
    val autoOffStatus: FeatureStatus = FeatureStatus.UNVERIFIED,
    val voicePromptToneOnly: Boolean = false,
    val voicePromptInfo: VoicePromptState? = null,
    val voicePromptStatus: FeatureStatus = FeatureStatus.UNVERIFIED,
    val multiAiMode: Int = 0,
    val multiAiStatus: FeatureStatus = FeatureStatus.UNVERIFIED,

    // Find My Buds (0x23 + 0x36 fallback, confirmation + 15s auto-stop)
    val findMyBuds: FindMyBudsState = FindMyBudsState(false, false),
    val findBudsConfirmPending: Boolean = false,
    val findBudsPendingLeft: Boolean = false,
    val findBudsPendingRight: Boolean = false,
    val findBudsStatus: FeatureStatus = FeatureStatus.UNVERIFIED,
    val findBudsDetail: String = "需先确认双耳已摘下，蜂鸣会在 15 秒后自动停止",

    val features: List<FeatureUi> = defaultFeatures(),
    val message: String = "",
    val confidence: Map<String, Confidence> = emptyMap()
) {
    val controlReady: Boolean get() = link is LinkState.Ready
}

private fun defaultFeatures() = listOf(
    FeatureUi("eq", "EQ 均衡器", FeatureStatus.UNVERIFIED, "支持 0x40 预设与 0x41/0x42/0x43 10 段曲线读写", false),
    FeatureUi("anc", "降噪 / 环境音", FeatureStatus.UNVERIFIED, "支持 0x91 模式、等级与 0x31/0x32 兼容路径", false),
    FeatureUi("battery", "电量与设备信息", FeatureStatus.UNVERIFIED, "读取左右耳/充电盒电量、固件号、序列号与 TWS 状态", false),
    FeatureUi("gesture", "触控手势", FeatureStatus.UNVERIFIED, "支持 0x77 左右耳手势组配置与重置", false),
    FeatureUi("smart_av", "智能音视频", FeatureStatus.UNVERIFIED, "支持 0x81/0x82/0x83 正常/音频/视频模式", false),
    FeatureUi("voice_aware", "VoiceAware 侧音", FeatureStatus.UNVERIFIED, "支持 0x98 关闭/低/中/高 四档调节", false),
    FeatureUi("wearing", "佩戴与贴合检测", FeatureStatus.UNVERIFIED, "自动播放暂停 (0x35)、入耳检测 (0x41)、贴合测试 (0x42)", false),
    FeatureUi("power_voice", "电源、提示音与助手", FeatureStatus.UNVERIFIED, "自动待机 (0x96)、自动关机 (0x33)、提示音 (0x93)、AI (0x34)", false),
    FeatureUi("find", "查找耳机", FeatureStatus.UNVERIFIED, "支持 0x23/0x36 双耳或单耳寻回（需弹窗确认且 15 秒自动停止）", false)
)
