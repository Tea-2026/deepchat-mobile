package com.deepseek.mobilechat.data.local.store

import com.deepseek.mobilechat.application.PresetStore
import com.deepseek.mobilechat.data.local.bus.ChangeBus
import com.deepseek.mobilechat.data.local.codec.JsonCodecs
import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.db.DbException
import com.deepseek.mobilechat.data.local.db.Row
import com.deepseek.mobilechat.data.local.db.queryOne
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ThinkingLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * SQLite-backed [PresetStore].
 *
 * `is_default` is kept unique at the store boundary: upserting a default preset
 * clears the flag on every other row in the same transaction, so "the global
 * default preset" (architecture rule 9) can never have two answers.
 */
class SqlitePresetStore(
    private val db: Db,
    private val bus: ChangeBus,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PresetStore {

    override fun observeAll(): Flow<List<Preset>> = bus.observed(io) { loadAll() }

    override suspend fun get(id: PresetId): Preset? = withContext(io) { loadOne(id) }

    override suspend fun upsert(preset: Preset) {
        withContext(io) {
            db.transaction {
                if (preset.isDefault) {
                    db.exec("UPDATE presets SET is_default = 0 WHERE is_default = 1")
                }
                if (db.rowExists(TABLE, preset.id.raw)) {
                    db.exec(
                        """
                        UPDATE presets
                        SET name = ?, system_prompt = ?, model_ref_json = ?, params_json = ?,
                            thinking_level = ?, context_strategy_id = ?, is_default = ?
                        WHERE id = ?
                        """.trimIndent(),
                        argumentList(preset) + preset.id.raw,
                    )
                } else {
                    db.exec(
                        """
                        INSERT INTO presets
                            (name, system_prompt, model_ref_json, params_json, thinking_level,
                             context_strategy_id, is_default, id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        argumentList(preset) + preset.id.raw,
                    )
                }
            }
            bus.notifyChanged()
        }
    }

    override suspend fun delete(id: PresetId) {
        withContext(io) {
            db.exec("DELETE FROM presets WHERE id = ?", listOf(id.raw))
            bus.notifyChanged()
        }
    }

    private fun loadAll(): List<Preset> = db.query(
        "SELECT $COLUMNS FROM presets ORDER BY is_default DESC, name ASC, id ASC",
        emptyList(),
        ::mapPreset,
    )

    private fun loadOne(id: PresetId): Preset? =
        db.queryOne("SELECT $COLUMNS FROM presets WHERE id = ?", listOf(id.raw), ::mapPreset)

    private fun mapPreset(row: Row): Preset {
        val id = row.string(0) ?: throw DbException("preset row without id")
        return Preset(
            id = PresetId(id),
            name = row.string(1) ?: "",
            systemPrompt = row.string(2) ?: "",
            modelRef = try {
                JsonCodecs.decodeModelRef(row.string(3))
            } catch (e: IllegalArgumentException) {
                throw DbException("corrupt model_ref_json for preset $id", e)
            },
            params = try {
                JsonCodecs.decodeParams(row.string(4))
            } catch (e: IllegalArgumentException) {
                throw DbException("corrupt params_json for preset $id", e)
            },
            thinkingLevel = try {
                ThinkingLevel.valueOf(row.string(5) ?: ThinkingLevel.OFF.name)
            } catch (e: IllegalArgumentException) {
                throw DbException("unknown thinking_level for preset $id", e)
            },
            contextStrategyId = row.string(6) ?: "",
            isDefault = row.int(7) != 0,
        )
    }

    /** Order matches the `UPDATE`/`INSERT` statements above (id goes last). */
    private fun argumentList(preset: Preset): List<Any?> = listOf(
        preset.name,
        preset.systemPrompt,
        preset.modelRef?.let(JsonCodecs::encodeModelRef),
        JsonCodecs.encodeParams(preset.params),
        preset.thinkingLevel.name,
        preset.contextStrategyId,
        if (preset.isDefault) 1 else 0,
    )

    private companion object {
        const val TABLE = "presets"

        const val COLUMNS =
            "id, name, system_prompt, model_ref_json, params_json, thinking_level, context_strategy_id, is_default"
    }
}
