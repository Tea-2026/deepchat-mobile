package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.application.UsageTotals
import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.Usage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * End-to-end proofs for the v3 spend columns and their reader.
 *
 * Everything runs against the real SQLite engine (sqlite-jdbc) with the same
 * DDL and SQL the phone executes. The calendar is pinned to Asia/Shanghai so
 * the day boundaries under test do not depend on the machine running them; the
 * production store defaults to the device zone.
 */
class UsageAggregateStoreTest {

    private val beijing: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val beijingZone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun dayStart(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(beijingZone).toInstant().toEpochMilli()

    /** Local wall-clock instant in the pinned calendar (Shanghai has no DST). */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        dayStart(year, month, day) + hour * 3_600_000L + minute * 60_000L

    @Test
    fun freshSchemaRoundTripsUsageIntoTheAggregateColumnsAndBack() = runBlocking {
        TestEnv(zone = beijing).use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(
                message(
                    "m1",
                    "c1",
                    role = MessageRole.ASSISTANT,
                    createdAt = at(2026, 11, 21, 10, 0),
                ).copy(
                    modelInfo = modelInfo(providerId = "prov-a", modelId = "model-a"),
                    usage = Usage(
                        promptTokens = 100,
                        completionTokens = 40,
                        reasoningTokens = 10,
                        cachedPromptTokens = 60,
                        cost = 0.0125,
                    ),
                ),
            )

            // The store wrote the copies in the same INSERT as the JSON.
            val raw = env.db.rawUsageColumns("m1")
            assertEquals(100L, raw.prompt)
            assertEquals(60L, raw.cached)
            assertEquals(40L, raw.completion)
            assertEquals(0.0125, raw.cost!!, 0.0)
            assertEquals("model-a", raw.modelId)
            assertEquals("prov-a", raw.providerId)

            // ... and the aggregate reads them without touching the JSON.
            val days = env.usage.dailyTotals(0)
            assertEquals(1, days.size)
            assertEquals(dayStart(2026, 11, 21), days[0].dayStartMillis)
            assertTotals(
                UsageTotals(
                    promptTokens = 100,
                    cachedPromptTokens = 60,
                    completionTokens = 40,
                    costCny = 0.0125,
                    messageCount = 1,
                ),
                days[0].totals,
            )

            val byModel = env.usage.modelTotals(0)
            assertEquals(1, byModel.size)
            assertEquals("prov-a", byModel[0].providerId)
            assertEquals("model-a", byModel[0].modelId)
            assertTotals(days[0].totals, byModel[0].totals)

            assertTotals(days[0].totals, env.usage.conversationTotals(ConversationId("c1")))
        }
    }

    @Test
    fun dailyTotalsSplitsAtLocalMidnightNotUtcMidnight() = runBlocking {
        TestEnv(zone = beijing).use { env ->
            env.conversations.upsert(conversation("c1"))
            val beforeMidnight = at(2026, 11, 20, 23, 50)
            val afterMidnight = at(2026, 11, 21, 0, 10)

            // The fixture only discriminates if both rows fall in the *same* UTC
            // day: a `created_at / 86400000` bucket would merge them.
            assertEquals(beforeMidnight / 86_400_000L, afterMidnight / 86_400_000L)

            env.messages.append(
                message("m1", "c1", createdAt = beforeMidnight).copy(
                    usage = Usage(promptTokens = 10, completionTokens = 1, cost = 0.1),
                ),
            )
            env.messages.append(
                message("m2", "c1", createdAt = afterMidnight, seq = 2).copy(
                    usage = Usage(promptTokens = 20, completionTokens = 2, cost = 0.2),
                ),
            )

            val days = env.usage.dailyTotals(0)
            assertEquals(
                listOf(dayStart(2026, 11, 20), dayStart(2026, 11, 21)),
                days.map { it.dayStartMillis },
            )
            assertTotals(UsageTotals(10, 0, 1, 0.1, 1), days[0].totals, "Nov 20")
            assertTotals(UsageTotals(20, 0, 2, 0.2, 1), days[1].totals, "Nov 21")

            // The window starts at local midnight; that drops the 23:50 row,
            // while a UTC bucket would have kept it.
            val todayOnly = env.usage.dailyTotals(dayStart(2026, 11, 21))
            assertEquals(listOf(dayStart(2026, 11, 21)), todayOnly.map { it.dayStartMillis })
            assertTotals(UsageTotals(20, 0, 2, 0.2, 1), todayOnly.single().totals)
        }
    }

    @Test
    fun modelTotalsGroupsByProviderAndModelAndOrdersByCostDescending() = runBlocking {
        TestEnv(zone = beijing).use { env ->
            env.conversations.upsert(conversation("c1"))
            val created = at(2026, 11, 21, 12, 0)

            suspend fun appendUsage(
                id: String,
                providerId: String?,
                modelId: String?,
                prompt: Int,
                cached: Int,
                completion: Int,
                cost: Double?,
            ) {
                env.messages.append(
                    message(id, "c1", role = MessageRole.ASSISTANT, createdAt = created).copy(
                        modelInfo = if (providerId != null && modelId != null) {
                            modelInfo(providerId = providerId, modelId = modelId)
                        } else {
                            null
                        },
                        usage = Usage(
                            promptTokens = prompt,
                            cachedPromptTokens = cached,
                            completionTokens = completion,
                            cost = cost,
                        ),
                    ),
                )
            }

            // Costliest single row: 2.0.
            appendUsage("pricey", "prov-a", "model-pricey", 100, 10, 5, 2.0)
            // Two rows under one group must be summed into one entry: 0.1 + 0.1.
            appendUsage("cheap-1", "prov-a", "model-cheap", 30, 0, 3, 0.1)
            appendUsage("cheap-2", "prov-a", "model-cheap", 40, 5, 4, 0.1)
            // Same model id under a different provider is a separate group.
            appendUsage("mid", "prov-b", "model-cheap", 50, 0, 6, 0.5)
            // Tokens without a price: money stays 0.0, tokens still count.
            appendUsage("unpriced", "prov-b", "model-free", 7, 1, 2, null)
            // Usage with no model attribution: appears in daily totals, but it
            // cannot be grouped under a model.
            appendUsage("no-model", null, null, 1_000, 0, 100, 0.0)
            // Model info without usage: no spend reported, so no group at all.
            env.messages.append(
                message("no-usage", "c1", role = MessageRole.ASSISTANT, createdAt = created).copy(
                    modelInfo = modelInfo(providerId = "prov-c", modelId = "model-ghost"),
                ),
            )

            val models = env.usage.modelTotals(0)
            assertEquals(
                listOf(
                    "prov-a/model-pricey",
                    "prov-b/model-cheap",
                    "prov-a/model-cheap",
                    "prov-b/model-free",
                ),
                models.map { "${it.providerId}/${it.modelId}" },
            )

            assertTotals(UsageTotals(100, 10, 5, 2.0, 1), models[0].totals, "pricey")
            assertTotals(UsageTotals(50, 0, 6, 0.5, 1), models[1].totals, "mid")
            assertTotals(UsageTotals(70, 5, 7, 0.2, 2), models[2].totals, "cheap")
            // Unpriced tokens: 7 prompt + 1 cached + 2 completion, cost 0.0.
            assertTotals(UsageTotals(7, 1, 2, 0.0, 1), models[3].totals, "free")

            // Daily totals do include the unattributed message, so the two
            // views agree on money and differ only in attribution.
            val day = env.usage.dailyTotals(0).single()
            assertTotals(
                UsageTotals(
                    promptTokens = 100 + 30 + 40 + 50 + 7 + 1_000,
                    cachedPromptTokens = 10 + 0 + 5 + 0 + 1,
                    completionTokens = 5 + 3 + 4 + 6 + 2 + 100,
                    costCny = 2.0 + 0.1 + 0.1 + 0.5 + 0.0,
                    messageCount = 6,
                ),
                day.totals,
            )
        }
    }

    @Test
    fun conversationTotalsIncludesAnAbandonedBranch() = runBlocking {
        TestEnv(zone = beijing).use { env ->
            // Active leaf is m2; m3 hangs off the same parent and is abandoned.
            env.conversations.upsert(conversation("c1", activeLeafId = "m2"))
            env.conversations.upsert(conversation("c2", activeLeafId = "other"))

            env.messages.append(message("m0", "c1", seq = 1, createdAt = 1_000L))
            env.messages.append(
                message(
                    "m1",
                    "c1",
                    parentId = "m0",
                    seq = 1,
                    role = MessageRole.ASSISTANT,
                    createdAt = 2_000L,
                ).copy(usage = Usage(promptTokens = 10, completionTokens = 5, cost = 0.1)),
            )
            env.messages.append(
                message(
                    "m2",
                    "c1",
                    parentId = "m1",
                    seq = 1,
                    role = MessageRole.ASSISTANT,
                    createdAt = 3_000L,
                ).copy(usage = Usage(promptTokens = 20, completionTokens = 7, cost = 0.2)),
            )
            env.messages.append(
                message(
                    "m3",
                    "c1",
                    parentId = "m1",
                    seq = 2,
                    role = MessageRole.ASSISTANT,
                    createdAt = 4_000L,
                ).copy(usage = Usage(promptTokens = 30, completionTokens = 9, cost = 0.3)),
            )
            env.messages.append(
                message("other", "c2", seq = 1, role = MessageRole.ASSISTANT, createdAt = 5_000L)
                    .copy(usage = Usage(promptTokens = 999, completionTokens = 999, cost = 9.99)),
            )

            assertEquals(
                "m2",
                env.conversations.get(ConversationId("c1"))!!.activeLeafId!!.raw,
            )

            // All three usage rows of c1 are summed: the abandoned m3 is real
            // spend and must not be filtered by active_leaf_id.
            assertTotals(
                UsageTotals(
                    promptTokens = 60,
                    cachedPromptTokens = 0,
                    completionTokens = 21,
                    costCny = 0.6,
                    messageCount = 3,
                ),
                env.usage.conversationTotals(ConversationId("c1")),
            )
            assertTotals(
                UsageTotals(999, 0, 999, 9.99, 1),
                env.usage.conversationTotals(ConversationId("c2")),
                "c2",
            )
            // Unknown conversation is a zero total, not an error.
            assertTotals(UsageTotals(), env.usage.conversationTotals(ConversationId("missing")))
        }
    }

    @Test
    fun rowsWithoutUsageAreNotCountedAndNullCostContributesNoMoney() = runBlocking {
        TestEnv(zone = beijing).use { env ->
            env.conversations.upsert(conversation("c1"))
            val created = at(2026, 11, 21, 9, 0)

            // Never reported usage: all six columns NULL.
            env.messages.append(message("m-none", "c1", createdAt = created))

            // Reported usage that happens to be all zeroes, no price.
            env.messages.append(
                message("m-zero", "c1", createdAt = created).copy(usage = Usage()),
            )
            // Tokens but no price.
            env.messages.append(
                message("m-unpriced", "c1", createdAt = created).copy(
                    usage = Usage(promptTokens = 50, completionTokens = 5),
                ),
            )
            // An explicit zero price must not be confused with "no price".
            env.messages.append(
                message("m-zerocost", "c1", createdAt = created).copy(
                    usage = Usage(promptTokens = 1, cost = 0.0),
                ),
            )

            val noUsage = env.db.rawUsageColumns("m-none")
            assertNull(noUsage.prompt)
            assertNull(noUsage.cached)
            assertNull(noUsage.completion)
            assertNull(noUsage.cost)
            assertNull(noUsage.modelId)
            assertNull(noUsage.providerId)

            val unpriced = env.db.rawUsageColumns("m-unpriced")
            assertEquals(50L, unpriced.prompt)
            assertEquals(5L, unpriced.completion)
            assertNull("a missing price is stored as NULL, not 0.0", unpriced.cost)

            val expected = UsageTotals(
                promptTokens = 51,
                cachedPromptTokens = 0,
                completionTokens = 5,
                costCny = 0.0,
                messageCount = 3,
            )
            assertTotals(expected, env.usage.conversationTotals(ConversationId("c1")))

            val day = env.usage.dailyTotals(0).single()
            assertEquals(dayStart(2026, 11, 21), day.dayStartMillis)
            assertTotals(expected, day.totals)

            // Nothing has model attribution, so there is no per-model group —
            // in particular no "(null, null)" pseudo-model.
            assertTrue(env.usage.modelTotals(0).isEmpty())
        }
    }

    @Test
    fun dailyTotalsWindowExcludesOlderRowsButKeepsSameDayZeroCostRows() = runBlocking {
        TestEnv(zone = beijing).use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(
                message("old", "c1", createdAt = dayStart(2026, 11, 18)).copy(
                    modelInfo = modelInfo(providerId = "prov-old", modelId = "old-model"),
                    usage = Usage(promptTokens = 5, cost = 5.0),
                ),
            )
            env.messages.append(
                message("zero-only", "c1", createdAt = dayStart(2026, 11, 21)).copy(
                    usage = Usage(),
                ),
            )

            val days = env.usage.dailyTotals(dayStart(2026, 11, 21))
            assertEquals(1, days.size)
            // A reported-but-zero message still puts the day on the map.
            assertTotals(UsageTotals(messageCount = 1), days.single().totals)

            assertTrue("older rows are outside the window", env.usage.modelTotals(dayStart(2026, 11, 21)).isEmpty())
        }
    }

    private fun assertTotals(expected: UsageTotals, actual: UsageTotals, label: String = "totals") {
        assertEquals("$label promptTokens", expected.promptTokens, actual.promptTokens)
        assertEquals("$label cachedPromptTokens", expected.cachedPromptTokens, actual.cachedPromptTokens)
        assertEquals("$label completionTokens", expected.completionTokens, actual.completionTokens)
        assertEquals("$label costCny", expected.costCny, actual.costCny, 1e-9)
        assertEquals("$label messageCount", expected.messageCount, actual.messageCount)
    }

    private fun Db.rawUsageColumns(id: String): RawUsage = query(
        """
        SELECT usage_prompt_tokens, usage_cached_tokens, usage_completion_tokens,
               usage_cost_cny, model_id, provider_id
        FROM messages
        WHERE id = ?
        """.trimIndent(),
        listOf(id),
    ) { row ->
        RawUsage(
            prompt = if (row.isNull(0)) null else row.long(0),
            cached = if (row.isNull(1)) null else row.long(1),
            completion = if (row.isNull(2)) null else row.long(2),
            cost = row.double(3),
            modelId = row.string(4),
            providerId = row.string(5),
        )
    }.single()

    private data class RawUsage(
        val prompt: Long?,
        val cached: Long?,
        val completion: Long?,
        val cost: Double?,
        val modelId: String?,
        val providerId: String?,
    )
}
