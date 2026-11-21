package com.deepseek.mobilechat.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Tail following for the transcript — **one** mechanism.
 *
 * ## Why the old version twitched (see `MessageList.kt` history)
 *
 * It had two `LaunchedEffect`s racing over the same `LazyListState`:
 *
 *  1. `LaunchedEffect(messages.lastOrNull()?.id)` → `animateToBottom()`, which is
 *     `animateScrollToItem(lastIndex)` **then** `animateScrollBy(delta)`. The
 *     first half animates to the *top* of the last row; for a reply taller than
 *     the viewport that is several screens *above* the tail, so the list first
 *     travelled up and only then came back down. That is the branch-switch lurch.
 *  2. `LaunchedEffect(streamingLength)` → a hand-rolled "snap" **on every token**.
 *
 * Both go through `ScrollableState.scroll` (mutex priority `Default`). Compose's
 * `MutatorMutex` cancels the *current* mutator when a new one of equal priority
 * arrives (`tryMutateOrCancel` → `Mutator.cancel()`), so every token killed the
 * in-flight `animateScrollBy` from (1) wherever it happened to be — and (1)'s
 * `finally { programmatic = false }` ran at that arbitrary moment, while (2) was
 * still scrolling. The `programmatic` flag had three writers on three effect
 * coroutines and one reader on the `snapshotFlow { isScrollInProgress }`
 * collector, so the reader could legitimately observe "scrolling" with the flag
 * already cleared and drop `autoFollow` — which `atBottom` then re-enabled on
 * the next layout. Follow/detach flipped on a schedule nobody controlled.
 *
 * On top of that, `atBottom` (a `derivedStateOf` on the last row's bottom) goes
 * false *between* the token growing the row and the scroll fixing it, so the
 * jump affordance reversed its enter/exit transition on every token.
 *
 * ## The invariant this file restores
 *
 * The only thing that can **detach** is a real user gesture, and the only thing
 * that can **re-attach** is the user being at the tail with no gesture in
 * flight. A programmatic scroll cannot do either: it is issued from the same
 * coroutine that reads the state machine, and its effect (moving back to the
 * tail) can only ever satisfy the *attach* condition, never the detach one.
 * There is no boolean that one coroutine flips and another coroutine believes.
 *
 * Concretely:
 *  - the whole follower is one `LaunchedEffect` + one `snapshotFlow`, and every
 *    scroll is applied by that same coroutine;
 *  - "the user is dragging" comes from `LazyListState.interactionSource`
 *    (`DragInteraction`), a channel that `scrollBy` / `scrollToItem` /
 *    `animateScrollBy` physically cannot produce;
 *  - a detached state only clears at the tail, and reaching the tail is what a
 *    follower scroll does — so the follower can never detach itself.
 *
 * Scrolls are *instant* (`scrollBy`) instead of animated: on a streaming reply
 * the content grows by exactly one line per token, so a one-line scroll per
 * token is what "smooth" looks like; animating 20px over 300ms while a token
 * arrives every 30ms can only ever lag behind the text and feel sticky.
 */

/** Slack (in px) that counts as "at the tail" despite rounding/sub-pixel layout. */
private const val TailSlackPx = 2

/**
 * One immutable sample of the transcript tail. Everything the state machine is
 * allowed to look at arrives here, which is what makes it testable off-device.
 */
internal data class TailSample(
    /** `LazyListLayoutInfo.totalItemsCount`. */
    val totalItems: Int,
    /** Index of the last laid-out item, or `-1` when nothing is laid out yet. */
    val lastVisibleIndex: Int,
    /** Bottom edge (`offset + size`) of the last laid-out item, in viewport px. */
    val lastVisibleBottom: Int,
    /** Where the tail should sit: viewport end minus the list's bottom padding. */
    val tailEdge: Int,
    /** A real finger is on the list (`DragInteraction`), not a programmatic scroll. */
    val userDragging: Boolean,
    /** `isScrollInProgress` — includes a fling that continues after the finger lifts. */
    val userScrolling: Boolean,
) {
    /** The last item of the list has been laid out. */
    val tailIsVisible: Boolean get() = totalItems > 0 && lastVisibleIndex >= totalItems - 1

    /** The tail is where it belongs (or the content is shorter than the viewport). */
    val isAtTail: Boolean get() = tailIsVisible && lastVisibleBottom <= tailEdge + TailSlackPx

    /** How far the tail is below the fold; `0` when it is not. */
    val distanceToTail: Int get() = (lastVisibleBottom - tailEdge).coerceAtLeast(0)
}

/** What the follower should do after looking at one [TailSample]. */
internal sealed interface TailFollow {
    /** Nothing to do: at the tail, or the user is in charge. */
    data object Hold : TailFollow

    /** The tail is off-screen while we are supposed to follow it — one instant jump. */
    data object Jump : TailFollow

    /** Scroll this many px (towards the end of the list). */
    data class ScrollBy(val deltaPx: Int) : TailFollow
}

/**
 * Pure tail-follow state machine: no Compose, no coroutines, no `LazyListState`.
 *
 * Transitions:
 *  - `isDetached` becomes `true` **only** while a user drag has pushed the list
 *    off the tail;
 *  - `isDetached` becomes `false` **only** when a sample reports "at the tail,
 *    not dragging, not coasting".
 *
 * Because every sample that is produced by our own scroll reports `atTail` and
 * never reports a drag, feeding the machine the result of its own scroll is a
 * no-op (`Hold`). That is the property the unit test pins down.
 */
internal class AutoFollowController {

    var isDetached: Boolean = false
        private set

    fun onSample(sample: TailSample): TailFollow {
        if (sample.userDragging) {
            // A finger on the list is authoritative. Never scroll under it: the
            // gesture owns the scroll mutex, and a follower scroll would be
            // rejected (or, worse, fight it).
            if (!sample.isAtTail) isDetached = true
            return TailFollow.Hold
        }
        if (isDetached) {
            if (sample.isAtTail && !sample.userScrolling) {
                isDetached = false
            } else {
                return TailFollow.Hold
            }
        }
        if (sample.totalItems == 0 || sample.lastVisibleIndex < 0) return TailFollow.Hold
        if (!sample.tailIsVisible) return TailFollow.Jump
        val distance = sample.distanceToTail
        return if (distance > 0) TailFollow.ScrollBy(distance) else TailFollow.Hold
    }
}

/** What `MessageList` needs: whether to offer "jump to bottom", and how. */
internal class TailFollowHandle(
    val isDetached: Boolean,
    val jumpToBottom: () -> Unit,
)

/**
 * Remembers the follower for [listState] and starts the one and only scroll loop.
 *
 * No other effect in the transcript is allowed to touch [listState].
 */
@Composable
internal fun rememberTailFollow(listState: LazyListState): TailFollowHandle {
    val scope = rememberCoroutineScope()
    val controller = remember(listState) { AutoFollowController() }
    var detached by remember(listState) { mutableStateOf(false) }
    // A `@Composable` State so the snapshotFlow below can observe it; a
    // `DragInteraction` is emitted by the gesture pipeline only.
    val dragging: State<Boolean> = listState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(listState) {
        snapshotFlow { listState.sampleTail(userDragging = dragging.value) }
            .collect { sample ->
                val move = controller.onSample(sample)
                // Mirror only on transitions, so the button never recomposes per token.
                if (detached != controller.isDetached) detached = controller.isDetached
                when (move) {
                    TailFollow.Hold -> Unit
                    TailFollow.Jump -> listState.jumpToTail()
                    is TailFollow.ScrollBy -> listState.scrollByIfAble(move.deltaPx.toFloat())
                }
            }
    }

    return remember(listState, detached) {
        TailFollowHandle(
            isDetached = detached,
            jumpToBottom = { scope.launch { listState.jumpToTail() } },
        )
    }
}

/** Snapshot of the tail geometry; the only reader of `layoutInfo` on the hot path. */
private fun LazyListState.sampleTail(userDragging: Boolean): TailSample {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()
    return TailSample(
        totalItems = info.totalItemsCount,
        lastVisibleIndex = last?.index ?: -1,
        lastVisibleBottom = if (last == null) 0 else last.offset + last.size,
        tailEdge = info.viewportEndOffset - info.afterContentPadding,
        userDragging = userDragging,
        userScrolling = isScrollInProgress,
    )
}

/**
 * Scrolls by [deltaPx] towards the end.
 *
 * A scroll can be rejected with a `CancellationException` when the user's
 * gesture (higher `MutatePriority`) owns the mutex. That exception must not kill
 * the follower loop, so it is swallowed while the coroutine itself is still
 * active; a genuine cancellation of the effect is re-thrown.
 */
private suspend fun LazyListState.scrollByIfAble(deltaPx: Float) {
    if (deltaPx <= 0f) return
    try {
        scrollBy(deltaPx)
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
    }
}

/**
 * One-shot, instant jump so the *bottom* of the last item meets the viewport.
 *
 * Deliberately not `animateScrollToItem`: that animates to the **top** of the
 * item, which for a tall reply means travelling away from the tail first. Both
 * `scroll` calls below run synchronously inside their mutex block, so no frame
 * is drawn between them and the intermediate position is never visible.
 */
internal suspend fun LazyListState.jumpToTail() {
    try {
        val info = layoutInfo
        if (info.totalItemsCount == 0) return
        scrollToItem(info.totalItemsCount - 1)
        val after = layoutInfo
        val last = after.visibleItemsInfo.lastOrNull() ?: return
        val delta = last.offset + last.size - (after.viewportEndOffset - after.afterContentPadding)
        if (delta > 0) scrollBy(delta.toFloat())
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
    }
}
