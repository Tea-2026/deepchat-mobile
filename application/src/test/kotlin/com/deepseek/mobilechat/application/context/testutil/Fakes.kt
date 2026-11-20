package com.deepseek.mobilechat.application.context.testutil

import com.deepseek.mobilechat.application.AttachmentStore
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.CompactionStore
import com.deepseek.mobilechat.application.ContextAssembly
import com.deepseek.mobilechat.application.ContextBlock
import com.deepseek.mobilechat.application.ConversationStore
import com.deepseek.mobilechat.application.IdPort
import com.deepseek.mobilechat.application.ImportedFile
import com.deepseek.mobilechat.application.InjectionStore
import com.deepseek.mobilechat.application.MessageStore
import com.deepseek.mobilechat.application.ModelBudgetLookup
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.PresetStore
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.domain.Attachment
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.AttachmentKind
import com.deepseek.mobilechat.domain.Compaction
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.CompactionKind
import com.deepseek.mobilechat.domain.ContextBudget
import com.deepseek.mobilechat.domain.ContextInjection
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.ConversationOverrides
import com.deepseek.mobilechat.domain.InjectionId
import com.deepseek.mobilechat.domain.InjectionScope
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.ThinkingLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

// ---------------------------------------------------------------------------
// In-memory port fakes. Every observable store is backed by a StateFlow so
// production code that samples via `observe(...).first()` behaves like the real
// adapter. No store here is mocked: only port boundaries are faked.
// ---------------------------------------------------------------------------

class FakeConversationStore : ConversationStore {

    val state = MutableStateFlow<Map<ConversationId, Conversation>>(emptyMap())

    override fun observeAll(): Flow<List<Conversation>> = state.map { it.values.toList() }

    override fun observe(id: ConversationId): Flow<Conversation?> = state.map { it[id] }

    override fun search(query: String): Flow<List<Conversation>> =
        state.map { conversations ->
            conversations.values.filter { it.title.contains(query, ignoreCase = true) }
        }

    override suspend fun get(id: ConversationId): Conversation? = state.value[id]

    override suspend fun upsert(conversation: Conversation) {
        state.update { it + (conversation.id to conversation) }
    }

    override suspend fun delete(id: ConversationId) {
        state.update { it - id }
    }
}

class FakeMessageStore : MessageStore {

    val state = MutableStateFlow<Map<MessageId, Message>>(emptyMap())

    override fun observe(conversationId: ConversationId): Flow<List<Message>> =
        state.map { messages -> snapshot(conversationId, messages.values) }

    override suspend fun get(id: MessageId): Message? = state.value[id]

    override suspend fun list(conversationId: ConversationId): List<Message> =
        snapshot(conversationId, state.value.values)

    override suspend fun append(message: Message) {
        state.update { it + (message.id to message) }
    }

    override suspend fun update(message: Message) {
        state.update { it + (message.id to message) }
    }

    override suspend fun remove(ids: Set<MessageId>) {
        state.update { it - ids }
    }

    private fun snapshot(conversationId: ConversationId, values: Collection<Message>): List<Message> =
        values.filter { it.conversationId == conversationId }
            .sortedWith(compareBy({ it.seq }, { it.createdAt }))
}

class FakeInjectionStore : InjectionStore {

    val state = MutableStateFlow<List<ContextInjection>>(emptyList())

    override fun observe(conversationId: ConversationId): Flow<List<ContextInjection>> =
        state.map { injections -> injections.filter { it.conversationId == conversationId } }

    override suspend fun list(conversationId: ConversationId): List<ContextInjection> =
        state.value.filter { it.conversationId == conversationId }

    override suspend fun add(injection: ContextInjection) {
        state.update { it + injection }
    }

    override suspend fun remove(id: InjectionId) {
        state.update { injections -> injections.filterNot { it.id == id } }
    }
}

class FakeCompactionStore : CompactionStore {

    val state = MutableStateFlow<List<Compaction>>(emptyList())

    override fun observe(conversationId: ConversationId): Flow<List<Compaction>> =
        state.map { compactions -> compactions.filter { it.conversationId == conversationId } }

    override suspend fun list(conversationId: ConversationId): List<Compaction> =
        state.value.filter { it.conversationId == conversationId }

    override suspend fun add(compaction: Compaction) {
        state.update { it + compaction }
    }

    override suspend fun markUndone(id: CompactionId, atMillis: Long) {
        state.update { compactions ->
            compactions.map { if (it.id == id) it.copy(undoneAt = atMillis) else it }
        }
    }
}

/**
 * In-memory [AttachmentStore]. [Attachment] has no conversationId of its own, so
 * [observe] returns every stored attachment; the assembler only needs [get].
 */
class FakeAttachmentStore : AttachmentStore {

    private val attachments = MutableStateFlow<Map<AttachmentId, Attachment>>(emptyMap())

    private var counter = 0

    fun put(attachment: Attachment) {
        attachments.update { it + (attachment.id to attachment) }
    }

    override fun observe(conversationId: ConversationId): Flow<List<Attachment>> =
        attachments.map { it.values.toList() }

    override suspend fun get(id: AttachmentId): Attachment? = attachments.value[id]

    override suspend fun save(conversationId: ConversationId?, file: ImportedFile): Outcome<Attachment> {
        val attachment = Attachment(
            id = AttachmentId("attachment-${++counter}"),
            fileName = file.fileName,
            mimeType = file.mimeType,
            sizeBytes = file.sizeBytes,
            localPath = "memory/${file.fileName}",
            sha256 = "",
            kind = if (file.mimeType.startsWith("text/")) AttachmentKind.TEXT else AttachmentKind.OTHER,
            createdAt = 0L,
        )
        put(attachment)
        return Outcome.Ok(attachment)
    }

    override suspend fun linkToConversation(ids: List<AttachmentId>, conversationId: ConversationId) = Unit

    override suspend fun delete(id: AttachmentId) {
        attachments.update { it - id }
    }
}

class FakePresetStore : PresetStore {

    val state = MutableStateFlow<List<Preset>>(emptyList())

    override fun observeAll(): Flow<List<Preset>> = state.map { it }

    override suspend fun get(id: PresetId): Preset? = state.value.firstOrNull { it.id == id }

    override suspend fun upsert(preset: Preset) {
        state.update { presets -> presets.filterNot { it.id == preset.id } + preset }
    }

    override suspend fun delete(id: PresetId) {
        state.update { presets -> presets.filterNot { it.id == id } }
    }
}

class FakeProviderProfileStore : ProviderProfileStore {

    val state = MutableStateFlow<List<ProviderProfile>>(emptyList())

    override fun observeAll(): Flow<List<ProviderProfile>> = state.map { it }

    override suspend fun get(id: ProviderId): ProviderProfile? = state.value.firstOrNull { it.id == id }

    override suspend fun upsert(profile: ProviderProfile) {
        state.update { profiles -> profiles.filterNot { it.id == profile.id } + profile }
    }

    override suspend fun delete(id: ProviderId) {
        state.update { profiles -> profiles.filterNot { it.id == id } }
    }
}

class FakeClock(private var now: Long = 0L) : ClockPort {

    override fun nowMillis(): Long = now

    fun advanceBy(millis: Long) {
        now += millis
    }

    fun setNow(millis: Long) {
        now = millis
    }
}

class FakeIdPort(private val prefix: String = "generated") : IdPort {

    private var counter = 0

    override fun newId(): String = "$prefix-${++counter}"
}

/** Budget lookup with a single fixed limit, for budget-boundary tests. */
class FixedBudgetLookup(private val limit: Int) : ModelBudgetLookup {

    override fun contextWindow(modelRef: ModelRef): Int = limit
}

// ---------------------------------------------------------------------------
// Builders: readable fixtures without spreading constructor calls everywhere.
// ---------------------------------------------------------------------------

fun testConversation(
    id: String,
    activeLeafId: String? = null,
    overrides: ConversationOverrides = ConversationOverrides(),
    title: String = id,
): Conversation = Conversation(
    id = ConversationId(id),
    title = title,
    activeLeafId = activeLeafId?.let { MessageId(it) },
    overrides = overrides,
    createdAt = 0L,
    updatedAt = 0L,
)

fun testMessage(
    id: String,
    conversationId: String,
    role: MessageRole,
    seq: Long,
    text: String = "",
    parentId: String? = null,
    pinned: Boolean = false,
    parts: List<MessagePart> = listOf(MessagePart.Text(text)),
): Message = Message(
    id = MessageId(id),
    conversationId = ConversationId(conversationId),
    parentId = parentId?.let { MessageId(it) },
    seq = seq,
    role = role,
    parts = parts,
    status = MessageStatus.COMPLETE,
    createdAt = seq,
    pinned = pinned,
)

fun testUserMessage(
    id: String,
    conversationId: String,
    text: String = "",
    seq: Long,
    parentId: String? = null,
    pinned: Boolean = false,
    parts: List<MessagePart> = listOf(MessagePart.Text(text)),
): Message = testMessage(
    id = id,
    conversationId = conversationId,
    role = MessageRole.USER,
    seq = seq,
    parentId = parentId,
    pinned = pinned,
    parts = parts,
)

fun testAssistantMessage(
    id: String,
    conversationId: String,
    text: String = "",
    seq: Long,
    parentId: String? = null,
    pinned: Boolean = false,
    parts: List<MessagePart> = listOf(MessagePart.Text(text)),
): Message = testMessage(
    id = id,
    conversationId = conversationId,
    role = MessageRole.ASSISTANT,
    seq = seq,
    parentId = parentId,
    pinned = pinned,
    parts = parts,
)

fun testInjection(
    id: String,
    conversationId: String,
    content: String,
    scope: InjectionScope = InjectionScope.PERSISTENT,
    position: Int = 0,
    createdAt: Long = 0L,
    anchorMessageId: String? = null,
): ContextInjection = ContextInjection(
    id = InjectionId(id),
    conversationId = ConversationId(conversationId),
    anchorMessageId = anchorMessageId?.let { MessageId(it) },
    scope = scope,
    position = position,
    content = content,
    createdAt = createdAt,
)

fun testCompaction(
    id: String,
    conversationId: String,
    from: String,
    to: String,
    summary: String = "summary",
    createdAt: Long = 0L,
    undoneAt: Long? = null,
): Compaction = Compaction(
    id = CompactionId(id),
    conversationId = ConversationId(conversationId),
    fromMessageId = MessageId(from),
    toMessageId = MessageId(to),
    summary = summary,
    kind = CompactionKind.SUMMARY,
    createdAt = createdAt,
    undoneAt = undoneAt,
)

fun testAttachment(
    id: String,
    fileName: String = "$id.txt",
    extractedText: String? = null,
    mimeType: String = "text/plain",
): Attachment = Attachment(
    id = AttachmentId(id),
    fileName = fileName,
    mimeType = mimeType,
    sizeBytes = extractedText?.length?.toLong() ?: 0L,
    localPath = "memory/$fileName",
    sha256 = "",
    kind = AttachmentKind.TEXT,
    extractedText = extractedText,
    createdAt = 0L,
)

fun testPreset(
    id: String,
    systemPrompt: String = "",
    isDefault: Boolean = false,
    modelRef: ModelRef? = null,
): Preset = Preset(
    id = PresetId(id),
    name = id,
    systemPrompt = systemPrompt,
    modelRef = modelRef,
    params = SamplingParams(),
    thinkingLevel = ThinkingLevel.OFF,
    isDefault = isDefault,
)

/**
 * Minimal [ContextAssembly] for strategy tests: the policy under test only
 * reads blocks, prompt and budget, so no stores are involved.
 */
fun testAssembly(
    conversationId: String = "c1",
    leafId: String? = "leaf",
    blocks: List<ContextBlock> = emptyList(),
    limit: Int = 100_000,
    used: Int = blocks.sumOf { it.tokens },
    prompt: List<PromptMessage> = blocks.flatMap { block ->
        block.messageIds.map { messageId ->
            PromptMessage(
                role = MessageRole.USER,
                parts = listOf(MessagePart.Text(messageId.raw)),
                sourceMessageId = messageId,
            )
        }
    },
): ContextAssembly = ContextAssembly(
    conversationId = ConversationId(conversationId),
    leafId = leafId?.let { MessageId(it) },
    blocks = blocks,
    budget = ContextBudget(limit = limit, used = used),
    prompt = prompt,
    systemPrompt = null,
    params = SamplingParams(),
    modelRef = ModelRef(ProviderId("test-provider"), ModelId("test-model")),
    thinkingLevel = ThinkingLevel.OFF,
)
