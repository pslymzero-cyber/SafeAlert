package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoneWorkerLogic contract tests. The clock is an injected elapsed ms; no Android dependency.
 */
class LoneWorkerLogicTest {

    private val stillMs = 180_000L
    private val responseMs = 120_000L

    private fun LoneWorkerLogic.toChecking() { seenAt(stillMs) }
    private fun LoneWorkerLogic.toSos() { seenAt(stillMs); seenAt(stillMs + responseMs) }

    // -- own state --

    @Test fun still_180s_opens_check_but_not_179_999() {
        val l = newLogic(carried = true)
        l.seenAt(179_999)
        assertEquals(Mode.WATCHING, l.mode)
        l.seenAt(180_000)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("still", l.trigger)
    }

    @Test fun unanswered_check_escalates_to_sos() {
        val l = newLogic(carried = true)
        l.toChecking()
        l.seenAt(180_000 + responseMs - 1)
        assertEquals(Mode.CHECKING, l.mode)
        l.seenAt(180_000 + responseMs)
        assertEquals(Mode.SOS, l.mode)
        assertTrue(l.sosActive)
    }

    @Test fun ack_returns_to_watching_and_restarts_still_count() {
        val l = newLogic(carried = true)
        l.toChecking()
        assertTrue(l.ackWorking(200_000))
        assertEquals(Mode.WATCHING, l.mode)
        l.seenAt(200_000 + stillMs - 1)
        assertEquals(Mode.WATCHING, l.mode)
        l.seenAt(200_000 + stillMs)
        assertEquals(Mode.CHECKING, l.mode)
    }

    /** A fidget MOVED signal is not distinct movement, so it closes neither a still check nor a fall check. */
    @Test fun fidget_moved_signal_is_not_distinct_motion_and_keeps_checks_open() {
        val l = newLogic(carried = true)
        l.toChecking()
        l.onMoved(190_000)
        l.tick(191_000)
        assertEquals(Mode.CHECKING, l.mode)

        val f = newLogic(carried = true).also { it.stillMs = 3_600_000L }
        f.onAccident(10_000)
        assertEquals(Mode.CHECKING, f.seenAt(40_000))
        f.onMoved(45_000)
        assertEquals(Mode.CHECKING, f.seenAt(50_000))
        assertEquals("fall", f.trigger)
    }

    @Test fun cancel_sos_returns_true_and_ack_is_ignored_in_sos() {
        val l = newLogic(carried = true)
        l.toSos()
        assertFalse(l.ackWorking(400_000))
        assertEquals(Mode.SOS, l.mode)
        assertTrue(l.cancelSos(400_000))
        assertEquals(Mode.WATCHING, l.mode)
        assertFalse(l.cancelSos(400_001))
    }

    // An SOS ended by the one-hour limit (holdStill) leaves the phone waiting as after an unplug: lying still raises no
    //   new check however long, and walking starts the no-motion count again.
    @Test fun sos_ended_by_the_limit_waits_for_movement() {
        val l = newLogic(carried = true)
        l.toSos()
        assertTrue(l.cancelSos(400_000))
        l.holdStill(400_000)
        assertEquals(LoneWorkerLogic.Rest.WAIT, l.rest)
        val walked = 400_000 + 10 * stillMs
        assertEquals(Mode.WATCHING, l.seenAt(walked))
        l.walk(walked + 10_000, 10)
        assertEquals(LoneWorkerLogic.Rest.NONE, l.rest)
        assertEquals(Mode.CHECKING, l.seenAt(walked + 10_000 + stillMs))
    }

    @Test fun disabled_never_checks_and_cancels_open_check_but_keeps_sos() {
        val off = newLogic(carried = true)
        off.setEnabled(false, 0)
        off.tick(600_000)
        assertEquals(Mode.WATCHING, off.mode)

        val chk = newLogic(carried = true)
        chk.toChecking()
        chk.setEnabled(false, 181_000)
        assertEquals(Mode.WATCHING, chk.mode)

        val sos = newLogic(carried = true)
        sos.toSos()
        sos.setEnabled(false, 400_000)
        assertEquals(Mode.SOS, sos.mode)
    }

    @Test fun reenable_restarts_still_count_from_enable_time() {
        val l = newLogic(carried = true)
        l.setEnabled(false, 0)
        l.setEnabled(true, 600_000)
        l.seenAt(600_000 + stillMs - 1)
        assertEquals(Mode.WATCHING, l.mode)
        l.seenAt(600_000 + stillMs)
        assertEquals(Mode.CHECKING, l.mode)
    }

    // -- safe zone --

    @Test fun settled_zone_cancels_still_check_but_not_accident() {
        val l = newLogic(carried = true)
        l.toChecking()
        l.onZone(true, 180_000)
        l.tick(239_999)
        assertEquals(Mode.CHECKING, l.mode)
        l.tick(240_000)
        assertTrue(l.zoneSettled)
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(900_000)
        assertEquals(Mode.WATCHING, l.mode)
        l.onAccident(900_100)
        l.seenAt(930_100)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("fall", l.trigger)
        l.tick(931_000)
        assertEquals(Mode.CHECKING, l.mode)
    }

    @Test fun inside_59s_then_out_does_not_reset_still_count() {
        val l = newLogic(carried = true)
        l.onZone(true, 100_000)
        l.onZone(false, 159_000)
        l.seenAt(179_999)
        assertEquals(Mode.WATCHING, l.mode)
        l.seenAt(180_000)
        assertEquals(Mode.CHECKING, l.mode)
    }

    @Test fun flapping_never_settles() {
        val l = newLogic(carried = true)
        var t = 0L
        while (t < 175_000) {
            l.onZone(true, t)
            l.tick(t)
            l.onZone(false, t + 1_000)
            l.tick(t + 1_000)
            t += 5_000
        }
        assertFalse(l.zoneSettled)
        l.seenAt(180_000)
        assertEquals(Mode.CHECKING, l.mode)
    }

    // -- beacon hint (peer SOS: LoneWorkerPeerTest) --

    @Test fun beacon_hint_takes_strongest_within_60s() {
        val l = newLogic(carried = true)
        l.noteBeacon("A", -50, 0)
        l.noteBeacon("B", -70, 30_000)
        assertEquals("A" to -50, l.beaconHint(50_000))
        assertEquals("B" to -70, l.beaconHint(70_000))
        assertNull(l.beaconHint(100_000))
    }

    @Test fun beacon_sid_takes_strongest_registered_sample() {
        val l = newLogic(carried = true)
        assertEquals(0, l.beaconSid(0))
        l.noteBeacon("A", -40, 0, 0)
        l.noteBeacon("B", -60, 10_000, 11)
        l.noteBeacon("C", -50, 20_000, 22)
        assertEquals(22, l.beaconSid(30_000))
        assertEquals(0, l.beaconSid(90_000))
    }

    @Test fun restore_sos_survives_settle_disable_and_ack() {
        val l = newLogic(carried = true)
        l.restoreSos("fall", 5_000)
        assertEquals(Mode.SOS, l.mode)
        assertEquals("fall", l.trigger)
        assertTrue(l.sosActive)
        l.onZone(true, 6_000)
        l.tick(70_000)
        assertTrue(l.zoneSettled)
        assertEquals(Mode.SOS, l.mode)
        l.setEnabled(false, 71_000)
        assertFalse(l.ackWorking(72_000))
        assertEquals(Mode.SOS, l.mode)
        assertTrue(l.cancelSos(73_000))
        assertEquals(Mode.WATCHING, l.mode)
    }

    @Test fun alarm_vibrates_only_for_peer_siren_not_on_the_suspect_device() {
        val l = newLogic(carried = true)
        assertFalse(l.alarmVibrates)
        l.toChecking()
        assertEquals(Mode.CHECKING, l.mode)
        assertFalse(l.alarmVibrates)
        l.onPeerBle("P", true, stillMs + 1)
        assertFalse(l.alarmVibrates)
        l.seenAt(stillMs + responseMs)
        assertEquals(Mode.SOS, l.mode)
        assertFalse(l.alarmVibrates)

        val r = newLogic(carried = true)
        r.onPeerBle("P", true, 1_000)
        assertEquals(1, r.audiblePeers().size)
        assertTrue(r.alarmVibrates)
    }
}
