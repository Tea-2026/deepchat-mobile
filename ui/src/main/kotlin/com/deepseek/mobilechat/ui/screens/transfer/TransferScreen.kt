@file:OptIn(ExperimentalMaterial3Api::class)

package com.deepseek.mobilechat.ui.screens.transfer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.deepseek.mobilechat.application.ExportFormat
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.ui.AppServices
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.animatedEnter
import com.deepseek.mobilechat.ui.screens.common.HintText
import com.deepseek.mobilechat.ui.screens.common.InfoCallout
import com.deepseek.mobilechat.ui.screens.common.ScreenTopBar
import com.deepseek.mobilechat.ui.screens.common.SectionCard
import com.deepseek.mobilechat.ui.screens.common.relativeTime
import com.deepseek.mobilechat.ui.screens.common.rememberNow
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class TransferJob { EXPORT, IMPORT }

/**
 * Export / import. Both directions go through SAF: the screen only hands a URI
 * plus the chosen options to [AppServices.transfer], so nothing here knows how
 * a bundle is encoded.
 */
@Composable
fun TransferScreen(
    services: AppServices,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val now = rememberNow(services.clock)
    val conversations by services.conversations.observeConversations()
        .collectAsStateWithLifecycle(initialValue = emptyList<Conversation>())

    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var formatName by rememberSaveable { mutableStateOf(ExportFormat.JSON.name) }
    var includeAttachments by rememberSaveable { mutableStateOf(true) }
    var job by remember { mutableStateOf<TransferJob?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    val format = ExportFormat.valueOf(formatName)

    // Drop selections whose conversation disappeared while the screen was open.
    LaunchedEffect(conversations) {
        val live = conversations.map { it.id.raw }.toSet()
        if (selected.any { it !in live }) selected = selected intersect live
    }

    fun startExport(targetUri: String, exportFormat: ExportFormat) {
        val ids = conversations.map { it.id }.filter { it.raw in selected }
        job = TransferJob.EXPORT
        failure = null
        result = null
        scope.launch {
            when (val outcome = services.transfer.exportConversations(ids, exportFormat, targetUri, includeAttachments)) {
                is Outcome.Ok -> {
                    val label = if (exportFormat == ExportFormat.JSON) "JSON" else "Markdown"
                    result = "已导出 ${ids.size} 个会话（$label${if (includeAttachments) "，含附件" else ""}）"
                }
                is Outcome.Err -> {
                    failure = outcome.error.message
                    snackbar.showSnackbar(outcome.error.message)
                }
            }
            job = null
        }
    }

    fun startImport(sourceUri: String) {
        job = TransferJob.IMPORT
        failure = null
        result = null
        scope.launch {
            when (val outcome = services.transfer.importConversations(sourceUri)) {
                is Outcome.Ok -> result = "已导入 ${outcome.value} 个会话"
                is Outcome.Err -> {
                    failure = outcome.error.message
                    snackbar.showSnackbar(outcome.error.message)
                }
            }
            job = null
        }
    }

    val timestamp = remember(now) {
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(services.clock.nowMillis()))
    }

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { startExport(it.toString(), ExportFormat.JSON) }
    }
    val exportMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let { startExport(it.toString(), ExportFormat.MARKDOWN) }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { startImport(it.toString()) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ScreenTopBar(
                title = "导出 / 导入",
                subtitle = "整包带走，换机或备份",
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                .animatedEnter(offsetY = 6.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SectionCard(
                title = "导出",
                subtitle = if (selected.isEmpty()) "选中要导出的会话" else "已选 ${selected.size} 个会话",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "会话",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            selected = if (selected.size == conversations.size) {
                                emptySet()
                            } else {
                                conversations.map { it.id.raw }.toSet()
                            }
                        },
                    ) {
                        Text(if (selected.size == conversations.size && conversations.isNotEmpty()) "取消全选" else "全选")
                    }
                }
                when {
                    conversations.isEmpty() -> HintText("还没有可以导出的会话。")
                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                    ) {
                        items(conversations, key = { it.id.raw }) { conversation ->
                            val checked = conversation.id.raw in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = checked,
                                        onValueChange = { isOn ->
                                            selected = if (isOn) {
                                                selected + conversation.id.raw
                                            } else {
                                                selected - conversation.id.raw
                                            }
                                        },
                                    )
                                    .padding(vertical = Spacing.xxs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = null,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = conversation.title.ifBlank { "未命名会话" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = relativeTime(now, conversation.updatedAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LocalChatColors.current.mutedText,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "格式",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(end = Spacing.sm),
                    )
                    FilterChip(
                        selected = format == ExportFormat.JSON,
                        onClick = { formatName = ExportFormat.JSON.name },
                        label = { Text("JSON") },
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    FilterChip(
                        selected = format == ExportFormat.MARKDOWN,
                        onClick = { formatName = ExportFormat.MARKDOWN.name },
                        label = { Text("Markdown") },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "包含附件",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        HintText("关闭后只导出文字，文件更小。")
                    }
                    Switch(
                        checked = includeAttachments,
                        onCheckedChange = { includeAttachments = it },
                    )
                }
                ExportButton(
                    busy = job == TransferJob.EXPORT,
                    enabled = selected.isNotEmpty() && job == null,
                    onExport = {
                        val name = "mobilechat-$timestamp" + if (format == ExportFormat.JSON) ".json" else ".md"
                        if (format == ExportFormat.JSON) {
                            exportJson.launch(name)
                        } else {
                            exportMarkdown.launch(name)
                        }
                    },
                )
            }

            SectionCard(
                title = "导入",
                subtitle = "从导出的文件恢复",
            ) {
                HintText("导入总是生成新的会话 id，不会覆盖本机已有数据。")
                ImportButton(
                    busy = job == TransferJob.IMPORT,
                    enabled = job == null,
                    onImport = {
                        importPicker.launch(arrayOf("application/json", "text/markdown", "text/plain", "application/octet-stream"))
                    },
                )
            }

            ResultCard(result = result, failure = failure, onDismiss = {
                result = null
                failure = null
            })

            HintText("导出文件里可能包含完整对话内容，请自行保管。")
        }
    }
}

@Composable
private fun ExportButton(busy: Boolean, enabled: Boolean, onExport: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onExport, enabled = enabled) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(if (busy) "正在导出…" else "导出到文件")
            }
        }
        AnimatedVisibility(visible = busy, enter = Motion.enterFromTop(), exit = Motion.exitToTop()) {
            HintText(
                text = "正在写入，请保持界面打开。",
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun ImportButton(busy: Boolean, enabled: Boolean, onImport: () -> Unit) {
    Column {
        OutlinedButton(onClick = onImport, enabled = enabled) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(if (busy) "正在导入…" else "选择文件导入")
        }
        AnimatedVisibility(visible = busy, enter = Motion.enterFromTop(), exit = Motion.exitToTop()) {
            HintText(
                text = "正在解析文件并写入数据库。",
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun ResultCard(result: String?, failure: String?, onDismiss: () -> Unit) {
    AnimatedContent(
        targetState = result to failure,
        transitionSpec = { Motion.morph() },
        label = "transferResult",
    ) { (okMessage, errorMessage) ->
        when {
            okMessage != null -> InfoCallout(okMessage)
            errorMessage != null -> Column {
                InfoCallout("失败：$errorMessage", error = true)
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
            else -> Spacer(Modifier.size(0.dp))
        }
    }
}
