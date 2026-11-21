package com.deepseek.mobilechat.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `max 4 + N` rule for the message attachment strip. */
class AttachmentStripTest {

    @Test
    fun `no attachments produce no slots`() {
        assertTrue(attachmentStripSlots(0).isEmpty())
    }

    @Test
    fun `up to four images are all visible`() {
        val slots = attachmentStripSlots(4)
        assertEquals(listOf(0, 1, 2, 3), slots.map { it.index })
        assertTrue(slots.all { it.overflow == 0 })
    }

    @Test
    fun `the fourth square carries the overflow count`() {
        val slots = attachmentStripSlots(6)
        assertEquals(4, slots.size)
        assertEquals(listOf(0, 1, 2, 3), slots.map { it.index })
        assertEquals(0, slots[2].overflow)
        assertEquals(2, slots[3].overflow)
    }

    @Test
    fun `a single image has no overflow`() {
        val slots = attachmentStripSlots(1).single()
        assertEquals(0, slots.index)
        assertEquals(0, slots.overflow)
    }

    @Test
    fun `max visible is configurable`() {
        val slots = attachmentStripSlots(5, maxVisible = 2)
        assertEquals(2, slots.size)
        assertEquals(3, slots.last().overflow)
    }
}
