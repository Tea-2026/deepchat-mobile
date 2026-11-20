package com.deepseek.mobilechat.data.local.store

import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.data.local.bus.ChangeBus
import com.deepseek.mobilechat.data.local.codec.JsonCodecs
import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.db.DbException
import com.deepseek.mobilechat.data.local.db.Row
import com.deepseek.mobilechat.data.local.db.queryOne
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.ProviderProtocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** SQLite-backed [ProviderProfileStore]. Secrets themselves stay in SecretStore. */
class SqliteProviderProfileStore(
    private val db: Db,
    private val bus: ChangeBus,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ProviderProfileStore {

    override fun observeAll(): Flow<List<ProviderProfile>> = bus.observed(io) { loadAll() }

    override suspend fun get(id: ProviderId): ProviderProfile? = withContext(io) { loadOne(id) }

    override suspend fun upsert(profile: ProviderProfile) {
        withContext(io) {
            val args = listOf(
                profile.name,
                profile.protocol.name,
                profile.baseUrl,
                profile.secretRef,
                profile.defaultModelId?.raw,
                JsonCodecs.encodeHeaders(profile.extraHeaders),
                profile.createdAt,
            )
            db.transaction {
                if (db.rowExists(TABLE, profile.id.raw)) {
                    db.exec(
                        """
                        UPDATE provider_profiles
                        SET name = ?, protocol = ?, base_url = ?, secret_ref = ?,
                            default_model_id = ?, extra_headers_json = ?, created_at = ?
                        WHERE id = ?
                        """.trimIndent(),
                        args + profile.id.raw,
                    )
                } else {
                    db.exec(
                        """
                        INSERT INTO provider_profiles
                            (name, protocol, base_url, secret_ref, default_model_id,
                             extra_headers_json, created_at, id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        args + profile.id.raw,
                    )
                }
            }
            bus.notifyChanged()
        }
    }

    override suspend fun delete(id: ProviderId) {
        withContext(io) {
            db.exec("DELETE FROM provider_profiles WHERE id = ?", listOf(id.raw))
            bus.notifyChanged()
        }
    }

    private fun loadAll(): List<ProviderProfile> = db.query(
        "SELECT $COLUMNS FROM provider_profiles ORDER BY created_at ASC, id ASC",
        emptyList(),
        ::mapProfile,
    )

    private fun loadOne(id: ProviderId): ProviderProfile? =
        db.queryOne("SELECT $COLUMNS FROM provider_profiles WHERE id = ?", listOf(id.raw), ::mapProfile)

    private fun mapProfile(row: Row): ProviderProfile {
        val id = row.string(0) ?: throw DbException("provider profile row without id")
        return ProviderProfile(
            id = ProviderId(id),
            name = row.string(1) ?: "",
            protocol = try {
                ProviderProtocol.valueOf(row.string(2) ?: "")
            } catch (e: IllegalArgumentException) {
                throw DbException("unknown protocol for provider $id", e)
            },
            baseUrl = row.string(3) ?: "",
            secretRef = row.string(4) ?: "",
            defaultModelId = row.string(5)?.let(::ModelId),
            extraHeaders = try {
                JsonCodecs.decodeHeaders(row.string(6))
            } catch (e: IllegalArgumentException) {
                throw DbException("corrupt extra_headers_json for provider $id", e)
            },
            createdAt = row.long(7),
        )
    }

    private companion object {
        const val TABLE = "provider_profiles"

        const val COLUMNS =
            "id, name, protocol, base_url, secret_ref, default_model_id, extra_headers_json, created_at"
    }
}
