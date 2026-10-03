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
 * Reverse/loading special-alert quality.
 *  - A first-detection special alert goes through the same confirmation as a normal alert → at the same RSSI it never sounds before IDLE.
 *  - A device already alerting that switches to reverse gets the special alert immediately.
 *  - The approach streak ignores short gaps of up to 300ms.
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
        for (state in listOf(BleConstants.PSTATE_REVERSE, BleConstants.PSTATE_LOADING)) {
            val service = BleServiceTestHarness.newService()
            var clock = 1_000L
            repeat(10) {
                BleServiceTestHarness.callProcessAlert(service, id, -45, remoteState = payload(BleConstants.PSTATE_IDLE), payloadPresent = true, nowMs = clock)
                clock += 120L
            }
            assertNotNull("IDLE 로 먼저 경보에 들어가 있어야 한다", BleServiceTestHarness.alertLevelOf(service, id))
            assertFalse(suddenLabels(service).containsKey(id))

            val broadcasts = BleServiceTestHarness.alertBroadcasts().size
            BleServiceTestHarness.callProcessAlert(service, id, -45, remoteState = payload(state), payloadPresent = true, nowMs = clock)
            assertTrue("state=$state: 경보 중 후진·하역 전환은 같은 프레임에 특수경보", suddenLabels(service).containsKey(id))
            assertEquals(BleConstants.LEVEL_DANGER, BleServiceTestHarness.alertLevelOf(service, id))
            assertTrue("state=$state: 그 프레임에 경보 브로드캐스트", BleServiceTestHarness.alertBroadcasts().size > broadcasts)
        }

        // Alerting at WARNING (-78), then moving in to -45 while switching to reverse: DANGER comes no later than in the IDLE control
        fun dangerFrameAfterWarning(state: Int): Int? {
            val service = BleServiceTestHarness.newService()
            var clock = 1_000L
            repeat(15) {
                BleServiceTestHarness.callProcessAlert(service, id, -78, remoteState = payload(BleConstants.PSTATE_IDLE), payloadPresent = true, nowMs = clock)
                clock += 120L
            }
            assertNotNull("전환 전에 경보 중이어야 한다", BleServiceTestHarness.alertLevelOf(service, id))
            for (f in 0 until 15) {
                BleServiceTestHarness.callProcessAlert(service, id, -45, remoteState = payload(state), payloadPresent = true, nowMs = clock)
                if (BleServiceTestHarness.alertLevelOf(service, id) == BleConstants.LEVEL_DANGER) return f
                clock += 120L
            }
            return null
        }
        val rev = dangerFrameAfterWarning(BleConstants.PSTATE_REVERSE)
        val idle = dangerFrameAfterWarning(BleConstants.PSTATE_IDLE)
        assertNotNull("WARNING 경보 중 후진 전환은 DANGER 에 도달해야 한다", rev)
        assertTrue("후진 전환 DANGER 가 IDLE 대조군보다 늦다 rev=$rev idle=$idle", idle == null || rev!! <= idle)
    }

    @Test
    fun shortNonApproachFrameKeepsApproachStreak() {
        val service = BleServiceTestHarness.newService()
        val asm = ReflectionHelpers.getField<Any>(service, "asm")
        val streaks = ReflectionHelpers.getField<Map<String, Long>>(service, "approachStreakStartMap")
        fun gate(vel: Double, now: Long) = evalGate(asm, vel, now)
        fun streakMs(g: Any) = ReflectionHelpers.getField<Long>(g, "streakMs")

        gate(50.0, 1_000L)
        assertEquals(1_000L, streaks[id])

        val dip = gate(0.0, 1_120L)                       // one non-approach frame (120ms) — within grace
        assertEquals("짧은 끊김은 streak 유지", 1_000L, streaks[id])
        assertEquals(120L, streakMs(dip))

        gate(50.0, 1_240L)
        assertEquals(240L, streakMs(gate(50.0, 1_240L)))

        gate(0.0, 1_600L)                                 // last approach 1240 → 360ms > 300ms
        assertFalse("유예 초과는 streak 리셋", streaks.containsKey(id))

        // ms boundary: up to 300ms after the last approach frame is grace (<=); 301ms resets (streakMs 0)
        for ((gap, kept) in listOf(299L to true, 300L to true, 301L to false)) {
            val s = BleServiceTestHarness.newService()
            val a = ReflectionHelpers.getField<Any>(s, "asm")
            val m = ReflectionHelpers.getField<Map<String, Long>>(s, "approachStreakStartMap")
            evalGate(a, 50.0, 1_000L)
            val g = evalGate(a, 0.0, 1_000L + gap)
            assertEquals("gap=$gap", kept, m.containsKey(id))
            assertEquals("gap=$gap", if (kept) gap else 0L, streakMs(g))
        }
    }

    private fun evalGate(asm: Any, vel: Double, now: Long) = ReflectionHelpers.callInstanceMethod<Any>(
        asm, "evalTimeGate",
        ClassParameter.from(String::class.java, id),
        ClassParameter.from(Double::class.javaPrimitiveType, vel),
        ClassParameter.from(Long::class.javaPrimitiveType, now),
        ClassParameter.from(Int::class.javaPrimitiveType, 10),
    )
}
