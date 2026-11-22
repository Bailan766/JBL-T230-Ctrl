package com.jblt230.ctrl.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jblt230.ctrl.BuildConfig
import com.jblt230.ctrl.R
import com.jblt230.ctrl.ble.GestureFunctionGroup
import com.jblt230.ctrl.ble.LinkState
import com.jblt230.ctrl.ble.NoiseMode
import com.jblt230.ctrl.ble.SmartAudioMode
import com.jblt230.ctrl.bridge.BridgeContract
import com.jblt230.ctrl.bridge.FluidCloudNotifier
import com.jblt230.ctrl.data.AppViewModel
import com.jblt230.ctrl.data.UiState
import com.jblt230.ctrl.logging.DiagStatus
import com.jblt230.ctrl.logging.DiagnosticExporter
import java.util.Locale

private val EXPRESSIVE_TABS = listOf(
    ExpressiveNavTab("耳机", R.drawable.ic_nav_earbuds),
    ExpressiveNavTab("音效", R.drawable.ic_nav_sound),
    ExpressiveNavTab("操控", R.drawable.ic_nav_touch),
    ExpressiveNavTab("系统", R.drawable.ic_nav_settings)
)

@Composable
fun AppRoot(vm: AppViewModel, onRequestPermissions: () -> Unit) {
    val state by vm.state.collectAsState()
    val cfg by vm.takeoverConfig.collectAsState()
    var tab by remember { mutableStateOf(0) }

    JblExpressiveTheme(useDynamicColor = cfg.useDynamicColor) {
        val cs = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(cs.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                when (tab) {
                    0 -> EarbudsScreen(state, vm, onRequestPermissions)
                    1 -> SoundScreen(state, vm)
                    2 -> ControlsScreen(state, vm)
                    else -> SettingsScreen(state, vm)
                }
            }

            FloatingPillNavBar(
                selectedTab = tab,
                tabs = EXPRESSIVE_TABS,
                onSelect = { tab = it },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun ExpressiveHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = cs.onBackground
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

@Composable
private fun <T> ExpressiveSegmentedBar(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(cs.surfaceContainerHighest)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val bgColor by animateColorAsState(
                targetValue = if (active) cs.primaryContainer else Color.Transparent,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "segBg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(bgColor)
                    .clickable(enabled = enabled) { onSelect(value) }
                    .padding(vertical = 10.dp, horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        !enabled -> cs.onSurfaceVariant.copy(alpha = 0.42f)
                        active -> cs.onPrimaryContainer
                        else -> cs.onSurfaceVariant
                    },
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TonalPillChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = CircleShape,
        elevation = null,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) cs.primaryContainer else cs.surfaceContainerHighest,
            contentColor = if (selected) cs.onPrimaryContainer else cs.onSurface,
            disabledContainerColor = cs.surfaceContainerHigh,
            disabledContentColor = cs.onSurfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun ExpressiveFeatureRow(
    iconRes: Int,
    shape: Shape,
    iconBg: Color,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    badgeText: String? = null,
    trailing: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TonalIconBadge(
            iconRes = iconRes,
            shape = shape,
            containerColor = iconBg,
            contentColor = iconTint
        )
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface
                )
                if (!badgeText.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    ExpressiveBadge(
                        text = badgeText,
                        containerColor = cs.secondaryContainer,
                        contentColor = cs.onSecondaryContainer
                    )
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        trailing()
    }
}

@Composable
private fun ExpressiveSwitchRow(
    iconRes: Int,
    shape: Shape = SquircleShape,
    iconBg: Color = MaterialTheme.colorScheme.secondaryContainer,
    iconTint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    title: String,
    subtitle: String? = null,
    badgeText: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    ExpressiveFeatureRow(
        iconRes = iconRes,
        shape = shape,
        iconBg = iconBg,
        iconTint = iconTint,
        title = title,
        subtitle = subtitle,
        badgeText = badgeText
    ) {
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = cs.onPrimary,
                checkedTrackColor = cs.primary,
                uncheckedThumbColor = cs.outline,
                uncheckedTrackColor = cs.surfaceContainerHighest,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

// ---------------------------------------------------------------- Tab 0: 耳机 (M3 Expressive Rose Hero)

@Composable
private fun EarbudsScreen(
    state: UiState,
    vm: AppViewModel,
    onRequestPermissions: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val melodyAlive by vm.melodyHookAlive.collectAsState()
    val rootBusy by vm.rootBusy.collectAsState()
    val cfg by vm.takeoverConfig.collectAsState()

    val hasBleBattery = state.battery != null
    val leftBat = if (hasBleBattery) state.battery?.left else state.system.systemBattery
    val rightBat = if (hasBleBattery) state.battery?.right else state.system.systemBattery
    val caseBat = state.battery?.case

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ExpressiveHeader(
            title = "JBL Tune 230NC",
            subtitle = when (val l = state.link) {
                LinkState.Idle -> if (state.system.a2dpConnected) "蓝牙音频已连接 · 点击右上角同步控制" else "耳机未连接"
                is LinkState.Connecting, is LinkState.Discovering -> "正在建立低延迟控制链路…"
                is LinkState.Ready -> "控制链路就绪 · ${if (cfg.keepLinkAlive) "零延迟常驻" else "按需连接"}"
                is LinkState.Failed -> l.reason
                LinkState.Disconnected -> "已释放控制链路"
            },
            trailing = {
                val dotColor = when {
                    state.controlReady -> Color(0xFF10B981)
                    state.link is LinkState.Connecting || state.link is LinkState.Discovering -> Color(0xFFF59E0B)
                    else -> cs.onSurfaceVariant
                }
                Surface(
                    color = cs.surfaceContainerHigh,
                    shape = CircleShape,
                    modifier = Modifier.clickable {
                        when {
                            !state.permissionsGranted -> onRequestPermissions()
                            state.controlReady -> vm.refreshAll()
                            else -> vm.connect()
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                !state.permissionsGranted -> "授权"
                                state.controlReady -> "刷新"
                                else -> "连接"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = cs.onSurface
                        )
                    }
                }
            }
        )

        // Rose / PrimaryContainer Gradient Hero Card
        val heroGradient = Brush.linearGradient(
            colors = listOf(
                cs.primaryContainer,
                lerp(cs.primaryContainer, cs.primary, 0.24f)
            )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(heroGradient)
                .padding(18.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val fw = state.deviceInfo?.firmwareVersion?.takeIf { it.isNotBlank() }
                    ExpressiveBadge(
                        text = if (state.controlReady) "已连接" else if (state.system.a2dpConnected) "音频在线" else "离线",
                        containerColor = cs.onPrimaryContainer.copy(alpha = 0.14f),
                        contentColor = cs.onPrimaryContainer
                    )
                    if (fw != null) {
                        ExpressiveBadge(
                            text = "FW $fw",
                            containerColor = cs.onPrimaryContainer.copy(alpha = 0.12f),
                            contentColor = cs.onPrimaryContainer
                        )
                    }
                    if (cfg.melodySpoofEnabled) {
                        ExpressiveBadge(
                            text = if (melodyAlive) cfg.spoofModelName else "待握手",
                            containerColor = cs.onPrimaryContainer.copy(alpha = 0.12f),
                            contentColor = cs.onPrimaryContainer
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_earbuds_hero),
                        contentDescription = "JBL Tune 230NC TWS",
                        modifier = Modifier.height(104.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeroBatteryTile(
                        label = "左耳 L",
                        percent = leftBat,
                        charging = state.battery?.leftCharging == true,
                        modifier = Modifier.weight(1f)
                    )
                    HeroBatteryTile(
                        label = "右耳 R",
                        percent = rightBat,
                        charging = state.battery?.rightCharging == true,
                        modifier = Modifier.weight(1f)
                    )
                    HeroBatteryTile(
                        label = "充电盒",
                        percent = caseBat,
                        charging = state.battery?.caseCharging == true,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (!state.controlReady || !state.permissionsGranted) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { if (state.permissionsGranted) vm.connect() else onRequestPermissions() },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cs.onPrimaryContainer,
                            contentColor = cs.primaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (state.permissionsGranted) "连接并同步三端电量" else "授予蓝牙附近设备权限",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 2-Column Expressive Quick Tiles (双列功能块)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickTileCard(
                title = "系统耳机页",
                subtitle = if (melodyAlive) "已直通欢律操控" else "打开 ColorOS 原生页",
                badge = if (melodyAlive) "默认" else "唤醒",
                iconRes = R.drawable.ic_m3_system,
                shape = SoftCloverShape,
                iconBg = cs.secondaryContainer,
                iconTint = cs.onSecondaryContainer,
                enabled = cfg.melodySpoofEnabled && !rootBusy,
                modifier = Modifier.weight(1f),
                onClick = { vm.openMelodyDetail() }
            )
            QuickTileCard(
                title = "欢律 3D 弹窗",
                subtitle = if (rootBusy) "正在唤起…" else "触发官方开盖动效",
                badge = if (melodyAlive) "就绪" else "Root",
                iconRes = R.drawable.ic_m3_popup,
                shape = OrganicBlobShape,
                iconBg = cs.tertiaryContainer,
                iconTint = cs.onTertiaryContainer,
                enabled = cfg.melodySpoofEnabled && !rootBusy,
                modifier = Modifier.weight(1f),
                onClick = { vm.triggerMelodyPopup() }
            )
        }

        // Grouped Noise Control Card
        GroupedListCard(
            title = "噪声控制",
            subtitle = if (state.controlReady) "当前模式：${BridgeContract.noiseModeLabel(state.noiseMode)}" else "连接耳机后可即时切换",
            badgeText = if (state.noiseMode != NoiseMode.OFF) BridgeContract.noiseModeLabel(state.noiseMode) else null,
            badgeContainer = cs.primaryContainer,
            badgeContent = cs.onPrimaryContainer
        ) {
            ExpressiveSegmentedBar(
                options = listOf(
                    NoiseMode.ANC to "降噪",
                    NoiseMode.AMBIENT_AWARE to "环境感知",
                    NoiseMode.OFF to "关闭"
                ),
                selected = state.noiseMode,
                enabled = state.controlReady,
                onSelect = { vm.setNoiseMode(it) }
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.55f), thickness = 0.6.dp)
            Spacer(Modifier.height(6.dp))
            ExpressiveFeatureRow(
                iconRes = R.drawable.ic_m3_anc,
                shape = OrganicBlobShape,
                iconBg = cs.secondaryContainer,
                iconTint = cs.onSecondaryContainer,
                title = "自由通话（TalkThru）",
                subtitle = "临时降低音乐并放大近场人声",
                badgeText = if (state.noiseMode == NoiseMode.TALKTHRU) "已开启" else null
            ) {
                TonalPillChip(
                    text = if (state.noiseMode == NoiseMode.TALKTHRU) "关闭" else "开启",
                    selected = state.noiseMode == NoiseMode.TALKTHRU,
                    enabled = state.controlReady
                ) {
                    val target = if (state.noiseMode == NoiseMode.TALKTHRU) NoiseMode.OFF else NoiseMode.TALKTHRU
                    vm.setNoiseMode(target)
                }
            }
        }

        if (state.system.musicMaxVolume > 0) {
            GroupedListCard {
                ExpressiveFeatureRow(
                    iconRes = R.drawable.ic_m3_volume,
                    shape = SquircleShape,
                    iconBg = cs.secondaryContainer,
                    iconTint = cs.onSecondaryContainer,
                    title = "媒体音量",
                    subtitle = "${state.system.musicVolume} / ${state.system.musicMaxVolume}"
                ) {}
                Slider(
                    value = state.system.musicVolume.coerceAtLeast(0).toFloat(),
                    onValueChange = { vm.setVolume(it.toInt()) },
                    valueRange = 0f..state.system.musicMaxVolume.toFloat(),
                    steps = state.system.musicMaxVolume.coerceAtLeast(1) - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = cs.primary,
                        activeTrackColor = cs.primary,
                        inactiveTrackColor = cs.surfaceContainerHighest
                    )
                )
            }
        }

        if (state.message.isNotBlank()) {
            Text(
                text = state.message,
                fontSize = 11.sp,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp)
            )
        }

        Spacer(Modifier.height(104.dp).navigationBarsPadding())
    }
}

@Composable
private fun HeroBatteryTile(
    label: String,
    percent: Int?,
    charging: Boolean,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val ink = cs.onPrimaryContainer
    val valid = percent != null && percent in 0..100
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(ink.copy(alpha = 0.09f))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = ink.copy(alpha = 0.78f), modifier = Modifier.weight(1f))
            if (charging && valid) {
                Text("⚡", fontSize = 11.sp, color = ink)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = if (valid) "$percent%" else "--",
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ink
        )
        Spacer(Modifier.height(7.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
                .background(ink.copy(alpha = 0.16f))
        ) {
            if (valid) {
                val barColor = if ((percent ?: 100) <= 20) cs.error else ink
                Box(
                    modifier = Modifier
                        .fillMaxWidth((percent!!.coerceIn(0, 100)) / 100f)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(barColor)
                )
            }
        }
    }
}

@Composable
private fun QuickTileCard(
    title: String,
    subtitle: String,
    badge: String,
    iconRes: Int,
    shape: Shape,
    iconBg: Color,
    iconTint: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        modifier = modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TonalIconBadge(
                    iconRes = iconRes,
                    shape = shape,
                    containerColor = iconBg,
                    contentColor = iconTint
                )
                ExpressiveBadge(
                    text = badge,
                    containerColor = iconBg.copy(alpha = 0.72f),
                    contentColor = iconTint
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) cs.onSurface else cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------- Tab 1: 音效

@Composable
private fun SoundScreen(state: UiState, vm: AppViewModel) {
    val cs = MaterialTheme.colorScheme
    var showSliders by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ExpressiveHeader(title = "音效调音", subtitle = "均衡器预设与声学模式")

        GroupedListCard(
            title = "均衡器预设",
            subtitle = state.eqDetail,
            badgeText = vm.eqPresets.firstOrNull { it.id == state.eqPresetId }?.label ?: "自定义",
            badgeContainer = cs.primaryContainer,
            badgeContent = cs.onPrimaryContainer
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.eqPresets) { preset ->
                    TonalPillChip(
                        text = preset.label,
                        selected = state.eqPresetId == preset.id,
                        enabled = state.controlReady,
                        onClick = { vm.setEqPreset(preset) }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            EqCurve(state.eqGains)
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cs.surfaceContainerHigh)
                    .clickable { showSliders = !showSliders }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TonalIconBadge(
                    iconRes = R.drawable.ic_m3_eq,
                    shape = SquircleShape,
                    containerColor = cs.secondaryContainer,
                    contentColor = cs.onSecondaryContainer,
                    badgeSize = 34.dp,
                    iconSize = 18.dp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (showSliders) "收起 10 段自定义均衡器" else "自定义 10 段均衡器",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f)
                )
                ExpressiveBadge(
                    text = if (showSliders) "收起" else "展开",
                    containerColor = cs.surfaceContainerHighest,
                    contentColor = cs.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = showSliders) {
                Column(Modifier.padding(top = 12.dp)) {
                    vm.bandHz.forEachIndexed { index, hz ->
                        val rawGain = state.eqGains.getOrNull(index) ?: 0f
                        val gain = if (rawGain.isFinite()) rawGain.coerceIn(-6f, 6f) else 0f
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (hz >= 1000) "${hz / 1000}k" else "$hz",
                                fontSize = 11.sp,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.width(36.dp)
                            )
                            Slider(
                                value = gain,
                                onValueChange = { vm.setCustomBand(index, it) },
                                valueRange = -6f..6f,
                                steps = 23,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = cs.primary,
                                    activeTrackColor = cs.primary,
                                    inactiveTrackColor = cs.surfaceContainerHighest
                                )
                            )
                            Text(
                                text = String.format(Locale.US, "%+.1fdB", gain),
                                fontSize = 11.sp,
                                color = cs.onSurface,
                                modifier = Modifier.width(50.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.applyCustomEq() },
                        enabled = state.controlReady,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cs.primaryContainer,
                            contentColor = cs.onPrimaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("应用并验证自定义曲线", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        GroupedListCard(title = "声学策略") {
            ExpressiveFeatureRow(
                iconRes = R.drawable.ic_m3_anc,
                shape = SoftCloverShape,
                iconBg = cs.secondaryContainer,
                iconTint = cs.onSecondaryContainer,
                title = "智能音视频模式",
                subtitle = "观影低延迟与纯享音质切换",
                badgeText = state.smartAudioMode.label
            ) {}
            Spacer(Modifier.height(6.dp))
            ExpressiveSegmentedBar(
                options = SmartAudioMode.values().map { it to it.label },
                selected = state.smartAudioMode,
                enabled = state.controlReady,
                onSelect = { vm.setSmartAudioMode(it) }
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.55f), thickness = 0.6.dp)
            Spacer(Modifier.height(10.dp))

            ExpressiveFeatureRow(
                iconRes = R.drawable.ic_m3_voice,
                shape = OrganicBlobShape,
                iconBg = cs.tertiaryContainer,
                iconTint = cs.onTertiaryContainer,
                title = "通话自听（VoiceAware）",
                subtitle = "通话时回传人声，缓解入耳闷感",
                badgeText = "档位 ${state.voiceAwareLevel}"
            ) {}
            Spacer(Modifier.height(6.dp))
            ExpressiveSegmentedBar(
                options = listOf(0 to "关闭", 1 to "低", 2 to "中", 3 to "高"),
                selected = state.voiceAwareLevel,
                enabled = state.controlReady,
                onSelect = { vm.setVoiceAwareLevel(it) }
            )
        }

        Spacer(Modifier.height(104.dp).navigationBarsPadding())
    }
}

@Composable
private fun EqCurve(gains: List<Float>) {
    val cs = MaterialTheme.colorScheme
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(98.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
        drawLine(
            color = cs.onSurfaceVariant.copy(alpha = 0.28f),
            start = Offset(0f, mid),
            end = Offset(w, mid),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dash
        )

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
            brush = Brush.verticalGradient(listOf(cs.primary.copy(alpha = 0.28f), Color.Transparent))
        )
        drawPath(curve, cs.primary, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round))
        points.forEach { pt ->
            drawCircle(cs.primary, radius = 3.4.dp.toPx(), center = pt)
            drawCircle(cs.surfaceContainer, radius = 1.5.dp.toPx(), center = pt)
        }
    }
}

// ---------------------------------------------------------------- Tab 2: 操控

@Composable
private fun ControlsScreen(state: UiState, vm: AppViewModel) {
    val cs = MaterialTheme.colorScheme
    if (state.findBudsConfirmPending) {
        AlertDialog(
            containerColor = cs.surfaceContainerHigh,
            titleContentColor = cs.onSurface,
            textContentColor = cs.onSurfaceVariant,
            onDismissRequest = { vm.dismissFindMyBudsConfirm() },
            title = { Text("请确认已摘下耳机", fontWeight = FontWeight.Bold) },
            text = { Text("查找耳机将发出高频蜂鸣提示，为保护听力请勿佩戴时开启（15 秒后自动停止）。") },
            confirmButton = {
                Button(
                    onClick = { vm.confirmFindMyBuds() },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cs.errorContainer,
                        contentColor = cs.onErrorContainer
                    )
                ) { Text("确认响铃", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissFindMyBudsConfirm() }) {
                    Text("取消", color = cs.onSurfaceVariant)
                }
            }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ExpressiveHeader(title = "触控与习惯", subtitle = "手势映射、使用习惯与查找耳机")

        GroupedListCard(
            title = "触控手势",
            subtitle = "单击 · 双击 · 三击快捷功能组"
        ) {
            GestureSideSection(
                label = "左耳 L",
                current = state.leftGestureGroup,
                holdVoice = state.leftHoldVoiceAssistant,
                enabled = state.controlReady,
                onGroup = { vm.setGestureGroup(true, it, state.leftHoldVoiceAssistant) },
                onHold = { vm.setGestureGroup(true, state.leftGestureGroup, it) }
            )
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.55f), modifier = Modifier.padding(vertical = 12.dp))
            GestureSideSection(
                label = "右耳 R",
                current = state.rightGestureGroup,
                holdVoice = state.rightHoldVoiceAssistant,
                enabled = state.controlReady,
                onGroup = { vm.setGestureGroup(false, it, state.rightHoldVoiceAssistant) },
                onHold = { vm.setGestureGroup(false, state.rightGestureGroup, it) }
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TonalPillChip(
                    text = "恢复默认手势",
                    selected = false,
                    enabled = state.controlReady
                ) { vm.resetGestures() }
            }
        }

        GroupedListCard(title = "使用习惯与省电") {
            ExpressiveSwitchRow(
                iconRes = R.drawable.ic_m3_power,
                shape = SoftCloverShape,
                title = "自动播放 / 暂停",
                subtitle = "摘下或放回时自动暂停或恢复音频",
                checked = state.autoPlayPause,
                enabled = state.controlReady,
                onCheckedChange = { vm.setAutoPlayPause(it) }
            )
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 6.dp))
            ExpressiveSwitchRow(
                iconRes = R.drawable.ic_m3_anc,
                shape = OrganicBlobShape,
                title = "待机时保持降噪",
                subtitle = "无媒体播放时持续保留当前降噪状态",
                checked = state.standbyKeepAnc,
                enabled = state.controlReady,
                onCheckedChange = { vm.setStandbyKeepAnc(it) }
            )
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 6.dp))
            ExpressiveSwitchRow(
                iconRes = R.drawable.ic_m3_power,
                shape = SquircleShape,
                title = "自动关机",
                subtitle = if (state.autoOffEnabled) "${state.autoOffMinutes} 分钟无音频后关机" else "已关闭自动关机",
                badgeText = if (state.autoOffEnabled) "${state.autoOffMinutes}m" else null,
                checked = state.autoOffEnabled,
                enabled = state.controlReady,
                onCheckedChange = { vm.setAutoPowerOff(it, state.autoOffMinutes) }
            )
            AnimatedVisibility(visible = state.autoOffEnabled) {
                Column(Modifier.padding(top = 6.dp, bottom = 4.dp)) {
                    ExpressiveSegmentedBar(
                        options = listOf(15 to "15 分钟", 30 to "30 分钟", 60 to "60 分钟"),
                        selected = state.autoOffMinutes,
                        enabled = state.controlReady,
                        onSelect = { vm.setAutoPowerOff(true, it) }
                    )
                }
            }
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 10.dp))
            ExpressiveFeatureRow(
                iconRes = R.drawable.ic_m3_voice,
                shape = SoftCloverShape,
                iconBg = cs.tertiaryContainer,
                iconTint = cs.onTertiaryContainer,
                title = "默认语音助手",
                subtitle = "长按手势触发的语音服务"
            ) {}
            Spacer(Modifier.height(6.dp))
            ExpressiveSegmentedBar(
                options = listOf(0x00 to "系统默认", 0x01 to "Google", 0xDF to "关闭"),
                selected = state.multiAiMode,
                enabled = state.controlReady,
                onSelect = { vm.setMultiAiMode(it) }
            )
        }

        GroupedListCard(
            title = "查找耳机",
            subtitle = "发出高频蜂鸣提示定位遗落耳机（请先摘下）"
        ) {
            ExpressiveFeatureRow(
                iconRes = R.drawable.ic_m3_find,
                shape = OrganicBlobShape,
                iconBg = cs.errorContainer,
                iconTint = cs.onErrorContainer,
                title = "蜂鸣发声频道",
                subtitle = state.findBudsDetail,
                badgeText = if (state.findMyBuds.leftBeeping || state.findMyBuds.rightBeeping) "响铃中" else null
            ) {}
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TonalPillChip(
                    "响左耳",
                    state.findMyBuds.leftBeeping && !state.findMyBuds.rightBeeping,
                    state.controlReady,
                    Modifier.weight(1f)
                ) { vm.requestFindMyBuds(left = true, right = false) }
                TonalPillChip(
                    "响右耳",
                    !state.findMyBuds.leftBeeping && state.findMyBuds.rightBeeping,
                    state.controlReady,
                    Modifier.weight(1f)
                ) { vm.requestFindMyBuds(left = false, right = true) }
                TonalPillChip(
                    "响双耳",
                    state.findMyBuds.leftBeeping && state.findMyBuds.rightBeeping,
                    state.controlReady,
                    Modifier.weight(1f)
                ) { vm.requestFindMyBuds(left = true, right = true) }
            }
            if (state.findMyBuds.leftBeeping || state.findMyBuds.rightBeeping) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { vm.stopFindMyBuds() },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cs.errorContainer,
                        contentColor = cs.onErrorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("停止响铃", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        }

        Spacer(Modifier.height(104.dp).navigationBarsPadding())
    }
}

@Composable
private fun GestureSideSection(
    label: String,
    current: GestureFunctionGroup,
    holdVoice: Boolean,
    enabled: Boolean,
    onGroup: (GestureFunctionGroup) -> Unit,
    onHold: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    ExpressiveFeatureRow(
        iconRes = R.drawable.ic_m3_gesture,
        shape = SquircleShape,
        iconBg = cs.secondaryContainer,
        iconTint = cs.onSecondaryContainer,
        title = label,
        subtitle = "当前功能组：${current.label}"
    ) {}
    Spacer(Modifier.height(6.dp))
    ExpressiveSegmentedBar(
        options = GestureFunctionGroup.values().map { it to it.label },
        selected = current,
        enabled = enabled,
        onSelect = onGroup
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "长按唤醒语音助手",
            fontSize = 13.sp,
            color = cs.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = holdVoice,
            enabled = enabled,
            onCheckedChange = onHold,
            colors = SwitchDefaults.colors(
                checkedThumbColor = cs.onPrimary,
                checkedTrackColor = cs.primary,
                uncheckedThumbColor = cs.outline,
                uncheckedTrackColor = cs.surfaceContainerHighest,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

// ---------------------------------------------------------------- Tab 3: 系统与外观

@Composable
private fun SettingsScreen(state: UiState, vm: AppViewModel) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val cfg by vm.takeoverConfig.collectAsState()
    val melodyAlive by vm.melodyHookAlive.collectAsState()
    val melodyStatus by vm.melodyHookStatus.collectAsState()
    val rootBusy by vm.rootBusy.collectAsState()
    val fluidReady by vm.fluidCloudReady.collectAsState()
    val running by vm.diagRunning.collectAsState()
    val progress by vm.diagProgress.collectAsState()
    val steps by vm.diagSteps.collectAsState()
    val logs by vm.logs.collectAsState()
    var showDevPanel by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(vm.buildDiagnosticReport().toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
            vm.setToastMessage(if (ok) "诊断报告已保存。" else "保存失败。")
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ExpressiveHeader(title = "系统与外观", subtitle = "ColorOS 原生接管 · Material 3 动态主题 · 诊断")

        GroupedListCard(
            title = "ColorOS 官方耳机接管",
            subtitle = "LSPosed 作用域需勾选 欢律 / 无线设置 / 配件服务",
            badgeText = if (melodyAlive) "已握手" else "待激活",
            badgeContainer = if (melodyAlive) cs.primaryContainer else cs.surfaceContainerHighest,
            badgeContent = if (melodyAlive) cs.onPrimaryContainer else cs.onSurfaceVariant
        ) {
            ExpressiveFeatureRow(
                iconRes = R.drawable.ic_m3_system,
                shape = SoftCloverShape,
                iconBg = cs.secondaryContainer,
                iconTint = cs.onSecondaryContainer,
                title = "欢律实时状态",
                subtitle = melodyStatus
            ) {
                TonalPillChip(
                    text = if (rootBusy) "执行中" else "Root 重启",
                    selected = false,
                    enabled = cfg.melodySpoofEnabled && !rootBusy,
                    onClick = { vm.restartAndWakeMelody() }
                )
            }

            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))

            ExpressiveSwitchRow(
                iconRes = R.drawable.ic_m3_popup,
                shape = OrganicBlobShape,
                title = "欢律官方耳机伪装",
                subtitle = "系统白名单注入 · 精简功能组 · 双向控耳机",
                checked = cfg.melodySpoofEnabled,
                onCheckedChange = { c -> vm.updateTakeoverConfig { it.copy(melodySpoofEnabled = c) } }
            )
            ExpressiveSwitchRow(
                iconRes = R.drawable.ic_m3_popup,
                shape = SquircleShape,
                title = "连接自动弹 3D 开盖动画",
                checked = cfg.melodyAutoPopup,
                enabled = cfg.melodySpoofEnabled,
                onCheckedChange = { c -> vm.updateTakeoverConfig { it.copy(melodyAutoPopup = c) } }
            )
            ExpressiveSwitchRow(
                iconRes = R.drawable.ic_m3_cloud,
                shape = SoftCloverShape,
                title = "连接自动上流体云",
                subtitle = if (fluidReady) "实时通知权限已就绪" else "请点下方开启实时活动权限",
                badgeText = if (fluidReady) "已授权" else "未授权",
                checked = cfg.useFluidCloud && cfg.melodyAutoCapsule,
                onCheckedChange = { c ->
                    vm.updateTakeoverConfig { it.copy(useFluidCloud = c, melodyAutoCapsule = c) }
                }
            )
            ExpressiveSwitchRow(
                iconRes = R.drawable.ic_m3_power,
                shape = SquircleShape,
                title = "后台常驻控制链路",
                subtitle = "保持 BLE 通道活跃，系统耳机页切换零延迟",
                checked = cfg.keepLinkAlive,
                onCheckedChange = { c -> vm.updateTakeoverConfig { it.copy(keepLinkAlive = c) } }
            )

            Spacer(Modifier.height(10.dp))
            Text("伪装目标 OPPO 型号：", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(BridgeContract.SPOOF_PRESETS) { preset ->
                    TonalPillChip(
                        text = preset.melodyName,
                        selected = cfg.spoofModelName == preset.melodyName,
                        enabled = cfg.melodySpoofEnabled,
                        onClick = { vm.updateTakeoverConfig { it.copy(spoofModelName = preset.melodyName) } }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.openMelodyDetail() },
                    enabled = cfg.melodySpoofEnabled && !rootBusy,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cs.primaryContainer,
                        contentColor = cs.onPrimaryContainer
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("打开系统耳机页", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                TonalPillChip(
                    text = if (fluidReady) "测试流体云" else "开启权限",
                    selected = false,
                    enabled = true,
                    modifier = Modifier.weight(1f),
                    onClick = { if (fluidReady) vm.testFluidCloud() else vm.openFluidCloudSettings() }
                )
            }
        }

        GroupedListCard(title = "界面质感与主题") {
            ExpressiveSwitchRow(
                iconRes = R.drawable.ic_m3_theme,
                shape = SoftCloverShape,
                iconBg = cs.primaryContainer,
                iconTint = cs.onPrimaryContainer,
                title = "M3 壁纸动态取色",
                subtitle = "开启后跟随 ColorOS 壁纸主色（Android 12+）",
                badgeText = if (cfg.useDynamicColor) "Dynamic" else "玫瑰粉",
                checked = cfg.useDynamicColor,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                onCheckedChange = { c -> vm.updateTakeoverConfig { it.copy(useDynamicColor = c) } }
            )
        }

        GroupedListCard(
            title = "高级诊断与日志导出",
            subtitle = "一键测试全量协议指令并导出 .txt（无联网权限）",
            badgeText = "离线安全"
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cs.surfaceContainerHigh)
                    .clickable { showDevPanel = !showDevPanel }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TonalIconBadge(
                    iconRes = R.drawable.ic_m3_diag,
                    shape = OrganicBlobShape,
                    containerColor = cs.secondaryContainer,
                    contentColor = cs.onSecondaryContainer,
                    badgeSize = 34.dp,
                    iconSize = 18.dp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (showDevPanel) "收起诊断与日志终端" else "展开协议自检、硬件信息与帧日志",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f)
                )
                ExpressiveBadge(
                    text = if (showDevPanel) "收起" else "展开",
                    containerColor = cs.surfaceContainerHighest,
                    contentColor = cs.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = showDevPanel) {
                Column(Modifier.padding(top = 12.dp)) {
                    val info = state.deviceInfo
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} · FW ${info?.firmwareVersion?.ifBlank { "--" } ?: "--"} · S/N ${state.serialNumber ?: "--"} · 盒 ${state.caseVersion ?: "--"}",
                        fontSize = 11.sp,
                        color = cs.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.runSelfTest() },
                        enabled = !running,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cs.primaryContainer,
                            contentColor = cs.onPrimaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (running) "正在执行自检…" else "一键全量协议测试",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TonalPillChip("分享 .txt", false, true, Modifier.weight(1f)) { vm.shareDiagnostics() }
                        TonalPillChip("保存文件", false, true, Modifier.weight(1f)) {
                            saveLauncher.launch(DiagnosticExporter.buildFileName())
                        }
                        TonalPillChip("复制报告", false, true, Modifier.weight(1f)) { vm.copyDiagnostics() }
                    }
                    if (progress.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(progress, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = cs.primary)
                    }
                    if (steps.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        steps.forEach { s ->
                            val dark = isSystemInDarkTheme()
                            val (bg, fg) = when (s.status) {
                                DiagStatus.PASS -> if (dark) Color(0xFF123526) to Color(0xFF34D399) else Color(0xFFD1FAE5) to Color(0xFF047857)
                                DiagStatus.WARN -> if (dark) Color(0xFF3B2A0A) to Color(0xFFFBBF24) else Color(0xFFFEF3C7) to Color(0xFFB45309)
                                DiagStatus.FAIL -> cs.errorContainer to cs.onErrorContainer
                                DiagStatus.SKIP -> cs.surfaceContainerHighest to cs.onSurfaceVariant
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(cs.surfaceContainerHigh)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ExpressiveBadge(s.status.tag, bg, fg)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        s.label,
                                        color = cs.onSurface,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("${s.durationMs}ms", color = cs.onSurfaceVariant, fontSize = 10.sp)
                                }
                                Text(s.detail, color = cs.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("蓝牙十六进制日志 (${logs.size})", fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.clearLog() }) { Text("清空", fontSize = 11.sp, color = cs.primary) }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF0A080B))
                            .padding(10.dp)
                    ) {
                        LazyColumn {
                            if (logs.isEmpty()) {
                                item { Text("（暂无记录）", color = cs.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                            } else {
                                items(logs.takeLast(120)) { e ->
                                    Text(
                                        "${e.time} ${e.direction} ${e.frameHex} ${e.note}",
                                        color = if (e.direction == "←") Color(0xFF34D399) else cs.primary,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(104.dp).navigationBarsPadding())
    }
}
