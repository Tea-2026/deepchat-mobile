package com.deepseek.mobilechat.data.local.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JdbcDbTest {

    @Test
    fun bindsTextNumbersAndNulls() {
        val db = JdbcDb.inMemory()
        db.use {
            db.exec("CREATE TABLE sample (id TEXT, count INTEGER, ratio REAL, note TEXT)")
            db.exec("INSERT INTO sample VALUES (?, ?, ?, ?)", listOf("a", 7, 0.5, null))
            val row = db.query("SELECT id, count, ratio, note FROM sample", emptyList()) { row ->
                listOf(row.string(0), row.long(1), row.double(2), row.isNull(3))
            }.single()
            assertEquals("a", row[0])
            assertEquals(7L, row[1])
            assertEquals(0.5, row[2])
            assertEquals(true, row[3])
            assertNull(db.query("SELECT note FROM sample", emptyList()) { it.string(0) }.single())
        }
    }

    @Test
    fun transactionRollsBackOnFailure() {
        val db = JdbcDb.inMemory()
        db.use {
            db.exec("CREATE TABLE sample (id TEXT)")
            assertThrows(IllegalStateException::class.java) {
                db.transaction {
                    db.exec("INSERT INTO sample VALUES ('rolled-back')")
                    error("boom")
                }
            }
            assertTrue(db.query("SELECT id FROM sample", emptyList()) { it.string(0) }.isEmpty())
        }
    }

    @Test
    fun nestedTransactionJoinsTheOuterOne() {
        val db = JdbcDb.inMemory()
        db.use {
            db.exec("CREATE TABLE sample (id TEXT)")
            db.transaction {
                db.exec("INSERT INTO sample VALUES ('outer')")
                db.transaction {
                    db.exec("INSERT INTO sample VALUES ('inner')")
                }
            }
            assertEquals(
                listOf("inner", "outer"),
                db.query("SELECT id FROM sample ORDER BY id", emptyList()) { it.string(0) },
            )
        }
    }

    @Test
    fun userVersionReadsAndWrites() {
        val db = JdbcDb.inMemory()
        db.use {
            assertEquals(0, db.userVersion())
            db.setUserVersion(3)
            assertEquals(3, db.userVersion())
            assertThrows(IllegalArgumentException::class.java) { db.setUserVersion(-1) }
        }
    }
}
