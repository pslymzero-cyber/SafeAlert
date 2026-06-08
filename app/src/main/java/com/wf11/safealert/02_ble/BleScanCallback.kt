package com.wf11.safealert.ble

interface BleScanCallback {
    // [v1.0.29] remoteState: 상대 기기의 IMU 모션 상태 코드(0x00/0x01/0x02). 미지원 기기는 0x00.
    fun onDeviceDetected(deviceId: String, rssi: Int, alertLevel: Int, remoteState: Int)
    fun onDeviceLost(deviceId: String)
    fun onScanError(errorCode: Int)
    // UWB 주소가 스캔 응답에서 파싱됐을 때 (기본: 무시)
    fun onUwbAddressReceived(deviceId: String, uwbAddress: ByteArray) {}
}
