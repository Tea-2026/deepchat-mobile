package com.deepseek.mobilechat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.ui.chat.ActiveModel
import com.deepseek.mobilechat.ui.chat.thinkingDescription
import com.deepseek.mobilechat.ui.chat.thinkingSliderLabel
import com.deepseek.mobilechat.ui.chat.thinkingStopFraction
import com.deepseek.mobilechat.ui.chat.valueColorFor
import com.deepseek.mobilechat.ui.motion.pressable
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.ShapeTokens
import com.deepseek.mobilechat.ui.theme.Spacing

/**
 * One control for "which model + how hard it should think": the composer's
 * model chip, now also carrying the reasoning level, which opens a bubble above
 * itself.
 *
 * The bubble is a `Popup` anchored to the chip (not a bottom sheet) because the
 * product specifically wants the slider *in place*, right above the composer,
 * dismissed by tapping outside. `focusable = true` is what makes the platform
 * route the outside tap and the back gesture to `onDismissRequest`.
 *
 * Layout, top to bottom:
 * ```
 *  模型        deepseek-flash  ›   ← tapping opens the familiar model picker
 *  推理等级                 High   ← [ThinkingSlider] owns this row + the track
 *  ●━━━━━━━━━━━━━━━━━━━━━━━━━
 * ```
 * The model row keeps the entry point the chip replaced, so nothing about model
 * selection moved; only the reasoning control left the control bar.
 */
@Composable
fun ModelThinkingChip(
    model: ActiveModel,
    level: ThinkingLevel,
    onOpenModelPicker: () -> Unit,
    onLevelChange: (ThinkingLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    val colorScheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.roundToPx() }
    val marginPx = with(density) { 12.dp.roundToPx() }
    val positionProvider = remember(gapPx, marginPx) { BubbleAboveAnchor(gapPx, marginPx) }

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    // Never wider than the screen minus the bubble margins, never so narrow
    // that the four stops collapse.
    val bubbleWidth = (screenWidth - 32.dp).coerceAtMost(340.dp).coerceAtLeast(240.dp)

    val levelColor = valueColorFor(
        pct = thinkingStopFraction(level),
        level = level,
    )?.let { Color(it) } ?: chatColors.mutedText

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .pressable { expanded = !expanded }
                .semantics {
                    // Spoken form stays Chinese (the app's language); only the
                    // printed level name is English.
                    contentDescription =
                        "模型 ${model.displayName}，推理等级 " +
                            thinkingDescription(level)
                },
            shape = ShapeTokens.Pill,
            color = colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .padding(start = Spacing.xs, end = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                if (model.hasModel) {
                    BrandAvatar(
                        protocol = model.protocol,
                        name = model.displayName,
                        size = 18.dp,
                    )
                } else {
                    // No explicit model: a neutral mark beats a "默" monogram.
                    Box(
                        modifier = Modifier.width(18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        SparkIcon(
                            modifier = Modifier.iconSize(15.dp),
                            tint = chatColors.mutedText,
                            contentDescription = null,
                        )
                    }
                }
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = thinkingSliderLabel(level),
                    style = MaterialTheme.typography.labelMedium,
                    color = levelColor,
                    maxLines = 1,
                )
                ChevronDownIcon(
                    modifier = Modifier.iconSize(14.dp),
                    tint = chatColors.mutedText,
                    contentDescription = null,
                )
            }
        }

        if (expanded) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnClickOutside = true,
                    dismissOnBackPress = true,
                ),
            ) {
                Surface(
                    modifier = Modifier.width(bubbleWidth),
                    shape = RoundedCornerShape(20.dp),
                    color = colorScheme.surfaceBright,
                    border = BorderStroke(1.dp, chatColors.hairline),
                    shadowElevation = 16.dp,
                ) {
                    Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
                        ModelRow(
                            model = model,
                            onClick = {
                                expanded = false
                                onOpenModelPicker()
                            },
                        )
                        ThinkingSlider(
                            level = level,
                            onLevelChange = onLevelChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
                        )
                    }
                }
            }
        }
    }
}

/** `模型  <name>  ›` — read-only display that doubles as the model-picker entry. */
@Composable
private fun ModelRow(
    model: ActiveModel,
    onClick: () -> Unit,
) {
    val chatColors = LocalChatColors.current
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "模型",
            style = MaterialTheme.typography.labelLarge,
            color = colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (model.hasModel) {
            BrandAvatar(
                protocol = model.protocol,
                name = model.displayName,
                size = 16.dp,
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
        }
        Text(
            text = model.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = chatColors.mutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.width(Spacing.xxs))
        ChevronRightIcon(
            modifier = Modifier.iconSize(14.dp),
            tint = chatColors.mutedText,
            contentDescription = null,
        )
    }
}

/**
 * Places the bubble directly above its anchor (the chip), horizontally centred
 * on it, clamped into the window with a small margin. If there is no room above
 * — a short screen with the keyboard up — it flips below the anchor instead of
 * overlapping it.
 */
private class BubbleAboveAnchor(
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val width = popupContentSize.width
        val centred = anchorBounds.left + (anchorBounds.width - width) / 2
        val maxX = (windowSize.width - width - marginPx).coerceAtLeast(marginPx)
        val x = centred.coerceIn(marginPx, maxX)

        val above = anchorBounds.top - popupContentSize.height - gapPx
        val y = if (above >= marginPx) {
            above
        } else {
            val below = anchorBounds.bottom + gapPx
            val maxY = (windowSize.height - popupContentSize.height - marginPx)
                .coerceAtLeast(marginPx)
            below.coerceAtMost(maxY)
        }
        return IntOffset(x, y)
    }
}
