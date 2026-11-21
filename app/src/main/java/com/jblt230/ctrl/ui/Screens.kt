package com.jblt230.ctrl.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jblt230.ctrl.BuildConfig
import com.jblt230.ctrl.ble.GestureFunctionGroup
import com.jblt230.ctrl.ble.LinkState
import com.jblt230.ctrl.ble.NoiseMode
import com.jblt230.ctrl.ble.SmartAudioMode
import com.jblt230.ctrl.data.AppViewModel
import com.jblt230.ctrl.data.FeatureStatus
import com.jblt230.ctrl.data.FeatureUi
import com.jblt230.ctrl.data.UiState
import com.jblt230.ctrl.logging.DiagStatus
import com.jblt230.ctrl.logging.DiagnosticExporter
import com.jblt230.ctrl.ui.components.EarbudsShowcaseArtwork
import com.jblt230.ctrl.ui.components.NavTabIcon
import com.jblt230.ctrl.ui.components.StatusBadge
import java.util.Locale
import kotlin.math.roundToInt

// JBL Studio Dark Palette
private val BgCanvas = Color(0xFF0B0F14)
private val NavSurface = Color(0xFF10151C)
private val CardSurface = Color(0xFF151C26)
private val HeroTop = Color(0xFF1D2634)
private val HeroBottom = Color(0xFF121821)
private val Hairline = Color(0xFF243040)
private val Ink = Color(0xFFF0F4F8)
private val Muted = Color(0xFF8C9BB0)
private val JblOrange = Color(0xFFFF5722)
private val TealReady = Color(0xFF2CE09B)
private val SoftPill = Color(0xFF1F2937)
private val TrackBg = Color(0xFF253142)

private val TABS = listOf("耳机", "声音", "触控", "拓展", "设置")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel, onRequestPermissions: () -> Unit) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableStateOf(0) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = JblOrange,
            secondary = TealReady,
            background = BgCanvas,
            surface = CardSurface,
            surfaceVariant = SoftPill,
            onPrimary = Color.White,
            onBackground = Ink,
            onSurface = Ink,
            onSurfaceVariant = Muted
        )
    ) {
        Scaffold(
            containerColor = BgCanvas,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = BgCanvas,
                        titleContentColor = Ink
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(JblOrange, RoundedCornerShape(7.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text("JBL", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("TUNE 230NC TWS", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ink)
                                Text("独立接管 · v${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = Muted)
                            }
                            ConnectionStatusPill(ready = state.controlReady)
                        }
                    }
                )
            },
            bottomBar = {
                Column {
                    HorizontalDivider(color = Hairline, thickness = 0.8.dp)
                    NavigationBar(containerColor = NavSurface, tonalElevation = 0.dp) {
                        TABS.forEachIndexed { index, label ->
                            val selected = tab == index
                            NavigationBarItem(
                                selected = selected,
                                onClick = { tab = index },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = JblOrange,
                                    selectedTextColor = JblOrange,
                                    unselectedIconColor = Muted,
                                    unselectedTextColor = Muted,
                                    indicatorColor = JblOrange.copy(alpha = 0.14f)
                                ),
                                icon = {
                                    NavTabIcon(
                                        index = index,
                                        tint = if (selected) JblOrange else Muted
                                    )
                                },
                                label = {
                                    Text(
                                        label,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(BgCanvas)
            ) {
                when (tab) {
                    0 -> DashboardScreen(state, vm, onRequestPermissions)
                    1 -> SoundScreen(state, vm)
                    2 -> GestureScreen(state, vm)
                    3 -> ExtrasScreen(state, vm)
                    else -> SettingsScreen(state, vm)
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusPill(ready: Boolean) {
    val dot = if (ready) TealReady else Color(0xFF6B7B8F)
    Row(
        modifier = Modifier
            .background(dot.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .border(0.8.dp, dot.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(5.dp))
        Text(
            text = if (ready) "已接管" else "待连接",
            fontSize = 11.sp,
            color = if (ready) TealReady else Muted,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    badge: FeatureStatus? = null,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(0.8.dp, Hairline),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink)
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                if (badge != null) StatusBadge(badge)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontSize = 12.sp, color = Muted, modifier = Modifier.width(108.dp))
        Text(value, fontSize = 12.sp, color = Ink, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PillButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) JblOrange else SoftPill,
            contentColor = if (selected) Color.White else Ink,
            disabledContainerColor = SoftPill.copy(alpha = 0.45f),
            disabledContentColor = Muted.copy(alpha = 0.55f)
        )
    ) {
        Text(text, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun DiagnosticActionPanel(vm: AppViewModel, showSteps: Boolean) {
    val ctx = LocalContext.current
    val running by vm.diagRunning.collectAsState()
    val progress by vm.diagProgress.collectAsState()
    val steps by vm.diagSteps.collectAsState()

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val saved = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(vm.buildDiagnosticReport().toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
            vm.setToastMessage(if (saved) "诊断报告已保存到所选目录。" else "保存文件失败，请重试。")
        }
    }

    Button(
        onClick = { vm.runSelfTest() },
        enabled = !running,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = JblOrange, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (running) "正在执行一键全量协议自检…" else "一键全量协议测试", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }

    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { vm.shareDiagnostics() },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Hairline),
            modifier = Modifier.weight(1f)
        ) { Text("分享 .txt", fontSize = 11.sp, color = Ink) }
        OutlinedButton(
            onClick = { saveLauncher.launch(DiagnosticExporter.buildFileName()) },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Hairline),
            modifier = Modifier.weight(1f)
        ) { Text("保存到本地", fontSize = 11.sp, color = Ink) }
        OutlinedButton(
            onClick = { vm.copyDiagnostics() },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Hairline),
            modifier = Modifier.weight(1f)
        ) { Text("复制报告", fontSize = 11.sp, color = Ink) }
    }

    if (progress.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(progress, fontSize = 11.sp, color = TealReady)
    }

    if (showSteps && steps.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        steps.forEach { s ->
            val stColor = when (s.status) {
                DiagStatus.PASS -> TealReady
                DiagStatus.WARN -> Color(0xFFF5B041)
                DiagStatus.FAIL -> Color(0xFFFF5252)
                DiagStatus.SKIP -> Muted
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .background(Color(0xFF0E141C), RoundedCornerShape(8.dp))
                    .border(0.6.dp, Hairline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("[${s.status.tag}]", color = stColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text(s.label, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("${s.durationMs}ms", color = Muted, fontSize = 10.sp)
                }
                Text(s.detail, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                if (s.rawHex.isNotBlank()) {
                    Text(s.rawHex, color = TealReady.copy(alpha = 0.85f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun SwitchSettingRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 1.dp))
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = JblOrange,
                uncheckedThumbColor = Muted,
                uncheckedTrackColor = TrackBg,
                uncheckedBorderColor = Hairline
            )
        )
    }
}

// ---------------------------------------------------------------- 概览 (Hero Studio)

@Composable
private fun DashboardScreen(state: UiState, vm: AppViewModel, onRequestPermissions: () -> Unit) {
    var showDiagnostics by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(4.dp))

        // Hero Earbud Showcase Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(HeroTop, HeroBottom)))
                .border(1.dp, Hairline, RoundedCornerShape(22.dp))
                .padding(18.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.system.deviceName.ifBlank { "JBL TUNE230NC TWS" },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                        val linkSubtitle = when (val l = state.link) {
                            LinkState.Idle -> "点击下方按钮建立蓝牙控制链路"
                            is LinkState.Connecting -> "正在连接 ${l.address}..."
                            is LinkState.Discovering -> "正在握手识别 GATT 服务..."
                            is LinkState.Ready -> {
                                val fw = state.deviceInfo?.firmwareVersion?.takeIf { it.isNotBlank() }?.let { "FW $it · " } ?: ""
                                "${fw}MTU ${l.mtu} · 蓝牙音频独立在线"
                            }
                            is LinkState.Failed -> "连接受阻：${l.reason}"
                            LinkState.Disconnected -> "控制链路已释放"
                        }
                        Text(linkSubtitle, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
                    }
                    StatusBadge(state.batteryStatus)
                }

                Spacer(Modifier.height(14.dp))
                EarbudsShowcaseArtwork(connected = state.controlReady, accent = JblOrange)
                Spacer(Modifier.height(14.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BatteryRing(
                        label = "左耳 L",
                        percent = state.battery?.left ?: state.system.systemBattery,
                        charging = state.battery?.leftCharging == true,
                        millivolts = state.battery?.leftMv,
                        modifier = Modifier.weight(1f)
                    )
                    BatteryRing(
                        label = "右耳 R",
                        percent = state.battery?.right ?: state.system.systemBattery,
                        charging = state.battery?.rightCharging == true,
                        millivolts = state.battery?.rightMv,
                        modifier = Modifier.weight(1f)
                    )
                    BatteryRing(
                        label = "充电盒",
                        percent = state.battery?.case,
                        charging = state.battery?.caseCharging == true,
                        millivolts = state.battery?.caseMv,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (!state.permissionsGranted) {
                                onRequestPermissions()
                            } else if (state.controlReady) {
                                vm.refreshAll()
                            } else {
                                vm.connect()
                            }
                        },
                        modifier = Modifier.weight(1.3f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = JblOrange, contentColor = Color.White)
                    ) {
                        Text(
                            when {
                                !state.permissionsGranted -> "授权蓝牙权限"
                                state.controlReady -> "刷新全量状态"
                                else -> "连接控制链路"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (state.controlReady) {
                        OutlinedButton(
                            onClick = { vm.disconnect() },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Hairline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
                        ) { Text("断开", fontSize = 12.sp) }
                    }
                    OutlinedButton(
                        onClick = { vm.triggerTakeoverSync(triggerPopup = true) },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Hairline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
                    ) { Text("弹窗", fontSize = 12.sp) }
                }
            }
        }

        if (state.message.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .background(SoftPill.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .border(0.6.dp, Hairline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(state.message, fontSize = 11.sp, color = Muted)
            }
        }

        // Quick Noise Control Bar right on Dashboard
        SectionCard("快捷降噪模式", state.noiseStatus, state.noiseDetail) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                val quickModes = listOf(
                    NoiseMode.ANC to "降噪",
                    NoiseMode.AMBIENT_AWARE to "通透",
                    NoiseMode.TALKTHRU to "通话",
                    NoiseMode.OFF to "关闭"
                )
                quickModes.forEach { (mode, label) ->
                    PillButton(
                        text = label,
                        selected = state.noiseMode == mode,
                        enabled = state.controlReady,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.setNoiseMode(mode) }
                    )
                }
            }
        }

        if (state.system.musicMaxVolume > 0) {
            SectionCard("媒体音量", subtitle = "系统音频直调 · ${state.system.musicVolume} / ${state.system.musicMaxVolume}") {
                Slider(
                    value = state.system.musicVolume.coerceAtLeast(0).toFloat(),
                    onValueChange = { vm.setVolume(it.toInt()) },
                    valueRange = 0f..state.system.musicMaxVolume.toFloat(),
                    steps = state.system.musicMaxVolume.coerceAtLeast(1) - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = JblOrange,
                        activeTrackColor = JblOrange,
                        inactiveTrackColor = TrackBg
                    )
                )
            }
        }

        // Compact Hardware & Diagnostic Accordion
        SectionCard(
            title = "硬件详情与一键排障",
            subtitle = "点击一键自检或导出完整蓝牙日志，快速定位失效指令"
        ) {
            val info = state.deviceInfo
            InfoLine("设备名称", info?.deviceName?.ifBlank { "--" } ?: "--")
            InfoLine("固件 / 盒版本", "${info?.firmwareVersion?.ifBlank { "--" } ?: "--"} / ${state.caseVersion ?: "--"}")
            InfoLine("序列号 (S/N)", state.serialNumber ?: "--")
            InfoLine(
                "佩戴状态",
                state.inEar?.let {
                    "左 ${if (it.leftInEar) "已入耳" else "未戴"} · 右 ${if (it.rightInEar) "已入耳" else "未戴"}"
                } ?: "--"
            )
            InfoLine("电量数据源", state.batterySource)

            Spacer(Modifier.height(10.dp))
            DiagnosticActionPanel(vm = vm, showSteps = showDiagnostics)

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SoftPill.copy(alpha = 0.65f))
                    .clickable { showDiagnostics = !showDiagnostics }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (showDiagnostics) "收起明细与测试结果 (${state.features.size} 项)"
                    else "展开明细与测试结果 (${state.features.size} 项)",
                    fontSize = 12.sp,
                    color = Ink,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(if (showDiagnostics) "▲" else "▼", fontSize = 11.sp, color = Muted)
            }
            if (showDiagnostics) {
                Spacer(Modifier.height(8.dp))
                InfoLine("PID / Model", info?.let { "${it.productIdHex} / ${it.modelId}" } ?: "--")
                InfoLine("设备 MAC", state.system.deviceAddress.ifBlank { "--" })
                InfoLine("A2DP / HFP", "${state.system.a2dpConnected} / ${state.system.hfpConnected}")
                HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 8.dp))
                state.features.forEach { f -> FeatureRow(f) }
            }
        }

        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun BatteryRing(
    label: String,
    percent: Int?,
    charging: Boolean,
    millivolts: Int?,
    modifier: Modifier = Modifier
) {
    val ringColor = when {
        percent != null && percent <= 20 -> Color(0xFFFF5252)
        charging -> TealReady
        else -> JblOrange
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(Color(0xFF10161F), RoundedCornerShape(16.dp))
            .border(0.6.dp, Hairline, RoundedCornerShape(16.dp))
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(68.dp)) {
            Canvas(modifier = Modifier.size(68.dp)) {
                val stroke = Stroke(width = 6.5.dp.toPx(), cap = StrokeCap.Round)
                drawArc(TrackBg, -90f, 360f, false, style = stroke)
                if (percent != null) {
                    drawArc(ringColor, -90f, 360f * percent.coerceIn(0, 100) / 100f, false, style = stroke)
                }
            }
            Text(
                percent?.let { "$it%" } ?: "--",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Ink
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Medium)
        val sub = when {
            charging -> "⚡ 充电中"
            millivolts != null -> "$millivolts mV"
            else -> "待读取"
        }
        Text(sub, fontSize = 10.sp, color = if (charging) TealReady else Muted)
    }
}

@Composable
private fun FeatureRow(f: FeatureUi) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(f.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink)
            Text(f.detail, fontSize = 11.sp, color = Muted)
        }
        Spacer(Modifier.width(8.dp))
        StatusBadge(f.status)
    }
}

// ---------------------------------------------------------------- 声音 (ANC + EQ Studio)

@Composable
private fun SoundScreen(state: UiState, vm: AppViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(4.dp))

        SectionCard("降噪与环境声控制", state.noiseStatus, state.noiseDetail) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                NoiseMode.values().forEach { mode ->
                    PillButton(
                        text = when (mode) {
                            NoiseMode.ANC -> "主动降噪"
                            NoiseMode.AMBIENT_AWARE -> "环境感知"
                            NoiseMode.TALKTHRU -> "自由通话"
                            NoiseMode.OFF -> "关闭"
                        },
                        selected = state.noiseMode == mode,
                        enabled = state.controlReady,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.setNoiseMode(mode) }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("环境感知透传等级：${state.ambientAwareLevel} / 7", fontSize = 12.sp, color = Ink)
            var aaDraft by remember(state.ambientAwareLevel) { mutableStateOf(state.ambientAwareLevel.toFloat()) }
            Slider(
                value = aaDraft,
                onValueChange = { aaDraft = it },
                onValueChangeFinished = { vm.setAmbientAwareLevel(aaDraft.roundToInt().coerceIn(1, 7)) },
                valueRange = 1f..7f,
                steps = 5,
                enabled = state.controlReady,
                colors = SliderDefaults.colors(
                    thumbColor = JblOrange,
                    activeTrackColor = JblOrange,
                    inactiveTrackColor = TrackBg
                )
            )

            Spacer(Modifier.height(4.dp))
            Text("ANC 子模式（0x91）：${state.ancSubMode ?: "--"}", fontSize = 11.sp, color = Muted)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "关闭联动", 1 to "子模式 1", 2 to "子模式 2").forEach { (code, label) ->
                    PillButton(
                        text = label,
                        selected = state.ancSubMode == code,
                        enabled = state.controlReady,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.setAncSubMode(code) }
                    )
                }
            }
        }

        val activeHex = state.activePresetByte?.let { " · 槽位 0x%02X".format(it) } ?: ""
        SectionCard("EQ 均衡器调音台", state.eqStatus, "${state.eqDetail}$activeHex") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(vm.eqPresets) { preset ->
                    PillButton(
                        text = preset.label,
                        selected = state.eqPresetId == preset.id,
                        enabled = state.controlReady,
                        onClick = { vm.setEqPreset(preset) }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            EqCurve(state.eqGains)
            Spacer(Modifier.height(10.dp))
            vm.bandHz.forEachIndexed { index, hz ->
                val rawGain = state.eqGains.getOrNull(index) ?: 0f
                val gain = if (rawGain.isFinite()) rawGain.coerceIn(-6f, 6f) else 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (hz >= 1000) "${hz / 1000}k" else "$hz",
                        fontSize = 11.sp,
                        color = Muted,
                        modifier = Modifier.width(38.dp)
                    )
                    Slider(
                        value = gain,
                        onValueChange = { vm.setCustomBand(index, it) },
                        valueRange = -6f..6f,
                        steps = 23,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = JblOrange,
                            activeTrackColor = JblOrange,
                            inactiveTrackColor = TrackBg
                        )
                    )
                    val formatted = String.format(Locale.US, "%+.1f", gain)
                    Text(
                        "$formatted dB",
                        fontSize = 11.sp,
                        color = if (gain != 0f) Ink else Muted,
                        modifier = Modifier.width(54.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.applyCustomEq() },
                enabled = state.controlReady,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = JblOrange, contentColor = Color.White)
            ) {
                Text("写入自定义 10 段 EQ 并回读确认", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        SectionCard("智能音视频 (Smart Audio & Video)", state.smartAudioStatus, state.smartAudioDetail) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmartAudioMode.values().forEach { mode ->
                    PillButton(
                        text = mode.label,
                        selected = state.smartAudioMode == mode,
                        enabled = state.controlReady,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.setSmartAudioMode(mode) }
                    )
                }
            }
        }

        SectionCard("VoiceAware 通话自听", state.voiceAwareStatus, state.voiceAwareDetail) {
            val levels = listOf(0 to "关闭", 1 to "低", 2 to "中", 3 to "高")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                levels.forEach { (level, label) ->
                    PillButton(
                        text = label,
                        selected = state.voiceAwareLevel == level,
                        enabled = state.controlReady,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.setVoiceAwareLevel(level) }
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun EqCurve(gains: List<Float>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(106.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0E141C))
            .border(0.8.dp, Hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp)
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
        drawLine(Hairline, Offset(0f, mid), Offset(w, mid), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        drawLine(Hairline.copy(alpha = 0.5f), Offset(0f, h * 0.15f), Offset(w, h * 0.15f), strokeWidth = 0.8.dp.toPx(), pathEffect = dash)
        drawLine(Hairline.copy(alpha = 0.5f), Offset(0f, h * 0.85f), Offset(w, h * 0.85f), strokeWidth = 0.8.dp.toPx(), pathEffect = dash)

        val points = gains.mapIndexed { i, g ->
            val x = w * i / (gains.size - 1).coerceAtLeast(1)
            val y = mid - (g.coerceIn(-6f, 6f) / 6f) * (h * 0.40f)
            Offset(x, y)
        }
        if (points.isEmpty()) return@Canvas

        val curve = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 0 until points.lastIndex) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val cx = (p0.x + p1.x) / 2f
                cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
            }
        }
        val fillPath = Path().apply {
            addPath(curve)
            lineTo(points.last().x, h)
            lineTo(points.first().x, h)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(listOf(JblOrange.copy(alpha = 0.34f), Color.Transparent))
        )
        drawPath(curve, JblOrange, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round))
        points.forEach { pt ->
            drawCircle(JblOrange, radius = 3.5.dp.toPx(), center = pt)
            drawCircle(Color.White, radius = 1.5.dp.toPx(), center = pt)
        }
    }
}

// ---------------------------------------------------------------- 触控 (Gestures)

@Composable
private fun GestureScreen(state: UiState, vm: AppViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(4.dp))

        SectionCard("触控手势映射（0x77）", state.gestureStatus, state.gestureDetail) {
            EarbudGestureGroupCard(
                title = "左耳手势组（单击 / 双击 / 三击）",
                currentGroup = state.leftGestureGroup,
                holdVoiceAssistant = state.leftHoldVoiceAssistant,
                enabled = state.controlReady,
                onSelectGroup = { grp -> vm.setGestureGroup(true, grp, state.leftHoldVoiceAssistant) },
                onToggleHoldVoice = { hold -> vm.setGestureGroup(true, state.leftGestureGroup, hold) }
            )

            HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 12.dp))

            EarbudGestureGroupCard(
                title = "右耳手势组（单击 / 双击 / 三击）",
                currentGroup = state.rightGestureGroup,
                holdVoiceAssistant = state.rightHoldVoiceAssistant,
                enabled = state.controlReady,
                onSelectGroup = { grp -> vm.setGestureGroup(false, grp, state.rightHoldVoiceAssistant) },
                onToggleHoldVoice = { hold -> vm.setGestureGroup(false, state.rightGestureGroup, hold) }
            )

            Spacer(Modifier.height(10.dp))
            val pairSummary = if (state.gesturePairs.isEmpty()) "尚未回读手势键值"
            else state.gesturePairs.entries.sortedBy { it.key }
                .joinToString("  ") { "0x%02X→0x%02X".format(it.key, it.value) }
            Text("原始键值：$pairSummary", fontSize = 10.sp, color = Muted)

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { vm.resetGestures() },
                enabled = state.controlReady,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Hairline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("恢复官方默认手势配置", fontSize = 12.sp, color = Ink)
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun EarbudGestureGroupCard(
    title: String,
    currentGroup: GestureFunctionGroup,
    holdVoiceAssistant: Boolean,
    enabled: Boolean,
    onSelectGroup: (GestureFunctionGroup) -> Unit,
    onToggleHoldVoice: (Boolean) -> Unit
) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GestureFunctionGroup.values().forEach { group ->
            PillButton(
                text = group.label,
                selected = currentGroup == group,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onSelectGroup(group) }
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    SwitchSettingRow(
        title = "长按（Tap & Hold）触发语音助手",
        checked = holdVoiceAssistant,
        enabled = enabled,
        onCheckedChange = onToggleHoldVoice
    )
}

// ---------------------------------------------------------------- 拓展 (Extras)

@Composable
private fun ExtrasScreen(state: UiState, vm: AppViewModel) {
    if (state.findBudsConfirmPending) {
        AlertDialog(
            containerColor = CardSurface,
            titleContentColor = Ink,
            textContentColor = Muted,
            onDismissRequest = { vm.dismissFindMyBudsConfirm() },
            title = { Text("安全确认：请先摘下耳机", fontWeight = FontWeight.Bold) },
            text = {
                Text("查找耳机将发出高分贝蜂鸣声，请确认左、右耳机已从耳中摘下，蜂鸣将在 15 秒后自动停止。")
            },
            confirmButton = {
                Button(
                    onClick = { vm.confirmFindMyBuds() },
                    colors = ButtonDefaults.buttonColors(containerColor = JblOrange)
                ) { Text("确认已摘下并响铃") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissFindMyBudsConfirm() }) { Text("取消", color = Muted) }
            }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(4.dp))

        SectionCard("佩戴检测与贴合度（Best Fit）", state.autoPlayPauseStatus) {
            SwitchSettingRow(
                title = "自动播放 / 暂停（0x35）",
                subtitle = "摘下耳机自动暂停、戴回继续播放",
                checked = state.autoPlayPause,
                enabled = state.controlReady,
                onCheckedChange = { vm.setAutoPlayPause(it) }
            )
            InfoLine(
                "当前佩戴状态",
                state.inEar?.let {
                    "左 ${if (it.leftInEar) "已入耳" else "未戴"} · 右 ${if (it.rightInEar) "已入耳" else "未戴"}"
                } ?: "未知"
            )

            HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("耳塞贴合度检测", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
                StatusBadge(state.sealingStatus)
            }
            Text(state.sealingDetail, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
            state.sealingState?.let { s ->
                Text(
                    "结果：左耳 ${if (s.leftGood) "良好" else "请调整 (code ${s.leftRaw})"} · 右耳 ${if (s.rightGood) "良好" else "请调整 (code ${s.rightRaw})"}",
                    fontSize = 12.sp,
                    color = TealReady,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.runSealingTest() },
                enabled = state.controlReady,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("开始贴合度检测 (Check My Best Fit)", fontSize = 12.sp) }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("耳塞尺寸标记（0x9C）", fontSize = 12.sp, color = Ink, modifier = Modifier.weight(1f))
                StatusBadge(state.tipsTypeStatus)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "默认 S/M", 2 to "中号 M+", 3 to "大号 L").forEach { (typeId, label) ->
                    PillButton(
                        text = label,
                        selected = state.tipsType == typeId,
                        enabled = state.controlReady,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.setTipsType(typeId) }
                    )
                }
            }
        }

        SectionCard("电源、提示音与语音助手") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动待机保留降噪（0x96）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
                StatusBadge(state.standbyStatus)
            }
            SwitchSettingRow(
                title = if (state.standbyKeepAnc) "已开启保留 ANC" else "常规省电待机",
                subtitle = "无音频进入待机时维持降噪开启",
                checked = state.standbyKeepAnc,
                enabled = state.controlReady,
                onCheckedChange = { vm.setStandbyKeepAnc(it) }
            )

            HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动关机（0x33）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text(
                        if (state.autoOffEnabled) "${state.autoOffMinutes} 分钟无操作后关机" else "已关闭自动关机",
                        fontSize = 11.sp,
                        color = Muted
                    )
                }
                StatusBadge(state.autoOffStatus)
            }
            SwitchSettingRow(
                title = "启用自动关机",
                checked = state.autoOffEnabled,
                enabled = state.controlReady,
                onCheckedChange = { vm.setAutoPowerOff(it, state.autoOffMinutes) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 60).forEach { mins ->
                    PillButton(
                        text = "$mins 分钟",
                        selected = state.autoOffEnabled && state.autoOffMinutes == mins,
                        enabled = state.controlReady,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.setAutoPowerOff(true, mins) }
                    )
                }
            }

            HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ver = state.voicePromptInfo?.version?.let { " · v$it" } ?: ""
                Text("语音提示偏好（0x93$ver）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
                StatusBadge(state.voicePromptStatus)
            }
            SwitchSettingRow(
                title = if (state.voicePromptToneOnly) "仅保留提示音" else "完整语音播报",
                checked = state.voicePromptToneOnly,
                enabled = state.controlReady,
                onCheckedChange = { vm.setVoicePromptToneOnly(it) }
            )

            HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("语音助手选择（0x34）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
                StatusBadge(state.multiAiStatus)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0x00 to "系统默认", 0x01 to "Google", 0x02 to "Alexa", 0xDF to "关闭").forEach { (code, label) ->
                    PillButton(
                        text = label,
                        selected = state.multiAiMode == code,
                        enabled = state.controlReady,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.setMultiAiMode(code) }
                    )
                }
            }
        }

        SectionCard("查找耳机 (Find My Buds)", state.findBudsStatus, state.findBudsDetail) {
            Text(
                "当前蜂鸣：左耳 ${if (state.findMyBuds.leftBeeping) "响铃中" else "静音"} · 右耳 ${if (state.findMyBuds.rightBeeping) "响铃中" else "静音"}",
                fontSize = 12.sp,
                color = Ink
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { vm.requestFindMyBuds(left = true, right = false) },
                    enabled = state.controlReady,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("响左耳", fontSize = 11.sp) }
                Button(
                    onClick = { vm.requestFindMyBuds(left = false, right = true) },
                    enabled = state.controlReady,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("响右耳", fontSize = 11.sp) }
                Button(
                    onClick = { vm.requestFindMyBuds(left = true, right = true) },
                    enabled = state.controlReady,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("响双耳", fontSize = 11.sp) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.stopFindMyBuds() },
                enabled = state.controlReady,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Hairline),
                modifier = Modifier.fillMaxWidth()
            ) { Text("停止蜂鸣", fontSize = 12.sp, color = Ink) }
        }

        SectionCard("独立接管 · 弹窗与桌面工具", subtitle = "无 Root / 无官方软件，直接使用弹窗卡片与桌面小组件") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.triggerTakeoverSync(triggerPopup = true) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("预览开盖弹窗", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { vm.openOverlaySettings() },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Hairline),
                    modifier = Modifier.weight(1f)
                ) { Text("悬浮窗权限", fontSize = 12.sp, color = Ink) }
            }
        }

        Spacer(Modifier.height(22.dp))
    }
}

// ---------------------------------------------------------------- 设置

@Composable
private fun SettingsScreen(state: UiState, vm: AppViewModel) {
    val logs by vm.logs.collectAsState()
    val takeoverCfg by vm.takeoverConfig.collectAsState()
    val overlayOk by vm.overlayGranted.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).weight(1f)) {
            Spacer(Modifier.height(4.dp))
            SectionCard(
                "免 Root 接管、开盖弹窗与小组件",
                subtitle = "支持深浅色自适应弹窗、下拉关闭与桌面小组件「JBL T230 电量与降噪」"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (overlayOk) "悬浮窗权限：已授权" else "悬浮窗权限：未授权（自动弹窗需要）",
                        fontSize = 12.sp,
                        color = if (overlayOk) TealReady else Color(0xFFF5B041),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { vm.openOverlaySettings() },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Hairline)
                    ) { Text(if (overlayOk) "查看" else "去授权", fontSize = 11.sp, color = Ink) }
                }
                HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 8.dp))
                SwitchSettingRow(
                    title = "启用蓝牙连接 / 开盖后台接管",
                    checked = takeoverCfg.enabled,
                    onCheckedChange = { checked -> vm.updateTakeoverConfig { it.copy(enabled = checked) } }
                )
                SwitchSettingRow(
                    title = "连接后自动弹出电量与降噪卡片",
                    checked = takeoverCfg.autoPopupOnConnect,
                    enabled = takeoverCfg.enabled,
                    onCheckedChange = { checked -> vm.updateTakeoverConfig { it.copy(autoPopupOnConnect = checked) } }
                )
                SwitchSettingRow(
                    title = "无操作 6.5 秒后自动收起弹窗",
                    checked = takeoverCfg.autoDismissPopup,
                    enabled = takeoverCfg.enabled,
                    onCheckedChange = { checked -> vm.updateTakeoverConfig { it.copy(autoDismissPopup = checked) } }
                )
                SwitchSettingRow(
                    title = "切后台保持 GATT 控制连接",
                    checked = takeoverCfg.keepLinkAlive,
                    enabled = takeoverCfg.enabled,
                    onCheckedChange = { checked -> vm.updateTakeoverConfig { it.copy(keepLinkAlive = checked) } }
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { vm.triggerTakeoverSync(triggerPopup = true) },
                        enabled = takeoverCfg.enabled,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("测试开盖弹窗", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { vm.triggerTakeoverSync(triggerPopup = false) },
                        enabled = takeoverCfg.enabled,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Hairline),
                        modifier = Modifier.weight(1f)
                    ) { Text("同步三端电量", fontSize = 12.sp, color = Ink) }
                }
            }

            SectionCard("一键自检与日志导出", subtitle = "自动测试连接/全部只读指令/写回读闭环并导出 .txt 报告") {
                DiagnosticActionPanel(vm = vm, showSteps = true)
            }

            SectionCard("协议安全与说明") {
                InfoLine("命令表条目", "${vm.commandCount} 条")
                InfoLine("危险命令黑名单", "${vm.blacklist.size} 项（CommandGuard 拦截）")
                InfoLine(
                    "可信度分布",
                    "实测 ${state.confidence.values.count { it.name == "RECORDED" }} / " +
                        "静态 ${state.confidence.values.count { it.name == "APK_STATIC" }} / " +
                        "文档 ${state.confidence.values.count { it.name == "PRIOR_DOC" }}"
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "零联网权限，不上传任何设备标识或日志。所有设置写入必须由耳机蓝牙回读确认一致后才显示为真实生效，不采信单纯 ACK。",
                    fontSize = 11.sp,
                    color = Muted
                )
            }

            SectionCard("脱敏蓝牙帧终端") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "成对显示 TX / RX 十六进制帧",
                        fontSize = 11.sp,
                        color = Muted,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { vm.clearLog() },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Hairline)
                    ) { Text("清空", fontSize = 11.sp, color = Ink) }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF070A0E))
                .border(0.8.dp, Hairline, RoundedCornerShape(14.dp))
                .padding(10.dp)
        ) {
            LazyColumn {
                if (logs.isEmpty()) {
                    item {
                        Text("（暂无帧记录）", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                } else {
                    items(logs.takeLast(120)) { e ->
                        Text(
                            "${e.time}  ${e.direction}  ${e.frameHex}  ${e.note}",
                            color = if (e.direction == "←") TealReady else Color(0xFFFFB454),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}
