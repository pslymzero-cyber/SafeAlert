package com.wf11.safealert.ble

interface BleScanCallback {
    // [v1.0.29] remoteState: 상대 기기의 1바이트 페이로드(0~255). BleService 가 Category/State 언패킹.
    // [v1.0.36] remoteSpeedKmh: 상대 송신 예상속도(km/h, Speed 4비트 디코드). 미지원/비콘은 0.0.
    fun onDeviceDetected(deviceId: String, rssi: Int, alertLevel: Int, remoteState: Int, remoteSpeedKmh: Double = 0.0)
    fun onDeviceLost(deviceId: String)
    fun onScanError(errorCode: Int)
    // UWB 주소가 스캔 응답에서 파싱됐을 때 (기본: 무시)
    fun onUwbAddressReceived(deviceId: String, uwbAddress: ByteArray) {}
}
