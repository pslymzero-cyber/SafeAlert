package com.wf11.safealert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Peer SOS scenarios for the per-episode store: server records and BLE bits of the same (bleId, ep) are one entry,
 * other episodes or other server keys are separate entries, and [confirm] silences only the listed episodes.
 */
class LoneWorkerPeerTest {

    private fun newLogic() = LoneWorkerLogic("ME").apply { start(0L, false) }

    private fun LoneWorkerLogic.srv(
        key: String, id: String, ep: Int, active: Boolean, created: Long, now: Long,
        name: String = "n", beacon: String = "",
        resolvedAt: Long = 0L, serverNow: Long = 0L, slack: Long = 0L
    ) = onPeerServer(key, id, name, "WALKER", "still", beacon, created, active, now, ep, resolvedAt, serverNow, slack)

    private fun LoneWorkerLogic.peer(id: String, ep: Int? = null) =
        peers.single { it.bleId == id && (ep == null || it.episode == ep) }

    @Test fun server_resolve_then_ble_only_new_episode_sounds() {
        val l = newLogic()
        l.srv("k1", "P", 1, true, 1_000, 1_000)
        l.onPeerBle("P", true, 1_100, 1)
        assertEquals(1, l.peers.size)
        l.srv("k1", "P", 1, false, 1_000, 5_000)
        assertFalse(l.peer("P").active)
        l.onPeerBle("P", true, 6_000, 1)
        assertFalse(l.peer("P").active)
        l.onPeerBle("P", true, 20_000, 2)
        val p = l.peer("P", 2)
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
        assertFalse(l.peer("P").active)
        l.tick(12_000 + LoneWorkerPeers.RESOLVED_KEEP_MS)
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
        assertEquals(1_000L, l.peer("P").firstSeenMs)
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
        assertTrue(l.peer("P").active)
        assertTrue(l.peer("P").silenced)
        assertEquals(0, l.audiblePeers().size)
    }

    @Test fun other_episode_ble_bit_is_new_entry_and_keeps_server_entry() {
        val l = newLogic()
        l.srv("k1", "P", 3, true, 1_000, 1_000)
        l.silencePeers(1_100)
        l.onPeerBle("P", true, 2_000, 3)
        assertEquals(1, l.audiblePeers().size)
        l.silencePeers(2_100)
        l.onPeerBle("P", true, 2_500, 3)
        assertEquals(0, l.audiblePeers().size)
        l.onPeerBle("P", true, 3_000, 4)
        assertEquals(2, l.peers.size)
        val a = l.audiblePeers().single()
        assertNull(a.key)
        assertEquals(4, a.episode)
        assertEquals("k1", l.peer("P", 3).key)
        assertTrue(l.peer("P", 3).silenced)
        l.srv("k1", "P", 3, false, 1_000, 4_000)
        assertFalse(l.peer("P", 3).active)
        assertTrue(l.peer("P", 4).active)
    }

    @Test fun adopted_entry_sounds_again_with_name() {
        val a = newLogic()
        a.onPeerBle("P", true, 1_000, 4)
        a.silencePeers(1_100)
        a.srv("k1", "P", 4, true, 1_100, 1_200)
        assertEquals("k1", a.peer("P").key)
        assertFalse(a.peer("P").silenced)
        assertEquals("n", a.audiblePeers().single().name)

        val b = newLogic()
        b.onPeerBle("P", true, 1_000, 4)
        b.srv("k0", "P", 3, true, 900, 1_200)
        assertTrue(b.peer("P", 3).active)
        assertEquals("k0", b.peer("P", 3).key)
        assertFalse(b.peer("P", 3).silenced)
        assertNull(b.peer("P", 4).key)
        b.srv("k0", "P", 3, false, 900, 1_400)
        assertFalse(b.peer("P", 3).active)
        assertTrue(b.peer("P", 4).active)
    }

    @Test fun stale_ble_entry_does_not_swallow_new_server_episode() {
        val l = newLogic()
        l.onPeerBle("P", true, 1_000, 3)
        l.silencePeers(1_100)
        l.srv("k5", "P", 5, true, 5_000, 5_000)
        val a = l.audiblePeers().single()
        assertEquals("k5", a.key)
        assertEquals(5, a.episode)
        assertTrue(l.peer("P", 3).active)
        assertTrue(l.peer("P", 3).silenced)
    }

    @Test fun replayed_old_resolve_does_not_end_live_ble_entry() {
        val l = newLogic()
        l.onPeerBle("P", true, 1_000, 3)
        l.onPeerBle("P", true, 20_000, 3)
        l.srv("k1", "P", 3, false, 900, 25_000, resolvedAt = 990_000, serverNow = 1_000_000)
        assertTrue(l.peer("P", 3).active)
        assertEquals(1, l.audiblePeers().size)
        l.srv("k1", "P", 3, false, 900, 40_000, resolvedAt = 950_000, serverNow = 1_000_000)
        assertTrue(l.peer("P", 3).active)
    }

    @Test fun out_of_range_later_resolve_ends_ble_entry() {
        val l = newLogic()
        l.onPeerBle("P", true, 1_000, 3)
        l.srv("k1", "P", 3, false, 900, 40_000, resolvedAt = 970_000, serverNow = 1_000_000)
        assertFalse(l.peer("P", 3).active)
        assertEquals(0, l.audiblePeers().size)
        l.onPeerBle("P", true, 40_500, 3)
        assertFalse(l.peer("P", 3).active)

        val m = newLogic()
        m.onPeerBle("P", true, 1_000, 3)
        m.srv("k1", "P", 3, false, 900, 40_000, resolvedAt = 950_000, serverNow = 1_000_000)
        assertTrue(m.peer("P", 3).active)
        m.srv("k1", "P", 3, false, 900, 40_000, resolvedAt = 0L, serverNow = 1_000_000)
        assertTrue(m.peer("P", 3).active)
    }

    @Test fun unknown_offset_uses_slack() {
        val l = newLogic()
        l.onPeerBle("P", true, 1_000, 3)
        l.srv("k1", "P", 3, false, 900, 40_000, resolvedAt = 965_000, serverNow = 1_000_000, slack = 10_000)
        assertTrue(l.peer("P", 3).active)
        l.srv("k1", "P", 3, false, 900, 40_000, resolvedAt = 975_000, serverNow = 1_000_000, slack = 10_000)
        assertFalse(l.peer("P", 3).active)
    }

    @Test fun server_entry_first_ble_hearing_and_30s_gap_resound() {
        val l = newLogic()
        l.srv("k1", "P", 1, true, 1_000, 1_000)
        l.silencePeers(1_100)
        assertEquals(0, l.audiblePeers().size)
        l.onPeerBle("P", true, 2_000, 1)
        assertEquals(1, l.audiblePeers().size)
        l.silencePeers(2_100)
        l.onPeerBle("P", true, 22_000, 1)
        assertEquals(0, l.audiblePeers().size)
        l.onPeerBle("P", true, 53_000, 1)
        assertEquals(1, l.audiblePeers().size)
        assertEquals(1_000L, l.peer("P").firstSeenMs)
    }

    @Test fun shared_bleid_two_phones_keep_separate_entries() {
        val l = newLogic()
        l.srv("kX", "P", 2, true, 1_000, 1_000, name = "X", beacon = "B1")
        l.srv("kY", "P", 5, true, 1_500, 1_500, name = "Y", beacon = "B2")
        l.onPeerBle("P", true, 1_600, 5)
        assertEquals(2, l.peers.size)
        l.srv("kY", "P", 5, false, 1_500, 2_000)
        assertFalse(l.peers.single { it.key == "kY" }.active)
        val x = l.peers.single { it.key == "kX" }
        assertTrue(x.active)
        assertEquals("X", x.name)
        assertEquals("B1", x.beacon)
        assertEquals("kX", l.audiblePeers().single().key)
        l.srv("kX", "P", 2, false, 1_000, 3_000)
        assertFalse(l.peers.single { it.key == "kX" }.active)

        val m = newLogic()
        m.srv("kA", "Q", 1, true, 1_000, 1_000, name = "A")
        m.srv("kB", "Q", 1, true, 1_100, 1_100, name = "B")
        assertEquals(2, m.peers.size)
        assertEquals("A", m.peers.single { it.key == "kA" }.name)
        assertEquals("B", m.peers.single { it.key == "kB" }.name)
        m.srv("kA", "Q", 1, false, 1_000, 2_000)
        assertFalse(m.peers.single { it.key == "kA" }.active)
        assertTrue(m.peers.single { it.key == "kB" }.active)
    }

    @Test fun cold_confirm_silences_only_listed_entries() {
        val l = newLogic()
        l.silencePeers(1_000, listOf("P#3"))
        l.srv("k1", "P", 3, true, 2_000, 2_000)
        assertTrue(l.peer("P").active)
        assertTrue(l.peer("P").silenced)
        l.srv("k2", "Q", 1, true, 3_000, 3_000)
        assertEquals("Q", l.audiblePeers().single().bleId)
        l.onPeerBle("R", true, 4_000, 2)
        assertEquals(2, l.audiblePeers().size)

        val e = newLogic()
        e.silencePeers(1_000, listOf("S#1"))
        e.srv("kS", "S", 1, true, 61_001, 61_001)
        assertEquals(1, e.audiblePeers().size)

        val m = newLogic()
        m.srv("k1", "P", 0, true, 1_000, 1_000)
        m.srv("k1", "P", 0, false, 1_000, 2_000)
        m.silencePeers(3_000)
        m.onPeerBle("Q", true, 4_000, 1)
        assertEquals(1, m.audiblePeers().size)
    }

    @Test fun notification_confirm_silences_only_listed_entries() {
        val l = newLogic()
        l.onPeerBle("P", true, 1_000, 1)
        l.onPeerBle("Q", true, 2_000, 2)
        l.silencePeers(2_100, listOf("P#1"))
        assertEquals("Q", l.audiblePeers().single().bleId)
    }

    @Test fun confirm_right_after_resolve_keeps_the_guard() {
        val l = newLogic()
        l.srv("k1", "P", 1, true, 1_000, 1_000)
        l.srv("k1", "P", 1, false, 1_000, 2_000)
        l.silencePeers(2_100)
        assertEquals(1, l.peers.size)
        assertTrue(l.peer("P").silenced)
        l.onPeerBle("P", true, 2_500, 1)
        assertEquals(0, l.audiblePeers().size)
        assertEquals(1, l.peers.size)
        l.onPeerBle("P", true, 32_100, 1)
        assertEquals(1, l.audiblePeers().size)
    }
}
