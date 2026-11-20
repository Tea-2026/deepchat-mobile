package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.service.testutil.FakePresetStore
import com.deepseek.mobilechat.application.service.testutil.preset
import com.deepseek.mobilechat.application.service.testutil.requireErr
import com.deepseek.mobilechat.application.service.testutil.requireOk
import com.deepseek.mobilechat.domain.PresetId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPresetServiceTest {

    private val store = FakePresetStore()
    private val service = DefaultPresetService(store)

    @Test
    fun `saving a default demotes every other default`() = runTest {
        store.put(preset("a", isDefault = true))
        store.put(preset("b"))

        service.save(preset("b", isDefault = true)).requireOk()

        val presets = service.observe().first()
        assertEquals(listOf(PresetId("b")), presets.filter { it.isDefault }.map { it.id })
        assertEquals(2, presets.size)
    }

    @Test
    fun `setDefault keeps exactly one default`() = runTest {
        store.put(preset("a", isDefault = true))
        store.put(preset("b"))

        service.setDefault(PresetId("b")).requireOk()

        val presets = service.observe().first()
        assertEquals(listOf(PresetId("b")), presets.filter { it.isDefault }.map { it.id })
    }

    @Test
    fun `setDefault rejects an unknown preset`() = runTest {
        val error = service.setDefault(PresetId("ghost")).requireErr()
        assertTrue(error is AppError.Validation)
    }

    @Test
    fun `save rejects a blank name and trims a valid one`() = runTest {
        val error = service.save(preset("x").copy(name = "   ")).requireErr()
        assertTrue(error is AppError.Validation)

        service.save(preset("x").copy(name = "  My preset  ")).requireOk()
        assertEquals("My preset", store.get(PresetId("x"))!!.name)
    }

    @Test
    fun `delete removes the preset`() = runTest {
        store.put(preset("a"))

        service.delete(PresetId("a")).requireOk()

        assertTrue(store.state.value.isEmpty())
    }
}
