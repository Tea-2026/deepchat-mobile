package com.deepseek.mobilechat.ui.components

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.deepseek.mobilechat.ui.motion.pressable
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.ShapeTokens
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One image already on the device. Only the content URI crosses the boundary. */
data class DevicePhoto(
    val uri: String,
    val name: String,
    val takenAt: Long,
)

/** How many recent photos the grid shows (3 rows of 3). */
private const val RecentPhotoCount = 9

/** `READ_MEDIA_IMAGES` on 13+, `READ_EXTERNAL_STORAGE` below it (spec §5). */
private val readImagesPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * The `＋` panel (spec §5): recent photos, three big attach actions, and the two
 * "this conversation only" entry points.
 *
 * Photo access is a permission, not an assumption: without it the grid is
 * replaced by an explanation and a request button, and the photo-picker action
 * still works because the system picker needs no permission at all.
 */
@Composable
fun AttachmentSheetContent(
    onPickPhoto: (DevicePhoto) -> Unit,
    onTakePhoto: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenSystemPrompt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val chatColors = LocalChatColors.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, readImagesPermission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var photos by remember { mutableStateOf<List<DevicePhoto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
    }

    LaunchedEffect(granted) {
        if (!granted) {
            photos = emptyList()
            return@LaunchedEffect
        }
        loading = true
        photos = withContext(Dispatchers.IO) {
            queryRecentPhotos(context.contentResolver, RecentPhotoCount)
        }
        loading = false
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "最近照片",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
        Spacer(modifier = Modifier.height(Spacing.sm))

        when {
            !granted -> PhotoPermissionCard(
                onRequest = { permissionLauncher.launch(readImagesPermission) },
                onUsePicker = onPickImage,
            )

            loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            photos.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "相册里还没有照片",
                    style = MaterialTheme.typography.bodyMedium,
                    color = chatColors.mutedText,
                )
            }

            else -> PhotoGrid(photos = photos, onPick = onPickPhoto)
        }

        Spacer(modifier = Modifier.height(Spacing.lg))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            BigAttachAction(
                label = "拍照",
                hint = "用相机",
                modifier = Modifier.weight(1f),
                onClick = onTakePhoto,
            ) { CameraIcon(modifier = Modifier.iconSize(22.dp), tint = MaterialTheme.colorScheme.primary) }
            BigAttachAction(
                label = "相册",
                hint = "从系统相册选",
                modifier = Modifier.weight(1f),
                onClick = onPickImage,
            ) { GalleryIcon(modifier = Modifier.iconSize(22.dp), tint = MaterialTheme.colorScheme.primary) }
            BigAttachAction(
                label = "文件",
                hint = "任意文档",
                modifier = Modifier.weight(1f),
                onClick = onPickFile,
            ) { FileIcon(modifier = Modifier.iconSize(22.dp), tint = MaterialTheme.colorScheme.primary) }
        }

        Spacer(modifier = Modifier.height(Spacing.lg))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
            shape = ShapeTokens.Card,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column {
                FooterEntry(
                    label = "高级参数",
                    hint = "温度、top-p、最大 token、停止序列",
                    icon = { SlidersIcon(modifier = Modifier.iconSize(18.dp)) },
                    onClick = onOpenAdvanced,
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    color = chatColors.divider,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(1.dp),
                ) {}
                FooterEntry(
                    label = "系统提示词",
                    hint = "只影响这个会话",
                    icon = { PromptIcon(modifier = Modifier.iconSize(18.dp)) },
                    onClick = onOpenSystemPrompt,
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.lg))
    }
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

@Composable
private fun PhotoPermissionCard(
    onRequest: () -> Unit,
    onUsePicker: () -> Unit,
) {
    val chatColors = LocalChatColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        shape = ShapeTokens.Card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, chatColors.hairline.copy(alpha = 0.8f)),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = "允许访问相册，就能在这里直接挑最近的照片",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "只读取照片列表，不会上传任何东西。也可以不走授权，直接用系统相册选择。",
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            Row(
                modifier = Modifier.padding(top = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton(label = "允许访问", onClick = onRequest, primary = true)
                PillButton(label = "用系统相册", onClick = onUsePicker, primary = false)
            }
        }
    }
}

@Composable
private fun PhotoGrid(
    photos: List<DevicePhoto>,
    onPick: (DevicePhoto) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        photos.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                row.forEach { photo ->
                    PhotoCell(
                        photo = photo,
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(photo) },
                    )
                }
                // Keep the last row's cells square when it is short.
                repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PhotoCell(
    photo: DevicePhoto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    val targetPx = with(LocalDensity.current) { 120.dp.roundToPx() }
    val bitmap = rememberContentBitmap(photo.uri, targetPx)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(ShapeTokens.Thumbnail)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pressable(onClick = onClick),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = photo.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GalleryIcon(
                    modifier = Modifier.iconSize(20.dp),
                    tint = chatColors.mutedText,
                )
            }
        }
    }
}

@Composable
private fun BigAttachAction(
    label: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    val chatColors = LocalChatColors.current
    Surface(
        modifier = modifier.pressable(onClick = onClick),
        shape = ShapeTokens.Card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, chatColors.hairline.copy(alpha = 0.8f)),
    ) {
        Column(
            modifier = Modifier.padding(vertical = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = CircleShape, color = chatColors.accentSoft) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    icon()
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
            )
        }
    }
}

@Composable
private fun FooterEntry(
    label: String,
    hint: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val chatColors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            icon()
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
            )
        }
        ChevronRightIcon(
            modifier = Modifier.iconSize(16.dp),
            tint = chatColors.mutedText,
            contentDescription = null,
        )
    }
}

/** Shared pill button used by the panels (Material buttons are too tall here). */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    Surface(
        modifier = modifier.pressable(onClick = onClick),
        shape = ShapeTokens.Pill,
        color = if (primary) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = if (primary) null else BorderStroke(1.dp, chatColors.hairline),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
    }
}

// ---------------------------------------------------------------------------
// MediaStore + thumbnail decoding
// ---------------------------------------------------------------------------

private fun queryRecentPhotos(resolver: ContentResolver, limit: Int): List<DevicePhoto> {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_ADDED,
    )
    return runCatching {
        resolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val out = ArrayList<DevicePhoto>(limit)
            while (cursor.moveToNext() && out.size < limit) {
                val id = cursor.getLong(idColumn)
                out += DevicePhoto(
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    name = cursor.getString(nameColumn) ?: "照片",
                    takenAt = cursor.getLong(dateColumn) * 1000L,
                )
            }
            out
        }.orEmpty()
    }.getOrElse { emptyList() }
}

/**
 * Decodes a content URI into a thumbnail, downsampled to [targetPx].
 *
 * Two passes over the stream (bounds, then pixels) because a media URI cannot
 * be decoded from a file path and there is no Coil here on purpose (spec §5).
 */
@Composable
private fun rememberContentBitmap(uri: String, targetPx: Int): Bitmap? {
    val context = LocalContext.current
    val state by produceState<Bitmap?>(initialValue = null, key1 = uri, key2 = targetPx) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0) return@runCatching null
                var sample = 1
                var width = bounds.outWidth
                while (width / 2 >= targetPx && sample < 16) {
                    width /= 2
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }.getOrNull()
        }
    }
    return state
}
