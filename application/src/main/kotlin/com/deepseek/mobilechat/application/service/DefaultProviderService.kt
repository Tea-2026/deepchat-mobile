package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.BalanceResult
import com.deepseek.mobilechat.application.ModelCatalogPort
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.application.ProviderService
import com.deepseek.mobilechat.application.SecretStore
import com.deepseek.mobilechat.domain.ModelDescriptor
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import kotlinx.coroutines.flow.Flow

/**
 * Default [ProviderService].
 *
 * API keys never travel through [ProviderProfile]: the profile only carries an
 * opaque `secretRef`, and [upsert] writes the key into [SecretStore] under
 * `provider.<id>`. A profile without a `secretRef` gets that canonical ref, so
 * credentials stay reachable after an edit. [delete] removes the credential
 * too — leaving orphan secrets behind is exactly the kind of leak the port
 * exists to prevent.
 */
class DefaultProviderService(
    private val providerStore: ProviderProfileStore,
    private val secretStore: SecretStore,
    private val catalog: ModelCatalogPort,
) : ProviderService {

    override fun observe(): Flow<List<ProviderProfile>> = providerStore.observeAll()

    override suspend fun upsert(profile: ProviderProfile, apiKey: String?): Outcome<Unit> {
        val name = profile.name.trim()
        if (name.isEmpty()) {
            return Outcome.Err(AppError.Validation("provider name must not be empty"))
        }
        if (profile.baseUrl.isBlank()) {
            return Outcome.Err(AppError.Validation("provider base url must not be empty"))
        }
        val reference = profile.secretRef.ifBlank { secretRefFor(profile.id) }
        val normalized = profile.copy(name = name, secretRef = reference)
        if (!apiKey.isNullOrBlank()) secretStore.put(reference, apiKey)
        providerStore.upsert(normalized)
        return Outcome.Ok(Unit)
    }

    override suspend fun delete(id: ProviderId): Outcome<Unit> {
        val profile = providerStore.get(id)
        val reference = profile?.secretRef?.ifBlank { secretRefFor(id) } ?: secretRefFor(id)
        secretStore.delete(reference)
        providerStore.delete(id)
        return Outcome.Ok(Unit)
    }

    override suspend fun test(profile: ProviderProfile): Outcome<String> = catalog.testConnection(profile)

    override suspend fun listModels(profile: ProviderProfile): Outcome<List<ModelDescriptor>> =
        catalog.listModels(profile)

    override suspend fun balance(profile: ProviderProfile): BalanceResult = catalog.balance(profile)

    private fun secretRefFor(id: ProviderId): String = "provider.${id.raw}"
}
