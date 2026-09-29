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
    fun stall_asks_reregister_once_then_reports_stalled_until_an_event() {
        val s = SensorStall()
        s.reset(0L)
        assertEquals(SensorStall.Action.OK, s.check(29_999L))
        assertEquals(SensorStall.Action.REREGISTER, s.check(30_000L))
        assertEquals(SensorStall.Action.OK, s.check(45_000L))
        assertEquals(SensorStall.Action.STALLED, s.check(60_000L))
        assertTrue(s.stalled)
        s.onEvent(61_000L)
        assertFalse(s.stalled)
        assertEquals(SensorStall.Action.OK, s.check(80_000L))
        assertEquals(SensorStall.Action.REREGISTER, s.check(91_000L))
    }
}
