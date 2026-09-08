package com.wf11.safealert.ble

import com.wf11.safealert.service.BleService
import com.wf11.safealert.support.BleServiceTestHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * [회귀] 정지·저속 이탈 시나리오 — 경보 해제 시점을 기준선에 고정한다.
 * 기준선은 v1.1.79 실측치다. 판정 타이밍이 달라지면 여기서 실패한다.
 * 출력: app/build/sim_passby_<name>.log (탭 구분 프레임 표 + SUMMARY 1줄),
 *       app/build/sim_passby_SUMMARY.txt (SUMMARY 1줄 append).
 */
@RunWith(RobolectricTestRunner::class)
class PassByStopSimulationTest {
    @Test fun s1_hoverAboveWarn() {
        val hover = intArrayOf(-71, -74, -73, -76, -72, -75, -73, -74)
        val rssi = IntArray(100) { i ->
            when {
                i <= 22 -> -95 + i + n(i)
                i <= 27 -> -73
                else -> hover[(i - 28) % 8]
            }
        }
        assertEquals(100, rssi.size); assertEquals(-71, rssi[28])
        run("s1_hoverAboveWarn", rssi, dtMs = 1000L, peakFrame = 27,
            expectRelease = -1, expectFinalLevel = 1, maxLockFrames = 0)
    }

    @Test fun s2_slowDrift3f() {
        val rssi = IntArray(88) { i ->
            when {
                i <= 22 -> -95 + i + n(i)
                i <= 27 -> -73
                i <= 57 -> -74 - (i - 28) / 3
                else -> -83 + n(i)
            }
        }
        assertEquals(88, rssi.size); assertEquals(-83, rssi[57]); assertEquals(-74, rssi[28])
        run("s2_slowDrift3f", rssi, dtMs = 1000L, peakFrame = 27,
            expectRelease = 58, expectFinalLevel = null, maxLockFrames = 9)
    }

    @Test fun s4_minimalStreak2f() {
        val rssi = IntArray(80) { i ->
            when {
                i <= 20 -> -95 + i + n(i)
                i == 21 -> -74
                i <= 23 -> -73
                i <= 41 -> -74 - (i - 24) / 2
                else -> -82 + n(i)
            }
        }
        assertEquals(80, rssi.size); assertEquals(-82, rssi[41]); assertEquals(-74, rssi[21])
        run("s4_minimalStreak2f", rssi, dtMs = 1000L, peakFrame = 23,
            expectRelease = 39, expectFinalLevel = null, maxLockFrames = 1)
    }

    @Test fun s5b_cadence400ms() {
        val rssi = IntArray(268) { i ->
            when {
                i <= 22 -> -95 + i + n(i)
                i <= 37 -> -73
                i <= 167 -> -74 - (i - 38) / 10
                else -> -86 + n(i)
            }
        }
        assertEquals(268, rssi.size); assertEquals(-86, rssi[167]); assertEquals(-74, rssi[38])
        run("s5b_cadence400ms", rssi, dtMs = 400L, peakFrame = 37,
            expectRelease = 120, expectFinalLevel = null, maxLockFrames = 12)
    }

    private fun run(
        name: String,
        rssi: IntArray,
        dtMs: Long = FRAME_DT_MS,
        peakFrame: Int,
        expectRelease: Int,
        expectFinalLevel: Int?,
        maxLockFrames: Int,
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
            val track = simTrackOf(service)
            wStreak = simStreakOf(service, "warningContactStreakMap")
            val kfVel = simKfVelOf(service)
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
                if (level != null && rssi[i] <= -81) lockFrames++
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

        // 회귀 기준선 — 값이 바뀌었다면 판정 타이밍이 달라졌다는 뜻이다.
        // 의도한 변경이면 호출부 기대값을 갱신하고, 아니면 회귀다.
        // 프레임별 전문은 app/build/sim_passby_${name}.log 에 남는다.
        assertEquals("${name} 해제 프레임", expectRelease, release)
        assertEquals("${name} 최종 레벨", expectFinalLevel, level)
        assertEquals("${name} 해제 후 재경보(플래핑)", 0, reAlerts)
        assertTrue(
            "${name} 이탈 후 경보 잔류 ${lockFrames}프레임 > 허용 ${maxLockFrames}",
            lockFrames <= maxLockFrames,
        )
    }
}

private const val T0_MS = 1_000_000L
private const val FRAME_DT_MS = 1000L
private const val SIM_DEVICE_ID = "AA:BB:CC:DD:EE:5B"
private val NOISE = intArrayOf(0, -1, 1, 0)
private fun n(i: Int) = NOISE[i % 4]

private fun simTrackOf(service: BleService): String {
    val map = ReflectionHelpers.getField(service, "trackingStateMap") as Map<String, *>
    return map[SIM_DEVICE_ID]?.toString() ?: "NONE"
}

@Suppress("UNCHECKED_CAST")
private fun simStreakOf(service: BleService, fieldName: String): Int {
    val map = ReflectionHelpers.getField(service, fieldName) as Map<String, Int>
    return map[SIM_DEVICE_ID] ?: 0
}

@Suppress("UNCHECKED_CAST")
private fun simKfVelOf(service: BleService): Double {
    val map = ReflectionHelpers.getField(service, "kalmanFilters") as Map<String, KalmanFilter>
    return map[SIM_DEVICE_ID]?.estimatedVel ?: 0.0
}

/** 칼만 추정 RSSI(dBm). 필드 미도달·미등록 시 null -> 로그 "-". */
@Suppress("UNCHECKED_CAST")
private fun simKfRssiOf(service: BleService): Double? = try {
    (ReflectionHelpers.getField(service, "kalmanFilters") as Map<String, KalmanFilter>)[SIM_DEVICE_ID]?.estimatedRssi
} catch (e: Exception) { null }

/** 후처리 P-EMA 상태값: BleService.pEmaFilter(RssiPreFilter).emaState[deviceId]. 미도달 시 null -> "-". */
@Suppress("UNCHECKED_CAST")
private fun simPEmaOf(service: BleService): Double? = try {
    val filter = ReflectionHelpers.getField<Any>(service, "pEmaFilter")
    (ReflectionHelpers.getField(filter, "emaState") as Map<String, Double>)[SIM_DEVICE_ID]
} catch (e: Exception) { null }

/** recedingStartMap 에 기기가 등록돼 있으면 true. 필드 미도달 시 null -> "-" / recedingFrames=-1. */
private fun simRecedingOf(service: BleService): Boolean? = try {
    (ReflectionHelpers.getField(service, "recedingStartMap") as Map<String, *>).containsKey(SIM_DEVICE_ID)
} catch (e: Exception) { null }
