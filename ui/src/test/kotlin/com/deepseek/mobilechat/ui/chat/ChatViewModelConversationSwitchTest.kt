package com.deepseek.mobilechat.ui.chat

import com.deepseek.mobilechat.application.AttachmentStore
import com.deepseek.mobilechat.application.ChatService
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.ContextAssembler
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
import com.deepseek.mobilechat.application.UsageTotals
import com.deepseek.mobilechat.application.log.BusyKind
import com.deepseek.mobilechat.application.log.ChatGateway
import com.deepseek.mobilechat.application.log.ChatSnapshot
import com.deepseek.mobilechat.application.log.CompactionProposalView
import com.deepseek.mobilechat.application.log.EditOutcome
import com.deepseek.mobilechat.application.log.Overlay
import com.deepseek.mobilechat.application.log.StreamingTurn
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FolderId
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
import com.deepseek.mobilechat.domain.log.TranscriptNode
import com.deepseek.mobilechat.ui.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ViewModel now has exactly one conversation source: a [ChatGateway]
 * snapshot stream. Re-pointing it at another conversation must stop the previous
 * stream, and the overlay alone must decide "busy / streaming / searching".
 *
 * The "second message of a promoted conversation flashes back to the previous
 * conversation" bug is invisible to a final-state assertion when the new
 * conversation happens to emit last, so the test records **every** state
 * emission and asserts that no emission while B is active carries A's messages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelConversationSwitchTest {

    private val a = ConversationId("conversation-a")
    private val b = ConversationId("conversation-b")

    private fun message(
        conversation: ConversationId,
        id: String,
        parent: String?,
        text: String,
        seq: Long,
        status: MessageStatus = MessageStatus.COMPLETE,
    ) = Message(
        id = MessageId(id),
        conversationId = conversation,
        parentId = parent?.let(::MessageId),
        seq = seq,
        role = if (parent == null) MessageRole.USER else MessageRole.ASSISTANT,
        parts = listOf(MessagePart.Text(text)),
        status = status,
        createdAt = seq,
    )

    private fun snapshot(
        id: ConversationId,
        messages: List<Message>,
        leaf: MessageId?,
        title: String = id.raw,
        overlay: Overlay = Overlay(),
    ) = ChatSnapshot(
        version = 1L,
        conversationId = id,
        title = title,
        messages = messages,
        activeLeafId = leaf,
        transcript = messages.mapIndexed { index, message ->
            TranscriptNode.MessageNode(index + 1L, message)
        },
        overlay = overlay,
        totals = UsageTotals(),
    )

    private fun conversation(id: ConversationId, leaf: MessageId?, title: String) = Conversation(
        id = id,
        title = title,
        activeLeafId = leaf,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `switching conversations stops the previous conversation's snapshot stream`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val aUser = message(a, "a-user", null, "A question", 1)
            val aReply = message(a, "a-reply", "a-user", "A answer", 2)
            val bUser = message(b, "b-user", null, "B question", 1)
            val bReply = message(b, "b-reply", "b-user", "B answer", 2)

            val gateway = FakeGateway(
                initial = mapOf(
                    a to snapshot(a, listOf(aUser, aReply), aReply.id, "Conversation A"),
                    b to snapshot(b, listOf(bUser, bReply), bReply.id, "Conversation B"),
                ),
            )
            val viewModel = ChatViewModel(
                services = FakeAppServices(
                    conversations = mapOf(
                        a.raw to conversation(a, aReply.id, "Conversation A"),
                        b.raw to conversation(b, bReply.id, "Conversation B"),
                    ),
                ),
                gateway = gateway,
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

            // B gets a second turn; the old flow for A is still alive in the fake
            // but nothing may subscribe to it any more.
            val bUser2 = message(b, "b-user-2", "b-reply", "B follow-up", 3)
            val bReply2 = message(b, "b-reply-2", "b-user-2", "B follow-up answer", 4)
            gateway.emit(b, snapshot(b, listOf(bUser, bReply, bUser2, bReply2), bReply2.id))
            // And A writes too — the exact event that used to make A flash back.
            gateway.emit(a, snapshot(a, listOf(aUser, aReply), aReply.id))
            advanceUntilIdle()

            val leaked = emissions.filter { state ->
                state.conversationId == b &&
                    state.messages.any { it.conversationId == a }
            }
            assertTrue(
                "conversation A's stream survived the switch and published A's messages " +
                    "into B's state: " +
                    leaked.map { state ->
                        "id=${state.conversationId?.raw} texts=${state.messages.map { it.text }}"
                    },
                leaked.isEmpty(),
            )

            // And the final state really is B's latest snapshot.
            assertEquals(b, viewModel.state.value.conversationId)
            assertEquals(
                listOf("B question", "B answer", "B follow-up", "B follow-up answer"),
                viewModel.state.value.messages.map { it.text },
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `busy flags come from the overlay, never from message status`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val user = message(a, "a-user", null, "A question", 1)
            val reply = message(a, "a-reply", "a-user", "A answer", 2)
            val gateway = FakeGateway(
                initial = mapOf(a to snapshot(a, listOf(user, reply), reply.id)),
            )
            val viewModel = ChatViewModel(
                services = FakeAppServices(conversations = mapOf(a.raw to conversation(a, reply.id, "A"))),
                gateway = gateway,
                conversationId = a,
            )
            advanceUntilIdle()

            gateway.emit(
                a,
                snapshot(
                    a,
                    listOf(user, reply),
                    reply.id,
                    overlay = Overlay(busy = BusyKind.SEND, searching = true),
                ),
            )
            advanceUntilIdle()
            assertTrue(viewModel.state.value.isSending)
            assertTrue(viewModel.state.value.isSearching)
            assertFalse(viewModel.state.value.isGenerating)

            val streaming = StreamingTurn(MessageId("a-streaming"), text = "", reasoning = "thinking")
            gateway.emit(
                a,
                snapshot(a, listOf(user, reply), reply.id, overlay = Overlay(streaming = streaming)),
            )
            advanceUntilIdle()
            assertTrue(viewModel.state.value.isGenerating)
            assertFalse(viewModel.state.value.isSending)
            // The streaming row is injected into `messages` by the engine; the
            // flag itself must not depend on that (or on message.status).
            assertTrue(viewModel.state.value.messages.none { it.status == MessageStatus.STREAMING })

            // A STREAMING message without an overlay stream is *not* "generating":
            // status is history, the overlay owns in-flight work.
            val streamed = message(a, "a-streamed", "a-reply", "partial", 3, MessageStatus.STREAMING)
            gateway.emit(a, snapshot(a, listOf(user, reply, streamed), streamed.id))
            advanceUntilIdle()
            assertFalse(viewModel.state.value.isGenerating)
            assertTrue(viewModel.state.value.messages.any { it.status == MessageStatus.STREAMING })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `dismissing a compaction restores the held text from the overlay`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val user = message(a, "a-user", null, "A question", 1)
            val reply = message(a, "a-reply", "a-user", "A answer", 2)
            val proposal = CompactionProposalView(
                id = CompactionId("compaction-1"),
                coveredCount = 3,
                tokensBefore = 900,
                tokensAfter = 300,
            )
            val gateway = FakeGateway(
                initial = mapOf(
                    a to snapshot(
                        a,
                        listOf(user, reply),
                        reply.id,
                        overlay = Overlay(
                            compactionProposal = proposal,
                            pendingUserText = "held message",
                        ),
                    ),
                ),
            )
            val viewModel = ChatViewModel(
                services = FakeAppServices(conversations = mapOf(a.raw to conversation(a, reply.id, "A"))),
                gateway = gateway,
                conversationId = a,
            )
            advanceUntilIdle()
            assertEquals(3, viewModel.state.value.compactionProposal?.coveredCount)

            // The gateway cancels the proposal but keeps the held text in the
            // overlay; the UI owns only the composer copy.
            gateway.onDismiss = { id ->
                gateway.emit(
                    id,
                    snapshot(
                        id,
                        listOf(user, reply),
                        reply.id,
                        overlay = Overlay(pendingUserText = "held message"),
                    ),
                )
            }
            viewModel.dismissCompaction()
            advanceUntilIdle()

            assertNull(viewModel.state.value.compactionProposal)
            assertEquals("held message", viewModel.state.value.draftText)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // -----------------------------------------------------------------------
    // Fakes
    // -----------------------------------------------------------------------

    /**
     * In-memory gateway: one [MutableStateFlow] per conversation plus the fixed
     * empty draft snapshot. Commands not used by these scenarios fail loudly.
     */
    private class FakeGateway(initial: Map<ConversationId, ChatSnapshot>) : ChatGateway {
        private val streams = initial.mapValues { (_, snapshot) -> MutableStateFlow(snapshot) }
        private val draft = MutableStateFlow(emptyDraft())

        var onDismiss: ((ConversationId) -> Unit)? = null

        fun emit(id: ConversationId, snapshot: ChatSnapshot) {
            streams.getValue(id).value = snapshot
        }

        override fun snapshots(conversationId: ConversationId): Flow<ChatSnapshot> =
            streams.getValue(conversationId)

        override fun draftSnapshot(): Flow<ChatSnapshot> = draft

        override suspend fun createConversation(title: String): Outcome<ConversationId> = unused()

        override suspend fun send(
            conversationId: ConversationId,
            text: String,
            attachmentIds: List<AttachmentId>,
            search: Boolean,
        ): Outcome<Unit> = unused()

        override suspend fun regenerate(messageId: MessageId): Outcome<Unit> = unused()

        override suspend fun edit(messageId: MessageId, text: String): Outcome<EditOutcome> = unused()

        override suspend fun switchBranch(messageId: MessageId): Outcome<Unit> = unused()

        override suspend fun cancel(conversationId: ConversationId): Outcome<Unit> = unused()

        override suspend fun confirmCompaction(conversationId: ConversationId): Outcome<Unit> = unused()

        override suspend fun dismissCompaction(conversationId: ConversationId): Outcome<Unit> {
            onDismiss?.invoke(conversationId)
            return Outcome.Ok(Unit)
        }

        private companion object {
            fun emptyDraft() = ChatSnapshot(
                version = 0L,
                conversationId = ConversationId("draft"),
                title = "",
                messages = emptyList(),
                activeLeafId = null,
                transcript = emptyList(),
                overlay = Overlay(),
                totals = UsageTotals(),
            )
        }
    }

    /**
     * Only the ports the ViewModel still reads outside the gateway: the
     * conversation aggregate (overrides), the preset/provider lists and title
     * refresh. Every other capability fails loudly if touched.
     */
    private class FakeAppServices(
        conversations: Map<String, Conversation>,
    ) : AppServices {
        override val conversationStore: ConversationStore = object : ConversationStore {
            override fun observeAll(): Flow<List<Conversation>> = flow { emit(emptyList()) }

            override fun observe(id: ConversationId): Flow<Conversation?> = flow { emit(null) }

            override fun search(query: String): Flow<List<Conversation>> = flow { emit(emptyList()) }

            override suspend fun get(id: ConversationId): Conversation? = conversations[id.raw]

            override suspend fun upsert(conversation: Conversation) = unused<Unit>()

            override suspend fun delete(id: ConversationId) = unused<Unit>()
        }
        override val conversations: ConversationService = FakeConversationService()
        override val presetStore: PresetStore = object : PresetStore {
            override fun observeAll(): Flow<List<Preset>> = flow { emit(emptyList()) }

            override suspend fun get(id: PresetId): Preset? = null

            override suspend fun upsert(preset: Preset) = unused<Unit>()

            override suspend fun delete(id: PresetId) = unused<Unit>()
        }
        override val providerStore: ProviderProfileStore = object : ProviderProfileStore {
            override fun observeAll(): Flow<List<ProviderProfile>> = flow { emit(emptyList()) }

            override suspend fun get(id: ProviderId): ProviderProfile? = null

            override suspend fun upsert(profile: ProviderProfile) = unused<Unit>()

            override suspend fun delete(id: ProviderId) = unused<Unit>()
        }
        override val clock: ClockPort = object : ClockPort {
            override fun nowMillis(): Long = 1L
        }

        override val chat: ChatService get() = unused()
        override val chatGateway: ChatGateway get() = unused()
        override val context: ContextService get() = unused()
        override val presets: PresetService get() = unused()
        override val providers: ProviderService get() = unused()
        override val search: SearchService get() = unused()
        override val transfer: TransferService get() = unused()
        override val queue: GenerationQueue get() = unused()
        override val messageStore: MessageStore get() = unused()
        override val attachmentStore: AttachmentStore get() = unused()
        override val folderTagStore: FolderTagStore get() = unused()
        override val contextAssembler: ContextAssembler get() = unused()
        override val fileImport: FileImportPort get() = unused()
        override val usage: UsageService get() = unused()
    }

    /** Title refresh is part of the real path; titles are a no-op here. */
    private class FakeConversationService : ConversationService {
        override fun observeConversations(): Flow<List<Conversation>> = flow { emit(emptyList()) }

        override fun search(query: String): Flow<List<Conversation>> = flow { emit(emptyList()) }

        override suspend fun create(title: String): Outcome<Conversation> = unused()

        override suspend fun rename(id: ConversationId, title: String): Outcome<Unit> = unused()

        override suspend fun delete(id: ConversationId): Outcome<Unit> = unused()

        override suspend fun moveToFolder(id: ConversationId, folderId: FolderId?): Outcome<Unit> =
            unused()

        override suspend fun setTags(id: ConversationId, tagIds: Set<TagId>): Outcome<Unit> = unused()

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
}

/** A port the scenario must not touch; an accidental call fails the test loudly. */
private fun <T> unused(): T = error("this port is not used by the scenario under test")
