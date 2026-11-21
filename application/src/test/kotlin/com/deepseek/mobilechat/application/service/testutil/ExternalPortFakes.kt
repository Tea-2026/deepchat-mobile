package com.deepseek.mobilechat.application.service.testutil

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.BalanceResult
import com.deepseek.mobilechat.application.ExportBundle
import com.deepseek.mobilechat.application.ExportFormat
import com.deepseek.mobilechat.application.ExportPort
import com.deepseek.mobilechat.application.ImportedBundle
import com.deepseek.mobilechat.application.ModelCatalogPort
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.SearchOptions
import com.deepseek.mobilechat.application.SearchPort
import com.deepseek.mobilechat.application.SearchResponse
import com.deepseek.mobilechat.domain.ModelDescriptor
import com.deepseek.mobilechat.domain.ProviderProfile

// ---------------------------------------------------------------------------
// Fakes for the "outside world" ports: model catalog, search, export/import.
// ---------------------------------------------------------------------------

class FakeModelCatalogPort : ModelCatalogPort {

    var models: List<ModelDescriptor> = emptyList()
    var listCalls: Int = 0
    var listFailure: AppError? = null
    var listThrows: Throwable? = null
    var balanceResult: BalanceResult = BalanceResult.Unsupported

    override suspend fun listModels(profile: ProviderProfile): Outcome<List<ModelDescriptor>> {
        listCalls++
        listThrows?.let { throw it }
        listFailure?.let { return Outcome.Err(it) }
        return Outcome.Ok(models)
    }

    override suspend fun testConnection(profile: ProviderProfile): Outcome<String> = Outcome.Ok("ok")

    override suspend fun balance(profile: ProviderProfile): BalanceResult = balanceResult
}

class FakeSearchPort(private val response: SearchResponse = SearchResponse("", emptyList())) : SearchPort {

    val queries = mutableListOf<String>()

    var configured: Boolean = true

    /** When set, [search] fails with this error instead of returning [response]. */
    var failure: AppError? = null

    override suspend fun isConfigured(): Boolean = configured

    override suspend fun search(query: String, options: SearchOptions): Outcome<SearchResponse> {
        queries += query
        failure?.let { return Outcome.Err(it) }
        return Outcome.Ok(response.copy(query = query))
    }
}

class FakeExportPort : ExportPort {

    var exported: ExportBundle? = null
    var exportedFormat: ExportFormat? = null
    var exportedTarget: String? = null
    var exportedBundleResult: Outcome<Unit> = Outcome.Ok(Unit)
    var importedBundle: ImportedBundle =
        ImportedBundle(emptyList(), emptyList(), emptyList())
    var importedSource: String? = null

    override suspend fun export(
        bundle: ExportBundle,
        format: ExportFormat,
        targetUri: String,
    ): Outcome<Unit> {
        exported = bundle
        exportedFormat = format
        exportedTarget = targetUri
        return exportedBundleResult
    }

    override suspend fun import(sourceUri: String): Outcome<ImportedBundle> {
        importedSource = sourceUri
        return Outcome.Ok(importedBundle)
    }
}
