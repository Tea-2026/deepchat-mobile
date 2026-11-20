package com.deepseek.mobilechat.ui.chat

import androidx.lifecycle.viewModelScope
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.domain.AttachmentId
import kotlinx.coroutines.launch

/**
 * Attachment staging, kept next to but out of [ChatViewModel]'s own file.
 *
 * It is the only command path that touches two ports in sequence (import →
 * store) and owns its own busy flag; separating it keeps both files readable
 * without turning the ViewModel into a god object.
 *
 * **Draft conversations have no conversation row yet**, so the blob cannot be
 * filed under one: `AttachmentStore.save` takes a nullable conversation id and
 * a draft passes `null`. The attachment is then re-linked by
 * [`ChatViewModel.promote`] as soon as the first send has created the row
 * (`AttachmentStore.linkToConversation`). Filing a draft under a synthetic id
 * used to violate the attachment → conversation foreign key, so the blob existed
 * but the row could not be written at all.
 */

/** Imports a picker URI and persists it, then stages it for the next send. */
fun ChatViewModel.attach(uri: String) {
    val state = state.value
    if (state.attachmentBusy) return
    updateState { it.copy(attachmentBusy = true) }
    viewModelScope.launch {
        try {
            // `null` while the composer is a draft: there is no conversation row
            // to point at until the first send promotes it (see the file KDoc).
            val owner = activeConversationId.value
            when (val imported = services.fileImport.import(uri)) {
                is Outcome.Ok -> when (
                    val saved = services.attachmentStore.save(owner, imported.value)
                ) {
                    is Outcome.Ok -> updateState {
                        it.copy(
                            pendingAttachments = AttachmentDraft.add(
                                it.pendingAttachments,
                                saved.value,
                            ),
                        )
                    }

                    is Outcome.Err -> handleError(saved.error)
                }

                is Outcome.Err -> handleError(imported.error)
            }
        } finally {
            updateState { it.copy(attachmentBusy = false) }
        }
    }
}

/** Drops a staged attachment and the blob written for it. */
fun ChatViewModel.removeAttachment(id: AttachmentId) {
    updateState { it.copy(pendingAttachments = AttachmentDraft.remove(it.pendingAttachments, id)) }
    viewModelScope.launch {
        // The blob was written when it was staged; drop it so removing a chip
        // does not leave orphans behind.
        runCatching { services.attachmentStore.delete(id) }
    }
}
