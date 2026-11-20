package com.deepseek.mobilechat.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.ui.chat.EnergyGradientPositions
import com.deepseek.mobilechat.ui.chat.EnergyGradientStops
import com.deepseek.mobilechat.ui.chat.ReasoningBlue
import com.deepseek.mobilechat.ui.chat.ReasoningParticles
import com.deepseek.mobilechat.ui.chat.ThinkingStops
import com.deepseek.mobilechat.ui.chat.energyFor
import com.deepseek.mobilechat.ui.chat.fillColorFor
import com.deepseek.mobilechat.ui.chat.particleCountFor
import com.deepseek.mobilechat.ui.chat.speedFor
import com.deepseek.mobilechat.ui.chat.starBrightnessFor
import com.deepseek.mobilechat.ui.chat.starLayerOpacityFor
import com.deepseek.mobilechat.ui.chat.starProgress
import com.deepseek.mobilechat.ui.chat.thinkingDescription
import com.deepseek.mobilechat.ui.chat.thinkingIndexAt
import com.deepseek.mobilechat.ui.chat.thinkingSliderLabel
import com.deepseek.mobilechat.ui.chat.thinkingStopFraction
import com.deepseek.mobilechat.ui.chat.valueColorFor
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The reasoning row of the model bubble: `推理等级 … High` plus the track.
 *
 * Interaction (product decision, 2026-11-20 — supersedes the earlier "detent"
 * version):
 *
 *  - **The knob follows the finger 1:1** while a gesture is live. Position is a
 *    continuous float, so the fill, the nebula and the star dust all move with
 *    the finger instead of jumping between stops.
 *  - **The label/purple chrome follow the nearest stop** (they flip when the
 *    finger crosses a midpoint) so the user always reads *what it would land
 *    on* — but nothing is stored yet.
 *  - **Release snaps to the nearest stop** with a monotonic 180ms tween (no
 *    overshoot), then the stop is committed to the conversation overrides.
 *  - **Tap anywhere** = jump to the finger on press, then the same snap on
 *    release; a tap therefore commits exactly like a drag.
 *
 * The visuals are a port of `dsh-codex-effort-slider/lib/client.js` (blue→violet
 * fill, purple nebula + sweep from the second stop, 22 deterministic particles,
 * purple row outline that fades in with the energy).
 *
 * @param level the committed level (conversation override → preset → OFF).
 * @param onLevelChange called once per gesture, on release, with the stop the
 *   knob snapped to. Never called while the finger is down.
 */
@Composable
fun ThinkingSlider(
    level: ThinkingLevel,
    onLevelChange: (ThinkingLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    val colorScheme = MaterialTheme.colorScheme

    val committedIndex = ThinkingStops.indexOf(level).coerceAtLeast(0)
    // Finger position while a gesture is live (`null` otherwise). This is the
    // only continuously-changing value, and it is read exclusively from draw /
    // layer lambdas, so a drag invalidates drawing — never layout.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    // The stop the label, the value colour and the purple chrome show. It
    // follows the nearest stop *during* the drag too, but only changes on a
    // midpoint crossing (so it does not recompose per frame).
    var nearestIndex by remember { mutableIntStateOf(committedIndex) }
    // The last stop this control asked the host to store; any *other* level it
    // observes came from outside (preset switch, another screen) and is adopted.
    var lastCommittedIndex by remember { mutableIntStateOf(committedIndex) }
    val latestOnLevelChange by rememberUpdatedState(onLevelChange)
    val scope = rememberCoroutineScope()

    // Rest position. Animated only at rest — while dragging the finger wins.
    val knob = remember { Animatable(thinkingStopFraction(level)) }
    val dragging = dragFraction != null

    LaunchedEffect(level) {
        val target = ThinkingStops.indexOf(level).coerceAtLeast(0)
        if (target != lastCommittedIndex) {
            lastCommittedIndex = target
            nearestIndex = target
        }
    }
    // Settle onto the nearest stop whenever the finger is up. `knob` is seeded
    // with the release position before `dragFraction` goes null (see the
    // gesture), so this always starts from the finger and never jumps.
    LaunchedEffect(nearestIndex, dragging) {
        if (dragging) return@LaunchedEffect
        knob.animateTo(
            targetValue = thinkingStopFraction(ThinkingStops[nearestIndex]),
            animationSpec = tween(
                durationMillis = Motion.Durations.Snap,
                easing = Motion.Easings.FastOutSlow,
            ),
        )
    }

    val nearestFraction = thinkingStopFraction(ThinkingStops[nearestIndex])
    val energyTarget = energyFor(nearestFraction)
    // The chrome cross-fades in 200ms (`transition: opacity .2s` in the
    // reference); the geometry itself is the continuous track below.
    val chromeEnergy by animateFloatAsState(
        targetValue = energyTarget,
        animationSpec = tween(durationMillis = 200, easing = Motion.Easings.Standard),
        label = "reasoningEnergy",
    )

    val density = LocalDensity.current
    val knobRadiusPx = with(density) { KnobSize.toPx() / 2f }
    val tickRadiusPx = with(density) { 1.5.dp.toPx() }
    val starRadiusPx = with(density) { 1.5.dp.toPx() }
    val energyStops = remember {
        Array(EnergyGradientStops.size) { index ->
            EnergyGradientPositions[index] to Color(EnergyGradientStops[index])
        }
    }

    var trackWidthPx by remember { mutableFloatStateOf(0f) }

    // One shared clock for the star drift and the sweep. Read exclusively inside
    // the draw lambda, so a running field invalidates drawing — not layout.
    val clock = remember { mutableFloatStateOf(0f) }
    val starfieldRunning = energyTarget > 0.001f
    LaunchedEffect(starfieldRunning) {
        if (!starfieldRunning) return@LaunchedEffect
        val startedAt = withFrameMillis { it }
        while (true) {
            withFrameMillis { now -> clock.floatValue = (now - startedAt) / 1000f }
        }
    }

    val purple = Purple

    /** Displayed track position: the finger while down, the resting knob otherwise. */
    val shownFraction: () -> Float = { dragFraction ?: knob.value }

    /**
     * Ends a live gesture: commits the nearest stop, then hands the finger
     * position to the settle animation.
     */
    fun releaseAt(fraction: Float) {
        val target = thinkingIndexAt(fraction, ThinkingStops.size)
        nearestIndex = target
        lastCommittedIndex = target
        latestOnLevelChange(ThinkingStops[target])
        scope.launch {
            // Seed the resting animation with the finger position first, so the
            // knob glides back instead of teleporting to the old stop.
            knob.snapTo(fraction)
            // A newer press may have taken over while we were snapping.
            if (dragFraction == fraction) dragFraction = null
        }
    }

    Column(
        modifier = modifier
            .border(
                width = 1.dp,
                // `energyOn ? "inset … rgba(168,85,247,.16+.34e)" : undefined`.
                color = if (chromeEnergy > 0.001f) {
                    purple.copy(alpha = 0.16f + 0.34f * chromeEnergy)
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // ── header row: label + current stop ──────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "推理等级",
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            AnimatedContent(
                targetState = nearestIndex,
                transitionSpec = { Motion.morph(initialScale = 0.9f, targetScale = 0.9f) },
                label = "reasoningValue",
            ) { index ->
                val stop = ThinkingStops[index]
                // Off keeps the theme's muted grey; the reference clears the
                // inline colour for the off stop for the same reason.
                val valueColor = valueColorFor(thinkingStopFraction(stop), stop)
                    ?.let { Color(it) }
                    ?: chatColors.mutedText
                Text(
                    text = thinkingSliderLabel(stop),
                    style = MaterialTheme.typography.labelLarge,
                    color = valueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }

        // ── the track ─────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 40dp touch band around a 28dp tube: the knob is as tall as
                // the track (reference), the band only adds finger room.
                .height(TouchHeight)
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
                .pointerInput(knobRadiusPx) {
                    awaitEachGesture {
                        // Safety net: if a previous gesture never saw its
                        // pointer-up (the platform can drop it for very fast
                        // taps), `awaitEachGesture` has still waited for all
                        // pointers to be up before starting this block — so the
                        // stale gesture can be settled and committed here
                        // instead of leaving the track un-stored.
                        val stale = dragFraction
                        if (stale != null) {
                            nearestIndex = thinkingIndexAt(stale, ThinkingStops.size)
                            lastCommittedIndex = nearestIndex
                            latestOnLevelChange(ThinkingStops[nearestIndex])
                            dragFraction = null
                        }

                        val down = awaitFirstDown(requireUnconsumed = false)
                        val usable = (size.width - 2f * knobRadiusPx).coerceAtLeast(1f)
                        fun pctAt(x: Float): Float =
                            ((x - knobRadiusPx) / usable).coerceIn(0f, 1f)

                        down.consume()
                        // Press: the knob jumps to the finger and then tracks it
                        // 1:1. The nearest stop is what the label shows meanwhile.
                        val pressed = pctAt(down.position.x)
                        dragFraction = pressed
                        nearestIndex = thinkingIndexAt(pressed, ThinkingStops.size)

                        var lastX = down.position.x
                        while (true) {
                            val change = awaitPointerEvent()
                                .changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            if (change.position.x != lastX) {
                                lastX = change.position.x
                                val pct = pctAt(change.position.x)
                                dragFraction = pct
                                // The label follows the *nearest* stop, so it
                                // flips on the midpoint without ever writing.
                                val nearest = thinkingIndexAt(pct, ThinkingStops.size)
                                if (nearest != nearestIndex) nearestIndex = nearest
                                change.consume()
                            }
                        }

                        // Release: snap to the nearest stop (the animation is
                        // started by the settle effect) and store it.
                        releaseAt(dragFraction ?: knob.value)
                    }
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = "推理等级：${thinkingDescription(ThinkingStops[nearestIndex])}"
                    stateDescription = thinkingSliderLabel(ThinkingStops[nearestIndex])
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = nearestFraction,
                        range = 0f..1f,
                        steps = ThinkingStops.size - 2,
                    )
                    setProgress { target ->
                        val next = thinkingIndexAt(target, ThinkingStops.size)
                        if (next != nearestIndex) {
                            nearestIndex = next
                            lastCommittedIndex = next
                            onLevelChange(ThinkingStops[next])
                            true
                        } else {
                            false
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val trackShape = RoundedCornerShape(percent = 50)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TrackHeight)
                    // Purple halo that grows with the nebula (the reference's
                    // `0 0 (energy×16px) rgba(168,85,247,.5)` box-shadow).
                    .shadow(
                        elevation = (10f * chromeEnergy).dp,
                        shape = trackShape,
                        clip = false,
                        ambientColor = purple,
                        spotColor = purple,
                    ),
            ) {
                val height = size.height
                val radius = height / 2f
                // Continuous: the finger while down, the animated rest position
                // otherwise. Everything below is derived from this one value.
                val pct = shownFraction()
                val knobX = radius + pct * (size.width - 2f * radius)
                val capsule = CornerRadius(radius, radius)
                val trackPath = Path().apply {
                    // All four corners, not two: the 6-arg RoundRect overload
                    // takes (topLeft, topRight) positionally and leaves the
                    // bottom corners square — which showed up as a square
                    // blue wedge under the knob at the left end.
                    addRoundRect(
                        RoundRect(
                            left = 0f,
                            top = 0f,
                            right = size.width,
                            bottom = height,
                            topLeftCornerRadius = capsule,
                            topRightCornerRadius = capsule,
                            bottomRightCornerRadius = capsule,
                            bottomLeftCornerRadius = capsule,
                        ),
                    )
                }

                // 1. Empty tube.
                drawPath(trackPath, color = chatColors.hairline)

                clipPath(trackPath) {
                    if (knobX > 0.5f) {
                        // 2. Blue fill: the left end is always the reference
                        //    blue, the right end is the current position colour.
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(Color(ReasoningBlue), Color(fillColorFor(pct))),
                                start = Offset.Zero,
                                end = Offset(knobX, 0f),
                            ),
                            size = Size(knobX, height),
                        )

                        val energy = energyFor(pct)
                        if (energy > 0.001f) {
                            // 3. Nebula over the filled part; opacity = energy,
                            //    so it fades in from High onwards.
                            drawRect(
                                brush = Brush.linearGradient(
                                    *energyStops,
                                    start = Offset.Zero,
                                    end = Offset(knobX, 0f),
                                ),
                                size = Size(knobX, height),
                                alpha = energy,
                            )
                            // 4. Sweep: one 2.4s linear pass, right → left
                            //    (`.ces-energy__sweep`).
                            clipRect(right = knobX) {
                                val sweep = (clock.floatValue / SweepSeconds) % 1f
                                val shift = knobX * (1f - 2f * sweep)
                                drawRect(
                                    brush = Brush.linearGradient(
                                        colorStops = arrayOf(
                                            0.18f to Color.Transparent,
                                            0.50f to Color.White.copy(alpha = 0.30f),
                                            0.82f to Color.Transparent,
                                        ),
                                        start = Offset(shift, 0f),
                                        end = Offset(shift + knobX, 0f),
                                    ),
                                    size = Size(knobX, height),
                                    alpha = energy,
                                )
                            }
                        }

                        // 5. Star dust drifting right → left inside the fill.
                        val visible = particleCountFor(pct)
                        if (visible > 0) {
                            val layerAlpha = starLayerOpacityFor(pct)
                            val speed = speedFor(pct)
                            clipRect(right = knobX) {
                                for (index in 0 until visible) {
                                    val progress = starProgress(index, clock.floatValue, speed)
                                    val x = knobX * (1f - progress)
                                    val y = ReasoningParticles[index].y / 100f * height
                                    // The reference fades a dot only at the two
                                    // ends of its pass (6% keyframes).
                                    val edge = when {
                                        progress < 0.06f -> progress / 0.06f
                                        progress > 0.94f -> (1f - progress) / 0.06f
                                        else -> 1f
                                    }
                                    val brightness = starBrightnessFor(index)
                                    val dotRadius = starRadiusPx * brightness
                                    val alpha = layerAlpha * edge
                                    // The reference draws a 3px dot with a
                                    // `0 0 4px` glow, not a soft bokeh ball:
                                    // keep the halo tight and faint.
                                    drawCircle(
                                        color = Color.White.copy(alpha = 0.16f * alpha),
                                        radius = dotRadius * 1.9f,
                                        center = Offset(x, y),
                                    )
                                    drawCircle(
                                        color = Color.White.copy(alpha = 0.85f * alpha),
                                        radius = dotRadius,
                                        center = Offset(x, y),
                                    )
                                }
                            }
                        }
                    }
                }

                // 6. Ticks: one per stop, reached ones bright white.
                val last = ThinkingStops.lastIndex
                for (index in ThinkingStops.indices) {
                    val tx = radius +
                        (if (last <= 0) 0f else index.toFloat() / last) * (size.width - 2f * radius)
                    drawCircle(
                        color = if (index <= nearestIndex) {
                            Color.White.copy(alpha = 0.5f)
                        } else {
                            colorScheme.onSurface.copy(alpha = 0.26f)
                        },
                        radius = tickRadiusPx,
                        center = Offset(tx, radius),
                    )
                }
            }

            // 7. Knob: same diameter as the track ("a ball in a tube"), white.
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        val usable = (trackWidthPx - 2f * knobRadiusPx).coerceAtLeast(0f)
                        translationX = shownFraction() * usable
                    }
                    .size(KnobSize)
                    .shadow(
                        elevation = 2.dp,
                        shape = CircleShape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.34f),
                        spotColor = Color.Black.copy(alpha = 0.34f),
                    )
                    .background(Color.White, CircleShape),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Geometry (the reference's px values, used as dp)
// ---------------------------------------------------------------------------

/** `TRACK_HEIGHT` = `KNOB_SIZE`: the knob is a full circle inside the tube. */
private val TrackHeight = 28.dp

private val KnobSize = TrackHeight

/** Touch band: the tube plus 6dp above and below, so the finger can hit it. */
private val TouchHeight = 40.dp

/** `.ces-energy__sweep` runs for 2.4s. */
private const val SweepSeconds = 2.4f

/** `rgba(168,85,247)` — the reference's nebula accent. */
private val Purple = Color(0xFFA855F7)
