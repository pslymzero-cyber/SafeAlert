package com.wf11.safealert.model

/**
 * UUID-based beacon profile.
 * Every beacon sharing the same UUID is detected automatically.
 *
 * type:
 *   "IBEACON"      - Apple iBeacon format (Proximity UUID)
 *   "SERVICE_UUID" - custom service UUID format
 */
data class BeaconProfile(
    val uuid: String,               // e.g. "550E8400-E29B-41D4-A716-446655440000"
    val label: String,              // e.g. "현장 작업자", "SmartTag-홍길동"
    val type: String = "IBEACON",
    val addedAt: Long = System.currentTimeMillis(),
    // Detection range correction (dBm offset)
    // 0 = use the global setting
    // +10 = also detect signals 10dBm weaker = roughly 2x the distance (SmartTag recommended: +15)
    val rssiOffset: Int = 0,
    // Zone beacon: when true, this beacon is not an alert target but a 'safe zone' marker.
    //   A device receiving it at zoneEnterRssi or stronger declares IN_ZONE (mutes itself; peers judge it harmless).
    // Default -80: zones use raw RSSI while alerts use EMA + offset, so the scales differ.
    //   A default of -65 would be narrower than the alert threshold (-78, lower still for MAC beacons with an
    //   offset), which by design guarantees a blind spot where the alert fires but the zone never holds.
    val zoneMute: Boolean = false,
    val zoneEnterRssi: Int = -80,
    // Visitor beacon: when true, this UUID profile is treated as a pedestrian,
    //   so walker-mode PDAs are not alerted (only forklifts and EPJs receive it). Beacons attached to equipment use
    //   false. Default true: all existing registered profiles are read as visitor beacons.
    val visitorBeacon: Boolean = true
)
