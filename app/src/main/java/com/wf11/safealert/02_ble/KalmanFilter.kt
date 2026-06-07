package com.wf11.safealert.ble

import com.wf11.safealert.utils.DevSettings
import kotlin.math.log10
import kotlin.math.pow

/**
 * 2D 칼만 필터 — 거리(m) + 속도(m/s) 동시 추적 (v1.0.20)
 *
 * 상태 벡터: x = [d, v]^T
 *   d : 추정 거리 (m, 양수)
 *   v : 추정 상대 속도 (m/s, 음수=접근 / 양수=이탈)
 *
 * 관측값: RSSI(dBm) → 경로손실 모델로 거리(m) 변환 후 입력.
 * 전이 모델(등속): F = [[1, dt], [0, 1]]
 * 관측 모델: H = [1, 0]
 * 과정 노이즈: Q = q × [[dt⁴/4, dt³/2], [dt³/2, dt²]]
 *
 * 프리셋별 파라미터:
 *   FAST   — q=0.50, R=0.50  빠른 반응, 속도 추정 노이즈 큼
 *   NORMAL — q=0.15, R=1.50  균형 (기본값)
 *   SMOOTH — q=0.05, R=4.00  느린 반응, 속도 추정 안정
 *
 * @param preset DevSettings.KALMAN_PRESET_* 값
 */
class KalmanFilter(private var preset: Int = DevSettings.KALMAN_PRESET_NORMAL) {

    // ── 상태 변수 ─────────────────────────────────────────────────────
    private var dist: Double = 0.0          // 추정 거리 (m)
    private var vel:  Double = 0.0          // 추정 속도 (m/s)
    // 2×2 공분산 행렬 (대칭: pDv == pVd 이므로 3값으로 표현)
    private var pDd: Double = 100.0
    private var pDv: Double = 0.0
    private var pVv: Double = 100.0
    private var initialized: Boolean = false
    private var lastTsMs:    Long    = 0L

    // ── 프리셋별 파라미터 ─────────────────────────────────────────────
    /** 과정 노이즈 q ((m/s²)²) */
    private val processNoise: Double
        get() = when (preset) {
            DevSettings.KALMAN_PRESET_FAST   -> 0.50
            DevSettings.KALMAN_PRESET_NORMAL -> 0.15
            else                             -> 0.05
        }
    /** 관측 노이즈 R (m²) */
    private val measureNoise: Double
        get() = when (preset) {
            DevSettings.KALMAN_PRESET_FAST   -> 0.50
            DevSettings.KALMAN_PRESET_NORMAL -> 1.50
            else                             -> 4.00
        }

    fun updatePreset(p: Int) { preset = p }

    // ── 공개 상태 읽기 ────────────────────────────────────────────────
    val estimatedDist:  Double  get() = if (initialized) dist.coerceAtLeast(0.1) else 0.0
    val estimatedVel:   Double  get() = if (initialized) vel  else 0.0
    val isInitialized:  Boolean get() = initialized

    /**
     * 현재 추정 거리를 RSSI(dBm)로 역변환.
     * calcLevelWithHysteresis() 등 기존 RSSI 기반 로직과 호환.
     */
    fun estimatedRssi(): Int {
        if (!initialized) return -99
        val calib = DevSettings.calibRssiAt1m.toDouble()
        val n     = DevSettings.pathLossExp.toDouble()
        return (calib - 10.0 * n * log10(dist.coerceAtLeast(0.1))).toInt()
    }

    /**
     * 새 RSSI 측정값으로 필터 업데이트.
     *
     * @param rssi      전처리된 RSSI (dBm)
     * @param imuQScale IMU 어댑티브 Q 배율 (ImuFusion.adaptiveQFactor)
     *                  정지≈0.3 / 보통≈1.0 / 빠른이동≈2.0
     * @return Pair(추정 거리 m, 추정 속도 m/s)
     */
    fun update(rssi: Int, imuQScale: Double = 1.0): Pair<Double, Double> {
        val calib    = DevSettings.calibRssiAt1m.toDouble()
        val n        = DevSettings.pathLossExp.toDouble()
        val measDist = 10.0.pow((calib - rssi) / (10.0 * n)).coerceIn(0.1, 200.0)
        val nowMs    = System.currentTimeMillis()

        if (!initialized) {
            dist        = measDist
            vel         = 0.0
            pDd         = 5.0
            pDv         = 0.0
            pVv         = 5.0
            initialized = true
            lastTsMs    = nowMs
            return Pair(measDist, 0.0)
        }

        val dt = ((nowMs - lastTsMs) / 1000.0).coerceIn(0.05, 2.0)
        lastTsMs = nowMs

        // ── 예측 단계 ─────────────────────────────────────────────────
        // x' = F·x
        val predDist = dist + vel * dt
        val predVel  = vel

        // P' = F·P·F^T + Q   (Q = q·qMatrix)
        val qs   = processNoise * imuQScale
        val qDd  = qs * dt.pow(4) / 4.0
        val qDv  = qs * dt.pow(3) / 2.0
        val qVv  = qs * dt.pow(2)

        val pDdP = pDd + 2 * pDv * dt + pVv * dt * dt + qDd
        val pDvP = pDv + pVv * dt + qDv
        val pVvP = pVv + qVv

        // ── 갱신 단계 (H = [1, 0]) ────────────────────────────────────
        val s  = pDdP + measureNoise    // S = H·P'·H^T + R
        val kD = pDdP / s               // 거리 칼만 게인
        val kV = pDvP / s               // 속도 칼만 게인
        val innov = measDist - predDist

        dist = predDist + kD * innov
        vel  = predVel  + kV * innov
        pDd  = (1 - kD) * pDdP
        pDv  = (1 - kD) * pDvP
        pVv  = pVvP - kV * pDvP

        dist = dist.coerceIn(0.1, 200.0)
        return Pair(dist, vel)
    }

    /**
     * Cold-Start 웜업 주입.
     * BleDetectedReceiver → injectInitialSample() 경로에서 사용.
     * errorCovariance를 낮게 설정 → 초기값 신뢰도 높음 (Cold Start 딜레이 제거).
     */
    fun injectWarmup(rssi: Int) {
        val calib    = DevSettings.calibRssiAt1m.toDouble()
        val n        = DevSettings.pathLossExp.toDouble()
        dist        = 10.0.pow((calib - rssi) / (10.0 * n)).coerceIn(0.1, 200.0)
        vel         = 0.0
        pDd         = 1.0
        pDv         = 0.0
        pVv         = 1.0
        initialized = true
        lastTsMs    = System.currentTimeMillis()
    }

    /** 상태 초기화 (기기 소실 / SAFE 전환 시) */
    fun reset() {
        initialized = false
        vel = 0.0
    }
}
