package com.deepseek.mobilechat.data.local.store

import com.deepseek.mobilechat.application.UsageAggregateStore
import com.deepseek.mobilechat.application.UsageByModel
import com.deepseek.mobilechat.application.UsageDay
import com.deepseek.mobilechat.application.UsageTotals
import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.db.Row
import com.deepseek.mobilechat.data.local.db.queryOne
import com.deepseek.mobilechat.domain.ConversationId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * SQL aggregation over the v3 denormalised usage columns
 * (`usage_prompt_tokens`, `usage_cached_tokens`, `usage_completion_tokens`,
 * `usage_cost_cny`, `model_id`, `provider_id`) instead of decoding
 * `usage_json` for every row of the window.
 *
 * Which rows "reported usage"
 * ---------------------------
 * The six columns are all NULL together for a message that never reported
 * usage, and populated together by `SqliteMessageStore` (or by the v2->v3
 * backfill). So a row counts as a message with usage iff at least one of the
 * four usage columns is non-NULL ([HAS_USAGE]). That definition drives
 * `messageCount` everywhere: it is the number of messages that actually
 * reported usage, not the number of messages in the window. A message with no
 * usage contributes nothing and is not counted; a message that reported
 * `prompt=0, completion=0, cost=null` is still counted (it did report).
 *
 * Money vs tokens
 * ---------------
 * `usage_cost_cny` is nullable even on a row that reported usage (a provider
 * can return tokens without a price). Such a row contributes its tokens but
 * 0.0 money: `SUM` ignores NULLs and every sum is wrapped in `COALESCE(...,
 * 0)`.
 *
 * Day bucketing strategy
 * ----------------------
 * `dailyTotals` buckets **in Kotlin**, from a `SELECT created_at, usage_*`
 * projection, and only because SQL cannot answer the question correctly:
 *
 *  - `created_at / 86400000` is midnight **UTC**. The device is the reference
 *    clock here, so on a UTC+8 phone a message sent at 01:00 local would land
 *    on the previous day and every "today" figure would be off by the offset.
 *  - SQLite has no timezone. Its `date(..., 'localtime')` modifier goes
 *    through the C runtime's process-wide TZ, which cannot be pinned per
 *    store, is resolved differently by Android's SQLite and by the
 *    sqlite-jdbc native library used in tests, and would still have to be
 *    turned back into the local-midnight epoch millis the port asks for —
 *    i.e. the same offset arithmetic, just hidden.
 *
 * So SQL does the cheap, exact part (window filter, projection, and the full
 * aggregation for [modelTotals] / [conversationTotals]), Kotlin assigns each
 * projected row to a local calendar day with [startOfDay], and the per-day
 * sums are folded into [UsageTotals]. Only the rows inside the requested
 * window are read, and only four numeric columns per row.
 *
 * [startOfDay] deliberately mirrors `DefaultUsageService.startOfDay` (offset
 * at the instant, then truncate). The service derives the window start and the
 * chart's day keys with that exact arithmetic, so a different definition here
 * would produce day keys the chart cannot line up. [zone] is injectable so
 * tests can pin a calendar instead of depending on the machine's TZ.
 *
 * Branches
 * --------
 * [conversationTotals] never consults `conversations.active_leaf_id`: an
 * abandoned branch is still spend that happened, so every message of the
 * conversation is summed.
 *
 * Messages without model attribution (`model_info_json` never written, so
 * `model_id`/`provider_id` are NULL) appear in the day totals and in
 * [conversationTotals] but cannot be grouped under a model, so [modelTotals]
 * excludes them. Their spend is therefore not double-counted, it is simply
 * unattributed.
 *
 * Window edges
 * ------------
 * Both windowed queries filter rows by `created_at >= sinceMillis`.
 * `DefaultUsageService` passes a local midnight, so every returned day starts
 * at or after the window; a mid-day `sinceMillis` keeps that (partial) day
 * rather than silently dropping the rows between the boundary and midnight.
 */
class SqliteUsageAggregateStore(
    private val db: Db,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val zone: TimeZone = TimeZone.getDefault(),
) : UsageAggregateStore {

    override suspend fun dailyTotals(sinceMillis: Long): List<UsageDay> = withContext(io) {
        val byDay = HashMap<Long, UsageTotals>()
        db.query(
            """
            SELECT created_at, usage_prompt_tokens, usage_cached_tokens,
                   usage_completion_tokens, usage_cost_cny
            FROM messages
            WHERE created_at >= ? AND $HAS_USAGE
            ORDER BY created_at ASC
            """.trimIndent(),
            listOf(sinceMillis),
        ) { row ->
            val dayStart = startOfDay(row.long(0))
            byDay[dayStart] = (byDay[dayStart] ?: UsageTotals()) + row.dayTotals()
        }
        byDay.entries
            .sortedBy { it.key }
            .map { (dayStart, totals) -> UsageDay(dayStartMillis = dayStart, totals = totals) }
    }

    override suspend fun modelTotals(sinceMillis: Long): List<UsageByModel> = withContext(io) {
        db.query(
            """
            SELECT provider_id, model_id,
                   COALESCE(SUM(usage_prompt_tokens), 0),
                   COALESCE(SUM(usage_cached_tokens), 0),
                   COALESCE(SUM(usage_completion_tokens), 0),
                   COALESCE(SUM(usage_cost_cny), 0),
                   COALESCE(SUM(CASE WHEN $HAS_USAGE THEN 1 ELSE 0 END), 0)
            FROM messages
            WHERE created_at >= ?
              AND provider_id IS NOT NULL
              AND model_id IS NOT NULL
              AND $HAS_USAGE
            GROUP BY provider_id, model_id
            ORDER BY SUM(usage_cost_cny) DESC, provider_id ASC, model_id ASC
            """.trimIndent(),
            listOf(sinceMillis),
        ) { row ->
            UsageByModel(
                providerId = row.string(0) ?: "",
                modelId = row.string(1) ?: "",
                totals = UsageTotals(
                    promptTokens = row.longOrZero(2),
                    cachedPromptTokens = row.longOrZero(3),
                    completionTokens = row.longOrZero(4),
                    costCny = row.double(5) ?: 0.0,
                    messageCount = row.longOrZero(6).toInt(),
                ),
            )
        }
    }

    override suspend fun conversationTotals(conversationId: ConversationId): UsageTotals =
        withContext(io) {
            db.queryOne(
                """
                SELECT
                    COALESCE(SUM(usage_prompt_tokens), 0),
                    COALESCE(SUM(usage_cached_tokens), 0),
                    COALESCE(SUM(usage_completion_tokens), 0),
                    COALESCE(SUM(usage_cost_cny), 0),
                    COALESCE(SUM(CASE WHEN $HAS_USAGE THEN 1 ELSE 0 END), 0)
                FROM messages
                WHERE conversation_id = ?
                """.trimIndent(),
                listOf(conversationId.raw),
            ) { row ->
                UsageTotals(
                    promptTokens = row.longOrZero(0),
                    cachedPromptTokens = row.longOrZero(1),
                    completionTokens = row.longOrZero(2),
                    costCny = row.double(3) ?: 0.0,
                    messageCount = row.longOrZero(4).toInt(),
                )
            } ?: UsageTotals()
        }

    /** Start of the local calendar day containing [atMillis] in [zone]. */
    private fun startOfDay(atMillis: Long): Long {
        val offset = zone.getOffset(atMillis)
        val local = atMillis + offset
        return local - Math.floorMod(local, MILLIS_PER_DAY) - offset
    }

    /** One message row from the `dailyTotals` projection. */
    private fun Row.dayTotals(): UsageTotals = UsageTotals(
        promptTokens = longOrZero(1),
        cachedPromptTokens = longOrZero(2),
        completionTokens = longOrZero(3),
        costCny = double(4) ?: 0.0,
        messageCount = 1,
    )

    private fun Row.longOrZero(i: Int): Long = if (isNull(i)) 0L else long(i)

    private companion object {
        const val MILLIS_PER_DAY: Long = 24L * 60 * 60 * 1000

        /**
         * "This message reported usage": at least one of the four usage
         * columns is set. Interpolated into both `WHERE` and `CASE` clauses;
         * it contains no user input.
         */
        const val HAS_USAGE: String =
            "(usage_prompt_tokens IS NOT NULL OR usage_cached_tokens IS NOT NULL " +
                "OR usage_completion_tokens IS NOT NULL OR usage_cost_cny IS NOT NULL)"
    }
}
