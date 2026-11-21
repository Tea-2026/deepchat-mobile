package com.deepseek.mobilechat.data.local.log

import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.log.ConversationLog
import com.deepseek.mobilechat.domain.log.LogFormatException
import com.deepseek.mobilechat.domain.log.TranscriptProjector

/**
 * The migration acceptance gate: does replaying [log] reproduce [legacy]
 * field-for-field?
 *
 * This is deliberately a *pure function over values*, not a database check. The
 * migration calls it before committing a conversation, and the tests call it
 * with a deliberately mutated [legacy] list to prove it actually catches drift
 * instead of vacuously returning an empty list.
 *
 * Fields compared, per `docs/architecture-v2-log.md` §13.8 and the acceptance
 * brief: `id`, `parentId`, `role`, `parts` (structural equality), `usage`,
 * `modelInfo`, `finishReason`, `createdAt`. Position matters as well — the log
 * must reproduce the same order, not just the same set.
 *
 * Two conversions are intentional and therefore normalised on both sides:
 *
 *  - the event vocabulary has no `MessageStatus` / `error` / `editedAt` /
 *    `pinned`, so those are not compared;
 *  - `assistant/message` requires a non-null `FinishReason`. A legacy row with
 *    a NULL `finish_reason` (an interrupted `STREAMING` row, or a `FAILED` row
 *    written by an older build) is read through [assistantFinishReason], the
 *    same normalisation the migration applies when it encodes the event.
 *
 * The function never throws: a log that cannot even be projected is reported as
 * a discrepancy, because "the migration produced an unreadable log" is exactly
 * what the gate must fail on.
 *
 * @return every difference found; empty means the migration is faithful.
 */
fun verifyMigration(
    conversationId: ConversationId,
    legacy: List<Message>,
    log: ConversationLog,
): List<String> {
    val discrepancies = ArrayList<String>()

    if (log.conversationId != conversationId) {
        discrepancies += "log belongs to ${log.conversationId.raw}, expected ${conversationId.raw}"
    }

    val projected = try {
        TranscriptProjector.project(log).messages
    } catch (e: LogFormatException) {
        discrepancies += "log cannot be projected: ${e.message}"
        return discrepancies
    } catch (e: IllegalArgumentException) {
        discrepancies += "log cannot be projected: ${e.message}"
        return discrepancies
    }

    if (projected.size != legacy.size) {
        discrepancies += "message count differs: legacy=${legacy.size}, log=${projected.size}"
    }

    val count = maxOf(legacy.size, projected.size)
    for (index in 0 until count) {
        val old = legacy.getOrNull(index)
        val new = projected.getOrNull(index)
        if (old == null) {
            discrepancies += "message[$index]: ${new?.id?.raw} exists in the log but not in the legacy table"
            continue
        }
        if (new == null) {
            discrepancies += "message[$index]: ${old.id.raw} exists in the legacy table but not in the log"
            continue
        }
        val where = "message[$index] ${old.id.raw}"

        if (old.conversationId != conversationId) {
            discrepancies += "$where: conversationId legacy=${old.conversationId.raw}, expected=${conversationId.raw}"
        }
        if (old.id != new.id) {
            discrepancies += "$where: id legacy=${old.id.raw}, log=${new.id.raw}"
        }
        if (old.parentId != new.parentId) {
            discrepancies += "$where: parentId legacy=${old.parentId?.raw}, log=${new.parentId?.raw}"
        }
        if (old.role != new.role) {
            discrepancies += "$where: role legacy=${old.role}, log=${new.role}"
        }
        if (old.parts != new.parts) {
            discrepancies += "$where: parts differ (legacy=${describeParts(old.parts)}, log=${describeParts(new.parts)})"
        }
        if (old.usage != new.usage) {
            discrepancies += "$where: usage legacy=${old.usage}, log=${new.usage}"
        }
        if (old.modelInfo != new.modelInfo) {
            discrepancies += "$where: modelInfo legacy=${old.modelInfo}, log=${new.modelInfo}"
        }
        val expectedFinish = expectedFinishReason(old)
        if (expectedFinish != new.finishReason) {
            discrepancies += "$where: finishReason legacy=$expectedFinish, log=${new.finishReason}"
        }
        if (old.createdAt != new.createdAt) {
            discrepancies += "$where: createdAt legacy=${old.createdAt}, log=${new.createdAt}"
        }
    }

    return discrepancies
}

/**
 * The `FinishReason` an `assistant/message` event can carry for [message].
 *
 * `EventData.AssistantMessage.finishReason` is non-nullable, so a legacy NULL
 * has to be given the terminal meaning its [MessageStatus] already had. This is
 * the only normalisation in the comparison and both the encoder and the verifier
 * call it, so neither side can drift.
 */
internal fun assistantFinishReason(message: Message): FinishReason =
    message.finishReason ?: when (message.status) {
        MessageStatus.STREAMING, MessageStatus.CANCELLED -> FinishReason.CANCELLED
        MessageStatus.FAILED -> FinishReason.ERROR
        MessageStatus.COMPLETE -> FinishReason.STOP
    }

private fun expectedFinishReason(message: Message): FinishReason? =
    if (message.role == MessageRole.ASSISTANT) assistantFinishReason(message) else message.finishReason

private fun describeParts(parts: List<com.deepseek.mobilechat.domain.MessagePart>): String =
    parts.joinToString(prefix = "[", postfix = "]") { part -> part::class.simpleName ?: "?" }
