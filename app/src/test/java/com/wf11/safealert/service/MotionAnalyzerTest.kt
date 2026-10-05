package com.wf11.safealert.service

import com.wf11.safealert.service.MotionAnalyzer.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * MotionAnalyzer synthetic 50 Hz trace tests. m/s^2 with gravity, 20 ms step.
 */
class MotionAnalyzerTest {

    private class Run(val a: MotionAnalyzer = MotionAnalyzer()) {
        val signals = ArrayList<Pair<Long, Signal>>()
        fun sample(t: Long, x: Float, y: Float, z: Float) {
            val s = a.add(t, x, y, z)
            if (s != Signal.NONE) signals.add(t to s)
        }
        fun span(from: Long, to: Long, f: (Long) -> FloatArray) { signals += a.feed(from, to, f = f) }
        fun span(from: Long, to: Long, v: FloatArray) { span(from, to) { v } }
        fun maskedSpan(from: Long, to: Long, f: (Long) -> FloatArray) { signals += a.feed(from, to, masked = true, f = f) }
        fun maskedSpan(from: Long, to: Long, v: FloatArray) { maskedSpan(from, to) { v } }
        fun times(s: Signal) = signals.filter { it.second == s }.map { it.first }
    }

    private fun sec(t: Long) = t / 1000.0
    private val still = UPRIGHT_STILL
    private val breathing = { t: Long ->
        floatArrayOf(0f, 0f, (9.81 + 0.05 * sin(2 * PI * 0.3 * sec(t))).toFloat())
    }
    private fun walking(amp: Double, hz: Double) = { t: Long ->
        floatArrayOf(0f, 0f, (9.81 + amp * sin(2 * PI * hz * sec(t))).toFloat())
    }
    private val lying = LYING_STILL

    // Fall trace head: upright 3 s, 200 ms free fall, 3 impact samples. Impact at 3200.
    private fun Run.fallHead() {
        span(0, 3000, still)
        span(3000, 3200, FREE_FALL_SAMPLE)
        span(3200, 3260, IMPACT_SAMPLE)
    }

    @Test fun still_with_breathing_never_moves() {
        val r = Run()
        r.span(0, 60_000, breathing)
        assertTrue(r.signals.isEmpty())
    }

    @Test fun walking_moves_within_3s() {
        val r = Run()
        r.span(0, 10_000, walking(3.0, 2.0))
        assertTrue(r.times(Signal.MOVED).first() <= 3000)
    }

    @Test fun single_twitch_is_not_moved_but_three_active_seconds_are() {
        val twitch = Run()
        twitch.span(0, 5_000, still)
        twitch.span(5_000, 6_000, walking(3.0, 2.0))
        twitch.span(6_000, 30_000, still)
        assertTrue(twitch.times(Signal.MOVED).isEmpty())

        val three = Run()
        three.span(0, 5_000, still)
        three.span(5_000, 8_000, walking(3.0, 2.0))
        three.span(8_000, 12_000, still)
        assertTrue(three.times(Signal.MOVED).isNotEmpty())
    }

    @Test fun fall_then_walking_cancels() {
        val r = Run()
        r.fallHead()
        r.span(3260, 6_200, lying)
        r.span(6_200, 20_000) { t ->
            floatArrayOf((9.81 + 3.0 * sin(2 * PI * 2 * sec(t))).toFloat(), 0f, 0f)
        }
        assertTrue(r.times(Signal.FALL).isEmpty())
    }

    // Each fall rule alone: the negative breaks only that rule, the control differs only in that input.

    /** Rule: a free fall must come before the impact. An impact alone (3 G) then lying still is not a fall. */
    @Test fun impact_without_free_fall_is_not_a_fall() {
        val r = Run()
        r.span(0, 3000, still)
        r.span(3000, 3060) { floatArrayOf(0f, 0f, 30f) }
        r.span(3060, 17_000, lying)
        assertTrue(r.times(Signal.FALL).isEmpty())

        val control = Run()
        control.fallHead()
        control.span(3260, 17_000, lying)
        assertEquals(1, control.times(Signal.FALL).size)
    }

    /** Rule: the free fall lasts at least FREE_FALL_MIN_MS (60 ms). 40 ms of low G then impact and lying is not a fall. */
    @Test fun free_fall_shorter_than_60ms_is_not_a_fall() {
        fun trace(lowMs: Long): Run {
            val r = Run()
            r.span(0, 3000, still)
            r.span(3000, 3000 + lowMs) { floatArrayOf(0f, 0f, 1.5f) }
            r.span(3000 + lowMs, 3060 + lowMs) { floatArrayOf(0f, 0f, 30f) }
            r.span(3060 + lowMs, 17_000, lying)
            return r
        }
        assertTrue(40L < MotionAnalyzer.FREE_FALL_MIN_MS)
        assertTrue(trace(40).times(Signal.FALL).isEmpty())
        val sixty = trace(60)
        assertEquals(1, sixty.times(Signal.FALL).size)
        assertEquals(60L, sixty.a.fallShape.freeFallMs)
    }

    /** Rule: the posture changes at least POSTURE_DEG (45). A full fall ending 30 deg from upright is not a fall. */
    @Test fun small_posture_change_is_not_a_fall() {
        fun trace(deg: Double): Run {
            val a = deg * PI / 180.0
            val r = Run()
            r.fallHead()
            r.span(3260, 17_000) { floatArrayOf((9.81 * sin(a)).toFloat(), 0f, (9.81 * cos(a)).toFloat()) }
            return r
        }
        assertTrue(trace(30.0).times(Signal.FALL).isEmpty())
        assertEquals(1, trace(60.0).times(Signal.FALL).size)
    }


    @Test fun long_gap_shifts_still_windows_before_moved_is_evaluated() {
        val r = Run()
        r.span(0, 5_000, walking(3.0, 2.0))
        r.sample(65_000, 0f, 0f, 9.81f)
        assertTrue(r.times(Signal.MOVED).none { it >= 65_000 })
    }

    @Test fun short_gap_keeps_recent_active_windows() {
        val r = Run()
        r.span(0, 5_000, walking(3.0, 2.0))
        r.span(7_000, 9_000, walking(3.0, 2.0))
        assertTrue(r.times(Signal.MOVED).contains(7_000L))
    }

    /**
     * Impact threshold (G) from the sensor's reported maximum range: a 2 G-class sensor that clips below the wanted
     * threshold uses 90 % of its range; an unknown (0, negative, NaN), implausibly small or large enough range keeps
     * the wanted G, and a zone setting below IMPACT_G never lowers it. Small ranges are read as g units.
     * Rows: range, zone setting (null = the plain call, IMPACT_G), want, tolerance.
     */
    @Test fun impact_threshold_by_sensor_range_and_setting() {
        val g = MotionAnalyzer.G.toFloat()
        val base = MotionAnalyzer.IMPACT_G
        for ((i, row) in listOf(
            // m/s^2
            listOf(19.6f, null, 1.8, 0.01),
            listOf(78.4f, null, base, 1e-9),
            listOf(0f, null, base, 1e-9),
            listOf(-1f, null, base, 1e-9),
            listOf(Float.NaN, null, 2.5, 1e-9),
            listOf(1.4f * g, null, base, 1e-9),
            listOf(1.5f * g, null, base, 1e-9),
            listOf(1.8f * g, null, 1.62, 0.01),
            listOf(2.4f * g, null, 2.16, 0.01),
            listOf(2.6f * g, null, base, 1e-9),
            // g units; 16 (g) / G = 1.63: still read as g units, not as a 1.63 g sensor
            listOf(2.0f, null, 1.8, 0.01),
            listOf(2.4f, null, 2.16, 0.01),
            listOf(1.4f, null, base, 1e-9),
            listOf(2.6f, null, base, 1e-9),
            listOf(4f, null, base, 1e-9),
            listOf(8f, null, base, 1e-9),
            listOf(16f, null, base, 1e-9),
            // a safe-zone setting
            listOf(19.6f, 4.0, 1.8, 0.01),
            listOf(78.4f, 4.0, 4.0, 0.0),
            listOf(78.4f, 8.0, 7.2, 0.01),
            listOf(78.4f, 1.5, 2.5, 0.0),
            listOf(2.4f * g, 1.5, 2.16, 0.01),   // a low setting does not lower a small sensor's threshold either
            listOf(2.6f * g, 2.7, 2.5, 1e-9),
            listOf(0f, 3.0, 3.0, 1e-9),
            listOf(Float.NaN, 3.0, 3.0, 1e-9),
            listOf(Float.NaN, 1.5, 2.5, 1e-9)).withIndex()) {
            val range = row[0] as Float
            val want = row[1] as Double?
            val got = if (want == null) MotionAnalyzer.impactGFor(range) else MotionAnalyzer.impactGFor(range, want)
            assertEquals("row $i range $range setting $want", row[2] as Double, got, row[3] as Double)
        }
    }

    /** Raising the zone setting never lowers the threshold, and the default setting equals the plain call. */
    @Test fun raising_the_zone_setting_never_lowers_the_threshold() {
        val g = MotionAnalyzer.G.toFloat()
        for (r in listOf(19.6f, 2.0f, 2.4f, 2.6f * g, 2.7f * g, 39.2f, 78.4f, 0f, 16f)) {
            val base = MotionAnalyzer.impactGFor(r)
            assertEquals(base, MotionAnalyzer.impactGFor(r, 2.5), 1e-9)
            var prev = base
            for (i in 25..80) {
                val v = MotionAnalyzer.impactGFor(r, i / 10.0)
                assertTrue("range $r want ${i / 10.0}", v >= prev && v >= base)
                prev = v
            }
        }
    }

    @Test fun clipped_two_g_sensor_needs_the_range_based_threshold() {
        fun trace(a: MotionAnalyzer): Run {
            val r = Run(a)
            r.span(0, 3000, still)
            r.span(3000, 3200) { floatArrayOf(0f, 0f, 1.5f) }
            r.span(3200, 3260) { floatArrayOf(0f, 0f, 19.6f) }
            r.span(3260, 17_000, lying)
            return r
        }
        assertTrue(trace(MotionAnalyzer()).times(Signal.FALL).isEmpty())
        assertEquals(1, trace(MotionAnalyzer(MotionAnalyzer.impactGFor(19.6f))).times(Signal.FALL).size)
    }


    @Test fun masked_samples_never_count_as_motion() {
        val r = Run()
        r.maskedSpan(0, 15_000, walking(3.0, 2.0))
        assertTrue(r.signals.isEmpty())
        r.span(15_000, 20_000, walking(3.0, 2.0))
        val moved = r.times(Signal.MOVED)
        assertTrue(moved.isNotEmpty())
        assertTrue(moved.all { it >= 15_000 })
    }

    @Test fun masked_samples_still_feed_fall_detection() {
        val r = Run()
        r.maskedSpan(0, 3000, still)
        r.maskedSpan(3000, 3200) { floatArrayOf(0f, 0f, 1.5f) }
        r.maskedSpan(3200, 3260) { floatArrayOf(0f, 0f, 30f) }
        r.maskedSpan(3260, 17_000, lying)
        assertEquals(1, r.times(Signal.FALL).size)
    }

    @Test fun window_reports_walking_shape_per_second() {
        val windows = ArrayList<MotionAnalyzer.Window>()
        val r = Run(MotionAnalyzer { windows.add(it) })
        val tilt = 40.0 * PI / 180.0
        r.span(0, 3000, still)
        r.span(3000, 6000) { t -> floatArrayOf(0f, 0f, (9.81 + 1.5 * sin(2 * PI * 20.0 * sec(t))).toFloat()) }
        r.span(6000, 9000, walking(3.0, 2.0))
        r.span(9000, 12_020) { floatArrayOf(0f, (9.81 * sin(tilt)).toFloat(), (9.81 * cos(tilt)).toFloat()) }
        fun w(end: Long) = windows.single { it.endMs == end }
        for (e in listOf(1000L, 2000L, 3000L)) assertFalse(w(e).strong)
        // vibration: activity but below walking level
        for (e in listOf(4000L, 5000L, 6000L)) assertFalse(w(e).strong)
        for (e in listOf(7000L, 8000L, 9000L)) assertTrue(w(e).strong)
        // a posture change alone is never walking-shaped
        assertFalse(w(10_000L).strong)
    }

    @Test fun fall_reports_impact_time_not_decision_time() {
        val r = Run()
        r.fallHead()
        r.span(3260, 17_000, lying)
        val falls = r.times(Signal.FALL)
        assertEquals(1, falls.size)
        assertEquals(3200L, r.a.eventMs)
        assertTrue(falls[0] - r.a.eventMs >= 12_000)
        // and the single FALL is decided by 16.2 s
        assertTrue(falls[0] <= 16_200)
    }
}
