package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Rest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walking-shaped steps: a step counts only when the closed 1 s accelerometer window covering its
 * time (end - 1000 <= t < end) is walking-shaped and the app was not vibrating at that time.
 * floor = the time after which steps or windows count (a start, an unplug edge, a check opening); a step exactly
 * at the floor does not count. steps_ tests call WalkingSteps directly, logic_ tests go through LoneWorkerLogic.
 */
class WalkingStepsTest {

    /** within / firstWithin count only steps after the floor time (the floor step itself does not count). */
    @Test fun steps_within_counts_only_steps_after_the_floor() {
        val w = WalkingSteps()
        for (t in listOf(1_100L, 1_300L, 1_500L, 1_700L, 1_900L)) w.onStep(t, false)
        w.onWindow(2_000, true)
        assertTrue(w.within(1_000, 1_900, 5, 10_000L))
        assertFalse(w.within(1_100, 1_900, 5, 10_000L))
        assertEquals(1_900L, w.firstWithin(1_000, 5, 10_000L))
        assertNull(w.firstWithin(1_100, 5, 10_000L))
    }

    @Test fun step_before_its_window_is_accepted_when_the_window_closes_walking() {
        val w = WalkingSteps()
        assertNull(w.onStep(1_200, false))
        assertNull(w.onStep(1_700, false))
        assertEquals(listOf(1_200L, 1_700L), w.onWindow(2_000, true))
        assertEquals(2, w.stepsIn(0, 10_000))
    }

    @Test fun step_before_its_window_is_dropped_when_the_window_is_not_walking() {
        val w = WalkingSteps()
        w.onStep(1_200, false)
        assertEquals(emptyList<Long>(), w.onWindow(2_000, false))
        assertEquals(0, w.stepsIn(0, 10_000))
    }

    @Test fun step_after_its_window_closed_is_judged_by_the_recorded_window() {
        val w = WalkingSteps()
        w.onWindow(2_000, true)
        w.onWindow(3_000, false)
        assertEquals(1_500L, w.onStep(1_500, false))
        assertNull(w.onStep(2_500, false))
        assertEquals(1, w.stepsIn(0, 10_000))
    }

    @Test fun step_without_a_covering_window_is_dropped_when_a_later_window_closes() {
        val w = WalkingSteps()
        w.onWindow(1_000, true)
        w.onStep(1_500, false)
        // no samples in 1-2 s: the next closed window is 2-3 s
        assertEquals(emptyList<Long>(), w.onWindow(3_000, true))
        assertEquals(0, w.stepsIn(0, 10_000))
        // a gap before any recorded window also drops a late step
        assertNull(w.onStep(1_800, false))
    }

    @Test fun step_during_app_vibration_is_dropped() {
        val w = WalkingSteps()
        assertNull(w.onStep(1_200, true))
        assertEquals(emptyList<Long>(), w.onWindow(2_000, true))
        w.onWindow(3_000, true)
        assertNull(w.onStep(2_500, true))
        assertEquals(0, w.stepsIn(0, 10_000))
        assertEquals(2_500L, w.stepSeenTo)
    }

    @Test fun window_at_or_before_the_closed_end_is_ignored() {
        val w = WalkingSteps()
        w.onWindow(2_000, false)
        assertNull(w.onWindow(2_000, true))
        assertNull(w.onWindow(1_500, true))
        assertNull(w.onStep(1_600, false))
        assertEquals(2_000L, w.closedTo)
    }

    @Test fun steps_in_includes_both_edges() {
        val w = WalkingSteps()
        for (t in listOf(1_000L, 5_000L, 11_000L)) {
            w.onStep(t, false)
            w.onWindow(t / 1000 * 1000 + 1000, true)
        }
        assertEquals(3, w.stepsIn(1_000, 11_000))
        assertEquals(2, w.stepsIn(1_001, 11_000))
        assertEquals(2, w.stepsIn(1_000, 10_999))
    }

    @Test fun logic_distinct_motion_needs_5_steps_after_the_floor_within_10s_inclusive() {
        // start floor = 0: a step at exactly 0 does not count; 5 steps spanning exactly 10 s do
        val l = newLogic()
        for (t in listOf(0L, 2_500L, 5_000L, 7_500L, 10_000L)) l.step(t)
        assertEquals(Rest.WAIT, l.rest)
        l.step(12_500)
        assertEquals(Rest.NONE, l.rest)
    }

    @Test fun strong_in_counts_walking_windows_started_and_ended_inside() {
        val w = WalkingSteps()
        for (e in listOf(1_000L, 5_000L, 9_000L, 13_000L, 17_000L, 21_000L, 25_000L, 29_000L, 31_000L)) {
            w.onWindow(e, true)
        }
        w.onWindow(32_000, false)
        assertEquals(9, w.strongIn(0, 32_000))
        assertEquals(8, w.strongIn(4_000, 32_000))
        assertEquals(7, w.strongIn(4_001, 32_000))
        assertEquals(8, w.strongIn(0, 30_999))
        // the last 30 s up to 31 s holds every window but the first (started at 0 s)
        assertEquals(8, w.strongIn(31_000 - 30_000, 31_000))
    }

    /**
     * firstRunEnd(floor, length): the end of the first window where a run of walking-shaped windows, each starting at or
     * after the floor, reaches the length; a still window or a second with no samples breaks the run.
     */
    @Test fun first_run_end_is_where_a_run_from_the_floor_reaches_the_length() {
        val w = WalkingSteps()
        w.onWindow(1_000, true)
        w.onWindow(2_000, true)
        w.onWindow(3_000, false)
        for (e in listOf(4_000L, 5_000L, 6_000L, 7_000L)) w.onWindow(e, true)
        w.onWindow(9_000, true) // 8 s had no samples: the run breaks
        assertEquals(6_000L, w.firstRunEnd(0, 3_000))
        assertEquals(7_000L, w.firstRunEnd(3_500, 3_000))
        assertEquals(7_000L, w.firstRunEnd(0, 4_000))
        assertNull(w.firstRunEnd(5_000, 3_000))
        assertEquals(1_000L, w.firstRunEnd(0, 1_000))

        // windows from the floor only; a run that ended before the latest (still) window is still found
        val v = WalkingSteps()
        v.onWindow(1_000, true)
        v.onWindow(2_000, false)
        v.onWindow(3_000, true)
        v.onWindow(4_000, true)
        v.onWindow(5_000, true)
        assertEquals(5_000L, v.firstRunEnd(0, 3_000))
        assertEquals(5_000L, v.firstRunEnd(2_500, 2_000))
        assertNull(v.firstRunEnd(2_500, 3_000))
        v.onWindow(7_000, true) // 5-6 s had no samples: the run breaks
        assertNull(v.firstRunEnd(4_000, 2_000))
        v.onWindow(8_000, false)
        assertEquals(7_000L, v.firstRunEnd(6_000, 1_000))
        assertNull(v.firstRunEnd(7_000, 1_000))
    }

    @Test fun delivered_times_follow_windows_steps_and_flushes() {
        val w = WalkingSteps()
        assertEquals(Long.MIN_VALUE, w.closedTo)
        assertEquals(Long.MIN_VALUE, w.stepSeenTo)
        w.onWindow(4_000, false)
        assertEquals(4_000L, w.closedTo)
        w.onStep(3_000, false)
        assertEquals(3_000L, w.stepSeenTo)
        w.stepsFlushed(5_000)
        assertEquals(5_000L, w.stepSeenTo)
        w.onStep(4_500, false)
        w.stepsFlushed(4_800)
        assertEquals(5_000L, w.stepSeenTo)
        w.reset()
        assertEquals(Long.MIN_VALUE, w.closedTo)
        assertTrue(w.stepsIn(Long.MIN_VALUE, Long.MAX_VALUE) == 0)
    }

    @Test fun cpu_wake_needed_only_for_non_wakeup_registered_sensors() {
        // (accelOn, accelWake, stepOn, stepWake)
        assertFalse(sensorsNeedCpuWake(false, false, false, false))
        assertFalse(sensorsNeedCpuWake(true, true, false, false))
        assertFalse(sensorsNeedCpuWake(false, false, true, true))
        assertFalse(sensorsNeedCpuWake(true, true, true, true))
        assertTrue(sensorsNeedCpuWake(true, false, false, false))
        assertTrue(sensorsNeedCpuWake(false, false, true, false))
        assertTrue(sensorsNeedCpuWake(true, true, true, false))
        assertTrue(sensorsNeedCpuWake(true, false, true, true))
        assertTrue(sensorsNeedCpuWake(true, false, true, false))
    }
}
