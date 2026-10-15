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

    private val still = UPRIGHT_STILL
    private val lying = LYING_STILL
    private val freeFall = FREE_FALL_SAMPLE
    private val impact = IMPACT_SAMPLE

    private fun MotionAnalyzer.span(from: Long, to: Long, v: FloatArray, out: MutableList<Long>) {
        out += feed(from, to) { v }.filter { it.second == Signal.FALL }.map { it.first }
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

    /** Settled inside the zone (not charging), fall at 100 s. */
    private fun zoneFall(shape: FallShape, zone: ZoneFall = ZoneFall()): LoneWorkerLogic {
        val l = fallRuleOnly(zoneInside = true)
        l.zoneFall = zone
        l.tick(60_000)
        l.onAccident(100_000, shape)
        return l
    }

    /** No check for the fall at 100 s up to 400 s; m (a row label) prefixes the assertion messages. */
    private fun assertDiscarded(l: LoneWorkerLogic, m: String? = null) {
        for (t in 130_000L..400_000L step 10_000L) assertEquals(m, Mode.WATCHING, l.seenAt(t))
    }

    /** The fall at 100 s opens its check at 130 s; m (a row label) prefixes the assertion messages. */
    private fun assertChecking(l: LoneWorkerLogic, m: String? = null) {
        assertEquals(m, Mode.CHECKING, l.seenAt(130_000))
        assertEquals(m, "fall", l.trigger)
    }

    // -- analyzer shape --

    /**
     * The analyzer's fall shape. Rows: the trace (one FALL each), then the expected drop ms, peak G and posture change;
     * a posture the analyzer cannot know (the trace starts with the fall) is null, and a low G older than one second
     * is not counted in the drop.
     */
    @Test fun standard_fall_shape() {
        class Row(val name: String, val run: MotionAnalyzer.() -> List<Long>, val freeFallMs: Long? = null,
                  val peakG: Double? = null, val postureDeg: Double? = null, val postureUnknown: Boolean = false)
        for (r in listOf(
            Row("standard fall", { fall(3000, 260) }, freeFallMs = 260, peakG = 3.059, postureDeg = 90.0),
            Row("peak is the max in the impact window", { fall(3000, 260, listOf(26f, 40f, 30f)) }, peakG = 4.079),
            Row("unknown pre-fall posture", { fall(0, 300) }, postureUnknown = true),
            Row("low G older than one second", {
                trace(
                    seg(0, 1000, still), seg(1000, 1200, freeFall), seg(1200, 3000, still), seg(3000, 3100, freeFall),
                    seg(3100, 3160, impact), seg(3160, 18_160, lying)
                )
            }, freeFallMs = 100))) {
            val a = MotionAnalyzer()
            assertEquals("${r.name}: one fall", 1, r.run(a).size)
            r.freeFallMs?.let { assertEquals("${r.name}: drop", it, a.fallShape.freeFallMs) }
            r.peakG?.let { assertEquals("${r.name}: peak", it, a.fallShape.peakG, 0.01) }
            r.postureDeg?.let { assertEquals("${r.name}: posture", it, a.fallShape.postureDeg!!, 1.0) }
            if (r.postureUnknown) assertNull("${r.name}: posture", a.fallShape.postureDeg)
        }
    }

    /** Zone impact thresholds by sensor range: MotionAnalyzerTest.impact_threshold_by_sensor_range_and_setting. */
    @Test fun zone_defaults_and_free_fall_ms_for_drop_height() {
        assertEquals(247L, MotionAnalyzer.freeFallMsFor(30))
        assertEquals(64L, MotionAnalyzer.freeFallMsFor(2))
        assertEquals(452L, MotionAnalyzer.freeFallMsFor(100))
        assertEquals(ZoneFall(247L, 2.5, 60.0), ZoneFall())
    }

    // -- logic inside / outside the zone --

    /**
     * Inside a settled zone a fall counts only when its drop, impact and tilt reach the zone settings; an unknown tilt
     * counts as reached. Rows: the fall shape, the zone settings, whether the fall counts, and for the full fall that
     * its unanswered check becomes SOS 60 s after it opened (190 s).
     */
    @Test fun zone_fall_counts_only_past_the_drop_impact_and_tilt_settings() {
        class Row(val name: String, val shape: FallShape, val zone: ZoneFall, val counts: Boolean, val sos: Boolean = false)
        for (r in listOf(
            Row("full fall (250, 3.0, 70 deg)", FallShape(250, 3.0, 70.0), ZoneFall(), true, sos = true),
            Row("drop 60 ms", FallShape(60, 3.0, 90.0), ZoneFall(), false),
            Row("tilt 50 deg", FallShape(250, 3.0, 50.0), ZoneFall(), false),
            Row("tilt unknown", FallShape(250, 3.0, null), ZoneFall(), true),
            Row("impact 3.0 G, setting 4.0 G", FallShape(300, 3.0, 90.0), ZoneFall(impactG = 4.0), false),
            Row("impact 4.2 G, setting 4.0 G", FallShape(300, 4.2, 90.0), ZoneFall(impactG = 4.0), true))) {
            val l = zoneFall(r.shape, r.zone)
            if (r.sos) assertTrue("${r.name}: settled", l.zoneSettled)
            if (r.counts) assertChecking(l, r.name) else assertDiscarded(l, r.name)
            if (r.sos) assertEquals("${r.name}: SOS", Mode.SOS, l.seenAt(190_000))
        }
    }

    // -- drop length, posture, zone state at the impact, zone impact threshold --

    /** From the analyzer trace to the zone verdict: a mid-air upright blip does not shorten the drop. */
    @Test fun mid_air_blip_keeps_the_full_drop_for_the_zone_rule() {
        val a = MotionAnalyzer()
        val falls = a.trace(
            seg(0, 3000, still), seg(3000, 3100, freeFall), seg(3100, 3120, still), seg(3120, 3300, freeFall),
            seg(3300, 3360, impact), seg(3360, 18_360, lying)
        )
        assertEquals(1, falls.size)
        assertEquals(280L, a.fallShape.freeFallMs)
        val l = fallRuleOnly(zoneInside = true)
        l.tick(60_000)
        l.onAccident(100_000, a.fallShape)
        assertChecking(l)
        assertNotNull(a.fallShape.postureDeg)
        // an analyzer shape with a 100 ms drop is too low for the zone rule
        val brief = MotionAnalyzer().apply { assertEquals(1, fall(3000, 100).size) }
        assertDiscarded(zoneFall(brief.fallShape))
    }

    // Charging and inside the zone at the impact, with the exit confirmed within or after 12 s:
    // LoneWorkerAccidentTest.fall_is_ignored_when_docked_at_the_impact (rows with zoneExitAt).

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

    // A NaN posture sample is not a fall: MotionAnalyzerTest.each_fall_rule_alone_breaks_the_fall.

    // -- zone exit lag --

    private val short = FallShape(60, 2.6, 46.0)

    /**
     * A zone exit confirmed within EXIT_LAG_MS (12 s) after the impact uses the outside rule, so the short fall counts;
     * an exit confirmed later keeps the inside rule.
     */
    @Test fun zone_exit_within_12s_of_the_impact_uses_the_outside_rule() {
        assertEquals(12_000L, ZoneHistory.EXIT_LAG_MS)
        assertTrue(ZoneHistory.EXIT_LAG_MS <= MotionAnalyzer.POST_END_MS)
        for ((exit, outside) in (108_000L..112_000L step 1_000L).map { it to true } + (113_000L to false)) {
            val m = "exit at $exit"
            val l = fallRuleOnly(zoneInside = true)
            l.tick(60_000)
            l.onZone(false, exit)
            l.onAccident(100_000, short)
            if (outside) assertChecking(l, m) else assertDiscarded(l, m)
        }
        // a zone entered after the impact is judged by the zone state at the impact: the outside rule
        val e = fallRuleOnly()
        e.onZone(true, 105_000)
        e.onAccident(100_000, short)
        assertEquals("entered after the impact", Mode.WATCHING, e.seenAt(129_999))
        assertEquals("entered after the impact", Mode.CHECKING, e.seenAt(130_000))
        assertEquals("entered after the impact", "fall", e.trigger)
    }

    /** The restored zone is held until 10 s; the FALL (processed 12 s after the impact) sees that hold end as the exit. */
    @Test fun restart_zone_hold_exit_within_12s_uses_outside_rule() {
        val l = LoneWorkerLogic("SAFEALERT_WALKER_ME")
        l.stillMs = 3_600_000L
        l.startFrom(0, false, false, LoneWorkerResume.State(null, null, "", false, true, -200_000L, true, -100_000L))
        l.onAccident(2_000, short)
        assertEquals(Mode.CHECKING, l.seenAt(32_000))
        assertEquals("fall", l.trigger)
    }
}
