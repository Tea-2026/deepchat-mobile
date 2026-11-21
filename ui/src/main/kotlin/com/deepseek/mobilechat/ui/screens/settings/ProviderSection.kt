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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.application.BalanceResult
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.ProviderProtocol
import com.deepseek.mobilechat.ui.AppServices
import com.deepseek.mobilechat.ui.components.DownIcon
import com.deepseek.mobilechat.ui.components.iconSize
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.animatedEnter
import com.deepseek.mobilechat.ui.screens.common.BalanceGlyph
import com.deepseek.mobilechat.ui.screens.common.ConfirmDialog
import com.deepseek.mobilechat.ui.screens.common.HintText
import com.deepseek.mobilechat.ui.screens.common.SectionCard
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Editable snapshot of a provider; the screen owns ids and timestamps. */
internal data class ProviderDraft(
    val name: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val apiKey: String,
    val defaultModelId: String,
)

@Composable
internal fun ProviderSection(
    services: AppServices,
    providers: List<ProviderProfile>,
    nowMillis: () -> Long,
    onMessage: (String) -> Unit,
    title: String = "模型服务",
) {
    val scope = rememberCoroutineScope()
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProviderProfile?>(null) }
    // Mints exactly one id per "new provider" editing session, so pressing
    // 测试连接 and then 保存 upserts the same row instead of creating two.
    var pendingNewId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ProviderProfile?>(null) }
    var balanceText by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var balanceHidden by remember { mutableStateOf<Set<String>>(emptySet()) }
    var balanceBusy by remember { mutableStateOf<String?>(null) }

    fun draftToProfile(draft: ProviderDraft, existing: ProviderProfile?): ProviderProfile {
        val id = existing?.id ?: ProviderId(pendingNewId ?: localId("provider"))
        return ProviderProfile(
            id = id,
            name = draft.name,
            protocol = draft.protocol,
            baseUrl = draft.baseUrl,
            secretRef = providerSecretRef(id),
            defaultModelId = draft.defaultModelId.takeIf { it.isNotBlank() }?.let { ModelId(it) },
            createdAt = existing?.createdAt ?: nowMillis(),
        )
    }

    SectionCard(
        title = title,
        subtitle = if (providers.isEmpty()) "还没有配置服务" else "${providers.size} 个服务",
        trailing = {
            TextButton(
                onClick = {
                    editing = null
                    pendingNewId = localId("provider")
                    editorOpen = true
                },
            ) { Text("新增") }
        },
    ) {
        if (providers.isEmpty()) {
            HintText("支持 OpenAI 兼容、Anthropic、Gemini 三种协议。API Key 只写入本机密钥库，不落数据库。")
        }
        providers.forEach { profile ->
            // Only the vendors whose API actually exposes a credit/balance
            // endpoint get the button: OpenRouter and DeepSeek. Predicting it
            // keeps a button that can only fail off the screen; an unexpected
            // Unsupported at runtime still hides it for the session.
            val balanceSupported = profile.protocol == ProviderProtocol.OPENAI_COMPATIBLE &&
                (
                    profile.baseUrl.contains("openrouter", ignoreCase = true) ||
                        profile.baseUrl.contains("deepseek", ignoreCase = true)
                    )
            ProviderRow(
                profile = profile,
                balance = balanceText[profile.id.raw],
                showBalance = balanceSupported && profile.id.raw !in balanceHidden,
                balanceBusy = balanceBusy == profile.id.raw,
                onEdit = {
                    editing = profile
                    pendingNewId = null
                    editorOpen = true
                },
                onBalance = {
                    scope.launch {
                        balanceBusy = profile.id.raw
                        when (val result = services.providers.balance(profile)) {
                            is BalanceResult.Available ->
                                balanceText = balanceText + (profile.id.raw to "${result.label} ${result.amount} ${result.currency}")
                            is BalanceResult.Unsupported ->
                                balanceHidden = balanceHidden + profile.id.raw
                            is BalanceResult.Failed -> onMessage(result.error.message)
                        }
                        balanceBusy = null
                    }
                },
                onDelete = { deleteTarget = profile },
                modifier = Modifier.animatedEnter(key = profile.id.raw),
            )
        }
    }

    if (editorOpen) {
        ProviderEditorDialog(
            initial = editing,
            onDismiss = { editorOpen = false },
            onSave = { draft ->
                editorOpen = false
                val existing = editing
                scope.launch {
                    when (val result = services.providers.upsert(
                        draftToProfile(draft, existing),
                        draft.apiKey.takeIf { it.isNotBlank() },
                    )) {
                        is Outcome.Ok -> onMessage(if (existing == null) "已添加服务" else "已保存服务")
                        is Outcome.Err -> onMessage(result.error.message)
                    }
                }
            },
            onTest = { draft ->
                val profile = draftToProfile(draft, editing)
                when (val saved = services.providers.upsert(profile, draft.apiKey.takeIf { it.isNotBlank() })) {
                    is Outcome.Err -> Outcome.Err(saved.error)
                    is Outcome.Ok -> services.providers.test(profile)
                }
            },
        )
    }

    deleteTarget?.let { profile ->
        ConfirmDialog(
            title = "删除服务",
            message = "「${profile.name}」及其保存的 API Key 会被删除，引用它的预设需要重新指定模型。",
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    when (val result = services.providers.delete(profile.id)) {
                        is Outcome.Ok -> onMessage("已删除服务")
                        is Outcome.Err -> onMessage(result.error.message)
                    }
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun ProviderRow(
    profile: ProviderProfile,
    balance: String?,
    showBalance: Boolean,
    balanceBusy: Boolean,
    onEdit: () -> Unit,
    onBalance: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ProtocolBadge(protocolLabel(profile.protocol))
            }
            Text(
                text = profile.baseUrl.ifBlank { "未填写 baseUrl" },
                style = MaterialTheme.typography.bodySmall,
                color = chatColors.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
            Text(
                text = profile.defaultModelId?.raw?.let { "默认模型 $it" } ?: "未设置默认模型",
                style = MaterialTheme.typography.bodySmall,
                color = chatColors.mutedText,
            )
            if (balance != null) {
                Text(
                    text = "余额 $balance",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spacing.xxs),
                )
            }
            Row(
                modifier = Modifier.padding(top = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                TextButton(onClick = onEdit) { Text("编辑") }
                if (showBalance) {
                    TextButton(onClick = onBalance, enabled = !balanceBusy) {
                        if (balanceBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(Spacing.xs))
                        } else {
                            BalanceGlyph(modifier = Modifier.iconSize(15.dp))
                            Spacer(Modifier.width(Spacing.xs))
                        }
                        Text("余额")
                    }
                }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ProtocolBadge(label: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
        )
    }
}

