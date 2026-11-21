package com.deepseek.mobilechat.ui.chat

import com.deepseek.mobilechat.application.AttachmentStore
import com.deepseek.mobilechat.application.ChatService
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.CompactionProposal
import com.deepseek.mobilechat.application.ContextAssembler
import com.deepseek.mobilechat.application.ContextAssembly
import com.deepseek.mobilechat.application.ContextService
import com.deepseek.mobilechat.application.ConversationService
import com.deepseek.mobilechat.application.ConversationStore
import com.deepseek.mobilechat.application.FileImportPort
import com.deepseek.mobilechat.application.FolderTagStore
import com.deepseek.mobilechat.application.GenerationQueue
import com.deepseek.mobilechat.application.MessageStore
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.PresetService
import com.deepseek.mobilechat.application.PresetStore
import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.application.ProviderService
import com.deepseek.mobilechat.application.SearchService
import com.deepseek.mobilechat.application.TransferService
import com.deepseek.mobilechat.application.UsageService
import com.deepseek.mobilechat.domain.Compaction
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.ContextInjection
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FolderId
import com.deepseek.mobilechat.domain.InjectionId
import com.deepseek.mobilechat.domain.InjectionScope
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.TagId
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.ui.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the "second message of a promoted conversation flashes
 * back to the previous conversation" bug.
 *
 * `ChatViewModel` owns exactly one conversation. When [ChatViewModel.bindTo]
 * re-points it, every collector of the **previous** conversation must stop:
 * otherwise the next database write (which is broadcast to every store on the
 * shared change bus) makes the old conversation re-query itself and publish its
 * own snapshot into the new conversation's state.
 *
 * The bug is invisible to a "final state" assertion when the new conversation
 * happens to emit last, so the test records **every** state emission and asserts
 * that no emission while B is active carries A's messages — that intermediate
 * emission is the flash the user sees.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelConversationSwitchTest {

    private val a = ConversationId("conversation-a")
    private val b = ConversationId("conversation-b")

    private fun conversation(id: ConversationId, leaf: MessageId?, title: String) = Conversation(
        id = id,
        title = title,
        activeLeafId = leaf,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun message(
        conversation: ConversationId,
        id: String,
        parent: String?,
        text: String,
        seq: Long,
    ) = Message(
        id = MessageId(id),
        conversationId = conversation,
        parentId = parent?.let(::MessageId),
        seq = seq,
        role = if (parent == null) MessageRole.USER else MessageRole.ASSISTANT,
        parts = listOf(MessagePart.Text(text)),
        status = MessageStatus.COMPLETE,
        createdAt = seq,
    )

    @Test
    fun `switching conversations stops the previous conversation's collectors`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // Conversation A: a full turn. Conversation B: the newly promoted one.
            val aUser = message(a, "a-user", null, "A question", 1)
            val aReply = message(a, "a-reply", "a-user", "A answer", 2)
            val bUser = message(b, "b-user", null, "B question", 1)
            val bReply = message(b, "b-reply", "b-user", "B answer", 2)

            val bus = FakeChangeBus()
            val conversations = FakeConversationStore(bus).apply {
                rows[a.raw] = conversation(a, aReply.id, "Conversation A")
                rows[b.raw] = conversation(b, bReply.id, "Conversation B")
            }
            val messages = FakeMessageStore(bus).apply {
                rows[a.raw] = listOf(aUser, aReply)
                rows[b.raw] = listOf(bUser, bReply)
            }

            val viewModel = ChatViewModel(
                services = FakeAppServices(
                    conversationStore = conversations,
                    messageStore = messages,
                    context = FakeContextService(bus),
                ),
                conversationId = a,
            )

            val emissions = mutableListOf<ChatUiState>()
            // Unconfined: every `_state` write is recorded instead of being
            // conflated away, so the intermediate "flash" state is visible here.
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.state.collect { emissions += it }
            }

            advanceUntilIdle()
            assertEquals(a, viewModel.state.value.conversationId)
            assertEquals(
                listOf("A question", "A answer"),
                viewModel.state.value.messages.map { it.text },
            )

            // The promotion / drawer tap: the ViewModel is re-pointed at B.
            viewModel.bindTo(b)
            advanceUntilIdle()
            assertEquals(b, viewModel.state.value.conversationId)
            assertEquals(
                listOf("B question", "B answer"),
                viewModel.state.value.messages.map { it.text },
            )

            // Any database write is broadcast to every store. Emulate the second
            // message of B being persisted: the change bus fires and every *live*
            // observer re-queries its own conversation.
            bus.notifyChanged()
            advanceUntilIdle()

            val leaked = emissions.filter { state ->
                state.conversationId == b &&
                    state.messages.any { it.conversationId == a }
            }
            assertTrue(
                "conversation A's collector survived the switch and published A's messages " +
                    "into B's state: " +
                    leaked.map { state ->
                        "id=${state.conversationId?.raw} texts=${state.messages.map { it.text }}"
                    },
                leaked.isEmpty(),
            )

            // And the final state really is B.
            assertEquals(b, viewModel.state.value.conversationId)
            assertEquals(
                listOf("B question", "B answer"),
                viewModel.state.value.messages.map { it.text },
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    // -----------------------------------------------------------------------
    // Fakes: only the ports the ViewModel reads in this scenario are real.
    // -----------------------------------------------------------------------

    /** Shared "something changed" signal, like `adapter-persistence`'s ChangeBus. */
    private class FakeChangeBus {
        private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

        fun notifyChanged() {
            events.tryEmit(Unit)
        }

        fun changes(): Flow<Unit> = events
    }

    private class FakeConversationStore(private val bus: FakeChangeBus) : ConversationStore {
        val rows = linkedMapOf<String, Conversation>()

        override fun observeAll(): Flow<List<Conversation>> = observed { rows.values.toList() }

        override fun observe(id: ConversationId): Flow<Conversation?> = observed { rows[id.raw] }

        override fun search(query: String): Flow<List<Conversation>> = observed { emptyList() }

        override suspend fun get(id: ConversationId): Conversation? = rows[id.raw]

        override suspend fun upsert(conversation: Conversation) {
            rows[conversation.id.raw] = conversation
            bus.notifyChanged()
        }

        override suspend fun delete(id: ConversationId) {
            rows.remove(id.raw)
            bus.notifyChanged()
        }

        private fun <T> observed(load: () -> T): Flow<T> = flow {
            emit(load())
            emitAll(bus.changes().map { load() }.conflate())
        }
    }

    private class FakeMessageStore(private val bus: FakeChangeBus) : MessageStore {
        val rows = linkedMapOf<String, List<Message>>()

        override fun observe(conversationId: ConversationId): Flow<List<Message>> = flow {
            emit(list(conversationId))
            emitAll(bus.changes().map { list(conversationId) }.conflate())
        }

        override suspend fun get(id: MessageId): Message? =
            rows.values.flatten().firstOrNull { it.id == id }

        override suspend fun list(conversationId: ConversationId): List<Message> =
            rows[conversationId.raw].orEmpty()

        override suspend fun append(message: Message) {
            rows[message.conversationId.raw] = list(message.conversationId) + message
            bus.notifyChanged()
        }

        override suspend fun update(message: Message) {
            rows[message.conversationId.raw] = list(message.conversationId).map {
                if (it.id == message.id) message else it
            }
            bus.notifyChanged()
        }

        override suspend fun remove(ids: Set<MessageId>) {
            rows.replaceAll { _, messages -> messages.filterNot { it.id in ids } }
            bus.notifyChanged()
        }
    }

    private class FakeContextService(private val bus: FakeChangeBus) : ContextService {
        override fun observeInjections(conversationId: ConversationId): Flow<List<ContextInjection>> =
            flow {
                emit(emptyList())
                emitAll(bus.changes().map { emptyList<ContextInjection>() }.conflate())
            }

        override fun observeCompactions(conversationId: ConversationId): Flow<List<Compaction>> =
            flow { emit(emptyList()) }

        override suspend fun assemble(conversationId: ConversationId): Outcome<ContextAssembly> =
            unused()

        override suspend fun planCompaction(conversationId: ConversationId): Outcome<CompactionProposal> =
            unused()

        override suspend fun applyCompaction(proposal: CompactionProposal): Outcome<Compaction> =
            unused()

        override suspend fun undoCompaction(compactionId: CompactionId): Outcome<Unit> = unused()

        override suspend fun addInjection(
            conversationId: ConversationId,
            content: String,
            scope: InjectionScope,
            anchorMessageId: MessageId?,
        ): Outcome<ContextInjection> = unused()

        override suspend fun removeInjection(injectionId: InjectionId): Outcome<Unit> = unused()
    }

    /**
     * `requestTitleIfNeeded` fires once a conversation has a completed reply,
     * so this port is part of the real path under test; titles are a no-op here.
     */
    private class FakeConversationService : ConversationService {
        override fun observeConversations(): Flow<List<Conversation>> = flow { emit(emptyList()) }

        override fun search(query: String): Flow<List<Conversation>> = flow { emit(emptyList()) }

        override suspend fun create(title: String): Outcome<Conversation> = unused()

        override suspend fun rename(id: ConversationId, title: String): Outcome<Unit> =
            unused()

        override suspend fun delete(id: ConversationId): Outcome<Unit> = unused()

        override suspend fun moveToFolder(id: ConversationId, folderId: FolderId?): Outcome<Unit> =
            unused()

        override suspend fun setTags(id: ConversationId, tagIds: Set<TagId>): Outcome<Unit> =
            unused()

        override suspend fun setModelOverride(
            conversationId: ConversationId,
            modelRef: ModelRef?,
            params: SamplingParams?,
            thinkingLevel: ThinkingLevel?,
        ): Outcome<Unit> = unused()

        override suspend fun applyPreset(
            conversationId: ConversationId,
            presetId: PresetId?,
        ): Outcome<Unit> = unused()

        override suspend fun ensureTitle(conversationId: ConversationId): Outcome<Unit> =
            Outcome.Ok(Unit)
    }

    /**
     * The ViewModel only touches these ports in the scenario under test; every
     * other capability throws, so an accidental call is a test failure rather
     * than a silent no-op.
     */
    private class FakeAppServices(
        override val conversationStore: ConversationStore,
        override val messageStore: MessageStore,
        override val context: ContextService,
    ) : AppServices {
        override val chat: ChatService get() = unused()
        override val conversations: ConversationService get() = FakeConversationService()
        override val presets: PresetService get() = unused()
        override val providers: ProviderService get() = unused()
        override val search: SearchService get() = unused()
        override val transfer: TransferService get() = unused()
        override val queue: GenerationQueue get() = unused()
        override val attachmentStore: AttachmentStore get() = unused()
        override val presetStore: PresetStore get() = FakePresetStore()
        override val providerStore: ProviderProfileStore get() = FakeProviderStore()
        override val folderTagStore: FolderTagStore get() = unused()
        override val contextAssembler: ContextAssembler get() = unused()
        override val clock: ClockPort get() = object : ClockPort {
            override fun nowMillis(): Long = 1L
        }
        override val fileImport: FileImportPort get() = unused()
        override val usage: UsageService get() = unused()
    }

    private class FakePresetStore : PresetStore {
        override fun observeAll(): Flow<List<Preset>> = flow { emit(emptyList()) }

        override suspend fun get(id: PresetId): Preset? = null

        override suspend fun upsert(preset: Preset) = unused<Unit>()

        override suspend fun delete(id: PresetId) = unused<Unit>()
    }

    private class FakeProviderStore : ProviderProfileStore {
        override fun observeAll(): Flow<List<ProviderProfile>> = flow { emit(emptyList()) }

        override suspend fun get(id: ProviderId): ProviderProfile? = null

        override suspend fun upsert(profile: ProviderProfile) = unused<Unit>()

        override suspend fun delete(id: ProviderId) = unused<Unit>()
    }
}

/** A port the scenario must not touch; an accidental call fails the test loudly. */
private fun <T> unused(): T = error("this port is not used by the scenario under test")
