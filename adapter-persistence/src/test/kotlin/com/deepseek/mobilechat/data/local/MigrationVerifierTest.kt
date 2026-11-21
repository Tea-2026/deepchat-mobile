package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.data.local.log.verifyMigration
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.Usage
import com.deepseek.mobilechat.domain.log.ConversationLog
import com.deepseek.mobilechat.domain.log.EventData
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.SessionEvent
import com.deepseek.mobilechat.domain.log.SurfaceOp
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proof that [verifyMigration] is a real gate.
 *
 * A verifier that always returns an empty list would let a broken migration
 * through, so every field it claims to compare is mutated on purpose and the
 * discrepancy is asserted. The clean cases pin the two intentional normalisations
 * (nullable `finishReason`, and the excluded status/error/pinned fields) so the
 * gate does not produce false positives on real history.
 */
class MigrationVerifierTest {

    private val cid = ConversationId("c1")

    private val user = legacyUser("u1", null, "hi", 1_000)
    private val assistant = legacyAssistant(
        id = "a1",
        parent = "u1",
        text = "hello",
        time = 2_000,
        finishReason = FinishReason.STOP,
        usage = Usage(promptTokens = 10, completionTokens = 5, cost = 0.01),
    )

    private fun log(assistantFinish: FinishReason = FinishReason.STOP): ConversationLog = ConversationLog(
        cid,
        listOf(
            userEvent(1, "u1", null, "hi", 1_000),
            assistantEvent(2, "a1", "u1", "hello", 2_000, assistantFinish),
        ),
    )

    @Test
    fun faithfulReplayHasNoDiscrepancies() {
        val discrepancies = verifyMigration(cid, listOf(user, assistant), log())
        assertTrue("expected no differences, got $discrepancies", discrepancies.isEmpty())
    }

    @Test
    fun missingMessageIsReported() {
        val discrepancies = verifyMigration(cid, listOf(user), log())
        assertTrue(discrepancies.any { it.contains("count differs") })
        assertTrue(discrepancies.any { it.contains("a1") && it.contains("not in the legacy table") })
    }

    @Test
    fun extraMessageInTheLogIsReported() {
        val extra = legacyUser("u2", MessageId("a1"), "extra", 3_000)
        val discrepancies = verifyMigration(cid, listOf(user, assistant, extra), log())
        assertTrue(discrepancies.any { it.contains("count differs") })
        assertTrue(discrepancies.any { it.contains("u2") && it.contains("not in the log") })
    }

    @Test
    fun reorderingIsReported() {
        val discrepancies = verifyMigration(cid, listOf(assistant, user), log())
        assertTrue("reordering must be caught", discrepancies.isNotEmpty())
        assertTrue(discrepancies.any { it.contains("id legacy=") })
    }

    @Test
    fun everyComparedFieldIsActuallyCompared() {
        val mutations: List<Pair<String, Message>> = listOf(
            "id" to assistant.copy(id = MessageId("mutated-id")),
            "parentId" to assistant.copy(parentId = null),
            "role" to assistant.copy(role = MessageRole.USER),
            "parts" to assistant.copy(parts = listOf(MessagePart.Text("mutated text"))),
            "usage" to assistant.copy(usage = Usage(promptTokens = 999, completionTokens = 5)),
            "modelInfo" to assistant.copy(modelInfo = modelInfo(modelId = "mutated-model")),
            "finishReason" to assistant.copy(finishReason = FinishReason.LENGTH),
            "createdAt" to assistant.copy(createdAt = 9_999),
        )

        mutations.forEach { (field, mutated) ->
            val discrepancies = verifyMigration(cid, listOf(user, mutated), log())
            assertTrue(
                "mutation of $field was not reported: $discrepancies",
                discrepancies.any { it.contains(field) },
            )
        }
    }

    @Test
    fun aNullFinishReasonIsNormalisedFromTheLegacyStatus() {
        // A COMPLETE assistant row written before finish_reason was populated is
        // the same turn as finishReason = STOP; that must not be a migration
        // failure.
        val completeNull = assistant.copy(finishReason = null, status = MessageStatus.COMPLETE)
        assertTrue(verifyMigration(cid, listOf(user, completeNull), log()).isEmpty())

        // An interrupted STREAMING row means CANCELLED, and that is exactly what
        // the migration writes for it.
        val streamed = assistant.copy(finishReason = null, status = MessageStatus.STREAMING)
        assertTrue(
            verifyMigration(cid, listOf(user, streamed), log(FinishReason.CANCELLED)).isEmpty(),
        )
        // ... but the normalisation does not blindly accept any reason.
        assertTrue(verifyMigration(cid, listOf(user, streamed), log(FinishReason.STOP)).isNotEmpty())
    }

    @Test
    fun statusErrorAndPinnedAreDeliberatelyNotCompared() {
        // The event vocabulary cannot carry them; the acceptance brief excludes
        // them. Keeping this explicit stops someone "fixing" the verifier into
        // rejecting every real migration.
        val tweaked = assistant.copy(
            status = MessageStatus.FAILED,
            error = "boom",
            pinned = true,
            editedAt = 123,
        )
        assertTrue(verifyMigration(cid, listOf(user, tweaked), log()).isEmpty())
    }

    @Test
    fun anUnreadableLogIsReportedInsteadOfThrown() {
        val broken = ConversationLog(cid, listOf(userEvent(2, "u1", null, "hi", 1_000)))
        val discrepancies = verifyMigration(cid, listOf(user), broken)
        assertTrue(discrepancies.any { it.contains("cannot be projected") })
    }

    @Test
    fun aLogForAnotherConversationIsReported() {
        val otherId = ConversationId("other")
        val discrepancies = verifyMigration(otherId, listOf(user, assistant), log())
        assertTrue(discrepancies.any { it.contains("log belongs to c1") })
    }

    // --------------------------------------------------------------- fixtures

    private fun legacyUser(
        id: String,
        parent: MessageId?,
        text: String,
        time: Long,
    ): Message = Message(
        id = MessageId(id),
        conversationId = cid,
        parentId = parent,
        seq = 1,
        role = MessageRole.USER,
        parts = listOf(MessagePart.Text(text)),
        status = MessageStatus.COMPLETE,
        createdAt = time,
    )

    private fun legacyAssistant(
        id: String,
        parent: String?,
        text: String,
        time: Long,
        finishReason: FinishReason?,
        usage: Usage?,
    ): Message = Message(
        id = MessageId(id),
        conversationId = cid,
        parentId = parent?.let(::MessageId),
        seq = 1,
        role = MessageRole.ASSISTANT,
        parts = listOf(MessagePart.Text(text)),
        status = MessageStatus.COMPLETE,
        createdAt = time,
        modelInfo = modelInfo(),
        usage = usage,
        finishReason = finishReason,
    )

    private fun userEvent(
        seq: Long,
        id: String,
        parent: String?,
        text: String,
        time: Long,
    ): SessionEvent = SessionEvent(
        seq = seq,
        eventId = "e$seq",
        type = EventType.USER_MESSAGE,
        time = time,
        data = EventData.UserMessage(
            MessageId(id),
            parent?.let(::MessageId),
            listOf(MessagePart.Text(text)),
        ),
        surfaceOp = SurfaceOp.Append,
    )

    private fun assistantEvent(
        seq: Long,
        id: String,
        parent: String?,
        text: String,
        time: Long,
        finishReason: FinishReason,
    ): SessionEvent = SessionEvent(
        seq = seq,
        eventId = "e$seq",
        type = EventType.ASSISTANT_MESSAGE,
        time = time,
        data = EventData.AssistantMessage(
            messageId = MessageId(id),
            parentId = parent?.let(::MessageId),
            parts = listOf(MessagePart.Text(text)),
            modelInfo = modelInfo(),
            usage = Usage(promptTokens = 10, completionTokens = 5, cost = 0.01),
            finishReason = finishReason,
        ),
        surfaceOp = SurfaceOp.Append,
    )
}
