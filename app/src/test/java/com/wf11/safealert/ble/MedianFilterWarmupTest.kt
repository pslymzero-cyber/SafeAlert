package com.wf11.safealert.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MedianFilter.isFull()` warm-up contract test.
 *
 * [Why a separate file] The golden cascade test (RssiCascadeTest) compares only the median stage's **output**, not
 *   whether the window is **full**, and the isolation test (RssiCascadeIsolationTest) has a single `assertFalse`, so
 *   no other test checks the true path of `isFull()` directly. Other suites cover warm-up only indirectly: an
 *   always-false `isFull()` would make `val warmingUp = !fx.medianFilter.isFull(deviceId)` in AlertStateMachine.kt
 *   (the only production call site) permanently true, which blocks the special-alert and TTC paths (both gate on
 *   `!warmingUp`) and lets normal alerts through only on fastContact, so tests such as SpecialAlertTimeGateTest and
 *   Sim0914SpecialGateTest would fail, but only indirectly. This test pins the `isFull()` true path directly.
 *
 * [Coverage] the false→true transition point, staying true after the window overflows, back to false after `clear()`,
 *   per-device independence, a custom windowSize, and an unknown device. Kills both the always-false and the
 *   always-true mutant.
 *
 * Failure message format: `"warmup/<case> n=<samples> stage=median"`.
 */
class MedianFilterWarmupTest {

    companion object {
        const val DEVICE_01 = "AA:BB:CC:DD:EE:01"
        const val DEVICE_02 = "AA:BB:CC:DD:EE:02"

        /** Arbitrary valid RSSI samples. The values do not affect isFull(); only the count matters. */
        val SAMPLES = intArrayOf(-92, -88, -85, -83, -80, -77, -75, -72, -70, -68)
    }

    /** FIFO pushes old samples out, but the size stays at windowSize, so it stays true. */
    @Test
    fun isFull_staysTrue_afterWindowOverflows() {
        val medianFilter = MedianFilter()

        for (i in SAMPLES.indices) {
            medianFilter.push(DEVICE_01, SAMPLES[i])
            val n = i + 1
            if (n < MedianFilter.DEFAULT_WINDOW) {
                assertFalse("warmup/overflow n=$n stage=median", medianFilter.isFull(DEVICE_01))
            } else {
                assertTrue("warmup/overflow n=$n stage=median", medianFilter.isFull(DEVICE_01))
            }
        }
    }

    /** `clear(deviceId)` returns only that device to cold start. */
    @Test
    fun clear_returnsDeviceToNotFull() {
        val medianFilter = MedianFilter()

        for (i in 0 until MedianFilter.DEFAULT_WINDOW) medianFilter.push(DEVICE_01, SAMPLES[i])
        assertTrue("warmup/clear n=3 before stage=median", medianFilter.isFull(DEVICE_01))

        medianFilter.clear(DEVICE_01)
        assertFalse("warmup/clear n=0 after stage=median", medianFilter.isFull(DEVICE_01))
    }

    /** Fill state is per device — device01 being full leaves device02 still warming up. */
    @Test
    fun isFull_isPerDevice() {
        val medianFilter = MedianFilter()

        for (i in 0 until MedianFilter.DEFAULT_WINDOW) medianFilter.push(DEVICE_01, SAMPLES[i])
        medianFilter.push(DEVICE_02, SAMPLES[0])

        assertTrue("warmup/perDevice device01 n=3 stage=median", medianFilter.isFull(DEVICE_01))
        assertFalse("warmup/perDevice device02 n=1 stage=median", medianFilter.isFull(DEVICE_02))
    }

    /** The transition point follows the constructor's windowSize, not a hard-coded 3. */
    @Test
    fun isFull_respectsCustomWindowSize() {
        val medianFilter = MedianFilter(windowSize = 5)

        for (i in 0 until 4) medianFilter.push(DEVICE_01, SAMPLES[i])
        assertFalse("warmup/customWindow n=4 stage=median", medianFilter.isFull(DEVICE_01))

        medianFilter.push(DEVICE_01, SAMPLES[4])
        assertTrue("warmup/customWindow n=5 stage=median", medianFilter.isFull(DEVICE_01))
    }

    /** A device that has never received a sample counts as warming up (null-buffer path). */
    @Test
    fun isFull_falseForUnknownDevice() {
        val medianFilter = MedianFilter()
        medianFilter.push(DEVICE_01, SAMPLES[0])

        assertFalse("warmup/unknownDevice n=0 stage=median", medianFilter.isFull("FF:FF:FF:FF:FF:FF"))
    }
}
