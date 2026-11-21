package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.application.ImportedFile
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.data.local.codec.JsonCodecs
import com.deepseek.mobilechat.data.local.codec.PartsCodec
import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.db.JdbcDb
import com.deepseek.mobilechat.data.local.db.Schema
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.AttachmentKind
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.ConversationOverrides
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelInfo
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.Usage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Schema versioning proofs. `user_version` is the only authority: data written
 * under v1 must survive the upgrade run, and the version must be persisted
 * across database reopen.
 *
 * The v1 databases below are built from [V1_CREATE_STATEMENTS], a frozen copy of
 * the DDL the v1 build shipped, instead of from the current `Schema` — otherwise
 * the tests would only prove that a v2 database can "migrate" itself. The only
 * intended difference from v2 is `attachments.conversation_id TEXT NOT NULL`;
 * the rest is byte-for-byte the v1 schema.
 */
class MigrationTest {

    @Test
    fun migratingFromZeroBuildsTheCurrentSchema() {
        JdbcDb.inMemory().use { db ->
            assertEquals(0, db.userVersion())
            Schema.migrate(db, fromVersion = 0)
            assertEquals(Schema.VERSION, db.userVersion())
            val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'", emptyList()) {
                it.string(0) ?: ""
            }.toSet()
            assertTrue("messages" in tables)
            assertTrue("messages_fts" in tables)
            db.exec("INSERT INTO conversations (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)", listOf("c", "t", 1L, 1L))
        }
    }

    @Test
    fun v1PresetWithMediumThinkingLevelBecomesHigh() = runBlocking {
        TestEnv(schema = ::createV1Schema).use { env ->
            env.db.exec(
                "INSERT INTO presets (id, name, thinking_level) VALUES (?, ?, ?)",
                listOf("p-medium", "Medium", "MEDIUM"),
            )
            env.db.exec(
                "INSERT INTO presets (id, name, thinking_level) VALUES (?, ?, ?)",
                listOf("p-high", "High", "HIGH"),
            )
            env.db.exec(
                "INSERT INTO presets (id, name, thinking_level) VALUES (?, ?, ?)",
                listOf("p-off", "Off", "OFF"),
            )

            // This is the real-device failure: v1 rows make `valueOf` throw.
            assertTrue(
                "a v1 MEDIUM preset must be unreadable before the upgrade",
                runCatching { env.presets.get(PresetId("p-medium")) }.isFailure,
            )

            Schema.migrate(env.db, fromVersion = 1, toVersion = 2)

            assertEquals(2, env.db.userVersion())
            assertEquals(ThinkingLevel.HIGH, env.presets.get(PresetId("p-medium"))!!.thinkingLevel)
            // Only MEDIUM is folded; the other levels are untouched.
            assertEquals(ThinkingLevel.HIGH, env.presets.get(PresetId("p-high"))!!.thinkingLevel)
            assertEquals(ThinkingLevel.OFF, env.presets.get(PresetId("p-off"))!!.thinkingLevel)
            assertEquals(
                listOf("HIGH"),
                env.db.query("SELECT thinking_level FROM presets WHERE id = 'p-medium'", emptyList()) { it.string(0) },
            )
        }
    }

    @Test
    fun v1AttachmentsSurviveTheUpgradeAndAcceptNullConversationId() = runBlocking {
        TestEnv(schema = ::createV1Schema).use { env ->
            env.conversations.upsert(conversation("c1"))
            insertV1Attachment(env.db, id = "a1", conversationId = "c1")

            Schema.migrate(env.db, fromVersion = 1, toVersion = 2)

            assertEquals(2, env.db.userVersion())

            // No row was lost and every column survived the rebuild.
            val stored = env.attachments.get(AttachmentId("a1"))!!
            assertEquals("a1.png", stored.fileName)
            assertEquals("image/png", stored.mimeType)
            assertEquals(3L, stored.sizeBytes)
            assertEquals("/files/a1.png", stored.localPath)
            assertEquals("sha-a1", stored.sha256)
            assertEquals(AttachmentKind.IMAGE, stored.kind)
            assertEquals(16, stored.width)
            assertEquals(16, stored.height)
            assertEquals(1_000L, stored.createdAt)
            assertEquals(listOf(AttachmentId("a1")), env.attachments.observe(ConversationId("c1")).first().map { it.id })

            // `conversation_id` is now nullable (PRAGMA table_info.notnull == 0) ...
            val notNull = env.db
                .query("PRAGMA table_info(attachments)", emptyList()) { row ->
                    (row.string(1) ?: "") to row.int(3)
                }
                .toMap()
                .getValue("conversation_id")
            assertEquals(0, notNull)

            // ... and the index v1 had was recreated on the rebuilt table.
            assertEquals(
                1,
                env.db.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = 'idx_attachments_conversation'",
                    emptyList(),
                ) { true }.size,
            )

            // The actual bug scenario: a draft attachment (conversationId == null)
            // saves successfully, then gets claimed by the real conversation.
            val draft = env.attachments.save(
                null,
                ImportedFile("draft.txt", "text/plain", "hi".toByteArray(), 2L),
            ).unwrap()
            env.attachments.linkToConversation(listOf(draft.id), ConversationId("c1"))
            assertEquals(
                listOf(AttachmentId("a1"), draft.id),
                env.attachments.observe(ConversationId("c1")).first().map { it.id },
            )

            // The rebuilt FK still cascades: deleting the conversation removes
            // the v1 row and the linked draft, while a new NULL row is unaffected.
            env.conversations.delete(ConversationId("c1"))
            assertNull(env.attachments.get(AttachmentId("a1")))
            assertNull(env.attachments.get(draft.id))
            val orphan = env.attachments.save(
                null,
                ImportedFile("still-here.txt", "text/plain", "ok".toByteArray(), 2L),
            ).unwrap()
            assertEquals(orphan, env.attachments.get(orphan.id))
        }
    }

    @Test
    fun v1ThinkingLevelInsideJsonColumnsIsRewrittenToo() = runBlocking {
        TestEnv(schema = ::createV1Schema).use { env ->
            env.conversations.upsert(conversation("c1"))
            insertPreV3Message(env.db, id = "m1", conversationId = "c1", createdAt = 1_000L)

            // The enum name is persisted in JSON as well; build the fixture with
            // the production encoders so the bytes match what v1 wrote, then swap
            // the level back to the removed MEDIUM.
            val v1Overrides = JsonCodecs.encodeOverrides(
                ConversationOverrides(thinkingLevel = ThinkingLevel.HIGH),
            ).replace("\"thinkingLevel\":\"HIGH\"", "\"thinkingLevel\":\"MEDIUM\"")
            val v1ModelInfo = JsonCodecs.encodeModelInfo(
                ModelInfo(
                    ref = ModelRef(ProviderId("prov"), ModelId("model")),
                    params = SamplingParams(),
                    thinkingLevel = ThinkingLevel.HIGH,
                ),
            ).replace("\"thinkingLevel\":\"HIGH\"", "\"thinkingLevel\":\"MEDIUM\"")
            assertTrue("\"thinkingLevel\":\"MEDIUM\"" in v1Overrides)
            assertTrue("\"thinkingLevel\":\"MEDIUM\"" in v1ModelInfo)

            env.db.exec("UPDATE conversations SET overrides_json = ? WHERE id = 'c1'", listOf(v1Overrides))
            env.db.exec("UPDATE messages SET model_info_json = ? WHERE id = 'm1'", listOf(v1ModelInfo))

            // Both rows are unreadable before the upgrade.
            assertTrue(runCatching { env.conversations.get(ConversationId("c1")) }.isFailure)
            assertTrue(runCatching { env.messages.get(MessageId("m1")) }.isFailure)

            Schema.migrate(env.db, fromVersion = 1, toVersion = 2)

            assertEquals(
                ThinkingLevel.HIGH,
                env.conversations.get(ConversationId("c1"))!!.overrides.thinkingLevel,
            )
            assertEquals(
                ThinkingLevel.HIGH,
                env.messages.get(MessageId("m1"))!!.modelInfo!!.thinkingLevel,
            )
            assertEquals(
                0,
                env.db.query(
                    "SELECT 1 FROM conversations WHERE overrides_json LIKE '%MEDIUM%'",
                    emptyList(),
                ) { true }.size,
            )
            assertEquals(
                0,
                env.db.query(
                    "SELECT 1 FROM messages WHERE model_info_json LIKE '%MEDIUM%'",
                    emptyList(),
                ) { true }.size,
            )
        }
    }

    @Test
    fun upgradeRunsInsideTheHelperTransactionWithForeignKeysOn() = runBlocking {
        TestEnv(schema = ::createV1Schema).use { env ->
            env.conversations.upsert(conversation("c1"))
            insertV1Attachment(env.db, id = "a1", conversationId = "c1")

            // Android's SQLiteOpenHelper already wraps `onUpgrade` in a
            // transaction. `PRAGMA foreign_keys` is a no-op in there, so the
            // rebuild must be safe with foreign keys enabled — this is the exact
            // configuration the phone runs.
            env.db.transaction { Schema.migrate(env.db, fromVersion = 1, toVersion = 2) }

            assertEquals(2, env.db.userVersion())
            assertEquals(1, env.db.query("SELECT 1 FROM attachments", emptyList()) { true }.size)
            assertTrue(
                "foreign keys must stay enforced after the table rebuild",
                runCatching {
                    env.db.exec("UPDATE attachments SET conversation_id = 'missing' WHERE id = 'a1'")
                }.isFailure,
            )
            env.conversations.delete(ConversationId("c1"))
            assertEquals(0, env.db.query("SELECT 1 FROM attachments", emptyList()) { true }.size)
        }
    }

    @Test
    fun v1DataSurvivesTheUpgradeStep() = runBlocking {
        TestEnv(schema = ::createV1Schema).use { env ->
            val conversation = conversation("c1")
            env.conversations.upsert(conversation)
            insertPreV3Message(
                env.db,
                id = "m1",
                conversationId = "c1",
                createdAt = 1_000L,
                parts = listOf(MessagePart.Text("survive the migration")),
            )
            env.presets.upsert(
                com.deepseek.mobilechat.domain.Preset(
                    id = PresetId("p1"),
                    name = "Preset",
                    systemPrompt = "be terse",
                ),
            )
            insertV1Attachment(env.db, id = "a1", conversationId = "c1")

            // Upgrade the way the app does: to whatever the current schema is.
            // A row set written by v1 has to survive every step, otherwise the
            // upgrade from the oldest supported install is broken.
            Schema.migrate(env.db, fromVersion = 1)

            assertEquals(Schema.VERSION, env.db.userVersion())
            assertEquals("survive the migration", env.messages.get(MessageId("m1"))!!.text)
            assertEquals(conversation.title, env.conversations.get(ConversationId("c1"))!!.title)
            assertEquals(1, env.presets.observeAll().first().size)
            assertEquals(listOf("m1"), ftsMessageIds(env.db, "migration"))
            assertEquals(
                listOf(AttachmentId("a1")),
                env.attachments.observe(ConversationId("c1")).first().map { it.id },
            )

            // Tables were not re-created: writes (and FTS triggers) still work
            // after the upgrade.
            env.messages.append(message("m2", "c1", seq = 2, text = "still writable"))
            assertEquals(2, env.messages.list(ConversationId("c1")).size)
            assertEquals(listOf("m2"), ftsMessageIds(env.db, "writable"))
        }
    }

    @Test
    fun v2MessagesGetTheUsageColumnsBackfilledFromTheirJson() = runBlocking {
        TestEnv(schema = ::createV2Schema).use { env ->
            env.conversations.upsert(conversation("c1"))

            val currentUsage = JsonCodecs.encodeUsage(
                Usage(
                    promptTokens = 10,
                    completionTokens = 5,
                    reasoningTokens = 2,
                    cachedPromptTokens = 4,
                    cost = 0.5,
                ),
            )
            // A row written by the 0.2.0 build: the estimate lives under
            // "costUsd" and must land in usage_cost_cny the same way
            // decodeUsage reads it back (as-is, no re-conversion).
            val legacyUsage =
                """{"promptTokens":7,"completionTokens":3,"reasoningTokens":1,""" +
                    """"cachedPromptTokens":2,"costUsd":0.25}"""
            val modelJson = JsonCodecs.encodeModelInfo(
                ModelInfo(
                    ref = ModelRef(ProviderId("prov-a"), ModelId("model-a")),
                    params = SamplingParams(),
                    thinkingLevel = ThinkingLevel.HIGH,
                ),
            )

            insertPreV3Message(
                env.db,
                id = "m-current",
                conversationId = "c1",
                createdAt = 1_000L,
                usageJson = currentUsage,
                modelInfoJson = modelJson,
            )
            insertPreV3Message(
                env.db,
                id = "m-legacy",
                conversationId = "c1",
                createdAt = 2_000L,
                usageJson = legacyUsage,
                modelInfoJson = null,
            )
            insertPreV3Message(
                env.db,
                id = "m-model-only",
                conversationId = "c1",
                createdAt = 3_000L,
                usageJson = null,
                modelInfoJson = modelJson,
            )
            insertPreV3Message(
                env.db,
                id = "m-bare",
                conversationId = "c1",
                createdAt = 4_000L,
            )

            Schema.migrate(env.db, fromVersion = 2, toVersion = 3)

            assertEquals(3, env.db.userVersion())

            assertUsageColumns(
                env.db,
                "m-current",
                UsageColumns(10, 4, 5, 0.5, "model-a", "prov-a"),
            )
            assertUsageColumns(
                env.db,
                "m-legacy",
                UsageColumns(7, 2, 3, 0.25, null, null),
            )
            assertUsageColumns(
                env.db,
                "m-model-only",
                UsageColumns(null, null, null, null, "model-a", "prov-a"),
            )
            assertUsageColumns(env.db, "m-bare", UsageColumns(null, null, null, null, null, null))

            // The JSON columns remain the source of truth for the domain
            // object; the migration did not rewrite them.
            val stored = env.messages.get(MessageId("m-current"))!!
            assertEquals(Usage(10, 5, 2, 4, 0.5), stored.usage)
            assertEquals(ModelId("model-a"), stored.modelInfo!!.ref.modelId)

            // And the aggregate store reads the backfilled columns.
            val totals = env.usage.conversationTotals(ConversationId("c1"))
            assertEquals(17L, totals.promptTokens)
            assertEquals(6L, totals.cachedPromptTokens)
            assertEquals(8L, totals.completionTokens)
            assertEquals(0.75, totals.costCny, 1e-9)
            assertEquals(2, totals.messageCount)
        }
    }

    @Test
    fun v1DatabaseUpgradesStraightToV3IncludingTheBackfill() = runBlocking {
        TestEnv(schema = ::createV1Schema).use { env ->
            env.conversations.upsert(conversation("c1"))

            // v1 wrote MEDIUM into model_info_json; the v1->v2 step has to
            // rewrite it before the v2->v3 step can decode the model columns.
            val v1ModelJson = JsonCodecs.encodeModelInfo(
                ModelInfo(
                    ref = ModelRef(ProviderId("prov-a"), ModelId("model-a")),
                    params = SamplingParams(),
                    thinkingLevel = ThinkingLevel.HIGH,
                ),
            ).replace("\"thinkingLevel\":\"HIGH\"", "\"thinkingLevel\":\"MEDIUM\"")
            assertTrue("\"thinkingLevel\":\"MEDIUM\"" in v1ModelJson)

            insertPreV3Message(
                env.db,
                id = "m1",
                conversationId = "c1",
                createdAt = 1_000L,
                usageJson = JsonCodecs.encodeUsage(
                    Usage(promptTokens = 3, completionTokens = 2, cost = 0.03),
                ),
                modelInfoJson = v1ModelJson,
            )

            Schema.migrate(env.db, fromVersion = 1)

            assertEquals(3, env.db.userVersion())
            assertUsageColumns(
                env.db,
                "m1",
                UsageColumns(3, 0, 2, 0.03, "model-a", "prov-a"),
            )
            assertEquals(ThinkingLevel.HIGH, env.messages.get(MessageId("m1"))!!.modelInfo!!.thinkingLevel)
            assertEquals(
                0,
                env.db.query("SELECT 1 FROM messages WHERE model_info_json LIKE '%MEDIUM%'", emptyList()) { true }.size,
            )
        }
    }

    @Test
    fun v3StepIsSafeToRunAgainWithoutDuplicatingColumns() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(
                message("m1", "c1").copy(
                    usage = Usage(promptTokens = 9, completionTokens = 4, cost = 0.09),
                ),
            )

            // Simulate a hand-rolled downgrade of the version stamp; the
            // column-existence guard must keep the step from failing on
            // ADD COLUMN and the backfill must be a no-op.
            env.db.setUserVersion(2)
            Schema.migrate(env.db, fromVersion = 2, toVersion = 3)

            assertEquals(3, env.db.userVersion())
            val usageColumnCount = env.db
                .query("PRAGMA table_info(messages)", emptyList()) { it.string(1) }
                .count { it == "usage_prompt_tokens" }
            assertEquals(1, usageColumnCount)
            assertUsageColumns(env.db, "m1", UsageColumns(9, 0, 4, 0.09, null, null))
        }
    }

    @Test
    fun migrationIsNoOpAtCurrentVersion() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(message("m1", "c1"))

            Schema.migrate(env.db, fromVersion = Schema.VERSION, toVersion = Schema.VERSION)

            assertEquals(Schema.VERSION, env.db.userVersion())
            assertEquals(1, env.messages.list(ConversationId("c1")).size)
        }
    }

    @Test
    fun downgradeIsRejected() {
        TestEnv().use { env ->
            assertThrows(IllegalArgumentException::class.java) {
                Schema.migrate(env.db, fromVersion = 2, toVersion = 1)
            }
        }
    }

    @Test
    fun userVersionAndDataSurviveReopeningTheDatabaseFile() = runBlocking {
        val directory = Files.createTempDirectory("mobilechat-migration").toFile()
        val file = directory.resolve("mobilechat.db")
        try {
            JdbcDb.open(file).use { db ->
                Schema.create(db)
                val env = TestEnvShim(db)
                env.conversations.upsert(conversation("c1"))
                env.messages.append(message("m1", "c1", text = "persisted across reopen"))
            }
            JdbcDb.open(file).use { db ->
                assertEquals(Schema.VERSION, db.userVersion())
                val rows = db.query("SELECT plain_text FROM messages", emptyList()) { it.string(0) }
                assertEquals(listOf("persisted across reopen"), rows)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aV1FileOnDiskUpgradesToV2AndSurvivesReopen() = runBlocking {
        val directory = Files.createTempDirectory("mobilechat-v1-upgrade").toFile()
        val file = directory.resolve("mobilechat.db")
        try {
            JdbcDb.open(file).use { db ->
                createV1Schema(db)
                db.exec(
                    "INSERT INTO conversations (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    listOf("c1", "Mobile", 1L, 1L),
                )
                db.exec("INSERT INTO presets (id, name, thinking_level) VALUES ('p1', 'Old', 'MEDIUM')")
                insertV1Attachment(db, id = "a1", conversationId = "c1")
                // Same nesting as SQLiteOpenHelper.onUpgrade.
                db.transaction { Schema.migrate(db, fromVersion = 1, toVersion = 2) }
            }
            JdbcDb.open(file).use { db ->
                assertEquals(2, db.userVersion())
                assertEquals(
                    listOf("HIGH"),
                    db.query("SELECT thinking_level FROM presets WHERE id = 'p1'", emptyList()) { it.string(0) },
                )
                assertEquals(
                    listOf("c1"),
                    db.query("SELECT conversation_id FROM attachments", emptyList()) { it.string(0) },
                )
                // The relaxed constraint is part of the persisted file, not just
                // of the connection that ran the migration.
                db.exec(
                    "INSERT INTO attachments " +
                        "(id, conversation_id, file_name, mime_type, size_bytes, local_path, sha256, kind, created_at) " +
                        "VALUES ('a2', NULL, 'draft.txt', 'text/plain', 1, '/files/draft.txt', 'sha-a2', 'TEXT', 2)",
                )
                assertEquals(
                    listOf("a1", "a2"),
                    db.query("SELECT id FROM attachments ORDER BY id", emptyList()) { it.string(0) },
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Minimal store set over an existing [JdbcDb] (used by the reopen test). */
    private class TestEnvShim(private val db: JdbcDb) {
        private val bus = com.deepseek.mobilechat.data.local.bus.ChangeBus()
        private val io = kotlinx.coroutines.Dispatchers.Unconfined
        val conversations = com.deepseek.mobilechat.data.local.store.SqliteConversationStore(db, bus, io)
        val messages = com.deepseek.mobilechat.data.local.store.SqliteMessageStore(db, bus, io)
    }
}

/** Inserts a v1 attachment row; v1 required a non-null conversation. */
private fun insertV1Attachment(db: Db, id: String, conversationId: String) {
    db.exec(
        """
        INSERT INTO attachments
            (id, conversation_id, file_name, mime_type, size_bytes, local_path, sha256,
             kind, extracted_text, width, height, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        listOf(
            id,
            conversationId,
            "$id.png",
            "image/png",
            3L,
            "/files/$id.png",
            "sha-$id",
            "IMAGE",
            null,
            16,
            16,
            1_000L,
        ),
    )
}

private fun <T> Outcome<T>.unwrap(): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> throw AssertionError("expected Ok but got $error")
}

private fun createV1Schema(db: Db) {
    V1_CREATE_STATEMENTS.forEach(db::exec)
    db.setUserVersion(1)
}

/**
 * A real v2 database: the frozen v1 DDL (the only v2 DDL change is
 * `attachments.conversation_id`, which [Schema.migrate] applies) upgraded by
 * the shipped v1->v2 step. Building it through the migration instead of
 * copy-pasting another frozen DDL keeps the fixture honest about what a v2
 * install actually contains.
 */
private fun createV2Schema(db: Db) {
    createV1Schema(db)
    Schema.migrate(db, fromVersion = 1, toVersion = 2)
}

/**
 * Inserts a message using only the pre-v3 columns, exactly like the v2 store
 * did. The current store would write the v3 columns, which do not exist yet.
 */
private fun insertPreV3Message(
    db: Db,
    id: String,
    conversationId: String,
    createdAt: Long,
    usageJson: String? = null,
    modelInfoJson: String? = null,
    parts: List<MessagePart> = emptyList(),
) {
    db.exec(
        """
        INSERT INTO messages
            (id, conversation_id, parent_id, seq, role, status, parts_json, plain_text,
             created_at, edited_at, pinned, model_info_json, usage_json, finish_reason, error)
        VALUES (?, ?, NULL, 1, 'ASSISTANT', 'COMPLETE', ?, ?, ?, NULL, 0, ?, ?, NULL, NULL)
        """.trimIndent(),
        listOf(
            id,
            conversationId,
            PartsCodec.encode(parts),
            PartsCodec.plainText(parts),
            createdAt,
            modelInfoJson,
            usageJson,
        ),
    )
}

private data class UsageColumns(
    val prompt: Long?,
    val cached: Long?,
    val completion: Long?,
    val cost: Double?,
    val modelId: String?,
    val providerId: String?,
)

private fun assertUsageColumns(db: Db, id: String, expected: UsageColumns) {
    val actual = db.query(
        """
        SELECT usage_prompt_tokens, usage_cached_tokens, usage_completion_tokens,
               usage_cost_cny, model_id, provider_id
        FROM messages
        WHERE id = ?
        """.trimIndent(),
        listOf(id),
    ) { row ->
        UsageColumns(
            prompt = if (row.isNull(0)) null else row.long(0),
            cached = if (row.isNull(1)) null else row.long(1),
            completion = if (row.isNull(2)) null else row.long(2),
            cost = row.double(3),
            modelId = row.string(4),
            providerId = row.string(5),
        )
    }.single()
    assertEquals("$id usage columns", expected, actual)
}

/**
 * Frozen copy of the v1 DDL. `attachments.conversation_id` is NOT NULL
 * here — that is the one shape v2 changes, and the whole point of the
 * migration test is to prove that a database created by v1 upgrades.
 */
private val V1_CREATE_STATEMENTS: List<String> = listOf(
    """
    CREATE TABLE IF NOT EXISTS folders (
        id TEXT PRIMARY KEY NOT NULL,
        name TEXT NOT NULL,
        sort_order INTEGER NOT NULL DEFAULT 0
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS tags (
        id TEXT PRIMARY KEY NOT NULL,
        name TEXT NOT NULL,
        color INTEGER NOT NULL DEFAULT 4285231744
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS conversations (
        id TEXT PRIMARY KEY NOT NULL,
        title TEXT NOT NULL DEFAULT '',
        active_leaf_id TEXT,
        folder_id TEXT REFERENCES folders(id) ON DELETE SET NULL,
        overrides_json TEXT NOT NULL DEFAULT '{}',
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        pinned INTEGER NOT NULL DEFAULT 0
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS messages (
        id TEXT PRIMARY KEY NOT NULL,
        conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
        parent_id TEXT REFERENCES messages(id) ON DELETE CASCADE,
        seq INTEGER NOT NULL DEFAULT 0,
        role TEXT NOT NULL,
        status TEXT NOT NULL,
        parts_json TEXT NOT NULL DEFAULT '{"v":1,"parts":[]}',
        plain_text TEXT NOT NULL DEFAULT '',
        created_at INTEGER NOT NULL,
        edited_at INTEGER,
        pinned INTEGER NOT NULL DEFAULT 0,
        model_info_json TEXT,
        usage_json TEXT,
        finish_reason TEXT,
        error TEXT
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS presets (
        id TEXT PRIMARY KEY NOT NULL,
        name TEXT NOT NULL,
        system_prompt TEXT NOT NULL DEFAULT '',
        model_ref_json TEXT,
        params_json TEXT NOT NULL DEFAULT '{}',
        thinking_level TEXT NOT NULL DEFAULT 'OFF',
        context_strategy_id TEXT NOT NULL DEFAULT 'confirm-compaction',
        is_default INTEGER NOT NULL DEFAULT 0
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS provider_profiles (
        id TEXT PRIMARY KEY NOT NULL,
        name TEXT NOT NULL,
        protocol TEXT NOT NULL,
        base_url TEXT NOT NULL,
        secret_ref TEXT NOT NULL,
        default_model_id TEXT,
        extra_headers_json TEXT NOT NULL DEFAULT '{}',
        created_at INTEGER NOT NULL
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS injections (
        id TEXT PRIMARY KEY NOT NULL,
        conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
        anchor_message_id TEXT,
        scope TEXT NOT NULL,
        position INTEGER NOT NULL DEFAULT 0,
        content TEXT NOT NULL,
        created_at INTEGER NOT NULL
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS compactions (
        id TEXT PRIMARY KEY NOT NULL,
        conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
        from_message_id TEXT NOT NULL,
        to_message_id TEXT NOT NULL,
        summary TEXT NOT NULL,
        kind TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        undone_at INTEGER
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS conversation_tags (
        conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
        tag_id TEXT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
        PRIMARY KEY (conversation_id, tag_id)
    )
    """.trimIndent(),
    // v1 shape: a draft conversation had to exist before its attachments.
    """
    CREATE TABLE IF NOT EXISTS attachments (
        id TEXT PRIMARY KEY NOT NULL,
        conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
        file_name TEXT NOT NULL,
        mime_type TEXT NOT NULL,
        size_bytes INTEGER NOT NULL,
        local_path TEXT NOT NULL,
        sha256 TEXT NOT NULL,
        kind TEXT NOT NULL,
        extracted_text TEXT,
        width INTEGER,
        height INTEGER,
        created_at INTEGER NOT NULL
    )
    """.trimIndent(),
    "CREATE INDEX IF NOT EXISTS idx_conversations_updated ON conversations(updated_at DESC, created_at DESC)",
    "CREATE INDEX IF NOT EXISTS idx_conversations_folder ON conversations(folder_id)",
    "CREATE INDEX IF NOT EXISTS idx_messages_tree ON messages(conversation_id, parent_id, seq)",
    "CREATE INDEX IF NOT EXISTS idx_messages_time ON messages(conversation_id, created_at)",
    "CREATE INDEX IF NOT EXISTS idx_injections_conversation ON injections(conversation_id, position)",
    "CREATE INDEX IF NOT EXISTS idx_compactions_conversation ON compactions(conversation_id, created_at)",
    "CREATE INDEX IF NOT EXISTS idx_attachments_conversation ON attachments(conversation_id, created_at)",
    "CREATE INDEX IF NOT EXISTS idx_conversation_tags_tag ON conversation_tags(tag_id)",
    """
    CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts4(
        plain_text,
        content=messages,
        tokenize=unicode61
    )
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS messages_fts_ai AFTER INSERT ON messages BEGIN
        INSERT INTO messages_fts(docid, plain_text) VALUES (new.rowid, new.plain_text);
    END
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS messages_fts_bd BEFORE DELETE ON messages BEGIN
        DELETE FROM messages_fts WHERE docid = old.rowid;
    END
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS messages_fts_bu BEFORE UPDATE OF plain_text ON messages
    WHEN old.plain_text IS NOT new.plain_text
    BEGIN
        DELETE FROM messages_fts WHERE docid = old.rowid;
    END
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS messages_fts_au AFTER UPDATE OF plain_text ON messages
    WHEN old.plain_text IS NOT new.plain_text
    BEGIN
        INSERT INTO messages_fts(docid, plain_text) VALUES (new.rowid, new.plain_text);
    END
    """.trimIndent(),
)
