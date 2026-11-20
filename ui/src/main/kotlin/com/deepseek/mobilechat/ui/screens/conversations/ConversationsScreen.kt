@file:OptIn(ExperimentalMaterial3Api::class)

package com.deepseek.mobilechat.ui.screens.conversations

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.Folder
import com.deepseek.mobilechat.domain.Tag
import com.deepseek.mobilechat.ui.AppServices
import com.deepseek.mobilechat.ui.components.iconSize
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.animatedEnter
import com.deepseek.mobilechat.ui.screens.common.BusyRow
import com.deepseek.mobilechat.ui.screens.common.ConfirmDialog
import com.deepseek.mobilechat.ui.screens.common.ScreenEmptyState
import com.deepseek.mobilechat.ui.screens.common.ScreenTopBar
import com.deepseek.mobilechat.ui.screens.common.SettingsGlyph
import com.deepseek.mobilechat.ui.screens.common.TransferGlyph
import com.deepseek.mobilechat.ui.screens.common.rememberNow
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Debounce window for the search field; below this the query is not sent. */
private const val SearchDebounceMillis = 250L

/**
 * Conversation index: debounced search, folder chips, pinned-first list and a
 * long-press action sheet.
 *
 * State is local to the screen (no ViewModel) because everything it needs is a
 * cold flow from [AppServices]; the screen stays a pure function of those
 * flows plus its own transient UI state.
 */
@Composable
fun ConversationsScreen(
    services: AppServices,
    onOpenConversation: (ConversationId) -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTransfer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val now = rememberNow(services.clock)

    var query by rememberSaveable { mutableStateOf("") }
    var folderKey by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }

    val folders by services.folderTagStore.observeFolders()
        .collectAsStateWithLifecycle(initialValue = emptyList<Folder>())
    val tags by services.folderTagStore.observeTags()
        .collectAsStateWithLifecycle(initialValue = emptyList<Tag>())

    var actionTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var moveTarget by remember { mutableStateOf<Conversation?>(null) }
    var tagTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }

    // Debounce happens by cancelling the previous collector: a new query
    // restarts this effect, so nothing reaches the store before 250ms of quiet.
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(SearchDebounceMillis)
        loading = true
        val source = if (query.isBlank()) {
            services.conversations.observeConversations()
        } else {
            services.conversations.search(query.trim())
        }
        source.collect { incoming ->
            conversations = incoming.sortedWith(
                compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt },
            )
            loading = false
        }
    }

    fun run(block: suspend () -> Outcome<*>, success: String) {
        scope.launch {
            val result = block()
            snackbar.showSnackbar(
                when (result) {
                    is Outcome.Ok -> success
                    is Outcome.Err -> result.error.message
                },
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ScreenTopBar(
                title = "会话",
                subtitle = if (query.isBlank()) {
                    "${conversations.size} 个会话"
                } else {
                    "搜索「${query.trim()}」"
                },
                actions = {
                    IconButton(onClick = onOpenTransfer) {
                        TransferGlyph(
                            modifier = Modifier.iconSize(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            contentDescription = "导入导出",
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        SettingsGlyph(
                            modifier = Modifier.iconSize(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                        .animatedEnter(key = "search"),
                )
                if (folders.isNotEmpty()) {
                    FolderChips(
                        folders = folders,
                        selectedKey = folderKey,
                        onSelect = { folderKey = it },
                    )
                }
                AnimatedContent(
                    targetState = folderKey,
                    transitionSpec = { Motion.morph() },
                    label = "conversationFilter",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = Spacing.sm),
                ) { key ->
                    val rows = remember(conversations, key) { filterByFolder(conversations, key) }
                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                            loading && conversations.isEmpty() -> BusyRow("正在加载会话…")
                            rows.isEmpty() -> ScreenEmptyState(
                                title = if (query.isBlank()) "还没有会话" else "没有匹配的会话",
                                subtitle = if (query.isBlank()) {
                                    "新建一个会话，消息、分支和重生成都保存在本机。"
                                } else {
                                    "换个关键词，或者清空搜索框看看全部会话。"
                                },
                                actionLabel = if (query.isBlank()) "新建会话" else null,
                                onAction = if (query.isBlank()) onNewConversation else null,
                            )
                            else -> ConversationList(
                                conversations = rows,
                                tags = tags,
                                now = now,
                                onOpen = onOpenConversation,
                                onLongPress = { actionTarget = it },
                            )
                        }
                    }
                }
            }
            CreateConversationFab(
                onClick = onNewConversation,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.xl)
                    .animatedEnter(key = "fab", delayMillis = 120),
            )
        }
    }

    val target = actionTarget
    if (target != null) {
        ConversationActionSheet(
            conversation = target,
            onDismiss = { actionTarget = null },
            onRename = {
                actionTarget = null
                renameTarget = target
            },
            onMoveToFolder = {
                actionTarget = null
                moveTarget = target
            },
            onEditTags = {
                actionTarget = null
                tagTarget = target
            },
            onTogglePin = {
                actionTarget = null
                run(
                    {
                        services.conversationStore.upsert(target.copy(pinned = !target.pinned))
                        Outcome.Ok(Unit)
                    },
                    if (target.pinned) "已取消置顶" else "已置顶",
                )
            },
            onDelete = {
                actionTarget = null
                deleteTarget = target
            },
        )
    }

    renameTarget?.let { conversation ->
        RenameConversationDialog(
            initialTitle = conversation.title,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                renameTarget = null
                run({ services.conversations.rename(conversation.id, title) }, "已重命名")
            },
        )
    }

    moveTarget?.let { conversation ->
        MoveToFolderDialog(
            folders = folders,
            currentFolderId = conversation.folderId,
            onDismiss = { moveTarget = null },
            onSelect = { folderId ->
                moveTarget = null
                run({ services.conversations.moveToFolder(conversation.id, folderId) }, "已移动")
            },
        )
    }

    tagTarget?.let { conversation ->
        TagPickerDialog(
            tags = tags,
            currentTagIds = conversation.tagIds,
            onDismiss = { tagTarget = null },
            onConfirm = { ids ->
                tagTarget = null
                run({ services.conversations.setTags(conversation.id, ids) }, "已更新标签")
            },
        )
    }

    deleteTarget?.let { conversation ->
        ConfirmDialog(
            title = "删除会话",
            message = "「${conversation.title.ifBlank { "未命名会话" }}」及其全部消息会被删除，无法恢复。",
            onConfirm = {
                deleteTarget = null
                run({ services.conversations.delete(conversation.id) }, "已删除")
            },
            onDismiss = { deleteTarget = null },
        )
    }

}

