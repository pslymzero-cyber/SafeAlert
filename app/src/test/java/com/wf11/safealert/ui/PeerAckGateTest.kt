package com.wf11.safealert.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The screen confirm silences only the ids it last drew, and ignores a tap right after the active set changed. */
class PeerAckGateTest {

    @Test fun tap_after_settle_returns_last_drawn_ids() {
        val g = PeerAckGate()
        g.onRender(listOf("A#1", "B#2"), setOf("A#1", "B#2"), 0L)
        assertEquals(listOf("A#1", "B#2"), g.onTap(PeerAckGate.SETTLE_MS))
    }

    @Test fun tap_within_settle_after_change_is_ignored() {
        val g = PeerAckGate()
        g.onRender(listOf("A#1"), setOf("A#1"), 0L)
        assertEquals(listOf("A#1"), g.onTap(800L))
        g.onRender(listOf("A#1", "B#2"), setOf("A#1", "B#2"), 1_000L)
        assertNull(g.onTap(1_500L))
        assertEquals(listOf("A#1", "B#2"), g.onTap(1_700L))
    }

    @Test fun same_active_set_with_new_shown_ids_does_not_restart_delay() {
        val g = PeerAckGate()
        g.onRender(listOf("A#1"), setOf("A#1"), 0L)
        g.onRender(listOf("A#1", "R#2"), setOf("A#1"), 1_000L)
        assertEquals(listOf("A#1", "R#2"), g.onTap(1_100L))
    }

    @Test fun lost_active_ids_restart_delay() {
        val g = PeerAckGate()
        g.onRender(listOf("A#1", "B#2"), setOf("A#1", "B#2"), 0L)
        g.onRender(listOf("A#1", "B#2"), setOf("A#1"), 1_000L)
        assertNull(g.onTap(1_200L))
        assertEquals(listOf("A#1", "B#2"), g.onTap(1_700L))
    }

    @Test fun never_returns_empty_means_all() {
        val g = PeerAckGate()
        assertEquals(emptyList<String>(), g.onTap(0L))
        assertEquals(emptyList<String>(), g.onTap(Long.MAX_VALUE))
        g.onRender(emptyList(), emptySet(), 5_000L)
        assertEquals(emptyList<String>(), g.onTap(5_001L))
    }
}
