package com.wf11.safealert.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** VibrationWindow: own vibration segments used to mask activity statistics (v1.1.99). Pure JVM. */
class VibrationWindowTest {

    @Test
    fun fresh_window_covers_nothing() {
        val w = VibrationWindow()
        assertFalse(w.covers(0L))
        assertFalse(w.covers(1_000L))
        assertFalse(w.covers(Long.MAX_VALUE - 1))
    }

    @Test
    fun one_shot_covers_start_to_end_plus_grace() {
        val w = VibrationWindow()
        w.oneShot(1_000L, 500L)
        assertFalse(w.covers(999L))
        assertTrue(w.covers(1_000L))
        assertTrue(w.covers(1_700L))
        assertFalse(w.covers(1_701L))
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
    fun later_one_shot_keeps_the_earlier_interval_for_late_samples() {
        val w = VibrationWindow()
        w.oneShot(1_000L, 500L)
        w.oneShot(1_300L, 500L)
        assertFalse(w.covers(999L))
        assertTrue(w.covers(1_100L))
        assertTrue(w.covers(2_000L))
        assertFalse(w.covers(2_001L))
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
    fun segments_older_than_ten_seconds_are_pruned() {
        val w = VibrationWindow()
        w.oneShot(0L, 500L)
        assertTrue(w.covers(100L))
        w.oneShot(20_000L, 100L)
        assertFalse(w.covers(100L))
        assertTrue(w.covers(20_050L))
    }
}
