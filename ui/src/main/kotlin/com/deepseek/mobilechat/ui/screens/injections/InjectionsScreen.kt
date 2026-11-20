@file:OptIn(ExperimentalMaterial3Api::class)

package com.deepseek.mobilechat.ui.screens.injections

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.domain.ContextInjection
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.InjectionScope
import com.deepseek.mobilechat.ui.AppServices
import com.deepseek.mobilechat.ui.components.TrashIcon
import com.deepseek.mobilechat.ui.components.iconSize
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.animatedEnter
import com.deepseek.mobilechat.ui.screens.common.ClockGlyph
import com.deepseek.mobilechat.ui.screens.common.HintText
import com.deepseek.mobilechat.ui.screens.common.ScreenEmptyState
import com.deepseek.mobilechat.ui.screens.common.ScreenTopBar
import com.deepseek.mobilechat.ui.screens.common.absoluteTime
import com.deepseek.mobilechat.ui.screens.common.rememberNow
import com.deepseek.mobilechat.ui.screens.common.relativeTime
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Manual context injection: persistent blocks ride along with every request,
 * ephemeral ones are consumed by the next send only.
 *
 * The screen explains the concept in the empty state, because "injection" is
 * not a word a first-time user can be expected to know.
 */
@Composable
fun InjectionsScreen(
    services: AppServices,
    conversationId: ConversationId,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val now = rememberNow(services.clock)
    val injections by services.context.observeInjections(conversationId)
        .collectAsStateWithLifecycle(initialValue = emptyList<ContextInjection>())

    var draft by rememberSaveable { mutableStateOf("") }
    var scopeName by rememberSaveable { mutableStateOf(InjectionScope.PERSISTENT.name) }
    var adding by remember { mutableStateOf(false) }
    val injectionScope = InjectionScope.valueOf(scopeName)

    val persistent = remember(injections) { injections.filter { it.scope == InjectionScope.PERSISTENT } }
    val ephemeral = remember(injections) { injections.filter { it.scope == InjectionScope.EPHEMERAL } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ScreenTopBar(
                title = "上下文注入",
                subtitle = if (injections.isEmpty()) "还没有注入内容" else "${injections.size} 条注入",
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = injections.isEmpty(),
                    transitionSpec = { Motion.morph() },
                    label = "injectionsEmpty",
                    modifier = Modifier.fillMaxSize(),
                ) { empty ->
                    if (empty) {
                        ScreenEmptyState(
                            title = "什么是上下文注入？",
                            subtitle = "把一段不属于对话的内容（设定、资料、规则）直接塞进下一次请求。" +
                                "常驻注入每次都带，临时注入只带一次。",
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = Spacing.lg,
                                end = Spacing.lg,
                                top = Spacing.md,
                                bottom = Spacing.md,
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            item(key = "persistent-header") {
                                GroupHeader(
                                    title = "常驻注入",
                                    count = persistent.size,
                                    hint = "每次请求都会带上",
                                )
                            }
                            if (persistent.isEmpty()) {
                                item(key = "persistent-empty") {
                                    HintText("还没有常驻注入。")
                                }
                            }
                            items(persistent, key = { it.id.raw }) { injection ->
                                InjectionRow(
                                    injection = injection,
                                    now = now,
                                    onDelete = {
                                        scope.launch {
                                            when (val result = services.context.removeInjection(injection.id)) {
                                                is Outcome.Ok -> snackbar.showSnackbar("已删除注入")
                                                is Outcome.Err -> snackbar.showSnackbar(result.error.message)
                                            }
                                        }
                                    },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                            item(key = "ephemeral-header") {
                                GroupHeader(
                                    title = "临时注入",
                                    count = ephemeral.size,
                                    hint = "只随下一次请求发送",
                                )
                            }
                            if (ephemeral.isEmpty()) {
                                item(key = "ephemeral-empty") {
                                    HintText("还没有临时注入。")
                                }
                            }
                            items(ephemeral, key = { it.id.raw }) { injection ->
                                InjectionRow(
                                    injection = injection,
                                    now = now,
                                    onDelete = {
                                        scope.launch {
                                            when (val result = services.context.removeInjection(injection.id)) {
                                                is Outcome.Ok -> snackbar.showSnackbar("已删除注入")
                                                is Outcome.Err -> snackbar.showSnackbar(result.error.message)
                                            }
                                        }
                                    },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }
            AddInjectionBar(
                draft = draft,
                onDraftChange = { draft = it },
                scope = injectionScope,
                onScopeChange = { scopeName = it.name },
                adding = adding,
                onAdd = {
                    val content = draft.trim()
                    if (content.isNotEmpty()) {
                        adding = true
                        scope.launch {
                            when (val result = services.context.addInjection(conversationId, content, injectionScope)) {
                                is Outcome.Ok -> {
                                    draft = ""
                                    snackbar.showSnackbar("已添加注入")
                                }
                                is Outcome.Err -> snackbar.showSnackbar(result.error.message)
                            }
                            adding = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun GroupHeader(title: String, count: Int, hint: String) {
    val chatColors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm, bottom = Spacing.xxs)
            .animatedEnter(key = title),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = chatColors.mutedText,
        )
    }
}

@Composable
private fun InjectionRow(
    injection: ContextInjection,
    now: Long,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    var expanded by remember(injection.id.raw) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(Spacing.md),
        ) {
            Text(
                text = injection.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClockGlyph(modifier = Modifier.iconSize(13.dp), tint = chatColors.mutedText)
                Spacer(Modifier.width(Spacing.xxs))
                Text(
                    text = "${relativeTime(now, injection.createdAt)} · ${absoluteTime(injection.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = chatColors.mutedText,
                    modifier = Modifier.weight(1f),
                )
                if (injection.content.length > 90) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起" else "展开")
                    }
                }
                TextButton(onClick = onDelete) {
                    TrashIcon(
                        modifier = Modifier.iconSize(15.dp),
                        tint = MaterialTheme.colorScheme.error,
                        contentDescription = "删除注入",
                    )
                }
            }
        }
    }
}

@Composable
private fun AddInjectionBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    scope: InjectionScope,
    onScopeChange: (InjectionScope) -> Unit,
    adding: Boolean,
    onAdd: () -> Unit,
) {
    val chatColors = LocalChatColors.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
                placeholder = {
                    Text(
                        text = "把要注入的内容写在这里…",
                        style = MaterialTheme.typography.bodySmall,
                        color = chatColors.mutedText,
                    )
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = scope == InjectionScope.PERSISTENT,
                    onClick = { onScopeChange(InjectionScope.PERSISTENT) },
                    label = { Text("常驻") },
                )
                Spacer(Modifier.width(Spacing.xs))
                FilterChip(
                    selected = scope == InjectionScope.EPHEMERAL,
                    onClick = { onScopeChange(InjectionScope.EPHEMERAL) },
                    label = { Text("临时") },
                )
                Spacer(Modifier.weight(1f))
                Button(onClick = onAdd, enabled = draft.isNotBlank() && !adding) {
                    if (adding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(Spacing.sm))
                    }
                    Text("添加")
                }
            }
        }
    }
}
