package com.deepseek.mobilechat.application.log

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.ChatCompletionPort
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.GenerationRequest
import com.deepseek.mobilechat.application.IdPort
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.SettingsStore
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.ModelInfo
import com.deepseek.mobilechat.domain.SearchHit
import com.deepseek.mobilechat.domain.Usage
import com.deepseek.mobilechat.domain.log.EventData
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.SurfaceOp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Progress notifications owned by the gateway's overlay (I11). */
internal interface TurnProgressSink {
    /** Publishes (or clears, with `null`) the in-flight turn of a conversation. */
    fun onStreaming(conversationId: ConversationId, messageId: MessageId, turn: StreamingTurn?)
}

/** Appends one batch; supplied by [DefaultChatGateway] so caching/versioning stays there. */
internal fun interface TurnAppend {
    suspend fun append(
        conversationId: ConversationId,
        events: List<PendingEvent>,
        index: ConversationIndexPatch?,
    ): Outcome<Long>
}

/**
 * Streams one assistant turn and lands it in the log.
 *
 * The log never contains a half turn (I8): while generation runs the only
 * durable state is the [TurnDraftStore] draft, which is overwritten at most once
 * per `AppSettings.streamingCheckpointMillis`. [TurnProgressSink.onStreaming]
 * publishes the live text to the UI.
 *
 * Terminal writes are exactly one batch:
 *  - completion → `assistant/message` (Append) + `turn/end` + `leaf/changed`
 *    + index patch (`activeLeafId`, `updatedAt`), then the draft is deleted;
 *  - failure/cancellation → `turn/interrupted` + `leaf/changed`, then the draft
 *    is deleted.
 *
 * The terminal batch runs inside `NonCancellable`, so a `cancel()` that
 * observes the interrupted event is guaranteed to find a complete log: the
 * caller never sees "cancelled but still streaming". A cancellation that wins
 * the race *before* the completion batch is decided produces an interrupted
 * turn instead, never a half-written assistant message.
 *
 * [leafOnFailure] is the I10 hook: `regenerate` passes the old assistant id so
 * a failed regeneration leaves the previous answer on the active path instead
 * of swapping in a failed sibling.
 */
internal class LogTurnRunner(
    private val draftStore: TurnDraftStore,
    private val chatCompletion: ChatCompletionPort,
    private val settingsStore: SettingsStore,
    private val clock: ClockPort,
    private val idPort: IdPort,
    private val append: TurnAppend,
    private val progress: TurnProgressSink,
) {

    suspend fun run(
        conversationId: ConversationId,
        assistantMessageId: MessageId,
        parentId: MessageId?,
        request: GenerationRequest,
        citations: List<SearchHit> = emptyList(),
        leafOnFailure: MessageId? = null,
    ) {
        val startedAt = clock.nowMillis()
        val accumulator = StreamAccumulator()
        var usage: Usage? = null
        var finishReason: FinishReason? = null
        var failure: AppError? = null
        var lastCheckpoint = startedAt
        val checkpointMillis = checkpointInterval()

        publish(conversationId, assistantMessageId, accumulator)
        runCatching { draftStore.put(conversationId, assistantMessageId, accumulator.text, accumulator.reasoning) }

        try {
            chatCompletion.stream(request).collect { event ->
                when (event) {
                    is GenerationEvent.Started -> Unit

                    is GenerationEvent.TextDelta -> {
                        accumulator.textDelta(event.text)
                        publish(conversationId, assistantMessageId, accumulator)
                        lastCheckpoint = checkpoint(
                            conversationId = conversationId,
                            assistantMessageId = assistantMessageId,
                            accumulator = accumulator,
                            lastCheckpoint = lastCheckpoint,
                            intervalMillis = checkpointMillis,
                        )
                    }

                    is GenerationEvent.ReasoningDelta -> {
                        accumulator.reasoningDelta(event.text)
                        publish(conversationId, assistantMessageId, accumulator)
                        lastCheckpoint = checkpoint(
                            conversationId = conversationId,
                            assistantMessageId = assistantMessageId,
                            accumulator = accumulator,
                            lastCheckpoint = lastCheckpoint,
                            intervalMillis = checkpointMillis,
                        )
                    }

                    is GenerationEvent.ReasoningSignature ->
                        accumulator.reasoningSignature(event.signature)

                    is GenerationEvent.ToolCallDelta -> {
                        accumulator.toolCallDelta(event.callId, event.name, event.argumentsDelta)
                        lastCheckpoint = checkpoint(
                            conversationId = conversationId,
                            assistantMessageId = assistantMessageId,
                            accumulator = accumulator,
                            lastCheckpoint = lastCheckpoint,
                            intervalMillis = checkpointMillis,
                        )
                    }

                    is GenerationEvent.UsageReport -> usage = event.usage
                    is GenerationEvent.Finished -> finishReason = event.reason
                    is GenerationEvent.Failed -> failure = event.error
                }
            }

            val error = failure
            if (error != null) {
                finalizeInterrupted(
                    conversationId = conversationId,
                    assistantMessageId = assistantMessageId,
                    parentId = parentId,
                    partialText = accumulator.text,
                    reason = error.message,
                    leafOnFailure = leafOnFailure,
                )
            } else {
                finalizeCompleted(
                    conversationId = conversationId,
                    assistantMessageId = assistantMessageId,
                    parentId = parentId,
                    parts = accumulator.snapshotParts(),
                    modelInfo = ModelInfo(request.modelRef, request.params, request.thinkingLevel),
                    usage = usage,
                    finishReason = finishReason ?: FinishReason.STOP,
                    citations = citations,
                    startedAt = startedAt,
                )
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching {
                    finalizeInterrupted(
                        conversationId = conversationId,
                        assistantMessageId = assistantMessageId,
                        parentId = parentId,
                        partialText = accumulator.text,
                        reason = CANCELLED_REASON,
                        leafOnFailure = leafOnFailure,
                    )
                }
                runCatching { draftStore.delete(assistantMessageId) }
            }
            throw cancelled
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                runCatching {
                    finalizeInterrupted(
                        conversationId = conversationId,
                        assistantMessageId = assistantMessageId,
                        parentId = parentId,
                        partialText = accumulator.text,
                        reason = error.message ?: GENERATION_FAILED_REASON,
                        leafOnFailure = leafOnFailure,
                    )
                }
            }
        } finally {
            progress.onStreaming(conversationId, assistantMessageId, null)
        }
    }

    // ---------------------------------------------------------------------
    // Terminal writes
    // ---------------------------------------------------------------------

    private suspend fun finalizeCompleted(
        conversationId: ConversationId,
        assistantMessageId: MessageId,
        parentId: MessageId?,
        parts: List<MessagePart>,
        modelInfo: ModelInfo,
        usage: Usage?,
        finishReason: FinishReason,
        citations: List<SearchHit>,
        startedAt: Long,
    ) {
        withContext(NonCancellable) {
            val now = clock.nowMillis()
            val events = listOf(
                PendingEvent(
                    eventId = idPort.newId(),
                    type = EventType.ASSISTANT_MESSAGE,
                    time = now,
                    data = EventData.AssistantMessage(
                        messageId = assistantMessageId,
                        parentId = parentId,
                        parts = parts,
                        modelInfo = modelInfo,
                        // The engine owns the money too: price the turn before it
                        // is written, so a message can never be stored without a
                        // cost while usage is known.
                        usage = usage?.let {
                            com.deepseek.mobilechat.application.pricing.PriceBook.priceUsage(
                                modelId = modelInfo.ref.modelId.raw,
                                usage = it,
                                atMillis = now,
                            )
                        },
                        finishReason = finishReason,
                        citations = citations,
                    ),
                    surfaceOp = SurfaceOp.Append,
                ),
                PendingEvent(
                    eventId = idPort.newId(),
                    type = EventType.TURN_END,
                    time = now,
                    data = EventData.TurnEnd(
                        messageId = assistantMessageId,
                        finishReason = finishReason,
                        usage = usage,
                        durationMs = (now - startedAt).coerceAtLeast(0L),
                    ),
                ),
                PendingEvent(
                    eventId = idPort.newId(),
                    type = EventType.LEAF_CHANGED,
                    time = now,
                    data = EventData.LeafChanged(assistantMessageId),
                ),
            )
            val appended = append.append(
                conversationId = conversationId,
                events = events,
                index = ConversationIndexPatch(
                    activeLeafId = assistantMessageId,
                    updatedAt = now,
                ),
            )
            // Only forget the draft once the answer is durable; if the append
            // failed the draft is the only copy left and startup reconciliation
            // will turn it into an interrupted turn instead of losing text.
            if (appended is Outcome.Ok) {
                runCatching { draftStore.delete(assistantMessageId) }
            }
        }
    }

    private suspend fun finalizeInterrupted(
        conversationId: ConversationId,
        assistantMessageId: MessageId,
        parentId: MessageId?,
        partialText: String,
        reason: String,
        leafOnFailure: MessageId?,
    ) {
        withContext(NonCancellable) {
            val now = clock.nowMillis()
            val leaf = leafOnFailure ?: assistantMessageId
            val events = listOf(
                PendingEvent(
                    eventId = idPort.newId(),
                    type = EventType.TURN_INTERRUPTED,
                    time = now,
                    data = EventData.TurnInterrupted(
                        messageId = assistantMessageId,
                        parentId = parentId,
                        partialText = partialText,
                        reason = reason,
                    ),
                ),
                PendingEvent(
                    eventId = idPort.newId(),
                    type = EventType.LEAF_CHANGED,
                    time = now,
                    data = EventData.LeafChanged(leaf),
                ),
            )
            append.append(
                conversationId = conversationId,
                events = events,
                index = ConversationIndexPatch(activeLeafId = leaf, updatedAt = now),
            )
            runCatching { draftStore.delete(assistantMessageId) }
        }
    }

    // ---------------------------------------------------------------------
    // Checkpointing / progress
    // ---------------------------------------------------------------------

    private fun publish(
        conversationId: ConversationId,
        assistantMessageId: MessageId,
        accumulator: StreamAccumulator,
    ) {
        progress.onStreaming(
            conversationId,
            assistantMessageId,
            StreamingTurn(
                messageId = assistantMessageId,
                text = accumulator.text,
                reasoning = accumulator.reasoning,
            ),
        )
    }

    private suspend fun checkpoint(
        conversationId: ConversationId,
        assistantMessageId: MessageId,
        accumulator: StreamAccumulator,
        lastCheckpoint: Long,
        intervalMillis: Long,
    ): Long {
        val now = clock.nowMillis()
        if (now - lastCheckpoint < intervalMillis) return lastCheckpoint
        runCatching {
            draftStore.put(conversationId, assistantMessageId, accumulator.text, accumulator.reasoning)
        }
        return now
    }

    private suspend fun checkpointInterval(): Long =
        try {
            settingsStore.observe().first().streamingCheckpointMillis.coerceAtLeast(0L)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            DEFAULT_CHECKPOINT_MILLIS
        }

    companion object {
        const val DEFAULT_CHECKPOINT_MILLIS: Long = 300L
        const val CANCELLED_REASON: String = "生成已取消"
        const val GENERATION_FAILED_REASON: String = "生成失败"
    }
}

/**
 * Mutable part list fed by stream events. Text/reasoning deltas extend the last
 * part of their own kind; tool-call fragments merge per call id and are emitted
 * after the text so a persisted message can be replayed verbatim.
 */
private class StreamAccumulator {

    private val parts = ArrayList<MessagePart>()
    private val toolCalls = LinkedHashMap<String, ToolCallDraft>()
    private var lastReasoningIndex = -1
    private var pendingSignature: String? = null

    val text: String
        get() = parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }

    val reasoning: String
        get() = parts.filterIsInstance<MessagePart.Reasoning>().joinToString("") { it.text }

    fun textDelta(delta: String) {
        if (delta.isEmpty()) return
        val last = parts.lastOrNull()
        if (last is MessagePart.Text) {
            parts[parts.lastIndex] = last.copy(text = last.text + delta)
        } else {
            parts += MessagePart.Text(delta)
        }
    }

    fun reasoningDelta(delta: String) {
        if (delta.isEmpty()) return
        val last = parts.lastOrNull()
        if (last is MessagePart.Reasoning) {
            parts[parts.lastIndex] = last.copy(text = last.text + delta)
            lastReasoningIndex = parts.lastIndex
        } else {
            parts += MessagePart.Reasoning(delta, pendingSignature)
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

    fun snapshotParts(): List<MessagePart> {
        if (toolCalls.isEmpty()) return parts.toList()
        return parts + toolCalls.values.map {
            MessagePart.ToolCall(
                callId = it.callId,
                name = it.name,
                argumentsJson = it.arguments.toString(),
            )
        }
    }

    private class ToolCallDraft(val callId: String) {
        var name: String = ""
        val arguments = StringBuilder()
    }
}
