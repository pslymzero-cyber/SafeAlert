package com.wf11.safealert.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 기기 간 필터 상태 격리 테스트 (D-07).
 *
 * `MedianFilter`·`RssiPreFilter` 는 둘 다 `deviceId` 키의 `MutableMap` 으로 기기별 상태를
 * 보관한다. `KalmanFilter` 는 기기별로 별도 인스턴스를 새로 만드는 배선(BleService.kt:1473
 * 부근)이라 공유 맵이 없으므로 이 파일의 격리 검증 범위 밖이다 — 이 파일은 median/prefilter
 * 두 단만 다룬다.
 *
 * 여기서 record-then-freeze(D-09) 는 쓰지 않는다: 기대값이 "다른 실행(단독 실행)의 출력과
 * 같다"는 **관계**로 정의되므로 숫자를 동결할 필요 자체가 없다. `INPUT_A`/`INPUT_B` 는
 * RssiCascadeTest.kt 의 시퀀스와 값이 같더라도(재선언, 두 파일은 결합하지 않는다) 이 파일
 * 안에서 독립적으로 선언한다.
 *
 * 실패 메시지 규약(D-19 확장): `"isolation/<state> frame=<i> stage=<median|prefilter>"`.
 */
class RssiCascadeIsolationTest {

    companion object {
        const val DEVICE_01 = "AA:BB:CC:DD:EE:01"
        const val DEVICE_02 = "AA:BB:CC:DD:EE:02"

        /** device01 시퀀스 — 단조 접근(수기 설계, 실기 캡처 아님). */
        val INPUT_A = intArrayOf(-92, -90, -88, -87, -85, -83, -82, -80, -78, -77, -75, -73, -72, -70, -68, -67, -65, -63, -62, -60)

        /** device02 시퀀스 — device01 과 성격이 다른 단조 이탈. 간섭 유무를 뚜렷이 드러내기 위한 대비값. */
        val INPUT_B = intArrayOf(-60, -62, -63, -65, -67, -68, -70, -72, -73, -75, -77, -78, -80, -82, -83, -85, -87, -88, -90, -92)
    }

    /** device01 을 단독으로(다른 기기 개입 없이) 밀어넣었을 때의 median/prefilter 기준 출력. */
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
     * (1) 인터리브 불변성 — device01 을 device02(다른 성격의 시퀀스)와 번갈아 밀어넣어도
     * device01 의 프레임별 출력이 단독 실행 기준값과 정확히 일치해야 한다.
     */
    @Test
    fun interleavedPush_deviceOneMatchesSoloBaseline() {
        val (baseMedian, basePrefilter) = soloBaseline()

        val medianFilter = MedianFilter()
        val rssiPreFilter = RssiPreFilter()
        for (i in INPUT_A.indices) {
            val m1 = medianFilter.push(DEVICE_01, INPUT_A[i])
            val p1 = rssiPreFilter.push(DEVICE_01, m1, prevVel = 0.0, fallBoost = false)
            // device02 를 매 프레임 함께 밀어넣는다 — 아래 두 줄을 주석 처리해도 device01 어서션은
            // 그대로 통과해야 한다(교차오염 없음의 수동 검증 절차, acceptance_criteria 항목).
            val m2 = medianFilter.push(DEVICE_02, INPUT_B[i])
            rssiPreFilter.push(DEVICE_02, m2, prevVel = 0.0, fallBoost = false)

            assertEquals("isolation/interleaved frame=$i stage=median", baseMedian[i], m1)
            assertEquals("isolation/interleaved frame=$i stage=prefilter", basePrefilter[i], p1)
        }
    }

    /**
     * (2) 선택적 clear — 인터리브 중간에 device02 만 `clear()` 해도 device01 의 출력은
     * 영향받지 않는다.
     */
    @Test
    fun selectiveClear_onlyAffectsTargetDevice() {
        val (baseMedian, basePrefilter) = soloBaseline()

        val medianFilter = MedianFilter()
        val rssiPreFilter = RssiPreFilter()
        val clearAtIndex = 10

        for (i in INPUT_A.indices) {
            val m1 = medianFilter.push(DEVICE_01, INPUT_A[i])
            val p1 = rssiPreFilter.push(DEVICE_01, m1, prevVel = 0.0, fallBoost = false)
            val m2 = medianFilter.push(DEVICE_02, INPUT_B[i])
            rssiPreFilter.push(DEVICE_02, m2, prevVel = 0.0, fallBoost = false)

            if (i == clearAtIndex) {
                medianFilter.clear(DEVICE_02)
                rssiPreFilter.clear(DEVICE_02)
            }

            assertEquals("isolation/afterClear frame=$i stage=median", baseMedian[i], m1)
            assertEquals("isolation/afterClear frame=$i stage=prefilter", basePrefilter[i], p1)
        }
    }

    /**
     * (3) `clearAll()` — 모든 기기를 콜드스타트로 되돌린다. 직후 `MedianFilter.isFull(device01)`
     * 은 반드시 false 여야 하고, device01 을 처음부터 다시 밀어넣으면 단독 실행 기준값을
     * 그대로 재현해야 한다.
     */
    @Test
    fun clearAll_resetsAllDevicesToColdStart() {
        val (baseMedian, basePrefilter) = soloBaseline()

        val medianFilter = MedianFilter()
        val rssiPreFilter = RssiPreFilter()

        // device01 을 윈도우가 가득 찰 때까지(3프레임 이상) 밀어넣어 비-콜드 상태로 만든다.
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
