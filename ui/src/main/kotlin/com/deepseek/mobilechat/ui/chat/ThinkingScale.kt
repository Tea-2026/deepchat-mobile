package com.deepseek.mobilechat.ui.chat

import com.deepseek.mobilechat.domain.ThinkingLevel

/**
 * Geometry + motion of the reasoning slider, as **plain functions**.
 *
 * The visual constants and formulas are a 1:1 port of the reference the product
 * owner pinned: `dsh-codex-effort-slider/lib/client.js` (`energyFor`,
 * `fillColorFor`, `PARTICLES`, `starDelayFor`, `speedFor`, …). Nothing here is
 * "smoothed" or "improved": the look the user asked for is exactly these
 * numbers, so they stay in `:ui` where a unit test can pin them.
 *
 * The *feel* is no longer the reference's: since 2026-11-20 the knob follows the
 * finger continuously and only settles onto a stop on release, so the detent /
 * hysteresis rules that used to live here are gone.
 *
 * The file deliberately has **no Compose imports** — colours are packed ARGB
 * `Long`s and the component turns them into `Color` at the call site.
 */

// ---------------------------------------------------------------------------
// Stops
// ---------------------------------------------------------------------------

/**
 * The four stops of the reasoning track, left to right (spec §4).
 *
 * The composer draws them as one continuous track, so the *order* here is the
 * contract: index 0 is the left end (no reasoning) and the last index is the
 * hardest effort. Anything that maps a finger position to a level goes through
 * [thinkingIndexAt] / [thinkingStopFraction] instead of hard-coding `ordinal`.
 */
val ThinkingStops: List<ThinkingLevel> = listOf(
    ThinkingLevel.OFF,
    ThinkingLevel.LOW,
    ThinkingLevel.HIGH,
    ThinkingLevel.MAX,
)

/**
 * The label printed next to the thumb. English on purpose (product decision):
 * four one-word labels fit a small slot and never wrap, which Chinese labels of
 * unequal width would not.
 */
fun thinkingSliderLabel(level: ThinkingLevel): String = when (level) {
    ThinkingLevel.OFF -> "Off"
    ThinkingLevel.LOW -> "Low"
    ThinkingLevel.HIGH -> "High"
    ThinkingLevel.MAX -> "Max"
}

/**
 * Chinese name used by TalkBack only. The visible label stays English, but
 * announcing "High" to a Chinese screen reader reads like a bug, so the
 * accessibility string is localised separately.
 */
fun thinkingDescription(level: ThinkingLevel): String = when (level) {
    ThinkingLevel.OFF -> "关闭"
    ThinkingLevel.LOW -> "低"
    ThinkingLevel.HIGH -> "高"
    ThinkingLevel.MAX -> "最高"
}

/** Where [level]'s stop sits on the track, `0f` (left) … `1f` (right). */
fun thinkingStopFraction(level: ThinkingLevel): Float {
    val index = ThinkingStops.indexOf(level).coerceAtLeast(0)
    val last = ThinkingStops.lastIndex
    return if (last <= 0) 0f else index.toFloat() / last
}

/**
 * Percentage → nearest stop index (`indexFromPct` in the reference).
 *
 * This is the *only* mapping between the continuous finger position and a stop:
 * it names the stop the label shows while dragging, it is the settle target on
 * release, and it backs the TalkBack adjust action. Rounding (not flooring)
 * means "past the halfway point picks the next stop".
 */
fun thinkingIndexAt(fraction: Float, count: Int): Int {
    if (count <= 1) return 0
    return (fraction.coerceIn(0f, 1f) * (count - 1) + 0.5f).toInt().coerceIn(0, count - 1)
}

// ---------------------------------------------------------------------------
// Detent semantics were removed with the switch to free dragging (2026-11-20):
// the knob now follows the finger 1:1 and only *settles* on release, so there is
// no hysteresis rule left to model. `thinkingIndexAt` is the one mapping that
// survived: percentage → nearest stop (used for the settle and for the label).
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Energy (purple nebula) — pure position driven
// ---------------------------------------------------------------------------

/** Second stop's position: the fill stays a clean blue up to here. */
const val EnergyStart: Float = 1f / 3f

/** Colour anchor: violet arrives fully here (4 stops → High). */
const val EnergyEnd: Float = 2f / 3f

/**
 * Position → energy `0…1` (`energyFor`). Anchors: `1/3 → 0`, `2/3 → 0.5`,
 * `1 → 1`. Independent of the stop count, so the nebula does not change shape
 * when a provider exposes three levels instead of four.
 */
fun energyFor(pct: Float): Float {
    val t = pct.coerceIn(0f, 1f)
    return ((t - EnergyStart) / (1f - EnergyStart)).coerceIn(0f, 1f)
}

// ---------------------------------------------------------------------------
// Colours (ARGB, no Compose dependency)
// ---------------------------------------------------------------------------

/** `COLOR_BLUE` — the reference track's blue. */
const val ReasoningBlue: Long = 0xFF4D93F8

/** `COLOR_VIOLET` — reached at [EnergyEnd]. */
const val ReasoningViolet: Long = 0xFF9333EA

/** `COLOR_DEEP` — the filled end at the far right. */
const val ReasoningDeep: Long = 0xFF4C1D95

/**
 * `COLOR_TEXT_VIOLET` — the value text's bright end.
 *
 * The fill may end in a near-black violet, but text cannot: `#8b5cf6` keeps
 * ≈4.2:1 on white and ≈4.0:1 on the dark popup, which is why the reference uses
 * a separate, lighter anchor for text.
 */
const val ReasoningTextViolet: Long = 0xFF8B5CF6

/** Nebula gradient stops, in order (`linear-gradient(90deg, …)`). */
val EnergyGradientStops: List<Long> = listOf(
    0xFF3B1178,
    0xFF6D28D9,
    0xFF9333EA,
    0xFFC084FC,
)

/** Nebula stop positions, matching [EnergyGradientStops]. */
val EnergyGradientPositions: List<Float> = listOf(0f, 0.30f, 0.62f, 1f)

/** Linear mix of two packed ARGB colours. */
fun mixColor(from: Long, to: Long, t: Float): Long {
    val k = t.coerceIn(0f, 1f)
    fun channel(shift: Int): Long {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + ((b - a) * k)).toLong().coerceIn(0, 255)
    }
    return (0xFFL shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/**
 * Position → the **right end** of the fill (`fillColorFor`): blue until the
 * second stop, then violet, then deep violet. The fill is drawn as a gradient
 * from [ReasoningBlue] to this colour, so "left blue, right violet" appears by
 * itself as the knob travels.
 */
fun fillColorFor(pct: Float): Long {
    val t = pct.coerceIn(0f, 1f)
    return when {
        t <= EnergyStart -> ReasoningBlue
        t <= EnergyEnd -> mixColor(
            ReasoningBlue,
            ReasoningViolet,
            (t - EnergyStart) / (EnergyEnd - EnergyStart),
        )

        else -> mixColor(ReasoningViolet, ReasoningDeep, (t - EnergyEnd) / (1f - EnergyEnd))
    }
}

/**
 * Position → value-text colour (`valueColorFor`), or `null` for the Off stop,
 * which must keep the theme's muted grey.
 */
fun valueColorFor(pct: Float, level: ThinkingLevel): Long? {
    if (level == ThinkingLevel.OFF) return null
    return mixColor(ReasoningBlue, ReasoningTextViolet, energyFor(pct))
}

// ---------------------------------------------------------------------------
// Starfield — 22 deterministic particles
// ---------------------------------------------------------------------------

/** `STARFIELD_MIN`: the dimmest/smallest particle (1 = full size). */
const val StarfieldMin: Float = 0.5f

/** Mean crossing time of the starfield at the reference speed (seconds). */
const val StarfieldDurationMean: Float = 1.5f

/** Per-star duration spread (± this fraction) so fast stars do not catch slow ones. */
const val StarfieldDurationSpread: Float = 0.08f

/** Position speed cap (= the fastest stop), used to anchor the mean duration. */
const val MaxSpeedFactor: Float = 2f

/** Golden ratio: spreads heights and phases as a low-discrepancy sequence. */
const val GoldenRatio: Float = 0.6180339887498949f

/** Phase-adjacent stars must differ by at least this many height slots. */
const val MinHeightSlots: Int = 3

/** Number of star-dust particles the reference draws. */
const val StarCount: Int = 22

/** One star-dust particle: height (`8…92`% of the track) and phase (`0…1`). */
data class ReasoningParticle(val y: Float, val phase: Float)

/**
 * Deterministic pseudo-random `0…1` (`starHash01`): a hash, not `Random`, so the
 * field is identical on every launch and every recomposition and can be
 * asserted offline.
 */
fun starHash01(index: Int, salt: Int): Float {
    val value = ((index + 1).toLong() * salt.toLong() * 2654435761L) % 4294967296L
    return (((value ushr 8) % 1000).toFloat()) / 1000f
}

/**
 * The 22 particles (`buildParticles` in the reference).
 *
 * Phase and height use **two unrelated assignments** on purpose: the phase is a
 * golden-ratio low-discrepancy sequence (any prefix is evenly spread), the
 * height is a hashed permutation of equidistant slots decoupled from the phase
 * ranking. Deriving both from `frac((i+1)×φ)` makes the stars line up along a
 * diagonal ("too regular"), which is exactly what the reference fixed.
 */
val ReasoningParticles: List<ReasoningParticle> = buildParticles()

private fun buildParticles(): List<ReasoningParticle> {
    val count = StarCount
    val phase = FloatArray(count) { index -> ((index + 1) * GoldenRatio) % 1f }
    val byPhase = (0 until count).sortedBy { phase[it] }
    val slots = findSlotPermutation(count)
    val out = arrayOfNulls<ReasoningParticle>(count)
    for (rank in 0 until count) {
        val y = 8f + (slots[rank].toFloat() / (count - 1).toFloat()) * 84f
        out[byPhase[rank]] = ReasoningParticle(y = y, phase = phase[byPhase[rank]])
    }
    @Suppress("UNCHECKED_CAST")
    return (out.toList() as List<ReasoningParticle>)
}

/**
 * A deterministic permutation of `0 until count` whose **adjacent** entries are
 * at least [MinHeightSlots] apart — i.e. stars that walk side by side on the
 * track never share a height band. The salt search is the reference's: start at
 * 71 and take the first salt that satisfies the gap.
 */
private fun findSlotPermutation(count: Int): List<Int> {
    val fallback = List(count) { index -> (index * 5) % count }
    for (salt in 71 until 4000) {
        val candidate = (0 until count).sortedWith(
            compareBy<Int> { starHash01(it, salt) }.thenBy { it },
        )
        if ((1 until count).all { kotlin.math.abs(candidate[it] - candidate[it - 1]) >= MinHeightSlots }) {
            return candidate
        }
    }
    return fallback
}

/** Brightness/size factor of particle [index] in `[StarfieldMin, 1]`. */
fun starBrightnessFor(index: Int): Float =
    StarfieldMin + (1f - StarfieldMin) * starHash01(index, 61)

/**
 * Position → particle speed factor (`speedFor`). Anchors: High = 1×, Max = 2×,
 * left end floors at 0.35× so the field never freezes.
 */
fun speedFor(pct: Float): Float {
    val t = pct.coerceIn(0f, 1f)
    return (3f * t - 1f).coerceIn(0.35f, MaxSpeedFactor)
}

/**
 * Seconds for particle [index] to cross the whole track.
 *
 * Speed scales with position, so Max crosses in 1.5s, High in 3s and the left
 * end in ≈8.6s; the ±8% spread keeps stars from bunching.
 */
fun starDurationFor(index: Int, speedFactor: Float): Float {
    val speed = speedFactor.coerceIn(0.35f, MaxSpeedFactor)
    val spread = 1f - StarfieldDurationSpread +
        2f * StarfieldDurationSpread * starHash01(index, 29)
    return StarfieldDurationMean * MaxSpeedFactor / speed * spread
}

/**
 * Phase offset of particle [index] in `0…1` — the negative `animation-delay` of
 * the reference, expressed as progress instead of time.
 *
 * It depends on the index alone (never on how many particles are currently
 * visible): rebuilding it from `i / visibleCount` would restart every running
 * animation whenever the knob crosses a stop and the field would visibly jump.
 */
fun starPhaseOffset(index: Int): Float {
    val particle = ReasoningParticles[index]
    val jitter = starHash01(index, 53) * 0.03f
    return ((particle.phase + jitter) % 1f + 1f) % 1f
}

/**
 * Progress of particle [index] across the track at [seconds], in `0…1`
 * (0 = right edge, 1 = left edge). Wraps, so the field is endless.
 */
fun starProgress(index: Int, seconds: Float, speedFactor: Float): Float {
    val duration = starDurationFor(index, speedFactor)
    if (duration <= 0f) return 0f
    val raw = seconds / duration + starPhaseOffset(index)
    return raw - kotlin.math.floor(raw)
}

/** How many of the 22 particles are visible at [pct] (`starCountFor`). */
fun particleCountFor(pct: Float): Int =
    (energyFor(pct) * StarCount).let { kotlin.math.round(it) }.toInt().coerceIn(0, StarCount)

/**
 * Opacity of the whole star layer (`starLayerOpacityFor`).
 *
 * The stars do **not** fade with the nebula: at High the nebula is only 0.5
 * opaque, which would crush the dust to invisibility. The layer keeps a 0.6
 * floor and "gradually appears" is expressed by [particleCountFor] instead.
 */
fun starLayerOpacityFor(pct: Float): Float = 0.6f + 0.4f * energyFor(pct)
