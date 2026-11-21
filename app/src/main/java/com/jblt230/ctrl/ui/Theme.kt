package com.jblt230.ctrl.ui

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlin.math.cos
import kotlin.math.sin

val RoseDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB2C8),
    onPrimary = Color(0xFF54162D),
    primaryContainer = Color(0xFFF3B4C7),
    onPrimaryContainer = Color(0xFF2F0817),
    secondary = Color(0xFFE6BDC8),
    onSecondary = Color(0xFF442932),
    secondaryContainer = Color(0xFF542838),
    onSecondaryContainer = Color(0xFFFFD9E3),
    tertiary = Color(0xFFF2BC9B),
    onTertiary = Color(0xFF4B2811),
    tertiaryContainer = Color(0xFF5D3443),
    onTertiaryContainer = Color(0xFFFFDCE5),
    background = Color(0xFF0C0A0D),
    onBackground = Color(0xFFF6EDF0),
    surface = Color(0xFF141115),
    onSurface = Color(0xFFF6EDF0),
    surfaceVariant = Color(0xFF332A2F),
    onSurfaceVariant = Color(0xFFB7A8AE),
    surfaceContainerLowest = Color(0xFF09070A),
    surfaceContainerLow = Color(0xFF171318),
    surfaceContainer = Color(0xFF1F1A20),
    surfaceContainerHigh = Color(0xFF29222A),
    surfaceContainerHighest = Color(0xFF352C36),
    outline = Color(0xFF6B5B62),
    outlineVariant = Color(0xFF342B30),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF7D2429),
    onErrorContainer = Color(0xFFFFDAD6)
)

val RoseLightColorScheme = lightColorScheme(
    primary = Color(0xFFA63459),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD8E4),
    onPrimaryContainer = Color(0xFF3B071D),
    secondary = Color(0xFF75565F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCE4),
    onSecondaryContainer = Color(0xFF2B151C),
    tertiary = Color(0xFF815334),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBC7),
    onTertiaryContainer = Color(0xFF311404),
    background = Color(0xFFFFF8F9),
    onBackground = Color(0xFF22191C),
    surface = Color(0xFFFFF8F9),
    onSurface = Color(0xFF22191C),
    surfaceVariant = Color(0xFFF2DEE3),
    onSurfaceVariant = Color(0xFF6B585E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCF1F4),
    surfaceContainer = Color(0xFFF7EBEF),
    surfaceContainerHigh = Color(0xFFF1E3E8),
    surfaceContainerHighest = Color(0xFFEBD9DF),
    outline = Color(0xFF8A747A),
    outlineVariant = Color(0xFFE0CFD4),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

/** Soft 4-lobed scalloped organic M3 Expressive shape. */
val SoftCloverShape: Shape = polarExpressiveShape(lobes = 4, amplitude = 0.085f, phaseRad = (Math.PI / 4).toFloat())

/** Subtle 6-scallop pill-flower M3 Expressive shape. */
val OrganicBlobShape: Shape = polarExpressiveShape(lobes = 6, amplitude = 0.065f, phaseRad = 0f)

/** High-continuity squircle shape for feature icon badges. */
val SquircleShape: Shape = RoundedCornerShape(14.dp)

private fun polarExpressiveShape(lobes: Int, amplitude: Float, phaseRad: Float): Shape =
    GenericShape { size: Size, _ ->
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseR = minOf(cx, cy) * (1f - amplitude)
        val steps = 72
        val path = Path()
        for (i in 0..steps) {
            val theta = (i.toFloat() / steps) * (2f * Math.PI.toFloat())
            val r = baseR * (1f + amplitude * cos(lobes * theta + phaseRad))
            val x = cx + r * cos(theta)
            val y = cy + r * sin(theta)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        addPath(path)
    }

@Composable
fun JblExpressiveTheme(
    useDynamicColor: Boolean,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> RoseDarkColorScheme
        else -> RoseLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

@Composable
fun ExpressiveBadge(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    modifier: Modifier = Modifier
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = CircleShape,
        modifier = modifier
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun TonalIconBadge(
    iconRes: Int,
    contentDescription: String? = null,
    shape: Shape = SoftCloverShape,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    badgeSize: Dp = 42.dp,
    iconSize: Dp = 20.dp
) {
    Box(
        modifier = Modifier
            .size(badgeSize)
            .clip(shape)
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun GroupedListCard(
    title: String? = null,
    subtitle: String? = null,
    badgeText: String? = null,
    badgeContainer: Color = MaterialTheme.colorScheme.secondaryContainer,
    badgeContent: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        color = containerColor,
        shape = RoundedCornerShape(26.dp),
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(18.dp)) {
            if (!title.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    if (!badgeText.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        ExpressiveBadge(
                            text = badgeText,
                            containerColor = badgeContainer,
                            contentColor = badgeContent
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            content()
        }
    }
}

data class ExpressiveNavTab(val label: String, val iconRes: Int)

@Composable
fun FloatingPillNavBar(
    selectedTab: Int,
    tabs: List<ExpressiveNavTab>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = cs.surfaceContainerHigh,
            shape = CircleShape,
            shadowElevation = 10.dp,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == selectedTab
                    val pillColor by animateColorAsState(
                        targetValue = if (selected) cs.primaryContainer else Color.Transparent,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "navPill"
                    )
                    val contentColor by animateColorAsState(
                        targetValue = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "navInk"
                    )
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .weight(if (selected) 1.35f else 1f)
                            .clip(CircleShape)
                            .background(pillColor)
                            .clickable(
                                interactionSource = interaction,
                                indication = null
                            ) { onSelect(index) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(tab.iconRes),
                            contentDescription = tab.label,
                            tint = contentColor,
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = tab.label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = contentColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
