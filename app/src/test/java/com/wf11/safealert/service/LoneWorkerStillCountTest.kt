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
 */
class LoneWorkerStillCountTest {

    @Test fun peer_siren_pauses_still_count_and_resumes_after() {
        val l = newLogic(carried = true)
        for (t in 60_000L..230_000L step 1_000L) {
            l.onPeerBle("P", true, t)
            assertEquals(Mode.WATCHING, l.seenAt(t))
        }
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
        for (t in 60_000L..359_000L step 1_000L) {
            l.onPeerBle("P", true, t)
            assertEquals(Mode.WATCHING, l.seenAt(t))
        }
        assertTrue(l.alarmVibrates)
        // the pause ran 60 s .. 240 s (stillMs) and ended there: 60 s before it, 120 s after it
        l.onPeerBle("P", true, 360_000)
        assertEquals(Mode.CHECKING, l.seenAt(360_000))
        assertEquals("still", l.trigger)
    }

    @Test fun siren_pause_starts_again_for_a_new_siren() {
        val l = newLogic(carried = true)
        for (t in 60_000L..250_000L step 1_000L) {
            l.onPeerBle("P", true, t)
            assertEquals(Mode.WATCHING, l.seenAt(t))
        }
        // capped at 240 s: base 180 s; the vibration stops, so the next siren pauses again
        l.ackAll(250_000)
        assertEquals(Mode.WATCHING, l.seenAt(250_000))
        for (t in 260_000L..400_000L step 1_000L) {
            l.onPeerBle("Q", true, t)
            assertEquals(Mode.WATCHING, l.seenAt(t))
        }
        l.ackAll(400_000)
        // 80 s before Q, 100 s after it
        assertEquals(Mode.WATCHING, l.seenAt(400_000))
        assertEquals(Mode.WATCHING, l.seenAt(499_000))
        assertEquals(Mode.CHECKING, l.seenAt(500_000))
    }

    @Test fun no_vibrator_does_not_pause_still_count() {
        val l = newLogic(carried = true).apply { canVibrate = false }
        for (t in 60_000L..179_000L step 1_000L) {
            l.onPeerBle("P", true, t)
            assertEquals(Mode.WATCHING, l.seenAt(t))
        }
        assertFalse(l.alarmVibrates)
        assertEquals(1, l.audiblePeers().size)
        assertEquals(Mode.CHECKING, l.seenAt(180_000))
    }

    @Test fun siren_end_by_resolve_also_resumes() {
        val l = newLogic(carried = true)
        for (t in 60_000L..200_000L step 1_000L) {
            l.onPeerBle("P", true, t)
            assertEquals(Mode.WATCHING, l.seenAt(t))
        }
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
        l.setCharging(true, 12_000)
        assertEquals(Mode.WATCHING, l.modeAt(12_000))
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
}
