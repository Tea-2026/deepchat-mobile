package com.deepseek.mobilechat.platform.export

import com.deepseek.mobilechat.application.ExportBundle
import com.deepseek.mobilechat.domain.Attachment
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.MessageTree
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure Markdown renderer for exports. No Android APIs: unit-testable on the JVM.
 *
 * Only the **active path** of each conversation is rendered: the tree can hold
 * abandoned branches (regenerated answers, forks) that a human reading the
 * transcript does not need. Sibling information is kept as a `branch i/n`
 * marker so the reader can tell that alternatives exist.
 */
object ExportMarkdown {
    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC)

    /** One Markdown document containing every conversation, separated by rules. */
    fun renderAll(bundle: ExportBundle): String {
        if (bundle.conversations.isEmpty()) {
            return "# Mobile Chat export\n\n_No conversations were exported._\n"
        }
        return bundle.conversations.joinToString(separator = "\n\n---\n\n") {
            renderConversation(bundle, it)
        }
    }

    /** Messages on the conversation's active branch, root first. */
    fun activePath(bundle: ExportBundle, conversation: Conversation): List<Message> {
        val tree = MessageTree.from(
            bundle.messages.filter { it.conversationId == conversation.id },
        )
        return tree.pathTo(conversation.activeLeafId)
    }

    /** Readable transcript for a single conversation (active path only). */
    fun renderConversation(bundle: ExportBundle, conversation: Conversation): String {
        val tree = MessageTree.from(
            bundle.messages.filter { it.conversationId == conversation.id },
        )
        val path = tree.pathTo(conversation.activeLeafId)
        val attachmentsById = bundle.attachments.associateBy { it.id }

        val out = StringBuilder()
        out.append("# ").append(conversation.title.ifBlank { "Untitled conversation" }).append('\n')
        out.append('\n')
        out.append("- Conversation ID: `").append(conversation.id.raw).append("`\n")
        out.append("- Created: ").append(formatTimestamp(conversation.createdAt)).append('\n')
        out.append("- Updated: ").append(formatTimestamp(conversation.updatedAt)).append('\n')
        val overrides = conversation.overrides
        overrides.modelRef?.let { ref ->
            out.append("- Model: `").append(ref.providerId.raw).append('/')
                .append(ref.modelId.raw).append("`\n")
        }
        overrides.presetId?.let { preset ->
            out.append("- Preset: `").append(preset.raw).append("`\n")
        }
        conversation.folderId?.let { folder ->
            out.append("- Folder: `").append(folder.raw).append("`\n")
        }
        if (conversation.tagIds.isNotEmpty()) {
            out.append("- Tags: ")
            out.append(conversation.tagIds.joinToString(", ") { "`${it.raw}`" })
            out.append('\n')
        }
        if (conversation.pinned) out.append("- Pinned: yes\n")
        out.append("- Messages on active path: ").append(path.size).append('\n')

        if (path.isEmpty()) {
            out.append("\n_No messages on the active branch._\n")
            return out.toString()
        }

        path.forEach { message ->
            out.append('\n')
            out.append("## ")
                .append(roleLabel(message.role))
                .append(" · ")
                .append(formatTimestamp(message.createdAt))
                .append(branchSuffix(tree, message))
                .append(statusSuffix(message))
                .append('\n')
            out.append('\n')
            val rendered = message.parts.mapNotNull { renderPart(it, attachmentsById) }
            if (rendered.isEmpty()) {
                out.append("_(no content)_\n")
            } else {
                rendered.forEachIndexed { index, block ->
                    if (index > 0) out.append('\n')
                    out.append(block.trimEnd()).append('\n')
                }
            }
        }

        val referenced = path.asSequence()
            .flatMap { it.parts.asSequence() }
            .mapNotNull { partAttachmentId(it) }
            .toSet()
        val usedAttachments = bundle.attachments.filter { it.id in referenced }
        if (usedAttachments.isNotEmpty()) {
            out.append("\n## Attachments\n\n")
            usedAttachments.forEach { attachment ->
                out.append("- `").append(attachment.id.raw).append("` ")
                    .append(attachment.fileName)
                    .append(" (").append(attachment.mimeType)
                    .append(", ").append(attachment.sizeBytes).append(" bytes)\n")
            }
        }

        return out.toString()
    }

    /** Suggested file name for a per-conversation Markdown document. */
    fun fileNameFor(conversation: Conversation): String {
        val title = conversation.title.ifBlank { "conversation" }
        val safe = title
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "-")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)
            .trimEnd('.', ' ', '-')
            .ifBlank { "conversation" }
        val suffix = conversation.id.raw.take(8).ifBlank { "export" }
        return "$safe-$suffix.md"
    }

    fun formatTimestamp(millis: Long): String =
        TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(millis))

    // -----------------------------------------------------------------------

    private fun roleLabel(role: MessageRole): String = when (role) {
        MessageRole.SYSTEM -> "System"
        MessageRole.USER -> "User"
        MessageRole.ASSISTANT -> "Assistant"
        MessageRole.TOOL -> "Tool"
    }

    private fun branchSuffix(tree: MessageTree, message: Message): String {
        val siblings = tree.siblingsOf(message.id)
        if (siblings.size <= 1) return ""
        val index = siblings.indexOfFirst { it.id == message.id } + 1
        return " · branch $index/${siblings.size}"
    }

    private fun statusSuffix(message: Message): String =
        if (message.status == MessageStatus.COMPLETE) {
            ""
        } else {
            " · ${message.status.name.lowercase(Locale.ROOT)}"
        }

    private fun renderPart(
        part: MessagePart,
        attachments: Map<AttachmentId, Attachment>,
    ): String? = when (part) {
        is MessagePart.Text -> part.text.takeIf { it.isNotBlank() }
        is MessagePart.Image -> placeholder("Image", part.attachmentId, attachments)
        is MessagePart.FileRef -> placeholder("File", part.attachmentId, attachments)
        is MessagePart.Reasoning -> {
            val body = part.text.trim()
            buildString {
                append("> **Reasoning**\n>\n")
                append(quote(body.ifEmpty { "_(empty reasoning block)_" }))
                if (part.signature != null) {
                    append("\n>\n> _(signature preserved for provider round-trip)_")
                }
            }
        }
        is MessagePart.ContextBlock -> buildString {
            append("#### Context block · ").append(part.title)
                .append(" (").append(part.source.name).append(")\n\n")
            append(part.body.trim())
            if (part.citations.isNotEmpty()) {
                append("\n\n_Citations:_\n\n")
                part.citations.forEach { citation ->
                    append("- [").append(citation.title).append("](")
                        .append(citation.url).append(")\n")
                }
            }
        }
        is MessagePart.SearchResults -> buildString {
            append("#### Search · ").append(part.query).append("\n\n")
            if (part.hits.isEmpty()) {
                append("_No hits._")
            } else {
                part.hits.forEach { hit ->
                    append("- [").append(hit.title).append("](").append(hit.url).append(") — ")
                        .append(hit.snippet).append('\n')
                }
            }
        }
        is MessagePart.ToolCall -> buildString {
            append("#### Tool call · ").append(part.name).append('\n')
            append("\n`").append(part.callId).append("`\n\n")
            append("~~~json\n").append(part.argumentsJson).append("\n~~~")
        }
        is MessagePart.ToolResult -> buildString {
            append("#### Tool result · ").append(part.callId)
                .append(if (part.ok) " (ok)" else " (failed)").append("\n\n")
            append("~~~\n").append(part.content).append("\n~~~")
        }
    }

    private fun partAttachmentId(part: MessagePart): AttachmentId? = when (part) {
        is MessagePart.Image -> part.attachmentId
        is MessagePart.FileRef -> part.attachmentId
        else -> null
    }

    private fun placeholder(
        label: String,
        attachmentId: AttachmentId,
        attachments: Map<AttachmentId, Attachment>,
    ): String {
        val attachment = attachments[attachmentId]
        return if (attachment != null) {
            "[$label: ${attachment.fileName}]"
        } else {
            "[$label: ${attachmentId.raw}]"
        }
    }

    private fun quote(text: String): String =
        text.lineSequence().joinToString("\n") { line ->
            if (line.isEmpty()) ">" else "> $line"
        }
}
