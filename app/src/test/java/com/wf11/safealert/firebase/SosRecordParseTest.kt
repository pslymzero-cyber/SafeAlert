package com.wf11.safealert.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.1.99 SOS record parsing at the RTDB snapshot boundary (map input, no Firebase needed). */
class SosRecordParseTest {

    private fun valid(): MutableMap<String, Any?> = mutableMapOf(
        "bleId" to "SAFEALERT_WALKER_W01",
        "name" to "W01",
        "role" to "WALKER",
        "trigger" to "fall",
        "beacon" to "GATE-A",
        "beaconRssi" to -70L,
        "createdAt" to 1780000000000L,
        "status" to "active",
        "uid" to "u1"
    )

    @Test
    fun validMap_keepsFields() {
        val r = FirebaseManager.parseSosRecord("k1", valid())
        assertNotNull(r)
        r!!
        assertEquals("k1", r.key)
        assertEquals("SAFEALERT_WALKER_W01", r.bleId)
        assertEquals("W01", r.name)
        assertEquals("WALKER", r.role)
        assertEquals("fall", r.trigger)
        assertEquals("GATE-A", r.beacon)
        assertEquals(-70, r.beaconRssi)
        assertEquals(1780000000000L, r.createdAt)
        assertTrue(r.active)
    }

    @Test
    fun resolved_isNotActive() {
        val r = FirebaseManager.parseSosRecord("k", valid().apply { put("status", "resolved") })
        assertFalse(r!!.active)
    }

    @Test
    fun badRequiredFields_yieldNull() {
        assertNull(FirebaseManager.parseSosRecord("k", null))
        assertNull(FirebaseManager.parseSosRecord("k", "text"))
        assertNull(FirebaseManager.parseSosRecord("k", valid().apply { remove("bleId") }))
        assertNull(FirebaseManager.parseSosRecord("k", valid().apply { put("bleId", 5L) }))
        assertNull(FirebaseManager.parseSosRecord("k", valid().apply { put("bleId", "x".repeat(65)) }))
        assertNull(FirebaseManager.parseSosRecord("k", valid().apply { put("status", "done") }))
        assertNull(FirebaseManager.parseSosRecord("k", valid().apply { remove("status") }))
        assertNull(FirebaseManager.parseSosRecord("k", valid().apply { remove("createdAt") }))
        assertNull(FirebaseManager.parseSosRecord("k", valid().apply { put("createdAt", "1780000000000") }))
    }

    @Test
    fun optionalFields_normalized() {
        val long = "y".repeat(100)
        val r = FirebaseManager.parseSosRecord("k", valid().apply {
            put("name", long); put("role", long); put("beacon", long); put("trigger", "weird")
        })!!
        assertEquals(64, r.name.length)
        assertEquals(64, r.role.length)
        assertEquals(64, r.beacon.length)
        assertEquals("still", r.trigger)

        val r2 = FirebaseManager.parseSosRecord("k", valid().apply {
            remove("beacon"); remove("beaconRssi"); put("createdAt", 1780000000000.0)
        })!!
        assertEquals("", r2.beacon)
        assertNull(r2.beaconRssi)
        assertEquals(1780000000000L, r2.createdAt)
    }
}
