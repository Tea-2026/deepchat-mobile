@file:OptIn(ExperimentalMaterial3Api::class)

package com.deepseek.mobilechat.ui.screens.settings

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.domain.ContextStrategyIds
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.ui.components.DownIcon
import com.deepseek.mobilechat.ui.components.iconSize
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.screens.common.HintText
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Editable snapshot of a preset; the section owns ids and defaults. */
internal data class PresetDraft(    val name: String,
    val systemPrompt: String,
    val providerIdRaw: String?,
    val modelIdText: String,
    val temperature: Float,
    val maxTokensText: String,
    val thinking: ThinkingLevel,
    val strategyId: String,
)

@Composable
internal fun PresetEditorDialog(
    initial: Preset?,
    providers: List<ProviderProfile>,
    onDismiss: () -> Unit,
    onSave: (PresetDraft) -> Unit,
) {
    val key = initial?.id?.raw ?: "new"
    var name by remember(key) { mutableStateOf(initial?.name ?: "") }
    var systemPrompt by remember(key) { mutableStateOf(initial?.systemPrompt ?: "") }
    var providerIdRaw by remember(key) { mutableStateOf(initial?.modelRef?.providerId?.raw) }
    var modelId by remember(key) { mutableStateOf(initial?.modelRef?.modelId?.raw ?: "") }
    var temperature by remember(key) { mutableFloatStateOf((initial?.params?.temperature ?: 0.7).toFloat()) }
    var maxTokens by remember(key) { mutableStateOf(initial?.params?.maxTokens?.toString() ?: "") }
    var thinking by remember(key) { mutableStateOf(initial?.thinkingLevel ?: ThinkingLevel.OFF) }
    var strategy by remember(key) { mutableStateOf(initial?.contextStrategyId ?: ContextStrategyIds.CONFIRM_COMPACTION) }
    var providerMenu by remember { mutableStateOf(false) }
    var strategyMenu by remember { mutableStateOf(false) }
    // The "back onto the 0.05 grid" glide started when the temperature slider is
    // released; a new drag cancels it.
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val selectedProviderName = providers.firstOrNull { it.id.raw == providerIdRaw }?.name ?: "不指定"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增预设" else "编辑预设", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("系统提示词") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { providerMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = selectedProviderName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            DownIcon(modifier = Modifier.iconSize(16.dp), contentDescription = null)
                        }
                        DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("不指定（用会话默认）") },
                                onClick = {
                                    providerIdRaw = null
                                    providerMenu = false
                                },
                            )
                            providers.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text(profile.name) },
                                    onClick = {
                                        providerIdRaw = profile.id.raw
                                        providerMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("模型 id") },
                    placeholder = { Text("留空表示用服务默认模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column {
                    // The slider owns the raw local float (finger-accurate); the
                    // printed number is what eases, so it never reads as a jump
                    // and never pulls the thumb behind the finger.
                    val shownTemperature by animateFloatAsState(
                        targetValue = temperature,
                        animationSpec = tween(Motion.Durations.Short, easing = Motion.Easings.Standard),
                        label = "presetTemperature",
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "温度",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = String.format(Locale.US, "%.2f", shownTemperature),
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalChatColors.current.mutedText,
                        )
                    }
                    Slider(
                        value = temperature,
                        onValueChange = { next ->
                            // A new drag wins over the settle of the previous one.
                            settleJob?.cancel()
                            temperature = next
                        },
                        valueRange = 0f..2f,
                        // **No `steps`**: a stepped slider re-snaps the thumb
                        // under the finger, which is exactly the stutter the user
                        // reported on the concurrency slider. Free drag here, and
                        // the 0.05 grid is restored on release by an eased glide
                        // so the thumb never freezes off-grid.
                        onValueChangeFinished = {
                            val target = temperature.roundToGrid(TemperatureStep)
                            settleJob = scope.launch {
                                animate(
                                    initialValue = temperature,
                                    targetValue = target,
                                    animationSpec = tween(
                                        durationMillis = Motion.Durations.Snap,
                                        easing = Motion.Easings.FastOutSlow,
                                    ),
                                ) { settled, _ -> temperature = settled }
                            }
                        },
                    )
                }
                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { typed -> maxTokens = typed.filter { it.isDigit() } },
                    label = { Text("最大 token（留空用默认）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Column {
                    Text(
                        text = "思考等级",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier.padding(top = Spacing.xxs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        ThinkingLevel.entries.forEach { level ->
                            FilterChip(
                                selected = thinking == level,
                                onClick = { thinking = level },
                                label = { Text(thinkingLabel(level)) },
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(
                        onClick = { strategyMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = contextStrategyLabel(strategy),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        DownIcon(modifier = Modifier.iconSize(16.dp), contentDescription = null)
                    }
                    DropdownMenu(expanded = strategyMenu, onDismissRequest = { strategyMenu = false }) {
                        ContextStrategyOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.second) },
                                onClick = {
                                    strategy = option.first
                                    strategyMenu = false
                                },
                            )
                        }
                    }
                }
                HintText("模型 id 需要先选择一个模型服务；不选服务时使用会话的默认模型。")
                HintText("上下文超限时的处理方式；压缩需要你确认后才执行。")
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        PresetDraft(
                            name = name.trim(),
                            systemPrompt = systemPrompt,
                            providerIdRaw = providerIdRaw,
                            modelIdText = modelId.trim(),
                            temperature = temperature,
                            maxTokensText = maxTokens,
                            thinking = thinking,
                            strategyId = strategy,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** The grid the temperature slider settles onto when the finger is lifted. */
private const val TemperatureStep = 0.05f

/** Rounds [this] to the nearest multiple of [step] — the slider's legal values. */
private fun Float.roundToGrid(step: Float): Float = (this / step).roundToInt() * step
