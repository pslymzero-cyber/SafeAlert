package com.wf11.safealert.ble

import android.os.SystemClock
import com.wf11.safealert.service.BleService
import com.wf11.safealert.support.BleServiceTestHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Simulation: the 300ms approach-streak grace (APPROACH_STREAK_GRACE_MS) and re-approach after release or loss.
 * Symbols that may be missing (evalTimeGate, approachLastSeenMap) are reached through reflection + runCatching, so the
 * file compiles and runs without them; missing values print N/A and the checks that need evalTimeGate are skipped.
 * Measurement output format: "[S0914-A2] <scenario> key=value".
 * Expected values follow AlertStateMachine.kt (APPROACH_STREAK_GRACE_MS, the `<=` check in evalTimeGate and the map
 * cleanup on release) and SpecialAlertTimeGateTest.shortNonApproachFrameKeepsApproachStreak: a non-approach gap of up
 * to 300ms (exactly 300ms included) keeps the streak without delaying confirmation, a longer gap resets it, and release
 * or device loss leaves no stale start or grace time for re-approach.
 */
@RunWith(RobolectricTestRunner::class)
class Sim0914StreakGraceTest {

    private val id = "SA-SIM-A2"
    private val h = BleServiceTestHarness
    private val vel = 1.0   // ≥0.5 (approaching), <2.0 (no fastApproach)

    private fun out(s: String, kv: String) = println("[S0914-A2] $s $kv")
    private fun na(v: Any?) = v?.toString() ?: "N/A"
    private fun asmOf(svc: BleService): Any = ReflectionHelpers.getField(svc, "asm")

    @Suppress("UNCHECKED_CAST")
    private fun <T> fieldOrNull(o: Any, name: String): T? =
        runCatching { ReflectionHelpers.getField<Any>(o, name) as T }.getOrNull()

    private fun starts(asm: Any): MutableMap<String, Long> = fieldOrNull(asm, "approachStreakStartMap")!!
    private fun lastSeen(asm: Any): MutableMap<String, Long>? = fieldOrNull(asm, "approachLastSeenMap") // null when the field is absent

    private data class G(val streakMs: Long, val sustained: Boolean)

    /** Calls evalTimeGate directly via reflection; null when it is missing. */
    private fun gate(asm: Any, v: Double, now: Long): G? = runCatching {
        val g = ReflectionHelpers.callInstanceMethod<Any>(
            asm, "evalTimeGate",
            ClassParameter.from(String::class.java, id),
            ClassParameter.from(Double::class.javaPrimitiveType, v),
            ClassParameter.from(Long::class.javaPrimitiveType, now),
            ClassParameter.from(Int::class.javaPrimitiveType, 10),
        )
        G(ReflectionHelpers.getField<Long>(g, "streakMs"), ReflectionHelpers.getField<Boolean>(g, "sustained"))
    }.getOrNull()

    private fun kfVel(svc: BleService): Double? = runCatching {
        val kf = ReflectionHelpers.getField<Map<String, Any>>(svc, "kalmanFilters")[id] ?: return null
        runCatching { ReflectionHelpers.getField<Double>(kf, "estimatedVel") }
            .getOrElse { ReflectionHelpers.callInstanceMethod<Double>(kf, "getEstimatedVel") }
    }.getOrNull()

    // ── a. Short non-approach frames ──────────────────────────────────────
    private data class Dip(val kept: Boolean, val sustainedAt: Long?, val resumeStreakMs: Long)

    /** One approach frame (t=1000) → `dips` non-approach frames → approach resumes; records the first sustained time. */
    private fun dipRun(dips: Int, stepMs: Long): Dip? {
        val asm = asmOf(h.newService())
        var now = 1_000L
        gate(asm, vel, now) ?: return null
        var kept = true
        repeat(dips) { now += stepMs; gate(asm, 0.0, now); if (!starts(asm).containsKey(id)) kept = false }
        var sustainedAt: Long? = null
        var resume = -1L
        for (i in 0 until 40) {
            now += stepMs
            val g = gate(asm, vel, now)!!
            if (i == 0) resume = g.streakMs
            if (g.sustained) { sustainedAt = now; break }
        }
        return Dip(kept, sustainedAt, resume)
    }

    @Test
    fun a_shortDipKeepsStreak_direct() {
        val r120 = (0..3).associateWith { dipRun(it, 120L) }
        val r100 = (0..4).associateWith { dipRun(it, 100L) }
        r120.forEach { (n, d) -> out("a.direct120", "dips=$n kept=${na(d?.kept)} resumeStreakMs=${na(d?.resumeStreakMs)} sustainedAt=${na(d?.sustainedAt)}") }
        r100.forEach { (n, d) -> out("b.frame100", "dips=$n gapMs=${n * 100} kept=${na(d?.kept)} sustainedAt=${na(d?.sustainedAt)}") }
        if (r120[0] == null) return   // evalTimeGate missing: gate() returns null
        val base = r120[0]!!.sustainedAt
        assertTrue("1프레임(120ms) 비접근은 streak 유지", r120[1]!!.kept)
        assertTrue("2프레임(240ms) 비접근은 streak 유지", r120[2]!!.kept)
        assertEquals("1프레임 끊김이 확인 시간을 늘리면 안 됨", base, r120[1]!!.sustainedAt)
        assertEquals("2프레임 끊김이 확인 시간을 늘리면 안 됨", base, r120[2]!!.sustainedAt)
        assertFalse("3프레임(360ms) 비접근은 리셋", r120[3]!!.kept)
        assertTrue(r100[2]!!.kept); assertTrue("정확히 300ms 는 유예(<=)", r100[3]!!.kept); assertFalse("400ms 는 리셋", r100[4]!!.kept)
        assertEquals(r100[0]!!.sustainedAt, r100[3]!!.sustainedAt)
    }

    // ── c/d. Map cleanup after release, and re-approach ───────────────────
    private fun alertSteady(svc: BleService, startMs: Long, trace: StringBuilder? = null): Long? {
        var now = startMs
        repeat(120) { k ->
            h.callProcessAlert(svc, id, -45, nowMs = now)
            if (trace != null && (k < 6 || k % 8 == 0)) {
                val asm = asmOf(svc)
                trace.append("${now - startMs}:ts=${na(fieldOrNull<Map<String, Any>>(asm, "trackingStateMap")?.get(id))}/v=${"%.2f".format(kfVel(svc) ?: Double.NaN)}/ws=${na(fieldOrNull<Map<String, Int>>(asm, "warningContactStreakMap")?.get(id))}/dep=${na(fieldOrNull<Map<String, Long>>(asm, "departingStartMap")?.get(id))};")
            }
            if (h.alertLevelOf(svc, id) != null) return now
            now += 120L
        }
        return null
    }

    private data class Rel(val releaseMs: Long?, val path: String, val startLeft: Boolean, val lastSeenLeft: Boolean?)

    /**
     * Releases a device that is alerting. Before every frame, plants a 'recent approach' trace (start=now-2000, lastSeen=now-100).
     */
    private fun release(svc: BleService, mode: String, fromMs: Long): Rel {
        val asm = asmOf(svc)
        var now = fromMs
        for (k in 0 until 80) {
            starts(asm)[id] = now - 2_000L
            lastSeen(asm)?.set(id, now - 100L)
            if (mode == "peerInZone") fieldOrNull<MutableMap<String, Boolean>>(asm, "peerInZoneMap")?.set(id, true)
            val rssi = when (mode) { "drop" -> -95; "ramp" -> maxOf(-45 - k, -95); else -> -45 }
            h.callProcessAlert(svc, id, rssi, nowMs = now)
            if (h.alertLevelOf(svc, id) == null) {
                val dep = fieldOrNull<Map<String, Long>>(asm, "departingStartMap")?.get(id)
                val path = if (dep == now) "recede" else "safe"
                return Rel(now, path, starts(asm).containsKey(id), lastSeen(asm)?.containsKey(id))
            }
            now += 120L
        }
        return Rel(null, "none", starts(asm).containsKey(id), lastSeen(asm)?.containsKey(id))
    }

    @Test
    fun c_d_releaseClearsAndReapproach() {
        val freshSvc = h.newService()
        val freshAlert = alertSteady(freshSvc, 1_000L)
        out("d.fresh", "firstAlertMs=${na(freshAlert)} elapsedMs=${freshAlert?.minus(1_000L)}")
        val fails = mutableListOf<String>()
        for (mode in listOf("peerInZone", "drop", "ramp")) {
            // c + d (direct gate)
            val svc = h.newService(); val asm = asmOf(svc)
            val a = alertSteady(svc, 1_000L)
            if (a == null) { out("c.$mode", "alert=none"); fails += "$mode:noAlert"; continue }
            val r = release(svc, mode, a + 120L)
            out("c.$mode", "alertMs=$a releaseMs=${na(r.releaseMs)} path=${r.path} startLeft=${r.startLeft} lastSeenLeft=${na(r.lastSeenLeft)} trackingState=${na(fieldOrNull<Map<String, Any>>(asm, "trackingStateMap")?.get(id))}")
            if (r.releaseMs == null) { fails += "$mode:noRelease"; continue }
            if (r.startLeft || r.lastSeenLeft == true) fails += "$mode:mapsLeft"
            fieldOrNull<MutableMap<String, Boolean>>(asm, "peerInZoneMap")?.remove(id)
            val g1 = gate(asm, 0.0, r.releaseMs + 120L)
            val kept1 = starts(asm).containsKey(id)
            val g2 = gate(asm, vel, r.releaseMs + 240L)
            out("d.direct.$mode", "nonApproach@+120 streakMs=${na(g1?.streakMs)} sustained=${na(g1?.sustained)} kept=$kept1 | approach@+240 streakMs=${na(g2?.streakMs)} sustained=${na(g2?.sustained)}")
            if (g1 != null && (g1.streakMs != 0L || g1.sustained || kept1)) fails += "$mode:staleGrace"
            if (g2 != null && (g2.streakMs != 0L || g2.sustained)) fails += "$mode:staleStart"

            // d (re-approach time via processAlert) — replays the same sequence on a new service
            val svc2 = h.newService(); val asm2 = asmOf(svc2)
            val a2 = alertSteady(svc2, 1_000L)!!
            val r2 = release(svc2, mode, a2 + 120L)
            fieldOrNull<MutableMap<String, Boolean>>(asm2, "peerInZoneMap")?.remove(id)
            val reStart = r2.releaseMs!! + 120L
            val tr = StringBuilder()
            val re = alertSteady(svc2, reStart, tr)
            out("d.processTrace.$mode", tr.toString())
            out("d.process.$mode", "releaseMs=${r2.releaseMs} reapproachFromMs=$reStart reAlertMs=${na(re)} reElapsedMs=${na(re?.minus(reStart))} freshElapsedMs=${na(freshAlert?.minus(1_000L))} level=${na(h.alertLevelOf(svc2, id))}")
        }
        assertTrue("해제 후 맵 정리·재접근 게이트: $fails", fails.isEmpty())
    }

    // ── e. BLE timeout (onDeviceLost → registry.purge) ────────────────────
    private fun lost(svc: BleService, cold: Boolean, now: Long): String {
        val asm = asmOf(svc)
        starts(asm)[id] = now - 2_000L
        lastSeen(asm)?.set(id, now - 100L)
        if (!cold) runCatching {   // BleService.onDeviceLost: lastRssi != null → store in filterPreserveMap, then purge(cold=false)
            val cls = Class.forName(asm.javaClass.name + "\$FilterPreserveState")
            val ctor = cls.getDeclaredConstructor(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
            fieldOrNull<MutableMap<String, Any>>(asm, "filterPreserveMap")!![id] = ctor.newInstance(-45, SystemClock.elapsedRealtime())
        }.onFailure { return "preserve=N/A(${it.javaClass.simpleName})" }
        val reg = ReflectionHelpers.getField<Any>(asm, "registry")
        ReflectionHelpers.callInstanceMethod<Any?>(reg, "purge",
            ClassParameter.from(String::class.java, id), ClassParameter.from(Boolean::class.javaPrimitiveType, cold))
        return "ok"
    }

    @Test
    fun e_deviceLostClearsAndReapproach() {
        val fails = mutableListOf<String>()
        for (cold in listOf(false, true)) {
            val tag = if (cold) "cold" else "warm"
            val svc = h.newService(); val asm = asmOf(svc)
            val a = alertSteady(svc, 1_000L)
            assertNotNull(a)
            val t = a!! + 120L
            val st = lost(svc, cold, t)
            val startLeft = starts(asm).containsKey(id)
            val lsLeft = lastSeen(asm)?.containsKey(id)
            val kfLeft = ReflectionHelpers.getField<Map<String, Any>>(svc, "kalmanFilters").containsKey(id)
            out("e.$tag", "purge=$st lostMs=$t startLeft=$startLeft lastSeenLeft=${na(lsLeft)} alertLeft=${h.alertLevelOf(svc, id) != null} kalmanLeft=$kfLeft")
            if (startLeft || lsLeft == true) fails += "$tag:mapsLeft"
            val g1 = gate(asm, 0.0, t + 120L)
            val g2 = gate(asm, vel, t + 240L)
            out("e.direct.$tag", "nonApproach@+120 streakMs=${na(g1?.streakMs)} kept=${starts(asm).containsKey(id)} | approach@+240 streakMs=${na(g2?.streakMs)} sustained=${na(g2?.sustained)}")
            if (g1 != null && g1.streakMs != 0L) fails += "$tag:staleGrace"
            if (g2 != null && (g2.streakMs != 0L || g2.sustained)) fails += "$tag:staleStart"

            val svc2 = h.newService()
            val a2 = alertSteady(svc2, 1_000L)!!
            val t2 = a2 + 120L
            lost(svc2, cold, t2)
            val reStart = t2 + 120L
            val re = alertSteady(svc2, reStart)
            out("e.process.$tag", "lostMs=$t2 reapproachFromMs=$reStart reAlertMs=${na(re)} reElapsedMs=${na(re?.minus(reStart))} waiveConsumed=${fieldOrNull<Set<String>>(asmOf(svc2), "timeGateWaiveSet")?.contains(id)?.not()}")
        }
        assertTrue("소실 후 맵 정리·재접근 게이트: $fails", fails.isEmpty())
    }
}
