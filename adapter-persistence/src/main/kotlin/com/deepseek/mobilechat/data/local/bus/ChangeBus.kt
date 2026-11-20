package com.deepseek.mobilechat.data.local.bus

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One shared "something changed" signal for the whole local database.
 *
 * Stores call [notifyChanged] after every committed write. Observation flows
 * re-query on every event, which keeps SQLite as the single source of truth.
 * There is deliberately no per-table granularity: the database is local and
 * small, and a coarse signal makes cascades (deleting a conversation touches
 * messages, tags, injections, attachments) trivially correct.
 *
 * The buffer is lossy on purpose: if a burst of writes outruns the collectors
 * they only need one more re-query anyway, and [tryEmit] never suspends a store
 * write.
 */
class ChangeBus(extraBufferCapacity: Int = 64) {

    init {
        require(extraBufferCapacity > 0) { "extraBufferCapacity must be > 0 for DROP_OLDEST" }
    }

    private val mutableChanges = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = extraBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val changes: SharedFlow<Unit> = mutableChanges.asSharedFlow()

    fun notifyChanged() {
        mutableChanges.tryEmit(Unit)
    }
}
