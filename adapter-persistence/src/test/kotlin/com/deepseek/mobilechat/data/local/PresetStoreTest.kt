package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.domain.ContextStrategyIds
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ThinkingLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetStoreTest {

    private fun preset(
        id: String,
        name: String = "preset $id",
        isDefault: Boolean = false,
    ) = Preset(
        id = PresetId(id),
        name = name,
        systemPrompt = "You are terse.",
        modelRef = ModelRef(ProviderId("prov"), ModelId("model")),
        params = Defaults.params,
        thinkingLevel = ThinkingLevel.MAX,
        contextStrategyId = ContextStrategyIds.DROP_OLDEST,
        isDefault = isDefault,
    )

    @Test
    fun upsertAndGetRoundTrip() = runBlocking {
        TestEnv().use { env ->
            val original = preset("p1")
            env.presets.upsert(original)
            assertEquals(original, env.presets.get(PresetId("p1")))
        }
    }

    @Test
    fun insertingNewDefaultClearsThePreviousOne() = runBlocking {
        TestEnv().use { env ->
            env.presets.upsert(preset("p1", isDefault = true))
            env.presets.upsert(preset("p2", isDefault = true))

            assertEquals(false, env.presets.get(PresetId("p1"))!!.isDefault)
            assertEquals(true, env.presets.get(PresetId("p2"))!!.isDefault)
            assertEquals(1, env.presets.observeAll().first().count { it.isDefault })
        }
    }

    @Test
    fun updateDoesNotDuplicateTheRow() = runBlocking {
        TestEnv().use { env ->
            env.presets.upsert(preset("p1", name = "first"))
            env.presets.upsert(preset("p1", name = "second"))

            assertEquals("second", env.presets.get(PresetId("p1"))!!.name)
            assertEquals(1, env.presets.observeAll().first().size)
        }
    }

    @Test
    fun observeAllPutsDefaultFirst() = runBlocking {
        TestEnv().use { env ->
            env.presets.upsert(preset("a", name = "Alpha"))
            env.presets.upsert(preset("z", name = "Zulu", isDefault = true))

            assertEquals(listOf("z", "a"), env.presets.observeAll().first().map { it.id.raw })
        }
    }

    @Test
    fun deleteRemovesPreset() = runBlocking {
        TestEnv().use { env ->
            env.presets.upsert(preset("p1"))
            env.presets.delete(PresetId("p1"))

            assertNull(env.presets.get(PresetId("p1")))
            assertTrue(env.presets.observeAll().first().isEmpty())
        }
    }
}
