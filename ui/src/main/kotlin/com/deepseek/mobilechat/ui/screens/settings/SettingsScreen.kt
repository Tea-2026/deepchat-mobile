package com.deepseek.mobilechat.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseek.mobilechat.application.AppSettings
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.ui.AppServices
import com.deepseek.mobilechat.ui.components.ChevronRightIcon
import com.deepseek.mobilechat.ui.components.DownIcon
import com.deepseek.mobilechat.ui.components.iconSize
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.animatedEnter
import com.deepseek.mobilechat.ui.screens.common.HintText
import com.deepseek.mobilechat.ui.screens.common.InfoCallout
import com.deepseek.mobilechat.ui.screens.common.ScreenTopBar
import com.deepseek.mobilechat.ui.screens.common.TransferGlyph
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Settings: a grouped list (reference P4) that drills into second-level pages.
 *
 * The screen keeps its own "list <-> sub-page" state instead of leaning on
 * `:app`'s navigation — the settings stack is momentary UI, and pushing four
 * routes for it would put transient state into the back stack. Every
 * second-level page reuses the section composables that already own the
 * business logic; this file only arranges them, so no persistence rule is
 * duplicated.
 */
@Composable
fun SettingsScreen(
    services: AppServices,
    onBack: () -> Unit,
    onOpenTransfer: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val providers by services.providers.observe()
        .collectAsStateWithLifecycle(initialValue = emptyList<ProviderProfile>())
    val presets by services.presets.observe()
        .collectAsStateWithLifecycle(initialValue = emptyList<Preset>())

    val capability = remember(services) { services.settingsCapability() }
    val settingsFlow = remember(capability) {
        capability?.settingsStore?.observe()?.map<AppSettings, AppSettings?> { it } ?: flowOf<AppSettings?>(null)
    }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
    val clock = services.clock
    val nowMillis = remember(clock) { { clock.nowMillis() } }

    val notify: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }

    // Storage usage is computed off the main thread once, then on demand when
    // the row is tapped. Until the first read lands the row says 计算中.
    var storageBytes by remember { mutableStateOf<Long?>(null) }
    var storageReady by remember { mutableStateOf(false) }
    suspend fun measureStorage() {
        storageBytes = readDatabaseBytes(context)
        storageReady = true
    }
    LaunchedEffect(context) { measureStorage() }

    val versionName = remember(context) { appVersionName(context) }

    // `null` = the root list; a leaf name = a second-level page. Saved so a
    // configuration change does not throw the user back to the list.
    var leafName by rememberSaveable { mutableStateOf<String?>(null) }
    val page = leafName?.let { name -> SettingsLeaf.entries.firstOrNull { it.name == name } }

    val cycleTheme: () -> Unit = {
        val cap = capability
        val current = settings
        when {
            cap == null -> notify("当前构建未提供设置存储，外观暂不可改")
            current == null -> notify("正在读取设置…")
            else -> {
                val next = nextThemeMode(current.themeMode)
                scope.launch {
                    runCatching { cap.settingsStore.update(current.copy(themeMode = next)) }
                        .onSuccess { notify("外观已切换为 ${themeModeLabel(next)}") }
                        .onFailure { notify(it.message ?: "保存外观设置失败") }
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ScreenTopBar(
                title = page?.title ?: "设置",
                subtitle = page?.subtitle ?: "${providers.size} 个服务 · ${presets.size} 个预设",
                onBack = {
                    if (page != null) leafName = null else onBack()
                },
                actions = {
                    if (page == null) {
                        IconButton(onClick = onOpenTransfer) {
                            TransferGlyph(
                                modifier = Modifier.iconSize(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AnimatedContent(
                targetState = page,
                // Depth decides direction: drilling in travels left, coming
                // back travels right, so the list keeps its spatial memory.
                transitionSpec = {
                    if (targetState == null) Motion.horizontalTravel(-1) else Motion.horizontalTravel(1)
                },
                label = "settingsPage",
                modifier = Modifier.fillMaxSize(),
            ) { current ->
                if (current == null) {
                    SettingsRootPage(
                        providers = providers,
                        presets = presets,
                        settings = settings,
                        capability = capability,
                        storageLabel = when {
                            !storageReady -> "计算中"
                            storageBytes == null -> "不可用"
                            else -> formatStorageBytes(storageBytes ?: 0L)
                        },
                        versionName = versionName,
                        onOpenLeaf = { leaf -> leafName = leaf.name },
                        onOpenTransfer = onOpenTransfer,
                        onCycleTheme = cycleTheme,
                        onRefreshStorage = {
                            storageReady = false
                            scope.launch { measureStorage() }
                        },
                    )
                } else {
                    SettingsLeafPage(
                        leaf = current,
                        services = services,
                        capability = capability,
                        settings = settings,
                        providers = providers,
                        presets = presets,
                        nowMillis = nowMillis,
                        onMessage = notify,
                    )
                }
            }
        }
    }
}

/** Second-level pages reachable from the root list. */
private enum class SettingsLeaf(val title: String, val subtitle: String) {
    Providers(title = "模型服务", subtitle = "协议、地址与默认模型"),
    Presets(title = "预设", subtitle = "提示词、模型与采样参数"),
    Search(title = "联网搜索", subtitle = "搜索源与 API Key"),
    Generation(title = "生成", subtitle = "同时进行的生成数量"),
    Licenses(title = "开源许可", subtitle = "本应用使用的第三方组件"),
}

// ---------------------------------------------------------------------------
// Root list
// ---------------------------------------------------------------------------

@Composable
private fun SettingsRootPage(
    providers: List<ProviderProfile>,
    presets: List<Preset>,
    settings: AppSettings?,
    capability: SettingsCapability?,
    storageLabel: String,
    versionName: String?,
    onOpenLeaf: (SettingsLeaf) -> Unit,
    onOpenTransfer: () -> Unit,
    onCycleTheme: () -> Unit,
    onRefreshStorage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .animatedEnter(offsetY = 6.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SettingsGroup(
            title = "模型",
            items = listOf(
                SettingsItem(
                    icon = SettingsIcon.Model,
                    title = "模型服务",
                    value = "${providers.size} 个",
                    onClick = { onOpenLeaf(SettingsLeaf.Providers) },
                ),
                SettingsItem(
                    icon = SettingsIcon.Preset,
                    title = "预设",
                    value = "${presets.size} 个",
                    onClick = { onOpenLeaf(SettingsLeaf.Presets) },
                ),
            ),
        )

        SettingsGroup(
            title = "工具",
            items = listOf(
                SettingsItem(
                    icon = SettingsIcon.Search,
                    title = "联网搜索",
                    value = settings?.activeSearchProvider?.let(::searchProviderLabel) ?: "读取中",
                    onClick = { onOpenLeaf(SettingsLeaf.Search) },
                ),
                SettingsItem(
                    icon = SettingsIcon.Generation,
                    title = "生成",
                    value = settings?.let { "并发 ${it.maxConcurrentGenerations}" } ?: "读取中",
                    onClick = { onOpenLeaf(SettingsLeaf.Generation) },
                ),
            ),
        )

        SettingsGroup(
            title = "数据",
            items = listOf(
                SettingsItem(
                    icon = SettingsIcon.Transfer,
                    title = "导出 / 导入",
                    onClick = onOpenTransfer,
                ),
                SettingsItem(
                    icon = SettingsIcon.Storage,
                    title = "存储占用",
                    value = storageLabel,
                    onClick = onRefreshStorage,
                ),
            ),
        )

        SettingsGroup(
            title = "通用",
            items = listOf(
                SettingsItem(
                    icon = SettingsIcon.Appearance,
                    title = "外观",
                    value = themeModeLabel(settings?.themeMode),
                    onClick = onCycleTheme,
                ),
            ),
        )

        SettingsGroup(
            title = "关于",
            items = listOf(
                SettingsItem(
                    icon = SettingsIcon.Version,
                    title = "版本号",
                    value = versionName ?: "未知",
                ),
                SettingsItem(
                    icon = SettingsIcon.License,
                    title = "开源许可",
                    onClick = { onOpenLeaf(SettingsLeaf.Licenses) },
                ),
            ),
        )

        if (capability == null) {
            InfoCallout("当前构建未提供设置存储：外观与生成参数暂不可改。")
        }
        HintText("所有配置只保存在本机；API Key 写入系统密钥库。")
    }
}

// ---------------------------------------------------------------------------
// Second-level pages
// ---------------------------------------------------------------------------

@Composable
private fun SettingsLeafPage(
    leaf: SettingsLeaf,
    services: AppServices,
    capability: SettingsCapability?,
    settings: AppSettings?,
    providers: List<ProviderProfile>,
    presets: List<Preset>,
    nowMillis: () -> Long,
    onMessage: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .animatedEnter(key = leaf, offsetY = 6.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        when (leaf) {
            SettingsLeaf.Providers -> {
                ProviderSection(
                    services = services,
                    providers = providers,
                    nowMillis = nowMillis,
                    onMessage = onMessage,
                    title = "服务列表",
                )
                HintText("支持 OpenAI 兼容、Anthropic、Gemini 三种协议；API Key 只写入本机密钥库。")
            }

            SettingsLeaf.Presets -> {
                PresetSection(
                    services = services,
                    presets = presets,
                    providers = providers,
                    onMessage = onMessage,
                    title = "预设列表",
                )
                HintText("预设是一套可复用的提示词与参数；设为默认后会用于新建的会话。")
            }

            SettingsLeaf.Search -> SearchSourceSection(
                capability = capability,
                settings = settings,
                onMessage = onMessage,
            )

            SettingsLeaf.Generation -> GenerationSection(
                capability = capability,
                settings = settings,
                onMessage = onMessage,
            )

            SettingsLeaf.Licenses -> LicensesPage()
        }
    }
}

@Composable
private fun LicensesPage() {
    SettingsGroup(
        title = "第三方组件",
        items = listOf(
            SettingsItem(SettingsIcon.License, "AndroidX / Jetpack Compose", "Apache-2.0"),
            SettingsItem(SettingsIcon.License, "Kotlin 与 kotlinx.coroutines", "Apache-2.0"),
            SettingsItem(SettingsIcon.License, "SQLite", "Public Domain"),
            SettingsItem(SettingsIcon.License, "OkHttp", "Apache-2.0"),
        ),
    )
    HintText("以上组件按其各自的许可协议分发；许可正文随安装包一同提供。")
}

// ---------------------------------------------------------------------------
// Grouped list primitives
// ---------------------------------------------------------------------------

/** One row of a group: colour-washed glyph, title, current value, chevron. */
private data class SettingsItem(
    val icon: SettingsIcon,
    val title: String,
    val value: String? = null,
    val onClick: (() -> Unit)? = null,
)

/**
 * A collapsible group: small caption, then a rounded surface of rows.
 *
 * Height is animated by `animateContentSize` (the house accordion motion comes
 * from the shared [Motion] tokens, never a literal duration).
 */
@Composable
private fun SettingsGroup(title: String, items: List<SettingsItem>) {
    val chatColors = LocalChatColors.current
    var expanded by rememberSaveable(title) { mutableStateOf(true) }
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(Motion.Durations.Short, easing = Motion.Easings.Standard),
        label = "settingsGroupChevron",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = chatColors.mutedText,
                modifier = Modifier.weight(1f),
            )
            DownIcon(
                modifier = Modifier
                    .iconSize(14.dp)
                    .graphicsLayer { rotationZ = chevron },
                tint = chatColors.mutedText,
                contentDescription = null,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = Motion.enter(),
            exit = Motion.exit(),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xxs),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column {
                    items.forEachIndexed { index, item ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp),
                                thickness = 1.dp,
                                color = chatColors.divider,
                            )
                        }
                        SettingsRow(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(item: SettingsItem) {
    val chatColors = LocalChatColors.current
    val onClick = item.onClick
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(30.dp),
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SettingsIconGlyph(
                    icon = item.icon,
                    modifier = Modifier.iconSize(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (item.value != null) {
            Text(
                text = item.value,
                style = MaterialTheme.typography.bodyMedium,
                color = chatColors.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(Spacing.xs))
            ChevronRightIcon(
                modifier = Modifier.iconSize(16.dp),
                tint = chatColors.mutedText.copy(alpha = 0.6f),
                contentDescription = null,
            )
        }
    }
}
