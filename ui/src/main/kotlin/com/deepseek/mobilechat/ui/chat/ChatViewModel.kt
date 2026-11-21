package com.deepseek.mobilechat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.SendResult
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
import com.deepseek.mobilechat.domain.SearchHit
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
 * Everything observable comes from the stores (single source of truth); the
 * ViewModel only derives the *active path* and branch positions, and forwards
 * commands to [com.deepseek.mobilechat.application.ChatService].
 *
 * **Draft conversations** (spec §2): [conversationId] may be `null`. In that
 * mode the transcript lives in memory (`draftMessages`) and nothing is written
 * to the database until the first send; the first send creates the conversation,
 * then sends, and deletes it again if the send fails — a failed first message
 * must never leave an empty conversation in the drawer.
 *
 * A single instance can be re-pointed at another conversation with [bindTo],
 * which is what lets the root screen keep one draft (and its text) alive while
 * the user browses history.
 *
 * Failures are **one-shot events** on [errors] — putting them in the state
 * would replay the same snackbar forever after every recomposition or
 * configuration change.
 */
class ChatViewModel(
    internal val services: AppServices,
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

    private var conversationSnapshot: Conversation? = null
    private var messageSnapshot: List<Message> = emptyList()

    /** The draft's in-memory transcript; never persisted before the first send. */
    private var draftMessages: List<Message> = emptyList()

    /** Overrides picked before the conversation existed; applied on creation. */
    private var draftOverrides = ConversationOverrides()

    private var providersSnapshot: List<ProviderProfile> = emptyList()

    /** Presets decide the effective model/thinking when the chat has no override. */
    private var presetsSnapshot: List<Preset> = emptyList()

    /** Display names learned from the model picker, keyed by model id. */
    private val modelNames = mutableMapOf<ModelId, String>()

    /** True between "conversation selected" and "its first emission arrived". */
    private var awaitingFirstEmission = false

    /**
     * True once the conversation aggregate has answered for the active id.
     * `visibleMessages` refuses to build a transcript without it, so the screen
     * must keep showing the loading state until it lands.
     */
    private var conversationLoaded = false

    private val thinkingStartedAt = mutableMapOf<MessageId, Long>()
    private val thinkingMeasured = mutableMapOf<MessageId, Int>()

    /** Text kept aside while the user decides about a compaction proposal. */
    private var pendingSend: PendingSend? = null

    private data class PendingSend(
        val text: String,
        val injection: String?,
        val attachmentIds: List<AttachmentId>,
        /** Sources already fetched for this turn; replayed after a compaction. */
        val searchHits: List<SearchHit>,
    )

    /** The in-flight send (search phase included); "停止" cancels it. */
    private var sendJob: Job? = null

    /** Auto-titling fires at most once per conversation. */
    private val titleRequested = mutableSetOf<ConversationId>()

    init {
        viewModelScope.launch {
            // Re-subscribes whenever the active conversation changes. The
            // collectors must be children of *this* block (hence
            // `coroutineScope`, which suspends until they finish): a bare
            // `launch` here would attach to `viewModelScope` instead, so the
            // previous conversation's collectors would keep running after a
            // switch and publish its snapshots into the next conversation's
            // state on the next database change ("发送第二条消息时闪回上一个
            // 会话").
            activeId.collectLatest { id ->
                conversationSnapshot = null
                messageSnapshot = emptyList()
                awaitingFirstEmission = id != null
                conversationLoaded = false
                recompute()
                if (id == null) return@collectLatest
                coroutineScope {
                    launch {
                        services.conversationStore.observe(id)
                            .catch {
                                _errors.tryEmit("加载会话失败：${it.message ?: "未知错误"}")
                                // The flow is over; stop waiting for an aggregate
                                // that will never come, or the spinner never ends.
                                conversationLoaded = true
                                recompute()
                            }
                            .collect { conversation ->
                                conversationSnapshot = conversation
                                conversationLoaded = true
                                recompute()
                            }
                    }
                    launch {
                        services.messageStore.observe(id)
                            .catch { _errors.tryEmit("加载消息失败：${it.message ?: "未知错误"}") }
                            .collect { messages ->
                                messageSnapshot = messages
                                awaitingFirstEmission = false
                                measureThinking(messages)
                                recompute()
                                requestTitleIfNeeded(id, messages)
                            }
                    }
                    launch {
                        services.context.observeInjections(id)
                            .catch { _errors.tryEmit("加载注入失败：${it.message ?: "未知错误"}") }
                            .collect { injections ->
                                _state.update { it.copy(injections = injections) }
                            }
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
        draftMessages = emptyList()
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
        if (_state.value.isBusy) return
        val manualInjection = ephemeralInjection?.takeIf { it.isNotBlank() }
        val searchFirst = _state.value.searchEnabled && trimmed.isNotEmpty()

        // The composer is emptied immediately; a failure puts the text back.
        _state.update { it.copy(draftText = "", isSending = true) }

        sendJob = viewModelScope.launch {
            var created: ConversationId? = null
            try {
                val target = activeId.value ?: run {
                    when (val outcome = services.conversations.create()) {
                        is Outcome.Ok -> {
                            created = outcome.value.id
                            created
                        }

                        is Outcome.Err -> {
                            _state.update { it.copy(isSending = false, draftText = trimmed) }
                            handleError(outcome.error)
                            null
                        }
                    }
                }
                if (target == null) return@launch

                // Draft overrides were chosen before the row existed; persist
                // them now, before the first request is assembled.
                if (created != null && draftOverrides != ConversationOverrides()) {
                    val fresh = services.conversationStore.get(target)
                    if (fresh != null) {
                        services.conversationStore.upsert(fresh.copy(overrides = draftOverrides))
                    }
                }

                var injection = manualInjection
                var searchHits: List<SearchHit> = emptyList()
                if (searchFirst) {
                    _state.update { it.copy(isSearching = true) }
                    val phase = searchPhase(trimmed)
                    injection = mergeInjections(manualInjection, phase.injection)
                    searchHits = phase.hits
                    _state.update { it.copy(isSearching = false) }
                }
                val outcome = services.chat.send(
                    conversationId = target,
                    text = trimmed,
                    attachmentIds = attachmentIds,
                    ephemeralInjection = injection,
                    searchHits = searchHits,
                )
                when (outcome) {
                    is Outcome.Ok -> when (val result = outcome.value) {
                        is SendResult.Started -> {
                            pendingSend = null
                            _state.update {
                                it.copy(pendingAttachments = emptyList(), isSending = false)
                            }
                            if (created != null) promote(created!!, attachmentIds)
                        }

                        is SendResult.NeedsConfirmation -> {
                            pendingSend = PendingSend(trimmed, injection, attachmentIds, searchHits)
                            _state.update {
                                it.copy(compactionProposal = result.proposal, isSending = false)
                            }
                            if (created != null) promote(created!!, attachmentIds)
                        }
                    }

                    is Outcome.Err -> {
                        if (outcome.error is AppError.BudgetExceeded) {
                            // Some implementations surface the proposal as an
                            // error instead of NeedsConfirmation; never drop the text.
                            pendingSend = PendingSend(trimmed, injection, attachmentIds, searchHits)
                        }
                        _state.update {
                            it.copy(draftText = restoreText(trimmed), isSending = false)
                        }
                        handleError(outcome.error)
                        created?.let { rollback(it, trimmed) }
                    }
                }
            } catch (cancelled: CancellationException) {
                // Stopped during the search phase: give the text back and make
                // sure the conversation we just created does not survive.
                _state.update {
                    it.copy(
                        isSearching = false,
                        isSending = false,
                        draftText = restoreText(trimmed),
                    )
                }
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
     * Search first, then send with the hits folded into the injection.
     *
     * The same hits also travel to [com.deepseek.mobilechat.application.ChatService.send]
     * so they can be recorded on the message and rendered as "参考 N 个网页";
     * without that the sources exist only inside the prompt and the user can
     * never see where an answer came from.
     */
    private suspend fun searchPhase(query: String): SearchPhase =
        when (val outcome = services.search.webSearch(query)) {
            is Outcome.Ok -> SearchPhase(
                injection = formatSearchInjection(query, outcome.value.hits),
                hits = outcome.value.hits,
            )

            is Outcome.Err -> {
                _errors.tryEmit(outcome.error.toUserMessage())
                SearchPhase(
                    injection = searchFailureInjection(outcome.error.toUserMessage()),
                    hits = emptyList(),
                )
            }
        }

    private data class SearchPhase(val injection: String?, val hits: List<SearchHit>)

    /**
     * Deletes a conversation that was created for a first message which then
     * failed, and returns the ViewModel to the draft so the transcript and the
     * text are exactly where the user left them.
     */
    private suspend fun rollback(created: ConversationId, text: String) {
        withContext(NonCancellable) {
            runCatching { services.conversations.delete(created) }
        }
        draftMessages = emptyList()
        if (activeId.value == created) activeId.value = null
        _state.update { it.copy(draftText = restoreText(text)) }
        recompute()
    }

    /** Never overwrite something the user started typing after the attempt. */
    private fun restoreText(text: String): String =
        if (_state.value.draftText.isBlank()) text else _state.value.draftText

    /**
     * Promotes the draft to a real conversation after a successful send.
     *
     * The attachments staged before the conversation existed were saved with a
     * `null` conversation id (a draft has no row to point at yet), so this is
     * the first moment they can be linked. The link is best-effort on its own
     * coroutine: the message has already been accepted, and a failed relink is
     * reported rather than allowed to roll the send back.
     */
    private fun promote(
        conversationId: ConversationId,
        attachmentIds: List<AttachmentId> = emptyList(),
    ) {
        draftMessages = emptyList()
        draftOverrides = ConversationOverrides()
        _state.update { it.copy(draftText = "") }
        awaitingFirstEmission = true
        activeId.value = conversationId
        if (attachmentIds.isEmpty()) return
        viewModelScope.launch {
            try {
                services.attachmentStore.linkToConversation(attachmentIds, conversationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Same voice as the collectors above; never swallowed silently,
                // because an unlinked blob is invisible in the conversation.
                _errors.tryEmit("附件关联失败：${error.message ?: "未知错误"}")
            }
        }
    }

    fun stop() {
        val job = sendJob
        if (job != null && job.isActive && _state.value.isSearching) {
            job.cancel()
            return
        }
        val id = activeId.value ?: return
        viewModelScope.launch {
            when (val outcome = services.chat.cancel(id)) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> handleError(outcome.error)
            }
        }
    }

    fun regenerate(messageId: MessageId) {
        viewModelScope.launch {
            when (val outcome = services.chat.regenerate(messageId)) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> handleError(outcome.error)
            }
        }
    }

    /** Retry after a failed assistant turn: same command as regenerate. */
    fun retry(messageId: MessageId) = regenerate(messageId)

    /**
     * Explicit recovery for a branch whose active leaf is an unanswered user
     * message (legacy fork data, or a fork whose generation failed). The
     * service routes a user id to "start the assistant reply"; nothing is
     * generated until the user taps the affordance.
     */
    fun generateReply() {
        val leaf = _state.value.conversation?.activeLeafId ?: return
        if (_state.value.isBusy) return
        viewModelScope.launch {
            when (val outcome = services.chat.regenerate(leaf)) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> handleError(outcome.error)
            }
        }
    }

    fun edit(messageId: MessageId, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _errors.tryEmit("内容不能为空")
            return
        }
        viewModelScope.launch {
            when (val outcome = services.chat.editMessage(messageId, trimmed)) {
                is Outcome.Ok -> {
                    if (outcome.value.forked) {
                        _notices.tryEmit("已另存为一个新分支")
                    }
                }

                is Outcome.Err -> handleError(outcome.error)
            }
        }
    }

    fun switchBranch(messageId: MessageId) {
        viewModelScope.launch {
            when (val outcome = services.chat.switchToBranch(messageId)) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> handleError(outcome.error)
            }
        }
    }

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
     * here, so one gesture is one store write.
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
     * `ConversationService.setModelOverride` replaces all three fields at once
     * (it nulls whatever it is not given), so using it for "system prompt only"
     * would wipe the model choice. The store is the contract-approved way to
     * write the aggregate, so this stays inside the frozen surface.
     */
    private fun writeOverrides(transform: (ConversationOverrides) -> ConversationOverrides) {
        val id = activeId.value
        if (id == null) {
            draftOverrides = transform(draftOverrides)
            recompute()
            return
        }
        val current = conversationSnapshot
        if (current == null) {
            // The store has not emitted the conversation yet (first frame after
            // selecting it): read it once instead of dropping the change.
            viewModelScope.launch {
                val loaded = services.conversationStore.get(id) ?: return@launch
                runCatching {
                    services.conversationStore.upsert(
                        loaded.copy(
                            overrides = transform(loaded.overrides),
                            updatedAt = services.clock.nowMillis(),
                        ),
                    )
                }
            }
            return
        }
        val updated = current.copy(
            overrides = transform(current.overrides),
            updatedAt = services.clock.nowMillis(),
        )
        conversationSnapshot = updated
        recompute()
        viewModelScope.launch {
            runCatching { services.conversationStore.upsert(updated) }
        }
    }

    // -----------------------------------------------------------------------
    // Compaction confirmation
    // -----------------------------------------------------------------------

    fun confirmCompaction() {
        val proposal = _state.value.compactionProposal ?: return
        val target = activeId.value ?: return
        viewModelScope.launch {
            when (val outcome = services.context.applyCompaction(proposal)) {
                is Outcome.Ok -> {
                    _state.update { it.copy(compactionProposal = null) }
                    val saved = (proposal.tokensBefore - proposal.tokensAfter).coerceAtLeast(0)
                    _notices.tryEmit("已压缩 ${proposal.coveredCount} 条消息，节省约 $saved tokens")
                    val pending = pendingSend
                    pendingSend = null
                    if (pending != null) {
                        when (
                            val sent = services.chat.send(
                                conversationId = target,
                                text = pending.text,
                                attachmentIds = pending.attachmentIds,
                                ephemeralInjection = pending.injection,
                                searchHits = pending.searchHits,
                            )
                        ) {
                            is Outcome.Ok -> when (val result = sent.value) {
                                is SendResult.Started -> _state.update {
                                    it.copy(pendingAttachments = emptyList())
                                }

                                is SendResult.NeedsConfirmation -> {
                                    pendingSend = pending
                                    _state.update { it.copy(compactionProposal = result.proposal) }
                                }
                            }

                            is Outcome.Err -> handleError(sent.error)
                        }
                    }
                }

                is Outcome.Err -> {
                    _state.update { it.copy(compactionProposal = null) }
                    handleError(outcome.error)
                }
            }
        }
    }

    fun dismissCompaction() {
        val pending = pendingSend
        pendingSend = null
        _state.update {
            it.copy(
                compactionProposal = null,
                draftText = pending?.text ?: it.draftText,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Derivation
    // -----------------------------------------------------------------------

    private fun recompute() {
        val id = activeId.value
        val conversation = if (id == null) null else conversationSnapshot
        val all = if (id == null) draftMessages else messageSnapshot
        val tree = MessageTree.from(all)
        val visible = visibleMessages(tree, conversation, all)
        val branches = buildBranches(tree, visible)
        val generating = all.any { it.status == MessageStatus.STREAMING }
        val needsReply = leafNeedsReply(tree, conversation)
        val overrides = overridesOf(id, conversation)
        val preset = effectivePreset(overrides)
        // Same precedence the context assembler uses, so the chip always names
        // the model that will actually be sent:
        // conversation override > preset > provider default.
        val effectiveModelRef = overrides.modelRef
            ?: preset?.modelRef
            ?: providersSnapshot.firstOrNull { it.defaultModelId != null }?.let { provider ->
                provider.defaultModelId?.let { ModelRef(provider.id, it) }
            }
        val activeModel = resolveActiveModel(effectiveModelRef)
        val title = conversation?.title?.takeIf { it.isNotBlank() } ?: "新对话"
        val thinking = overrides.thinkingLevel ?: preset?.thinkingLevel ?: ThinkingLevel.OFF
        val subtitle = describeModel(activeModel.displayName, thinking)

        _state.update {
            it.copy(
                conversationId = id,
                conversation = conversation,
                messages = visible,
                branches = branches,
                isGenerating = generating,
                needsReply = needsReply,
                title = title,
                subtitle = subtitle,
                activeModel = activeModel,
                thinkingLevel = thinking,
                params = overrides.params ?: preset?.params ?: SamplingParams(),
                paramsOverridden = overrides.params != null,
                systemPrompt = overrides.systemPrompt.orEmpty(),
                thinkingSeconds = thinkingMeasured.toMap(),
                isLoadingConversation = id != null &&
                    visible.isEmpty() &&
                    (awaitingFirstEmission || !conversationLoaded),
            )
        }
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

    private fun overridesOf(
        id: ConversationId?,
        conversation: Conversation?,
    ): ConversationOverrides =
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
    private fun requestTitleIfNeeded(id: ConversationId, messages: List<Message>) {
        if (id in titleRequested) return
        val hasCompletedReply = messages.any {
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
    private fun measureThinking(messages: List<Message>) {
        val now = services.clock.nowMillis()
        messages.forEach { message ->
            val hasReasoning = message.parts.any {
                it is MessagePart.Reasoning && it.text.isNotBlank()
            }
            val hasBody = message.text.isNotBlank()
            val started = thinkingStartedAt[message.id]
            when {
                started == null &&
                    message.status == MessageStatus.STREAMING &&
                    hasReasoning &&
                    !hasBody -> thinkingStartedAt[message.id] = now

                started != null && (hasBody || message.status != MessageStatus.STREAMING) -> {
                    thinkingStartedAt.remove(message.id)
                    thinkingMeasured[message.id] =
                        ((now - started) / 1000L).toInt().coerceIn(1, 600)
                }
            }
        }
    }

    /** Used by the attachment commands in [ChatViewModelAttachments]. */
    internal fun updateState(transform: (ChatUiState) -> ChatUiState) {
        _state.update(transform)
    }

    internal fun handleError(error: AppError) {
        if (error is AppError.BudgetExceeded) {
            _state.update { it.copy(compactionProposal = error.proposal) }
            return
        }
        _errors.tryEmit(error.toUserMessage())
    }

    companion object {
        fun factory(
            services: AppServices,
            conversationId: ConversationId?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(services, conversationId) }
        }
    }
}
