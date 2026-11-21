package com.deepseek.mobilechat.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.pressable
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlin.math.abs
import kotlinx.coroutines.launch

private val ThumbnailSize = 72.dp
private val ThumbnailGap = 8.dp

/**
 * The images of one message, the way the official app shows them: a single
 * row of 72dp square thumbnails (never stretched to the bubble width), four at
 * most, with the last square showing `+N` when more exist. Tapping a square
 * opens the full-screen pager at that image.
 */
@Composable
fun MessageAttachmentStrip(
    attachmentIds: List<AttachmentId>,
    modifier: Modifier = Modifier,
) {
    if (attachmentIds.isEmpty()) return
    var previewIndex by remember(attachmentIds) { mutableStateOf<Int?>(null) }
    val slots = remember(attachmentIds) { attachmentStripSlots(attachmentIds.size) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ThumbnailGap),
    ) {
        slots.forEach { slot ->
            AttachmentThumbnail(
                attachmentId = attachmentIds[slot.index],
                overflow = slot.overflow,
                onClick = { previewIndex = slot.index },
            )
        }
    }

    previewIndex?.let { index ->
        MessageImagePreview(
            attachmentIds = attachmentIds,
            initialIndex = index,
            onDismiss = { previewIndex = null },
        )
    }
}

/**
 * Single-image entry point kept for existing callers. Message bodies now use
 * [MessageAttachmentStrip]; this is just a one-image strip.
 */
@Composable
fun MessageAttachmentImage(
    attachmentId: AttachmentId,
    modifier: Modifier = Modifier,
) {
    MessageAttachmentStrip(attachmentIds = listOf(attachmentId), modifier = modifier)
}

@Composable
private fun AttachmentThumbnail(
    attachmentId: AttachmentId,
    overflow: Int,
    onClick: () -> Unit,
) {
    val targetPx = with(LocalDensity.current) { (ThumbnailSize * 2f).roundToPx() }
    val state = rememberAttachmentBitmap(attachmentId, targetPx)
    val chatColors = LocalChatColors.current
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = Modifier
            .size(ThumbnailSize)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pressable(onClick = onClick),
    ) {
        when (state) {
            is AttachmentBitmapState.Ready -> Image(
                bitmap = state.bitmap.asImageBitmap(),
                contentDescription = "图片附件",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            AttachmentBitmapState.Loading -> LoadingThumbnail()

            AttachmentBitmapState.Missing -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                GalleryIcon(
                    modifier = Modifier.iconSize(18.dp),
                    tint = chatColors.mutedText,
                )
            }
        }

        if (overflow > 0) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.52f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Breathing placeholder that reserves the square while the image decodes. */
@Composable
private fun LoadingThumbnail() {
    val chatColors = LocalChatColors.current
    val transition = rememberInfiniteTransition(label = "thumbnailSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.Durations.Typing, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thumbnailSkeletonAlpha",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(chatColors.codeBackground.copy(alpha = alpha)),
    )
}

/**
 * Full-screen image viewer: dark scrim, horizontal pager across the message's
 * images, pinch / double-tap zoom, pan while zoomed, tap or swipe down to
 * dismiss, and a close affordance. Compose only — no image-viewer dependency.
 */
@Composable
fun MessageImagePreview(
    attachmentIds: List<AttachmentId>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (attachmentIds.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val pagerState = rememberPagerState(
            initialPage = initialIndex.coerceIn(0, attachmentIds.lastIndex),
        ) { attachmentIds.size }
        val zoom = remember { PreviewZoomState() }
        val dragY = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        var viewport by remember { mutableStateOf(IntSize.Zero) }
        val dismissThreshold = with(density) { 140.dp.toPx() }
        val zoomed = zoom.scale > 1.01f

        LaunchedEffect(pagerState.currentPage) {
            zoom.reset()
            dragY.snapTo(0f)
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewport = it }
                    .graphicsLayer {
                        translationY = dragY.value
                        val progress = if (size.height > 0f) {
                            (abs(dragY.value) / size.height).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        alpha = 1f - progress * 0.5f
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onDismiss() },
                            onDoubleTap = {
                                if (zoom.scale > 1.01f) {
                                    zoom.reset()
                                } else {
                                    zoom.scale = 2.5f
                                    zoom.offset = Offset.Zero
                                }
                            },
                        )
                    }
                    .then(
                        if (!zoomed) {
                            Modifier.pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        if (dragY.value > dismissThreshold) {
                                            onDismiss()
                                        } else {
                                            scope.launch {
                                                dragY.animateTo(0f, Motion.Springs.Light)
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        scope.launch { dragY.animateTo(0f, Motion.Springs.Light) }
                                    },
                                ) { change, dragAmount ->
                                    change.consume()
                                    scope.launch {
                                        dragY.snapTo(dragY.value + dragAmount)
                                    }
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !zoomed,
                ) { page ->
                    val isCurrent = page == pagerState.currentPage
                    PreviewPage(
                        attachmentId = attachmentIds[page],
                        zoom = zoom,
                        applyZoom = isCurrent,
                        onTransform = { zoomDelta, pan ->
                            val nextScale = (zoom.scale * zoomDelta).coerceIn(1f, 5f)
                            zoom.scale = nextScale
                            zoom.offset = if (nextScale > 1f) {
                                clampOffset(zoom.offset + pan, nextScale, viewport)
                            } else {
                                Offset.Zero
                            }
                        },
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(Spacing.lg)
                    .size(38.dp)
                    .pressable(onClick = onDismiss),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CloseIcon(
                        modifier = Modifier.iconSize(16.dp),
                        tint = Color.White,
                        contentDescription = "关闭",
                    )
                }
            }

            if (attachmentIds.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1}/${attachmentIds.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = Spacing.xl),
                )
            }
        }
    }
}

/** Mutable transform shared by the pager page and the tap/drag chrome. */
private class PreviewZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

@Composable
private fun PreviewPage(
    attachmentId: AttachmentId,
    zoom: PreviewZoomState,
    applyZoom: Boolean,
    onTransform: (zoomDelta: Float, pan: Offset) -> Unit,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val targetWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val state = rememberAttachmentBitmap(attachmentId, targetWidthPx)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(attachmentId) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            val zoomDelta = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoomDelta != 1f || pan != Offset.Zero) {
                                onTransform(zoomDelta, pan)
                                event.changes.forEach { if (it.pressed) it.consume() }
                            }
                        } else if (zoom.scale > 1.01f) {
                            val pan = event.calculatePan()
                            if (pan != Offset.Zero) {
                                onTransform(1f, pan)
                                event.changes.forEach { if (it.pressed) it.consume() }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is AttachmentBitmapState.Ready -> Image(
                bitmap = state.bitmap.asImageBitmap(),
                contentDescription = "图片附件",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (applyZoom) {
                            scaleX = zoom.scale
                            scaleY = zoom.scale
                            translationX = zoom.offset.x
                            translationY = zoom.offset.y
                        }
                    },
            )

            AttachmentBitmapState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )

            AttachmentBitmapState.Missing -> Text(
                text = "图片不可用",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

private fun clampOffset(offset: Offset, scale: Float, viewport: IntSize): Offset {
    if (viewport.width <= 0 || viewport.height <= 0) return offset
    val maxX = viewport.width * (scale - 1f) / 2f
    val maxY = viewport.height * (scale - 1f) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}
