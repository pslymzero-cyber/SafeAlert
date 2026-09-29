package com.wf11.safealert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Peer SOS judgment scenarios (third review): 30 s gap, 10 s falling delay, record episode, cold silence. */
class LoneWorkerPeerTest {

    private fun newLogic() = LoneWorkerLogic("ME").apply { start(0L, false) }

    private fun LoneWorkerLogic.srv(key: String, id: String, ep: Int, active: Boolean, created: Long, now: Long) =
        onPeerServer(key, id, "n", "WALKER", "still", "", created, active, now, ep)

    @Test fun server_resolve_then_ble_only_new_episode_sounds() {
        val l = newLogic()
        l.srv("k1", "P", 1, true, 1_000, 1_000)
        l.onPeerBle("P", true, 1_100, 1)
        assertEquals(1, l.peers.size)
        l.srv("k1", "P", 1, false, 1_000, 5_000)
        assertFalse(l.peers.getValue("P").active)
        l.onPeerBle("P", true, 6_000, 1)
        assertFalse(l.peers.getValue("P").active)
        l.onPeerBle("P", true, 20_000, 2)
        val p = l.peers.getValue("P")
        assertTrue(p.active)
        assertFalse(p.silenced)
        assertNull(p.key)
        assertEquals(2, p.episode)
    }

    @Test fun pruned_entry_then_same_bit_makes_new_entry() {
        val l = newLogic()
        l.onPeerBle("P", true, 1_000, 1)
        l.onPeerBle("P", false, 2_000)
        l.onPeerBle("P", false, 12_000)
        assertFalse(l.peers.getValue("P").active)
        l.tick(12_000 + LoneWorkerLogic.RESOLVED_KEEP_MS)
        assertTrue(l.peers.isEmpty())
        l.onPeerBle("P", true, 80_000, 1)
        assertEquals(1, l.audiblePeers().size)
    }

    @Test fun same_bleid_other_phone_same_episode_resounds_after_30s_gap() {
        val l = newLogic()
        l.onPeerBle("P", true, 1_000, 1)
        l.silencePeers(1_500)
        l.onPeerBle("P", true, 20_000, 1)
        assertEquals(0, l.audiblePeers().size)
        l.onPeerBle("P", true, 51_000, 1)
        assertEquals(1, l.audiblePeers().size)
    }

    @Test fun shared_id_flapping_neither_resolves_nor_resounds() {
        val l = newLogic()
        l.onPeerBle("P", true, 1_000, 3)
        l.silencePeers(1_100)
        for (i in 1..80) {
            val t = 1_000L + i * 500L
            if (i % 2 == 0) l.onPeerBle("P", true, t, 3) else l.onPeerBle("P", false, t)
            l.tick(t)
        }
        assertEquals(1, l.peers.size)
        assertTrue(l.peers.getValue("P").active)
        assertTrue(l.peers.getValue("P").silenced)
        assertEquals(0, l.audiblePeers().size)
    }

    @Test fun server_record_episode_differs_from_ble_is_new_sos() {
        val l = newLogic()
        l.srv("k1", "P", 3, true, 1_000, 1_000)
        assertEquals(3, l.peers.getValue("P").episode)
        l.silencePeers(1_100)
        l.onPeerBle("P", true, 2_000, 3)
        assertEquals(0, l.audiblePeers().size)
        l.onPeerBle("P", true, 3_000, 4)
        assertEquals(1, l.audiblePeers().size)
        assertNull(l.peers.getValue("P").key)
        assertEquals(4, l.peers.getValue("P").episode)
    }

    @Test fun ble_entry_adopts_server_key_only_for_same_episode() {
        val a = newLogic()
        a.onPeerBle("P", true, 1_000, 4)
        a.silencePeers(1_100)
        a.srv("k1", "P", 4, true, 1_100, 1_200)
        assertEquals("k1", a.peers.getValue("P").key)
        assertTrue(a.peers.getValue("P").silenced)

        val b = newLogic()
        b.onPeerBle("P", true, 1_000, 4)
        b.srv("k0", "P", 3, true, 900, 1_200)
        assertNull(b.peers.getValue("P").key)
        assertEquals(4, b.peers.getValue("P").episode)
        b.srv("k0", "P", 3, false, 900, 1_400)
        assertTrue(b.peers.getValue("P").active)
    }

    @Test fun server_resolve_keeps_peer_when_other_episode_bit_seen_within_10s() {
        fun steps(resolveAt: Long): LoneWorkerLogic {
            val l = newLogic()
            l.srv("k0", "P", 4, true, 1_000, 1_000)
            l.onPeerBle("P", true, 1_100, 4)
            l.srv("k1", "P", 3, true, 1_500, 1_500)
            l.srv("k1", "P", 3, false, 1_500, resolveAt)
            return l
        }
        val a = steps(2_000)
        val p = a.peers.getValue("P")
        assertTrue(p.active)
        assertNull(p.key)
        assertFalse(p.fromServer)
        assertEquals(4, p.episode)
        assertFalse(steps(11_100).peers.getValue("P").active)
    }

    @Test fun cold_start_silence_mutes_new_entries_for_60s() {
        val l = newLogic()
        l.silencePeers(1_000)
        l.srv("k1", "P", 0, true, 2_000, 2_000)
        assertTrue(l.peers.getValue("P").active)
        assertEquals(0, l.audiblePeers().size)
        l.onPeerBle("Q", true, 30_000, 1)
        assertEquals(0, l.audiblePeers().size)
        l.onPeerBle("R", true, 61_000, 1)
        assertEquals(1, l.audiblePeers().size)

        val m = newLogic()
        m.srv("k1", "P", 0, true, 1_000, 1_000)
        m.srv("k1", "P", 0, false, 1_000, 2_000)
        m.silencePeers(3_000)
        m.onPeerBle("Q", true, 4_000, 1)
        assertEquals(1, m.audiblePeers().size)
    }
}
