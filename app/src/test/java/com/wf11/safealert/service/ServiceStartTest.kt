package com.wf11.safealert.service

import android.Manifest
import android.app.ApplicationExitInfo
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStartTest {

    private val nearby = listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT
    )
    private val cd = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    private val user = ApplicationExitInfo.REASON_USER_REQUESTED
    private val update = ApplicationExitInfo.REASON_PACKAGE_UPDATED

    @Test
    fun required_is_nearby_devices_only_on_31_plus() {
        assertTrue(ServiceStartGate.required(30).isEmpty())
        for (sdk in listOf(31, 34)) {
            val r = ServiceStartGate.required(sdk).toList()
            assertEquals(nearby.toSet(), r.toSet())
            assertFalse(r.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    @Test
    fun fgs_type_adds_location_only_on_29_30_with_fine() {
        assertEquals(cd or loc, ServiceStartGate.fgsType(29, true))
        assertEquals(cd or loc, ServiceStartGate.fgsType(30, true))
        assertEquals(cd, ServiceStartGate.fgsType(30, false))
        assertEquals(cd, ServiceStartGate.fgsType(31, true))
        assertEquals(cd, ServiceStartGate.fgsType(34, true))
    }

    private fun stopped(sdk: Int, exits: List<Pair<Int, Long>>, since: Long = 1_000L, updatedAt: Long = 10_000_000L) =
        BootRestoreReceiver.userStopped(sdk, exits, since, updatedAt)

    @Test
    fun exit_before_start_is_ignored_and_below_api30_never_stops() {
        assertFalse(stopped(29, listOf(user to 2_000L)))
        assertTrue(stopped(30, listOf(user to 2_000L)))
        assertFalse(stopped(30, listOf(user to 500L)))
        assertFalse(stopped(30, listOf(ApplicationExitInfo.REASON_LOW_MEMORY to 2_000L)))
        assertFalse(stopped(30, emptyList()))
    }

    @Test
    fun missing_start_key_skips_judgement_and_restores() {
        assertFalse(stopped(34, listOf(user to 2_000L), since = 0L))
        assertFalse(stopped(30, listOf(user to 2_000L), since = 0L))
    }

    @Test
    fun api30_to_33_update_kill_within_60s_of_update_is_not_user_stop() {
        for (sdk in listOf(30, 33)) {
            assertFalse(stopped(sdk, listOf(user to 100_000L), updatedAt = 160_000L))
            assertFalse(stopped(sdk, listOf(user to 100_000L), updatedAt = 40_000L))
            assertFalse(stopped(sdk, listOf(update to 100_000L, user to 130_000L), updatedAt = 100_000L))
        }
    }

    @Test
    fun api30_user_stop_61s_away_from_update_counts() {
        assertTrue(stopped(30, listOf(user to 100_000L), updatedAt = 160_001L))
        assertTrue(stopped(33, listOf(user to 100_000L), updatedAt = 39_999L))
    }

    @Test
    fun api34_user_stop_after_start_counts() {
        assertTrue(stopped(34, listOf(user to 100_000L), updatedAt = 100_000L))
        assertTrue(stopped(34, listOf(update to 3_000L, user to 2_000L)))
        assertFalse(stopped(34, listOf(update to 2_000L)))
    }

    @Test
    fun screen_permissions_contain_every_start_permission() {
        for (sdk in listOf(30, 31, 34)) {
            val s = ServiceStartGate.screenPermissions(sdk).toSet()
            assertTrue(s.containsAll(ServiceStartGate.required(sdk).toList()))
            assertTrue(s.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    @Test
    fun bg_location_limit_only_for_background_started_api30() {
        assertTrue(ServiceStartGate.bgLocationLimited(30, fine = true, background = false, bgStarted = true))
        assertFalse(ServiceStartGate.bgLocationLimited(30, fine = true, background = false, bgStarted = false))
        assertFalse(ServiceStartGate.bgLocationLimited(30, fine = true, background = true, bgStarted = true))
        assertFalse(ServiceStartGate.bgLocationLimited(29, fine = true, background = false, bgStarted = true))
        assertFalse(ServiceStartGate.bgLocationLimited(31, fine = true, background = false, bgStarted = true))
    }

    @Test
    fun retype_needed_when_fine_granted_later_only_on_api29_30() {
        assertTrue(ServiceStartGate.needsRetype(29, cd, true))
        assertTrue(ServiceStartGate.needsRetype(30, cd, true))
        assertFalse(ServiceStartGate.needsRetype(30, cd or loc, true))
        assertFalse(ServiceStartGate.needsRetype(31, cd, true))
    }
}
