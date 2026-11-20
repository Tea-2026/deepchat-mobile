package com.deepseek.mobilechat.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Re-generate glyph: an open circular arrow whose head is placed on the exact
 * arc end (computed from the sweep, not eyeballed), so the tip always meets the
 * stroke.
 */
@Composable
fun RefreshIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "重新生成",
) {
    IconBox(modifier, tint, contentDescription) {
        val cx = 12f
        val cy = 12f
        val radius = 7.6f
        val startAngle = 55f
        val sweep = 260f
        drawArc(
            color = tint,
            startAngle = startAngle,
            sweepAngle = sweep,
            useCenter = false,
            style = IconStroke,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2f, radius * 2f),
        )
        // Arrow head placed exactly on the arc end, pointing along the tangent.
        val endRadians = (startAngle + sweep) * PI / 180.0
        val cos = cos(endRadians).toFloat()
        val sin = sin(endRadians).toFloat()
        val endX = cx + radius * cos
        val endY = cy + radius * sin
        val tangentX = -sin
        val tangentY = cos
        val normalX = -tangentY
        val normalY = tangentX
        val barb = 3.1f
        val head = Path().apply {
            moveTo(
                endX - tangentX * barb + normalX * barb,
                endY - tangentY * barb + normalY * barb,
            )
            lineTo(endX + tangentX * 1.6f, endY + tangentY * 1.6f)
            lineTo(
                endX - tangentX * barb - normalX * barb,
                endY - tangentY * barb - normalY * barb,
            )
        }
        drawPath(head, color = tint, style = IconStroke)
    }
}
