package com.deepseek.mobilechat.platform.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.ExportBundle
import com.deepseek.mobilechat.application.ExportFormat
import com.deepseek.mobilechat.application.ExportPort
import com.deepseek.mobilechat.application.IdPort
import com.deepseek.mobilechat.application.ImportedBundle
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.map
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Default id source for the platform adapters. */
object UuidIdPort : IdPort {
    override fun newId(): String = UUID.randomUUID().toString()
}

/** Default clock for the platform adapters. */
object SystemClockPort : ClockPort {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * SAF-backed export/import.
 *
 * The format itself lives in [ExportJson] / [ExportMarkdown] (pure Kotlin, JVM
 * tested). This class only does Android I/O: resolving a `content://` URI,
 * reading/writing streams, and creating per-conversation documents when the
 * target is a tree URI.
 */
class ExportPortImpl(
    context: Context,
    private val idPort: IdPort = UuidIdPort,
    private val clock: ClockPort = SystemClockPort,
) : ExportPort {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override suspend fun export(
        bundle: ExportBundle,
        format: ExportFormat,
        targetUri: String,
    ): Outcome<Unit> = withContext(Dispatchers.IO) {
        try {
            val uri = requireContentUri(targetUri)
            when (format) {
                ExportFormat.JSON -> {
                    val bytes = if (bundle.includeAttachmentBytes) {
                        readAttachmentBytes(bundle)
                    } else {
                        emptyMap()
                    }
                    writeText(uri, ExportJson.encode(bundle, bytes, clock.nowMillis()))
                }

                ExportFormat.MARKDOWN -> writeMarkdown(bundle, uri)
            }
            Outcome.Ok(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Outcome.Err(
                AppError.Storage(
                    "export failed: ${error.message ?: error::class.simpleName}",
                ),
            )
        }
    }

    override suspend fun import(sourceUri: String): Outcome<ImportedBundle> =
        // toImportedBundle() forwards DecodedExport.attachmentBytes, whose keys
        // are already the remapped (new) AttachmentIds.
        importDecoded(sourceUri).map { it.toImportedBundle() }

    /**
     * Full-fidelity variant of [import], kept on the implementation (not the
     * frozen [ExportPort] interface). [import] already carries presets, folders,
     * tags and attachment bytes through [ImportedBundle]; this exists for
     * callers that prefer to work with [DecodedExport] directly.
     */
    suspend fun importDecoded(sourceUri: String): Outcome<DecodedExport> =
        withContext(Dispatchers.IO) {
            try {
                val uri = requireContentUri(sourceUri)
                val input = resolver.openInputStream(uri)
                    ?: throw IOException("provider returned no input stream for $uri")
                val bytes = input.use { readLimited(it, MAX_IMPORT_BYTES) }
                val text = bytes.toString(StandardCharsets.UTF_8)
                Outcome.Ok(ExportJson.decode(text) { idPort.newId() })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (tooLarge: ImportTooLargeException) {
                Outcome.Err(AppError.Validation(tooLarge.message ?: "export file is too large"))
            } catch (format: JsonFormatException) {
                Outcome.Err(AppError.Validation("invalid export file: ${format.message}"))
            } catch (error: IOException) {
                Outcome.Err(AppError.Storage("import failed: ${error.message}"))
            } catch (error: SecurityException) {
                Outcome.Err(AppError.Storage("import failed: ${error.message}"))
            } catch (error: IllegalArgumentException) {
                Outcome.Err(AppError.Validation(error.message ?: "invalid source URI"))
            }
        }

    // -----------------------------------------------------------------------
    // Markdown
    // -----------------------------------------------------------------------

    private fun writeMarkdown(bundle: ExportBundle, targetUri: Uri) {
        if (!DocumentsContract.isTreeUri(targetUri)) {
            writeText(targetUri, ExportMarkdown.renderAll(bundle))
            return
        }

        // A tree URI means "one document per conversation" is possible.
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            targetUri,
            DocumentsContract.getTreeDocumentId(targetUri),
        )
        var written = 0
        bundle.conversations.forEach { conversation ->
            val documentUri = DocumentsContract.createDocument(
                resolver,
                parent,
                MARKDOWN_MIME,
                ExportMarkdown.fileNameFor(conversation),
            ) ?: throw IOException("could not create a Markdown document for '${conversation.title}'")
            writeText(documentUri, ExportMarkdown.renderConversation(bundle, conversation))
            written++
        }
        if (written == 0) {
            // Nothing to export: still leave a readable placeholder document.
            val documentUri = DocumentsContract.createDocument(
                resolver,
                parent,
                MARKDOWN_MIME,
                "mobile-chat-export.md",
            ) ?: throw IOException("could not create a Markdown document")
            writeText(documentUri, ExportMarkdown.renderAll(bundle))
        }
    }

    // -----------------------------------------------------------------------
    // I/O helpers
    // -----------------------------------------------------------------------

    private fun writeText(uri: Uri, text: String) {
        // "wt" truncates reliably, but a few providers reject the modes overload;
        // fall back to plain "w" for those.
        val stream = try {
            resolver.openOutputStream(uri, "wt")
        } catch (_: Exception) {
            null
        } ?: resolver.openOutputStream(uri, "w")
        if (stream == null) {
            throw IOException("provider returned no output stream for $uri")
        }
        stream.use { output ->
            output.write(text.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }
    }

    private fun readAttachmentBytes(bundle: ExportBundle): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        bundle.attachments.forEach { attachment ->
            val path = attachment.localPath
            if (path.isBlank()) return@forEach
            val file = File(path)
            if (!file.isFile) return@forEach
            if (file.length() > MAX_ATTACHMENT_BYTES) return@forEach
            try {
                val bytes = file.readBytes()
                if (bytes.size <= MAX_ATTACHMENT_BYTES) {
                    out[attachment.id.raw] = bytes
                }
            } catch (_: IOException) {
                // An unreadable local file must not abort the whole export: the
                // attachment stays in the manifest, just without inline bytes.
            }
        }
        return out
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw ImportTooLargeException(limit)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun requireContentUri(raw: String): Uri {
        val uri = Uri.parse(raw)
        require(uri.scheme == "content") { "expected a content:// URI but got '$raw'" }
        return uri
    }

    private class ImportTooLargeException(limit: Long) :
        IOException("export exceeds the ${limit / (1024 * 1024)} MB import limit")

    private companion object {
        const val MARKDOWN_MIME = "text/markdown"
        const val MAX_ATTACHMENT_BYTES = 32L * 1024 * 1024
        const val MAX_IMPORT_BYTES = 64L * 1024 * 1024
    }
}
