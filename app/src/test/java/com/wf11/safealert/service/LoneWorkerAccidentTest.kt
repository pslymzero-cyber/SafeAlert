package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
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

    /** Carried, not charging, rule 2 pushed out of the way so only rule 1 acts. */
    private fun rule1(charging: Boolean = false, zoneInside: Boolean = false) =
        newLogic(charging, zoneInside, carried = true).apply { stillMs = 3_600_000L }

    @Test fun fall_opens_suspicion_without_prior_motion() {
        val l = rule1()
        l.onAccident(6_000)
        assertEquals(Mode.WATCHING, l.modeAt(18_000))
        assertEquals(Mode.WATCHING, l.seenAt(35_999))
        assertEquals(Mode.CHECKING, l.seenAt(36_000))
        assertEquals("fall", l.trigger)
    }

    @Test fun accident_check_unanswered_for_1min_becomes_sos() {
        val l = rule1()
        l.responseMs = 600_000L
        l.onAccident(6_000)
        assertEquals(Mode.CHECKING, l.seenAt(36_000))
        assertEquals(60_000L, l.responseLeftMs(36_000))
        assertEquals(Mode.CHECKING, l.seenAt(95_999))
        assertEquals(Mode.SOS, l.seenAt(96_000))
        assertEquals("fall", l.trigger)
    }

    @Test fun fall_while_charging_inside_zone_is_ignored_right_after_entering() {
        val l = rule1(charging = true)
        l.onZone(true, 5_000)
        l.onAccident(6_000)
        for (t in 40_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    @Test fun fall_while_charging_outside_zone_counts() {
        val l = rule1(charging = true)
        l.onAccident(20_000)
        assertEquals(Mode.CHECKING, l.seenAt(50_000))
    }

    @Test fun fall_inside_zone_not_charging_counts() {
        val l = rule1(zoneInside = true)
        l.tick(60_000)
        assertEquals(true, l.zoneSettled)
        l.onAccident(100_000)
        assertEquals(Mode.CHECKING, l.seenAt(130_000))
        assertEquals(Mode.SOS, l.seenAt(190_000))
    }

    @Test fun plug_within_10s_before_trigger_ignores_it() {
        val l = rule1()
        l.setCharging(true, 10_000)
        l.onAccident(20_000)
        for (t in 50_000L..330_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))

        val early = rule1()
        early.setCharging(true, 9_999)
        early.onAccident(20_000)
        assertEquals(Mode.CHECKING, early.seenAt(50_000))
    }

    @Test fun plug_during_suspicion_ends_it_without_check() {
        val l = rule1()
        l.onAccident(10_000)
        assertEquals(Mode.WATCHING, l.seenAt(29_000))
        l.setCharging(true, 30_000)
        for (t in 40_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    @Test fun plug_after_steps_closed_accident_check_ends_suspicion() {
        val l = rule1()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        l.walk(45_000, 5)
        assertEquals(Mode.WATCHING, l.mode)
        l.setCharging(true, 50_000)
        for (t in 60_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    @Test fun real_plug_during_accident_check_closes_it_and_ends_suspicion() {
        val l = rule1()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        l.setCharging(true, 50_000)
        assertEquals(Mode.WATCHING, l.mode)
        for (t in 60_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    @Test fun unplug_during_accident_check_keeps_it_running_to_sos() {
        val l = rule1(charging = true)
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        l.setCharging(false, 45_000)
        assertEquals(Mode.CHECKING, l.seenAt(99_999))
        assertEquals(Mode.SOS, l.seenAt(100_000))
    }

    @Test fun power_changes_during_sos_do_not_end_sos() {
        val l = rule1()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        assertEquals(Mode.SOS, l.seenAt(100_000))
        l.setCharging(true, 110_000)
        assertEquals(Mode.SOS, l.mode)
        assertEquals(Mode.SOS, l.seenAt(120_000))
        l.setCharging(false, 130_000)
        assertEquals(Mode.SOS, l.mode)
        for (t in 140_000L..600_000L step 20_000L) assertEquals(Mode.SOS, l.seenAt(t))
        assertEquals("fall", l.trigger)
    }

    @Test fun non_walking_steps_do_not_hold() {
        val l = rule1()
        l.onAccident(10_000)
        l.shuffle(38_000, 5)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
    }

    @Test fun steps_during_app_vibration_do_not_hold() {
        val l = rule1()
        l.onAccident(10_000)
        for (i in 4 downTo 0) l.step(38_000 - i * 500L, vibrating = true)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
    }

    @Test fun late_step_before_sos_deadline_closes_check() {
        val l = rule1()
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
        val l = rule1()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        assertEquals(Mode.CHECKING, l.modeAt(100_000))
        assertEquals(Mode.CHECKING, l.modeAt(100_000 + late - 1))
        assertEquals(Mode.SOS, l.modeAt(100_000 + late))
    }

    @Test fun sos_fires_at_deadline_when_data_covers_it() {
        val l = rule1()
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
        val l = rule1()
        l.onAccident(10_000)
        assertEquals(Mode.WATCHING, l.modeAt(40_000))
        assertEquals(Mode.WATCHING, l.modeAt(40_000 + late - 1))
        assertEquals(Mode.CHECKING, l.modeAt(40_000 + late))
        assertEquals(60_000L, l.responseLeftMs(40_000 + late))

        val seen = rule1()
        seen.onAccident(10_000)
        assertEquals(Mode.CHECKING, seen.seenAt(40_000))
    }

    @Test fun accident_switch_from_still_check_resets_step_floor() {
        val l = rule1()
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
        val l = rule1()
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
        val l = rule1()
        l.onAccident(10_000)
        for (last in 35_000L..285_000L step 25_000L) {
            l.walk(last, 5)
            assertEquals(Mode.WATCHING, l.modeAt(last))
            if (last == 85_000L) l.onAccident(100_000)
        }
        assertEquals(Mode.WATCHING, l.seenAt(314_999))
        assertEquals(Mode.CHECKING, l.seenAt(315_000))
    }

    @Test fun five_steps_hold_then_restill_within_5min_opens_check() {
        val l = rule1()
        l.onAccident(10_000)
        l.walk(38_000, 5)
        assertEquals(Mode.WATCHING, l.seenAt(40_000))
        assertEquals(Mode.WATCHING, l.seenAt(67_999))
        assertEquals(Mode.CHECKING, l.seenAt(68_000))
    }

    @Test fun four_steps_are_not_distinct_motion() {
        val l = rule1()
        l.onAccident(10_000)
        l.walk(30_000, 4)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
    }

    @Test fun five_steps_spread_over_more_than_10s_are_not_distinct_motion() {
        val l = rule1()
        l.onAccident(10_000)
        for (i in 0 until 5) l.step(12_000 + i * 2_501L)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
    }

    @Test fun five_steps_within_10s_are_distinct_motion() {
        val l = rule1()
        l.onAccident(10_000)
        for (i in 0 until 5) l.step(12_000 + i * 2_500L)
        assertEquals(Mode.WATCHING, l.seenAt(51_999))
        assertEquals(Mode.CHECKING, l.seenAt(52_000))
    }

    @Test fun sporadic_single_steps_never_close_accident_check() {
        val l = rule1()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        for (t in 41_000L..98_000L step 3_000L) {
            l.step(t)
            assertEquals(Mode.CHECKING, l.modeAt(t))
        }
        assertEquals(Mode.SOS, l.seenAt(100_000))
    }

    @Test fun steps_before_the_trigger_do_not_count() {
        val l = rule1()
        l.walk(9_000, 4)
        l.onAccident(10_000)
        l.step(12_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
    }

    @Test fun ok_closes_accident_check_and_ends_suspicion() {
        val l = rule1()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        assertEquals(true, l.ackWorking(45_000))
        for (t in 50_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    @Test fun steps_close_accident_check_and_watching_continues_until_5min() {
        val l = rule1()
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
        val l = rule1()
        l.stillMs = 180_000L
        assertEquals(Mode.CHECKING, l.seenAt(180_000))
        assertEquals("still", l.trigger)
        l.onAccident(190_000)
        assertEquals(true, l.ackWorking(195_000))
        for (t in 200_000L..370_000L step 5_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    @Test fun moved_signal_alone_does_not_close_accident_check() {
        val l = rule1()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        l.onMoved(45_000)
        assertEquals(Mode.CHECKING, l.seenAt(50_000))
    }

    @Test fun without_step_sensor_3s_strong_motion_holds() {
        val l = rule1()
        l.stepsAvailable = false
        l.onAccident(10_000)
        l.strongRun(36_000, 3)
        assertEquals(Mode.WATCHING, l.seenAt(67_999))
        assertEquals(Mode.CHECKING, l.seenAt(68_000))

        val two = rule1()
        two.stepsAvailable = false
        two.onAccident(10_000)
        two.strongRun(30_000, 2)
        assertEquals(Mode.CHECKING, two.seenAt(40_000))
    }

    @Test fun strong_window_containing_the_trigger_does_not_count() {
        val l = rule1()
        l.stepsAvailable = false
        l.onAccident(10_500)
        l.strongRun(11_000, 3) // windows 10-11 s, 11-12 s, 12-13 s: only two start after the trigger
        assertEquals(Mode.CHECKING, l.seenAt(40_500))
    }

    @Test fun with_step_sensor_strong_motion_alone_does_not_hold() {
        val l = rule1()
        l.onAccident(10_000)
        l.strongRun(20_000, 5)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
    }

    @Test fun earlier_deadline_wins_when_accident_fires_during_still_check() {
        val l = rule1()
        l.stillMs = 180_000L
        assertEquals(Mode.CHECKING, l.seenAt(180_000))
        assertEquals("still", l.trigger)
        l.onAccident(175_000)
        assertEquals(Mode.CHECKING, l.seenAt(205_000))
        assertEquals("fall", l.trigger)
        assertEquals(Mode.CHECKING, l.seenAt(264_999))
        assertEquals(Mode.SOS, l.seenAt(265_000))

        val later = rule1()
        later.stillMs = 180_000L
        later.seenAt(180_000)
        later.onAccident(215_000)
        later.seenAt(245_000)
        assertEquals("still", later.trigger)
        assertEquals(Mode.SOS, later.seenAt(300_000))
        assertEquals("still", later.trigger)
    }

    @Test fun sos_in_progress_ignores_new_triggers() {
        val l = rule1()
        l.restoreSos("still", 1_000)
        l.onAccident(10_000)
        assertEquals(Mode.SOS, l.seenAt(40_000))
        l.cancelSos(41_000)
        for (t in 50_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    @Test fun disabled_ignores_accident_and_withdraws_accident_check() {
        val off = rule1()
        off.setEnabled(false, 0)
        off.onAccident(10_000)
        assertEquals(Mode.WATCHING, off.seenAt(40_000))

        val chk = rule1()
        chk.onAccident(10_000)
        assertEquals(Mode.CHECKING, chk.seenAt(40_000))
        chk.setEnabled(false, 41_000)
        assertEquals(Mode.WATCHING, chk.mode)
        chk.setEnabled(true, 42_000)
        for (t in 50_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, chk.seenAt(t))
    }
}
