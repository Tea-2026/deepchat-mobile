package com.deepseek.mobilechat.application.log

import com.deepseek.mobilechat.application.BlockKind
import com.deepseek.mobilechat.application.ContextAssembly
import com.deepseek.mobilechat.application.ContextBlock
import com.deepseek.mobilechat.application.ModelBudgetLookup
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.TokenEstimatorPort
import com.deepseek.mobilechat.domain.ContextBudget
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.ConversationOverrides
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.ModelInfo
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.Surface
import com.deepseek.mobilechat.domain.log.SurfaceContent

/**
 * Builds the model-request context from the projected [Surface] instead of the
 * `messages` table.
 *
 * This replaces [com.deepseek.mobilechat.application.context.DefaultContextAssembler]
 * on the chat path (the context debug screen keeps using that one): the log is
 * the truth, so the prompt must be "whatever survives the surface fold", not
 * "whatever a second, differently-validated tree happens to say".
 *
 * Contract (docs/architecture-v2-log.md §13.3/§13.4):
 *
 * 1. the system prompt resolved from the conversation overrides / preset is
 *    carried by [ContextAssembly.systemPrompt] (adapters render it as the
 *    system block), never duplicated into the message list;
 * 2. surface nodes are converted in order — System/Developer become `SYSTEM`
 *    messages, User/Assistant keep their parts, Tool becomes a `TOOL` message
 *    so the `tool_use` / `tool_result` pairing survives;
 * 3. **only for the current turn**, the ephemeral search block and the not-yet-
 *    persisted user message are appended at the end. Search results are never
 *    written into the log (they die with this request);
 * 4. the budget is an estimate over the assembled blocks, using the same
 *    heuristic as the rest of the app, so `ConfirmCompactionStrategy` sees
 *    exactly the numbers the debug panel would show.
 *
 * Every block carries the message id it came from (for tool nodes: the id of
 * the assistant that issued the call). That is what lets the compaction
 * strategy propose a range that can later be resolved back onto the surface.
 */
class SurfaceContextBuilder(
    private val tokenEstimator: TokenEstimatorPort,
    private val modelBudgetLookup: ModelBudgetLookup,
) {

    /** Resolved per-turn knobs: model, sampling, thinking and system prompt. */
    data class TurnSettings(
        val modelRef: ModelRef,
        val params: SamplingParams,
        val thinkingLevel: ThinkingLevel,
        val systemPrompt: String?,
    ) {
        fun toModelInfo(): ModelInfo = ModelInfo(modelRef, params, thinkingLevel)
    }

    /**
     * Resolution order matches the old assembler: conversation overrides, then
     * the bound/default preset, then the first provider profile that declares a
     * default model. `null` means "this device cannot send a turn yet" — the
     * gateway turns that into an [com.deepseek.mobilechat.application.AppError]
     * instead of calling a provider with an empty model ref.
     */
    fun settings(
        overrides: ConversationOverrides,
        preset: Preset?,
        fallbackModelRef: ModelRef?,
    ): TurnSettings? {
        val modelRef = overrides.modelRef ?: preset?.modelRef ?: fallbackModelRef ?: return null
        return TurnSettings(
            modelRef = modelRef,
            params = overrides.params ?: preset?.params ?: SamplingParams(),
            thinkingLevel = overrides.thinkingLevel ?: preset?.thinkingLevel ?: ThinkingLevel.OFF,
            systemPrompt = overrides.systemPrompt?.takeIf { it.isNotBlank() }
                ?: preset?.systemPrompt?.takeIf { it.isNotBlank() },
        )
    }

    fun build(
        conversationId: ConversationId,
        settings: TurnSettings,
        surface: Surface,
        searchBlock: PromptMessage? = null,
        currentUser: PromptMessage? = null,
    ): ContextAssembly {
        val blocks = ArrayList<ContextBlock>(surface.nodes.size + 3)
        val prompt = ArrayList<PromptMessage>(surface.nodes.size + 3)

        settings.systemPrompt?.let { systemPrompt ->
            blocks += ContextBlock(
                id = BLOCK_ID_SYSTEM,
                kind = BlockKind.SYSTEM_PROMPT,
                title = SYSTEM_PROMPT_TITLE,
                tokens = tokenEstimator.estimate(systemPrompt),
            )
        }

        var leafId: MessageId? = null
        // toolUseId -> message id of the assistant that issued it. Used so a
        // tool/result node is attributed to the message that owns the pair.
        val toolCallOwners = HashMap<String, MessageId>()

        surface.nodes.forEach { node ->
            val message: PromptMessage
            val messageId: MessageId?
            when (val content = node.content) {
                is SurfaceContent.System -> {
                    message = PromptMessage(
                        role = MessageRole.SYSTEM,
                        parts = listOf(MessagePart.Text(content.text)),
                    )
                    messageId = null
                }

                is SurfaceContent.Developer -> {
                    message = PromptMessage(
                        role = MessageRole.SYSTEM,
                        parts = listOf(MessagePart.Text(content.text)),
                    )
                    messageId = null
                }

                is SurfaceContent.User -> {
                    message = PromptMessage(
                        role = MessageRole.USER,
                        parts = content.message.parts,
                        sourceMessageId = content.message.id,
                    )
                    messageId = content.message.id
                }

                is SurfaceContent.Assistant -> {
                    content.message.parts.filterIsInstance<MessagePart.ToolCall>()
                        .forEach { call -> toolCallOwners[call.callId] = content.message.id }
                    message = PromptMessage(
                        role = MessageRole.ASSISTANT,
                        parts = content.message.parts,
                        sourceMessageId = content.message.id,
                    )
                    messageId = content.message.id
                }

                is SurfaceContent.Tool -> {
                    message = PromptMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(
                            MessagePart.ToolResult(
                                callId = content.toolUseId,
                                ok = !content.isError,
                                content = content.content,
                            ),
                        ),
                    )
                    // Attribute the block to the assistant that issued the call,
                    // so a compaction range never starts inside a tool pair.
                    messageId = toolCallOwners[content.toolUseId]
                }
            }

            val kind = when (node.type) {
                EventType.SYSTEM_MESSAGE -> BlockKind.SYSTEM_PROMPT
                EventType.DEVELOPER_MESSAGE -> BlockKind.INJECTION
                else -> BlockKind.HISTORY
            }
            blocks += ContextBlock(
                id = "history:${node.seq}",
                kind = kind,
                title = HISTORY_TITLE,
                tokens = tokenEstimator.estimate(message),
                messageIds = messageId?.let { listOf(it) } ?: emptyList(),
            )
            prompt += message
            if (messageId != null) leafId = messageId
        }

        searchBlock?.let { search ->
            blocks += ContextBlock(
                id = BLOCK_ID_SEARCH,
                kind = BlockKind.SEARCH,
                title = SEARCH_TITLE,
                tokens = tokenEstimator.estimate(search),
            )
            prompt += search
        }

        currentUser?.let { current ->
            blocks += ContextBlock(
                id = BLOCK_ID_CURRENT,
                kind = BlockKind.CURRENT_USER,
                title = CURRENT_TITLE,
                tokens = tokenEstimator.estimate(current),
                messageIds = current.sourceMessageId?.let { listOf(it) } ?: emptyList(),
            )
            prompt += current
            current.sourceMessageId?.let { leafId = it }
        }

        return ContextAssembly(
            conversationId = conversationId,
            leafId = leafId,
            blocks = blocks,
            budget = ContextBudget(
                limit = modelBudgetLookup.contextWindow(settings.modelRef),
                used = blocks.sumOf { it.tokens },
            ),
            prompt = prompt,
            systemPrompt = settings.systemPrompt,
            params = settings.params,
            modelRef = settings.modelRef,
            thinkingLevel = settings.thinkingLevel,
        )
    }

    companion object {
        const val BLOCK_ID_SYSTEM: String = "system"
        const val BLOCK_ID_SEARCH: String = "search:0"
        const val BLOCK_ID_CURRENT: String = "current"

        const val SYSTEM_PROMPT_TITLE: String = "系统提示词"
        const val HISTORY_TITLE: String = "历史消息"
        const val SEARCH_TITLE: String = "搜索结果"
        const val CURRENT_TITLE: String = "当前消息"
    }
}
