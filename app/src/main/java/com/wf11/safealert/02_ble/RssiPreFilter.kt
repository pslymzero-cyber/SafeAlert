package com.wf11.safealert.ble

import kotlin.math.roundToInt

/**
 * RSSI 전처리 — 비대칭 비례제어(Asymmetric P-Control) EMA LPF (v1.0.32)
 *
 * [설계 교체] v1.0.31 까지의 IQR → Max-Hold 통계 파이프라인을 폐기하고,
 * 지수이동평균(EMA) 기반 1차 비례제어 저역통과 필터로 단일화한다.
 *   공식:  S_t = S_{t-1} + α · (R_t − S_{t-1})   (현재추정 = 이전추정 + 비례상수 × 오차)
 *
 * [비대칭 P-Gain] RSSI는 음수다(0에 가까울수록 강·근접).
 *   ① R_t ≥ S_{t-1} (신호 강해짐 = 접근/위험 상황)       → α = ALPHA_RISE (0.3)  빠른 추종
 *   ② R_t <  S_{t-1} (신호 약해짐 = 철제랙 간섭 등 난수)  → α = ALPHA_FALL (0.05) 매우 느린 추종(가짜 난수 무시)
 *      ※ [v1.0.33] fall α=0.05 는 실제 이탈(신호 감소)도 함께 둔화시켜 SAFE 전환을 지연시킬 수 있다
 *        (의도된 트레이드오프). 이 지연은 BleService 의 raw 기반 2차 방어선 avg1sec — ⒜ 2차 게이트
 *        SAFE 강등, ⒝ 피크 대비 페이드아웃, ⒞ 0x02 하이브리드 교차검증 — 가 상호 보완하여, 실제
 *        이탈 시 경보가 raw 경로로 신속히 해제·차단되도록 설계되어 있다.
 *
 * [미분(속도) 연동 D-Boost] 2D 칼만이 정제한 접근속도(prevVel, dBm/s)를 피드백 받아 α를 가변 조절.
 *   ※ 부호 규칙(KalmanFilter): vel>0 = RSSI 증가 = 접근(돌진).
 *      지침 원문의 'velocity < −2.0(거리 관념)'은 본 코드의 RSSI 공간과 부호가 반대이므로,
 *      의도(돌진 시 빗장 개방)를 살려 코드 부호 관례 prevVel > +VEL_DBOOST_DBM 로 정정 구현한다.
 *   prevVel > VEL_DBOOST_DBM(+2.0) (강한 돌진) → 신호가 일시적으로 감소(R<S)하더라도 난수 방어
 *      하한선 α=0.05 를 무시하고 α = ALPHA_DBOOST(0.4) 로 필터 빗장을 완전히 열어
 *      필터 지연을 최소화하고 생존 반응속도를 확보한다.
 *
 * 정제된 출력(smoothedRssi)만 2D 칼만 필터의 Measurement 로 주입된다(raw 직접 입력 금지).
 *
 * [v1.0.45 재사용 파라미터화] 동일 비대칭 EMA 코어를 칼만 '후처리 P-EMA'로도 재사용한다.
 *   · 기본 생성자(인자 없음) = 전단(front) EMA: 상승0.3/하강0.05/D-Boost0.4, D-Boost ON (기존 동작 보존).
 *   · 후처리 P-EMA 재사용 시: RssiPreFilter(alphaRise=0.4, alphaFall=0.15, dBoostEnabled=false).
 *     칼만이 이미 속도(D)를 반영하므로 P-EMA 단계의 D-Boost 는 비활성화한다(거리 P항 전용 평활).
 *
 * (v1.1.29) [워밍업 대칭화] 앱 재시작마다 같은 자리·같은 기기인데 정착 RSSI 가 다른 '세션 간
 *   편차'의 근본 교정. 원인: 첫 샘플을 그대로 앵커로 신뢰하는데, BLE 원시 RSSI 는 고정 거리라도
 *   3개 광고채널·다중경로로 ±8~12dB 퍼져 있어 앵커 자체가 세션마다 복불복이다. 앵커가 우연히
 *   '높게'(강하게) 잡히면 하강α=0.05 의 느린 추종 탓에 참값 복귀까지 약 11.7초(상승 0.3 은 2.7초,
 *   4.3배 비대칭 지속) — 이 잔상이 초반 수 초의 레벨 판정 품질을 세션마다 다르게 만든다.
 *   교정: 기기별 첫 warmupSymmetricPushes(기본 10, 3Hz 기준 약 3.3초) 푸시 동안만 하강 알파를
 *   상승 알파와 동일하게(대칭) 적용해 잘못 잡힌 앵커를 양방향 같은 속도로 신속 교정한다.
 *   상승·D-Boost 경로는 불변 → 접근(위험) 추종은 어떤 경우에도 기존보다 느려지지 않는다.
 *   워밍업 종료 후엔 기존 비대칭(난수 방어)으로 완전 복귀. 0=끄기(기존 동작과 동일).
 */
class RssiPreFilter(
    // 알파 3종은 var — 개발자 설정(DevSettings)에서 라이브 조절 가능(emaState 보존한 채 즉시 반영)
    var alphaRise:     Double  = ALPHA_RISE,
    var alphaFall:     Double  = ALPHA_FALL,
    var alphaDBoost:   Double  = ALPHA_DBOOST,
    // (v1.1.29) 워밍업 대칭 푸시 수 — var: DevSettings 에서 라이브 조절(0=끄기). 전단·후처리 공통.
    var warmupSymmetricPushes: Int = WARMUP_SYMMETRIC_PUSHES,
    private val dBoostEnabled: Boolean = true,
) {

    companion object {
        // 비대칭 비례상수(α) — 전단 EMA 기본값
        const val ALPHA_RISE     = 0.3    // 신호 강해짐(접근/위험): 빠른 추종
        const val ALPHA_FALL     = 0.05   // 신호 약해짐(난수 의심): 느린 추종
        const val ALPHA_DBOOST   = 0.4    // 강한 돌진(D-Boost): 빗장 완전 개방
        // D-Boost 임계: 칼만 추정 접근속도(dBm/s). RSSI 공간이라 양수(+)=접근.
        const val VEL_DBOOST_DBM = 2.0
        // (v1.1.29) 워밍업 대칭 푸시 수 기본값 — 3Hz 광고 기준 약 3.3초
        const val WARMUP_SYMMETRIC_PUSHES = 10
    }

    // 기기별 EMA 상태 S_{t-1} (Double 정밀도 유지, 출력만 Int 양자화)
    private val emaState = mutableMapOf<String, Double>()
    // (v1.1.29) 기기별 누적 푸시 수 — 워밍업(하강 대칭화) 구간 판정용. 앵커(첫 샘플)=1.
    private val pushCount = mutableMapOf<String, Int>()

    /**
     * 신규 RSSI 샘플을 비대칭 EMA로 정제해 반환.
     *
     * @param deviceId 기기 식별자 (기기별 독립 상태)
     * @param rssi     원시 RSSI (dBm, 음수)
     * @param prevVel  직전 프레임 칼만 추정속도(dBm/s). +접근/−이탈. 첫 프레임 0.0.
     * @return 2D 칼만 필터에 입력할 정제 RSSI(smoothedRssi)
     */
    fun push(deviceId: String, rssi: Int, prevVel: Double = 0.0): Int {
        // 첫 샘플: 상태 초기화(콜드스타트 지연 제거) — 원시값을 그대로 신뢰
        val prev = emaState[deviceId] ?: run {
            emaState[deviceId] = rssi.toDouble()
            pushCount[deviceId] = 1   // (v1.1.29) 앵커 푸시=1. 재발견(clear 후) 시 워밍업 재시작.
            return rssi
        }

        // (v1.1.29) 워밍업 카운트 — 앵커 이후 푸시부터 2,3,... 증가
        val n = (pushCount[deviceId] ?: 1) + 1
        pushCount[deviceId] = n
        // (v1.1.29) 워밍업 구간(n ≤ warmupSymmetricPushes)에는 하강도 상승 알파로 대칭 추종:
        //   우연히 높게 잡힌 앵커를 신속 교정(11.7초 → 약 3.3초). 상승·D-Boost 분기는 불변.
        val fallEff = if (n <= warmupSymmetricPushes) alphaRise else alphaFall

        val r = rssi.toDouble()
        val alpha = when {
            // D-Boost: 강한 돌진(접근속도 가파름) → 신호 일시감소(R<S)여도 빗장 개방
            dBoostEnabled && prevVel > VEL_DBOOST_DBM -> alphaDBoost
            // 신호 강해짐(R ≥ S): 위험 방향 → 빠른 추종
            r >= prev                                 -> alphaRise
            // 신호 약해짐(R < S): 철제랙 간섭 등 난수 의심 → 매우 느린 추종(워밍업 중엔 대칭)
            else                                      -> fallEff
        }

        val s = prev + alpha * (r - prev)
        emaState[deviceId] = s
        return s.roundToInt()
    }

    fun clear(deviceId: String) { emaState.remove(deviceId); pushCount.remove(deviceId) }
    fun clearAll() { emaState.clear(); pushCount.clear() }
}
