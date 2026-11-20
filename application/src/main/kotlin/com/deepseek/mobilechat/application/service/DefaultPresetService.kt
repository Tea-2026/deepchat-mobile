package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.PresetService
import com.deepseek.mobilechat.application.PresetStore
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.PresetId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Default [PresetService].
 *
 * The "exactly one default preset" invariant is enforced here rather than in
 * the store: [save] demotes every other default before writing a new one and
 * [setDefault] does the same, so observe() never shows two defaults.
 */
class DefaultPresetService(private val presetStore: PresetStore) : PresetService {

    override fun observe(): Flow<List<Preset>> = presetStore.observeAll()

    override suspend fun save(preset: Preset): Outcome<Unit> {
        val name = preset.name.trim()
        if (name.isEmpty()) {
            return Outcome.Err(AppError.Validation("preset name must not be empty"))
        }
        val normalized = preset.copy(name = name)
        if (normalized.isDefault) clearOtherDefaults(normalized.id)
        presetStore.upsert(normalized)
        return Outcome.Ok(Unit)
    }

    override suspend fun delete(id: PresetId): Outcome<Unit> {
        presetStore.delete(id)
        return Outcome.Ok(Unit)
    }

    override suspend fun setDefault(id: PresetId): Outcome<Unit> {
        val target = presetStore.get(id)
            ?: return Outcome.Err(AppError.Validation("preset not found: ${id.raw}"))
        clearOtherDefaults(id)
        if (!target.isDefault) presetStore.upsert(target.copy(isDefault = true))
        return Outcome.Ok(Unit)
    }

    private suspend fun clearOtherDefaults(keep: PresetId) {
        presetStore.observeAll().first()
            .filter { it.isDefault && it.id != keep }
            .forEach { presetStore.upsert(it.copy(isDefault = false)) }
    }
}
