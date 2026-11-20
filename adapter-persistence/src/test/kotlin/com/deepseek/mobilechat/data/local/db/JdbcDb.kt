package com.deepseek.mobilechat.data.local.db

import java.io.Closeable
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/**
 * Test implementation of [Db] backed by sqlite-jdbc.
 *
 * This is the whole point of the [Db] abstraction: the *same* DDL, triggers and
 * DAO SQL that ship on Android are executed here against a real SQLite engine,
 * in plain JVM unit tests (no Robolectric, no emulator).
 */
class JdbcDb private constructor(private val connection: Connection) : Db, Closeable {

    private var transactionDepth: Int = 0

    override fun exec(sql: String, args: List<Any?>) {
        try {
            connection.prepareStatement(sql).use { statement ->
                bind(statement, args)
                statement.execute()
            }
        } catch (e: SQLException) {
            throw DbException("exec failed: ${preview(sql)}", e)
        }
    }

    override fun <T> query(sql: String, args: List<Any?>, mapper: (Row) -> T): List<T> {
        try {
            connection.prepareStatement(sql).use { statement ->
                bind(statement, args)
                statement.executeQuery().use { resultSet ->
                    val rows = ArrayList<T>()
                    while (resultSet.next()) {
                        rows.add(mapper(JdbcRow(resultSet)))
                    }
                    return rows
                }
            }
        } catch (e: SQLException) {
            throw DbException("query failed: ${preview(sql)}", e)
        }
    }

    override fun <T> transaction(block: () -> T): T {
        if (transactionDepth > 0) return block()
        connection.autoCommit = false
        transactionDepth += 1
        try {
            val result = block()
            connection.commit()
            return result
        } catch (t: Throwable) {
            runCatching { connection.rollback() }
            throw t
        } finally {
            transactionDepth -= 1
            connection.autoCommit = true
        }
    }

    override fun userVersion(): Int =
        query("PRAGMA user_version", emptyList()) { it.int(0) }.firstOrNull() ?: 0

    override fun setUserVersion(v: Int) {
        require(v >= 0) { "user_version must be >= 0, was $v" }
        exec("PRAGMA user_version = $v")
    }

    override fun close() {
        connection.close()
    }

    private fun bind(statement: PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { index, value ->
            val position = index + 1
            when (value) {
                null -> statement.setObject(position, null)
                is String -> statement.setString(position, value)
                is Int -> statement.setInt(position, value)
                is Long -> statement.setLong(position, value)
                is Double -> statement.setDouble(position, value)
                is Float -> statement.setFloat(position, value)
                is Boolean -> statement.setInt(position, if (value) 1 else 0)
                is ByteArray -> statement.setBytes(position, value)
                else -> statement.setObject(position, value)
            }
        }
    }

    private class JdbcRow(private val resultSet: ResultSet) : Row {
        override fun string(i: Int): String? = resultSet.getString(i + 1)

        override fun long(i: Int): Long = resultSet.getLong(i + 1)

        override fun int(i: Int): Int = resultSet.getInt(i + 1)

        override fun double(i: Int): Double? {
            val value = resultSet.getDouble(i + 1)
            return if (resultSet.wasNull()) null else value
        }

        override fun isNull(i: Int): Boolean {
            resultSet.getObject(i + 1)
            return resultSet.wasNull()
        }
    }

    companion object {
        init {
            // Make sure the driver is registered even on a cold test JVM.
            Class.forName("org.sqlite.JDBC")
        }

        /** Fresh in-memory database with foreign keys enabled, like the Android helper. */
        fun inMemory(): JdbcDb = configure(DriverManager.getConnection("jdbc:sqlite::memory:"))

        /** On-disk database, used by the migration tests. */
        fun open(file: File): JdbcDb =
            configure(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}"))

        private fun configure(connection: Connection): JdbcDb = JdbcDb(connection).apply {
            exec("PRAGMA foreign_keys = ON")
        }

        private fun preview(sql: String): String = sql.replace('\n', ' ').trim().take(160)
    }
}
