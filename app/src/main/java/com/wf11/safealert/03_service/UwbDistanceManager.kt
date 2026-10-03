package com.wf11.safealert.service

import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.UwbRanger

/**
 * Owner of UWB measurement freshness — whether Case A (UWB-exclusive judging) holds, and lookup of 'fresh' measured distances.
 * Only this class reads UwbRanger.uwbDistances directly (BleService sees it only through this class).
 *
 * @param ranger lambda returning the current UwbRanger reference — it is created,
 * replaced and stopped during the service lifetime, so never snapshot it.
 */
class UwbDistanceManager(private val ranger: () -> UwbRanger?) {

    val peerUwbSeenMap   = mutableMapOf<String, Long>()   // deviceId → last 0x9ABC (UWB active flag) sighting (diagnostic, not judging)
    val uwbSampleAtMsMap = mutableMapOf<String, Long>()   // deviceId → last UWB measurement sample time (basis of Case A freshness)
    val uwbSafeStreakMap = mutableMapOf<String, Int>()    // deviceId → consecutive samples confirming a UWB judgment downgrade
    private val UWB_MEAS_FRESH_MS    = 1_000L  // Freshness window (~8 samples at the normal ~120ms); past it RSSI judges at once
                                               // This window is also the longest UWB judging gap: RSSI takes over within ~1.7m at forklift 1.7m/s

    // ── Case A (UWB↔UWB exclusive judging) check — measurement freshness is the sole authority ────
    //   Last measured sample within UWB_MEAS_FRESH_MS (1s) = judge by UWB distance. Otherwise RSSI judges from that
    //   moment, and when measurements flow again UWB takes over on the first sample — no judging gap either way.
    //   Do not tear down or reopen the ranging session here — connecting and disconnecting is UwbRanger's internal business
    //   (scan response, backoff, end events); judging looks only at the signal. Tearing down via onDeviceLost when samples
    //   stop makes marginal-signal pairs flap endlessly (teardown 1s → rejoin 250ms → teardown), and a controller teardown
    //   cascades through stopActiveLocked into every session, so only the judging mode switches.
    //   The uwbDistances entry check stays — a pair whose entry was removed by an end event goes to RSSI at once,
    //   regardless of timestamp freshness (prevents misjudging on a stale timestamp left behind).
    fun uwbJudgeModeExclusive(deviceId: String, now: Long): Boolean {
        if (!DevSettings.uwbExclusiveJudgeEnabled) return false    // Kill switch off: never UWB-exclusive (RSSI judging)
        val r = ranger() ?: return false                     // My UWB not running (no HW, no permission, or system off)
        if (!r.uwbDistances.containsKey(deviceId)) return false  // No measurement history (before open / after end) → RSSI
        val sampleAt = uwbSampleAtMsMap[deviceId] ?: return false
        return now - sampleAt <= UWB_MEAS_FRESH_MS
    }

    // 'Fresh' measured UWB distance — same freshness window as Case A. Used by Calibrator learning input, distance display (and the UWB tag)
    //   and alert escalation/exit judging (zombie blocking): learning from a stale distance pollutes Δ (skewing the displayed distance),
    //   displaying one passes a dead number off as a measurement, and escalating on one keeps a vanished device alive as a zombie DANGER.
    //   Not fresh → null = RSSI path (estimate / back-calculation).
    fun freshUwbDistM(deviceId: String): Float? {
        val d = ranger()?.uwbDistances?.get(deviceId) ?: return null
        val at = uwbSampleAtMsMap[deviceId] ?: return null
        return if (System.currentTimeMillis() - at <= UWB_MEAS_FRESH_MS) d else null
    }
}
