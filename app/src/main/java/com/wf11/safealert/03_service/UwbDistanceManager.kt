package com.wf11.safealert.service

import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.UwbRanger

/**
 * [Phase 3 T2] UWB 실측 신선도 소유자 — Case A(UWB 배타 판정) 성립 여부와 '신선한' 실측 거리 조회.
 * UwbRanger.uwbDistances 직접 조회는 이 클래스 단독이다(BleService 는 이 클래스를 통해서만 본다).
 * 판정 로직·상수는 v1.1.46 원본 그대로 — 이동만 했다.
 *
 * @param ranger 현재 UwbRanger 참조를 돌려주는 람다 — 서비스 수명 중 생성·교체·정지되므로 스냅샷 금지.
 */
class UwbDistanceManager(private val ranger: () -> UwbRanger?) {

    val peerUwbSeenMap   = mutableMapOf<String, Long>()   // deviceId → 0x9ABC(UWB 활성 플래그) 최근 관측 시각(진단용 — 판정 불사용)
    val uwbSampleAtMsMap = mutableMapOf<String, Long>()   // deviceId → UWB 실측 표본 최근 수신 시각(Case A 신선도 근거)
    val uwbSafeStreakMap = mutableMapOf<String, Int>()    // deviceId → UWB 판정 격하 확증 연속표본 수
    private val UWB_MEAS_FRESH_MS    = 1_000L  // [v1.1.46] 실측 신호 신선 창(정상 주기 ~120ms 의 ~8표본) — 초과=그 순간부터 RSSI 판정.
                                               //   이 창이 곧 UWB 판정 공백 최대 길이 — 지게차 1.7m/s 기준 최대 ~1.7m 내 RSSI 인계

    // ── [v1.1.46] Case A(UWB↔UWB 배타 판정) 성립 판정 — 실측 신호 신선도가 유일한 권위 ────
    //   마지막 실측 표본이 UWB_MEAS_FRESH_MS(1s) 이내 = UWB 거리로 판정. 아니면 그 순간부터 RSSI
    //   판정, 실측이 다시 흐르면 첫 표본에서 즉시 UWB 복귀 — 어느 쪽으로도 판정 공백이 없다.
    //   여기서 레인징 배관(세션)을 철거·재개설하지 않는다 — 잇고 끊는 건 UwbRanger(스캔응답·백오프·
    //   종료 이벤트) 내부 사정이고 판정은 신호만 본다. v1.1.43/44 는 무표본 시 onDeviceLost 철거를
    //   함께 했는데, 마지널 신호 페어에서 철거(1s)→재합류(250ms)→철거 무한 반복(플랩)+컨트롤러
    //   철거 시 stopActiveLocked 전 세션 연쇄 붕괴가 생겨 판정 전환만 남기고 철거를 폐지했다.
    //   uwbDistances 엔트리 확인은 유지 — 종료 이벤트로 엔트리가 걷힌 페어는 timestamp 신선 여부와
    //   무관하게 즉시 RSSI(스테일 timestamp 단독 잔존 오판 방지).
    fun uwbJudgeModeExclusive(deviceId: String, now: Long): Boolean {
        if (!DevSettings.uwbExclusiveJudgeEnabled) return false    // 킬스위치 off = v1.1.40 거동
        val r = ranger() ?: return false                     // 내 UWB 미가동(HW·권한·시스템 OFF)
        if (!r.uwbDistances.containsKey(deviceId)) return false  // 실측 이력 없음(개설 전·종료 후) → RSSI
        val sampleAt = uwbSampleAtMsMap[deviceId] ?: return false
        return now - sampleAt <= UWB_MEAS_FRESH_MS
    }

    // [v1.1.46] '신선한' UWB 실측 거리 — Case A 성립과 같은 신선 창. Calibrator 학습 입력·거리
    //   표시(·UWB 태그)·경보 승격/이탈 판정(v1.1.50 좀비 차단)이 쓴다: 오래된 거리로 학습하면
    //   Δ 오염(즉시 DANGER 의 한 축), 오래된 거리를 표시하면 죽은 숫자를 실측으로 오인하고, 오래된
    //   거리로 승격하면 사라진 기기가 좀비로 DANGER 를 유지한다. 신선하지 않으면 null=RSSI 경로(추정·역산).
    fun freshUwbDistM(deviceId: String): Float? {
        val d = ranger()?.uwbDistances?.get(deviceId) ?: return null
        val at = uwbSampleAtMsMap[deviceId] ?: return null
        return if (System.currentTimeMillis() - at <= UWB_MEAS_FRESH_MS) d else null
    }
}
