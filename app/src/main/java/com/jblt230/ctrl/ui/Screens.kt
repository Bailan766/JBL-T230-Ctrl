package com.jblt230.ctrl.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.roundToInt

private data class ColorOsPalette(
    val dark: Boolean,
    val bg: Color,
    val navBg: Color,
    val card: Color,
    val track: Color,
    val thumb: Color,
    val divider: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val green: Color,
    val amber: Color,
    val red: Color,
    val logTerminalBg: Color
)

@Composable
private fun rememberPalette(dark: Boolean = isSystemInDarkTheme()): ColorOsPalette =
    remember(dark) {
        if (dark) {
            ColorOsPalette(
                dark = true,
                bg = Color(0xFF000000),
                navBg = Color(0xFF0E1015),
                card = Color(0xFF17191F),
                track = Color(0xFF242730),
                thumb = Color(0xFF333744),
                divider = Color(0xFF262A34),
                ink = Color(0xFFF5F7FA),
                muted = Color(0xFF8A92A0),
                accent = Color(0xFF3B82F6),
                green = Color(0xFF10B981),
                amber = Color(0xFFF59E0B),
                red = Color(0xFFEF4444),
                logTerminalBg = Color(0xFF0A0C10)
            )
        } else {
            ColorOsPalette(
                dark = false,
                bg = Color(0xFFF5F6F8),
                navBg = Color(0xFFFFFFFF),
                card = Color(0xFFFFFFFF),
                track = Color(0xFFEBEEF3),
                thumb = Color(0xFFFFFFFF),
                divider = Color(0xFFECEFF4),
                ink = Color(0xFF111318),
                muted = Color(0xFF7A828F),
                accent = Color(0xFF1470FF),
                green = Color(0xFF0FA968),
                amber = Color(0xFFD97706),
                red = Color(0xFFDC2626),
                logTerminalBg = Color(0xFF14171D)
            )
        }
    }

private data class NavTab(val label: String, val iconRes: Int)

private val NAV_TABS = listOf(
    NavTab("耳机", R.drawable.ic_nav_earbuds),
    NavTab("音效", R.drawable.ic_nav_sound),
    NavTab("操控", R.drawable.ic_nav_touch),
    NavTab("设置", R.drawable.ic_nav_settings)
)

@Composable
fun AppRoot(vm: AppViewModel, onRequestPermissions: () -> Unit) {
    val state by vm.state.collectAsState()
    val p = rememberPalette()
    var tab by remember { mutableStateOf(0) }

    MaterialTheme(
        colorScheme = if (p.dark) {
            darkColorScheme(
                primary = p.accent,
                secondary = p.green,
                background = p.bg,
                surface = p.card,
                surfaceVariant = p.track,
                onPrimary = Color.White,
                onBackground = p.ink,
                onSurface = p.ink,
                onSurfaceVariant = p.muted
            )
        } else {
            lightColorScheme(
                primary = p.accent,
                secondary = p.green,
                background = p.bg,
                surface = p.card,
                surfaceVariant = p.track,
                onPrimary = Color.White,
                onBackground = p.ink,
                onSurface = p.ink,
                onSurfaceVariant = p.muted
            )
        }
    ) {
        Scaffold(
            containerColor = p.bg,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Column(
                    Modifier
                        .background(p.navBg)
                        .navigationBarsPadding()
                ) {
                    HorizontalDivider(color = p.divider, thickness = 0.6.dp)
                    NavigationBar(
                        containerColor = p.navBg,
                        tonalElevation = 0.dp
                    ) {
                        NAV_TABS.forEachIndexed { index, item ->
                            val selected = tab == index
                            NavigationBarItem(
                                selected = selected,
                                onClick = { tab = index },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = p.accent,
                                    selectedTextColor = p.accent,
                                    unselectedIconColor = p.muted,
                                    unselectedTextColor = p.muted,
                                    indicatorColor = p.accent.copy(alpha = 0.12f)
                                ),
                                icon = {
                                    Icon(
                                        painter = painterResource(item.iconRes),
                                        contentDescription = item.label,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        item.label,
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
                    .background(p.bg)
                    .padding(padding)
                    .statusBarsPadding()
            ) {
                when (tab) {
                    0 -> EarbudsScreen(state, vm, p, onRequestPermissions)
                    1 -> SoundScreen(state, vm, p)
                    2 -> ControlsScreen(state, vm, p)
                    else -> SettingsScreen(state, vm, p)
                }
            }
        }
    }
}

@Composable
private fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    p: ColorOsPalette,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = p.ink)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
private fun FluidCard(
    p: ColorOsPalette,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        color = p.card,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(18.dp)) {
            if (!title.isNullOrBlank()) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.height(14.dp))
            }
            content()
        }
    }
}

@Composable
private fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    p: ColorOsPalette,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.track)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val bgColor by animateColorAsState(
                targetValue = if (active) p.thumb else Color.Transparent,
                animationSpec = tween(180),
                label = "segBg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .then(if (active) Modifier.shadow(2.dp, RoundedCornerShape(12.dp)) else Modifier)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bgColor)
                    .clickable(enabled = enabled) { onSelect(value) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = when {
                        !enabled -> p.muted.copy(alpha = 0.45f)
                        active -> p.ink
                        else -> p.muted
                    },
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun PillChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    p: ColorOsPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        elevation = null,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) p.accent else p.track,
            contentColor = if (selected) Color.White else p.ink,
            disabledContainerColor = p.track.copy(alpha = 0.5f),
            disabledContentColor = p.muted.copy(alpha = 0.5f)
        )
    ) {
        Text(text, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    p: ColorOsPalette,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.ink)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 11.sp, color = p.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = p.accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = if (p.dark) Color(0xFF3A3E4A) else Color(0xFFD2D7E0),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

// ---------------------------------------------------------------- Tab 0: 耳机 (ColorOS Hero)

@Composable
private fun EarbudsScreen(
    state: UiState,
    vm: AppViewModel,
    p: ColorOsPalette,
    onRequestPermissions: () -> Unit
) {
    val fluidReady by vm.fluidCloudReady.collectAsState()
    val melodyAlive by vm.melodyHookAlive.collectAsState()
    val melodyStatus by vm.melodyHookStatus.collectAsState()
    val rootBusy by vm.rootBusy.collectAsState()
    val cfg by vm.takeoverConfig.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(
            title = "JBL Tune 230NC",
            subtitle = when (val l = state.link) {
                LinkState.Idle -> if (state.system.a2dpConnected) "音频已连接 · 待建立控制" else "耳机未连接"
                is LinkState.Connecting, is LinkState.Discovering -> "正在连接耳机…"
                is LinkState.Ready -> {
                    val fw = state.deviceInfo?.firmwareVersion?.takeIf { it.isNotBlank() }?.let { "v$it · " } ?: ""
                    "${fw}已连接"
                }
                is LinkState.Failed -> l.reason
                LinkState.Disconnected -> "已断开控制链路"
            },
            p = p,
            trailing = {
                val dotColor = when {
                    state.controlReady -> p.green
                    state.link is LinkState.Connecting || state.link is LinkState.Discovering -> p.amber
                    else -> p.muted
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(p.card)
                        .clickable {
                            when {
                                !state.permissionsGranted -> onRequestPermissions()
                                state.controlReady -> vm.refreshAll()
                                else -> vm.connect()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
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
                        fontWeight = FontWeight.SemiBold,
                        color = p.ink
                    )
                }
            }
        )

        // Hero Earbud Card
        FluidCard(p = p) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(R.drawable.ic_earbuds_hero),
                    contentDescription = "JBL Tune 230NC TWS",
                    modifier = Modifier.height(108.dp)
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BatteryMeterTile(
                        label = "左耳 L",
                        percent = state.battery?.left ?: state.system.systemBattery,
                        charging = state.battery?.leftCharging == true,
                        p = p,
                        modifier = Modifier.weight(1f)
                    )
                    BatteryMeterTile(
                        label = "右耳 R",
                        percent = state.battery?.right ?: state.system.systemBattery,
                        charging = state.battery?.rightCharging == true,
                        p = p,
                        modifier = Modifier.weight(1f)
                    )
                    BatteryMeterTile(
                        label = "充电盒",
                        percent = state.battery?.case,
                        charging = state.battery?.caseCharging == true,
                        p = p,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (!state.controlReady || !state.permissionsGranted) {
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { if (state.permissionsGranted) vm.connect() else onRequestPermissions() },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (state.permissionsGranted) "连接并同步耳机" else "授予蓝牙附近设备权限",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // Quick Noise Control Card
        FluidCard(
            p = p,
            title = "噪声控制",
            subtitle = if (state.controlReady) "当前：${when (state.noiseMode) {
                NoiseMode.ANC -> "降噪"
                NoiseMode.AMBIENT_AWARE -> "环境感知"
                NoiseMode.TALKTHRU -> "自由通话"
                NoiseMode.OFF -> "关闭"
            }}" else "连接后即可切换"
        ) {
            SegmentedControl(
                options = listOf(
                    NoiseMode.ANC to "降噪",
                    NoiseMode.AMBIENT_AWARE to "环境感知",
                    NoiseMode.OFF to "关闭"
                ),
                selected = state.noiseMode,
                enabled = state.controlReady,
                p = p,
                onSelect = { vm.setNoiseMode(it) }
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("交谈模式（TalkThru）", fontSize = 12.sp, color = p.muted, modifier = Modifier.weight(1f))
                PillChip(
                    text = if (state.noiseMode == NoiseMode.TALKTHRU) "已开启" else "点击开启",
                    selected = state.noiseMode == NoiseMode.TALKTHRU,
                    enabled = state.controlReady,
                    p = p
                ) {
                    val target = if (state.noiseMode == NoiseMode.TALKTHRU) NoiseMode.OFF else NoiseMode.TALKTHRU
                    vm.setNoiseMode(target)
                }
            }
        }

        // Official OPPO Melody Spoofing + Fluid Cloud Quick Card
        FluidCard(
            p = p,
            title = "OPPO 官方耳机伪装 · ${cfg.spoofModelName}",
            subtitle = when {
                !cfg.melodySpoofEnabled -> "已关闭欢律伪装（可在设置重新开启）"
                melodyAlive -> melodyStatus
                else -> "待激活 · 点右侧「Root 重启欢律」或确认 LSPosed 已勾选欢律"
            }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.triggerMelodyPopup() },
                    enabled = cfg.melodySpoofEnabled && !rootBusy,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (rootBusy) "执行中…" else "欢律 3D 弹窗", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { vm.triggerMelodyCapsule() },
                    enabled = cfg.melodySpoofEnabled && !rootBusy,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (melodyAlive) p.green else p.track,
                        contentColor = if (melodyAlive) Color.White else p.ink
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("欢律原生流体云", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    melodyStatus,
                    fontSize = 11.sp,
                    color = if (melodyAlive) p.green else p.muted,
                    modifier = Modifier.weight(1f)
                )
                PillChip(
                    text = if (rootBusy) "重启中…" else "Root 重启欢律",
                    selected = false,
                    enabled = cfg.melodySpoofEnabled && !rootBusy,
                    p = p,
                    onClick = { vm.restartAndWakeMelody() }
                )
                PillChip(
                    text = if (fluidReady) "本机流体云" else "开启权限",
                    selected = false,
                    enabled = true,
                    p = p,
                    onClick = { if (fluidReady) vm.testFluidCloud() else vm.openFluidCloudSettings() }
                )
            }
        }

        if (state.system.musicMaxVolume > 0) {
            FluidCard(p = p, title = "媒体音量", subtitle = "${state.system.musicVolume} / ${state.system.musicMaxVolume}") {
                Slider(
                    value = state.system.musicVolume.coerceAtLeast(0).toFloat(),
                    onValueChange = { vm.setVolume(it.toInt()) },
                    valueRange = 0f..state.system.musicMaxVolume.toFloat(),
                    steps = state.system.musicMaxVolume.coerceAtLeast(1) - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = p.accent,
                        activeTrackColor = p.accent,
                        inactiveTrackColor = p.track
                    )
                )
            }
        }

        if (state.message.isNotBlank()) {
            Text(
                text = state.message,
                fontSize = 11.sp,
                color = p.muted,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp)
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BatteryMeterTile(
    label: String,
    percent: Int?,
    charging: Boolean,
    p: ColorOsPalette,
    modifier: Modifier = Modifier
) {
    val barColor = when {
        percent != null && percent <= 20 -> p.red
        charging -> p.accent
        else -> p.green
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(p.track)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp, color = p.muted, modifier = Modifier.weight(1f))
            if (charging) {
                Text("⚡", fontSize = 11.sp, color = p.accent)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = percent?.let { "$it%" } ?: "--",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = p.ink
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (p.dark) Color(0xFF323744) else Color(0xFFDDE2EB))
        ) {
            val frac = ((percent ?: 0).coerceIn(0, 100)) / 100f
            if (percent != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .height(5.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(barColor)
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Tab 1: 音效

@Composable
private fun SoundScreen(state: UiState, vm: AppViewModel, p: ColorOsPalette) {
    var showSliders by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(title = "音效调音", subtitle = "均衡器预设与声学模式", p = p)

        FluidCard(p = p, title = "均衡器预设", subtitle = state.eqDetail) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.eqPresets) { preset ->
                    PillChip(
                        text = preset.label,
                        selected = state.eqPresetId == preset.id,
                        enabled = state.controlReady,
                        p = p,
                        onClick = { vm.setEqPreset(preset) }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            EqCurve(state.eqGains, p)
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.track)
                    .clickable { showSliders = !showSliders }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (showSliders) "收起 10 段自定义均衡器" else "自定义 10 段均衡器",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = p.ink,
                    modifier = Modifier.weight(1f)
                )
                Text(if (showSliders) "▲" else "▼", fontSize = 11.sp, color = p.muted)
            }

            AnimatedVisibility(visible = showSliders) {
                Column(Modifier.padding(top = 10.dp)) {
                    vm.bandHz.forEachIndexed { index, hz ->
                        val rawGain = state.eqGains.getOrNull(index) ?: 0f
                        val gain = if (rawGain.isFinite()) rawGain.coerceIn(-6f, 6f) else 0f
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (hz >= 1000) "${hz / 1000}k" else "$hz",
                                fontSize = 11.sp,
                                color = p.muted,
                                modifier = Modifier.width(36.dp)
                            )
                            Slider(
                                value = gain,
                                onValueChange = { vm.setCustomBand(index, it) },
                                valueRange = -6f..6f,
                                steps = 23,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = p.accent,
                                    activeTrackColor = p.accent,
                                    inactiveTrackColor = p.track
                                )
                            )
                            Text(
                                String.format(Locale.US, "%+.1fdB", gain),
                                fontSize = 11.sp,
                                color = p.ink,
                                modifier = Modifier.width(50.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.applyCustomEq() },
                        enabled = state.controlReady,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("应用自定义曲线", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        FluidCard(p = p, title = "智能音视频模式", subtitle = "在低延迟观影与高音质听歌之间切换") {
            SegmentedControl(
                options = SmartAudioMode.values().map { it to it.label },
                selected = state.smartAudioMode,
                enabled = state.controlReady,
                p = p,
                onSelect = { vm.setSmartAudioMode(it) }
            )
        }

        FluidCard(p = p, title = "通话自听（VoiceAware）", subtitle = "通话时麦克风回传环境音，避免戴耳机闷感") {
            SegmentedControl(
                options = listOf(0 to "关闭", 1 to "低", 2 to "中", 3 to "高"),
                selected = state.voiceAwareLevel,
                enabled = state.controlReady,
                p = p,
                onSelect = { vm.setVoiceAwareLevel(it) }
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun EqCurve(gains: List<Float>, p: ColorOsPalette) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(p.track)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
        drawLine(p.muted.copy(alpha = 0.25f), Offset(0f, mid), Offset(w, mid), strokeWidth = 1.dp.toPx(), pathEffect = dash)

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
            brush = Brush.verticalGradient(listOf(p.accent.copy(alpha = 0.26f), Color.Transparent))
        )
        drawPath(curve, p.accent, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
        points.forEach { pt ->
            drawCircle(p.accent, radius = 3.2.dp.toPx(), center = pt)
            drawCircle(p.card, radius = 1.4.dp.toPx(), center = pt)
        }
    }
}

// ---------------------------------------------------------------- Tab 2: 操控 (Verified HW Controls)

@Composable
private fun ControlsScreen(state: UiState, vm: AppViewModel, p: ColorOsPalette) {
    if (state.findBudsConfirmPending) {
        AlertDialog(
            containerColor = p.card,
            titleContentColor = p.ink,
            textContentColor = p.muted,
            onDismissRequest = { vm.dismissFindMyBudsConfirm() },
            title = { Text("请确认已摘下耳机", fontWeight = FontWeight.Bold) },
            text = { Text("查找耳机将发出高频蜂鸣提示，为保护听力请勿佩戴时开启（15 秒后自动停止）。") },
            confirmButton = {
                Button(
                    onClick = { vm.confirmFindMyBuds() },
                    colors = ButtonDefaults.buttonColors(containerColor = p.red, contentColor = Color.White)
                ) { Text("确认响铃") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissFindMyBudsConfirm() }) { Text("取消", color = p.muted) }
            }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(title = "触控与习惯", subtitle = "左右耳触控手势与日常省电", p = p)

        FluidCard(p = p, title = "触控手势") {
            GestureSideSection(
                label = "左耳（单击 / 双击 / 三击）",
                current = state.leftGestureGroup,
                holdVoice = state.leftHoldVoiceAssistant,
                enabled = state.controlReady,
                p = p,
                onGroup = { vm.setGestureGroup(true, it, state.leftHoldVoiceAssistant) },
                onHold = { vm.setGestureGroup(true, state.leftGestureGroup, it) }
            )
            HorizontalDivider(color = p.divider, modifier = Modifier.padding(vertical = 12.dp))
            GestureSideSection(
                label = "右耳（单击 / 双击 / 三击）",
                current = state.rightGestureGroup,
                holdVoice = state.rightHoldVoiceAssistant,
                enabled = state.controlReady,
                p = p,
                onGroup = { vm.setGestureGroup(false, it, state.rightHoldVoiceAssistant) },
                onHold = { vm.setGestureGroup(false, state.rightGestureGroup, it) }
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { vm.resetGestures() }, enabled = state.controlReady) {
                    Text("恢复默认手势", fontSize = 12.sp, color = p.accent)
                }
            }
        }

        FluidCard(p = p, title = "使用习惯") {
            SwitchRow(
                title = "自动播放 / 暂停",
                subtitle = "摘下或放回时控制音频播放",
                checked = state.autoPlayPause,
                enabled = state.controlReady,
                p = p,
                onCheckedChange = { vm.setAutoPlayPause(it) }
            )
            HorizontalDivider(color = p.divider, modifier = Modifier.padding(vertical = 6.dp))
            SwitchRow(
                title = "待机时保持降噪",
                subtitle = "无音乐播放时持续开启降噪功能",
                checked = state.standbyKeepAnc,
                enabled = state.controlReady,
                p = p,
                onCheckedChange = { vm.setStandbyKeepAnc(it) }
            )
            HorizontalDivider(color = p.divider, modifier = Modifier.padding(vertical = 6.dp))
            SwitchRow(
                title = "自动关机",
                subtitle = if (state.autoOffEnabled) "${state.autoOffMinutes} 分钟无音频后关机" else "已关闭自动关机",
                checked = state.autoOffEnabled,
                enabled = state.controlReady,
                p = p,
                onCheckedChange = { vm.setAutoPowerOff(it, state.autoOffMinutes) }
            )
            if (state.autoOffEnabled) {
                Spacer(Modifier.height(6.dp))
                SegmentedControl(
                    options = listOf(15 to "15 分钟", 30 to "30 分钟", 60 to "60 分钟"),
                    selected = state.autoOffMinutes,
                    enabled = state.controlReady,
                    p = p,
                    onSelect = { vm.setAutoPowerOff(true, it) }
                )
            }
            HorizontalDivider(color = p.divider, modifier = Modifier.padding(vertical = 10.dp))
            Text("默认语音助手", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = p.ink)
            Spacer(Modifier.height(8.dp))
            SegmentedControl(
                options = listOf(0x00 to "系统默认", 0x01 to "Google", 0xDF to "关闭"),
                selected = state.multiAiMode,
                enabled = state.controlReady,
                p = p,
                onSelect = { vm.setMultiAiMode(it) }
            )
        }

        FluidCard(p = p, title = "查找耳机", subtitle = "发出高频提示音定位遗落的耳机") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PillChip("响左耳", state.findMyBuds.leftBeeping && !state.findMyBuds.rightBeeping, state.controlReady, p, Modifier.weight(1f)) {
                    vm.requestFindMyBuds(left = true, right = false)
                }
                PillChip("响右耳", !state.findMyBuds.leftBeeping && state.findMyBuds.rightBeeping, state.controlReady, p, Modifier.weight(1f)) {
                    vm.requestFindMyBuds(left = false, right = true)
                }
                PillChip("响双耳", state.findMyBuds.leftBeeping && state.findMyBuds.rightBeeping, state.controlReady, p, Modifier.weight(1f)) {
                    vm.requestFindMyBuds(left = true, right = true)
                }
            }
            if (state.findMyBuds.leftBeeping || state.findMyBuds.rightBeeping) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { vm.stopFindMyBuds() },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = p.red, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("停止响铃", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun GestureSideSection(
    label: String,
    current: GestureFunctionGroup,
    holdVoice: Boolean,
    enabled: Boolean,
    p: ColorOsPalette,
    onGroup: (GestureFunctionGroup) -> Unit,
    onHold: (Boolean) -> Unit
) {
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = p.ink)
    Spacer(Modifier.height(8.dp))
    SegmentedControl(
        options = GestureFunctionGroup.values().map { it to it.label },
        selected = current,
        enabled = enabled,
        p = p,
        onSelect = onGroup
    )
    Spacer(Modifier.height(6.dp))
    SwitchRow(
        title = "长按唤醒语音助手",
        checked = holdVoice,
        enabled = enabled,
        p = p,
        onCheckedChange = onHold
    )
}

// ---------------------------------------------------------------- Tab 3: 设置 & 高级诊断

@Composable
private fun SettingsScreen(state: UiState, vm: AppViewModel, p: ColorOsPalette) {
    val ctx = LocalContext.current
    val cfg by vm.takeoverConfig.collectAsState()
    val melodyAlive by vm.melodyHookAlive.collectAsState()
    val melodyStatus by vm.melodyHookStatus.collectAsState()
    val rootBusy by vm.rootBusy.collectAsState()
    val fluidReady by vm.fluidCloudReady.collectAsState()
    val overlayOk by vm.overlayGranted.collectAsState()
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
        ScreenHeader(title = "设置", subtitle = "OPPO 官方耳机伪装（Root）与诊断", p = p)

        FluidCard(
            p = p,
            title = "LSPosed 官方耳机伪装（Root）",
            subtitle = "在 LSPosed 勾选作用域：com.oplus.melody（欢律）、com.oplus.wirelesssettings、com.heytap.accessory 后点下方「Root 重启欢律」即可激活"
        ) {
            Text(
                text = "欢律 Hook 状态：$melodyStatus",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (melodyAlive) p.green else p.amber
            )
            Spacer(Modifier.height(6.dp))
            SwitchRow(
                title = "启用欢律 OPPO 官方耳机伪装",
                subtitle = "注入 Enco 白名单、短接 SPP 并双向接管欢律 ANC/EQ/查找按键",
                checked = cfg.melodySpoofEnabled,
                p = p,
                onCheckedChange = { c -> vm.updateTakeoverConfig { it.copy(melodySpoofEnabled = c) } }
            )
            SwitchRow(
                title = "蓝牙连接自动弹出欢律 3D 开盖动画",
                checked = cfg.melodyAutoPopup,
                enabled = cfg.melodySpoofEnabled,
                p = p,
                onCheckedChange = { c -> vm.updateTakeoverConfig { it.copy(melodyAutoPopup = c) } }
            )
            SwitchRow(
                title = "蓝牙连接自动触发欢律原生流体云",
                checked = cfg.melodyAutoCapsule,
                enabled = cfg.melodySpoofEnabled,
                p = p,
                onCheckedChange = { c -> vm.updateTakeoverConfig { it.copy(melodyAutoCapsule = c) } }
            )
            Spacer(Modifier.height(6.dp))
            Text("伪装目标 OPPO 型号：", fontSize = 12.sp, color = p.muted)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(BridgeContract.SPOOF_PRESETS) { preset ->
                    PillChip(
                        text = preset.melodyName,
                        selected = cfg.spoofModelName == preset.melodyName,
                        enabled = cfg.melodySpoofEnabled,
                        p = p,
                        onClick = { vm.updateTakeoverConfig { it.copy(spoofModelName = preset.melodyName) } }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.triggerMelodyPopup() },
                    enabled = cfg.melodySpoofEnabled && !rootBusy,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White),
                    modifier = Modifier.weight(1f)
                ) { Text(if (rootBusy) "执行中…" else "测试 3D 弹窗", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                PillChip(
                    text = "测试原生胶囊",
                    selected = false,
                    enabled = cfg.melodySpoofEnabled && !rootBusy,
                    p = p,
                    onClick = { vm.triggerMelodyCapsule() }
                )
                PillChip(
                    text = if (rootBusy) "重启中…" else "Root 重启欢律",
                    selected = false,
                    enabled = cfg.melodySpoofEnabled && !rootBusy,
                    p = p,
                    onClick = { vm.restartAndWakeMelody() }
                )
            }
        }

        FluidCard(
            p = p,
            title = "Android 16 本机流体云与兜底控制",
            subtitle = "将耳机电量与降噪切换直接呈现在系统流体云胶囊，断连自动消失"
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (fluidReady) "实时活动权限：已就绪" else "实时活动权限：未授权",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (fluidReady) p.green else p.amber
                    )
                    val promotable = FluidCloudNotifier.lastHasPromotableCharacteristics
                    if (promotable != null) {
                        Text(
                            text = if (promotable) "系统胶囊校验通过" else "请检查通知渠道重要性设置",
                            fontSize = 11.sp,
                            color = p.muted
                        )
                    }
                }
                PillChip(
                    text = if (fluidReady) "管理" else "去开启",
                    selected = !fluidReady,
                    enabled = true,
                    p = p,
                    onClick = { vm.openFluidCloudSettings() }
                )
            }
            HorizontalDivider(color = p.divider, modifier = Modifier.padding(vertical = 8.dp))
            SwitchRow(
                title = "启用后台蓝牙接管",
                subtitle = "耳机连上蓝牙后在后台拉取左右耳及盒电量",
                checked = cfg.enabled,
                p = p,
                onCheckedChange = { c -> vm.updateTakeoverConfig { it.copy(enabled = c) } }
            )
            SwitchRow(
                title = "连上时自动上流体云",
                checked = cfg.useFluidCloud,
                enabled = cfg.enabled,
                p = p,
                onCheckedChange = { c -> vm.updateTakeoverConfig { it.copy(useFluidCloud = c) } }
            )
            SwitchRow(
                title = "切后台保持蓝牙控制连接",
                subtitle = "不频繁断开重连（会略微增加耳机待机功耗）",
                checked = cfg.keepLinkAlive,
                enabled = cfg.enabled,
                p = p,
                onCheckedChange = { c -> vm.updateTakeoverConfig { it.copy(keepLinkAlive = c) } }
            )
            SwitchRow(
                title = "兼容模式：同时弹底部悬浮卡片",
                subtitle = if (overlayOk) "已授予悬浮窗权限" else "默认推荐关闭，仅用流体云",
                checked = cfg.autoPopupOnConnect,
                enabled = cfg.enabled,
                p = p,
                onCheckedChange = { c -> vm.updateTakeoverConfig { it.copy(autoPopupOnConnect = c) } }
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.testFluidCloud() },
                    enabled = cfg.enabled,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White),
                    modifier = Modifier.weight(1f)
                ) { Text("测试流体云", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                PillChip(
                    text = "预览悬浮卡片",
                    selected = false,
                    enabled = cfg.enabled,
                    p = p,
                    onClick = { vm.triggerTakeoverSync(triggerPopup = true, forceOverlay = true) }
                )
            }
        }

        FluidCard(
            p = p,
            title = "桌面小组件",
            subtitle = "长按桌面空白处添加「JBL T230 电量与降噪」，支持一键刷新电量与循环切换降噪。"
        ) {}

        FluidCard(
            p = p,
            title = "高级诊断与日志导出",
            subtitle = "一键测试协议指令并导出 .txt 报告（无联网权限，所有身份已脱敏）"
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.track)
                    .clickable { showDevPanel = !showDevPanel }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (showDevPanel) "收起诊断面板" else "展开诊断、协议详情与日志终端",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = p.ink,
                    modifier = Modifier.weight(1f)
                )
                Text(if (showDevPanel) "▲" else "▼", fontSize = 11.sp, color = p.muted)
            }

            AnimatedVisibility(visible = showDevPanel) {
                Column(Modifier.padding(top = 12.dp)) {
                    val info = state.deviceInfo
                    Text(
                        "版本 v${BuildConfig.VERSION_NAME} · 固件 ${info?.firmwareVersion?.ifBlank { "--" } ?: "--"} · S/N ${state.serialNumber ?: "--"} · 盒 ${state.caseVersion ?: "--"}",
                        fontSize = 11.sp,
                        color = p.muted
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.runSelfTest() },
                        enabled = !running,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (running) "正在执行自检…" else "一键全量协议测试", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        PillChip("分享 .txt", false, true, p, Modifier.weight(1f)) { vm.shareDiagnostics() }
                        PillChip("保存文件", false, true, p, Modifier.weight(1f)) {
                            saveLauncher.launch(DiagnosticExporter.buildFileName())
                        }
                        PillChip("复制报告", false, true, p, Modifier.weight(1f)) { vm.copyDiagnostics() }
                    }
                    if (progress.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(progress, fontSize = 11.sp, color = p.green)
                    }
                    if (steps.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        steps.forEach { s ->
                            val stColor = when (s.status) {
                                DiagStatus.PASS -> p.green
                                DiagStatus.WARN -> p.amber
                                DiagStatus.FAIL -> p.red
                                DiagStatus.SKIP -> p.muted
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(p.track)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("[${s.status.tag}]", color = stColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(6.dp))
                                    Text(s.label, color = p.ink, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    Text("${s.durationMs}ms", color = p.muted, fontSize = 10.sp)
                                }
                                Text(s.detail, color = p.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("蓝牙十六进制日志 (${logs.size})", fontSize = 11.sp, color = p.muted, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.clearLog() }) { Text("清空", fontSize = 11.sp, color = p.accent) }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(p.logTerminalBg)
                            .padding(10.dp)
                    ) {
                        LazyColumn {
                            if (logs.isEmpty()) {
                                item { Text("（暂无记录）", color = p.muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                            } else {
                                items(logs.takeLast(120)) { e ->
                                    Text(
                                        "${e.time} ${e.direction} ${e.frameHex} ${e.note}",
                                        color = if (e.direction == "←") Color(0xFF34D399) else Color(0xFFFBBF24),
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

        Spacer(Modifier.height(16.dp))
    }
}
