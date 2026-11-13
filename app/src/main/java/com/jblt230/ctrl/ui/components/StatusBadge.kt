package com.jblt230.ctrl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jblt230.ctrl.data.FeatureStatus

private val Real = Color(0xFF0F7B4F)
private val ReadOnly = Color(0xFF1D5FA8)
private val Unverified = Color(0xFF9A6700)
private val Degraded = Color(0xFF8A4B18)

private fun colorOf(status: FeatureStatus) = when (status) {
    FeatureStatus.REAL -> Real
    FeatureStatus.READ_ONLY -> ReadOnly
    FeatureStatus.UNVERIFIED -> Unverified
    FeatureStatus.DEGRADED -> Degraded
}

/** The only place that renders "working" state. Always fed from read-back results. */
@Composable
fun StatusBadge(status: FeatureStatus, modifier: Modifier = Modifier) {
    val c = colorOf(status)
    Row(
        modifier = modifier
            .background(c.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "●",
            color = c,
            fontSize = 9.sp
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = status.label,
            color = c,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
