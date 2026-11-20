package com.deepseek.mobilechat.ui.screens.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Facts about the install itself that the settings screen shows but
 * `AppServices` does not expose.
 *
 * Both are read from the Android framework (package manager / app data dir)
 * rather than from a store, so they stay honest without widening the frozen
 * `AppServices` contract. Anything that can throw on a locked-down device
 * returns `null` and the row shows an explicit "unavailable" instead of a
 * wrong number.
 */

/**
 * Size of the app's SQLite databases directory in bytes, measured off the main
 * thread. Returns `null` when the directory cannot be read at all (the row
 * then reads "不可用", not "0 B").
 */
internal suspend fun readDatabaseBytes(context: Context): Long? = withContext(Dispatchers.IO) {
    runCatching { databaseBytesBlocking(context) }.getOrNull()
}

private fun databaseBytesBlocking(context: Context): Long? {
    val app = context.applicationContext
    // Resolved from the data dir instead of a database name: `:ui` does not
    // depend on `:adapter-persistence`, so `Schema.DATABASE_NAME` is not on its
    // classpath, and the row wants the whole directory anyway (main file plus
    // the -wal / -shm sidecars and any future database).
    val databases = File(app.applicationInfo.dataDir, "databases")
    if (!databases.isDirectory) return 0L
    return databases.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }
}

/** 1234567 -> "1.2 MB"; always one readable unit, never raw byte noise. */
internal fun formatStorageBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

/** Version name from the manifest, or `null` where the package is unknown. */
internal fun appVersionName(context: Context): String? = runCatching {
    val app = context.applicationContext
    @Suppress("DEPRECATION")
    val info = app.packageManager.getPackageInfo(app.packageName, 0)
    info.versionName
}.getOrNull()

/** The three values `AppSettings.themeMode` accepts, as the user sees them. */
internal fun themeModeLabel(mode: String?): String = when (mode) {
    "light" -> "浅色"
    "dark" -> "深色"
    "system" -> "跟随系统"
    else -> "读取中"
}

/** `system -> light -> dark -> system`, the order the appearance row cycles. */
internal fun nextThemeMode(current: String): String {
    val modes = listOf("system", "light", "dark")
    val index = modes.indexOf(current)
    return modes[(if (index < 0) 0 else index + 1) % modes.size]
}
