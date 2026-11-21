package com.deepseek.mobilechat.ui.chat

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
import androidx.compose.runtime.remember
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

/**
 * The transcript.
 *
 * The list renders [TranscriptRow]s, not a bare message list: the snapshot's
 * transcript interleaves messages with compaction dividers, and keeping that
 * order here is what lets the divider sit between the messages it covers. A
 * streaming turn is an ordinary message row (the snapshot carries it with
 * `status = STREAMING`), so the bubble/caret/scroll behaviour is unchanged.
 *
 * Scrolling rules that matter for a streaming chat:
 *  - the view stays pinned to the bottom while the user has not scrolled away;
 *  - the moment the user drags, auto-follow stops, and it resumes only after
 *    they are back at the bottom themselves (or tap the jump button).
 *
 * All of that is implemented by exactly one follower: [rememberTailFollow]. No
 * other effect in this file touches [listState] — the previous version had two
 * of them racing over the same scroll mutex, which is what made the list twitch
 * (the full mechanism is written up in `AutoFollow.kt`).
 *
 * Items are keyed by *parent*, not by message id: a regenerated reply replaces
 * its sibling in the same slot, which is what lets the row cross-slide between
 * branches instead of being torn down and recreated. A compaction divider is not
 * a branch slot, so it gets its own key.
 */
@Composable
fun ChatMessageList(
    transcript: List<TranscriptRow>,
    branches: Map<MessageId, BranchPosition>,
    listState: LazyListState,
    editing: MessageId?,
    modelName: String,
    protocol: ProviderProtocol?,
    activeProviderId: ProviderId?,
    thinkingSeconds: Map<MessageId, Int>,
    onRequestActions: (MessageId) -> Unit,
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
    LaunchedEffect(transcript) {
        transcript.forEach { row ->
            if (row is TranscriptRow.Turn) archive[row.message.id] = row.message
        }
    }
    // One stable lookup for every row: a lambda rebuilt per row (and therefore
    // per re-composition) would make every `MessageRow` non-skippable.
    val resolveMessage: (MessageId) -> Message? = remember(archive) { { id -> archive[id] } }

    // v2: a message fades + lifts 4dp the first time it is ever shown, and
    // never again — scrolling a message back into view must not replay it.
    val arrived = remember { HashSet<MessageId>() }

    val tail = rememberTailFollow(listState)

    // Hoisted so the LazyColumn item provider does not get a fresh key function
    // on every recomposition of this composable.
    val keyOf: (TranscriptRow) -> Any = remember {
        { row ->
            when (row) {
                is TranscriptRow.Turn -> slotKey(row.message)
                is TranscriptRow.CompactionDivider -> "compaction:${row.compactionId.raw}"
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
                items = transcript,
                key = keyOf,
            ) { row ->
                when (row) {
                    is TranscriptRow.CompactionDivider -> CompactionDividerRow(
                        row = row,
                        modifier = Modifier.animateItem(),
                    )

                    is TranscriptRow.Turn -> {
                        val message = row.message
                        val streaming = message.status == MessageStatus.STREAMING

                        val firstAppearance = remember(message.id) { arrived.add(message.id) }
                        val entrance = if (firstAppearance) {
                            Modifier.messageEnter(key = message.id)
                        } else {
                            Modifier
                        }

                        // `animateItem()` animates *placement* deltas between
                        // layouts. On the streaming row the height changes on
                        // every token while the follower is moving the scroll
                        // offset, and Compose only disables that animation pass
                        // for its own `animateScrollToItem` (via
                        // `skipItemPlacementAnimation`), which we no longer use.
                        // A row that is still being written has nothing to
                        // animate: gate the item animation on the message being
                        // finished.
                        val itemAnimation = remember(streaming) {
                            if (streaming) Modifier else Modifier.animateItem()
                        }

                        // Per-row callbacks must survive the token that changes
                        // `message.text`; they are keyed on the *id*, which does
                        // not.
                        val requestActions = remember(message.id, onRequestActions) {
                            { onRequestActions(message.id) }
                        }
                        val saveEdit = remember(message.id, onSaveEdit) {
                            { text: String -> onSaveEdit(message.id, text) }
                        }
                        val retry = remember(message.id, onRetry) {
                            { onRetry(message.id) }
                        }
                        val continueGeneration = remember(message.id, onContinue) {
                            { onContinue(message.id) }
                        }

                        MessageRow(
                            message = message,
                            branch = branches[message.id],
                            resolveMessage = resolveMessage,
                            modelName = modelName,
                            protocol = protocol,
                            thinkingSeconds = thinkingSeconds[message.id],
                            activeProviderId = activeProviderId,
                            editing = editing == message.id,
                            onRequestActions = requestActions,
                            onCancelEdit = onCancelEdit,
                            onSaveEdit = saveEdit,
                            onSwitchBranch = onSwitchBranch,
                            onRetry = retry,
                            onContinue = continueGeneration,
                            modifier = itemAnimation.then(entrance),
                        )
                    }
                }
            }
        }

        // Driven by the follower's detached state, not by the raw distance to the
        // bottom: while following, the distance is non-zero for a frame after
        // every token, and reading it here reversed this transition per token.
        MotionVisibility(
            visible = tail.isDetached,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = Spacing.md),
            enter = Motion.enterFromBottom(),
            exit = Motion.exitToBottom(),
            label = "jumpToBottom",
        ) {
            JumpToBottomButton(onClick = tail.jumpToBottom)
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
