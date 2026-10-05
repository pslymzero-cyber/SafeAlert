package com.wf11.safealert.ble

import com.wf11.safealert.service.BleService
import com.wf11.safealert.support.BleServiceTestHarness
import com.wf11.safealert.support.BleServiceTestHarness.NOISE_4
import com.wf11.safealert.support.BleServiceTestHarness.NOISE_6
import com.wf11.safealert.support.BleServiceTestHarness.asmOf
import com.wf11.safealert.support.BleServiceTestHarness.fieldOf
import com.wf11.safealert.support.BleServiceTestHarness.streakOf
import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.roundToInt

/**
 * Simulation: escalation and departure of a normal device (no payload, no UWB).
 * Measurements print as `[NormalApproach] <scenario> key=value`; ms values are relative to T0.
 * Assertions cover only items whose intent is documented in commits, comments or existing
 * goldens (measurements still print when an assertion fails).
 */
@RunWith(RobolectricTestRunner::class)
class NormalApproachScenarioTest {

    private data class Trace(
        val firstAlertMs: Long?, val firstAlertLevel: Int?,
        val firstWarnMs: Long?, val firstDangerMs: Long?, val firstDangerRssi: Int?,
        val dangerToWarnMs: Long?, val releaseMs: Long?, val releaseFrame: Int?,
        val reAlertMs: Long?, val releases: Int, val reAlerts: Int,
        val warnBcasts: Int, val dangerBcasts: Int, val maxFastFrames: Int,
        val finalLevel: Int?, val transitions: String,
    )

    /** rssiAt(frame, releaseFrame) — for adaptive scenarios (re-approach) that pick the next input from the release frame. */
    private fun run(
        name: String, frames: Int, dtMs: Long = DT, service: BleService = BleServiceTestHarness.newService(),
        startFrame: Int = 0, rssiAt: (Int, Int?) -> Int,
    ): Trace {
        if (startFrame == 0) BleServiceTestHarness.resetBetweenTests(service)
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
            val fast = streakOf(service, "fastApproachStreakMap", ID)
            if (fast > maxFast) maxFast = fast
            val b = BleServiceTestHarness.alertBroadcasts()
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
        println("[NormalApproach] $name dtMs=$dtMs firstAlertMs=${out.firstAlertMs} firstAlertLevel=${out.firstAlertLevel} " +
            "firstWarnMs=${out.firstWarnMs} firstDangerMs=${out.firstDangerMs} firstDangerRssi=${out.firstDangerRssi} " +
            "dangerToWarnMs=${out.dangerToWarnMs} releaseMs=${out.releaseMs} reAlertMs=${out.reAlertMs} releases=${out.releases} " +
            "reAlerts=${out.reAlerts} warnBcasts=$warnB dangerBcasts=$dangerB maxFastFrames=$maxFast finalLevel=${out.finalLevel} " +
            "transitions=[${out.transitions}]")
        return out
    }

    // 120ms slow approach + jitter (non-approach frames mixed in → grace applies): WARNING comes first, none missed.
    @Test fun slowJitteryApproachWarnsBeforeDanger() {
        val t = run("slowJitter120", 420) { f, _ -> minOf(-90 + f / 8, -40) + NOISE_6[f % 6] }
        assertNotNull("WARNING 누락", t.firstWarnMs)
        assertEquals("첫 경보는 WARNING", BleConstants.LEVEL_WARNING, t.firstAlertLevel)
    }

    // Fast-approach bypass alone (evalTimeGate: kfVel >= fastApproachBypassVelDbm on two evaluated frames releases a
    // held first detection). -80/-92 flicker for 16 frames (f=0..15; gate median -86, inside the filter-keep band),
    // then both edges climb 0.6 dB per frame. The median crosses the warning line only every other frame and each drop
    // resets the WARNING streak, so the 2-frame confirmation (fastContact) never completes; kfVel stays near 2.3~2.5
    // and there is no payload (no special alert). The TTC pre-alert is skipped on a normal fast run because avg1sec
    // (a wall-clock 1 s average, which in a fast loop is the running mean of all frames) stays below effWarning, not
    // because TTC exceeds 3 s. At the first alert the plain approach streak is still under the Time-Gate, so only the
    // bypass can have released it. Measured: alert at 4320 ms; without the bypass the alert comes at 4680 ms, released
    // by fastContact (warnStreak=2) together with the plain Time-Gate. On a slow run avg1sec covers fewer frames, the
    // low frames pass the gates too and the alert comes one frame earlier; the assertions check the release conditions,
    // not a frame number, and the scenario passes at every measured cadence.
    @Test fun fastApproachBypassAloneReleasesFirstWarning() {
        val service = BleServiceTestHarness.newService()
        BleServiceTestHarness.resetBetweenTests(service)
        val asm = asmOf(service)
        fun valueOf(owner: Any, field: String) = fieldOf<Map<String, Number>>(owner, field)[ID]
        for (f in 0 until 46) {
            val now = T0 + f * DT
            val r = (-86.0 + 0.6 * maxOf(0, f - 15) + if (f % 2 == 0) 6 else -6).roundToInt()
            BleServiceTestHarness.callProcessAlert(service, ID, r, nowMs = now)
            val lvl = BleServiceTestHarness.alertLevelOf(service, ID) ?: continue
            val fast = valueOf(asm, "fastApproachStreakMap")?.toInt() ?: 0
            val streakMs = now - (valueOf(asm, "approachStreakStartMap")?.toLong() ?: now)
            val w = valueOf(service, "warningContactStreakMap")?.toInt() ?: 0
            val d = valueOf(service, "dangerContactStreakMap")?.toInt() ?: 0
            println("[NormalApproach] fastBypass firstAlertMs=${now - T0} level=$lvl fast=$fast approachStreakMs=$streakMs warnStreak=$w dangerStreak=$d")
            assertEquals("첫 경보는 WARNING (TTC·특수경보는 DANGER)", BleConstants.LEVEL_WARNING, lvl)
            assertTrue("2프레임 확인이 열렸다 w=$w d=$d", w < 2 && d < 2)
            assertTrue("빠른접근 프레임 $fast < 2", fast >= 2)
            assertTrue("일반 Time-Gate 가 이미 충족 ${streakMs}ms", streakMs < DevSettings.timeGateMs)
            return
        }
        fail("경보 없음")
    }

    // First detection with a cold Kalman — fastContact: two confirming raw frames bypass the Time-Gate.
    @Test fun closeFirstDetectionAlertsWithColdFilters() {
        val close = run("cold-50", 20) { _, _ -> -50 }
        val dangerLine = run("cold-65", 20) { _, _ -> -65 }
        assertNotNull("근접 첫 감지 경보 없음", close.firstAlertMs)
        assertNotNull("위험선 첫 감지 경보 없음", dangerLine.firstAlertMs)
    }

    // One-frame spike — AlertStateMachine Time-Gate comment: "A one-frame radio spike
    // that briefly touches the danger zone triggers no sound/screen alert."
    @Test fun singleFrameSpikeNeverAlerts() {
        val far = run("spike_base-90", 60) { f, _ -> if (f == 20) -50 else -90 }
        val near = run("spike_base-80", 60) { f, _ -> if (f == 20) -50 else -80 }
        assertNull("-90 바탕 spike 경보", far.firstAlertMs)
        assertNull("-80 바탕 spike 경보", near.firstAlertMs)
    }

    // TTC early alert (AlertStateMachine TTC pre-alert branch, `ttc <= TTC_THRESHOLD_SEC`) — while approaching inside the
    //    warning zone with TTC at or below the threshold, DANGER fires before RSSI reaches the danger threshold. Measured:
    //    1.5 dB/frame WARNING 1200ms → DANGER 1920ms (rssi -66), 0.5 dB/frame WARNING 3120ms → DANGER 5520ms (rssi -67);
    //    danger threshold -65. A 1 s-interval variant (DANGER at -62) is not included because its DANGER is not early.
    @Test fun steadyApproachGetsTtcDangerBeforeTheDangerLine() {
        val quick = run("ttc_1.5dBpf120", 50) { f, _ -> minOf(-90 + (f * 3) / 2, -45) }
        val slow = run("ttc_0.5dBpf120", 140) { f, _ -> minOf(-90 + f / 2, -45) }
        for ((name, t) in listOf("1.5dB/frame" to quick, "0.5dB/frame" to slow)) {
            assertNotNull("$name WARNING 없음", t.firstWarnMs)
            assertNotNull("$name DANGER 없음", t.firstDangerMs)
            assertTrue("$name WARNING 이 DANGER 보다 먼저", t.firstWarnMs!! < t.firstDangerMs!!)
            assertTrue("$name DANGER 가 위험 임계(${BleConstants.rssiDanger}) 전에 나와야 한다: ${t.firstDangerRssi}",
                t.firstDangerRssi!! < BleConstants.rssiDanger)
        }
    }

    // RSSI wobble at the warning line — flapping suppression (as in PassByStopSimulationTest: reAlerts=0).
    // The first alert is asserted too, so "no re-alert" cannot pass by never alerting at all.
    @Test fun wobbleAtWarningLineAlertsWithoutFlapping() {
        val w = intArrayOf(-73, -78, -74, -77, -72, -79, -75, -76)
        val t = run("wobble-75_120", 270) { f, _ -> if (f <= 16) -90 + f else w[f % 8] }
        assertNotNull("첫 경보 없음", t.firstAlertMs)
        assertEquals("WARNING↔해제 플래핑", 0, t.reAlerts)
    }

    // Stopping 2-7 dB inside the warning zone at 1 s frames, approach ramp included: stays WARNING, never released.
    @Test fun stoppingInsideWarningZoneStaysWarning() {
        val hover = intArrayOf(-71, -74, -73, -76, -72, -75, -73, -74)
        val t = run("hover1000", 100, dtMs = 1000L) { i, _ ->
            when { i <= 22 -> -95 + i + NOISE_4[i % 4]; i <= 27 -> -73; else -> hover[(i - 28) % 8] }
        }
        assertNull("정지 중 해제", t.releaseMs)
        assertEquals("최종 WARNING", BleConstants.LEVEL_WARNING, t.finalLevel)
    }

    // Stopping inside the danger zone (-60) and staying there for 60 s at 120ms frames: never released.
    @Test fun stoppingInsideDangerZoneIsNeverReleased() {
        val t = run("stop-60_120_60s", 520) { f, _ -> minOf(-90 + 2 * f, -60) + NOISE_4[f % 4] }
        assertNull("정지 중 해제", t.releaseMs)
    }

    // Re-approach right after release — cooldown (WARNING 3000/DANGER 2000, ×2 while departing),
    // DEPARTING re-entry 5000ms (DEPARTING_REENTRY_COOLDOWN_MS), vel>1.5 velocity gate.
    @Test fun reApproachAfterReleaseAlertsAgain() {
        fun seq(step: Int): (Int, Int?) -> Int = { f, rel ->
            when {
                rel == null && f <= 15 -> minOf(-95 + 3 * f, -50)
                rel == null && f <= 40 -> -50
                rel == null -> maxOf(-50 - 3 * (f - 40), -95)
                else -> minOf(-95 + step * (f - rel), -50)
            }
        }
        val fast = run("reApproachFast3dB", 160, rssiAt = seq(3))
        val slow = run("reApproachSlow1dB", 200, rssiAt = seq(1))
        assertNotNull("빠른 재접근: 해제 자체가 없음", fast.releaseMs)
        assertNotNull("빠른 재접근: 재경보 없음", fast.reAlertMs)
        assertNotNull("느린 재접근: 재경보 없음", slow.reAlertMs)
    }

    private companion object {
        const val T0 = 1_000_000L
        const val DT = 120L
        const val ID = "AA:BB:CC:DD:09:14"
    }
}
