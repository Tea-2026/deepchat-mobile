package com.deepseek.mobilechat.application.service.testutil

import com.deepseek.mobilechat.application.AppSettings
import com.deepseek.mobilechat.application.AttachmentStore
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.CompactionStore
import com.deepseek.mobilechat.application.ConversationStore
import com.deepseek.mobilechat.application.FolderTagStore
import com.deepseek.mobilechat.application.IdPort
import com.deepseek.mobilechat.application.ImportedFile
import com.deepseek.mobilechat.application.InjectionStore
import com.deepseek.mobilechat.application.MessageStore
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.PresetStore
import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.application.SecretStore
import com.deepseek.mobilechat.application.SettingsStore
import com.deepseek.mobilechat.application.TokenEstimatorPort
import com.deepseek.mobilechat.domain.Attachment
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.AttachmentKind
import com.deepseek.mobilechat.domain.Compaction
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.ContextInjection
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.Folder
import com.deepseek.mobilechat.domain.FolderId
import com.deepseek.mobilechat.domain.InjectionId
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.Tag
import com.deepseek.mobilechat.domain.TagId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

// ---------------------------------------------------------------------------
// In-memory port fakes, self-contained on purpose: the service tests must not
// depend on another package's test fixtures.
// ---------------------------------------------------------------------------

class FakeConversationStore : ConversationStore {

    val state = MutableStateFlow<Map<ConversationId, Conversation>>(emptyMap())

    fun put(vararg conversations: Conversation) {
        state.update { current -> current + conversations.associateBy { it.id } }
    }

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

    /** Append/update calls in order, for checkpoint and persistence assertions. */
    val appends = mutableListOf<Message>()
    val updates = mutableListOf<Message>()

    fun put(vararg messages: Message) {
        state.update { current -> current + messages.associateBy { it.id } }
    }

    override fun observe(conversationId: ConversationId): Flow<List<Message>> =
        state.map { snapshot(conversationId, it.values) }

    override suspend fun get(id: MessageId): Message? = state.value[id]

    override suspend fun list(conversationId: ConversationId): List<Message> =
        snapshot(conversationId, state.value.values)

    override suspend fun append(message: Message) {
        appends += message
        state.update { it + (message.id to message) }
    }

    override suspend fun update(message: Message) {
        updates += message
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
        state.map { list -> list.filter { it.conversationId == conversationId } }

    override suspend fun list(conversationId: ConversationId): List<ContextInjection> =
        state.value.filter { it.conversationId == conversationId }

    override suspend fun add(injection: ContextInjection) {
        state.update { it + injection }
    }

    override suspend fun remove(id: InjectionId) {
        state.update { list -> list.filterNot { it.id == id } }
    }
}

class FakeCompactionStore : CompactionStore {

    val state = MutableStateFlow<List<Compaction>>(emptyList())

    override fun observe(conversationId: ConversationId): Flow<List<Compaction>> =
        state.map { list -> list.filter { it.conversationId == conversationId } }

    override suspend fun list(conversationId: ConversationId): List<Compaction> =
        state.value.filter { it.conversationId == conversationId }

    override suspend fun add(compaction: Compaction) {
        state.update { it + compaction }
    }

    override suspend fun markUndone(id: CompactionId, atMillis: Long) {
        state.update { list ->
            list.map { if (it.id == id) it.copy(undoneAt = atMillis) else it }
        }
    }
}

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
            kind = when {
                file.mimeType.startsWith("image/") -> AttachmentKind.IMAGE
                file.mimeType.startsWith("text/") -> AttachmentKind.TEXT
                else -> AttachmentKind.OTHER
            },
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

    fun put(preset: Preset) {
        state.update { list -> list.filterNot { it.id == preset.id } + preset }
    }

    override fun observeAll(): Flow<List<Preset>> = state.asStateFlow()

    override suspend fun get(id: PresetId): Preset? = state.value.firstOrNull { it.id == id }

    override suspend fun upsert(preset: Preset) {
        state.update { list -> list.filterNot { it.id == preset.id } + preset }
    }

    override suspend fun delete(id: PresetId) {
        state.update { list -> list.filterNot { it.id == id } }
    }
}

class FakeProviderProfileStore : ProviderProfileStore {

    val state = MutableStateFlow<List<ProviderProfile>>(emptyList())

    fun put(profile: ProviderProfile) {
        state.update { list -> list.filterNot { it.id == profile.id } + profile }
    }

    override fun observeAll(): Flow<List<ProviderProfile>> = state.asStateFlow()

    override suspend fun get(id: ProviderId): ProviderProfile? =
        state.value.firstOrNull { it.id == id }

    override suspend fun upsert(profile: ProviderProfile) {
        state.update { list -> list.filterNot { it.id == profile.id } + profile }
    }

    override suspend fun delete(id: ProviderId) {
        state.update { list -> list.filterNot { it.id == id } }
    }
}

class FakeFolderTagStore : FolderTagStore {

    val folders = MutableStateFlow<List<Folder>>(emptyList())
    val tags = MutableStateFlow<List<Tag>>(emptyList())

    override fun observeFolders(): Flow<List<Folder>> = folders.asStateFlow()

    override fun observeTags(): Flow<List<Tag>> = tags.asStateFlow()

    override suspend fun upsertFolder(folder: Folder) {
        folders.update { list -> list.filterNot { it.id == folder.id } + folder }
    }

    override suspend fun deleteFolder(id: FolderId) {
        folders.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun upsertTag(tag: Tag) {
        tags.update { list -> list.filterNot { it.id == tag.id } + tag }
    }

    override suspend fun deleteTag(id: TagId) {
        tags.update { list -> list.filterNot { it.id == id } }
    }
}

class FakeSecretStore : SecretStore {

    val values = MutableStateFlow<Map<String, String>>(emptyMap())

    override suspend fun put(ref: String, value: String) {
        values.update { it + (ref to value) }
    }

    override suspend fun get(ref: String): String? = values.value[ref]

    override suspend fun delete(ref: String) {
        values.update { it - ref }
    }
}

class FakeSettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {

    val state = MutableStateFlow(initial)

    fun set(settings: AppSettings) {
        state.value = settings
    }

    override fun observe(): Flow<AppSettings> = state.asStateFlow()

    override suspend fun update(settings: AppSettings) {
        state.value = settings
    }
}

/**
 * Deterministic clock. [autoAdvance] makes every read move time forward, which
 * is how the streaming checkpoint throttle is exercised: real time advances
 * between events, a frozen test clock would never checkpoint.
 */
class FakeClock(
    private var now: Long = 1_000L,
    private var autoAdvanceMillis: Long = 0L,
) : ClockPort {

    override fun nowMillis(): Long {
        val current = now
        now += autoAdvanceMillis
        return current
    }

    fun advanceBy(millis: Long) {
        now += millis
    }

    fun setNow(millis: Long) {
        now = millis
    }

    fun autoAdvance(millisPerRead: Long) {
        autoAdvanceMillis = millisPerRead
    }
}

class FakeIdPort(private val prefix: String = "id") : IdPort {

    private var counter = 0

    override fun newId(): String = "$prefix-${++counter}"
}

/** Deterministic stand-in: one token per three characters, minimum one. */
class FakeTokenEstimator : TokenEstimatorPort {

    override fun estimate(text: String): Int = (text.length / 3).coerceAtLeast(1)

    override fun estimate(prompt: com.deepseek.mobilechat.application.PromptMessage): Int =
        prompt.parts.sumOf { part ->
            when (part) {
                is com.deepseek.mobilechat.domain.MessagePart.Text -> estimate(part.text)
                else -> 1
            }
        }.coerceAtLeast(1)
}

