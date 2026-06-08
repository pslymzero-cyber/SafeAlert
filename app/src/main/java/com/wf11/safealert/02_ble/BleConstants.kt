package com.wf11.safealert.ble

import com.wf11.safealert.utils.DevSettings

object BleConstants {
    const val SERVICE_UUID         = "00001234-0000-1000-8000-00805F9B34FB"
    const val DEVICE_PREFIX        = "SAFEALERT_DEVICE_"
    const val WALKER_PREFIX        = "SAFEALERT_WALKER_"

    // Device/Walker 구분을 CompanyID로 처리 → 광고 패킷 크기 절약
    const val COMPANY_ID_DEVICE   = 0x1234  // 장비 작업자
    const val COMPANY_ID_WALKER   = 0x5678  // 보행자
    // UWB 주소 교환용 (스캔 응답 전용 — 메인 광고 패킷과 분리)
    // 형식: [addr_lo][addr_hi]  (컨트롤러/컨트롤리 모두 동일 2바이트)
    const val COMPANY_ID_UWB_EXT  = 0x9ABC

    // RSSI >= 임계값 → 경보 (가까울수록 RSSI가 0에 가까워짐)
    // 경고: 더 멀리서(더 음수), 위험: 더 가까이서(덜 음수)
    // 기본값: txPower=-38, n=2.53, 경고 12m, 위험 6m 기준으로 계산된 RSSI
    // 경고 12m: -38 - 10*2.53*log10(12) = -65 dBm
    // 위험  6m: -38 - 10*2.53*log10(6)  = -58 dBm
    const val DEFAULT_RSSI_WARNING       = -65
    const val DEFAULT_RSSI_DANGER        = -58
    const val DEFAULT_SCAN_PERIOD_MS     = 3000L
    const val DEFAULT_ADVERTISE_INTERVAL = 200

    val rssiWarning: Int       get() = runCatching { DevSettings.rssiWarning }.getOrDefault(DEFAULT_RSSI_WARNING)
    val rssiDanger: Int        get() = runCatching { DevSettings.rssiDanger }.getOrDefault(DEFAULT_RSSI_DANGER)
    val scanPeriodMs: Long     get() = runCatching { DevSettings.scanPeriodMs }.getOrDefault(DEFAULT_SCAN_PERIOD_MS)
    val advertiseInterval: Int get() = runCatching { DevSettings.advertiseInterval }.getOrDefault(DEFAULT_ADVERTISE_INTERVAL)

    const val LEVEL_SAFE    = 0
    const val LEVEL_WARNING = 1
    const val LEVEL_DANGER  = 2

    // [v1.0.29 다이나믹 페이로드] IMU 모션 상태 코드 — ServiceData(1Byte)로 송수신
    //   0x00 정지 / 0x01 일반 이동 / 0x02 급정거·급회전(특수경보 트리거)
    const val MOTION_STATE_STATIONARY = 0x00
    const val MOTION_STATE_NORMAL     = 0x01
    const val MOTION_STATE_SUDDEN     = 0x02

    // 상대가 0x02(급정거/급회전)이고 정제 칼만 RSSI가 이 값 이상(가까움)이면
    // TTC·속도·방향 조건을 모두 무시하고 즉시 최고 DANGER로 격상한다.
    const val SUDDEN_ALERT_RSSI_THRESHOLD = -60
}
