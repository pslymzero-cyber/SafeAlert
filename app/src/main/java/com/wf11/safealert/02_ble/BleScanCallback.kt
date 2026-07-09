package com.wf11.safealert.ble

interface BleScanCallback {
    // [v1.0.29] remoteState: 상대 기기의 1바이트 페이로드(0~255). BleService 가 Category/State 언패킹.
    // [v1.1.7 #1] remoteTurn: 상대 송신 회전 방향(TURN_*, bits 3:2 디코드). 미지원/비콘은 TURN_STRAIGHT.
    // [v1.1.11 C2] payloadPresent: 상대가 실제 1바이트 자기-신고를 송신했는지(true) / 비콘·구버전 부재(false).
    //   IDLE-IDLE 가청 억제를 '진짜 정지 자기-신고' 기기에만 적용해 이동 비콘 장비의 DANGER 무음화 구멍을 막는다.
    // [v1.1.53 상호RSSI] peerEchoRssi: 상대가 되돌려 보낸 '상대가 측정한 나의 RSSI'(rssi_me→peer). 부재/구버전=NO_ECHO_RSSI.
    fun onDeviceDetected(deviceId: String, rssi: Int, alertLevel: Int, remoteState: Int, remoteTurn: Int = BleConstants.TURN_STRAIGHT, payloadPresent: Boolean = false, peerEchoRssi: Int = BleConstants.NO_ECHO_RSSI)
    fun onDeviceLost(deviceId: String)
    fun onScanError(errorCode: Int)
    // UWB 주소가 스캔 응답에서 파싱됐을 때 (기본: 무시)
    fun onUwbAddressReceived(deviceId: String, uwbAddress: ByteArray) {}
}
