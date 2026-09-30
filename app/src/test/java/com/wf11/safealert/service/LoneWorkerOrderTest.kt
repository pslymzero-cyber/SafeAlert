package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import com.wf11.safealert.service.LoneWorkerLogic.Rest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Judgment order (JudgeOrder): the same sensor values and raw power values with their times give the same result
 * whenever the sensor batches arrive and whenever the power confirm tick runs (N1, Y2).
 */
class LoneWorkerOrderTest : RestartKit() {

    /** Charging, carried by walking-shaped windows (no step sensor), still check open at 200.95 s: SOS due 320.95 s. */
    private fun heldUnplug(): LoneWorkerLogic = newLogic(charging = true).apply {
        stepsAvailable = false
        strongWindows(4_000, 8_000, 12_000, 16_000, 20_000)
        sensed(200_000)
        assertEquals(Mode.CHECKING, modeAt(200_950))
    }

    /** One accelerometer window callback every second from..to, walking-shaped when its end is in walking. */
    private fun windows(from: Long, to: Long, walking: LongRange): List<Feed> = (from..to step 1_000L).map { s ->
        Feed(s, sense = { onWindow(MotionAnalyzer.Window(s, s in walking)) })
    }

    /**
     * Unplug at 320.9 s, 50 ms before the SOS deadline, then walking windows 320..322 s (3 s run). The walking
     * after the deadline waits for the confirm (or the bounce back) and the SOS goes out, delivered live or late.
     */
    @Test fun inputs_after_a_power_blocked_deadline_wait_for_its_judgment() {
        val sensors = windows(201_000, 324_000, 320_000L..322_000L)
        val confirm = listOf(Feed(320_900, on = false))
        val drop = confirm + Feed(322_500, on = true)
        for ((power, late, want) in listOf(
            Triple(confirm, 320_000L..322_950L, listOf("SOS still @322950", "end SOS still WAIT")),
            Triple(drop, 320_000L..322_500L, listOf("SOS still @322500", "end SOS still NONE")))) {
            val feeds = (sensors + power).sortedBy { it.at }
            val live = heldUnplug().drive(200_950, feeds, 324_000)
            val later = heldUnplug().drive(200_950, feeds, 324_000, late, late.last)
            assertEquals(listOf("CHECKING still @200950") + want, live)
            assertEquals(live, later)
        }
    }

    /** Plug at 301 s after the SOS deadline 300 s: SOS whether the data comes before or after the confirm. */
    @Test fun stable_change_after_a_passed_deadline_waits_for_its_judgment() {
        for (late in listOf(false, true)) {
            val m = "late=$late"
            val l = newLogic(carried = true)
            assertEquals(m, Mode.CHECKING, l.seenAt(180_000))
            assertEquals(m, Mode.CHECKING, l.modeAt(300_000))
            l.powerRaw(true, 301_000)
            if (late) {
                assertEquals(m, Mode.SOS, l.seenAt(303_000))
                assertEquals(m, Mode.SOS, l.modeAt(306_000))
            } else {
                assertEquals(m, Mode.SOS, l.seenAt(302_000))
                assertEquals(m, Mode.SOS, l.modeAt(303_050))
            }
            assertEquals(m, Rest.DOCKED, l.rest)
        }
    }

    /** A change at the restart is applied first even when the saved still deadline has passed (E9, L1). */
    @Test fun restart_change_is_not_deferred_by_an_old_deadline() {
        for (plugged in listOf(true, false)) {
            val m = "plugged=$plugged"
            val old = if (plugged) newLogic(carried = true) else carriedWhileCharging()
            val s = saved(old, 100_000, 5_000, 300_000)
            assertTrue(m, s.carried && s.stillBase + old.stillMs < 5_000)
            val l = restart(old, 100_000, 5_000, 300_000, charging = plugged)
            assertEquals(m, Mode.WATCHING, l.seenAt(5_000 + RestartHold.POWER_HOLD_MS + 1_000))
            assertEquals(m, if (plugged) Rest.DOCKED else Rest.WAIT, l.rest)
        }
    }

    @Test fun next_check_never_returns_a_past_time() {
        val l = newLogic(carried = true)
        l.powerRaw(true, 1_000)
        assertEquals(180_000L, l.nextCheckAt(5_000))
    }

    /** The confirmed change leaves the debounce before the opposite raw value, which then starts a new wait (Y8). */
    @Test fun opposite_broadcast_right_after_a_confirmation_starts_a_new_wait() {
        val l = newLogic(carried = true)
        l.powerRaw(true, 10_000)
        assertTrue(l.powerRaw(false, 12_500))
        assertEquals(Rest.DOCKED, l.rest)
        assertEquals(12_500 + PowerDebounce.CONFIRM_MS, l.nextCheckAt(12_500))
        l.seenAt(14_500)
        assertEquals(Rest.WAIT, l.rest)
    }

    /** A sticky check that only confirms a stable change still asks the monitor for a tick (Y8). */
    @Test fun sticky_call_that_only_confirms_asks_for_a_tick() {
        val l = newLogic(carried = true)
        l.powerRaw(true, 10_000)
        assertTrue(l.powerRaw(true, 12_000, sticky = true))
        assertEquals(Rest.DOCKED, l.rest)
    }
}
