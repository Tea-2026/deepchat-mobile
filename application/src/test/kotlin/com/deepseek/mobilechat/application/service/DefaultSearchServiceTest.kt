package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.AppSettings
import com.deepseek.mobilechat.application.SearchPort
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

    private val deepSeek = FakeSearchPort(SearchResponse("", listOf(SearchHit("d", "u-d", "s"))))
    private val bing = FakeSearchPort(SearchResponse("", listOf(SearchHit("b", "u-b", "s"))))
    private val tavily = FakeSearchPort(SearchResponse("", listOf(SearchHit("t", "u-t", "s"))))
    private val settings = FakeSettingsStore(AppSettings(activeSearchProvider = SearchProviderKind.TAVILY))

    private fun service(vararg adapters: Pair<SearchProviderKind, SearchPort>): DefaultSearchService =
        DefaultSearchService(adapters.toMap(), settings)

    @Test
    fun `uses the adapter selected in settings`() = runTest {
        val service = service(
            SearchProviderKind.TAVILY to tavily,
            SearchProviderKind.DEEPSEEK to deepSeek,
            SearchProviderKind.BING to bing,
        )

        val response = service.webSearch("  kotlin coroutines ").requireOk()

        assertEquals("kotlin coroutines", response.query)
        assertEquals(listOf("kotlin coroutines"), tavily.queries)
        assertTrue(deepSeek.queries.isEmpty())
        assertTrue(bing.queries.isEmpty())
    }

    @Test
    fun `an unconfigured deepseek falls back to bing`() = runTest {
        settings.set(AppSettings(activeSearchProvider = SearchProviderKind.DEEPSEEK))
        deepSeek.configured = false
        val service = service(
            SearchProviderKind.DEEPSEEK to deepSeek,
            SearchProviderKind.BING to bing,
        )

        val response = service.webSearch("hello").requireOk()

        assertEquals("hello", response.query)
        assertTrue(deepSeek.queries.isEmpty())
        assertEquals(listOf("hello"), bing.queries)
        assertEquals(listOf(SearchHit("b", "u-b", "s")), response.hits)
    }

    @Test
    fun `a secret that vanishes during the call still falls back to bing`() = runTest {
        settings.set(AppSettings(activeSearchProvider = SearchProviderKind.DEEPSEEK))
        deepSeek.failure = AppError.SecretMissing("key removed")
        val service = service(
            SearchProviderKind.DEEPSEEK to deepSeek,
            SearchProviderKind.BING to bing,
        )

        service.webSearch("hello").requireOk()

        assertEquals(listOf("hello"), deepSeek.queries)
        assertEquals(listOf("hello"), bing.queries)
    }

    @Test
    fun `bing is used when deepseek is absent from the graph entirely`() = runTest {
        settings.set(AppSettings(activeSearchProvider = SearchProviderKind.DEEPSEEK))
        val service = service(SearchProviderKind.BING to bing)

        service.webSearch("hello").requireOk()

        assertEquals(listOf("hello"), bing.queries)
    }

    @Test
    fun `nothing configured is unsupported and says search is not configured`() = runTest {
        settings.set(AppSettings(activeSearchProvider = SearchProviderKind.TAVILY))
        deepSeek.configured = false
        bing.configured = false
        tavily.configured = false
        val service = service(
            SearchProviderKind.TAVILY to tavily,
            SearchProviderKind.DEEPSEEK to deepSeek,
            SearchProviderKind.BING to bing,
        )

        val error = service.webSearch("hello").requireErr()

        assertTrue(error is AppError.Unsupported)
        assertTrue(error.message.contains("not configured"))
        assertTrue(tavily.queries.isEmpty())
        assertTrue(deepSeek.queries.isEmpty())
        assertTrue(bing.queries.isEmpty())
    }

    @Test
    fun `a non-secret failure is surfaced instead of masked by a fallback`() = runTest {
        settings.set(AppSettings(activeSearchProvider = SearchProviderKind.DEEPSEEK))
        deepSeek.failure = AppError.ProviderRateLimit(3L)
        val service = service(
            SearchProviderKind.DEEPSEEK to deepSeek,
            SearchProviderKind.BING to bing,
        )

        val error = service.webSearch("hello").requireErr()

        assertTrue(error is AppError.ProviderRateLimit)
        assertEquals(listOf("hello"), deepSeek.queries)
        assertTrue(bing.queries.isEmpty())
    }

    @Test
    fun `keyed adapters come after deepseek and bing in the fallback order`() = runTest {
        // The selected source is not wired; deepseek/bing are unconfigured.
        settings.set(AppSettings(activeSearchProvider = SearchProviderKind.SERPER))
        deepSeek.configured = false
        bing.configured = false
        val service = service(
            SearchProviderKind.TAVILY to tavily,
            SearchProviderKind.DEEPSEEK to deepSeek,
            SearchProviderKind.BING to bing,
        )

        service.webSearch("hello").requireOk()

        assertTrue(deepSeek.queries.isEmpty())
        assertTrue(bing.queries.isEmpty())
        assertEquals(listOf("hello"), tavily.queries)
    }

    @Test
    fun `a blank query is a validation error and never reaches an adapter`() = runTest {
        val service = service(
            SearchProviderKind.TAVILY to tavily,
            SearchProviderKind.DEEPSEEK to deepSeek,
            SearchProviderKind.BING to bing,
        )

        val error = service.webSearch("   ").requireErr()

        assertTrue(error is AppError.Validation)
        assertTrue(tavily.queries.isEmpty())
        assertTrue(deepSeek.queries.isEmpty())
        assertTrue(bing.queries.isEmpty())
    }
}
