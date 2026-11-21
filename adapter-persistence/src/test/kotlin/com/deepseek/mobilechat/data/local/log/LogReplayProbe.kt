package com.deepseek.mobilechat.data.local.log

import com.deepseek.mobilechat.data.local.bus.ChangeBus
import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.store.SqliteConversationLogStore
import com.deepseek.mobilechat.data.local.store.SqliteMessageStore
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.log.TranscriptProjector
import kotlinx.coroutines.runBlocking

/**
 * Replays one migrated conversation and compares it with the legacy rows.
 *
 * This is the acceptance gate asked for in `docs/architecture-v2-log.md` §13.8:
 * after the upgrade, folding the new event log must reproduce exactly the message
 * list the old tables held. Test-only.
 */
class LogReplayProbe(private val db: Db) {

    data class Report(
        val conversationId: String,
        val eventCount: Int,
        val legacyMessages: Int,
        val projectedMessages: Int,
        val projectedLeaf: String?,
        val discrepancies: List<String>,
    )

    fun verify(rawId: String): Report = runBlocking {
        val id = ConversationId(rawId)
        val legacy = SqliteMessageStore(db, ChangeBus()).list(id)
        val log = SqliteConversationLogStore(db, ChangeBus()).log(id)
        val transcript = TranscriptProjector.project(log)
        Report(
            conversationId = rawId,
            eventCount = log.events.size,
            legacyMessages = legacy.size,
            projectedMessages = transcript.messages.size,
            projectedLeaf = transcript.activeLeafId?.raw,
            discrepancies = verifyMigration(id, legacy, log),
        )
    }
}
