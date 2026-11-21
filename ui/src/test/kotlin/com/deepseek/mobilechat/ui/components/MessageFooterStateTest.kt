package com.deepseek.mobilechat.ui.components

import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the footer affordance decision (no Robolectric): an
 * interrupted/cancelled/errored turn must expose 「继续生成」 so the user is not
 * left at a dead end after a crash or a failed regenerate.
 */
class MessageFooterStateTest {

    private fun assistant(
        status: MessageStatus,
        finishReason: FinishReason?,
        text: String = "世卫 +",
    ): Message = Message(
        id = MessageId("a1"),
        conversationId = ConversationId("c1"),
        parentId = MessageId("u1"),
        seq = 1,
        role = MessageRole.ASSISTANT,
        parts = if (text.isEmpty()) emptyList() else listOf(MessagePart.Text(text)),
        status = status,
        createdAt = 0L,
        finishReason = finishReason,
    )

    @Test
    fun `cancelled turn offers continue`() {
        val state = messageFooterState(
            assistant(MessageStatus.CANCELLED, FinishReason.CANCELLED),
        )

        assertEquals(MessageFooterMode.INTERRUPTED, state.mode)
        assertTrue(state.visible)
        assertTrue(state.canContinue)
        assertFalse(state.canRetry)
    }

    @Test
    fun `failed turn offers retry and continue`() {
        val state = messageFooterState(assistant(MessageStatus.FAILED, FinishReason.ERROR))

        assertEquals(MessageFooterMode.FAILED, state.mode)
        assertTrue(state.visible)
        assertTrue(state.canContinue)
        assertTrue(state.canRetry)
    }

    @Test
    fun `errored finish reason is treated as failed even when status is complete`() {
        val state = messageFooterState(assistant(MessageStatus.COMPLETE, FinishReason.ERROR))

        assertEquals(MessageFooterMode.FAILED, state.mode)
        assertTrue(state.canContinue)
    }

    @Test
    fun `length truncation keeps the original continue affordance`() {
        val state = messageFooterState(assistant(MessageStatus.COMPLETE, FinishReason.LENGTH))

        assertEquals(MessageFooterMode.TRUNCATED, state.mode)
        assertTrue(state.canContinue)
    }

    @Test
    fun `natural completion has no footer`() {
        val state = messageFooterState(assistant(MessageStatus.COMPLETE, FinishReason.STOP))

        assertEquals(MessageFooterMode.NONE, state.mode)
        assertFalse(state.visible)
        assertFalse(state.canContinue)
    }

    @Test
    fun `streaming turn has no footer`() {
        val state = messageFooterState(assistant(MessageStatus.STREAMING, null))

        assertFalse(state.visible)
    }
}
