package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Rule 2 (still) counting: the count pauses while a peer siren vibrates on this device (C2) for at most
 * stillMs per vibration (D1; a device without a vibrator never pauses, D2), is not kept
 * inside a settled safe zone (C3), and the still check opens on sensor time like the accident deadline (C5).
 * A suspect device (accident suspicion running) does not vibrate for a peer siren (C4).
 * A deadline also waits while a power change that started at or before it is pending (at most about 2 s), then is
 * judged with the reported power, or with the power before it if the change drops (M1).
 */
class LoneWorkerStillCountTest {

    @Test fun peer_siren_pauses_still_count_and_resumes_after() {
        val l = newLogic(carried = true)
        l.peerSiren(60_000L, 230_000L)
        assertTrue(l.alarmVibrates)
        l.ackAll(230_000)
        assertFalse(l.alarmVibrates)
        // 60 s were still before the siren, 120 s remain after it
        assertEquals(Mode.WATCHING, l.seenAt(230_000))
        assertEquals(Mode.WATCHING, l.seenAt(349_000))
        assertEquals(Mode.CHECKING, l.seenAt(350_000))
        assertEquals("still", l.trigger)
    }

    @Test fun siren_pause_ends_after_still_ms() {
        val l = newLogic(carried = true)
        l.peerSiren(60_000L, 359_000L)
        assertTrue(l.alarmVibrates)
        // the pause ran 60 s .. 240 s (stillMs) and ended there: 60 s before it, 120 s after it
        l.onPeerBle("P", true, 360_000)
        assertEquals(Mode.CHECKING, l.seenAt(360_000))
        assertEquals("still", l.trigger)
    }

    @Test fun siren_pause_starts_again_for_a_new_siren() {
        val l = newLogic(carried = true)
        l.peerSiren(60_000L, 250_000L)
        // capped at 240 s: base 180 s; the vibration stops, so the next siren pauses again
        l.ackAll(250_000)
        assertEquals(Mode.WATCHING, l.seenAt(250_000))
        l.peerSiren(260_000L, 400_000L, "Q")
        l.ackAll(400_000)
        // 80 s before Q, 100 s after it
        assertEquals(Mode.WATCHING, l.seenAt(400_000))
        assertEquals(Mode.WATCHING, l.seenAt(499_000))
        assertEquals(Mode.CHECKING, l.seenAt(500_000))
    }

    @Test fun no_vibrator_does_not_pause_still_count() {
        val l = newLogic(carried = true).apply { canVibrate = false }
        l.peerSiren(60_000L, 179_000L)
        assertFalse(l.alarmVibrates)
        assertEquals(1, l.audiblePeers().size)
        assertEquals(Mode.CHECKING, l.seenAt(180_000))
    }

    @Test fun siren_end_by_resolve_also_resumes() {
        val l = newLogic(carried = true)
        l.peerSiren(60_000L, 200_000L)
        l.onPeerBle("P", false, 200_000)
        l.tick(210_000)
        assertTrue(l.audiblePeers().isEmpty())
        assertEquals(Mode.WATCHING, l.seenAt(210_000))
        assertEquals(Mode.WATCHING, l.seenAt(329_000))
        assertEquals(Mode.CHECKING, l.seenAt(330_000))
    }

    @Test fun movement_during_siren_counts_from_siren_end() {
        val l = newLogic(carried = true)
        for (t in 60_000L..200_000L step 1_000L) {
            l.onPeerBle("P", true, t)
            if (t == 100_000L) l.onMoved(t)
            assertEquals(Mode.WATCHING, l.seenAt(t))
        }
        l.ackAll(200_000)
        assertEquals(Mode.WATCHING, l.seenAt(200_000))
        assertEquals(Mode.WATCHING, l.seenAt(379_000))
        assertEquals(Mode.CHECKING, l.seenAt(380_000))
    }

    @Test fun suspect_device_does_not_vibrate_for_peer_siren() {
        val l = newLogic(carried = true)
        l.onAccident(10_000)
        l.onPeerBle("P", true, 11_000)
        assertEquals(Mode.WATCHING, l.modeAt(11_000))
        assertEquals(1, l.audiblePeers().size)
        assertFalse(l.alarmVibrates)
        // a real plug ends the suspicion: the siren vibrates again
        l.reportPower(true, 12_000)
        assertEquals(Mode.WATCHING, l.modeAt(12_000 + PowerDebounce.CONFIRM_MS))
        assertTrue(l.alarmVibrates)
    }

    @Test fun settled_zone_does_not_count_still() {
        val l = newLogic(zoneInside = true, carried = true)
        l.tick(60_000)
        assertTrue(l.zoneSettled)
        for (t in 60_000L..600_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
        l.onZone(false, 600_000)
        assertEquals(Mode.WATCHING, l.seenAt(779_000))
        assertEquals(Mode.CHECKING, l.seenAt(780_000))
    }

    @Test fun still_check_waits_for_sensor_data() {
        val l = newLogic(carried = true)
        l.sensed(178_000)
        assertEquals(Mode.WATCHING, l.modeAt(180_000))
        assertTrue(l.waitingOnSensors(180_000))
        l.sensed(180_000)
        assertEquals(Mode.CHECKING, l.modeAt(180_000))

        val silent = newLogic(carried = true)
        silent.sensed(178_000)
        assertEquals(Mode.WATCHING, silent.modeAt(185_999))
        assertEquals(Mode.CHECKING, silent.modeAt(186_000))
    }

    @Test fun late_movement_before_still_deadline_keeps_watching() {
        val l = newLogic(carried = true)
        l.sensed(178_000)
        assertEquals(Mode.WATCHING, l.modeAt(180_000))
        l.onMoved(179_500)
        l.sensed(181_000)
        assertEquals(Mode.WATCHING, l.modeAt(181_000))
        assertEquals(Mode.WATCHING, l.seenAt(359_499))
        assertEquals(Mode.CHECKING, l.seenAt(359_500))
    }

    @Test fun still_deadline_is_scheduled() {
        assertEquals(180_000L, newLogic(carried = true).nextCheckAt(0))

        val settled = newLogic(zoneInside = true, carried = true)
        settled.tick(60_000)
        assertNull(settled.nextCheckAt(60_000))

        val siren = newLogic(carried = true)
        siren.onPeerBle("P", true, 1_000)
        siren.tick(1_000)
        assertNull(siren.nextCheckAt(1_000))

        assertNull(newLogic().nextCheckAt(0))
    }

    @Test fun due_now_only_when_passed_deadline_has_data() {
        val l = newLogic(carried = true)
        l.sensed(170_000)
        assertFalse(l.dueNow(179_999))
        assertFalse(l.dueNow(180_000))
        l.sensed(180_000)
        assertTrue(l.dueNow(180_000))
        assertEquals(Mode.CHECKING, l.modeAt(180_000))
        assertFalse(l.dueNow(180_000))
    }

    /** A still deadline that passed before the siren started is still judged once sensor data covers it (S1). */
    @Test fun siren_does_not_swallow_a_passed_still_deadline() {
        val l = newLogic(carried = true)
        l.sensed(178_000)
        assertEquals(Mode.WATCHING, l.modeAt(180_000))
        assertTrue(l.waitingOnSensors(180_000))
        l.onPeerBle("P", true, 181_000)
        assertEquals(Mode.WATCHING, l.modeAt(181_000))
        assertTrue(l.alarmVibrates)
        assertTrue(l.waitingOnSensors(181_000))
        l.sensed(181_000)
        assertEquals(Mode.CHECKING, l.modeAt(181_000))
        assertEquals("still", l.trigger)
    }

    /** A still deadline that passed before the siren keeps its base when the vibration stops before the data comes (S1, C5). */
    @Test fun siren_stopping_before_data_keeps_a_passed_still_deadline() {
        val l = newLogic(carried = true)
        l.sensed(178_000)
        assertEquals(Mode.WATCHING, l.modeAt(180_000))
        assertTrue(l.waitingOnSensors(180_000))
        l.onPeerBle("P", true, 181_000)
        assertEquals(Mode.WATCHING, l.modeAt(181_000))
        assertEquals(0L, l.snapshot(181_500).stillBase)
        // the siren is acknowledged before the data comes: the vibration stops
        l.ackAll(182_000)
        assertEquals(Mode.WATCHING, l.modeAt(182_000))
        assertFalse(l.alarmVibrates)
        // one batch: data covering the deadline, then a motion after it
        l.sensed(180_000)
        assertTrue(l.dueNow(182_000))
        assertEquals(Mode.CHECKING, l.modeAt(182_000))
        l.onMoved(180_500)
        l.sensed(181_000)
        assertEquals(Mode.CHECKING, l.modeAt(182_000))
        assertEquals("still", l.trigger)
    }

    /** A plug started 1 s before the SOS deadline holds it until confirmed and closes the check; an unplug only delays the SOS (M1). */
    @Test fun sos_deadline_waits_for_a_power_change_started_before_it() {
        val l = newLogic(carried = true)
        assertEquals(Mode.CHECKING, l.seenAt(180_000))
        assertEquals("still", l.trigger)
        val sos = 180_000 + l.responseMs
        l.powerRaw(true, sos - 1_000)
        assertEquals(Mode.CHECKING, l.seenAt(sos))
        assertEquals(sos - 1_000 + PowerDebounce.CONFIRM_MS, l.nextCheckAt(sos))
        assertEquals(Mode.WATCHING, l.seenAt(sos - 1_000 + PowerDebounce.DEBOUNCE_MS))
        assertEquals(LoneWorkerLogic.Rest.DOCKED, l.rest)
        assertEquals(Mode.WATCHING, l.seenAt(sos + LoneWorkerLogic.LATE_MS))
        // unplug: the check stays open, the SOS comes when the unplug is confirmed
        val u = carriedWhileCharging()
        assertEquals(Mode.CHECKING, u.seenAt(190_000))
        val s2 = 190_000 + u.responseMs
        u.powerRaw(false, s2 - 1_000)
        assertEquals(Mode.CHECKING, u.seenAt(s2))
        assertEquals(Mode.SOS, u.seenAt(s2 - 1_000 + PowerDebounce.DEBOUNCE_MS))
    }

    /** A plug started at or before a still or fall deadline holds it; a plug started after it does not (M1). */
    @Test fun window_deadlines_wait_for_a_plug_started_at_or_before_them() {
        for (at in listOf(179_000L, 180_000L)) {
            val m = "at=$at"
            val l = newLogic(carried = true)
            l.powerRaw(true, at)
            assertEquals(m, Mode.WATCHING, l.seenAt(180_000))
            assertEquals(m, Mode.WATCHING, l.seenAt(at + PowerDebounce.DEBOUNCE_MS))
            assertEquals(m, LoneWorkerLogic.Rest.DOCKED, l.rest)
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

    /** A dropped power wait lets the deadline be judged with the power before it, the response counted from the opening (M1). */
    @Test fun dropped_power_wait_judges_in_the_power_state_before_it() {
        val l = newLogic(carried = true)
        l.powerRaw(true, 179_500)
        assertEquals(Mode.WATCHING, l.seenAt(180_000))
        assertTrue(l.powerRaw(false, 180_400))
        assertEquals(Mode.CHECKING, l.seenAt(180_400))
        assertEquals("still", l.trigger)
        assertEquals(l.responseMs, l.responseLeftMs(180_400))
        assertEquals(LoneWorkerLogic.Rest.NONE, l.rest)
    }

    /** A power wait keeps the CPU awake but asks for a flush only when data up to the deadline is missing (X4). */
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
}
