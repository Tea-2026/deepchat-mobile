package com.deepseek.mobilechat.ui.screens.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.ui.AppServices
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.screens.common.HintText
import com.deepseek.mobilechat.ui.screens.common.InfoCallout
import com.deepseek.mobilechat.ui.screens.settings.ProviderKeyField
import com.deepseek.mobilechat.ui.screens.settings.VendorChipGrid
import com.deepseek.mobilechat.ui.screens.settings.VendorPreset
import com.deepseek.mobilechat.ui.screens.settings.VendorPresets
import com.deepseek.mobilechat.ui.screens.settings.localId
import com.deepseek.mobilechat.ui.screens.settings.normalizeBaseUrl
import com.deepseek.mobilechat.ui.screens.settings.providerSecretRef
import com.deepseek.mobilechat.ui.screens.settings.settingsCapability
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * First-run guide: vendor → API key → test → start chatting.
 *
 * The composition root owns the route and the call site (this file deliberately
 * has no navigation). It only needs:
 * ```
 * FirstRunGuide(services = services, onFinished = { ... })
 * ```
 *
 * Finishing or pressing 「稍后再说」 writes `AppSettings.firstRunCompleted = true`
 * through [settingsCapability] when the build provides it. Without the
 * capability the guide still works (the provider is still saved) and says so in
 * an [InfoCallout] instead of leaving a disabled control.
 *
 * Safe to re-run: if a provider for the chosen vendor already exists it is
 * reused (same id), so running the guide twice never creates a duplicate row.
 */
@Composable
fun FirstRunGuide(
    services: AppServices,
    onFinished: () -> Unit,
) {
    val capability = remember(services) { services.settingsCapability() }
    val providers by services.providers.observe()
        .collectAsStateWithLifecycle(initialValue = emptyList<ProviderProfile>())
    val state = remember { FirstRunGuideState() }
    val scope = rememberCoroutineScope()
    // Minted once; test-then-finish upserts the same id, never two rows.
    val pendingNewId = remember { localId("provider") }

    fun profileFor(vendor: VendorPreset): ProviderProfile {
        val existing = providers.firstOrNull {
            it.protocol == vendor.protocol &&
                normalizeBaseUrl(it.baseUrl) == normalizeBaseUrl(vendor.baseUrl)
        }
        val id = existing?.id ?: ProviderId(pendingNewId)
        return ProviderProfile(
            id = id,
            name = existing?.name ?: vendor.displayName,
            protocol = vendor.protocol,
            baseUrl = vendor.baseUrl,
            secretRef = existing?.secretRef?.takeIf { it.isNotBlank() } ?: providerSecretRef(id),
            defaultModelId = ModelId(vendor.defaultModelId),
            createdAt = existing?.createdAt ?: services.clock.nowMillis(),
        )
    }

    /** Saves the provider and returns an error message, or null on success. */
    suspend fun persist(vendor: VendorPreset): String? {
        val key = state.apiKey.value.trim()
        if (key.isEmpty()) return "请先填写 API Key"
        val result = services.providers.upsert(profileFor(vendor), key)
        return (result as? Outcome.Err)?.error?.message
    }

    suspend fun markFinished() {
        val cap = capability ?: return
        runCatching {
            val current = cap.settingsStore.observe().first()
            if (!current.firstRunCompleted) {
                cap.settingsStore.update(current.copy(firstRunCompleted = true))
            }
        }
    }

    fun finish(skip: Boolean) {
        if (state.busy) return
        state.busy = true
        state.error = null
        scope.launch {
            val vendor = state.vendor
            if (!skip && vendor != null) {
                val error = persist(vendor)
                if (error != null) {
                    state.error = error
                    state.busy = false
                    return@launch
                }
            }
            markFinished()
            state.busy = false
            onFinished()
        }
    }

    fun testAndContinue() {
        val vendor = state.vendor ?: return
        if (state.busy) return
        state.busy = true
        state.testing = true
        state.error = null
        state.testMessage = null
        scope.launch {
            val saveError = persist(vendor)
            if (saveError != null) {
                state.error = saveError
                state.testing = false
                state.busy = false
                return@launch
            }
            when (val result = services.providers.test(profileFor(vendor))) {
                is Outcome.Ok -> {
                    state.testOk = true
                    state.testMessage = "连接成功"
                    state.step = 2
                }
                is Outcome.Err -> {
                    state.testOk = false
                    state.testMessage = "连接失败：${result.error.message}"
                }
            }
            state.testing = false
            state.busy = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Same single inset owner as the provider editor: no IME pan,
                // the content viewport shrinks and the key field stays visible.
                .windowInsetsPadding(WindowInsets.safeContent),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "快速开始",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { finish(skip = true) },
                    enabled = !state.busy,
                ) { Text("稍后再说") }
            }
            HorizontalDivider(color = LocalChatColors.current.divider)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                StepIndicator(step = state.step)
                AnimatedContent(
                    targetState = state.step,
                    transitionSpec = { Motion.morph() },
                    label = "firstRunStep",
                ) { step ->
                    when (step) {
                        0 -> VendorStep(state)
                        1 -> KeyStep(state)
                        else -> ReadyStep(state)
                    }
                }

                if (capability == null) {
                    InfoCallout(
                        "当前构建未提供设置存储：完成状态不会被记住，下次可能再次看到引导。" +
                            "服务本身仍会照常保存。",
                    )
                }
                state.error?.let { message ->
                    InfoCallout(message, error = true)
                }
            }

            HorizontalDivider(color = LocalChatColors.current.divider)
            GuideFooter(
                state = state,
                onBack = { state.step = (state.step - 1).coerceAtLeast(0) },
                onSkipTest = { state.step = 2 },
                onPrimary = {
                    when (state.step) {
                        0 -> state.step = 1
                        1 -> testAndContinue()
                        else -> finish(skip = false)
                    }
                },
            )
        }
    }
}

@Stable
private class FirstRunGuideState {
    var step by mutableIntStateOf(0)
    var vendorId by mutableStateOf<String?>(null)
    val apiKey = mutableStateOf("")
    var busy by mutableStateOf(false)
    var testing by mutableStateOf(false)
    var testMessage by mutableStateOf<String?>(null)
    var testOk by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    val vendor: VendorPreset?
        get() = VendorPresets.byId(vendorId)
}

@Composable
private fun StepIndicator(step: Int) {
    val labels = listOf("选择服务", "填写密钥", "开始聊天")
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val active = index <= step
            Surface(
                shape = CircleShape,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    LocalChatColors.current.mutedText
                },
            )
        }
    }
}

@Composable
private fun VendorStep(state: FirstRunGuideState) {
    Text(
        text = "先选一个服务商",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    HintText("选好后只需要粘贴 API Key，地址和默认模型会自动填好。")
    VendorChipGrid(
        selectedId = state.vendorId,
        onSelect = { preset ->
            if (state.vendorId != preset.id) state.apiKey.value = ""
            state.vendorId = preset.id
        },
        includeCustom = false,
    )
    HintText("想接别的服务？可以先去设置里的「服务列表 → 新增」使用自定义。")
}

@Composable
private fun KeyStep(state: FirstRunGuideState) {
    val vendor = state.vendor ?: return
    Text(
        text = "填写 ${vendor.displayName} 的 Key",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    HintText("默认模型 ${vendor.defaultModelId} · ${vendor.baseUrl}")
    ProviderKeyField(
        apiKey = state.apiKey,
        hint = vendor.keyHint,
        consoleUrl = vendor.consoleUrl,
    )
    state.testMessage?.let { message ->
        InfoCallout(message, error = !state.testOk)
    }
    HintText("测试连接会先保存当前配置；失败也可以先跳过测试。")
}

@Composable
private fun ReadyStep(state: FirstRunGuideState) {
    val vendor = state.vendor
    Text(
        text = "可以开始聊天了",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (vendor != null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                Text(
                    text = vendor.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                HintText("默认模型 ${vendor.defaultModelId}")
                if (state.testOk) {
                    HintText("连接测试已通过")
                }
            }
        }
    }
    HintText("之后随时可以在「设置 → 服务列表」里新增、修改或删除服务。")
}

@Composable
private fun GuideFooter(
    state: FirstRunGuideState,
    onBack: () -> Unit,
    onSkipTest: () -> Unit,
    onPrimary: () -> Unit,
) {
    // Reads field state in this row's own scope: a keystroke invalidates the
    // footer, not the guide page or the key field.
    val primaryEnabled by remember(state) {
        derivedStateOf {
            state.vendor != null && (state.step == 0 || state.apiKey.value.isNotBlank())
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (state.step > 0) {
            OutlinedButton(onClick = onBack, enabled = !state.busy) { Text("上一步") }
        }
        Spacer(Modifier.weight(1f))
        if (state.step == 1) {
            TextButton(
                onClick = onSkipTest,
                enabled = primaryEnabled && !state.busy,
            ) { Text("跳过测试") }
        }
        Button(
            onClick = onPrimary,
            enabled = primaryEnabled && !state.busy,
        ) {
            if (state.testing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(Spacing.sm))
            }
            Text(
                when {
                    state.testing -> "测试中…"
                    state.step == 0 -> "下一步"
                    state.step == 1 -> "测试并继续"
                    else -> "开始聊天"
                },
            )
        }
    }
}
