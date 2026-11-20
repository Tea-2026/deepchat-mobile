package com.deepseek.mobilechat.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.pressable
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing

/**
 * One image part of a message.
 *
 * The file is decoded off the main thread at roughly the width it will be drawn
 * at; while it loads a soft pulsing placeholder holds the layout so the bubble
 * does not jump when the image appears. Tapping opens the full-screen viewer.
 */
@Composable
fun MessageAttachmentImage(
    attachmentId: AttachmentId,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val targetWidthPx = with(density) {
        (configuration.screenWidthDp.dp * 0.8f).roundToPx()
    }
    val bitmapState = rememberAttachmentBitmap(attachmentId, targetWidthPx)
    var viewerOpen by remember { mutableStateOf(false) }
    val chatColors = LocalChatColors.current

    val shape = RoundedCornerShape(12.dp)
    val baseModifier = modifier
        .widthIn(max = 240.dp)
        .clip(shape)

    when (bitmapState) {
        is AttachmentBitmapState.Ready -> {
            val bitmap = bitmapState.bitmap
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "图片附件",
                contentScale = ContentScale.Crop,
                modifier = baseModifier
                    .aspectRatio(
                        (bitmap.width.toFloat() / bitmap.height.toFloat()).coerceIn(0.55f, 2.2f),
                    )
                    .pressable { viewerOpen = true },
            )
            if (viewerOpen) {
                ImageViewer(bitmap = bitmap, onDismiss = { viewerOpen = false })
            }
        }

        AttachmentBitmapState.Loading -> SkeletonBox(modifier = baseModifier)

        AttachmentBitmapState.Missing -> Surface(
            modifier = baseModifier.aspectRatio(4f / 3f),
            shape = shape,
            color = chatColors.codeBackground,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "图片不可用",
                    style = MaterialTheme.typography.labelSmall,
                    color = chatColors.mutedText,
                )
            }
        }
    }
}

/** Breathing block that reserves the image's space while it decodes. */
@Composable
private fun SkeletonBox(modifier: Modifier = Modifier) {
    val chatColors = LocalChatColors.current
    val transition = rememberInfiniteTransition(label = "imageSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.Durations.Typing, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "imageSkeletonAlpha",
    )
    Box(
        modifier = modifier
            .aspectRatio(4f / 3f)
            .background(chatColors.codeBackground.copy(alpha = alpha)),
    )
}

/**
 * Full-screen viewer: pinch to zoom, drag to pan while zoomed, tap the close
 * badge (or back) to dismiss. Single image, no pager — a message's images are
 * independent parts, not a gallery.
 */
@Composable
private fun ImageViewer(
    bitmap: android.graphics.Bitmap,
    onDismiss: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = nextScale
                        offset = if (nextScale > 1f) offset + pan else Offset.Zero
                    }
                },
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "图片附件",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.lg)
                    .size(36.dp)
                    .pressable(onClick = onDismiss),
                shape = RoundedCornerShape(percent = 50),
                color = Color.Black.copy(alpha = 0.5f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CloseIcon(
                        modifier = Modifier.iconSize(16.dp),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
