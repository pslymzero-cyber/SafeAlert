package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoneWorkerLogic contract tests (v1.1.99). The clock is an injected elapsed ms; no Android dependency.
 */
class LoneWorkerLogicTest {

    private val stillMs = 180_000L
    private val responseMs = 120_000L

    private fun carriedLogic(zoneInside: Boolean = false) =
        LoneWorkerLogic("SAFEALERT_WALKER_ME").apply { start(0L, zoneInside); sensorSilent(0L) }

    private fun LoneWorkerLogic.peer(id: String, ep: Int? = null) =
        peers.single { it.bleId == id && (ep == null || it.episode == ep) }

    private fun LoneWorkerLogic.toChecking() { seenAt(stillMs) }
    private fun LoneWorkerLogic.toSos() { seenAt(stillMs); seenAt(stillMs + responseMs) }

    private fun LoneWorkerLogic.server(
        key: String, id: String, active: Boolean, now: Long, name: String = "Hong"
    ) = peerServer(key, id, name, "WALKER", "still", "B1", now, active, now)

    private fun LoneWorkerLogic.peerServer(
        key: String, id: String, name: String, role: String, trigger: String, beacon: String,
        created: Long, active: Boolean, now: Long, ep: Int = 0
    ) = onPeerServer(LoneWorkerPeers.ServerRec(key, id, name, role, trigger, beacon, created, active, ep, null), now)

    // -- own state --

    @Test fun still_180s_opens_check_but_not_179_999() {
        val l = carriedLogic()
        l.seenAt(179_999)
        assertEquals(Mode.WATCHING, l.mode)
        l.seenAt(180_000)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("still", l.trigger)
    }

    @Test fun unanswered_check_escalates_to_sos() {
        val l = carriedLogic()
        l.toChecking()
        l.seenAt(180_000 + responseMs - 1)
        assertEquals(Mode.CHECKING, l.mode)
        l.seenAt(180_000 + responseMs)
        assertEquals(Mode.SOS, l.mode)
        assertTrue(l.sosActive)
    }

    @Test fun ack_returns_to_watching_and_restarts_still_count() {
        val l = carriedLogic()
        l.toChecking()
        assertTrue(l.ackWorking(200_000))
        assertEquals(Mode.WATCHING, l.mode)
        l.seenAt(200_000 + stillMs - 1)
        assertEquals(Mode.WATCHING, l.mode)
        l.seenAt(200_000 + stillMs)
        assertEquals(Mode.CHECKING, l.mode)
    }

    @Test fun moved_signal_does_not_close_an_open_check() {
        val l = carriedLogic()
        l.toChecking()
        l.onMoved(190_000)
        l.tick(191_000)
        assertEquals(Mode.CHECKING, l.mode)
    }

    @Test fun cancel_sos_returns_true_and_ack_is_ignored_in_sos() {
        val l = carriedLogic()
        l.toSos()
        assertFalse(l.ackWorking(400_000))
        assertEquals(Mode.SOS, l.mode)
        assertTrue(l.cancelSos(400_000))
        assertEquals(Mode.WATCHING, l.mode)
        assertFalse(l.cancelSos(400_001))
    }

    @Test fun disabled_never_checks_and_cancels_open_check_but_keeps_sos() {
        val off = carriedLogic()
        off.setEnabled(false, 0)
        off.tick(600_000)
        assertEquals(Mode.WATCHING, off.mode)

        val chk = carriedLogic()
        chk.toChecking()
        chk.setEnabled(false, 181_000)
        assertEquals(Mode.WATCHING, chk.mode)

        val sos = carriedLogic()
        sos.toSos()
        sos.setEnabled(false, 400_000)
        assertEquals(Mode.SOS, sos.mode)
    }

    @Test fun reenable_restarts_still_count_from_enable_time() {
        val l = carriedLogic()
        l.setEnabled(false, 0)
        l.setEnabled(true, 600_000)
        l.seenAt(600_000 + stillMs - 1)
        assertEquals(Mode.WATCHING, l.mode)
        l.seenAt(600_000 + stillMs)
        assertEquals(Mode.CHECKING, l.mode)
    }

    // -- safe zone (D-03) --

    @Test fun settled_zone_cancels_still_check_but_not_accident() {
        val l = carriedLogic()
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
        val l = carriedLogic()
        l.onZone(true, 100_000)
        l.onZone(false, 159_000)
        l.seenAt(179_999)
        assertEquals(Mode.WATCHING, l.mode)
        l.seenAt(180_000)
        assertEquals(Mode.CHECKING, l.mode)
    }

    @Test fun flapping_never_settles() {
        val l = carriedLogic()
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

    @Test fun leaving_settled_zone_starts_new_count() {
        val l = carriedLogic()
        l.onZone(true, 1_000)
        l.tick(61_000)
        assertTrue(l.zoneSettled)
        l.onZone(false, 300_000)
        l.seenAt(479_999)
        assertEquals(Mode.WATCHING, l.mode)
        l.seenAt(480_000)
        assertEquals(Mode.CHECKING, l.mode)
    }

    @Test fun sos_survives_settled_zone() {
        val l = carriedLogic()
        l.toSos()
        l.onZone(true, 300_000)
        l.tick(400_000)
        assertTrue(l.zoneSettled)
        assertEquals(Mode.SOS, l.mode)
    }

    @Test fun peers_are_received_while_zone_settled() {
        val l = carriedLogic()
        l.onZone(true, 0)
        l.tick(70_000)
        assertTrue(l.zoneSettled)
        l.server("k1", "P", true, 71_000)
        assertEquals(1, l.audiblePeers().size)
    }

    // -- peer SOS (D-06) --

    @Test fun same_bleid_as_mine_still_alarms() {
        val l = carriedLogic()
        l.server("k1", "SAFEALERT_WALKER_ME", true, 1_000)
        l.onPeerBle("SAFEALERT_WALKER_ME", true, 1_100)
        assertEquals(1, l.peers.size)
        assertEquals(1, l.audiblePeers().size)
    }

    @Test fun server_plus_ble_make_one_peer_and_silence_sticks() {
        val l = carriedLogic()
        l.server("k1", "P", true, 1_000)
        l.onPeerBle("P", true, 1_100)
        assertEquals(1, l.peers.size)
        assertEquals(1, l.audiblePeers().size)
        l.ackAll(1_101)
        assertEquals(0, l.audiblePeers().size)
        assertTrue(l.peer("P").active)
        l.onPeerBle("P", true, 1_200)
        assertEquals(0, l.audiblePeers().size)
    }

    @Test fun server_resolved_matches_key_only() {
        val l = carriedLogic()
        l.server("k1", "P", true, 1_000)
        l.server("k1", "P", false, 2_000)
        assertFalse(l.peers.single { it.key == "k1" }.active)

        // each key is its own entry: resolving k2 leaves k3 sounding
        l.server("k2", "P", true, 3_000)
        assertTrue(l.peers.single { it.key == "k2" }.active)
        l.server("k3", "P", true, 3_500)
        l.server("k2", "P", false, 4_000)
        assertFalse(l.peers.single { it.key == "k2" }.active)
        assertTrue(l.peers.single { it.key == "k3" }.active)
    }

    @Test fun ble_only_peer_is_resolved_after_10s_of_false() {
        val l = carriedLogic()
        l.onPeerBle("P", true, 1_000)
        assertTrue(l.peer("P").active)
        assertFalse(l.peer("P").fromServer)
        l.onPeerBle("P", false, 2_000)
        assertTrue(l.peer("P").active)
        l.onPeerBle("P", true, 3_000)
        l.onPeerBle("P", false, 4_000)
        l.onPeerBle("P", false, 13_999)
        assertTrue(l.peer("P").active)
        l.onPeerBle("P", false, 14_000)
        assertFalse(l.peer("P").active)

        val m = carriedLogic()
        m.onPeerBle("P", true, 1_000)
        m.onPeerBle("P", false, 2_000)
        m.tick(12_000)
        assertFalse(m.peer("P").active)
    }

    @Test fun ble_bit_after_30s_gap_is_a_rising_edge() {
        val l = carriedLogic()
        l.onPeerBle("P", true, 1_000)
        l.ackAll(1_001)
        l.onPeerBle("P", true, 29_000)
        assertEquals(0, l.audiblePeers().size)
        l.onPeerBle("P", true, 60_000)
        assertEquals(1, l.audiblePeers().size)
        l.onPeerBle("P", false, 61_000)
        assertTrue(l.peer("P").active)
        l.onPeerBle("P", false, 71_000)
        assertFalse(l.peer("P").active)
    }

    @Test fun server_backed_peer_is_not_resolved_by_ble_falling_edge() {
        val l = carriedLogic()
        l.server("k1", "P", true, 1_000)
        l.onPeerBle("P", true, 1_100)
        l.onPeerBle("P", false, 2_000)
        assertTrue(l.peer("P").active)
    }

    @Test fun device_lost_leaves_peer_active() {
        val l = carriedLogic()
        l.onPeerBle("P", true, 1_000)
        l.tick(500_000)
        assertTrue(l.peer("P").active)
    }

    @Test fun stale_ble_edge_right_after_resolve_is_ignored_then_new_episode_revives() {
        fun resolved(): LoneWorkerLogic {
            val l = carriedLogic()
            l.peerServer("k1", "P", "n", "WALKER", "still", "B1", 1_000, true, 1_000, 1)
            l.peerServer("k1", "P", "n", "WALKER", "still", "B1", 1_000, false, 2_000, 1)
            return l
        }
        val l = resolved()
        l.onPeerBle("P", true, 5_000, 1)
        assertFalse(l.peer("P").active)
        l.onPeerBle("P", false, 6_000)
        l.onPeerBle("P", true, 7_000, 1)
        assertFalse(l.peer("P").active)
        l.onPeerBle("P", true, 8_000, 2)
        val p = l.peer("P", 2)
        assertTrue(p.active)
        assertFalse(p.silenced)

        val m = resolved()
        m.onPeerBle("P", true, 32_001, 1)
        val a = m.audiblePeers().single()
        assertEquals(1, a.episode)
        assertNull(a.key)
    }

    @Test fun resolved_peers_are_pruned_after_keep_time() {
        val l = carriedLogic()
        l.server("k1", "P", true, 1_000)
        l.server("k1", "P", false, 2_000)
        l.tick(2_000 + LoneWorkerPeers.RESOLVED_KEEP_MS - 1)
        assertEquals(1, l.peers.size)
        l.tick(2_000 + LoneWorkerPeers.RESOLVED_KEEP_MS)
        assertTrue(l.peers.isEmpty())
    }

    // -- beacon hint --

    @Test fun beacon_hint_takes_strongest_within_60s() {
        val l = carriedLogic()
        l.noteBeacon("A", -50, 0)
        l.noteBeacon("B", -70, 30_000)
        assertEquals("A" to -50, l.beaconHint(50_000))
        assertEquals("B" to -70, l.beaconHint(70_000))
        assertNull(l.beaconHint(100_000))
    }

    // v1.1.99 review fixes

    @Test fun new_server_key_is_a_new_audible_episode() {
        val l = carriedLogic()
        l.peerServer("k1", "P", "n", "WALKER", "still", "", 1_000, true, 1_000)
        l.ackAll(1_001)
        l.peerServer("k2", "P", "n", "WALKER", "still", "", 2_000, true, 2_000)
        assertEquals(2, l.peers.size)
        assertEquals("k2", l.audiblePeers().single().key)
    }

    @Test fun older_record_does_not_touch_newer_entry() {
        val l = carriedLogic()
        l.peerServer("k2", "P", "n", "WALKER", "still", "", 3_000, true, 3_000)
        l.ackAll(3_001)
        l.peerServer("k1", "P", "n", "WALKER", "still", "", 1_000, true, 3_500)
        assertEquals(2, l.peers.size)
        assertTrue(l.peers.single { it.key == "k2" }.silenced)
        assertTrue(l.peers.single { it.key == "k1" }.active)
    }

    @Test fun epless_record_never_adopts_or_ends_ble_entry() {
        val l = carriedLogic()
        l.onPeerBle("P", true, 1_000)
        l.peerServer("k9", "P", "n", "WALKER", "still", "", 500, false, 2_000)
        assertTrue(l.peer("P").active)

        val m = carriedLogic()
        m.onPeerBle("P", true, 1_000)
        m.ackAll(1_001)
        m.peerServer("k1", "P", "n", "WALKER", "still", "", 1_500, true, 1_600)
        assertEquals("k1", m.audiblePeers().single().key)
        val ble = m.peers.single { it.key == null }
        assertTrue(ble.active)
        assertTrue(ble.silenced)
        m.peerServer("k1", "P", "n", "WALKER", "still", "", 1_500, false, 5_000)
        assertFalse(m.peers.single { it.key == "k1" }.active)
        assertTrue(m.audiblePeers().isEmpty())
    }

    @Test fun untracked_resolved_record_is_ignored_and_first_ble_edge_alarms() {
        val l = carriedLogic()
        l.server("k9", "P", false, 1_000)
        assertTrue(l.peers.isEmpty())
        l.onPeerBle("P", true, 2_000)
        assertEquals(1, l.audiblePeers().size)
    }

    @Test fun new_ble_episode_number_is_a_new_audible_episode() {
        val l = carriedLogic()
        l.onPeerBle("P", true, 1_000, 1)
        assertEquals(1, l.peer("P").episode)
        l.ackAll(1_001)
        l.onPeerBle("P", true, 29_000, 1)
        assertEquals(0, l.audiblePeers().size)
        l.onPeerBle("P", true, 30_000, 2)
        assertEquals(1, l.audiblePeers().size)
        assertEquals(2, l.peer("P", 2).episode)
        l.onPeerBle("P", false, 31_000)
        l.onPeerBle("P", false, 41_000)
        assertEquals(2, l.peers.size)
        assertTrue(l.peers.none { it.active })
    }

    @Test fun server_entry_matches_same_ble_episode_then_next_number_resounds() {
        val l = carriedLogic()
        l.peerServer("k1", "P", "n", "WALKER", "still", "B1", 1_000, true, 1_000, 5)
        l.ackAll(1_001)
        l.onPeerBle("P", true, 2_000, 5)
        assertEquals(1, l.audiblePeers().size)
        l.ackAll(2_001)
        l.onPeerBle("P", true, 2_500, 5)
        assertEquals(0, l.audiblePeers().size)
        assertEquals("k1", l.peers.single().key)
        l.onPeerBle("P", true, 3_000, 6)
        assertEquals(6, l.audiblePeers().single().episode)
    }

    @Test fun beacon_sid_takes_strongest_registered_sample() {
        val l = carriedLogic()
        assertEquals(0, l.beaconSid(0))
        l.noteBeacon("A", -40, 0, 0)
        l.noteBeacon("B", -60, 10_000, 11)
        l.noteBeacon("C", -50, 20_000, 22)
        assertEquals(22, l.beaconSid(30_000))
        assertEquals(0, l.beaconSid(90_000))
    }

    @Test fun restore_sos_survives_settle_disable_and_ack() {
        val l = carriedLogic()
        l.restoreSos("fall", 5_000)
        assertEquals(Mode.SOS, l.mode)
        assertEquals("fall", l.trigger)
        assertTrue(l.sosActive)
        l.onZone(true, 6_000)
        l.tick(70_000)
        l.setEnabled(false, 71_000)
        assertFalse(l.ackWorking(72_000))
        assertEquals(Mode.SOS, l.mode)
        assertTrue(l.cancelSos(73_000))
        assertEquals(Mode.WATCHING, l.mode)
    }

    @Test fun alarm_vibrates_only_for_peer_siren_not_on_the_suspect_device() {
        val l = carriedLogic()
        assertFalse(l.alarmVibrates)
        l.toChecking()
        assertEquals(Mode.CHECKING, l.mode)
        assertFalse(l.alarmVibrates)
        l.onPeerBle("P", true, stillMs + 1)
        assertFalse(l.alarmVibrates)
        l.seenAt(stillMs + responseMs)
        assertEquals(Mode.SOS, l.mode)
        assertFalse(l.alarmVibrates)

        val r = carriedLogic()
        r.onPeerBle("P", true, 1_000)
        assertEquals(1, r.audiblePeers().size)
        assertTrue(r.alarmVibrates)
    }
}
