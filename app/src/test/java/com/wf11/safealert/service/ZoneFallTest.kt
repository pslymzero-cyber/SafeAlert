package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import com.wf11.safealert.service.MotionAnalyzer.FallShape
import com.wf11.safealert.service.MotionAnalyzer.Signal
import com.wf11.safealert.service.MotionAnalyzer.ZoneFall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safe-zone fall criteria: inside the zone a fall counts only when free fall >= zone ms (height),
 * peak impact >= sensor-scaled zone G and posture change >= zone angle (unknown posture counts as met).
 * Outside the zone and the charging-in-zone exception are unchanged. 50 Hz analyzer traces, 20 ms step.
 */
class ZoneFallTest {

    private val still = floatArrayOf(0f, 0f, 9.81f)
    private val lying = floatArrayOf(9.81f, 0f, 0f)
    private val freeFall = floatArrayOf(0f, 0f, 1.5f)

    private fun MotionAnalyzer.span(from: Long, to: Long, v: FloatArray, out: MutableList<Long>) {
        var t = from
        while (t < to) {
            if (add(t, v[0], v[1], v[2]) == Signal.FALL) out.add(t)
            t += 20
        }
    }

    /** Upright from 0 to ffFrom, free fall ffMs, the impact samples, then lying 15 s. Returns FALL times. */
    private fun MotionAnalyzer.fall(ffFrom: Long, ffMs: Long, impacts: List<Float> = listOf(30f, 30f, 30f)): List<Long> {
        val out = ArrayList<Long>()
        span(0, ffFrom, still, out)
        span(ffFrom, ffFrom + ffMs, freeFall, out)
        var t = ffFrom + ffMs
        for (m in impacts) { span(t, t + 20, floatArrayOf(0f, 0f, m), out); t += 20 }
        span(t, t + 15_000, lying, out)
        return out
    }

    /** Consecutive segments (from, to, sample). Returns FALL times. */
    private fun MotionAnalyzer.trace(vararg segs: Triple<Long, Long, FloatArray>): List<Long> {
        val out = ArrayList<Long>()
        for ((from, to, v) in segs) span(from, to, v, out)
        return out
    }

    private fun seg(from: Long, to: Long, v: FloatArray) = Triple(from, to, v)

    private val impact = floatArrayOf(0f, 0f, 30f)

    private fun rule1(charging: Boolean = false, zoneInside: Boolean = false) =
        newLogic(charging, zoneInside, carried = true).apply { stillMs = 3_600_000L }

    /** Mirrors fall_inside_zone_not_charging_counts: settled inside the zone, fall at 100 s. */
    private fun zoneFall(shape: FallShape, zone: ZoneFall = ZoneFall()): LoneWorkerLogic {
        val l = rule1(zoneInside = true)
        l.zoneFall = zone
        l.tick(60_000)
        l.onAccident(100_000, shape)
        return l
    }

    private fun assertDiscarded(l: LoneWorkerLogic) {
        for (t in 130_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    private fun assertChecking(l: LoneWorkerLogic) {
        assertEquals(Mode.CHECKING, l.seenAt(130_000))
        assertEquals("fall", l.trigger)
    }

    // -- analyzer shape --

    @Test fun standard_fall_shape() {
        val a = MotionAnalyzer()
        assertEquals(1, a.fall(3000, 260).size)
        assertEquals(260L, a.fallShape.freeFallMs)
        assertEquals(3.059, a.fallShape.peakG, 0.01)
        assertEquals(90.0, a.fallShape.postureDeg!!, 1.0)
    }

    @Test fun peak_is_max_in_impact_window() {
        val a = MotionAnalyzer()
        assertEquals(1, a.fall(3000, 260, listOf(26f, 40f, 30f)).size)
        assertEquals(4.079, a.fallShape.peakG, 0.01)
    }

    @Test fun unknown_pre_fall_posture_is_null() {
        val a = MotionAnalyzer()
        assertEquals(1, a.fall(0, 300).size)
        assertNull(a.fallShape.postureDeg)
    }

    @Test fun base_60ms_free_fall_still_falls() {
        val a = MotionAnalyzer()
        assertEquals(1, a.fall(3000, 60).size)
        assertEquals(60L, a.fallShape.freeFallMs)
    }

    @Test fun impact_g_scaling_and_free_fall_ms() {
        assertEquals(1.8, MotionAnalyzer.impactGFor(19.6f, 4.0), 0.01)
        assertEquals(4.0, MotionAnalyzer.impactGFor(78.4f, 4.0), 0.0)
        // A4: a zone G below the base never lowers the threshold
        assertEquals(2.5, MotionAnalyzer.impactGFor(78.4f, 1.5), 0.0)
        assertEquals(1.8, MotionAnalyzer.impactGFor(19.6f), 0.01)
        assertEquals(247L, MotionAnalyzer.freeFallMsFor(30))
        assertEquals(64L, MotionAnalyzer.freeFallMsFor(2))
        assertEquals(452L, MotionAnalyzer.freeFallMsFor(100))
        assertEquals(ZoneFall(247L, 2.5, 60.0), ZoneFall())
    }

    // -- logic inside / outside the zone --

    @Test fun zone_short_free_fall_discarded() = assertDiscarded(zoneFall(FallShape(60, 3.0, 90.0)))

    @Test fun zone_full_fall_counts() = assertChecking(zoneFall(FallShape(250, 3.0, 70.0)))

    @Test fun zone_small_tilt_discarded() = assertDiscarded(zoneFall(FallShape(250, 3.0, 50.0)))

    @Test fun zone_unknown_posture_counts() = assertChecking(zoneFall(FallShape(250, 3.0, null)))

    @Test fun zone_impact_threshold_from_settings() {
        assertDiscarded(zoneFall(FallShape(300, 3.0, 90.0), ZoneFall(impactG = 4.0)))
        assertChecking(zoneFall(FallShape(300, 4.2, 90.0), ZoneFall(impactG = 4.0)))
    }

    @Test fun outside_zone_unchanged() {
        val l = rule1()
        l.onAccident(6_000, FallShape(60, 2.6, 46.0))
        assertEquals(Mode.WATCHING, l.seenAt(35_999))
        assertEquals(Mode.CHECKING, l.seenAt(36_000))
    }

    @Test fun charging_inside_zone_still_ignored() {
        val l = rule1(charging = true)
        l.onZone(true, 5_000)
        l.onAccident(6_000, FallShape(400, 5.0, 90.0))
        for (t in 40_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    // -- tracer: analyzer shape into the zone logic --

    @Test fun tracer_analyzer_shape_into_zone_logic() {
        val short = MotionAnalyzer().apply { assertEquals(1, fall(3000, 100).size) }
        assertDiscarded(zoneFall(short.fallShape))
        val long = MotionAnalyzer().apply { assertEquals(1, fall(3000, 300).size) }
        assertNotNull(long.fallShape.postureDeg)
        assertChecking(zoneFall(long.fallShape))
    }

    // -- review fixes A1-A4 --

    /** Tracer: a mid-air upright blip no longer shortens the drop (A2), and the zone is read at the impact (A1). */
    @Test fun tracer_blip_split_fall_and_impact_zone_state() {
        val a = MotionAnalyzer()
        val falls = a.trace(
            seg(0, 3000, still), seg(3000, 3100, freeFall), seg(3100, 3120, still), seg(3120, 3300, freeFall),
            seg(3300, 3360, impact), seg(3360, 18_360, lying)
        )
        assertEquals(1, falls.size)
        assertEquals(280L, a.fallShape.freeFallMs)
        val l = rule1(zoneInside = true)
        l.tick(60_000)
        l.onAccident(100_000, a.fallShape)
        assertChecking(l)

        val short = MotionAnalyzer().apply { assertEquals(1, fall(3000, 100).size) }
        val left = rule1(zoneInside = true)
        left.tick(60_000)
        left.onZone(false, 105_000)
        left.onAccident(100_000, short.fallShape)
        assertDiscarded(left)
    }

    @Test fun zone_entered_after_impact_uses_outside_rule() {
        val l = rule1()
        l.onZone(true, 105_000)
        l.onAccident(100_000, FallShape(60, 2.6, 46.0))
        assertEquals(Mode.WATCHING, l.seenAt(129_999))
        assertEquals(Mode.CHECKING, l.seenAt(130_000))
        assertEquals("fall", l.trigger)
    }

    @Test fun charging_in_zone_at_impact_ignored_after_leaving() {
        val l = rule1(charging = true)
        l.onZone(true, 5_000)
        l.onZone(false, 8_000)
        l.onAccident(6_000, FallShape(400, 5.0, 90.0))
        for (t in 40_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    @Test fun zone_history_keeps_state_in_effect() {
        val h = ZoneHistory()
        h.reset(false, 0)
        h.mark(true, 1_000)
        h.mark(false, 2_000)
        h.mark(true, 200_000)
        assertEquals(false, h.insideAt(100_000))
        assertEquals(true, h.insideAt(200_000))
        assertEquals(true, h.inside)
        assertEquals(200_000L, h.since)
        assertEquals(false, h.insideAt(500))
    }

    @Test fun low_g_older_than_one_second_not_counted() {
        val a = MotionAnalyzer()
        val falls = a.trace(
            seg(0, 1000, still), seg(1000, 1200, freeFall), seg(1200, 3000, still), seg(3000, 3100, freeFall),
            seg(3100, 3160, impact), seg(3160, 18_160, lying)
        )
        assertEquals(1, falls.size)
        assertEquals(100L, a.fallShape.freeFallMs)
    }

    @Test fun nan_posture_is_not_a_fall() {
        val nan = floatArrayOf(Float.NaN, 0f, 0f)
        val a = MotionAnalyzer()
        val falls = a.trace(
            seg(0, 3000, still), seg(3000, 3300, freeFall), seg(3300, 3360, impact),
            seg(3360, 8300, lying), seg(8300, 8320, nan), seg(8320, 18_360, lying)
        )
        assertEquals(0, falls.size)
    }

    @Test fun zone_impact_monotonic_and_reachable() {
        val g = MotionAnalyzer.G.toFloat()
        assertEquals(2.5, MotionAnalyzer.impactGFor(2.6f * g, 2.7), 1e-9)
        assertEquals(7.2, MotionAnalyzer.impactGFor(78.4f, 8.0), 0.01)
        assertEquals(3.0, MotionAnalyzer.impactGFor(0f, 3.0), 1e-9)
        assertEquals(2.5, MotionAnalyzer.impactGFor(78.4f, 1.5), 1e-9)
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
}
