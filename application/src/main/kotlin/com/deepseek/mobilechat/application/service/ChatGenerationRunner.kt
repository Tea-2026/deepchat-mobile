package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.ChatCompletionPort
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.GenerationRequest
import com.deepseek.mobilechat.application.MessageStore
import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.application.SettingsStore
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.ModelDescriptor
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.Usage
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Tracks the job currently streaming into each message.
 *
 * [com.deepseek.mobilechat.application.ChatService.cancel] must be able to stop
 * a running stream without owning the queue's scope, so every streaming job
 * registers itself here for the duration of the generation. Entries are keyed
 * by message id: one message can only ever have one live stream.
 */
internal class GenerationRegistry {

    private data class Entry(
        val conversationId: ConversationId,
        val messageId: MessageId,
        val job: Job,
    )

    private val entries = MutableStateFlow<List<Entry>>(emptyList())

    fun register(conversationId: ConversationId, messageId: MessageId, job: Job) {
        entries.update { current ->
            current.filterNot { it.messageId == messageId } + Entry(conversationId, messageId, job)
        }
    }

    fun unregister(messageId: MessageId, job: Job) {
        entries.update { current ->
            current.filterNot { it.messageId == messageId && it.job === job }
        }
    }

    /** Cancels every stream of [conversationId]; returns the affected message ids. */
    fun cancelConversation(conversationId: ConversationId): Set<MessageId> {
        val targets = entries.value.filter { it.conversationId == conversationId }
        targets.forEach { it.job.cancel() }
        entries.update { current -> current.filterNot { it.conversationId == conversationId } }
        return targets.map { it.messageId }.toSet()
    }

    fun activeMessageIds(conversationId: ConversationId): Set<MessageId> =
        entries.value.filter { it.conversationId == conversationId }.map { it.messageId }.toSet()
}

/**
 * Consumes a [ChatCompletionPort] stream and checkpoints it into the message
 * store.
 *
 * Persistence contract (docs/architecture.md §4.5):
 *
 * - drafts are written at most once per `streamingCheckpointMillis`;
 * - the terminal write always happens, including on cancellation (inside
 *   `NonCancellable`, so a cancelled job can still persist partial output);
 * - `UsageReport` is stored together with a cost estimate from
 *   [ModelCatalogCache];
 * - `ToolCallDelta` is accumulated but never executed (v1 has no tool loop);
 *   calls are persisted as [MessagePart.ToolCall] parts so a later tool turn
 *   can replay them;
 * - [MessagePart.Reasoning.signature] is preserved verbatim.
 *
 * The runner refuses to start when the message is no longer `STREAMING` (it
 * was cancelled or deleted while queued), and a deleted message is never
 * resurrected by a checkpoint.
 */
internal class ChatGenerationRunner(
    private val messageStore: MessageStore,
    private val chatCompletion: ChatCompletionPort,
    private val clock: ClockPort,
    private val settingsStore: SettingsStore,
    private val modelCatalog: ModelCatalogCache,
    private val providerProfileStore: ProviderProfileStore,
    private val registry: GenerationRegistry,
) {

    suspend fun run(
        messageId: MessageId,
        request: GenerationRequest,
        appendToExisting: Boolean,
    ) {
        val job = coroutineContext[Job] ?: return
        val start = messageStore.get(messageId) ?: return
        if (start.status != MessageStatus.STREAMING) return

        registry.register(start.conversationId, messageId, job)
        val accumulator = PartAccumulator(if (appendToExisting) start.parts else emptyList())
        val checkpointMillis = checkpointInterval()
        var lastCheckpoint = clock.nowMillis()
        var usage: Usage? = start.usage
        var finishReason: FinishReason? = null
        var failure: AppError? = null
        var descriptor: ModelDescriptor? = null

        suspend fun checkpoint() {
            val now = clock.nowMillis()
            if (now - lastCheckpoint < checkpointMillis) return
            lastCheckpoint = now
            persist(messageId, accumulator.snapshot(), MessageStatus.STREAMING, usage, null, null)
        }

        try {
            descriptor = descriptorFor(request.modelRef)
            chatCompletion.stream(request).collect { event ->
                when (event) {
                    is GenerationEvent.Started -> Unit
                    is GenerationEvent.TextDelta -> {
                        accumulator.textDelta(event.text)
                        checkpoint()
                    }

                    is GenerationEvent.ReasoningDelta -> {
                        accumulator.reasoningDelta(event.text)
                        checkpoint()
                    }

                    is GenerationEvent.ReasoningSignature -> accumulator.reasoningSignature(event.signature)

                    is GenerationEvent.ToolCallDelta -> {
                        accumulator.toolCallDelta(event.callId, event.name, event.argumentsDelta)
                        checkpoint()
                    }

                    is GenerationEvent.UsageReport -> {
                        val reported = event.usage
                        usage = reported.copy(
                            costUsd = reported.costUsd ?: modelCatalog.costUsd(descriptor, reported),
                        )
                    }

                    is GenerationEvent.Finished -> finishReason = event.reason
                    is GenerationEvent.Failed -> failure = event.error
                }
            }
            val failureError = failure
            if (failureError != null) {
                persist(
                    messageId = messageId,
                    parts = accumulator.snapshot(),
                    status = MessageStatus.FAILED,
                    usage = usage,
                    finishReason = FinishReason.ERROR,
                    error = failureError.message,
                )
            } else {
                persist(
                    messageId = messageId,
                    parts = accumulator.snapshot(),
                    status = MessageStatus.COMPLETE,
                    usage = usage,
                    finishReason = finishReason ?: FinishReason.STOP,
                    error = null,
                )
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                try {
                    persist(
                        messageId = messageId,
                        parts = accumulator.snapshot(),
                        status = MessageStatus.CANCELLED,
                        usage = usage,
                        finishReason = FinishReason.CANCELLED,
                        error = null,
                    )
                } catch (_: Throwable) {
                    // The stream is already gone; losing the terminal write is
                    // better than masking the cancellation.
                }
            }
            throw cancelled
        } catch (error: Throwable) {
            persist(
                messageId = messageId,
                parts = accumulator.snapshot(),
                status = MessageStatus.FAILED,
                usage = usage,
                finishReason = FinishReason.ERROR,
                error = error.message ?: "generation failed",
            )
        } finally {
            registry.unregister(messageId, job)
        }
    }

    private suspend fun checkpointInterval(): Long =
        try {
            settingsStore.observe().first().streamingCheckpointMillis.coerceAtLeast(0L)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            300L
        }

    private suspend fun descriptorFor(modelRef: ModelRef): ModelDescriptor? {
        val profile = providerProfileStore.get(modelRef.providerId) ?: return null
        return modelCatalog.descriptor(profile, modelRef.modelId)
    }

    private suspend fun persist(
        messageId: MessageId,
        parts: List<MessagePart>,
        status: MessageStatus,
        usage: Usage?,
        finishReason: FinishReason?,
        error: String?,
    ) {
        val current = messageStore.get(messageId) ?: return
        messageStore.update(
            current.copy(
                parts = parts,
                status = status,
                usage = usage,
                finishReason = finishReason,
                error = error,
            ),
        )
    }
}

/**
 * Mutable part list fed by stream events.
 *
 * Text and reasoning deltas append to the last part of their own kind; a new
 * block starts when the kind changes. For "continue generation" the
 * accumulator is seeded with the existing parts so deltas land after the
 * truncated text instead of in a second message.
 */
private class PartAccumulator(initial: List<MessagePart>) {

    private val parts = ArrayList(initial)
    private val toolCalls = LinkedHashMap<String, ToolCallDraft>()
    private var lastReasoningIndex = parts.indexOfLast { it is MessagePart.Reasoning }
    private var pendingSignature: String? = null

    fun textDelta(text: String) {
        if (text.isEmpty()) return
        val last = parts.lastOrNull()
        if (last is MessagePart.Text) {
            parts[parts.lastIndex] = last.copy(text = last.text + text)
        } else {
            parts += MessagePart.Text(text)
        }
    }

    fun reasoningDelta(text: String) {
        if (text.isEmpty()) return
        val last = parts.lastOrNull()
        if (last is MessagePart.Reasoning) {
            parts[parts.lastIndex] = last.copy(text = last.text + text)
            lastReasoningIndex = parts.lastIndex
        } else {
            parts += MessagePart.Reasoning(text, pendingSignature)
            pendingSignature = null
            lastReasoningIndex = parts.lastIndex
        }
    }

    fun reasoningSignature(signature: String) {
        if (signature.isEmpty()) return
        val part = parts.getOrNull(lastReasoningIndex)
        if (part is MessagePart.Reasoning) {
            parts[lastReasoningIndex] = part.copy(signature = signature)
        } else {
            pendingSignature = signature
        }
    }

    fun toolCallDelta(callId: String, name: String?, argumentsDelta: String) {
        val draft = toolCalls.getOrPut(callId) { ToolCallDraft(callId) }
        if (!name.isNullOrEmpty()) draft.name = name
        draft.arguments.append(argumentsDelta)
    }

    fun snapshot(): List<MessagePart> {
        if (toolCalls.isEmpty()) return parts.toList()
        return parts + toolCalls.values.map {
            MessagePart.ToolCall(callId = it.callId, name = it.name, argumentsJson = it.arguments.toString())
        }
    }

    private class ToolCallDraft(val callId: String) {
        var name: String = ""
        val arguments = StringBuilder()
    }
}
