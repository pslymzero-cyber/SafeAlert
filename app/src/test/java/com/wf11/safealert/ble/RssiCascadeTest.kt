package com.wf11.safealert.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 3단 RSSI 필터 캐스케이드(MedianFilter → RssiPreFilter → KalmanFilter) 골든 회귀 테스트.
 *
 * 이 파일의 기대값은 v1.1.70 현행 구현의 **실제 출력을 채집해 그대로 동결**한 것이다
 * (record-then-freeze, D-09) — 손으로 계산한 값이 아니다. 따라서 채집 시점에 이미 존재하던
 * 버그가 있다면 그 버그도 함께 동결되어 있다. 이 테스트가 실패하면 먼저 "구현이 퇴행했는가"를
 * 의심하고, "기대값 자체가 틀렸는가"는 그 다음에 검토한다.
 *
 * 기대값 재동결은 항상 사람이 diff 를 검토한 뒤 **수동으로만** 한다 — 기대값을 자동으로
 * 덮어쓰는 갱신 경로(`-PupdateGolden` 류 Gradle 프로퍼티, 환경변수 스위치, 자동 재기록 태스크)는
 * 의도적으로 만들지 않는다(D-12 / P-02).
 *
 * 재동결(v1.1.96, 2026-09-16): MedianFilter 부분버퍼 짝수 표본이 평균→약한 쪽으로 바뀌어
 * 콜드스타트 2번째 표본(index 1)의 median 과 그 하류 prefilter·kalman 을 재채집해 동결했다.
 */
class RssiCascadeTest {

    companion object {
        /** 캐스케이드 배선(BleService.kt:1473-1519)에서 쓰는 단일 deviceId. 다기기 격리는 RssiCascadeIsolationTest 의 몫(D-07). */
        const val DEVICE_ID = "AA:BB:CC:DD:EE:01"

        /**
         * 프레임 간격. BleService.kt:682 의 "정상 주기 ~120ms" 기술에서 가져왔다(D-02).
         * KalmanFilter.dt 는 0.05..2.0(초) 로 클램프되므로, 120ms(=0.12s) 는 클램프 구간 안쪽이다.
         * 이 값을 "대충 반올림"해 50ms 미만이나 2000ms 초과로 바꾸면 dt 가 조용히 클램프되어
         * 골든이 무의미해진다.
         */
        const val FRAME_DT_MS = 120L

        // ── 입력 시퀀스 (수기 설계 합성값, 실기 캡처 아님 — D-10 / P-06) ────────────────
        val INPUT_APPROACH = intArrayOf(-92, -90, -88, -87, -85, -83, -82, -80, -78, -77, -75, -73, -72, -70, -68, -67, -65, -63, -62, -60)

        /** 단조 이탈. approach 의 역순 — RssiPreFilter 의 상승/하강 비대칭 α 를 반대 방향으로 드러낸다. */
        val INPUT_DEPARTURE = intArrayOf(-60, -62, -63, -65, -67, -68, -70, -72, -73, -75, -77, -78, -80, -82, -83, -85, -87, -88, -90, -92)

        /** 평탄 구간 + 인덱스 5(-45, 비현실적 근접)·11(-105, 비현실적 원거리) 이상치 주입. MedianFilter(3) 흡수 대상. */
        val INPUT_IMPULSE = intArrayOf(-78, -77, -78, -79, -78, -45, -78, -77, -79, -78, -78, -105, -77, -78, -79, -78, -77, -78, -79, -78)

        /** ±2dBm 잡음이 있는 정지. */
        val INPUT_STATIONARY = intArrayOf(-80, -81, -79, -80, -82, -80, -79, -81, -80, -78, -80, -81, -80, -79, -82, -80, -81, -79, -80, -80)

        // ── approach / coldStart 기대값 (record-then-freeze, D-09) ─────────────────────
        val EXPECTED_APPROACH_COLD_MEDIAN = intArrayOf(-92, -92, -90, -88, -87, -85, -83, -82, -80, -78, -77, -75, -73, -72, -70, -68, -67, -65, -63, -62)
        val EXPECTED_APPROACH_COLD_PREFILTER = intArrayOf(-92, -92, -91, -90, -89, -88, -87, -85, -84, -82, -80, -79, -77, -76, -74, -72, -71, -69, -67, -66)
        val EXPECTED_APPROACH_COLD_KALMAN = doubleArrayOf(
            -92.0, -92.0, -91.65266500958131, -91.17944170961161, -90.59847046088846,
            -89.90169594912372, -89.08683114951079, -87.92976772946724, -86.7180798725337,
            -85.24309113439033, -83.5632731774607, -81.95770252668598, -80.20929416997916,
            -78.56975330546526, -76.8207868213231, -74.98912201578348, -73.28870914152598,
            -71.51295682931878, -69.67821640179953, -67.97068989527916
        )

        // ── approach / warmStart 기대값 (record-then-freeze, D-09) ──────────────────────
        val EXPECTED_APPROACH_WARM_MEDIAN = EXPECTED_APPROACH_COLD_MEDIAN
        val EXPECTED_APPROACH_WARM_PREFILTER = EXPECTED_APPROACH_COLD_PREFILTER
        val EXPECTED_APPROACH_WARM_KALMAN = doubleArrayOf(
            -92.0, -92.0, -91.66995714217292, -91.20307811031887, -90.61941825644338,
            -89.91475287524315, -89.09072492489423, -87.92615900158611, -86.71026502944834,
            -85.23503438231978, -83.55862445459047, -81.95777319323957, -80.21572263330552,
            -78.58174370642026, -76.83884563092002, -75.01332330270331, -73.31721927090734,
            -71.54576368465261, -69.71514174883751, -68.01005062356688
        )

        // ── departure / coldStart 기대값 (record-then-freeze, D-09) ─────────────────────
        val EXPECTED_DEPARTURE_COLD_MEDIAN = intArrayOf(-60, -62, -62, -63, -65, -67, -68, -70, -72, -73, -75, -77, -78, -80, -82, -83, -85, -87, -88, -90)
        val EXPECTED_DEPARTURE_COLD_PREFILTER = intArrayOf(-60, -61, -61, -62, -63, -64, -65, -67, -68, -70, -70, -71, -72, -73, -74, -75, -76, -78, -79, -80)
        val EXPECTED_DEPARTURE_COLD_KALMAN = doubleArrayOf(
            -60.0, -60.50357464855079, -60.680667143185254, -61.06047575656406, -61.576334688653915,
            -62.22180418239052, -62.99440151944591, -64.11665518409221, -65.300259015374,
            -66.75318516842233, -67.9552609671222, -69.15721555778337, -70.34951684521877,
            -71.52704078286212, -72.68769791823182, -73.83128053147331, -74.95863521981661,
            -76.25751758719778, -77.5156227093635, -78.73868708295574
        )

        // ── departure / warmStart 기대값 (record-then-freeze, D-09) ─────────────────────
        val EXPECTED_DEPARTURE_WARM_MEDIAN = EXPECTED_DEPARTURE_COLD_MEDIAN
        val EXPECTED_DEPARTURE_WARM_PREFILTER = EXPECTED_DEPARTURE_COLD_PREFILTER
        val EXPECTED_DEPARTURE_WARM_KALMAN = doubleArrayOf(
            -60.0, -60.46030408157192, -60.64449030508961, -61.026851455224445, -61.54980874333675,
            -62.20590993873243, -62.98965609438461, -64.12094097836678, -65.30991229474967,
            -66.76391387372577, -67.96528333591544, -69.16508768270303, -70.35458203278829,
            -71.52916965218866, -72.68706544575974, -73.82820907639355, -74.95349691769961,
            -76.24913633655648, -77.50473480651704, -78.72591665240128
        )

        // ── impulse / coldStart 기대값 (record-then-freeze, D-09) ───────────────────────
        // 인덱스 5(-45)·11(-105) 이상치는 3-윈도 중앙값에 절대 등장하지 않는다(항상 이웃 2개에 밀려 흡수됨).
        val EXPECTED_IMPULSE_COLD_MEDIAN = intArrayOf(-78, -78, -78, -78, -78, -78, -78, -77, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78)
        val EXPECTED_IMPULSE_COLD_PREFILTER = intArrayOf(-78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78)
        val EXPECTED_IMPULSE_COLD_KALMAN = doubleArrayOf(
            -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0,
            -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0
        )

        // ── impulse / warmStart 기대값 (record-then-freeze, D-09) ───────────────────────
        val EXPECTED_IMPULSE_WARM_MEDIAN = intArrayOf(-78, -78, -78, -78, -78, -78, -78, -77, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78, -78)
        val EXPECTED_IMPULSE_WARM_PREFILTER = EXPECTED_IMPULSE_COLD_PREFILTER
        val EXPECTED_IMPULSE_WARM_KALMAN = doubleArrayOf(
            -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0,
            -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0, -78.0
        )

        // ── stationary / coldStart 기대값 (record-then-freeze, D-09) ────────────────────
        val EXPECTED_STATIONARY_COLD_MEDIAN = intArrayOf(-80, -81, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -81, -80, -80, -80)
        val EXPECTED_STATIONARY_COLD_PREFILTER = intArrayOf(-80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80, -80)
        val EXPECTED_STATIONARY_COLD_KALMAN = doubleArrayOf(
            -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0,
            -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0
        )

        // ── stationary / warmStart 기대값 (record-then-freeze, D-09) ────────────────────
        val EXPECTED_STATIONARY_WARM_MEDIAN = EXPECTED_STATIONARY_COLD_MEDIAN
        val EXPECTED_STATIONARY_WARM_PREFILTER = EXPECTED_STATIONARY_COLD_PREFILTER
        val EXPECTED_STATIONARY_WARM_KALMAN = doubleArrayOf(
            -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0,
            -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0, -80.0
        )
    }

    /**
     * 캐스케이드 배선(BleService.kt:1473-1519 재현):
     * `medianFilter.push` → `rssiPreFilter.push(prevVel=0.0, fallBoost=false)` → `kf.update(imuQScale=1.0)`.
     * `pEmaFilter`(1519행, 표시용 EMA)는 골든 경계 밖이다(D-05 / P-05).
     *
     * 가짜 클록은 `1_000_000L` 에서 시작해(0L 은 `lastTsMs` 필드 초기값과 같아 오해를 부른다)
     * 매 프레임 `kf.update(...)` 호출 직전에 `FRAME_DT_MS` 만큼 전진한다.
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

    /** 실패 메시지 규약(D-19): `"<scenario>/<startState> frame=<i> stage=<median|prefilter|kalman>"`. */
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

    @Test
    fun approach_coldStart_matchesGolden() {
        val actual = runCascade(INPUT_APPROACH, warmStart = false)
        assertCascade(
            "approach", "coldStart", actual,
            EXPECTED_APPROACH_COLD_MEDIAN, EXPECTED_APPROACH_COLD_PREFILTER, EXPECTED_APPROACH_COLD_KALMAN,
        )
    }

    @Test
    fun approach_warmStart_matchesGolden() {
        val actual = runCascade(INPUT_APPROACH, warmStart = true)
        assertCascade(
            "approach", "warmStart", actual,
            EXPECTED_APPROACH_WARM_MEDIAN, EXPECTED_APPROACH_WARM_PREFILTER, EXPECTED_APPROACH_WARM_KALMAN,
        )
    }

    @Test
    fun departure_coldStart_matchesGolden() {
        val actual = runCascade(INPUT_DEPARTURE, warmStart = false)
        assertCascade(
            "departure", "coldStart", actual,
            EXPECTED_DEPARTURE_COLD_MEDIAN, EXPECTED_DEPARTURE_COLD_PREFILTER, EXPECTED_DEPARTURE_COLD_KALMAN,
        )
    }

    @Test
    fun departure_warmStart_matchesGolden() {
        val actual = runCascade(INPUT_DEPARTURE, warmStart = true)
        assertCascade(
            "departure", "warmStart", actual,
            EXPECTED_DEPARTURE_WARM_MEDIAN, EXPECTED_DEPARTURE_WARM_PREFILTER, EXPECTED_DEPARTURE_WARM_KALMAN,
        )
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
    fun impulse_warmStart_matchesGolden() {
        val actual = runCascade(INPUT_IMPULSE, warmStart = true)
        assertCascade(
            "impulse", "warmStart", actual,
            EXPECTED_IMPULSE_WARM_MEDIAN, EXPECTED_IMPULSE_WARM_PREFILTER, EXPECTED_IMPULSE_WARM_KALMAN,
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

    @Test
    fun stationary_warmStart_matchesGolden() {
        val actual = runCascade(INPUT_STATIONARY, warmStart = true)
        assertCascade(
            "stationary", "warmStart", actual,
            EXPECTED_STATIONARY_WARM_MEDIAN, EXPECTED_STATIONARY_WARM_PREFILTER, EXPECTED_STATIONARY_WARM_KALMAN,
        )
    }
}
