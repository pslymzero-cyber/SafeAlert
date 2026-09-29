package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rule 1 (accident): a fall or a 4 g impact while moving (activity or a step in the prior 10 s),
 * then 30 s without distinct motion inside the 5 min suspicion opens a 1 min accident check that
 * becomes SOS. Applies docked, charging and inside a settled zone. A real plug within 10 s before
 * or after the impact cancels the trigger. Distinct motion = 5 steps, or (no step sensor) 3 s of
 * walking-level strong windows.
 */
class LoneWorkerAccidentTest {

    /** Carried, not charging, rule 2 pushed out of the way so only rule 1 acts. */
    private fun newLogic(charging: Boolean = false, zoneInside: Boolean = false) =
        LoneWorkerLogic("SAFEALERT_WALKER_ME").apply {
            start(0L, zoneInside, charging)
            sensorSilent(0L)
            stillMs = 3_600_000L
        }

    private fun win(endMs: Long, active: Boolean = true, strong: Boolean = false) =
        MotionAnalyzer.Window(endMs, true, active, strong)

    private fun LoneWorkerLogic.activeAt(vararg ends: Long) { for (e in ends) onWindow(win(e)) }

    private fun LoneWorkerLogic.strongRun(firstEnd: Long, count: Int) {
        for (i in 0 until count) onWindow(win(firstEnd + i * 1000L, active = true, strong = true))
    }

    /** n steps, 500 ms apart, the last one at lastMs. */
    private fun LoneWorkerLogic.steps(lastMs: Long, n: Int) {
        for (i in n - 1 downTo 0) onStep(lastMs - i * 500L)
    }

    private fun LoneWorkerLogic.modeAt(t: Long): Mode { tick(t); return mode }

    @Test fun moving_fall_then_30s_without_distinct_motion_opens_accident_check() {
        val l = newLogic()
        l.activeAt(1_000, 2_000, 3_000, 4_000, 5_000)
        l.onAccident(6_000)
        assertEquals(Mode.WATCHING, l.modeAt(18_000))
        assertEquals(Mode.WATCHING, l.modeAt(35_999))
        assertEquals(Mode.CHECKING, l.modeAt(36_000))
        assertEquals("fall", l.trigger)
    }

    @Test fun accident_check_unanswered_for_1min_becomes_sos() {
        val l = newLogic()
        l.responseMs = 600_000L
        l.activeAt(5_000)
        l.onAccident(6_000)
        assertEquals(Mode.CHECKING, l.modeAt(36_000))
        assertEquals(60_000L, l.responseLeftMs(36_000))
        assertEquals(Mode.CHECKING, l.modeAt(95_999))
        assertEquals(Mode.SOS, l.modeAt(96_000))
        assertEquals("fall", l.trigger)
    }

    @Test fun impact_delivered_at_once_follows_same_path() {
        val l = newLogic()
        l.activeAt(5_000)
        l.onAccident(5_500)
        assertEquals(Mode.WATCHING, l.modeAt(35_499))
        assertEquals(Mode.CHECKING, l.modeAt(35_500))
        assertEquals(Mode.SOS, l.modeAt(95_500))
    }

    @Test fun impact_without_motion_in_prior_10s_is_ignored() {
        val far = newLogic()
        far.activeAt(1_000)
        far.onAccident(11_001)
        for (t in 20_000L..320_000L step 10_000L) assertEquals(Mode.WATCHING, far.modeAt(t))

        val edge = newLogic()
        edge.activeAt(1_000)
        edge.onAccident(11_000)
        assertEquals(Mode.CHECKING, edge.modeAt(41_000))
    }

    @Test fun window_containing_the_impact_does_not_count_as_prior_motion() {
        val l = newLogic()
        l.activeAt(7_000)
        l.onAccident(6_500)
        assertEquals(Mode.WATCHING, l.modeAt(40_000))
        assertEquals(Mode.WATCHING, l.modeAt(320_000))
    }

    @Test fun a_step_counts_as_prior_motion() {
        val l = newLogic()
        l.onStep(3_000)
        l.onAccident(8_000)
        assertEquals(Mode.CHECKING, l.modeAt(38_000))
    }

    @Test fun five_steps_hold_then_restill_within_5min_opens_check() {
        val l = newLogic()
        l.activeAt(9_000)
        l.onAccident(10_000)
        l.steps(60_000, 5)
        assertEquals(Mode.WATCHING, l.modeAt(40_000))
        assertEquals(Mode.WATCHING, l.modeAt(89_999))
        assertEquals(Mode.CHECKING, l.modeAt(90_000))
    }

    @Test fun four_steps_are_not_distinct_motion() {
        val l = newLogic()
        l.activeAt(9_000)
        l.onAccident(10_000)
        l.steps(30_000, 4)
        assertEquals(Mode.CHECKING, l.modeAt(40_000))
    }

    @Test fun steps_before_the_impact_do_not_count_toward_distinct_motion() {
        val l = newLogic()
        l.steps(9_000, 4)
        l.onAccident(10_000)
        l.onStep(12_000)
        assertEquals(Mode.CHECKING, l.modeAt(40_000))
    }

    @Test fun without_step_sensor_3s_strong_motion_holds() {
        val l = newLogic()
        l.stepsAvailable = false
        l.activeAt(9_000)
        l.onAccident(10_000)
        l.strongRun(58_000, 3)
        assertEquals(Mode.WATCHING, l.modeAt(89_999))
        assertEquals(Mode.CHECKING, l.modeAt(90_000))

        val two = newLogic()
        two.stepsAvailable = false
        two.activeAt(9_000)
        two.onAccident(10_000)
        two.strongRun(30_000, 2)
        assertEquals(Mode.CHECKING, two.modeAt(40_000))
    }

    @Test fun with_step_sensor_strong_motion_alone_does_not_hold() {
        val l = newLogic()
        l.activeAt(9_000)
        l.onAccident(10_000)
        l.strongRun(20_000, 5)
        assertEquals(Mode.CHECKING, l.modeAt(40_000))
    }

    @Test fun weak_wriggling_while_lying_is_not_distinct_motion() {
        val l = newLogic()
        l.stepsAvailable = false
        l.activeAt(9_000)
        l.onAccident(10_000)
        l.activeAt(20_000, 21_000, 22_000, 23_000, 24_000, 25_000)
        assertEquals(Mode.CHECKING, l.modeAt(40_000))
    }

    @Test fun strong_window_containing_the_impact_does_not_count() {
        val l = newLogic()
        l.stepsAvailable = false
        l.activeAt(9_000)
        l.onAccident(10_500)
        l.strongRun(11_000, 3) // windows 10-11 s, 11-12 s, 12-13 s: only two start after the impact
        assertEquals(Mode.CHECKING, l.modeAt(40_500))
    }

    @Test fun still_period_must_complete_within_5min() {
        val l = newLogic()
        l.activeAt(9_000)
        l.onAccident(10_000)
        l.steps(290_000, 5)
        assertEquals(Mode.WATCHING, l.modeAt(310_000))
        assertEquals(Mode.WATCHING, l.modeAt(320_000))
        assertEquals(Mode.WATCHING, l.modeAt(400_000))
    }

    @Test fun new_trigger_during_suspicion_extends_window() {
        val l = newLogic()
        l.activeAt(9_000)
        l.onAccident(10_000)
        l.steps(20_000, 5)
        l.activeAt(99_000)
        l.onAccident(100_000)
        l.steps(290_000, 5)
        assertEquals(Mode.WATCHING, l.modeAt(319_999))
        assertEquals(Mode.CHECKING, l.modeAt(320_000))
    }

    @Test fun plug_within_10s_before_impact_ignores_trigger() {
        val l = newLogic()
        l.setCharging(true, 10_000)
        l.activeAt(15_000)
        l.onAccident(20_000)
        assertEquals(Mode.WATCHING, l.modeAt(50_000))
        assertEquals(Mode.WATCHING, l.modeAt(330_000))

        val early = newLogic()
        early.setCharging(true, 9_999)
        early.activeAt(15_000)
        early.onAccident(20_000)
        assertEquals(Mode.CHECKING, early.modeAt(50_000))
    }

    @Test fun plug_within_10s_after_impact_drops_suspicion() {
        val l = newLogic()
        l.activeAt(9_000)
        l.onAccident(10_000)
        l.setCharging(true, 20_000)
        assertEquals(Mode.WATCHING, l.modeAt(40_000))
        assertEquals(Mode.WATCHING, l.modeAt(320_000))

        val late = newLogic()
        late.activeAt(9_000)
        late.onAccident(10_000)
        late.setCharging(true, 20_001)
        assertEquals(Mode.CHECKING, late.modeAt(40_000))
    }

    @Test fun accident_rule_applies_in_settled_zone_and_while_docked() {
        val l = newLogic(charging = true, zoneInside = true)
        l.tick(60_000)
        assertEquals(true, l.zoneSettled)
        assertEquals(LoneWorkerLogic.Rest.DOCKED, l.rest)
        l.activeAt(99_000)
        l.onAccident(100_000)
        assertEquals(Mode.CHECKING, l.modeAt(130_000))
        assertEquals(Mode.SOS, l.modeAt(190_000))
    }

    @Test fun real_plug_during_accident_check_closes_it_and_ends_suspicion() {
        val l = newLogic()
        l.activeAt(9_000)
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.modeAt(40_000))
        l.setCharging(true, 50_000)
        assertEquals(Mode.WATCHING, l.mode)
        for (t in 60_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.modeAt(t))
    }

    @Test fun unplug_during_accident_check_keeps_it_running_to_sos() {
        val l = newLogic(charging = true)
        l.activeAt(9_000)
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.modeAt(40_000))
        l.setCharging(false, 45_000)
        assertEquals(Mode.CHECKING, l.modeAt(99_999))
        assertEquals(Mode.SOS, l.modeAt(100_000))
    }

    @Test fun ok_closes_accident_check_and_ends_suspicion() {
        val l = newLogic()
        l.activeAt(9_000)
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.modeAt(40_000))
        assertEquals(true, l.ackWorking(45_000))
        for (t in 50_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.modeAt(t))
    }

    @Test fun steps_close_accident_check_and_watching_continues_until_5min() {
        val l = newLogic()
        l.activeAt(9_000)
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.modeAt(40_000))
        l.steps(50_000, 5)
        assertEquals(Mode.WATCHING, l.mode)
        assertEquals(Mode.WATCHING, l.modeAt(79_999))
        assertEquals(Mode.CHECKING, l.modeAt(80_000))
        l.steps(285_000, 5)
        assertEquals(Mode.WATCHING, l.mode)
        for (t in 290_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.modeAt(t))
    }

    @Test fun moved_signal_alone_does_not_close_accident_check() {
        val l = newLogic()
        l.activeAt(9_000)
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.modeAt(40_000))
        l.onMoved(45_000)
        l.activeAt(46_000, 47_000, 48_000, 49_000)
        assertEquals(Mode.CHECKING, l.modeAt(50_000))
    }

    @Test fun earlier_deadline_wins_when_accident_fires_during_still_check() {
        val l = newLogic()
        l.stillMs = 180_000L
        assertEquals(Mode.CHECKING, l.modeAt(180_000))
        assertEquals("still", l.trigger)
        l.activeAt(174_000)
        l.onAccident(175_000)
        assertEquals(Mode.CHECKING, l.modeAt(205_000))
        assertEquals("fall", l.trigger)
        assertEquals(Mode.CHECKING, l.modeAt(264_999))
        assertEquals(Mode.SOS, l.modeAt(265_000))

        val later = newLogic()
        later.stillMs = 180_000L
        later.tick(180_000)
        later.activeAt(214_000)
        later.onAccident(215_000)
        later.tick(245_000)
        assertEquals("still", later.trigger)
        assertEquals(Mode.SOS, later.modeAt(300_000))
        assertEquals("still", later.trigger)
    }

    @Test fun sos_in_progress_ignores_new_triggers() {
        val l = newLogic()
        l.restoreSos("still", 1_000)
        l.activeAt(9_000)
        l.onAccident(10_000)
        l.tick(40_000)
        assertEquals(Mode.SOS, l.mode)
        l.cancelSos(41_000)
        for (t in 50_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.modeAt(t))
    }

    @Test fun disabled_ignores_accident_and_withdraws_accident_check() {
        val off = newLogic()
        off.setEnabled(false, 0)
        off.activeAt(9_000)
        off.onAccident(10_000)
        assertEquals(Mode.WATCHING, off.modeAt(40_000))

        val chk = newLogic()
        chk.activeAt(9_000)
        chk.onAccident(10_000)
        assertEquals(Mode.CHECKING, chk.modeAt(40_000))
        chk.setEnabled(false, 41_000)
        assertEquals(Mode.WATCHING, chk.mode)
        chk.setEnabled(true, 42_000)
        for (t in 50_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, chk.modeAt(t))
    }
}
