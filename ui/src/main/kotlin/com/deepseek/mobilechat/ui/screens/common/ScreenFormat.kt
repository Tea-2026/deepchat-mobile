package com.deepseek.mobilechat.ui.screens.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.deepseek.mobilechat.application.ClockPort
import java.util.Calendar
import java.util.Locale

/**
 * Time and number formatting shared by the secondary screens.
 *
 * Kept apart from [ScreenChrome] so the composable chrome file stays focused and
 * these pure functions can be unit-tested without a Compose runtime.
 */

/**
 * Human relative time: 刚刚 / N 分钟前 / 今天 HH:mm / 昨天 HH:mm / M月d日.
 *
 * Day boundaries use the device's wall clock, while `now` comes from the
 * caller (the injected [ClockPort]), so tests and previews can pin time.
 */
fun relativeTime(nowMillis: Long, atMillis: Long): String {
    val diff = (nowMillis - atMillis).coerceAtLeast(0L)
    if (diff < 60_000L) return "刚刚"
    if (diff < 3_600_000L) return "${diff / 60_000L} 分钟前"

    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val then = Calendar.getInstance().apply { timeInMillis = atMillis }
    val clock = String.format(Locale.CHINA, "%02d:%02d", then.get(Calendar.HOUR_OF_DAY), then.get(Calendar.MINUTE))

    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "今天 $clock"

    val yesterday = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        add(Calendar.DAY_OF_YEAR, -1)
    }
    val isYesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "昨天 $clock"

    return if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
        String.format(Locale.CHINA, "%d月%d日", then.get(Calendar.MONTH) + 1, then.get(Calendar.DAY_OF_MONTH))
    } else {
        String.format(
            Locale.CHINA,
            "%d年%d月%d日",
            then.get(Calendar.YEAR),
            then.get(Calendar.MONTH) + 1,
            then.get(Calendar.DAY_OF_MONTH),
        )
    }
}

/** Full date+time, used where "when exactly" matters (injections, exports). */
fun absoluteTime(atMillis: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = atMillis }
    return String.format(
        Locale.CHINA,
        "%d月%d日 %02d:%02d",
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH),
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE),
    )
}

/** 1234 -> "1.2k", so token labels fit on one line. */
fun compactCount(count: Int): String {
    if (count < 1_000) return count.toString()
    if (count < 1_000_000) return String.format(Locale.US, "%.1fk", count / 1_000f).replace(".0k", "k")
    return String.format(Locale.US, "%.1fm", count / 1_000_000f).replace(".0m", "m")
}

/**
 * A clock that re-reads every [intervalMillis], so relative timestamps age in
 * place instead of freezing at first composition. The time source stays the
 * injected [ClockPort] — no `System.currentTimeMillis()` in screen code.
 */
@Composable
fun rememberNow(clock: ClockPort, intervalMillis: Long = 30_000L): Long {
    var now by remember(clock) { mutableStateOf(clock.nowMillis()) }
    LaunchedEffect(clock) {
        while (true) {
            kotlinx.coroutines.delay(intervalMillis)
            now = clock.nowMillis()
        }
    }
    return now
}
