package com.wf11.safealert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmVolumeShareTest {

    @Test
    fun collision_target_never_lowers_while_sos_sounds() {
        assertEquals(7, AlarmVolumeShare.collisionTarget(4, 7, true))
        assertEquals(4, AlarmVolumeShare.collisionTarget(4, 7, false))
        assertEquals(7, AlarmVolumeShare.collisionTarget(7, 3, true))
    }

    @Test
    fun restore_needs_volume_still_ours() {
        assertTrue(AlarmVolumeShare.mayRestore(7, 7, -1, 5, 100_000, 99_000))
        assertFalse(AlarmVolumeShare.mayRestore(6, 7, -1, 5, 100_000, 0))
        assertFalse(AlarmVolumeShare.mayRestore(6, 7, 3, 3, 100_000, 0))
    }

    @Test
    fun same_process_collision_change_blocks_restore() {
        assertTrue(AlarmVolumeShare.mayRestore(7, 7, 3, 3, 100_000, 80_000))
        assertFalse(AlarmVolumeShare.mayRestore(7, 7, 3, 4, 100_000, 80_000))
        assertFalse(AlarmVolumeShare.mayRestore(7, 7, 3, 3, 100_000, 95_000))
    }

    @Test
    fun collision_noted_only_when_volume_changed() {
        val g = AlarmVolumeShare.collisionGen
        AlarmVolumeShare.noteCollisionIfChanged(5, 5, 1_000)
        assertEquals(g, AlarmVolumeShare.collisionGen)
        AlarmVolumeShare.noteCollisionIfChanged(5, 7, 2_000)
        assertEquals(g + 1, AlarmVolumeShare.collisionGen)
        assertEquals(2_000L, AlarmVolumeShare.collisionAtMs)
    }
}
