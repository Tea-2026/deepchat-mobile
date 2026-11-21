@file:OptIn(ExperimentalMaterial3Api::class)

package com.deepseek.mobilechat.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.ProviderProtocol
import com.deepseek.mobilechat.ui.components.BackIcon
import com.deepseek.mobilechat.ui.components.DownIcon
import com.deepseek.mobilechat.ui.components.iconSize
import com.deepseek.mobilechat.ui.screens.common.HintText
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Full-screen provider editor.
 *
 * Two complaints are fixed structurally here:
 *
 * 1. **Typing jitter.** The old surface was a platform-width `AlertDialog`
 *    whose body was `heightIn(max = 460.dp).verticalScroll(...)`; the platform
 *    window re-centers whenever its measured height or the IME pan changes, and
 *    every keystroke rebuilt the whole dialog body. This surface is a
 *    `usePlatformDefaultWidth = false` dialog that handles its own insets, and
 *    each field owns a local [androidx.compose.runtime.MutableState] read only
 *    inside [DraftTextField] — a keystroke invalidates that field (plus the
 *    validation of the action row), not the page.
 * 2. **No simple mode.** Adding starts at the vendor chips; choosing one fills
 *    protocol/base URL/default model from [VendorPresets] and leaves only the
 *    API key on screen. Everything else is behind 高级选项.
 *
 * `onTest` still routes through `ProviderSection`'s `pendingNewId`, so
 * 测试连接 followed by 保存 upserts exactly one row.
 */
@Composable
internal fun ProviderEditorDialog(
    initial: ProviderProfile?,
    onDismiss: () -> Unit,
    onSave: (ProviderDraft) -> Unit,
    onTest: suspend (ProviderDraft) -> Outcome<String>,
) {
    val state = remember(initial?.id?.raw) { ProviderEditorState(initial) }
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestOutcome?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // One inset owner: safeContent = system bars + cutout + IME.
                    // The page no longer gets panned/re-centred under the keyboard.
                    .windowInsetsPadding(WindowInsets.safeContent),
            ) {
                ProviderEditorHeader(
                    title = if (state.isNew) "新增服务" else "编辑服务",
                    subtitle = if (state.isNew) "选择厂商后只需填写 API Key" else null,
                    advanced = state.advanced,
                    onToggleAdvanced = { state.toggleAdvanced() },
                    onDismiss = onDismiss,
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (state.isNew) {
                        HintText("先选一个厂商，地址和默认模型会自动填好。")
                    }
                    Text(
                        text = "厂商",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    VendorChipGrid(
                        selectedId = state.selectedVendorId,
                        onSelect = { state.selectVendor(it) },
                    )

                    val vendor = state.vendor
                    if (state.advanced) {
                        AdvancedFields(state)
                        ApiKeySection(state, vendor)
                    } else if (vendor != null) {
                        Text(
                            text = vendor.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        HintText("地址与默认模型已自动填好（${vendor.defaultModelId}），只需粘贴 API Key。")
                        ApiKeySection(state, vendor)
                    }

                    testResult?.let { result ->
                        Text(
                            text = when (result) {
                                is TestOutcome.Success -> result.message
                                is TestOutcome.Failure -> "连接失败：${result.message}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result is TestOutcome.Success) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    HintText("测试连接会先保存当前配置。")
                }

                ProviderEditorActions(
                    state = state,
                    testing = testing,
                    onTest = {
                        testing = true
                        testResult = null
                        scope.launch {
                            val result = onTest(state.draft())
                            testResult = when (result) {
                                is Outcome.Ok -> TestOutcome.Success(result.value)
                                is Outcome.Err -> TestOutcome.Failure(result.error.message)
                            }
                            testing = false
                        }
                    },
                    onDismiss = onDismiss,
                    onSave = { onSave(state.draft()) },
                )
            }
        }
    }
}

/**
 * Editor state, kept out of the composition so passing it around never rebuilds
 * a draft object. Field values are [androidx.compose.runtime.MutableState] so
 * only the field that reads them recomposes.
 */
@Stable
internal class ProviderEditorState(initial: ProviderProfile?) {
    val isNew: Boolean = initial == null

    val name = mutableStateOf(initial?.name.orEmpty())
    val baseUrl = mutableStateOf(initial?.baseUrl.orEmpty())
    val modelId = mutableStateOf(initial?.defaultModelId?.raw.orEmpty())
    val apiKey = mutableStateOf("")

    var protocol by mutableStateOf(initial?.protocol ?: ProviderProtocol.OPENAI_COMPATIBLE)

    /** `null` = nothing picked yet; [VendorPresets.CUSTOM_ID] = 自定义. */
    var selectedVendorId by mutableStateOf(
        if (initial == null) null else VendorPresets.match(initial)?.id ?: VendorPresets.CUSTOM_ID,
    )

    /** Editing opens the full form; a custom vendor does too. */
    var advanced by mutableStateOf(initial != null || selectedVendorId == VendorPresets.CUSTOM_ID)

    val vendor: VendorPreset?
        get() = VendorPresets.byId(selectedVendorId)

    /**
     * Opening the full form without a chip selected means the user is going the
     * custom route, so the 自定义 chip is marked selected too.
     */
    fun toggleAdvanced() {
        advanced = !advanced
        if (advanced && selectedVendorId == null) {
            selectedVendorId = VendorPresets.CUSTOM_ID
        }
    }

    /**
     * Picking a vendor fills every field the simple flow promises, from the
     * same [VendorPreset.toDraft] mapping the unit tests cover.
     */
    fun selectVendor(preset: VendorPreset) {
        val previousName = vendor?.displayName
        val changedVendor = selectedVendorId != preset.id
        selectedVendorId = preset.id
        if (changedVendor) apiKey.value = ""
        if (preset.id == VendorPresets.CUSTOM_ID) {
            advanced = true
            return
        }
        val draft = preset.toDraft()
        protocol = draft.protocol
        baseUrl.value = draft.baseUrl
        modelId.value = draft.defaultModelId
        if (isNew || name.value.isBlank() || name.value == previousName) {
            name.value = draft.name
        }
        if (isNew) advanced = false
    }

    /**
     * Draft used for validation. Deliberately does not read [apiKey], so typing
     * a key cannot invalidate the action row (the laggy path).
     */
    fun validationDraft(): ProviderDraft = ProviderDraft(
        name = name.value.trim(),
        protocol = protocol,
        baseUrl = baseUrl.value.trim(),
        apiKey = "",
        defaultModelId = modelId.value.trim(),
    )

    fun draft(): ProviderDraft = validationDraft().copy(apiKey = apiKey.value)
}

@Composable
private fun ProviderEditorHeader(
    title: String,
    subtitle: String?,
    advanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            BackIcon(
                modifier = Modifier.iconSize(20.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.xs),
        ) {
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
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatColors.current.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onToggleAdvanced) {
            Text(if (advanced) "收起高级" else "高级选项")
        }
    }
    HorizontalDivider(color = LocalChatColors.current.divider)
}

@Composable
private fun AdvancedFields(state: ProviderEditorState) {
    var protocolMenu by remember { mutableStateOf(false) }

    DraftTextField(value = state.name, label = "名称")
    Box {
        OutlinedButton(
            onClick = { protocolMenu = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = protocolLabel(state.protocol),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            DownIcon(modifier = Modifier.iconSize(16.dp), contentDescription = null)
        }
        DropdownMenu(expanded = protocolMenu, onDismissRequest = { protocolMenu = false }) {
            ProviderProtocol.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(protocolLabel(candidate)) },
                    onClick = {
                        state.protocol = candidate
                        protocolMenu = false
                    },
                )
            }
        }
    }
    DraftTextField(
        value = state.baseUrl,
        label = "Base URL",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        placeholder = { Text("https://api.example.com/v1") },
    )
    DraftTextField(
        value = state.modelId,
        label = "默认模型 id",
        placeholder = { Text("如 gpt-4o-mini") },
    )
}

@Composable
private fun ApiKeySection(state: ProviderEditorState, vendor: VendorPreset?) {
    ProviderKeyField(
        apiKey = state.apiKey,
        hint = vendor?.keyHint ?: "API Key 只写入本机密钥库，不落数据库",
        consoleUrl = vendor?.consoleUrl,
    )
}

/**
 * Bottom bar. It reads validation in its own scope, so editing an advanced
 * field invalidates the buttons — not the scroll page or the other fields.
 */
@Composable
private fun ProviderEditorActions(
    state: ProviderEditorState,
    testing: Boolean,
    onTest: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val validation by remember(state) {
        derivedStateOf { validateProviderDraft(state.validationDraft()) }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        val missingHint = when {
            state.vendor == null && !state.advanced ->
                "请选择厂商，或打开右上角「高级选项」手动填写。"
            !validation.isValid -> "请填写名称、Base URL 和默认模型 id。"
            else -> null
        }
        missingHint?.let { hint ->
            HintText(
                text = hint,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }
        validation.protocolWarning?.let { warning ->
            HintText(
                text = warning,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedButton(
                onClick = onTest,
                enabled = validation.isValid && !testing,
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(if (testing) "测试中…" else "测试连接")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(onClick = onSave, enabled = validation.isValid) { Text("保存") }
        }
    }
}

private sealed interface TestOutcome {
    data class Success(val message: String) : TestOutcome
    data class Failure(val message: String) : TestOutcome
}
