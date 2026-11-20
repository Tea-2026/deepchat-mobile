package com.deepseek.mobilechat.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.ui.components.BackIcon
import com.deepseek.mobilechat.ui.components.ExportIcon
import com.deepseek.mobilechat.ui.components.MenuIcon
import com.deepseek.mobilechat.ui.components.MoreIcon
import com.deepseek.mobilechat.ui.components.NewChatIcon
import com.deepseek.mobilechat.ui.components.iconSize
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing

/** What the left slot of the top bar does (spec §2 / §3). */
enum class ChatLeading {
    /** Home: opens the navigation drawer. */
    Drawer,

    /** Pushed from elsewhere: goes back. */
    Back,
}

/**
 * Top bar: drawer/back, animated title, 「模型 · 思考等级」 subtitle, overflow.
 *
 * The hairline under the bar fades in only once the list can scroll, so an
 * empty conversation has no chrome to explain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    title: String,
    subtitle: String,
    scrolled: Boolean,
    injectionCount: Int,
    leading: ChatLeading,
    onLeading: () -> Unit,
    onOpenContextDebug: () -> Unit,
    onOpenInjections: () -> Unit,
    onExport: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onNewConversation: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    val chatColors = LocalChatColors.current
    var menuOpen by remember { mutableStateOf(false) }
    val dividerAlpha by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = tween(Motion.Durations.Medium, easing = Motion.Easings.Standard),
        label = "topBarDivider",
    )

    Column(modifier = modifier) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onLeading) {
                    AnimatedContent(
                        targetState = leading,
                        transitionSpec = {
                            fadeIn(
                                tween(Motion.Durations.Medium, easing = Motion.Easings.Decelerate),
                            ) togetherWith fadeOut(
                                tween(Motion.Durations.Short, easing = Motion.Easings.Accelerate),
                            )
                        },
                        label = "leading",
                    ) { mode ->
                        when (mode) {
                            ChatLeading.Drawer -> MenuIcon(
                                modifier = Modifier.iconSize(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )

                            ChatLeading.Back -> BackIcon(
                                modifier = Modifier.iconSize(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            title = {
                Column {
                    AnimatedContent(
                        targetState = title,
                        transitionSpec = {
                            fadeIn(tween(Motion.Durations.Medium, easing = Motion.Easings.Decelerate)) togetherWith
                                fadeOut(tween(Motion.Durations.Short, easing = Motion.Easings.Accelerate))
                        },
                        label = "title",
                    ) { current ->
                        Text(
                            text = current,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 220.dp),
                        )
                    }
                    AnimatedContent(
                        targetState = subtitle,
                        transitionSpec = {
                            fadeIn(tween(Motion.Durations.Medium, easing = Motion.Easings.Decelerate)) togetherWith
                                fadeOut(tween(Motion.Durations.Short, easing = Motion.Easings.Accelerate))
                        },
                        label = "subtitle",
                    ) { current ->
                        Text(
                            text = current,
                            style = MaterialTheme.typography.labelSmall,
                            color = chatColors.mutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 240.dp),
                        )
                    }
                }
            },
            actions = {
                if (onNewConversation != null) {
                    IconButton(onClick = onNewConversation) {
                        NewChatIcon(
                            modifier = Modifier.iconSize(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        MoreIcon(
                            modifier = Modifier.iconSize(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        DropdownMenuItem(
                            text = { Text("上下文调试") },
                            onClick = {
                                menuOpen = false
                                onOpenContextDebug()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (injectionCount > 0) "上下文注入（$injectionCount）" else "上下文注入",
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onOpenInjections()
                            },
                        )
                        if (onExport != null) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ExportIcon(
                                            modifier = Modifier.iconSize(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(modifier = Modifier.width(Spacing.sm))
                                        Text("导出")
                                    }
                                },
                                onClick = {
                                    menuOpen = false
                                    onExport()
                                },
                            )
                        }
                        if (onOpenSettings != null) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = Spacing.md),
                                color = chatColors.divider,
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = {
                                    menuOpen = false
                                    onOpenSettings()
                                },
                            )
                        }
                    }
                }
            },
        )
        HorizontalDivider(
            thickness = 1.dp,
            color = chatColors.divider.copy(alpha = dividerAlpha),
        )
    }
}
