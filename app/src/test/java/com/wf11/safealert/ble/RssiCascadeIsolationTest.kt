package com.wf11.safealert.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Per-device filter state isolation test.
 *
 * `MedianFilter` and `RssiPreFilter` both keep per-device state in a `MutableMap` keyed by `deviceId`.
 * `KalmanFilter` holds one device's state and is wired as a separate instance per device (the `kalmanFilters`
 * map in AlertStateMachine), so it has no shared map and is outside this file's isolation scope — this file
 * covers only the median and prefilter stages.
 *
 * No record-then-freeze here: the expected value is defined by a **relation** ("equals the output of a
 * separate, solo run"), so there are no numbers to freeze. `INPUT_A`/`INPUT_B` are declared independently in
 * this file even though their values match the sequences in RssiCascadeTest.kt (redeclared on purpose; the two
 * files are not coupled).
 *
 * Failure message format: `"isolation/<state> frame=<i> stage=<median|prefilter>"`, prefixed with `"row <n> "` in the table test.
 */
class RssiCascadeIsolationTest {

    companion object {
        const val DEVICE_01 = "AA:BB:CC:DD:EE:01"
        const val DEVICE_02 = "AA:BB:CC:DD:EE:02"

        /** device01 sequence — monotonic approach (hand-designed, not a field capture). */
        val INPUT_A = intArrayOf(-92, -90, -88, -87, -85, -83, -82, -80, -78, -77, -75, -73, -72, -70, -68, -67, -65, -63, -62, -60)

        /** device02 sequence — a monotonic departure, unlike device01. The contrast makes any interference obvious. */
        val INPUT_B = intArrayOf(-60, -62, -63, -65, -67, -68, -70, -72, -73, -75, -77, -78, -80, -82, -83, -85, -87, -88, -90, -92)
    }

    /** Baseline median/prefilter output for device01 pushed alone (no other device involved). */
    private fun soloBaseline(): Pair<IntArray, IntArray> {
        val medianFilter = MedianFilter()
        val rssiPreFilter = RssiPreFilter()
        val medianOut = IntArray(INPUT_A.size)
        val prefilterOut = IntArray(INPUT_A.size)
        for (i in INPUT_A.indices) {
            val m = medianFilter.push(DEVICE_01, INPUT_A[i])
            val p = rssiPreFilter.push(DEVICE_01, m, prevVel = 0.0, fallBoost = false)
            medianOut[i] = m
            prefilterOut[i] = p
        }
        return Pair(medianOut, prefilterOut)
    }

    /**
     * (1) Another device never changes device01: even when device01 is pushed alternately with device02 (a different
     * kind of sequence), device01's per-frame output must exactly match the solo baseline — whether device02 is never
     * cleared (row 1; commenting out its two push lines must leave the row passing, a manual check that there is no
     * cross-contamination) or cleared alone (`clear()`) mid-interleave after frame 10 (row 2).
     * Each row starts from new filters.
     */
    @Test
    fun otherDevice_pushesAndClears_leaveDeviceOneUnchanged() {
        val (baseMedian, basePrefilter) = soloBaseline()

        for ((label, clearAtIndex) in listOf("row 1 isolation/interleaved" to null, "row 2 isolation/afterClear" to 10)) {
            val medianFilter = MedianFilter()
            val rssiPreFilter = RssiPreFilter()
            for (i in INPUT_A.indices) {
                val m1 = medianFilter.push(DEVICE_01, INPUT_A[i])
                val p1 = rssiPreFilter.push(DEVICE_01, m1, prevVel = 0.0, fallBoost = false)
                val m2 = medianFilter.push(DEVICE_02, INPUT_B[i])
                rssiPreFilter.push(DEVICE_02, m2, prevVel = 0.0, fallBoost = false)

                if (i == clearAtIndex) {
                    medianFilter.clear(DEVICE_02)
                    rssiPreFilter.clear(DEVICE_02)
                }

                assertEquals("$label frame=$i stage=median", baseMedian[i], m1)
                assertEquals("$label frame=$i stage=prefilter", basePrefilter[i], p1)
            }
        }
    }

    /**
     * (2) `clearAll()` — returns every device to cold start. Right after it, `MedianFilter.isFull(device01)`
     * must be false, and pushing device01 again from the start must reproduce the solo baseline exactly.
     */
    @Test
    fun clearAll_resetsAllDevicesToColdStart() {
        val (baseMedian, basePrefilter) = soloBaseline()

        val medianFilter = MedianFilter()
        val rssiPreFilter = RssiPreFilter()

        // Push device01 until its window is full (3+ frames) so it is no longer cold.
        for (i in 0 until 5) {
            val m = medianFilter.push(DEVICE_01, INPUT_A[i])
            rssiPreFilter.push(DEVICE_01, m, prevVel = 0.0, fallBoost = false)
            medianFilter.push(DEVICE_02, INPUT_B[i])
            rssiPreFilter.push(DEVICE_02, INPUT_B[i], prevVel = 0.0, fallBoost = false)
        }

        medianFilter.clearAll()
        rssiPreFilter.clearAll()

        assertFalse("isolation/clearAll device01 not full after clearAll() stage=median", medianFilter.isFull(DEVICE_01))

        for (i in INPUT_A.indices) {
            val m = medianFilter.push(DEVICE_01, INPUT_A[i])
            val p = rssiPreFilter.push(DEVICE_01, m, prevVel = 0.0, fallBoost = false)
            assertEquals("isolation/clearAllReplay frame=$i stage=median", baseMedian[i], m)
            assertEquals("isolation/clearAllReplay frame=$i stage=prefilter", basePrefilter[i], p)
        }
    }
}
