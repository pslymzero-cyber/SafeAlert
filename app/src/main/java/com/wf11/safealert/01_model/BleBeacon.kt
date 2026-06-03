package com.wf11.safealert.model

data class BleBeacon(
    val mac: String,        // BLE MAC 주소 (예: AA:BB:CC:DD:EE:FF)
    val name: String,       // 사용자 지정 이름 (예: "작업자01")
    val addedAt: Long = System.currentTimeMillis()
)
