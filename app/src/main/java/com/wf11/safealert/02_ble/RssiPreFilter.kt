package com.wf11.safealert.ble

import android.util.Log

/**
 * RSSI 전처리 파이프라인 (v1.0.20)
 *
 * 모든 원시 데이터는 예외 없이 다음 3단계를 거친다:
 *   ① 슬라이딩 윈도우 — 기기별 독립 버퍼 (WINDOW_SIZE 샘플)
 *   ② IQR 필터       — [Q1-1.5×IQR, Q3+1.5×IQR] 범위 이탈 샘플 제거 (다중경로 노이즈)
 *   ③ Max-Hold 필터  — IQR 통과 샘플 중 상위 MAX_HOLD_RATIO 강신호 평균 (LoS 경로 추출)
 *
 * ※ Approach Override(급접근 바이패스) 완전 삭제:
 *   신호가 순간 튀어도 필터를 우회하지 않는다.
 *   1~2초 누적·정제가 최우선이며, 정제된 데이터만 2D 칼만 필터로 전달한다.
 */
class RssiPreFilter(
    private val windowSize:    Int   = WINDOW_SIZE_DEFAULT,
    private val minSamples:    Int   = MIN_SAMPLES_DEFAULT,
    private val maxHoldRatio:  Float = MAX_HOLD_RATIO_DEFAULT,
    private val iqrMultiplier: Float = IQR_MULTIPLIER_DEFAULT
) {
    companion object {
        private const val TAG = "RssiPreFilter"

        const val WINDOW_SIZE_DEFAULT    = 8
        const val MIN_SAMPLES_DEFAULT    = 4
        const val MAX_HOLD_RATIO_DEFAULT = 0.20f
        const val IQR_MULTIPLIER_DEFAULT = 1.5f
    }

    private val windows = mutableMapOf<String, ArrayDeque<Int>>()

    /**
     * 신규 RSSI 샘플을 추가하고 정제된 출력 반환.
     *
     * 어떠한 조건에서도 슬라이딩 윈도우 → IQR → Max-Hold 파이프라인을 거친다.
     * 샘플 부족(< minSamples) 구간에는 원시 RSSI를 폴백으로 반환한다.
     *
     * @param deviceId 기기 식별자 (기기별 독립 윈도우)
     * @param rssi     원시 RSSI (dBm, 음수)
     * @return 2D 칼만 필터에 입력할 정제 RSSI
     */
    fun push(deviceId: String, rssi: Int): Int {
        val window = windows.getOrPut(deviceId) { ArrayDeque() }
        window.addLast(rssi)
        if (window.size > windowSize) window.removeFirst()

        // 샘플 부족 — 폴백: 원시 RSSI 그대로
        if (window.size < minSamples) return rssi

        // IQR 필터
        val filtered = applyIqr(window.toList())
        if (filtered.isEmpty()) return rssi

        // Max-Hold
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
