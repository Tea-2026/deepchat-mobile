package com.deepseek.mobilechat.application.context

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.AttachmentStore
import com.deepseek.mobilechat.application.BlockKind
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.CompactionStore
import com.deepseek.mobilechat.application.ContextAssembler
import com.deepseek.mobilechat.application.ContextAssembly
import com.deepseek.mobilechat.application.ContextBlock
import com.deepseek.mobilechat.application.ConversationStore
import com.deepseek.mobilechat.application.IdPort
import com.deepseek.mobilechat.application.InjectionStore
import com.deepseek.mobilechat.application.MessageStore
import com.deepseek.mobilechat.application.ModelBudgetLookup
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.PresetStore
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.application.TokenEstimatorPort
import com.deepseek.mobilechat.domain.Attachment
import com.deepseek.mobilechat.domain.BlockSource
import com.deepseek.mobilechat.domain.Citation
import com.deepseek.mobilechat.domain.ContextBudget
import com.deepseek.mobilechat.domain.ContextInjection
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.InjectionScope
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageTree
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.ThinkingLevel
import kotlinx.coroutines.flow.first

/**
 * Default [ContextAssembler].
 *
 * The pipeline order is a contract (docs/architecture.md §4.3) and is mirrored
 * one-to-one by the emitted [ContextBlock] ids, which are stable and
 * predictable so the context debug panel can diff two assemblies:
 *
 * 1. `system`            system prompt (carried by [ContextAssembly.systemPrompt], never by the prompt list)
 * 2. `injection:<id>`    persistent injections, ordered by `position` then `createdAt`
 * 3. `compaction:<id>`   active compactions, replacing the range they cover
 * 4. `history:<id>`      active-path history, minus compacted, system and current messages
 * 5. `search:<index>` / `file:<index>` hanging blocks extracted from the current user message
 * 6. `injection:<id>`    ephemeral injections, ordered by `position` then `createdAt`
 * 7. `current`           the current user message itself
 *
 * Notes on the interpretation of the pipeline:
 * - "current user message" is the active-path leaf when that leaf is a user
 *   message. When the leaf is an assistant message (e.g. a preview of an
 *   already answered conversation) there is no current message and steps 5 and
 *   7 are skipped, so history stays in its natural order.
 * - `pinned` messages are never replaced by a compaction; they stay in history.
 * - `SearchResults` / `FileRef` parts of the current message are turned into
 *   hanging blocks and stripped from the current message so the model does not
 *   receive the same payload twice. If stripping would leave the message with
 *   no parts at all, the original parts are kept.
 * - `FileRef` parts are resolved through [AttachmentStore]: the block title is
 *   the file name and the body is the attachment's extracted text. An
 *   attachment without extracted text degrades to
 *   "附件：<fileName>（无法抽取文本）", a missing attachment to
 *   "附件缺失：<attachmentId>".
 *
 * Failure contract: a missing conversation, a conversation without an active
 * leaf, or an active leaf that is not part of the conversation is an expected
 * failure and returns [Outcome.Err] with [AppError.Validation]; program errors
 * still throw.
 */
class DefaultContextAssembler(
    private val conversationStore: ConversationStore,
    private val messageStore: MessageStore,
    private val injectionStore: InjectionStore,
    private val compactionStore: CompactionStore,
    private val presetStore: PresetStore,
    private val providerProfileStore: ProviderProfileStore,
    private val attachmentStore: AttachmentStore,
    private val tokenEstimator: TokenEstimatorPort,
    private val modelBudgetLookup: ModelBudgetLookup,
    @Suppress("unused") private val clock: ClockPort,
    @Suppress("unused") private val idPort: IdPort,
) : ContextAssembler {

    override suspend fun assemble(conversationId: ConversationId): Outcome<ContextAssembly> {
        val conversation = conversationStore.get(conversationId)
            ?: return Outcome.Err(AppError.Validation("conversation not found: ${conversationId.raw}"))
        val leafId = conversation.activeLeafId
            ?: return Outcome.Err(AppError.Validation("conversation has no active leaf: ${conversationId.raw}"))

        val messages = messageStore.list(conversationId)
        if (messages.none { it.id == leafId }) {
            return Outcome.Err(AppError.Validation("message not found for active leaf: ${leafId.raw}"))
        }
        val path = MessageTree.from(messages).pathTo(leafId)
        if (path.isEmpty()) {
            return Outcome.Err(AppError.Validation("cannot resolve active path for leaf: ${leafId.raw}"))
        }

        val preset = resolvePreset(conversation)
        val systemPrompt = conversation.overrides.systemPrompt?.takeIf { it.isNotBlank() }
            ?: preset?.systemPrompt?.takeIf { it.isNotBlank() }
        val modelRef = resolveModelRef(conversation, preset)
        val params = conversation.overrides.params ?: preset?.params ?: SamplingParams()
        val thinkingLevel = conversation.overrides.thinkingLevel ?: preset?.thinkingLevel ?: ThinkingLevel.OFF

        val blocks = ArrayList<ContextBlock>()
        val prompt = ArrayList<PromptMessage>()

        // 1. System prompt: carried by the systemPrompt field, never by the prompt list.
        if (systemPrompt != null) {
            blocks += ContextBlock(
                id = BLOCK_ID_SYSTEM,
                kind = BlockKind.SYSTEM_PROMPT,
                title = "系统提示词",
                tokens = tokenEstimator.estimate(systemPrompt),
            )
        }

        val injections = injectionStore.list(conversationId)
            .sortedWith(compareBy({ it.position }, { it.createdAt }))

        // 2. Persistent injections.
        injections.filter { it.scope == InjectionScope.PERSISTENT }.forEach { injection ->
            val message = injectionPrompt(injection)
            blocks += ContextBlock(
                id = blockIdForInjection(injection),
                kind = BlockKind.INJECTION,
                title = INJECTION_TITLE,
                tokens = tokenEstimator.estimate(message),
            )
            prompt += message
        }

        // 3. Active compactions replace the range they cover.
        val pathIndex = HashMap<MessageId, Int>(path.size)
        path.forEachIndexed { index, message -> pathIndex[message.id] = index }

        val compactions = compactionStore.list(conversationId)
            .filter { it.undoneAt == null }
            .mapNotNull { compaction ->
                val from = pathIndex[compaction.fromMessageId] ?: return@mapNotNull null
                val to = pathIndex[compaction.toMessageId] ?: return@mapNotNull null
                compaction to (minOf(from, to)..maxOf(from, to))
            }
            .sortedBy { (_, range) -> range.first }

        val replacedIds = LinkedHashSet<MessageId>()
        compactions.forEach { (compaction, range) ->
            val covered = path.subList(range.first, range.last + 1)
                .filter { !it.pinned && it.id !in replacedIds }
            if (covered.isEmpty()) return@forEach
            replacedIds += covered.map { it.id }
            val title = "已压缩的 ${covered.size} 条消息"
            val message = PromptMessage(
                role = MessageRole.USER,
                parts = listOf(
                    MessagePart.ContextBlock(
                        title = title,
                        body = compaction.summary,
                        source = BlockSource.COMPACTION,
                    ),
                ),
                sourceMessageId = compaction.fromMessageId,
            )
            blocks += ContextBlock(
                id = "compaction:${compaction.id.raw}",
                kind = BlockKind.COMPACTION,
                title = title,
                tokens = tokenEstimator.estimate(message),
                messageIds = covered.map { it.id },
            )
            prompt += message
        }

        val currentUser: Message? = path.lastOrNull()?.takeIf { it.role == MessageRole.USER }
        val currentUserId = currentUser?.id

        // 4. Active-path history.
        path.forEach { message ->
            if (message.role == MessageRole.SYSTEM) return@forEach
            if (message.id == currentUserId) return@forEach
            if (message.id in replacedIds) return@forEach
            val promptMessage = PromptMessage(
                role = message.role,
                parts = message.parts,
                sourceMessageId = message.id,
            )
            blocks += ContextBlock(
                id = "history:${message.id.raw}",
                kind = BlockKind.HISTORY,
                title = "历史消息",
                tokens = tokenEstimator.estimate(promptMessage),
                messageIds = listOf(message.id),
            )
            prompt += promptMessage
        }

        // 5. Hanging blocks extracted from the current user message.
        var currentParts: List<MessagePart> = currentUser?.parts ?: emptyList()
        if (currentUser != null) {
            currentUser.parts.forEachIndexed { index, part ->
                when (part) {
                    is MessagePart.SearchResults -> {
                        val title = "搜索结果：${part.query}"
                        val message = PromptMessage(
                            role = MessageRole.USER,
                            parts = listOf(
                                MessagePart.ContextBlock(
                                    title = title,
                                    body = searchResultsBody(part),
                                    source = BlockSource.SEARCH,
                                    citations = part.hits.map { Citation(it.title, it.url) },
                                ),
                            ),
                            sourceMessageId = currentUser.id,
                        )
                        blocks += ContextBlock(
                            id = "search:$index",
                            kind = BlockKind.SEARCH,
                            title = title,
                            tokens = tokenEstimator.estimate(message),
                        )
                        prompt += message
                    }

                    is MessagePart.FileRef -> {
                        val attachment: Attachment? = attachmentStore.get(part.attachmentId)
                        val extractedText = attachment?.extractedText?.takeIf { it.isNotBlank() }
                        val title = attachment?.fileName ?: FILE_FALLBACK_TITLE
                        val body = if (attachment == null) {
                            "附件缺失：${part.attachmentId.raw}"
                        } else {
                            extractedText ?: "附件：${attachment.fileName}（无法抽取文本）"
                        }
                        val message = PromptMessage(
                            role = MessageRole.USER,
                            parts = listOf(
                                MessagePart.ContextBlock(
                                    title = title,
                                    body = body,
                                    source = BlockSource.FILE_EXTRACT,
                                ),
                            ),
                            sourceMessageId = currentUser.id,
                        )
                        blocks += ContextBlock(
                            id = "file:$index",
                            kind = BlockKind.FILE,
                            title = title,
                            tokens = tokenEstimator.estimate(message),
                        )
                        prompt += message
                    }

                    else -> Unit
                }
            }
            val remaining = currentUser.parts.filterNot {
                it is MessagePart.SearchResults || it is MessagePart.FileRef
            }
            currentParts = remaining.ifEmpty { currentUser.parts }
        }

        // 6. Ephemeral injections.
        injections.filter { it.scope == InjectionScope.EPHEMERAL }.forEach { injection ->
            val message = injectionPrompt(injection)
            blocks += ContextBlock(
                id = blockIdForInjection(injection),
                kind = BlockKind.EPHEMERAL,
                title = EPHEMERAL_TITLE,
                tokens = tokenEstimator.estimate(message),
            )
            prompt += message
        }

        // 7. The current user message itself.
        if (currentUser != null) {
            val message = PromptMessage(
                role = MessageRole.USER,
                parts = currentParts,
                sourceMessageId = currentUser.id,
            )
            blocks += ContextBlock(
                id = BLOCK_ID_CURRENT,
                kind = BlockKind.CURRENT_USER,
                title = "当前消息",
                tokens = tokenEstimator.estimate(message),
                messageIds = listOf(currentUser.id),
            )
            prompt += message
        }

        val used = blocks.sumOf { it.tokens }
        val limit = modelBudgetLookup.contextWindow(modelRef)
        return Outcome.Ok(
            ContextAssembly(
                conversationId = conversationId,
                leafId = leafId,
                blocks = blocks,
                budget = ContextBudget(limit = limit, used = used),
                prompt = prompt,
                systemPrompt = systemPrompt,
                params = params,
                modelRef = modelRef,
                thinkingLevel = thinkingLevel,
            ),
        )
    }

    private suspend fun resolvePreset(conversation: Conversation): Preset? {
        conversation.overrides.presetId?.let { presetId ->
            presetStore.get(presetId)?.let { return it }
        }
        val presets = presetStore.observeAll().first()
        return presets.firstOrNull { it.isDefault } ?: presets.firstOrNull()
    }

    private suspend fun resolveModelRef(conversation: Conversation, preset: Preset?): ModelRef {
        conversation.overrides.modelRef?.let { return it }
        preset?.modelRef?.let { return it }
        val profile = providerProfileStore.observeAll().first()
            .firstOrNull { it.defaultModelId != null }
        val defaultModelId = profile?.defaultModelId
        if (profile != null && defaultModelId != null) {
            return ModelRef(profile.id, defaultModelId)
        }
        return ModelRef(ProviderId(""), ModelId(""))
    }

    private fun injectionPrompt(injection: ContextInjection): PromptMessage =
        PromptMessage(
            role = MessageRole.USER,
            parts = listOf(
                MessagePart.ContextBlock(
                    title = INJECTION_TITLE,
                    body = injection.content,
                    source = BlockSource.USER_INJECTED,
                ),
            ),
        )

    private fun blockIdForInjection(injection: ContextInjection): String = "injection:${injection.id.raw}"

    private fun searchResultsBody(results: MessagePart.SearchResults): String = buildString {
        append("查询：").append(results.query)
        results.hits.forEachIndexed { index, hit ->
            append('\n')
            append(index + 1).append(". ").append(hit.title)
            append('\n').append(hit.url)
            if (hit.snippet.isNotBlank()) append('\n').append(hit.snippet)
        }
    }

    companion object {
        const val BLOCK_ID_SYSTEM: String = "system"
        const val BLOCK_ID_CURRENT: String = "current"

        const val INJECTION_TITLE: String = "上下文注入"
        const val EPHEMERAL_TITLE: String = "临时注入"
        const val FILE_FALLBACK_TITLE: String = "文件引用"
    }
}
