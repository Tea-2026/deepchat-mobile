package com.deepseek.mobilechat.data.remote.http

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.data.remote.RecordingHttpCaller
import com.deepseek.mobilechat.data.remote.testProfile
import com.deepseek.mobilechat.data.remote.testRequest
import com.deepseek.mobilechat.data.remote.provider.OpenAiCompatibleAdapter
import com.deepseek.mobilechat.domain.ProviderProtocol
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ErrorMappingTest {

    @Test
    fun `401 maps to provider auth`() {
        val error = HttpErrors.statusError(401, null, "invalid api key")

        assertTrue(error is AppError.ProviderAuth)
        assertTrue(error!!.message.contains("invalid api key"))
    }

    @Test
    fun `403 also maps to provider auth`() {
        assertTrue(HttpErrors.statusError(403, null, "") is AppError.ProviderAuth)
    }

    @Test
    fun `429 maps to rate limit and reads retry-after seconds`() {
        val error = HttpErrors.statusError(429, "12", "slow down") as AppError.ProviderRateLimit

        assertEquals(12L, error.retryAfterSeconds)
        assertTrue(error.message.contains("slow down"))
    }

    @Test
    fun `429 without retry-after leaves the delay unknown`() {
        val error = HttpErrors.statusError(429, null, "") as AppError.ProviderRateLimit

        assertNull(error.retryAfterSeconds)
    }

    @Test
    fun `429 with an http-date retry-after is converted to seconds`() {
        val now = 1_700_000_000L
        val at = ZonedDateTime.ofInstant(Instant.ofEpochSecond(now + 30), ZoneOffset.UTC)
        val header = DateTimeFormatter.RFC_1123_DATE_TIME.format(at)

        assertEquals(30L, HttpErrors.retryAfterSeconds(header, now))
    }

    @Test
    fun `unparseable retry-after is ignored`() {
        assertNull(HttpErrors.retryAfterSeconds("soon"))
        assertNull(HttpErrors.retryAfterSeconds(null))
    }

    @Test
    fun `5xx maps to provider unavailable`() {
        assertTrue(HttpErrors.statusError(503, null, "") is AppError.ProviderUnavailable)
    }

    @Test
    fun `other client errors map to provider protocol`() {
        assertTrue(HttpErrors.statusError(404, null, "no such model") is AppError.ProviderProtocol)
    }

    @Test
    fun `success has no error`() {
        assertNull(HttpErrors.statusError(200, null, ""))
    }

    @Test
    fun `socket timeout maps to timeout and other io to network`() {
        assertTrue(HttpErrors.ioError(SocketTimeoutException("read timed out")) is AppError.Timeout)
        assertTrue(HttpErrors.ioError(IOException("connection reset")) is AppError.Network)
    }

    @Test
    fun `long error bodies are collapsed into a short snippet`() {
        val snippet = HttpErrors.snippet("<html>\n\n  <body>boom</body></html>")

        assertEquals("<html> <body>boom</body></html>", snippet)
    }

    @Test
    fun `requireSuccess raises the normalized error`() {
        try {
            HttpResponse.json("""{"error":"nope"}""", status = 401).requireSuccess()
            fail("expected ProviderHttpException")
        } catch (e: ProviderHttpException) {
            assertTrue(e.error is AppError.ProviderAuth)
        }
    }

    @Test
    fun `requireSuccess accepts 2xx`() {
        HttpResponse.json("{}", status = 200).requireSuccess()
    }

    @Test
    fun `adapter converts a transport failure into a Failed event`() {
        val caller = RecordingHttpCaller { throw ProviderHttpException(AppError.ProviderRateLimit(7, "slow down")) }
        val adapter = OpenAiCompatibleAdapter(caller, now = { 42L })

        val events = runBlocking {
            adapter.stream(testRequest(), testProfile(ProviderProtocol.OPENAI_COMPATIBLE), "sk").toList()
        }

        assertEquals(2, events.size)
        assertEquals(GenerationEvent.Started(42L), events[0])
        val failed = events[1] as GenerationEvent.Failed
        assertEquals(7L, (failed.error as AppError.ProviderRateLimit).retryAfterSeconds)
    }

    @Test
    fun `adapter converts a socket timeout into a Timeout failure`() {
        val caller = RecordingHttpCaller { throw SocketTimeoutException("read timed out") }
        val adapter = OpenAiCompatibleAdapter(caller, now = { 0L })

        val events = runBlocking {
            adapter.stream(testRequest(), testProfile(ProviderProtocol.OPENAI_COMPATIBLE), "sk").toList()
        }

        assertTrue((events.last() as GenerationEvent.Failed).error is AppError.Timeout)
    }
}
