package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.ProviderProtocol
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderProfileStoreTest {

    private fun profile(id: String, name: String = "provider $id") = ProviderProfile(
        id = ProviderId(id),
        name = name,
        protocol = ProviderProtocol.ANTHROPIC,
        baseUrl = "https://api.anthropic.com",
        secretRef = "secret:$id",
        defaultModelId = ModelId("claude-x"),
        extraHeaders = mapOf("anthropic-version" to "2023-06-01", "X-Trace" to "on"),
        createdAt = 1_000L,
    )

    @Test
    fun upsertAndGetRoundTripKeepsHeaders() = runBlocking {
        TestEnv().use { env ->
            val original = profile("p1")
            env.providers.upsert(original)
            assertEquals(original, env.providers.get(ProviderId("p1")))
        }
    }

    @Test
    fun updateReplacesFieldsWithoutDuplicating() = runBlocking {
        TestEnv().use { env ->
            env.providers.upsert(profile("p1", name = "first"))
            env.providers.upsert(profile("p1", name = "second"))

            val loaded = env.providers.get(ProviderId("p1"))!!
            assertEquals("second", loaded.name)
            assertEquals(1, env.providers.observeAll().first().size)
        }
    }

    @Test
    fun observeAllOrdersByCreationTime() = runBlocking {
        TestEnv().use { env ->
            env.providers.upsert(profile("b").copy(createdAt = 200L))
            env.providers.upsert(profile("a").copy(createdAt = 100L))

            assertEquals(listOf("a", "b"), env.providers.observeAll().first().map { it.id.raw })
        }
    }

    @Test
    fun deleteRemovesProfile() = runBlocking {
        TestEnv().use { env ->
            env.providers.upsert(profile("p1"))
            env.providers.delete(ProviderId("p1"))

            assertNull(env.providers.get(ProviderId("p1")))
        }
    }
}
