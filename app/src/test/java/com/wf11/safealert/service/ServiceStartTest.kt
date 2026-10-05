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

    /** One userStopped case: exits are (reason, time ms); since = the last start (0 = no start key), updatedAt = the update time. */
    private class StopRow(
        val label: String, val sdk: Int, val exits: List<Pair<Int, Long>>,
        val since: Long = 1_000L, val updatedAt: Long = 10_000_000L, val stopped: Boolean
    )

    /**
     * From API 30 a user-requested exit after the last start means the user stopped the service. Below API 30, without
     * a start key, for other exit reasons or for exits before the start it never does. On API 30-33 a user exit within
     * 60 s of an update made after the last start is the update's own kill; API 34 needs no such slack.
     */
    @Test
    fun user_exit_after_last_start_is_a_stop_except_below_api30_or_near_an_update_on_api30_33() {
        val rows = ArrayList<StopRow>()
        rows += StopRow("api29 user exit", 29, listOf(user to 2_000L), stopped = false)
        rows += StopRow("api30 user exit after the start", 30, listOf(user to 2_000L), stopped = true)
        rows += StopRow("api30 user exit before the start", 30, listOf(user to 500L), stopped = false)
        rows += StopRow("api30 low-memory exit", 30, listOf(ApplicationExitInfo.REASON_LOW_MEMORY to 2_000L), stopped = false)
        rows += StopRow("api30 no exit records", 30, emptyList(), stopped = false)
        rows += StopRow("api34 no start key", 34, listOf(user to 2_000L), since = 0L, stopped = false)
        rows += StopRow("api30 no start key", 30, listOf(user to 2_000L), since = 0L, stopped = false)
        for (sdk in listOf(30, 33)) {
            rows += StopRow("api$sdk user exit 60 s before an update", sdk, listOf(user to 100_000L),
                updatedAt = 160_000L, stopped = false)
            rows += StopRow("api$sdk user exit 60 s after an update", sdk, listOf(user to 100_000L),
                updatedAt = 40_000L, stopped = false)
            rows += StopRow("api$sdk update exit, then a user exit 30 s later", sdk,
                listOf(update to 100_000L, user to 130_000L), updatedAt = 100_000L, stopped = false)
        }
        rows += StopRow("api30 user exit 60.001 s before an update", 30, listOf(user to 100_000L),
            updatedAt = 160_001L, stopped = true)
        rows += StopRow("api33 user exit 60.001 s after an update", 33, listOf(user to 100_000L),
            updatedAt = 39_999L, stopped = true)
        rows += StopRow("api34 user exit at the update time", 34, listOf(user to 100_000L),
            updatedAt = 100_000L, stopped = true)
        rows += StopRow("api34 user exit and a later update exit", 34, listOf(update to 3_000L, user to 2_000L), stopped = true)
        rows += StopRow("api34 update exit only", 34, listOf(update to 2_000L), stopped = false)
        for (sdk in listOf(30, 33)) {
            rows += StopRow("api$sdk update after the last start, user exit 10 s later", sdk, listOf(user to 100_000L),
                since = 50_000L, updatedAt = 90_000L, stopped = false)
            rows += StopRow("api$sdk update at the last start, user exit 10 s later", sdk, listOf(user to 100_000L),
                since = 90_000L, updatedAt = 90_000L, stopped = true)
            rows += StopRow("api$sdk update before the last start, user exit 10 s later", sdk, listOf(user to 100_000L),
                since = 95_000L, updatedAt = 90_000L, stopped = true)
        }
        for ((i, r) in rows.withIndex()) {
            assertEquals("row $i ${r.label}", r.stopped, BootRestoreReceiver.userStopped(r.sdk, r.exits, r.since, r.updatedAt))
        }
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
    fun started_at_falls_back_to_running_since_only_on_api34() {
        assertEquals(7_000L, BootRestoreReceiver.startedAt(0L, 7_000L, 34))
        assertEquals(0L, BootRestoreReceiver.startedAt(0L, 7_000L, 33))
        assertEquals(9_000L, BootRestoreReceiver.startedAt(9_000L, 7_000L, 30))
        assertEquals(0L, BootRestoreReceiver.startedAt(0L, 0L, 34))
    }

    // Limited only on API 30 with fine location, started from the background, without "always allow"; API 29 and 31+
    //   are never limited. The "always allow" probe is asked only when every other condition holds.
    @Test
    fun bg_location_limit_only_for_background_started_api30_and_asks_permission_only_then() {
        var asked = 0
        val probe = { asked++; false }
        assertFalse(ServiceStartGate.bgLocationLimited(31, true, true, probe))
        assertFalse(ServiceStartGate.bgLocationLimited(29, true, true, probe))
        assertFalse(ServiceStartGate.bgLocationLimited(30, true, false, probe))
        assertFalse(ServiceStartGate.bgLocationLimited(30, false, true, probe))
        assertEquals(0, asked)
        assertTrue(ServiceStartGate.bgLocationLimited(30, true, true, probe))
        assertEquals(1, asked)
        assertFalse(ServiceStartGate.bgLocationLimited(30, true, true) { asked++; true })
        assertEquals(2, asked)
    }

    @Test
    fun retype_needed_when_fine_granted_later_only_on_api29_30() {
        assertTrue(ServiceStartGate.needsRetype(29, cd, true))
        assertTrue(ServiceStartGate.needsRetype(30, cd, true))
        assertFalse(ServiceStartGate.needsRetype(30, cd or loc, true))
        assertFalse(ServiceStartGate.needsRetype(31, cd, true))
    }
}
