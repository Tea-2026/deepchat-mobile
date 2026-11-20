package com.deepseek.mobilechat.platform.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.FileImportPort
import com.deepseek.mobilechat.application.ImportedFile
import com.deepseek.mobilechat.application.Outcome
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a Storage Access Framework document into memory.
 *
 * The application layer only ever sees bytes + metadata; copying into internal
 * storage and hashing belongs to the attachment store.
 *
 * Limits: a single import may not exceed [MAX_BYTES]. The stream is read with a
 * hard cap (not just the provider-reported size, which may be missing or lie).
 */
class SafFileImportPort(context: Context) : FileImportPort {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override suspend fun import(uri: String): Outcome<ImportedFile> = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        if (parsed.scheme != ContentResolver.SCHEME_CONTENT) {
            return@withContext Outcome.Err(
                AppError.Unsupported("only content:// URIs can be imported, got '$uri'"),
            )
        }
        try {
            val metadata = queryMetadata(parsed)
            if (metadata.sizeBytes != null && metadata.sizeBytes > MAX_BYTES) {
                return@withContext tooLarge()
            }
            val stream = resolver.openInputStream(parsed)
                ?: return@withContext Outcome.Err(
                    AppError.Storage("the document provider returned no input stream"),
                )
            val bytes = stream.use { readLimited(it) }
            val fileName = metadata.displayName?.takeIf { it.isNotBlank() } ?: FALLBACK_FILE_NAME
            val mimeType = resolver.getType(parsed)?.takeIf { it.isNotBlank() }
                ?: mimeTypeFromFileName(fileName)
            return@withContext Outcome.Ok(
                ImportedFile(
                    fileName = fileName,
                    mimeType = mimeType,
                    bytes = bytes,
                    sizeBytes = bytes.size.toLong(),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FileTooLargeException) {
            tooLarge()
        } catch (error: SecurityException) {
            Outcome.Err(AppError.Storage("permission denied while reading the document: ${error.message}"))
        } catch (error: IOException) {
            Outcome.Err(AppError.Storage("could not read the document: ${error.message}"))
        } catch (error: RuntimeException) {
            Outcome.Err(AppError.Storage("could not read the document: ${error.message}"))
        }
    }

    private fun queryMetadata(uri: Uri): Metadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }
        return Metadata(displayName, sizeBytes)
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) throw FileTooLargeException()
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun tooLarge(): Outcome<ImportedFile> = Outcome.Err(
        AppError.Validation("file exceeds the ${MAX_BYTES / (1024 * 1024)} MB import limit"),
    )

    private class FileTooLargeException : IOException("file too large")

    private data class Metadata(val displayName: String?, val sizeBytes: Long?)

    companion object {
        const val MAX_BYTES: Long = 32L * 1024 * 1024
        private const val FALLBACK_FILE_NAME = "attachment"

        /** Fallback used when the provider has no MIME type (e.g. some file managers). */
        fun mimeTypeFromFileName(fileName: String): String {
            val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (extension) {
                "txt", "log" -> "text/plain"
                "md", "markdown" -> "text/markdown"
                "json" -> "application/json"
                "csv" -> "text/csv"
                "html", "htm" -> "text/html"
                "xml" -> "application/xml"
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "heic", "heif" -> "image/heic"
                "kt", "kts" -> "text/x-kotlin"
                "yaml", "yml" -> "application/yaml"
                else -> "application/octet-stream"
            }
        }
    }
}
