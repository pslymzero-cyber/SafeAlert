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

    @Test
    fun user_stop_below_30_never_counts() {
        assertFalse(BootRestoreReceiver.userStopped(29, false, listOf(user to 2_000L), 1_000L))
    }

    @Test
    fun boot_after_newer_user_stop_does_not_restore() {
        assertTrue(BootRestoreReceiver.userStopped(30, false, listOf(user to 2_000L), 1_000L))
        assertFalse(BootRestoreReceiver.userStopped(30, false, listOf(user to 500L), 1_000L))
        assertFalse(BootRestoreReceiver.userStopped(30, false, listOf(ApplicationExitInfo.REASON_LOW_MEMORY to 2_000L), 1_000L))
        assertFalse(BootRestoreReceiver.userStopped(30, false, emptyList(), 1_000L))
    }

    @Test
    fun package_replaced_checks_only_on_34_and_ignores_update_exits() {
        assertFalse(BootRestoreReceiver.userStopped(33, true, listOf(user to 2_000L), 1_000L))
        assertTrue(BootRestoreReceiver.userStopped(34, true, listOf(update to 3_000L, user to 2_000L), 1_000L))
        assertFalse(BootRestoreReceiver.userStopped(34, true, listOf(update to 2_000L), 1_000L))
    }
}
