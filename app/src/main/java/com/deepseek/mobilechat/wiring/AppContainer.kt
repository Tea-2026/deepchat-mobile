package com.deepseek.mobilechat.wiring

import android.content.Context
import com.deepseek.mobilechat.application.ChatService
import com.deepseek.mobilechat.application.CompactionStore
import com.deepseek.mobilechat.application.ContextAssembler
import com.deepseek.mobilechat.application.ContextService
import com.deepseek.mobilechat.application.ContextStrategy
import com.deepseek.mobilechat.application.ConversationService
import com.deepseek.mobilechat.application.ConversationStore
import com.deepseek.mobilechat.application.ExportPort
import com.deepseek.mobilechat.application.FileImportPort
import com.deepseek.mobilechat.application.FolderTagStore
import com.deepseek.mobilechat.application.GenerationQueue
import com.deepseek.mobilechat.application.InjectionStore
import com.deepseek.mobilechat.application.MessageStore
import com.deepseek.mobilechat.application.PresetService
import com.deepseek.mobilechat.application.PresetStore
import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.application.ProviderService
import com.deepseek.mobilechat.application.SearchProviderKind
import com.deepseek.mobilechat.application.SearchPort
import com.deepseek.mobilechat.application.SearchService
import com.deepseek.mobilechat.application.SettingsStore
import com.deepseek.mobilechat.application.TransferService
import com.deepseek.mobilechat.application.context.ConstantModelBudgetLookup
import com.deepseek.mobilechat.application.context.DefaultContextAssembler
import com.deepseek.mobilechat.application.context.HeuristicTokenEstimator
import com.deepseek.mobilechat.application.context.strategy.ConfirmCompactionStrategy
import com.deepseek.mobilechat.application.context.strategy.NoopContextStrategy
import com.deepseek.mobilechat.application.service.DefaultChatService
import com.deepseek.mobilechat.application.service.DefaultContextService
import com.deepseek.mobilechat.application.service.DefaultConversationService
import com.deepseek.mobilechat.application.service.DefaultGenerationQueue
import com.deepseek.mobilechat.application.service.DefaultPresetService
import com.deepseek.mobilechat.application.service.DefaultProviderService
import com.deepseek.mobilechat.application.UsageAggregateStore
import com.deepseek.mobilechat.application.UsageService
import com.deepseek.mobilechat.application.service.DefaultSearchService
import com.deepseek.mobilechat.application.service.DefaultUsageService
import com.deepseek.mobilechat.application.service.DefaultTransferService
import com.deepseek.mobilechat.application.service.ModelCatalogCache
import com.deepseek.mobilechat.application.service.StalledGenerationReconciler
import com.deepseek.mobilechat.data.local.bus.ChangeBus
import com.deepseek.mobilechat.data.local.db.AndroidDb
import com.deepseek.mobilechat.data.local.db.AppDatabaseHelper
import com.deepseek.mobilechat.data.local.files.AndroidFileSink
import com.deepseek.mobilechat.data.local.store.SqliteAttachmentStore
import com.deepseek.mobilechat.data.local.store.SqliteCompactionStore
import com.deepseek.mobilechat.data.local.store.SqliteConversationStore
import com.deepseek.mobilechat.data.local.store.SqliteFolderTagStore
import com.deepseek.mobilechat.data.local.store.SqliteInjectionStore
import com.deepseek.mobilechat.data.local.store.SqliteMessageStore
import com.deepseek.mobilechat.data.local.store.SqliteUsageAggregateStore
import com.deepseek.mobilechat.data.local.store.SqlitePresetStore
import com.deepseek.mobilechat.data.local.store.SqliteProviderProfileStore
import com.deepseek.mobilechat.data.remote.catalog.ModelCatalog
import com.deepseek.mobilechat.data.remote.http.OkHttpHttpCaller
import com.deepseek.mobilechat.data.remote.provider.RoutingChatCompletionPort
import com.deepseek.mobilechat.data.remote.search.BingRssSearchAdapter
import com.deepseek.mobilechat.data.remote.search.BraveSearchAdapter
import com.deepseek.mobilechat.data.remote.search.DeepSeekWebSearchAdapter
import com.deepseek.mobilechat.data.remote.search.SerperSearchAdapter
import com.deepseek.mobilechat.data.remote.search.TavilySearchAdapter
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.ContextStrategyIds
import com.deepseek.mobilechat.platform.connectivity.AndroidConnectivityPort
import com.deepseek.mobilechat.platform.crypto.AndroidSecretStore
import com.deepseek.mobilechat.platform.export.ExportPortImpl
import com.deepseek.mobilechat.platform.files.SafFileImportPort
import com.deepseek.mobilechat.platform.service.AndroidBackgroundExecutionPort
import com.deepseek.mobilechat.ui.AppServices
import com.deepseek.mobilechat.ui.screens.settings.SettingsCapability
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The composition root.
 *
 * The only place that knows every implementation, handed to the UI as
 * [AppServices].
 *
 * **Every non-trivial dependency is lazy.** Opening SQLite, touching the
 * Android Keystore and building an OkHttp client all cost tens to hundreds of
 * milliseconds; doing that inside `Application.onCreate` pushed the first frame
 * out by seconds. With `by lazy`, the app pays only when a screen actually
 * needs the capability, and the stores move that work onto their IO dispatchers.
 */
class AppContainer(context: Context) : AppServices, SettingsCapability {

    private val appContext: Context = context.applicationContext

    // -- foundation (cheap, eager) ------------------------------------------
    private val systemClock = SystemClockPort()
    private val ids = UuidIdPort()
    private val bus = ChangeBus()
    private val settings: SettingsStore = PrefsSettingsStore(appContext)
    private val tokenEstimator = HeuristicTokenEstimator()
    private val budgetLookup = ConstantModelBudgetLookup()

    /** Owns startup-only work (database recovery) without blocking the first frame. */
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val clock: com.deepseek.mobilechat.application.ClockPort get() = systemClock
    override val settingsStore: SettingsStore get() = settings
    override val secretStore: com.deepseek.mobilechat.application.SecretStore get() = secrets

    // -- persistence --------------------------------------------------------
    private val db: AndroidDb by lazy { AndroidDb(AppDatabaseHelper(appContext)) }

    override val conversationStore: ConversationStore by lazy {
        SqliteConversationStore(db, bus)
    }
    override val messageStore: MessageStore by lazy { SqliteMessageStore(db, bus) }

    private val stalledGenerations by lazy {
        StalledGenerationReconciler(conversationStore, messageStore)
    }
    override val presetStore: PresetStore by lazy { SqlitePresetStore(db, bus) }
    override val providerStore: ProviderProfileStore by lazy {
        SqliteProviderProfileStore(db, bus)
    }
    private val injectionStore: InjectionStore by lazy { SqliteInjectionStore(db, bus) }
    private val compactionStore: CompactionStore by lazy { SqliteCompactionStore(db, bus) }
    override val folderTagStore: FolderTagStore by lazy { SqliteFolderTagStore(db, bus) }
    override val attachmentStore: com.deepseek.mobilechat.application.AttachmentStore by lazy {
        SqliteAttachmentStore(db, AndroidFileSink(appContext.filesDir), ids, systemClock, bus)
    }

    // -- platform -----------------------------------------------------------
    private val secrets: com.deepseek.mobilechat.application.SecretStore by lazy {
        AndroidSecretStore(appContext)
    }
    override val fileImport: FileImportPort by lazy { SafFileImportPort(appContext) }
    private val exportPort: ExportPort by lazy {
        ExportPortImpl(appContext, ids, systemClock)
    }
    private val connectivity by lazy { AndroidConnectivityPort(appContext) }
    private val background by lazy { AndroidBackgroundExecutionPort(appContext) }

    // -- network ------------------------------------------------------------
    private val http by lazy { OkHttpHttpCaller() }

    /**
     * Attachments are handed to providers as raw bytes. The attachment store
     * lookup is suspend-only, while the adapter contract is synchronous, so we
     * block on the adapter's IO thread for the duration of a local DB read plus
     * one file read. Bounded by the 32 MiB import cap.
     */
    private val resolveAttachmentBytes: (AttachmentId) -> ByteArray? = { id ->
        runBlocking {
            attachmentStore.get(id)?.let { attachment ->
                runCatching { File(attachment.localPath).readBytes() }.getOrNull()
            }
        }
    }

    private val chatCompletion by lazy {
        RoutingChatCompletionPort(providerStore, secrets, resolveAttachmentBytes, http)
    }

    private val modelCatalogPort by lazy { ModelCatalog(secrets, http) }

    private val searchAdapters: Map<SearchProviderKind, SearchPort> by lazy {
        mapOf(
            SearchProviderKind.DEEPSEEK to DeepSeekWebSearchAdapter(providerStore, secrets, http),
            SearchProviderKind.BING to BingRssSearchAdapter(http),
            SearchProviderKind.TAVILY to TavilySearchAdapter(secrets, http),
            SearchProviderKind.BRAVE to BraveSearchAdapter(secrets, http),
            SearchProviderKind.SERPER to SerperSearchAdapter(secrets, http),
        )
    }

    // -- context ------------------------------------------------------------
    override val contextAssembler: ContextAssembler by lazy {
        DefaultContextAssembler(
            conversationStore = conversationStore,
            messageStore = messageStore,
            injectionStore = injectionStore,
            compactionStore = compactionStore,
            presetStore = presetStore,
            providerProfileStore = providerStore,
            attachmentStore = attachmentStore,
            tokenEstimator = tokenEstimator,
            modelBudgetLookup = budgetLookup,
            clock = systemClock,
            idPort = ids,
        )
    }

    private val strategies: Map<String, ContextStrategy> by lazy {
        mapOf(
            ContextStrategyIds.NONE to NoopContextStrategy(),
            ContextStrategyIds.CONFIRM_COMPACTION to ConfirmCompactionStrategy(tokenEstimator),
        )
    }

    // -- services -----------------------------------------------------------
    private val generationQueue by lazy {
        DefaultGenerationQueue(
            settings,
            systemClock,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    override val queue: GenerationQueue get() = generationQueue

    private val modelCatalog by lazy { ModelCatalogCache(modelCatalogPort) }

    override val chat: ChatService by lazy {
        DefaultChatService(
            conversationStore = conversationStore,
            messageStore = messageStore,
            injectionStore = injectionStore,
            contextAssembler = contextAssembler,
            scheduler = generationQueue,
            chatCompletion = chatCompletion,
            clock = systemClock,
            idPort = ids,
            tokenEstimator = tokenEstimator,
            settingsStore = settings,
            modelCatalog = modelCatalog,
            providerProfileStore = providerStore,
            presetStore = presetStore,
            attachmentStore = attachmentStore,
            strategies = strategies,
        )
    }

    /**
     * Settles assistant rows left `STREAMING` by the previous process death.
     *
     * The queue lives in memory, so nothing can be running at this point; an
     * unfinished row is orphaned by definition. Runs on [startupScope] so
     * opening SQLite never delays the first frame, and a recovery failure must
     * not stop the app from launching.
     */
    fun reconcileStalledGenerations() {
        startupScope.launch {
            runCatching { stalledGenerations.reconcile() }
        }
    }

    override val context: ContextService by lazy {
        DefaultContextService(
            contextAssembler = contextAssembler,
            conversationStore = conversationStore,
            messageStore = messageStore,
            injectionStore = injectionStore,
            compactionStore = compactionStore,
            presetStore = presetStore,
            chatCompletion = chatCompletion,
            clock = systemClock,
            idPort = ids,
            strategies = strategies,
        )
    }

    override val conversations: ConversationService by lazy {
        DefaultConversationService(
            conversationStore = conversationStore,
            messageStore = messageStore,
            injectionStore = injectionStore,
            compactionStore = compactionStore,
            attachmentStore = attachmentStore,
            presetStore = presetStore,
            providerProfileStore = providerStore,
            chatCompletion = chatCompletion,
            clock = systemClock,
            idPort = ids,
        )
    }

    override val presets: PresetService by lazy { DefaultPresetService(presetStore) }

    override val providers: ProviderService by lazy {
        DefaultProviderService(providerStore, secrets, modelCatalogPort)
    }

    override val search: SearchService by lazy {
        DefaultSearchService(searchAdapters, settings)
    }

    /**
     * Spend aggregation. `SqliteUsageAggregateStore` reads the denormalised
     * usage columns (schema v3) so the usage screen never walks message JSON.
     */
    private val usageAggregateStore: UsageAggregateStore by lazy {
        SqliteUsageAggregateStore(db)
    }

    override val usage: UsageService by lazy {
        DefaultUsageService(usageAggregateStore, systemClock)
    }

    override val transfer: TransferService by lazy {
        DefaultTransferService(
            conversationStore = conversationStore,
            messageStore = messageStore,
            attachmentStore = attachmentStore,
            presetStore = presetStore,
            folderTagStore = folderTagStore,
            exportPort = exportPort,
            idPort = ids,
        )
    }
}
