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
        assertEquals(Restore.RESTORE, AlarmVolumeShare.restoreAction(7, 3, 7, 100_000, 89_000))
    }

    @Test
    fun wait_while_collision_recent() {
        assertEquals(Restore.WAIT, AlarmVolumeShare.restoreAction(7, 3, 7, 100_000, 97_000))
    }

    @Test
    fun drop_when_not_ours_or_unknown() {
        assertEquals(Restore.DROP, AlarmVolumeShare.restoreAction(6, 3, 7, 100_000, 0))
        assertEquals(Restore.DROP, AlarmVolumeShare.restoreAction(3, 3, 3, 100_000, 0))
        assertEquals(Restore.DROP, AlarmVolumeShare.restoreAction(null, 3, 7, 100_000, 0))
        assertEquals(Restore.DROP, AlarmVolumeShare.restoreAction(7, 3, -1, 100_000, 0))
        assertEquals(Restore.DROP, AlarmVolumeShare.restoreAction(7, -1, 7, 100_000, 0))
        // someone else changed it during a recent collision: drop, never wait
        assertEquals(Restore.DROP, AlarmVolumeShare.restoreAction(5, 3, 7, 100_000, 99_000))
    }

    @Test
    fun note_collision_records_every_request() {
        AlarmVolumeShare.noteCollision(1_000)
        assertEquals(1_000L, AlarmVolumeShare.collisionAtMs)
        AlarmVolumeShare.noteCollision(2_000)
        assertEquals(2_000L, AlarmVolumeShare.collisionAtMs)
    }
}
