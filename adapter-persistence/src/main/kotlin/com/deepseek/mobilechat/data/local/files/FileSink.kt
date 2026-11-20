package com.deepseek.mobilechat.data.local.files

import java.io.File

/**
 * Byte-level file store used by the attachment store.
 *
 * Kept behind an interface so attachment persistence can be tested on the JVM
 * without touching `Context.filesDir`; [AndroidFileSink] is the only production
 * implementation and takes a plain [File], so it needs no Android API either.
 */
interface FileSink {
    /**
     * Writes [bytes] to [relativePath] below the sink root and returns the
     * absolute path that should be stored in `attachments.local_path`.
     */
    fun write(relativePath: String, bytes: ByteArray): String

    /** Best-effort removal; returns `true` when the file no longer exists. */
    fun delete(localPath: String): Boolean

    fun exists(localPath: String): Boolean
}

/** Writes into the app-private files directory (e.g. `context.filesDir`). */
class AndroidFileSink(private val baseDir: File) : FileSink {

    override fun write(relativePath: String, bytes: ByteArray): String {
        val base = baseDir.canonicalFile
        val target = File(base, relativePath).canonicalFile
        require(target.toPath().startsWith(base.toPath())) {
            "attachment path escapes the app files directory: $relativePath"
        }
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return target.absolutePath
    }

    override fun delete(localPath: String): Boolean {
        val file = File(localPath)
        return !file.exists() || file.delete()
    }

    override fun exists(localPath: String): Boolean = File(localPath).isFile
}
