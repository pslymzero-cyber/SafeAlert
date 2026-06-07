package com.wf11.safealert.ble

import android.util.Log

/**
 * RSSI 전처리 파이프라인: raw RSSI → IQR 필터 → Max-Hold 필터 → 칼만 입력
 *
 * 처리 순서:
 *   ① Approach Override  — 급접근(≥ APPROACH_BYPASS_DBM dBm) 시 윈도우 바이패스, 즉시 통과
 *                          단, isStationary=true(지게차 정지) 또는 isDeparting=true(이탈 방향)이면 억제 (v1.0.20)
 *   ② 슬라이딩 윈도우   — 기기별 독립 버퍼 (WINDOW_SIZE 샘플)
 *   ③ IQR 필터          — [Q1-1.5×IQR, Q3+1.5×IQR] 범위 이탈 샘플 제거 (다중경로 노이즈)
 *   ④ Max-Hold 필터     — IQR 통과 샘플 중 상위 MAX_HOLD_RATIO 강신호 평균 (LoS 경로 추출)
 *   ⑤ 폴백 보존         — 필터 결과 없으면 원본 RSSI 그대로 반환
 */
class RssiPreFilter(
    private val windowSize: Int      = WINDOW_SIZE_DEFAULT,
    private val minSamples: Int      = MIN_SAMPLES_DEFAULT,
    private val maxHoldRatio: Float  = MAX_HOLD_RATIO_DEFAULT,
    private val iqrMultiplier: Float = IQR_MULTIPLIER_DEFAULT,
    val approachBypassDbm: Int       = APPROACH_BYPASS_DBM_DEFAULT
) {
    companion object {
        private const val TAG = "RssiPreFilter"

        const val WINDOW_SIZE_DEFAULT         = 8
        const val MIN_SAMPLES_DEFAULT         = 4
        const val MAX_HOLD_RATIO_DEFAULT      = 0.20f
        const val IQR_MULTIPLIER_DEFAULT      = 1.5f
        const val APPROACH_BYPASS_DBM_DEFAULT = 6
    }

    private val windows = mutableMapOf<String, ArrayDeque<Int>>()

    /**
     * 신규 RSSI 샘플을 추가하고 전처리 출력 반환.
     *
     * @param deviceId     기기 식별자 (기기별 독립 윈도우)
     * @param rssi         원시 RSSI (dBm, 음수)
     * @param kalmanEst    현재 칼만 추정 RSSI dBm (0.0 = 미초기화)
     * @param isStationary ImuFusion.isStationary — true이면 급접근 바이패스 억제 (v1.0.20)
     * @param isDeparting  TrackingState.DEPARTING — true이면 급접근 바이패스 억제 (v1.0.20)
     * @return 칼만 필터에 입력할 전처리 RSSI
     */
    fun push(deviceId: String, rssi: Int, kalmanEst: Double,
             isStationary: Boolean = false, isDeparting: Boolean = false): Int {
        val window = windows.getOrPut(deviceId) { ArrayDeque() }
        window.addLast(rssi)
        if (window.size > windowSize) window.removeFirst()

        // ① Approach Override: 지게차 정지 중이거나 이탈 방향이면 바이패스 억제 (v1.0.20)
        //    kalmanEst == 0.0 은 초기화 전 → 바이패스 건너뜀
        val suppressBypass = isStationary || isDeparting
        if (!suppressBypass && kalmanEst != 0.0 && (rssi - kalmanEst) >= approachBypassDbm) {
            Log.d(TAG, "[$deviceId] 급접근 Override: rssi=$rssi est=${"%.1f".format(kalmanEst)} " +
                "Δ=${"%.1f".format(rssi - kalmanEst)} dBm")
            return rssi
        }

        // ② 샘플 부족 — 폴백: 원시 RSSI 그대로
        if (window.size < minSamples) return rssi

        // ③ IQR 필터
        val filtered = applyIqr(window.toList())

        // ④ IQR 결과 없음 — 폴백
        if (filtered.isEmpty()) return rssi

        // ⑤ Max-Hold
        return applyMaxHold(filtered)
    }

    private fun applyIqr(samples: List<Int>): List<Int> {
        val sorted = samples.sorted()
        val q1  = percentile(sorted, 25.0)
        val q3  = percentile(sorted, 75.0)
        val iqr = q3 - q1
        if (iqr < 1e-6) return samples
        val lower = q1 - iqrMultiplier * iqr
        val upper = q3 + iqrMultiplier * iqr
        return samples.filter { it.toDouble() in lower..upper }
    }

    private fun percentile(sorted: List<Int>, pct: Double): Double {
        if (sorted.size == 1) return sorted[0].toDouble()
        val pos  = pct / 100.0 * (sorted.size - 1)
        val lo   = pos.toInt()
        val hi   = (lo + 1).coerceAtMost(sorted.size - 1)
        val frac = pos - lo
        return sorted[lo] + frac * (sorted[hi] - sorted[lo])
    }

    private fun applyMaxHold(samples: List<Int>): Int {
        val sorted   = samples.sortedDescending()
        val topCount = maxOf(1, (sorted.size * maxHoldRatio).toInt())
        return sorted.take(topCount).average().toInt()
    }

    fun clear(deviceId: String) { windows.remove(deviceId) }
    fun clearAll() { windows.clear() }
}
