package com.deepseek.mobilechat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.domain.Attachment
import com.deepseek.mobilechat.domain.AttachmentKind
import com.deepseek.mobilechat.ui.chat.formatFileSize
import com.deepseek.mobilechat.ui.motion.pressable
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing

/**
 * A file staged for the next message. Images show their own thumbnail (that is
 * the fastest way to confirm the right picture was picked); everything else
 * shows name + size.
 */
@Composable
fun StagedAttachmentChip(
    attachment: Attachment,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachment.kind == AttachmentKind.IMAGE) {
        ImageAttachmentChip(attachment = attachment, onRemove = onRemove, modifier = modifier)
    } else {
        FileAttachmentChip(attachment = attachment, onRemove = onRemove, modifier = modifier)
    }
}

@Composable
private fun ImageAttachmentChip(
    attachment: Attachment,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val targetPx = with(LocalDensity.current) { 56.dp.roundToPx() * 2 }
    val bitmapState = rememberPathBitmap(attachment.localPath, targetPx)
    val chatColors = LocalChatColors.current

    Box(modifier = modifier.size(56.dp)) {
        Surface(
            modifier = Modifier.matchParentSize(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, chatColors.divider),
        ) {
            when (bitmapState) {
                is AttachmentBitmapState.Ready -> Image(
                    bitmap = bitmapState.bitmap.asImageBitmap(),
                    contentDescription = attachment.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> Box(contentAlignment = Alignment.Center) {
                    FileIcon(
                        modifier = Modifier.iconSize(20.dp),
                        tint = chatColors.mutedText,
                    )
                }
            }
        }
        RemoveBadge(
            onRemove = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp),
            containerColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun FileAttachmentChip(
    attachment: Attachment,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, chatColors.divider),
    ) {
        Row(
            modifier = Modifier.padding(
                start = Spacing.sm,
                end = Spacing.xs,
                top = Spacing.xs,
                bottom = Spacing.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileIcon(
                modifier = Modifier.iconSize(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
            Column(modifier = Modifier.widthIn(max = 150.dp)) {
                Text(
                    text = attachment.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatFileSize(attachment.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = chatColors.mutedText,
                )
            }
            Spacer(modifier = Modifier.width(Spacing.xxs))
            RemoveBadge(
                onRemove = onRemove,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RemoveBadge(
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    Surface(
        modifier = modifier
            .size(18.dp)
            .pressable(onClick = onRemove),
        shape = CircleShape,
        color = containerColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CloseIcon(modifier = Modifier.iconSize(10.dp), tint = contentColor)
        }
    }
}

/** Shown while the picked file is being imported into the app's storage. */
@Composable
fun AttachmentBusyChip(modifier: Modifier = Modifier) {
    val chatColors = LocalChatColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, chatColors.divider),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.6.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
            Text(
                text = "正在读取…",
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
            )
        }
    }
}
