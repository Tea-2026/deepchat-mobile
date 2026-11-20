package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.AppSettings
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.GenerationQueue
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.QueueItem
import com.deepseek.mobilechat.application.QueueState
import com.deepseek.mobilechat.application.SettingsStore
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.MessageId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/**
 * Internal scheduling seam used by [DefaultChatService].
 *
 * It is deliberately smaller than [GenerationQueue]: a use case only needs to
 * hand a streaming block to the scheduler and learn whether the item was
 * accepted. Waiting, ordering and cancellation are the queue's business.
 *
 * [submit] returns as soon as the item is registered; the block runs later on
 * the queue's own scope. The returned [Outcome] therefore describes
 * *acceptance*, not the result of the block.
 */
interface GenerationScheduler {
    suspend fun submit(
        conversationId: ConversationId,
        messageId: MessageId,
        block: suspend () -> Unit,
    ): Outcome<Unit>
}

/**
 * In-memory generation queue (docs/architecture.md §4.5).
 *
 * Execution model:
 *
 * - [submit] registers a [QueueItem] and launches the work on [scope]; it
 *   returns immediately after acceptance. Callers must never wait on the
 *   generation itself inside a use case.
 * - One [Mutex] per conversation makes every conversation strictly serial:
 *   the next item of a conversation waits for the previous one to finish.
 * - One global [Semaphore] caps **concurrently running** generations at
 *   `SettingsStore.maxConcurrentGenerations` (default 2). Only items that are
 *   actually running hold a permit, so a serialized conversation never blocks
 *   another conversation's slot.
 * - [pause] parks items that have not started yet without touching running
 *   ones; [resume] releases them in FIFO order.
 * - [cancel] cancels the running job and drops every queued item of one
 *   conversation.
 *
 * Changing `maxConcurrentGenerations` at runtime replaces the semaphore; items
 * that already hold a permit release it into the previous semaphore, so the new
 * limit can be exceeded for at most one generation per conversation. That
 * transient is preferable to blocking the queue until every old task drains.
 *
 * A task that throws a non-cancellation exception does not escape into the
 * application scope (a background crash would be invisible and unrecoverable
 * from here); the block itself is responsible for turning failures into
 * persisted message state. Cancellation propagates normally.
 */
class DefaultGenerationQueue(
    private val settingsStore: SettingsStore,
    private val clock: ClockPort,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : GenerationQueue, GenerationScheduler {

    private val paused = MutableStateFlow(false)
    private val items = MutableStateFlow<List<QueueItem>>(emptyList())

    /** Registered items, in enqueue order. */
    private val entries = MutableStateFlow<List<Entry>>(emptyList())

    private val conversationLocks = ConcurrentHashMap<ConversationId, Mutex>()

    private val semaphoreGuard = Mutex()
    private var semaphore: Semaphore? = null
    private var semaphoreLimit: Int = -1

    override fun observe(): Flow<List<QueueItem>> = items.asStateFlow()

    override fun pause() {
        paused.value = true
        publish()
    }

    override fun resume() {
        paused.value = false
        publish()
    }

    override suspend fun submit(
        conversationId: ConversationId,
        messageId: MessageId,
        block: suspend () -> Unit,
    ): Outcome<Unit> {
        if (!scope.isActive) {
            return Outcome.Err(AppError.Unknown("generation queue is not active"))
        }
        val running = MutableStateFlow(false)
        lateinit var entry: Entry
        val job = scope.launch(start = CoroutineStart.LAZY) { runEntry(entry, block) }
        entry = Entry(
            conversationId = conversationId,
            messageId = messageId,
            enqueuedAt = clock.nowMillis(),
            job = job,
            running = running,
        )
        entries.update { it + entry }
        publish()
        job.start()
        return Outcome.Ok(Unit)
    }

    override suspend fun cancel(conversationId: ConversationId) {
        val targets = entries.value.filter { it.conversationId == conversationId }
        targets.forEach { it.job.cancel() }
        entries.update { current -> current.filterNot { it.conversationId == conversationId } }
        publish()
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private suspend fun runEntry(entry: Entry, block: suspend () -> Unit) {
        try {
            conversationLock(entry.conversationId).withLock {
                awaitResumed()
                val semaphore = semaphoreFor(currentSettings())
                semaphore.acquire()
                try {
                    // Pause may have been requested while this item waited for
                    // a global permit; a paused queue starts nothing.
                    awaitResumed()
                    entry.running.value = true
                    publish()
                    block()
                } finally {
                    entry.running.value = false
                    semaphore.release()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Program errors inside a task must not tear down the application
            // scope. The block owns its failure reporting (persisted message
            // state), so there is nothing left to do here.
        } finally {
            remove(entry)
        }
    }

    private suspend fun awaitResumed() {
        if (!paused.value) return
        paused.first { !it }
    }

    private fun conversationLock(conversationId: ConversationId): Mutex =
        conversationLocks.getOrPut(conversationId) { Mutex() }

    private suspend fun currentSettings(): AppSettings =
        try {
            settingsStore.observe().first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AppSettings()
        }

    private suspend fun semaphoreFor(settings: AppSettings): Semaphore = semaphoreGuard.withLock {
        val limit = settings.maxConcurrentGenerations.coerceAtLeast(1)
        val existing = semaphore
        if (existing != null && semaphoreLimit == limit) {
            existing
        } else {
            Semaphore(limit).also {
                semaphore = it
                semaphoreLimit = limit
            }
        }
    }

    private fun remove(entry: Entry) {
        entries.update { current -> current.filterNot { it === entry } }
        publish()
    }

    private fun publish() {
        val isPaused = paused.value
        val snapshot = entries.value.map { entry ->
            val state = when {
                entry.running.value -> QueueState.RUNNING
                isPaused -> QueueState.PAUSED
                else -> QueueState.QUEUED
            }
            QueueItem(
                conversationId = entry.conversationId,
                messageId = entry.messageId,
                state = state,
                enqueuedAt = entry.enqueuedAt,
            )
        }
        items.value = snapshot
    }

    private class Entry(
        val conversationId: ConversationId,
        val messageId: MessageId,
        val enqueuedAt: Long,
        val job: Job,
        val running: MutableStateFlow<Boolean>,
    )
}
