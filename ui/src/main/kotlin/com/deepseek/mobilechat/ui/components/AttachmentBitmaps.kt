package com.deepseek.mobilechat.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import com.deepseek.mobilechat.domain.Attachment
import com.deepseek.mobilechat.domain.AttachmentId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves an attachment id to its stored record.
 *
 * Provided by the chat screen (it owns [com.deepseek.mobilechat.ui.AppServices]),
 * so deep children do not have to depend on a globally provided container and
 * the default composes harmlessly as "missing".
 */
val LocalAttachmentResolver = staticCompositionLocalOf<suspend (AttachmentId) -> Attachment?> {
    { _ -> null }
}

sealed interface AttachmentBitmapState {
    data object Loading : AttachmentBitmapState
    data class Ready(val bitmap: Bitmap) : AttachmentBitmapState
    data object Missing : AttachmentBitmapState
}

/**
 * Decodes a stored image off the main thread, downsampled to roughly the width
 * it will be drawn at. A full-resolution phone photo is ~50 MB in memory; the
 * thumbnail needs two orders of magnitude less.
 */
internal fun decodeSampledBitmap(path: String, targetWidthPx: Int): Bitmap? {
    if (path.isBlank() || targetWidthPx <= 0) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    var width = bounds.outWidth
    while (width / 2 >= targetWidthPx && sampleSize < 32) {
        width /= 2
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
}

@Composable
fun rememberAttachmentBitmap(
    attachmentId: AttachmentId,
    targetWidthPx: Int,
): AttachmentBitmapState {
    val resolver = LocalAttachmentResolver.current
    val state by produceState<AttachmentBitmapState>(
        initialValue = AttachmentBitmapState.Loading,
        key1 = attachmentId,
        key2 = targetWidthPx,
    ) {
        value = withContext(Dispatchers.IO) {
            val attachment = resolver(attachmentId) ?: return@withContext AttachmentBitmapState.Missing
            val bitmap = decodeSampledBitmap(attachment.localPath, targetWidthPx)
                ?: return@withContext AttachmentBitmapState.Missing
            AttachmentBitmapState.Ready(bitmap)
        }
    }
    return state
}

/** Same loader for callers that already hold the [Attachment] (composer chips). */
@Composable
fun rememberPathBitmap(
    path: String?,
    targetWidthPx: Int,
): AttachmentBitmapState {
    val state by produceState<AttachmentBitmapState>(
        initialValue = if (path.isNullOrBlank()) {
            AttachmentBitmapState.Missing
        } else {
            AttachmentBitmapState.Loading
        },
        key1 = path,
        key2 = targetWidthPx,
    ) {
        if (path.isNullOrBlank()) {
            value = AttachmentBitmapState.Missing
        } else {
            value = withContext(Dispatchers.IO) {
                val bitmap = decodeSampledBitmap(path, targetWidthPx)
                if (bitmap == null) AttachmentBitmapState.Missing else AttachmentBitmapState.Ready(bitmap)
            }
        }
    }
    return state
}
