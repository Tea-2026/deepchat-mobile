package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.db.Schema
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaTest {

    @Test
    fun createBuildsEveryTable() {
        TestEnv().use { env ->
            val objects = env.db
                .query("SELECT type, name FROM sqlite_master", emptyList()) { row ->
                    (row.string(0) ?: "") to (row.string(1) ?: "")
                }
                .toSet()

            listOf(
                "conversations",
                "messages",
                "presets",
                "provider_profiles",
                "injections",
                "compactions",
                "folders",
                "tags",
                "conversation_tags",
                "attachments",
                "messages_fts",
            ).forEach { table ->
                assertTrue("missing table $table", ("table" to table) in objects)
            }

            listOf("messages_fts_ai", "messages_fts_bd", "messages_fts_bu", "messages_fts_au")
                .forEach { trigger ->
                    assertTrue("missing FTS trigger $trigger", ("trigger" to trigger) in objects)
                }

            listOf(
                "idx_messages_tree",
                "idx_messages_time",
                "idx_messages_created_at",
                "idx_conversations_updated",
            ).forEach { index ->
                assertTrue("missing index $index", ("index" to index) in objects)
            }
        }
    }

    @Test
    fun createStampsUserVersion() {
        TestEnv().use { env ->
            assertEquals(Schema.VERSION, env.db.userVersion())
        }
    }

    @Test
    fun schemaVersionIsThree() {
        TestEnv().use { env ->
            // Literal on purpose: bumping the DDL without writing a migration
            // must fail here as well as in the migration tests.
            assertEquals(3, Schema.VERSION)
            assertEquals(3, env.db.userVersion())
        }
    }

    @Test
    fun freshSchemaAllowsDraftAttachmentsAndKeepsTheCascade() = runBlocking {
        TestEnv().use { env ->
            val notNull = env.db
                .query("PRAGMA table_info(attachments)", emptyList()) { row ->
                    (row.string(1) ?: "") to row.int(3)
                }
                .toMap()
                .getValue("conversation_id")
            assertEquals(0, notNull)

            assertEquals(
                listOf("conversations" to "CASCADE"),
                env.db.query("PRAGMA foreign_key_list(attachments)", emptyList()) { row ->
                    (row.string(2) ?: "") to (row.string(6) ?: "")
                },
            )

            env.conversations.upsert(conversation("c1"))
            env.db.exec(
                """
                INSERT INTO attachments
                    (id, conversation_id, file_name, mime_type, size_bytes, local_path, sha256,
                     kind, created_at)
                VALUES ('a1', NULL, 'draft.txt', 'text/plain', 1, '/files/draft.txt', 'sha', 'TEXT', 1)
                """.trimIndent(),
            )
        }
    }

    @Test
    fun messagesTableHasColumnSetRequiredByModel() {
        TestEnv().use { env ->
            val columns = env.db
                .query("PRAGMA table_info(messages)", emptyList()) { it.string(1) ?: "" }
                .toSet()
            assertTrue(
                columns.containsAll(
                    listOf(
                        "id",
                        "conversation_id",
                        "parent_id",
                        "seq",
                        "role",
                        "status",
                        "parts_json",
                        "plain_text",
                        "created_at",
                        "edited_at",
                        "pinned",
                        "model_info_json",
                        "usage_json",
                        "finish_reason",
                        "error",
                        "usage_prompt_tokens",
                        "usage_cached_tokens",
                        "usage_completion_tokens",
                        "usage_cost_cny",
                        "model_id",
                        "provider_id",
                    ),
                ),
            )
        }
    }

    @Test
    fun usageColumnsAreNullableSoRowsWithoutUsageStayRepresentable() {
        TestEnv().use { env ->
            val notNull = env.db
                .query("PRAGMA table_info(messages)", emptyList()) { row ->
                    (row.string(1) ?: "") to row.int(3)
                }
                .toMap()

            listOf(
                "usage_prompt_tokens",
                "usage_cached_tokens",
                "usage_completion_tokens",
                "usage_cost_cny",
                "model_id",
                "provider_id",
            ).forEach { column ->
                assertEquals("$column must be nullable", 0, notNull[column])
            }
        }
    }

    @Test
    fun ftsIndexFollowsInsertUpdateAndDelete() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(message("m1", "c1", text = "hello sqlite world"))
            env.messages.append(message("m2", "c1", seq = 2, text = "unrelated body"))

            assertEquals(listOf("m1"), ftsMessageIds(env.db, "hello"))
            assertEquals(listOf("m1"), ftsMessageIds(env.db, "hello AND sqlite"))
            assertTrue(ftsMessageIds(env.db, "postgres").isEmpty())

            val updated = env.messages.get(MessageId("m1"))!!.copy(
                parts = listOf(MessagePart.Text("goodbye fts")),
            )
            env.messages.update(updated)
            assertTrue("old token must disappear", ftsMessageIds(env.db, "hello").isEmpty())
            assertEquals(listOf("m1"), ftsMessageIds(env.db, "goodbye"))

            env.messages.remove(setOf(MessageId("m1")))
            assertTrue("deleted row must leave no FTS entry", ftsMessageIds(env.db, "goodbye").isEmpty())
        }
    }

    @Test
    fun ftsIndexSurvivesRebuild() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(message("m1", "c1", text = "rebuild me"))
            Schema.rebuildFullTextIndex(env.db)
            assertEquals(listOf("m1"), ftsMessageIds(env.db, "rebuild"))
        }
    }

    @Test
    fun plainTextMirrorsTextParts() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(
                message(
                    "m1",
                    "c1",
                    parts = listOf(
                        MessagePart.Text("alpha "),
                        MessagePart.Reasoning("hidden thought", "sig"),
                        MessagePart.Text("beta"),
                    ),
                ),
            )
            val plainText = env.db
                .query("SELECT plain_text FROM messages WHERE id = 'm1'", emptyList()) { it.string(0) }
                .single()
            assertEquals("alpha beta", plainText)
        }
    }
}

internal fun ftsMessageIds(db: Db, term: String): List<String> = db.query(
    """
    SELECT m.id
    FROM messages_fts
    JOIN messages m ON m.rowid = messages_fts.rowid
    WHERE messages_fts MATCH ?
    ORDER BY m.id
    """.trimIndent(),
    listOf(term),
) { it.string(0) ?: "" }
