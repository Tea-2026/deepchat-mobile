package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.ChatCompletionPort
import com.deepseek.mobilechat.application.ContextAssembly
import com.deepseek.mobilechat.application.ContextPlan
import com.deepseek.mobilechat.application.ContextStrategy
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.GenerationRequest
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.PresetStore
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.domain.ContextStrategyIds
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.ModelInfo
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.ThinkingLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Resolves the [ContextStrategy] a conversation is configured to use.
 *
 * Strategy selection follows the preset resolution order of
 * docs/architecture.md §3.4 (conversation-bound preset, then the global default
 * preset). With no preset at all the documented default,
 * [ContextStrategyIds.CONFIRM_COMPACTION], applies. An id that is not in the
 * registry is an expected failure ([AppError.Unsupported]); silently falling
 * back to "fits" would bypass the user-confirmation guarantee.
 */
internal class ContextStrategyResolver(
    private val presetStore: PresetStore,
    private val strategies: Map<String, ContextStrategy>,
) {

    suspend fun resolve(conversation: Conversation): Outcome<ContextStrategy> {
        val preset = conversation.overrides.presetId?.let { presetStore.get(it) }
            ?: run {
                val presets = presetStore.observeAll().first()
                presets.firstOrNull { it.isDefault } ?: presets.firstOrNull()
            }
        val strategyId = preset?.contextStrategyId ?: ContextStrategyIds.CONFIRM_COMPACTION
        val strategy = strategies[strategyId]
            ?: return Outcome.Err(AppError.Unsupported("unknown context strategy: $strategyId"))
        return Outcome.Ok(strategy)
    }
}

/**
 * Runs the configured strategy when (and only when) the assembly is over
 * budget. Strategies are side-effect free by construction: the proposal is
 * returned, never persisted, so a caller can ask the user first.
 */
internal suspend fun applyBudgetPolicy(
    assembly: ContextAssembly,
    strategy: ContextStrategy,
    chatCompletion: ChatCompletionPort,
): ContextPlan {
    if (!assembly.budget.isOverBudget) return ContextPlan.Fits
    return strategy.plan(assembly, assembly.budget) { messages ->
        summarizeMessages(chatCompletion, assembly, messages)
    }
}

/**
 * Collects a model-generated summary of [messages] by draining a streaming
 * chat completion.
 *
 * Thinking is forced off: a summary is a utility call and must not burn
 * reasoning tokens or latency. A provider failure degrades to the text
 * collected so far instead of throwing — the caller is a strategy that has to
 * return a proposal either way.
 */
internal suspend fun summarizeMessages(
    chatCompletion: ChatCompletionPort,
    assembly: ContextAssembly,
    messages: List<PromptMessage>,
): String {
    if (messages.isEmpty()) return ""
    val request = GenerationRequest(
        modelRef = assembly.modelRef,
        systemPrompt = SUMMARIZATION_SYSTEM_PROMPT,
        messages = messages,
        params = assembly.params,
        thinkingLevel = ThinkingLevel.OFF,
        stream = true,
    )
    val summary = StringBuilder()
    try {
        chatCompletion.stream(request).collect { event ->
            if (event is GenerationEvent.TextDelta) summary.append(event.text)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // Keep the partial summary; a strategy still needs to answer.
    }
    return summary.toString().trim()
}

/**
 * Best-effort default model for utility calls (title generation) that run
 * without an assembled context: default preset first, then the first provider
 * profile that declares a default model.
 */
internal suspend fun resolveDefaultModelRef(
    presetStore: PresetStore,
    providerProfileStore: ProviderProfileStore,
): ModelRef? {
    val presets = presetStore.observeAll().first()
    val preset = presets.firstOrNull { it.isDefault } ?: presets.firstOrNull()
    preset?.modelRef?.let { return it }
    return providerProfileStore.observeAll().first()
        .mapNotNull { profile -> profile.defaultModelId?.let { ModelRef(profile.id, it) } }
        .firstOrNull()
}

internal const val SUMMARIZATION_SYSTEM_PROMPT: String =
    "你是一个上下文压缩器。把对话片段压缩成简洁的事实摘要，" +
        "保留人物、决定、约束和未完成的事项，不要添加原文没有的信息，不要使用列表以外的新内容。"

/** A STREAMING assistant message that is about to be filled by a stream. */
internal fun assistantPlaceholder(
    id: MessageId,
    conversationId: ConversationId,
    parentId: MessageId,
    seq: Long,
    createdAt: Long,
    assembly: ContextAssembly,
): Message = Message(
    id = id,
    conversationId = conversationId,
    parentId = parentId,
    seq = seq,
    role = MessageRole.ASSISTANT,
    parts = emptyList(),
    status = MessageStatus.STREAMING,
    createdAt = createdAt,
    modelInfo = ModelInfo(
        ref = assembly.modelRef,
        params = assembly.params,
        thinkingLevel = assembly.thinkingLevel,
    ),
)

/** Provider-agnostic request built from an assembly (no provider-specific fields). */
internal fun requestForAssembly(assembly: ContextAssembly): GenerationRequest = GenerationRequest(
    modelRef = assembly.modelRef,
    systemPrompt = assembly.systemPrompt,
    messages = assembly.prompt,
    params = assembly.params,
    thinkingLevel = assembly.thinkingLevel,
    tools = emptyList(),
    stream = true,
)
