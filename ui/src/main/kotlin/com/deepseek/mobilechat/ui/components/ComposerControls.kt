package com.deepseek.mobilechat.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.MotionVisibility
import com.deepseek.mobilechat.ui.motion.pressable
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.ShapeTokens
import com.deepseek.mobilechat.ui.theme.Spacing

/** What the circular action button is currently offering (spec §4). */
private enum class ComposerAction { Send, Stop, Searching }

/**
 * Circular action that morphs between send / stop / searching instead of
 * swapping. Container colour, glyph and scale all travel on the same beat, so
 * the button never looks like it was replaced by a different control.
 */
@Composable
fun SendStopButton(
    isGenerating: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSearching: Boolean = false,
    size: Dp = 40.dp,
) {
    val chatColors = LocalChatColors.current
    val action = when {
        isSearching -> ComposerAction.Searching
        isGenerating -> ComposerAction.Stop
        else -> ComposerAction.Send
    }
    val active = action != ComposerAction.Send || enabled

    val containerColor by animateColorAsState(
        targetValue = when {
            action == ComposerAction.Searching -> MaterialTheme.colorScheme.primaryContainer
            action == ComposerAction.Stop -> MaterialTheme.colorScheme.surfaceContainerHighest
            enabled -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(Motion.Durations.Medium, easing = Motion.Easings.Standard),
        label = "sendContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            action == ComposerAction.Searching -> MaterialTheme.colorScheme.onPrimaryContainer
            action == ComposerAction.Stop -> MaterialTheme.colorScheme.onSurface
            enabled -> MaterialTheme.colorScheme.onPrimary
            else -> chatColors.mutedText
        },
        animationSpec = tween(Motion.Durations.Medium, easing = Motion.Easings.Standard),
        label = "sendContent",
    )

    Surface(
        modifier = modifier
            .size(size)
            .pressable(enabled = active, onClick = onClick),
        shape = CircleShape,
        color = containerColor,
    ) {
        AnimatedContent(
            targetState = action,
            transitionSpec = {
                Motion.morph(initialScale = 0.55f, targetScale = 0.55f)
            },
            label = "sendStopMorph",
        ) { state ->
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides contentColor,
                ) {
                    when (state) {
                        ComposerAction.Searching -> CircularProgressIndicator(
                            modifier = Modifier.size(size * 0.45f),
                            strokeWidth = 2.dp,
                            color = contentColor,
                        )

                        ComposerAction.Stop -> StopIcon(
                            modifier = Modifier.iconSize(size * 0.42f),
                            tint = contentColor,
                        )

                        ComposerAction.Send -> SendIcon(
                            modifier = Modifier.iconSize(size * 0.46f),
                            tint = contentColor,
                        )
                    }
                }
            }
        }
    }
}

/**
 * "联网" toggle. Off: a bare globe button. On: it grows into a pill and the
 * label slides in — the width change is animated, not snapped.
 */
@Composable
fun SearchToggle(
    enabled: Boolean,
    searching: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        targetValue = if (enabled) {
            // Spec §4: "开启时强调色填充" — a real accent fill, not a tint.
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(Motion.Durations.Medium, easing = Motion.Easings.Standard),
        label = "searchToggleContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(Motion.Durations.Medium, easing = Motion.Easings.Standard),
        label = "searchToggleContent",
    )

    Surface(
        modifier = modifier.pressable(enabled = !searching, onClick = onToggle),
        shape = ShapeTokens.Pill,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (searching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.6.dp,
                        color = contentColor,
                    )
                } else {
                    GlobeIcon(
                        modifier = Modifier.iconSize(16.dp),
                        tint = contentColor,
                    )
                }
            }
            MotionVisibility(
                visible = enabled,
                enter = fadeIn(
                    tween(Motion.Durations.Medium, easing = Motion.Easings.Decelerate),
                ) + expandHorizontally(
                    animationSpec = tween(Motion.Durations.Medium, easing = Motion.Easings.Emphasized),
                    expandFrom = Alignment.Start,
                ),
                exit = fadeOut(tween(Motion.Durations.Short, easing = Motion.Easings.Accelerate)) +
                    shrinkHorizontally(
                        animationSpec = tween(Motion.Durations.Short, easing = Motion.Easings.Standard),
                        shrinkTowards = Alignment.Start,
                    ),
                label = "searchToggleLabel",
            ) {
                Text(
                    text = "联网",
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    modifier = Modifier.padding(end = Spacing.sm),
                )
            }
        }
    }
}

/** The `＋` on the left of the control bar: opens the attachment panel. */
@Composable
fun AttachButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    Surface(
        modifier = modifier
            .size(size)
            .pressable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(contentAlignment = Alignment.Center) {
            AttachIcon(
                modifier = Modifier.iconSize(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
