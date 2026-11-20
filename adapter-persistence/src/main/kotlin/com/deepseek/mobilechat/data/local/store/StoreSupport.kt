package com.deepseek.mobilechat.data.local.store

import com.deepseek.mobilechat.data.local.bus.ChangeBus
import com.deepseek.mobilechat.data.local.db.Db
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * "Emit current value, then re-query on every [ChangeBus] signal."
 *
 * `conflate()` collapses write bursts: a slow collector always gets the newest
 * snapshot instead of replaying intermediate states.
 */
internal fun <T> ChangeBus.observed(dispatcher: CoroutineDispatcher, load: () -> T): Flow<T> = flow {
    emit(load())
    emitAll(changes.map { load() }.conflate())
}.flowOn(dispatcher)

/** Existence probe used to emulate UPSERT without `ON CONFLICT` (SQLite < 3.24 on API 26). */
internal fun Db.rowExists(table: String, id: String): Boolean =
    query("SELECT 1 FROM $table WHERE id = ? LIMIT 1", listOf(id)) { true }.isNotEmpty()

internal fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")

/** SQLite's default limit is 999 bound variables; stay well below it. */
internal const val IN_CLAUSE_CHUNK: Int = 400
