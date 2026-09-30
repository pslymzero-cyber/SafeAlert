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
 *  - charging: docked until 10 walking-shaped steps within 30 s (no step sensor: 5 walking-shaped
 *    windows within 30 s), then carried until the next real plug.
 *  - off in a settled zone. Power flaps shorter than 2 s are ignored; a real plug resets carrying
 *    and withdraws open checks.
 */
class LoneWorkerChargeTest {

    private val stillMs = 180_000L
    private val responseMs = 120_000L

    @Test fun start_without_charging_waits_for_first_distinct_motion() {
        val l = newLogic()
        assertEquals(Rest.WAIT, l.rest)
        assertEquals(Mode.WATCHING, l.modeAt(600_000))
        l.walk(600_000, 5)
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.WATCHING, l.seenAt(600_000 + stillMs - 1))
        assertEquals(Mode.CHECKING, l.seenAt(600_000 + stillMs))
        assertEquals("still", l.trigger)
    }

    @Test fun four_steps_do_not_end_the_wait() {
        val l = newLogic()
        l.walk(10_000, 4)
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
        assertEquals(Mode.CHECKING, l.seenAt(22_000 + stillMs))
    }

    @Test fun unplug_waits_for_first_distinct_motion() {
        val l = newLogic(charging = true)
        assertEquals(Rest.DOCKED, l.rest)
        l.walk(9_000, 4)
        l.setCharging(false, 10_000)
        assertEquals(Rest.WAIT, l.rest)
        l.step(11_000)
        assertEquals(Rest.WAIT, l.rest)
        l.walk(15_000, 5)
        assertEquals(Rest.NONE, l.rest)
    }

    /**
     * Steps after the unplug edge reported before the debounce confirms it carry from the distinct motion;
     * the still deadline counts from the distinct motion after the unplug edge, not from one made with earlier steps.
     */
    @Test fun steps_before_unplug_report_carry_from_the_distinct_motion() {
        val l = newLogic(charging = true)
        l.step(9_300)
        l.step(9_700)
        for (i in 0..4) l.step(10_100L + i * 400)
        l.setCharging(false, 10_000)
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.WATCHING, l.seenAt(11_700 + stillMs - 1))
        assertEquals(Mode.CHECKING, l.seenAt(11_700 + stillMs))
        assertEquals("still", l.trigger)
        // only three steps after the unplug edge: steps before the unplug do not count
        val early = newLogic(charging = true)
        for (i in 0..4) early.step(9_300L + i * 400)
        early.setCharging(false, 10_000)
        assertEquals(Rest.WAIT, early.rest)
    }

    /** An unplug report does not lower the open check's step floor: steps before the check opened do not close it. */
    @Test fun unplug_report_keeps_the_open_check_floor() {
        val l = carriedWhileCharging()
        val open = 10_000 + l.stillMs
        l.powerRaw(false, open - 1_000)
        for (i in 0..3) l.step(open - 900 + i * 200)
        assertEquals(Mode.CHECKING, l.seenAt(open))
        assertEquals("still", l.trigger)
        assertEquals(Mode.CHECKING, l.seenAt(open - 1_000 + PowerDebounce.CONFIRM_MS))
        assertEquals(Rest.WAIT, l.rest)
        l.step(open + 1_500)
        assertEquals(Mode.CHECKING, l.seenAt(open + 2_000))
        // the same for an open accident check
        val f = newLogic(charging = true)
        f.onAccident(1_000)
        f.powerRaw(false, 30_000)
        for (i in 0..3) f.step(30_100 + i * 200L)
        assertEquals(Mode.CHECKING, f.seenAt(31_000))
        assertEquals("fall", f.trigger)
        assertEquals(Mode.CHECKING, f.seenAt(30_000 + PowerDebounce.CONFIRM_MS))
        f.step(32_500)
        assertEquals(Mode.CHECKING, f.seenAt(33_000))
        assertEquals("fall", f.trigger)
    }

    @Test fun sensor_silence_ends_wait_and_counts_from_there() {
        val l = newLogic()
        l.sensorSilent(100_000)
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.WATCHING, l.seenAt(100_000 + stillMs - 1))
        assertEquals(Mode.CHECKING, l.seenAt(100_000 + stillMs))
    }

    @Test fun charging_without_steps_is_docked_and_never_checks_still() {
        val l = newLogic(charging = true)
        l.walk(10_000, 9)
        assertEquals(Rest.DOCKED, l.rest)
        for (t in 60_000L..3_600_000L step 60_000L) assertEquals(Mode.WATCHING, l.modeAt(t))
    }

    @Test fun charging_with_ten_steps_is_carried_until_replug() {
        val l = carriedWhileCharging()
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.WATCHING, l.seenAt(10_000 + stillMs - 1))
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.CHECKING, l.seenAt(10_000 + stillMs))
        assertEquals("still", l.trigger)
    }

    @Test fun steps_before_the_plug_do_not_count_toward_carrying() {
        val l = newLogic()
        l.walk(9_000, 9)
        l.setCharging(true, 10_000)
        l.step(11_000)
        assertEquals(Rest.DOCKED, l.rest)
    }

    @Test fun replug_resets_step_carry() {
        val l = carriedWhileCharging()
        l.setCharging(false, 20_000)
        assertEquals(Rest.WAIT, l.rest)
        l.setCharging(true, 30_000)
        assertEquals(Rest.DOCKED, l.rest)
    }

    // No step sensor: carrying while charging = 5 walking-shaped windows within the last 30 s, not
    // necessarily in a row, all started after the plug.

    @Test fun fallback_five_walking_windows_within_30s_carry_while_charging() {
        val l = newLogic(charging = true)
        l.stepsAvailable = false
        l.strongWindows(5_000, 11_000, 17_000, 23_000)
        assertEquals(Rest.DOCKED, l.rest)
        l.strongWindows(29_000)
        assertEquals(Rest.NONE, l.rest)

        val withSteps = newLogic(charging = true)
        withSteps.strongWindows(5_000, 11_000, 17_000, 23_000, 29_000)
        assertEquals(Rest.DOCKED, withSteps.rest)
    }

    @Test fun fallback_four_walking_windows_do_not_carry() {
        val l = newLogic(charging = true)
        l.stepsAvailable = false
        l.strongWindows(5_000, 11_000, 17_000, 23_000)
        for (t in 24_000L..60_000L step 1_000L) {
            l.onWindow(MotionAnalyzer.Window(t, false))
            assertEquals(Rest.DOCKED, l.rest)
        }
    }

    @Test fun fallback_windows_spread_over_more_than_30s_do_not_carry() {
        val l = newLogic(charging = true)
        l.stepsAvailable = false
        for (i in 0 until 20) {
            l.strongWindows(5_000 + i * 8_000L)
            assertEquals(Rest.DOCKED, l.rest)
        }
    }

    @Test fun fallback_windows_before_the_plug_do_not_count() {
        val l = newLogic()
        l.stepsAvailable = false
        l.strongWindows(2_000, 4_000, 6_000, 8_000, 10_500)
        l.setCharging(true, 10_000)
        assertEquals(Rest.DOCKED, l.rest)
        l.strongWindows(11_000)
        assertEquals(Rest.DOCKED, l.rest)
        l.strongWindows(14_000, 16_000, 18_000)
        assertEquals(Rest.DOCKED, l.rest)
        l.strongWindows(20_000)
        assertEquals(Rest.NONE, l.rest)
    }

    @Test fun charging_with_ten_non_walking_steps_stays_docked() {
        val l = newLogic(charging = true)
        l.shuffle(10_000, 10)
        assertEquals(Rest.DOCKED, l.rest)
        l.shuffle(20_000, 20)
        assertEquals(Rest.DOCKED, l.rest)
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
        assertEquals(Mode.CHECKING, l.seenAt(stillMs))
        l.setCharging(true, stillMs + 1_000)
        assertEquals(Mode.WATCHING, l.mode)
        assertEquals(Rest.DOCKED, l.rest)
        assertEquals(Mode.WATCHING, l.modeAt(stillMs + responseMs + 10_000))
    }

    @Test fun unplug_during_still_check_keeps_it_running_to_sos() {
        val l = carriedWhileCharging()
        assertEquals(Mode.CHECKING, l.seenAt(10_000 + stillMs))
        l.setCharging(false, 200_000)
        assertEquals(Rest.WAIT, l.rest)
        assertEquals(Mode.CHECKING, l.modeAt(10_000 + stillMs + responseMs - 1))
        assertEquals(Mode.SOS, l.seenAt(10_000 + stillMs + responseMs))
    }

    @Test fun still_check_closed_by_ok_or_distinct_motion_not_by_moved() {
        val moved = newLogic()
        moved.sensorSilent(0)
        moved.seenAt(stillMs)
        moved.onMoved(stillMs + 1_000)
        moved.walk(stillMs + 5_000, 4)
        assertEquals(Mode.CHECKING, moved.modeAt(stillMs + 6_000))

        val walked = newLogic()
        walked.sensorSilent(0)
        walked.seenAt(stillMs)
        walked.walk(stillMs + 5_000, 5)
        assertEquals(Mode.WATCHING, walked.mode)
        assertEquals(Mode.WATCHING, walked.seenAt(stillMs + 5_000 + stillMs - 1))
        assertEquals(Mode.CHECKING, walked.seenAt(stillMs + 5_000 + stillMs))

        val ok = newLogic()
        ok.sensorSilent(0)
        ok.seenAt(stillMs)
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

    /** A change already stable for 2 s is reported before a later raw value, with or without a tick in between. */
    @Test fun stable_power_change_is_applied_before_a_later_raw() {
        val l = newLogic(charging = true)
        l.powerRaw(false, 10_000)
        assertEquals(10_000 + PowerDebounce.CONFIRM_MS, l.nextCheckAt(10_000))
        l.powerRaw(true, 12_500)
        assertEquals(Rest.WAIT, l.rest)
        l.modeAt(12_500 + PowerDebounce.DEBOUNCE_MS)
        assertEquals(Rest.DOCKED, l.rest)
        // a flap shorter than 2 s is dropped
        val d = newLogic(charging = true)
        d.powerRaw(false, 10_000)
        d.powerRaw(true, 11_500)
        assertEquals(Rest.DOCKED, d.rest)
        d.modeAt(20_000)
        assertEquals(Rest.DOCKED, d.rest)
    }

    // Carrying while charging = 10 steps within the last 30 s.

    @Test fun ten_steps_within_30s_while_charging_carry() {
        val l = newLogic(charging = true)
        for (i in 0 until 10) l.step(1_000 + i * 3_000L)
        assertEquals(Rest.NONE, l.rest)
    }

    @Test fun ten_steps_spread_over_more_than_30s_do_not_carry() {
        val l = newLogic(charging = true)
        for (i in 0 until 60) {
            l.step(1_000 + i * 3_500L)
            assertEquals(Rest.DOCKED, l.rest)
        }
    }

    @Test fun sporadic_single_steps_never_close_still_check() {
        val l = newLogic()
        l.sensorSilent(0L)
        assertEquals(Mode.CHECKING, l.seenAt(stillMs))
        for (t in stillMs + 1_000 until stillMs + responseMs step 3_000L) {
            l.step(t)
            assertEquals(Mode.CHECKING, l.modeAt(t))
        }
        assertEquals(Mode.SOS, l.seenAt(stillMs + responseMs))
    }
}
