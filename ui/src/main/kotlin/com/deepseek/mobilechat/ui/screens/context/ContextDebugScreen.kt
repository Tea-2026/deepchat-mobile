@file:OptIn(ExperimentalMaterial3Api::class)

package com.deepseek.mobilechat.ui.screens.context

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.application.BlockKind
import com.deepseek.mobilechat.application.ContextAssembly
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.ui.AppServices
import com.deepseek.mobilechat.ui.components.DownIcon
import com.deepseek.mobilechat.ui.components.RefreshIcon
import com.deepseek.mobilechat.ui.components.iconSize
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.animatedEnter
import com.deepseek.mobilechat.ui.screens.common.BusyRow
import com.deepseek.mobilechat.ui.screens.common.HintText
import com.deepseek.mobilechat.ui.screens.common.InfoCallout
import com.deepseek.mobilechat.ui.screens.common.ScreenTopBar
import com.deepseek.mobilechat.ui.screens.common.compactCount
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import java.util.Locale

/**
 * Context debug: what would actually be sent for a conversation's active leaf.
 *
 * Pure read model — it calls [AppServices.contextAssembler] and renders the
 * budget, the per-block token accounting and the raw prompt. Refresh re-runs the
 * assembly; the arrow spins with the shared motion tokens.
 */
@Composable
fun ContextDebugScreen(
    services: AppServices,
    conversationId: ConversationId,
    onBack: () -> Unit,
) {
    var reloadKey by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<ContextDebugState>(ContextDebugState.Loading) }
    var promptExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(conversationId, reloadKey) {
        state = ContextDebugState.Loading
        state = when (val result = services.contextAssembler.assemble(conversationId)) {
            is Outcome.Ok -> ContextDebugState.Loaded(result.value)
            is Outcome.Err -> ContextDebugState.Failed(result.error.message)
        }
    }

    val spin by animateFloatAsState(
        targetValue = reloadKey * 360f,
        animationSpec = tween(Motion.Durations.Long, easing = Motion.Easings.Emphasized),
        label = "contextRefreshSpin",
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScreenTopBar(
                title = "上下文调试",
                subtitle = "本次请求实际发送的内容",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { reloadKey += 1 }) {
                        RefreshIcon(
                            modifier = Modifier
                                .iconSize(20.dp)
                                .graphicsLayer { rotationZ = spin },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            contentDescription = "刷新",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AnimatedContent(
                targetState = state,
                transitionSpec = { Motion.morph() },
                label = "contextDebugState",
                modifier = Modifier.fillMaxSize(),
            ) { current ->
                when (current) {
                    is ContextDebugState.Loading -> BusyRow("正在组装上下文…")
                    is ContextDebugState.Failed -> FailurePane(
                        message = current.message,
                        onRetry = { reloadKey += 1 },
                    )
                    is ContextDebugState.Loaded -> AssemblyList(
                        assembly = current.assembly,
                        promptExpanded = promptExpanded,
                        onTogglePrompt = { promptExpanded = !promptExpanded },
                    )
                }
            }
        }
    }
}

private fun thinkingLevelLabel(level: com.deepseek.mobilechat.domain.ThinkingLevel): String = when (level) {
    com.deepseek.mobilechat.domain.ThinkingLevel.OFF -> "关闭"
    com.deepseek.mobilechat.domain.ThinkingLevel.LOW -> "低"
    com.deepseek.mobilechat.domain.ThinkingLevel.HIGH -> "中"
    com.deepseek.mobilechat.domain.ThinkingLevel.MAX -> "高"
}

private sealed interface ContextDebugState {
    data object Loading : ContextDebugState
    data class Failed(val message: String) : ContextDebugState
    data class Loaded(val assembly: ContextAssembly) : ContextDebugState
}

@Composable
private fun FailurePane(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        InfoCallout("无法组装上下文：$message", error = true)
        Spacer(Modifier.height(Spacing.md))
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun AssemblyList(
    assembly: ContextAssembly,
    promptExpanded: Boolean,
    onTogglePrompt: () -> Unit,
) {
    val chatColors = LocalChatColors.current
    val systemPrompt = assembly.systemPrompt
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "budget") {
            BudgetCard(assembly)
        }
        item(key = "meta") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "上下文块 · ${assembly.blocks.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "思考 ${thinkingLevelLabel(assembly.thinkingLevel)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = chatColors.mutedText,
                )
            }
            Text(
                text = "${assembly.modelRef.providerId.raw} / ${assembly.modelRef.modelId.raw}" +
                    " · 温度 " + String.format(Locale.US, "%.1f", assembly.params.temperature ?: 0.7),
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (assembly.blocks.isEmpty()) {
            item(key = "no-blocks") {
                HintText("这次请求没有额外的上下文块，只有系统提示词或空历史。")
            }
        }
        val totalTokens = assembly.blocks.sumOf { it.tokens }.coerceAtLeast(1)
        items(assembly.blocks, key = { it.id }) { block ->
            BlockRow(
                kind = block.kind,
                title = block.title,
                tokens = block.tokens,
                share = block.tokens.toFloat() / totalTokens,
                modifier = Modifier.animateItem(),
            )
        }
        item(key = "prompt-header") {
            PromptHeader(
                expanded = promptExpanded,
                count = assembly.prompt.size,
                onToggle = onTogglePrompt,
            )
        }
        if (promptExpanded) {
            if (assembly.prompt.isEmpty()) {
                item(key = "prompt-empty") { HintText("prompt 为空。") }
            }
            itemsIndexed(assembly.prompt) { index, message ->
                PromptRow(
                    index = index,
                    message = message,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (!systemPrompt.isNullOrBlank()) {
            item(key = "system-prompt") {
                Column(modifier = Modifier.padding(top = Spacing.sm)) {
                    Text(
                        text = "单独的系统提示词",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    MonospaceBlock(text = systemPrompt)
                }
            }
        }
    }
}

