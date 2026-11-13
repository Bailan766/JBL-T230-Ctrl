package com.jblt230.ctrl.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jblt230.ctrl.ble.GestureFunctionGroup
import com.jblt230.ctrl.ble.LinkState
import com.jblt230.ctrl.ble.NoiseMode
import com.jblt230.ctrl.ble.SmartAudioMode
import com.jblt230.ctrl.data.AppViewModel
import com.jblt230.ctrl.data.FeatureStatus
import com.jblt230.ctrl.data.FeatureUi
import com.jblt230.ctrl.data.UiState
import com.jblt230.ctrl.ui.components.StatusBadge
import java.util.Locale
import kotlin.math.roundToInt

private val Bg = Color(0xFFF7F8FA)
private val Ink = Color(0xFF1B2A41)
private val Muted = Color(0xFF6B7480)
private val Accent = Color(0xFF1D7A70)
private val CardBg = Color(0xFFFFFFFF)
private val SoftPill = Color(0xFFE9EDF2)

private val TABS = listOf("概览", "声音", "手势", "更多", "设置")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel, onRequestPermissions: () -> Unit) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableStateOf(0) }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent,
            background = Bg,
            surface = CardBg,
            onBackground = Ink,
            onSurface = Ink
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text("JBL T230 Ctrl", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("非官方 · 互操作研究 · ${vm.deviceLabel}", fontSize = 11.sp, color = Muted)
                    }
                })
            },
            bottomBar = {
                NavigationBar {
                    TABS.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            label = { Text(label, fontSize = 11.sp) },
                            icon = { Text("${index + 1}", fontSize = 13.sp) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Bg)
            ) {
                WarningBanner(ready = state.controlReady)
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
private fun WarningBanner(ready: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (ready) Color(0xFFE4F3EF) else Color(0xFFFFF1DC))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = if (ready)
                "控制链路已连接。期间官方 JBL app 无法连接；退出本页或点「释放链路」即可恢复，蓝牙音频不受影响。"
            else
                "本工具不会假装功能生效：只有「下发 → 回读一致」才标为真实，其余一律置灰或标未验证。",
            fontSize = 11.sp,
            color = if (ready) Color(0xFF0F6B52) else Color(0xFF8A5A00)
        )
    }
}

@Composable
private fun SectionCard(title: String, badge: FeatureStatus? = null, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (badge != null) StatusBadge(badge)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, fontSize = 12.sp, color = Muted, modifier = Modifier.width(116.dp))
        Text(value, fontSize = 12.sp, color = Ink)
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
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Accent else SoftPill,
            contentColor = if (selected) Color.White else Ink
        )
    ) { Text(text, fontSize = 11.sp) }
}

// ---------------------------------------------------------------- 概览

@Composable
private fun DashboardScreen(state: UiState, vm: AppViewModel, onRequestPermissions: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))

        if (state.message.isNotBlank()) {
            Text(
                state.message,
                fontSize = 12.sp,
                color = Muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        SectionCard("连接状态") {
            val linkText = when (val l = state.link) {
                LinkState.Idle -> "未连接"
                is LinkState.Connecting -> "正在连接 ${l.address}"
                is LinkState.Discovering -> "正在发现服务 ${l.address}"
                is LinkState.Ready -> "控制链路就绪（MTU ${l.mtu}）"
                is LinkState.Failed -> "失败：${l.reason}"
                LinkState.Disconnected -> "已断开"
            }
            InfoLine("控制链路", linkText)
            InfoLine("配对设备", state.system.deviceName.ifBlank { "--" })
            InfoLine("设备地址", state.system.deviceAddress.ifBlank { "--" })
            InfoLine("A2DP / HFP", "${state.system.a2dpConnected} / ${state.system.hfpConnected}")
            InfoLine("系统电量", state.system.systemBattery?.let { "$it%" } ?: "不可用")
            InfoLine("电量说明", state.system.systemBatteryNote)
            InfoLine("系统元数据", if (state.system.metadataAvailable) "可读取" else "不可用（权限受限）")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (state.permissionsGranted) vm.connect() else onRequestPermissions() }) {
                    Text(if (state.permissionsGranted) "连接控制链路" else "授予附近设备权限", fontSize = 12.sp)
                }
                OutlinedButton(onClick = { vm.refreshAll() }, enabled = state.controlReady) {
                    Text("重新回读", fontSize = 12.sp)
                }
                OutlinedButton(onClick = { vm.disconnect() }, enabled = state.controlReady) {
                    Text("释放链路", fontSize = 12.sp)
                }
            }
            if (state.system.musicMaxVolume > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "媒体音量 ${state.system.musicVolume}/${state.system.musicMaxVolume}（系统层能力）",
                    fontSize = 12.sp,
                    color = Muted
                )
                Slider(
                    value = state.system.musicVolume.coerceAtLeast(0).toFloat(),
                    onValueChange = { vm.setVolume(it.toInt()) },
                    valueRange = 0f..state.system.musicMaxVolume.toFloat(),
                    steps = state.system.musicMaxVolume.coerceAtLeast(1) - 1
                )
            }
        }

        SectionCard("耳机硬件信息") {
            val info = state.deviceInfo
            InfoLine("设备名称", info?.deviceName?.ifBlank { "--" } ?: "--")
            InfoLine("PID / Model", info?.let { "${it.productIdHex} / ${it.modelId}" } ?: "--")
            InfoLine("固件版本", info?.firmwareVersion?.ifBlank { "--" } ?: "--")
            InfoLine("充电盒版本", state.caseVersion ?: "--")
            InfoLine("序列号 (S/N)", state.serialNumber ?: "--")
            InfoLine("TWS 双耳互联", when (state.twsConnected ?: info?.twsConnected) {
                true -> "双耳已连通"
                false -> "仅单耳连接"
                null -> "未知"
            })
            InfoLine("入耳佩戴", state.inEar?.let {
                "左耳 ${if (it.leftInEar) "已入耳" else "未佩戴"} / 右耳 ${if (it.rightInEar) "已入耳" else "未佩戴"}"
            } ?: "--")
            InfoLine("通话状态", if (state.inCall) "通话中（部分设置受保护）" else "空闲")
        }

        SectionCard("耳机电量", state.batteryStatus) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BatteryRing("左耳", state.battery?.left, state.battery?.leftCharging == true, state.battery?.leftMv)
                BatteryRing("右耳", state.battery?.right, state.battery?.rightCharging == true, state.battery?.rightMv)
                BatteryRing("充电盒", state.battery?.case, state.battery?.caseCharging == true, state.battery?.caseMv)
            }
            Spacer(Modifier.height(8.dp))
            Text("数据来源：${state.batterySource}", fontSize = 11.sp, color = Muted)
        }

        SectionCard("功能状态总览") {
            state.features.forEach { f -> FeatureRow(f) }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun BatteryRing(label: String, percent: Int?, charging: Boolean, millivolts: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp)) {
            Canvas(modifier = Modifier.size(84.dp)) {
                val stroke = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
                drawArc(Color(0xFFE6EBF1), -90f, 360f, false, style = stroke)
                if (percent != null) {
                    drawArc(Accent, -90f, 360f * percent / 100f, false, style = stroke)
                }
            }
            Text(percent?.let { "$it%" } ?: "--", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, fontSize = 11.sp, color = Muted)
        if (millivolts != null) Text("$millivolts mV", fontSize = 10.sp, color = Muted)
        if (charging) Text("充电中", fontSize = 10.sp, color = Accent)
    }
}

@Composable
private fun FeatureRow(f: FeatureUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(f.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(f.detail, fontSize = 11.sp, color = Muted)
        }
        Spacer(Modifier.width(8.dp))
        StatusBadge(f.status)
    }
}

// ---------------------------------------------------------------- 声音

@Composable
private fun SoundScreen(state: UiState, vm: AppViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))

        SectionCard("降噪 / 环境音", state.noiseStatus) {
            Text(state.noiseDetail, fontSize = 11.sp, color = Muted)
            Spacer(Modifier.height(10.dp))
            NoiseMode.values().forEach { mode ->
                val selected = state.noiseMode == mode
                Button(
                    onClick = { vm.setNoiseMode(mode) },
                    enabled = state.controlReady,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Accent else SoftPill,
                        contentColor = if (selected) Color.White else Ink
                    )
                ) { Text(mode.label, fontSize = 13.sp) }
            }

            Spacer(Modifier.height(8.dp))
            Text("环境感知等级（Ambient Aware）：${state.ambientAwareLevel} / 7", fontSize = 12.sp, color = Ink)
            var aaDraft by remember(state.ambientAwareLevel) { mutableStateOf(state.ambientAwareLevel.toFloat()) }
            Slider(
                value = aaDraft,
                onValueChange = { aaDraft = it },
                onValueChangeFinished = { vm.setAmbientAwareLevel(aaDraft.roundToInt().coerceIn(1, 7)) },
                valueRange = 1f..7f,
                steps = 5,
                enabled = state.controlReady
            )

            Spacer(Modifier.height(4.dp))
            Text("ANC 子模式（0x91 SubMode）：${state.ancSubMode ?: "--"}", fontSize = 12.sp, color = Muted)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "关闭联动", 1 to "子模式 1", 2 to "子模式 2").forEach { (code, label) ->
                    PillButton(
                        text = label,
                        selected = state.ancSubMode == code,
                        enabled = state.controlReady,
                        onClick = { vm.setAncSubMode(code) }
                    )
                }
            }

            if (!state.controlReady) {
                Text(
                    "需先建立控制链路；未连接时所有降噪模式控件不可用。",
                    fontSize = 11.sp,
                    color = Muted,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        SectionCard("EQ 均衡器（0x40 预设 / 0x41-0x43 10 段自定义）", state.eqStatus) {
            val activeHex = state.activePresetByte?.let { " · 槽位 0x%02X".format(it) } ?: ""
            Text("${state.eqDetail}$activeHex", fontSize = 11.sp, color = Muted)
            Spacer(Modifier.height(10.dp))
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
            Spacer(Modifier.height(12.dp))
            EqCurve(state.eqGains)
            Spacer(Modifier.height(6.dp))
            vm.bandHz.forEachIndexed { index, hz ->
                val gain = state.eqGains.getOrNull(index) ?: 0f
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
                        modifier = Modifier.weight(1f)
                    )
                    val formatted = String.format(Locale.US, "%+.1f", gain)
                    Text(
                        "$formatted dB",
                        fontSize = 11.sp,
                        color = Ink,
                        modifier = Modifier.width(54.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { vm.applyCustomEq() },
                enabled = state.controlReady,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("写入自定义 10 段 EQ 到耳机（0x41 分包下发并回读）", fontSize = 12.sp)
            }
        }

        SectionCard("智能音视频 (Smart Audio & Video)", state.smartAudioStatus) {
            Text(state.smartAudioDetail, fontSize = 11.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
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

        SectionCard("VoiceAware 通话自听", state.voiceAwareStatus) {
            Text(state.voiceAwareDetail, fontSize = 11.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
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

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun EqCurve(gains: List<Float>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(90.dp).background(Color(0xFFF2F5F8))) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        drawLine(Color(0xFFD6DEE7), Offset(0f, mid), Offset(w, mid), strokeWidth = 1.dp.toPx())
        val path = Path()
        gains.forEachIndexed { i, g ->
            val x = w * i / (gains.size - 1).coerceAtLeast(1)
            val y = mid - (g / 6f) * (h * 0.42f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        gains.forEachIndexed { i, g ->
            val x = w * i / (gains.size - 1).coerceAtLeast(1)
            val y = mid - (g / 6f) * (h * 0.42f)
            drawCircle(Accent, radius = 3.dp.toPx(), center = Offset(x, y))
        }
    }
}

// ---------------------------------------------------------------- 手势

@Composable
private fun GestureScreen(state: UiState, vm: AppViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))

        SectionCard("触控手势映射（0x77）", state.gestureStatus) {
            Text(state.gestureDetail, fontSize = 11.sp, color = Muted)
            Spacer(Modifier.height(12.dp))

            EarbudGestureGroupCard(
                title = "左耳功能组（单击 / 双击 / 三击）",
                currentGroup = state.leftGestureGroup,
                holdVoiceAssistant = state.leftHoldVoiceAssistant,
                enabled = state.controlReady,
                onSelectGroup = { grp -> vm.setGestureGroup(true, grp, state.leftHoldVoiceAssistant) },
                onToggleHoldVoice = { hold -> vm.setGestureGroup(true, state.leftGestureGroup, hold) }
            )

            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE6EBF1)))
            Spacer(Modifier.height(12.dp))

            EarbudGestureGroupCard(
                title = "右耳功能组（单击 / 双击 / 三击）",
                currentGroup = state.rightGestureGroup,
                holdVoiceAssistant = state.rightHoldVoiceAssistant,
                enabled = state.controlReady,
                onSelectGroup = { grp -> vm.setGestureGroup(false, grp, state.rightHoldVoiceAssistant) },
                onToggleHoldVoice = { hold -> vm.setGestureGroup(false, state.rightGestureGroup, hold) }
            )

            Spacer(Modifier.height(10.dp))
            val pairSummary = if (state.gesturePairs.isEmpty()) "尚未回读手势键值对"
            else state.gesturePairs.entries.sortedBy { it.key }
                .joinToString("  ") { "0x%02X→0x%02X".format(it.key, it.value) }
            Text("原始键值：$pairSummary", fontSize = 10.sp, color = Muted)

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { vm.resetGestures() },
                enabled = state.controlReady,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("恢复官方默认手势配置（Reset Touch Gestures）", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
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
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("长按（Tap & Hold）触发原生语音助手", fontSize = 12.sp, color = Ink, modifier = Modifier.weight(1f))
        Switch(
            checked = holdVoiceAssistant,
            onCheckedChange = onToggleHoldVoice,
            enabled = enabled
        )
    }
}

// ---------------------------------------------------------------- 更多

@Composable
private fun ExtrasScreen(state: UiState, vm: AppViewModel) {
    if (state.findBudsConfirmPending) {
        AlertDialog(
            onDismissRequest = { vm.dismissFindMyBudsConfirm() },
            title = { Text("安全确认：请先摘下耳机", fontWeight = FontWeight.Bold) },
            text = {
                Text("查找耳机功能将发出高分贝蜂鸣声，可能损伤听力。请确认左、右耳机均已从耳中摘下，蜂鸣将在 15 秒后自动停止。")
            },
            confirmButton = {
                Button(onClick = { vm.confirmFindMyBuds() }) { Text("确认已摘下并响铃") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissFindMyBudsConfirm() }) { Text("取消") }
            }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))

        SectionCard("佩戴检测与贴合度（Best Fit）", state.autoPlayPauseStatus) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动播放 / 暂停（Auto Play & Pause）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("摘下耳机暂停、戴回继续（0x35）", fontSize = 11.sp, color = Muted)
                }
                Switch(
                    checked = state.autoPlayPause,
                    onCheckedChange = { vm.setAutoPlayPause(it) },
                    enabled = state.controlReady
                )
            }
            Spacer(Modifier.height(6.dp))
            InfoLine("当前佩戴状态", state.inEar?.let {
                "左 ${if (it.leftInEar) "入耳" else "未戴"} · 右 ${if (it.rightInEar) "入耳" else "未戴"}"
            } ?: "未知")

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("耳塞贴合度检测（Check My Best Fit）", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                StatusBadge(state.sealingStatus)
            }
            Spacer(Modifier.height(4.dp))
            Text(state.sealingDetail, fontSize = 11.sp, color = Muted)
            state.sealingState?.let { s ->
                Text(
                    "结果：左耳 ${if (s.leftGood) "良好" else "请调整 (code ${s.leftRaw})"} · 右耳 ${if (s.rightGood) "良好" else "请调整 (code ${s.rightRaw})"}",
                    fontSize = 12.sp,
                    color = Ink,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { vm.runSealingTest() },
                enabled = state.controlReady,
                modifier = Modifier.fillMaxWidth()
            ) { Text("开始贴合度检测 (Check My Best Fit)", fontSize = 12.sp) }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("耳塞尺寸类型（TipsType 0x9C）", fontSize = 12.sp, color = Ink, modifier = Modifier.weight(1f))
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
                Column(Modifier.weight(1f)) {
                    Text("自动待机保留降噪（Keep ANC on Standby）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("无音频播放进入待机时保持 ANC 工作（0x96）", fontSize = 11.sp, color = Muted)
                }
                StatusBadge(state.standbyStatus)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.standbyKeepAnc) "已开启保留 ANC" else "常规省电待机", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.standbyKeepAnc,
                    onCheckedChange = { vm.setStandbyKeepAnc(it) },
                    enabled = state.controlReady
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动关机（Auto Power Off · 0x33）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("当前：${if (state.autoOffEnabled) "${state.autoOffMinutes} 分钟后关机" else "已关闭"}", fontSize = 11.sp, color = Muted)
                }
                StatusBadge(state.autoOffStatus)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用自动关机", fontSize = 12.sp, color = Ink, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.autoOffEnabled,
                    onCheckedChange = { vm.setAutoPowerOff(it, state.autoOffMinutes) },
                    enabled = state.controlReady
                )
            }
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

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("语音提示偏好（Voice Prompts · 0x93）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    val ver = state.voicePromptInfo?.version?.let { " · 语音包 v$it" } ?: ""
                    Text("仅保留提示音 (Tone Only)$ver", fontSize = 11.sp, color = Muted)
                }
                StatusBadge(state.voicePromptStatus)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.voicePromptToneOnly) "仅提示音（关闭语音播报）" else "完整语音播报", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.voicePromptToneOnly,
                    onCheckedChange = { vm.setVoicePromptToneOnly(it) },
                    enabled = state.controlReady
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("语音助手偏好（Multi-AI · 0x34）", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
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

        SectionCard("查找耳机 (Find My Buds)", state.findBudsStatus) {
            Text(state.findBudsDetail, fontSize = 11.sp, color = Muted)
            Spacer(Modifier.height(4.dp))
            Text(
                "当前响铃：左耳 ${if (state.findMyBuds.leftBeeping) "响铃中" else "静音"} · 右耳 ${if (state.findMyBuds.rightBeeping) "响铃中" else "静音"}",
                fontSize = 12.sp,
                color = Ink
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { vm.requestFindMyBuds(left = true, right = false) },
                    enabled = state.controlReady,
                    modifier = Modifier.weight(1f)
                ) { Text("响左耳", fontSize = 11.sp) }
                Button(
                    onClick = { vm.requestFindMyBuds(left = false, right = true) },
                    enabled = state.controlReady,
                    modifier = Modifier.weight(1f)
                ) { Text("响右耳", fontSize = 11.sp) }
                Button(
                    onClick = { vm.requestFindMyBuds(left = true, right = true) },
                    enabled = state.controlReady,
                    modifier = Modifier.weight(1f)
                ) { Text("响双耳", fontSize = 11.sp) }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { vm.stopFindMyBuds() },
                enabled = state.controlReady,
                modifier = Modifier.fillMaxWidth()
            ) { Text("停止蜂鸣", fontSize = 12.sp) }
        }

        SectionCard("官方伴侣应用") {
            OutlinedButton(onClick = { vm.openOfficialApp() }, modifier = Modifier.fillMaxWidth()) {
                Text("打开官方 JBL Headphones", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ---------------------------------------------------------------- 设置

@Composable
private fun SettingsScreen(state: UiState, vm: AppViewModel) {
    val logs by vm.logs.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).weight(1f)) {
            Spacer(Modifier.height(8.dp))
            SectionCard("协议与证据") {
                InfoLine("命令表条目", "${vm.commandCount} 条")
                InfoLine("危险命令黑名单", "${vm.blacklist.size} 项")
                InfoLine("RECORDED", "${state.confidence.values.count { it.name == "RECORDED" }} 条")
                InfoLine("APK_STATIC", "${state.confidence.values.count { it.name == "APK_STATIC" }} 条")
                InfoLine("PRIOR_DOC", "${state.confidence.values.count { it.name == "PRIOR_DOC" }} 条")
                InfoLine("INFERRED", "${state.confidence.values.count { it.name == "INFERRED" }} 条")
                Spacer(Modifier.height(6.dp))
                Text(
                    "协议定义已对齐官方 5.38.2 Tune230Control + BesEQControl（0x40 预设与 0x41/0x42/0x43 多分包 EQ、0x91/0x31/0x32 双路降噪及全量控制）。所有写入均以回读一致为准，不采信单纯 ACK。",
                    fontSize = 11.sp,
                    color = Muted
                )
            }
            SectionCard("关于") {
                Text(
                    "本 app 为非官方互操作研究工具，与 JBL / Harman 无关。不申请联网权限，不上传任何抓包或设备标识。",
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "所有固件级命令在写往耳机前经过 CommandGuard 黑名单拦截；ACK 帧不采信，只有回读一致才标为真实。",
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "同一时刻只允许一条控制连接；本 app 持有期间官方 app 无法连接，音频 A2DP 不受影响。",
                    fontSize = 12.sp
                )
            }
            SectionCard("脱敏协议日志") {
                Row {
                    Text(
                        "成对记录下发 / 接收帧，MAC 等标识已替换为占位符。",
                        fontSize = 11.sp,
                        color = Muted,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { vm.clearLog() }) { Text("清空", fontSize = 11.sp) }
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth().height(190.dp).padding(horizontal = 14.dp, vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101A28))
        ) {
            LazyColumn(Modifier.padding(10.dp)) {
                if (logs.isEmpty()) {
                    item {
                        Text("（暂无帧记录）", color = Color(0xFF8FA3B8), fontSize = 11.sp)
                    }
                } else {
                    items(logs.takeLast(120)) { e ->
                        Text(
                            "${e.time}  ${e.direction}  ${e.frameHex}  ${e.note}",
                            color = if (e.direction == "←") Color(0xFF7FE0C4) else Color(0xFFFFD08A),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
