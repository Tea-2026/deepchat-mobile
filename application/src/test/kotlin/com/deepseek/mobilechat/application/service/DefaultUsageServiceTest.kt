package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.UsageAggregateStore
import com.deepseek.mobilechat.application.UsageByModel
import com.deepseek.mobilechat.application.UsageDay
import com.deepseek.mobilechat.application.UsageReport
import com.deepseek.mobilechat.application.UsageTotals
import com.deepseek.mobilechat.domain.ConversationId
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultUsageServiceTest {

    private val beijing = TimeZone.getTimeZone("Asia/Shanghai")

    /** 2026-11-21 15:00 Beijing. */
    private val nowMillis = java.time.OffsetDateTime
        .of(2026, 11, 21, 15, 0, 0, 0, java.time.ZoneOffset.ofHours(8))
        .toInstant()
        .toEpochMilli()

    private class FixedClock(private val value: Long) : ClockPort {
        override fun nowMillis(): Long = value
    }

    private class FakeStore(
        private val days: List<UsageDay> = emptyList(),
        private val models: List<UsageByModel> = emptyList(),
        private val conversation: UsageTotals = UsageTotals(),
        private val failDaily: Boolean = false,
    ) : UsageAggregateStore {
        var lastSince: Long? = null

        override suspend fun dailyTotals(sinceMillis: Long): List<UsageDay> {
            lastSince = sinceMillis
            if (failDaily) throw IllegalStateException("db is on fire")
            return days
        }

        override suspend fun modelTotals(sinceMillis: Long): List<UsageByModel> = models

        override suspend fun conversationTotals(conversationId: ConversationId): UsageTotals = conversation
    }

    @Test
    fun `empty days are filled so the chart has a continuous axis`() = runTest {
        val store = FakeStore(
            days = listOf(UsageDay(nowMillis, UsageTotals(costCny = 1.5, messageCount = 2))),
        )
        val service = DefaultUsageService(store, FixedClock(nowMillis), beijing)

        val report = (service.report(7) as Outcome.Ok).value

        assertEquals(7, report.days.size)
        // "Now" is 15:00 local, so today's bucket starts 15 hours earlier, and
        // the window starts six days before that.
        val todayStart = nowMillis - 15L * 3_600_000
        assertEquals(todayStart - 6L * 86_400_000, report.days.first().dayStartMillis)
        assertEquals(todayStart, report.days.last().dayStartMillis)
        assertEquals(1.5, report.totals.costCny, 1e-9)
        assertEquals(2, report.totals.messageCount)
        assertTrue(report.days.count { it.totals.costCny == 0.0 } == 6)
    }

    @Test
    fun `window start is the first day queried`() = runTest {
        val store = FakeStore()
        val service = DefaultUsageService(store, FixedClock(nowMillis), beijing)

        service.report(30)

        assertEquals(nowMillis - 15L * 3_600_000 - 29L * 86_400_000, store.lastSince)
    }

    @Test
    fun `totals are the sum of the window`() = runTest {
        val store = FakeStore(
            days = listOf(
                UsageDay(nowMillis - 86_400_000, UsageTotals(promptTokens = 10, completionTokens = 5, costCny = 0.5)),
                UsageDay(nowMillis, UsageTotals(promptTokens = 20, completionTokens = 7, costCny = 1.0)),
            ),
        )
        val service = DefaultUsageService(store, FixedClock(nowMillis), beijing)

        val report = (service.report(3) as Outcome.Ok).value

        assertEquals(30, report.totals.promptTokens)
        assertEquals(12, report.totals.completionTokens)
        assertEquals(1.5, report.totals.costCny, 1e-9)
        assertEquals(42, report.totals.totalTokens)
    }

    @Test
    fun `models are ordered by cost descending`() = runTest {
        val store = FakeStore(
            models = listOf(
                UsageByModel("p", "cheap", UsageTotals(costCny = 0.1)),
                UsageByModel("p", "pricey", UsageTotals(costCny = 9.0)),
            ),
        )
        val service = DefaultUsageService(store, FixedClock(nowMillis), beijing)

        val report = (service.report(7) as Outcome.Ok).value

        assertEquals(listOf("pricey", "cheap"), report.byModel.map { it.modelId })
    }

    @Test
    fun `window is clamped to a sane range`() = runTest {
        val store = FakeStore()
        val service = DefaultUsageService(store, FixedClock(nowMillis), beijing)

        val tiny = (service.report(0) as Outcome.Ok).value
        val huge = (service.report(10_000) as Outcome.Ok).value

        assertEquals(1, tiny.windowDays)
        assertEquals(365, huge.windowDays)
    }

    @Test
    fun `a failing store becomes a Storage error instead of a crash`() = runTest {
        val service = DefaultUsageService(FakeStore(failDaily = true), FixedClock(nowMillis), beijing)

        val outcome = service.report(7)

        assertTrue(outcome is Outcome.Err)
    }

    @Test
    fun `conversation totals survive a store failure`() = runTest {
        val service = DefaultUsageService(FakeStore(), FixedClock(nowMillis), beijing)

        val totals = service.conversationTotals(ConversationId("missing"))

        assertEquals(UsageTotals(), totals)
    }
}
