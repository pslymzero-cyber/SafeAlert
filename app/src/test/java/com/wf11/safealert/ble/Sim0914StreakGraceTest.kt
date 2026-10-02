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
 * 2026-09-14 시뮬 검증 에이전트2 — (v1.1.94) 접근 streak 300ms 유예(APPROACH_STREAK_GRACE_MS)와
 * 해제·소실 후 재접근. 16ee857(유예 도입 전)에서도 컴파일되도록 신규 심볼은 리플렉션+runCatching,
 * 없으면 N/A 출력. 측정 출력 형식: "[S0914-A2] <시나리오> key=value".
 * 기대값 근거: 커밋 b2edcec 메시지, AlertStateMachine.kt:293(300ms 이내 유예)·:511(<=)·:1344-1347/:1493-1496
 * (해제 시 맵 정리), SpecialAlertTimeGateTest.shortNonApproachFrameKeepsApproachStreak.
 */
@RunWith(RobolectricTestRunner::class)
class Sim0914StreakGraceTest {

    private val id = "SA-SIM-A2"
    private val h = BleServiceTestHarness
    private val vel = 1.0   // 0.5 이상(접근) 2.0 미만(fastApproach 제외)

    private fun out(s: String, kv: String) = println("[S0914-A2] $s $kv")
    private fun na(v: Any?) = v?.toString() ?: "N/A"
    private fun asmOf(svc: BleService): Any = ReflectionHelpers.getField(svc, "asm")

    @Suppress("UNCHECKED_CAST")
    private fun <T> fieldOrNull(o: Any, name: String): T? =
        runCatching { ReflectionHelpers.getField<Any>(o, name) as T }.getOrNull()

    private fun starts(asm: Any): MutableMap<String, Long> = fieldOrNull(asm, "approachStreakStartMap")!!
    private fun lastSeen(asm: Any): MutableMap<String, Long>? = fieldOrNull(asm, "approachLastSeenMap") // 16ee857: null

    private data class G(val streakMs: Long, val sustained: Boolean)

    /** evalTimeGate 직접 호출. 16ee857 에는 없음 → null. */
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

    // ── a. 짧은 비접근 프레임 ─────────────────────────────────────────────
    private data class Dip(val kept: Boolean, val sustainedAt: Long?, val resumeStreakMs: Long)

    /** 접근 1프레임(t=1000) → 비접근 dips 프레임 → 접근 재개. 첫 sustained 시각. */
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
        if (r120[0] == null) return   // 16ee857: evalTimeGate 없음
        val base = r120[0]!!.sustainedAt
        assertTrue("1프레임(120ms) 비접근은 streak 유지", r120[1]!!.kept)
        assertTrue("2프레임(240ms) 비접근은 streak 유지", r120[2]!!.kept)
        assertEquals("1프레임 끊김이 확인 시간을 늘리면 안 됨", base, r120[1]!!.sustainedAt)
        assertEquals("2프레임 끊김이 확인 시간을 늘리면 안 됨", base, r120[2]!!.sustainedAt)
        assertFalse("3프레임(360ms) 비접근은 리셋", r120[3]!!.kept)
        assertTrue(r100[2]!!.kept); assertTrue("정확히 300ms 는 유예(<=)", r100[3]!!.kept); assertFalse("400ms 는 리셋", r100[4]!!.kept)
        assertEquals(r100[0]!!.sustainedAt, r100[3]!!.sustainedAt)
    }

    // ── c/d. 해제 후 맵 정리와 재접근 ───────────────────────────────────
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

    /** 경보 중 기기를 해제시킨다. 매 프레임 직전 '최근 접근' 흔적(start=now-2000, lastSeen=now-100)을 심는다. */
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
            // c + d(직접 게이트)
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

            // d(processAlert 재접근 시각) — 같은 시퀀스를 새 서비스로 재현
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

    // ── e. BLE 타임아웃(onDeviceLost → registry.purge) ─────────────────
    private fun lost(svc: BleService, cold: Boolean, now: Long): String {
        val asm = asmOf(svc)
        starts(asm)[id] = now - 2_000L
        lastSeen(asm)?.set(id, now - 100L)
        if (!cold) runCatching {   // BleService.onDeviceLost: lastRssi != null → filterPreserveMap 적재 후 purge(cold=false)
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
