package com.deepseek.mobilechat.ui.chat

import com.deepseek.mobilechat.domain.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pure geometry of the reasoning slider.
 *
 * The knob follows the finger continuously now, so the only decision left to pin
 * down is "which stop does this position belong to" ([thinkingIndexAt]) plus the
 * track's visual maths (energy, colours, the 22 particles). Colours and particle
 * constants are ports of `dsh-codex-effort-slider/lib/client.js`.
 */
class ThinkingScaleTest {

    /** `thinkingIndexAt` in the vocabulary the slider speaks. */
    private fun nearestStop(fraction: Float): ThinkingLevel =
        ThinkingStops[thinkingIndexAt(fraction, ThinkingStops.size)]

    // ── stops ────────────────────────────────────────────────────────────

    @Test
    fun `stops run left to right from off to max`() {
        assertEquals(
            listOf(
                ThinkingLevel.OFF,
                ThinkingLevel.LOW,
                ThinkingLevel.HIGH,
                ThinkingLevel.MAX,
            ),
            ThinkingStops,
        )
    }

    @Test
    fun `fraction is evenly spaced over the track`() {
        assertEquals(0f, thinkingStopFraction(ThinkingLevel.OFF), 0.0001f)
        assertEquals(1f / 3f, thinkingStopFraction(ThinkingLevel.LOW), 0.0001f)
        assertEquals(2f / 3f, thinkingStopFraction(ThinkingLevel.HIGH), 0.0001f)
        assertEquals(1f, thinkingStopFraction(ThinkingLevel.MAX), 0.0001f)
    }

    /**
     * The core of "release snaps into the nearest legal stop": anything short of
     * the midpoint stays on the current stop, anything past it moves on.
     */
    @Test
    fun `a release snaps to the nearest stop`() {
        // Below the halfway point to Low → stays Off; above it → Low.
        assertEquals(ThinkingLevel.OFF, nearestStop(0.16f))
        assertEquals(ThinkingLevel.LOW, nearestStop(0.17f))
        // Halfway between Low and High.
        assertEquals(ThinkingLevel.LOW, nearestStop(0.49f))
        assertEquals(ThinkingLevel.HIGH, nearestStop(0.51f))
        // Dead centre of the track still resolves, never lands between stops.
        assertEquals(ThinkingLevel.HIGH, nearestStop(0.5f))
        // And the far end.
        assertEquals(ThinkingLevel.MAX, nearestStop(0.84f))
    }

    @Test
    fun `a drag past the ends is clamped not dropped`() {
        assertEquals(ThinkingLevel.OFF, nearestStop(-0.4f))
        assertEquals(ThinkingLevel.MAX, nearestStop(1.4f))
        assertEquals(0, thinkingIndexAt(-3f, 4))
        assertEquals(3, thinkingIndexAt(9f, 4))
        assertEquals(0, thinkingIndexAt(0.9f, 1))
    }

    @Test
    fun `every stop survives a fraction round trip`() {
        ThinkingStops.forEach { level ->
            assertEquals(level, nearestStop(thinkingStopFraction(level)))
        }
    }

    /** Any position on the track must resolve to a real stop (no "in between"). */
    @Test
    fun `the settle result is always an existing stop`() {
        var pos = 0f
        while (pos <= 1f) {
            assertTrue(thinkingIndexAt(pos, 4) in 0..3)
            pos += 0.001f
        }
    }

    @Test
    fun `labels are the english words the chip shows`() {
        assertEquals("Off", thinkingSliderLabel(ThinkingLevel.OFF))
        assertEquals("Low", thinkingSliderLabel(ThinkingLevel.LOW))
        assertEquals("High", thinkingSliderLabel(ThinkingLevel.HIGH))
        assertEquals("Max", thinkingSliderLabel(ThinkingLevel.MAX))
        assertEquals(
            ThinkingStops.size,
            ThinkingStops.map(::thinkingSliderLabel).distinct().size,
        )
        // The top-bar subtitle uses `thinkingLabel`; it must say the same words
        // (the product owner asked for English levels everywhere they are shown,
        // e.g. 「思考 Max」 rather than 「思考 最高」).
        ThinkingStops.forEach { stop ->
            assertEquals(thinkingSliderLabel(stop), thinkingLabel(stop))
        }
    }

    @Test
    fun `accessibility descriptions are chinese`() {
        assertEquals("关闭", thinkingDescription(ThinkingLevel.OFF))
        assertEquals("低", thinkingDescription(ThinkingLevel.LOW))
        assertEquals("高", thinkingDescription(ThinkingLevel.HIGH))
        assertEquals("最高", thinkingDescription(ThinkingLevel.MAX))
        assertTrue(ThinkingStops.all { thinkingDescription(it).isNotEmpty() })
    }

    // ── energy / colours ─────────────────────────────────────────────────

    @Test
    fun `energy fades in from the second stop`() {
        assertEquals(0f, energyFor(0f), 0.0001f)
        // Low is the last "clean blue" stop.
        assertEquals(0f, energyFor(1f / 3f), 0.0001f)
        assertEquals(0.5f, energyFor(2f / 3f), 0.0001f)
        assertEquals(1f, energyFor(1f), 0.0001f)
        // Never outside 0..1, even for out-of-track input.
        assertEquals(0f, energyFor(-1f), 0.0001f)
        assertEquals(1f, energyFor(2f), 0.0001f)
    }

    @Test
    fun `fill is blue until low, violet at high, deep at max`() {
        assertEquals(ReasoningBlue, fillColorFor(0f))
        assertEquals(ReasoningBlue, fillColorFor(1f / 3f))
        assertEquals(ReasoningViolet, fillColorFor(2f / 3f))
        assertEquals(ReasoningDeep, fillColorFor(1f))
        // The far-left fill is never anything but the reference blue.
        assertEquals(ReasoningBlue, fillColorFor(-0.5f))
    }

    @Test
    fun `value text stays muted at off and turns violet at max`() {
        assertEquals(null, valueColorFor(0f, ThinkingLevel.OFF))
        assertEquals(ReasoningBlue, valueColorFor(0f, ThinkingLevel.LOW))
        assertEquals(ReasoningTextViolet, valueColorFor(1f, ThinkingLevel.MAX))
    }

    // ── star dust ────────────────────────────────────────────────────────

    @Test
    fun `there are 22 particles on distinct height bands`() {
        assertEquals(22, ReasoningParticles.size)
        val heights = ReasoningParticles.map { it.y }
        assertEquals("heights must not repeat", heights.size, heights.distinct().size)
        assertTrue("heights stay inside the track", heights.all { it in 8f..92f })
    }

    @Test
    fun `particle phases are the golden ratio sequence`() {
        ReasoningParticles.forEachIndexed { index, particle ->
            val expected = ((index + 1) * GoldenRatio) % 1f
            assertEquals("phase of particle $index", expected, particle.phase, 0.0001f)
        }
    }

    /**
     * Stars that are neighbours in phase travel side by side forever; the
     * reference forces them apart by at least three height slots so they never
     * merge into one blob.
     */
    @Test
    fun `phase neighbours never share a height band`() {
        val byPhase = ReasoningParticles.sortedBy { it.phase }
        val slot = 84f / (ReasoningParticles.size - 1)
        for (index in 1 until byPhase.size) {
            val gap = abs(byPhase[index].y - byPhase[index - 1].y)
            assertTrue(
                "particles $index and ${index - 1} in phase order are ${gap}pt apart",
                gap >= slot * MinHeightSlots - 0.001f,
            )
        }
    }

    @Test
    fun `star timing follows the position speed`() {
        // High = 1× → 3s, Max = 2× → 1.5s (mean, ±8% per star).
        val high = (0 until 22).map { starDurationFor(it, speedFor(2f / 3f)) }
        val max = (0 until 22).map { starDurationFor(it, speedFor(1f)) }
        assertTrue(high.all { it in 2.7f..3.3f })
        assertTrue(max.all { it in 1.3f..1.7f })
        assertTrue(high.all { it > max.max() })
        // The left end never freezes.
        assertTrue((0 until 22).all { starDurationFor(it, speedFor(0f)) < 10f })
    }

    @Test
    fun `particle count and layer opacity ramp with energy`() {
        assertEquals(0, particleCountFor(0f))
        assertEquals(0, particleCountFor(1f / 3f))
        assertEquals(11, particleCountFor(2f / 3f))
        assertEquals(22, particleCountFor(1f))
        assertEquals(0.8f, starLayerOpacityFor(2f / 3f), 0.0001f)
        assertEquals(1f, starLayerOpacityFor(1f), 0.0001f)
    }

    @Test
    fun `star crossings wrap instead of jumping`() {
        val speed = speedFor(1f)
        val duration = starDurationFor(0, speed)
        var previous = starProgress(0, 0f, speed)
        var wraps = 0
        var t = 0f
        while (t < duration * 3f) {
            t += duration / 200f
            val current = starProgress(0, t, speed)
            assertTrue("progress stays in 0..1", current >= 0f && current < 1f)
            // A wrap is the only allowed discontinuity, and it is a full loop.
            if (current < previous) wraps += 1
            previous = current
        }
        assertTrue("the field loops while time passes", wraps >= 2)
    }

    @Test
    fun `brightness is deterministic and bounded`() {
        for (index in 0 until 22) {
            val first = starBrightnessFor(index)
            val second = starBrightnessFor(index)
            assertEquals(first, second, 0f)
            assertTrue(first in StarfieldMin..1f)
        }
    }
}
