package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.BalanceResult
import com.deepseek.mobilechat.application.service.testutil.FakeModelCatalogPort
import com.deepseek.mobilechat.application.service.testutil.FakeProviderProfileStore
import com.deepseek.mobilechat.application.service.testutil.FakeSecretStore
import com.deepseek.mobilechat.application.service.testutil.descriptor
import com.deepseek.mobilechat.application.service.testutil.providerProfile
import com.deepseek.mobilechat.application.service.testutil.requireErr
import com.deepseek.mobilechat.application.service.testutil.requireOk
import com.deepseek.mobilechat.domain.ProviderId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProviderServiceTest {

    private val store = FakeProviderProfileStore()
    private val secrets = FakeSecretStore()
    private val catalog = FakeModelCatalogPort()
    private val service = DefaultProviderService(store, secrets, catalog)

    @Test
    fun `upsert writes the api key under the provider secret ref`() = runTest {
        service.upsert(providerProfile("p1", secretRef = ""), apiKey = "sk-123").requireOk()

        assertEquals("sk-123", secrets.get("provider.p1"))
        assertEquals("provider.p1", store.get(ProviderId("p1"))!!.secretRef)
    }

    @Test
    fun `upsert without a key keeps the stored secret`() = runTest {
        service.upsert(providerProfile("p1"), apiKey = "sk-first").requireOk()

        service.upsert(providerProfile("p1", name = "renamed"), apiKey = null).requireOk()

        assertEquals("sk-first", secrets.get("provider.p1"))
        assertEquals("renamed", store.get(ProviderId("p1"))!!.name)
    }

    @Test
    fun `upsert rejects a blank name or base url`() = runTest {
        assertTrue(service.upsert(providerProfile("p1", name = "  ")).requireErr() is AppError.Validation)
        assertTrue(
            service.upsert(providerProfile("p1").copy(baseUrl = " ")).requireErr()
                is AppError.Validation,
        )
    }

    @Test
    fun `delete removes the credential as well`() = runTest {
        service.upsert(providerProfile("p1"), apiKey = "sk-123").requireOk()

        service.delete(ProviderId("p1")).requireOk()

        assertNull(store.get(ProviderId("p1")))
        assertNull(secrets.get("provider.p1"))
    }

    @Test
    fun `test and listModels delegate to the catalog`() = runTest {
        catalog.models = listOf(descriptor("model-9"))

        assertEquals("ok", service.test(providerProfile()).requireOk())
        assertEquals(listOf(descriptor("model-9")), service.listModels(providerProfile()).requireOk())
    }

    @Test
    fun `balance is passed through`() = runTest {
        catalog.balanceResult = BalanceResult.Available(12.5, "USD", "remaining")

        val balance = service.balance(providerProfile())

        assertEquals(BalanceResult.Available(12.5, "USD", "remaining"), balance)
    }
}
