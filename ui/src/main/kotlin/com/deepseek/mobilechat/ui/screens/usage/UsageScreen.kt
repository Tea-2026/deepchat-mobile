@file:OptIn(ExperimentalMaterial3Api::class)

package com.deepseek.mobilechat.ui.screens.usage

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.UsageByModel
import com.deepseek.mobilechat.application.UsageReport
import com.deepseek.mobilechat.application.UsageService
import com.deepseek.mobilechat.application.UsageTotals
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.animatedEnter
import com.deepseek.mobilechat.ui.motion.messageEnter
import com.deepseek.mobilechat.ui.motion.pressScale
import com.deepseek.mobilechat.ui.screens.common.HintText
import com.deepseek.mobilechat.ui.screens.common.InfoCallout
import com.deepseek.mobilechat.ui.screens.common.ScreenTopBar
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.ShapeTokens
import com.deepseek.mobilechat.ui.theme.Spacing
import com.deepseek.mobilechat.ui.theme.tabular

/**
 * "What does this app cost me?" — one page, in CNY.
 *
 * The report is assembled elsewhere (day boundaries, FX and the price book all
 * live in `:application`), so this screen only chooses a window, renders the
 * three states, and formats the numbers. [UsageTotals.costCny] is already CNY,
 * which is the whole point: no exchange-rate control is exposed here.
 *
 * @param service supplies the report; it is re-read on first composition, on
 *   retry, and whenever the user changes the window.
 * @param onBack leaves the screen (the drawer entry and route live in `:app`).
 */
@Composable
fun UsageScreen(
    service: UsageService,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var windowDays by rememberSaveable { mutableStateOf(DefaultWindowDays) }
    var reloadKey by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<UsageUiState>(UsageUiState.Loading) }

    LaunchedEffect(windowDays, reloadKey) {
        state = UsageUiState.Loading
        state = when (val result = service.report(windowDays)) {
            is Outcome.Ok -> UsageUiState.Loaded(result.value)
            is Outcome.Err -> UsageUiState.Failed(result.error.message.ifBlank { "未知错误" })
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScreenTopBar(
                title = "用量与花费",
                subtitle = "人民币估算，不用管汇率",
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            WindowSelector(
                selectedDays = windowDays,
                onSelect = { days -> if (days != windowDays) windowDays = days },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm),
            )
            AnimatedContent(
                targetState = state,
                transitionSpec = { Motion.morph() },
                label = "usageState",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { current ->
                when (current) {
                    is UsageUiState.Loading -> UsageSkeleton()
                    is UsageUiState.Failed -> UsageError(
                        message = current.message,
                        onRetry = { reloadKey += 1 },
                    )
                    is UsageUiState.Loaded -> UsageContent(current.report)
                }
            }
        }
    }
}

private const val DefaultWindowDays = 30

/** The windows the report service is asked for. Order is the visual order. */
private val WindowOptions = listOf(7, 30, 90)

private sealed interface UsageUiState {
    data object Loading : UsageUiState
    data class Failed(val message: String) : UsageUiState
    data class Loaded(val report: UsageReport) : UsageUiState
}

// ---------------------------------------------------------------------------
// Window control
// ---------------------------------------------------------------------------

/**
 * Three-state segmented control. The moving selection is a colour transition
 * rather than a sliding indicator, which keeps the row stable when the labels
 * are localised later.
 */
@Composable
private fun WindowSelector(
    selectedDays: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .selectableGroup()
            .clip(RoundedCornerShape(percent = 50))
            .background(scheme.surfaceContainerLow)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        WindowOptions.forEach { days ->
            val selected = days == selectedDays
            val container by animateColorAsState(
                targetValue = if (selected) scheme.primary else Color.Transparent,
                animationSpec = tween(Motion.Durations.Short, easing = Motion.Easings.Standard),
                label = "usageWindowContainer",
            )
            val content by animateColorAsState(
                targetValue = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                animationSpec = tween(Motion.Durations.Short, easing = Motion.Easings.Standard),
                label = "usageWindowContent",
            )
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(container)
                    .selectable(
                        selected = selected,
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        role = Role.RadioButton,
                        onClick = { onSelect(days) },
                    )
                    .pressScale(interaction)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$days 天",
                    style = MaterialTheme.typography.labelLarge,
                    color = content,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Loading / error
// ---------------------------------------------------------------------------

/**
 * Skeleton shaped like the real page. A spinner alone would leave the layout
 * collapsed and then shove the content in; placeholder blocks keep the arrival
 * quiet.
 */
@Composable
private fun UsageSkeleton() {
    val transition = rememberInfiniteTransition(label = "usageSkeleton")
    val shimmer by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.Durations.Typing, easing = Motion.Easings.Standard),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "usageSkeletonShimmer",
    )
    val chatColors = LocalChatColors.current
    val placeholder = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = shimmer)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.sm, bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.6.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = "正在读取用量…",
                style = MaterialTheme.typography.bodySmall,
                color = chatColors.mutedText,
            )
        }
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp),
            color = placeholder,
            shape = ShapeTokens.Card,
        )
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .height(226.dp),
            color = placeholder,
            shape = ShapeTokens.Card,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            repeat(3) {
                SkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    color = placeholder,
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }
    }
}

@Composable
private fun SkeletonBlock(modifier: Modifier, color: Color, shape: Shape) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(color),
    )
}

/** The only state allowed to be empty: say why, and offer the retry. */
@Composable
private fun UsageError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        InfoCallout(
            text = "无法读取用量：$message",
            error = true,
        )
        Spacer(Modifier.height(Spacing.md))
        Button(onClick = onRetry) {
            Text("重试")
        }
    }
}

// ---------------------------------------------------------------------------
// Content
// ---------------------------------------------------------------------------

@Composable
private fun UsageContent(report: UsageReport) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.sm, bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        HeadlineCard(
            report = report,
            modifier = Modifier.animatedEnter(key = report),
        )
        DailySpendCard(
            report = report,
            modifier = Modifier.animatedEnter(
                key = report,
                delayMillis = Motion.Durations.Stagger,
            ),
        )
        ModelBreakdownCard(
            report = report,
            modifier = Modifier.animatedEnter(
                key = report,
                delayMillis = Motion.Durations.Stagger * 2,
            ),
        )
        HintText(
            text = "费用按内置价格表估算，供应商可能调整价格。",
            modifier = Modifier.padding(horizontal = Spacing.xs),
        )
    }
}

/**
 * Today, the selected window, and total tokens. Values count up from zero on
 * arrival instead of appearing as a finished string; the 本月 line is only
 * present when the returned days actually cover the whole month.
 */
@Composable
private fun HeadlineCard(report: UsageReport, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val chatColors = LocalChatColors.current
    val today = report.days.lastOrNull()?.totals ?: UsageTotals()
    val monthCny = monthToDateCny(report.days)

    val todayValue = animateFromZero(today.costCny)
    val windowValue = animateFromZero(report.totals.costCny)
    val tokensValue = animateFromZero(report.totals.totalTokens.toDouble())

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ShapeTokens.Card,
        color = scheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "今日花费",
                        style = MaterialTheme.typography.labelSmall,
                        color = chatColors.mutedText,
                    )
                    Text(
                        text = formatCny(todayValue),
                        style = MaterialTheme.typography.headlineSmall.tabular(),
                        color = scheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = "近 ${report.windowDays} 天",
                        style = MaterialTheme.typography.labelSmall,
                        color = chatColors.mutedText,
                    )
                    Text(
                        text = formatCny(windowValue),
                        style = MaterialTheme.typography.headlineSmall.tabular(),
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(color = chatColors.divider)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "总 tokens",
                    style = MaterialTheme.typography.labelMedium,
                    color = chatColors.mutedText,
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = formatTokens(tokensValue.toLong()),
                    style = MaterialTheme.typography.titleLarge.tabular(),
                    color = scheme.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                if (monthCny != null) {
                    Text(
                        text = "本月估算",
                        style = MaterialTheme.typography.bodySmall,
                        color = chatColors.mutedText,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = formatCny(monthCny),
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = scheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun DailySpendCard(report: UsageReport, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val chatColors = LocalChatColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ShapeTokens.Card,
        color = scheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            Text(
                text = "每日花费",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
            )
            Text(
                text = "按本地日历日",
                style = MaterialTheme.typography.bodySmall,
                color = chatColors.mutedText,
            )
            Spacer(Modifier.height(Spacing.sm))
            DailyBarChart(
                report = report,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(196.dp),
            )
        }
    }
}

/**
 * Hand-drawn bar chart — one bar per returned day.
 *
 * Design rules worth keeping:
 * - a zero-cost day paints nothing at all (the axis remains), so an empty slot
 *   cannot be mistaken for a 1px bar of real data;
 * - the costliest day carries its own label, and only a handful of dates are
 *   printed on the axis;
 * - colours come from the scheme and [LocalChatColors], so light and dark both
 *   pass without a second palette.
 */
@Composable
private fun DailyBarChart(report: UsageReport, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val chatColors = LocalChatColors.current
    val days = report.days

    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = chatColors.mutedText)
    val peakStyle = MaterialTheme.typography.labelMedium.copy(color = scheme.onSurface)
    val emptyStyle = MaterialTheme.typography.bodySmall.copy(color = chatColors.mutedText)
    val textMeasurer = rememberTextMeasurer()

    val rawMax = days.maxOfOrNull { it.totals.costCny } ?: 0.0
    val maxCost = days
        .map { it.totals.costCny }
        .filter { it.isFinite() && it > 0.0 }
        .maxOrNull()
        ?: 0.0
    val peakIndex = if (maxCost > 0.0) {
        days.indexOfFirst { it.totals.costCny == maxCost }
    } else {
        -1
    }
    val peakLabel = if (peakIndex >= 0) formatCny(maxCost) else null
    val dates = remember(days) {
        axisLabelIndices(days.size).map { index -> index to dateLabel(days[index].dayStartMillis) }
    }
    val description = if (maxCost > 0.0) {
        "每日花费柱状图，共 ${days.size} 天，最高 ${formatCny(maxCost)}"
    } else {
        "每日花费柱状图，这段时间没有花费"
    }

    val progress = remember(report) { Animatable(0f) }
    LaunchedEffect(report) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(Motion.Durations.Long, easing = Motion.Easings.Emphasized),
        )
    }

    Canvas(
        modifier = modifier.semantics { contentDescription = description },
    ) {
        val axisHeight = 20.dp.toPx()
        val topReserve = 24.dp.toPx()
        val bottom = size.height - axisHeight
        val top = topReserve
        val chartHeight = (bottom - top).coerceAtLeast(1f)
        val hairline = 1.dp.toPx()

        drawLine(
            color = chatColors.divider,
            start = Offset(0f, bottom),
            end = Offset(size.width, bottom),
            strokeWidth = hairline,
        )
        listOf(0.33f, 0.66f).forEach { fraction ->
            val y = bottom - chartHeight * fraction
            drawLine(
                color = chatColors.divider.copy(alpha = 0.45f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = hairline,
            )
        }

        if (days.isEmpty()) {
            drawCenteredText(
                textMeasurer = textMeasurer,
                text = "暂无数据",
                style = emptyStyle,
                centerX = size.width / 2f,
                centerY = top + chartHeight / 2f,
            )
            return@Canvas
        }

        if (maxCost <= 0.0) {
            drawCenteredText(
                textMeasurer = textMeasurer,
                text = "这段时间还没有花费",
                style = emptyStyle,
                centerX = size.width / 2f,
                centerY = top + chartHeight / 2f,
            )
        } else {
            val slot = size.width / days.size
            val barWidth = (slot * 0.55f).coerceIn(2.dp.toPx(), 22.dp.toPx())
            val minBarHeight = 2.5.dp.toPx()

            days.forEachIndexed { index, day ->
                val cost = day.totals.costCny
                if (!cost.isFinite() || !(cost > 0.0)) return@forEachIndexed
                val fraction = (cost / maxCost).toFloat().coerceIn(0f, 1f)
                val grown = chartHeight * fraction * progress.value
                val barHeight = if (grown > 0f) {
                    grown.coerceAtLeast(minBarHeight * progress.value)
                } else {
                    0f
                }
                if (barHeight <= 0f) return@forEachIndexed
                val left = index * slot + (slot - barWidth) / 2f
                drawRoundRect(
                    color = if (index == peakIndex) {
                        scheme.primary
                    } else {
                        scheme.primary.copy(alpha = 0.55f)
                    },
                    topLeft = Offset(left, bottom - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                )
            }

            if (peakIndex >= 0 && peakLabel != null) {
                val layout = textMeasurer.measure(peakLabel, peakStyle)
                val peakBarHeight = (chartHeight * progress.value).let { grown ->
                    if (grown > 0f) grown.coerceAtLeast(minBarHeight * progress.value) else 0f
                }
                val labelY = (bottom - peakBarHeight - layout.size.height - 2.dp.toPx())
                    .coerceAtLeast(0f)
                val center = peakIndex * slot + slot / 2f
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x = (center - layout.size.width / 2f)
                            .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f)),
                        y = labelY,
                    ),
                    alpha = progress.value,
                )
            }
        }

        dates.forEach { (index, text) ->
            val layout = textMeasurer.measure(text, labelStyle)
            val slot = size.width / days.size
            val x = (index * slot + slot / 2f - layout.size.width / 2f)
                .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(x, size.height - layout.size.height),
            )
        }
    }
}

private fun DrawScope.drawCenteredText(
    textMeasurer: TextMeasurer,
    text: String,
    style: TextStyle,
    centerX: Float,
    centerY: Float,
) {
    val layout = textMeasurer.measure(text, style)
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            x = centerX - layout.size.width / 2f,
            y = centerY - layout.size.height / 2f,
        ),
    )
}

@Composable
private fun ModelBreakdownCard(report: UsageReport, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val chatColors = LocalChatColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ShapeTokens.Card,
        color = scheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = "按模型",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
            )
            Text(
                text = "按花费从高到低",
                style = MaterialTheme.typography.bodySmall,
                color = chatColors.mutedText,
            )
            if (report.byModel.isEmpty()) {
                HintText("这段时间还没有模型调用记录。")
            } else {
                report.byModel.forEachIndexed { index, item ->
                    ModelRow(
                        item = item,
                        share = shareOfTotal(
                            partCost = item.totals.costCny,
                            totalCost = report.totals.costCny,
                            partTokens = item.totals.totalTokens,
                            totalTokens = report.totals.totalTokens,
                        ),
                        index = index,
                        animationKey = report.windowDays to item,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    item: UsageByModel,
    share: Double,
    index: Int,
    animationKey: Any,
) {
    val scheme = MaterialTheme.colorScheme
    val chatColors = LocalChatColors.current

    val progress = remember(animationKey) { Animatable(0f) }
    LaunchedEffect(animationKey) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(Motion.Durations.Long, easing = Motion.Easings.Emphasized),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .messageEnter(key = animationKey, index = index)
            .padding(top = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = modelDisplayName(item.modelId, item.providerId),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = formatShare(share),
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
            )
        }
        Text(
            text = "${formatTokens(item.totals.totalTokens)} tokens · ${formatCny(item.totals.costCny)}",
            style = MaterialTheme.typography.bodySmall,
            color = chatColors.mutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(chatColors.divider.copy(alpha = 0.55f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = (share * progress.value).toFloat().coerceIn(0f, 1f))
                    .background(scheme.primary, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/**
 * Counts a number up on arrival. The animation drives a 0→1 progress and the
 * displayed value is `target * progress`, so the final frame is exactly the
 * reported `Double` — animating the amount itself through a `Float` would lose
 * the cents once a window costs more than a few hundred yuan.
 */
@Composable
private fun animateFromZero(target: Double): Double {
    val safe = if (target.isFinite()) target.coerceAtLeast(0.0) else 0.0
    val progress = remember { Animatable(0f) }
    LaunchedEffect(safe) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(Motion.Durations.Long, easing = Motion.Easings.Emphasized),
        )
    }
    return safe * progress.value
}
