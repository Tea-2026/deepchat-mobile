package com.deepseek.mobilechat.application.service.testutil

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.BlockKind
import com.deepseek.mobilechat.application.ChatCompletionPort
import com.deepseek.mobilechat.application.CompactionProposal
import com.deepseek.mobilechat.application.ContextAssembler
import com.deepseek.mobilechat.application.ContextAssembly
import com.deepseek.mobilechat.application.ContextBlock
import com.deepseek.mobilechat.application.ContextPlan
import com.deepseek.mobilechat.application.ContextStrategy
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.GenerationRequest
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.service.DefaultChatService
import com.deepseek.mobilechat.application.service.DefaultGenerationQueue
import com.deepseek.mobilechat.application.service.ModelCatalogCache
import com.deepseek.mobilechat.domain.ContextBudget
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.Usage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

// ---------------------------------------------------------------------------
// Message builders
// ---------------------------------------------------------------------------

fun userMessage(
    id: String,
    conversationId: String = "c1",
    text: String = "",
    seq: Long = 1L,
    parentId: String? = null,
    status: MessageStatus = MessageStatus.COMPLETE,
    parts: List<MessagePart> = listOf(MessagePart.Text(text)),
): Message = Message(
    id = MessageId(id),
    conversationId = ConversationId(conversationId),
    parentId = parentId?.let { MessageId(it) },
    seq = seq,
    role = MessageRole.USER,
    parts = parts,
    status = status,
    createdAt = seq,
)

fun assistantMessage(
    id: String,
    conversationId: String = "c1",
    text: String = "",
    seq: Long = 1L,
    parentId: String? = null,
    status: MessageStatus = MessageStatus.COMPLETE,
    finishReason: FinishReason = FinishReason.STOP,
    usage: Usage? = null,
    parts: List<MessagePart> = listOf(MessagePart.Text(text)),
): Message = Message(
    id = MessageId(id),
    conversationId = ConversationId(conversationId),
    parentId = parentId?.let { MessageId(it) },
    seq = seq,
    role = MessageRole.ASSISTANT,
    parts = parts,
    status = status,
    createdAt = seq,
    usage = usage,
    finishReason = finishReason,
)

// ---------------------------------------------------------------------------
// Generation fakes
// ---------------------------------------------------------------------------

/**
 * Scripted [ChatCompletionPort]. [events] is replayed on every call unless
 * [hangAfter] is set, in which case the first N events are emitted and the flow
 * then suspends forever (cancellation tests).
 */
class FakeChatCompletion : ChatCompletionPort {

    val requests = mutableListOf<GenerationRequest>()
    var events: List<GenerationEvent> = listOf(GenerationEvent.Finished(FinishReason.STOP))
    var hangAfter: Int? = null
    var throwOnStream: Throwable? = null

    override fun stream(request: GenerationRequest): Flow<GenerationEvent> = flow {
        requests += request
        throwOnStream?.let { throw it }
        val limit = hangAfter ?: events.size
        events.take(limit).forEach { emit(it) }
        if (hangAfter != null) awaitCancellation()
    }
}

/** Configurable [ContextAssembler]; records every call in [calls]. */
class FakeContextAssembler : ContextAssembler {

    var limit: Int = 100_000
    var used: Int = 10
    var failure: AppError? = null
    var prompt: List<PromptMessage> = listOf(
        PromptMessage(role = MessageRole.USER, parts = listOf(MessagePart.Text("hello"))),
    )
    var systemPrompt: String? = "system prompt"
    var modelRef: ModelRef = ModelRef(ProviderId("provider-1"), ModelId("model-1"))
    var params: SamplingParams = SamplingParams(temperature = 0.5)
    var thinkingLevel: ThinkingLevel = ThinkingLevel.OFF
    val calls = mutableListOf<ConversationId>()

    /** Runs inside [assemble] before the result is produced (state probes). */
    var beforeAssemble: (suspend () -> Unit)? = null

    override suspend fun assemble(conversationId: ConversationId): Outcome<ContextAssembly> {
        calls += conversationId
        beforeAssemble?.invoke()
        failure?.let { return Outcome.Err(it) }
        return Outcome.Ok(
            ContextAssembly(
                conversationId = conversationId,
                leafId = null,
                blocks = listOf(
                    ContextBlock(
                        id = "current",
                        kind = BlockKind.CURRENT_USER,
                        title = "当前消息",
                        tokens = used,
                    ),
                ),
                budget = ContextBudget(limit = limit, used = used),
                prompt = prompt,
                systemPrompt = systemPrompt,
                params = params,
                modelRef = modelRef,
                thinkingLevel = thinkingLevel,
            ),
        )
    }
}

/** Strategy whose plan is supplied by the test. */
class FixedPlanStrategy(
    override val id: String,
    private val planner: (ContextAssembly, ContextBudget) -> ContextPlan,
) : ContextStrategy {

    var planCalls: Int = 0

    override suspend fun plan(
        assembly: ContextAssembly,
        budget: ContextBudget,
        summarize: suspend (List<PromptMessage>) -> String,
    ): ContextPlan {
        planCalls++
        return planner(assembly, budget)
    }
}

/** Strategy that really invokes `summarize`, to prove the port is wired. */
class SummarizingStrategy(
    override val id: String,
    private val from: String,
    private val to: String,
) : ContextStrategy {

    override suspend fun plan(
        assembly: ContextAssembly,
        budget: ContextBudget,
        summarize: suspend (List<PromptMessage>) -> String,
    ): ContextPlan {
        val summary = summarize(assembly.prompt)
        return ContextPlan.RequiresConfirmation(
            CompactionProposal(
                conversationId = assembly.conversationId,
                fromMessageId = MessageId(from),
                toMessageId = MessageId(to),
                coveredCount = 2,
                summary = summary,
                tokensBefore = budget.used,
                tokensAfter = budget.used / 2,
                strategyId = id,
            ),
        )
    }
}

fun proposal(
    conversationId: String = "c1",
    from: String = "u1",
    to: String = "a1",
    summary: String = "summary",
): CompactionProposal = CompactionProposal(
    conversationId = ConversationId(conversationId),
    fromMessageId = MessageId(from),
    toMessageId = MessageId(to),
    coveredCount = 2,
    summary = summary,
    tokensBefore = 900,
    tokensAfter = 100,
    strategyId = "confirm-compaction",
)

/**
 * Wires the whole send pipeline with the real queue running on the test
 * scheduler, so `advanceUntilIdle()` in a test drives the stream to completion.
 */
class ChatHarness(scheduler: TestCoroutineScheduler) {

    val conversations = FakeConversationStore()
    val messages = FakeMessageStore()
    val injections = FakeInjectionStore()
    val attachments = FakeAttachmentStore()
    val presets = FakePresetStore()
    val profiles = FakeProviderProfileStore()
    val settings = FakeSettingsStore()
    val clock = FakeClock()
    val ids = FakeIdPort("msg")
    val chat = FakeChatCompletion()
    val catalog = FakeModelCatalogPort()
    val assembler = FakeContextAssembler()
    val strategies = mutableMapOf<String, ContextStrategy>()

    private val queue = DefaultGenerationQueue(
        settingsStore = settings,
        clock = clock,
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)),
    )

    /** Exposed so tests can pause/resume/cancel through the real queue. */
    fun queue(): DefaultGenerationQueue = queue

    val modelCatalog = ModelCatalogCache(catalog)

    val service = DefaultChatService(
        conversationStore = conversations,
        messageStore = messages,
        injectionStore = injections,
        contextAssembler = assembler,
        scheduler = queue,
        chatCompletion = chat,
        clock = clock,
        idPort = ids,
        tokenEstimator = FakeTokenEstimator(),
        settingsStore = settings,
        modelCatalog = modelCatalog,
        providerProfileStore = profiles,
        presetStore = presets,
        attachmentStore = attachments,
        strategies = strategies,
    )
}
