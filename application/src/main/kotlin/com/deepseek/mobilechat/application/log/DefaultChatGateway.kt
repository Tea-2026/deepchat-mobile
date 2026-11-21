package com.deepseek.mobilechat.application.log

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.AttachmentStore
import com.deepseek.mobilechat.application.ChatCompletionPort
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.CompactionProposal
import com.deepseek.mobilechat.application.ContextAssembly
import com.deepseek.mobilechat.application.ContextPlan
import com.deepseek.mobilechat.application.ContextStrategy
import com.deepseek.mobilechat.application.ConversationStore
import com.deepseek.mobilechat.application.GenerationRequest
import com.deepseek.mobilechat.application.IdPort
import com.deepseek.mobilechat.application.ModelBudgetLookup
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.PresetStore
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.application.SearchOptions
import com.deepseek.mobilechat.application.SearchPort
import com.deepseek.mobilechat.application.SettingsStore
import com.deepseek.mobilechat.application.TokenEstimatorPort
import com.deepseek.mobilechat.application.UsageTotals
import com.deepseek.mobilechat.application.map
import com.deepseek.mobilechat.application.context.ConstantModelBudgetLookup
import com.deepseek.mobilechat.application.context.HeuristicTokenEstimator
import com.deepseek.mobilechat.application.context.strategy.ConfirmCompactionStrategy
import com.deepseek.mobilechat.application.context.strategy.NoopContextStrategy
import com.deepseek.mobilechat.application.service.ContextStrategyResolver
import com.deepseek.mobilechat.application.service.resolveDefaultModelRef
import com.deepseek.mobilechat.application.service.summarizeMessages
import com.deepseek.mobilechat.application.service.temporaryConversationTitle
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.AttachmentKind
import com.deepseek.mobilechat.domain.BlockSource
import com.deepseek.mobilechat.domain.Citation
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.ModelInfo
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.SearchHit
import com.deepseek.mobilechat.domain.Usage
import com.deepseek.mobilechat.domain.log.ConversationLog
import com.deepseek.mobilechat.domain.log.EventData
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.Surface
import com.deepseek.mobilechat.domain.log.SurfaceFolder
import com.deepseek.mobilechat.domain.log.SurfaceOp
import com.deepseek.mobilechat.domain.log.Transcript
import com.deepseek.mobilechat.domain.log.TranscriptNode
import com.deepseek.mobilechat.domain.log.TranscriptProjector
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The id `draftSnapshot()` reports for the not-yet-created conversation. */
const val DRAFT_CONVERSATION_ID_RAW: String = "draft"

/**
 * Default [ChatGateway]: the log is the only history, commands are atomic
 * batches, and streaming never enters the log (invariants I1–I12 of
 * docs/architecture-v2-log.md §13).
 *
 * Command → batch map (one command = one `ConversationLogStore.append`):
 *
 * | command             | events (one batch)                                                        |
 * |---------------------|---------------------------------------------------------------------------|
 * | `send`              | `user/message`(Append) + `turn/start` + `session/title`(first turn only)  |
 * | `regenerate`        | `turn/start`                                                              |
 * | `edit` (no child)   | `user/message`(Replace same id) + `turn/start`                            |
 * | `edit` (has child)  | `user/message`(Append sibling) + `leaf/changed` + `turn/start`            |
 * | `switchBranch`      | `leaf/changed`                                                            |
 * | `confirmCompaction` | `compaction/start` + stand-in `user/message`(Replace) + `compaction/end`  |
 * | turn completion     | `assistant/message`(Append) + `turn/end` + `leaf/changed`                 |
 * | turn failure/cancel | `turn/interrupted` + `leaf/changed`                                       |
 *
 * `Overlay` is owned exclusively by this class (I11): every command sets `busy`
 * on entry and clears it in a `finally`, and only [TurnProgressSink.onStreaming]
 * touches `streaming`. No command calls [ChatCompletionPort] before its batch is
 * durable (I9) — with the single documented exception of the model-generated
 * compaction summary, which is produced *instead of* sending a turn and only
 * after the turn has been refused, never before an append that might fail.
 *
 * ## Snapshots
 *
 * One [ChatSnapshot] per version: the log is read once per version (and cached
 * until the version changes), so a token-driven `overlay.streaming` update
 * re-uses the same [ConversationLog] and projection. A snapshot can therefore
 * never mix two versions.
 *
 * While a turn streams, the snapshot carries a synthetic `status = STREAMING`
 * assistant [Message] (derived from `overlay.streaming` + the turn metadata,
 * never written to the log) in both `messages` and `transcript`, and
 * `activeLeafId` is that message's id ("presentation leaf"). The log's own leaf
 * does not move until the terminal batch lands (I10). Once the log contains the
 * terminal row for that message id the synthetic row is suppressed in the very
 * same snapshot that first shows the real one, so there is no observable frame
 * with both or neither.
 */
class DefaultChatGateway(
    private val logStore: ConversationLogStore,
    private val draftStore: TurnDraftStore,
    private val conversationStore: ConversationStore,
    private val presetStore: PresetStore,
    private val providerProfileStore: ProviderProfileStore,
    private val attachmentStore: AttachmentStore,
    private val chatCompletion: ChatCompletionPort,
    private val searchPort: SearchPort,
    private val settingsStore: SettingsStore,
    private val clock: ClockPort,
    private val idPort: IdPort,
    tokenEstimator: TokenEstimatorPort = HeuristicTokenEstimator(),
    budgetLookup: ModelBudgetLookup = ConstantModelBudgetLookup(),
    private val strategies: Map<String, ContextStrategy> = mapOf(
        com.deepseek.mobilechat.domain.ContextStrategyIds.CONFIRM_COMPACTION to
            ConfirmCompactionStrategy(tokenEstimator),
        com.deepseek.mobilechat.domain.ContextStrategyIds.NONE to NoopContextStrategy(),
    ),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val reconcileOnStart: Boolean = true,
) : ChatGateway {

    private val contextBuilder = SurfaceContextBuilder(tokenEstimator, budgetLookup)
    private val strategyResolver = ContextStrategyResolver(presetStore, strategies)

    // -- overlay ownership (I11) -------------------------------------------
    private val overlays = MutableStateFlow<Map<ConversationId, Overlay>>(emptyMap())

    // -- version bus / caches ----------------------------------------------
    private val versions = MutableStateFlow<Map<ConversationId, Long>>(emptyMap())
    private val cacheMutex = Mutex()
    private val cachedLogs = HashMap<ConversationId, ConversationLog>()
    private val cachedProjections = HashMap<ConversationId, Projection>()

    // -- append serialization (needed for predicted compaction seqs) --------
    private val appendMutex = Mutex()

    private val activeTurns = ConcurrentHashMap<ConversationId, ActiveTurn>()
    private val streamingTurns = ConcurrentHashMap<ConversationId, StreamingMeta>()
    private val pendingCompactions = ConcurrentHashMap<ConversationId, PendingCompaction>()
    private val messageIndex = ConcurrentHashMap<MessageId, ConversationId>()
    private val knownConversations = ConcurrentHashMap.newKeySet<ConversationId>()

    private val progress = object : TurnProgressSink {
        override fun onStreaming(
            conversationId: ConversationId,
            messageId: MessageId,
            turn: StreamingTurn?,
        ) {
            val meta = streamingTurns[conversationId] ?: return
            // A newer turn superseded this one: a stale stream must never clear
            // or overwrite the progress of the live turn.
            if (meta.messageId != messageId) return
            if (turn == null) {
                updateOverlay(conversationId) { it.copy(streaming = null) }
                streamingTurns.remove(conversationId, meta)
            } else {
                updateOverlay(conversationId) { it.copy(streaming = turn) }
            }
        }
    }

    private val runner = LogTurnRunner(
        draftStore = draftStore,
        chatCompletion = chatCompletion,
        settingsStore = settingsStore,
        clock = clock,
        idPort = idPort,
        append = TurnAppend { conversationId, events, index -> append(conversationId, events, index) },
        progress = progress,
    )

    init {
        if (reconcileOnStart) {
            scope.launch { runCatching { reconcile() } }
        }
    }

    // -----------------------------------------------------------------------
    // Snapshots
    // -----------------------------------------------------------------------

    override fun snapshots(conversationId: ConversationId): Flow<ChatSnapshot> =
        combine(versions, overlays) { bumpedVersions, overlayMap ->
            bumpedVersions[conversationId] to (overlayMap[conversationId] ?: Overlay())
        }
            .transform { (expectedVersion, overlay) ->
                emit(snapshotFor(conversationId, expectedVersion, overlay))
            }
            .distinctUntilChanged()

    override fun draftSnapshot(): Flow<ChatSnapshot> = flowOf(
        ChatSnapshot(
            version = 0,
            conversationId = DRAFT_CONVERSATION_ID,
            title = "",
            messages = emptyList(),
            activeLeafId = null,
            transcript = emptyList(),
            overlay = Overlay(),
            totals = UsageTotals(),
        ),
    )

    override suspend fun createConversation(title: String): Outcome<ConversationId> {
        val conversationId = ConversationId(idPort.newId())
        val now = clock.nowMillis()
        val trimmed = title.trim()
        conversationStore.upsert(
            Conversation(
                id = conversationId,
                title = trimmed,
                activeLeafId = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        knownConversations += conversationId
        if (trimmed.isEmpty()) {
            versions.update { it + (conversationId to 0L) }
            return Outcome.Ok(conversationId)
        }
        val events = listOf(
            PendingEvent(
                eventId = idPort.newId(),
                type = EventType.SESSION_TITLE,
                time = now,
                data = EventData.SessionTitle(trimmed, source = "manual"),
            ),
        )
        return when (val appended = append(conversationId, events, ConversationIndexPatch(title = trimmed, updatedAt = now))) {
            is Outcome.Ok -> Outcome.Ok(conversationId)
            is Outcome.Err -> appended
        }
    }

    // -----------------------------------------------------------------------
    // send
    // -----------------------------------------------------------------------

    override suspend fun send(
        conversationId: ConversationId,
        text: String,
        attachmentIds: List<AttachmentId>,
        search: Boolean,
    ): Outcome<Unit> = withBusy(conversationId, BusyKind.SEND) {
        if (text.isBlank() && attachmentIds.isEmpty()) {
            return@withBusy Outcome.Err(AppError.Validation("message has neither text nor attachments"))
        }
        val conversation = conversationStore.get(conversationId)
            ?: return@withBusy Outcome.Err(AppError.Validation("conversation not found: ${conversationId.raw}"))
        knownConversations += conversationId
        // The user acted; any text held across a dismissed compaction is theirs
        // to re-send and is no longer "held by the gateway".
        updateOverlay(conversationId) { it.copy(pendingUserText = null, searching = search) }

        val preset = resolvePreset(conversation)
        val settings = contextBuilder.settings(
            overrides = conversation.overrides,
            preset = preset,
            fallbackModelRef = resolveDefaultModelRef(presetStore, providerProfileStore),
        ) ?: return@withBusy Outcome.Err(AppError.Validation("no model is configured for this conversation"))

        val log = readLogFresh(conversationId)
        val transcript = TranscriptProjector.project(log)
        val surface = SurfaceFolder.fold(log)

        val parts = when (val built = buildUserParts(text, attachmentIds)) {
            is Outcome.Ok -> built.value
            is Outcome.Err -> return@withBusy Outcome.Err(built.error)
        }

        val userMessageId = MessageId(idPort.newId())
        val assistantMessageId = MessageId(idPort.newId())
        val parentId = transcript.activeLeafId
        // Search is best effort and never enters the log: the hits only shape
        // this one request and are attached as citations to the answer.
        val searchHits = if (search) searchHitsFor(text) else emptyList()
        val assembly = contextBuilder.build(
            conversationId = conversationId,
            settings = settings,
            surface = surface,
            searchBlock = searchHits.takeIf { it.isNotEmpty() }?.let {
                searchPromptMessage(query = text.trim(), hits = it, sourceMessageId = userMessageId)
            },
            currentUser = PromptMessage(
                role = MessageRole.USER,
                parts = parts,
                sourceMessageId = userMessageId,
            ),
        )

        if (assembly.budget.isOverBudget) {
            return@withBusy holdForCompaction(conversationId, conversation, assembly, surface, text)
        }

        val now = clock.nowMillis()
        val title = transcript.title.ifBlank { temporaryConversationTitle(text) }
        val events = ArrayList<PendingEvent>(3)
        events += PendingEvent(
            eventId = idPort.newId(),
            type = EventType.USER_MESSAGE,
            time = now,
            data = EventData.UserMessage(
                messageId = userMessageId,
                parentId = parentId,
                parts = parts,
            ),
            surfaceOp = SurfaceOp.Append,
        )
        events += PendingEvent(
            eventId = idPort.newId(),
            type = EventType.TURN_START,
            time = now,
            data = EventData.TurnStart(
                messageId = assistantMessageId,
                parentId = userMessageId,
                modelInfo = settings.toModelInfo(),
            ),
        )
        val newTitle = if (transcript.title.isBlank() && title.isNotEmpty()) {
            events += PendingEvent(
                eventId = idPort.newId(),
                type = EventType.SESSION_TITLE,
                time = now,
                data = EventData.SessionTitle(title, source = "auto"),
            )
            title
        } else {
            null
        }

        // I9: the batch must be durable before the port is touched. `append`
        // returns Err without calling anything, and we return before startTurn.
        when (
            val appended = append(
                conversationId = conversationId,
                events = events,
                index = ConversationIndexPatch(
                    title = newTitle,
                    activeLeafId = userMessageId,
                    updatedAt = now,
                    modelRef = settings.modelRef,
                    thinkingLevel = settings.thinkingLevel,
                ),
            )
        ) {
            is Outcome.Err -> return@withBusy appended
            is Outcome.Ok -> Unit
        }

        val request = GenerationRequest(
            modelRef = settings.modelRef,
            systemPrompt = settings.systemPrompt,
            messages = assembly.prompt,
            params = settings.params,
            thinkingLevel = settings.thinkingLevel,
            stream = true,
        )
        launchTurn(
            conversationId = conversationId,
            assistantMessageId = assistantMessageId,
            parentId = userMessageId,
            startedAt = now,
            modelInfo = settings.toModelInfo(),
            request = request,
            citations = searchHits,
            leafOnFailure = null,
        )
        Outcome.Ok(Unit)
    }

    // -----------------------------------------------------------------------
    // regenerate
    // -----------------------------------------------------------------------

    override suspend fun regenerate(messageId: MessageId): Outcome<Unit> {
        val conversationId = conversationOf(messageId)
            ?: return Outcome.Err(AppError.Validation("message not found: ${messageId.raw}"))
        return withBusy(conversationId, BusyKind.REGENERATE) {
            val conversation = conversationStore.get(conversationId)
                ?: return@withBusy Outcome.Err(AppError.Validation("conversation not found: ${conversationId.raw}"))
            val preset = resolvePreset(conversation)
            val settings = contextBuilder.settings(
                overrides = conversation.overrides,
                preset = preset,
                fallbackModelRef = resolveDefaultModelRef(presetStore, providerProfileStore),
            ) ?: return@withBusy Outcome.Err(AppError.Validation("no model is configured for this conversation"))

            val log = readLogFresh(conversationId)
            val transcript = TranscriptProjector.project(log)
            val target = transcript.messages.firstOrNull { it.id == messageId }
                ?: return@withBusy Outcome.Err(AppError.Validation("message not found: ${messageId.raw}"))
            val surface = SurfaceFolder.fold(log)
            val targetSeq = surface.nodes.firstOrNull { it.messageId() == messageId }?.seq

            val (parentId, promptSurface) = when (target.role) {
                MessageRole.ASSISTANT -> {
                    // Answer again *without* showing the model its previous
                    // answer: the prompt is the surface prefix before that node.
                    val prefix = if (targetSeq == null) {
                        surface
                    } else {
                        Surface(surface.nodes.filter { it.seq < targetSeq })
                    }
                    (target.parentId ?: return@withBusy Outcome.Err(
                        AppError.Validation("cannot regenerate a message without a parent"),
                    )) to prefix
                }

                MessageRole.USER -> {
                    // A user leaf is a branch without its reply: replying to it
                    // is the recovery, and its prompt includes that message.
                    val prefix = if (targetSeq == null) {
                        surface
                    } else {
                        Surface(surface.nodes.filter { it.seq <= targetSeq })
                    }
                    target.id to prefix
                }

                MessageRole.SYSTEM, MessageRole.TOOL -> return@withBusy Outcome.Err(
                    AppError.Validation("only user or assistant messages can be regenerated"),
                )
            }

            // I10: the old leaf is not touched here. It is restored on failure
            // and replaced by `leaf/changed(new id)` only on completion.
            val oldLeaf = transcript.activeLeafId
            val assistantMessageId = MessageId(idPort.newId())
            val now = clock.nowMillis()
            val events = listOf(
                PendingEvent(
                    eventId = idPort.newId(),
                    type = EventType.TURN_START,
                    time = now,
                    data = EventData.TurnStart(
                        messageId = assistantMessageId,
                        parentId = parentId,
                        modelInfo = settings.toModelInfo(),
                    ),
                ),
            )
            when (
                val appended = append(
                    conversationId = conversationId,
                    events = events,
                    index = ConversationIndexPatch(
                        updatedAt = now,
                        modelRef = settings.modelRef,
                        thinkingLevel = settings.thinkingLevel,
                    ),
                )
            ) {
                is Outcome.Err -> return@withBusy appended
                is Outcome.Ok -> Unit
            }

            val assembly = contextBuilder.build(
                conversationId = conversationId,
                settings = settings,
                surface = promptSurface,
            )
            launchTurn(
                conversationId = conversationId,
                assistantMessageId = assistantMessageId,
                parentId = parentId,
                startedAt = now,
                modelInfo = settings.toModelInfo(),
                request = GenerationRequest(
                    modelRef = settings.modelRef,
                    systemPrompt = settings.systemPrompt,
                    messages = assembly.prompt,
                    params = settings.params,
                    thinkingLevel = settings.thinkingLevel,
                    stream = true,
                ),
                citations = emptyList(),
                leafOnFailure = if (target.role == MessageRole.ASSISTANT) oldLeaf else null,
            )
            Outcome.Ok(Unit)
        }
    }

    // -----------------------------------------------------------------------
    // edit
    // -----------------------------------------------------------------------

    override suspend fun edit(messageId: MessageId, text: String): Outcome<EditOutcome> {
        val conversationId = conversationOf(messageId)
            ?: return Outcome.Err(AppError.Validation("message not found: ${messageId.raw}"))
        return withBusy(conversationId, BusyKind.EDIT) {
            if (text.isBlank()) {
                return@withBusy Outcome.Err(AppError.Validation("edited message must not be empty"))
            }
            val conversation = conversationStore.get(conversationId)
                ?: return@withBusy Outcome.Err(AppError.Validation("conversation not found: ${conversationId.raw}"))
            // The user is editing, so text restored after a dismissed compaction
            // is no longer pending.
            updateOverlay(conversationId) { it.copy(pendingUserText = null) }
            val preset = resolvePreset(conversation)
            val settings = contextBuilder.settings(
                overrides = conversation.overrides,
                preset = preset,
                fallbackModelRef = resolveDefaultModelRef(presetStore, providerProfileStore),
            ) ?: return@withBusy Outcome.Err(AppError.Validation("no model is configured for this conversation"))

            val log = readLogFresh(conversationId)
            val transcript = TranscriptProjector.project(log)
            val message = transcript.messages.firstOrNull { it.id == messageId }
                ?: return@withBusy Outcome.Err(AppError.Validation("message not found: ${messageId.raw}"))
            if (message.role != MessageRole.USER) {
                return@withBusy Outcome.Err(AppError.Validation("only user messages can be edited"))
            }
            val surface = SurfaceFolder.fold(log)
            val nodeSeq = surface.nodes.firstOrNull { it.messageId() == messageId }?.seq
                ?: return@withBusy Outcome.Err(AppError.Validation("message is not on the model-visible surface"))

            val assistantMessageId = MessageId(idPort.newId())
            val now = clock.nowMillis()
            val newParts = listOf(MessagePart.Text(text))
            val hasChildren = transcript.messages.any { it.parentId == messageId }

            if (!hasChildren) {
                // In-place edit: same message id, replace-op over exactly its
                // node. The projector rewrites the transcript row, so the user
                // never sees the old text next to the new one.
                val events = listOf(
                    PendingEvent(
                        eventId = idPort.newId(),
                        type = EventType.USER_MESSAGE,
                        time = now,
                        data = EventData.UserMessage(
                            messageId = messageId,
                            parentId = message.parentId,
                            parts = newParts,
                        ),
                        surfaceOp = SurfaceOp.Replace(nodeSeq, nodeSeq),
                        sourceEventSeqs = listOf(nodeSeq),
                    ),
                    PendingEvent(
                        eventId = idPort.newId(),
                        type = EventType.TURN_START,
                        time = now,
                        data = EventData.TurnStart(
                            messageId = assistantMessageId,
                            parentId = messageId,
                            modelInfo = settings.toModelInfo(),
                        ),
                    ),
                )
                when (
                    val appended = append(
                        conversationId = conversationId,
                        events = events,
                        index = ConversationIndexPatch(
                            updatedAt = now,
                            modelRef = settings.modelRef,
                            thinkingLevel = settings.thinkingLevel,
                        ),
                    )
                ) {
                    is Outcome.Err -> return@withBusy appended
                    is Outcome.Ok -> Unit
                }
                // Re-read so the prompt contains the rewritten row exactly as
                // the surface fold will see it.
                val fresh = SurfaceFolder.fold(readLogFresh(conversationId))
                val assembly = contextBuilder.build(conversationId, settings, fresh)
                launchTurn(
                    conversationId = conversationId,
                    assistantMessageId = assistantMessageId,
                    parentId = messageId,
                    startedAt = now,
                    modelInfo = settings.toModelInfo(),
                    request = generationRequest(settings, assembly),
                    citations = emptyList(),
                    leafOnFailure = null,
                )
                Outcome.Ok(EditOutcome.InPlace)
            } else {
                // Forked edit: a sibling user message under the same parent.
                val siblingId = MessageId(idPort.newId())
                // Prompt = the branch above the edited message + the new
                // sibling. Without this the linear surface would still contain
                // the old text and its answer.
                val prefixSurface = Surface(surface.nodes.filter { it.seq < nodeSeq })
                val assembly = contextBuilder.build(
                    conversationId = conversationId,
                    settings = settings,
                    surface = prefixSurface,
                    currentUser = PromptMessage(
                        role = MessageRole.USER,
                        parts = newParts,
                        sourceMessageId = siblingId,
                    ),
                )
                val events = listOf(
                    PendingEvent(
                        eventId = idPort.newId(),
                        type = EventType.USER_MESSAGE,
                        time = now,
                        data = EventData.UserMessage(
                            messageId = siblingId,
                            parentId = message.parentId,
                            parts = newParts,
                        ),
                        surfaceOp = SurfaceOp.Append,
                    ),
                    PendingEvent(
                        eventId = idPort.newId(),
                        type = EventType.LEAF_CHANGED,
                        time = now,
                        data = EventData.LeafChanged(siblingId),
                    ),
                    PendingEvent(
                        eventId = idPort.newId(),
                        type = EventType.TURN_START,
                        time = now,
                        data = EventData.TurnStart(
                            messageId = assistantMessageId,
                            parentId = siblingId,
                            modelInfo = settings.toModelInfo(),
                        ),
                    ),
                )
                when (
                    val appended = append(
                        conversationId = conversationId,
                        events = events,
                        index = ConversationIndexPatch(
                            activeLeafId = siblingId,
                            updatedAt = now,
                            modelRef = settings.modelRef,
                            thinkingLevel = settings.thinkingLevel,
                        ),
                    )
                ) {
                    is Outcome.Err -> return@withBusy appended
                    is Outcome.Ok -> Unit
                }
                launchTurn(
                    conversationId = conversationId,
                    assistantMessageId = assistantMessageId,
                    parentId = siblingId,
                    startedAt = now,
                    modelInfo = settings.toModelInfo(),
                    request = generationRequest(settings, assembly),
                    citations = emptyList(),
                    leafOnFailure = null,
                )
                Outcome.Ok(EditOutcome.Forked(siblingId))
            }
        }
    }

    // -----------------------------------------------------------------------
    // switchBranch / cancel
    // -----------------------------------------------------------------------

    override suspend fun switchBranch(messageId: MessageId): Outcome<Unit> {
        val conversationId = conversationOf(messageId)
            ?: return Outcome.Err(AppError.Validation("message not found: ${messageId.raw}"))
        return withBusy(conversationId, BusyKind.SWITCH) {
            val log = readLogFresh(conversationId)
            val transcript = TranscriptProjector.project(log)
            if (transcript.messages.none { it.id == messageId }) {
                return@withBusy Outcome.Err(AppError.Validation("message not found: ${messageId.raw}"))
            }
            val now = clock.nowMillis()
            append(
                conversationId = conversationId,
                events = listOf(
                    PendingEvent(
                        eventId = idPort.newId(),
                        type = EventType.LEAF_CHANGED,
                        time = now,
                        data = EventData.LeafChanged(messageId),
                    ),
                ),
                index = ConversationIndexPatch(activeLeafId = messageId, updatedAt = now),
            ).map { Unit }
        }
    }

    override suspend fun cancel(conversationId: ConversationId): Outcome<Unit> =
        withBusy(conversationId, BusyKind.CANCEL) {
            val active = activeTurns[conversationId]
            if (active != null) {
                active.job.cancel()
                // The runner lands `turn/interrupted` and deletes the draft
                // inside NonCancellable; waiting here means "cancel returned" is
                // a statement about the log, not just about the job handle.
                active.job.join()
            }
            updateOverlay(conversationId) { it.copy(streaming = null) }
            Outcome.Ok(Unit)
        }

    // -----------------------------------------------------------------------
    // Compaction
    // -----------------------------------------------------------------------

    override suspend fun confirmCompaction(conversationId: ConversationId): Outcome<Unit> =
        withBusy(conversationId, BusyKind.COMPACT) {
            val pending = pendingCompactions[conversationId]
                ?: return@withBusy Outcome.Err(AppError.Validation("no compaction proposal to confirm"))
            val heldText = overlays.value[conversationId]?.pendingUserText

            val result = appendMutex.withLock {
                val log = cacheMutex.withLock { readLogFreshLocked(conversationId) }
                val surface = SurfaceFolder.fold(log)
                val range = surface.rangeFor(
                    fromMessageId = pending.proposal.fromMessageId,
                    toMessageId = pending.proposal.toMessageId,
                ) ?: return@withLock Outcome.Err(
                    AppError.BranchConflict("compaction range is no longer on the surface"),
                )

                val now = clock.nowMillis()
                val standInId = MessageId(idPort.newId())
                // Seqs are assigned contiguously by the store inside the same
                // transaction; holding `appendMutex` makes that arithmetic
                // exact. `compaction/end.summarySeq` needs the stand-in's seq,
                // and I6 forbids splitting start/end into two transactions.
                val standInSeq = log.version + 2
                val events = listOf(
                    PendingEvent(
                        eventId = idPort.newId(),
                        type = EventType.COMPACTION_START,
                        time = now,
                        data = EventData.CompactionStart(
                            compactionId = pending.id,
                            strategyId = pending.proposal.strategyId,
                            targetFromSeq = range.startSeq,
                            targetToSeq = range.endSeq,
                            tokensBefore = pending.tokensBefore,
                        ),
                    ),
                    PendingEvent(
                        eventId = idPort.newId(),
                        type = EventType.USER_MESSAGE,
                        time = now,
                        data = EventData.UserMessage(
                            messageId = standInId,
                            parentId = null,
                            parts = listOf(MessagePart.Text(pending.proposal.summary)),
                            compactionId = pending.id,
                        ),
                        surfaceOp = SurfaceOp.Replace(range.startSeq, range.endSeq),
                        sourceEventSeqs = range.seqs,
                    ),
                    PendingEvent(
                        eventId = idPort.newId(),
                        type = EventType.COMPACTION_END,
                        time = now,
                        data = EventData.CompactionEnd(
                            compactionId = pending.id,
                            summarySeq = standInSeq,
                            tokensAfter = pending.tokensAfter,
                        ),
                    ),
                )
                appendWithinLock(
                    conversationId = conversationId,
                    events = events,
                    index = ConversationIndexPatch(updatedAt = now),
                ).map { Unit }
            }

            when (result) {
                is Outcome.Err -> result // keep the proposal + held text so nothing is lost
                is Outcome.Ok -> {
                    pendingCompactions.remove(conversationId)
                    updateOverlay(conversationId) { it.copy(compactionProposal = null, pendingUserText = null) }
                    if (heldText != null) send(conversationId, heldText) else Outcome.Ok(Unit)
                }
            }
        }

    override suspend fun dismissCompaction(conversationId: ConversationId): Outcome<Unit> =
        withBusy(conversationId, BusyKind.COMPACT) {
            pendingCompactions.remove(conversationId)
            // The proposal is dropped, but the text stays in
            // `overlay.pendingUserText`: that field is how the held text is
            // restored to the user (the UI puts it back into the composer).
            updateOverlay(conversationId) { it.copy(compactionProposal = null) }
            Outcome.Ok(Unit)
        }

    // -----------------------------------------------------------------------
    // Startup reconciliation
    // -----------------------------------------------------------------------

    /**
     * Settles every draft left behind by a process death: appends
     * `turn/interrupted` for its message id and deletes the draft. This is the
     * new-path replacement for [com.deepseek.mobilechat.application.service.StalledGenerationReconciler]
     * (which only knows the old `messages` table and must keep working for it).
     *
     * Runs automatically on construction unless `reconcileOnStart = false`; the
     * composition root may also call it explicitly.
     */
    suspend fun reconcile(): List<MessageId> {
        val settled = ArrayList<MessageId>()
        val drafts = try {
            draftStore.all()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return settled
        }
        for (draft in drafts) {
            knownConversations += draft.conversationId
            val log = readLogFresh(draft.conversationId)
            val alreadySettled = log.events.any { event ->
                when (val data = event.data) {
                    is EventData.AssistantMessage -> data.messageId == draft.messageId
                    is EventData.TurnInterrupted -> data.messageId == draft.messageId
                    is EventData.TurnEnd -> data.messageId == draft.messageId
                    else -> false
                }
            }
            if (!alreadySettled) {
                val now = clock.nowMillis()
                val appended = append(
                    conversationId = draft.conversationId,
                    events = listOf(
                        PendingEvent(
                            eventId = idPort.newId(),
                            type = EventType.TURN_INTERRUPTED,
                            time = now,
                            data = EventData.TurnInterrupted(
                                messageId = draft.messageId,
                                parentId = null,
                                partialText = draft.text,
                                reason = INTERRUPTED_BY_RESTART,
                            ),
                        ),
                    ),
                    index = ConversationIndexPatch(updatedAt = now),
                )
                if (appended is Outcome.Err) continue
                settled += draft.messageId
            }
            runCatching { draftStore.delete(draft.messageId) }
        }
        return settled
    }

    // -----------------------------------------------------------------------
    // Turn launch
    // -----------------------------------------------------------------------

    private fun launchTurn(
        conversationId: ConversationId,
        assistantMessageId: MessageId,
        parentId: MessageId?,
        startedAt: Long,
        modelInfo: ModelInfo,
        request: GenerationRequest,
        citations: List<SearchHit>,
        leafOnFailure: MessageId?,
    ) {
        // One live turn per conversation: a new one supersedes a stale job, whose
        // cancellation finalizes as an interrupted turn of its own.
        activeTurns[conversationId]?.job?.cancel()
        streamingTurns[conversationId] = StreamingMeta(
            messageId = assistantMessageId,
            parentId = parentId,
            startedAt = startedAt,
            modelInfo = modelInfo,
        )
        val job = scope.launch {
            runner.run(
                conversationId = conversationId,
                assistantMessageId = assistantMessageId,
                parentId = parentId,
                request = request,
                citations = citations,
                leafOnFailure = leafOnFailure,
            )
        }
        val turn = ActiveTurn(assistantMessageId, job)
        activeTurns[conversationId] = turn
        job.invokeOnCompletion {
            activeTurns.remove(conversationId, turn)
        }
    }

    // -----------------------------------------------------------------------
    // Compaction proposal
    // -----------------------------------------------------------------------

    private suspend fun holdForCompaction(
        conversationId: ConversationId,
        conversation: Conversation,
        assembly: ContextAssembly,
        surface: Surface,
        text: String,
    ): Outcome<Unit> {
        val strategy = when (val resolved = strategyResolver.resolve(conversation)) {
            is Outcome.Ok -> resolved.value
            is Outcome.Err -> return Outcome.Err(resolved.error)
        }
        val plan = strategy.plan(assembly, assembly.budget) { messages ->
            summarizeMessages(chatCompletion, assembly, messages)
        }
        return when (plan) {
            is ContextPlan.Fits -> Outcome.Err(
                AppError.Validation("context exceeds the model window and cannot be compacted"),
            )

            is ContextPlan.Rejected -> Outcome.Err(plan.error)

            is ContextPlan.RequiresConfirmation -> {
                val proposal = plan.proposal
                val range = surface.rangeFor(proposal.fromMessageId, proposal.toMessageId)
                    ?: return Outcome.Err(
                        AppError.BranchConflict("compaction range is not on the model-visible surface"),
                    )
                val proposalId = CompactionId(idPort.newId())
                pendingCompactions[conversationId] = PendingCompaction(
                    id = proposalId,
                    proposal = proposal,
                    tokensBefore = proposal.tokensBefore,
                    tokensAfter = proposal.tokensAfter,
                )
                updateOverlay(conversationId) {
                    it.copy(
                        pendingUserText = text,
                        compactionProposal = CompactionProposalView(
                            id = proposalId,
                            coveredCount = range.seqs.size,
                            tokensBefore = proposal.tokensBefore,
                            tokensAfter = proposal.tokensAfter,
                        ),
                    )
                }
                Outcome.Err(AppError.BudgetExceeded(proposal))
            }
        }
    }

    // -----------------------------------------------------------------------
    // Snapshot assembly
    // -----------------------------------------------------------------------

    private suspend fun snapshotFor(
        conversationId: ConversationId,
        expectedVersion: Long?,
        overlay: Overlay,
    ): ChatSnapshot {
        val (log, projection) = cacheMutex.withLock {
            val cached = cachedLogs[conversationId]
            val log = if (cached != null && (expectedVersion == null || cached.version == expectedVersion)) {
                cached
            } else {
                logStore.log(conversationId).also { cachedLogs[conversationId] = it }
            }
            val projection = cachedProjections[conversationId]?.takeIf { it.version == log.version }
                ?: project(log).also { cachedProjections[conversationId] = it }
            log to projection
        }
        return assembleSnapshot(log, projection, overlay)
    }

    private fun assembleSnapshot(
        log: ConversationLog,
        projection: Projection,
        overlay: Overlay,
    ): ChatSnapshot {
        val transcript = projection.transcript
        transcript.messages.forEach { messageIndex[it.id] = log.conversationId }

        val streaming = overlay.streaming
        val meta = streaming?.let { streamingTurns[log.conversationId] }
        // Otherwise the terminal batch has already landed (completion or
        // interruption) and the real row is in the projection; suppress the
        // synthetic so the transition is single-row at every snapshot.
        val settled = streaming != null && transcript.messages.any { it.id == streaming.messageId }
        val synthetic = if (streaming != null && meta != null && !settled) {
            syntheticStreamingMessage(log, streaming, meta)
        } else {
            null
        }

        val messages = if (synthetic != null) transcript.messages + synthetic else transcript.messages
        val nodes = if (synthetic != null) {
            transcript.nodes + TranscriptNode.MessageNode(seq = log.version + 1, message = synthetic)
        } else {
            transcript.nodes
        }

        return ChatSnapshot(
            version = log.version,
            conversationId = log.conversationId,
            title = transcript.title,
            messages = messages,
            // Presentation leaf: while streaming the UI must be able to walk
            // `pathTo(activeLeafId)` and land on the synthetic row. The log's
            // own leaf is untouched until the terminal batch lands (I10).
            activeLeafId = synthetic?.id ?: transcript.activeLeafId,
            transcript = nodes,
            overlay = overlay,
            totals = projection.totals,
            modelRef = transcript.modelRef,
            thinkingLevel = transcript.thinkingLevel,
        )
    }

    private fun syntheticStreamingMessage(
        log: ConversationLog,
        streaming: StreamingTurn,
        meta: StreamingMeta,
    ): Message = Message(
        id = streaming.messageId,
        conversationId = log.conversationId,
        parentId = meta.parentId,
        seq = log.version + 1,
        role = MessageRole.ASSISTANT,
        parts = buildList {
            add(MessagePart.Text(streaming.text))
            if (streaming.reasoning.isNotEmpty()) add(MessagePart.Reasoning(streaming.reasoning))
        },
        status = MessageStatus.STREAMING,
        createdAt = meta.startedAt,
        modelInfo = meta.modelInfo,
    )

    private fun project(log: ConversationLog): Projection {
        val transcript = TranscriptProjector.project(log)
        var totals = UsageTotals()
        log.events.forEach { event ->
            val usage = (event.data as? EventData.AssistantMessage)?.usage ?: return@forEach
            totals = totals + usage.toTotals()
        }
        return Projection(log.version, transcript, totals)
    }

    // -----------------------------------------------------------------------
    // Append / caches
    // -----------------------------------------------------------------------

    private suspend fun append(
        conversationId: ConversationId,
        events: List<PendingEvent>,
        index: ConversationIndexPatch?,
    ): Outcome<Long> = appendMutex.withLock {
        appendWithinLock(conversationId, events, index)
    }

    private suspend fun appendWithinLock(
        conversationId: ConversationId,
        events: List<PendingEvent>,
        index: ConversationIndexPatch?,
    ): Outcome<Long> {
        val result = logStore.append(conversationId, events, index)
        if (result is Outcome.Ok) {
            knownConversations += conversationId
            cacheMutex.withLock {
                cachedLogs.remove(conversationId)
                cachedProjections.remove(conversationId)
            }
            versions.update { it + (conversationId to result.value) }
        }
        return result
    }

    private suspend fun readLogFresh(conversationId: ConversationId): ConversationLog =
        cacheMutex.withLock { readLogFreshLocked(conversationId) }

    /** Caller must hold [cacheMutex]. */
    private suspend fun readLogFreshLocked(conversationId: ConversationId): ConversationLog =
        logStore.log(conversationId).also { cachedLogs[conversationId] = it }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun updateOverlay(conversationId: ConversationId, transform: (Overlay) -> Overlay) {
        overlays.update { current ->
            current + (conversationId to transform(current[conversationId] ?: Overlay()))
        }
    }

    private suspend fun <T> withBusy(
        conversationId: ConversationId,
        kind: BusyKind,
        block: suspend () -> Outcome<T>,
    ): Outcome<T> {
        updateOverlay(conversationId) { it.copy(busy = kind) }
        return try {
            block()
        } finally {
            // I11: whatever happened — success, error, cancellation — the
            // command is not in flight any more.
            updateOverlay(conversationId) { it.copy(busy = null, searching = false) }
        }
    }

    private suspend fun resolvePreset(conversation: Conversation): Preset? {
        conversation.overrides.presetId?.let { presetId ->
            presetStore.get(presetId)?.let { return it }
        }
        val presets = try {
            presetStore.observeAll().first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emptyList()
        }
        return presets.firstOrNull { it.isDefault } ?: presets.firstOrNull()
    }

    private suspend fun conversationOf(messageId: MessageId): ConversationId? {
        messageIndex[messageId]?.let { return it }
        val candidates = LinkedHashSet<ConversationId>()
        candidates += knownConversations
        runCatching { conversationStore.observeAll().first() }
            .getOrElse { emptyList() }
            .forEach { candidates += it.id }
        for (conversationId in candidates) {
            val transcript = try {
                TranscriptProjector.project(readLogFresh(conversationId))
            } catch (_: Throwable) {
                continue
            }
            transcript.messages.forEach { messageIndex[it.id] = conversationId }
            if (transcript.messages.any { it.id == messageId }) return conversationId
        }
        return null
    }

    private suspend fun buildUserParts(
        text: String,
        attachmentIds: List<AttachmentId>,
    ): Outcome<List<MessagePart>> {
        val parts = ArrayList<MessagePart>(attachmentIds.size + 1)
        if (text.isNotBlank()) parts += MessagePart.Text(text)
        for (id in attachmentIds) {
            val attachment = attachmentStore.get(id)
                ?: return Outcome.Err(AppError.Validation("attachment not found: ${id.raw}"))
            parts += when (attachment.kind) {
                AttachmentKind.IMAGE -> MessagePart.Image(attachmentId = id)
                else -> MessagePart.FileRef(attachmentId = id)
            }
        }
        if (parts.isEmpty()) {
            return Outcome.Err(AppError.Validation("message has neither text nor attachments"))
        }
        return Outcome.Ok(parts)
    }

    private suspend fun searchHitsFor(query: String): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        return try {
            if (!searchPort.isConfigured()) return emptyList()
            when (val response = searchPort.search(query.trim(), SearchOptions())) {
                is Outcome.Ok -> response.value.hits
                is Outcome.Err -> emptyList() // search is best effort; never fail the turn
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun searchPromptMessage(
        query: String,
        hits: List<SearchHit>,
        sourceMessageId: MessageId,
    ): PromptMessage {
        val body = buildString {
            append("查询：").append(query)
            hits.forEachIndexed { index, hit ->
                append('\n')
                append(index + 1).append(". ").append(hit.title)
                append('\n').append(hit.url)
                if (hit.snippet.isNotBlank()) append('\n').append(hit.snippet)
            }
        }
        return PromptMessage(
            role = MessageRole.USER,
            parts = listOf(
                MessagePart.ContextBlock(
                    title = "搜索结果：$query",
                    body = body,
                    source = BlockSource.SEARCH,
                    citations = hits.map { Citation(it.title, it.url) },
                ),
            ),
            sourceMessageId = sourceMessageId,
        )
    }

    private fun generationRequest(
        settings: SurfaceContextBuilder.TurnSettings,
        assembly: ContextAssembly,
    ): GenerationRequest = GenerationRequest(
        modelRef = settings.modelRef,
        systemPrompt = settings.systemPrompt,
        messages = assembly.prompt,
        params = settings.params,
        thinkingLevel = settings.thinkingLevel,
        stream = true,
    )

    // -----------------------------------------------------------------------
    // Internal state types
    // -----------------------------------------------------------------------

    private data class Projection(
        val version: Long,
        val transcript: Transcript,
        val totals: UsageTotals,
    )

    private data class ActiveTurn(val messageId: MessageId, val job: Job)

    private data class StreamingMeta(
        val messageId: MessageId,
        val parentId: MessageId?,
        val startedAt: Long,
        val modelInfo: ModelInfo?,
    )

    private data class PendingCompaction(
        val id: CompactionId,
        val proposal: CompactionProposal,
        val tokensBefore: Int,
        val tokensAfter: Int,
    )

    companion object {
        val DRAFT_CONVERSATION_ID: ConversationId = ConversationId(DRAFT_CONVERSATION_ID_RAW)

        const val INTERRUPTED_BY_RESTART: String = "生成已中断：应用在生成过程中退出"
    }
}

/** Sums one assistant message's usage into the conversation totals. */
internal fun Usage.toTotals(): UsageTotals = UsageTotals(
    promptTokens = promptTokens.toLong(),
    cachedPromptTokens = cachedPromptTokens.toLong(),
    completionTokens = completionTokens.toLong(),
    costCny = cost ?: 0.0,
    messageCount = 1,
)
