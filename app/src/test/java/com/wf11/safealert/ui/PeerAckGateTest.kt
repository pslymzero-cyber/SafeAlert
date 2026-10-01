package com.wf11.safealert.ui

import com.wf11.safealert.service.PeerRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen confirm silences only the rows it last drew, and ignores a tap right after the active item set changed. */
class PeerAckGateTest {

    private fun row(epId: String, active: Boolean = true, key: String? = null) =
        PeerRow(if (key != null) "k:$key" else "b:$epId", epId, "", active)

    private fun rows(vararg ids: String, activeIds: Set<String> = ids.toSet()) =
        ids.map { row(it, it in activeIds) }

    private fun ids(r: List<PeerRow>?) = r?.map { it.epId }

    @Test fun tap_after_settle_returns_last_drawn_ids() {
        val g = PeerAckGate()
        g.onRender(rows("A#1", "B#2"), 0L)
        assertEquals(listOf("A#1", "B#2"), ids(g.onTap(PeerAckGate.SETTLE_MS)))
    }

    @Test fun tap_within_settle_after_change_is_ignored() {
        val g = PeerAckGate()
        g.onRender(rows("A#1"), 0L)
        assertEquals(listOf("A#1"), ids(g.onTap(800L)))
        g.onRender(rows("A#1", "B#2"), 1_000L)
        assertNull(g.onTap(1_500L))
        assertEquals(listOf("A#1", "B#2"), ids(g.onTap(1_700L)))
    }

    @Test fun same_active_set_with_new_shown_ids_does_not_restart_delay() {
        val g = PeerAckGate()
        g.onRender(rows("A#1"), 0L)
        g.onRender(rows("A#1", "R#2", activeIds = setOf("A#1")), 1_000L)
        assertEquals(listOf("A#1", "R#2"), ids(g.onTap(1_100L)))
    }

    @Test fun lost_active_ids_restart_delay() {
        val g = PeerAckGate()
        g.onRender(rows("A#1", "B#2"), 0L)
        g.onRender(rows("A#1", "B#2", activeIds = setOf("A#1")), 1_000L)
        assertNull(g.onTap(1_200L))
        assertEquals(listOf("A#1", "B#2"), ids(g.onTap(1_700L)))
    }

    @Test fun never_returns_empty_means_all() {
        val g = PeerAckGate()
        assertEquals(emptyList<String>(), ids(g.onTap(0L)))
        assertEquals(emptyList<String>(), ids(g.onTap(Long.MAX_VALUE)))
        g.onRender(emptyList(), 5_000L)
        assertEquals(emptyList<String>(), ids(g.onTap(5_001L)))
    }

    @Test fun screen_change_with_same_peers_opens_settle_window() {
        // own check window closed by the first tap: the same button now acks peers, so a quick second tap must not pass
        val g = PeerAckGate()
        g.onRender(rows("A#1"), 0L, "CHECKING:true")
        assertTrue(g.settled(800L))
        g.onRender(rows("A#1"), 1_000L, "WATCHING:true")
        assertFalse(g.settled(1_300L))
        assertNull(g.onTap(1_300L))
        assertTrue(g.settled(1_700L))
        assertEquals(listOf("A#1"), ids(g.onTap(1_700L)))
        g.onRender(rows("A#1"), 1_800L, "WATCHING:true")
        assertTrue(g.settled(1_800L))
    }

    @Test fun new_item_with_same_epid_opens_settle_window() {
        val g = PeerAckGate()
        g.onRender(listOf(row("X#1", key = "K1")), 0L)
        assertEquals(listOf("k:K1"), g.onTap(800L)?.map { it.id })
        g.onRender(listOf(row("X#1", key = "K1"), row("X#1", key = "K2")), 1_000L)
        assertNull(g.onTap(1_500L))
        assertEquals(listOf("k:K1", "k:K2"), g.onTap(1_700L)?.map { it.id })
    }
}
