package com.deepseek.mobilechat.ui.components

internal const val AttachmentStripMaxVisible = 4

/** One square in the message attachment strip. */
internal data class AttachmentStripSlot(
    /** Index into the message's attachment list. */
    val index: Int,
    /** Extra images this square stands for (`+N` overlay); 0 when it is not the last square. */
    val overflow: Int,
)

/**
 * Chooses which attachment indices get a thumbnail: at most [maxVisible]
 * squares, with the last one carrying the count of images that did not fit.
 * Pure so the `+N` rule can be tested without pixels.
 */
internal fun attachmentStripSlots(
    count: Int,
    maxVisible: Int = AttachmentStripMaxVisible,
): List<AttachmentStripSlot> {
    if (count <= 0 || maxVisible <= 0) return emptyList()
    val visible = minOf(count, maxVisible)
    return (0 until visible).map { index ->
        AttachmentStripSlot(
            index = index,
            overflow = if (index == visible - 1 && count > maxVisible) count - maxVisible else 0,
        )
    }
}
