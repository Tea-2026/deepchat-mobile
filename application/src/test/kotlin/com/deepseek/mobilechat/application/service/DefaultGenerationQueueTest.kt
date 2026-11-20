package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppSettings
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.QueueState
import com.deepseek.mobilechat.application.service.testutil.FakeClock
import com.deepseek.mobilechat.application.service.testutil.FakeSettingsStore
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.MessageId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultGenerationQueueTest {

    private fun TestScope.newQueue(
        settings: FakeSettingsStore = FakeSettingsStore(),
    ): DefaultGenerationQueue = DefaultGenerationQueue(
        settingsStore = settings,
        clock = FakeClock(),
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
    )

    private val c1 = ConversationId("c1")
    private val c2 = ConversationId("c2")
    private val c3 = ConversationId("c3")

    @Test
    fun `tasks of the same conversation run strictly one after another`() = runTest {
        val queue = newQueue()
        var running = 0
        var maxRunning = 0
        val order = mutableListOf<String>()

        suspend fun task(name: String) {
            order += "start:$name"
            running++
            maxRunning = maxOf(maxRunning, running)
            delay(100)
            running--
            order += "end:$name"
        }

        queue.submit(c1, MessageId("m1")) { task("a") }
        queue.submit(c1, MessageId("m2")) { task("b") }
        runCurrent()
        assertEquals(
            listOf(QueueState.RUNNING, QueueState.QUEUED),
            queue.observe().first().map { it.state },
        )
        advanceUntilIdle()

        assertEquals(1, maxRunning)
        assertEquals(listOf("start:a", "end:a", "start:b", "end:b"), order)
        assertTrue(queue.observe().first().isEmpty())
    }

    @Test
    fun `global concurrency never exceeds the configured limit`() = runTest {
        val settings = FakeSettingsStore(AppSettings(maxConcurrentGenerations = 2))
        val queue = newQueue(settings)
        var running = 0
        var maxRunning = 0

        suspend fun task() {
            running++
            maxRunning = maxOf(maxRunning, running)
            delay(100)
            running--
        }

        queue.submit(c1, MessageId("m1")) { task() }
        queue.submit(c2, MessageId("m2")) { task() }
        queue.submit(c3, MessageId("m3")) { task() }
        advanceUntilIdle()

        assertEquals(2, maxRunning)
        assertTrue(queue.observe().first().isEmpty())
    }

    @Test
    fun `pause keeps new tasks from starting and resume releases them`() = runTest {
        val queue = newQueue()
        var started = 0
        queue.pause()

        val accepted = queue.submit(c1, MessageId("m1")) { started++ }
        advanceUntilIdle()

        assertTrue(accepted is Outcome.Ok)
        assertEquals(0, started)
        val paused = queue.observe().first().single()
        assertEquals(QueueState.PAUSED, paused.state)
        assertEquals(c1, paused.conversationId)
        assertEquals(MessageId("m1"), paused.messageId)

        queue.resume()
        advanceUntilIdle()

        assertEquals(1, started)
        assertTrue(queue.observe().first().isEmpty())
    }

    @Test
    fun `pause does not interrupt a running task`() = runTest {
        val queue = newQueue()
        var finished = false
        queue.submit(c1, MessageId("m1")) {
            delay(100)
            finished = true
        }
        runCurrent()
        assertEquals(QueueState.RUNNING, queue.observe().first().single().state)

        queue.pause()
        advanceUntilIdle()

        assertTrue(finished)
    }

    @Test
    fun `cancel drops the running task and the queued ones of that conversation`() = runTest {
        val queue = newQueue()
        var cancelled = false
        var secondStarted = false

        queue.submit(c1, MessageId("m1")) {
            try {
                delay(1_000)
            } catch (cancellation: CancellationException) {
                cancelled = true
                throw cancellation
            }
        }
        queue.submit(c1, MessageId("m2")) { secondStarted = true }
        runCurrent()

        queue.cancel(c1)
        advanceUntilIdle()

        assertTrue(cancelled)
        assertEquals(false, secondStarted)
        assertTrue(queue.observe().first().isEmpty())
    }

    @Test
    fun `queue reports running and queued items`() = runTest {
        val queue = newQueue()
        queue.submit(c1, MessageId("m1")) { delay(1_000) }
        queue.submit(c2, MessageId("m2")) { delay(1_000) }
        runCurrent()

        val items = queue.observe().first()
        assertEquals(2, items.size)
        assertEquals(QueueState.RUNNING, items[0].state)
        assertEquals(QueueState.RUNNING, items[1].state)
    }
}
