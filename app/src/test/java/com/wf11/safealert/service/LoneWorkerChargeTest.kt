package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import com.wf11.safealert.service.LoneWorkerLogic.Rest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule 2 (no motion) runs only while the device is carried:
 *  - not charging: carried from the first distinct motion after start or unplug (or after 1 min of
 *    sensor silence); before that it waits.
 *  - charging: docked until 10 steps (no step sensor: 30 s of continuous strong motion), then
 *    carried until the next real plug.
 *  - off in a settled zone. Power flaps shorter than 2 s are ignored; a real plug resets carrying
 *    and withdraws open checks.
 */
class LoneWorkerChargeTest {

    private val stillMs = 180_000L
    private val responseMs = 120_000L

    private fun newLogic(charging: Boolean = false, zoneInside: Boolean = false) =
        LoneWorkerLogic("SAFEALERT_WALKER_ME").apply { start(0L, zoneInside, charging) }

    private fun LoneWorkerLogic.steps(lastMs: Long, n: Int) {
        for (i in n - 1 downTo 0) onStep(lastMs - i * 500L)
    }

    private fun LoneWorkerLogic.strongRun(firstEnd: Long, count: Int) {
        for (i in 0 until count) onWindow(MotionAnalyzer.Window(firstEnd + i * 1000L, true, true, true))
    }

    private fun LoneWorkerLogic.modeAt(t: Long): Mode { tick(t); return mode }

    /** Charging, then carried by 10 steps ending at 10 s. */
    private fun carriedWhileCharging() = newLogic(charging = true).apply { steps(10_000, 10) }

    @Test fun start_without_charging_waits_for_first_distinct_motion() {
        val l = newLogic()
        assertEquals(Rest.WAIT, l.rest)
        assertEquals(Mode.WATCHING, l.modeAt(600_000))
        l.steps(600_000, 5)
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.WATCHING, l.modeAt(600_000 + stillMs - 1))
        assertEquals(Mode.CHECKING, l.modeAt(600_000 + stillMs))
        assertEquals("still", l.trigger)
    }

    @Test fun four_steps_do_not_end_the_wait() {
        val l = newLogic()
        l.steps(10_000, 4)
        assertEquals(Rest.WAIT, l.rest)
        assertEquals(Mode.WATCHING, l.modeAt(600_000))
    }

    @Test fun without_step_sensor_3s_strong_motion_ends_the_wait() {
        val l = newLogic()
        l.stepsAvailable = false
        l.strongRun(10_000, 2)
        assertEquals(Rest.WAIT, l.rest)
        l.strongRun(20_000, 3)
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.CHECKING, l.modeAt(22_000 + stillMs))
    }

    @Test fun unplug_waits_for_first_distinct_motion() {
        val l = newLogic(charging = true)
        assertEquals(Rest.DOCKED, l.rest)
        l.steps(9_000, 4)
        l.setCharging(false, 10_000)
        assertEquals(Rest.WAIT, l.rest)
        l.onStep(11_000)
        assertEquals(Rest.WAIT, l.rest)
        l.steps(15_000, 5)
        assertEquals(Rest.NONE, l.rest)
    }

    @Test fun sensor_silence_ends_wait_and_counts_from_there() {
        val l = newLogic()
        l.sensorSilent(100_000)
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.WATCHING, l.modeAt(100_000 + stillMs - 1))
        assertEquals(Mode.CHECKING, l.modeAt(100_000 + stillMs))
    }

    @Test fun charging_without_steps_is_docked_and_never_checks_still() {
        val l = newLogic(charging = true)
        l.steps(10_000, 9)
        assertEquals(Rest.DOCKED, l.rest)
        for (t in 60_000L..3_600_000L step 60_000L) assertEquals(Mode.WATCHING, l.modeAt(t))
    }

    @Test fun charging_with_ten_steps_is_carried_until_replug() {
        val l = carriedWhileCharging()
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.WATCHING, l.modeAt(10_000 + stillMs - 1))
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.CHECKING, l.modeAt(10_000 + stillMs))
        assertEquals("still", l.trigger)
    }

    @Test fun steps_before_the_plug_do_not_count_toward_carrying() {
        val l = newLogic()
        l.steps(9_000, 9)
        l.setCharging(true, 10_000)
        l.onStep(11_000)
        assertEquals(Rest.DOCKED, l.rest)
    }

    @Test fun replug_resets_step_carry() {
        val l = carriedWhileCharging()
        l.setCharging(false, 20_000)
        assertEquals(Rest.WAIT, l.rest)
        l.setCharging(true, 30_000)
        assertEquals(Rest.DOCKED, l.rest)
    }

    @Test fun fallback_30s_strong_motion_carries_only_when_steps_unavailable() {
        val short = newLogic(charging = true)
        short.stepsAvailable = false
        short.strongRun(1_000, 29)
        assertEquals(Rest.DOCKED, short.rest)

        val long = newLogic(charging = true)
        long.stepsAvailable = false
        long.strongRun(1_000, 30)
        assertEquals(Rest.NONE, long.rest)

        val withSteps = newLogic(charging = true)
        withSteps.strongRun(1_000, 30)
        assertEquals(Rest.DOCKED, withSteps.rest)
    }

    @Test fun still_rule_is_off_in_settled_zone() {
        val l = newLogic(zoneInside = true)
        l.sensorSilent(0)
        l.tick(60_000)
        assertTrue(l.zoneSettled)
        for (t in 60_000L..900_000L step 60_000L) assertEquals(Mode.WATCHING, l.modeAt(t))
    }

    @Test fun real_plug_withdraws_open_still_check() {
        val l = newLogic()
        l.sensorSilent(0)
        assertEquals(Mode.CHECKING, l.modeAt(stillMs))
        l.setCharging(true, stillMs + 1_000)
        assertEquals(Mode.WATCHING, l.mode)
        assertEquals(Rest.DOCKED, l.rest)
        assertEquals(Mode.WATCHING, l.modeAt(stillMs + responseMs + 10_000))
    }

    @Test fun unplug_during_still_check_keeps_it_running_to_sos() {
        val l = carriedWhileCharging()
        assertEquals(Mode.CHECKING, l.modeAt(10_000 + stillMs))
        l.setCharging(false, 200_000)
        assertEquals(Rest.WAIT, l.rest)
        assertEquals(Mode.CHECKING, l.modeAt(10_000 + stillMs + responseMs - 1))
        assertEquals(Mode.SOS, l.modeAt(10_000 + stillMs + responseMs))
    }

    @Test fun still_check_closed_by_ok_or_distinct_motion_not_by_moved() {
        val moved = newLogic()
        moved.sensorSilent(0)
        moved.tick(stillMs)
        moved.onMoved(stillMs + 1_000)
        moved.steps(stillMs + 5_000, 4)
        assertEquals(Mode.CHECKING, moved.modeAt(stillMs + 6_000))

        val walked = newLogic()
        walked.sensorSilent(0)
        walked.tick(stillMs)
        walked.steps(stillMs + 5_000, 5)
        assertEquals(Mode.WATCHING, walked.mode)
        assertEquals(Mode.WATCHING, walked.modeAt(stillMs + 5_000 + stillMs - 1))
        assertEquals(Mode.CHECKING, walked.modeAt(stillMs + 5_000 + stillMs))

        val ok = newLogic()
        ok.sensorSilent(0)
        ok.tick(stillMs)
        assertTrue(ok.ackWorking(stillMs + 1_000))
        assertEquals(Mode.WATCHING, ok.mode)
    }

    @Test fun rest_state_carries_its_own_texts() {
        assertNull(Rest.NONE.banner)
        assertTrue(Rest.DOCKED.banner!!.isNotEmpty())
        assertTrue(Rest.WAIT.banner!!.isNotEmpty())
        assertFalse(Rest.DOCKED.keepText == Rest.NONE.keepText)
        assertFalse(Rest.WAIT.keepText == Rest.NONE.keepText)
    }

    // -- PowerDebounce (2 s) --

    @Test fun debounce_ignores_flaps_shorter_than_2s() {
        val d = PowerDebounce()
        d.seed(false)
        d.raw(true, 0)
        d.raw(false, 1_000)
        assertNull(d.poll(2_050))
        assertNull(d.poll(10_000))

        val u = PowerDebounce()
        u.seed(true)
        u.raw(false, 0)
        u.raw(true, 1_500)
        assertNull(u.poll(3_550))
        assertTrue(u.reported)
    }

    @Test fun debounce_reports_stable_change_with_first_change_time() {
        val d = PowerDebounce()
        d.seed(false)
        d.raw(true, 1_000)
        d.raw(true, 1_500)
        assertNull(d.poll(2_999))
        assertEquals(true to 1_000L, d.poll(3_000))
        assertNull(d.poll(3_001))
        assertTrue(d.reported)
    }

    @Test fun debounce_restarts_when_a_flap_returns() {
        val d = PowerDebounce()
        d.seed(false)
        d.raw(true, 0)
        d.raw(false, 500)
        d.raw(true, 1_000)
        assertNull(d.poll(2_050))
        assertEquals(true to 1_000L, d.poll(3_050))
    }
}
