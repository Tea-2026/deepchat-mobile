package com.deepseek.mobilechat.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.ui.AppServices
import com.deepseek.mobilechat.ui.chat.ChatLeading
import com.deepseek.mobilechat.ui.chat.ChatScreen
import com.deepseek.mobilechat.ui.chat.ChatViewModel
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import kotlinx.coroutines.launch

/**
 * The app's home (spec §2): the chat surface with the conversation drawer.
 *
 * This is the only stateful piece of navigation the UI owns:
 *  - it keeps **one** [ChatViewModel], re-pointed with [ChatViewModel.bindTo]
 *    whenever the drawer selects another conversation. One ViewModel means the
 *    unsaved draft (its text and its staged attachments) survives browsing
 *    history exactly as the spec requires;
 *  - the drawer is a `ModalNavigationDrawer`, so swipe-to-open, the scrim and
 *    the edge drag come from the platform rather than from hand-rolled gesture
 *    code;
 *  - everything that leaves this screen (settings, context debug, injections,
 *    export) is a callback, so `:app` keeps owning the navigation graph.
 *
 * @param initialConversationId open a specific conversation instead of the draft.
 */
@Composable
fun RootScreen(
    services: AppServices,
    modifier: Modifier = Modifier,
    initialConversationId: ConversationId? = null,
    onOpenSettings: () -> Unit = {},
    onOpenContextDebug: (ConversationId) -> Unit = {},
    onOpenInjections: (ConversationId) -> Unit = {},
    onOpenExport: (ConversationId) -> Unit = {},
    onOpenUsage: () -> Unit = {},
    onManageProviders: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val chatColors = LocalChatColors.current
    val focusManager = LocalFocusManager.current

    // The selected conversation is persisted as a raw string so a rotation does
    // not silently throw the user back onto the draft.
    var currentRaw by rememberSaveable {
        mutableStateOf(initialConversationId?.raw)
    }
    val current = currentRaw?.let(::ConversationId)

    val viewModel: ChatViewModel = viewModel(
        key = "root-chat",
        factory = ChatViewModel.factory(
            services,
            com.deepseek.mobilechat.ui.chat.LocalChatGateway.current,
            null,
        ),
    )

    LaunchedEffect(currentRaw) {
        viewModel.bindTo(currentRaw?.let(::ConversationId))
    }

    // A draft becomes a real conversation on its first send; the drawer must
    // stop calling it a draft at that moment.
    val activeConversation by viewModel.activeConversationId.collectAsStateWithLifecycle()
    LaunchedEffect(activeConversation) {
        val raw = activeConversation?.raw ?: return@LaunchedEffect
        if (raw != currentRaw) currentRaw = raw
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    /**
     * Drop text focus before the sheet starts moving, so the composer's IME is
     * not left hanging under the drawer. Only one thing is done here: clearing
     * focus already dismisses the IME, and doing both was two relayouts racing
     * the open animation for no benefit.
     */
    fun openDrawer() {
        focusManager.clearFocus()
        scope.launch { drawerState.open() }
    }

    /** Top-bar "新对话" and the drawer's entry share one behaviour. */
    fun startNewConversation() {
        if (currentRaw == null) {
            viewModel.startNewDraft()
        } else {
            currentRaw = null
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        scrimColor = chatColors.scrim,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
                drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(
                    topEnd = 28.dp,
                    bottomEnd = 28.dp,
                ),
            ) {
                RootDrawerContent(
                    services = services,
                    currentConversationId = current,
                    onOpenConversation = { id ->
                        currentRaw = id.raw
                        closeDrawer()
                    },
                    onNewConversation = {
                        startNewConversation()
                        closeDrawer()
                    },
                    onOpenSettings = {
                        closeDrawer()
                        onOpenSettings()
                    },
                    onOpenUsage = {
                        closeDrawer()
                        onOpenUsage()
                    },
                    onConversationDeleted = { id ->
                        if (currentRaw == id.raw) {
                            currentRaw = null
                            viewModel.startNewDraft()
                        }
                    },
                )
            }
        },
    ) {
        ChatScreen(
            services = services,
            viewModel = viewModel,
            leading = ChatLeading.Drawer,
            onLeading = { openDrawer() },
            onOpenContextDebug = { id ->
                currentRaw = id.raw
                onOpenContextDebug(id)
            },
            onOpenInjections = { id ->
                currentRaw = id.raw
                onOpenInjections(id)
            },
            onExport = onOpenExport,
            onNewConversation = { startNewConversation() },
            onOpenSettings = onOpenSettings,
            onManageProviders = onManageProviders,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
