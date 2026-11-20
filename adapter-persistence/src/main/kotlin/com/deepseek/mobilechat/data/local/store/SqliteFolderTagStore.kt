package com.deepseek.mobilechat.data.local.store

import com.deepseek.mobilechat.application.FolderTagStore
import com.deepseek.mobilechat.data.local.bus.ChangeBus
import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.db.DbException
import com.deepseek.mobilechat.data.local.db.Row
import com.deepseek.mobilechat.domain.Folder
import com.deepseek.mobilechat.domain.FolderId
import com.deepseek.mobilechat.domain.Tag
import com.deepseek.mobilechat.domain.TagId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * SQLite-backed [FolderTagStore].
 *
 * Deleting a folder detaches its conversations explicitly (`folder_id = NULL`)
 * before removing the row, so the behaviour does not depend on the foreign-key
 * pragma. Tag deletion cascades through `conversation_tags`.
 */
class SqliteFolderTagStore(
    private val db: Db,
    private val bus: ChangeBus,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FolderTagStore {

    override fun observeFolders(): Flow<List<Folder>> = bus.observed(io) { loadFolders() }

    override fun observeTags(): Flow<List<Tag>> = bus.observed(io) { loadTags() }

    override suspend fun upsertFolder(folder: Folder) {
        withContext(io) {
            db.transaction {
                if (db.rowExists(FOLDER_TABLE, folder.id.raw)) {
                    db.exec(
                        "UPDATE folders SET name = ?, sort_order = ? WHERE id = ?",
                        listOf(folder.name, folder.order, folder.id.raw),
                    )
                } else {
                    db.exec(
                        "INSERT INTO folders (name, sort_order, id) VALUES (?, ?, ?)",
                        listOf(folder.name, folder.order, folder.id.raw),
                    )
                }
            }
            bus.notifyChanged()
        }
    }

    override suspend fun deleteFolder(id: FolderId) {
        withContext(io) {
            db.transaction {
                db.exec("UPDATE conversations SET folder_id = NULL WHERE folder_id = ?", listOf(id.raw))
                db.exec("DELETE FROM folders WHERE id = ?", listOf(id.raw))
            }
            bus.notifyChanged()
        }
    }

    override suspend fun upsertTag(tag: Tag) {
        withContext(io) {
            db.transaction {
                if (db.rowExists(TAG_TABLE, tag.id.raw)) {
                    db.exec(
                        "UPDATE tags SET name = ?, color = ? WHERE id = ?",
                        listOf(tag.name, tag.color, tag.id.raw),
                    )
                } else {
                    db.exec(
                        "INSERT INTO tags (name, color, id) VALUES (?, ?, ?)",
                        listOf(tag.name, tag.color, tag.id.raw),
                    )
                }
            }
            bus.notifyChanged()
        }
    }

    override suspend fun deleteTag(id: TagId) {
        withContext(io) {
            db.transaction {
                db.exec("DELETE FROM conversation_tags WHERE tag_id = ?", listOf(id.raw))
                db.exec("DELETE FROM tags WHERE id = ?", listOf(id.raw))
            }
            bus.notifyChanged()
        }
    }

    private fun loadFolders(): List<Folder> = db.query(
        "SELECT id, name, sort_order FROM folders ORDER BY sort_order ASC, name ASC, id ASC",
        emptyList(),
    ) { row -> mapFolder(row) }

    private fun loadTags(): List<Tag> = db.query(
        "SELECT id, name, color FROM tags ORDER BY name ASC, id ASC",
        emptyList(),
    ) { row -> mapTag(row) }

    private fun mapFolder(row: Row): Folder = Folder(
        id = FolderId(row.string(0) ?: throw DbException("folder row without id")),
        name = row.string(1) ?: "",
        order = row.int(2),
    )

    private fun mapTag(row: Row): Tag = Tag(
        id = TagId(row.string(0) ?: throw DbException("tag row without id")),
        name = row.string(1) ?: "",
        color = row.long(2),
    )

    private companion object {
        const val FOLDER_TABLE = "folders"
        const val TAG_TABLE = "tags"
    }
}
