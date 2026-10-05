package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** A fall alone opens the suspicion; 30 s without distinct motion after it opens the accident check. */
    @Test fun fall_opens_accident_check_after_30s_without_distinct_motion() {
        val l = fallRuleOnly()
        l.onAccident(6_000)
        assertEquals(Mode.WATCHING, l.modeAt(18_000))
        assertEquals(Mode.WATCHING, l.seenAt(35_999))
        assertEquals(Mode.CHECKING, l.seenAt(36_000))
        assertEquals("fall", l.trigger)
    }

    @Test fun accident_check_unanswered_for_1min_becomes_sos() {
        val l = fallRuleOnly()
        l.responseMs = 600_000L
        l.onAccident(6_000)
        assertEquals(Mode.CHECKING, l.seenAt(36_000))
        assertEquals(60_000L, l.responseLeftMs(36_000))
        assertEquals(Mode.CHECKING, l.seenAt(95_999))
        assertEquals(Mode.SOS, l.seenAt(96_000))
        assertEquals("fall", l.trigger)
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
                  val power: Pair<Boolean, Long>? = null,
                  val shape: MotionAnalyzer.FallShape = MotionAnalyzer.FallShape.ANY)
        fun watching(from: Long, to: Long) = (from..to step 10_000L).map { it to Mode.WATCHING }
        for (r in listOf(
            Row("charging, zone entered 1 s before", 6_000, watching(40_000, 400_000),
                charging = true, zoneEnteredAt = 5_000),
            // a full-shape fall (long drop, hard impact, big tilt) is ignored the same way
            Row("charging, zone entered 1 s before, full-shape fall", 6_000, watching(40_000, 400_000),
                charging = true, zoneEnteredAt = 5_000, shape = MotionAnalyzer.FallShape(400, 5.0, 90.0)),
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
            r.power?.let { (on, at) -> l.reportPower(on, at) }
            l.onAccident(r.fallAt, r.shape)
            for ((t, want) in r.seen) assertEquals("${r.name}: seen at $t", want, l.seenAt(t))
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
    }

    @Test fun real_plug_during_accident_check_closes_it_and_ends_suspicion() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        l.reportPower(true, 50_000)
        assertEquals(Mode.WATCHING, l.mode)
        for (t in 60_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    @Test fun unplug_during_accident_check_keeps_it_running_to_sos() {
        val l = fallRuleOnly(charging = true)
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        l.reportPower(false, 45_000)
        assertEquals(Mode.CHECKING, l.seenAt(99_999))
        assertEquals(Mode.SOS, l.seenAt(100_000))
    }

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
            r.motion(l)
            for ((t, want) in r.seen) assertEquals("${r.name}: seen at $t", want, l.seenAt(t))
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

    @Test fun sos_waits_for_sensor_data_up_to_6s() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        assertEquals(Mode.CHECKING, l.modeAt(100_000))
        assertEquals(Mode.CHECKING, l.modeAt(100_000 + late - 1))
        assertEquals(Mode.SOS, l.modeAt(100_000 + late))
    }

    @Test fun sos_fires_at_deadline_when_data_covers_it() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        assertEquals(Mode.CHECKING, l.seenAt(99_999))
        // accelerometer covers the deadline but the step sensor has not been flushed yet
        l.onWindow(MotionAnalyzer.Window(100_000, false))
        assertEquals(Mode.CHECKING, l.modeAt(100_000))
        l.stepsFlushed(100_000)
        assertEquals(Mode.SOS, l.modeAt(100_000))
    }

    @Test fun accident_check_open_waits_for_sensor_data() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.WATCHING, l.modeAt(40_000))
        assertEquals(Mode.WATCHING, l.modeAt(40_000 + late - 1))
        assertEquals(Mode.CHECKING, l.modeAt(40_000 + late))
        assertEquals(60_000L, l.responseLeftMs(40_000 + late))

        val seen = fallRuleOnly()
        seen.onAccident(10_000)
        assertEquals(Mode.CHECKING, seen.seenAt(40_000))
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

    @Test fun still_period_must_complete_within_5min() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        for (last in 35_000L..285_000L step 25_000L) {
            l.walk(last, 5)
            assertEquals(Mode.WATCHING, l.modeAt(last))
        }
        assertEquals(Mode.WATCHING, l.seenAt(310_000))
        assertEquals(Mode.WATCHING, l.seenAt(320_000))
        assertEquals(Mode.WATCHING, l.seenAt(400_000))
    }

    @Test fun new_trigger_during_suspicion_extends_window() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        for (last in 35_000L..285_000L step 25_000L) {
            l.walk(last, 5)
            assertEquals(Mode.WATCHING, l.modeAt(last))
            if (last == 85_000L) l.onAccident(100_000)
        }
        assertEquals(Mode.WATCHING, l.seenAt(314_999))
        assertEquals(Mode.CHECKING, l.seenAt(315_000))
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

    @Test fun ok_closes_accident_check_and_ends_suspicion() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        assertEquals(true, l.ackWorking(45_000))
        for (t in 50_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
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

    @Test fun ok_on_still_check_also_ends_accident_suspicion() {
        val l = fallRuleOnly()
        l.stillMs = 180_000L
        assertEquals(Mode.CHECKING, l.seenAt(180_000))
        assertEquals("still", l.trigger)
        l.onAccident(190_000)
        assertEquals(true, l.ackWorking(195_000))
        for (t in 200_000L..370_000L step 5_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    @Test fun earlier_deadline_wins_when_accident_fires_during_still_check() {
        val l = fallRuleOnly()
        l.stillMs = 180_000L
        assertEquals(Mode.CHECKING, l.seenAt(180_000))
        assertEquals("still", l.trigger)
        l.onAccident(175_000)
        assertEquals(Mode.CHECKING, l.seenAt(205_000))
        assertEquals("fall", l.trigger)
        assertEquals(Mode.CHECKING, l.seenAt(264_999))
        assertEquals(Mode.SOS, l.seenAt(265_000))

        val later = fallRuleOnly()
        later.stillMs = 180_000L
        later.seenAt(180_000)
        later.onAccident(215_000)
        later.seenAt(245_000)
        assertEquals("still", later.trigger)
        assertEquals(Mode.SOS, later.seenAt(300_000))
        assertEquals("still", later.trigger)
    }

    @Test fun accident_deadline_that_cannot_beat_still_check_is_not_pending() {
        val l = fallRuleOnly()
        l.stillMs = 180_000L
        assertEquals(Mode.CHECKING, l.seenAt(180_000))
        l.onAccident(215_000)
        assertEquals(300_000L, l.nextCheckAt(230_000))
        assertFalse(l.waitingOnSensors(245_000))
        l.sensed(245_000)
        assertFalse(l.dueNow(245_000))
        assertEquals(Mode.CHECKING, l.modeAt(245_000))
        assertEquals("still", l.trigger)

        val early = fallRuleOnly()
        early.stillMs = 180_000L
        early.seenAt(180_000)
        early.onAccident(175_000)
        assertEquals(205_000L, early.nextCheckAt(190_000))
        assertTrue(early.waitingOnSensors(205_000))
        assertFalse(early.dueNow(205_000))
        early.sensed(205_000)
        assertTrue(early.dueNow(205_000))
        assertEquals(Mode.CHECKING, early.modeAt(205_000))
        assertEquals("fall", early.trigger)
        assertFalse(early.dueNow(205_000))
    }

    @Test fun sos_in_progress_ignores_new_triggers() {
        val l = fallRuleOnly()
        l.restoreSos("still", 1_000)
        l.onAccident(10_000)
        assertEquals(Mode.SOS, l.seenAt(40_000))
        l.cancelSos(41_000)
        for (t in 50_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    @Test fun disabled_ignores_accident_and_withdraws_accident_check() {
        val off = fallRuleOnly()
        off.setEnabled(false, 0)
        off.onAccident(10_000)
        assertEquals(Mode.WATCHING, off.seenAt(40_000))

        val chk = fallRuleOnly()
        chk.onAccident(10_000)
        assertEquals(Mode.CHECKING, chk.seenAt(40_000))
        chk.setEnabled(false, 41_000)
        assertEquals(Mode.WATCHING, chk.mode)
        chk.setEnabled(true, 42_000)
        for (t in 50_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, chk.seenAt(t))
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
