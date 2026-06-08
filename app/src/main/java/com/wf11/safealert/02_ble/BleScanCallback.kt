package com.wf11.safealert.ble

interface BleScanCallback {
    fun onDeviceDetected(deviceId: String, rssi: Int, alertLevel: Int)
    fun onDeviceLost(deviceId: String)
    fun onScanError(errorCode: Int)
    // UWB 주소가 스캔 응답에서 파싱됐을 때 (기본: 무시)
    fun onUwbAddressReceived(deviceId: String, uwbAddress: ByteArray) {}
}
