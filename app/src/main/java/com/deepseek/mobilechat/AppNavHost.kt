package com.deepseek.mobilechat

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.ui.AppServices
import com.deepseek.mobilechat.ui.navigation.RootScreen
import com.deepseek.mobilechat.ui.screens.onboarding.FirstRunGuide
import com.deepseek.mobilechat.ui.screens.context.ContextDebugScreen
import com.deepseek.mobilechat.ui.screens.injections.InjectionsScreen
import com.deepseek.mobilechat.ui.screens.settings.SettingsScreen
import com.deepseek.mobilechat.ui.screens.settings.settingsCapability
import com.deepseek.mobilechat.ui.screens.transfer.TransferScreen
import com.deepseek.mobilechat.ui.screens.usage.UsageScreen
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import kotlinx.coroutines.flow.first

private object Routes {
    const val ROOT = "root"
    const val SETTINGS = "settings"
    const val TRANSFER = "transfer"
    const val CONTEXT = "context/{conversationId}"
    const val INJECTIONS = "injections/{conversationId}"
    const val USAGE = "usage"

    fun context(id: String) = "context/$id"
    fun injections(id: String) = "injections/$id"
}

@Composable
fun AppNavHost(services: AppServices) {
    val nav = rememberNavController()

    NavHost(
        navController = nav,
        startDestination = Routes.ROOT,
        enterTransition = {
            fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 12 }
        },
        exitTransition = {
            fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { -it / 16 }
        },
        popEnterTransition = {
            fadeIn(tween(220)) + slideInHorizontally(tween(220)) { -it / 12 }
        },
        popExitTransition = {
            fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 16 }
        },
    ) {
        composable(Routes.ROOT) {
            // The first-run guide replaces the chat home until it has been
            // finished or skipped, and only when there is nothing to chat with
            // yet — an existing user never sees it again.
            var showGuide by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(Unit) {
                val settings = services.settingsCapability()?.settingsStore?.observe()?.first()
                val hasProvider = runCatching { services.providers.observe().first() }
                    .getOrDefault(emptyList())
                    .isNotEmpty()
                showGuide = settings?.firstRunCompleted == false && !hasProvider
            }
            when (showGuide) {
                null -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LocalChatColors.current.assistantBubble),
                )

                true -> FirstRunGuide(services = services, onFinished = { showGuide = false })

                false -> RootScreen(
                    services = services,
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenContextDebug = { id -> nav.navigate(Routes.context(id.raw)) },
                    onOpenInjections = { id -> nav.navigate(Routes.injections(id.raw)) },
                    onOpenExport = { nav.navigate(Routes.TRANSFER) },
                    onOpenUsage = { nav.navigate(Routes.USAGE) },
                    onManageProviders = { nav.navigate(Routes.SETTINGS) },
                )
            }
        }

        composable(Routes.CONTEXT) { entry ->
            val id = entry.arguments?.getString("conversationId").orEmpty()
            ContextDebugScreen(
                services = services,
                conversationId = ConversationId(id),
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.INJECTIONS) { entry ->
            val id = entry.arguments?.getString("conversationId").orEmpty()
            InjectionsScreen(
                services = services,
                conversationId = ConversationId(id),
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                services = services,
                onBack = { nav.popBackStack() },
                onOpenTransfer = { nav.navigate(Routes.TRANSFER) },
            )
        }

        composable(Routes.USAGE) {
            UsageScreen(
                service = services.usage,
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.TRANSFER) {
            TransferScreen(
                services = services,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
