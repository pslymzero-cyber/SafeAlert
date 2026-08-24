package com.wf11.safealert.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MedianFilter.isFull()` 워밍업 계약 테스트 (CR-01 대응).
 *
 * [왜 별도 파일인가] 골든 캐스케이드 테스트(RssiCascadeTest)는 median 단의 **출력값**만 대조하고
 *   윈도우 **충전 여부**는 보지 않는다. 격리 테스트(RssiCascadeIsolationTest)에는 `assertFalse`
 *   1건뿐이라 `isFull()` 의 true 경로가 저장소 전체에서 미검증이었다. 그 결과 `isFull()` 이 항상
 *   false 를 반환하는 회귀가 기존 11개 테스트를 전원 통과한 뒤, 프로덕션 유일 호출부인
 *   `BleService.kt:1524` 의 `val warmingUp = !medianFilter.isFull(deviceId)` 를 영구 true 로
 *   만들어 전 경보 무발령을 초래할 수 있었다.
 *
 * [커버 범위] false→true 전이 시점, 윈도우 초과 후 유지, `clear()` 후 복귀, 기기별 독립성,
 *   커스텀 windowSize, 미등록 기기. 항상-false 변이와 항상-true 변이를 모두 죽인다.
 *
 * 실패 메시지 규약(D-19 확장): `"warmup/<case> n=<표본수> stage=median"`.
 */
class MedianFilterWarmupTest {

    companion object {
        const val DEVICE_01 = "AA:BB:CC:DD:EE:01"
        const val DEVICE_02 = "AA:BB:CC:DD:EE:02"

        /** 임의의 유효 RSSI 표본. 값 자체는 isFull() 판정에 영향이 없다(개수만 관여). */
        val SAMPLES = intArrayOf(-92, -88, -85, -83, -80, -77, -75, -72, -70, -68)
    }

    /** 윈도우가 채워지기 전까지 false, 정확히 windowSize 번째 표본에서 true 로 전이한다. */
    @Test
    fun coldStart_isNotFull_untilWindowFilled() {
        val medianFilter = MedianFilter()   // windowSize = DEFAULT_WINDOW = 3

        assertFalse("warmup/coldStart n=0 stage=median", medianFilter.isFull(DEVICE_01))

        medianFilter.push(DEVICE_01, SAMPLES[0])
        assertFalse("warmup/coldStart n=1 stage=median", medianFilter.isFull(DEVICE_01))

        medianFilter.push(DEVICE_01, SAMPLES[1])
        assertFalse("warmup/coldStart n=2 stage=median", medianFilter.isFull(DEVICE_01))

        medianFilter.push(DEVICE_01, SAMPLES[2])
        assertTrue("warmup/coldStart n=3 stage=median", medianFilter.isFull(DEVICE_01))
    }

    /** FIFO 로 오래된 표본이 밀려나도 크기는 windowSize 로 유지되므로 true 가 지속된다. */
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

    /** `clear(deviceId)` 는 해당 기기만 콜드스타트로 되돌린다. */
    @Test
    fun clear_returnsDeviceToNotFull() {
        val medianFilter = MedianFilter()

        for (i in 0 until MedianFilter.DEFAULT_WINDOW) medianFilter.push(DEVICE_01, SAMPLES[i])
        assertTrue("warmup/clear n=3 before stage=median", medianFilter.isFull(DEVICE_01))

        medianFilter.clear(DEVICE_01)
        assertFalse("warmup/clear n=0 after stage=median", medianFilter.isFull(DEVICE_01))
    }

    /** 충전 상태는 기기별로 독립이다 — device01 이 가득 차도 device02 는 워밍업 구간이다. */
    @Test
    fun isFull_isPerDevice() {
        val medianFilter = MedianFilter()

        for (i in 0 until MedianFilter.DEFAULT_WINDOW) medianFilter.push(DEVICE_01, SAMPLES[i])
        medianFilter.push(DEVICE_02, SAMPLES[0])

        assertTrue("warmup/perDevice device01 n=3 stage=median", medianFilter.isFull(DEVICE_01))
        assertFalse("warmup/perDevice device02 n=1 stage=median", medianFilter.isFull(DEVICE_02))
    }

    /** 전이 시점은 하드코딩 3 이 아니라 생성자 windowSize 를 따른다. */
    @Test
    fun isFull_respectsCustomWindowSize() {
        val medianFilter = MedianFilter(windowSize = 5)

        for (i in 0 until 4) medianFilter.push(DEVICE_01, SAMPLES[i])
        assertFalse("warmup/customWindow n=4 stage=median", medianFilter.isFull(DEVICE_01))

        medianFilter.push(DEVICE_01, SAMPLES[4])
        assertTrue("warmup/customWindow n=5 stage=median", medianFilter.isFull(DEVICE_01))
    }

    /** 표본을 한 번도 받지 않은 기기는 워밍업 구간으로 취급한다(널 버퍼 경로). */
    @Test
    fun isFull_falseForUnknownDevice() {
        val medianFilter = MedianFilter()
        medianFilter.push(DEVICE_01, SAMPLES[0])

        assertFalse("warmup/unknownDevice n=0 stage=median", medianFilter.isFull("FF:FF:FF:FF:FF:FF"))
    }
}
