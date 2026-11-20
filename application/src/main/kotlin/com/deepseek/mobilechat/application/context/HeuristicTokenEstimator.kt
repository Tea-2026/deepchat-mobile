package com.deepseek.mobilechat.application.context

import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.TokenEstimatorPort
import com.deepseek.mobilechat.domain.MessagePart

/**
 * Character-class heuristic token estimator.
 *
 * Deliberately dependency-free (no tokenizer library): the number is used for
 * budgeting and for the context debug panel, never for billing. The heuristic
 * is deterministic, so tests, the budget checker and the debug panel agree.
 *
 * Rules:
 * - ASCII: 4 characters per token
 * - CJK: 1.6 characters per token (Han, kana, Hangul, CJK punctuation and
 *   fullwidth forms)
 * - any other non-ASCII character (emoji, Cyrillic, ...): 1 token each, which
 *   deliberately over-estimates rather than under-estimates
 * - [MessagePart.Image]: a flat [IMAGE_TOKENS] tokens per image, a
 *   provider-agnostic vision estimate
 * - [MessagePart.Reasoning] is counted exactly like [MessagePart.Text]
 * - [MessagePart.FileRef] only carries an attachment id and contributes 0
 * - every [PromptMessage] additionally pays a flat [ROLE_OVERHEAD_TOKENS]
 *   role overhead
 *
 * The per-class character counts are accumulated separately and rounded up
 * once, in fixed-point units (1 token = [UNITS_PER_TOKEN] units), so that no
 * floating point rounding can drift the result.
 */
class HeuristicTokenEstimator : TokenEstimatorPort {

    override fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var units = 0L
        for (character in text) {
            val code = character.code
            units += when {
                code < ASCII_LIMIT -> ASCII_UNITS_PER_CHAR
                isCjk(code) -> CJK_UNITS_PER_CHAR
                else -> OTHER_UNITS_PER_CHAR
            }
        }
        return ((units + UNITS_PER_TOKEN - 1) / UNITS_PER_TOKEN).toInt()
    }

    override fun estimate(prompt: PromptMessage): Int =
        ROLE_OVERHEAD_TOKENS + prompt.parts.sumOf { partTokens(it) }

    private fun partTokens(part: MessagePart): Int = when (part) {
        is MessagePart.Text -> estimate(part.text)
        is MessagePart.Reasoning -> estimate(part.text)
        is MessagePart.Image -> IMAGE_TOKENS
        is MessagePart.FileRef -> 0
        is MessagePart.ContextBlock ->
            estimate(part.title) + estimate(part.body) +
                part.citations.sumOf { estimate(it.title) + estimate(it.url) }
        is MessagePart.SearchResults ->
            estimate(part.query) +
                part.hits.sumOf { estimate(it.title) + estimate(it.url) + estimate(it.snippet) }
        is MessagePart.ToolCall -> estimate(part.name) + estimate(part.argumentsJson)
        is MessagePart.ToolResult -> estimate(part.content)
    }

    companion object {
        /** Flat, provider-agnostic vision estimate for a single image part. */
        const val IMAGE_TOKENS: Int = 800

        /** Per-message overhead for the role marker and message separators. */
        const val ROLE_OVERHEAD_TOKENS: Int = 4

        private const val UNITS_PER_TOKEN = 80L
        private const val ASCII_LIMIT = 0x80
        private const val ASCII_UNITS_PER_CHAR = 20L // 4 chars/token
        private const val CJK_UNITS_PER_CHAR = 50L // 1.6 chars/token
        private const val OTHER_UNITS_PER_CHAR = 80L // 1 token per char

        private fun isCjk(code: Int): Boolean =
            code in 0x3000..0x303F || // CJK symbols and punctuation
                code in 0x3040..0x30FF || // hiragana and katakana
                code in 0x3400..0x4DBF || // CJK unified ideographs extension A
                code in 0x4E00..0x9FFF || // CJK unified ideographs
                code in 0xAC00..0xD7AF || // Hangul syllables
                code in 0xF900..0xFAFF || // CJK compatibility ideographs
                code in 0xFF00..0xFFEF // fullwidth and halfwidth forms
    }
}
