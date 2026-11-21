package com.deepseek.mobilechat.ui.screens.settings

import com.deepseek.mobilechat.domain.ContextStrategyIds
import com.deepseek.mobilechat.application.SearchProviderKind
import com.deepseek.mobilechat.application.SecretStore
import com.deepseek.mobilechat.application.SettingsStore
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProtocol
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.ui.AppServices
import java.util.UUID

/**
 * The two stores the settings screen needs but [AppServices] does not carry yet.
 *
 * `AppServices` is a frozen contract owned by the composition root; adding
 * `settings` / `secrets` to it would be a one-line change there, but the UI must
 * not depend on that landing first. So the screen asks for this *optional*
 * capability at runtime: the composition root implements it on its
 * `AppServices` implementation and the sections activate; without it the
 * sections render an honest "not available in this build" notice instead of
 * silently writing nowhere.
 *
 * Wiring (composition root only):
 * ```kotlin
 * class AppServicesImpl(...) : AppServices, SettingsCapability {
 *     override val settingsStore: SettingsStore = ...
 *     override val secretStore: SecretStore = ...
 * }
 * ```
 */
interface SettingsCapability {
    val settingsStore: SettingsStore
    val secretStore: SecretStore
}

/** The capability, or `null` when this build does not provide it. */
fun AppServices.settingsCapability(): SettingsCapability? = this as? SettingsCapability

/**
 * Canonical secret refs, mirrored from the search adapters
 * (`adapter-network` `SearchAdapters.kt`). They are part of the cross-module
 * contract: writing a different ref would store a key the adapter never reads.
 */
internal const val TAVILY_SECRET_REF = "search.tavily"
internal const val BRAVE_SECRET_REF = "search.brave"
internal const val SERPER_SECRET_REF = "search.serper"

/**
 * Secret ref for a search source; empty for sources that need no credential.
 * `DEEPSEEK` reuses the chat provider's key and `BING` is keyless, so the
 * settings UI must not render a key field for either.
 */
internal fun searchSecretRef(kind: SearchProviderKind): String = when (kind) {
    SearchProviderKind.DEEPSEEK -> ""
    SearchProviderKind.BING -> ""
    SearchProviderKind.TAVILY -> TAVILY_SECRET_REF
    SearchProviderKind.BRAVE -> BRAVE_SECRET_REF
    SearchProviderKind.SERPER -> SERPER_SECRET_REF
}

internal fun searchProviderLabel(kind: SearchProviderKind): String = when (kind) {
    SearchProviderKind.DEEPSEEK -> "DeepSeek 自带联网"
    SearchProviderKind.BING -> "必应免密钥"
    SearchProviderKind.TAVILY -> "Tavily"
    SearchProviderKind.BRAVE -> "Brave"
    SearchProviderKind.SERPER -> "Serper"
}

/** Same canonical ref `DefaultProviderService` assigns: `provider.<id>`. */
internal fun providerSecretRef(id: ProviderId): String = "provider.${id.raw}"

internal fun protocolLabel(protocol: ProviderProtocol): String = when (protocol) {
    ProviderProtocol.OPENAI_COMPATIBLE -> "OpenAI 兼容"
    ProviderProtocol.ANTHROPIC -> "Anthropic"
    ProviderProtocol.GEMINI -> "Gemini"
}

/** The reasoning levels are shown in English on purpose (Off/Low/High/Max). */
internal fun thinkingLabel(level: ThinkingLevel): String = when (level) {
    ThinkingLevel.OFF -> "Off"
    ThinkingLevel.LOW -> "Low"
    ThinkingLevel.HIGH -> "High"
    ThinkingLevel.MAX -> "Max"
}

/** Context strategies as the user sees them, ids as the domain stores them. */
internal val ContextStrategyOptions: List<Pair<String, String>> = listOf(
    ContextStrategyIds.NONE to "不处理（超限直接失败）",
    ContextStrategyIds.CONFIRM_COMPACTION to "超限时询问并压缩",
)

internal fun contextStrategyLabel(id: String): String =
    ContextStrategyOptions.firstOrNull { it.first == id }?.second ?: id

/**
 * Locally minted ids. The UI is allowed to create aggregate ids because it
 * hands complete aggregates to the services; `IdPort` is not on `AppServices`.
 */
internal fun localId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
