package com.jblt230.ctrl.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Minimal geometric vector icons drawn with Compose Canvas (zero external icon pack bloat). */
@Composable
fun NavTabIcon(index: Int, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.8.dp.toPx()
        when (index) {
            0 -> {
                // Earbuds duo stems
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.18f, h * 0.18f),
                    size = Size(w * 0.24f, h * 0.34f),
                    cornerRadius = CornerRadius(w * 0.12f),
                    style = Stroke(sw)
                )
                drawLine(tint, Offset(w * 0.30f, h * 0.52f), Offset(w * 0.30f, h * 0.84f), sw, StrokeCap.Round)
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.58f, h * 0.18f),
                    size = Size(w * 0.24f, h * 0.34f),
                    cornerRadius = CornerRadius(w * 0.12f),
                    style = Stroke(sw)
                )
                drawLine(tint, Offset(w * 0.70f, h * 0.52f), Offset(w * 0.70f, h * 0.84f), sw, StrokeCap.Round)
            }
            1 -> {
                // Equalizer vertical sliders
                val xs = listOf(0.22f, 0.50f, 0.78f)
                val dots = listOf(0.35f, 0.65f, 0.42f)
                xs.forEachIndexed { idx, x ->
                    drawLine(tint, Offset(w * x, h * 0.16f), Offset(w * x, h * 0.84f), sw, StrokeCap.Round)
                    drawCircle(tint, radius = 2.4.dp.toPx(), center = Offset(w * x, h * dots[idx]))
                }
            }
            2 -> {
                // Touch ripple circles
                drawCircle(tint, radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.48f))
                drawCircle(tint, radius = w * 0.29f, center = Offset(w * 0.5f, h * 0.48f), style = Stroke(sw * 0.85f))
                drawCircle(tint.copy(alpha = 0.55f), radius = w * 0.43f, center = Offset(w * 0.5f, h * 0.48f), style = Stroke(sw * 0.7f))
            }
            3 -> {
                // Extras 2x2 rounded grid
                val pad = 0.16f
                val box = 0.28f
                val step = 0.40f
                for (r in 0..1) {
                    for (c in 0..1) {
                        drawRoundRect(
                            color = tint,
                            topLeft = Offset(w * (pad + c * step), h * (pad + r * step)),
                            size = Size(w * box, h * box),
                            cornerRadius = CornerRadius(w * 0.08f),
                            style = Stroke(sw)
                        )
                    }
                }
            }
            else -> {
                // Settings ring / dial
                drawCircle(tint, radius = w * 0.33f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(sw))
                drawCircle(tint, radius = w * 0.12f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(sw))
            }
        }
    }
}

/** Stylized JBL Tune 230NC TWS silhouette artwork rendered natively in Canvas. */
@Composable
fun EarbudsShowcaseArtwork(
    connected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(width = 112.dp, height = 64.dp)) {
        val w = size.width
        val h = size.height
        val glow = if (connected) accent else Color(0xFF566375)

        // Charging Case Silhouette (center)
        val caseLeft = w * 0.30f
        val caseTop = h * 0.16f
        val caseW = w * 0.40f
        val caseH = h * 0.72f
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFF283242), Color(0xFF171E28))),
            topLeft = Offset(caseLeft, caseTop),
            size = Size(caseW, caseH),
            cornerRadius = CornerRadius(14.dp.toPx())
        )
        drawRoundRect(
            color = glow.copy(alpha = if (connected) 0.55f else 0.25f),
            topLeft = Offset(caseLeft, caseTop),
            size = Size(caseW, caseH),
            cornerRadius = CornerRadius(14.dp.toPx()),
            style = Stroke(1.2.dp.toPx())
        )
        // Case lid seam & LED dot
        drawLine(
            color = Color(0xFF3A475A),
            start = Offset(caseLeft + 4.dp.toPx(), h * 0.40f),
            end = Offset(caseLeft + caseW - 4.dp.toPx(), h * 0.40f),
            strokeWidth = 1.dp.toPx()
        )
        drawCircle(
            color = if (connected) Color(0xFF2CE09B) else Color(0xFF6A7789),
            radius = 2.6.dp.toPx(),
            center = Offset(w * 0.50f, h * 0.56f)
        )

        // Left & Right Tune 230NC stem earbuds flanking the case
        listOf(0.14f, 0.86f).forEach { cxFrac ->
            val cx = w * cxFrac
            val bodyColor = Color(0xFF232C3B)
            // Stem
            drawRoundRect(
                color = bodyColor,
                topLeft = Offset(cx - 4.dp.toPx(), h * 0.34f),
                size = Size(8.dp.toPx(), h * 0.48f),
                cornerRadius = CornerRadius(4.dp.toPx())
            )
            drawRoundRect(
                color = glow.copy(alpha = 0.65f),
                topLeft = Offset(cx - 4.dp.toPx(), h * 0.34f),
                size = Size(8.dp.toPx(), h * 0.48f),
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = Stroke(1.dp.toPx())
            )
            // Head capsule
            drawCircle(
                color = bodyColor,
                radius = 9.dp.toPx(),
                center = Offset(cx, h * 0.28f)
            )
            drawCircle(
                color = glow,
                radius = 3.dp.toPx(),
                center = Offset(cx, h * 0.28f)
            )
        }
    }
}
