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

        // ── approach / coldStart 기대값 (record-then-freeze, D-09) ─────────────────────
        val EXPECTED_APPROACH_COLD_MEDIAN = intArrayOf(-92, -91, -90, -88, -87, -85, -83, -82, -80, -78, -77, -75, -73, -72, -70, -68, -67, -65, -63, -62)
        val EXPECTED_APPROACH_COLD_PREFILTER = intArrayOf(-92, -92, -91, -90, -89, -88, -86, -85, -84, -82, -80, -79, -77, -76, -74, -72, -71, -69, -67, -66)
        val EXPECTED_APPROACH_COLD_KALMAN = doubleArrayOf(
            -92.0, -92.0, -91.65266500958131, -91.17944170961161, -90.59847046088846,
            -89.90169594912372, -88.85151755797317, -87.72617861478422, -86.54512338005225,
            -85.09866000454736, -83.44452861835482, -81.86148522275899, -80.13245981903054,
            -78.50938197083461, -76.77428376274302, -74.95424539896467, -73.26355599791144,
            -71.49592904663851, -69.66797990132058, -67.96613409211149
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
}
