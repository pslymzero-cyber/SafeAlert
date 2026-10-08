package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerAlarm.Pattern
import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Peer SOS scenarios for the per-episode store: server records and BLE bits of the same (bleId, ep) are one entry,
 * other episodes or other server keys are separate entries, and [confirm] silences only the listed item ids.
 * episode (ep) = the SOS number a phone advertises, 0 for an old sender without one. An ack "before the entry exists"
 * is an [OK] pressed while this phone has no entry for it yet (for example right after a restart); it is kept for
 * LoneWorkerPeers.PENDING_SILENCE_MS. Helpers srv / peer / ack: LoneWorkerTestKit.
 */
class LoneWorkerPeerTest {

    private fun meLogic() = LoneWorkerLogic("ME").apply { start(0L, false) }

    // An SOS still active an hour after its server record was made is over: it stops ringing, shows as ended without an
    //   answer, its record is queued to be marked released on the server (its writer may be gone), and its adverts (an old
    //   phone that cannot release itself keeps advertising) open nothing until they turn false; after that even the same
    //   episode rings as a new SOS.
    @Test fun peer_sos_stops_ringing_an_hour_after_it_began() {
        val l = meLogic()
        l.srv("K1", "P", 1, true, created = 5L, now = 1_000, serverNow = 5L)
        l.onPeerBle("P", true, 1_500, 1)
        l.tick(1_000 + SosLedger.AUTO_RELEASE_MS - 1)
        assertEquals(1, l.audiblePeers().size)
        assertTrue(l.takeAutoReleasedPeers().isEmpty())
        val end = 1_000 + SosLedger.AUTO_RELEASE_MS
        l.onPeerBle("P", true, end - 1, 1)
        l.tick(end)
        assertTrue("서버 기록이 있으면 광고가 들려도 끝난다", l.audiblePeers().isEmpty())
        assertTrue("응답 없이 끝난 것으로 보인다", l.peer("P").autoEnded)
        assertEquals(listOf("K1"), l.takeAutoReleasedPeers())
        assertTrue(l.takeAutoReleasedPeers().isEmpty())
        val later = end + LoneWorkerPeers.RESOLVED_KEEP_MS
        l.tick(later)
        l.onPeerBle("P", true, later + 1_000, 1)
        assertTrue("같은 SOS 광고로 다시 울리면 안 된다", l.audiblePeers().isEmpty())
        l.onPeerBle("P", false, later + 2_000)
        l.onPeerBle("P", true, later + 3_000, 1)
        assertEquals("광고가 꺼졌다 다시 켜지면 새 SOS 로 울린다", 1, l.audiblePeers().size)
    }

    // A record replayed after its hour is over (a phone starting later, or restarting) never rings. Its age comes only from
    //   the server's clock: with that unknown it rings and runs an hour from when it is first heard here.
    @Test fun replayed_sos_older_than_an_hour_never_rings() {
        val l = meLogic()
        val now = 2 * SosLedger.AUTO_RELEASE_MS
        l.srv("K1", "P", 1, true, created = 5L, now = now, serverNow = 5L + SosLedger.AUTO_RELEASE_MS)
        assertTrue(l.audiblePeers().isEmpty())
        assertEquals(listOf("K1"), l.takeAutoReleasedPeers())
        l.srv("K2", "Q", 1, true, created = 5L, now = now)
        assertEquals("서버 시각을 모르면 이 폰 시계로 끝내지 않는다", 1, l.audiblePeers().size)
        l.tick(now + SosLedger.AUTO_RELEASE_MS)
        assertTrue(l.audiblePeers().isEmpty())
    }

    // A record marked released by the one-hour limit (reason auto) also shows as ended without an answer, not as cleared.
    @Test fun server_auto_release_shows_as_ended_without_an_answer() {
        val l = meLogic()
        l.srv("K1", "P", 1, true, created = 5L, now = 1_000)
        l.srv("K1", "P", 1, false, created = 5L, now = 2_000, auto = true)
        assertFalse(l.peer("P").active)
        assertTrue(l.peer("P").autoEnded)
    }

    // An old sender (episode 0) cannot tell one SOS from its next, so an hour-old record never blocks its adverts on air:
    //   neither a stale record replayed here nor one that ran out here hides its SOS heard now.
    @Test fun old_sender_sos_is_never_ignored_on_air() {
        val l = meLogic()
        val t = 2 * SosLedger.AUTO_RELEASE_MS
        l.srv("K0", "P", 0, true, created = 5L, now = t, serverNow = 5L + SosLedger.AUTO_RELEASE_MS)
        l.onPeerBle("P", true, t + 1_000, 0)
        assertEquals("오래된 기록이 그 폰의 지금 구조 요청을 가리면 안 된다", 1, l.audiblePeers().size)

        val m = meLogic()
        m.srv("K0", "P", 0, true, created = 5L, now = 1_000, serverNow = 5L)
        val end = 1_000 + SosLedger.AUTO_RELEASE_MS
        m.tick(end)
        assertTrue(m.audiblePeers().isEmpty())
        m.onPeerBle("P", true, end + LoneWorkerPeers.PEER_RESOLVE_GUARD_MS + 1, 0)
        assertEquals("한 시간 지난 기록이 그 폰의 다음 구조 요청을 가리면 안 된다", 1, m.audiblePeers().size)
    }

    // An SOS heard only over the air may never have reached the server (no mail, nobody told), so it keeps ringing past the
    //   hour while its adverts go on; once they stop (the phone died or left) it ends without an answer, and heard again it
    //   rings as live.
    @Test fun ble_only_sos_rings_past_the_hour_while_heard_and_ends_once_silent() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 1)
        val end = 1_000 + SosLedger.AUTO_RELEASE_MS
        l.onPeerBle("P", true, end - 1, 1)
        l.tick(end)
        assertEquals("아직 들리는 동안은 한 시간이 지나도 울린다", 1, l.audiblePeers().size)
        val silent = end - 1 + LoneWorkerPeers.PEER_LIVE_AD_MS
        l.tick(silent)
        assertTrue("광고가 끊기면 끝난다", l.audiblePeers().isEmpty())
        assertTrue(l.peer("P").autoEnded)
        assertTrue("서버 기록이 없으면 서버에 쓸 것도 없다", l.takeAutoReleasedPeers().isEmpty())
        l.tick(silent + LoneWorkerPeers.RESOLVED_KEEP_MS)
        l.onPeerBle("P", true, silent + LoneWorkerPeers.RESOLVED_KEEP_MS + 1_000, 1)
        assertEquals("다시 들리면 다시 울린다", 1, l.audiblePeers().size)
    }

    // A record released by the one-hour limit relabels its SOS's BLE entry even when that entry ended on air (its adverts
    //   turned false first, or the record came while they were still heard).
    @Test fun server_auto_release_relabels_an_entry_ended_on_air() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 5)
        l.onPeerBle("P", false, 2_000)
        l.onPeerBle("P", false, 12_000)
        l.srv("K1", "P", 5, false, created = 5L, now = 13_000, resolvedAt = 12_500, serverNow = 13_000, auto = true)
        assertTrue(l.peer("P").autoEnded)

        val m = meLogic()
        m.onPeerBle("P", true, 1_000, 5)
        m.srv("K1", "P", 5, false, created = 5L, now = 2_000, resolvedAt = 1_500, serverNow = 2_000, auto = true)
        m.onPeerBle("P", false, 3_000)
        m.onPeerBle("P", false, 13_000)
        m.tick(13_000)
        assertTrue(m.peer("P").autoEnded)
    }

    // -- one phone per entry: server records, BLE edges, episodes --

    @Test fun same_bleid_as_mine_still_alarms() {
        val l = newLogic(carried = true)
        l.srv("k1", "SAFEALERT_WALKER_ME", 0, true, 1_000, 1_000, name = "Hong", beacon = "B1")
        l.onPeerBle("SAFEALERT_WALKER_ME", true, 1_100)
        assertEquals(1, l.peers.size)
        assertEquals(1, l.audiblePeers().size)
    }

    @Test fun server_resolved_matches_key_only() {
        val l = newLogic(carried = true)
        l.srv("k1", "P", 0, true, 1_000, 1_000, name = "Hong", beacon = "B1")
        l.srv("k1", "P", 0, false, 2_000, 2_000, name = "Hong", beacon = "B1")
        assertFalse(l.peers.single { it.key == "k1" }.active)

        // each key is its own entry: resolving k2 leaves k3 sounding
        l.srv("k2", "P", 0, true, 3_000, 3_000, name = "Hong", beacon = "B1")
        assertTrue(l.peers.single { it.key == "k2" }.active)
        l.srv("k3", "P", 0, true, 3_500, 3_500, name = "Hong", beacon = "B1")
        l.srv("k2", "P", 0, false, 4_000, 4_000, name = "Hong", beacon = "B1")
        assertFalse(l.peers.single { it.key == "k2" }.active)
        assertTrue(l.peers.single { it.key == "k3" }.active)
    }

    @Test fun ble_only_peer_is_resolved_after_10s_of_false() {
        val l = newLogic(carried = true)
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

        val m = newLogic(carried = true)
        m.onPeerBle("P", true, 1_000)
        m.onPeerBle("P", false, 2_000)
        m.tick(12_000)
        assertFalse(m.peer("P").active)
    }

    /**
     * After 10 s of BLE false the BLE-only entry of the same phone (another episode) is resolved - the 10 s path ran -
     * while the entry backed by server record k1 stays active: only the server ends it.
     */
    @Test fun server_backed_peer_is_not_resolved_by_ble_falling_edge() {
        val l = newLogic(carried = true)
        l.srv("k1", "P", 0, true, 1_000, 1_000, name = "Hong", beacon = "B1")
        l.onPeerBle("P", true, 1_100)
        l.onPeerBle("P", true, 1_200, episode = 5)
        assertEquals(2, l.peers.size)
        l.onPeerBle("P", false, 2_000)
        assertTrue(l.peers.single { it.key == "k1" }.active)
        l.onPeerBle("P", false, 2_000 + LoneWorkerPeers.PEER_BLE_FALL_MS)
        assertFalse(l.peers.single { it.key == null }.active)
        assertTrue(l.peers.single { it.key == "k1" }.active)
    }

    @Test fun device_lost_leaves_peer_active() {
        val l = newLogic(carried = true)
        l.onPeerBle("P", true, 1_000)
        l.tick(500_000)
        assertTrue(l.peer("P").active)
    }

    @Test fun stale_ble_edge_right_after_resolve_is_ignored_then_new_episode_revives() {
        fun resolved(): LoneWorkerLogic {
            val l = newLogic(carried = true)
            l.srv("k1", "P", 1, true, 1_000, 1_000, beacon = "B1")
            l.srv("k1", "P", 1, false, 1_000, 2_000, beacon = "B1")
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
        assertNull(p.key)

        val m = resolved()
        m.onPeerBle("P", true, 32_001, 1)
        val a = m.audiblePeers().single()
        assertEquals(1, a.episode)
        assertNull(a.key)
    }

    @Test fun resolved_peers_are_pruned_after_keep_time() {
        val l = newLogic(carried = true)
        l.srv("k1", "P", 0, true, 1_000, 1_000, name = "Hong", beacon = "B1")
        l.srv("k1", "P", 0, false, 2_000, 2_000, name = "Hong", beacon = "B1")
        l.tick(2_000 + LoneWorkerPeers.RESOLVED_KEEP_MS - 1)
        assertEquals(1, l.peers.size)
        l.tick(2_000 + LoneWorkerPeers.RESOLVED_KEEP_MS)
        assertTrue(l.peers.isEmpty())
    }

    @Test fun new_server_key_is_a_new_audible_episode() {
        val l = newLogic(carried = true)
        l.srv("k1", "P", 0, true, 1_000, 1_000)
        l.ackAll(1_001)
        l.srv("k2", "P", 0, true, 2_000, 2_000)
        assertEquals(2, l.peers.size)
        assertEquals("k2", l.audiblePeers().single().key)
    }

    @Test fun older_record_does_not_touch_newer_entry() {
        val l = newLogic(carried = true)
        l.srv("k2", "P", 0, true, 3_000, 3_000)
        l.ackAll(3_001)
        l.srv("k1", "P", 0, true, 1_000, 3_500)
        assertEquals(2, l.peers.size)
        assertTrue(l.peers.single { it.key == "k2" }.silenced)
        assertTrue(l.peers.single { it.key == "k1" }.active)
    }

    @Test fun record_without_episode_never_adopts_or_ends_ble_entry() {
        val l = newLogic(carried = true)
        l.onPeerBle("P", true, 1_000)
        l.srv("k9", "P", 0, false, 500, 2_000)
        assertTrue(l.peer("P").active)

        val m = newLogic(carried = true)
        m.onPeerBle("P", true, 1_000)
        m.ackAll(1_001)
        m.srv("k1", "P", 0, true, 1_500, 1_600)
        assertEquals("k1", m.audiblePeers().single().key)
        val ble = m.peers.single { it.key == null }
        assertTrue(ble.active)
        assertTrue(ble.silenced)
        m.srv("k1", "P", 0, false, 1_500, 5_000)
        assertFalse(m.peers.single { it.key == "k1" }.active)
        assertTrue(m.audiblePeers().isEmpty())
    }

    @Test fun untracked_resolved_record_is_ignored_and_first_ble_edge_alarms() {
        val l = newLogic(carried = true)
        l.srv("k9", "P", 0, false, 1_000, 1_000, name = "Hong", beacon = "B1")
        assertTrue(l.peers.isEmpty())
        l.onPeerBle("P", true, 2_000)
        assertEquals(1, l.audiblePeers().size)
    }

    @Test fun new_ble_episode_number_is_a_new_audible_episode() {
        val l = newLogic(carried = true)
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

    @Test fun peers_are_received_while_zone_settled() {
        val l = newLogic(carried = true)
        l.onZone(true, 0)
        l.tick(70_000)
        assertTrue(l.zoneSettled)
        l.srv("k1", "P", 0, true, 71_000, 71_000, name = "Hong", beacon = "B1")
        assertEquals(1, l.audiblePeers().size)
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
        assertEquals(1, l.peers.size)
        assertTrue(l.peer("P").active)
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

    @Test fun confirm_before_entries_exist_silences_only_listed_ones() {
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

    /**
     * A server resolve time in this phone's elapsed time: from the server clock when it is known, without slack; else
     * from the wall clock, CLOCK_SLACK_MS later; no resolve time (0 or negative) gives none.
     */
    @Test fun resolved_local_time_uses_the_server_clock_or_the_wall_clock_with_slack() {
        class Row(val name: String, val resolvedAt: Long, val serverNow: Long?, val wall: Long, val now: Long,
                  val want: Long?)
        for (r in listOf(
            Row("server clock known", 970_000, 1_000_000, 5_000_000, 40_000, 10_000L),
            Row("server clock unknown", 970_000, null, 1_000_000, 40_000, 10_000L - LoneWorkerPeers.CLOCK_SLACK_MS),
            Row("resolve time 0", 0L, 1_000_000, 1_000_000, 40_000, null),
            Row("negative resolve time", -5L, null, 1_000_000, 40_000, null))) {
            assertEquals(r.name, r.want, LoneWorkerPeers.resolvedLocalMs(r.resolvedAt, r.serverNow, r.wall, r.now))
        }
    }

    // -- refused resolve is re-judged on tick --

    @Test fun untracked_resolve_refused_by_live_ad_is_retried_on_tick() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 3)
        l.onPeerBle("P", true, 20_000, 3)
        l.srv("k1", "P", 3, false, 900, 25_000, resolvedAt = 990_000, serverNow = 1_000_000)
        assertTrue(l.peer("P", 3).active)
        assertEquals(1, l.audiblePeers().size)
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

    @Test fun ack_before_ble_entry_exists_survives_server_absorption() {
        val l = meLogic()
        l.ack(1_000, "b:X#1" to "X#1")
        l.onPeerBle("X", true, 2_000, 1)
        assertTrue(l.peer("X").silenced)
        l.srv("K1", "X", 1, true, 2_500, 3_000)
        assertEquals("K1", l.peer("X").key)
        assertTrue(l.peer("X").silenced)
        assertEquals(0, l.audiblePeers().size)
    }

    @Test fun ack_before_server_entry_exists_survives_first_ble_hearing() {
        val l = meLogic()
        l.ack(1_000, "k:K1" to "X#1")
        l.srv("K1", "X", 1, true, 2_000, 2_000)
        assertTrue(l.peer("X").silenced)
        l.onPeerBle("X", true, 3_000, 1)
        assertTrue(l.peer("X").silenced)
        assertEquals(0, l.audiblePeers().size)
    }

    @Test fun ack_before_entry_exists_for_k1_does_not_silence_absorbing_k2() {
        val l = meLogic()
        l.ack(1_000, "k:K1" to "X#1")
        l.onPeerBle("X", true, 2_000, 1)
        assertTrue(l.peer("X").silenced)
        l.srv("K2", "X", 1, true, 2_500, 3_000)
        assertEquals("K2", l.audiblePeers().single().key)
    }

    @Test fun gap_30s_after_ack_rings_again() {
        val l = meLogic()
        l.onPeerBle("X", true, 1_000, 1)
        l.ack(1_100, "b:X#1" to "X#1")
        l.onPeerBle("X", true, 20_000, 1)
        assertEquals(0, l.audiblePeers().size)
        l.onPeerBle("X", true, 50_000, 1)
        assertEquals(1, l.audiblePeers().size)
        assertEquals(1_000L, l.peer("X").firstSeenMs)
    }

    /** Sound priority: own SOS siren, then the check tone, then a peer siren; the check has no vibration. */
    @Test fun check_tone_wins_over_peer_siren() {
        assertEquals(Pattern.SIREN, Pattern.of(Mode.SOS, false))
        assertEquals(Pattern.SIREN, Pattern.of(Mode.SOS, true))
        assertEquals(Pattern.CHECK, Pattern.of(Mode.CHECKING, true))
        assertEquals(Pattern.CHECK, Pattern.of(Mode.CHECKING, false))
        assertEquals(Pattern.SIREN, Pattern.of(Mode.WATCHING, true))
        assertNull(Pattern.of(Mode.WATCHING, false))

        val l = newLogic(carried = true)
        assertEquals(Mode.CHECKING, l.seenAt(180_000))
        l.onPeerBle("P", true, 181_000)
        assertEquals(Mode.CHECKING, l.seenAt(181_000))
        assertEquals(Pattern.CHECK, Pattern.of(l.mode, l.audiblePeers().isNotEmpty()))
        assertFalse(l.alarmVibrates)
        l.ackWorking(182_000)
        l.tick(182_000)
        assertEquals(Pattern.SIREN, Pattern.of(l.mode, l.audiblePeers().isNotEmpty()))
        assertTrue(l.alarmVibrates)
    }

    // A server SOS outside this phone's floor/process scope stays hidden and silent until it is heard over Bluetooth; then
    //   it is one named entry that rings and its row carries the sender's floor-proc.
    @Test fun out_of_scope_sos_stays_quiet_until_heard_on_air() {
        val l = meLogic()
        l.srv("K1", "P", 1, true, created = 5L, now = 1_000, inScope = false, floor = "2F", proc = "OB")
        assertTrue("범위 밖 기록은 목록에 없다", l.peers.isEmpty())
        assertTrue("범위 밖 기록은 울리지 않는다", l.audiblePeers().isEmpty())
        l.onPeerBle("P", true, 2_000, 1)
        assertEquals(1, l.audiblePeers().size)
        val p = l.peer("P")
        assertEquals("K1", p.key)
        assertEquals("n", p.name)
        assertTrue("행에 층·공정이 보인다", p.line(2_000).contains("2F-OB"))
    }

    // The same merge when the advert came first: the out-of-scope record only names the entry that already rings.
    @Test fun ble_first_then_out_of_scope_record_names_the_entry() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 1)
        assertEquals(1, l.audiblePeers().size)
        l.srv("K1", "P", 1, true, created = 5L, now = 2_000, inScope = false)
        assertEquals(1, l.peers.size)
        assertEquals("K1", l.peer("P").key)
        assertEquals("n", l.peer("P").name)
        assertEquals(1, l.audiblePeers().size)
    }

    // A quiet entry stays hidden when it ends; it turns normal only when a later delivery of the record is in scope.
    @Test fun quiet_entry_stays_hidden_when_resolved_and_rings_when_scope_widens() {
        val l = meLogic()
        l.srv("K1", "P", 1, true, created = 5L, now = 1_000, inScope = false)
        l.srv("K1", "P", 1, false, created = 5L, now = 2_000, resolvedAt = 6L, serverNow = 6L)
        assertTrue(l.peers.isEmpty())
        assertTrue(l.audiblePeers().isEmpty())
        l.srv("K2", "Q", 1, true, created = 5L, now = 3_000, inScope = false)
        assertTrue(l.peers.isEmpty())
        l.srv("K2", "Q", 1, true, created = 5L, now = 4_000, inScope = true)
        assertEquals(1, l.peers.size)
        assertEquals(1, l.audiblePeers().size)
    }

    // A quiet entry still runs the one-hour release (also for a record replayed after its hour), without ever showing.
    @Test fun out_of_scope_sos_runs_the_one_hour_release() {
        val l = meLogic()
        l.srv("K1", "P", 1, true, created = 5L, now = 1_000, serverNow = 5L, inScope = false)
        l.tick(1_000 + SosLedger.AUTO_RELEASE_MS)
        assertEquals(listOf("K1"), l.takeAutoReleasedPeers())
        assertTrue(l.peers.isEmpty())
        val now = 2 * SosLedger.AUTO_RELEASE_MS
        l.srv("K2", "Q", 1, true, created = 5L, now = now, serverNow = 5L + SosLedger.AUTO_RELEASE_MS, inScope = false)
        assertEquals(listOf("K2"), l.takeAutoReleasedPeers())
        assertTrue(l.peers.isEmpty())
    }

    // The same out-of-scope SOS that already ended over Bluetooth must not ring again when its (late) active server record
    //   arrives; while its adverts are still heard it rings as before, and an in-scope record rings as before.
    @Test fun out_of_scope_record_for_an_sos_that_ended_on_air_stays_quiet() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 1)
        l.onPeerBle("P", false, 2_000)
        l.tick(2_000 + LoneWorkerPeers.PEER_BLE_FALL_MS)
        assertFalse(l.peer("P").active)
        l.srv("K1", "P", 1, true, created = 5L, now = 13_000, inScope = false)
        assertTrue("끝난 범위 밖 SOS 는 다시 울리지 않는다", l.audiblePeers().isEmpty())
        assertTrue("목록에도 없다", l.peers.isEmpty())
    }

    @Test fun out_of_scope_record_for_an_sos_still_heard_on_air_rings() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 1)
        l.srv("K1", "P", 1, true, created = 5L, now = 2_000, inScope = false)
        assertEquals(1, l.audiblePeers().size)
        assertEquals("K1", l.peer("P").key)
    }

    // Leftover adverts of an out-of-scope SOS that ended on air stay silent for the guard time after the on-air end, also
    //   after its late active record took over the entry; heard again after the guard they ring.
    @Test fun leftover_advert_of_an_out_of_scope_sos_that_ended_on_air_stays_silent_within_the_guard() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 1)
        l.onPeerBle("P", false, 2_000)
        l.tick(2_000 + LoneWorkerPeers.PEER_BLE_FALL_MS)
        l.srv("K1", "P", 1, true, created = 5L, now = 13_000, inScope = false)
        l.onPeerBle("P", true, 14_000, 1)
        assertTrue("공중에서 끝난 범위 밖 SOS 의 남은 광고는 울리지 않는다", l.audiblePeers().isEmpty())
        assertTrue("목록에도 없다", l.peers.isEmpty())
    }

    @Test fun advert_of_an_out_of_scope_sos_that_ended_on_air_rings_after_the_guard() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 1)
        l.onPeerBle("P", false, 2_000)
        l.tick(2_000 + LoneWorkerPeers.PEER_BLE_FALL_MS)
        l.srv("K1", "P", 1, true, created = 5L, now = 13_000, inScope = false)
        l.onPeerBle("P", true, 2_000 + LoneWorkerPeers.PEER_BLE_FALL_MS + LoneWorkerPeers.PEER_RESOLVE_GUARD_MS + 1, 1)
        assertEquals("보호 시간 뒤 다시 들리면 울린다", 1, l.audiblePeers().size)
        assertEquals(1, l.peers.size)
        assertEquals("K1", l.peer("P").key)
    }

    @Test fun in_scope_record_for_an_sos_that_ended_on_air_rings_again() {
        val l = meLogic()
        l.onPeerBle("P", true, 1_000, 1)
        l.onPeerBle("P", false, 2_000)
        l.tick(2_000 + LoneWorkerPeers.PEER_BLE_FALL_MS)
        l.srv("K1", "P", 1, true, created = 5L, now = 13_000, inScope = true)
        assertEquals(1, l.audiblePeers().size)
    }

    // sig() is the change signature of the visible entries (id, active, silenced); quiet entries are left out.
    @Test fun peer_sig_matches_the_hash_over_visible_entries() {
        val l = meLogic()
        l.srv("K1", "P", 1, true, created = 5L, now = 1_000)
        l.srv("K2", "Q", 1, true, created = 5L, now = 1_000)
        l.silencePeers(1_500, mapOf(l.peer("Q").id to "Q#1"))
        l.srv("K3", "R", 1, true, created = 5L, now = 2_000, inScope = false)
        var h = 0
        for (p in l.peers) {
            h = h * 31 + p.id.hashCode()
            h = h * 31 + (if (p.active) 1 else 0) + (if (p.silenced) 2 else 0)
        }
        assertEquals(2, l.peers.size)
        assertEquals(h, l.peerSig())
    }
}
