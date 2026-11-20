package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.AppSettings
import com.deepseek.mobilechat.application.SearchProviderKind
import com.deepseek.mobilechat.application.SearchResponse
import com.deepseek.mobilechat.application.service.testutil.FakeSearchPort
import com.deepseek.mobilechat.application.service.testutil.FakeSettingsStore
import com.deepseek.mobilechat.application.service.testutil.requireErr
import com.deepseek.mobilechat.application.service.testutil.requireOk
import com.deepseek.mobilechat.domain.SearchHit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSearchServiceTest {

    private val tavily = FakeSearchPort(SearchResponse("", listOf(SearchHit("t", "u", "s"))))
    private val brave = FakeSearchPort()
    private val settings = FakeSettingsStore(AppSettings(activeSearchProvider = SearchProviderKind.TAVILY))
    private val service = DefaultSearchService(
        adapters = mapOf(
            SearchProviderKind.TAVILY to tavily,
            SearchProviderKind.BRAVE to brave,
        ),
        settingsStore = settings,
    )

    @Test
    fun `uses the adapter selected in settings`() = runTest {
        val response = service.webSearch("  kotlin coroutines ").requireOk()

        assertEquals("kotlin coroutines", response.query)
        assertEquals(listOf("kotlin coroutines"), tavily.queries)
        assertTrue(brave.queries.isEmpty())
    }

    @Test
    fun `switching settings switches the adapter without rebuilding`() = runTest {
        settings.set(AppSettings(activeSearchProvider = SearchProviderKind.BRAVE))

        service.webSearch("hello").requireOk()

        assertEquals(listOf("hello"), brave.queries)
        assertTrue(tavily.queries.isEmpty())
    }

    @Test
    fun `a missing adapter is unsupported`() = runTest {
        settings.set(AppSettings(activeSearchProvider = SearchProviderKind.SERPER))

        val error = service.webSearch("hello").requireErr()

        assertTrue(error is AppError.Unsupported)
    }

    @Test
    fun `a blank query is a validation error`() = runTest {
        val error = service.webSearch("   ").requireErr()

        assertTrue(error is AppError.Validation)
        assertTrue(tavily.queries.isEmpty())
    }
}
