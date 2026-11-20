package com.deepseek.mobilechat.ui.screens.settings

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
import com.deepseek.mobilechat.ui.components.GlobeIcon
import com.deepseek.mobilechat.ui.screens.common.ShieldCheckGlyph
import com.deepseek.mobilechat.ui.screens.common.TransferGlyph

/**
 * The row glyphs of the settings list.
 *
 * They follow the same construction rules as `ui/components/Icons.kt` (a 24x24
 * box, 2px round stroke) and live next to the settings screen so the list can
 * own a coherent icon set without growing the shared one.
 */
internal enum class SettingsIcon {
    Model,
    Preset,
    Search,
    Generation,
    Transfer,
    Storage,
    Appearance,
    Version,
    License,
}

@Composable
internal fun SettingsIconGlyph(
    icon: SettingsIcon,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    when (icon) {
        SettingsIcon.Model -> ModelGlyph(modifier, tint)
        SettingsIcon.Preset -> PresetGlyph(modifier, tint)
        SettingsIcon.Search -> GlobeIcon(modifier, tint, contentDescription = null)
        SettingsIcon.Generation -> BoltGlyph(modifier, tint)
        SettingsIcon.Transfer -> TransferGlyph(modifier, tint, contentDescription = null)
        SettingsIcon.Storage -> StorageGlyph(modifier, tint)
        SettingsIcon.Appearance -> ContrastGlyph(modifier, tint)
        SettingsIcon.Version -> InfoGlyph(modifier, tint)
        SettingsIcon.License -> ShieldCheckGlyph(modifier, tint)
    }
}

private val StrokeWidth = 1.8f

private fun stroke(width: Float = StrokeWidth): Stroke =
    Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable
private fun Glyph(modifier: Modifier, tint: Color, draw: DrawScope.() -> Unit) {
    Canvas(modifier = modifier) {
        val unit = size.minDimension / 24f
        scale(unit, unit, pivot = Offset.Zero) { draw() }
    }
}

/** A chip: rounded body with pins on all four sides — "model service". */
@Composable
private fun ModelGlyph(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Glyph(modifier, tint) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(6f, 6f),
            size = Size(12f, 12f),
            cornerRadius = CornerRadius(2.6f, 2.6f),
            style = stroke(),
        )
        // Pins: two per side, short and symmetrical.
        for (x in listOf(9f, 15f)) {
            drawLine(tint, Offset(x, 2.6f), Offset(x, 6f), StrokeWidth, StrokeCap.Round)
            drawLine(tint, Offset(x, 18f), Offset(x, 21.4f), StrokeWidth, StrokeCap.Round)
        }
        for (y in listOf(9f, 15f)) {
            drawLine(tint, Offset(2.6f, y), Offset(6f, y), StrokeWidth, StrokeCap.Round)
            drawLine(tint, Offset(18f, y), Offset(21.4f, y), StrokeWidth, StrokeCap.Round)
        }
    }
}

/** Three sliders — a preset is a bundle of sampling knobs. */
@Composable
private fun PresetGlyph(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Glyph(modifier, tint) {
        val rows = listOf(7f to 8.5f, 12f to 15.5f, 17f to 10.5f)
        rows.forEach { (y, knobX) ->
            drawLine(tint, Offset(3.4f, y), Offset(20.6f, y), StrokeWidth, StrokeCap.Round)
            drawCircle(color = tint, radius = 2.2f, center = Offset(knobX, y))
        }
    }
}

/** Lightning bolt — "generation". */
@Composable
private fun BoltGlyph(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Glyph(modifier, tint) {
        val bolt = Path().apply {
            moveTo(13.6f, 3.4f)
            lineTo(6.4f, 13.4f)
            lineTo(11.4f, 13.4f)
            lineTo(10.4f, 20.6f)
            lineTo(17.6f, 10.6f)
            lineTo(12.6f, 10.6f)
            close()
        }
        drawPath(bolt, color = tint, style = stroke())
    }
}

/** Database cylinder — "storage". */
@Composable
private fun StorageGlyph(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Glyph(modifier, tint) {
        val width = 14f
        val height = 5.4f
        val left = 5f
        drawOval(
            color = tint,
            topLeft = Offset(left, 3.8f),
            size = Size(width, height),
            style = stroke(),
        )
        drawLine(tint, Offset(left, 6.5f), Offset(left, 17.5f), StrokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(left + width, 6.5f), Offset(left + width, 17.5f), StrokeWidth, StrokeCap.Round)
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(left, 14.8f),
            size = Size(width, height),
            style = stroke(),
        )
        // One ring so the cylinder reads as a stack rather than a cup.
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(left, 9.2f),
            size = Size(width, height),
            style = stroke(1.4f),
        )
    }
}

/** Half-filled circle — "appearance" (light/dark). */
@Composable
private fun ContrastGlyph(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Glyph(modifier, tint) {
        drawArc(
            color = tint.copy(alpha = 0.28f),
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(3.6f, 3.6f),
            size = Size(16.8f, 16.8f),
        )
        drawCircle(
            color = tint,
            radius = 8.4f,
            center = Offset(12f, 12f),
            style = stroke(),
        )
    }
}

/** Info circle — "version / about". */
@Composable
private fun InfoGlyph(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Glyph(modifier, tint) {
        drawCircle(color = tint, radius = 8.4f, center = Offset(12f, 12f), style = stroke())
        drawCircle(color = tint, radius = 1.25f, center = Offset(12f, 8.1f))
        drawLine(tint, Offset(12f, 11.2f), Offset(12f, 17f), StrokeWidth, StrokeCap.Round)
    }
}
