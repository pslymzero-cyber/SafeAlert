package com.wf11.safealert.ble

interface BleScanCallback {
    // [v1.0.29] remoteState: 상대 기기의 1바이트 페이로드(0~255). BleService 가 Category/State 언패킹.
    // [v1.1.7 #1] remoteTurn: 상대 송신 회전 방향(TURN_*, bits 3:2 디코드). 미지원/비콘은 TURN_STRAIGHT.
    fun onDeviceDetected(deviceId: String, rssi: Int, alertLevel: Int, remoteState: Int, remoteTurn: Int = BleConstants.TURN_STRAIGHT)
    fun onDeviceLost(deviceId: String)
    fun onScanError(errorCode: Int)
    // UWB 주소가 스캔 응답에서 파싱됐을 때 (기본: 무시)
    fun onUwbAddressReceived(deviceId: String, uwbAddress: ByteArray) {}
}
