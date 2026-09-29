package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoneWorkerLogic 계약 테스트 (v1.1.99). 시계는 주입하는 elapsed ms 이며 안드로이드 의존이 없다.
 */
class LoneWorkerLogicTest {

    private val stillMs = 180_000L
    private val responseMs = 120_000L

    private fun newLogic(zoneInside: Boolean = false) =
        LoneWorkerLogic("SAFEALERT_WALKER_ME").apply { start(0L, zoneInside) }

    private fun LoneWorkerLogic.toChecking() { tick(stillMs) }
    private fun LoneWorkerLogic.toSos() { tick(stillMs); tick(stillMs + responseMs) }

    private fun LoneWorkerLogic.server(
        key: String, id: String, active: Boolean, now: Long, name: String = "홍길동"
    ) = onPeerServer(key, id, name, "WALKER", "still", "B1", now, active, now)

    // ── 본인 상태 ──

    @Test fun still_180s_opens_check_but_not_179_999() {
        val l = newLogic()
        l.tick(179_999)
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(180_000)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("still", l.trigger)
    }

    @Test fun unanswered_check_escalates_to_sos() {
        val l = newLogic()
        l.toChecking()
        l.tick(180_000 + responseMs - 1)
        assertEquals(Mode.CHECKING, l.mode)
        l.tick(180_000 + responseMs)
        assertEquals(Mode.SOS, l.mode)
        assertTrue(l.sosActive)
    }

    @Test fun ack_returns_to_watching_and_restarts_still_count() {
        val l = newLogic()
        l.toChecking()
        assertTrue(l.ackWorking(200_000))
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(200_000 + stillMs - 1)
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(200_000 + stillMs)
        assertEquals(Mode.CHECKING, l.mode)
    }

    @Test fun motion_does_not_close_an_open_check() {
        val l = newLogic()
        l.toChecking()
        l.onMoved(190_000)
        l.tick(191_000)
        assertEquals(Mode.CHECKING, l.mode)
    }

    @Test fun cancel_sos_returns_true_and_ack_is_ignored_in_sos() {
        val l = newLogic()
        l.toSos()
        assertFalse(l.ackWorking(400_000))
        assertEquals(Mode.SOS, l.mode)
        assertTrue(l.cancelSos(400_000))
        assertEquals(Mode.WATCHING, l.mode)
        assertFalse(l.cancelSos(400_001))
    }

    @Test fun fall_opens_check_at_next_tick_regardless_of_still_timer() {
        val l = newLogic()
        l.onMoved(1_000)
        l.onFall(1_500)
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(1_600)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("fall", l.trigger)
    }

    @Test fun disabled_never_checks_and_cancels_open_check_but_keeps_sos() {
        val off = newLogic()
        off.setEnabled(false, 0)
        off.tick(600_000)
        assertEquals(Mode.WATCHING, off.mode)

        val chk = newLogic()
        chk.toChecking()
        chk.setEnabled(false, 181_000)
        assertEquals(Mode.WATCHING, chk.mode)

        val sos = newLogic()
        sos.toSos()
        sos.setEnabled(false, 400_000)
        assertEquals(Mode.SOS, sos.mode)
    }

    @Test fun reenable_restarts_still_count_from_enable_time() {
        val l = newLogic()
        l.setEnabled(false, 0)
        l.setEnabled(true, 600_000)
        l.tick(600_000 + stillMs - 1)
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(600_000 + stillMs)
        assertEquals(Mode.CHECKING, l.mode)
    }

    // ── 안전구역 (D-03) ──

    @Test fun settled_zone_cancels_check_and_blocks_trigger_and_fall() {
        val l = newLogic()
        l.toChecking()
        l.onZone(true, 180_000)
        l.tick(239_999)
        assertEquals(Mode.CHECKING, l.mode)
        l.tick(240_000)
        assertTrue(l.zoneSettled)
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(900_000)
        assertEquals(Mode.WATCHING, l.mode)
        l.onFall(900_100)
        l.tick(900_200)
        assertEquals(Mode.WATCHING, l.mode)
    }

    @Test fun inside_59s_then_out_does_not_reset_still_count() {
        val l = newLogic()
        l.onZone(true, 100_000)
        l.onZone(false, 159_000)
        l.tick(179_999)
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(180_000)
        assertEquals(Mode.CHECKING, l.mode)
    }

    @Test fun flapping_never_settles() {
        val l = newLogic()
        var t = 0L
        while (t < 175_000) {
            l.onZone(true, t)
            l.tick(t)
            l.onZone(false, t + 1_000)
            l.tick(t + 1_000)
            t += 5_000
        }
        assertFalse(l.zoneSettled)
        l.tick(180_000)
        assertEquals(Mode.CHECKING, l.mode)
    }

    @Test fun leaving_settled_zone_starts_new_count() {
        val l = newLogic()
        l.onZone(true, 1_000)
        l.tick(61_000)
        assertTrue(l.zoneSettled)
        l.onZone(false, 300_000)
        l.tick(479_999)
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(480_000)
        assertEquals(Mode.CHECKING, l.mode)
    }

    @Test fun sos_survives_settled_zone() {
        val l = newLogic()
        l.toSos()
        l.onZone(true, 300_000)
        l.tick(400_000)
        assertTrue(l.zoneSettled)
        assertEquals(Mode.SOS, l.mode)
    }

    @Test fun peers_are_received_while_zone_settled() {
        val l = newLogic()
        l.onZone(true, 0)
        l.tick(70_000)
        assertTrue(l.zoneSettled)
        l.server("k1", "P", true, 71_000)
        assertEquals(1, l.audiblePeers().size)
    }

    // ── 동료 SOS (D-06) ──

    @Test fun own_bleid_is_ignored() {
        val l = newLogic()
        l.server("k1", "SAFEALERT_WALKER_ME", true, 1_000)
        l.onPeerBle("SAFEALERT_WALKER_ME", true, 1_000)
        assertTrue(l.peers.isEmpty())
    }

    @Test fun server_plus_ble_make_one_peer_and_silence_sticks() {
        val l = newLogic()
        l.server("k1", "P", true, 1_000)
        l.onPeerBle("P", true, 1_100)
        assertEquals(1, l.peers.size)
        assertEquals(1, l.audiblePeers().size)
        l.silencePeers()
        assertEquals(0, l.audiblePeers().size)
        assertTrue(l.peers.getValue("P").active)
        l.onPeerBle("P", true, 1_200)
        assertEquals(0, l.audiblePeers().size)
    }

    @Test fun server_resolved_matches_key_only() {
        val l = newLogic()
        l.server("k1", "P", true, 1_000)
        l.server("k1", "P", false, 2_000)
        assertFalse(l.peers.getValue("P").active)

        // 더 새로운 에피소드 뒤의 옛 키 해제는 무시
        l.server("k2", "P", true, 3_000)
        assertTrue(l.peers.getValue("P").active)
        l.server("k3", "P", true, 3_500)
        l.server("k2", "P", false, 4_000)
        assertTrue(l.peers.getValue("P").active)
    }

    @Test fun ble_only_peer_is_resolved_by_falling_edge() {
        val l = newLogic()
        l.onPeerBle("P", true, 1_000)
        assertTrue(l.peers.getValue("P").active)
        assertFalse(l.peers.getValue("P").fromServer)
        l.onPeerBle("P", false, 2_000)
        assertFalse(l.peers.getValue("P").active)
    }

    @Test fun server_backed_peer_is_not_resolved_by_ble_falling_edge() {
        val l = newLogic()
        l.server("k1", "P", true, 1_000)
        l.onPeerBle("P", true, 1_100)
        l.onPeerBle("P", false, 2_000)
        assertTrue(l.peers.getValue("P").active)
    }

    @Test fun device_lost_leaves_peer_active() {
        val l = newLogic()
        l.onPeerBle("P", true, 1_000)
        l.tick(500_000)
        assertTrue(l.peers.getValue("P").active)
    }

    @Test fun stale_ble_edge_right_after_resolve_is_ignored_then_new_episode_revives() {
        val l = newLogic()
        l.server("k1", "P", true, 1_000)
        l.server("k1", "P", false, 2_000)
        l.onPeerBle("P", true, 5_000)          // 해제 직후 광고 잔상
        assertFalse(l.peers.getValue("P").active)
        l.onPeerBle("P", false, 6_000)
        l.onPeerBle("P", true, 7_000)          // 관측된 false 이후의 true = 새 에피소드
        val p = l.peers.getValue("P")
        assertTrue(p.active)
        assertFalse(p.silenced)
    }

    @Test fun resolved_peers_are_pruned_after_keep_time() {
        val l = newLogic()
        l.server("k1", "P", true, 1_000)
        l.server("k1", "P", false, 2_000)
        l.tick(2_000 + LoneWorkerLogic.RESOLVED_KEEP_MS - 1)
        assertEquals(1, l.peers.size)
        l.tick(2_000 + LoneWorkerLogic.RESOLVED_KEEP_MS)
        assertTrue(l.peers.isEmpty())
    }

    // ── 비콘 힌트 ──

    @Test fun beacon_hint_takes_strongest_within_60s() {
        val l = newLogic()
        l.noteBeacon("A", -50, 0)
        l.noteBeacon("B", -70, 30_000)
        assertEquals("A" to -50, l.beaconHint(50_000))
        assertEquals("B" to -70, l.beaconHint(70_000))
        assertNull(l.beaconHint(100_000))
    }
}
