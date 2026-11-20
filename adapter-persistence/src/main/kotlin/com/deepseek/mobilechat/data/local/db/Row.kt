package com.deepseek.mobilechat.data.local.db

/**
 * One row of a result set.
 *
 * Column indices are **0-based**, mirroring `android.database.Cursor`. The JDBC
 * test implementation adds one internally, so DAO code is identical on device
 * and on the JVM.
 */
interface Row {
    fun string(i: Int): String?

    fun long(i: Int): Long

    fun int(i: Int): Int

    fun double(i: Int): Double?

    fun isNull(i: Int): Boolean
}
