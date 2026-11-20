package com.deepseek.mobilechat.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The single source of motion truth for `:ui`.
 *
 * Screens must never write `tween(300)` inline: durations, easings and springs
 * come from here, sized to the three temporal jobs of the product —
 * acknowledgement (short), travel (medium), and arrival (long).
 */
object Motion {

    object Durations {
        /** Press feedback, colour swaps, small chrome. */
        const val Short = 160

        /** Standard travel: enter, expand, slide, list placement. */
        const val Medium = 240

        /** Deliberate arrivals: staggered content, dialogs, branch slides. */
        const val Long = 320

        /** Streaming caret half-period. */
        const val Caret = 530

        /** One full cycle of the "waiting for first token" dots. */
        const val Typing = 1200

        /** Spec §1: the navigation drawer travels out in 260ms. */
        const val Drawer = 260

        /** Spec §1: bottom panels arrive in 280ms… */
        const val SheetEnter = 280

        /** …and leave in 220ms (leaving must always win the race). */
        const val SheetExit = 220

        /** Offset between two items of a staggered arrival (drawer rows). */
        const val Stagger = 40

        /**
         * Reasoning slider: settle from the released finger position onto the
         * nearest stop.
         *
         * Paired with [Easings.FastOutSlow] this is monotonic (no overshoot) and
         * short enough to feel like a snap rather than an animation.
         */
        const val Snap = 180
    }

    object Easings {
        /** Default for things moving between two resting states. */
        val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        /** Slow start, confident finish — used for arrivals and morphs. */
        val Emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

        /** Decelerating only; used for content that fades in place. */
        val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

        /** For things leaving; slightly faster than [Decelerate]. */
        val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

        /**
         * Material's `FastOutSlowIn`: accelerates a little, decelerates a lot.
         * Used where a value must travel and *stop* — never overshoots, which
         * is why the reasoning slider's snap-back uses it instead of a spring.
         */
        val FastOutSlow: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    }

    object Springs {
        /** Small controls snapping back (press scale, chips). */
        val Light = spring<Float>(dampingRatio = 0.9f, stiffness = 900f)

        /** Default for layout travel that should feel physical. */
        val Medium = spring<Float>(dampingRatio = 0.82f, stiffness = 420f)

        /** Large surfaces (sheets, dialogs, bubble lists). */
        val Heavy = spring<Float>(dampingRatio = 0.78f, stiffness = 200f)
    }

    /** Enter transition for in-place content (fade + subtle lift). */
    fun enter(): EnterTransition =
        fadeIn(animationSpec = tween(Durations.Medium, easing = Easings.Decelerate))

    /** Exit transition paired with [enter]. */
    fun exit(): ExitTransition =
        fadeOut(animationSpec = tween(Durations.Short, easing = Easings.Accelerate))

    /** Vertical accordion — injection panel, action bar, inline editors. */
    fun enterFromTop(): EnterTransition =
        fadeIn(animationSpec = tween(Durations.Medium, easing = Easings.Decelerate)) +
            expandVertically(
                animationSpec = tween(Durations.Medium, easing = Easings.Emphasized),
                expandFrom = Alignment.Top,
            )

    fun exitToTop(): ExitTransition =
        fadeOut(animationSpec = tween(Durations.Short, easing = Easings.Accelerate)) +
            shrinkVertically(
                animationSpec = tween(Durations.Short, easing = Easings.Standard),
                shrinkTowards = Alignment.Top,
            )

    /** Bottom sheets and snackbar-like surfaces. */
    fun enterFromBottom(): EnterTransition =
        fadeIn(animationSpec = tween(Durations.Medium, easing = Easings.Decelerate)) +
            slideInVertically(
                animationSpec = tween(Durations.Medium, easing = Easings.Emphasized),
                initialOffsetY = { height -> height / 4 },
            )

    fun exitToBottom(): ExitTransition =
        fadeOut(animationSpec = tween(Durations.Short, easing = Easings.Accelerate)) +
            slideOutVertically(
                animationSpec = tween(Durations.Short, easing = Easings.Standard),
                targetOffsetY = { height -> height / 4 },
            )

    /**
     * Content morph used by `AnimatedContent`: the outgoing copy leaves a touch
     * faster than the incoming one arrives, so the change never reads as a cut.
     */
    fun morph(
        enterDuration: Int = Durations.Medium,
        exitDuration: Int = Durations.Short,
        initialScale: Float = 0.94f,
        targetScale: Float = 0.94f,
    ): ContentTransform {
        val enter =
            fadeIn(animationSpec = tween(enterDuration, easing = Easings.Decelerate)) +
                scaleIn(
                    animationSpec = tween(enterDuration, easing = Easings.Emphasized),
                    initialScale = initialScale,
                )
        val exit =
            fadeOut(animationSpec = tween(exitDuration, easing = Easings.Accelerate)) +
                scaleOut(
                    animationSpec = tween(exitDuration, easing = Easings.Standard),
                    targetScale = targetScale,
                )
        return ContentTransform(enter, exit, 0f, SizeTransform(clip = false))
    }

    /**
     * Horizontal branch travel. [direction] is +1 for "next sibling to the
     * right", -1 for "previous", so the old copy leaves the way the user is
     * heading and the new one arrives from the opposite side.
     */
    fun horizontalTravel(
        direction: Int,
        distanceFraction: Int = 4,
    ): ContentTransform {
        val sign = if (direction >= 0) 1 else -1
        val divisor = distanceFraction.coerceAtLeast(1)
        val enter =
            fadeIn(animationSpec = tween(Durations.Medium, easing = Easings.Decelerate)) +
                slideInHorizontally(
                    animationSpec = tween(Durations.Long, easing = Easings.Emphasized),
                    initialOffsetX = { width -> sign * width / divisor },
                )
        val exit =
            fadeOut(animationSpec = tween(Durations.Short, easing = Easings.Accelerate)) +
                slideOutHorizontally(
                    animationSpec = tween(Durations.Long, easing = Easings.Standard),
                    targetOffsetX = { width -> -sign * width / divisor },
                )
        return ContentTransform(enter, exit, 0f, SizeTransform(clip = false))
    }
}

/**
 * Fade in while lifting 8dp into place. Runs once per [key] (and therefore once
 * per composition lifetime), which is what "content arrived" should feel like.
 */
@Composable
fun Modifier.animatedEnter(
    key: Any? = Unit,
    delayMillis: Int = 0,
    offsetY: Dp = 8.dp,
): Modifier {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(progress, delayMillis) {
        if (delayMillis > 0) delay(delayMillis.toLong())
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(Motion.Durations.Medium, easing = Motion.Easings.Decelerate),
        )
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * offsetY.toPx()
    }
}

/**
 * Press feedback at the indication layer: the whole surface scales to
 * [pressedScale] while the finger is down. Pass the same [interactionSource]
 * that the gesture modifier uses, otherwise there is nothing to observe.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = Motion.Springs.Light,
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/** Clickable + press scale, so no screen has to wire it by hand. */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .pressScale(source, enabled)
        .clickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = onClick,
        )
}

/** Long-press + click + press scale, used by message bubbles. */
@Composable
fun Modifier.longPressable(
    enabled: Boolean = true,
    onLongClick: () -> Unit,
    onClick: () -> Unit = {},
): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .pressScale(source, enabled, pressedScale = 0.99f)
        .combinedClickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/** `AnimatedVisibility` with the house transitions already applied. */
@Composable
fun MotionVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = Motion.enterFromTop(),
    exit: ExitTransition = Motion.exitToTop(),
    label: String = "motionVisibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = enter,
        exit = exit,
        label = label,
        content = content,
    )
}

/**
 * Crisp streaming caret: on for a beat, off for a beat, with a fast edge so it
 * blinks instead of pulsing.
 */
@Composable
fun rememberCaretAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = Motion.Durations.Caret * 2
                1f at 0
                1f at Motion.Durations.Caret - 40
                0f at Motion.Durations.Caret
                0f at Motion.Durations.Caret * 2 - 40
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "caretAlpha",
    )
    return alpha
}

/** Small horizontal shake, used when a message turns into its failed state. */
@Composable
fun Modifier.shakeWhen(
    trigger: Any?,
    enabled: Boolean = true,
    amplitude: Dp = 5.dp,
): Modifier {
    val offset = remember { Animatable(0f) }
    val px = with(LocalDensity.current) { amplitude.toPx() }
    LaunchedEffect(trigger) {
        if (!enabled || trigger == null) return@LaunchedEffect
        offset.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = Motion.Durations.Long
                -px at 40
                px at 100
                -px * 0.6f at 165
                px * 0.32f at 235
                0f at Motion.Durations.Long
            },
        )
    }
    return this.graphicsLayer { translationX = offset.value }
}

// ---------------------------------------------------------------------------
// v2 arrivals — the drawer, the half-screen panels, and message rows.
//
// Each one is a *named* transition built from the token table above, so a
// screen asks for "a sheet arrival" instead of choosing numbers itself.
// ---------------------------------------------------------------------------

/**
 * Drawer content arrival (spec §1: 260ms).
 *
 * **Do not gate drawer content on `DrawerState.isOpen`.** That flag only flips
 * once the sheet has finished travelling, so a body wrapped in
 * `AnimatedVisibility(visible = drawerState.isOpen)` stays empty for the whole
 * open animation — shipped once as "点开抽屉卡在空白页". The drawer body is
 * painted from the first frame and the sheet's own anchored-draggable travel is
 * the transition; these two functions are kept for other surfaces (a future
 * side panel that owns its own open state), not for `ModalNavigationDrawer`.
 */
fun drawerContentEnter(): EnterTransition =
    fadeIn(animationSpec = tween(Motion.Durations.Drawer, easing = Motion.Easings.Decelerate)) +
        slideInHorizontally(
            animationSpec = tween(Motion.Durations.Drawer, easing = Motion.Easings.Emphasized),
            initialOffsetX = { width -> -width / 10 },
        )

fun drawerContentExit(): ExitTransition =
    fadeOut(animationSpec = tween(Motion.Durations.Short, easing = Motion.Easings.Accelerate))

/** Bottom panel content: 280ms in, 220ms out (spec §1). */
fun sheetEnter(): EnterTransition =
    fadeIn(animationSpec = tween(Motion.Durations.SheetEnter, easing = Motion.Easings.Decelerate)) +
        slideInVertically(
            animationSpec = tween(Motion.Durations.SheetEnter, easing = Motion.Easings.Emphasized),
            initialOffsetY = { height -> height / 6 },
        )

fun sheetExit(): ExitTransition =
    fadeOut(animationSpec = tween(Motion.Durations.SheetExit, easing = Motion.Easings.Accelerate)) +
        slideOutVertically(
            animationSpec = tween(Motion.Durations.SheetExit, easing = Motion.Easings.Standard),
            targetOffsetY = { height -> height / 8 },
        )

/**
 * Message arrival (spec §1): fade in while lifting 4dp into place. [index]
 * staggers a burst of messages (a restored transcript) by one [Motion.Durations.Stagger]
 * beat each, capped so a long history does not take a second to settle.
 */
@Composable
fun Modifier.messageEnter(
    key: Any?,
    index: Int = 0,
    offsetY: Dp = 4.dp,
): Modifier = animatedEnter(
    key = key,
    delayMillis = index.coerceIn(0, 8) * Motion.Durations.Stagger,
    offsetY = offsetY,
)
