package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.IdPort
import com.deepseek.mobilechat.data.local.bus.ChangeBus
import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.db.JdbcDb
import com.deepseek.mobilechat.data.local.db.Schema
import com.deepseek.mobilechat.data.local.files.AndroidFileSink
import com.deepseek.mobilechat.data.local.store.SqliteAttachmentStore
import com.deepseek.mobilechat.data.local.store.SqliteCompactionStore
import com.deepseek.mobilechat.data.local.store.SqliteConversationStore
import com.deepseek.mobilechat.data.local.store.SqliteFolderTagStore
import com.deepseek.mobilechat.data.local.store.SqliteInjectionStore
import com.deepseek.mobilechat.data.local.store.SqliteMessageStore
import com.deepseek.mobilechat.data.local.store.SqlitePresetStore
import com.deepseek.mobilechat.data.local.store.SqliteProviderProfileStore
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.ConversationOverrides
import com.deepseek.mobilechat.domain.FolderId
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.TagId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.file.Files

internal class MutableClock(var now: Long = 1_700_000_000_000L) : ClockPort {
    override fun nowMillis(): Long = now
}

internal class SequenceIdPort(private val prefix: String = "id") : IdPort {
    private var counter: Int = 0

    override fun newId(): String = "$prefix-${++counter}"
}

/**
 * One isolated database + all stores for a test.
 *
 * Uses `Dispatchers.Unconfined` so store suspensions complete eagerly, which
 * keeps the tests synchronous and deterministic.
 *
 * [schema] defaults to creating the current schema; migration tests pass a
 * frozen v1 builder to get a database that really looks like a v1 install.
 */
internal class TestEnv(
    val io: CoroutineDispatcher = Dispatchers.Unconfined,
    schema: (Db) -> Unit = Schema::create,
) : AutoCloseable {

    val db: JdbcDb = JdbcDb.inMemory()

    val bus: ChangeBus = ChangeBus()

    val ids: SequenceIdPort = SequenceIdPort()

    val clock: MutableClock = MutableClock()

    val filesDir: File = Files.createTempDirectory("mobilechat-attachments").toFile()

    val sink: AndroidFileSink = AndroidFileSink(filesDir)

    val conversations = SqliteConversationStore(db, bus, io)

    val messages = SqliteMessageStore(db, bus, io)

    val presets = SqlitePresetStore(db, bus, io)

    val providers = SqliteProviderProfileStore(db, bus, io)

    val injections = SqliteInjectionStore(db, bus, io)

    val compactions = SqliteCompactionStore(db, bus, io)

    val foldersAndTags = SqliteFolderTagStore(db, bus, io)

    val attachments = SqliteAttachmentStore(db, sink, ids, clock, bus, io)

    init {
        schema(db)
    }

    override fun close() {
        runCatching { db.close() }
        filesDir.deleteRecursively()
    }
}

internal fun conversation(
    id: String,
    title: String = "conversation $id",
    activeLeafId: String? = null,
    folderId: String? = null,
    tagIds: Set<String> = emptySet(),
    overrides: ConversationOverrides = ConversationOverrides(),
    createdAt: Long = 1_000L,
    updatedAt: Long = 2_000L,
    pinned: Boolean = false,
): Conversation = Conversation(
    id = ConversationId(id),
    title = title,
    activeLeafId = activeLeafId?.let(::MessageId),
    folderId = folderId?.let(::FolderId),
    tagIds = tagIds.map(::TagId).toSet(),
    overrides = overrides,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pinned = pinned,
)

internal fun message(
    id: String,
    conversationId: String,
    parentId: String? = null,
    seq: Long = 1L,
    role: MessageRole = MessageRole.USER,
    status: MessageStatus = MessageStatus.COMPLETE,
    parts: List<MessagePart>? = null,
    text: String = "text of $id",
    createdAt: Long = 1_000L,
    editedAt: Long? = null,
    pinned: Boolean = false,
): Message = Message(
    id = MessageId(id),
    conversationId = ConversationId(conversationId),
    parentId = parentId?.let(::MessageId),
    seq = seq,
    role = role,
    parts = parts ?: listOf(MessagePart.Text(text)),
    status = status,
    createdAt = createdAt,
    editedAt = editedAt,
    pinned = pinned,
)

internal object Defaults {
    val params: SamplingParams = SamplingParams(temperature = 0.7, topP = 0.9, maxTokens = 2048)
}
