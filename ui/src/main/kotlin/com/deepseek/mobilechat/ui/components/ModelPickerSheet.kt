package com.deepseek.mobilechat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.domain.ModelDescriptor
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.ProviderProtocol
import com.deepseek.mobilechat.ui.AppServices
import com.deepseek.mobilechat.ui.motion.pressable
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.ShapeTokens
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Model picker (spec §4: the model chip opens a choice panel).
 *
 * Providers come from the store; each provider's model list is fetched lazily
 * when the panel opens. A provider that cannot be reached still appears, with
 * its configured default model — an offline provider must not make the list
 * look empty or the picker unusable.
 */
@Composable
fun ModelPickerSheetContent(
    services: AppServices,
    currentRef: ModelRef?,
    onSelect: (ModelRef, String) -> Unit,
    onManageProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    val providers by services.providers.observe()
        .collectAsStateWithLifecycle(initialValue = emptyList<ProviderProfile>())

    var models by remember { mutableStateOf<Map<ProviderId, List<ModelDescriptor>>>(emptyMap()) }
    var failed by remember { mutableStateOf<Set<ProviderId>>(emptySet()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(providers) {
        if (providers.isEmpty()) return@LaunchedEffect
        loading = true
        val results = coroutineScope {
            providers.map { profile ->
                async {
                    val outcome = services.providers.listModels(profile)
                    profile.id to outcome
                }
            }.awaitAll()
        }
        val ok = LinkedHashMap<ProviderId, List<ModelDescriptor>>()
        val bad = LinkedHashSet<ProviderId>()
        results.forEach { (id, outcome) ->
            when (outcome) {
                is Outcome.Ok -> ok[id] = outcome.value
                is Outcome.Err -> {
                    bad += id
                    val fallback = providers.firstOrNull { it.id == id }?.defaultModelId
                    if (fallback != null) {
                        ok[id] = listOf(ModelDescriptor(id = fallback, displayName = fallback.raw))
                    }
                }
            }
        }
        models = ok
        failed = bad
        loading = false
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "选择模型",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
        Text(
            text = "只对这个会话生效，随时可以再换",
            style = MaterialTheme.typography.labelSmall,
            color = chatColors.mutedText,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xxs),
        )
        Spacer(modifier = Modifier.height(Spacing.sm))

        when {
            providers.isEmpty() -> EmptyProvidersCallout(onManageProviders = onManageProviders)

            loading && models.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                providers.forEach { profile ->
                    item(key = "header-${profile.id.raw}") {
                        ProviderHeader(
                            profile = profile,
                            unreachable = profile.id in failed,
                        )
                    }
                    val list = models[profile.id].orEmpty()
                    if (list.isEmpty()) {
                        item(key = "empty-${profile.id.raw}") {
                            Text(
                                text = "这个服务还没有可用的模型",
                                style = MaterialTheme.typography.labelSmall,
                                color = chatColors.mutedText,
                                modifier = Modifier.padding(
                                    start = Spacing.lg + Spacing.xxl,
                                    end = Spacing.lg,
                                    bottom = Spacing.sm,
                                ),
                            )
                        }
                    } else {
                        items(list, key = { "${profile.id.raw}/${it.id.raw}" }) { descriptor ->
                            ModelRow(
                                providerName = profile.name,
                                protocol = profile.protocol,
                                descriptor = descriptor,
                                selected = currentRef?.providerId == profile.id &&
                                    currentRef.modelId == descriptor.id,
                                onClick = { onSelect(ModelRef(profile.id, descriptor.id), descriptor.displayName) },
                            )
                        }
                    }
                }
                item(key = "manage") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressable(onClick = onManageProviders)
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Text(
                            text = "在设置中管理模型服务",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                item(key = "bottom-space") { Spacer(modifier = Modifier.height(Spacing.md)) }
            }
        }
    }
}

@Composable
private fun ProviderHeader(profile: ProviderProfile, unreachable: Boolean) {
    val chatColors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        BrandAvatar(protocol = profile.protocol, name = profile.name, size = 18.dp)
        Text(
            text = profile.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = when {
                unreachable -> "离线 · 用默认模型"
                else -> protocolLabel(profile.protocol)
            },
            style = MaterialTheme.typography.labelSmall,
            color = chatColors.mutedText,
        )
    }
}

@Composable
private fun ModelRow(
    providerName: String,
    protocol: ProviderProtocol,
    descriptor: ModelDescriptor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val chatColors = LocalChatColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = 2.dp)
            .pressable(onClick = onClick),
        shape = ShapeTokens.Card,
        color = if (selected) chatColors.accentSoft else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            BrandAvatar(protocol = protocol, name = providerName, size = 22.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = descriptor.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = descriptor.id.raw,
                    style = MaterialTheme.typography.labelSmall,
                    color = chatColors.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                CheckIcon(
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                    contentDescription = "当前模型",
                )
            }
        }
    }
}

@Composable
private fun EmptyProvidersCallout(onManageProviders: () -> Unit) {
    val chatColors = LocalChatColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        shape = ShapeTokens.Card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, chatColors.hairline),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = "还没有配置模型服务",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "添加一个服务并填入 API Key，就能在这里选择模型。",
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            Row(modifier = Modifier.padding(top = Spacing.md)) {
                PillButton(label = "去设置", onClick = onManageProviders, primary = true)
            }
        }
    }
}

/** Protocol → label. Mirrors the settings screen's wording, kept local to :ui. */
internal fun protocolLabel(protocol: ProviderProtocol): String = when (protocol) {
    ProviderProtocol.OPENAI_COMPATIBLE -> "OpenAI 兼容"
    ProviderProtocol.ANTHROPIC -> "Anthropic"
    ProviderProtocol.GEMINI -> "Gemini"
}
