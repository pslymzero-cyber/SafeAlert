package com.wf11.safealert.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** VibrationWindow: own vibration span used to drop motion samples (v1.1.99, F07). Pure JVM. */
class VibrationWindowTest {

    @Test
    fun fresh_window_covers_nothing() {
        val w = VibrationWindow()
        assertFalse(w.covers(0L))
        assertFalse(w.covers(1_000L))
        assertFalse(w.covers(Long.MAX_VALUE - 1))
    }

    @Test
    fun timed_vibration_covers_start_to_end_plus_grace() {
        val w = VibrationWindow()
        w.onStart(1_000L, 500L)
        assertFalse(w.covers(999L))
        assertTrue(w.covers(1_000L))
        assertTrue(w.covers(1_700L))
        assertFalse(w.covers(1_701L))
    }

    @Test
    fun loop_has_no_overflow_and_stop_truncates() {
        val w = VibrationWindow()
        w.onStart(1_000L, -1L)
        assertTrue(w.covers(1_000_000_000L))
        w.onStop(5_000L)
        assertTrue(w.covers(5_200L))
        assertFalse(w.covers(5_201L))
    }

    @Test
    fun overlapping_start_keeps_the_first_start() {
        val w = VibrationWindow()
        w.onStart(1_000L, 500L)
        w.onStart(1_300L, 500L)
        assertTrue(w.covers(1_100L))
        assertTrue(w.covers(2_000L))
        assertFalse(w.covers(2_001L))
    }
}
