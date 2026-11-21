package com.deepseek.mobilechat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tail follower is the whole fix for the streaming/branch-switch twitching,
 * and it is the only part of it that can run off-device. These tests pin down the
 * two properties the old two-effect implementation could not hold:
 *
 *  1. a scroll the follower itself performs can never be mistaken for a user
 *     gesture (so `following` cannot drop out while a reply streams), and
 *  2. the machine cannot oscillate: the only edge into "detached" is a real drag
 *     that left the tail, and the only edge back is being at the tail with no
 *     gesture in flight.
 */
class AutoFollowControllerTest {

    private fun sample(
        total: Int = 10,
        lastVisible: Int = 9,
        bottom: Int = 1_000,
        edge: Int = 1_000,
        dragging: Boolean = false,
        scrolling: Boolean = false,
    ) = TailSample(
        totalItems = total,
        lastVisibleIndex = lastVisible,
        lastVisibleBottom = bottom,
        tailEdge = edge,
        userDragging = dragging,
        userScrolling = scrolling,
    )

    @Test
    fun `a fresh follower scrolls exactly the distance to the tail`() {
        val controller = AutoFollowController()

        assertFalse(controller.isDetached)
        assertEquals(
            TailFollow.ScrollBy(40),
            controller.onSample(sample(bottom = 1_040)),
        )
    }

    @Test
    fun `a sample that is already at the tail holds still`() {
        val controller = AutoFollowController()

        assertEquals(TailFollow.Hold, controller.onSample(sample(bottom = 1_000)))
        // Content shorter than the viewport is "at the tail" too.
        assertEquals(TailFollow.Hold, controller.onSample(sample(bottom = 400)))
    }

    /**
     * The regression the bug report is about: the follower's own scroll must not
     * detach it. `isScrollInProgress` is true while the scroll mutex is held, and
     * that is exactly the value the old code read as "the user scrolled".
     */
    @Test
    fun `a programmatic scroll observed in flight does not detach the follower`() {
        val controller = AutoFollowController()
        assertEquals(TailFollow.ScrollBy(40), controller.onSample(sample(bottom = 1_040)))

        // The layout after our own scroll: back at the tail, and the scroll state
        // is still flagged as in progress for this frame.
        val afterOwnScroll = controller.onSample(
            sample(bottom = 1_000, scrolling = true),
        )

        assertEquals(TailFollow.Hold, afterOwnScroll)
        assertFalse("our own scroll must never detach follow", controller.isDetached)
    }

    @Test
    fun `a drag that leaves the tail detaches immediately and holds`() {
        val controller = AutoFollowController()

        val onDrag = controller.onSample(sample(bottom = 1_300, dragging = true))

        assertEquals(TailFollow.Hold, onDrag)
        assertTrue(controller.isDetached)
    }

    @Test
    fun `detached means no scrolling at all while tokens keep arriving`() {
        val controller = AutoFollowController()
        controller.onSample(sample(bottom = 1_300, dragging = true))

        // The reply keeps streaming while the user reads above it.
        repeat(20) { token ->
            val moved = controller.onSample(
                sample(bottom = 1_300 + token * 40, dragging = false, scrolling = false),
            )
            assertEquals(TailFollow.Hold, moved)
            assertTrue(controller.isDetached)
        }
    }

    @Test
    fun `re-attaches only once the user is at the tail and no scroll is in flight`() {
        val controller = AutoFollowController()
        controller.onSample(sample(bottom = 2_000, dragging = true))
        assertTrue(controller.isDetached)

        // Still dragging, now back at the bottom: the finger owns the list.
        assertEquals(
            TailFollow.Hold,
            controller.onSample(sample(bottom = 1_000, dragging = true)),
        )
        assertTrue(controller.isDetached)

        // Finger lifted but the fling is still coasting.
        assertEquals(
            TailFollow.Hold,
            controller.onSample(sample(bottom = 1_000, scrolling = true)),
        )
        assertTrue(controller.isDetached)

        // Settled at the tail with no gesture: following resumes.
        assertEquals(
            TailFollow.Hold,
            controller.onSample(sample(bottom = 1_000, scrolling = false)),
        )
        assertFalse(controller.isDetached)

        // …and the next token is followed again.
        assertEquals(
            TailFollow.ScrollBy(24),
            controller.onSample(sample(bottom = 1_024)),
        )
    }

    /**
     * The old implementation flip-flopped: a token grew the row (`atBottom` went
     * false), the follower scrolled, `isScrollInProgress` was sampled against a
     * flag that another coroutine had already cleared, and `autoFollow` dropped
     * out. Replaying a whole stream — growth, our scroll, the in-progress sample —
     * must leave the follower attached throughout.
     */
    @Test
    fun `a full stream of tokens never detaches the follower`() {
        val controller = AutoFollowController()
        var bottom = 1_000
        var detachTransitions = 0
        var scrollCommands = 0

        repeat(500) {
            bottom += 37
            val command = controller.onSample(sample(bottom = bottom))
            when (command) {
                is TailFollow.ScrollBy -> {
                    scrollCommands++
                    assertEquals(37, command.deltaPx)
                    // Applying the scroll puts the tail back exactly at the edge.
                    bottom -= command.deltaPx
                }
                TailFollow.Hold -> Unit
                TailFollow.Jump -> throw AssertionError("tail was always visible")
            }
            if (controller.isDetached) detachTransitions++
            // The layout immediately after our own scroll, with the scroll flag
            // still set for that frame.
            controller.onSample(sample(bottom = bottom, scrolling = true))
            if (controller.isDetached) detachTransitions++
            // Steady state between tokens.
            assertEquals(TailFollow.Hold, controller.onSample(sample(bottom = bottom)))
        }

        assertEquals(0, detachTransitions)
        assertEquals(500, scrollCommands)
    }

    @Test
    fun `tapping jump re-attaches through the same at-tail edge`() {
        val controller = AutoFollowController()
        controller.onSample(sample(bottom = 5_000, dragging = true))
        assertTrue(controller.isDetached)

        // `jumpToTail()` is not a gesture and sets no flag; the follower simply
        // observes that the tail arrived.
        assertEquals(
            TailFollow.Hold,
            controller.onSample(sample(bottom = 1_000, scrolling = false)),
        )
        assertFalse(controller.isDetached)
    }

    @Test
    fun `an off-screen tail jumps once, but never while detached`() {
        val following = AutoFollowController()
        assertEquals(
            TailFollow.Jump,
            following.onSample(sample(total = 40, lastVisible = 20, bottom = 900)),
        )

        val detached = AutoFollowController()
        detached.onSample(sample(total = 40, lastVisible = 20, bottom = 2_000, dragging = true))
        assertTrue(detached.isDetached)
        assertEquals(
            TailFollow.Hold,
            detached.onSample(sample(total = 40, lastVisible = 20, bottom = 2_000)),
        )
    }

    @Test
    fun `an empty transcript never scrolls`() {
        val controller = AutoFollowController()

        assertEquals(TailFollow.Hold, controller.onSample(sample(total = 0, lastVisible = -1)))
        assertEquals(TailFollow.Hold, controller.onSample(sample(total = 1, lastVisible = -1)))
        assertFalse(controller.isDetached)
    }

    @Test
    fun `dragging with the whole list already visible does not detach`() {
        // Overscrolling a transcript that does not overflow must not show the
        // jump affordance or silently stop following the next reply.
        val controller = AutoFollowController()

        assertEquals(
            TailFollow.Hold,
            controller.onSample(sample(bottom = 300, edge = 1_000, dragging = true)),
        )
        assertFalse(controller.isDetached)
    }

    @Test
    fun `no command ever scrolls under a finger`() {
        val controller = AutoFollowController()
        val cases = listOf(
            sample(bottom = 1_400, dragging = true),
            sample(bottom = 1_000, dragging = true),
            sample(total = 0, lastVisible = -1, dragging = true),
            sample(total = 40, lastVisible = 10, dragging = true),
        )
        repeat(2) {
            cases.forEach { case ->
                assertEquals(TailFollow.Hold, controller.onSample(case))
            }
        }
    }
}
