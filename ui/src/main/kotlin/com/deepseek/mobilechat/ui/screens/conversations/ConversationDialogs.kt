@file:OptIn(ExperimentalMaterial3Api::class)

package com.deepseek.mobilechat.ui.screens.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.Folder
import com.deepseek.mobilechat.domain.FolderId
import com.deepseek.mobilechat.domain.Tag
import com.deepseek.mobilechat.domain.TagId
import com.deepseek.mobilechat.ui.components.EditIcon
import com.deepseek.mobilechat.ui.components.PinIcon
import com.deepseek.mobilechat.ui.components.TrashIcon
import com.deepseek.mobilechat.ui.components.iconSize
import com.deepseek.mobilechat.ui.screens.common.FolderGlyph
import com.deepseek.mobilechat.ui.screens.common.TagGlyph
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Long-press action sheet for one conversation: rename, move, tags, pin, delete.
 *
 * Pure UI — the screen owns the service calls, so a dialog can never mutate
 * state behind the caller's back.
 */
@Composable
fun ConversationActionSheet(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMoveToFolder: () -> Unit,
    onEditTags: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    val chatColors = LocalChatColors.current
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    // Play the sheet's own exit before the caller drops it from composition,
    // otherwise picking an action would cut the surface away mid-air.
    val dismissThen: (() -> Unit) -> Unit = { action ->
        scope.launch {
            sheetState.hide()
            action()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = Spacing.xl)) {
            Text(
                text = conversation.title.ifBlank { "未命名会话" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm),
            )
            HorizontalDivider(color = chatColors.divider)
            SheetAction(label = "重命名", icon = { tint ->
                EditIcon(modifier = Modifier.iconSize(18.dp), tint = tint, contentDescription = null)
            }, onClick = { dismissThen(onRename) })
            SheetAction(label = "移动到文件夹", icon = { tint ->
                FolderGlyph(modifier = Modifier.iconSize(18.dp), tint = tint, contentDescription = null)
            }, onClick = { dismissThen(onMoveToFolder) })
            SheetAction(label = "编辑标签", icon = { tint ->
                TagGlyph(modifier = Modifier.iconSize(18.dp), tint = tint, contentDescription = null)
            }, onClick = { dismissThen(onEditTags) })
            SheetAction(label = if (conversation.pinned) "取消置顶" else "置顶", icon = { tint ->
                PinIcon(modifier = Modifier.iconSize(18.dp), tint = tint, contentDescription = null)
            }, onClick = { dismissThen(onTogglePin) })
            HorizontalDivider(color = chatColors.divider)
            SheetAction(
                label = "删除会话",
                destructive = true,
                icon = { tint ->
                    TrashIcon(modifier = Modifier.iconSize(18.dp), tint = tint, contentDescription = null)
                },
                onClick = { dismissThen(onDelete) },
            )
        }
    }
}

@Composable
private fun SheetAction(
    label: String,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val tint = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(tint)
        Spacer(Modifier.size(Spacing.md))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun RenameConversationDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话", style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("标题") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun MoveToFolderDialog(
    folders: List<Folder>,
    currentFolderId: FolderId?,
    onDismiss: () -> Unit,
    onSelect: (FolderId?) -> Unit,
) {
    var selected by remember { mutableStateOf(currentFolderId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到文件夹", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                FolderOption(
                    label = "不归入任何文件夹",
                    selected = selected == null,
                    onClick = { selected = null },
                )
                folders.forEach { folder ->
                    FolderOption(
                        label = folder.name,
                        selected = selected == folder.id,
                        onClick = { selected = folder.id },
                    )
                }
                if (folders.isEmpty()) {
                    Text(
                        text = "还没有文件夹。会话仍然可以留在这里，之后在设置里整理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatColors.current.mutedText,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSelect(selected) }) { Text("移动") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun FolderOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = Spacing.xs),
        )
    }
}

@Composable
fun TagPickerDialog(
    tags: List<Tag>,
    currentTagIds: Set<TagId>,
    onDismiss: () -> Unit,
    onConfirm: (Set<TagId>) -> Unit,
) {
    var selected by remember { mutableStateOf(currentTagIds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标签", style = MaterialTheme.typography.titleMedium) },
        text = {
            if (tags.isEmpty()) {
                Text(
                    text = "还没有标签。标签可以在会话之间共享，用来做轻量分类。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatColors.current.mutedText,
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                ) {
                    tags.forEach { tag ->
                        val checked = tag.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (checked) selected - tag.id else selected + tag.id
                                }
                                .padding(vertical = Spacing.xxs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isOn ->
                                    selected = if (isOn) selected + tag.id else selected - tag.id
                                },
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(Color(tag.color), CircleShape),
                            )
                            Text(
                                text = tag.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Normal,
                                modifier = Modifier
                                    .padding(start = Spacing.sm)
                                    .weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
