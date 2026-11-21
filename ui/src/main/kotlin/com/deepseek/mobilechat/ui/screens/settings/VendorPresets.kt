package com.deepseek.mobilechat.ui.screens.settings

import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.ProviderProtocol

/**
 * One row of the built-in vendor table.
 *
 * The table is the *only* place the "simple add" flow gets its defaults from:
 * picking a vendor must be enough to produce a savable provider, so the model
 * id is required here and never empty. Everything else (display copy, console
 * link) is presentation data that the editor and the first-run guide both use.
 */
internal data class VendorPreset(
    val id: String,
    val displayName: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val defaultModelId: String,
    val keyHint: String,
    val consoleUrl: String? = null,
)

/**
 * Real endpoints as of 2026-11. Protocol/base URL pairs are what the adapters
 * append to (`chat/completions`, `v1/messages`, `v1beta/models`), so a wrong
 * trailing slash here turns into a wrong request URL everywhere.
 */
internal object VendorPresets {

    /** The "fill everything by hand" pseudo-vendor; never matches a profile. */
    const val CUSTOM_ID: String = "custom"

    val deepSeek = VendorPreset(
        id = "deepseek",
        displayName = "DeepSeek",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = "https://api.deepseek.com",
        defaultModelId = "deepseek-flash",
        keyHint = "在 platform.deepseek.com 创建",
        consoleUrl = "https://platform.deepseek.com/api_keys",
    )

    val openAi = VendorPreset(
        id = "openai",
        displayName = "OpenAI",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = "https://api.openai.com/v1",
        defaultModelId = "gpt-4o-mini",
        keyHint = "在 platform.openai.com 创建",
        consoleUrl = "https://platform.openai.com/api-keys",
    )

    val anthropic = VendorPreset(
        id = "anthropic",
        displayName = "Anthropic",
        protocol = ProviderProtocol.ANTHROPIC,
        baseUrl = "https://api.anthropic.com",
        defaultModelId = "claude-sonnet-4-20250514",
        keyHint = "在 console.anthropic.com 创建",
        consoleUrl = "https://console.anthropic.com/settings/keys",
    )

    val gemini = VendorPreset(
        id = "gemini",
        displayName = "Google Gemini",
        protocol = ProviderProtocol.GEMINI,
        baseUrl = "https://generativelanguage.googleapis.com",
        defaultModelId = "gemini-2.5-flash",
        keyHint = "在 aistudio.google.com 获取",
        consoleUrl = "https://aistudio.google.com/app/apikey",
    )

    val openRouter = VendorPreset(
        id = "openrouter",
        displayName = "OpenRouter",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = "https://openrouter.ai/api/v1",
        defaultModelId = "deepseek/deepseek-chat",
        keyHint = "在 openrouter.ai/keys 创建",
        consoleUrl = "https://openrouter.ai/keys",
    )

    val moonshot = VendorPreset(
        id = "moonshot",
        displayName = "Moonshot（Kimi）",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = "https://api.moonshot.cn/v1",
        defaultModelId = "moonshot-v1-8k",
        keyHint = "在 platform.moonshot.cn 创建",
        consoleUrl = "https://platform.moonshot.cn/console/api-keys",
    )

    val siliconFlow = VendorPreset(
        id = "siliconflow",
        displayName = "硅基流动 SiliconFlow",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = "https://api.siliconflow.cn/v1",
        defaultModelId = "deepseek-ai/DeepSeek-V3",
        keyHint = "在 cloud.siliconflow.cn 创建",
        consoleUrl = "https://cloud.siliconflow.cn/account/ak",
    )

    val zhipu = VendorPreset(
        id = "zhipu",
        displayName = "智谱 GLM",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModelId = "glm-4-flash",
        keyHint = "在 open.bigmodel.cn 创建",
        consoleUrl = "https://open.bigmodel.cn/usercenter/apikeys",
    )

    val dashScope = VendorPreset(
        id = "dashscope",
        displayName = "通义千问 DashScope",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModelId = "qwen-plus",
        keyHint = "在 dashscope.console.aliyun.com 创建",
        consoleUrl = "https://dashscope.console.aliyun.com/apiKey",
    )

    /** Custom is rendered as the last chip and opens the full form expanded. */
    val custom = VendorPreset(
        id = CUSTOM_ID,
        displayName = "自定义",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = "",
        defaultModelId = "",
        keyHint = "手动填写 Base URL 与默认模型 id",
        consoleUrl = null,
    )

    /** Display order: the default the app is built around comes first. */
    val all: List<VendorPreset> = listOf(
        deepSeek,
        openAi,
        anthropic,
        gemini,
        openRouter,
        moonshot,
        siliconFlow,
        zhipu,
        dashScope,
    )

    fun byId(id: String?): VendorPreset? =
        if (id == null) null else (all + custom).firstOrNull { it.id == id }

    /**
     * The preset a saved profile was created from, if its endpoint still matches.
     *
     * Used by the editor so "编辑" opens with the vendor chip already selected
     * and the key-only surface (`advanced = false`) is reachable for a known
     * vendor. A profile whose endpoint was hand-edited falls back to 自定义.
     */
    fun match(profile: ProviderProfile?): VendorPreset? {
        if (profile == null) return null
        val target = normalizeBaseUrl(profile.baseUrl)
        if (target.isEmpty()) return null
        return all.firstOrNull {
            it.protocol == profile.protocol && normalizeBaseUrl(it.baseUrl) == target
        }
    }
}

/** Trailing slashes are never meaningful for these bases; compare without them. */
internal fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')

/** The simple flow produces this draft; a preset can never yield a blank model. */
internal fun VendorPreset.toDraft(apiKey: String = ""): ProviderDraft = ProviderDraft(
    name = displayName,
    protocol = protocol,
    baseUrl = baseUrl,
    apiKey = apiKey,
    defaultModelId = defaultModelId,
)

/** Host of an absolute URL, or "" when the value has no `scheme://` prefix. */
internal fun hostOfUrl(value: String): String = value.trim()
    .substringAfter("://", "")
    .substringBefore('/')
    .substringBefore('?')
    .substringBefore('#')
    .substringBefore(':')
    .lowercase()

/** `http(s)://host` with a non-empty host; rejects bare hosts and spaces. */
internal fun isHttpUrl(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
    val lower = trimmed.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    val host = hostOfUrl(trimmed)
    return host == "localhost" || (host.isNotEmpty() && host.contains('.'))
}

/**
 * Whether the protocol can plausibly talk to this endpoint.
 *
 * Only the two protocol-specific bases are pinned: Anthropic is served from
 * `anthropic.com` and Gemini from `googleapis.com`/`generativelanguage`. The
 * OpenAI-compatible surface is a convention shared by dozens of vendors, so any
 * host is acceptable there — only endpoints that are *known* to speak another
 * protocol are flagged.
 */
internal fun isProtocolConsistentWithUrl(protocol: ProviderProtocol, baseUrl: String): Boolean {
    val host = hostOfUrl(baseUrl)
    if (host.isEmpty()) return false
    return when (protocol) {
        ProviderProtocol.ANTHROPIC -> host.contains("anthropic")
        ProviderProtocol.GEMINI ->
            host.contains("googleapis.com") || host.contains("generativelanguage")
        ProviderProtocol.OPENAI_COMPATIBLE ->
            !host.contains("anthropic") && !host.contains("generativelanguage")
    }
}

/**
 * Pure validation for the editor. Field errors block 保存; [protocolWarning] is
 * advisory (proxies and gateways legitimately mimic another vendor's host).
 */
internal data class ProviderDraftValidation(
    val nameError: String? = null,
    val baseUrlError: String? = null,
    val modelError: String? = null,
    val protocolWarning: String? = null,
) {
    val isValid: Boolean
        get() = nameError == null && baseUrlError == null && modelError == null
}

internal fun validateProviderDraft(draft: ProviderDraft): ProviderDraftValidation {
    val name = draft.name.trim()
    val baseUrl = draft.baseUrl.trim()
    val model = draft.defaultModelId.trim()
    val baseUrlError = when {
        baseUrl.isEmpty() -> "请填写 Base URL"
        !isHttpUrl(baseUrl) -> "Base URL 需以 http:// 或 https:// 开头"
        else -> null
    }
    return ProviderDraftValidation(
        nameError = if (name.isEmpty()) "请填写名称" else null,
        baseUrlError = baseUrlError,
        modelError = if (model.isEmpty()) "请填写默认模型 id" else null,
        protocolWarning = if (baseUrlError == null && !isProtocolConsistentWithUrl(draft.protocol, baseUrl)) {
            "该地址通常不用于所选协议，仍可保存"
        } else {
            null
        },
    )
}
