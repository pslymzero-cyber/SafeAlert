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
    fun restore_only_without_collision_volume_since_raise() {
        assertFalse(AlarmVolumeShare.mayRestore(-1, 0, 100_000, -10_000))
        assertTrue(AlarmVolumeShare.mayRestore(3, 3, 100_000, 80_000))
        assertFalse(AlarmVolumeShare.mayRestore(3, 4, 100_000, 80_000))
        assertFalse(AlarmVolumeShare.mayRestore(3, 3, 100_000, 95_000))
    }
}
