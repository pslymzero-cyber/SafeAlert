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
 * MotionAnalyzer synthetic 50 Hz trace tests (v1.1.99). m/s^2 with gravity, 20 ms step.
 */
class MotionAnalyzerTest {

    private class Run(val a: MotionAnalyzer = MotionAnalyzer()) {
        val signals = ArrayList<Pair<Long, Signal>>()
        fun sample(t: Long, x: Float, y: Float, z: Float) {
            val s = a.add(t, x, y, z)
            if (s != Signal.NONE) signals.add(t to s)
        }
        fun span(from: Long, to: Long, f: (Long) -> FloatArray) {
            var t = from
            while (t < to) {
                val v = f(t)
                sample(t, v[0], v[1], v[2])
                t += 20
            }
        }
        fun maskedSpan(from: Long, to: Long, f: (Long) -> FloatArray) {
            var t = from
            while (t < to) {
                val v = f(t)
                val s = a.add(t, v[0], v[1], v[2], masked = true)
                if (s != Signal.NONE) signals.add(t to s)
                t += 20
            }
        }
        fun times(s: Signal) = signals.filter { it.second == s }.map { it.first }
    }

    private fun sec(t: Long) = t / 1000.0
    private val still = { _: Long -> floatArrayOf(0f, 0f, 9.81f) }
    private val breathing = { t: Long ->
        floatArrayOf(0f, 0f, (9.81 + 0.05 * sin(2 * PI * 0.3 * sec(t))).toFloat())
    }
    private fun walking(amp: Double, hz: Double) = { t: Long ->
        floatArrayOf(0f, 0f, (9.81 + amp * sin(2 * PI * hz * sec(t))).toFloat())
    }
    private val lying = { _: Long -> floatArrayOf(9.81f, 0f, 0f) }

    // Fall trace head: upright 3 s, 200 ms free fall, 3 impact samples. Impact at 3200.
    private fun Run.fallHead() {
        span(0, 3000, still)
        span(3000, 3200) { floatArrayOf(0f, 0f, 1.5f) }
        span(3200, 3260) { floatArrayOf(0f, 0f, 30f) }
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

    @Test fun sample_gap_counts_as_still() {
        val r = Run()
        r.span(0, 5_000, still)
        r.span(65_000, 70_000, still)
        assertTrue(r.signals.isEmpty())
    }

    @Test fun fall_positive_fires_exactly_once_after_settle_window() {
        val r = Run()
        r.fallHead()
        r.span(3260, 17_000, lying)
        val falls = r.times(Signal.FALL)
        assertEquals(1, falls.size)
        assertTrue(falls[0] in 14_200..16_200)
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

    @Test fun spike_without_free_fall_is_not_a_fall() {
        val r = Run()
        r.span(0, 3000, still)
        r.span(3000, 3060) { floatArrayOf(0f, 0f, 39f) }
        r.span(3060, 25_000) { t ->
            floatArrayOf(0f, 0f, (9.81 + 2.0 * sin(2 * PI * 5 * sec(t))).toFloat())
        }
        assertTrue(r.times(Signal.FALL).isEmpty())
    }

    @Test fun sit_down_dip_is_not_a_fall() {
        val r = Run()
        r.span(0, 3000, still)
        r.span(3000, 3200) { floatArrayOf(0f, 0f, 6.9f) }    // 0.7 g
        r.span(3200, 3300) { floatArrayOf(0f, 0f, 19.6f) }   // 2 g
        r.span(3300, 25_000, lying)
        assertTrue(r.times(Signal.FALL).isEmpty())
    }

    @Test fun walking_and_running_are_not_falls() {
        val walk = Run()
        walk.span(0, 60_000, walking(3.0, 2.0))
        assertTrue(walk.times(Signal.FALL).isEmpty())

        val run = Run()
        run.span(0, 60_000, walking(4.0, 3.0))
        assertTrue(run.times(Signal.FALL).isEmpty())
    }

    // v1.1.99 review fixes

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

    @Test fun impact_threshold_follows_sensor_range() {
        assertEquals(1.8, MotionAnalyzer.impactGFor(19.6f), 0.01)
        assertEquals(MotionAnalyzer.IMPACT_G, MotionAnalyzer.impactGFor(78.4f), 1e-9)
        assertEquals(MotionAnalyzer.IMPACT_G, MotionAnalyzer.impactGFor(0f), 1e-9)
    }

    @Test fun implausibly_small_range_uses_default_threshold() {
        val g = MotionAnalyzer.G.toFloat()
        assertEquals(MotionAnalyzer.IMPACT_G, MotionAnalyzer.impactGFor(1.4f * g), 1e-9)
        assertEquals(MotionAnalyzer.IMPACT_G, MotionAnalyzer.impactGFor(-1f), 1e-9)
        assertEquals(MotionAnalyzer.IMPACT_G, MotionAnalyzer.impactGFor(1.5f * g), 1e-9)
        assertEquals(1.62, MotionAnalyzer.impactGFor(1.8f * g), 0.01)
        assertEquals(2.16, MotionAnalyzer.impactGFor(2.4f * g), 0.01)
        assertEquals(MotionAnalyzer.IMPACT_G, MotionAnalyzer.impactGFor(2.6f * g), 1e-9)
    }

    @Test fun range_reported_in_g_units_uses_90_percent() {
        assertEquals(1.8, MotionAnalyzer.impactGFor(2.0f), 0.01)
        assertEquals(2.16, MotionAnalyzer.impactGFor(2.4f), 0.01)
        assertEquals(MotionAnalyzer.IMPACT_G, MotionAnalyzer.impactGFor(1.4f), 1e-9)
        assertEquals(MotionAnalyzer.IMPACT_G, MotionAnalyzer.impactGFor(2.6f), 1e-9)
        assertEquals(MotionAnalyzer.IMPACT_G, MotionAnalyzer.impactGFor(4f), 1e-9)
        assertEquals(MotionAnalyzer.IMPACT_G, MotionAnalyzer.impactGFor(8f), 1e-9)
        // 16 (g) / G = 1.63: still read as g units, not as a 1.63 g sensor
        assertEquals(MotionAnalyzer.IMPACT_G, MotionAnalyzer.impactGFor(16f), 1e-9)
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

    // v1.1.99 re-review fixes

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
    }
}
