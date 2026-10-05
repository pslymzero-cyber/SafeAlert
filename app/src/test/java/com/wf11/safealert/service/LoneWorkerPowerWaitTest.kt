package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import com.wf11.safealert.service.LoneWorkerLogic.Rest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Power wait: a still, fall or SOS deadline waits while a raw power change that started at or before it is pending
 * (at most about 2 s, until PowerDebounce confirms or drops it), then is judged with the reported power, or with the
 * power before it if the change drops. A plug started after the deadline does not hold it. The same rule under every
 * delivery order: LoneWorkerOrderTest (the determinism tables).
 */
class LoneWorkerPowerWaitTest {

    /**
     * A plug started 1 s before the SOS deadline holds it until confirmed and closes the check; an unplug only delays the SOS.
     */
    @Test fun sos_deadline_waits_for_a_power_change_started_before_it() {
        val l = newLogic(carried = true)
        assertEquals(Mode.CHECKING, l.seenAt(180_000))
        assertEquals("still", l.trigger)
        val sos = 180_000 + l.responseMs
        l.powerRaw(true, sos - 1_000)
        assertEquals(Mode.CHECKING, l.seenAt(sos))
        assertEquals(sos - 1_000 + PowerDebounce.CONFIRM_MS, l.nextCheckAt(sos))
        assertEquals(Mode.WATCHING, l.seenAt(sos - 1_000 + PowerDebounce.DEBOUNCE_MS))
        assertEquals(Rest.DOCKED, l.rest)
        assertEquals(Mode.WATCHING, l.seenAt(sos + LoneWorkerLogic.LATE_MS))
        // unplug: the check stays open, the SOS comes when the unplug is confirmed
        val u = carriedWhileCharging()
        assertEquals(Mode.CHECKING, u.seenAt(190_000))
        val s2 = 190_000 + u.responseMs
        u.powerRaw(false, s2 - 1_000)
        assertEquals(Mode.CHECKING, u.seenAt(s2))
        assertEquals(Mode.SOS, u.seenAt(s2 - 1_000 + PowerDebounce.DEBOUNCE_MS))
    }

    /** A plug started at or before a still or fall deadline holds it; a plug started after it does not. */
    @Test fun window_deadlines_wait_for_a_plug_started_at_or_before_them() {
        for (at in listOf(179_000L, 180_000L)) {
            val m = "at=$at"
            val l = newLogic(carried = true)
            l.powerRaw(true, at)
            assertEquals(m, Mode.WATCHING, l.seenAt(180_000))
            assertEquals(m, Mode.WATCHING, l.seenAt(at + PowerDebounce.DEBOUNCE_MS))
            assertEquals(m, Rest.DOCKED, l.rest)
            assertEquals(m, Mode.WATCHING, l.seenAt(400_000))
        }
        val f = newLogic(carried = true)
        f.onAccident(1_000)
        f.powerRaw(true, 30_000)
        assertEquals(Mode.WATCHING, f.seenAt(31_000))
        assertEquals(Mode.WATCHING, f.seenAt(32_000))
        assertNull(f.snapshot(32_000).accidentUntil)
        // a plug started after the deadline does not hold it; the confirmed plug then withdraws the check
        val c = newLogic(carried = true)
        assertEquals(Mode.WATCHING, c.modeAt(180_000))
        c.powerRaw(true, 180_500)
        assertEquals(Mode.CHECKING, c.seenAt(180_500))
        assertEquals("still", c.trigger)
        assertEquals(Mode.WATCHING, c.seenAt(180_500 + PowerDebounce.DEBOUNCE_MS))
    }

    /** A dropped power wait lets the deadline be judged with the power before it, the response counted from the opening. */
    @Test fun dropped_power_wait_judges_in_the_power_state_before_it() {
        val l = newLogic(carried = true)
        l.powerRaw(true, 179_500)
        assertEquals(Mode.WATCHING, l.seenAt(180_000))
        assertTrue(l.powerRaw(false, 180_400))
        assertEquals(Mode.CHECKING, l.seenAt(180_400))
        assertEquals("still", l.trigger)
        assertEquals(l.responseMs, l.responseLeftMs(180_400))
        assertEquals(Rest.NONE, l.rest)
    }

    /** A power wait keeps the CPU awake but asks for a flush only when data up to the deadline is missing. */
    @Test fun power_wait_keeps_the_cpu_awake_but_flushes_only_for_missing_data() {
        val l = newLogic(carried = true)
        l.powerRaw(true, 179_500)
        assertEquals(Mode.WATCHING, l.seenAt(180_000))
        assertTrue(l.waitingToJudge(180_000))
        assertFalse(l.waitingOnSensors(180_000))
        assertEquals(179_500 + PowerDebounce.CONFIRM_MS, l.nextCheckAt(180_000))
        assertEquals(Mode.WATCHING, l.seenAt(179_500 + PowerDebounce.DEBOUNCE_MS))
        assertFalse(l.waitingToJudge(179_500 + PowerDebounce.DEBOUNCE_MS))
        val d = newLogic(carried = true)
        d.sensed(178_000)
        d.powerRaw(true, 179_500)
        assertEquals(Mode.WATCHING, d.modeAt(180_000))
        assertTrue(d.waitingToJudge(180_000))
        assertTrue(d.waitingOnSensors(180_000))
    }

    /**
     * An unplug that started before the fall deadline makes it wait; the check then opens with its step floor
     * at the deadline, so steps before it do not close it.
     */
    @Test fun unplug_wait_at_the_fall_deadline_counts_steps_only_from_the_deadline() {
        val f = newLogic(charging = true)
        f.onAccident(1_000)
        f.powerRaw(false, 30_000)
        for (i in 0..3) f.step(30_100 + i * 200L)
        assertEquals(Mode.WATCHING, f.seenAt(31_000))
        assertEquals(Mode.CHECKING, f.seenAt(30_000 + PowerDebounce.CONFIRM_MS))
        assertEquals("fall", f.trigger)
        assertEquals(LoneWorkerLogic.ACCIDENT_RESPONSE_MS, f.responseLeftMs(30_000 + PowerDebounce.CONFIRM_MS))
        assertEquals(Rest.WAIT, f.rest)
        f.step(32_500)
        assertEquals(Mode.CHECKING, f.seenAt(33_000))
        assertEquals("fall", f.trigger)
    }

    /** Carried on the dock, the unplug starts 1 s before the still deadline: the deadline waits, the reported unplug is a wait, no check. */
    @Test fun unplug_started_before_the_still_deadline_rests_instead_of_checking() {
        val l = carriedWhileCharging()
        val open = 10_000 + l.stillMs
        l.powerRaw(false, open - 1_000)
        for (i in 0..3) l.step(open - 900 + i * 200)
        assertEquals(Mode.WATCHING, l.seenAt(open))
        assertEquals(open - 1_000 + PowerDebounce.CONFIRM_MS, l.nextCheckAt(open))
        assertEquals(Mode.WATCHING, l.seenAt(open - 1_000 + PowerDebounce.CONFIRM_MS))
        assertEquals(Rest.WAIT, l.rest)
        assertEquals(Mode.WATCHING, l.seenAt(open + LoneWorkerLogic.LATE_MS))
    }
}
