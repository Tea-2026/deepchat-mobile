package com.deepseek.mobilechat.ui.chat

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProtocol
import com.deepseek.mobilechat.ui.components.DownIcon
import com.deepseek.mobilechat.ui.components.MessageRow
import com.deepseek.mobilechat.ui.components.iconSize
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.MotionVisibility
import com.deepseek.mobilechat.ui.motion.messageEnter
import com.deepseek.mobilechat.ui.motion.pressable
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The transcript.
 *
 * Scrolling rules that matter for a streaming chat:
 *  - new messages animate to the bottom;
 *  - streaming text growth keeps the view pinned to the bottom, but only while
 *    the user has not scrolled away;
 *  - the moment the user drags, auto-follow stops, and it resumes only after
 *    they come back to the bottom themselves (or tap the jump button).
 *
 * Items are keyed by *parent*, not by message id: a regenerated reply replaces
 * its sibling in the same slot, which is what lets the row cross-slide between
 * branches instead of being torn down and recreated.
 */
@Composable
fun ChatMessageList(
    messages: List<Message>,
    branches: Map<MessageId, BranchPosition>,
    listState: LazyListState,
    editing: MessageId?,
    modelName: String,
    protocol: ProviderProtocol?,
    activeProviderId: ProviderId?,
    thinkingSeconds: Map<MessageId, Int>,
    onRequestActions: (Message) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: (MessageId, String) -> Unit,
    onSwitchBranch: (MessageId) -> Unit,
    onRetry: (MessageId) -> Unit,
    onContinue: (MessageId) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keeps the previous branch's message around long enough for the exit half
    // of the branch transition to render real content.
    val archive = remember { HashMap<MessageId, Message>() }
    LaunchedEffect(messages) {
        messages.forEach { archive[it.id] = it }
    }

    // v2: a message fades + lifts 4dp the first time it is ever shown, and
    // never again — scrolling a message back into view must not replay it.
    val arrived = remember { mutableSetOf<MessageId>() }

    var autoFollow by remember { mutableStateOf(true) }
    var programmatic by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val atBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 4
        }
    }

    LaunchedEffect(atBottom) {
        if (atBottom) autoFollow = true
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            // A scroll the user started wins over the auto-follow loop.
            if (scrolling && !programmatic) autoFollow = false
        }
    }

    val streamingLength = messages.lastOrNull()
        ?.takeIf { it.status == MessageStatus.STREAMING }
        ?.text
        ?.length ?: 0

    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty() && autoFollow) {
            programmatic = true
            try {
                listState.animateToBottom()
            } finally {
                programmatic = false
            }
        }
    }

    LaunchedEffect(streamingLength) {
        if (autoFollow && streamingLength > 0) {
            programmatic = true
            try {
                listState.snapToBottom()
            } finally {
                programmatic = false
            }
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(vertical = Spacing.sm),
        ) {
            items(
                items = messages,
                key = { message -> slotKey(message) },
            ) { message ->
                val firstAppearance = remember(message.id) { arrived.add(message.id) }
                val entrance = if (firstAppearance) {
                    Modifier.messageEnter(key = message.id)
                } else {
                    Modifier
                }
                MessageRow(
                    message = message,
                    branch = branches[message.id],
                    resolveMessage = { id -> archive[id] },
                    modelName = modelName,
                    protocol = protocol,
                    thinkingSeconds = thinkingSeconds[message.id],
                    activeProviderId = activeProviderId,
                    editing = editing == message.id,
                    onRequestActions = { onRequestActions(message) },
                    onCancelEdit = onCancelEdit,
                    onSaveEdit = { text -> onSaveEdit(message.id, text) },
                    onSwitchBranch = onSwitchBranch,
                    onRetry = { onRetry(message.id) },
                    onContinue = { onContinue(message.id) },
                    modifier = Modifier.animateItem().then(entrance),
                )
            }
        }

        MotionVisibility(
            visible = !atBottom,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = Spacing.md),
            enter = Motion.enterFromBottom(),
            exit = Motion.exitToBottom(),
            label = "jumpToBottom",
        ) {
            JumpToBottomButton(
                onClick = {
                    scope.launch {
                        programmatic = true
                        try {
                            listState.animateToBottom()
                        } finally {
                            programmatic = false
                        }
                    }
                },
            )
        }
    }
}

/**
 * Stable list key: one slot per *turn*. A regenerated reply reuses its slot
 * (which is what enables the branch cross-slide), while the prefix guarantees
 * a message never collides with its own parent's slot — the parent is itself a
 * list item, so a naive `parentId` key would duplicate.
 */
private fun slotKey(message: Message): String =
    message.parentId?.let { parent -> "child-of:${parent.raw}" } ?: "root:${message.id.raw}"

@Composable
private fun JumpToBottomButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(36.dp)
            .pressable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            DownIcon(
                modifier = Modifier.iconSize(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Scrolls the list so the *bottom* of the last item meets the viewport. */
private suspend fun LazyListState.snapToBottom() {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return
    scrollToItem(info.totalItemsCount - 1)
    val after = layoutInfo
    val last = after.visibleItemsInfo.lastOrNull() ?: return
    val delta = last.offset + last.size - (after.viewportEndOffset - after.afterContentPadding)
    if (delta > 0) scrollBy(delta.toFloat())
}

/** Same, but animated — used when a whole message appears or disappears. */
private suspend fun LazyListState.animateToBottom() {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return
    animateScrollToItem(info.totalItemsCount - 1)
    val after = layoutInfo
    val last = after.visibleItemsInfo.lastOrNull() ?: return
    val delta = last.offset + last.size - (after.viewportEndOffset - after.afterContentPadding)
    if (delta > 0) animateScrollBy(delta.toFloat())
}
