package com.wf11.safealert.ble

import com.wf11.safealert.utils.DevSettings
import kotlin.math.pow

/**
 * 2D 칼만 필터 — RSSI(dBm) + RSSI 변화율(dBm/s) 동시 추적 (v1.0.20)
 *
 * 상태 벡터: x = [rssi, vel]^T
 *   rssi : 추정 RSSI (dBm)
 *   vel  : RSSI 변화율 (dBm/s)
 *          ★ 부호 규칙: 양수(+) = RSSI 증가 = 보행자 접근
 *                       음수(-) = RSSI 감소 = 보행자 이탈
 *          RSSI는 0에 가까울수록 강한 신호(-80 → -40)이므로
 *          vel > 0 이면 신호가 강해지는 중 = 다가옴이다.
 *
 * 입력: RssiPreFilter를 거친 정제 RSSI (raw 데이터 직접 입력 금지)
 * 전이 모델(등속): F = [[1, dt], [0, 1]]
 * 관측 모델: H = [1, 0]
 * 과정 노이즈: Q = q × [[dt⁴/4, dt³/2], [dt³/2, dt²]]
 *
 * 프리셋별 파라미터:
 *   FAST   — q=0.50, R=2.0   빠른 반응, 속도 추정 노이즈 큼
 *   NORMAL — q=0.15, R=5.0   균형 (기본값)
 *   SMOOTH — q=0.05, R=10.0  느린 반응, 속도 추정 안정
 */
class KalmanFilter(private var preset: Int = DevSettings.KALMAN_PRESET_NORMAL) {

    // ── 상태 변수 ─────────────────────────────────────────────────────
    private var rssi: Double = 0.0    // 추정 RSSI (dBm)
    private var vel:  Double = 0.0    // RSSI 변화율 (dBm/s), 양수=접근 / 음수=이탈
    // 2×2 공분산 행렬 (대칭: pRV == pVR 이므로 3값으로 표현)
    private var pRR: Double = 100.0   // 분산: rssi-rssi
    private var pRV: Double = 0.0     // 공분산: rssi-vel
    private var pVV: Double = 100.0   // 분산: vel-vel
    private var initialized: Boolean = false
    private var lastTsMs:    Long    = 0L
    private var updateCnt:   Int     = 0      // [v1.0.49 #1] 누적 update 횟수 — 기하학 판정 워밍업 게이트용

    // ── 프리셋별 파라미터 ─────────────────────────────────────────────
    /** 과정 노이즈 q ((dBm/s²)²) */
    private val processNoise: Double
        get() = when (preset) {
            DevSettings.KALMAN_PRESET_FAST   -> 0.50
            DevSettings.KALMAN_PRESET_NORMAL -> 0.15
            else                             -> 0.05
        }
    /** 관측 노이즈 R (dBm²) */
    private val measureNoise: Double
        get() = when (preset) {
            DevSettings.KALMAN_PRESET_FAST   -> 2.0
            DevSettings.KALMAN_PRESET_NORMAL -> 5.0
            else                             -> 10.0
        }

    fun updatePreset(p: Int) { preset = p }

    // ── 공개 상태 읽기 ────────────────────────────────────────────────
    /** 추정 RSSI (dBm). 미초기화 시 0.0 */
    val estimatedRssi:  Double  get() = if (initialized) rssi else 0.0
    /** 추정 변화율 (dBm/s). 양수=접근 / 음수=이탈. 미초기화 시 0.0 */
    val estimatedVel:   Double  get() = if (initialized) vel  else 0.0
    val isInitialized:  Boolean get() = initialized
    /** [v1.0.49 #1] 누적 update 횟수. 콜드 구간(vel≈초기값 0.0)의 측면 오판정 유예 게이트에 사용 */
    val updateCount:    Int     get() = updateCnt

    /**
     * 새 정제 RSSI 샘플로 필터 업데이트.
     *
     * @param filteredRssi RssiPreFilter 출력값 (정제 RSSI, dBm)
     * @param imuQScale    IMU 어댑티브 Q 배율 (ImuFusion.adaptiveQFactor)
     *                     정지≈0.3 / 보통≈1.0 / 빠른이동≈2.0
     * @return Pair(추정 RSSI dBm, 추정 변화율 dBm/s)
     *         vel > 0 = 접근 / vel < 0 = 이탈
     */
    fun update(filteredRssi: Int, imuQScale: Double = 1.0): Pair<Double, Double> {
        val meas  = filteredRssi.toDouble()
        val nowMs = System.currentTimeMillis()
        updateCnt++   // [v1.0.49 #1] 초기화/정상 양 경로 공통 증가

        if (!initialized) {
            rssi        = meas
            vel         = 0.0
            pRR         = 5.0
            pRV         = 0.0
            pVV         = 5.0
            initialized = true
            lastTsMs    = nowMs
            return Pair(rssi, vel)
        }

        val dt = ((nowMs - lastTsMs) / 1000.0).coerceIn(0.05, 2.0)
        lastTsMs = nowMs

        // ── 예측 단계 ─────────────────────────────────────────────────
        val predRssi = rssi + vel * dt
        val predVel  = vel

        val qs   = processNoise * imuQScale
        val qRR  = qs * dt.pow(4) / 4.0
        val qRV  = qs * dt.pow(3) / 2.0
        val qVV  = qs * dt.pow(2)

        val pRRP = pRR + 2.0 * pRV * dt + pVV * dt * dt + qRR
        val pRVP = pRV + pVV * dt + qRV
        val pVVP = pVV + qVV

        // ── 갱신 단계 (H = [1, 0]) ────────────────────────────────────
        val s     = pRRP + measureNoise   // S = H·P'·H^T + R
        val kR    = pRRP / s              // RSSI 칼만 게인
        val kV    = pRVP / s              // 속도 칼만 게인
        val innov = meas - predRssi

        rssi = predRssi + kR * innov
        vel  = predVel  + kV * innov
        pRR  = (1.0 - kR) * pRRP
        pRV  = (1.0 - kR) * pRVP
        pVV  = pVVP - kV * pRVP

        return Pair(rssi, vel)
    }

    /**
     * Cold-Start 웜업 주입 — BleService 가 기기별 KalmanFilter 최초 생성 직후 첫 원시 RSSI 로 호출.
     * 상태를 첫 표본으로 즉시 초기화해 Cold Start 딜레이를 제거하되,
     * (v1.1.29) 초기 공분산은 '정직하게' 설정한다: BLE 원시 RSSI 단일 표본은 3개 광고채널·
     * 다중경로 탓에 표준편차 약 5dB 수준이므로 pRR=25(=5의 제곱), pVV=5.
     * 이전 pRR=1.0/pVV=1.0 과신은 우연히 높거나 낮게 잡힌 첫 표본을 필터가 강하게 붙들어
     * 앱 재시작(세션)마다 초반 기준선이 달라지는 편차의 한 원인이었다. 정직한 초기 분산은
     * 칼만 게인을 키워 이후 관측이 첫 표본의 복불복을 빠르게 씻어내게 한다.
     * (일반 초기화 경로 update 의 !initialized 는 pRR=5 — 이 경로는 '생성 즉시 앵커'라
     *  불확실성이 더 크므로 더 큰 초기 분산이 타당하다.)
     * (v1.1.56 U3) initVel — SAFE/이탈 정리 직전 캡처된 이탈속도(음수, -1.5 캡)를 재시드해, 얕은
     *   SAFE 딥 직후 재등록 시 속도 0 재출발로 이탈 판정이 원점부터 재시작되는 플랩을 줄인다. 기본 0.0.
     */
    fun injectWarmup(rssiVal: Int, initVel: Double = 0.0) {
        rssi        = rssiVal.toDouble()
        vel         = initVel
        pRR         = 25.0   // (v1.1.29) 1.0 → 25.0: 첫 표본 과신 제거(단일표본 정직 분산)
        pRV         = 0.0
        pVV         = 5.0    // (v1.1.29) 1.0 → 5.0: 초기 속도 불확실성 정직화
        initialized = true
        lastTsMs    = System.currentTimeMillis()
    }

    /** 상태 초기화 (기기 소실 / SAFE 전환 시) */
    fun reset() {
        initialized = false
        vel         = 0.0
        updateCnt   = 0   // [v1.0.49 #1] 워밍업 카운터 리셋
    }
}
