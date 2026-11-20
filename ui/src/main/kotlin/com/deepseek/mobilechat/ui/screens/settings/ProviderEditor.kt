@file:OptIn(ExperimentalMaterial3Api::class)

package com.deepseek.mobilechat.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.ProviderProtocol
import com.deepseek.mobilechat.ui.components.DownIcon
import com.deepseek.mobilechat.ui.components.iconSize
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.screens.common.HintText
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Provider editor dialog plus the test-connection feedback states. */

@Composable
internal fun ProviderEditorDialog(
    initial: ProviderProfile?,
    onDismiss: () -> Unit,
    onSave: (ProviderDraft) -> Unit,
    onTest: suspend (ProviderDraft) -> Outcome<String>,
) {
    val scope = rememberCoroutineScope()
    val key = initial?.id?.raw ?: "new"
    var name by remember(key) { mutableStateOf(initial?.name ?: "") }
    var protocol by remember(key) { mutableStateOf(initial?.protocol ?: ProviderProtocol.OPENAI_COMPATIBLE) }
    var baseUrl by remember(key) { mutableStateOf(initial?.baseUrl ?: "") }
    var apiKey by remember(key) { mutableStateOf("") }
    var modelId by remember(key) { mutableStateOf(initial?.defaultModelId?.raw ?: "") }
    var protocolMenu by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestOutcome?>(null) }

    fun currentDraft() = ProviderDraft(
        name = name.trim(),
        protocol = protocol,
        baseUrl = baseUrl.trim(),
        apiKey = apiKey,
        defaultModelId = modelId.trim(),
    )

    val valid = name.isNotBlank() && baseUrl.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增模型服务" else "编辑模型服务", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
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
                Box {
                    OutlinedButton(
                        onClick = { protocolMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = protocolLabel(protocol),
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
                                    protocol = candidate
                                    protocolMenu = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text(if (initial == null) "填入密钥" else "留空表示不修改") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("默认模型 id") },
                    placeholder = { Text("如 gpt-4o-mini") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                val result = onTest(currentDraft())
                                testResult = when (result) {
                                    is Outcome.Ok -> TestOutcome.Success(result.value)
                                    is Outcome.Err -> TestOutcome.Failure(result.error.message)
                                }
                                testing = false
                            }
                        },
                        enabled = valid && !testing,
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(Spacing.sm))
                        }
                        Text(if (testing) "测试中…" else "测试连接")
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    HintText("测试会先保存当前配置")
                }
                AnimatedVisibility(
                    visible = testResult != null,
                    enter = Motion.enterFromTop(),
                    exit = Motion.exitToTop(),
                ) {
                    val result = testResult
                    if (result != null) {
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
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(currentDraft()) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private sealed interface TestOutcome {
    data class Success(val message: String) : TestOutcome
    data class Failure(val message: String) : TestOutcome
}
