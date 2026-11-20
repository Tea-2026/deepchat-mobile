@file:OptIn(ExperimentalMaterial3Api::class)

package com.deepseek.mobilechat.ui.screens.conversations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.Folder
import com.deepseek.mobilechat.domain.Tag
import com.deepseek.mobilechat.ui.components.CloseIcon
import com.deepseek.mobilechat.ui.components.PinIcon
import com.deepseek.mobilechat.ui.components.iconSize
import com.deepseek.mobilechat.ui.motion.animatedEnter
import com.deepseek.mobilechat.ui.motion.longPressable
import com.deepseek.mobilechat.ui.motion.pressable
import com.deepseek.mobilechat.ui.screens.common.PlusGlyph
import com.deepseek.mobilechat.ui.screens.common.SearchGlyph
import com.deepseek.mobilechat.ui.screens.common.relativeTime
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing

/**
 * Presentational pieces of the conversation index: the search field, the folder
 * chip rail, the list rows and the custom FAB. Split from the screen file so
 * neither exceeds a reviewable size.
 */

/** Sentinel chip for "has no folder" — a real [Folder] cannot carry it. */
internal const val UnfiledFilter = "__unfiled__"

@Composable
internal fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        placeholder = {
            Text(
                text = "搜索标题或内容",
                style = MaterialTheme.typography.bodyMedium,
                color = chatColors.mutedText,
            )
        },
        leadingIcon = {
            SearchGlyph(modifier = Modifier.iconSize(18.dp), tint = chatColors.mutedText)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    CloseIcon(
                        modifier = Modifier.iconSize(16.dp),
                        tint = chatColors.mutedText,
                        contentDescription = "清空搜索",
                    )
                }
            }
        },
    )
}

@Composable
internal fun FolderChips(
    folders: List<Folder>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "all") {
            FilterChip(
                selected = selectedKey == null,
                onClick = { onSelect(null) },
                label = { Text("全部") },
            )
        }
        items(folders, key = { it.id.raw }) { folder ->
            FilterChip(
                selected = selectedKey == folder.id.raw,
                onClick = { onSelect(folder.id.raw) },
                label = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
        item(key = "unfiled") {
            FilterChip(
                selected = selectedKey == UnfiledFilter,
                onClick = { onSelect(UnfiledFilter) },
                label = { Text("未归档") },
            )
        }
    }
}

@Composable
internal fun ConversationList(
    conversations: List<Conversation>,
    tags: List<Tag>,
    now: Long,
    onOpen: (ConversationId) -> Unit,
    onLongPress: (Conversation) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.lg,
            end = Spacing.lg,
            top = Spacing.xs,
            bottom = 104.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(conversations, key = { it.id.raw }) { conversation ->
            ConversationRow(
                conversation = conversation,
                tags = tags,
                now = now,
                onClick = { onOpen(conversation.id) },
                onLongClick = { onLongPress(conversation) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    tags: List<Tag>,
    now: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    val conversationTags = remember(tags, conversation.tagIds) {
        tags.filter { it.id in conversation.tagIds }.take(5)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, chatColors.divider),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .longPressable(onLongClick = onLongClick, onClick = onClick)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.pinned) {
                    PinIcon(
                        modifier = Modifier.iconSize(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                        contentDescription = "已置顶",
                    )
                    Spacer(Modifier.width(Spacing.xs))
                }
                Text(
                    text = conversation.title.ifBlank { "未命名会话" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = relativeTime(now, conversation.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = chatColors.mutedText,
                )
            }
            if (conversationTags.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    conversationTags.forEach { tag ->
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(Color(tag.color), CircleShape),
                        )
                    }
                    Text(
                        text = conversationTags.joinToString("、") { it.name },
                        style = MaterialTheme.typography.labelSmall,
                        color = chatColors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Custom FAB instead of `FloatingActionButton` so the press scale comes from the
 * shared motion tokens rather than Material's ripple-only press.
 */
@Composable
internal fun CreateConversationFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .size(56.dp)
            .pressable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 6.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PlusGlyph(
                modifier = Modifier.iconSize(22.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
                contentDescription = "新建会话",
            )
        }
    }
}

internal fun filterByFolder(conversations: List<Conversation>, key: String?): List<Conversation> = when (key) {
    null -> conversations
    UnfiledFilter -> conversations.filter { it.folderId == null }
    else -> conversations.filter { it.folderId?.raw == key }
}
