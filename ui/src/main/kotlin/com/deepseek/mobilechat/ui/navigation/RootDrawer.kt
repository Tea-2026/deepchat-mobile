package com.deepseek.mobilechat.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.Folder
import com.deepseek.mobilechat.domain.Tag
import com.deepseek.mobilechat.ui.AppServices
import com.deepseek.mobilechat.ui.components.ChartIcon
import com.deepseek.mobilechat.ui.components.CloseIcon
import com.deepseek.mobilechat.ui.components.GearIcon
import com.deepseek.mobilechat.ui.components.PlusIcon
import com.deepseek.mobilechat.ui.components.SearchIcon
import com.deepseek.mobilechat.ui.components.iconSize
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.pressable
import com.deepseek.mobilechat.ui.screens.common.ConfirmDialog
import com.deepseek.mobilechat.ui.screens.common.ScreenEmptyState
import com.deepseek.mobilechat.ui.screens.common.rememberNow
import com.deepseek.mobilechat.ui.screens.conversations.ConversationActionSheet
import com.deepseek.mobilechat.ui.screens.conversations.ConversationList
import com.deepseek.mobilechat.ui.screens.conversations.FolderChips
import com.deepseek.mobilechat.ui.screens.conversations.MoveToFolderDialog
import com.deepseek.mobilechat.ui.screens.conversations.RenameConversationDialog
import com.deepseek.mobilechat.ui.screens.conversations.TagPickerDialog
import com.deepseek.mobilechat.ui.screens.conversations.filterByFolder
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.ShapeTokens
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Debounce window for the drawer search field (same as the full index). */
private const val SearchDebounceMillis = 250L

/**
 * The left drawer (spec §2): search, new conversation, the conversation list,
 * and the settings entry pinned to the bottom.
 *
 * The list *reuses* the conversation-index components from
 * `screens/conversations` (read-only) so both surfaces stay identical: same
 * rows, same folder chips, same long-press actions. Only the chrome is new.
 *
 * **The body is composed unconditionally.** It must never be gated on
 * `DrawerState.isOpen`: that flag only flips once the sheet has finished
 * travelling, so a gated body leaves the drawer as an empty dark panel for the
 * whole open animation (reported as "卡在一个空白页面"). Painting it from the
 * first frame costs one always-mounted list — much cheaper than a visible hole.
 * The panel's own travel is the transition; no extra content animation is
 * layered on top of it.
 */
@Composable
fun RootDrawerContent(
    services: AppServices,
    currentConversationId: ConversationId?,
    onOpenConversation: (ConversationId) -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsage: () -> Unit,
    onConversationDeleted: (ConversationId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val chatColors = LocalChatColors.current
    val now = rememberNow(services.clock)

    // Plain remember: the drawer body lives for the whole screen, so a search
    // must not survive into a later visit with a stale filter.
    var query by remember { mutableStateOf("") }
    var folderKey by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    val folders by services.folderTagStore.observeFolders()
        .collectAsStateWithLifecycle(initialValue = emptyList<Folder>())
    val tags by services.folderTagStore.observeTags()
        .collectAsStateWithLifecycle(initialValue = emptyList<Tag>())

    var actionTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var moveTarget by remember { mutableStateOf<Conversation?>(null) }
    var tagTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }

    // A new query restarts this effect, so nothing hits the store before the
    // user has been quiet for a beat.
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

    LaunchedEffect(error) {
        if (error != null) {
            delay(4000)
            error = null
        }
    }

    fun run(block: suspend () -> Outcome<*>) {
        scope.launch {
            when (val result = block()) {
                is Outcome.Ok -> error = null
                is Outcome.Err -> error = result.error.message
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(
                start = Spacing.xl,
                end = Spacing.xl,
                top = Spacing.xl,
            ),
        ) {
            Text(
                text = "会话",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (query.isBlank()) {
                    "${conversations.size} 个会话"
                } else {
                    "搜索「${query.trim()}」"
                },
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        val onDraft = currentConversationId == null
        val chipTint = if (onDraft) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onPrimary
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .pressable(onClick = onNewConversation),
            shape = ShapeTokens.Pill,
            color = if (onDraft) chatColors.accentSoft else MaterialTheme.colorScheme.primary,
            border = if (onDraft) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            } else {
                null
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = chipTint.copy(alpha = 0.14f)) {
                    Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        PlusIcon(modifier = Modifier.iconSize(14.dp), tint = chipTint)
                    }
                }
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = if (onDraft) "正在写新对话" else "新建对话",
                    style = MaterialTheme.typography.titleMedium,
                    color = chipTint,
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        DrawerSearchField(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )

        if (folders.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            FolderChips(
                folders = folders,
                selectedKey = folderKey,
                onSelect = { folderKey = it },
            )
        }

        if (error != null) {
            Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(top = Spacing.sm),
        ) {
            val rows = remember(conversations, folderKey) {
                filterByFolder(conversations, folderKey)
            }
            when {
                loading && conversations.isEmpty() -> Text(
                    text = "正在加载会话…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = chatColors.mutedText,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                )

                rows.isEmpty() -> ScreenEmptyState(
                    title = if (query.isBlank()) "还没有会话" else "没有匹配的会话",
                    subtitle = if (query.isBlank()) {
                        "发一条消息，这个对话就会出现在这里。"
                    } else {
                        "换个关键词试试。"
                    },
                    actionLabel = if (query.isBlank()) "新建对话" else null,
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

        Surface(modifier = Modifier.fillMaxWidth(), color = chatColors.divider) {
            Box(modifier = Modifier.height(1.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressable(onClick = onOpenUsage)
                .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            ChartIcon(
                modifier = Modifier.iconSize(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "用量与花费",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = chatColors.mutedText,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressable(onClick = onOpenSettings)
                .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            GearIcon(
                modifier = Modifier.iconSize(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = chatColors.mutedText,
            )
        }
    }

    // ---- long-press conversation actions (same set as the full index) -----
    actionTarget?.let { target ->
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
                run {
                    services.conversationStore.upsert(target.copy(pinned = !target.pinned))
                    Outcome.Ok(Unit)
                }
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
                run { services.conversations.rename(conversation.id, title) }
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
                run { services.conversations.moveToFolder(conversation.id, folderId) }
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
                run { services.conversations.setTags(conversation.id, ids) }
            },
        )
    }

    deleteTarget?.let { conversation ->
        ConfirmDialog(
            title = "删除会话",
            message = "「${conversation.title.ifBlank { "未命名会话" }}」及其全部消息会被删除，无法恢复。",
            onConfirm = {
                deleteTarget = null
                onConversationDeleted(conversation.id)
                run { services.conversations.delete(conversation.id) }
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/**
 * Drawer search.
 *
 * Collapsed it is a tap target, not a text field: nothing in the drawer is
 * focusable until the user actually taps it, so opening the drawer can never
 * bring up the keyboard or paint a focused outline. Tapping expands it and only
 * then requests focus; collapsing clears the query.
 */
@Suppress("DEPRECATION")
@Composable
private fun DrawerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    val borderColor by animateColorAsState(
        targetValue = if (focused) {
            MaterialTheme.colorScheme.primary
        } else {
            chatColors.hairline
        },
        animationSpec = tween(Motion.Durations.Short, easing = Motion.Easings.Standard),
        label = "drawerSearchBorder",
    )

    LaunchedEffect(expanded) {
        if (expanded) runCatching { focusRequester.requestFocus() }
    }

    fun collapse() {
        onQueryChange("")
        expanded = false
        focusManager.clearFocus()
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .pressable(enabled = !expanded) { expanded = true },
        shape = ShapeTokens.Card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SearchIcon(
                modifier = Modifier.iconSize(18.dp),
                tint = chatColors.mutedText,
            )
            if (expanded) {
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜索会话",
                            style = MaterialTheme.typography.bodyMedium,
                            color = chatColors.mutedText,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { focused = it.isFocused },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    )
                }
                Surface(
                    modifier = Modifier
                        .size(24.dp)
                        .pressable(onClick = { collapse() }),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CloseIcon(
                            modifier = Modifier.iconSize(12.dp),
                            tint = chatColors.mutedText,
                            contentDescription = "收起搜索",
                        )
                    }
                }
            } else {
                Text(
                    text = "搜索会话",
                    style = MaterialTheme.typography.bodyMedium,
                    color = chatColors.mutedText,
                )
            }
        }
    }
}
