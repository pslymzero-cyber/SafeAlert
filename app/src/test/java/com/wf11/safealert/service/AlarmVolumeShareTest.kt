package com.wf11.safealert.service

import com.wf11.safealert.service.AlarmVolumeShare.Restore
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmVolumeShareTest {

    @Test
    fun collision_target_never_lowers_while_sos_sounds() {
        assertEquals(7, AlarmVolumeShare.collisionTarget(4, 7, true))
        assertEquals(4, AlarmVolumeShare.collisionTarget(4, 7, false))
        assertEquals(7, AlarmVolumeShare.collisionTarget(7, 3, true))
    }

    /** One restoreAction case. isFinal = null calls without the argument, so its default stays exercised. */
    private class RestoreRow(
        val label: String, val cur: Int?, val orig: Int, val ours: Int, val nowMs: Long, val collisionAtMs: Long,
        val isFinal: Boolean?, val want: Restore
    )

    /**
     * Restore only while the volume is still the one we set and both the original and ours are known; within the
     * collision hold wait, unless this is the final stop.
     */
    @Test
    fun restore_only_while_still_ours_and_after_the_collision_hold_unless_final() {
        val rows = listOf(
            RestoreRow("still ours, collision 11 s ago", 7, 3, 7, 100_000, 89_000, null, Restore.RESTORE),
            RestoreRow("changed by someone else", 6, 3, 7, 100_000, 0, null, Restore.DROP),
            RestoreRow("volume back at the original", 3, 3, 3, 100_000, 0, null, Restore.DROP),
            RestoreRow("current volume unknown", null, 3, 7, 100_000, 0, null, Restore.DROP),
            RestoreRow("our volume unknown", 7, 3, -1, 100_000, 0, null, Restore.DROP),
            RestoreRow("original volume unknown", 7, -1, 7, 100_000, 0, null, Restore.DROP),
            // someone else changed it during a recent collision: drop, never wait
            RestoreRow("changed by someone else 1 s after a collision", 5, 3, 7, 100_000, 99_000, null, Restore.DROP),
            RestoreRow("final stop 1 s after a collision", 7, 3, 7, 100_000, 99_000, true, Restore.RESTORE),
            RestoreRow("non-final stop 1 s after a collision", 7, 3, 7, 100_000, 99_000, false, Restore.WAIT),
            RestoreRow("stop 3 s after a collision", 7, 3, 7, 100_000, 97_000, null, Restore.WAIT),
            RestoreRow("final stop, changed by someone else", 5, 3, 7, 100_000, 99_000, true, Restore.DROP)
        )
        for ((i, r) in rows.withIndex()) {
            val got = when (val f = r.isFinal) {
                null -> AlarmVolumeShare.restoreAction(
                    cur = r.cur, orig = r.orig, ours = r.ours, nowMs = r.nowMs, collisionAtMs = r.collisionAtMs)
                else -> AlarmVolumeShare.restoreAction(
                    cur = r.cur, orig = r.orig, ours = r.ours, nowMs = r.nowMs, collisionAtMs = r.collisionAtMs, final = f)
            }
            assertEquals("row $i ${r.label}", r.want, got)
        }
    }
}
