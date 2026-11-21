package com.deepseek.mobilechat.application.pricing

import com.deepseek.mobilechat.domain.Currency
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The peak/idle split is worth real tests: DeepSeek bills off-peak at half, so a
 * wrong boundary silently doubles or halves every estimate.
 */
class PriceBookTest {

    private fun beijing(day: Int, hour: Int, minute: Int = 0): Long =
        java.time.OffsetDateTime.of(2026, 11, day, hour, minute, 0, 0, ZoneOffset.ofHours(8))
            .toInstant()
            .toEpochMilli()

    @Test
    fun `deepseek flash is billed in CNY at the documented peak rates`() {
        val pricing = PriceBook.lookup("deepseek-flash", beijing(day = 23, hour = 10))!!

        assertEquals(Currency.CNY, pricing.currency)
        assertEquals(2.0, pricing.inputPerMTok, 1e-9)
        assertEquals(8.0, pricing.outputPerMTok, 1e-9)
        assertEquals(0.04, pricing.cachedInputPerMTok!!, 1e-9)
    }

    @Test
    fun `off-peak is exactly half`() {
        val peak = PriceBook.lookup("deepseek-flash", beijing(day = 23, hour = 10))!!
        val idle = PriceBook.lookup("deepseek-flash", beijing(day = 23, hour = 20))!!

        assertEquals(peak.inputPerMTok / 2, idle.inputPerMTok, 1e-9)
        assertEquals(peak.outputPerMTok / 2, idle.outputPerMTok, 1e-9)
        assertEquals(peak.cachedInputPerMTok!! / 2, idle.cachedInputPerMTok!!, 1e-9)
    }

    @Test
    fun `peak window boundaries are 9-12 and 14-18 on weekdays`() {
        // 2026-11-23 is a Monday.
        assertFalse(PriceBook.isDeepSeekPeak(beijing(day = 23, hour = 8, minute = 59)))
        assertTrue(PriceBook.isDeepSeekPeak(beijing(day = 23, hour = 9)))
        assertTrue(PriceBook.isDeepSeekPeak(beijing(day = 23, hour = 11, minute = 59)))
        assertFalse(PriceBook.isDeepSeekPeak(beijing(day = 23, hour = 12)))
        assertFalse(PriceBook.isDeepSeekPeak(beijing(day = 23, hour = 13)))
        assertTrue(PriceBook.isDeepSeekPeak(beijing(day = 23, hour = 14)))
        assertTrue(PriceBook.isDeepSeekPeak(beijing(day = 23, hour = 17, minute = 59)))
        assertFalse(PriceBook.isDeepSeekPeak(beijing(day = 23, hour = 18)))
    }

    @Test
    fun `weekends are never peak`() {
        // 2026-11-21 is a Saturday, 2026-11-22 a Sunday.
        assertFalse(PriceBook.isDeepSeekPeak(beijing(day = 21, hour = 10)))
        assertFalse(PriceBook.isDeepSeekPeak(beijing(day = 22, hour = 15)))
    }

    @Test
    fun `longest pattern wins`() {
        val mini = PriceBook.lookup("gpt-4o-mini", beijing(day = 23, hour = 10))!!
        val full = PriceBook.lookup("gpt-4o", beijing(day = 23, hour = 10))!!

        assertEquals(0.15, mini.inputPerMTok, 1e-9)
        assertEquals(2.50, full.inputPerMTok, 1e-9)
        assertEquals(Currency.USD, full.currency)
    }

    @Test
    fun `unknown model has no price`() {
        assertNull(PriceBook.lookup("some-unknown-model", beijing(day = 23, hour = 10)))
    }

    @Test
    fun `usd prices are converted once at the boundary`() {
        assertEquals(7.2, Fx.toCny(1.0, Currency.USD), 1e-9)
        // A CNY price must never be converted twice.
        assertEquals(1.0, Fx.toCny(1.0, Currency.CNY), 1e-9)
    }
}
