package com.wf11.safealert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Peer SOS scenarios for the per-episode store: server records and BLE bits of the same (bleId, ep) are one entry,
 * other episodes or other server keys are separate entries, and [confirm] silences only the listed item ids.
 */
class LoneWorkerPeerTest {

    private fun meLogic() = LoneWorkerLogic("ME").apply { start(0L, false) }

    private fun LoneWorkerLogic.srv(
        key: String, id: String, ep: Int, active: Boolean, created: Long, now: Long,
        name: String = "n", beacon: String = "",
        resolvedAt: Long = 0L, serverNow: Long? = null, wall: Long = 0L
    ) = onPeerServer(
        LoneWorkerPeers.ServerRec(key, id, name, "WALKER", "still", beacon, created, active, ep,
            LoneWorkerPeers.resolvedLocalMs(resolvedAt, serverNow, wall, now)),
        now
    )

    private fun LoneWorkerLogic.ackAll(now: Long) = silencePeers(now, peers.associate { it.id to it.epId })

    private fun LoneWorkerLogic.ack(now: Long, vararg targets: Pair<String, String>) = silencePeers(now, mapOf(*targets))

    private fun LoneWorkerLogic.peer(id: String, ep: Int? = null) =
        peers.single { it.bleId == id && (ep == null || it.episode == ep) }

    @Test fun server_resolve_then_ble_only_new_episode_sounds() {
        val l = meLogic()
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
        val l = meLogic()
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
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 1)
        l.ackAll(1_500)
        l.onPeerBle("P", true, 20_000, 1)
        assertEquals(0, l.audiblePeers().size)
        l.onPeerBle("P", true, 51_000, 1)
        assertEquals(1, l.audiblePeers().size)
        assertEquals(1_000L, l.peer("P").firstSeenMs)
    }

    @Test fun shared_id_flapping_neither_resolves_nor_resounds() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 3)
        l.ackAll(1_100)
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
        val l = meLogic()
        l.srv("k1", "P", 3, true, 1_000, 1_000)
        l.ackAll(1_100)
        l.onPeerBle("P", true, 2_000, 3)
        assertEquals(1, l.audiblePeers().size)
        l.ackAll(2_100)
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
        val a = meLogic()
        a.onPeerBle("P", true, 1_000, 4)
        a.ackAll(1_100)
        a.srv("k1", "P", 4, true, 1_100, 1_200)
        assertEquals("k1", a.peer("P").key)
        assertFalse(a.peer("P").silenced)
        assertEquals("n", a.audiblePeers().single().name)

        val b = meLogic()
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
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 3)
        l.ackAll(1_100)
        l.srv("k5", "P", 5, true, 5_000, 5_000)
        val a = l.audiblePeers().single()
        assertEquals("k5", a.key)
        assertEquals(5, a.episode)
        assertTrue(l.peer("P", 3).active)
        assertTrue(l.peer("P", 3).silenced)
    }

    @Test fun replayed_old_resolve_does_not_end_live_ble_entry() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 3)
        l.onPeerBle("P", true, 20_000, 3)
        l.srv("k1", "P", 3, false, 900, 25_000, resolvedAt = 990_000, serverNow = 1_000_000)
        assertTrue(l.peer("P", 3).active)
        assertEquals(1, l.audiblePeers().size)
        l.srv("k1", "P", 3, false, 900, 40_000, resolvedAt = 950_000, serverNow = 1_000_000)
        assertTrue(l.peer("P", 3).active)
    }

    @Test fun out_of_range_later_resolve_ends_ble_entry() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 3)
        l.srv("k1", "P", 3, false, 900, 40_000, resolvedAt = 970_000, serverNow = 1_000_000)
        assertFalse(l.peer("P", 3).active)
        assertEquals(0, l.audiblePeers().size)
        l.onPeerBle("P", true, 40_500, 3)
        assertFalse(l.peer("P", 3).active)

        val m = meLogic()
        m.onPeerBle("P", true, 1_000, 3)
        m.srv("k1", "P", 3, false, 900, 40_000, resolvedAt = 950_000, serverNow = 1_000_000)
        assertTrue(m.peer("P", 3).active)
        m.srv("k1", "P", 3, false, 900, 40_000, resolvedAt = 0L, serverNow = 1_000_000)
        assertTrue(m.peer("P", 3).active)
    }

    @Test fun unknown_offset_uses_slack() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 3)
        l.srv("k1", "P", 3, false, 900, 40_000, resolvedAt = 965_000, wall = 1_000_000)
        assertTrue(l.peer("P", 3).active)
        l.srv("k1", "P", 3, false, 900, 40_000, resolvedAt = 975_000, wall = 1_000_000)
        assertFalse(l.peer("P", 3).active)
    }

    @Test fun server_entry_first_ble_hearing_and_30s_gap_resound() {
        val l = meLogic()
        l.srv("k1", "P", 1, true, 1_000, 1_000)
        l.ackAll(1_100)
        assertEquals(0, l.audiblePeers().size)
        l.onPeerBle("P", true, 2_000, 1)
        assertEquals(1, l.audiblePeers().size)
        l.ackAll(2_100)
        l.onPeerBle("P", true, 22_000, 1)
        assertEquals(0, l.audiblePeers().size)
        l.onPeerBle("P", true, 53_000, 1)
        assertEquals(1, l.audiblePeers().size)
        assertEquals(1_000L, l.peer("P").firstSeenMs)
    }

    @Test fun shared_bleid_two_phones_keep_separate_entries() {
        val l = meLogic()
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

        val m = meLogic()
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
        val l = meLogic()
        l.ack(1_000, "k:k1" to "P#3")
        l.srv("k1", "P", 3, true, 2_000, 2_000)
        assertTrue(l.peer("P").active)
        assertTrue(l.peer("P").silenced)
        l.srv("k2", "Q", 1, true, 3_000, 3_000)
        assertEquals("Q", l.audiblePeers().single().bleId)
        l.onPeerBle("R", true, 4_000, 2)
        assertEquals(2, l.audiblePeers().size)

        val e = meLogic()
        e.ack(1_000, "k:kS" to "S#1")
        e.srv("kS", "S", 1, true, 61_001, 61_001)
        assertEquals(1, e.audiblePeers().size)

        val m = meLogic()
        m.srv("k1", "P", 0, true, 1_000, 1_000)
        m.srv("k1", "P", 0, false, 1_000, 2_000)
        m.ackAll(3_000)
        m.onPeerBle("Q", true, 4_000, 1)
        assertEquals(1, m.audiblePeers().size)
    }

    @Test fun notification_confirm_silences_only_listed_entries() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 1)
        l.onPeerBle("Q", true, 2_000, 2)
        l.ack(2_100, "b:P#1" to "P#1")
        assertEquals("Q", l.audiblePeers().single().bleId)
    }

    @Test fun confirm_right_after_resolve_keeps_the_guard() {
        val l = meLogic()
        l.srv("k1", "P", 1, true, 1_000, 1_000)
        l.srv("k1", "P", 1, false, 1_000, 2_000)
        l.ackAll(2_100)
        assertEquals(1, l.peers.size)
        assertTrue(l.peer("P").silenced)
        l.onPeerBle("P", true, 2_500, 1)
        assertEquals(0, l.audiblePeers().size)
        assertEquals(1, l.peers.size)
        l.onPeerBle("P", true, 32_100, 1)
        assertEquals(1, l.audiblePeers().size)
    }

    // -- server clock --

    @Test fun resolved_local_uses_server_now_without_slack_when_known() {
        assertEquals(10_000L, LoneWorkerPeers.resolvedLocalMs(970_000, 1_000_000, 5_000_000, 40_000))
    }

    @Test fun resolved_local_falls_back_to_wall_clock_with_10s_slack() {
        assertEquals(10_000L - LoneWorkerPeers.CLOCK_SLACK_MS, LoneWorkerPeers.resolvedLocalMs(970_000, null, 1_000_000, 40_000))
        assertNull(LoneWorkerPeers.resolvedLocalMs(0L, 1_000_000, 1_000_000, 40_000))
        assertNull(LoneWorkerPeers.resolvedLocalMs(-5L, null, 1_000_000, 40_000))
    }

    // -- refused resolve is re-judged on tick --

    @Test fun untracked_resolve_refused_by_live_ad_is_retried_on_tick() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 3)
        l.onPeerBle("P", true, 20_000, 3)
        l.srv("k1", "P", 3, false, 900, 25_000, resolvedAt = 990_000, serverNow = 1_000_000)
        assertTrue(l.peer("P", 3).active)
        l.tick(30_000)
        assertTrue(l.peer("P", 3).active)
        l.tick(35_000)
        assertFalse(l.peer("P", 3).active)
        assertEquals(0, l.audiblePeers().size)
    }

    @Test fun replayed_old_resolve_is_never_retried() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 3)
        l.onPeerBle("P", true, 20_000, 3)
        l.srv("k1", "P", 3, false, 900, 25_000, resolvedAt = 900_000, serverNow = 1_000_000)
        l.tick(40_000)
        l.tick(60_000)
        assertTrue(l.peer("P", 3).active)
    }

    @Test fun pending_resolve_dropped_when_item_ends_otherwise() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 3)
        l.onPeerBle("P", true, 20_000, 3)
        // resolve time lands ahead of now (clock step), refused while the ad is live
        l.srv("k1", "P", 3, false, 900, 25_000, resolvedAt = 1_100_000, serverNow = 1_000_000)
        assertTrue(l.peer("P", 3).active)
        l.onPeerBle("P", false, 26_000)
        l.onPeerBle("P", false, 36_000)
        assertFalse(l.peer("P", 3).active)
        l.tick(40_000)
        l.onPeerBle("P", true, 70_000, 3)
        assertTrue(l.peer("P", 3).active)
        l.tick(90_000)
        assertTrue(l.peer("P", 3).active)
    }

    // -- confirm by item id --

    @Test fun ack_silences_only_the_item_id_not_same_episode_sibling() {
        val l = meLogic()
        l.srv("K1", "X", 1, true, 1_000, 1_000)
        l.srv("K2", "X", 1, true, 1_100, 1_100)
        assertEquals(2, l.audiblePeers().size)
        l.ack(1_200, "k:K1" to "X#1")
        assertEquals("K2", l.audiblePeers().single().key)
        assertTrue(l.peers.single { it.key == "K1" }.silenced)
    }

    @Test fun cold_ack_of_ble_item_survives_server_absorption() {
        val l = meLogic()
        l.ack(1_000, "b:X#1" to "X#1")
        l.onPeerBle("X", true, 2_000, 1)
        assertTrue(l.peer("X").silenced)
        l.srv("K1", "X", 1, true, 2_500, 3_000)
        assertEquals("K1", l.peer("X").key)
        assertTrue(l.peer("X").silenced)
        assertEquals(0, l.audiblePeers().size)
    }

    @Test fun cold_ack_of_server_item_survives_first_ble_hearing() {
        val l = meLogic()
        l.ack(1_000, "k:K1" to "X#1")
        l.srv("K1", "X", 1, true, 2_000, 2_000)
        assertTrue(l.peer("X").silenced)
        l.onPeerBle("X", true, 3_000, 1)
        assertTrue(l.peer("X").silenced)
        assertEquals(0, l.audiblePeers().size)
    }

    @Test fun cold_ack_of_k1_does_not_silence_absorbing_k2() {
        val l = meLogic()
        l.ack(1_000, "k:K1" to "X#1")
        l.onPeerBle("X", true, 2_000, 1)
        assertTrue(l.peer("X").silenced)
        l.srv("K2", "X", 1, true, 2_500, 3_000)
        assertEquals("K2", l.audiblePeers().single().key)
    }

    @Test fun live_ble_ack_then_server_absorption_rings_again() {
        val l = meLogic()
        l.onPeerBle("X", true, 1_000, 1)
        l.ack(1_100, "b:X#1" to "X#1")
        assertEquals(0, l.audiblePeers().size)
        l.srv("K1", "X", 1, true, 1_200, 1_300)
        assertEquals("K1", l.audiblePeers().single().key)
    }

    @Test fun gap_30s_after_ack_rings_again() {
        val l = meLogic()
        l.onPeerBle("X", true, 1_000, 1)
        l.ack(1_100, "b:X#1" to "X#1")
        l.onPeerBle("X", true, 20_000, 1)
        assertEquals(0, l.audiblePeers().size)
        l.onPeerBle("X", true, 50_000, 1)
        assertEquals(1, l.audiblePeers().size)
    }
}
