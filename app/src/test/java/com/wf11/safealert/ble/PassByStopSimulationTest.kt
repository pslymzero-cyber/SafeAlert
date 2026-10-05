package com.wf11.safealert.ble

import com.wf11.safealert.service.BleService
import com.wf11.safealert.support.BleServiceTestHarness
import com.wf11.safealert.support.BleServiceTestHarness.NOISE_4
import com.wf11.safealert.support.BleServiceTestHarness.kfVelOf
import com.wf11.safealert.support.BleServiceTestHarness.streakOf
import com.wf11.safealert.support.BleServiceTestHarness.trackingStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * [Regression] Stop and slow-departure scenarios — pins the alert release timing to a baseline.
 * They guard against the self-lock where the alert of a departed device never clears: s2, s4 and s5b stop just above the
 * warning line, then drift away slowly and must be released; s1 is the control, a device that stays and keeps its alert.
 * The baseline (release frame, lock frames) is recorded from actual runs; the test fails here if the decision timing changes.
 * Output: app/build/sim_passby_<name>.log (tab-separated frame table + 1 SUMMARY line),
 *         app/build/sim_passby_SUMMARY.txt (1 SUMMARY line appended).
 */
@RunWith(RobolectricTestRunner::class)
class PassByStopSimulationTest {
    /**
     * After an approach the device stops just above the warning line, then either stays (s1, the control: never released,
     * keeps WARNING) or drifts away slowly (s2, s4, s5b: released at the baseline frame). No row may re-alert after its
     * release, and none may keep its alert for more than maxLockFrames frames at -84 dBm or weaker. Each row runs on a
     * new service and writes its own frame log; the frame count and probe values check the built input itself.
     */
    @Test fun stopNearWarningLine_releaseMatchesBaseline() {
        class Row(
            val name: String, val frames: Int, val rssiAt: (Int) -> Int, val probes: List<Pair<Int, Int>>,
            val dtMs: Long, val peakFrame: Int, val expectRelease: Int, val expectFinalLevel: Int?, val maxLockFrames: Int,
        )
        val hover = intArrayOf(-74, -77, -76, -79, -75, -78, -76, -77)
        val rows = listOf(
            Row("s1_hoverAboveWarn", 100, { i ->
                when {
                    i <= 22 -> -98 + i + n(i)
                    i <= 27 -> -76
                    else -> hover[(i - 28) % 8]
                }
            }, probes = listOf(28 to -74), dtMs = 1000L, peakFrame = 27,
                expectRelease = -1, expectFinalLevel = 1, maxLockFrames = 0),
            Row("s2_slowDrift3f", 88, { i ->
                when {
                    i <= 22 -> -98 + i + n(i)
                    i <= 27 -> -76
                    i <= 57 -> -77 - (i - 28) / 3
                    else -> -86 + n(i)
                }
            }, probes = listOf(57 to -86, 28 to -77), dtMs = 1000L, peakFrame = 27,
                expectRelease = 58, expectFinalLevel = null, maxLockFrames = 9),
            Row("s4_minimalStreak2f", 80, { i ->
                when {
                    i <= 20 -> -98 + i + n(i)
                    i == 21 -> -77
                    i <= 23 -> -76
                    i <= 41 -> -77 - (i - 24) / 2
                    else -> -85 + n(i)
                }
            }, probes = listOf(41 to -85, 21 to -77), dtMs = 1000L, peakFrame = 23,
                expectRelease = 39, expectFinalLevel = null, maxLockFrames = 1),
            Row("s5b_cadence400ms", 268, { i ->
                when {
                    i <= 22 -> -98 + i + n(i)
                    i <= 37 -> -76
                    i <= 167 -> -77 - (i - 38) / 10
                    else -> -89 + n(i)
                }
            }, probes = listOf(167 to -89, 38 to -77), dtMs = 400L, peakFrame = 37,
                expectRelease = 120, expectFinalLevel = null, maxLockFrames = 12),
        )
        for ((idx, r) in rows.withIndex()) {
            val label = "row ${idx + 1} ${r.name}"
            val rssi = IntArray(r.frames) { r.rssiAt(it) }
            assertEquals("$label: frame count", r.frames, rssi.size)
            for ((at, value) in r.probes) assertEquals("$label: rssi[$at]", value, rssi[at])
            run(r.name, rssi, dtMs = r.dtMs, peakFrame = r.peakFrame, expectRelease = r.expectRelease,
                expectFinalLevel = r.expectFinalLevel, maxLockFrames = r.maxLockFrames, label = label)
        }
    }

    private fun run(
        name: String,
        rssi: IntArray,
        dtMs: Long = FRAME_DT_MS,
        peakFrame: Int,
        expectRelease: Int,
        expectFinalLevel: Int?,
        maxLockFrames: Int,
        label: String = name,
    ) {
        val service = BleServiceTestHarness.newService()
        BleServiceTestHarness.resetBetweenTests(service)
        val out = StringBuilder("i\ttMs\trssi\tlevel\ttrack\twStreak\tkfVel\tkfRssi\tpEma\treceding\tbcast\n")
        var release = -1
        var reAlerts = 0
        var bcastAfterPeak = 0
        var lockFrames = 0
        var minKfVel = Double.MAX_VALUE
        var minKfVelFrame = -1
        var crossingFrames = 0
        var departingFrames = 0
        var recedingFrames = 0
        var recedingReachable = true
        var prevLevel: Int? = null
        var prevBcastCount = 0
        var level: Int? = null
        var wStreak = 0
        for (i in rssi.indices) {
            val now = T0_MS + i * dtMs
            BleServiceTestHarness.callProcessAlert(service, SIM_DEVICE_ID, rssi[i], nowMs = now)
            level = BleServiceTestHarness.alertLevelOf(service, SIM_DEVICE_ID)
            val track = trackingStateOf(service, SIM_DEVICE_ID)
            wStreak = streakOf(service, "warningContactStreakMap", SIM_DEVICE_ID)
            val kfVel = kfVelOf(service, SIM_DEVICE_ID)
            val kfRssi = simKfRssiOf(service)
            val pEma = simPEmaOf(service)
            val receding = simRecedingOf(service)
            val bcasts = BleServiceTestHarness.alertBroadcasts()
            val newBcasts = bcasts.drop(prevBcastCount).map { it.getIntExtra(BleService.EXTRA_ALERT_LEVEL, -1) }
            prevBcastCount = bcasts.size
            out.append(i).append('\t').append(now - T0_MS).append('\t').append(rssi[i]).append('\t')
                .append(level?.toString() ?: "null").append('\t').append(track).append('\t')
                .append(wStreak).append('\t').append("%.3f".format(kfVel)).append('\t')
                .append(kfRssi?.let { "%.3f".format(it) } ?: "-").append('\t')
                .append(pEma?.let { "%.3f".format(it) } ?: "-").append('\t')
                .append(receding?.toString() ?: "-").append('\t')
                .append(if (newBcasts.isEmpty()) "-" else newBcasts.joinToString(",")).append('\n')
            if (i > peakFrame) {
                if (prevLevel != null && level == null && release < 0) release = i
                if (release >= 0 && prevLevel == null && level != null) reAlerts++
                bcastAfterPeak += newBcasts.size
                if (level != null && rssi[i] <= -84) lockFrames++
            }
            if (i >= peakFrame && kfVel < minKfVel) { minKfVel = kfVel; minKfVelFrame = i }
            if (track.contains("CROSSING")) crossingFrames++
            if (track.contains("DEPARTING")) departingFrames++
            when (receding) { null -> recedingReachable = false; true -> recedingFrames++; false -> {} }
            prevLevel = level
        }
        val summary = "SUMMARY name=$name dtMs=$dtMs frames=${rssi.size} peak=$peakFrame release=$release " +
            "finalLevel=${level?.toString() ?: "null"} reAlerts=$reAlerts bcastAfterPeak=$bcastAfterPeak " +
            "lockFrames=$lockFrames lastWStreak=$wStreak minKfVel=${"%.3f".format(minKfVel)} " +
            "minKfVelFrame=$minKfVelFrame crossingFrames=$crossingFrames departingFrames=$departingFrames " +
            "recedingFrames=${if (recedingReachable) recedingFrames else -1}"
        out.append(summary).append('\n')
        File("build/sim_passby_$name.log").writeText(out.toString())
        File("build/sim_passby_SUMMARY.txt").appendText(summary + "\n")

        // Regression baseline — a changed value means the decision timing changed.
        // If the change is intended, update the expected values at the call site; otherwise it is a regression.
        // The full per-frame table is in app/build/sim_passby_${name}.log.
        assertEquals("$label 해제 프레임", expectRelease, release)
        assertEquals("$label 최종 레벨", expectFinalLevel, level)
        assertEquals("$label 해제 후 재경보(플래핑)", 0, reAlerts)
        assertTrue(
            "$label 이탈 후 경보 잔류 ${lockFrames}프레임 > 허용 ${maxLockFrames}",
            lockFrames <= maxLockFrames,
        )
    }
}

private const val T0_MS = 1_000_000L
private const val FRAME_DT_MS = 1000L
private const val SIM_DEVICE_ID = "AA:BB:CC:DD:EE:5B"
private fun n(i: Int) = NOISE_4[i % 4]

/** Kalman-estimated RSSI (dBm). null if the field is unreachable or the device is not registered -> logged as "-". */
@Suppress("UNCHECKED_CAST")
private fun simKfRssiOf(service: BleService): Double? = try {
    (ReflectionHelpers.getField(service, "kalmanFilters") as Map<String, KalmanFilter>)[SIM_DEVICE_ID]?.estimatedRssi
} catch (e: Exception) { null }

/** Post-filter P-EMA state: BleService.pEmaFilter (RssiPreFilter).emaState[deviceId]. null if unreachable -> "-". */
@Suppress("UNCHECKED_CAST")
private fun simPEmaOf(service: BleService): Double? = try {
    val filter = ReflectionHelpers.getField<Any>(service, "pEmaFilter")
    (ReflectionHelpers.getField(filter, "emaState") as Map<String, Double>)[SIM_DEVICE_ID]
} catch (e: Exception) { null }

/** true if the device is in recedingStartMap. null if the field is unreachable -> "-" / recedingFrames=-1. */
private fun simRecedingOf(service: BleService): Boolean? = try {
    (ReflectionHelpers.getField(service, "recedingStartMap") as Map<String, *>).containsKey(SIM_DEVICE_ID)
} catch (e: Exception) { null }
