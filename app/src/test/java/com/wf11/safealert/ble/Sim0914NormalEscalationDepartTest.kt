package com.wf11.safealert.ble

import com.wf11.safealert.service.BleService
import com.wf11.safealert.support.BleServiceTestHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

/**
 * [시뮬 2026-09-14 / 에이전트4] 일반 기기(payload 없음, UWB 없음) 승급·이탈 — b2edcec(v1.1.94) vs 16ee857 비교용.
 * 측정값은 `[S0914-A4] <시나리오> key=value` 로 출력한다. ms 는 T0 기준 상대값.
 * 단정은 커밋·주석·기존 골든에 적힌 의도가 있는 항목에만 둔다(기준선에서 실패해도 측정은 남는다).
 * 16ee857 호환: approachLastSeenMap 은 runCatching 리플렉션으로만 확인(graceField).
 */
@RunWith(RobolectricTestRunner::class)
class Sim0914NormalEscalationDepartTest {

    private data class Trace(
        val firstAlertMs: Long?, val firstAlertLevel: Int?,
        val firstWarnMs: Long?, val firstDangerMs: Long?, val firstDangerRssi: Int?,
        val dangerToWarnMs: Long?, val releaseMs: Long?, val releaseFrame: Int?,
        val reAlertMs: Long?, val releases: Int, val reAlerts: Int,
        val warnBcasts: Int, val dangerBcasts: Int, val maxFastFrames: Int,
        val finalLevel: Int?, val transitions: String,
    )

    /** rssiAt(frame, releaseFrame) — 해제 프레임을 보고 다음 입력을 정하는 적응형 시나리오(k)용. */
    private fun run(
        name: String, frames: Int, dtMs: Long = DT, service: BleService = BleServiceTestHarness.newService(),
        startFrame: Int = 0, dbg: IntRange? = null, rssiAt: (Int, Int?) -> Int,
    ): Trace {
        if (startFrame == 0) BleServiceTestHarness.resetBetweenTests(service)
        val asm = ReflectionHelpers.getField<Any>(service, "asm")
        val grace = runCatching { ReflectionHelpers.getField<Any>(asm, "approachLastSeenMap") != null }.getOrDefault(false)
        var prev: Int? = null
        var firstAlertMs: Long? = null; var firstAlertLevel: Int? = null
        var firstWarnMs: Long? = null; var firstDangerMs: Long? = null; var firstDangerRssi: Int? = null
        var d2w: Long? = null; var releaseMs: Long? = null; var releaseFrame: Int? = null; var reAlertMs: Long? = null
        var releases = 0; var reAlerts = 0; var maxFast = 0
        var bcastSeen = BleServiceTestHarness.alertBroadcasts().size
        var warnB = 0; var dangerB = 0
        val tr = StringBuilder()
        var trCount = 0
        for (f in startFrame until startFrame + frames) {
            val now = T0 + f * dtMs
            val t = now - T0
            val r = rssiAt(f, releaseFrame)
            BleServiceTestHarness.callProcessAlert(service, ID, r, nowMs = now)
            val lvl = BleServiceTestHarness.alertLevelOf(service, ID)
            val fast = runCatching { (ReflectionHelpers.getField<Map<String, Int>>(asm, "fastApproachStreakMap")[ID]) ?: 0 }.getOrDefault(0)
            if (fast > maxFast) maxFast = fast
            if (dbg != null && f in dbg) {
                fun sf(n: String) = runCatching { (ReflectionHelpers.getField<Map<String, Any?>>(service, n)[ID]).toString() }.getOrDefault("?")
                val kv = runCatching { (ReflectionHelpers.getField<Map<String, KalmanFilter>>(service, "kalmanFilters")[ID])?.estimatedVel?.let { "%.2f".format(it) } }.getOrNull()
                println("[S0914-A4] dbg $name f=$f t=$t r=$r lvl=$lvl kfVel=$kv fast=$fast dStreak=${sf("dangerContactStreakMap")} " +
                    "wStreak=${sf("warningContactStreakMap")} track=${sf("trackingStateMap")}")
            }
            val b =BleServiceTestHarness.alertBroadcasts()
            b.drop(bcastSeen).forEach {
                when (it.getIntExtra(BleService.EXTRA_ALERT_LEVEL, -1)) {
                    BleConstants.LEVEL_WARNING -> warnB++
                    BleConstants.LEVEL_DANGER -> dangerB++
                }
            }
            bcastSeen = b.size
            if (lvl != null && firstAlertMs == null) { firstAlertMs = t; firstAlertLevel = lvl }
            if (lvl == BleConstants.LEVEL_WARNING && firstWarnMs == null) firstWarnMs = t
            if (lvl == BleConstants.LEVEL_DANGER && firstDangerMs == null) { firstDangerMs = t; firstDangerRssi = r }
            if (prev == BleConstants.LEVEL_DANGER && lvl == BleConstants.LEVEL_WARNING && d2w == null) d2w = t
            if (prev != null && lvl == null) { releases++; if (releaseMs == null) { releaseMs = t; releaseFrame = f } }
            if (prev == null && lvl != null && releaseMs != null) { reAlerts++; if (reAlertMs == null) reAlertMs = t }
            if (lvl != prev && trCount < 14) { tr.append("$t:${lvl ?: "S"}(r$r) "); trCount++ }
            prev = lvl
        }
        val out = Trace(firstAlertMs, firstAlertLevel, firstWarnMs, firstDangerMs, firstDangerRssi, d2w, releaseMs,
            releaseFrame, reAlertMs, releases, reAlerts, warnB, dangerB, maxFast, prev, tr.toString().trim())
        println("[S0914-A4] $name graceField=$grace dtMs=$dtMs firstAlertMs=${out.firstAlertMs} firstAlertLevel=${out.firstAlertLevel} " +
            "firstWarnMs=${out.firstWarnMs} firstDangerMs=${out.firstDangerMs} firstDangerRssi=${out.firstDangerRssi} " +
            "dangerToWarnMs=${out.dangerToWarnMs} releaseMs=${out.releaseMs} reAlertMs=${out.reAlertMs} releases=${out.releases} " +
            "reAlerts=${out.reAlerts} warnBcasts=$warnB dangerBcasts=$dangerB maxFastFrames=$maxFast finalLevel=${out.finalLevel} " +
            "transitions=[${out.transitions}]")
        return out
    }

    // a. 정상 속도 정면 접근 — AlertCascadeGoldenTest:210 골든(-95+1/프레임, 120ms): WARNING f19=2280ms(v1.1.95 경고 -78 재동결, 이전 -75 기준 f22=2640ms), DANGER f32=3840ms(v1.1.95 위험 -65 재동결, 이전 -55 기준 f39=4680ms).
    // h. 이어서 -1dB/프레임 이탈 — AlertCascadeGoldenTest:240-244: f59(7080ms) 해제(v1.1.95 실측 재동결, 이전 f78=9360ms), WARNING 경유 없음.
    @Test fun a_h_goldenHeadOnApproachThenDepart() {
        val service = BleServiceTestHarness.newService()
        val a = run("a_headOn", 42, service = service) { f, _ -> -95 + f }
        val h = run("h_departGolden", 48, service = service, startFrame = 42) { f, _ -> -54 - (f - 42) }
        assertEquals("a WARNING 진입(골든 f19)", 2280L, a.firstWarnMs)
        assertEquals("a DANGER 진입(골든 f32)", 3840L, a.firstDangerMs)
        assertEquals("h 해제(골든 f59)", 7080L, h.releaseMs)
    }

    // h2. 느린 이탈 1000ms — PassByStopSimulationTest s2_slowDrift3f 기준선 release=58.
    @Test fun h2_passByStopSlowDrift() {
        val t = run("h2_slowDrift1000", 88, dtMs = 1000L) { i, _ ->
            when { i <= 22 -> -98 + i + n4(i); i <= 27 -> -76; i <= 57 -> -77 - (i - 28) / 3; else -> -86 + n4(i) }
        }
        assertEquals("h2 해제(PassByStop s2 release=58)", 58_000L, t.releaseMs)
        assertEquals("h2 해제 후 재경보 없음", 0, t.reAlerts)
    }

    // b1. BUG-02 저속 접근 골든 — LowSpeedApproachRegressionTest:259 첫 WARNING f82(82000ms).
    @Test fun b1_lowSpeedGolden() {
        val t = run("b1_lowSpeed1000", 264, dtMs = 1000L) { i, _ -> -95 + i / 4 + intArrayOf(0, -1, 2, -1)[i % 4] }
        assertNotNull("b1 WARNING 누락", t.firstWarnMs)
        assertTrue("b1 WARNING 지연 ${t.firstWarnMs} > 82000", t.firstWarnMs!! <= 82_000L)
    }

    // b2. 120ms 저속 + 지터(비접근 프레임 섞임 → 유예 영향) — 기대: WARNING 이 DANGER 보다 먼저 뜬다(누락 없음).
    @Test fun b2_slowJitter120() {
        val t = run("b2_slowJitter120", 420) { f, _ -> minOf(-90 + f / 8, -40) + J6[f % 6] }
        assertNotNull("b2 WARNING 누락", t.firstWarnMs)
        assertEquals("b2 첫 경보는 WARNING", BleConstants.LEVEL_WARNING, t.firstAlertLevel)
    }

    // c. 빠른 접근 4dB/프레임 — v1.1.21 kfVel>=2.0 2프레임 우회(:311).
    @Test fun c_fastApproach() {
        val t = run("c_fast4dB", 30) { f, _ -> minOf(-95 + 4 * f, -45) }
        assertNotNull("c 경보 없음", t.firstAlertMs)
        assertTrue("c 빠른접근 프레임 ${t.maxFastFrames} < 2", t.maxFastFrames >= 2)
    }

    // d. 칼만 콜드 첫 감지 — fastContact(v1.1.18/v1.1.22 C) raw 2프레임 확증이면 Time-Gate 우회.
    @Test fun d_coldFirstDetectionClose() {
        val d1 = run("d1_cold-50", 20) { _, _ -> -50 }
        val d2 = run("d2_cold-65", 20) { _, _ -> -65 }
        assertNotNull("d1 근접 첫 감지 경보 없음", d1.firstAlertMs)
        assertNotNull("d2 WARNING권 첫 감지 경보 없음", d2.firstAlertMs)
    }

    // e. 1프레임 spike — AlertStateMachine Time-Gate KDoc "1프레임 spike 로 위험권에 잠깐 닿은 것만으론 경보하지 않는다".
    @Test fun e_singleSpike() {
        val e1 = run("e1_spike_base-90", 60, dbg = 18..24) { f, _ -> if (f == 20) -50 else -90 }
        val e2 = run("e2_spike_base-80", 60) { f, _ -> if (f == 20) -50 else -80 }
        run("e3_spikeGapSpike240ms", 60) { f, _ -> if (f == 20 || f == 22) -50 else -90 }   // 기대 불명 — 측정만
        assertNull("e1 spike 경보", e1.firstAlertMs)
        assertNull("e2 spike 경보", e2.firstAlertMs)
    }

    // f. 스쳐 지나감 — 16ee857 대비 경보 유무·횟수 비교(측정만, 단정 없음).
    @Test fun f_passBy() {
        run("f1_passBy_peak-72_jitter", 60) { f, _ -> (if (f <= 18) -90 + f else -72 - (f - 18)) + J6[f % 6] }
        run("f2_passBy_peak-58_jitter", 60) { f, _ -> (if (f <= 16) -90 + 2 * f else -58 - 2 * (f - 16)) + J6[f % 6] }
        run("f3_passBy_peak-62_dip1", 60) { f, _ -> (if (f <= 14) -90 + 2 * f else -62 - 2 * (f - 14)) + (if (f % 3 == 2) -3 else 0) }
    }

    // g. TTC 조기경보 — WARNING+APPROACHING 에서 TTC<=3.0s 면 위험 임계 도달 전 DANGER(:1541). 발령 시각은 16ee857 과 동일해야 한다.
    @Test fun g_ttcEarlyDanger() {
        run("g1_ttc_1.5dBps120", 50) { f, _ -> minOf(-90 + (f * 3) / 2, -45) }
        run("g2_ttc_0.5dB120", 140) { f, _ -> minOf(-90 + f / 2, -45) }
        run("g3_ttc_2dB1000", 30, dtMs = 1000L) { f, _ -> minOf(-90 + 2 * f, -45) }
    }

    // i. 경계 RSSI 흔들림 — v1.1.56 플래핑 억제, PassByStop reAlerts=0.
    @Test fun i_boundaryWobble() {
        val w = intArrayOf(-73, -78, -74, -77, -72, -79, -75, -76)
        val t = run("i_wobble-75_120", 270) { f, _ -> if (f <= 16) -90 + f else w[f % 8] }
        assertEquals("i WARNING↔해제 플래핑", 0, t.reAlerts)
    }

    // j. 근접 정지 — PassByStop s1_hoverAboveWarn(release=-1, finalLevel=1).
    @Test fun j_stopClose() {
        val hover = intArrayOf(-71, -74, -73, -76, -72, -75, -73, -74)
        val j1 = run("j1_hover1000", 100, dtMs = 1000L) { i, _ ->
            when { i <= 22 -> -95 + i + n4(i); i <= 27 -> -73; else -> hover[(i - 28) % 8] }
        }
        val j2 = run("j2_stop-60_120_60s", 520) { f, _ -> minOf(-90 + 2 * f, -60) + n4(f) }
        assertNull("j1 정지 중 해제", j1.releaseMs)
        assertEquals("j1 최종 WARNING", BleConstants.LEVEL_WARNING, j1.finalLevel)
        assertNull("j2 정지 중 해제", j2.releaseMs)
    }

    // k. 해제 직후 재접근 — 쿨다운(WARNING 3000/DANGER 2000, 이탈 ×2), DEPARTING 재진입 5000ms(:186), vel>1.5 속도 게이트.
    @Test fun k_reApproachAfterRelease() {
        fun seq(step: Int): (Int, Int?) -> Int = { f, rel ->
            when {
                rel == null && f <= 15 -> minOf(-95 + 3 * f, -50)
                rel == null && f <= 40 -> -50
                rel == null -> maxOf(-50 - 3 * (f - 40), -95)
                else -> minOf(-95 + step * (f - rel), -50)
            }
        }
        val k1 = run("k1_reApproachFast3dB", 160, rssiAt = seq(3))
        val k2 = run("k2_reApproachSlow1dB", 200, rssiAt = seq(1))
        assertNotNull("k1 해제 자체가 없음", k1.releaseMs)
        assertNotNull("k1 재접근 재경보 없음", k1.reAlertMs)
        assertNotNull("k2 재접근 재경보 없음", k2.reAlertMs)
    }

    private companion object {
        const val T0 = 1_000_000L
        const val DT = 120L
        const val ID = "AA:BB:CC:DD:09:14"
        val J6 = intArrayOf(0, -3, 2, -1, 3, -2)
        fun n4(i: Int) = intArrayOf(0, -1, 1, 0)[i % 4]
    }
}
