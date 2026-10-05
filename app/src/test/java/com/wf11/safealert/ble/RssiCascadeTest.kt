package com.wf11.safealert.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden regression test for the 3-stage RSSI filter cascade (MedianFilter → RssiPreFilter → KalmanFilter).
 *
 * The expected values are the implementation's **actual output, recorded and frozen as is** (record-then-freeze)
 * — not hand-calculated. Any bug present when they were recorded is frozen along with them. When this test fails,
 * first suspect a regression in the implementation; only then ask whether the expected values themselves are wrong.
 *
 * Expected values are re-frozen **by hand only**, always after a person reviews the diff — there is deliberately
 * no path that overwrites them automatically (no `-PupdateGolden`-style Gradle property, environment-variable
 * switch, or auto-rewrite task).
 *
 * On cold start, frame 1 (index 1) has two samples, so its median is the weaker one (MedianFilter's even-count
 * rule); the prefilter and Kalman values downstream follow from it.
 */
class RssiCascadeTest {

    companion object {
        /**
         * The single deviceId fed through the cascade wiring
         * (AlertStateMachine.processAlert). Multi-device isolation is
         * RssiCascadeIsolationTest's job.
         */
        const val DEVICE_ID = "AA:BB:CC:DD:EE:01"

        /**
         * Frame interval, from the ~120ms normal decision cycle.
         * KalmanFilter.dt is clamped to 0.05..2.0 (s), so 120ms (=0.12s) is inside the clamp range.
         * "Rounding" this value to below 50ms or above 2000ms would silently clamp dt and make the
         * golden meaningless.
         */
        const val FRAME_DT_MS = 120L

        // ── Input sequences (hand-designed synthetic values, not field captures) ────────
        val INPUT_APPROACH = intArrayOf(-92, -90, -88, -87, -85, -83, -82, -80, -78, -77, -75, -73, -72, -70, -68, -67, -65, -63, -62, -60)

        /**
         * Monotonic departure. The reverse of approach — exercises RssiPreFilter's asymmetric rise/fall α in the opposite direction.
         */
        val INPUT_DEPARTURE = intArrayOf(-60, -62, -63, -65, -67, -68, -70, -72, -73, -75, -77, -78, -80, -82, -83, -85, -87, -88, -90, -92)

        /**
         * Flat segment with outliers injected at index 5 (-45, unrealistically close) and 11
         * (-105, unrealistically far). MedianFilter(3) should absorb them.
         */
        val INPUT_IMPULSE = intArrayOf(-78, -77, -78, -79, -78, -45, -78, -77, -79, -78, -78, -105, -77, -78, -79, -78, -77, -78, -79, -78)

        /** Stationary with ±2dBm noise. */
        val INPUT_STATIONARY = intArrayOf(-80, -81, -79, -80, -82, -80, -79, -81, -80, -78, -80, -81, -80, -79, -82, -80, -81, -79, -80, -80)

        // ── approach / coldStart expected values (record-then-freeze) ───────────────────
        val EXPECTED_APPROACH_COLD_MEDIAN = intArrayOf(-92, -92, -90, -88, -87, -85, -83, -82, -80, -78, -77, -75, -73, -72, -70, -68, -67, -65, -63, -62)
        val EXPECTED_APPROACH_COLD_PREFILTER = intArrayOf(-92, -92, -91, -90, -89, -88, -87, -85, -84, -82, -80, -79, -77, -76, -74, -72, -71, -69, -67, -66)
        val EXPECTED_APPROACH_COLD_KALMAN = doubleArrayOf(
            -92.0, -92.0, -91.65266500958131, -91.17944170961161, -90.59847046088846,
            -89.90169594912372, -89.08683114951079, -87.92976772946724, -86.7180798725337,
            -85.24309113439033, -83.5632731774607, -81.95770252668598, -80.20929416997916,
            -78.56975330546526, -76.8207868213231, -74.98912201578348, -73.28870914152598,
            -71.51295682931878, -69.67821640179953, -67.97068989527916
        )

        // ── approach / warmStart expected values (record-then-freeze) ───────────────────
        // warmStart only changes the Kalman stage; median and prefilter equal the coldStart arrays.
        val EXPECTED_APPROACH_WARM_KALMAN = doubleArrayOf(
            -92.0, -92.0, -91.66995714217292, -91.20307811031887, -90.61941825644338,
            -89.91475287524315, -89.09072492489423, -87.92615900158611, -86.71026502944834,
            -85.23503438231978, -83.55862445459047, -81.95777319323957, -80.21572263330552,
            -78.58174370642026, -76.83884563092002, -75.01332330270331, -73.31721927090734,
            -71.54576368465261, -69.71514174883751, -68.01005062356688
        )

        // ── departure / coldStart expected values (record-then-freeze) ──────────────────
        val EXPECTED_DEPARTURE_COLD_MEDIAN = intArrayOf(-60, -62, -62, -63, -65, -67, -68, -70, -72, -73, -75, -77, -78, -80, -82, -83, -85, -87, -88, -90)
        val EXPECTED_DEPARTURE_COLD_PREFILTER = intArrayOf(-60, -61, -61, -62, -63, -64, -65, -67, -68, -70, -70, -71, -72, -73, -74, -75, -76, -78, -79, -80)
        val EXPECTED_DEPARTURE_COLD_KALMAN = doubleArrayOf(
            -60.0, -60.50357464855079, -60.680667143185254, -61.06047575656406, -61.576334688653915,
            -62.22180418239052, -62.99440151944591, -64.11665518409221, -65.300259015374,
            -66.75318516842233, -67.9552609671222, -69.15721555778337, -70.34951684521877,
            -71.52704078286212, -72.68769791823182, -73.83128053147331, -74.95863521981661,
            -76.25751758719778, -77.5156227093635, -78.73868708295574
        )

        // ── departure / warmStart expected values (record-then-freeze) ──────────────────
        // warmStart only changes the Kalman stage; median and prefilter equal the coldStart arrays.
        val EXPECTED_DEPARTURE_WARM_KALMAN = doubleArrayOf(
            -60.0, -60.46030408157192, -60.64449030508961, -61.026851455224445, -61.54980874333675,
            -62.20590993873243, -62.98965609438461, -64.12094097836678, -65.30991229474967,
            -66.76391387372577, -67.96528333591544, -69.16508768270303, -70.35458203278829,
            -71.52916965218866, -72.68706544575974, -73.82820907639355, -74.95349691769961,
            -76.24913633655648, -77.50473480651704, -78.72591665240128
        )

        // ── impulse / coldStart expected values (record-then-freeze) ────────────────────
        // The outliers at index 5 (-45) and 11 (-105) never appear in the 3-window median (always outvoted by their two neighbours).
        val EXPECTED_IMPULSE_COLD_MEDIAN = intArrayOf(-78, -78, -78, -78, -78, -78, -78, -77, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78)
        val EXPECTED_IMPULSE_COLD_PREFILTER = intArrayOf(-78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78)
        val EXPECTED_IMPULSE_COLD_KALMAN = doubleArrayOf(
            -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0,
            -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0
        )

        // ── stationary / coldStart expected values (record-then-freeze) ─────────────────
        val EXPECTED_STATIONARY_COLD_MEDIAN = intArrayOf(-80, -81, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -81, -80, -80, -80)
        val EXPECTED_STATIONARY_COLD_PREFILTER = intArrayOf(-80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80)
        val EXPECTED_STATIONARY_COLD_KALMAN = doubleArrayOf(
            -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0,
            -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0
        )
    }

    /**
     * Reproduces the cascade wiring in AlertStateMachine.processAlert:
     * `medianFilter.push` → `rssiPreFilter.push(prevVel=0.0, fallBoost=false)` → `kf.update(imuQScale=1.0)`.
     * `pEmaFilter` (the post-Kalman P-EMA whose output is the distance used for level decisions) is outside the
     * golden boundary, and no other test pins pEma values directly either: AlertCascadeGoldenTest sees pEma only
     * through level timing.
     *
     * The fake clock starts at `1_000_000L` (0L would be misleading, since it equals the `lastTsMs` field's initial
     * value) and advances by `FRAME_DT_MS` before each frame's `kf.update(...)` call.
     */
    private fun runCascade(input: IntArray, warmStart: Boolean, deviceId: String = DEVICE_ID): Triple<IntArray, IntArray, DoubleArray> {
        var fakeNow = 1_000_000L
        val medianFilter = MedianFilter()
        val rssiPreFilter = RssiPreFilter()
        val kf = KalmanFilter(nowMs = { fakeNow })

        if (warmStart) {
            kf.injectWarmup(rssiVal = input[0], initVel = 0.0)
        }

        val medianOut = IntArray(input.size)
        val prefilterOut = IntArray(input.size)
        val kalmanOut = DoubleArray(input.size)

        for (i in input.indices) {
            fakeNow += FRAME_DT_MS
            val medianValue = medianFilter.push(deviceId, input[i])
            val preFiltered = rssiPreFilter.push(deviceId, medianValue, prevVel = 0.0, fallBoost = false)
            val (est, _) = kf.update(preFiltered, imuQScale = 1.0)

            medianOut[i] = medianValue
            prefilterOut[i] = preFiltered
            kalmanOut[i] = est
        }

        return Triple(medianOut, prefilterOut, kalmanOut)
    }

    /** Failure message format: `"<scenario>/<startState> frame=<i> stage=<median|prefilter|kalman>"`. */
    private fun assertCascade(
        scenario: String,
        startState: String,
        actual: Triple<IntArray, IntArray, DoubleArray>,
        expectedMedian: IntArray,
        expectedPrefilter: IntArray,
        expectedKalman: DoubleArray,
    ) {
        val (median, prefilter, kalman) = actual
        for (i in expectedMedian.indices) {
            assertEquals("$scenario/$startState frame=$i stage=median", expectedMedian[i], median[i])
            assertEquals("$scenario/$startState frame=$i stage=prefilter", expectedPrefilter[i], prefilter[i])
            assertEquals("$scenario/$startState frame=$i stage=kalman", expectedKalman[i], kalman[i], 1e-9)
        }
    }

    /** Failure message format: `"<scenario>/warmStart frame=<i> stage=kalman"`. */
    private fun assertWarmKalman(scenario: String, actual: Triple<IntArray, IntArray, DoubleArray>, expectedKalman: DoubleArray) {
        val kalman = actual.third
        for (i in expectedKalman.indices) {
            assertEquals("$scenario/warmStart frame=$i stage=kalman", expectedKalman[i], kalman[i], 1e-9)
        }
    }

    /**
     * Cold and warm start in one test. Production seeds every device's Kalman filter through injectWarmup
     * (the warmStart path), so the warm Kalman column is the field path; the median and prefilter stages do not
     * depend on the start state and are asserted once on the cold run.
     */
    @Test
    fun approach_coldAndWarmStart_matchesGolden() {
        assertCascade(
            "approach", "coldStart", runCascade(INPUT_APPROACH, warmStart = false),
            EXPECTED_APPROACH_COLD_MEDIAN, EXPECTED_APPROACH_COLD_PREFILTER, EXPECTED_APPROACH_COLD_KALMAN,
        )
        assertWarmKalman("approach", runCascade(INPUT_APPROACH, warmStart = true), EXPECTED_APPROACH_WARM_KALMAN)
    }

    /** Same split as approach; the departure warm Kalman values differ from approach, so both are kept. */
    @Test
    fun departure_coldAndWarmStart_matchesGolden() {
        assertCascade(
            "departure", "coldStart", runCascade(INPUT_DEPARTURE, warmStart = false),
            EXPECTED_DEPARTURE_COLD_MEDIAN, EXPECTED_DEPARTURE_COLD_PREFILTER, EXPECTED_DEPARTURE_COLD_KALMAN,
        )
        assertWarmKalman("departure", runCascade(INPUT_DEPARTURE, warmStart = true), EXPECTED_DEPARTURE_WARM_KALMAN)
    }

    @Test
    fun impulse_coldStart_matchesGolden() {
        val actual = runCascade(INPUT_IMPULSE, warmStart = false)
        assertCascade(
            "impulse", "coldStart", actual,
            EXPECTED_IMPULSE_COLD_MEDIAN, EXPECTED_IMPULSE_COLD_PREFILTER, EXPECTED_IMPULSE_COLD_KALMAN,
        )
    }

    @Test
    fun stationary_coldStart_matchesGolden() {
        val actual = runCascade(INPUT_STATIONARY, warmStart = false)
        assertCascade(
            "stationary", "coldStart", actual,
            EXPECTED_STATIONARY_COLD_MEDIAN, EXPECTED_STATIONARY_COLD_PREFILTER, EXPECTED_STATIONARY_COLD_KALMAN,
        )
    }
}
