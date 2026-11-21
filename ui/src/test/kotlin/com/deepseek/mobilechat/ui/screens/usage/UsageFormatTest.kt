package com.deepseek.mobilechat.ui.screens.usage

import com.deepseek.mobilechat.application.UsageDay
import com.deepseek.mobilechat.application.UsageTotals
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the rules that make the usage page trustworthy: money never renders a
 * cheap day as zero, tokens stay compact, and a share can never be NaN.
 */
class UsageFormatTest {

    // ── money ─────────────────────────────────────────────────────────────

    @Test
    fun `money uses two decimals and thousands separators`() {
        assertEquals("¥0.00", formatCny(0.0))
        assertEquals("¥1.50", formatCny(1.5))
        assertEquals("¥10,000.00", formatCny(10_000.0))
        assertEquals("¥12,345.68", formatCny(12_345.678))
        assertEquals("¥1,234.57", formatCny(1234.567))
        assertEquals("¥1,234,567.89", formatCny(1234567.891))
    }

    @Test
    fun `sub-cent money keeps four decimals instead of reading as zero`() {
        assertEquals("¥0.0034", formatCny(0.0034))
        assertEquals("¥0.0002", formatCny(0.0002))
        assertEquals("¥0.0001", formatCny(0.0001))
        // Three ten-thousandths of a yuan is real spend, not ¥0.00.
        assertEquals("<¥0.0001", formatCny(0.00003))
        assertEquals("<¥0.0001", formatCny(0.00005))
    }

    @Test
    fun `money switches to two decimals at one cent`() {
        assertEquals("¥0.01", formatCny(0.01))
        assertEquals("¥0.02", formatCny(0.015))
        assertEquals("¥0.99", formatCny(0.99))
    }

    @Test
    fun `negative and non finite money is clamped to zero`() {
        assertEquals("¥0.00", formatCny(-1.0))
        assertEquals("¥0.00", formatCny(Double.NaN))
        assertEquals("¥0.00", formatCny(Double.POSITIVE_INFINITY))
        assertEquals("¥0.00", formatCny(Double.NEGATIVE_INFINITY))
    }

    // ── tokens ────────────────────────────────────────────────────────────

    @Test
    fun `tokens group below ten thousand`() {
        assertEquals("0", formatTokens(0))
        assertEquals("999", formatTokens(999))
        assertEquals("1,000", formatTokens(1_000))
        assertEquals("1,234", formatTokens(1234))
        assertEquals("9,999", formatTokens(9999))
    }

    @Test
    fun `tokens use wan and yi for large counts`() {
        assertEquals("1.2 万", formatTokens(12_345))
        assertEquals("1 万", formatTokens(10_000))
        assertEquals("100 万", formatTokens(1_000_000))
        assertEquals("12.3 万", formatTokens(123_456))
        assertEquals("123.5 万", formatTokens(1_234_567))
        assertEquals("1.2 亿", formatTokens(123_456_789))
        // Rounding must promote instead of printing "10000 万".
        assertEquals("1 亿", formatTokens(99_999_999))
    }

    @Test
    fun `negative tokens clamp to zero`() {
        assertEquals("0", formatTokens(-5))
        assertEquals("0", formatTokens(Long.MIN_VALUE))
    }

    // ── shares ────────────────────────────────────────────────────────────

    @Test
    fun `share uses cost and falls back to tokens when nothing is priced`() {
        assertEquals(0.25, shareOfTotal(5.0, 20.0, 0, 0), 0.0001)
        assertEquals(0.25, shareOfTotal(0.0, 0.0, 100, 400), 0.0001)
        assertEquals(0.0, shareOfTotal(0.0, 0.0, 0, 0), 0.0001)
    }

    @Test
    fun `share is clamped against bad input`() {
        // Ordinary cost share.
        assertEquals(0.5, shareOfTotal(5.0, 10.0, 0, 0), 0.0001)
        // A part larger than the total cannot render wider than 100%.
        assertEquals(1.0, shareOfTotal(50.0, 10.0, 0, 0), 0.0001)
        // A negative or NaN part is not a share of anything.
        assertEquals(0.0, shareOfTotal(-5.0, 10.0, 0, 0), 0.0001)
        assertEquals(0.0, shareOfTotal(Double.NaN, 10.0, 0, 0), 0.0001)
        // A NaN or zero denominator has no meaningful share: no tokens either,
        // so the honest answer is 0, not a division result.
        assertEquals(0.0, shareOfTotal(5.0, Double.NaN, 0, 0), 0.0001)
        assertEquals(0.0, shareOfTotal(5.0, 0.0, 0, 0), 0.0001)
    }

    @Test
    fun `share labels stay readable`() {
        assertEquals("0%", formatShare(0.0))
        assertEquals("<1%", formatShare(0.004))
        assertEquals("62%", formatShare(0.62))
        assertEquals("100%", formatShare(1.0))
        assertEquals("0%", formatShare(Double.NaN))
    }

    // ── labels ────────────────────────────────────────────────────────────

    @Test
    fun `model name falls back to provider then a placeholder`() {
        assertEquals("deepseek-v4-pro", modelDisplayName("deepseek-v4-pro", "deepseek"))
        assertEquals("deepseek", modelDisplayName("   ", "deepseek"))
        assertEquals("未知模型", modelDisplayName("", ""))
    }

    @Test
    fun `date label is month and day`() {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 21, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("11月21日", dateLabel(calendar.timeInMillis))
    }

    @Test
    fun `axis prints only a few evenly spaced dates`() {
        assertEquals(listOf(0, 2, 4, 6), axisLabelIndices(7))
        assertEquals(listOf(0, 10, 19, 29), axisLabelIndices(30))
        assertEquals(listOf(0, 30, 59, 89), axisLabelIndices(90))
        assertEquals(listOf(0, 1), axisLabelIndices(2))
        assertEquals(emptyList<Int>(), axisLabelIndices(0))
    }

    // ── month-to-date ─────────────────────────────────────────────────────

    @Test
    fun `month total appears only when the window covers the month start`() {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val monthStart = calendar.timeInMillis
        val day = 24L * 60 * 60 * 1000
        val monthDays = listOf(
            UsageDay(monthStart, UsageTotals(costCny = 1.0)),
            UsageDay(monthStart + day, UsageTotals(costCny = 2.0)),
        )
        assertEquals(3.0, monthToDateCny(monthDays)!!, 0.0001)

        val partialMonth = listOf(
            UsageDay(monthStart + 9 * day, UsageTotals(costCny = 4.0)),
        )
        assertNull(monthToDateCny(partialMonth))
        assertNull(monthToDateCny(emptyList()))
    }

    @Test
    fun `month total ignores days from the previous month`() {
        val october = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 25, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val november = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val day = 24L * 60 * 60 * 1000
        val days = listOf(
            UsageDay(october, UsageTotals(costCny = 99.0)),
            UsageDay(november, UsageTotals(costCny = 2.0)),
            UsageDay(november + day, UsageTotals(costCny = 3.0)),
        )
        assertEquals(5.0, monthToDateCny(days)!!, 0.0001)
    }
}
