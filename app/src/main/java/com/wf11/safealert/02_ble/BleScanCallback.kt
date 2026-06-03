package com.wf11.safealert.ble

interface BleScanCallback {
    fun onDeviceDetected(deviceId: String, rssi: Int, alertLevel: Int)
    fun onDeviceLost(deviceId: String)
    fun onScanError(errorCode: Int)
}
