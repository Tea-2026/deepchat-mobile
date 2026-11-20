package com.deepseek.mobilechat.data.local.db

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Production [Db] over an Android `SQLiteDatabase`.
 *
 * Bind arguments are stored as `Object[]`; `PRAGMA user_version` is used for
 * schema versioning so the same version semantics apply on device and in the
 * JVM tests.
 */
class AndroidDb(private val database: SQLiteDatabase) : Db {

    constructor(helper: SQLiteOpenHelper) : this(helper.writableDatabase)

    override fun exec(sql: String, args: List<Any?>) {
        try {
            database.execSQL(sql, args.toTypedArray())
        } catch (e: RuntimeException) {
            throw DbException("exec failed: ${preview(sql)}", e)
        }
    }

    override fun <T> query(sql: String, args: List<Any?>, mapper: (Row) -> T): List<T> {
        val selectionArgs = Array<String?>(args.size) { index -> selectionArg(args[index]) }
        try {
            database.rawQuery(sql, selectionArgs).use { cursor ->
                val result = ArrayList<T>(cursor.count)
                while (cursor.moveToNext()) {
                    result.add(mapper(CursorRow(cursor)))
                }
                return result
            }
        } catch (e: RuntimeException) {
            throw DbException("query failed: ${preview(sql)}", e)
        }
    }

    override fun <T> transaction(block: () -> T): T {
        // Android supports nested transactions, but joining the outer one keeps
        // FTS triggers and cascades in a single commit and matches the JDBC impl.
        if (database.inTransaction()) return block()
        database.beginTransaction()
        try {
            val result = block()
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }

    override fun userVersion(): Int =
        query("PRAGMA user_version", emptyList()) { it.int(0) }.firstOrNull() ?: 0

    override fun setUserVersion(v: Int) {
        require(v >= 0) { "user_version must be >= 0, was $v" }
        database.execSQL("PRAGMA user_version = $v")
    }

    private fun selectionArg(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Int, is Long, is Double, is Float, is Short, is Byte -> value.toString()
        else -> throw IllegalArgumentException(
            "query() only binds text/number/null on Android, got ${value::class.java.name}",
        )
    }

    private class CursorRow(private val cursor: Cursor) : Row {
        override fun string(i: Int): String? = if (cursor.isNull(i)) null else cursor.getString(i)

        override fun long(i: Int): Long = cursor.getLong(i)

        override fun int(i: Int): Int = cursor.getInt(i)

        override fun double(i: Int): Double? = if (cursor.isNull(i)) null else cursor.getDouble(i)

        override fun isNull(i: Int): Boolean = cursor.isNull(i)
    }

    private companion object {
        fun preview(sql: String): String = sql.replace('\n', ' ').trim().take(160)
    }
}

/**
 * `SQLiteOpenHelper` wiring the hand-written [Schema] into Android's lifecycle.
 *
 * `onCreate` builds the current schema; `onUpgrade` delegates to the
 * `user_version`-driven migration list in [Schema]. Foreign keys are enabled per
 * connection before any transaction starts so `ON DELETE` actions behave exactly
 * like the JDBC tests expect.
 */
class AppDatabaseHelper(
    context: Context,
    name: String = Schema.DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, name, null, Schema.VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        Schema.create(AndroidDb(db))
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Schema.migrate(AndroidDb(db), oldVersion, newVersion)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException(
            "database downgrade $oldVersion -> $newVersion is not supported; reinstall or export first",
        )
    }

    /** Convenience for the composition root. */
    fun database(): AndroidDb = AndroidDb(this)
}
