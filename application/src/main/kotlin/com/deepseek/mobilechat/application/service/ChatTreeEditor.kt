package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.ConversationStore
import com.deepseek.mobilechat.application.EditResult
import com.deepseek.mobilechat.application.IdPort
import com.deepseek.mobilechat.application.MessageStore
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.MessageTree

/**
 * Message-tree mutations of [DefaultChatService], kept apart from generation
 * orchestration because they never touch the network.
 *
 * Every invariant of docs/architecture.md §3.4 is enforced here:
 *
 * - edit in place only through [MessageTree.canEditInPlace] (leaf); otherwise a
 *   sibling user message starts a new branch;
 * - deleting a subtree never touches a message that is still streaming, and it
 *   repairs `activeLeaf` (newest remaining sibling -> parent -> no leaf);
 * - switching a branch only accepts a message that exists and owns a
 *   conversation.
 */
internal class ChatTreeEditor(
    private val messageStore: MessageStore,
    private val conversationStore: ConversationStore,
    private val clock: ClockPort,
    private val idPort: IdPort,
) {

    suspend fun editMessage(messageId: MessageId, text: String): Outcome<EditResult> {
        val message = messageStore.get(messageId)
            ?: return Outcome.Err(AppError.Validation("message not found: ${messageId.raw}"))
        if (message.role != MessageRole.USER) {
            return Outcome.Err(AppError.Validation("only user messages can be edited"))
        }
        if (message.status == MessageStatus.STREAMING) {
            return Outcome.Err(AppError.BranchConflict("cannot edit a message that is still generating"))
        }
        val edited = text.trim()
        if (edited.isEmpty()) {
            return Outcome.Err(AppError.Validation("edited text must not be empty"))
        }
        val conversation = conversationStore.get(message.conversationId)
            ?: return Outcome.Err(AppError.Validation("conversation not found: ${message.conversationId.raw}"))
        val tree = MessageTree.from(messageStore.list(message.conversationId))
        val now = clock.nowMillis()

        if (tree.canEditInPlace(messageId)) {
            // Attachments stay attached; only the text part is rewritten.
            val parts = listOf(MessagePart.Text(edited)) +
                message.parts.filterNot { it is MessagePart.Text }
            messageStore.update(message.copy(parts = parts, editedAt = now))
            return Outcome.Ok(EditResult(messageId = messageId, forked = false))
        }

        val parentId = message.parentId
        val sibling = Message(
            id = MessageId(idPort.newId()),
            conversationId = message.conversationId,
            parentId = parentId,
            seq = tree.nextSeq(parentId),
            role = MessageRole.USER,
            parts = listOf(MessagePart.Text(edited)),
            status = MessageStatus.COMPLETE,
            createdAt = now,
        )
        messageStore.append(sibling)
        conversationStore.upsert(conversation.copy(activeLeafId = sibling.id, updatedAt = now))
        return Outcome.Ok(EditResult(messageId = sibling.id, forked = true))
    }

    suspend fun switchToBranch(messageId: MessageId): Outcome<Unit> {
        val message = messageStore.get(messageId)
            ?: return Outcome.Err(AppError.Validation("message not found: ${messageId.raw}"))
        val conversation = conversationStore.get(message.conversationId)
            ?: return Outcome.Err(AppError.Validation("conversation not found: ${message.conversationId.raw}"))
        conversationStore.upsert(
            conversation.copy(activeLeafId = messageId, updatedAt = clock.nowMillis()),
        )
        return Outcome.Ok(Unit)
    }

    suspend fun deleteSubtree(messageId: MessageId): Outcome<Unit> {
        val message = messageStore.get(messageId)
            ?: return Outcome.Err(AppError.Validation("message not found: ${messageId.raw}"))
        val conversation = conversationStore.get(message.conversationId)
            ?: return Outcome.Err(AppError.Validation("conversation not found: ${message.conversationId.raw}"))
        val messages = messageStore.list(message.conversationId)
        val tree = MessageTree.from(messages)
        val subtree = tree.subtreeOf(messageId)
        if (subtree.isEmpty()) {
            return Outcome.Err(AppError.Validation("message not found: ${messageId.raw}"))
        }
        val streaming = messages.firstOrNull {
            it.id in subtree && it.status == MessageStatus.STREAMING
        }
        if (streaming != null) {
            return Outcome.Err(
                AppError.BranchConflict("cannot delete a message that is still generating: ${streaming.id.raw}"),
            )
        }

        messageStore.remove(subtree)

        val activeLeaf = conversation.activeLeafId
        val newLeaf = if (activeLeaf != null && activeLeaf in subtree) {
            val parentId = message.parentId
            val replacement = tree.childrenOf(parentId).lastOrNull { it.id !in subtree }
            replacement?.id ?: parentId
        } else {
            activeLeaf
        }
        conversationStore.upsert(
            conversation.copy(activeLeafId = newLeaf, updatedAt = clock.nowMillis()),
        )
        return Outcome.Ok(Unit)
    }

    suspend fun setPinned(messageId: MessageId, pinned: Boolean): Outcome<Unit> {
        val message = messageStore.get(messageId)
            ?: return Outcome.Err(AppError.Validation("message not found: ${messageId.raw}"))
        messageStore.update(message.copy(pinned = pinned))
        return Outcome.Ok(Unit)
    }
}
