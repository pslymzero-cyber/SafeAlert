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

    @Test
    fun restore_when_still_ours_and_collision_old() {
        assertEquals(Restore.RESTORE, AlarmVolumeShare.restoreAction(cur = 7, orig = 3, ours = 7, nowMs = 100_000, collisionAtMs = 89_000))
    }

    @Test
    fun drop_when_not_ours_or_unknown() {
        assertEquals(Restore.DROP, AlarmVolumeShare.restoreAction(cur = 6, orig = 3, ours = 7, nowMs = 100_000, collisionAtMs = 0))
        assertEquals(Restore.DROP, AlarmVolumeShare.restoreAction(cur = 3, orig = 3, ours = 3, nowMs = 100_000, collisionAtMs = 0))
        assertEquals(Restore.DROP, AlarmVolumeShare.restoreAction(cur = null, orig = 3, ours = 7, nowMs = 100_000, collisionAtMs = 0))
        assertEquals(Restore.DROP, AlarmVolumeShare.restoreAction(cur = 7, orig = 3, ours = -1, nowMs = 100_000, collisionAtMs = 0))
        assertEquals(Restore.DROP, AlarmVolumeShare.restoreAction(cur = 7, orig = -1, ours = 7, nowMs = 100_000, collisionAtMs = 0))
        // someone else changed it during a recent collision: drop, never wait
        assertEquals(Restore.DROP, AlarmVolumeShare.restoreAction(cur = 5, orig = 3, ours = 7, nowMs = 100_000, collisionAtMs = 99_000))
    }

    @Test
    fun final_stop_restores_even_within_collision_hold() {
        assertEquals(Restore.RESTORE, AlarmVolumeShare.restoreAction(cur = 7, orig = 3, ours = 7, nowMs = 100_000, collisionAtMs = 99_000, final = true))
        assertEquals(Restore.WAIT, AlarmVolumeShare.restoreAction(cur = 7, orig = 3, ours = 7, nowMs = 100_000, collisionAtMs = 99_000, final = false))
        assertEquals(Restore.WAIT, AlarmVolumeShare.restoreAction(cur = 7, orig = 3, ours = 7, nowMs = 100_000, collisionAtMs = 97_000))
        assertEquals(Restore.DROP, AlarmVolumeShare.restoreAction(cur = 5, orig = 3, ours = 7, nowMs = 100_000, collisionAtMs = 99_000, final = true))
    }
}
