package com.deepseek.mobilechat.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import com.deepseek.mobilechat.ui.theme.tabular
import java.util.Locale

/**
 * Per-conversation sampling overrides (spec §5).
 *
 * Sliders follow the finger in local state and only write back when the finger
 * lifts (spec §7), so dragging never fights the store round-trip; the number
 * above each slider eases with `animateFloatAsState` so it reads as a value
 * change rather than a stutter.
 */
@Composable
fun AdvancedParamsSheetContent(
    initial: SamplingParams,
    onApply: (SamplingParams) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    var temperature by remember(initial) {
        mutableFloatStateOf(initial.temperature?.toFloat() ?: DefaultTemperature)
    }
    var topP by remember(initial) { mutableFloatStateOf(initial.topP?.toFloat() ?: DefaultTopP) }
    var maxTokensText by rememberSaveable(initial.maxTokens) {
        mutableStateOf(initial.maxTokens?.toString().orEmpty())
    }
    var stopText by rememberSaveable(initial.stop) {
        mutableStateOf(initial.stop.joinToString("\n"))
    }

    fun emit(currentTemperature: Float = temperature, currentTopP: Float = topP) {
        onApply(
            SamplingParams(
                temperature = currentTemperature.toDouble(),
                topP = currentTopP.toDouble(),
                maxTokens = maxTokensText.trim().toIntOrNull(),
                stop = stopText.lines().map { it.trim() }.filter { it.isNotEmpty() },
            ),
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        SheetHeading(
            title = "高级参数",
            hint = "仅本会话 · 会覆盖模型默认值",
        )

        ParameterSlider(
            label = "温度",
            hint = "越低越稳定，越高越发散",
            value = temperature,
            range = 0f..2f,
            onChange = { temperature = it },
            onChangeFinished = { emit() },
        )
        ParameterSlider(
            label = "Top-p",
            hint = "候选词的累积概率上限",
            value = topP,
            range = 0.05f..1f,
            onChange = { topP = it },
            onChangeFinished = { emit() },
        )

        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
            Text(
                text = "最大 token",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "留空表示跟随模型上限",
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
            )
            OutlinedTextField(
                value = maxTokensText,
                onValueChange = { input -> maxTokensText = input.filter { it.isDigit() }.take(7) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs),
                singleLine = true,
                placeholder = {
                    Text(
                        text = "例如 4096",
                        style = MaterialTheme.typography.bodyMedium,
                        color = chatColors.mutedText,
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
            Text(
                text = "停止序列",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "每行一个，模型遇到它就停下",
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
            )
            OutlinedTextField(
                value = stopText,
                onValueChange = { stopText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .padding(top = Spacing.xs),
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = {
                    Text(
                        text = "例如\n\nUser:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = chatColors.mutedText,
                    )
                },
                maxLines = 4,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(
                label = "重置为默认",
                onClick = {
                    temperature = DefaultTemperature
                    topP = DefaultTopP
                    maxTokensText = ""
                    stopText = ""
                    onReset()
                },
                primary = false,
            )
            Spacer(modifier = Modifier.weight(1f))
            PillButton(
                label = "完成",
                onClick = {
                    emit()
                    onDismiss()
                },
                primary = true,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.md))
    }
}

@Composable
private fun ParameterSlider(
    label: String,
    hint: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
) {
    val chatColors = LocalChatColors.current
    val shown by animateFloatAsState(
        targetValue = value,
        animationSpec = tween(Motion.Durations.Medium, easing = Motion.Easings.Standard),
        label = "shown$label",
    )
    Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = chatColors.mutedText,
                )
            }
            Text(
                text = String.format(Locale.US, "%.2f", shown),
                style = MaterialTheme.typography.labelLarge.tabular(),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun SheetHeading(title: String, hint: String? = null) {
    val chatColors = LocalChatColors.current
    Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
            )
        }
    }
}

@Composable
internal fun SheetHintCard(text: String) {
    val chatColors = LocalChatColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = chatColors.accentSoft,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, chatColors.hairline.copy(alpha = 0.5f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
    }
}

private const val DefaultTemperature = 0.7f
private const val DefaultTopP = 1f
