package com.wf11.safealert.utils

import android.os.Build
import android.util.Log

/**
 * 기기 모델별 BLE 수신 감도 프로파일 자동 주입
 *
 * 스마트폰과 산업용 PDA는 BLE 안테나 설계 차이로
 * 동일 거리에서의 RSSI 측정값에 최대 ±10 dBm 편차가 생긴다.
 * 알려진 모델 prefix 기준으로 calibRssiAt1m 보정값을 사전 정의해
 * 별도 교정 없이도 거리 추정 정확도를 높인다.
 *
 * [적용 조건]
 * - calibRssiAt1m가 기본값(DEFAULT_CALIB)인 경우만 자동 적용
 * - 이 모델로 이미 한 번 적용된 경우 재적용 안 함
 * - 수동 교정(CalibrationWizard)으로 값이 변경됐으면 건드리지 않음
 *
 * [offset 부호 규칙]
 * offset < 0 : 수신 감도 낮음 → calibRssiAt1m 하향(더 음수)
 *               예) PDA가 1m에서 -44 측정 → offset = -6
 * offset > 0 : 수신 감도 높음 → calibRssiAt1m 상향(덜 음수)
 *               예) 고감도 기기가 1m에서 -35 측정 → offset = +3
 */
object DeviceProfileManager {

    private const val TAG = "DeviceProfile"

    /**
     * (모델 prefix, dBm 오프셋) 리스트.
     * 매칭: Build.MODEL.uppercase().startsWith(prefix.uppercase())
     * 기준 기기(Galaxy S 플래그십) = 0 dBm.
     */
    private val MODEL_PROFILES = listOf(
        // ── Samsung Galaxy ─────────────────────────────────────
        "SM-S"  to  0,    // Galaxy S 플래그십 (기준 기기)
        "SM-G"  to  0,    // Galaxy S/Note 구형 (S6~S9 계열)
        "SM-N"  to  0,    // Galaxy Note
        "SM-A"  to -2,    // Galaxy A 중급형 — 안테나 소형
        "SM-F"  to -1,    // Galaxy Z Fold/Flip
        "SM-T"  to -1,    // Galaxy Tab (태블릿)
        "SM-X"  to -1,    // Galaxy Tab S8+ Ultra 신형
        // ── 산업용 PDA: Zebra ───────────────────────────────────
        "TC5"   to -8,    // TC52, TC57 — 창고 현장 주력
        "TC7"   to -8,    // TC72, TC77
        "TC2"   to -7,    // TC21, TC26 엔트리 PDA
        "MC93"  to -8,    // MC9300 헤비듀티
        "MC33"  to -8,    // MC3300
        "MC22"  to -7,    // MC2200 엔트리
        "EC30"  to -6,    // EC30 컴팩트
        "ET5"   to -6,    // ET51, ET56 태블릿
        // ── 산업용 PDA: Honeywell ──────────────────────────────
        "EDA52" to -7,    // Honeywell EDA52
        "EDA56" to -7,
        "EDA61" to -7,
        "CK65"  to -8,    // Honeywell CK65
        "CN80"  to -7,
        "CT40"  to -7,
        "CT60"  to -7,
        // ── 산업용 PDA: Point Mobile ────────────────────────────
        "PM85"  to -6,
        "PM75"  to -6,
        "PM45"  to -5,
        "PM30"  to -5,
        // ── 산업용 PDA: Bluebird ────────────────────────────────
        "EF500" to -6,
        "EF400" to -6,
        "BP30"  to -5,
        // ── 산업용 PDA: Unitech ─────────────────────────────────
        "EA630" to -7,
        "EA520" to -7,
        "HT730" to -7,
        // ── Xiaomi / POCO / Redmi ───────────────────────────────
        "REDMI" to -3,
        "POCO"  to -3,
        "MI "   to -3,    // "Mi " (공백 포함)
        "2201"  to -3,    // 숫자형 Redmi 모델명 (22011)
        "2107"  to -3,
        "2109"  to -3,
        // ── Huawei / Honor ──────────────────────────────────────
        "ELS-"  to -2,    // P40 Pro
        "NOH-"  to -2,    // Mate 40 Pro
        "ANA-"  to -2,    // P40
        // ── LG ──────────────────────────────────────────────────
        "LM-"   to  0     // G/V/Velvet 계열 — 감도 양호
    )

    /** 현재 기기 모델에 맞는 calibRssiAt1m 오프셋 반환 (알 수 없으면 0) */
    fun getProfileOffset(model: String = Build.MODEL): Int {
        val upper = model.uppercase()
        return MODEL_PROFILES.firstOrNull { (prefix, _) ->
            upper.startsWith(prefix.uppercase())
        }?.second ?: 0
    }

    /**
     * DevSettings.init() 이후 호출.
     * 두 조건이 모두 충족될 때만 자동 주입:
     *   ① calibRssiAt1m == DEFAULT_CALIB (수동 교정 없음)
     *   ② 이 모델에 아직 적용한 적 없음 (appliedProfileModel != Build.MODEL)
     */
    fun autoApplyIfNeeded() {
        val model  = Build.MODEL
        val offset = getProfileOffset(model)

        // 알려지지 않은 기기 또는 오프셋 없음 → 기본값 유지
        if (offset == 0) {
            // 모델을 기록해서 다음 번에도 빠르게 스킵
            if (DevSettings.appliedProfileModel != model) DevSettings.appliedProfileModel = model
            return
        }

        // 이미 이 모델에 적용됐으면 재적용 안 함
        if (DevSettings.appliedProfileModel == model) return

        // 기본값과 달라졌으면 수동 교정으로 간주 → 건드리지 않음
        if (DevSettings.calibRssiAt1m != DevSettings.DEFAULT_CALIB) {
            DevSettings.appliedProfileModel = model  // 이후 스킵되도록 기록
            Log.d(TAG, "[$model] 수동 교정값 존재(${DevSettings.calibRssiAt1m}) → 프로파일 자동 주입 건너뜀")
            return
        }

        val newCalib = (DevSettings.DEFAULT_CALIB + offset).coerceIn(-90, 0)
        DevSettings.calibRssiAt1m       = newCalib
        DevSettings.appliedProfileModel = model
        Log.i(TAG, "기기 프로파일 자동 주입 [$model]: offset=${if (offset > 0) "+$offset" else "$offset"} dBm " +
                   "→ calibRssiAt1m=$newCalib dBm")
    }
}
