package com.wf11.safealert.ble

interface BleScanCallback {
    // [v1.0.29] remoteState: 상대 기기의 IMU 모션 상태 코드(0x00/0x01/0x02). 미지원 기기는 0x00.
    // [v1.0.35] remoteAzimuth: 상대 진행 방위각 코드(0~255). 미지원(1바이트 레거시/비콘)은 -1.
    fun onDeviceDetected(deviceId: String, rssi: Int, alertLevel: Int, remoteState: Int, remoteAzimuth: Int = -1)
    fun onDeviceLost(deviceId: String)
    fun onScanError(errorCode: Int)
    // UWB 주소가 스캔 응답에서 파싱됐을 때 (기본: 무시)
    fun onUwbAddressReceived(deviceId: String, uwbAddress: ByteArray) {}
}
