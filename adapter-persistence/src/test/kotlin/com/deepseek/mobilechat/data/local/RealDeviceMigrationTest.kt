package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.data.local.db.JdbcDb
import com.deepseek.mobilechat.data.local.db.Schema
import com.deepseek.mobilechat.data.local.log.LogReplayProbe
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the real v3 → v4 migration against a copy of the developer's phone
 * database, which is the only fixture that exercises the shapes the app actually
 * wrote: 4 conversations, dozens of messages, real parts JSON and real usage.
 *
 * Skipped when the fixture is absent (it lives in the private `_lead/` folder), so
 * the suite stays runnable on any machine. It is not a substitute for
 * `LegacyLogMigrationTest` — it is the acceptance gate for *this* device.
 */
class RealDeviceMigrationTest {

    private val fixture: File = File("../_lead/device-backup/mobilechat-v3-before-log.db")

    @Test
    fun `migrates a real v3 database without losing a single message`() {
        assumeTrue("device fixture not present", fixture.isFile)
        val work = File.createTempFile("device-v3", ".db").apply {
            deleteOnExit()
            fixture.copyTo(this, overwrite = true)
        }

        val before = JdbcDb.open(work)
        val conversations = before.query(
            "SELECT id, title, active_leaf_id FROM conversations",
            emptyList(),
        ) { row -> Triple(row.string(0), row.string(1), row.string(2)) }
        val messageCounts = conversations.associate { (id, _, _) ->
            id to before.query(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ?",
                listOf(id),
            ) { row -> row.int(0) }.single()
        }
        val versionBefore = before.query("PRAGMA user_version", emptyList()) { it.int(0) }.single()
        before.close()

        assertTrue("fixture must have conversations", conversations.isNotEmpty())
        assertTrue("fixture must be a v3 database", versionBefore >= 3)

        val db = JdbcDb.open(work)
        try {
            Schema.migrate(db, fromVersion = versionBefore)

            val versionAfter = db.query("PRAGMA user_version", emptyList()) { it.int(0) }.single()
            assertEquals(Schema.VERSION, versionAfter)

            val probe = LogReplayProbe(db)
            for ((id, title, leaf) in conversations) {
                val report = probe.verify(id ?: continue)
                assertEquals("$id: $title", messageCounts[id] ?: 0, report.legacyMessages)
                assertEquals("$id transcript size", report.legacyMessages, report.projectedMessages)
                assertEquals("$id leaf", leaf as String?, report.projectedLeaf as String?)
                assertTrue("$id discrepancies: ${report.discrepancies}", report.discrepancies.isEmpty())
                assertTrue("$id must have events", report.eventCount > 0)
            }
        } finally {
            db.close()
        }
    }
}
