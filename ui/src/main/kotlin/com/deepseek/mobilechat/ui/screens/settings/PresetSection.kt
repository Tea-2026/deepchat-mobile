package com.deepseek.mobilechat.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.ui.AppServices
import com.deepseek.mobilechat.ui.motion.animatedEnter
import com.deepseek.mobilechat.ui.screens.common.ConfirmDialog
import com.deepseek.mobilechat.ui.screens.common.HintText
import com.deepseek.mobilechat.ui.screens.common.SectionCard
import com.deepseek.mobilechat.ui.screens.common.compactCount
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Preset list + editor. A preset is prompt + model + sampling + thinking +
 * context strategy, so the row summarises all five rather than just the name.
 */
@Composable
internal fun PresetSection(
    services: AppServices,
    presets: List<Preset>,
    providers: List<ProviderProfile>,
    onMessage: (String) -> Unit,
    title: String = "预设",
) {
    val scope = rememberCoroutineScope()
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Preset?>(null) }
    var deleteTarget by remember { mutableStateOf<Preset?>(null) }

    fun draftToPreset(draft: PresetDraft, existing: Preset?): Preset {
        val modelRef = if (draft.providerIdRaw != null && draft.modelIdText.isNotBlank()) {
            ModelRef(ProviderId(draft.providerIdRaw), ModelId(draft.modelIdText))
        } else {
            null
        }
        return Preset(
            id = existing?.id ?: PresetId(localId("preset")),
            name = draft.name,
            systemPrompt = draft.systemPrompt.trim(),
            modelRef = modelRef,
            params = SamplingParams(
                temperature = draft.temperature.toDouble(),
                maxTokens = draft.maxTokensText.toIntOrNull(),
            ),
            thinkingLevel = draft.thinking,
            contextStrategyId = draft.strategyId,
            isDefault = existing?.isDefault ?: false,
        )
    }

    SectionCard(
        title = title,
        subtitle = if (presets.isEmpty()) "还没有预设" else "${presets.size} 个预设",
        trailing = {
            TextButton(
                onClick = {
                    editing = null
                    editorOpen = true
                },
            ) { Text("新增") }
        },
    ) {
        if (presets.isEmpty()) {
            HintText("预设是一套可复用的提示词与参数；设为默认后会用于新建的会话。")
        }
        presets.forEach { preset ->
            PresetRow(
                preset = preset,
                providers = providers,
                onEdit = {
                    editing = preset
                    editorOpen = true
                },
                onSetDefault = {
                    scope.launch {
                        when (val result = services.presets.setDefault(preset.id)) {
                            is Outcome.Ok -> onMessage("已将「${preset.name}」设为默认")
                            is Outcome.Err -> onMessage(result.error.message)
                        }
                    }
                },
                onDelete = { deleteTarget = preset },
                modifier = Modifier.animatedEnter(key = preset.id.raw),
            )
        }
    }

    if (editorOpen) {
        PresetEditorDialog(
            initial = editing,
            providers = providers,
            onDismiss = { editorOpen = false },
            onSave = { draft ->
                editorOpen = false
                val existing = editing
                scope.launch {
                    when (val result = services.presets.save(draftToPreset(draft, existing))) {
                        is Outcome.Ok -> onMessage(if (existing == null) "已添加预设" else "已保存预设")
                        is Outcome.Err -> onMessage(result.error.message)
                    }
                }
            },
        )
    }

    deleteTarget?.let { preset ->
        ConfirmDialog(
            title = "删除预设",
            message = "「${preset.name}」会被删除；使用它的会话会回到默认预设或自有参数。",
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    when (val result = services.presets.delete(preset.id)) {
                        is Outcome.Ok -> onMessage("已删除预设")
                        is Outcome.Err -> onMessage(result.error.message)
                    }
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun PresetRow(
    preset: Preset,
    providers: List<ProviderProfile>,
    onEdit: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    val providerName = preset.modelRef?.let { ref ->
        providers.firstOrNull { it.id == ref.providerId }?.name ?: ref.providerId.raw
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (preset.isDefault) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = "默认",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                        )
                    }
                }
            }
            if (preset.systemPrompt.isNotBlank()) {
                Text(
                    text = preset.systemPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = chatColors.mutedText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.xxs),
                )
            }
            Text(
                text = buildString {
                    append(preset.modelRef?.let { "${providerName ?: "未知服务"} / ${it.modelId.raw}" } ?: "未指定模型")
                    append(" · 温度 ")
                    append(String.format(Locale.US, "%.1f", preset.params.temperature ?: 0.7))
                    append(" · 思考 ")
                    append(thinkingLabel(preset.thinkingLevel))
                    preset.params.maxTokens?.let {
                        append(" · 上限 ")
                        append(compactCount(it))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
            Text(
                text = "上下文：${contextStrategyLabel(preset.contextStrategyId)}",
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                TextButton(onClick = onEdit) { Text("编辑") }
                if (!preset.isDefault) {
                    TextButton(onClick = onSetDefault) { Text("设为默认") }
                }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
