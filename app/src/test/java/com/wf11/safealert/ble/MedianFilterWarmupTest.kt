package com.wf11.safealert.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `MedianFilter.isFull()` warm-up contract test.
 *
 * [Why a separate file] The golden cascade test (RssiCascadeTest) compares only the median stage's **output**, not
 *   whether the window is **full**, and the isolation test (RssiCascadeIsolationTest) has a single `assertFalse`, so
 *   no other test checks the true path of `isFull()` directly. Other suites cover warm-up only indirectly: an
 *   always-false `isFull()` would make `val warmingUp = !fx.medianFilter.isFull(deviceId)` in AlertStateMachine.kt
 *   (the only production call site) permanently true, which blocks the special-alert and TTC paths (both gate on
 *   `!warmingUp`) and lets normal alerts through only on fastContact, so tests such as SpecialAlertTimeGateTest would
 *   fail, but only indirectly. This test pins the `isFull()` true path directly.
 *
 * [Coverage] the false→true transition point, staying true after the window overflows, back to false after `clear()`,
 *   per-device independence, and an unknown device. Kills both the always-false and the always-true mutant.
 *   Production only builds MedianFilter() with DEFAULT_WINDOW, so only the default window is tested here; the default's
 *   value is pinned by RssiCascadeTest's frozen median arrays (the tests here count against DEFAULT_WINDOW itself).
 *
 * Failure message format: `"row <i> warmup/<case> n=<samples> stage=median"`.
 */
class MedianFilterWarmupTest {

    companion object {
        const val DEVICE_01 = "AA:BB:CC:DD:EE:01"
        const val DEVICE_02 = "AA:BB:CC:DD:EE:02"

        /** Arbitrary valid RSSI samples. The values do not affect isFull(); only the count matters. */
        val SAMPLES = intArrayOf(-92, -88, -85, -83, -80, -77, -75, -72, -70, -68)
    }

    /**
     * isFull(device) is true exactly while that device's own window holds DEFAULT_WINDOW samples: it turns true at the
     * DEFAULT_WINDOW-th sample and stays true as the FIFO pushes old samples out (the size stays at the window),
     * `clear(deviceId)` returns that device to cold start, device02's samples do not count for device01 and the other
     * way round, and a device that never received a sample counts as warming up (null-buffer path).
     * Each row starts from a new filter: device01 gets its samples, then device02, then device01 is cleared if asked.
     */
    @Test
    fun isFull_onlyWhileThatDeviceHoldsAFullWindow() {
        class Row(
            val label: String, val device01: Int, val device02: Int = 0, val clear01: Boolean = false,
            val query: String = DEVICE_01, val full: Boolean,
        )
        val window = MedianFilter.DEFAULT_WINDOW
        val rows = (1..SAMPLES.size).map { n -> Row("warmup/overflow n=$n stage=median", device01 = n, full = n >= window) } +
            listOf(
                Row("warmup/clear n=3 before stage=median", device01 = window, full = true),
                Row("warmup/clear n=0 after stage=median", device01 = window, clear01 = true, full = false),
                Row("warmup/perDevice device01 n=3 stage=median", device01 = window, device02 = 1, full = true),
                Row("warmup/perDevice device02 n=1 stage=median", device01 = window, device02 = 1, query = DEVICE_02, full = false),
                Row("warmup/unknownDevice n=0 stage=median", device01 = 1, query = "FF:FF:FF:FF:FF:FF", full = false),
            )
        for ((i, r) in rows.withIndex()) {
            val medianFilter = MedianFilter()
            for (k in 0 until r.device01) medianFilter.push(DEVICE_01, SAMPLES[k])
            for (k in 0 until r.device02) medianFilter.push(DEVICE_02, SAMPLES[k])
            if (r.clear01) medianFilter.clear(DEVICE_01)
            assertEquals("row ${i + 1} ${r.label}", r.full, medianFilter.isFull(r.query))
        }
    }
}
