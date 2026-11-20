package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.AttachmentStore
import com.deepseek.mobilechat.application.ConversationStore
import com.deepseek.mobilechat.application.ExportBundle
import com.deepseek.mobilechat.application.ExportFormat
import com.deepseek.mobilechat.application.ExportPort
import com.deepseek.mobilechat.application.FolderTagStore
import com.deepseek.mobilechat.application.IdPort
import com.deepseek.mobilechat.application.ImportedBundle
import com.deepseek.mobilechat.application.ImportedFile
import com.deepseek.mobilechat.application.MessageStore
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.PresetStore
import com.deepseek.mobilechat.application.TransferService
import com.deepseek.mobilechat.domain.Attachment
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FolderId
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.TagId
import kotlinx.coroutines.flow.first

/**
 * Default [TransferService] (docs/architecture.md §8.4).
 *
 * Export collects the requested conversations plus every message, attachment,
 * preset, folder and tag so a bundle is self-contained.
 *
 * Import always **reassigns ids**: conversations, messages, folders, tags,
 * presets and the attachment ids referenced by message parts all get fresh
 * local ids before anything is written, so importing a bundle never overwrites
 * the local graph (even when the adapter already reallocated ids on decode).
 * Cross-references are rewired in the same pass:
 *
 * - a message parent outside the bundle becomes a root (tree rule 2);
 * - an `activeLeafId` that cannot be remapped is cleared;
 * - `folderId` / `tagIds` / `overrides.presetId` point at the newly imported
 *   folder/tag/preset rows;
 * - attachments whose bytes were embedded in the export are recreated through
 *   [AttachmentStore.save], so their parts reference real local records; an
 *   attachment without embedded bytes keeps a synthetic id (the adapter that
 *   produced the bundle owns the files) and the assembler degrades it to
 *   "附件缺失" instead of sending a dangling reference.
 *
 * Imported presets are never marked default: importing data must not silently
 * change which preset the user's own new conversations start with.
 */
class DefaultTransferService(
    private val conversationStore: ConversationStore,
    private val messageStore: MessageStore,
    private val attachmentStore: AttachmentStore,
    private val presetStore: PresetStore,
    private val folderTagStore: FolderTagStore,
    private val exportPort: ExportPort,
    private val idPort: IdPort,
) : TransferService {

    override suspend fun exportConversations(
        conversationIds: List<ConversationId>,
        format: ExportFormat,
        targetUri: String,
        includeAttachments: Boolean,
    ): Outcome<Unit> {
        if (conversationIds.isEmpty()) {
            return Outcome.Err(AppError.Validation("no conversations selected for export"))
        }
        if (targetUri.isBlank()) {
            return Outcome.Err(AppError.Validation("export target must not be empty"))
        }
        val conversations = ArrayList<Conversation>(conversationIds.size)
        val messages = ArrayList<Message>()
        val attachments = ArrayList<Attachment>()
        for (id in conversationIds) {
            val conversation = conversationStore.get(id)
                ?: return Outcome.Err(AppError.Validation("conversation not found: ${id.raw}"))
            conversations += conversation
            messages += messageStore.list(id)
            attachments += attachmentStore.observe(id).first()
        }
        val bundle = ExportBundle(
            conversations = conversations,
            messages = messages,
            attachments = attachments,
            presets = presetStore.observeAll().first(),
            folders = folderTagStore.observeFolders().first(),
            tags = folderTagStore.observeTags().first(),
            includeAttachmentBytes = includeAttachments,
        )
        return exportPort.export(bundle, format, targetUri)
    }

    override suspend fun importConversations(sourceUri: String): Outcome<Int> {
        if (sourceUri.isBlank()) {
            return Outcome.Err(AppError.Validation("import source must not be empty"))
        }
        val bundle = when (val outcome = exportPort.import(sourceUri)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return Outcome.Err(outcome.error)
        }
        if (bundle.conversations.isEmpty()) return Outcome.Ok(0)

        val conversationIds = bundle.conversations.associate { it.id to ConversationId(idPort.newId()) }
        val messageIds = bundle.messages.associate { it.id to MessageId(idPort.newId()) }
        val folderIds = bundle.folders.associate { it.id to FolderId(idPort.newId()) }
        val tagIds = bundle.tags.associate { it.id to TagId(idPort.newId()) }
        val presetIds = bundle.presets.associate { it.id to PresetId(idPort.newId()) }

        val attachmentIds = when (
            val stored = storeAttachments(bundle, conversationIds)
        ) {
            is Outcome.Ok -> stored.value
            is Outcome.Err -> return Outcome.Err(stored.error)
        }

        val conversations = bundle.conversations.map { conversation ->
            conversation.copy(
                id = conversationIds.getValue(conversation.id),
                activeLeafId = conversation.activeLeafId?.let { messageIds[it] },
                folderId = conversation.folderId?.let { folderIds[it] },
                tagIds = conversation.tagIds.mapNotNull { tagIds[it] }.toSet(),
                overrides = conversation.overrides.copy(
                    presetId = conversation.overrides.presetId?.let { presetIds[it] },
                ),
            )
        }
        val messages = bundle.messages
            .filter { conversationIds.containsKey(it.conversationId) }
            .map { message ->
                message.copy(
                    id = messageIds.getValue(message.id),
                    conversationId = conversationIds.getValue(message.conversationId),
                    parentId = message.parentId?.let { messageIds[it] },
                    parts = message.parts.map { part ->
                        when (part) {
                            is MessagePart.Image -> part.copy(
                                attachmentId = attachmentIds[part.attachmentId]
                                    ?: part.attachmentId,
                            )

                            is MessagePart.FileRef -> part.copy(
                                attachmentId = attachmentIds[part.attachmentId]
                                    ?: part.attachmentId,
                            )

                            else -> part
                        }
                    },
                )
            }

        bundle.folders.forEach { folder ->
            folderTagStore.upsertFolder(folder.copy(id = folderIds.getValue(folder.id)))
        }
        bundle.tags.forEach { tag ->
            folderTagStore.upsertTag(tag.copy(id = tagIds.getValue(tag.id)))
        }
        bundle.presets.forEach { preset ->
            presetStore.upsert(
                preset.copy(id = presetIds.getValue(preset.id), isDefault = false),
            )
        }
        conversations.forEach { conversationStore.upsert(it) }
        messages.forEach { messageStore.append(it) }
        return Outcome.Ok(conversations.size)
    }

    /**
     * Recreates attachments whose bytes travelled with the bundle. Returns the
     * old -> local id map used to rewire message parts.
     *
     * An attachment without embedded bytes cannot be recreated through
     * [AttachmentStore] (it only creates from bytes); its parts keep a synthetic
     * id. A storage failure for embedded bytes aborts the import before any
     * conversation is written.
     */
    private suspend fun storeAttachments(
        bundle: ImportedBundle,
        conversationIds: Map<ConversationId, ConversationId>,
    ): Outcome<Map<AttachmentId, AttachmentId>> {
        if (bundle.attachments.isEmpty()) return Outcome.Ok(emptyMap())
        val owners = attachmentOwners(bundle)
        val fallbackConversation = conversationIds[bundle.conversations.first().id]
        val mapping = HashMap<AttachmentId, AttachmentId>(bundle.attachments.size)

        bundle.attachments.forEach { attachment ->
            val bytes = bundle.attachmentBytes[attachment.id]
            val owner = owners[attachment.id]?.let { conversationIds[it] } ?: fallbackConversation
            if (bytes == null || owner == null) {
                mapping[attachment.id] = AttachmentId(idPort.newId())
                return@forEach
            }
            val file = ImportedFile(
                fileName = attachment.fileName,
                mimeType = attachment.mimeType,
                bytes = bytes,
                // The payload is the truth; the serialized metadata may be stale.
                sizeBytes = bytes.size.toLong(),
            )
            when (val saved = attachmentStore.save(owner, file)) {
                is Outcome.Ok -> mapping[attachment.id] = saved.value.id
                is Outcome.Err -> return Outcome.Err(
                    AppError.Storage(
                        "could not restore attachment ${attachment.fileName}: ${saved.error.message}",
                    ),
                )
            }
        }
        return Outcome.Ok(mapping)
    }

    /** First conversation that references each attachment, in bundle terms. */
    private fun attachmentOwners(bundle: ImportedBundle): Map<AttachmentId, ConversationId> {
        val owners = HashMap<AttachmentId, ConversationId>()
        bundle.messages.forEach { message ->
            message.parts.forEach { part ->
                val id = when (part) {
                    is MessagePart.Image -> part.attachmentId
                    is MessagePart.FileRef -> part.attachmentId
                    else -> null
                }
                if (id != null) owners.putIfAbsent(id, message.conversationId)
            }
        }
        return owners
    }
}
