package com.deepseek.mobilechat.wiring

import android.content.Context
import com.deepseek.mobilechat.application.AppSettings
import com.deepseek.mobilechat.application.SearchProviderKind
import com.deepseek.mobilechat.application.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings backed by SharedPreferences. Small enough that a DataStore
 * dependency would only add build weight.
 */
class PrefsSettingsStore(context: Context) : SettingsStore {

    private val prefs = context.getSharedPreferences("mobilechat.settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    override fun observe(): Flow<AppSettings> = state.asStateFlow()

    override suspend fun update(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_SEARCH_PROVIDER, settings.activeSearchProvider.name)
            .putInt(KEY_MAX_CONCURRENT, settings.maxConcurrentGenerations)
            .putLong(KEY_CHECKPOINT, settings.streamingCheckpointMillis)
            .putString(KEY_THEME_MODE, settings.themeMode)
            .apply()
        state.value = settings
    }

    private fun read(): AppSettings = AppSettings(
        activeSearchProvider = runCatching {
            SearchProviderKind.valueOf(
                prefs.getString(KEY_SEARCH_PROVIDER, SearchProviderKind.TAVILY.name)!!,
            )
        }.getOrDefault(SearchProviderKind.TAVILY),
        maxConcurrentGenerations = prefs.getInt(KEY_MAX_CONCURRENT, 2).coerceIn(1, 8),
        streamingCheckpointMillis = prefs.getLong(KEY_CHECKPOINT, 300L).coerceIn(50L, 5_000L),
        themeMode = prefs.getString(KEY_THEME_MODE, "system") ?: "system",
    )

    private companion object {
        const val KEY_SEARCH_PROVIDER = "search.provider"
        const val KEY_MAX_CONCURRENT = "generation.maxConcurrent"
        const val KEY_CHECKPOINT = "generation.checkpointMillis"
        const val KEY_THEME_MODE = "appearance.themeMode"
    }
}
