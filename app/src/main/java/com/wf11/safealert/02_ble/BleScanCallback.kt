package com.wf11.safealert.ble

interface BleScanCallback {
    // remoteState: the peer's 1-byte payload (0~255); AlertStateMachine unpacks Category/State/Risk.
    // remoteTurn: the peer's transmitted turn direction (TURN_*, decoded from bits 3:2). TURN_STRAIGHT if
    //   unsupported or a beacon.
    // payloadPresent: whether the peer actually sent the 1-byte self-report (true), or it is absent for beacons and
    //   old versions (false). IDLE-IDLE audible suppression applies only to devices that truly self-report being
    //   stopped, closing the hole where equipment carrying a moving beacon would have its DANGER muted.
    // peerEchoRssi: the peer's echoed 'RSSI the peer measured from me' (rssi_me→peer). Absent or old version =
    //   NO_ECHO_RSSI.
    // peerInZone: whether the peer declares zone beacon contact (IN_ZONE) (ServiceData extension byte bit0).
    //   Old version or beacon = false.
    fun onDeviceDetected(deviceId: String, rssi: Int, remoteState: Int, remoteTurn: Int = BleConstants.TURN_STRAIGHT, payloadPresent: Boolean = false, peerEchoRssi: Int = BleConstants.NO_ECHO_RSSI, peerInZone: Boolean = false)
    fun onDeviceLost(deviceId: String)
    fun onScanError(errorCode: Int)
    // A peer's rescue request (extension byte bit1) is delivered separately from onDeviceDetected, which passes
    //   through the zone and walker gates (default: ignored).
    //   episode = advertised rescue request episode (byte2, 0 if none), hint = short ID of the latest beacon
    //   (byte3-4, 0 if none)
    fun onPeerSos(deviceId: String, sos: Boolean, episode: Int = 0, hint: Int = 0) {}
    // When a UWB address is parsed from the scan response (default: ignored)
    fun onUwbAddressReceived(deviceId: String, uwbAddress: ByteArray) {}
    // Zone beacon (zoneMute profile) signal: not added to the device list or judgment, only passed to the zone
    //   state machine (default: ignored)
    fun onZoneBeaconSignal(beaconKey: String, rssi: Int, enterRssi: Int) {}
}
