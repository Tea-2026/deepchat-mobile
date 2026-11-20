package com.deepseek.mobilechat.application.service

/** Title the user sees before anything has been said. */
internal const val DEFAULT_CONVERSATION_TITLE: String = "新对话"

/** How many characters of the first user message become the provisional title. */
internal const val TEMPORARY_TITLE_LENGTH: Int = 20

/** Upper bound for a model-generated title. */
internal const val GENERATED_TITLE_LENGTH: Int = 24

/** Lower bound for the degraded (no-model) title. */
internal const val FALLBACK_TITLE_LENGTH: Int = 16

/**
 * Provisional title derived from the first user message: its first non-blank
 * line, truncated to [TEMPORARY_TITLE_LENGTH]. Empty when there is no usable
 * text (e.g. an image-only message).
 */
internal fun temporaryConversationTitle(text: String): String =
    text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.take(TEMPORARY_TITLE_LENGTH)
        .orEmpty()

/** Offline fallback when title generation is unavailable or fails. */
internal fun fallbackConversationTitle(text: String): String =
    text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.take(FALLBACK_TITLE_LENGTH)
        .orEmpty()

/**
 * Turns a raw model answer into a usable title: first non-blank line, without
 * markdown decoration or wrapping quotes. `null` when nothing usable is left.
 */
internal fun sanitizeGeneratedTitle(raw: String): String? {
    val line = raw.lineSequence()
        .map { it.trim().trimStart('#', '-', '*', '>').trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: return null
    val unquoted = line
        .removeSurrounding("\"")
        .removeSurrounding("'")
        .removeSurrounding("“", "”")
        .removeSurrounding("「", "」")
        .removeSuffix("。")
        .trim()
    if (unquoted.isEmpty()) return null
    return unquoted.take(GENERATED_TITLE_LENGTH).trim().ifEmpty { null }
}

/**
 * A title is a placeholder when it is empty, the neutral default, or exactly
 * the provisional title derived from the first user message — the last case is
 * what lets title generation upgrade the "first 20 characters" placeholder
 * once the first reply has landed.
 */
internal fun isPlaceholderTitle(title: String, firstUserText: String?): Boolean {
    val current = title.trim()
    if (current.isEmpty() || current == DEFAULT_CONVERSATION_TITLE) return true
    val provisional = firstUserText?.let(::temporaryConversationTitle).orEmpty()
    return provisional.isNotEmpty() && current == provisional
}

internal const val TITLE_SYSTEM_PROMPT: String =
    "给下面这段对话的第一条用户消息起一个简短标题。" +
        "只输出标题本身：不超过 16 个字，不要引号、不要句号、不要解释。"

/** Input cap for the title request; longer text never changes the title. */
internal const val MAX_TITLE_SOURCE_CHARS: Int = 800
