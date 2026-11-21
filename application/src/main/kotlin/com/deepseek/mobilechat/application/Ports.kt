package com.deepseek.mobilechat.application

import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.Compaction
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ContextBudget
import com.deepseek.mobilechat.domain.ContextInjection
import com.deepseek.mobilechat.domain.Folder
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.Tag
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.ConversationId

// ---------------------------------------------------------------------------
// Result envelope
// ---------------------------------------------------------------------------

sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val error: AppError) : Outcome<Nothing>
}

fun <T> outcomeOf(value: T): Outcome<T> = Outcome.Ok(value)

fun <T> Outcome<T>.valueOrNull(): T? = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> null
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Err -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Ok -> transform(value)
    is Outcome.Err -> this
}

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

sealed interface AppError {
    val message: String

    data class Validation(override val message: String) : AppError
    data class BranchConflict(override val message: String) : AppError
    data class BudgetExceeded(val proposal: CompactionProposal, override val message: String = "context exceeds budget") : AppError
    data class ProviderAuth(override val message: String) : AppError
    data class ProviderRateLimit(val retryAfterSeconds: Long? = null, override val message: String = "rate limited") : AppError
    data class ProviderProtocol(override val message: String) : AppError
    data class ProviderUnavailable(override val message: String) : AppError
    data class Network(override val message: String) : AppError
    data class Timeout(override val message: String) : AppError
    data class Storage(override val message: String) : AppError
    data class SecretMissing(override val message: String) : AppError
    data class Unsupported(override val message: String) : AppError
    data class Unknown(override val message: String, val cause: Throwable? = null) : AppError
}

// ---------------------------------------------------------------------------
// Prompt / generation
// ---------------------------------------------------------------------------

data class PromptMessage(
    val role: MessageRole,
    val parts: List<MessagePart>,
    val sourceMessageId: MessageId? = null,
)

data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,
)

data class GenerationRequest(
    val modelRef: ModelRef,
    val systemPrompt: String?,
    val messages: List<PromptMessage>,
    val params: SamplingParams,
    val thinkingLevel: ThinkingLevel,
    val tools: List<ToolSpec> = emptyList(),
    val stream: Boolean = true,
)

sealed interface GenerationEvent {
    data class Started(val atMillis: Long) : GenerationEvent
    data class TextDelta(val text: String) : GenerationEvent
    data class ReasoningDelta(val text: String) : GenerationEvent
    data class ReasoningSignature(val signature: String) : GenerationEvent
    data class ToolCallDelta(val callId: String, val name: String?, val argumentsDelta: String) : GenerationEvent
    data class UsageReport(val usage: com.deepseek.mobilechat.domain.Usage) : GenerationEvent
    data class Finished(val reason: com.deepseek.mobilechat.domain.FinishReason) : GenerationEvent
    data class Failed(val error: AppError) : GenerationEvent
}

// ---------------------------------------------------------------------------
// Context assembly
// ---------------------------------------------------------------------------

enum class BlockKind { SYSTEM_PROMPT, INJECTION, COMPACTION, HISTORY, SEARCH, FILE, EPHEMERAL, CURRENT_USER }

data class ContextBlock(
    val id: String,
    val kind: BlockKind,
    val title: String,
    val tokens: Int,
    val messageIds: List<MessageId> = emptyList(),
)

data class ContextAssembly(
    val conversationId: ConversationId,
    val leafId: MessageId?,
    val blocks: List<ContextBlock>,
    val budget: ContextBudget,
    val prompt: List<PromptMessage>,
    val systemPrompt: String?,
    val params: SamplingParams,
    val modelRef: ModelRef,
    val thinkingLevel: ThinkingLevel,
)

data class CompactionProposal(
    val conversationId: ConversationId,
    val fromMessageId: MessageId,
    val toMessageId: MessageId,
    val coveredCount: Int,
    val summary: String,
    val tokensBefore: Int,
    val tokensAfter: Int,
    val strategyId: String,
)

sealed interface ContextPlan {
    data object Fits : ContextPlan
    data class RequiresConfirmation(val proposal: CompactionProposal) : ContextPlan
    data class Rejected(val error: AppError) : ContextPlan
}

interface ContextStrategy {
    val id: String

    /**
     * @param summarize invoked when the strategy needs a model-generated summary.
     */
    suspend fun plan(
        assembly: ContextAssembly,
        budget: ContextBudget,
        summarize: suspend (List<PromptMessage>) -> String,
    ): ContextPlan
}

// ---------------------------------------------------------------------------
// Ports (implemented by adapters)
// ---------------------------------------------------------------------------

interface ChatCompletionPort {
    fun stream(request: GenerationRequest): kotlinx.coroutines.flow.Flow<GenerationEvent>
}

interface ModelCatalogPort {
    suspend fun listModels(profile: ProviderProfile): Outcome<List<com.deepseek.mobilechat.domain.ModelDescriptor>>
    suspend fun testConnection(profile: ProviderProfile): Outcome<String>
    suspend fun balance(profile: ProviderProfile): BalanceResult
}

sealed interface BalanceResult {
    data class Available(val amount: Double, val currency: String, val label: String) : BalanceResult
    data object Unsupported : BalanceResult
    data class Failed(val error: AppError) : BalanceResult
}

data class SearchOptions(val maxResults: Int = 5)

data class SearchResponse(val query: String, val hits: List<com.deepseek.mobilechat.domain.SearchHit>)

interface SearchPort {
    /**
     * Whether this source can run right now (key present, provider configured).
     * [com.deepseek.mobilechat.application.service.DefaultSearchService] asks
     * before falling back, so an unconfigured source never produces a failure
     * the user has to read.
     */
    suspend fun isConfigured(): Boolean

    suspend fun search(query: String, options: SearchOptions = SearchOptions()): Outcome<SearchResponse>
}

/**
 * Read side of the usage/spend ledger.
 *
 * Aggregation happens in SQL (the columns are denormalised for this reason), so
 * the usage page never has to walk every message row.
 */
interface UsageAggregateStore {
    /** Per-day totals with `dayStartMillis >= sinceMillis`, oldest day first. */
    suspend fun dailyTotals(sinceMillis: Long): List<UsageDay>

    /** Totals per (provider, model) over the same window, costliest first. */
    suspend fun modelTotals(sinceMillis: Long): List<UsageByModel>

    /** Everything one conversation has spent, including its abandoned branches. */
    suspend fun conversationTotals(conversationId: ConversationId): UsageTotals
}

interface ConversationStore {
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<Conversation>>
    fun observe(id: ConversationId): kotlinx.coroutines.flow.Flow<Conversation?>
    fun search(query: String): kotlinx.coroutines.flow.Flow<List<Conversation>>
    suspend fun get(id: ConversationId): Conversation?
    suspend fun upsert(conversation: Conversation)
    suspend fun delete(id: ConversationId)
}

interface MessageStore {
    fun observe(conversationId: ConversationId): kotlinx.coroutines.flow.Flow<List<Message>>
    suspend fun get(id: MessageId): Message?
    suspend fun list(conversationId: ConversationId): List<Message>
    suspend fun append(message: Message)
    suspend fun update(message: Message)
    suspend fun remove(ids: Set<MessageId>)
}

interface PresetStore {
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<Preset>>
    suspend fun get(id: com.deepseek.mobilechat.domain.PresetId): Preset?
    suspend fun upsert(preset: Preset)
    suspend fun delete(id: com.deepseek.mobilechat.domain.PresetId)
}

interface ProviderProfileStore {
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<ProviderProfile>>
    suspend fun get(id: com.deepseek.mobilechat.domain.ProviderId): ProviderProfile?
    suspend fun upsert(profile: ProviderProfile)
    suspend fun delete(id: com.deepseek.mobilechat.domain.ProviderId)
}

interface InjectionStore {
    fun observe(conversationId: ConversationId): kotlinx.coroutines.flow.Flow<List<ContextInjection>>
    suspend fun list(conversationId: ConversationId): List<ContextInjection>
    suspend fun add(injection: ContextInjection)
    suspend fun remove(id: com.deepseek.mobilechat.domain.InjectionId)
}

interface CompactionStore {
    fun observe(conversationId: ConversationId): kotlinx.coroutines.flow.Flow<List<Compaction>>
    suspend fun list(conversationId: ConversationId): List<Compaction>
    suspend fun add(compaction: Compaction)
    suspend fun markUndone(id: com.deepseek.mobilechat.domain.CompactionId, atMillis: Long)
}

interface FolderTagStore {
    fun observeFolders(): kotlinx.coroutines.flow.Flow<List<Folder>>
    fun observeTags(): kotlinx.coroutines.flow.Flow<List<Tag>>
    suspend fun upsertFolder(folder: Folder)
    suspend fun deleteFolder(id: com.deepseek.mobilechat.domain.FolderId)
    suspend fun upsertTag(tag: Tag)
    suspend fun deleteTag(id: com.deepseek.mobilechat.domain.TagId)
}

data class ImportedFile(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
    val sizeBytes: Long,
)

interface AttachmentStore {
    fun observe(conversationId: ConversationId): kotlinx.coroutines.flow.Flow<List<com.deepseek.mobilechat.domain.Attachment>>
    suspend fun get(id: AttachmentId): com.deepseek.mobilechat.domain.Attachment?

    /**
     * [conversationId] is null while the message is still a draft that has no
     * conversation row yet; the attachment is re-linked when the draft is
     * promoted on first send.
     */
    suspend fun save(
        conversationId: ConversationId?,
        file: ImportedFile,
    ): Outcome<com.deepseek.mobilechat.domain.Attachment>

    /** Links draft attachments to the conversation they ended up in. */
    suspend fun linkToConversation(ids: List<AttachmentId>, conversationId: ConversationId)

    suspend fun delete(id: AttachmentId)
}

interface SecretStore {
    suspend fun put(ref: String, value: String)
    suspend fun get(ref: String): String?
    suspend fun delete(ref: String)
}

interface TokenEstimatorPort {
    fun estimate(text: String): Int
    fun estimate(prompt: PromptMessage): Int
}

interface FileImportPort {
    suspend fun import(uri: String): Outcome<ImportedFile>
}

enum class ExportFormat { JSON, MARKDOWN }

data class ExportBundle(
    val conversations: List<Conversation>,
    val messages: List<Message>,
    val attachments: List<com.deepseek.mobilechat.domain.Attachment>,
    val presets: List<Preset>,
    val folders: List<Folder>,
    val tags: List<Tag>,
    val includeAttachmentBytes: Boolean = false,
)

data class ImportedBundle(
    val conversations: List<Conversation>,
    val messages: List<Message>,
    val attachments: List<com.deepseek.mobilechat.domain.Attachment>,
    /** Raw bytes for attachments whose payload was embedded in the export. */
    val attachmentBytes: Map<AttachmentId, ByteArray> = emptyMap(),
    val presets: List<Preset> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val tags: List<Tag> = emptyList(),
)

interface ExportPort {
    suspend fun export(bundle: ExportBundle, format: ExportFormat, targetUri: String): Outcome<Unit>
    suspend fun import(sourceUri: String): Outcome<ImportedBundle>
}

interface ClockPort {
    fun nowMillis(): Long
}

interface IdPort {
    fun newId(): String
}

interface ConnectivityPort {
    fun observe(): kotlinx.coroutines.flow.Flow<Boolean>
}

interface BackgroundExecutionPort {
    suspend fun <T> run(title: String, onCancel: () -> Unit, block: suspend () -> T): T
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

enum class SearchProviderKind {
    /**
     * DeepSeek's own server-side search, reached through its Anthropic-format
     * endpoint. Needs no search credential — it reuses the DeepSeek provider's
     * API key and bills as model tokens only.
     */
    DEEPSEEK,

    /** Keyless fallback: Bing's RSS output. No credential at all. */
    BING,

    TAVILY,
    BRAVE,
    SERPER,
}

data class AppSettings(
    val activeSearchProvider: SearchProviderKind = SearchProviderKind.DEEPSEEK,
    val maxConcurrentGenerations: Int = 2,
    val streamingCheckpointMillis: Long = 300,
    /** "system" | "light" | "dark" */
    val themeMode: String = "system",
    /** False until the first-run guide has been finished or skipped. */
    val firstRunCompleted: Boolean = false,
)

interface SettingsStore {
    fun observe(): kotlinx.coroutines.flow.Flow<AppSettings>
    suspend fun update(settings: AppSettings)
}
