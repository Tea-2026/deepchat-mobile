package com.deepseek.mobilechat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.ui.motion.animatedEnter
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing

/**
 * The first thing a new conversation shows.
 *
 * Deliberately almost empty: one calm mark and the title. No subtitle, no
 * example prompts, no explanation of where messages are stored — the composer
 * directly below already says what to do with this screen.
 */
@Composable
fun ChatEmptyState(
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            EmptyMark(
                modifier = Modifier
                    .size(44.dp)
                    .animatedEnter(),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .animatedEnter(delayMillis = 60),
            )
        }
    }
}

/**
 * A soft rounded tile with a speech bubble in it — no ring, no gradient, no
 * floating dot. The accent appears once, at low intensity, so the mark reads as
 * part of the page instead of as an illustration.
 */
@Composable
private fun EmptyMark(modifier: Modifier = Modifier) {
    // A 44dp tile with a 14dp radius — a squircle, deliberately not a ring and
    // not a circle: it must not compete with the round send button below.
    Box(
        modifier = modifier.background(
            color = LocalChatColors.current.accentSoft,
            shape = RoundedCornerShape(14.dp),
        ),
        contentAlignment = Alignment.Center,
    ) {
        ChatBubbleIcon(
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
