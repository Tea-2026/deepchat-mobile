package com.deepseek.mobilechat.application.log.testutil

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.AppSettings
import com.deepseek.mobilechat.application.ChatCompletionPort
import com.deepseek.mobilechat.application.CompactionProposal
import com.deepseek.mobilechat.application.ContextAssembly
import com.deepseek.mobilechat.application.ContextPlan
import com.deepseek.mobilechat.application.ContextStrategy
import com.deepseek.mobilechat.application.log.ConversationIndexPatch
import com.deepseek.mobilechat.application.log.ConversationLogStore
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.GenerationRequest
import com.deepseek.mobilechat.application.ModelBudgetLookup
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.log.PendingEvent
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.SearchOptions
import com.deepseek.mobilechat.application.SearchPort
import com.deepseek.mobilechat.application.SearchResponse
import com.deepseek.mobilechat.application.log.TurnDraftStore
import com.deepseek.mobilechat.application.service.testutil.FakeAttachmentStore
import com.deepseek.mobilechat.application.service.testutil.FakeClock
import com.deepseek.mobilechat.application.service.testutil.FakeConversationStore
import com.deepseek.mobilechat.application.service.testutil.FakeIdPort
import com.deepseek.mobilechat.application.service.testutil.FakePresetStore
import com.deepseek.mobilechat.application.service.testutil.FakeProviderProfileStore
import com.deepseek.mobilechat.application.service.testutil.FakeSettingsStore
import com.deepseek.mobilechat.application.service.testutil.conversation
import com.deepseek.mobilechat.application.service.testutil.preset
import com.deepseek.mobilechat.application.service.testutil.providerProfile
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.ContextBudget
import com.deepseek.mobilechat.domain.ContextStrategyIds
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.SearchHit
import com.deepseek.mobilechat.domain.Usage
import com.deepseek.mobilechat.domain.log.ConversationLog
import com.deepseek.mobilechat.domain.log.EventData
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.SessionEvent
import com.deepseek.mobilechat.domain.log.SurfaceFolder
import com.deepseek.mobilechat.domain.log.SurfaceOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/**
 * In-memory [ConversationLogStore] that behaves like the real adapter where it
 * matters for the gateway: seqs are assigned inside one transaction, the batch
 * is validated by the same fold the app uses, and an append can be made to fail.
 */
class InMemoryConversationLogStore : ConversationLogStore {

    data class AppendCall(
        val conversationId: ConversationId,
        val events: List<PendingEvent>,
        val index: ConversationIndexPatch?,
    )

    private val logs = MutableStateFlow<Map<ConversationId, ConversationLog>>(emptyMap())

    /** Every successful append, in order. */
    val appends = mutableListOf<AppendCall>()

    /** When set, the next append (then every append if [alwaysFail]) fails. */
    var failNextAppend: AppError? = null
    var appendFailure: AppError? = null

    /** Runs before each append attempt; used to assert call ordering. */
    var beforeAppend: (suspend () -> Unit)? = null

    fun seed(conversationId: ConversationId, events: List<SessionEvent>) {
        logs.update { it + (conversationId to ConversationLog(conversationId, events)) }
    }

    fun events(conversationId: ConversationId): List<SessionEvent> =
        logs.value[conversationId]?.events ?: emptyList()

    fun logOf(conversationId: ConversationId): ConversationLog =
        logs.value[conversationId] ?: ConversationLog(conversationId, emptyList())

    override suspend fun append(
        conversationId: ConversationId,
        events: List<PendingEvent>,
        index: ConversationIndexPatch?,
    ): Outcome<Long> {
        beforeAppend?.invoke()
        val failure = appendFailure ?: failNextAppend.also { failNextAppend = null }
        if (failure != null) return Outcome.Err(failure)

        val current = events(conversationId)
        var seq = current.size.toLong()
        val appended = events.map { pending ->
            seq += 1
            SessionEvent(
                seq = seq,
                eventId = pending.eventId,
                type = pending.type,
                time = pending.time,
                data = pending.data,
                schemaVersion = pending.schemaVersion,
                surfaceOp = pending.surfaceOp,
                sourceEventSeqs = pending.sourceEventSeqs,
            )
        }
        val next = ConversationLog(conversationId, current + appended)
        // The real adapter validates before committing; folding here catches an
        // invalid batch in tests instead of letting it poison later snapshots.
        appended.forEach { it.validate() }
        SurfaceFolder.fold(next)
        logs.update { it + (conversationId to next) }
        appends += AppendCall(conversationId, events, index)
        return Outcome.Ok(next.version)
    }

    override suspend fun log(conversationId: ConversationId): ConversationLog = logOf(conversationId)

    override suspend fun delete(conversationId: ConversationId): Outcome<Unit> {
        logs.update { it - conversationId }
        return Outcome.Ok(Unit)
    }
}

class InMemoryTurnDraftStore : TurnDraftStore {

    private val state = MutableStateFlow<Map<MessageId, TurnDraftStore.TurnDraft>>(emptyMap())

    val puts = mutableListOf<TurnDraftStore.TurnDraft>()
    val deletes = mutableListOf<MessageId>()

    fun seed(conversationId: ConversationId, messageId: MessageId, text: String, reasoning: String = "") {
        state.update {
            it + (messageId to TurnDraftStore.TurnDraft(messageId, conversationId, text, reasoning, 0L))
        }
    }

    override suspend fun put(
        conversationId: ConversationId,
        messageId: MessageId,
        text: String,
        reasoning: String,
    ) {
        val draft = TurnDraftStore.TurnDraft(messageId, conversationId, text, reasoning, 0L)
        puts += draft
        state.update { it + (messageId to draft) }
    }

    override suspend fun get(messageId: MessageId): TurnDraftStore.TurnDraft? = state.value[messageId]

    override suspend fun delete(messageId: MessageId) {
        deletes += messageId
        state.update { it - messageId }
    }

    override suspend fun all(): List<TurnDraftStore.TurnDraft> = state.value.values.toList()
}

/** Scripted completion port; [beforeStream] runs before the first event is emitted. */
class RecordingChatCompletion : ChatCompletionPort {

    val requests = mutableListOf<GenerationRequest>()

    var events: List<GenerationEvent> = listOf(GenerationEvent.Finished(FinishReason.STOP))
    var hangAfter: Int? = null
    var beforeStream: (() -> Unit)? = null

    override fun stream(request: GenerationRequest): Flow<GenerationEvent> = flow {
        requests += request
        beforeStream?.invoke()
        val limit = hangAfter ?: events.size
        events.take(limit).forEach { emit(it) }
        if (hangAfter != null) awaitCancellation()
    }
}

class FakeSearchPort : SearchPort {

    var configured: Boolean = true
    var hits: List<SearchHit> = emptyList()
    var failure: AppError? = null
    val queries = mutableListOf<String>()

    override suspend fun isConfigured(): Boolean = configured

    override suspend fun search(query: String, options: SearchOptions): Outcome<SearchResponse> {
        queries += query
        failure?.let { return Outcome.Err(it) }
        return Outcome.Ok(SearchResponse(query, hits))
    }
}

/** Budget lookup whose window can change between calls (proposal, then resend). */
class SwitchableBudgetLookup(vararg windows: Int) : ModelBudgetLookup {

    private val queue = ArrayDeque(windows.toList())
    var last: Int = windows.lastOrNull() ?: 100_000

    override fun contextWindow(modelRef: ModelRef): Int {
        if (queue.isNotEmpty()) last = queue.removeFirst()
        return last
    }
}

/** Strategy that proposes the given message range with a fixed summary. */
class FixedProposalStrategy(
    private val from: MessageId,
    private val to: MessageId,
    private val summary: String = "压缩摘要",
    override val id: String = ContextStrategyIds.CONFIRM_COMPACTION,
) : ContextStrategy {

    var planCalls: Int = 0

    override suspend fun plan(
        assembly: ContextAssembly,
        budget: ContextBudget,
        summarize: suspend (List<com.deepseek.mobilechat.application.PromptMessage>) -> String,
    ): ContextPlan {
        planCalls++
        return ContextPlan.RequiresConfirmation(
            CompactionProposal(
                conversationId = assembly.conversationId,
                fromMessageId = from,
                toMessageId = to,
                coveredCount = 2,
                summary = summary,
                tokensBefore = budget.used,
                tokensAfter = budget.used / 2,
                strategyId = id,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Deterministic fixtures
// ---------------------------------------------------------------------------

fun seedConversation(conversations: FakeConversationStore, id: String = "c1"): ConversationId {
    val conversationId = ConversationId(id)
    conversations.put(conversation(id = id))
    return conversationId
}

fun seedDefaultModel(
    presets: FakePresetStore,
    providers: FakeProviderProfileStore,
) {
    presets.put(
        preset(
            id = "preset-1",
            isDefault = true,
            systemPrompt = "You are a test assistant.",
            modelRef = ModelRef(ProviderId("provider-1"), ModelId("model-1")),
        ),
    )
    providers.put(providerProfile(id = "provider-1", defaultModelId = "model-1"))
}

fun userEvent(
    seq: Long,
    messageId: String,
    text: String,
    parentId: String? = null,
): SessionEvent = SessionEvent(
    seq = seq,
    eventId = "event-$seq",
    type = EventType.USER_MESSAGE,
    time = seq * 1_000L,
    data = EventData.UserMessage(
        messageId = MessageId(messageId),
        parentId = parentId?.let { MessageId(it) },
        parts = listOf(MessagePart.Text(text)),
    ),
    surfaceOp = SurfaceOp.Append,
)

fun assistantEvent(
    seq: Long,
    messageId: String,
    text: String,
    parentId: String? = null,
    usage: Usage? = null,
    parts: List<MessagePart> = listOf(MessagePart.Text(text)),
): SessionEvent = SessionEvent(
    seq = seq,
    eventId = "event-$seq",
    type = EventType.ASSISTANT_MESSAGE,
    time = seq * 1_000L,
    data = EventData.AssistantMessage(
        messageId = MessageId(messageId),
        parentId = parentId?.let { MessageId(it) },
        parts = parts,
        usage = usage,
        finishReason = FinishReason.STOP,
    ),
    surfaceOp = SurfaceOp.Append,
)

fun toolResultEvent(seq: Long, toolUseId: String, content: String): SessionEvent = SessionEvent(
    seq = seq,
    eventId = "event-$seq",
    type = EventType.TOOL_RESULT,
    time = seq * 1_000L,
    data = EventData.ToolResult(toolUseId = toolUseId, content = content),
    surfaceOp = SurfaceOp.Append,
)

fun leafEvent(seq: Long, messageId: String?): SessionEvent = SessionEvent(
    seq = seq,
    eventId = "event-$seq",
    type = EventType.LEAF_CHANGED,
    time = seq * 1_000L,
    data = EventData.LeafChanged(messageId?.let { MessageId(it) }),
)

/** Convenience defaults for a gateway built inside `runTest`. */
class LogHarness(
    private val scope: CoroutineScope,
    val reconcileOnStart: Boolean = false,
) {
    val logStore = InMemoryConversationLogStore()
    val drafts = InMemoryTurnDraftStore()
    val conversations = FakeConversationStore()
    val presets = FakePresetStore()
    val providers = FakeProviderProfileStore()
    val attachments = FakeAttachmentStore()
    val completion = RecordingChatCompletion()
    val search = FakeSearchPort()
    val settings = FakeSettingsStore(AppSettings(streamingCheckpointMillis = 0L))
    val clock = FakeClock(autoAdvanceMillis = 1_000L)
    val ids = FakeIdPort()
    val strategies = mutableMapOf<String, ContextStrategy>()
    var budgetLookup: ModelBudgetLookup = object : ModelBudgetLookup {
        override fun contextWindow(modelRef: ModelRef): Int = 100_000
    }

    init {
        seedDefaultModel(presets, providers)
    }

    fun seed(conversationId: ConversationId, vararg events: SessionEvent) {
        logStore.seed(conversationId, events.toList())
    }

    fun gateway(): com.deepseek.mobilechat.application.log.DefaultChatGateway = com.deepseek.mobilechat.application.log.DefaultChatGateway(
        logStore = logStore,
        draftStore = drafts,
        conversationStore = conversations,
        presetStore = presets,
        providerProfileStore = providers,
        attachmentStore = attachments,
        chatCompletion = completion,
        searchPort = search,
        settingsStore = settings,
        clock = clock,
        idPort = ids,
        budgetLookup = budgetLookup,
        strategies = strategies.toMap(),
        scope = scope,
        reconcileOnStart = reconcileOnStart,
    )
}
