package com.deepseek.mobilechat.ui.screens.usage

import com.deepseek.mobilechat.application.UsageDay
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure formatting for the usage screen.
 *
 * Kept out of [UsageScreen] so money, token and share rules can be unit-tested
 * without a Compose runtime. Everything here is defensive: money and counts come
 * from an estimate pipeline where a provider may report zero, a partial number,
 * or (after a bug) a negative/NaN value, and the UI must never print those.
 */

/** Below this, a positive amount rounds to zero at four decimals. */
private const val SubCent = 0.0001

/** Smallest amount we still render with four decimals. */
private const val Cent = 0.01

/**
 * Chinese-yuan amount, e.g. `¥1,234.56`.
 *
 * Rules, in order:
 * - zero, negative and non-finite collapse to `¥0.00` (a spend cannot be negative);
 * - below [SubCent] renders `<¥0.0001` instead of `¥0.00`, so a cheap day is
 *   visible rather than indistinguishable from a day with no usage;
 * - below [Cent] keeps four decimals (`¥0.0034`);
 * - everything else is two decimals with thousands separators.
 */
fun formatCny(value: Double): String {
    val safe = if (value.isFinite() && value > 0.0) value else 0.0
    return when {
        safe <= 0.0 -> "¥0.00"
        safe < SubCent -> "<¥0.0001"
        safe < Cent -> "¥" + String.format(Locale.US, "%,.4f", safe)
        else -> "¥" + String.format(Locale.US, "%,.2f", safe)
    }
}

/**
 * Token count: `1,234` below 万, `12.3 万` above it, `1.2 亿` above 亿.
 *
 * The promotion thresholds are rounding-aware: 99,999,999 must read `1 亿`,
 * never `10000 万`.
 */
fun formatTokens(tokens: Long): String {
    val safe = if (tokens > 0L) tokens else 0L
    if (safe < 10_000L) return String.format(Locale.US, "%,d", safe)

    val wan = safe / 10_000.0
    return if (wan < 9_999.5) {
        "${oneDecimal(wan)} 万"
    } else {
        "${oneDecimal(safe / 100_000_000.0)} 亿"
    }
}

/** `0.62` → `62%`; tiny nonzero shares read `<1%`, and bad input reads `0%`. */
fun formatShare(fraction: Double): String {
    if (!fraction.isFinite() || fraction <= 0.0) return "0%"
    if (fraction < 0.005) return "<1%"
    if (fraction >= 0.9995) return "100%"
    return "${(fraction * 100.0).roundToInt()}%"
}

/**
 * A model's share of the window.
 *
 * Cost is the natural denominator on a spend page. When nothing has a price yet
 * (unknown model, zero total) the token count is the honest fallback; with no
 * data at all the answer is 0, not NaN.
 */
fun shareOfTotal(
    partCost: Double,
    totalCost: Double,
    partTokens: Long,
    totalTokens: Long,
): Double {
    val share = when {
        totalCost.isFinite() && totalCost > 0.0 -> partCost / totalCost
        totalTokens > 0L -> partTokens.toDouble() / totalTokens
        else -> 0.0
    }
    return if (share.isFinite()) share.coerceIn(0.0, 1.0) else 0.0
}

/**
 * Name to show for a usage row. The model id is the name; when a provider
 * reported none, the provider id is the only meaningful label left.
 */
fun modelDisplayName(modelId: String, providerId: String): String =
    modelId.trim().ifBlank { providerId.trim() }.ifBlank { "未知模型" }

/**
 * Indices of the dates to print under the chart: at most [maxLabels], always
 * including the first and the last day. A 90-day window gets four labels, not
 * ninety.
 */
fun axisLabelIndices(count: Int, maxLabels: Int = 4): List<Int> {
    if (count <= 0) return emptyList()
    if (maxLabels <= 1 || count <= maxLabels) return (0 until count).toList()
    return (0 until maxLabels)
        .map { index -> (index * (count - 1).toDouble() / (maxLabels - 1)).roundToInt() }
        .distinct()
}

/** `11月21日` — the chart's axis label. */
fun dateLabel(dayStartMillis: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = dayStartMillis }
    return String.format(
        Locale.CHINA,
        "%d月%d日",
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH),
    )
}

/**
 * Month-to-date spend, or `null` when the returned window does not contain the
 * first day of the current month.
 *
 * The service returns one entry per day, oldest first and ending today, so the
 * month is complete exactly when the window reaches back past its first day.
 * Returning `null` is deliberate: a 7-day window must not present 5 of 30 days
 * as "本月".
 */
fun monthToDateCny(days: List<UsageDay>): Double? {
    val today = days.lastOrNull() ?: return null
    val todayCal = Calendar.getInstance().apply { timeInMillis = today.dayStartMillis }
    val year = todayCal.get(Calendar.YEAR)
    val month = todayCal.get(Calendar.MONTH)

    val firstCal = Calendar.getInstance().apply { timeInMillis = days.first().dayStartMillis }
    val coversMonthStart = when {
        firstCal.get(Calendar.YEAR) < year -> true
        firstCal.get(Calendar.YEAR) > year -> false
        firstCal.get(Calendar.MONTH) < month -> true
        firstCal.get(Calendar.MONTH) > month -> false
        else -> firstCal.get(Calendar.DAY_OF_MONTH) == 1
    }
    if (!coversMonthStart) return null

    return days.filter { day ->
        val cal = Calendar.getInstance().apply { timeInMillis = day.dayStartMillis }
        cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) == month
    }.sumOf { it.totals.costCny }
}

private fun oneDecimal(value: Double): String =
    String.format(Locale.US, "%.1f", value).removeSuffix(".0")
