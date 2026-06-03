package com.wf11.safealert.ble

import com.wf11.safealert.utils.DevSettings

object BleConstants {
    const val SERVICE_UUID         = "00001234-0000-1000-8000-00805F9B34FB"
    const val DEVICE_PREFIX        = "SAFEALERT_DEVICE_"
    const val WALKER_PREFIX        = "SAFEALERT_WALKER_"

    // Device/Walker 구분을 CompanyID로 처리 → 광고 패킷 크기 절약
    const val COMPANY_ID_DEVICE = 0x1234  // 장비 작업자
    const val COMPANY_ID_WALKER = 0x5678  // 보행자

    // RSSI >= 임계값 → 경보 (가까울수록 RSSI가 0에 가까워짐)
    // 경고: 더 멀리서(더 음수), 위험: 더 가까이서(덜 음수)
    // 기본: 경고 RSSI >= -89 (이론 약 15m), 위험 RSSI >= -79 (이론 약 5m)
    // ※ 실내·장애물 환경에서는 실제 거리와 다를 수 있으므로 실측 후 조정 권장
    const val DEFAULT_RSSI_WARNING       = -89
    const val DEFAULT_RSSI_DANGER        = -79
    const val DEFAULT_SCAN_PERIOD_MS     = 3000L
    const val DEFAULT_ADVERTISE_INTERVAL = 200

    val rssiWarning: Int       get() = runCatching { DevSettings.rssiWarning }.getOrDefault(DEFAULT_RSSI_WARNING)
    val rssiDanger: Int        get() = runCatching { DevSettings.rssiDanger }.getOrDefault(DEFAULT_RSSI_DANGER)
    val scanPeriodMs: Long     get() = runCatching { DevSettings.scanPeriodMs }.getOrDefault(DEFAULT_SCAN_PERIOD_MS)
    val advertiseInterval: Int get() = runCatching { DevSettings.advertiseInterval }.getOrDefault(DEFAULT_ADVERTISE_INTERVAL)

    const val LEVEL_SAFE    = 0
    const val LEVEL_WARNING = 1
    const val LEVEL_DANGER  = 2
}
