package com.wf11.safealert.ble

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
 * (v1.1.94) 후진·하역 특수경보 품질.
 *  - 첫 감지 특수경보는 일반 경보와 같은 확인을 거친다 → 같은 RSSI 에서 IDLE 보다 먼저 울리지 않는다.
 *  - 이미 경보 중인 기기가 후진으로 바뀌면 즉시 특수경보.
 *  - 접근 streak 은 300ms 이내의 짧은 끊김을 무시한다.
 */
@RunWith(RobolectricTestRunner::class)
class SpecialAlertTimeGateTest {

    private data class Result(val firstAlertFrame: Int?, val firstDangerFrame: Int?, val special: Boolean)

    private val id = "SA-TEST-01"
    private fun payload(state: Int) = BleConstants.encodePayload(BleConstants.CAT_FORKLIFT, state).toInt() and 0xFF
    private fun suddenLabels(service: BleService) = ReflectionHelpers.getField<Map<String, String>>(service, "suddenLabelMap")

    private fun run(state: Int, rssiAt: (Int) -> Int, frames: Int = 150): Result {
        val service = BleServiceTestHarness.newService()
        var clock = 1_000L
        var firstAlert: Int? = null
        for (f in 0 until frames) {
            BleServiceTestHarness.callProcessAlert(service, id, rssiAt(f), remoteState = payload(state), payloadPresent = true, nowMs = clock)
            val level = BleServiceTestHarness.alertLevelOf(service, id)
            if (level != null && firstAlert == null) firstAlert = f
            if (level == BleConstants.LEVEL_DANGER) return Result(firstAlert, f, suddenLabels(service).containsKey(id))
            clock += 120L
        }
        return Result(firstAlert, null, false)
    }

    private val noise = intArrayOf(0, -3, 2, -1, 3, -2)
    private val scenarios: List<Pair<String, (Int) -> Int>> = listOf(
        "slow" to { f -> minOf(-90 + f / 2, -40) },
        "fast" to { f -> minOf(-90 + f * 2, -40) },
        "jitter" to { f -> minOf(-90 + f, -40) + noise[f % noise.size] },
        "step" to { _ -> -45 },
        "weakThenStep" to { f -> if (f < 10) -90 else -45 },
    )

    @Test
    fun firstDetectionReverseNeverAlertsBeforeIdle() {
        for ((name, seq) in scenarios) {
            val rev = run(BleConstants.PSTATE_REVERSE, seq)
            val idle = run(BleConstants.PSTATE_IDLE, seq)
            println("[STG] $name REVERSE=$rev IDLE=$idle")
            assertNotNull("$name: 후진 기기도 경보가 떠야 한다", rev.firstAlertFrame)
            assertNotNull("$name: 후진 기기도 DANGER 에 도달해야 한다", rev.firstDangerFrame)
            assertTrue("$name: 후진 첫 경보가 IDLE 보다 빠르다 $rev vs $idle", rev.firstAlertFrame!! >= idle.firstAlertFrame!!)
            assertTrue("$name: 후진 DANGER 가 IDLE 보다 빠르다 $rev vs $idle", rev.firstDangerFrame!! >= idle.firstDangerFrame!!)
        }
    }

    @Test
    fun alertedDeviceSwitchingToReverseGetsSpecialImmediately() {
        val service = BleServiceTestHarness.newService()
        var clock = 1_000L
        repeat(10) {
            BleServiceTestHarness.callProcessAlert(service, id, -45, remoteState = payload(BleConstants.PSTATE_IDLE), payloadPresent = true, nowMs = clock)
            clock += 120L
        }
        assertNotNull("IDLE 로 먼저 경보에 들어가 있어야 한다", BleServiceTestHarness.alertLevelOf(service, id))
        assertFalse(suddenLabels(service).containsKey(id))

        BleServiceTestHarness.callProcessAlert(service, id, -45, remoteState = payload(BleConstants.PSTATE_REVERSE), payloadPresent = true, nowMs = clock)
        assertTrue("경보 중 후진 전환은 같은 프레임에 특수경보", suddenLabels(service).containsKey(id))
        assertEquals(BleConstants.LEVEL_DANGER, BleServiceTestHarness.alertLevelOf(service, id))
    }

    @Test
    fun shortNonApproachFrameKeepsApproachStreak() {
        val service = BleServiceTestHarness.newService()
        val asm = ReflectionHelpers.getField<Any>(service, "asm")
        val streaks = ReflectionHelpers.getField<Map<String, Long>>(service, "approachStreakStartMap")
        fun gate(vel: Double, now: Long) = ReflectionHelpers.callInstanceMethod<Any>(
            asm, "evalTimeGate",
            ClassParameter.from(String::class.java, id),
            ClassParameter.from(Double::class.javaPrimitiveType, vel),
            ClassParameter.from(Long::class.javaPrimitiveType, now),
            ClassParameter.from(Int::class.javaPrimitiveType, 10),
        )
        fun streakMs(g: Any) = ReflectionHelpers.getField<Long>(g, "streakMs")

        gate(50.0, 1_000L)
        assertEquals(1_000L, streaks[id])

        val dip = gate(0.0, 1_120L)                       // 비접근 1프레임(120ms) — 유예
        assertEquals("짧은 끊김은 streak 유지", 1_000L, streaks[id])
        assertEquals(120L, streakMs(dip))

        gate(50.0, 1_240L)
        assertEquals(240L, streakMs(gate(50.0, 1_240L)))

        gate(0.0, 1_600L)                                 // 마지막 접근 1240 → 360ms > 300ms
        assertFalse("유예 초과는 streak 리셋", streaks.containsKey(id))
    }
}
