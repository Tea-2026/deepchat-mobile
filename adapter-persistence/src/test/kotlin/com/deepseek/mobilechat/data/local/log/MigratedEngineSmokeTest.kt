package com.deepseek.mobilechat.data.local.log

import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.data.local.TestEnv
import com.deepseek.mobilechat.data.local.db.JdbcDb
import com.deepseek.mobilechat.data.local.db.Schema
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.log.SurfaceFolder
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The whole migration story end to end: take the developer's real v3 database,
 * upgrade it, then drive the production engine against the migrated log.
 *
 * Migrating is only half the claim — the other half is that the migrated
 * conversation is still usable: it folds, it renders, and a new turn can be
 * appended on top of it without violating a single surface rule.
 *
 * Skipped when the private fixture is absent.
 */
class MigratedEngineSmokeTest {

    private val fixture = File("../_lead/device-backup/mobilechat-v3-before-log.db")
    private var env: TestEnv? = null

    @After
    fun tearDown() {
        env?.close()
    }

    @Test
    fun `a migrated conversation accepts a new turn`() = runBlocking {
        assumeTrue("device fixture not present", fixture.isFile)
        val work = File.createTempFile("device-v3-smoke", ".db").apply {
            deleteOnExit()
            fixture.copyTo(this, overwrite = true)
        }

        val raw = JdbcDb.open(work)
        val fromVersion = raw.query("PRAGMA user_version", emptyList()) { it.int(0) }.single()
        Schema.migrate(raw, fromVersion = fromVersion)
        val target = raw.query(
            "SELECT id FROM conversations ORDER BY (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = conversations.id) DESC LIMIT 1",
            emptyList(),
        ) { it.string(0) }.single()
        raw.close()

        val testEnv = TestEnv(db = JdbcDb.open(work), schema = {})
        env = testEnv
        val conversationId = ConversationId(target!!)
        val before = testEnv.log.log(conversationId)
        assertTrue("the migrated conversation must have events", before.events.isNotEmpty())

        val migratedMessages = com.deepseek.mobilechat.domain.log.TranscriptProjector
            .project(before)
            .messages
        assertTrue("the migrated conversation must have messages", migratedMessages.isNotEmpty())

        val completion = ScriptedCompletion(listOf("迁移后"))
        val gateway = engineGateway(testEnv, completion)

        val sent = gateway.send(conversationId, "迁移后发一条")
        assertTrue("send into a migrated conversation must succeed: $sent", sent is Outcome.Ok)

        val snapshot = withTimeout(20_000) {
            gateway.snapshots(conversationId).first { candidate ->
                candidate.overlay.streaming == null &&
                    candidate.messages.any { it.role == MessageRole.ASSISTANT && it.text == "迁移后" }
            }
        }

        // Everything the migration produced is still there, plus the new turn.
        assertTrue(
            "migrated messages must survive the new turn",
            snapshot.messages.containsAll(migratedMessages),
        )
        assertEquals("迁移后发一条", snapshot.messages.first { it.text == "迁移后发一条" }.text)
        assertNull(snapshot.overlay.busy)
        assertNull(snapshot.overlay.streaming)

        // The grown log still folds — the new events cannot break the old surface.
        val after = testEnv.log.log(conversationId)
        assertTrue("the log grew", after.events.size > before.events.size)
        val surface = SurfaceFolder.fold(after)
        assertTrue("the surface still folds", surface.nodes.isNotEmpty())
        assertNotNull("the transcript still projects", com.deepseek.mobilechat.domain.log.TranscriptProjector.project(after).activeLeafId)
    }
}
