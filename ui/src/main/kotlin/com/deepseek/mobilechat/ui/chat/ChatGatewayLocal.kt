package com.deepseek.mobilechat.ui.chat

import androidx.compose.runtime.staticCompositionLocalOf
import com.deepseek.mobilechat.application.log.ChatGateway

/**
 * The chat seam, provided by the composition root next to `LocalAppServices`.
 *
 * The ViewModel takes the gateway as a constructor parameter (never through
 * [com.deepseek.mobilechat.ui.AppServices], which stays free of chat history);
 * `RootScreen` is the only composable that resolves it from the composition.
 *
 * The default throws instead of silently returning a stub: a missing provider is
 * a wiring mistake, and a stub would turn it into an app that looks alive but
 * never shows a message.
 */
val LocalChatGateway = staticCompositionLocalOf<ChatGateway> {
    error("ChatGateway was not provided by the composition root")
}
