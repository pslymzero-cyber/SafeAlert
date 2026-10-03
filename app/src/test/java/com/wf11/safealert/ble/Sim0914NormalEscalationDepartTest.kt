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
 * Simulation: escalation and departure of a normal device (no payload, no UWB).
 * Measurements print as `[S0914-A4] <scenario> key=value`; ms values are relative to T0.
 * Assertions cover only items whose intent is documented in commits, comments or existing
 * goldens (measurements still print when an assertion fails).
 * approachLastSeenMap may be absent, so it is probed only through runCatching reflection (graceField=false when missing).
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

    /** rssiAt(frame, releaseFrame) — for adaptive scenarios (k) that pick the next input from the release frame. */
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

    // b2. 120ms slow approach + jitter (non-approach frames mixed in → grace applies) — expect: WARNING before DANGER (none missed).
    @Test fun b2_slowJitter120() {
        val t = run("b2_slowJitter120", 420) { f, _ -> minOf(-90 + f / 8, -40) + J6[f % 6] }
        assertNotNull("b2 WARNING 누락", t.firstWarnMs)
        assertEquals("b2 첫 경보는 WARNING", BleConstants.LEVEL_WARNING, t.firstAlertLevel)
    }

    // c. Fast approach, 4dB/frame — checks only that an alert fires and that fastApproachStreakMap reaches 2
    // (kfVel>=2.0 on two Time-Gate evaluations in a row, the fastApproach condition in evalTimeGate); it does not
    // show which path released the first alert (fastContact can release it too).
    @Test fun c_fastApproach() {
        val t = run("c_fast4dB", 30) { f, _ -> minOf(-95 + 4 * f, -45) }
        assertNotNull("c 경보 없음", t.firstAlertMs)
        assertTrue("c 빠른접근 프레임 ${t.maxFastFrames} < 2", t.maxFastFrames >= 2)
    }

    // d. First detection with a cold Kalman — fastContact: two confirming raw frames bypass the Time-Gate.
    @Test fun d_coldFirstDetectionClose() {
        val d1 = run("d1_cold-50", 20) { _, _ -> -50 }
        val d2 = run("d2_cold-65", 20) { _, _ -> -65 }
        assertNotNull("d1 근접 첫 감지 경보 없음", d1.firstAlertMs)
        assertNotNull("d2 WARNING권 첫 감지 경보 없음", d2.firstAlertMs)
    }

    // e. One-frame spike — AlertStateMachine Time-Gate comment: "A one-frame radio spike
    // that briefly touches the danger zone triggers no sound/screen alert."
    @Test fun e_singleSpike() {
        val e1 = run("e1_spike_base-90", 60, dbg = 18..24) { f, _ -> if (f == 20) -50 else -90 }
        val e2 = run("e2_spike_base-80", 60) { f, _ -> if (f == 20) -50 else -80 }
        run("e3_spikeGapSpike240ms", 60) { f, _ -> if (f == 20 || f == 22) -50 else -90 }   // expectation unclear — measure only
        assertNull("e1 spike 경보", e1.firstAlertMs)
        assertNull("e2 spike 경보", e2.firstAlertMs)
    }

    // g. TTC early alert (AlertStateMachine TTC pre-alert branch, `ttc <= TTC_THRESHOLD_SEC`) — while approaching inside the
    //    warning zone with TTC at or below the threshold, DANGER fires before RSSI reaches the danger threshold. Measured:
    //    g1 WARNING 1200ms → DANGER 1920ms (rssi -66), g2 WARNING 3120ms → DANGER 5520ms (rssi -67); danger threshold -65.
    //    A 1 s-interval variant (g3, DANGER at -62) is not included because its DANGER is not early.
    @Test fun g_ttcEarlyDanger() {
        val g1 = run("g1_ttc_1.5dBps120", 50) { f, _ -> minOf(-90 + (f * 3) / 2, -45) }
        val g2 = run("g2_ttc_0.5dB120", 140) { f, _ -> minOf(-90 + f / 2, -45) }
        for ((name, t) in listOf("g1" to g1, "g2" to g2)) {
            assertNotNull("$name WARNING 없음", t.firstWarnMs)
            assertNotNull("$name DANGER 없음", t.firstDangerMs)
            assertTrue("$name WARNING 이 DANGER 보다 먼저", t.firstWarnMs!! < t.firstDangerMs!!)
            assertTrue("$name DANGER 가 위험 임계(${BleConstants.rssiDanger}) 전에 나와야 한다: ${t.firstDangerRssi}",
                t.firstDangerRssi!! < BleConstants.rssiDanger)
        }
    }

    // i. RSSI wobble at the boundary — flapping suppression (as in PassByStop: reAlerts=0).
    @Test fun i_boundaryWobble() {
        val w = intArrayOf(-73, -78, -74, -77, -72, -79, -75, -76)
        val t = run("i_wobble-75_120", 270) { f, _ -> if (f <= 16) -90 + f else w[f % 8] }
        assertEquals("i WARNING↔해제 플래핑", 0, t.reAlerts)
    }

    // j. Stopping close by — as in PassByStop s1_hoverAboveWarn (release=-1, finalLevel=1).
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

    // k. Re-approach right after release — cooldown (WARNING 3000/DANGER 2000, ×2 while departing),
    // DEPARTING re-entry 5000ms (DEPARTING_REENTRY_COOLDOWN_MS), vel>1.5 velocity gate.
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
