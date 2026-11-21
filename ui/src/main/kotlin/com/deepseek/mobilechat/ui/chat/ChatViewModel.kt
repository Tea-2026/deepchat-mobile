package com.deepseek.mobilechat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.CompactionProposal
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.log.BusyKind
import com.deepseek.mobilechat.application.log.ChatGateway
import com.deepseek.mobilechat.application.log.ChatSnapshot
import com.deepseek.mobilechat.application.log.CompactionProposalView
import com.deepseek.mobilechat.application.log.EditOutcome
import com.deepseek.mobilechat.application.log.Overlay
import com.deepseek.mobilechat.application.log.StreamingTurn
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.ConversationOverrides
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.MessageTree
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.ui.AppServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat screen state holder.
 *
 * **One source of truth.** Everything about the conversation comes from
 * [ChatGateway]: exactly one immutable [ChatSnapshot] per version. The ViewModel
 * stores the latest snapshot and runs only *presentation* derivation on it (the
 * active path via [MessageTree]/[visibleMessages], branch slots via
 * [buildBranches], the transcript rows via [buildTranscript]). It never derives
 * business state: which messages exist, whether something is in flight and what
 * the leaf is all come from the snapshot, which is what removes the old
 * "message store and conversation store disagree" bug class.
 *
 * What remains on [AppServices] is deliberately *not* chat history: provider and
 * preset lists for the model chip, conversation overrides (the gateway has no
 * command for them), attachments, title refresh and the non-gateway commands
 * (`delete`, `pin`, `continueGeneration` — see the KDoc on each).
 *
 * **Draft conversations** (spec §2): [conversationId] may be `null`. In that mode
 * the screen consumes [ChatGateway.draftSnapshot] (an empty placeholder); the
 * first send calls [ChatGateway.createConversation] and then [ChatGateway.send].
 *
 * Failures are **one-shot events** on [errors] — putting them in the state would
 * replay the same snackbar forever after every recomposition.
 */
class ChatViewModel(
    internal val services: AppServices,
    private val gateway: ChatGateway,
    /** `null` starts on an unsaved draft (spec §2). */
    conversationId: ConversationId?,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState(conversationId = conversationId))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** One-shot failures. Never stored in [state]. */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /** One-shot confirmations ("已压缩 N 条消息"…). */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /** `null` = the unsaved draft. */
    private val activeId = MutableStateFlow(conversationId)
    val activeConversationId: StateFlow<ConversationId?> = activeId.asStateFlow()

    /** The one snapshot this screen renders; `null` until the first emission. */
    private var snapshot: ChatSnapshot? = null

    /**
     * The conversation aggregate, fetched **once** per conversation and used only
     * for the fields the snapshot deliberately does not carry: sampling params,
     * system prompt and the preset binding. Chat history never comes from here.
     */
    private var conversation: Conversation? = null

    /** True when the snapshot flow for the active id failed; stops the spinner. */
    private var loadFailed = false

    /** Overrides picked before the conversation existed; applied on creation. */
    private var draftOverrides = ConversationOverrides()

    private var providersSnapshot: List<ProviderProfile> = emptyList()

    /** Presets decide the effective model/thinking when the chat has no override. */
    private var presetsSnapshot: List<Preset> = emptyList()

    /** Display names learned from the model picker, keyed by model id. */
    private val modelNames = mutableMapOf<ModelId, String>()

    private val thinkingStartedAt = mutableMapOf<MessageId, Long>()
    private val thinkingMeasured = mutableMapOf<MessageId, Int>()

    /** The in-flight send (search phase included). Guards double taps only. */
    private var sendJob: Job? = null

    /** Auto-titling fires at most once per conversation. */
    private val titleRequested = mutableSetOf<ConversationId>()

    init {
        viewModelScope.launch {
            // Re-subscribes whenever the active conversation changes. The
            // collectors must be children of *this* block (hence `coroutineScope`,
            // which suspends until they finish): a bare `launch` here would attach
            // to `viewModelScope` instead, so the previous conversation's
            // collector would keep running after a switch and publish its
            // snapshots into the next conversation's state.
            activeId.collectLatest { id ->
                snapshot = null
                conversation = null
                loadFailed = false
                recompute()
                if (id == null) {
                    collectSnapshots(gateway.draftSnapshot(), expected = null)
                } else {
                    coroutineScope {
                        launch { loadOverridesOnly(id) }
                        launch { collectSnapshots(gateway.snapshots(id), expected = id) }
                        launch { collectInjections(id) }
                    }
                }
            }
        }
        viewModelScope.launch {
            services.providerStore.observeAll()
                .catch { /* the model chip degrades to the raw model id */ }
                .collect { providers ->
                    providersSnapshot = providers
                    recompute()
                }
        }
        viewModelScope.launch {
            services.presetStore.observeAll()
                .catch { /* fall back to the provider default */ }
                .collect { presets ->
                    presetsSnapshot = presets
                    recompute()
                }
        }
        recompute()
    }

    /**
     * Manual context injections are not chat history — they are a side panel with
     * its own lifecycle — so they still come from the context service instead of
     * `ChatSnapshot`. Scoped to the conversation by the caller, so a switch cancels
     * it and the badge cannot show the previous conversation's injections.
     */
    private suspend fun collectInjections(id: ConversationId) {
        try {
            services.context.observeInjections(id)
                .catch { /* the badge is cosmetic; the panel reports its own errors */ }
                .collect { list -> _state.update { it.copy(injections = list) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A broken context service must never take the chat down with it.
        }
    }

    /** The single subscription: one flow, one snapshot at a time. */
    private suspend fun collectSnapshots(flow: Flow<ChatSnapshot>, expected: ConversationId?) {
        flow
            .catch { error ->
                if (activeId.value != expected) return@catch
                loadFailed = true
                _errors.tryEmit("加载会话失败：${error.message ?: "未知错误"}")
                recompute()
            }
            .collect { incoming ->
                // A late emission from the conversation we just left must never
                // reach the next one's state.
                if (activeId.value != expected) return@collect
                if (expected != null && incoming.conversationId != expected) return@collect
                snapshot = incoming
                recompute()
            }
    }

    /**
     * Reads the non-history aggregate once. Overrides are still written through
     * the conversation service (the gateway has no command for them), so a store
     * *collector* is neither needed nor allowed here — a one-shot read cannot
     * race a snapshot because it never updates chat state.
     */
    private suspend fun loadOverridesOnly(id: ConversationId) {
        val loaded = try {
            services.conversationStore.get(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _errors.tryEmit("读取会话设置失败：${error.message ?: "未知错误"}")
            null
        }
        if (activeId.value != id) return
        conversation = loaded
        recompute()
    }

    // -----------------------------------------------------------------------
    // Navigation between conversations / drafts
    // -----------------------------------------------------------------------

    /** Points this ViewModel at another conversation, or back at the draft. */
    fun bindTo(conversationId: ConversationId?) {
        if (activeId.value == conversationId) return
        activeId.value = conversationId
    }

    /**
     * Discards the current draft and starts a fresh one. Only the draft is
     * touched: a draft the user already typed into is reset explicitly here,
     * never implicitly by switching conversations.
     */
    fun startNewDraft() {
        draftOverrides = ConversationOverrides()
        _state.update {
            it.copy(
                draftText = "",
                pendingAttachments = emptyList(),
                compactionProposal = null,
            )
        }
        activeId.value = null
        recompute()
    }

    // -----------------------------------------------------------------------
    // Composer state
    // -----------------------------------------------------------------------

    fun updateDraftText(text: String) {
        _state.update { it.copy(draftText = text) }
    }

    // -----------------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------------

    fun send(text: String, ephemeralInjection: String? = null) {
        val trimmed = text.trim()
        val attachmentIds = AttachmentDraft.idsOf(_state.value.pendingAttachments)
        if (trimmed.isEmpty() && attachmentIds.isEmpty()) return
        if (_state.value.isBusy || sendJob?.isActive == true) return
        val searchFirst = _state.value.searchEnabled && trimmed.isNotEmpty()

        // The composer is emptied immediately; a failure puts the text back.
        _state.update { it.copy(draftText = "") }

        sendJob = viewModelScope.launch {
            var created: ConversationId? = null
            try {
                val target = activeId.value ?: when (val outcome = gateway.createConversation()) {
                    is Outcome.Ok -> {
                        created = outcome.value
                        outcome.value
                    }

                    is Outcome.Err -> {
                        restoreDraftText(trimmed)
                        handleError(outcome.error)
                        return@launch
                    }
                }

                if (created != null) {
                    applyDraftOverrides(target)
                    promote(target, attachmentIds)
                }

                when (val outcome = gateway.send(target, trimmed, attachmentIds, searchFirst)) {
                    is Outcome.Ok -> _state.update { it.copy(pendingAttachments = emptyList()) }
                    is Outcome.Err -> {
                        restoreDraftText(trimmed)
                        handleError(outcome.error)
                        if (created != null) rollback(created, trimmed)
                    }
                }
            } catch (cancelled: CancellationException) {
                // Stopped while the draft's conversation was being created: give
                // the text back and make sure the row does not survive.
                restoreDraftText(trimmed)
                created?.let { rollback(it, trimmed) }
                throw cancelled
            } finally {
                sendJob = null
            }
        }
    }

    fun toggleSearch() {
        if (_state.value.isSearching) return
        _state.update { it.copy(searchEnabled = !it.searchEnabled) }
    }

    /**
     * Deletes a conversation that was created for a first message which then
     * failed, and returns the ViewModel to the draft so the transcript and the
     * text are exactly where the user left them.
     *
     * The gateway has no delete command (it is not part of the chat seam yet),
     * so this keeps using [com.deepseek.mobilechat.application.ConversationService].
     */
    private suspend fun rollback(created: ConversationId, text: String) {
        withContext(NonCancellable) {
            runCatching { services.conversations.delete(created) }
        }
        if (activeId.value == created) activeId.value = null
        restoreDraftText(text)
    }

    /** Never overwrite something the user started typing after the attempt. */
    private fun restoreDraftText(text: String) {
        _state.update { if (it.draftText.isBlank()) it.copy(draftText = text) else it }
    }

    /** Persists overrides picked before the conversation row existed. */
    private suspend fun applyDraftOverrides(conversationId: ConversationId) {
        val overrides = draftOverrides
        if (overrides == ConversationOverrides()) return
        try {
            val fresh = services.conversationStore.get(conversationId) ?: return
            services.conversationStore.upsert(
                fresh.copy(overrides = overrides, updatedAt = services.clock.nowMillis()),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _errors.tryEmit("保存模型设置失败：${error.message ?: "未知错误"}")
        }
    }

    /**
     * Promotes the draft to a real conversation after its row has been created.
     *
     * The attachments staged before the conversation existed were saved with a
     * `null` conversation id (a draft has no row to point at yet), so this is the
     * first moment they can be linked. The link is best-effort on its own
     * coroutine: a failed relink is reported rather than allowed to roll the send
     * back.
     */
    private fun promote(
        conversationId: ConversationId,
        attachmentIds: List<AttachmentId> = emptyList(),
    ) {
        draftOverrides = ConversationOverrides()
        _state.update { it.copy(draftText = "") }
        activeId.value = conversationId
        if (attachmentIds.isEmpty()) return
        viewModelScope.launch {
            try {
                services.attachmentStore.linkToConversation(attachmentIds, conversationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _errors.tryEmit("附件关联失败：${error.message ?: "未知错误"}")
            }
        }
    }

    fun stop() {
        val id = activeId.value ?: return
        viewModelScope.launch {
            when (val outcome = gateway.cancel(id)) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> handleError(outcome.error)
            }
        }
    }

    fun regenerate(messageId: MessageId) {
        viewModelScope.launch {
            when (val outcome = gateway.regenerate(messageId)) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> handleError(outcome.error)
            }
        }
    }

    /** Retry after a failed assistant turn: same command as regenerate. */
    fun retry(messageId: MessageId) = regenerate(messageId)

    /**
     * Explicit recovery for a branch whose active leaf is an unanswered user
     * message (legacy fork data, or a fork whose generation failed). The gateway
     * routes a user id to "start the assistant reply"; nothing is generated until
     * the user taps the affordance.
     */
    fun generateReply() {
        val leaf = snapshot?.activeLeafId ?: return
        if (_state.value.isBusy) return
        regenerate(leaf)
    }

    fun edit(messageId: MessageId, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _errors.tryEmit("内容不能为空")
            return
        }
        viewModelScope.launch {
            when (val outcome = gateway.edit(messageId, trimmed)) {
                is Outcome.Ok -> {
                    if (outcome.value is EditOutcome.Forked) {
                        _notices.tryEmit("已另存为一个新分支")
                    }
                }

                is Outcome.Err -> handleError(outcome.error)
            }
        }
    }

    fun switchBranch(messageId: MessageId) {
        viewModelScope.launch {
            when (val outcome = gateway.switchBranch(messageId)) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> handleError(outcome.error)
            }
        }
    }

    /**
     * Deleting a subtree and pinning are not part of the gateway contract yet, so
     * these two keep going through [com.deepseek.mobilechat.application.ChatService].
     * Reported rather than replaced with an invented command.
     */
    fun delete(messageId: MessageId) {
        viewModelScope.launch {
            when (val outcome = services.chat.deleteSubtree(messageId)) {
                is Outcome.Ok -> _notices.tryEmit("已删除")
                is Outcome.Err -> handleError(outcome.error)
            }
        }
    }

    fun pin(messageId: MessageId) {
        val current = _state.value.messages.firstOrNull { it.id == messageId } ?: return
        val target = !current.pinned
        viewModelScope.launch {
            when (val outcome = services.chat.setPinned(messageId, target)) {
                is Outcome.Ok -> _notices.tryEmit(if (target) "已置顶" else "已取消置顶")
                is Outcome.Err -> handleError(outcome.error)
            }
        }
    }

    /**
     * Continuing a truncated reply is not part of the gateway contract yet; it
     * keeps using [com.deepseek.mobilechat.application.ChatService]. Reported
     * rather than replaced with an invented command.
     */
    fun continueGeneration(messageId: MessageId) {
        viewModelScope.launch {
            when (val outcome = services.chat.continueGeneration(messageId)) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> handleError(outcome.error)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Model / thinking / sampling (spec §4, §5)
    // -----------------------------------------------------------------------

    fun selectModel(ref: ModelRef, displayName: String) {
        modelNames[ref.modelId] = displayName
        writeOverrides { it.copy(modelRef = ref) }
    }

    /**
     * Commits a reasoning level picked on the composer's slider.
     *
     * Called once per gesture (on pointer-up), never while the finger is down —
     * the slider keeps the drag position locally and only hands the final stop
     * here, so one gesture is one write.
     */
    fun setThinkingLevel(level: ThinkingLevel) {
        if (_state.value.thinkingLevel == level) return
        writeOverrides { it.copy(thinkingLevel = level) }
    }

    fun saveParams(params: SamplingParams) {
        writeOverrides { it.copy(params = params) }
    }

    fun resetParams() {
        writeOverrides { it.copy(params = null) }
    }

    fun saveSystemPrompt(text: String) {
        writeOverrides { it.copy(systemPrompt = text.trim().ifBlank { null }) }
    }

    /**
     * Writes overrides through the conversation store (`ConversationStore.upsert`).
     *
     * The gateway has no command for model/params/prompt overrides, and
     * `ConversationService.setModelOverride` replaces all three fields at once (it
     * nulls whatever it is not given), so using it for "system prompt only" would
     * wipe the model choice. Everything written here is re-read by the next
     * snapshot anyway; only the aggregate fields live outside the log.
     */
    private fun writeOverrides(transform: (ConversationOverrides) -> ConversationOverrides) {
        val id = activeId.value
        if (id == null) {
            draftOverrides = transform(draftOverrides)
            recompute()
            return
        }
        val current = conversation
        if (current == null) {
            // The one-shot read has not landed yet: read it before writing so the
            // change is not dropped.
            viewModelScope.launch {
                try {
                    val loaded = services.conversationStore.get(id) ?: return@launch
                    val updated = loaded.copy(
                        overrides = transform(loaded.overrides),
                        updatedAt = services.clock.nowMillis(),
                    )
                    runCatching { services.conversationStore.upsert(updated) }
                    if (activeId.value == id) {
                        conversation = updated
                        recompute()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                }
            }
            return
        }
        val updated = current.copy(
            overrides = transform(current.overrides),
            updatedAt = services.clock.nowMillis(),
        )
        conversation = updated
        recompute()
        viewModelScope.launch {
            runCatching { services.conversationStore.upsert(updated) }
        }
    }

    // -----------------------------------------------------------------------
    // Compaction confirmation
    // -----------------------------------------------------------------------

    /**
     * Applies the proposal the gateway put in `Overlay.compactionProposal`. The
     * held text and the resend are owned by the gateway: this only confirms.
     */
    fun confirmCompaction() {
        val proposal = _state.value.compactionProposal ?: return
        val target = activeId.value ?: return
        viewModelScope.launch {
            when (val outcome = gateway.confirmCompaction(target)) {
                is Outcome.Ok -> {
                    val saved = (proposal.tokensBefore - proposal.tokensAfter).coerceAtLeast(0)
                    _notices.tryEmit("已压缩 ${proposal.coveredCount} 条消息，节省约 $saved tokens")
                    _state.update { it.copy(compactionProposal = null) }
                }

                is Outcome.Err -> handleError(outcome.error)
            }
        }
    }

    /**
     * Cancels the proposal. The gateway keeps the held text in
     * `Overlay.pendingUserText`; put it back into the composer so the user can
     * edit it before sending again.
     */
    fun dismissCompaction() {
        val target = activeId.value ?: return
        val held = snapshot?.overlay?.pendingUserText
        viewModelScope.launch {
            when (val outcome = gateway.dismissCompaction(target)) {
                is Outcome.Ok -> _state.update {
                    it.copy(
                        compactionProposal = null,
                        draftText = held?.takeIf { text -> text.isNotBlank() } ?: it.draftText,
                    )
                }

                is Outcome.Err -> handleError(outcome.error)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Derivation (presentation only)
    // -----------------------------------------------------------------------

    private fun recompute() {
        val id = activeId.value
        val current = snapshot
        val all = current?.messages.orEmpty()
        val tree = MessageTree.from(all)

        // `visibleMessages`/`leafNeedsReply` take the old aggregate shape; the
        // only field they read is the leaf, which now comes from the snapshot.
        val view = current?.let {
            Conversation(
                id = it.conversationId,
                title = it.title,
                activeLeafId = it.activeLeafId,
                createdAt = 0L,
                updatedAt = 0L,
            )
        }
        val visible = visibleMessages(tree, view, all)
        val branches = buildBranches(tree, visible)
        val transcript = buildTranscript(current?.transcript.orEmpty(), visible)

        val overrides = overridesOf(id)
        val preset = effectivePreset(overrides)
        // Local overrides come first so the picker/slider react immediately even
        // before the engine reflects the change in a snapshot; the preset and the
        // provider default are the same fallbacks the context assembler uses.
        val effectiveModelRef = overrides.modelRef
            ?: current?.modelRef
            ?: preset?.modelRef
            ?: providersSnapshot.firstOrNull { it.defaultModelId != null }?.let { provider ->
                provider.defaultModelId?.let { ModelRef(provider.id, it) }
            }
        val activeModel = resolveActiveModel(effectiveModelRef)
        val thinking = overrides.thinkingLevel
            ?: current?.thinkingLevel
            ?: preset?.thinkingLevel
            ?: ThinkingLevel.OFF
        val overlay = current?.overlay ?: Overlay()

        measureThinking(overlay.streaming)

        val title = current?.title?.takeIf { it.isNotBlank() }
            ?: conversation?.title?.takeIf { it.isNotBlank() }
            ?: "新对话"
        val subtitle = describeModel(activeModel.displayName, thinking)

        _state.update {
            it.copy(
                conversationId = id,
                conversation = conversation,
                messages = visible,
                transcript = transcript,
                branches = branches,
                isGenerating = overlay.streaming != null,
                isSending = overlay.busy == BusyKind.SEND,
                isSearching = overlay.searching,
                busyKind = overlay.busy,
                needsReply = leafNeedsReply(tree, view),
                title = title,
                subtitle = subtitle,
                activeModel = activeModel,
                thinkingLevel = thinking,
                params = overrides.params ?: preset?.params ?: SamplingParams(),
                paramsOverridden = overrides.params != null,
                systemPrompt = overrides.systemPrompt.orEmpty(),
                thinkingSeconds = thinkingMeasured.toMap(),
                compactionProposal = overlay.compactionProposal?.toDialogProposal(current),
                isLoadingConversation = id != null && current == null && !loadFailed,
            )
        }

        current?.let { requestTitleIfNeeded(it) }
    }

    /**
     * The preset that is actually in effect: the one the conversation is bound
     * to, else the default one, else the first — mirroring
     * `DefaultContextAssembler.resolvePreset`.
     */
    private fun effectivePreset(overrides: ConversationOverrides): Preset? {
        overrides.presetId?.let { id ->
            presetsSnapshot.firstOrNull { it.id == id }?.let { return it }
        }
        return presetsSnapshot.firstOrNull { it.isDefault } ?: presetsSnapshot.firstOrNull()
    }

    private fun overridesOf(id: ConversationId?): ConversationOverrides =
        if (id == null) draftOverrides else conversation?.overrides ?: ConversationOverrides()

    private fun resolveActiveModel(ref: ModelRef?): ActiveModel {
        if (ref == null) return ActiveModel()
        val provider = providersSnapshot.firstOrNull { it.id == ref.providerId }
        return ActiveModel(
            ref = ref,
            providerName = provider?.name.orEmpty(),
            displayName = modelNames[ref.modelId] ?: ref.modelId.raw,
            protocol = provider?.protocol,
        )
    }

    /**
     * Auto-title: the service decides whether the current title is still a
     * placeholder, so one cheap call after the first completed reply is enough.
     */
    private fun requestTitleIfNeeded(current: ChatSnapshot) {
        val id = activeId.value ?: return
        if (current.conversationId != id) return
        if (id in titleRequested) return
        val hasCompletedReply = current.messages.any {
            it.role == MessageRole.ASSISTANT && it.status == MessageStatus.COMPLETE
        }
        if (!hasCompletedReply) return
        titleRequested += id
        viewModelScope.launch {
            // A failed title generation must never surface to the user.
            services.conversations.ensureTitle(id)
        }
    }

    /**
     * Measures how long the model spent thinking, live from the stream: the
     * clock starts on the first reasoning chunk and stops when the body starts
     * arriving. Messages that were already finished when they were loaded have
     * no measurement, and the UI shows 「思考过程」 instead of a made-up number.
     */
    private fun measureThinking(streaming: StreamingTurn?) {
        val now = services.clock.nowMillis()
        val activeMessage = streaming?.messageId
        if (streaming != null) {
            val started = thinkingStartedAt[streaming.messageId]
            when {
                started == null &&
                    streaming.text.isBlank() &&
                    streaming.reasoning.isNotBlank() -> thinkingStartedAt[streaming.messageId] = now

                started != null && streaming.text.isNotBlank() -> {
                    thinkingStartedAt.remove(streaming.messageId)
                    thinkingMeasured[streaming.messageId] =
                        ((now - started) / 1000L).toInt().coerceIn(1, 600)
                }
            }
        }
        // A turn that stopped streaming (cancelled before any body) still gets a
        // measurement instead of staying open forever.
        thinkingStartedAt.keys.toList().forEach { id ->
            if (id == activeMessage) return@forEach
            val started = thinkingStartedAt.remove(id) ?: return@forEach
            thinkingMeasured[id] = ((now - started) / 1000L).toInt().coerceIn(1, 600)
        }
    }

    /**
     * `CompactionProposalView` carries only what the dialog renders; the dialog's
     * type wants the full `CompactionProposal`, so the opaque ids/summary are
     * empty. Everything the user sees (covered count, tokens before/after) is
     * exact.
     */
    private fun CompactionProposalView.toDialogProposal(current: ChatSnapshot?): CompactionProposal =
        CompactionProposal(
            conversationId = current?.conversationId ?: ConversationId("draft"),
            fromMessageId = MessageId(""),
            toMessageId = MessageId(""),
            coveredCount = coveredCount,
            summary = "",
            tokensBefore = tokensBefore,
            tokensAfter = tokensAfter,
            strategyId = "",
        )

    /** Used by the attachment commands in [ChatViewModelAttachments]. */
    internal fun updateState(transform: (ChatUiState) -> ChatUiState) {
        _state.update(transform)
    }

    internal fun handleError(error: AppError) {
        // Budget overruns now arrive as `Overlay.compactionProposal`; an error is
        // just an error and must not reintroduce a second proposal source.
        _errors.tryEmit(error.toUserMessage())
    }

    companion object {
        fun factory(
            services: AppServices,
            gateway: ChatGateway,
            conversationId: ConversationId?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(services, gateway, conversationId) }
        }
    }
}
