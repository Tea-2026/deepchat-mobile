package com.deepseek.mobilechat.ui.chat

import com.deepseek.mobilechat.domain.Attachment
import com.deepseek.mobilechat.domain.AttachmentId
import java.util.Locale

/**
 * Pure rules for the "attachments I am about to send" list.
 *
 * Kept out of the ViewModel so the one thing that must not regress — *which ids
 * are handed to `ChatService.send`* — is testable without Android, Compose or
 * coroutines.
 */
internal object AttachmentDraft {

    /** Appends unless the same attachment is already staged (idempotent retry). */
    fun add(current: List<Attachment>, attachment: Attachment): List<Attachment> =
        if (current.any { it.id == attachment.id }) current else current + attachment

    fun remove(current: List<Attachment>, id: AttachmentId): List<Attachment> =
        current.filterNot { it.id == id }

    /** Exactly what `ChatService.send(attachmentIds = …)` receives, in draft order. */
    fun idsOf(current: List<Attachment>): List<AttachmentId> = current.map { it.id }

    fun totalBytes(current: List<Attachment>): Long = current.sumOf { it.sizeBytes }

    /** Attachments alone can make a message; an empty bubble cannot. */
    fun canSend(text: String, current: List<Attachment>): Boolean =
        text.isNotBlank() || current.isNotEmpty()
}

/** `812 B` / `12.4 KB` / `3.1 MB` — stable, locale independent. */
internal fun formatFileSize(bytes: Long): String = when {
    bytes < 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
