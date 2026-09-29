package com.wf11.safealert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SensorStall: accelerometer silence detection (v1.1.99, RR08). Pure JVM. */
class SensorStallTest {

    @Test
    fun events_keep_it_ok() {
        val s = SensorStall()
        s.reset(0L)
        s.onEvent(20_000L)
        assertEquals(SensorStall.Action.OK, s.check(49_000L))
        assertFalse(s.stalled)
    }

    @Test
    fun stall_retries_with_backoff_and_reports_stalled_until_an_event() {
        val s = SensorStall()
        s.reset(0L)
        assertEquals(SensorStall.Action.OK, s.check(29_999L))
        assertEquals(SensorStall.Action.REREGISTER, s.check(30_000L))
        assertFalse(s.stalled)
        assertEquals(SensorStall.Action.OK, s.check(45_000L))
        assertEquals(SensorStall.Action.REREGISTER, s.check(60_000L))
        assertTrue(s.stalled)
        assertEquals(SensorStall.Action.STALLED, s.check(61_000L))
        assertEquals(SensorStall.Action.STALLED, s.check(119_999L))
        assertEquals(SensorStall.Action.REREGISTER, s.check(120_000L))
        assertEquals(SensorStall.Action.REREGISTER, s.check(240_000L))
        // gaps so far 30, 60, 120, 240 s; the next is capped at 5 min
        assertEquals(SensorStall.Action.STALLED, s.check(479_999L))
        assertEquals(SensorStall.Action.REREGISTER, s.check(480_000L))
        assertEquals(SensorStall.Action.STALLED, s.check(779_999L))
        assertEquals(SensorStall.Action.REREGISTER, s.check(780_000L))
        s.onEvent(781_000L)
        assertFalse(s.stalled)
        assertEquals(SensorStall.Action.OK, s.check(810_000L))
        assertEquals(SensorStall.Action.REREGISTER, s.check(811_000L))
    }
}
