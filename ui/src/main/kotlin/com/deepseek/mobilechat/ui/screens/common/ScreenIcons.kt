package com.deepseek.mobilechat.ui.screens.common

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Glyphs the secondary screens need and `ui/components/Icons.kt` does not
 * carry. Same construction rules as that file (a 24x24 box, 2px round stroke),
 * kept here so the screens package never has to touch the shared icon set while
 * another operator is editing it.
 */
private val StrokeWidth = 2f

private fun stroke(width: Float = StrokeWidth): Stroke =
    Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable
private fun Glyph(
    modifier: Modifier,
    tint: Color,
    contentDescription: String?,
    draw: DrawScope.() -> Unit,
) {
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Canvas(modifier = modifier.then(semanticsModifier)) {
        val unit = size.minDimension / 24f
        scale(unit, unit, pivot = Offset.Zero) { draw() }
    }
}

@Composable
fun SearchGlyph(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "搜索",
) {
    Glyph(modifier, tint, contentDescription) {
        drawCircle(color = tint, radius = 6.4f, center = Offset(10.5f, 10.5f), style = stroke())
        drawLine(
            color = tint,
            start = Offset(15.4f, 15.4f),
            end = Offset(20f, 20f),
            strokeWidth = StrokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun PlusGlyph(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "新增",
) {
    Glyph(modifier, tint, contentDescription) {
        drawLine(tint, Offset(12f, 5f), Offset(12f, 19f), StrokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(5f, 12f), Offset(19f, 12f), StrokeWidth, StrokeCap.Round)
    }
}

/** Two arrows crossing: export up, import down. */
@Composable
fun TransferGlyph(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "导入导出",
) {
    Glyph(modifier, tint, contentDescription) {
        drawLine(tint, Offset(8f, 20f), Offset(8f, 5f), StrokeWidth, StrokeCap.Round)
        val up = Path().apply {
            moveTo(4.4f, 8.6f)
            lineTo(8f, 5f)
            lineTo(11.6f, 8.6f)
        }
        drawPath(up, color = tint, style = stroke())
        drawLine(tint, Offset(16f, 4f), Offset(16f, 19f), StrokeWidth, StrokeCap.Round)
        val down = Path().apply {
            moveTo(12.4f, 15.4f)
            lineTo(16f, 19f)
            lineTo(19.6f, 15.4f)
        }
        drawPath(down, color = tint, style = stroke())
    }
}

/** Slider-ish gear: a circle plus eight short spokes. */
@Composable
fun SettingsGlyph(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "设置",
) {
    Glyph(modifier, tint, contentDescription) {
        drawCircle(color = tint, radius = 5.2f, center = Offset(12f, 12f), style = stroke())
        val spokes = 8
        repeat(spokes) { index ->
            val angle = (Math.PI * 2.0 * index / spokes).toFloat()
            val inner = 7.6f
            val outer = 10.2f
            val c = kotlin.math.cos(angle)
            val s = kotlin.math.sin(angle)
            drawLine(
                color = tint,
                start = Offset(12f + c * inner, 12f + s * inner),
                end = Offset(12f + c * outer, 12f + s * outer),
                strokeWidth = StrokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
fun FolderGlyph(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "文件夹",
) {
    Glyph(modifier, tint, contentDescription) {
        val path = Path().apply {
            moveTo(4f, 7f)
            lineTo(9.4f, 7f)
            lineTo(11.2f, 9.2f)
            lineTo(20f, 9.2f)
            lineTo(20f, 18.4f)
            lineTo(4f, 18.4f)
            close()
        }
        drawPath(path, color = tint, style = stroke())
    }
}

/** Tag: a rounded square with a punched hole. */
@Composable
fun TagGlyph(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "标签",
) {
    Glyph(modifier, tint, contentDescription) {
        val path = Path().apply {
            moveTo(12.4f, 4.4f)
            lineTo(19.6f, 4.4f)
            lineTo(19.6f, 11.6f)
            lineTo(11.8f, 19.4f)
            lineTo(4.6f, 12.2f)
            close()
        }
        drawPath(path, color = tint, style = stroke())
        drawCircle(color = tint, radius = 1.6f, center = Offset(15.6f, 8.4f))
    }
}

/** Stacked layers: the context debug panel. */
@Composable
fun LayersGlyph(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "上下文",
) {
    Glyph(modifier, tint, contentDescription) {
        val top = Path().apply {
            moveTo(12f, 4.4f)
            lineTo(20f, 8.6f)
            lineTo(12f, 12.8f)
            lineTo(4f, 8.6f)
            close()
        }
        drawPath(top, color = tint, style = stroke())
        drawLine(tint, Offset(4f, 12.8f), Offset(12f, 17f), StrokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(12f, 17f), Offset(20f, 12.8f), StrokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(4f, 17f), Offset(12f, 21.2f), StrokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(12f, 21.2f), Offset(20f, 17f), StrokeWidth, StrokeCap.Round)
    }
}

/** Clock used by "created at" lines. */
@Composable
fun ClockGlyph(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    Glyph(modifier, tint, contentDescription) {
        drawCircle(color = tint, radius = 8.2f, center = Offset(12f, 12f), style = stroke())
        drawLine(tint, Offset(12f, 7.4f), Offset(12f, 12.2f), StrokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(12f, 12.2f), Offset(15.4f, 14.2f), StrokeWidth, StrokeCap.Round)
    }
}

/** Tick inside a rounded square, drawn for the "test connection" result. */
@Composable
fun ShieldCheckGlyph(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    Glyph(modifier, tint, contentDescription) {
        val path = Path().apply {
            moveTo(12f, 4f)
            lineTo(19f, 6.6f)
            lineTo(19f, 12.2f)
            quadraticTo(19f, 17.4f, 12f, 20.4f)
            quadraticTo(5f, 17.4f, 5f, 12.2f)
            lineTo(5f, 6.6f)
            close()
        }
        drawPath(path, color = tint, style = stroke())
        val check = Path().apply {
            moveTo(8.6f, 12.2f)
            lineTo(11.2f, 14.8f)
            lineTo(15.6f, 9.8f)
        }
        drawPath(check, color = tint, style = stroke())
    }
}

/** Coin stack for the balance entry point. */
@Composable
fun BalanceGlyph(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "余额",
) {
    Glyph(modifier, tint, contentDescription) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(4.6f, 8f),
            size = Size(14.8f, 5f),
            cornerRadius = CornerRadius(2.5f, 2.5f),
            style = stroke(1.8f),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(4.6f, 13f),
            size = Size(14.8f, 5f),
            cornerRadius = CornerRadius(2.5f, 2.5f),
            style = stroke(1.8f),
        )
        drawLine(tint, Offset(17.4f, 4.6f), Offset(17.4f, 8f), 1.8f, StrokeCap.Round)
    }
}
