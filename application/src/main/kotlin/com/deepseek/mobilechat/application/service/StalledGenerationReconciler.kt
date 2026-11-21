package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.ConversationStore
import com.deepseek.mobilechat.application.MessageStore
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessageStatus
import kotlinx.coroutines.flow.first

/**
 * Settles assistant rows left `STREAMING` by a process death.
 *
 * [ChatGenerationRunner] checkpoints partial output as `STREAMING` and writes
 * the terminal status only from its cancellation/error/completion path. A crash
 * or a kill while the app is in the background skips that write, so the row
 * stays `STREAMING` forever: the UI keeps showing a typing caret, [DefaultChatService.cancel]
 * has nothing live to stop, `continueGeneration` refuses the message because it
 * is not `LENGTH`, and `deleteSubtree` refuses it because it looks active.
 *
 * No generation can be running when this runs at startup — the queue is
 * in-memory and the process that owned it is gone — so every `STREAMING` row
 * found here is orphaned by definition. It is settled to an honest terminal
 * state: [MessageStatus.CANCELLED] with [FinishReason.CANCELLED] and an
 * explicit error, which keeps the partial text, stops the streaming UI and
 * enables both retry and continue.
 */
class StalledGenerationReconciler(
    private val conversationStore: ConversationStore,
    private val messageStore: MessageStore,
) {

    /** Returns the ids that were settled; a second run is a no-op. */
    suspend fun reconcile(): List<MessageId> {
        val settled = ArrayList<MessageId>()
        conversationStore.observeAll().first().forEach { conversation ->
            messageStore.list(conversation.id)
                .filter { it.status == MessageStatus.STREAMING }
                .forEach { message ->
                    messageStore.update(
                        message.copy(
                            status = MessageStatus.CANCELLED,
                            finishReason = FinishReason.CANCELLED,
                            error = INTERRUPTED_ERROR,
                        ),
                    )
                    settled += message.id
                }
        }
        return settled
    }

    companion object {
        /** Shown by the message footer so the user knows why it stopped. */
        const val INTERRUPTED_ERROR: String = "生成已中断：应用在生成过程中退出"
    }
}
