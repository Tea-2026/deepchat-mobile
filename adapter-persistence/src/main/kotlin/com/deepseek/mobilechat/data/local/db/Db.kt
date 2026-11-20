package com.deepseek.mobilechat.data.local.db

/**
 * Extremely thin synchronous SQLite seam.
 *
 * Everything in this module talks to SQLite through [Db] only, never through
 * `android.database`. That is what lets the hand-written DAO logic run against
 * a real SQLite engine (sqlite-jdbc) in plain JVM unit tests:
 * production uses [AndroidDb] over a `SQLiteOpenHelper`, tests use a JDBC
 * implementation of the same interface.
 *
 * All methods are blocking. Suspend store methods hop onto an IO dispatcher
 * before touching this interface.
 */
interface Db {
    fun exec(sql: String, args: List<Any?> = emptyList())

    fun <T> query(sql: String, args: List<Any?>, mapper: (Row) -> T): List<T>

    /**
     * Runs [block] inside a transaction. Implementations must tolerate nesting:
     * a nested call joins the outer transaction and only the outermost one
     * commits or rolls back.
     */
    fun <T> transaction(block: () -> T): T

    fun userVersion(): Int

    fun setUserVersion(v: Int)
}

/** Wraps storage failures so upper layers never see `SQLiteException`/`SQLException`. */
class DbException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** First row of [sql], or `null`. */
fun <T> Db.queryOne(sql: String, args: List<Any?> = emptyList(), mapper: (Row) -> T): T? =
    query(sql, args, mapper).firstOrNull()
