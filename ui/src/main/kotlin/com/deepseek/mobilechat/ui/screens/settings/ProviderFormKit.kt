@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.deepseek.mobilechat.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.deepseek.mobilechat.ui.screens.common.HintText
import com.deepseek.mobilechat.ui.theme.Spacing

/**
 * Small shared parts of the vendor flows (settings editor + first-run guide).
 *
 * They live here rather than in either screen so the two surfaces cannot drift:
 * the guide's "pick a vendor / paste a key" step is literally the same widgets
 * as the simple add form.
 */

/** Vendor chips, 自定义 last. Selection is a single id; null = nothing picked. */
@Composable
internal fun VendorChipGrid(
    selectedId: String?,
    onSelect: (VendorPreset) -> Unit,
    modifier: Modifier = Modifier,
    includeCustom: Boolean = true,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        VendorPresets.all.forEach { preset ->
            FilterChip(
                selected = selectedId == preset.id,
                onClick = { onSelect(preset) },
                label = { Text(preset.displayName) },
            )
        }
        if (includeCustom) {
            FilterChip(
                selected = selectedId == VendorPresets.CUSTOM_ID,
                onClick = { onSelect(VendorPresets.custom) },
                label = { Text(VendorPresets.custom.displayName) },
            )
        }
    }
}

/**
 * Text field backed by a caller-owned [MutableState].
 *
 * The read/write happen *inside* this composable, so typing invalidates only
 * this field — never the surrounding editor page. That is the structural half
 * of the provider-editor typing fix; see `ProviderEditor.kt`.
 */
@Composable
internal fun DraftTextField(
    value: MutableState<String>,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    placeholder: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value.value,
        onValueChange = { value.value = it },
        label = { Text(label) },
        placeholder = placeholder,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth(),
    )
}

/** The only credential input in the simple flow: a password field plus a way to get a key. */
@Composable
internal fun ProviderKeyField(
    apiKey: MutableState<String>,
    hint: String?,
    consoleUrl: String?,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    DraftTextField(
        value = apiKey,
        label = "API Key",
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        placeholder = { Text("粘贴 API Key") },
    )
    if (!hint.isNullOrBlank()) {
        HintText(hint)
    }
    if (!consoleUrl.isNullOrBlank()) {
        TextButton(
            onClick = { runCatching { uriHandler.openUri(consoleUrl) } },
        ) { Text("打开厂商控制台") }
    }
}
