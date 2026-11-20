package com.deepseek.mobilechat.data.remote.provider

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.ChatCompletionPort
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.GenerationRequest
import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.application.SecretStore
import com.deepseek.mobilechat.data.remote.http.HttpCaller
import com.deepseek.mobilechat.domain.ProviderProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * The only [ChatCompletionPort] the composition root needs.
 *
 * Resolves the provider profile and its secret, then delegates to the adapter
 * matching `profile.protocol`. Failures that happen before any network call
 * (unknown profile, missing key) are surfaced as a single terminal
 * [GenerationEvent.Failed] so the caller has exactly one error channel.
 */
class RoutingChatCompletionPort(
    private val profiles: ProviderProfileStore,
    private val secrets: SecretStore,
    private val resolveAttachment: AttachmentResolver = { null },
    private val http: HttpCaller,
) : ChatCompletionPort {

    private val openAi = OpenAiCompatibleAdapter(http, resolveAttachment)
    private val anthropic = AnthropicAdapter(http, resolveAttachment)
    private val gemini = GeminiAdapter(http, resolveAttachment)

    override fun stream(request: GenerationRequest): Flow<GenerationEvent> = flow {
        val profile = try {
            profiles.get(request.modelRef.providerId)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            emit(GenerationEvent.Failed(t.toAppError()))
            return@flow
        }

        if (profile == null) {
            emit(
                GenerationEvent.Failed(
                    AppError.Validation(
                        "provider profile not found: ${request.modelRef.providerId.raw}",
                    ),
                ),
            )
            return@flow
        }

        val apiKey = try {
            secrets.get(profile.secretRef)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            emit(GenerationEvent.Failed(t.toAppError()))
            return@flow
        }

        if (apiKey.isNullOrBlank()) {
            emit(
                GenerationEvent.Failed(
                    AppError.SecretMissing(
                        "no API key stored for provider \"${profile.name}\" (${profile.secretRef})",
                    ),
                ),
            )
            return@flow
        }

        val adapter: Flow<GenerationEvent> = when (profile.protocol) {
            ProviderProtocol.OPENAI_COMPATIBLE -> openAi.stream(request, profile, apiKey)
            ProviderProtocol.ANTHROPIC -> anthropic.stream(request, profile, apiKey)
            ProviderProtocol.GEMINI -> gemini.stream(request, profile, apiKey)
        }
        emitAll(adapter)
    }
}
