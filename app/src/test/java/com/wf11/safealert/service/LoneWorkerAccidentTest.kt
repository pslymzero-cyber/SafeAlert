package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule 1 (accident): a fall signal alone (no prior-motion condition, no impact-only trigger) opens a
 * 5 min suspicion; 30 s without distinct motion inside it opens a 1 min accident check that becomes
 * SOS. A fall while charging inside the zone (raw inside, right after entering) is ignored. A real
 * plug up to 10 s before the trigger ignores it; a real plug during the suspicion ends it. Distinct
 * motion = 5 walking-shaped steps within 10 s, or (no step sensor) 3 s of walking-shaped windows.
 * Deadlines are judged once sensor data covers them, or LATE_MS after them.
 * Calls in each test run in arrival order.
 */
class LoneWorkerAccidentTest {

    private val late = LoneWorkerLogic.LATE_MS

    /**
     * A fall alone opens the suspicion; 30 s without distinct motion after it opens the accident check, which left
     * unanswered for 1 min becomes SOS (the setting responseMs is not used for a fall check).
     */
    @Test fun accident_check_unanswered_for_1min_becomes_sos() {
        val l = fallRuleOnly()
        l.responseMs = 600_000L
        l.onAccident(6_000)
        // the screen count (read only): 30 s from the impact, one buzz key per accepted impact
        assertEquals(LoneWorkerLogic.Countdown(LoneWorkerLogic.CountdownKind.FALL, 30_000L, 6_000L), l.countdown(6_000))
        assertEquals(Mode.WATCHING, l.modeAt(18_000))
        assertEquals(18_000L, l.countdown(18_000)!!.leftMs)
        assertEquals(Mode.WATCHING, l.seenAt(35_999))
        assertEquals(Mode.CHECKING, l.seenAt(36_000))
        assertEquals("fall", l.trigger)
        assertEquals(60_000L, l.responseLeftMs(36_000))
        assertEquals(LoneWorkerLogic.Countdown(LoneWorkerLogic.CountdownKind.RESPONSE, 60_000L, null), l.countdown(36_000))
        assertEquals(Mode.CHECKING, l.seenAt(95_999))
        assertEquals(1L, l.countdown(95_999)!!.leftMs)
        assertEquals(Mode.SOS, l.seenAt(96_000))
        assertEquals("fall", l.trigger)
        assertEquals(LoneWorkerLogic.Countdown(LoneWorkerLogic.CountdownKind.SOS, 0L, null), l.countdown(96_000))

        // a second impact inside the same suspicion is a new count: a new buzz key
        val twice = fallRuleOnly()
        twice.onAccident(6_000)
        twice.onAccident(10_000)
        assertEquals(10_000L, twice.countdown(10_000)!!.buzzKey)
    }

    /**
     * A fall is ignored when the phone was docked at the impact: charging inside the zone (also right after entering
     * it), a real plug within 10 s before the impact anywhere, or a real unplug within 10 s before the impact inside
     * the zone (it fell off the cradle). A fall before an unplug:
     * LoneWorkerOrderTest.fall_before_an_unplug_is_ignored_whenever_it_is_processed.
     * Rows: the fall, the modes seen at the listed times, the trigger when checked, then the power and zone at the
     * start, an optional zone entry, an optional power report and the fall shape.
     */
    @Test fun fall_is_ignored_when_docked_at_the_impact() {
        class Row(val name: String, val fallAt: Long, val seen: List<Pair<Long, Mode>>, val trigger: String? = null,
                  val charging: Boolean = false, val zoneInside: Boolean = false, val zoneEnteredAt: Long? = null,
                  val power: Pair<Boolean, Long>? = null, val zoneExitAt: Long? = null,
                  val shape: MotionAnalyzer.FallShape = MotionAnalyzer.FallShape.ANY)
        fun watching(from: Long, to: Long) = (from..to step 10_000L).map { it to Mode.WATCHING }
        for (r in listOf(
            Row("charging, zone entered 1 s before", 6_000, watching(40_000, 400_000),
                charging = true, zoneEnteredAt = 5_000),
            // a full-shape fall (long drop, hard impact, big tilt) is ignored the same way
            Row("charging, zone entered 1 s before, full-shape fall", 6_000, watching(40_000, 400_000),
                charging = true, zoneEnteredAt = 5_000, shape = MotionAnalyzer.FallShape(400, 5.0, 90.0)),
            // the zone exit confirmed within 12 s of the impact uses the outside rule, so the fall counts; later it does not
            Row("charging, zone entered 1 s before, exit 2 s after the impact", 6_000, listOf(36_000L to Mode.CHECKING), "fall",
                charging = true, zoneEnteredAt = 5_000, zoneExitAt = 8_000, shape = MotionAnalyzer.FallShape(400, 5.0, 90.0)),
            Row("charging, zone entered 1 s before, exit 15 s after the impact", 6_000, watching(40_000, 400_000),
                charging = true, zoneEnteredAt = 5_000, zoneExitAt = 21_000, shape = MotionAnalyzer.FallShape(400, 5.0, 90.0)),
            Row("charging outside the zone", 20_000, listOf(50_000L to Mode.CHECKING), charging = true),
            Row("plug 10 s before", 20_000, watching(50_000, 330_000), power = true to 10_000L),
            Row("plug 10.001 s before", 20_000, listOf(50_000L to Mode.CHECKING), power = true to 9_999L),
            Row("unplug 0.4 s before, inside the zone", 100_400, watching(130_400, 430_400),
                charging = true, zoneInside = true, power = false to 100_000L),
            Row("unplug exactly UNPLUG_FALL_MS before, inside the zone", 100_000 + LoneWorkerLogic.UNPLUG_FALL_MS,
                watching(140_000, 440_000), charging = true, zoneInside = true, power = false to 100_000L),
            Row("unplug 11 s before, inside the zone", 111_000,
                listOf(140_999L to Mode.WATCHING, 141_000L to Mode.CHECKING), "fall",
                charging = true, zoneInside = true, power = false to 100_000L),
            Row("unplug 0.4 s before, outside the zone", 100_400, listOf(130_400L to Mode.CHECKING), "fall",
                charging = true, power = false to 100_000L))) {
            val l = fallRuleOnly(charging = r.charging, zoneInside = r.zoneInside)
            r.zoneEnteredAt?.let { l.onZone(true, it) }
            r.zoneExitAt?.let { l.onZone(false, it) }
            r.power?.let { (on, at) -> l.reportPower(on, at) }
            val preview = l.previewFall(r.fallAt, r.shape)
            l.onAccident(r.fallAt, r.shape)
            for ((t, want) in r.seen) assertEquals("${r.name}: seen at $t", want, l.seenAt(t))
            assertEquals("${r.name}: preview", r.seen.any { it.second == Mode.CHECKING }, preview)
            r.trigger?.let { assertEquals("${r.name}: trigger", it, l.trigger) }
        }
    }

    @Test fun plug_during_suspicion_ends_it_without_check() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.WATCHING, l.seenAt(29_000))
        l.reportPower(true, 30_000)
        for (t in 40_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
        // also after steps closed the accident check (suspicion still running)
        val s = fallRuleOnly()
        s.onAccident(10_000)
        assertEquals(Mode.CHECKING, s.seenAt(40_000))
        s.walk(45_000, 5)
        assertEquals(Mode.WATCHING, s.mode)
        s.reportPower(true, 50_000)
        for (t in 60_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, s.seenAt(t))
        // a real plug with the accident check still open closes it and ends the suspicion
        val c = fallRuleOnly()
        c.onAccident(10_000)
        assertEquals(Mode.CHECKING, c.seenAt(40_000))
        c.reportPower(true, 50_000)
        assertEquals(Mode.WATCHING, c.mode)
        for (t in 60_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, c.seenAt(t))
    }

    // the unplug during an accident check: LoneWorkerChargeTest.unplug_during_still_check_keeps_it_running_to_sos

    @Test fun power_changes_during_sos_do_not_end_sos() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        assertEquals(Mode.SOS, l.seenAt(100_000))
        l.reportPower(true, 110_000)
        assertEquals(Mode.SOS, l.mode)
        assertEquals(Mode.SOS, l.seenAt(120_000))
        l.reportPower(false, 130_000)
        assertEquals(Mode.SOS, l.mode)
        for (t in 140_000L..600_000L step 20_000L) assertEquals(Mode.SOS, l.seenAt(t))
        assertEquals("fall", l.trigger)
    }

    /**
     * Distinct motion after the fall holds the accident check: 5 walking-shaped steps within 10 s taken while the app
     * did not vibrate, or without a step sensor a 3 s run of walking-shaped windows that start after the fall.
     * Rows: fall time, step sensor, the motion, then the modes seen at the listed times.
     */
    @Test fun only_distinct_motion_after_the_fall_delays_the_accident_check() {
        class Row(val name: String, val fallAt: Long, val stepSensor: Boolean, val motion: LoneWorkerLogic.() -> Unit,
                  val seen: List<Pair<Long, Mode>>)
        val open = listOf(40_000L to Mode.CHECKING)
        for (r in listOf(
            Row("5 non-walking steps", 10_000, true, { shuffle(38_000, 5) }, open),
            Row("5 steps while the app vibrates", 10_000, true,
                { for (i in 4 downTo 0) step(38_000 - i * 500L, vibrating = true) }, open),
            Row("4 steps", 10_000, true, { walk(30_000, 4) }, open),
            Row("5 steps over 10.004 s", 10_000, true, { for (i in 0 until 5) step(12_000 + i * 2_501L) }, open),
            Row("5 steps over exactly 10 s", 10_000, true, { for (i in 0 until 5) step(12_000 + i * 2_500L) },
                listOf(51_999L to Mode.WATCHING, 52_000L to Mode.CHECKING)),
            Row("step sensor present, 5 walking windows alone", 10_000, true, { strongRun(20_000, 5) }, open),
            Row("no step sensor, 3 s walking run", 10_000, false, { strongRun(36_000, 3) },
                listOf(67_999L to Mode.WATCHING, 68_000L to Mode.CHECKING)),
            Row("no step sensor, 2 s walking run", 10_000, false, { strongRun(30_000, 2) }, open),
            // windows 10-11 s, 11-12 s, 12-13 s: only two start after the fall
            Row("no step sensor, run starting with the window that holds the fall", 10_500, false,
                { strongRun(11_000, 3) }, listOf(40_500L to Mode.CHECKING)))) {
            val l = fallRuleOnly()
            if (!r.stepSensor) l.stepsAvailable = false
            l.onAccident(r.fallAt)
            val key = l.countdown(r.fallAt)!!.buzzKey
            r.motion(l)
            for ((t, want) in r.seen) {
                assertEquals("${r.name}: seen at $t", want, l.seenAt(t))
                // a count restarted by motion is still the same impact: no second buzz
                if (want == Mode.WATCHING) assertEquals("${r.name}: count at $t", key, l.countdown(t)?.buzzKey)
            }
        }
    }

    @Test fun late_step_before_sos_deadline_closes_check() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        // deadline 100 s passes before the batch carrying steps up to 99.9 s arrives
        assertEquals(Mode.CHECKING, l.modeAt(100_000))
        assertEquals(Mode.CHECKING, l.modeAt(102_000))
        l.walk(99_900, 5)
        assertEquals(Mode.WATCHING, l.mode)
        assertEquals(Mode.WATCHING, l.modeAt(103_000))
    }

    /**
     * The accident check's opening (40 s) and its SOS (100 s) wait for the sensor data that covers them, at most LATE_MS;
     * data that covers the deadline judges it at once, but only when the accelerometer and the step sensor both arrived.
     */
    @Test fun sos_waits_for_sensor_data_up_to_6s() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        assertEquals(Mode.CHECKING, l.modeAt(100_000))
        assertEquals(Mode.CHECKING, l.modeAt(100_000 + late - 1))
        assertEquals(Mode.SOS, l.modeAt(100_000 + late))

        // the opening waits the same way
        val open = fallRuleOnly()
        open.onAccident(10_000)
        assertEquals(Mode.WATCHING, open.modeAt(40_000))
        assertEquals(Mode.WATCHING, open.modeAt(40_000 + late - 1))
        assertEquals(Mode.CHECKING, open.modeAt(40_000 + late))
        assertEquals(60_000L, open.responseLeftMs(40_000 + late))

        val seen = fallRuleOnly()
        seen.onAccident(10_000)
        assertEquals(Mode.CHECKING, seen.seenAt(40_000))

        // data that covers the SOS deadline fires it at the deadline
        val data = fallRuleOnly()
        data.onAccident(10_000)
        assertEquals(Mode.CHECKING, data.seenAt(40_000))
        assertEquals(Mode.CHECKING, data.seenAt(99_999))
        // accelerometer covers the deadline but the step sensor has not been flushed yet
        data.onWindow(MotionAnalyzer.Window(100_000, false))
        assertEquals(Mode.CHECKING, data.modeAt(100_000))
        data.stepsFlushed(100_000)
        assertEquals(Mode.SOS, data.modeAt(100_000))
    }

    @Test fun fall_check_replacing_a_still_check_counts_steps_from_the_switch() {
        val l = fallRuleOnly()
        l.stillMs = 180_000L
        assertEquals(Mode.CHECKING, l.seenAt(180_000))
        assertEquals("still", l.trigger)
        l.onAccident(175_000)
        l.walk(202_500, 4)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals(Mode.CHECKING, l.seenAt(205_000))
        assertEquals("fall", l.trigger)
        // four steps before the switch plus one after it are not five steps after the new floor
        l.step(206_000)
        assertEquals(Mode.CHECKING, l.modeAt(207_000))
        assertEquals("fall", l.trigger)
    }

    /**
     * Walking every 25 s keeps the check closed until the 5 min suspicion ends; a new fall inside it (at 100 s) moves
     * the end of the suspicion, so the check opens 30 s after the last walk (315 s).
     */
    @Test fun still_period_must_complete_within_5min() {
        for (secondFall in listOf(false, true)) {
            val m = "secondFall=$secondFall"
            val l = fallRuleOnly()
            l.onAccident(10_000)
            for (last in 35_000L..285_000L step 25_000L) {
                l.walk(last, 5)
                assertEquals("$m: walk at $last", Mode.WATCHING, l.modeAt(last))
                if (secondFall && last == 85_000L) l.onAccident(100_000)
            }
            if (secondFall) {
                assertEquals(m, Mode.WATCHING, l.seenAt(314_999))
                assertEquals(m, Mode.CHECKING, l.seenAt(315_000))
            } else {
                assertEquals(m, Mode.WATCHING, l.seenAt(310_000))
                assertEquals(m, Mode.WATCHING, l.seenAt(320_000))
                assertEquals(m, Mode.WATCHING, l.seenAt(400_000))
            }
        }
    }

    @Test fun sporadic_single_steps_never_close_accident_check() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        for (t in 41_000L..98_000L step 3_000L) {
            l.step(t)
            assertEquals(Mode.CHECKING, l.modeAt(t))
        }
        assertEquals(Mode.SOS, l.seenAt(100_000))
    }

    /** [OK] on an accident check, and on a still check with a fall window open, closes it and ends the suspicion. */
    @Test fun ok_closes_accident_check_and_ends_suspicion() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        assertEquals(true, l.ackWorking(45_000))
        for (t in 50_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))

        val s = fallRuleOnly()
        s.stillMs = 180_000L
        assertEquals(Mode.CHECKING, s.seenAt(180_000))
        assertEquals("still", s.trigger)
        s.onAccident(190_000)
        assertEquals(true, s.ackWorking(195_000))
        for (t in 200_000L..370_000L step 5_000L) assertEquals(Mode.WATCHING, s.seenAt(t))
    }

    @Test fun steps_close_accident_check_and_watching_continues_until_5min() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        l.walk(50_000, 5)
        assertEquals(Mode.WATCHING, l.mode)
        assertEquals(Mode.WATCHING, l.seenAt(79_999))
        assertEquals(Mode.CHECKING, l.seenAt(80_000))
        l.walk(285_000, 5)
        assertEquals(Mode.WATCHING, l.mode)
        for (t in 290_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    /**
     * A fall during an open still check: the earlier deadline wins (a fall at 175 s: its check at 205 s replaces the still
     * check, SOS at 265 s; a fall at 215 s cannot beat the still check, whose SOS stays due at 300 s). The pending
     * queries (nextCheckAt, waitingOnSensors, dueNow) show a deadline that cannot beat the still check is not pending.
     */
    @Test fun earlier_deadline_wins_when_accident_fires_during_still_check() {
        val l = fallRuleOnly()
        l.stillMs = 180_000L
        assertEquals(Mode.CHECKING, l.seenAt(180_000))
        assertEquals("still", l.trigger)
        l.onAccident(175_000)
        assertEquals(205_000L, l.nextCheckAt(190_000))
        assertTrue(l.waitingOnSensors(205_000))
        assertFalse(l.dueNow(205_000))
        l.sensed(205_000)
        assertTrue(l.dueNow(205_000))
        assertEquals(Mode.CHECKING, l.modeAt(205_000))
        assertEquals("fall", l.trigger)
        assertFalse(l.dueNow(205_000))
        assertEquals(Mode.CHECKING, l.seenAt(205_000))
        assertEquals("fall", l.trigger)
        assertEquals(Mode.CHECKING, l.seenAt(264_999))
        assertEquals(Mode.SOS, l.seenAt(265_000))

        val later = fallRuleOnly()
        later.stillMs = 180_000L
        assertEquals(Mode.CHECKING, later.seenAt(180_000))
        later.onAccident(215_000)
        assertEquals(300_000L, later.nextCheckAt(230_000))
        assertFalse(later.waitingOnSensors(245_000))
        later.sensed(245_000)
        assertFalse(later.dueNow(245_000))
        assertEquals(Mode.CHECKING, later.modeAt(245_000))
        assertEquals("still", later.trigger)
        assertEquals(Mode.SOS, later.seenAt(300_000))
        assertEquals("still", later.trigger)
    }

    // An SOS in progress ignores a new fall: LoneWorkerLogicTest.restore_sos_survives_settle_disable_and_ack.
    // Disabled: no fall check opens, an open one is withdrawn: LoneWorkerLogicTest.disabled_never_checks_and_cancels_open_check_but_keeps_sos.

    /**
     * The screen count from the impact moment (a fall candidate): 30 s from the impact, the buzz key equals the confirmed
     * fall's key, it takes only the no-count / last-minute-of-no-motion place, and an impact the judgment would drop
     * (shut, SOS, check open) or one older than 30 s is not shown.
     */
    @Test fun fall_count_from_the_impact() {
        val fall = LoneWorkerLogic.CountdownKind.FALL
        val s = MotionAnalyzer.FallShape(300, 3.0, null)
        val l = fallRuleOnly()
        assertEquals(LoneWorkerLogic.Countdown(fall, 30_000L, 6_000L), l.countdown(6_000, 6_000, s))
        assertEquals(LoneWorkerLogic.Countdown(fall, 18_000L, 6_000L), l.countdown(18_000, 6_000, s))
        assertNull(l.countdown(36_000, 6_000, s))
        assertNull(l.countdown(18_000))
        l.onAccident(6_000, s)
        assertEquals(6_000L, l.countdown(18_000)!!.buzzKey)   // the candidate's key: confirming does not buzz again
        assertEquals(l.countdown(20_000), l.countdown(20_000, 19_000, s))   // the judgment's fall count comes first
        assertEquals(Mode.CHECKING, l.seenAt(36_000))
        assertEquals(LoneWorkerLogic.CountdownKind.RESPONSE, l.countdown(36_000, 35_000, s)!!.kind)
        assertFalse(l.previewFall(36_000, s))
        assertEquals(Mode.SOS, l.seenAt(96_000))
        assertEquals(LoneWorkerLogic.CountdownKind.SOS, l.countdown(96_000, 95_000, s)!!.kind)
        assertFalse(l.previewFall(96_000, s))

        val still = newLogic(carried = true)
        assertEquals(LoneWorkerLogic.CountdownKind.STILL, still.countdown(130_000)!!.kind)
        assertEquals(LoneWorkerLogic.Countdown(fall, 25_000L, 125_000L), still.countdown(130_000, 125_000, s))

        val off = fallRuleOnly().apply { setEnabled(false, 1_000) }
        assertFalse(off.previewFall(6_000, s))
        assertNull(off.countdown(6_000, 6_000, s))
    }

    /** A second fall while a fall check is open neither reopens nor moves that check: SOS stays due 60 s after it opened. */
    @Test fun second_fall_during_open_fall_check_does_not_move_it() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        l.onAccident(45_000)
        assertEquals(Mode.CHECKING, l.seenAt(50_000))
        assertEquals(50_000L, l.responseLeftMs(50_000))
        assertEquals(Mode.SOS, l.seenAt(100_000))
    }
}
