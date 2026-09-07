package com.wf11.safealert.model

/**
 * UUID 기반 비콘 프로파일
 * 같은 UUID를 가진 비콘은 전부 자동 감지됨
 *
 * type:
 *   "IBEACON"      - Apple iBeacon 형식 (Proximity UUID)
 *   "SERVICE_UUID" - 커스텀 서비스 UUID 형식
 */
data class BeaconProfile(
    val uuid: String,               // 예: "550E8400-E29B-41D4-A716-446655440000"
    val label: String,              // 예: "현장 작업자", "SmartTag-홍길동"
    val type: String = "IBEACON",
    val addedAt: Long = System.currentTimeMillis(),
    // 감지 거리 보정 (dBm 오프셋)
    // 0 = 전역 설정 사용
    // +10 = 10dBm 더 약한 신호도 감지 = 약 2배 먼 거리 (SmartTag 권장: +15)
    val rssiOffset: Int = 0,
    // (v1.1.62) 존 비콘 — true면 이 비콘은 경보 대상이 아니라 '안전구역' 마커.
    //   zoneEnterRssi 이상으로 수신 중인 기기는 IN_ZONE 선언(자기 무음+피어 무해 판정).
    // (v1.1.76) 기본 -80 — 존은 raw rssi, 경보는 EMA+오프셋으로 판정해 스케일이 다르다.
    //   기본을 -65 로 두면 경보 임계(-75, MAC 비콘은 오프셋 적용으로 더 낮음)보다 좁아
    //   '경보는 뜨는데 존은 성립 안 하는' 사각지대가 설계상 반드시 생긴다.
    val zoneMute: Boolean = false,
    val zoneEnterRssi: Int = -80
)
