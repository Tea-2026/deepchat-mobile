package com.deepseek.mobilechat.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hand-drawn icon set. `material-icons-extended` is not on the module's
 * dependency list, and pulling it in for eight glyphs is not worth the
 * dependency; every icon here is a couple of paths in a 24x24 box.
 *
 * All glyphs share [IconBox] so stroke weight, optical size and tint stay
 * consistent across the app.
 */
private val StrokeWidth = 2f
internal val IconStroke = Stroke(width = StrokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable
internal fun IconBox(
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
        scale(unit, unit, pivot = Offset.Zero) {
            draw()
        }
    }
}

private fun DrawScope.line(
    color: Color,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    stroke: Stroke = IconStroke,
) {
    drawLine(color = color, start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = stroke.width, cap = stroke.cap)
}

@Composable
fun BackIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "返回",
) {
    IconBox(modifier, tint, contentDescription) {
        val path = Path().apply {
            moveTo(15f, 4f)
            lineTo(7f, 12f)
            lineTo(15f, 20f)
        }
        drawPath(path, color = tint, style = IconStroke)
    }
}

@Composable
fun MoreIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "更多",
) {
    IconBox(modifier, tint, contentDescription) {
        listOf(5.5f, 12f, 18.5f).forEach { y ->
            drawCircle(color = tint, radius = 1.6f, center = Offset(12f, y))
        }
    }
}

@Composable
fun SendIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "发送",
) {
    IconBox(modifier, tint, contentDescription) {
        line(tint, 12f, 19.5f, 12f, 5f)
        val head = Path().apply {
            moveTo(5.5f, 11.5f)
            lineTo(12f, 5f)
            lineTo(18.5f, 11.5f)
        }
        drawPath(head, color = tint, style = IconStroke)
    }
}

@Composable
fun StopIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "停止生成",
) {
    IconBox(modifier, tint, contentDescription) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(7f, 7f),
            size = Size(10f, 10f),
            cornerRadius = CornerRadius(2.4f, 2.4f),
        )
    }
}

@Composable
fun AttachIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "添加附件",
) {
    IconBox(modifier, tint, contentDescription) {
        line(tint, 12f, 5.5f, 12f, 18.5f)
        line(tint, 5.5f, 12f, 18.5f, 12f)
    }
}

@Composable
fun CopyIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "复制",
) {
    IconBox(modifier, tint, contentDescription) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(9f, 9f),
            size = Size(11f, 11f),
            cornerRadius = CornerRadius(2.6f, 2.6f),
            style = IconStroke,
        )
        val back = Path().apply {
            moveTo(15f, 5.5f)
            lineTo(6.5f, 5.5f)
            quadraticTo(5f, 5.5f, 5f, 7f)
            lineTo(5f, 15f)
        }
        drawPath(back, color = tint, style = IconStroke)
    }
}

@Composable
fun EditIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "编辑",
) {
    IconBox(modifier, tint, contentDescription) {
        val path = Path().apply {
            moveTo(4.5f, 19.5f)
            lineTo(5.5f, 15.5f)
            lineTo(16f, 5f)
            lineTo(19f, 8f)
            lineTo(8.5f, 18.5f)
            close()
        }
        drawPath(path, color = tint, style = IconStroke)
    }
}

@Composable
fun PinIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "置顶",
) {
    IconBox(modifier, tint, contentDescription) {
        val head = Path().apply {
            moveTo(8f, 4f)
            lineTo(16f, 4f)
            lineTo(14f, 10f)
            lineTo(16f, 14f)
            lineTo(8f, 14f)
            lineTo(10f, 10f)
            close()
        }
        drawPath(head, color = tint, style = IconStroke)
        line(tint, 12f, 14f, 12f, 20f)
    }
}

@Composable
fun TrashIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "删除",
) {
    IconBox(modifier, tint, contentDescription) {
        line(tint, 4.5f, 7f, 19.5f, 7f)
        val lid = Path().apply {
            moveTo(9.5f, 7f)
            lineTo(9.5f, 4.5f)
            lineTo(14.5f, 4.5f)
            lineTo(14.5f, 7f)
        }
        drawPath(lid, color = tint, style = IconStroke)
        val body = Path().apply {
            moveTo(6.5f, 7f)
            lineTo(7.5f, 20f)
            lineTo(16.5f, 20f)
            lineTo(17.5f, 7f)
        }
        drawPath(body, color = tint, style = IconStroke)
        line(tint, 10.5f, 10.5f, 10.8f, 16.5f, IconStroke)
        line(tint, 13.5f, 10.5f, 13.2f, 16.5f, IconStroke)
    }
}

@Composable
fun BranchIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "从此分支继续",
) {
    IconBox(modifier, tint, contentDescription) {
        val fork = Path().apply {
            moveTo(6f, 5f)
            lineTo(6f, 8.5f)
            lineTo(12f, 12f)
            lineTo(18f, 8.5f)
            lineTo(18f, 5f)
        }
        drawPath(fork, color = tint, style = IconStroke)
        line(tint, 12f, 12f, 12f, 20f)
    }
}

@Composable
fun ChevronLeftIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "上一个分支",
) {
    IconBox(modifier, tint, contentDescription) {
        val path = Path().apply {
            moveTo(14.5f, 5f)
            lineTo(8.5f, 12f)
            lineTo(14.5f, 19f)
        }
        drawPath(path, color = tint, style = IconStroke)
    }
}

@Composable
fun ChevronRightIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "下一个分支",
) {
    IconBox(modifier, tint, contentDescription) {
        val path = Path().apply {
            moveTo(9.5f, 5f)
            lineTo(15.5f, 12f)
            lineTo(9.5f, 19f)
        }
        drawPath(path, color = tint, style = IconStroke)
    }
}

@Composable
fun CheckIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    IconBox(modifier, tint, contentDescription) {
        val path = Path().apply {
            moveTo(5f, 12.5f)
            lineTo(10f, 17.5f)
            lineTo(19f, 6.5f)
        }
        drawPath(path, color = tint, style = IconStroke)
    }
}

@Composable
fun CloseIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "取消",
) {
    IconBox(modifier, tint, contentDescription) {
        line(tint, 6f, 6f, 18f, 18f)
        line(tint, 18f, 6f, 6f, 18f)
    }
}

@Composable
fun DownIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "回到底部",
) {
    IconBox(modifier, tint, contentDescription) {
        line(tint, 12f, 5f, 12f, 19f)
        val head = Path().apply {
            moveTo(5.5f, 12.5f)
            lineTo(12f, 19f)
            lineTo(18.5f, 12.5f)
        }
        drawPath(head, color = tint, style = IconStroke)
    }
}

@Composable
fun FileIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    IconBox(modifier, tint, contentDescription) {
        val sheet = Path().apply {
            moveTo(6.5f, 3.5f)
            lineTo(14f, 3.5f)
            lineTo(18f, 7.5f)
            lineTo(18f, 20.5f)
            lineTo(6.5f, 20.5f)
            close()
        }
        drawPath(sheet, color = tint, style = IconStroke)
        val fold = Path().apply {
            moveTo(14f, 3.5f)
            lineTo(14f, 7.5f)
            lineTo(18f, 7.5f)
        }
        drawPath(fold, color = tint, style = IconStroke)
    }
}

/** Globe: outer ring, equator and one meridian. Used by the "联网" toggle. */
@Composable
fun GlobeIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "联网搜索",
) {
    IconBox(modifier, tint, contentDescription) {
        drawCircle(
            color = tint,
            radius = 8.6f,
            center = Offset(12f, 12f),
            style = IconStroke,
        )
        line(tint, 3.4f, 12f, 20.6f, 12f)
        drawOval(
            color = tint,
            topLeft = Offset(7.2f, 3.4f),
            size = Size(9.6f, 17.2f),
            style = IconStroke,
        )
    }
}

/** Hamburger: opens the navigation drawer (replaces the old back arrow). */
@Composable
fun MenuIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "打开会话抽屉",
) {
    IconBox(modifier, tint, contentDescription) {
        line(tint, 4f, 6.5f, 20f, 6.5f)
        line(tint, 4f, 12f, 20f, 12f)
        line(tint, 4f, 17.5f, 20f, 17.5f)
    }
}

/** The user's avatar glyph (spec §8: circle + person). */
@Composable
fun PersonIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    IconBox(modifier, tint, contentDescription) {
        drawCircle(color = tint, radius = 3.5f, center = Offset(12f, 8f), style = IconStroke)
        val shoulders = Path().apply {
            moveTo(5f, 19.5f)
            quadraticTo(5.2f, 14f, 12f, 14f)
            quadraticTo(18.8f, 14f, 19f, 19.5f)
        }
        drawPath(shoulders, color = tint, style = IconStroke)
    }
}

/** Sliders: the advanced sampling-parameter panel. */
@Composable
fun SlidersIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "高级参数",
) {
    IconBox(modifier, tint, contentDescription) {
        line(tint, 4f, 7f, 20f, 7f)
        line(tint, 4f, 12f, 20f, 12f)
        line(tint, 4f, 17f, 20f, 17f)
        drawCircle(color = tint, radius = 2.1f, center = Offset(9f, 7f))
        drawCircle(color = tint, radius = 2.1f, center = Offset(15.5f, 12f))
        drawCircle(color = tint, radius = 2.1f, center = Offset(8f, 17f))
    }
}

/** A prompt sheet with a folded corner — the system-prompt editor. */
@Composable
fun PromptIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "系统提示词",
) {
    IconBox(modifier, tint, contentDescription) {
        val sheet = Path().apply {
            moveTo(5.5f, 3.8f)
            lineTo(13.5f, 3.8f)
            lineTo(18.5f, 8.8f)
            lineTo(18.5f, 20.2f)
            lineTo(5.5f, 20.2f)
            close()
        }
        drawPath(sheet, color = tint, style = IconStroke)
        line(tint, 8.5f, 11f, 15.5f, 11f)
        line(tint, 8.5f, 15f, 13.5f, 15f)
    }
}

/** Camera shutter, for the "拍照" action. */
@Composable
fun CameraIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "拍照",
) {
    IconBox(modifier, tint, contentDescription) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(3.4f, 7f),
            size = Size(17.2f, 13f),
            cornerRadius = CornerRadius(3.2f, 3.2f),
            style = IconStroke,
        )
        val bump = Path().apply {
            moveTo(8.2f, 7f)
            lineTo(9.6f, 4.4f)
            lineTo(14.4f, 4.4f)
            lineTo(15.8f, 7f)
        }
        drawPath(bump, color = tint, style = IconStroke)
        drawCircle(color = tint, radius = 3.4f, center = Offset(12f, 13.4f), style = IconStroke)
    }
}

/** Picture frame with a horizon and a sun — the "相册" action. */
@Composable
fun GalleryIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "相册",
) {
    IconBox(modifier, tint, contentDescription) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(3.6f, 4.6f),
            size = Size(16.8f, 14.8f),
            cornerRadius = CornerRadius(3.2f, 3.2f),
            style = IconStroke,
        )
        drawCircle(color = tint, radius = 1.7f, center = Offset(8.8f, 9.6f))
        val horizon = Path().apply {
            moveTo(5.2f, 17.2f)
            lineTo(10f, 12.6f)
            lineTo(13.4f, 15.8f)
            lineTo(16f, 13.4f)
            lineTo(18.8f, 16.2f)
        }
        drawPath(horizon, color = tint, style = IconStroke)
    }
}

/** Downward chevron: "▾" on the model chip, "›" when rotated 270°. */
@Composable
fun ChevronDownIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    IconBox(modifier, tint, contentDescription) {
        val path = Path().apply {
            moveTo(6f, 9.5f)
            lineTo(12f, 15.5f)
            lineTo(18f, 9.5f)
        }
        drawPath(path, color = tint, style = IconStroke)
    }
}

/** Box with an arrow leaving it — "导出". */
@Composable
fun ExportIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "导出",
) {
    IconBox(modifier, tint, contentDescription) {
        line(tint, 12f, 14.5f, 12f, 3.8f)
        val head = Path().apply {
            moveTo(7.6f, 8.2f)
            lineTo(12f, 3.8f)
            lineTo(16.4f, 8.2f)
        }
        drawPath(head, color = tint, style = IconStroke)
        val tray = Path().apply {
            moveTo(4.6f, 14f)
            lineTo(4.6f, 19.4f)
            lineTo(19.4f, 19.4f)
            lineTo(19.4f, 14f)
        }
        drawPath(tray, color = tint, style = IconStroke)
    }
}

/** Four-point spark — the thinking-level chip. */
@Composable
fun SparkIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "思考等级",
) {
    IconBox(modifier, tint, contentDescription) {
        val spark = Path().apply {
            moveTo(12f, 3.4f)
            quadraticTo(12.9f, 10.7f, 20.6f, 12f)
            quadraticTo(12.9f, 13.3f, 12f, 20.6f)
            quadraticTo(11.1f, 13.3f, 3.4f, 12f)
            quadraticTo(11.1f, 10.7f, 12f, 3.4f)
            close()
        }
        drawPath(spark, color = tint, style = IconStroke)
    }
}

/** Plain plus, used by "新建对话" (the attach button keeps its own glyph). */
@Composable
fun PlusIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "新建",
) {
    IconBox(modifier, tint, contentDescription) {
        line(tint, 12f, 5f, 12f, 19f)
        line(tint, 5f, 12f, 19f, 12f)
    }
}

/** Gear: eight spokes around a ring — the settings entry. */
@Composable
fun GearIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "设置",
) {
    IconBox(modifier, tint, contentDescription) {
        drawCircle(color = tint, radius = 5.4f, center = Offset(12f, 12f), style = IconStroke)
        drawCircle(color = tint, radius = 2f, center = Offset(12f, 12f))
        val spokes = 8
        repeat(spokes) { index ->
            val angle = (PI * 2.0 * index / spokes).toFloat()
            val inner = 7.8f
            val outer = 10.4f
            drawLine(
                color = tint,
                start = Offset(12f + cos(angle) * inner, 12f + sin(angle) * inner),
                end = Offset(12f + cos(angle) * outer, 12f + sin(angle) * outer),
                strokeWidth = StrokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Rounded square with a plus: "start a new conversation" (top bar). */
@Composable
fun NewChatIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "新对话",
) {
    IconBox(modifier, tint, contentDescription) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(4f, 4f),
            size = Size(16f, 16f),
            cornerRadius = CornerRadius(4.5f, 4.5f),
            style = IconStroke,
        )
        line(tint, 12f, 8.4f, 12f, 15.6f)
        line(tint, 8.4f, 12f, 15.6f, 12f)
    }
}

/** Plain speech bubble — the calm mark the empty state uses. */
@Composable
fun ChatBubbleIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    IconBox(modifier, tint, contentDescription) {
        val bubble = Path().apply {
            moveTo(9.4f, 4.4f)
            lineTo(18.6f, 4.4f)
            quadraticTo(20.4f, 4.4f, 20.4f, 6.2f)
            lineTo(20.4f, 15.4f)
            quadraticTo(20.4f, 17.2f, 18.6f, 17.2f)
            lineTo(12.6f, 17.2f)
            lineTo(8.6f, 20.2f)
            lineTo(8.6f, 17.2f)
            lineTo(6.2f, 17.2f)
            quadraticTo(4.4f, 17.2f, 4.4f, 15.4f)
            lineTo(4.4f, 6.2f)
            quadraticTo(4.4f, 4.4f, 6.2f, 4.4f)
            close()
        }
        drawPath(bubble, color = tint, style = IconStroke)
    }
}

/** Magnifier — drawer search. */
@Composable
fun SearchIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = "搜索",
) {
    IconBox(modifier, tint, contentDescription) {
        drawCircle(color = tint, radius = 6.4f, center = Offset(10.5f, 10.5f), style = IconStroke)
        line(tint, 15.4f, 15.4f, 20f, 20f)
    }
}

/** Explicit size so callers do not repeat `.size(20.dp)` on every icon. */
fun Modifier.iconSize(size: Dp = 20.dp): Modifier = this.size(size)
