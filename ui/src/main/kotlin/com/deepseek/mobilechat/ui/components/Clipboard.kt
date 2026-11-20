package com.deepseek.mobilechat.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Copy-to-clipboard without depending on a Compose clipboard composition local
 * (which has churned across versions). Only text is ever copied, so the plain
 * platform manager is enough.
 */
@Composable
fun rememberCopyToClipboard(label: String = "消息"): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { text: String ->
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            manager?.setPrimaryClip(ClipData.newPlainText(label, text))
        }
    }
}
