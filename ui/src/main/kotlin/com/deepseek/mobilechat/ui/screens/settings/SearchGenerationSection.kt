@file:OptIn(ExperimentalMaterial3Api::class)

package com.deepseek.mobilechat.ui.screens.settings

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.application.AppSettings
import com.deepseek.mobilechat.application.SearchProviderKind
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.screens.common.HintText
import com.deepseek.mobilechat.ui.screens.common.InfoCallout
import com.deepseek.mobilechat.ui.screens.common.SectionCard
import com.deepseek.mobilechat.ui.screens.common.StatusDot
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Search source + per-source keys. Split from the generation cap so each is its
 * own second-level page; both still talk only through [SettingsCapability], and
 * when the composition root has not provided it an honest notice replaces the
 * controls instead of a dead switch.
 */
@Composable
internal fun SearchSourceSection(
    capability: SettingsCapability?,
    settings: AppSettings?,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val chatColors = LocalChatColors.current
    var savedRefs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var drafts by remember { mutableStateOf<Map<SearchProviderKind, String>>(emptyMap()) }

    // Existence only — the stored value is never read back into the UI.
    LaunchedEffect(capability) {
        val cap = capability ?: return@LaunchedEffect
        savedRefs = SearchProviderKind.entries
            .map { searchSecretRef(it) }
            .filter { it.isNotEmpty() }
            .filter { ref -> cap.secretStore.get(ref) != null }
            .toSet()
    }

    SectionCard(
        title = "搜索源",
        subtitle = "联网搜索结果作为上下文块注入",
    ) {
        if (capability == null) {
            InfoCallout("当前构建未提供设置存储，搜索源与 API Key 暂不可改。")
            return@SectionCard
        }
        if (settings == null) {
            HintText("正在读取设置…")
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            SearchProviderKind.entries.forEach { kind ->
                FilterChip(
                    selected = settings.activeSearchProvider == kind,
                    onClick = {
                        scope.launch {
                            runCatching {
                                capability.settingsStore.update(settings.copy(activeSearchProvider = kind))
                            }.onSuccess {
                                onMessage("搜索源已切换为 ${searchProviderLabel(kind)}")
                            }.onFailure { onMessage(it.message ?: "保存搜索源失败") }
                        }
                    },
                    label = { Text(searchProviderLabel(kind)) },
                )
            }
        }
        SearchProviderKind.entries.forEach { kind ->
            val ref = searchSecretRef(kind)
            // Keyless sources reuse an existing credential (DeepSeek provider
            // key) or need none at all; rendering a paste box for them would
            // write a key nobody ever reads.
            val keyless = ref.isEmpty()
            val saved = ref.isNotEmpty() && ref in savedRefs
            Column(modifier = Modifier.padding(top = Spacing.xs)) {
                if (keyless) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            text = searchProviderLabel(kind),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = if (kind == SearchProviderKind.DEEPSEEK) {
                                "无需额外密钥，复用 DeepSeek 服务源"
                            } else {
                                "无需密钥"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = chatColors.mutedText,
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(
                            color = if (saved) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                chatColors.mutedText.copy(alpha = 0.4f)
                            },
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            text = "${searchProviderLabel(kind)} API Key",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = if (saved) "已保存" else "未保存",
                            style = MaterialTheme.typography.labelSmall,
                            color = chatColors.mutedText,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = drafts[kind].orEmpty(),
                            onValueChange = { typed -> drafts = drafts + (kind to typed) },
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = Spacing.xxs),
                            singleLine = true,
                            placeholder = {
                                Text(
                                    text = if (saved) "留空表示不修改" else "粘贴密钥",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = chatColors.mutedText,
                                )
                            },
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        TextButton(
                            enabled = drafts[kind].orEmpty().isNotBlank(),
                            onClick = {
                                val value = drafts[kind].orEmpty().trim()
                                scope.launch {
                                    runCatching { capability.secretStore.put(ref, value) }
                                        .onSuccess {
                                            savedRefs = savedRefs + ref
                                            drafts = drafts - kind
                                            onMessage("已保存 ${searchProviderLabel(kind)} 密钥")
                                        }
                                        .onFailure { onMessage(it.message ?: "保存密钥失败") }
                                }
                            },
                        ) { Text("保存") }
                    }
                }
            }
        }
        HintText("密钥不落数据库，只写入本机密钥库；界面也不会回显已保存的值。")
    }
}

/**
 * Generation concurrency.
 *
 * Slider contract (spec §7): the thumb is bound to the *local* float while a
 * drag is in progress, so it tracks the finger with zero latency; nothing is
 * written to the store until [Slider] reports `onValueChangeFinished`. The
 * numeric readout is what animates (`animateFloatAsState`), not the thumb — an
 * animated thumb reads as lag, an animated caption reads as smoothing.
 *
 * The value is continuous during the drag (no `steps`), and on release the thumb
 * glides back onto the nearest integer — the only values the store accepts — in
 * [Motion.Durations.Snap] ms. Releasing at 2.4 therefore ends at 2 rather than
 * leaving the thumb parked between two inputs.
 */
@Composable
internal fun GenerationSection(
    capability: SettingsCapability?,
    settings: AppSettings?,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val stored = settings?.maxConcurrentGenerations

    // Local, finger-tracking value. Seeded once, then owned here while dragging.
    var value by remember { mutableFloatStateOf((stored ?: 2).toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    // The "back into the legal range" glide started on release.
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val shownValue by animateFloatAsState(
        targetValue = value,
        animationSpec = tween(Motion.Durations.Short, easing = Motion.Easings.Standard),
        label = "generationConcurrency",
    )

    // The store stays the source of truth whenever the user is not dragging;
    // the guard is what stops a late emission from yanking the thumb mid-drag.
    LaunchedEffect(stored) {
        if (!dragging && stored != null) value = stored.toFloat()
    }

    SectionCard(
        title = "生成",
        subtitle = "同时进行的生成数量",
    ) {
        if (capability == null) {
            InfoCallout("当前构建未提供设置存储，生成参数暂不可改。")
            return@SectionCard
        }
        if (settings == null) {
            HintText("正在读取设置…")
            return@SectionCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "并发上限",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = shownValue.roundToInt().toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            // Raw local value — not `shownValue`: the thumb must never lag the
            // finger, only the caption is allowed to ease.
            value = value,
            onValueChange = { next ->
                // A new drag wins over the settle animation of the previous one.
                settleJob?.cancel()
                dragging = true
                value = next
            },
            valueRange = 1f..4f,
            // **No `steps`**: with steps the thumb snaps to each integer while
            // the finger keeps moving between them, which reads as stutter. The
            // thumb is free during the drag and the legal value is restored (by
            // an eased glide, see below) on release.
            onValueChangeFinished = finished@{
                dragging = false
                val target = value.roundToInt()
                // Glide back into the legal range instead of teleporting: the
                // user may have released at 2.4, which is not a storable value.
                settleJob = scope.launch {
                    animate(
                        initialValue = value,
                        targetValue = target.toFloat(),
                        animationSpec = tween(
                            durationMillis = Motion.Durations.Snap,
                            easing = Motion.Easings.FastOutSlow,
                        ),
                    ) { settled, _ -> value = settled }
                }
                if (target == settings.maxConcurrentGenerations) return@finished
                scope.launch {
                    runCatching {
                        capability.settingsStore.update(settings.copy(maxConcurrentGenerations = target))
                    }.onSuccess {
                        onMessage("并发上限已设为 $target")
                    }.onFailure { onMessage(it.message ?: "保存并发设置失败") }
                }
            },
        )
        HintText("超出的请求会排队，切换会话不会打断正在运行的生成。")
    }
}
