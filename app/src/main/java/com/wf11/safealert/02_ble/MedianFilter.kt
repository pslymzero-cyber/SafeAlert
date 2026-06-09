package com.wf11.safealert.ble

/**
 * RSSI 비선형 순위통계 전처리 — 슬라이딩 윈도우 중앙값(Median) 필터 (v1.0.45)
 *
 * [설계 의도] 비대칭 EMA·2D 칼만은 모두 '선형' 필터라 단발 임펄스(철제랙 다중경로 반사로
 *   1프레임만 +쪽으로 튀는 값)를 평균/공분산에 그대로 흡수해 잔향(echo)을 남긴다. 중앙값 필터는
 *   '순위통계' 기반 비선형 필터로, 윈도우 내 단발 이상치를 '선택되지 않게' 만들어 임펄스를
 *   구조적으로 제거한다. 따라서 선형 단계(EMA→칼만) '앞단'에 배치해, 임펄스가 칼만 속도(kfVel)를
 *   오염시키기 전에 차단한다.
 *
 * [트레이드오프] 윈도우 N 의 그룹지연 = (N−1)/2 샘플. N=3 이면 약 1프레임의 상승엣지 지연이
 *   생긴다. 이 지연은 BleService 의 D-Bypass(kfVel 직결 Time-Gate)와 '돌진 시 칼만 FAST 조건부
 *   승격'이 상호 보완해 생존 반응속도를 확보한다.
 *
 * [부분버퍼] 콜드스타트(윈도우 미충전) 구간은 '가용 표본의 중앙값'을 반환한다(표본 1개=raw 통과).
 *   이 구간의 오염은 BleService 워밍업 가드(윈도우 충전 전 발령 보류)가 차단한다.
 */
class MedianFilter(private val windowSize: Int = DEFAULT_WINDOW) {

    companion object {
        const val DEFAULT_WINDOW = 3   // 임펄스 제거 vs 그룹지연 균형점 (지연 약 1프레임)
    }

    // 기기별 슬라이딩 윈도우(FIFO). 최신 windowSize 개 표본만 유지.
    private val buffers = mutableMapOf<String, ArrayDeque<Int>>()

    /**
     * 신규 RSSI 표본을 윈도우에 넣고 현재 윈도우의 중앙값을 반환.
     *
     * @param deviceId 기기 식별자 (기기별 독립 윈도우)
     * @param rssi     원시 RSSI (dBm, 음수)
     * @return 윈도우 중앙값(임펄스 제거된 RSSI). 짝수 표본은 중앙 2개의 정수평균.
     */
    fun push(deviceId: String, rssi: Int): Int {
        val buf = buffers.getOrPut(deviceId) { ArrayDeque() }
        if (buf.size >= windowSize) buf.removeFirst()   // 가장 오래된 표본 폐기(FIFO)
        buf.addLast(rssi)

        val sorted = buf.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2]
               else (sorted[n / 2 - 1] + sorted[n / 2]) / 2   // 부분버퍼 짝수: 중앙 2개 정수평균
    }

    /** 윈도우가 가득 찼는지 여부. false면 콜드스타트(워밍업) 구간 → 발령 보류 판정에 사용. */
    fun isFull(deviceId: String): Boolean = (buffers[deviceId]?.size ?: 0) >= windowSize

    fun clear(deviceId: String) { buffers.remove(deviceId) }
    fun clearAll() { buffers.clear() }
}
