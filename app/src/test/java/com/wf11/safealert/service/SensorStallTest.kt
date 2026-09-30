package com.wf11.safealert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    /** Gyro measurement log (D3): one line per whole sensor second, magnitude mean and max. */
    @Test
    fun gyro_stats_log_one_line_per_second() {
        val g = GyroStats()
        assertNull(g.add(0L, 1f, 0f, 0f))
        assertNull(g.add(500L, 0f, 3f, 4f))
        assertEquals("gyro 1s n=2 mean=3.000 max=5.000 rad/s", g.add(1_000L, 0f, 0f, 2f))
    }

    /** Turning the gyro log off flushes the open second once (I10). */
    @Test
    fun gyro_stats_flush_emits_the_open_second_once() {
        val g = GyroStats()
        assertNull(g.flush())
        assertNull(g.add(0L, 0f, 3f, 4f))
        assertEquals("gyro 1s n=1 mean=5.000 max=5.000 rad/s", g.flush())
        assertNull(g.flush())
    }

    /** The gyro registration is tried and logged once per peer siren, and only while it vibrates (G1, D3). */
    @Test
    fun gyro_gate_tries_once_per_siren() {
        val g = GyroGate()
        assertFalse(g.update(false, false))
        assertTrue(g.update(true, true))
        assertTrue(g.on)
        assertTrue(g.registered(false))
        assertFalse(g.on)
        assertFalse(g.update(true, false))
        // same siren: no retry after a failure
        assertFalse(g.update(true, true))
        assertFalse(g.on)
        // the siren ended: reset
        assertFalse(g.update(false, false))
        assertTrue(g.update(true, true))
        assertTrue(g.registered(true))
        // vibration ended: unregister
        assertTrue(g.update(true, false))
        assertFalse(g.on)
        // registered again in the same siren: no second log line
        assertTrue(g.update(true, true))
        assertFalse(g.registered(true))
    }

    /**
     * Turning the feature off or changing settings does not reset the gyro gate during a peer siren;
     * only the siren end and the monitor stop turn the gyro log off (W5, D3).
     */
    @Test
    fun gyro_log_is_turned_off_only_by_the_siren_or_the_monitor_stop() {
        fun src(name: String) = listOf(
            File("src/main/java/com/wf11/safealert/03_service/$name"),
            File("app/src/main/java/com/wf11/safealert/03_service/$name")
        ).first { it.exists() }.readText().replace("\r\n", "\n")
        fun block(s: String, head: String): String {
            val i = s.indexOf(head)
            assertTrue(head, i >= 0)
            return s.substring(i, s.indexOf("\n    }", i))
        }
        assertFalse(block(src("LoneWorkerSensors.kt"), "fun unregister()").contains("gyroLog("))
        val stop = block(src("LoneWorkerMonitor.kt"), "fun stop()")
        val off = stop.indexOf("sensors.gyroLog(false, false)")
        assertTrue(off >= 0)
        assertTrue(off < stop.indexOf("sensors.unregister()"))
    }
}
