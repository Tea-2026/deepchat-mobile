package com.deepseek.mobilechat.ui.chat

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.ui.AppServices
import com.deepseek.mobilechat.ui.components.ChatComposer
import com.deepseek.mobilechat.ui.components.ChatEmptyState
import com.deepseek.mobilechat.ui.components.CompactionDialog
import com.deepseek.mobilechat.ui.components.LocalAttachmentResolver
import com.deepseek.mobilechat.ui.components.rememberCopyToClipboard
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.animatedEnter
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Must match `ui_camera_file_provider_authority` in the module manifest
 * (`res/values/ui_provider.xml`). Kept as a literal rather than derived from
 * `packageName` so the two cannot drift when an applicationIdSuffix is added.
 */
private const val CameraFileProviderAuthority = "com.deepseek.mobilechat.ui.fileprovider"

// The document picker offers these; the wildcard MIME stays last as the catch-all.
private val AttachmentMimeTypes = arrayOf(
    "image/*",
    "text/*",
    "application/pdf",
    "application/json",
    "*/*",
)

/**
 * The home screen (spec §2/§3): a single conversation surface.
 *
 * Stateful entry point — owns the snackbar, the clipboard, the pickers and the
 * half-screen panel state; the conversation itself (including the unsaved
 * draft) lives in [ChatViewModel], so switching conversations never loses what
 * was typed.
 *
 * @param onLeading the top-left action. With [ChatLeading.Drawer] it opens the
 *   navigation drawer; with [ChatLeading.Back] it leaves the screen.
 */
@Composable
fun ChatScreen(
    services: AppServices,
    viewModel: ChatViewModel,
    onLeading: () -> Unit,
    onOpenContextDebug: (ConversationId) -> Unit,
    onOpenInjections: (ConversationId) -> Unit,
    onExport: ((ConversationId) -> Unit)? = null,
    modifier: Modifier = Modifier,
    leading: ChatLeading = ChatLeading.Drawer,
    onNewConversation: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onManageProviders: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val copyToClipboard = rememberCopyToClipboard()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val listState = rememberLazyListState()
    var panel by remember { mutableStateOf<ChatPanel?>(null) }
    var editingMessage by remember { mutableStateOf<MessageId?>(null) }

    fun notify(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    // Opening a panel and the keyboard are mutually exclusive (spec §5).
    fun openPanel(target: ChatPanel) {
        focusManager.clearFocus()
        keyboard?.hide()
        panel = target
    }

    LaunchedEffect(viewModel) {
        viewModel.errors.collect { notify(it) }
    }
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notify(it) }
    }

    // SAF document picker: the ViewModel imports + persists before staging.
    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.attach(uri.toString())
    }
    // System photo picker: no storage permission required.
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.attach(uri.toString())
    }
    // Camera app → a FileProvider URI → the same import path.
    val cameraTarget = remember { mutableStateOf<Uri?>(null) }
    // StartActivityForResult rather than TakePicture: the latter never grants the
    // camera app write access to the target URI, and the file then comes back empty.
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = cameraTarget.value
        cameraTarget.value = null
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            viewModel.attach(uri.toString())
        }
    }

    fun takePhoto() {
        runCatching {
            val directory = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(directory, "shot-${UUID.randomUUID()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                CameraFileProviderAuthority,
                file,
            )
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                // The grant has to be explicit: a FileProvider URI is private to
                // this app, and ClipData is what some camera apps read instead.
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("photo", uri)
            }
            cameraTarget.value = uri
            cameraLauncher.launch(intent)
        }.onFailure { notify("无法启动相机：${it.message ?: "未知错误"}") }
    }

    fun requireConversation(action: (ConversationId) -> Unit) {
        val id = state.conversationId
        if (id == null) {
            notify("先发一条消息，这个会话才有上下文")
        } else {
            action(id)
        }
    }

    CompositionLocalProvider(
        LocalAttachmentResolver provides { id -> services.attachmentStore.get(id) },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            modifier = modifier,
            topBar = {
                ChatTopBar(
                    title = state.title,
                    subtitle = state.subtitle,
                    scrolled = listState.canScrollBackward,
                    injectionCount = state.injections.size,
                    leading = leading,
                    onLeading = onLeading,
                    onOpenContextDebug = { requireConversation(onOpenContextDebug) },
                    onOpenInjections = { requireConversation(onOpenInjections) },
                    onExport = onExport?.let { export ->
                        { requireConversation(export) }
                    },
                    onNewConversation = onNewConversation,
                    onOpenSettings = onOpenSettings,
                )
            },
            snackbarHost = {},
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .animatedEnter(offsetY = 6.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = state.isEmpty,
                        transitionSpec = { Motion.morph() },
                        label = "transcript",
                        modifier = Modifier.fillMaxSize(),
                    ) { empty ->
                        if (empty && state.isLoadingConversation) {
                            LoadingConversation()
                        } else if (empty) {
                            ChatEmptyState(
                                title = if (state.isDraft) "开始一段对话" else "这个会话还是空的",
                            )
                        } else {
                            ChatMessageList(
                                messages = state.messages,
                                branches = state.branches,
                                listState = listState,
                                editing = editingMessage,
                                modelName = state.activeModel.displayName,
                                protocol = state.activeModel.protocol,
                                activeProviderId = state.activeModel.ref?.providerId,
                                thinkingSeconds = state.thinkingSeconds,
                                onRequestActions = { message ->
                                    openPanel(ChatPanel.Actions(message.id))
                                },
                                onCancelEdit = { editingMessage = null },
                                onSaveEdit = { id, text ->
                                    editingMessage = null
                                    viewModel.edit(id, text)
                                },
                                onSwitchBranch = viewModel::switchBranch,
                                onRetry = viewModel::retry,
                                onContinue = viewModel::continueGeneration,
                            )
                        }
                    }
                    // Anchored above the composer rather than at the window edge,
                    // so a toast never covers the input area.
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    )
                }

                ChatComposer(
                    text = state.draftText,
                    onTextChange = viewModel::updateDraftText,
                    isGenerating = state.isGenerating,
                    isSearching = state.isSearching,
                    isSending = state.isSending,
                    pendingAttachments = state.pendingAttachments,
                    attachmentBusy = state.attachmentBusy,
                    searchEnabled = state.searchEnabled,
                    onToggleSearch = viewModel::toggleSearch,
                    model = state.activeModel,
                    thinkingLevel = state.thinkingLevel,
                    onOpenAttachSheet = { openPanel(ChatPanel.Attach) },
                    onOpenModelPicker = { openPanel(ChatPanel.Model) },
                    onThinkingLevelChange = viewModel::setThinkingLevel,
                    onRemoveAttachment = viewModel::removeAttachment,
                    onSend = {
                        val text = state.draftText
                        if (state.canSend) {
                            focusManager.clearFocus()
                            viewModel.send(text)
                        }
                    },
                    onStop = viewModel::stop,
                    onInputFocused = {
                        // Focus wins over a panel: whatever was open closes.
                        if (panel != null) panel = null
                    },
                )
            }
        }

        CompactionDialog(
            proposal = state.compactionProposal,
            onConfirm = viewModel::confirmCompaction,
            onDismiss = viewModel::dismissCompaction,
        )

        ChatPanelHost(
            panel = panel,
            services = services,
            state = state,
            onDismiss = { panel = null },
            onAttachUri = viewModel::attach,
            onTakePhoto = ::takePhoto,
            onPickImage = {
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onPickFile = { documentPicker.launch(AttachmentMimeTypes) },
            onOpenAdvanced = { panel = ChatPanel.Advanced },
            onOpenSystemPrompt = { panel = ChatPanel.Prompt },
            onSelectModel = viewModel::selectModel,
            onSaveParams = viewModel::saveParams,
            onResetParams = viewModel::resetParams,
            onSaveSystemPrompt = viewModel::saveSystemPrompt,
            onManageProviders = onManageProviders,
            onCopyMessage = { message: Message ->
                if (message.text.isNotBlank()) {
                    copyToClipboard(message.text)
                    notify("已复制")
                }
            },
            onEditMessage = { id -> editingMessage = id },
            onRegenerateMessage = viewModel::regenerate,
            onPinMessage = viewModel::pin,
            onForkMessage = viewModel::switchBranch,
            onDeleteMessage = viewModel::delete,
        )
    }
}

/** Shown for the frame(s) between selecting a conversation and its transcript. */
@Composable
private fun LoadingConversation() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "正在加载会话…",
                style = MaterialTheme.typography.labelSmall,
                color = LocalChatColors.current.mutedText,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

/**
 * Compatibility entry point: one conversation, created and owned by this
 * composable, with a back arrow instead of the drawer.
 *
 * Kept so a host that has not moved to `RootScreen` yet still compiles and
 * behaves (it simply has no drawer).
 */
@Composable
fun ChatScreen(
    services: AppServices,
    conversationId: ConversationId,
    onBack: () -> Unit,
    onOpenContextDebug: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInjections: () -> Unit,
) {
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(services, conversationId),
    )
    ChatScreen(
        services = services,
        viewModel = viewModel,
        leading = ChatLeading.Back,
        onLeading = onBack,
        onOpenContextDebug = { onOpenContextDebug() },
        onOpenInjections = { onOpenInjections() },
        onOpenSettings = onOpenSettings,
    )
}
