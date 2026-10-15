package com.wf11.safealert.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** VibrationWindow: own vibration segments used to mask activity statistics. Pure JVM. */
class VibrationWindowTest {

    @Test
    fun one_shot_covers_start_to_end_plus_grace() {
        val w = VibrationWindow()
        w.oneShot(1_000L, 500L)
        assertFalse(w.covers(999L))
        assertTrue(w.covers(1_000L))
        assertTrue(w.covers(1_700L))
        assertFalse(w.covers(1_701L))

        // a later one-shot keeps the earlier interval for late samples
        w.oneShot(1_300L, 500L)
        assertFalse("second one-shot: before the first", w.covers(999L))
        assertTrue("second one-shot: the earlier interval is kept", w.covers(1_100L))
        assertTrue("second one-shot: covers to its end plus grace", w.covers(2_000L))
        assertFalse("second one-shot: past its grace", w.covers(2_001L))

        // segments older than ten seconds are pruned
        w.oneShot(20_000L, 100L)
        assertFalse("pruned after ten seconds", w.covers(1_100L))
        assertTrue(w.covers(20_050L))
    }

    @Test
    fun loop_masks_only_on_segments_and_stop_truncates() {
        val w = VibrationWindow()
        w.loopStart(1_000L, 700L, 1_000L)
        assertTrue(w.covers(1_000L))
        assertTrue(w.covers(1_899L))
        assertFalse(w.covers(1_900L))
        assertFalse(w.covers(1_999L))
        assertTrue(w.covers(2_000L))
        assertTrue(w.covers(1_000_001_000L))
        assertFalse(w.covers(1_000_001_950L))
        w.loopStop(5_000L)
        assertTrue(w.covers(5_200L))
        assertFalse(w.covers(5_201L))
        assertFalse(w.covers(4_950L))
    }

    @Test
    fun one_shot_during_a_loop_keeps_the_loop_on_segments() {
        val w = VibrationWindow()
        w.loopStart(0L, 700L, 1_000L)
        w.oneShot(1_750L, 100L)
        assertTrue(w.covers(1_950L))
        assertTrue(w.covers(2_100L))
        assertTrue(w.covers(3_000L))
        assertFalse(w.covers(2_950L))
    }

    @Test
    fun cut_ends_running_segments_at_the_cut_time() {
        val w = VibrationWindow()
        w.oneShot(0L, 100L)
        w.oneShot(1_000L, 2_000L)
        w.loopStart(1_500L, 100L, 500L)
        assertTrue(w.covers(2_100L))
        assertTrue(w.covers(2_500L))
        w.cut(1_800L)
        assertTrue(w.covers(1_999L))    // grace after the cut
        assertFalse(w.covers(2_001L))
        assertFalse(w.covers(2_100L))
        assertFalse(w.covers(2_500L))
        assertTrue(w.covers(250L))      // a finished segment is untouched (not stretched to the cut)
        assertFalse(w.covers(500L))
    }
}
