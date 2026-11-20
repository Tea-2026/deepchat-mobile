package com.deepseek.mobilechat.data.remote.http

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.Outcome
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * The narrow HTTP surface the protocol adapters need.
 *
 * Deliberately tiny and OkHttp-free so adapters and tests can substitute a
 * fake: [execute] performs one blocking request and returns the response with
 * a lazily-read line stream. Implementations must not leak transport
 * exceptions: they are normalized to [ProviderHttpException].
 */
interface HttpCaller {
    fun execute(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): HttpResponse
}

/**
 * A response whose body is exposed as a lazily consumed [Sequence] of lines.
 *
 * The sequence is meant for SSE: lines are read as they arrive. Because a
 * lazily built sequence has no `close` hook of its own, callers must always
 * close the response ([use] does it) so an abandoned stream releases the
 * socket. The sequence also closes the underlying body when it is exhausted.
 */
class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val bodyLines: Sequence<String> = emptySequence(),
    private val onClose: (() -> Unit)? = null,
) : Closeable {

    val isSuccess: Boolean get() = status in 200..299

    /** Case-insensitive header lookup (HTTP header names are case-insensitive). */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun close() {
        onClose?.invoke()
    }

    companion object {
        /** Builds a response from an in-memory payload; used by fakes and tests. */
        fun of(
            status: Int,
            body: String,
            headers: Map<String, String> = emptyMap(),
        ): HttpResponse = HttpResponse(status, headers, body.lines().asSequence())

        /** Convenience for JSON fixtures. */
        fun json(body: String, status: Int = 200): HttpResponse =
            of(status, body, mapOf("content-type" to "application/json"))
    }
}

/**
 * Transport/protocol failure carrying the already-normalized [AppError].
 *
 * Adapters catch this and turn it into `GenerationEvent.Failed`; nothing below
 * the adapter layer ever sees an OkHttp type.
 */
class ProviderHttpException(val error: AppError) : RuntimeException(error.message)

/**
 * Pure normalization rules shared by every adapter.
 *
 * Keeping them here (rather than inside the OkHttp implementation) makes the
 * mapping unit-testable without any network.
 */
object HttpErrors {

    private const val SNIPPET_LIMIT = 512

    /** Maps a non-2xx status to an [AppError]; returns null only for 2xx. */
    fun statusError(
        status: Int,
        retryAfterHeader: String?,
        bodySnippet: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): AppError? = when {
        status in 200..299 -> null
        status == 401 || status == 403 -> AppError.ProviderAuth(
            "HTTP $status: ${snippetOrFallback(bodySnippet, "authentication rejected")}",
        )
        status == 429 -> AppError.ProviderRateLimit(
            retryAfterSeconds = retryAfterSeconds(retryAfterHeader, nowEpochSeconds),
            message = "HTTP 429: ${snippetOrFallback(bodySnippet, "rate limited")}",
        )
        status == 408 -> AppError.Timeout(
            "HTTP 408: ${snippetOrFallback(bodySnippet, "request timeout")}",
        )
        status in 500..599 -> AppError.ProviderUnavailable(
            "HTTP $status: ${snippetOrFallback(bodySnippet, "provider unavailable")}",
        )
        else -> AppError.ProviderProtocol(
            "HTTP $status: ${snippetOrFallback(bodySnippet, "unexpected status")}",
        )
    }

    /**
     * Parses `Retry-After`: either delta-seconds or an RFC 1123 HTTP date.
     * Returns null when the header is absent or unparseable.
     */
    fun retryAfterSeconds(
        value: String?,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        raw.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
        return runCatching {
            val at = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
            (at.toEpochSecond() - nowEpochSeconds).coerceAtLeast(0L)
        }.getOrNull()
    }

    /** Maps transport failures: socket timeout becomes [AppError.Timeout]. */
    fun ioError(t: IOException): AppError = when (t) {
        is SocketTimeoutException -> AppError.Timeout(t.message ?: "socket timeout")
        else -> AppError.Network(t.message ?: t.javaClass.simpleName)
    }

    fun unknown(t: Throwable): AppError = AppError.Unknown(t.message ?: t.javaClass.simpleName, t)

    /** Collapses whitespace so an HTML error page does not blow up an error message. */
    fun snippet(body: String): String = body.replace(Regex("\\s+"), " ").trim().take(SNIPPET_LIMIT)

    private fun snippetOrFallback(body: String, fallback: String): String =
        snippet(body).ifEmpty { fallback }
}

/**
 * Runs a blocking request body and normalizes every failure into [Outcome.Err].
 * Shared by the catalog and the search adapters, which are `suspend` but do not
 * stream; inline so the block may stay in a suspend call site.
 */
inline fun <T> guardedHttp(block: () -> T): Outcome<T> = try {
    Outcome.Ok(block())
} catch (t: ProviderHttpException) {
    Outcome.Err(t.error)
} catch (t: IOException) {
    Outcome.Err(HttpErrors.ioError(t))
} catch (t: Throwable) {
    Outcome.Err(HttpErrors.unknown(t))
}

/**
 * Throws [ProviderHttpException] for a non-2xx response.
 *
 * The OkHttp implementation already does this; the check also covers fakes and
 * any future caller that uses a no-throw transport.
 */
fun HttpResponse.requireSuccess() {
    if (isSuccess) return
    throw ProviderHttpException(
        HttpErrors.statusError(status, header("Retry-After"), "") ?: AppError.ProviderProtocol("HTTP $status"),
    )
}

/**
 * Production [HttpCaller] backed by a synchronous OkHttp call.
 * Timeouts are tuned for streaming completions: 60s to connect, 300s between
 * body bytes (a long silence between tokens is still a timeout), 60s to write.
 */
class OkHttpHttpCaller(
    private val client: OkHttpClient = defaultClient(),
) : HttpCaller {

    override fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResponse {
        val verb = method.uppercase()
        val requestBody = when (verb) {
            "GET", "HEAD", "DELETE" -> body?.toRequestBody(JSON)
            else -> (body ?: "").toRequestBody(JSON)
        }
        val builder = Request.Builder().url(url).method(verb, requestBody)
        headers.forEach { (name, value) -> builder.header(name, value) }

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (t: IOException) {
            throw ProviderHttpException(HttpErrors.ioError(t))
        } catch (t: Throwable) {
            throw ProviderHttpException(HttpErrors.unknown(t))
        }

        if (!response.isSuccessful) {
            val errorBody = runCatching { response.body.string() }.getOrDefault("")
            runCatching { response.close() }
            val error = HttpErrors.statusError(response.code, response.header("Retry-After"), errorBody)
                ?: AppError.ProviderProtocol("HTTP ${response.code}")
            throw ProviderHttpException(error)
        }

        val source = runCatching { response.body.source() }.getOrNull()
        if (source == null) {
            runCatching { response.close() }
            throw ProviderHttpException(AppError.ProviderProtocol("empty response body"))
        }
        val lines = sequence {
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    yield(line)
                }
            } finally {
                runCatching { response.close() }
            }
        }
        return HttpResponse(
            status = response.code,
            headers = response.headers.toMultimap().mapValues { (_, values) -> values.firstOrNull().orEmpty() },
            bodyLines = lines,
            onClose = { runCatching { response.close() } },
        )
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
