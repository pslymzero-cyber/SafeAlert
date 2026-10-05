package com.wf11.safealert.ble

import com.wf11.safealert.service.BleService
import com.wf11.safealert.support.BleServiceTestHarness
import com.wf11.safealert.support.BleServiceTestHarness.asmOf
import com.wf11.safealert.support.BleServiceTestHarness.fieldOf
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Simulation: the approach-streak state (start time and APPROACH_STREAK_GRACE_MS grace time) after release or loss.
 * The AlertStateMachine maps are reached through reflection that fails the test when a field is missing or renamed
 * (no fallbacks). Measurement output format: "[StreakCleanup] <scenario> key=value".
 * Expected values follow AlertStateMachine.kt (the map cleanup on release and the registry purge on loss): release or
 * device loss leaves no stale start or grace time for a re-approach. The grace itself (a non-approach gap of up to 300ms
 * keeps the streak without delaying confirmation) is covered by SpecialAlertTimeGateTest.
 */
@RunWith(RobolectricTestRunner::class)
class ApproachStreakCleanupTest {

    private val id = "SA-SIM-A2"
    private val h = BleServiceTestHarness

    private fun out(s: String, kv: String) = println("[StreakCleanup] $s $kv")
    private fun na(v: Any?) = v?.toString() ?: "none"

    private fun starts(asm: Any): MutableMap<String, Long> = fieldOf(asm, "approachStreakStartMap")
    private fun lastSeen(asm: Any): MutableMap<String, Long> = fieldOf(asm, "approachLastSeenMap")

    /** Holds -45 until the device alerts; returns the alert time (null if it never alerts within 120 frames). */
    private fun alertSteady(svc: BleService, startMs: Long): Long? {
        var now = startMs
        repeat(120) {
            h.callProcessAlert(svc, id, -45, nowMs = now)
            if (h.alertLevelOf(svc, id) != null) return now
            now += 120L
        }
        return null
    }

    private data class Rel(val releaseMs: Long?, val path: String, val startLeft: Boolean, val lastSeenLeft: Boolean)

    /**
     * Releases a device that is alerting. Before every frame, plants a 'recent approach' trace (start=now-2000, lastSeen=now-100).
     */
    private fun release(svc: BleService, mode: String, fromMs: Long): Rel {
        val asm = asmOf(svc)
        var now = fromMs
        for (k in 0 until 80) {
            starts(asm)[id] = now - 2_000L
            lastSeen(asm)[id] = now - 100L
            if (mode == "peerInZone") fieldOf<MutableMap<String, Boolean>>(asm, "peerInZoneMap")[id] = true
            val rssi = when (mode) { "drop" -> -95; "ramp" -> maxOf(-45 - k, -95); else -> -45 }
            h.callProcessAlert(svc, id, rssi, nowMs = now)
            if (h.alertLevelOf(svc, id) == null) {
                val dep = fieldOf<Map<String, Long>>(asm, "departingStartMap")[id]
                val path = if (dep == now) "recede" else "safe"
                return Rel(now, path, starts(asm).containsKey(id), lastSeen(asm).containsKey(id))
            }
            now += 120L
        }
        return Rel(null, "none", starts(asm).containsKey(id), lastSeen(asm).containsKey(id))
    }

    /** Every release path (peer enters its zone, sudden drop, gradual fall) leaves no approach start or last-seen time. */
    @Test
    fun releaseClearsApproachStreak() {
        val fails = mutableListOf<String>()
        for (mode in listOf("peerInZone", "drop", "ramp")) {
            val svc = h.newService(); val asm = asmOf(svc)
            val a = alertSteady(svc, 1_000L)
            if (a == null) { out("release.$mode", "alert=none"); fails += "$mode:noAlert"; continue }
            val r = release(svc, mode, a + 120L)
            out("release.$mode", "alertMs=$a releaseMs=${na(r.releaseMs)} path=${r.path} startLeft=${r.startLeft} lastSeenLeft=${r.lastSeenLeft} trackingState=${na(fieldOf<Map<String, Any>>(asm, "trackingStateMap")[id])}")
            if (r.releaseMs == null) { fails += "$mode:noRelease"; continue }
            if (r.startLeft || r.lastSeenLeft) fails += "$mode:mapsLeft"
        }
        assertTrue("해제 후 접근 streak 정리: $fails", fails.isEmpty())
    }

    /** Plants a recent approach trace, then loses the device; with no RSSI snapshot the handler purges cold. */
    private fun lost(svc: BleService, cold: Boolean, now: Long) {
        val asm = asmOf(svc)
        starts(asm)[id] = now - 2_000L
        lastSeen(asm)[id] = now - 100L
        if (cold) fieldOf<MutableMap<String, Int>>(asm, "deviceRssiMap").remove(id)
        h.deviceLost(svc, id)
    }

    /** BLE timeout (BleService's real scan-callback onDeviceLost), warm and cold, leaves no approach streak behind. */
    @Test
    fun deviceLostClearsApproachStreak() {
        val fails = mutableListOf<String>()
        for (cold in listOf(false, true)) {
            val tag = if (cold) "cold" else "warm"
            val svc = h.newService(); val asm = asmOf(svc)
            val a = alertSteady(svc, 1_000L)
            assertNotNull(a)
            val t = a!! + 120L
            lost(svc, cold, t)
            val startLeft = starts(asm).containsKey(id)
            val lsLeft = lastSeen(asm).containsKey(id)
            out("lost.$tag", "lostMs=$t startLeft=$startLeft lastSeenLeft=$lsLeft alertLeft=${h.alertLevelOf(svc, id) != null}")
            if (startLeft || lsLeft) fails += "$tag:mapsLeft"
        }
        assertTrue("소실 후 접근 streak 정리: $fails", fails.isEmpty())
    }
}
