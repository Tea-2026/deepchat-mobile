package com.deepseek.mobilechat.ui.screens.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.ui.components.BackIcon
import com.deepseek.mobilechat.ui.components.DownIcon
import com.deepseek.mobilechat.ui.components.iconSize
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.animatedEnter
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing

/**
 * Shared chrome for the screens outside the chat transcript.
 *
 * Every screen in this package repeats the same three things — a back bar with
 * a hairline, collapsible sections, and a confirmation dialog — so they live
 * here rather than being copy-pasted (and drifting) five times.
 */

// ---------------------------------------------------------------------------
// Bars and sections
// ---------------------------------------------------------------------------

/** Back bar used by every secondary screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val chatColors = LocalChatColors.current
    Column {
        TopAppBar(
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        BackIcon(
                            modifier = Modifier.iconSize(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            title = {
                Column(modifier = Modifier.padding(start = if (onBack == null) Spacing.xs else 0.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = chatColors.mutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            actions = actions,
        )
        HorizontalDivider(thickness = 1.dp, color = chatColors.divider)
    }
}

/**
 * Collapsible section: header row (title + optional trailing action + chevron)
 * and a body that expands with the house accordion transition.
 */
@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    initiallyExpanded: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val chatColors = LocalChatColors.current
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(Motion.Durations.Medium, easing = Motion.Easings.Standard),
        label = "sectionChevron",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = chatColors.mutedText,
                        )
                    }
                }
                trailing?.invoke()
                Spacer(Modifier.width(Spacing.sm))
                DownIcon(
                    modifier = Modifier
                        .iconSize(16.dp)
                        .graphicsLayer { rotationZ = chevron },
                    tint = chatColors.mutedText,
                    contentDescription = null,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = Motion.enterFromTop(),
                exit = Motion.exitToTop(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    content()
                }
            }
        }
    }
}

/** Small muted line of copy; used for hints and footnotes. */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalChatColors.current.mutedText,
        modifier = modifier,
    )
}

/** Tinted notice — used when a capability is missing or a call failed softly. */
@Composable
fun InfoCallout(
    text: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (error) scheme.errorContainer else scheme.secondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) scheme.onErrorContainer else scheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
    }
}

/** Destructive confirmation; the only dialog shape this package needs twice. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "删除",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** Spinner + label, for a screen that has no data yet. */
@Composable
fun BusyRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.xl)
            .animatedEnter(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalChatColors.current.mutedText,
        )
    }
}

/**
 * Empty state with a drawn illustration (no bitmap assets in this module).
 * The mark is a stack of rounded sheets with a few dots — reads as "a list
 * waiting to be filled" without needing an icon font.
 */
@Composable
fun ScreenEmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val chatColors = LocalChatColors.current
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .animatedEnter()
                    .drawBehind {
                        val w = size.width
                        val h = size.height
                        val sheet = androidx.compose.ui.geometry.Size(w * 0.62f, h * 0.16f)
                        val radius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f, w * 0.05f)
                        for (index in 0..2) {
                            val alpha = 0.18f + index * 0.12f
                            drawRoundRect(
                                color = primary.copy(alpha = alpha),
                                topLeft = Offset(w * 0.19f, h * (0.24f + index * 0.19f)),
                                size = sheet,
                                cornerRadius = radius,
                            )
                        }
                        drawCircle(color = tertiary, radius = w * 0.045f, center = Offset(w * 0.7f, h * 0.27f))
                        drawCircle(
                            color = tertiary.copy(alpha = 0.45f),
                            radius = w * 0.028f,
                            center = Offset(w * 0.7f, h * 0.46f),
                        )
                    },
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = Spacing.md)
                    .animatedEnter(delayMillis = 60),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = chatColors.mutedText,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = Spacing.sm)
                    .animatedEnter(delayMillis = 110),
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(Spacing.xs))
                Surface(
                    modifier = Modifier
                        .padding(top = Spacing.sm)
                        .animatedEnter(delayMillis = 160)
                        .clickable(onClick = onAction),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                    )
                }
            }
        }
    }
}

/** Two-tone status dot; used by provider/test-connection states. */
@Composable
fun StatusDot(color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}
