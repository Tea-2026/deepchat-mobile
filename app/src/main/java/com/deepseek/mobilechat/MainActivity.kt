package com.deepseek.mobilechat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.deepseek.mobilechat.application.AppSettings
import com.deepseek.mobilechat.ui.LocalAppServices
import com.deepseek.mobilechat.ui.chat.LocalChatGateway
import com.deepseek.mobilechat.ui.screens.settings.SettingsCapability
import com.deepseek.mobilechat.ui.theme.MobileChatTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as MobileChatApp).container

        setContent {
            val settingsStore = (container as? SettingsCapability)?.settingsStore
            val settings by (settingsStore?.observe()
                ?: kotlinx.coroutines.flow.flowOf(AppSettings()))
                .collectAsState(initial = AppSettings())

            val darkTheme = when (settings.themeMode) {
                "light" -> false
                "dark" -> true
                else -> null
            }

            MobileChatTheme(darkTheme = darkTheme) {
                CompositionLocalProvider(
                    LocalAppServices provides container,
                    // The chat screen talks to the engine, never to the stores.
                    LocalChatGateway provides container.chatGateway,
                ) {
                    AppNavHost(services = container)
                }
            }
        }
    }
}
