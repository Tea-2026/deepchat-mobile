package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.UsageAggregateStore
import com.deepseek.mobilechat.application.UsageDay
import com.deepseek.mobilechat.application.UsageReport
import com.deepseek.mobilechat.application.UsageService
import com.deepseek.mobilechat.application.UsageTotals
import java.util.TimeZone
import kotlinx.coroutines.CancellationException

/**
 * Default [UsageService].
 *
 * The store returns only the days that have usage; a chart needs the empty days
 * too, so they are filled in here rather than in SQL (SQLite has no calendar).
 * Day boundaries are the *device's* local days, computed from a fixed offset, so
 * they match what the phone's clock shows. The zone is injectable for tests.
 */
class DefaultUsageService(
    private val store: UsageAggregateStore,
    private val clock: ClockPort,
    private val zone: TimeZone = TimeZone.getDefault(),
) : UsageService {

    override suspend fun report(windowDays: Int): Outcome<UsageReport> {
        val days = windowDays.coerceIn(1, MAX_WINDOW_DAYS)
        val todayStart = startOfDay(clock.nowMillis())
        val windowStart = todayStart - (days - 1).toLong() * MILLIS_PER_DAY
        return try {
            val daily = store.dailyTotals(windowStart)
            val filled = fillMissingDays(daily, windowStart, days)
            Outcome.Ok(
                UsageReport(
                    windowDays = days,
                    totals = filled.fold(UsageTotals()) { acc, day -> acc + day.totals },
                    days = filled,
                    byModel = store.modelTotals(windowStart).sortedByDescending { it.totals.costCny },
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Outcome.Err(AppError.Storage("usage aggregation failed: ${failure.message}"))
        }
    }

    override suspend fun conversationTotals(conversationId: ConversationId): UsageTotals =
        try {
            store.conversationTotals(conversationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A missing total is cosmetic and must never take down the chat screen.
            UsageTotals()
        }

    /** Start of the local calendar day containing [atMillis]. */
    private fun startOfDay(atMillis: Long): Long {
        val offset = zone.getOffset(atMillis)
        val local = atMillis + offset
        return local - Math.floorMod(local, MILLIS_PER_DAY) - offset
    }

    /**
     * One entry per day in the window, oldest first. A day without usage is
     * represented explicitly so the chart has a continuous axis.
     */
    private fun fillMissingDays(daily: List<UsageDay>, windowStart: Long, days: Int): List<UsageDay> {
        val byDay = daily.associateBy { startOfDay(it.dayStartMillis) }
        return (0 until days).map { offset ->
            val dayStart = windowStart + offset.toLong() * MILLIS_PER_DAY
            UsageDay(dayStartMillis = dayStart, totals = byDay[dayStart]?.totals ?: UsageTotals())
        }
    }

    private companion object {
        const val MILLIS_PER_DAY: Long = 24L * 60 * 60 * 1000
        const val MAX_WINDOW_DAYS: Int = 365
    }
}
