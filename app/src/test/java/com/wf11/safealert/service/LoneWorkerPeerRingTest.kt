package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerAlarm.Pattern
import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The siren of a peer SOS sounds on this phone for LoneWorkerPeers.PEER_RING_MS per entry, counted from when the entry first
 * alerted here. After that the entry keeps alerting by vibration, row and notification without the siren. "Siren" below is the
 * expression the monitor uses to choose the pattern; "vibrating" is LoneWorkerLogic.alarmVibrates.
 */
class LoneWorkerPeerRingTest {

    private fun meLogic() = LoneWorkerLogic("ME").apply { start(0L, false) }

    private fun siren(l: LoneWorkerLogic): Pattern? = Pattern.of(l.mode, l.soundingPeers().isNotEmpty())

    @Test fun ble_sos_siren_stops_30s_after_first_hearing_and_the_vibration_goes_on() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 1)
        l.tick(30_999)
        assertEquals(Pattern.SIREN, siren(l))
        assertEquals(1, l.soundingPeers().size)
        assertTrue(l.alarmVibrates)
        l.tick(31_000)
        assertNull(siren(l))
        assertTrue(l.soundingPeers().isEmpty())
        assertTrue(l.alarmVibrates)
        assertEquals(1, l.audiblePeers().size)
        val p = l.peer("P")
        assertTrue(p.active)
        assertFalse(p.silenced)
        assertTrue(p.ringOver)
        assertTrue(p.line(31_000).endsWith("구조 요청 · ${LoneWorkerNotifier.RING_OVER}"))
        l.tick(301_000)
        assertNull(siren(l))
        assertTrue(l.alarmVibrates)
    }

    @Test fun server_sos_siren_stops_30s_after_it_arrived() {
        val l = meLogic()
        l.srv("K1", "P", 1, true, created = 5L, now = 1_000)
        l.tick(30_999)
        assertEquals(Pattern.SIREN, siren(l))
        l.tick(31_000)
        assertNull(siren(l))
        assertTrue(l.alarmVibrates)
        assertEquals(1, l.audiblePeers().size)
    }

    @Test fun acknowledge_stops_the_vibration_after_the_siren_went_off() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 1)
        l.tick(31_000)
        assertNull(siren(l))
        assertTrue(l.alarmVibrates)
        l.ackAll(40_000)
        assertFalse(l.alarmVibrates)
        assertTrue(l.audiblePeers().isEmpty())
        assertTrue(l.peer("P").silenced)
    }

    @Test fun server_takeover_keeps_the_ring_time_of_its_bluetooth_entry() {
        val a = meLogic()
        a.onPeerBle("P", true, 1_000, 1)
        a.srv("K1", "P", 1, true, created = 5L, now = 20_000, name = "Kim")
        assertEquals("K1", a.peer("P").key)
        assertEquals("Kim", a.peer("P").name)
        a.tick(30_999)
        assertEquals(Pattern.SIREN, siren(a))
        a.tick(31_000)
        assertNull(siren(a))

        val b = meLogic()
        b.onPeerBle("P", true, 1_000, 1)
        b.tick(31_000)
        b.srv("K1", "P", 1, true, created = 5L, now = 40_000, name = "Kim")
        assertEquals("K1", b.peer("P").key)
        assertEquals("Kim", b.peer("P").name)
        assertFalse(b.peer("P").silenced)
        assertEquals(1, b.audiblePeers().size)
        assertTrue(b.alarmVibrates)
        assertNull(siren(b))
    }

    @Test fun acknowledged_row_shown_again_after_its_ring_time_vibrates_without_the_siren() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 1)
        l.ackAll(1_100)
        l.onPeerBle("P", true, 50_000, 1)
        assertFalse(l.peer("P").silenced)
        assertEquals(1, l.audiblePeers().size)
        assertTrue(l.alarmVibrates)
        assertNull(siren(l))
    }

    @Test fun acknowledged_row_heard_inside_its_ring_time_sounds_only_for_the_rest() {
        val l = meLogic()
        l.srv("K1", "P", 1, true, created = 5L, now = 1_000)
        l.ackAll(1_100)
        l.onPeerBle("P", true, 2_000, 1)
        assertEquals(Pattern.SIREN, siren(l))
        l.tick(30_999)
        assertEquals(Pattern.SIREN, siren(l))
        l.tick(31_000)
        assertNull(siren(l))
    }

    @Test fun a_different_sos_sounds_its_own_30s() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 1)
        l.tick(31_000)
        assertNull(siren(l))
        l.onPeerBle("P", true, 40_000, 2)
        assertEquals(Pattern.SIREN, siren(l))
        l.tick(69_999)
        assertEquals(Pattern.SIREN, siren(l))
        assertEquals(1, l.soundingPeers().size)
        assertEquals(2, l.audiblePeers().size)
        assertTrue(l.alarmVibrates)
        l.tick(70_000)
        assertNull(siren(l))
        assertTrue(l.alarmVibrates)
    }

    @Test fun out_of_scope_record_starts_its_ring_when_heard_on_air() {
        val l = meLogic()
        l.srv("K1", "P", 1, true, created = 5L, now = 1_000, inScope = false)
        for (t in listOf(1_000L, 31_000L, 100_000L - 1)) {
            l.tick(t)
            assertTrue(l.audiblePeers().isEmpty())
            assertNull(siren(l))
            assertFalse(l.alarmVibrates)
        }
        l.onPeerBle("P", true, 100_000, 1)
        assertEquals(Pattern.SIREN, siren(l))
        assertTrue(l.alarmVibrates)
        l.tick(130_000)
        assertNull(siren(l))
        assertTrue(l.alarmVibrates)
    }

    @Test fun siren_end_is_scheduled_and_changes_the_signature() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 1)
        assertEquals(31_000L, l.nextCueAt(5_000))
        val before = l.peerSig()
        l.tick(31_000)
        assertNotEquals(before, l.peerSig())
        assertNull(l.nextCueAt(31_000))
    }

    @Test fun monitor_takes_the_siren_from_sounding_peers_and_keeps_the_vibration() {
        val src = serviceSource("LoneWorkerMonitor.kt")
        assertTrue(src.contains("LoneWorkerAlarm.Pattern.of(mode, logic.soundingPeers().isNotEmpty())"))
        assertTrue(src.contains("alarm.stop(vibrate = vibrates)"))
        val update = src.substring(src.indexOf("notifier.update("))
            .lineSequence().first()
        assertTrue(update, update.contains("audible,"))
        assertEquals(1, Regex("notifier\\.update\\(").findAll(src).count())
    }

    /** A peer SOS heard during the phone's own SOS waits: its siren time starts when the phone's own SOS is released. */
    @Test fun sos_heard_during_my_own_sos_sounds_30s_after_i_release() {
        val l = newLogic(carried = true)
        l.seenAt(180_000)
        l.seenAt(300_000)
        assertEquals(Mode.SOS, l.mode)
        l.onPeerBle("P", true, 310_000, 1)
        l.tick(311_000)
        assertNull(l.peer("P").ringStartMs)
        l.onPeerBle("P", true, 399_000, 1)
        l.tick(399_000)
        assertNull(l.peer("P").ringStartMs)
        val t = 400_000L
        assertTrue(l.cancelSos(t))
        assertEquals(t, l.peer("P").ringStartMs)
        assertEquals(Pattern.SIREN, siren(l))
        l.tick(t + 29_999)
        assertEquals(Pattern.SIREN, siren(l))
        l.tick(t + 30_000)
        assertNull(siren(l))
        assertTrue(l.alarmVibrates)
    }

    /** A phone that receives a peer SOS: turning the feature off closes its own check window, and the held-back siren starts then. */
    @Test fun feature_off_closing_my_check_window_starts_the_held_back_peer_siren() {
        val l = newLogic(carried = true)
        assertEquals(Mode.CHECKING, l.seenAt(180_000))
        l.onPeerBle("P", true, 181_000, 1)
        assertNull(l.peer("P").ringStartMs)
        l.setEnabled(false, 182_000)
        assertEquals(Mode.WATCHING, l.mode)
        assertEquals(182_000L, l.peer("P").ringStartMs)
        assertEquals(Pattern.SIREN, siren(l))
    }

    /** Once started the ring time runs in any mode: a check window opened meanwhile does not stop the clock. */
    @Test fun ring_time_once_started_keeps_running_through_my_check_window() {
        val l = newLogic(carried = true).apply { stillMs = 10_000L }
        l.onPeerBle("P", true, 1_000, 1)
        l.tick(1_000)
        assertEquals(1_000L, l.peer("P").ringStartMs)
        // The peer's vibration pauses the no-motion count, so the check window opens a while after the siren started
        var opened = 0L
        for (t in 2_000L..30_000L step 1_000L) {
            l.onPeerBle("P", true, t, 1)
            if (l.seenAt(t) == Mode.CHECKING && opened == 0L) opened = t
        }
        assertTrue("the check window opened inside the ring time", opened in 1L..30_000L)
        assertEquals(Pattern.CHECK, siren(l))
        for (t in 31_000L..36_000L step 1_000L) { l.onPeerBle("P", true, t, 1); assertEquals(Mode.CHECKING, l.seenAt(t)) }
        assertTrue(l.peer("P").ringOver)
        l.onPeerBle("P", true, 41_000, 1)
        l.seenAt(41_000)
        assertTrue(l.ackWorking(41_000))
        assertNull(siren(l))
        assertTrue(l.alarmVibrates)
    }
}
