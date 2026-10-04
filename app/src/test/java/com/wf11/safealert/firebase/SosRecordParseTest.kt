package com.wf11.safealert.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SOS record parsing and the receive query bound at the RTDB boundary (map input, no Firebase needed). */
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
        val r = SosRemote.parseSosRecord("k1", valid())
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
        assertEquals("u1", r.uid)
        assertTrue(r.active)
    }

    @Test
    fun resolved_isNotActive() {
        val r = SosRemote.parseSosRecord("k", valid().apply { put("status", "resolved") })
        assertFalse(r!!.active)
    }

    /** resolvedAt (server time of the resolve) is read as a number; absent or not a number = 0. */
    @Test
    fun resolvedAt_isReadOrZero() {
        val r = SosRemote.parseSosRecord("k", valid().apply { put("status", "resolved"); put("resolvedAt", 1780000600000L) })
        assertEquals(1780000600000L, r!!.resolvedAt)
        assertEquals(0L, SosRemote.parseSosRecord("k", valid())!!.resolvedAt)
        assertEquals(0L, SosRemote.parseSosRecord("k", valid().apply { put("resolvedAt", "x") })!!.resolvedAt)
    }

    @Test
    fun badRequiredFields_yieldNull() {
        assertNull(SosRemote.parseSosRecord("k", null))
        assertNull(SosRemote.parseSosRecord("k", "text"))
        assertNull(SosRemote.parseSosRecord("k", valid().apply { remove("bleId") }))
        assertNull(SosRemote.parseSosRecord("k", valid().apply { put("bleId", 5L) }))
        assertNull(SosRemote.parseSosRecord("k", valid().apply { put("bleId", "x".repeat(65)) }))
        assertNull(SosRemote.parseSosRecord("k", valid().apply { put("status", "done") }))
        assertNull(SosRemote.parseSosRecord("k", valid().apply { remove("status") }))
        assertNull(SosRemote.parseSosRecord("k", valid().apply { remove("createdAt") }))
        assertNull(SosRemote.parseSosRecord("k", valid().apply { put("createdAt", "1780000000000") }))
    }

    @Test
    fun optionalFields_normalized() {
        val long = "y".repeat(100)
        val r = SosRemote.parseSosRecord("k", valid().apply {
            put("name", long); put("role", long); put("beacon", long); put("trigger", "weird")
        })!!
        assertEquals(64, r.name.length)
        assertEquals(64, r.role.length)
        assertEquals(64, r.beacon.length)
        assertEquals("still", r.trigger)

        val r2 = SosRemote.parseSosRecord("k", valid().apply {
            remove("beacon"); remove("beaconRssi"); put("createdAt", 1780000000000.0)
        })!!
        assertEquals("", r2.beacon)
        assertNull(r2.beaconRssi)
        assertEquals(1780000000000L, r2.createdAt)
    }

    @Test
    fun payload_beaconRssiOnlyInsideRuleRange() {
        fun p(rssi: Int?) = SosRemote.recordPayload("id", "n", "WALKER", "still", "GATE-A", rssi, "u1", 1L)
        assertFalse(p(-151).containsKey("beaconRssi"))
        assertFalse(p(21).containsKey("beaconRssi"))
        assertFalse(p(null).containsKey("beaconRssi"))
        assertEquals(-150, p(-150)["beaconRssi"])
        assertEquals(20, p(20)["beaconRssi"])
    }

    @Test
    fun payload_alwaysHasCoreFields_beaconOnlyWhenNotEmpty() {
        val m = SosRemote.recordPayload("id", "n", "WALKER", "fall", "", -70, "u1", 123L)
        assertFalse(m.containsKey("beacon"))
        assertFalse(SosRemote.recordPayload("id", "n", "WALKER", "fall", null, -70, "u1", 123L).containsKey("beacon"))
        assertEquals("id", m["bleId"])
        assertEquals("n", m["name"])
        assertEquals("WALKER", m["role"])
        assertEquals("fall", m["trigger"])
        assertEquals(123L, m["createdAt"])
        assertEquals("active", m["status"])
        assertEquals("u1", m["uid"])
        assertEquals("GATE-A", SosRemote.recordPayload("id", "n", "R", "still", "GATE-A", null, "u", 1L)["beacon"])
    }

    @Test
    fun episode_round_trips_and_out_of_range_is_dropped() {
        assertEquals(7, SosRemote.recordPayload("id", "n", "WALKER", "still", null, null, "u1", 1L, 7)["ep"])
        assertFalse(SosRemote.recordPayload("id", "n", "WALKER", "still", null, null, "u1", 1L, 0).containsKey("ep"))
        assertFalse(SosRemote.recordPayload("id", "n", "WALKER", "still", null, null, "u1", 1L, 256).containsKey("ep"))
        assertFalse(SosRemote.recordPayload("id", "n", "WALKER", "still", null, null, "u1", 1L).containsKey("ep"))
        assertEquals(7, SosRemote.parseSosRecord("k", valid().apply { put("ep", 7L) })!!.ep)
        assertEquals(0, SosRemote.parseSosRecord("k", valid())!!.ep)
        assertEquals(0, SosRemote.parseSosRecord("k", valid().apply { put("ep", 300L) })!!.ep)
        assertEquals(0, SosRemote.parseSosRecord("k", valid().apply { put("ep", "7") })!!.ep)
    }

    // A worker who fell before the crew's phones started is still waiting for rescue: a phone that starts (or restarts
    //   after an update or OS kill) hours later in the shift must still query that unresolved request.
    @Test
    fun replay_reachesARequestFromEarlierInTheShift() {
        val start = 1_780_000_000_000L
        val createdAt = start - 11 * 3_600_000L   // 11 h before the phone started listening
        assertTrue(createdAt >= SosRemote.replayStartAt(start, 0L))
    }
}
