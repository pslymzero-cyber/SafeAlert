package com.wf11.safealert.ble

import com.wf11.safealert.service.BleService
import com.wf11.safealert.support.BleServiceTestHarness
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

/**
 * Simulation: the approach-streak state (start time and APPROACH_STREAK_GRACE_MS grace time) after release or loss.
 * The AlertStateMachine maps are reached through reflection that fails the test when a field is missing or renamed
 * (no fallbacks). Measurement output format: "[S0914-A2] <scenario> key=value".
 * Expected values follow AlertStateMachine.kt (the map cleanup on release and the registry purge on loss): release or
 * device loss leaves no stale start or grace time for a re-approach. The grace itself (a non-approach gap of up to 300ms
 * keeps the streak without delaying confirmation) is covered by SpecialAlertTimeGateTest.shortNonApproachFrameKeepsApproachStreak.
 */
@RunWith(RobolectricTestRunner::class)
class Sim0914StreakGraceTest {

    private val id = "SA-SIM-A2"
    private val h = BleServiceTestHarness

    private fun out(s: String, kv: String) = println("[S0914-A2] $s $kv")
    private fun na(v: Any?) = v?.toString() ?: "none"
    private fun asmOf(svc: BleService): Any = ReflectionHelpers.getField(svc, "asm")

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(o: Any, name: String): T = ReflectionHelpers.getField<Any>(o, name) as T

    private fun starts(asm: Any): MutableMap<String, Long> = field(asm, "approachStreakStartMap")
    private fun lastSeen(asm: Any): MutableMap<String, Long> = field(asm, "approachLastSeenMap")

    // ── c. Map cleanup after release ──────────────────────────────────────
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
            if (mode == "peerInZone") field<MutableMap<String, Boolean>>(asm, "peerInZoneMap")[id] = true
            val rssi = when (mode) { "drop" -> -95; "ramp" -> maxOf(-45 - k, -95); else -> -45 }
            h.callProcessAlert(svc, id, rssi, nowMs = now)
            if (h.alertLevelOf(svc, id) == null) {
                val dep = field<Map<String, Long>>(asm, "departingStartMap")[id]
                val path = if (dep == now) "recede" else "safe"
                return Rel(now, path, starts(asm).containsKey(id), lastSeen(asm).containsKey(id))
            }
            now += 120L
        }
        return Rel(null, "none", starts(asm).containsKey(id), lastSeen(asm).containsKey(id))
    }

    @Test
    fun c_releaseClearsApproachStreak() {
        val fails = mutableListOf<String>()
        for (mode in listOf("peerInZone", "drop", "ramp")) {
            val svc = h.newService(); val asm = asmOf(svc)
            val a = alertSteady(svc, 1_000L)
            if (a == null) { out("c.$mode", "alert=none"); fails += "$mode:noAlert"; continue }
            val r = release(svc, mode, a + 120L)
            out("c.$mode", "alertMs=$a releaseMs=${na(r.releaseMs)} path=${r.path} startLeft=${r.startLeft} lastSeenLeft=${r.lastSeenLeft} trackingState=${na(field<Map<String, Any>>(asm, "trackingStateMap")[id])}")
            if (r.releaseMs == null) { fails += "$mode:noRelease"; continue }
            if (r.startLeft || r.lastSeenLeft) fails += "$mode:mapsLeft"
        }
        assertTrue("해제 후 접근 streak 정리: $fails", fails.isEmpty())
    }

    // ── e. BLE timeout (BleService's real scan-callback onDeviceLost) ─────
    /** Plants a recent approach trace, then loses the device; with no RSSI snapshot the handler purges cold. */
    private fun lost(svc: BleService, cold: Boolean, now: Long) {
        val asm = asmOf(svc)
        starts(asm)[id] = now - 2_000L
        lastSeen(asm)[id] = now - 100L
        if (cold) field<MutableMap<String, Int>>(asm, "deviceRssiMap").remove(id)
        h.deviceLost(svc, id)
    }

    @Test
    fun e_deviceLostClearsApproachStreak() {
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
            out("e.$tag", "lostMs=$t startLeft=$startLeft lastSeenLeft=$lsLeft alertLeft=${h.alertLevelOf(svc, id) != null}")
            if (startLeft || lsLeft) fails += "$tag:mapsLeft"
        }
        assertTrue("소실 후 접근 streak 정리: $fails", fails.isEmpty())
    }
}
