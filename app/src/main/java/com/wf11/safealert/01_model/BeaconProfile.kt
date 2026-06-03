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
    val rssiOffset: Int = 0
)
