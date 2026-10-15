package com.wf11.safealert.ui

import com.wf11.safealert.service.PeerRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen confirm silences only the rows it last drew, and ignores a tap right after the active item set changed. */
class PeerAckGateTest {

    // A row's epId is "<peer>#<episode>" ("A#1" = peer A, SOS episode 1); times are in ms.
    private fun row(epId: String, active: Boolean = true, key: String? = null) =
        PeerRow(if (key != null) "k:$key" else "b:$epId", epId, "", active)

    private fun rows(vararg ids: String, activeIds: Set<String> = ids.toSet()) =
        ids.map { row(it, it in activeIds) }

    private fun ids(r: List<PeerRow>?) = r?.map { it.id }

    /** render [first] @0 -> tap @800 (settled, draws [first]) -> render [second] @1000 -> each tap: time to the ids it returns (null = ignored). */
    private class Row(
        val label: String, val first: List<PeerRow>, val second: List<PeerRow>, val taps: List<Pair<Long, List<String>?>>
    )

    @Test fun tap_within_settle_after_change_is_ignored() {
        val table = listOf(
            Row("활성 집합이 바뀌면 설정 지연", rows("A#1"), rows("A#1", "B#2"),
                listOf(1_500L to null, 1_000L + PeerAckGate.SETTLE_MS to listOf("b:A#1", "b:B#2"))),
            // same active set with new shown ids does not restart the delay
            Row("활성 집합이 같고 비활성 행만 늘면 지연 재시작 없음", rows("A#1"),
                rows("A#1", "R#2", activeIds = setOf("A#1")), listOf(1_100L to listOf("b:A#1", "b:R#2"))),
            Row("활성이던 id 를 잃으면 지연 재시작", rows("A#1", "B#2"), rows("A#1", "B#2", activeIds = setOf("A#1")),
                listOf(1_200L to null, 1_700L to listOf("b:A#1", "b:B#2"))),
            // new item with the same epId but another key opens the window (compare PeerRow.id, not epId)
            Row("같은 epId 의 새 키 항목은 지연을 연다", listOf(row("X#1", key = "K1")),
                listOf(row("X#1", key = "K1"), row("X#1", key = "K2")),
                listOf(1_500L to null, 1_700L to listOf("k:K1", "k:K2")))
        )
        for ((i, r) in table.withIndex()) {
            val at = "row $i ${r.label}"
            val g = PeerAckGate()
            g.onRender(r.first, 0L)
            assertEquals("$at: 처음 그린 행은 800ms 뒤 탭이 통과", r.first.map { it.id }, ids(g.onTap(800L)))
            g.onRender(r.second, 1_000L)
            for ((t, want) in r.taps) assertEquals("$at: tap@$t", want, ids(g.onTap(t)))
        }
    }

    @Test fun tap_with_no_rows_drawn_silences_no_one() {
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
        assertEquals(listOf("b:A#1"), ids(g.onTap(1_700L)))
        g.onRender(rows("A#1"), 1_800L, "WATCHING:true")
        assertTrue(g.settled(1_800L))
    }
}
