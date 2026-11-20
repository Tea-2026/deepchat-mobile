package com.deepseek.mobilechat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.mobilechat.domain.ProviderProtocol
import com.deepseek.mobilechat.ui.R
import com.deepseek.mobilechat.ui.theme.LocalChatColors

/**
 * Which brand mark represents a model.
 *
 * `ProviderProtocol` only says how to *speak* to a provider, not who makes the
 * model: DeepSeek and OpenAI both speak `OPENAI_COMPATIBLE`. The provider's own
 * name is therefore the tiebreaker, and it is exactly what the user typed when
 * they configured the service — so a provider called "DeepSeek 官方" gets the
 * DeepSeek mark and a generic relay gets the OpenAI one.
 */
enum class BrandMark { DeepSeek, OpenAI, Anthropic, Gemini, Unknown }

private val brandKeywords: List<Pair<BrandMark, List<String>>> = listOf(
    BrandMark.DeepSeek to listOf("deepseek", "深度求索"),
    BrandMark.Anthropic to listOf("anthropic", "claude"),
    BrandMark.Gemini to listOf("gemini", "google", "bard"),
    BrandMark.OpenAI to listOf("openai", "gpt", "chatgpt", "azure"),
)

/**
 * Resolves the brand for a provider: protocol first (it is authoritative for
 * Anthropic/Gemini), then the provider name, then "unknown".
 */
fun resolveBrandMark(protocol: ProviderProtocol?, providerName: String?): BrandMark {
    val name = providerName.orEmpty().lowercase()
    val byName = brandKeywords.firstOrNull { (_, keys) -> keys.any { name.contains(it) } }?.first
    return when (protocol) {
        ProviderProtocol.ANTHROPIC -> BrandMark.Anthropic
        ProviderProtocol.GEMINI -> BrandMark.Gemini
        ProviderProtocol.OPENAI_COMPATIBLE -> byName ?: BrandMark.OpenAI
        null -> byName ?: BrandMark.Unknown
    }
}

private fun BrandMark.drawable(): Int? = when (this) {
    BrandMark.DeepSeek -> R.drawable.ic_brand_deepseek
    BrandMark.OpenAI -> R.drawable.ic_brand_openai
    BrandMark.Anthropic -> R.drawable.ic_brand_anthropic
    BrandMark.Gemini -> R.drawable.ic_brand_gemini
    BrandMark.Unknown -> null
}

/**
 * The model's brand mark on a disc (spec §8): a white-tinted logo on a dark
 * disc in light mode, and on a lifted dark disc in dark mode so it still reads
 * against pure black. Unknown providers fall back to the model name's initial
 * on the accent colour.
 */
@Composable
fun BrandAvatar(
    protocol: ProviderProtocol?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val mark = resolveBrandMark(protocol, name)
    val isDarkPage = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val disc = if (isDarkPage) Color(0xFF2A2F3A) else Color(0xFF111827)
    val drawable = mark.drawable()

    Box(
        modifier = modifier
            .size(size)
            .background(disc, CircleShape)
            .semantics { contentDescription = "$name 模型" },
        contentAlignment = Alignment.Center,
    ) {
        if (drawable != null) {
            Icon(
                painter = painterResource(drawable),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.62f),
            )
        } else {
            Text(
                text = initialOf(name),
                color = Color.White,
                fontSize = (size.value * 0.46f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * The user's own avatar (spec §8): a circular accent-soft disc with a person
 * glyph. There is no account system, so there is nothing to show a photo of.
 */
@Composable
fun UserAvatar(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val chatColors = LocalChatColors.current
    Box(
        modifier = modifier
            .size(size)
            .background(chatColors.accentSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        PersonIcon(
            modifier = Modifier.size(size * 0.62f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            contentDescription = "你",
        )
    }
}

internal fun initialOf(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    val first = trimmed.first()
    return if (first.isLetter()) first.uppercase() else "?"
}
