package com.wf11.safealert.service

import android.os.Build
import android.util.Log
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.ble.BleScanner
import com.wf11.safealert.ble.KalmanFilter
import com.wf11.safealert.ble.MedianFilter
import com.wf11.safealert.ble.RssiPreFilter
import com.wf11.safealert.firebase.FirebaseManager
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.ImuFusion
import com.wf11.safealert.utils.UwbCalibrator
import com.wf11.safealert.utils.UwbRanger

/**
 * Alert state machine — the processAlert / judgeUwbOnly decision paths.
 * Side effects and lookups are delegated to the Effects port.
 */
class AlertStateMachine(
    private val fx: Effects,
    private val uwbDist: UwbDistanceManager,
) {

    /** Side-effect/lookup surface owned by BleService. Exposes only what the decision logic uses. */
    interface Effects {
        val myId: String
        val myMode: String
        val myCategory: Int
        val myZoneInside: Boolean
        var activeSoundLevel: Int
        var lastApproachAtMs: Long
        val bleScanner: BleScanner?
        val uwbRanger: UwbRanger?
        val rssiPreFilter: RssiPreFilter
        val medianFilter: MedianFilter
        val pEmaFilter: RssiPreFilter
        fun getAudibleMaxLevel(): Int
        fun uwbPairKeyFor(deviceId: String): String
        fun resyncSoundToRemaining()
        fun forceAlarmVolume()
        fun isDeviceMuted(deviceId: String): Boolean
        fun updateDwellMute(deviceId: String, level: Int, now: Long, quiet: Boolean = false)
        fun isDwellMuted(deviceId: String, level: Int): Boolean
        fun clearDwellMute(deviceId: String)
        fun updateFloatingOverlay()
        fun collapseOverlay()
        fun sendStatusBroadcast(status: String)
        fun extractDisplayName(deviceId: String): String
        fun makeStateLabel(name: String, category: Int, state: Int): String
        fun sendAlertBroadcast(deviceId: String, level: Int)
        fun broadcastDeviceList()
        fun oneSecAvgRssi(deviceId: String, rssi: Int): Int
        fun recentPeakRssi(deviceId: String, windowMs: Long = 500L): Int?
        fun vibrateDanger()
        fun vibrateWarning()
        fun vibrateRapidApproach()
        fun stopVibration()
        fun playDanger()
        fun playWarning()
    }

    private val TAG = "AlertStateMachine"

    /** Alias of the map owned by UwbDistanceManager (same instance). */
    private val uwbSafeStreakMap = uwbDist.uwbSafeStreakMap

    internal val alertState = mutableMapOf<String, Pair<Int, Long>>()

    // Decision parameters: cooldowns — read live from DevSettings (same pattern as timeGateMs)
    internal val WARNING_COOLDOWN_MS: Long get() = DevSettings.warningCooldownMs

    internal val DANGER_COOLDOWN_MS:  Long get() = DevSettings.dangerCooldownMs

    // ── 2D Kalman filter map (KalmanFilter, tracks distance + velocity together) ──
    internal val kalmanFilters = mutableMapOf<String, KalmanFilter>()

    // Kalman velocity snapshot before cleanup — right before SAFE / departure cleanup / hard gate clears the
    //   Kalman, estimatedVel is kept and consumed once to reseed injectWarmup(initVel) on re-registration
    //   (negative only, capped at -1.5).
    // TTL — the capture time (elapsedRealtime, robust to reboots and clock changes) is stored too; if its age
    //   exceeds the TTL at consumption, it is discarded (restart from 0.0). Normal loss (onDeviceLost) and
    //   stopAll clear the snapshot immediately, so a stale seed only arises on paths that bypass onDeviceLost
    //   (hard-gate preservation, UWB loss deferral, scan-throttle window) — the time cap blocks those leftovers
    //   (simulation: during continuous reception the age is 1 frame, so behavior is unchanged).
    internal data class LastKfVelState(val velocity: Double, val timestamp: Long)

    internal val lastKfVelMap = mutableMapOf<String, LastKfVelState>()

    // Capture time of the snapshot the current Kalman filter was seeded from (absent = started from 0). A seeded filter
    //   hands the seed on with this time, so the TTL runs from the real capture however often the seed is passed on.
    internal val kfSeedAtMap = mutableMapOf<String, Long>()

    internal val KF_VEL_SEED_TTL_MS = 30_000L   // Reseed snapshot lifetime (simulated 30s vs 60s differ by only +0.3s)

    // ── Deferred clear of filter state on loss ──────────────────────
    //   onDeviceLost does not clear the filters immediately; it keeps only the last RSSI snapshot.
    //   Rediscovered within 30s (KF_VEL_SEED_TTL_MS) inside a ±10dB band (FILTER_PRESERVE_BAND_DB) → the warm
    //   filters are reused as is, plus one TimeGate exemption (can alert immediately on rediscovery) — flapping
    //   losses -87% (simulation). Otherwise they are cold-cleared on the spot (same result as clearing on loss).
    internal data class FilterPreserveState(val refRssi: Int, val atMs: Long)

    internal val filterPreserveMap = mutableMapOf<String, FilterPreserveState>()

    internal val timeGateWaiveSet  = mutableSetOf<String>()

    // ── Shadow IMU fusion — parallel tracker on the median stream ─────────────────
    // A shadow Kalman, fully separate from the main pipeline (EMA→Kalman→P-EMA), is fed only the median stream.
    // While my IMU is still (stable observing platform) it opens its process noise gently (0.15) only when the
    // other device self-reports FORWARD inside the warning range, and stays frozen otherwise; while I move it runs
    // at normal noise. Only two outputs — ① EMA fall-alpha boost (0.4, faster release) on frames with a confirmed
    // shadow departure from DANGER, moving or still ② a backup TTC candidate when the main TTC candidate fails
    // (only while I am still). The main Kalman/level/streak/gates/latches are untouched.
    // Kill switch DevSettings.imuShadowFusionEnabled=false, or a missing payload, bypasses every path.
    internal data class ShadowFusion(
        val kf: KalmanFilter = KalmanFilter(DevSettings.kalmanPreset),
        var apprStreak: Int = 0,          // Consecutive approach frames with process noise open (backup TTC eligibility)
        var departFrames: Int = 0,        // Consecutive departure frames (>=2 → tracking)
        var tracking: Boolean = false,    // Tracking a departure (prerequisite for the boost)
        var relLatch: Boolean = false,    // DANGER departure latch (boost held until the level releases to SAFE)
        var lastEffWarning: Int = Int.MIN_VALUE,   // Previous frame effWarning (first frame falls back to rssiWarning)
    )

    internal val shadowFusionMap = mutableMapOf<String, ShadowFusion>()

    internal val SHADOW_CROSS_Q_MILD       = 0.15   // Still + other FORWARD + in warning range: open process noise gently

    internal val SHADOW_Q_FREEZE           = 0.01   // Otherwise: effectively frozen (no noise learning)

    internal val SHADOW_DEPART_REENTER_VEL = 1.5    // Re-approach velocity (dBm/s) — ends departure tracking

    internal val SHADOW_LIVE_VEL_DBM       = -1.0   // Velocity that counts as really moving away (previous frame)

    // ── Conditional Kalman FAST promotion on a rush ─────────────────────────
    // Promotes NORMAL→FAST only when prevVel (previous Kalman velocity) > RUSH_FAST_VEL_DBM holds for
    // RUSH_FAST_MIN_FRAMES consecutive frames, or real IMU acceleration (adaptiveQFactor ≥ RUSH_FAST_IMU_QFACTOR)
    // accompanies it.
    //   ★ Guardrail: a single impulse fakes velocity for only 1 frame (fails 2 consecutive frames), so it cannot
    //     turn FAST on → it cannot undo the Median impulse removal. Reverts to the user preset when the rush ends.
    internal val rushFrameMap = mutableMapOf<String, Int>()

    internal val RUSH_FAST_VEL_DBM     = 2.0   // Rush candidate frame: prevVel above this

    internal val RUSH_FAST_MIN_FRAMES  = 2     // FAST only after N consecutive frames (blocks impulses)

    internal val RUSH_FAST_IMU_QFACTOR = 2.0   // With real IMU acceleration: allow at once if adaptiveQFactor ≥ this

    // ── Fast DANGER on first contact (2-frame confirmation) ────────────────────
    //   Beacons carry no payload, so a new DANGER entry during warm-up (Median window not yet full) is held.
    //   If medianValue (median-of-3) is in the danger range for 2 consecutive frames (blocks single impulses),
    //   bypass the warm-up and approach-velocity gates and allow one immediate alert → a device placed close by
    //   alerts at once instead of late or never.
    internal val dangerContactStreakMap = mutableMapOf<String, Int>()

    // Same raw 2-frame confirmation counter for WARNING distance — a stationary nearby device also bypasses
    //   Time-Gate and warm-up and alerts at once. effDanger ⊂ effWarning, so DANGER distance is covered too
    //   (superset of fastDangerContact). A single impulse stops at streak 1.
    internal val warningContactStreakMap = mutableMapOf<String, Int>()

    // Previous medianValue/time — tells single noise from a real departure when the WARNING streak falls short.
    //   Decided by rate (dBm/s) — release_goldenTimeline (120ms, -1dBm/frame ≈ -8.3dBm/s) is far steeper than
    //   the threshold and resets immediately; slow noisy approaches (1000ms interval, at most ±1dBm/s) stay
    //   below it and keep the streak (for the measured basis see the WARNING_DEPART_RATE_DBM_PER_SEC declaration).
    internal val warningMissRefMap = mutableMapOf<String, Pair<Int, Long>>()

    // ── TTC parameters ──────────────────────────────────────────────────
    // TTC threshold 3.0 s — pre-alert only when a collision is imminent (avoids oversensitive false alerts on site).
    // Decision parameters: read live from DevSettings (defaults 3.0/0.5).
    internal val TTC_THRESHOLD_SEC: Double get() = DevSettings.ttcThresholdSec

    // ★ RSSI-space sign rule: vel > 0 = RSSI rising = approaching / vel < 0 = RSSI falling = leaving
    internal val MIN_APPROACH_VEL_DBM: Double get() = DevSettings.minApproachVelDbm  // Minimum approach velocity for TTC (dBm/s)

    // ── Per-device tracking state machine ──────────────────────────────
    enum class TrackingState { APPROACHING, CROSSING, DEPARTING }

    internal val trackingStateMap   = mutableMapOf<String, TrackingState>()

    internal val crossingStartMap   = mutableMapOf<String, Long>()    // Time CROSSING was entered

    internal val departingStartMap  = mutableMapOf<String, Long>()    // Time DEPARTING was entered

    // State transition parameters (RSSI space)
    internal val CPA_VEL_THRESHOLD             = 0.5   // CPA velocity threshold (dBm/s)

    // Fall-rate threshold (dBm/s): single noise vs real departure when the WARNING streak falls short.
    //   3.0 sits between the measured fall rate of release_goldenTimeline (120ms interval, -1dBm/frame ≈
    //   -8.3dBm/s) and the maximum measured noise fall rate in LowSpeedApproachRegressionTest (1000ms interval,
    //   after median-of-3, at most -1dBm/frame = -1.0dBm/s), with over 3x margin — release exceeds it and resets
    //   immediately (golden unchanged); slow noise stays below it and keeps the streak.
    internal val WARNING_DEPART_RATE_DBM_PER_SEC = 3.0

    internal val CROSSING_CONFIRM_MS           = 1500L // Wait before confirming CROSSING → DEPARTING

    internal val DEPARTING_REENTRY_COOLDOWN_MS = 5000L // Minimum wait before re-entry after DEPARTING

    // Decision parameter: read live from DevSettings (default 8)
    internal val DEPARTING_HYSTERESIS_DBM: Int get() = DevSettings.departingHysteresisDbm // Extra margin to re-alert while DEPARTING (dBm)

    internal val recedingStartMap = mutableMapOf<String, Long>()

    // Decision parameters: fade-out release — read live from DevSettings (defaults 1500L/4, keeps alerts from
    // lingering after vehicles pass each other)
    internal val RECEDING_CLEAR_MS: Long get() = DevSettings.recedingClearMs

    // Departure detection: a raw absolute-max peak sticks to very-close-range BLE noise (±5~10dBm) and causes a
    //   false departure while in danger → sound off. A mid-smoothed EMA reference (recedeRefMap) absorbs the
    //   noise, and the peak slowly decays toward ref when stalled (recedePeakMap), so false departures clear
    //   automatically.
    internal val recedeRefMap   = mutableMapOf<String, Double>()  // Mid-smoothing (EMA) for departure detection only

    internal val recedePeakMap  = mutableMapOf<String, Double>()  // Peak hold + slow decay

    internal val RECEDE_REF_ALPHA = 0.3   // avg1sec → mid-smoothing EMA factor (absorbs close-range noise)

    internal val PEAK_DECAY_ALPHA = 0.05  // Decay toward ref when the peak stalls (clears false departures)

    internal val RECEDING_DBM_DROP: Int get() = DevSettings.recedingDbmDrop

    // Trend release — if the median 2s-window mean (ma) is 2dB below the peak + slope<=0 + the peak rose
    //   >=10dB since entry, held for 0.2s, go SAFE immediately. After release, no re-alert until ma rises
    //   3dB above its minimum (latch).
    internal val TREND_WINDOW_MS = 2000L
    internal val TREND_DROP_DB = 2.0
    internal val TREND_RISE_DB = 10.0
    internal val TREND_HOLD_MS = 200L
    internal val TREND_REARM_DB = 3.0
    internal val trendBufMap       = mutableMapOf<String, ArrayDeque<Pair<Long, Int>>>()
    internal val trendEntryMap     = mutableMapOf<String, Double>()
    internal val trendPeakMap      = mutableMapOf<String, Double>()
    internal val trendDropStartMap = mutableMapOf<String, Long>()
    internal val trendTroughMap    = mutableMapOf<String, Double>()

    /** (mean dBm, LSQ slope dB/s) of the median samples in the window. Slope is 0 if the window is not yet full. */
    private fun trendStats(deviceId: String, now: Long): Pair<Double, Double> {
        val buf = trendBufMap[deviceId] ?: return 0.0 to 0.0
        if (buf.isEmpty()) return 0.0 to 0.0
        val from = now - TREND_WINDOW_MS
        val w = buf.filter { it.first >= from }
        if (w.isEmpty()) return buf.last().second.toDouble() to 0.0
        val ma = w.sumOf { it.second.toDouble() } / w.size
        if (w.size < 2 || buf.first().first > from) return ma to 0.0
        val tm = w.sumOf { it.first.toDouble() } / w.size
        var sxy = 0.0; var sxx = 0.0
        for ((t, v) in w) { val x = (t - tm) / 1000.0; sxy += x * (v - ma); sxx += x * x }
        return ma to (if (sxx > 0) sxy / sxx else 0.0)
    }

    private fun clearTrend(deviceId: String) {
        trendBufMap.remove(deviceId); trendEntryMap.remove(deviceId); trendPeakMap.remove(deviceId)
        trendDropStartMap.remove(deviceId); trendTroughMap.remove(deviceId)
    }

    // Last avgRssi per device — used to pick and sort the top-priority device for the floating widget
    internal val deviceRssiMap     = mutableMapOf<String, Int>()

    // Per-device mute (Acknowledge) — deviceId → mute end time (elapsedRealtime ms). Set by a floating-widget tap, a
    //   mute-all or a temporary mute.
    internal val mutedDevices      = mutableMapOf<String, Long>()

    internal val peerInZoneMap    = mutableMapOf<String, Boolean>() // deviceId → IN_ZONE declared by the other device

    // Display-string override map for devices in the reverse/loading special alert.
    //   Value = fx.makeStateLabel text (e.g. "{name} 지게차 후진 중! 주의!") → shown instead of the plain name in the overlay/list.
    internal val suddenLabelMap    = mutableMapOf<String, String>()

    // Cache of the Category (role) of received devices — stores the decoded CAT_*.
    //   By prefix alone EPJ (01) and forklift (10) are both DEVICE and cannot be told apart, so the Category
    //   unpacked from the 1-byte payload decides the display label (pedestrian/EPJ/forklift).
    internal val deviceCategoryMap = mutableMapOf<String, Int>()

    // Cache of the State (dynamic state) of received devices — stores the decoded PSTATE_*.
    //   Switches the normal display text: stopped (IDLE) = waiting nearby / moving (FORWARD) = approaching.
    //   ※ Reversing/unloading (special alerts) take priority via suddenLabelMap (makeStateLabel) and do not
    //     depend on this cache.
    internal val deviceStateMap    = mutableMapOf<String, Int>()

    // Cache of the turn direction of received devices (TURN_*, decoded from bits 3:2). For display labels/debugging.
    internal val deviceTurnMap     = mutableMapOf<String, Int>()

    // Reverse (forward) warning — state for inferring an RSSI trend reversal on the RX side.
    //   reverseRssiHist: per-device window of (time ms, avg1sec) samples; detects a stable/weakening → sudden
    //   strengthening pattern.
    //   reversePrepUntil: latched to now+holdMs on detection — shows "후진(전진)을 대비해주세요" until then.
    internal val reverseRssiHist   = mutableMapOf<String, ArrayDeque<Pair<Long, Int>>>()

    internal val reversePrepUntil  = mutableMapOf<String, Long>()

    // Mobile-data guard for Firebase alert saves — last save time (ms) per device.
    //   The same device is not re-uploaded within FIREBASE_SAVE_THROTTLE_MS (1 min).
    internal val firebaseLastSaveMap = mutableMapOf<String, Long>()

    // Throttle per level: the key includes the level so each level is counted separately. With deviceId alone
    //   as the key, a DANGER within 1 min after a saved WARNING would be dropped entirely — yet the
    //   WARNING→DANGER escalation is the most important record.
    private fun fbKey(deviceId: String, level: String) = "$deviceId|$level"

    /** On device departure/cleanup, clears all its per-level throttles (including the legacy deviceId-only key). */
    private fun clearFbThrottle(deviceId: String) {
        firebaseLastSaveMap.keys.removeAll { it == deviceId || it.startsWith("$deviceId|") }
    }

    // Upload throttle for UWB ranging samples — last upload time (ms) per role pair.
    //   Decisions run every ~120ms, so uploading each would mean 8 per second. One per second is enough to see
    //   the distance/RSSI distribution, and a real-device measurement session (a few minutes) stays at a few
    //   hundred records.
    private val uwbProbeLastSaveMap = mutableMapOf<String, Long>()
    private val UWB_PROBE_THROTTLE_MS = 1_000L

    /** Records a UWB distance sample. Does nothing when the developer-settings switch is off. */
    private fun uploadUwbProbe(pairKey: String, rssi: Int, distM: Float, now: Long) {
        if (!DevSettings.uwbProbeUploadEnabled) return
        if (now - (uwbProbeLastSaveMap[pairKey] ?: 0L) < UWB_PROBE_THROTTLE_MS) return
        uwbProbeLastSaveMap[pairKey] = now
        FirebaseManager.saveUwbProbe(fx.myId, Build.MODEL, DevSettings.siteCode, pairKey, distM, rssi)
    }

    // Decision parameters: read live from DevSettings (defaults 60_000L/5)
    internal val FIREBASE_SAVE_THROTTLE_MS: Long get() = DevSettings.firebaseThrottleMs

    internal val HYSTERESIS_DBM: Int get() = DevSettings.hysteresisDbm

    // ── Sensitivity delay (Time-Gate) + cornering extension · collision geometry filter ──────────
    // Time-Gate: even inside the alert range, a new (first-detection) alert fires only when the 2D Kalman
    //   derivative (kfVel, dBm/s) stays closing at or above APPROACH_TIMEGATE_VEL_DBM for APPROACH_TIMEGATE_MS
    //   (0.5 s) in a row. → Blocks instant false alarms from radio spikes (single-frame spike). Escalations,
    //   cooldown re-alerts and TTC pre-alerts (which return earlier, above) are exempt, as are 2-frame-confirmed
    //   contacts and a rediscovered device's one-time waiver.
    //   A 0x02 special alert (reversing, unloading) goes through the same check first when first detected;
    //   a switch while already tracked is immediate.
    // Time-Gate delay is read live from DevSettings (applies without an app restart, default 500L).
    internal val APPROACH_TIMEGATE_MS: Long get() = DevSettings.timeGateMs   // Min continuous approach time before a new/escalated alert (normal)

    internal val APPROACH_TIMEGATE_VEL_DBM: Double get() = DevSettings.timeGateVelDbm  // Min approach velocity that counts as closing (dBm/s)

    // Time-Gate extension while cornering — when my equipment turns sharply the radio signal briefly swings,
    //   so the gate is temporarily extended from 0.5 s to 1.0 s to prevent misfires (ImuFusion.isCornering).
    internal val APPROACH_TIMEGATE_CORNERING_MS: Long get() = DevSettings.corneringTimeGateMs
    // Approach streak grace — a short non-approach gap within this time does not break the streak, so one or
    //   two frames of RSSI jitter do not restart the confirmation time from zero.
    internal val APPROACH_STREAK_GRACE_MS = 300L

    // Collision geometry filter parameters.
    //   Meant to convert the combined closing speed (my speed + other speed, km/h) to an RSSI rate (dBm/s) and
    //   compare it with kfVel, but the payload carries no speed: closingSpeedKmh is fixed at 0, so the filter
    //   disables itself and the plain Time-Gate applies (see evalTimeGate). The conversion factor approximates
    //   the danger range (~6m) and path-loss exponent (n≈2.5).
    //   Decision parameters: conversion factor and the two approach ratios — fixed (with no speed in the payload they
    //   cannot change an alert).
    internal val CLOSING_KMH_TO_DBMS = 0.5   // Combined speed (km/h) → expected approach (dBm/s) factor

    internal val COLLISION_MIN_CLOSING_KMH  = 1.0   // Below this combined speed geometry is undecidable (no hold)

    internal val COLLISION_HEAD_ON_RATIO = 0.6   // Actual/expected ratio ≥ this → head-on (passes Time-Gate at once)

    internal val COLLISION_SIDE_RATIO    = 0.3   // Actual/expected ratio ≤ this → side/parallel (hold candidate)

    internal val COLLISION_ABS_SAFE_VEL_DBM = 2.0   // Approach faster than this ignores the side verdict (no false negatives)

    // Fast head-on approach threshold (dBm/s) for passing Time-Gate at once — read live from DevSettings
    //   (default 2.0). Threshold of the path that replaces headOnCourse (always false: combined km/h is not
    //   computed) with the Kalman approach velocity.
    internal val FAST_APPROACH_BYPASS_VEL_DBM: Double get() = DevSettings.fastApproachBypassVelDbm

    // ── New-device alert delay guards ──────────────────────────────────────
    // 1. Cold-Kalman geometry grace: below this Kalman update count vel is still near its initial 0.0, so
    //    closingRatio≈0 → a false sideCourse (side) verdict would hold a rushing device. During warm-up only
    //    the side verdict is disabled — headOn pass-through and Time-Gate stay as they are (stays conservative).
    internal val KALMAN_GEOMETRY_MIN_UPDATES = 5

    // 2. Filter preservation band outside the warning range: even below the gate (effWarning), within this
    //    width (dB) the filter state (Median, EMA, Kalman, P-EMA) is kept, not deleted — converging before the
    //    warning range removes the cold start of a new device (Median 3 frames + seconds of Kalman vel
    //    convergence). Alert logic is still skipped (return), so no false alerts. Outside the band (far away)
    //    the alert-tracking state is cleared, but the device stays listed as a SAFE row. Lost devices are
    //    cleaned up by onDeviceLost.
    internal val FILTER_PRESERVE_BAND_DB: Int get() = DevSettings.filterPreserveBandDb  // Decision parameter, default 10

    internal val pendingDisplayMap = mutableMapOf<String, Long>()   // deviceId → last hold time (ms)

    internal val approachStreakStartMap    = mutableMapOf<String, Long>()  // Start time of the continuous approach (ms)

    internal val fastApproachStreakMap     = mutableMapOf<String, Int>()   // Consecutive fast head-on frames (2-frame confirmation)

    internal val approachLastSeenMap       = mutableMapOf<String, Long>()  // Last approach frame time (ms) — grace for brief gaps

    // Hysteresis latch for the forward-approach bias (forwardApproachBias) — ON/OFF state per deviceId.
    //   Prevents WARNING↔SAFE flicker near the threshold when kfVel jitters around APPROACH_TIMEGATE_VEL_DBM
    //   and payloadOffset (±3dB) would toggle every frame. Engages at the threshold immediately (fail-safe),
    //   releases only below threshold×RELEASE_FRAC (dead band) — an asymmetric latch.
    internal val forwardBiasLatchMap       = mutableMapOf<String, Boolean>()  // deviceId → forward-bias latch state

    internal val FORWARD_BIAS_VEL_RELEASE_FRAC = 0.5  // Release dead band = 50% of the threshold velocity

    internal val UWB_DEMOTE_STREAK    = 3       // Samples to confirm a downgrade (FREQUENT ~120ms → about 0.4s)

    internal val UWB_RELEASE_HYST_M   = 0.5f    // Damps boundary oscillation — while held, keep the level up to threshold+0.5m


    private fun calcLevelWithHysteresis(deviceId: String, rssi: Int, rssiOffset: Int = 0): Int {
        val prevLevel = alertState[deviceId]?.first ?: BleConstants.LEVEL_SAFE
        val warning   = BleConstants.rssiWarning - rssiOffset
        val danger    = BleConstants.rssiDanger  - rssiOffset
        return when {
            // Distance-based DANGER — even a slow approach (kfVel too low → no TTC, not reversing/unloading) is
            //   DANGER once inside the danger range.
            rssi >= danger -> BleConstants.LEVEL_DANGER
            prevLevel >= BleConstants.LEVEL_DANGER && rssi >= danger - HYSTERESIS_DBM -> BleConstants.LEVEL_DANGER
            rssi >= warning -> BleConstants.LEVEL_WARNING
            prevLevel >= BleConstants.LEVEL_WARNING && rssi >= warning - HYSTERESIS_DBM -> BleConstants.LEVEL_WARNING
            else -> BleConstants.LEVEL_SAFE
        }
    }

    /**
     * Computes the alert-threshold risk offset (dB) from the decoded hex (role, state).
     *   The returned (+) amount pulls the warning/danger thresholds out to a longer distance for an earlier
     *   alert (fail-safe direction); a negative amount (EPJ↔EPJ default) pulls them in.
     *   · Role: pedestrian↔forklift adds walkerVsEquipBiasDb, pedestrian↔EPJ walkerVsEpjBiasDb (mutual
     *     protection); equipment↔equipment adds equipVsEquipBiasDb, or epjVsEpjBiasDb when both are EPJ.
     *   · State: if the other device is moving FORWARD and approaching (kfVel), add forwardApproachBiasDb as well.
     * The state bias is guarded by a per-deviceId hysteresis latch: even if kfVel jitters near the threshold,
     *   once forwardBiasLatchMap turns the bias on it stays until kfVel drops below threshold×RELEASE_FRAC, so
     *   payloadOffset (±forwardApproachBiasDb) does not toggle every frame and make WARNING↔SAFE flicker.
     *   Engages at the threshold immediately (fail-safe), releases only after crossing the dead band — asymmetric.
     *   0 when the toggles (categoryBiasEnabled/stateModulationEnabled) are off or the pair/state does not apply.
     */
    private fun computePayloadRiskOffset(deviceId: String, rCategory: Int, rState: Int, kfVel: Double): Int {
        var offset = 0
        if (DevSettings.categoryBiasEnabled) {
            val iAmWalker   = fx.myCategory == BleConstants.CAT_WALKER
            val iAmForklift = fx.myCategory == BleConstants.CAT_FORKLIFT
            val iAmEpj      = fx.myCategory == BleConstants.CAT_EPJ
            val rIsWalker   = rCategory == BleConstants.CAT_WALKER
            val rIsForklift = rCategory == BleConstants.CAT_FORKLIFT
            val rIsEpj      = rCategory == BleConstants.CAT_EPJ
            // Per role pair: pedestrian↔forklift gets a strong early warning (+6), pedestrian↔EPJ a milder one (+2).
            //   EPJs work slowly in the same space, so the forklift threshold would over-alert → a separate offset.
            //   Equipment↔equipment gets equipVsEquipBiasDb below; only pedestrian↔pedestrian is 0.
            if ((iAmWalker && rIsForklift) || (iAmForklift && rIsWalker)) {
                offset += DevSettings.walkerVsEquipBiasDb
            }
            if ((iAmWalker && rIsEpj) || (iAmEpj && rIsWalker)) {
                offset += DevSettings.walkerVsEpjBiasDb
            }
            // Equipment↔equipment (forklift/EPJ with each other) — only when both sides are equipment (mutually
            //   exclusive with the pedestrian branch). Covers the blind spot left because every pedestrian offset is
            //   pedestrian-only. Compensates for metal-cab shielding.
            val iAmEquip = iAmForklift || iAmEpj
            val rIsEquip = rIsForklift || rIsEpj
            if (iAmEquip && rIsEquip) {
                // EPJ↔EPJ: when both sides are EPJ (no forklift), a separate offset for distance discrimination.
                //   EPJs have weak shielding, move slowly (3km/h) and normally work 5m apart, so +8 would over-alert
                //   → lowered to epjVsEpjBiasDb (default -2). If a forklift is on either side (strong shielding, hazard
                //   source), equipVsEquipBiasDb (+8) applies.
                if (iAmEpj && rIsEpj) {
                    offset += DevSettings.epjVsEpjBiasDb
                } else {
                    offset += DevSettings.equipVsEquipBiasDb
                }
            }
        }
        // Forward-approach bias: the kfVel threshold is wrapped in a hysteresis latch.
        if (DevSettings.stateModulationEnabled && rState == BleConstants.PSTATE_FORWARD) {
            val wasLatched = forwardBiasLatchMap[deviceId] ?: false
            val latched = when {
                kfVel >= APPROACH_TIMEGATE_VEL_DBM                              -> true   // Engage: at the threshold immediately (fail-safe)
                kfVel <  APPROACH_TIMEGATE_VEL_DBM * FORWARD_BIAS_VEL_RELEASE_FRAC -> false  // Release: after crossing the dead band
                else                                                           -> wasLatched  // In between: hold
            }
            forwardBiasLatchMap[deviceId] = latched
            if (latched) offset += DevSettings.forwardApproachBiasDb
        } else {
            forwardBiasLatchMap.remove(deviceId)  // Not FORWARD / toggle OFF → reset the latch
        }
        return offset
    }

    internal val wasStationaryMap  = mutableMapOf<String, Boolean>()

    // ── Single removal path for device state ─────────────────────────────
    /** State slot registry — BleService registers its own maps and filters here via `asm.registry`. */
    val registry = DeviceStateRegistry()

    init {
        // immediate — removed as soon as the signal is lost (the ASM's own maps)
        registry.addImmediate("alertState", alertState)
        registry.addImmediate("rushFrameMap", rushFrameMap)
        registry.addImmediate("dangerContactStreakMap", dangerContactStreakMap)
        registry.addImmediate("warningContactStreakMap", warningContactStreakMap)
        registry.addImmediate("warningMissRefMap", warningMissRefMap)
        registry.addImmediate("lastKfVelMap", lastKfVelMap)
        registry.addImmediate("timeGateWaiveSet", timeGateWaiveSet)
        registry.addImmediate("shadowFusionMap", shadowFusionMap)
        registry.addImmediate("trackingStateMap", trackingStateMap)
        registry.addImmediate("crossingStartMap", crossingStartMap)
        registry.addImmediate("departingStartMap", departingStartMap)
        registry.addImmediate("wasStationaryMap", wasStationaryMap)
        registry.addImmediate("recedingStartMap", recedingStartMap)
        registry.addImmediate("recedeRefMap", recedeRefMap)
        registry.addImmediate("recedePeakMap", recedePeakMap)
        registry.addImmediate("trendBufMap", trendBufMap)
        registry.addImmediate("trendEntryMap", trendEntryMap)
        registry.addImmediate("trendPeakMap", trendPeakMap)
        registry.addImmediate("trendDropStartMap", trendDropStartMap)
        registry.addImmediate("trendTroughMap", trendTroughMap)
        registry.addImmediate("deviceRssiMap", deviceRssiMap)
        registry.addImmediate("approachStreakStartMap", approachStreakStartMap)
        registry.addImmediate("fastApproachStreakMap", fastApproachStreakMap)
        registry.addImmediate("approachLastSeenMap", approachLastSeenMap)
        registry.addImmediate("forwardBiasLatchMap", forwardBiasLatchMap)
        registry.addImmediate("peerInZoneMap", peerInZoneMap)
        registry.addImmediate("suddenLabelMap", suddenLabelMap)
        registry.addImmediate("deviceCategoryMap", deviceCategoryMap)
        registry.addImmediate("deviceStateMap", deviceStateMap)
        registry.addImmediate("deviceTurnMap", deviceTurnMap)
        registry.addImmediate("reverseRssiHist", reverseRssiHist)
        registry.addImmediate("reversePrepUntil", reversePrepUntil)
        registry.addImmediate("firebaseLastSaveMap", firebaseLastSaveMap)
        registry.addImmediate("pendingDisplayMap", pendingDisplayMap)

        // immediate — the 3 maps owned by UwbDistanceManager (removed at the same time as ASM state)
        registry.addImmediate("peerUwbSeenMap", uwbDist.peerUwbSeenMap)
        registry.addImmediate("uwbSampleAtMsMap", uwbDist.uwbSampleAtMsMap)
        registry.addImmediate("uwbSafeStreakMap", uwbDist.uwbSafeStreakMap)

        // deferred — only on cold clear or TTL expiry. Kalman is reset before removal.
        registry.addDeferred(
            "kalmanFilters",
            { id -> kalmanFilters[id]?.reset(); kalmanFilters.remove(id) },
            { kalmanFilters.clear() },
            { kalmanFilters.size }
        )
        registry.addDeferred("kfSeedAtMap", kfSeedAtMap)   // lives as long as the filter it describes (kept warm across a loss)
        registry.addDeferred("mutedDevices", mutedDevices) // a short loss keeps an Acknowledge: the user saw that device

        // teardown — clearAll only. Excluded from per-device purge (it would break warm-filter preservation).
        registry.addTeardown("filterPreserveMap", filterPreserveMap)
    }

    /**
     * TTC estimate — uses the RSSI-space 2D Kalman vel directly.
     *
     * ★ RSSI sign rule: vel > 0 = RSSI rising = approaching
     * remaining = (danger threshold RSSI) - (current estimated RSSI)
     * TTC = remaining / vel  (only when vel > MIN_APPROACH_VEL_DBM)
     *
     * @param kfRssi estimated RSSI (dBm)
     * @param kfVel  estimated rate (dBm/s, positive = approaching)
     */
    private fun estimateTTC(kfRssi: Double, kfVel: Double): Double? {
        if (kfVel <= MIN_APPROACH_VEL_DBM) return null  // Not approaching, or too slow
        val remaining = BleConstants.rssiDanger.toDouble() - kfRssi
        if (remaining <= 0) return 0.0                  // Already in the danger zone
        val ttc = remaining / kfVel
        if (DevSettings.logVerbose)   // Per-frame log only when verbose (battery)
            Log.d(TAG, "TTC: kfRssi=%.1f rssiDanger=%d vel=%.2fdBm/s TTC=%.1fs"
                .format(kfRssi, BleConstants.rssiDanger, kfVel, ttc))
        return ttc
    }

    /** Time-Gate result — shared by the special-alert pre-check and the regular first-detection gate. */
    private data class TimeGate(val ms: Long, val streakMs: Long, val fastFrames: Int, val sustained: Boolean, val side: Boolean)

    /**
     * Time-Gate sustained-approach check. Updates the streak and fast-frame maps, so call it only once per frame.
     * A brief non-approach gap within APPROACH_STREAK_GRACE_MS keeps the streak (ignores jitter).
     */
    private fun evalTimeGate(deviceId: String, kfVel: Double, now: Long, kfUpdates: Int): TimeGate {
        val timeGateMs = if (ImuFusion.isCornering) APPROACH_TIMEGATE_CORNERING_MS else APPROACH_TIMEGATE_MS
        val kfApproaching = kfVel >= APPROACH_TIMEGATE_VEL_DBM
        val inGrace = !kfApproaching && approachStreakStartMap.containsKey(deviceId) &&
                      now - (approachLastSeenMap[deviceId] ?: 0L) <= APPROACH_STREAK_GRACE_MS
        if (kfApproaching) {
            approachStreakStartMap.putIfAbsent(deviceId, now)
            approachLastSeenMap[deviceId] = now
        } else if (!inGrace) {
            approachStreakStartMap.remove(deviceId)   // Approach broken (grace exceeded) → reset the streak
            approachLastSeenMap.remove(deviceId)
        }
        val approaching = kfApproaching || inGrace
        val approachStreakMs = if (approaching) now - (approachStreakStartMap[deviceId] ?: now) else 0L

        // Collision geometry — the payload has no speed bits, so the combined closing speed cannot be computed.
        //   closingSpeedKmh=0 → geometryValid=false → the geometry filter disables itself; plain Time-Gate.
        //   (The 2 turn bits only show direction; they are not used to estimate closing speed.)
        val closingSpeedKmh = 0.0                                             // Expected max closing speed (km/h) — not computed
        val expectedKfVel   = closingSpeedKmh * CLOSING_KMH_TO_DBMS            // → expected RSSI approach rate (dBm/s)
        val closingRatio    = if (expectedKfVel > 0.01) kfVel / expectedKfVel else 0.0
        val geometryValid   = closingSpeedKmh >= COLLISION_MIN_CLOSING_KMH     // Undecidable when both are nearly still
        // Head-on course: actual approach ≥ 60% of expected → pass Time-Gate at once (strong alert).
        val headOnCourse    = geometryValid && closingRatio >= COLLISION_HEAD_ON_RATIO
        // Side/parallel: actual approach ≤ 30% of expected and absolute approach also slow (<2.0) → hold (downgrade).
        // Cold-Kalman grace — below the update count vel is near its initial 0.0, so ratio≈0 and even a rushing
        //   device would be judged side course. The side verdict is disabled until the Kalman warms up (headOn
        //   pass-through and Time-Gate are unaffected — with a cold ratio≈0 headOn is false anyway; stays conservative).
        val kalmanWarm      = kfUpdates >= KALMAN_GEOMETRY_MIN_UPDATES
        val sideCourse      = kalmanWarm && geometryValid && closingRatio <= COLLISION_SIDE_RATIO &&
                              kfVel < COLLISION_ABS_SAFE_VEL_DBM

        // Fast head-on approach → pass Time-Gate at once. The 1-byte payload cannot provide closingSpeedKmh (km/h),
        //   so headOnCourse is always false; the Kalman approach velocity (kfVel) fills that gap. kfVel is
        //   phase-leading after the multi-stage Median→EMA→Kalman smoothing, so it catches an approach earlier than
        //   distance (pEma) or the 1 s average → removes the delay where a fast-approaching forklift is held by
        //   Time-Gate (0.5 s) + smoothing lag and only alerts after passing the CPA (closest point).
        //   Single raw spike defense: confirmed only after exceeding the threshold 2 frames in a row (with
        //   multi-stage smoothing a 1-frame jump cannot reach the threshold, and the extra confirmation adds one more
        //   layer against misfires). Side/parallel crossings have low kfVel and are not caught, so over-alerting
        //   barely rises. Threshold = DevSettings.fastApproachBypassVelDbm (live).
        val fastApproachFrames = if (kfVel >= FAST_APPROACH_BYPASS_VEL_DBM)
                                     (fastApproachStreakMap[deviceId] ?: 0) + 1 else 0
        fastApproachStreakMap[deviceId] = fastApproachFrames
        val fastApproach = fastApproachFrames >= 2

        // headOn (combined km/h not computed → always false) or a fast head-on approach (kfVel confirmed for
        //   2 frames) passes Time-Gate at once; otherwise the normal/cornering Time-Gate must be met.
        val approachSustained = headOnCourse || fastApproach || (approaching && approachStreakMs >= timeGateMs)
        return TimeGate(timeGateMs, approachStreakMs, fastApproachFrames, approachSustained, sideCourse)
    }

    /**
     * Updates the tracking state machine (RSSI space).
     *
     * ★ RSSI vel sign rule:
     *   vel > 0 = RSSI rising = pedestrian approaching (APPROACHING)
     *   vel < 0 = RSSI falling = pedestrian leaving (past CPA → DEPARTING)
     *
     * CPA detection: the moment vel turns from positive (+) to negative (-)
     */
    private fun updateTrackingState(deviceId: String, kfVel: Double, now: Long) {
        val current = trackingStateMap[deviceId] ?: TrackingState.APPROACHING
        when (current) {
            TrackingState.APPROACHING -> {
                // vel turns to the negative threshold or below → CPA candidate (enter CROSSING)
                if (kfVel < -CPA_VEL_THRESHOLD) {
                    crossingStartMap[deviceId] = now
                    trackingStateMap[deviceId] = TrackingState.CROSSING
                    Log.d(TAG, "[$deviceId] APPROACHING → CROSSING (vel=%.2fdBm/s)".format(kfVel))
                }
            }
            TrackingState.CROSSING -> {
                when {
                    // Reset dead band — resetting on kfVel >= 0.0 would let a momentary +0.1 blip from radio reflection while
                    //   leaving (Micro-Bounce) reset the 1.5s departure counter every time, so DEPARTING would never be reached
                    //   (the siren never stops). Only a clear re-approach (kfVel >= 1.0) counts as a misjudged CPA and
                    //   reverts; 0.0~1.0 micro-bounces are ignored and the counter is kept.
                    kfVel >= 1.0 -> {
                        // Clearly positive vel → CPA misjudged, back to APPROACHING
                        crossingStartMap.remove(deviceId)
                        trackingStateMap[deviceId] = TrackingState.APPROACHING
                        Log.d(TAG, "[$deviceId] CROSSING → APPROACHING 복귀 (vel=%.2fdBm/s)".format(kfVel))
                    }
                    now - (crossingStartMap[deviceId] ?: now) >= CROSSING_CONFIRM_MS -> {
                        // Negative vel sustained → confirm DEPARTING
                        crossingStartMap.remove(deviceId)
                        departingStartMap[deviceId] = now
                        trackingStateMap[deviceId] = TrackingState.DEPARTING
                        Log.d(TAG, "[$deviceId] CROSSING → DEPARTING 확정")
                        fx.sendStatusBroadcast("↗ 이탈 확인: ${fx.extractDisplayName(deviceId)}")
                    }
                }
            }
            TrackingState.DEPARTING -> {
                val timeDep = now - (departingStartMap[deviceId] ?: now)
                // After the cooldown, a device no longer moving away (vel at or above -CPA threshold) starts over as a
                //   fresh contact, alerting or not: kept DEPARTING it would stay 'moving away' for good, and a hazard that
                //   stops close by or comes back slowly would stay silent. A real departure (vel below it) keeps the state.
                if (timeDep >= DEPARTING_REENTRY_COOLDOWN_MS && kfVel >= -CPA_VEL_THRESHOLD) {
                    departingStartMap.remove(deviceId)
                    trackingStateMap[deviceId] = TrackingState.APPROACHING
                    Log.d(TAG, "[$deviceId] DEPARTING → APPROACHING 재진입 (vel=%.2fdBm/s)".format(kfVel))
                }
            }
        }
    }

    /**
     * Re-entry hysteresis for the DEPARTING state (blocks signal bounces reflected off pillars)
     * - During cooldown: all alerts suppressed (but a strong re-approach vel releases at once — velocity gate)
     * - After cooldown: WARNING allowed; DANGER needs the extra DEPARTING_HYSTERESIS_DBM margin
     */
    private fun applyDepartingHysteresis(
        deviceId: String, rawLevel: Int, blended: Int, offset: Int, now: Long, kfVel: Double
    ): Int {
        val state = trackingStateMap[deviceId] ?: return rawLevel
        if (state != TrackingState.DEPARTING) return rawLevel
        // Velocity gate: even inside the departure suppression window, a strong re-approach (vel > CPA×3 =
        //   1.5dBm/s) releases at once — if a device reacquired after departure cleanup is a forklift coming back,
        //   its real alert is not masked. Reflection bounces (Micro-Bounce ~+0.1dBm/s) stay below the 1.5
        //   threshold, so suppression holds → no false release.
        if (kfVel > (CPA_VEL_THRESHOLD * 3)) return rawLevel
        val timeDep = now - (departingStartMap[deviceId] ?: now)
        return when {
            timeDep < DEPARTING_REENTRY_COOLDOWN_MS -> BleConstants.LEVEL_SAFE
            rawLevel >= BleConstants.LEVEL_DANGER -> {
                val thresh = BleConstants.rssiDanger + DEPARTING_HYSTERESIS_DBM - offset
                if (blended >= thresh) rawLevel else BleConstants.LEVEL_WARNING
            }
            else -> rawLevel
        }
    }

    fun processAlert(deviceId: String, rssi: Int, remoteState: Int = 0x00, remoteTurn: Int = BleConstants.TURN_STRAIGHT, payloadPresent: Boolean = false, peerEchoRssi: Int = BleConstants.NO_ECHO_RSSI, nowMs: () -> Long = { System.currentTimeMillis() }) {
        // Unpacks the received 1-byte payload (ServiceData byte 0, passed through by BleScanner as 0~255)
        //   → Category / State / Risk. remoteTurn arrives already decoded by BleScanner (TURN_*, bits 3:2); it is
        //   for display labels/debug only (the payload has no speed bits).
        val rCategory = BleConstants.decodeCategory(remoteState)
        val rState    = BleConstants.decodeState(remoteState)
        val rRisk     = BleConstants.decodeRisk(remoteState)   // Hazard level (LEVEL_*) sent by the other device — receive side of 2-way alerts
        deviceCategoryMap[deviceId] = rCategory   // Cache for the display label (pedestrian/EPJ/forklift)
        deviceStateMap[deviceId]    = rState      // State cache for display text (stopped = waiting / moving = approaching)
        deviceTurnMap[deviceId]     = remoteTurn   // Turn direction cache (display/debug)

        // Distance estimation here is RSSI-only (Kalman filter): the raw received RSSI enters the pre-processing
        //   pipeline as is, with no UWB distance→RSSI conversion (calibRssiAt1m/pathLossExp).
        val inputRssi: Int = rssi

        val now      = nowMs()   // Clock seam for the golden test harness; the default uses the real clock

        // Restore check on rediscovery after loss — a preserved snapshot, if any, is consumed here exactly once.
        //   Fresh (within 30s) and contiguous (±10dB) → the filter maps are still alive, so getOrPut below returns
        //   the warm Kalman as is (restored) and one TimeGate exemption is granted (can alert immediately on
        //   rediscovery — flapping losses -87% in simulation).
        //   Otherwise → cold clear on the spot (same initial state as an immediate clear on loss).
        filterPreserveMap.remove(deviceId)?.let { snap ->
            val fresh      = android.os.SystemClock.elapsedRealtime() - snap.atMs <= KF_VEL_SEED_TTL_MS
            val contiguous = kotlin.math.abs(inputRssi - snap.refRssi) <= FILTER_PRESERVE_BAND_DB
            if (fresh && contiguous) {
                timeGateWaiveSet.add(deviceId)
            } else {
                fx.rssiPreFilter.clear(deviceId)
                fx.medianFilter.clear(deviceId)
                fx.pEmaFilter.clear(deviceId)
                kalmanFilters[deviceId]?.reset()
                kalmanFilters.remove(deviceId)
            }
        }

        // ── Get or create the 2D Kalman filter ──────────────────────────────
        val kf = kalmanFilters.getOrPut(deviceId) {
            // Cold-start warm-up injection — initializes a new/reacquired device from its first raw RSSI at once
            //   (covariance ↓) to shorten the Kalman velocity (D) convergence delay on reacquisition. (New alerts are
            //   still guarded by the Median N=3 warm-up gate below, so this only speeds up estimate convergence without
            //   any cold-start misfire risk.)
            // Reseeds the departure velocity captured at the previous cleanup (SAFE/departure/hard gate), negative only,
            //   capped at -1.5 — suppresses flapping where re-registering right after a shallow SAFE dip restarts from
            //   velocity 0 and replays the departure check from scratch. The entry is consumed once regardless of sign
            //   (remove).
            //   TTL check — a snapshot older than KF_VEL_SEED_TTL_MS since capture is discarded (restart from 0.0).
            val seed = lastKfVelMap.remove(deviceId)
                ?.takeIf { android.os.SystemClock.elapsedRealtime() - it.timestamp <= KF_VEL_SEED_TTL_MS }
            if (seed != null) kfSeedAtMap[deviceId] = seed.timestamp else kfSeedAtMap.remove(deviceId)
            val seedVel = seed?.velocity?.takeIf { it < 0.0 }?.coerceAtLeast(-1.5) ?: 0.0
            KalmanFilter(DevSettings.kalmanPreset).apply { injectWarmup(inputRssi, seedVel) }
        }
        // Previous frame Kalman velocity (estimatedVel) — shared by the rush FAST check and the D-Boost feedback.
        //   ※ kf.update() is called below, so this is still the previous frame velocity = 1-step derivative feedback.
        val prevVel = kf.estimatedVel

        // ── Conditional Kalman FAST promotion on a rush (with guardrail) ────────
        // The preset this frame kf.update() uses is decided from the previous velocity (causally consistent).
        //   Condition: (prevVel > threshold for 2 consecutive frames) OR (real IMU acceleration). A single impulse
        //   can meet neither → the Median impulse removal stays intact. Otherwise reverts to the user preset.
        val rushFrames = if (prevVel > RUSH_FAST_VEL_DBM) (rushFrameMap[deviceId] ?: 0) + 1 else 0
        rushFrameMap[deviceId] = rushFrames
        val imuRealAccel = ImuFusion.adaptiveQFactor >= RUSH_FAST_IMU_QFACTOR
        val promoteFast  = rushFrames >= RUSH_FAST_MIN_FRAMES || imuRealAccel
        kf.updatePreset(if (promoteFast) DevSettings.KALMAN_PRESET_FAST else DevSettings.kalmanPreset)

        // ── Serial pre-processing: Median (nonlinear impulse removal) → asymmetric EMA + D-Boost (linear smoothing) ──
        //   Pipeline: Raw → Median(N=3) → asymmetric EMA (D-Boost) → Kalman. Single reflection impulses are
        //   removed by order statistics before the linear stages, so they cannot corrupt the Kalman velocity (kfVel).
        val medianValue = fx.medianFilter.push(deviceId, inputRssi)
        // Median series for trend release: TREND_WINDOW_MS window + the 1 sample just before the window edge
        val trendBuf = trendBufMap.getOrPut(deviceId) { ArrayDeque() }
        trendBuf.addLast(now to medianValue)
        while (trendBuf.size > 1 && trendBuf[1].first <= now - TREND_WINDOW_MS) trendBuf.removeFirst()

        // ── Shadow IMU fusion update: median stream only, main pipeline untouched ──
        //   Decide the boost from sPrevVel (previous-frame shadow velocity), sh.tracking (previous-frame departure
        //   tracking) and prevLevel (previous-frame alertState) first, then apply this frame's observation
        //   (causal order).
        val shadowOn = DevSettings.imuShadowFusionEnabled && payloadPresent
        var shadowBoost = false
        val sh = if (shadowOn) shadowFusionMap.getOrPut(deviceId) { ShadowFusion() } else null
        if (sh != null) {
            val selfStat = ImuFusion.isStationary
            val peerFwdFresh = rState == BleConstants.PSTATE_FORWARD
            val sPrevVel = sh.kf.estimatedVel
            val effWarnRef = if (sh.lastEffWarning != Int.MIN_VALUE) sh.lastEffWarning else DevSettings.rssiWarning
            val sqf = when {
                !selfStat -> 1.0
                peerFwdFresh && medianValue >= effWarnRef -> SHADOW_CROSS_Q_MILD
                else -> SHADOW_Q_FREEZE
            }
            sh.kf.updatePreset(if (promoteFast) DevSettings.KALMAN_PRESET_FAST else DevSettings.kalmanPreset)
            val (_, sVel) = sh.kf.update(medianValue, sqf)
            sh.apprStreak = if (sqf > SHADOW_Q_FREEZE && sVel >= MIN_APPROACH_VEL_DBM) sh.apprStreak + 1 else 0
            val prevLevel = alertState[deviceId]?.first ?: BleConstants.LEVEL_SAFE
            // No walker-stationary condition (selfStat): a confirmed shadow departure velocity boosts
            //   whether moving or stopped.
            val live = sh.tracking && sPrevVel <= SHADOW_LIVE_VEL_DBM && peerFwdFresh
            if (live && prevLevel == BleConstants.LEVEL_DANGER) sh.relLatch = true
            if (!live || prevLevel == BleConstants.LEVEL_SAFE) sh.relLatch = false
            shadowBoost = live && (prevLevel == BleConstants.LEVEL_DANGER || sh.relLatch)
        }

        // ── RssiPreFilter: asymmetric proportional-control (P-control) EMA pre-processing ──
        //   On a strong rush (prevVel>+2.0) the α latch (D-Boost) opens to remove lag.
        //   ★ EMA input is medianValue (Median runs first), not raw. Only the refined output (preFiltered) feeds
        //     the Kalman filter (never feed raw directly).
        //   fallBoost=shadowBoost: frames with confirmed departure from DANGER get a falling-alpha boost
        //   (faster release).
        val preFiltered = fx.rssiPreFilter.push(deviceId, medianValue, prevVel, fallBoost = shadowBoost)

        // ── 2D Kalman filter update (RSSI space) ────────────────────────────
        // kfRssi: estimated RSSI (dBm) / kfVel: rate (dBm/s), positive = approaching / negative = departing
        val (kfRssi, kfVel) = kf.update(preFiltered, ImuFusion.adaptiveQFactor)
        if (kfVel > 0.0) fx.lastApproachAtMs = nowMs()  // record approach-sample time for the isDangerPresent power-save gate
        val kalmanRssi = kfRssi.toInt()

        // ── Post-filter P-EMA: smooths only the distance (P) term — kfRssi → asymmetric P-EMA → distance check ──
        //   The D term (kfVel) bypasses the post-filter to keep its phase lead (fed straight to Time-Gate/TTC below).
        //   Only the P term (distance) is smoothed (rise 0.4 / fall 0.15). The hard gate keeps the unsmoothed
        //   kalmanRssi as one of its three median voters.
        //   fallBoost=shadowBoost: same confirmed-departure falling boost as the front EMA (no P-EMA lingering).
        val pEma = fx.pEmaFilter.push(deviceId, kalmanRssi, fallBoost = shadowBoost)

        // ── Warm-up guard: hold new/escalated alerts until the Median window is full (cold start) ──
        //   The first N frames can look like false proximity if they start with an impulse, so filter state keeps
        //   accumulating but alerting is held. Once the window is full the Median impulse defense is complete
        //   (applies to special, TTC and normal alerts).
        val warmingUp = !fx.medianFilter.isFull(deviceId)

        // Raw 1s average for hard-gate / second-gate / TTC cross-checks, computed once, before the gates.
        //   fx.oneSecAvgRssi pushes into its buffer on every call (side effect), so call it once per frame and
        //   reuse the variable.
        val avg1sec = fx.oneSecAvgRssi(deviceId, inputRssi)   // 1s average stays on raw RSSI

        // ── Reverse (forward) prep: infer a trend reversal from RX-side RSSI ──────────────
        //   While approaching vehicle A, if A's signal was steady/weakening and then, within the window (default
        //   1.2s), suddenly turns sharply stronger (closer), A may have started moving toward me in reverse/forward.
        //   Split the window by time into halves: if ① the first-half trend (olderTrend) is steady/weakening (≤tol)
        //   and ② the rise from the first-half trough to now (rise) is at least the threshold (riseDbm), latch
        //   (now+holdMs).
        //   A monotonic approach (me approaching A, the normal case) has a large positive olderTrend and is
        //   excluded automatically.
        // If the peer broadcasts 'stopped (IDLE)' in its hex payload, a reverse/forward inference is a contradiction,
        //   so reversePrep entry is blocked. This inference is only an aid to catch an approach early from an RSSI
        //   trend reversal while the peer is moving (FORWARD); the peer's self-reported state (rState) takes
        //   priority. (Prevents false "reverse prep" alerts when I am moving and the peer is stopped.)
        if (DevSettings.reversePrepEnabled && rState != BleConstants.PSTATE_IDLE) {
            val hist = reverseRssiHist.getOrPut(deviceId) { ArrayDeque() }
            hist.addLast(now to avg1sec)
            val cutoff = now - DevSettings.reverseWindowMs
            while (hist.isNotEmpty() && hist.first().first < cutoff) hist.removeFirst()
            val spanMs = if (hist.size >= 2) hist.last().first - hist.first().first else 0L
            if (hist.size >= 3 && spanMs >= DevSettings.reverseWindowMs / 2) {
                val midTime   = hist.first().first + spanMs / 2
                val firstHalf = hist.filter { it.first <= midTime }
                if (firstHalf.size >= 2) {
                    val olderTrend = firstHalf.last().second - firstHalf.first().second  // positive = stronger (approaching)
                    val troughRssi = firstHalf.minOf { it.second }                        // first-half trough
                    val rise       = avg1sec - troughRssi                                 // rise from trough to now
                    if (olderTrend <= DevSettings.reverseStableTolDb && rise >= DevSettings.reverseRiseDbm) {
                        reversePrepUntil[deviceId] = now + DevSettings.reversePrepHoldMs
                        Log.d(TAG, "후진대비 감지 $deviceId trend=$olderTrend rise=$rise (avg1sec=$avg1sec)")
                    }
                }
            }
        }

        // Display/alert split: every detected SafeAlert device joins the display pool regardless of signal strength.
        //   · deviceRssiMap : RSSI for sorting the list by strength (smoothed kalmanRssi). Filled before the gate so
        //     the device stays listed even if the hard gate below blocks its alert. (Alerting devices are later
        //     overwritten with avgRssi etc. by the normal/special paths.)
        //   · pendingDisplayMap : display membership (+TTL) of non-alerting devices. Only devices not in alertState
        //     are added.
        //   Alerting is decided independently by the hard gate and judgment options below. The sidebar
        //   (hazardListForOverlay) reads only alertState, so weak signals never reach the sidebar and appear only
        //   as SAFE rows in the list.
        deviceRssiMap[deviceId] = kalmanRssi
        if (!alertState.containsKey(deviceId)) pendingDisplayMap[deviceId] = now

        // ── Use the hex payload (role, state): payload-based asymmetric shift of alert thresholds ──────────
        //   A risk offset derived from the decoded CAT (role) and STATE is applied consistently, as
        //   effWarning/effDanger, to every threshold gate (hard gate, special, TTC, calcLevel, forced SAFE).
        //   Shifting only one place makes the gates conflict (the hard gate blocks an intended early alert), so
        //   all of them use a single effective threshold.
        //   payloadOffset>0 = alert at a weaker signal (longer distance; fail-safe). 0 = no shift.
        val payloadOffset = computePayloadRiskOffset(deviceId, rCategory, rState, kfVel)
        // Per-beacon calibration (rssiOffset) is summed with the payload offset into a single effective threshold.
        //   Applying beaconOffset only to calcLevel and departure hysteresis (distance check) would make the hard
        //   gate, reverse special alert, safeForceFloor, cooperative escalation and TTC peak gate ignore beacon
        //   calibration (only half applied). Summing here makes every gate use the same totalOffset.
        //   Offset 0 = no change.
        val beaconOffset = runCatching { BeaconRegistry.getRssiOffsetForFullId(deviceId) }.getOrDefault(0)
        // Global beacon RX strength: the beacon gain (%) from BLE settings is converted to a common dBm correction
        //   and added to beacons only (including offset-0 beacons). Gain 100% (=0dBm) = no change. Not applied to
        //   regular SafeAlert devices.
        val beaconGlobalGain = if (BeaconRegistry.isBeaconFullId(deviceId)) DevSettings.beaconGainDbm else 0
        // UWB delta calibration: for pairs receiving UWB measurements, learn the pair's channel offset Δ from the
        //   true distance + medianValue (spikes removed, lag≈0). The learned correction is not added to the
        //   judgment offset (not part of totalOffset); it is used only for the on-screen distance. Rationale in the
        //   onSample comment below. UwbCalibrator decays Δ linearly over 24h after the last sample.
        val uwbPairKey = fx.uwbPairKeyFor(deviceId)   // learn/look up per role-pair segment, not per device
        // Learning input = fresh measurements only: pairing the current RSSI with a UWB distance whose last sample
        //   is old would corrupt Δ and skew the displayed distance. The distance display uses the same gate.
        uwbDist.freshUwbDistM(deviceId)?.let {
            UwbCalibrator.onSample(uwbPairKey, medianValue, it)
            // Through the same freshness gate, keep raw true-distance/RSSI samples for aggregation (default OFF).
            //   The 0.3~8m quality gate of learning (onSample) is not applied here: mapping RSSI to distance out to
            //   the 15m warning radius needs samples outside that range. Recording only; no effect on judgment.
            uploadUwbProbe(uwbPairKey, medianValue, it, System.currentTimeMillis())
        }
        // Learning (onSample) continues, but its output is fully decoupled from RSSI judgment (learned values are
        //   for distance display only).
        //   When the learned Δ fed the judgment offset, NLOS residuals drifted it up and pushed effDanger until
        //   RSSI judgment reported danger regardless of signal strength. So the learned Δ
        //   stays only in the on-screen distance (distanceTextFor) and is excluded from totalOffset → Case B (RSSI)
        //   uses pure RSSI thresholds (Case A judges by measured distance).
        // Level2 echo auto-calibration: unlike the UWB Δ above, echoCal IS added to totalOffset.
        //   Structural difference: the UWB residual is a one-way absolute comparison, so NLOS attenuation piles up
        //   as bias and drifts; the echo deviation is a two-way differential (my reading of the peer − the peer's
        //   reading of me), so NLOS and distance attenuation cancel as common mode to first order and what remains
        //   is TX/RX hardware asymmetry — exactly what we want to correct.
        //   Safety gear: median (spike-immune), ± clamp, n/spread gate, kill switch echoAutoCalibEnabled
        //   (default ON). OFF or spread too large = 0; too few local ticks fall back to the Firebase model-pair
        //   prior, else 0. Propagation: via effWarning/effDanger it reaches
        //   every derived threshold (coop, safeForceFloor, urgentBypass, ...), same principle as the beacon offset sum.
        val echoCalDb = if (DevSettings.echoAutoCalibEnabled) CalibrationEngine.echoCalAppliedDb(deviceId) else 0
        val totalOffset = payloadOffset + beaconOffset + beaconGlobalGain + echoCalDb
        val effWarning = BleConstants.rssiWarning - totalOffset
        val effDanger  = BleConstants.rssiDanger  - totalOffset

        // ── Case A (UWB↔UWB) exclusive judgment: early branch ─────────────────────────────
        //   Only for pairs with a fresh UWB measurement (uwbJudgeModeExclusive) is this device's alert decision
        //   UWB-only (judgeUwbOnly, driven by onUwbSampleReceived); RSSI never takes part.
        //   Returning here (after filter warm-up, display update and Calibrator learning; before streak, gate and
        //   level judgment) keeps the pre-processing above converging: when measurements stop, the next frame
        //   falls back to Case B (RSSI) automatically and hands over seamlessly with warm filters.
        //   Streak reset = prevents stale inflation on fallback.
        if (uwbDist.uwbJudgeModeExclusive(deviceId, now)) {
            dangerContactStreakMap[deviceId] = 0
            warningContactStreakMap[deviceId] = 0
            warningMissRefMap.remove(deviceId)
            return
        }

        // '2-frame proximity confirmation' counter for fast first-contact alerts (bypasses warm-up and Time-Gate).
        //   ★ The gate signal is medianValue (median-of-3, leading with phase lag≈0), not Kalman or the 1s average
        //   (both lag). Kalman (smoothing) and the 1s average (averaging) both peak later than the physical closest
        //   point (CPA), so the streak would not fill at the closest moment and only fill after passing (departing
        //   side): no alarm when right next to it, alarm when moving away.
        //   medianValue removes only single spikes without smoothing, so the 2-frame confirmation completes on the
        //   approach side (before CPA) (verified in simulation).
        val inDangerRaw = medianValue >= effDanger
        val dangerStreak = if (inDangerRaw) (dangerContactStreakMap[deviceId] ?: 0) + 1 else 0
        dangerContactStreakMap[deviceId] = dangerStreak
        // WARNING distance (effWarning) also uses the same medianValue-led 2-frame confirmation (immediate alert for
        //   stationary proximity). effDanger ⊂ effWarning, so DANGER distance is included automatically.
        //   median-of-3 blocks single impulses, preventing false streaks.
        // On a miss, only the WARNING streak uses the rate of change (dBm/s) to decide whether to reset at once.
        //   On a slow, noisy approach medianValue hovers around the proximity threshold; zeroing on every miss frame
        //   keeps delaying the 2-consecutive-frame confirmation (root cause of field misses). Measured noise falls
        //   slower than WARNING_DEPART_RATE_DBM_PER_SEC, so gentle misses keep the streak (absorb noise). The
        //   continuous fall in release_goldenTimeline far exceeds the threshold, so it still resets to 0 on every
        //   miss frame — golden unchanged.
        //   The DANGER streak (above) still resets on every miss: since effDanger ⊂ effWarning, relaxing only WARNING
        //   achieves the first confirmation (warning level) on slow approaches while keeping immediate DANGER
        //   suppression on the departing side.
        val inWarningRaw = medianValue >= effWarning
        val (prevMedianForWarning, prevAtMsForWarning) = warningMissRefMap[deviceId] ?: (medianValue to now)
        val warningStreak = when {
            inWarningRaw -> (warningContactStreakMap[deviceId] ?: 0) + 1
            else -> {
                val dtSec = (now - prevAtMsForWarning).coerceAtLeast(1L) / 1000.0
                val rateDbmPerSec = (medianValue - prevMedianForWarning) / dtSec
                // Keep the streak only while approaching (kfVel > 0); miss frames while stopped/departing (kfVel <= 0)
                //   reset to 0 at once (blocks self-locking re-escalation after passing by).
                if (rateDbmPerSec <= -WARNING_DEPART_RATE_DBM_PER_SEC || kfVel <= 0.0) 0 else (warningContactStreakMap[deviceId] ?: 0)
            }
        }
        warningContactStreakMap[deviceId] = warningStreak
        warningMissRefMap[deviceId] = medianValue to now
        // Shadow departure tracking + 1-frame effWarning cache. The boost reads the previous frame's tracking
        //   (block right after median, above), so this frame's update takes effect from the next frame (by design).
        if (sh != null) {
            val sVelNow = sh.kf.estimatedVel
            sh.departFrames = if (sVelNow < -MIN_APPROACH_VEL_DBM) sh.departFrames + 1 else 0
            if (sh.departFrames >= 2) sh.tracking = true
            if (sVelNow > SHADOW_DEPART_REENTER_VEL &&
                (!ImuFusion.isStationary || avg1sec >= effWarning)) sh.tracking = false
            sh.lastEffWarning = effWarning
        }
        // IDLE-IDLE audible suppression: if my IMU is stationary and the peer broadcasts IDLE (both stopped = no
        //   collision dynamics), suppress the regular WARNING audible alert below (display, list and widget stay).
        //   DANGER is never suppressed, and if either side moves (rState≠IDLE or IMU moving) it lifts on the next
        //   frame.
        // payloadPresent is required: beacons and older builds (no payload) always decode rState as IDLE, so moving
        //   beacon equipment would look permanently IDLE and DANGER could be demoted to WARNING and silenced.
        //   Suppression is allowed only for devices that actually sent the 1-byte self-report.
        // Role-pair scope: EPJ↔EPJ and EPJ↔walker pairs (no forklift involved) use a separate flag, default ON.
        //   Basis: EPJ close-work sim (sim_epj.py quiet): routine work (IMU duty30) suppresses 42~48% of WARNING
        //   beeps; worst case stationary→rush adds +0.01s audible delay, 0% misses, DANGER fully unchanged. An
        //   approach while moving cannot be suppressed by the gate's structure.
        //   Global flag ON = suppress all pairs (superset); EPJ-pair flag OFF = only the global flag applies
        //   (kill switch).
        val epjQuietPair = (fx.myCategory == BleConstants.CAT_EPJ || rCategory == BleConstants.CAT_EPJ) &&
            fx.myCategory != BleConstants.CAT_FORKLIFT && rCategory != BleConstants.CAT_FORKLIFT
        val quietArmed = DevSettings.idleIdleSuppressEnabled ||
            (DevSettings.idleIdleSuppressEpjPairsEnabled && epjQuietPair)
        val idleIdleQuiet = quietArmed && payloadPresent &&
            ImuFusion.isStationary && rState == BleConstants.PSTATE_IDLE

        // ── Top hard gate (absolute-distance pre-block; mind the negative sign) ─────
        // RSSI is negative. gateRssi = median of three paths: Kalman (kalmanRssi), raw 1s average (avg1sec) and the
        // Median output (medianValue) (sort ascending, take the middle one). If gateRssi is farther (more negative)
        // than the warning threshold (effWarning: rssiWarning, default -78, minus offsets), the device is blocked.
        //   ★ raw cross-check: Kalman alone can sit closer than reality from a momentary reflection (multipath
        //     spike) or smoothing lag and pass far-away false alarms. With raw (avg1sec) as a voter, a falsely
        //     close Kalman is blocked when raw is far.
        //   ★ medianValue as the third leg: the EMA output (preFiltered, slow fall α) lingers after departure (slow
        //     SAFE return); medianValue is raw-order (≈1 frame lag), removes impulses yet follows a real departure
        //     quickly, so the gate releases sooner.
        //   ★ median, not min: with min, one deep dip on any path from BLE fluctuation (±5~10dB) closes the gate,
        //     so a new device just above the threshold (e.g. -81 vs warning -85, 4dB margin) never escalates to a
        //     yellow alert. The median needs 2 of 3 paths to agree on 'far', so a single dip is ignored, while false
        //     proximity still needs 2 paths to agree (avg1sec raw cross-check stays a voter = MASTER invariant).
        // Unless the device was close and is now moving away (cooldown = already tracked in alertState), approach
        // speed, TTC and even the 0x02 special alert are all ignored and it returns at once (inside the
        // preservation band the filters are kept; outside it the alert-tracking state is cleared).
        // Devices already tracked (in alertState) pass the gate, so the receding fade-out logic below releases them
        // smoothly (no abrupt cut).
        val gateRssi = listOf(kalmanRssi, avg1sec, medianValue).sorted()[1]
        // Urgent-approach gate bypass: the voter avg1sec (raw average of the past 1s) is held down by old values
        //   while the signal climbs fast (e.g. a rush around a corner), so even with Kalman and median already in
        //   the danger zone the median (gateRssi) can say 'far' and block a new device's alert outright (return).
        //   On a fast approach (kfVel >= 2.0 dBm/s) or when median is already in the danger zone (effDanger), bypass
        //   the gate to give it a chance to be judged. Bypassing is not alerting: far false alarms are still
        //   filtered by the second defense line below (safeForceFloor forced SAFE), and alerting still needs streak
        //   confirmation (2 frames).
        val urgentBypass = kfVel >= 2.0 || medianValue >= effDanger
        if (!urgentBypass && gateRssi < effWarning && !alertState.containsKey(deviceId)) {   // consistently uses effWarning (payload shift)
            // Filter preservation band: devices just below the warning threshold (inside the band) keep their filter
            //   state and skip only the alert logic. Median, EMA, Kalman, P-EMA and the 1s buffer were already updated
            //   with this frame above, so they warm up while in the band and judge immediately, warm, from the frame
            //   the device enters the warning zone. (Clearing everything every frame would cold-start on entry and
            //   delay the alert by seconds.)
            if (gateRssi >= effWarning - FILTER_PRESERVE_BAND_DB) return
            // Display/alert split: even outside the alert range (outside the band), keep deviceRssiMap (list sort),
            //   deviceStateMap (moving/stopped label) and pendingDisplayMap (display membership) so the device stays
            //   listed as a SAFE row. Only alert-tracking state (suddenLabel, filters, Kalman, ...) is cleared here.
            //   (A truly lost device is fully cleared by onDeviceLost.)
            suddenLabelMap.remove(deviceId)
            deviceCategoryMap.remove(deviceId)
            deviceTurnMap.remove(deviceId); reverseRssiHist.remove(deviceId); reversePrepUntil.remove(deviceId)
            clearFbThrottle(deviceId)
            timeGateWaiveSet.remove(deviceId) // demoted to untracked: revoke unused TimeGate waiver
            fx.rssiPreFilter.clear(deviceId)     // clear EMA pre-filter state of untracked device
            fx.medianFilter.clear(deviceId)      // clear Median window (resets warm-up)
            fx.pEmaFilter.clear(deviceId)        // clear post-filter P-EMA state
            rushFrameMap.remove(deviceId)     // clear rush frame counter
            dangerContactStreakMap.remove(deviceId)   // clear first-contact DANGER counter
            warningContactStreakMap.remove(deviceId)  // clear first-contact WARNING counter
            warningMissRefMap.remove(deviceId)     // clear WARNING miss reference
            // Capture velocity for reseed (with timestamp). A seeded filter hands the seed on with its original capture time,
            //   so neither frames out of range nor a flicker across the band edge restart its TTL. A filter that started
            //   from 0 gives its velocity a fresh time once warmed up: before that it holds only the start-up transient.
            val seedAt = kfSeedAtMap[deviceId]
            if (seedAt != null || !warmingUp)
                lastKfVelMap[deviceId] = LastKfVelState(kf.estimatedVel, seedAt ?: android.os.SystemClock.elapsedRealtime())
            kalmanFilters.remove(deviceId)    // drop Kalman instance of untracked device (no stale reappearance)
            shadowFusionMap.remove(deviceId)  // clear shadow fusion state (untracked device)
            recedingStartMap.remove(deviceId)    // avoid departure-state leak and stale peak reappearing
            recedeRefMap.remove(deviceId)        // clear mid-smoothing EMA of untracked device
            recedePeakMap.remove(deviceId)       // clear peak hold of untracked device
            clearTrend(deviceId)                 // clear trend state and latch of untracked device
            fx.clearDwellMute(deviceId)             // demoted out of alert range = zone exit: reset dwell mute
            // Keep pendingDisplayMap: weak signals outside the alert range stay listed (SAFE row).
            return
        }

        // ── Reverse/loading special alert (top-priority branch, hybrid cross-check) ──────────
        // If the peer's remoteState is REVERSE (10) or LOADING (11) and both pEma and avg1sec (raw 1s average) are at
        // or above effDanger (rssiDanger with the payload shift, i.e. close), escalate to DANGER immediately, ignoring
        // the TTC/speed/direction/absolute-distance guards. Not entered while the peer declares IN_ZONE.
        //   ★ Distance is judged by pEma (Kalman post-filter P-EMA, distance P term), not kfRssi (Kalman): Kalman
        //     lag can sit closer than reality and cause far false alarms. P-D split: distance (P) is judged by the
        //     smoothed P-EMA; velocity (D=kfVel) bypasses separately.
        //   ★ avg1sec (raw) is ANDed in (hybrid): a departing device's smoothed value stays above the danger zone
        //     for a while from the slow EMA fall and can leave a 'DANGER afterimage (Ghost Danger)'; once the
        //     faster raw 1s average is far (below effDanger) it is blocked at once, removing ghost alerts from
        //     departing devices.
        //   ★ Alerting is held during warmingUp (Median window not full).
        // The display string is overwritten with fx.makeStateLabel (reverse/loading alert text) for the overlay
        // and list.
        //   ★ A first detection (not in alertState) goes through the same confirmation as a normal alert: waiver,
        //     2-frame proximity confirmation (not while departing), or Time-Gate sustained approach. If unconfirmed
        //     it continues on the normal path without the special label.
        //     A device already alerting that switches to reverse/loading goes to DANGER immediately.
        val specialCandidate = (rState == BleConstants.PSTATE_REVERSE || rState == BleConstants.PSTATE_LOADING)
            && !warmingUp                                   // hold alerts from cold-start impulses
            && pEma    >= effDanger                          // distance check: P-EMA vs effDanger (payload shift)
            && avg1sec >= effDanger
            && peerInZoneMap[deviceId] != true               // peer declared IN_ZONE = harmless: block special alert entry
        var preGate: TimeGate? = null
        var specialConfirmed = specialCandidate && alertState[deviceId] != null
        if (specialCandidate && !specialConfirmed) {
            val waived = timeGateWaiveSet.remove(deviceId)
            val g = evalTimeGate(deviceId, kfVel, now, kf.updateCount)
            preGate = g
            val departing = kfVel < -CPA_VEL_THRESHOLD || trackingStateMap[deviceId] == TrackingState.DEPARTING
            specialConfirmed = waived || (!departing && (dangerStreak >= 2 || warningStreak >= 2)) || (g.sustained && !g.side)
        }
        if (specialConfirmed) {
            deviceRssiMap[deviceId]  = kalmanRssi
            suddenLabelMap[deviceId] = fx.makeStateLabel(fx.extractDisplayName(deviceId), rCategory, rState)
            alertState[deviceId]     = Pair(BleConstants.LEVEL_DANGER, now)
            pendingDisplayMap.remove(deviceId)   // alert registered: clear pending display
            fx.bleScanner?.setEcoMode(false)   // switch to combat mode (ACTIVE) immediately
            Log.w(TAG, "특수경보(STATE=$rState CAT=$rCategory): $deviceId pEma=$pEma kfRssi=%.1f".format(kfRssi))
            fx.updateDwellMute(deviceId, BleConstants.LEVEL_DANGER, now)   // special alerts also track dwell (5s continuous dwell = mute)
            // Respect mute (acknowledge/dwell/zone): keep state and display, suppress only sound/vibration
            if (fx.isDeviceMuted(deviceId) || fx.isDwellMuted(deviceId, BleConstants.LEVEL_DANGER) || fx.myZoneInside) {
                fx.updateFloatingOverlay()
                return
            }
            fx.forceAlarmVolume()
            // No !isScreenOn condition: with FLAG_KEEP_SCREEN_ON the screen stays on, so it would kill
            //   foreground vibration.
            if (DevSettings.vibrationEnabled) fx.vibrateDanger()
            if (DevSettings.soundEnabled)     fx.playDanger()
            fx.activeSoundLevel = BleConstants.LEVEL_DANGER   // sync siren level: a later WARNING is neither cut early nor stuck on
            fx.updateFloatingOverlay()
            fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_DANGER)
            fx.sendStatusBroadcast("${suddenLabelMap[deviceId]}")
            return
        }
        // 0x02 cleared (or not close): drop the special label and continue with the normal alert logic
        suddenLabelMap.remove(deviceId)

        // ── Update tracking state machine ───────────────────────────────
        updateTrackingState(deviceId, kfVel, now)
        val newState    = trackingStateMap[deviceId] ?: TrackingState.APPROACHING
        val isNowDepart = newState == TrackingState.DEPARTING
        // Single 'moving away' decision: departing if the phase-leading kfVel (catches departure before the
        //   authoritative distance pEma) is at or below the CPA threshold (clearly moving away), or the tracking
        //   state machine has confirmed DEPARTING. At the CPA peak (kfVel≈0) it is false, so 'right next to it'
        //   counts as approaching (immediate alert kept); once past CPA and kfVel bends below -0.5 it is true,
        //   blocking escalation/re-alerts while moving away, so it doesn't 'ring after passing'.
        val isDepartingNow = kfVel < -CPA_VEL_THRESHOLD || isNowDepart

        // avg1sec (raw 1s average) was already computed before the hard gate above (once per frame).

        // When the stationary (isStationary) DANGER→WARNING demotion applies.
        //   Demoting whenever my IMU is stationary, whoever the peer is, would deny a standing walker the siren
        //   (DANGER) even as moving equipment approaches (an asymmetry where devices ring or stay silent depending
        //   on stopped/moving). Exception (no demotion): I am a walker + peer is equipment (forklift/EPJ) + peer is
        //   active (non-IDLE). Still demoted: equipment driver's phone while stationary (suppresses false alarms
        //   while parked/waiting) unless a walker is in the danger zone (closeWalkerHazard below), walkers standing
        //   close together (chatting), standing next to parked equipment (speed 0, IDLE).
        val movingEquipApproach = fx.myCategory == BleConstants.CAT_WALKER &&
            (rCategory == BleConstants.CAT_FORKLIFT || rCategory == BleConstants.CAT_EPJ) &&
            rState != BleConstants.PSTATE_IDLE
        val demoteWhileStationary = ImuFusion.isStationary && !movingEquipApproach

        var stableLevel: Int
        // Pure authoritative distance before demotion/departure hysteresis (pEma-based, noise-robust).
        //   When demoteWhileStationary artificially demotes a physical DANGER to WARNING, stableLevel alone would
        //   misread it as 'outside the danger zone' → false isReceding → total silence while close. The departure
        //   guard uses the pre-demotion distance (distanceLevel) to prevent that silence. The recovery gate keeps
        //   stableLevel (respects the demotion intent).
        var distanceLevel: Int
        val avgRssi: Int

        // Kalman only (no fixed 1s-average mode, no blend).
        //   Authoritative distance (P) is pure pEma (kalmanRssi → asymmetric post-filter P-EMA). Velocity (D) keeps
        //   its phase lead by going straight to Time-Gate/TTC as kfVel (not smoothed here).
        avgRssi = pEma
        val rawLevel = calcLevelWithHysteresis(deviceId, pEma, totalOffset)   // beacon + payload sum (totalOffset above)
        distanceLevel = rawLevel   // keep distance authority before hysteresis/demotion (departure guard)
        val afterHysteresis = applyDepartingHysteresis(deviceId, rawLevel, pEma, totalOffset, now, kfVel)
        // ── Stationary demotion guard (walker + approaching active equipment is exempt: demoteWhileStationary above) ──
        // If I am equipment (forklift/EPJ) and the peer is a walker/beacon in the danger zone (DANGER), do not demote
        //   DANGER→WARNING even if my IMU is stationary (a forklift stopped on top of a worker is still dangerous).
        //   Use distanceLevel preserved above (pure authoritative distance before demotion/hysteresis) to avoid
        //   contaminating the demotion loop.
        val iAmEquip = fx.myCategory == BleConstants.CAT_FORKLIFT || fx.myCategory == BleConstants.CAT_EPJ
        val closeWalkerHazard = iAmEquip && rCategory == BleConstants.CAT_WALKER &&
            distanceLevel >= BleConstants.LEVEL_DANGER
        stableLevel = if (demoteWhileStationary && !closeWalkerHazard && afterHysteresis >= BleConstants.LEVEL_DANGER)
            BleConstants.LEVEL_WARNING else afterHysteresis

        deviceRssiMap[deviceId] = avgRssi      // used to pick and sort the top device for the floating widget

        // ── Absolute distance guard (second defense line) ─────────────────────
        // The first defense is the hard gate above (gateRssi + return).
        // Here, for devices already tracked (so they passed the first gate), if either avgRssi (pEma) OR the raw-based
        // value (gateRssi, see below) is farther than the warning threshold (rssiWarning), force stableLevel to SAFE
        // and let it flow into the departure fade-out / SAFE handling below. Approach speed or TTC can never
        // escalate it.
        //   ★ avgRssi (=pEma) can stay above the threshold from post-filter smoothing lag even when raw is far, so
        //     the raw side is checked too.
        // For already-tracked devices (in alertState), the forced-SAFE floor matches calcLevel's downward hysteresis
        //   band (effWarning - HYSTERESIS_DBM); otherwise this guard overrides calcLevel's own hysteresis and
        //   WARNING↔SAFE flickers around effWarning. New (untracked) devices keep the effWarning entry floor
        //   (stricter = fail-safe entry).
        val safeForceFloor = if (alertState.containsKey(deviceId)) effWarning - HYSTERESIS_DBM else effWarning
        // Avoid forced SAFE from single-sample noise: use gateRssi (median(Kalman, raw 1s, Median) = median of 3
        //   paths) rather than raw avg1sec (vulnerable to a 1-frame dip). Even if a beacon shows a single −80 dip,
        //   it is not dropped to SAFE while the 3-path median is in the danger zone (removes silence when stopped
        //   close and re-warm-up churn). Still OR-cross-checked with avgRssi (smoothed pEma), so a real departure
        //   (both far) is handled as SAFE normally.
        if (avgRssi < safeForceFloor || gateRssi < safeForceFloor) {
            stableLevel = BleConstants.LEVEL_SAFE
        }

        // ── Two-way cooperative alert (compromise): escalate on the peer's risk broadcast (rRisk) + my RSSI gate ──
        //   When the peer detects danger/warning first and broadcasts it (decodeRisk), and my RSSI (both pEma and
        //   raw) is also within the warning zone (effWarning), raise to the level the peer sent (escalation only —
        //   never demotes).
        //   - onset (alert before passing): if both are approaching, the peer's broadcast rings first, before my
        //     RSSI reaches the DANGER threshold (effDanger).
        //   - safety: false alarms from a far peer are blocked by my RSSI gate (effWarning) (compromise = peer
        //     broadcast ∧ my RSSI close).
        //   - hybrid (avgRssi ∧ avg1sec) cross-check → also blocks false escalation on a departure afterimage
        //     (as in the special alert and safeForceFloor).
        //   Relaxed gate: covers per-phone TX/RX asymmetry (the peer rings but my phone doesn't). The acceptance
        //     threshold is lowered from effWarning by coopSlackDb (default 8dB) toward weaker signals, so the
        //     peer's risk broadcast also rings when I am just short of the warning zone. The normal threshold
        //     (effWarning) is unchanged; truly far false alarms (beyond the slack) are still blocked.
        // Reciprocal RSSI: the baseline-free coopSlack relaxation is a fallback only; when the peer echoes back its
        //   measurement (peerEchoRssi = rssi_me→peer), the symmetric check takes priority.
        //   sym = (my pEma (avgRssi) + the peer's measurement of me (peerEchoRssi)) / 2. Both phones average the
        //   same two values and get the same result, so comparing with a single effWarning threshold makes both
        //   sides agree by construction (no double correction or asymmetry).
        //   Consistency guard: if the two measurements differ by more than reciprocalMaxDisagreeDb (default 25dB)
        //   (bootstrap, hash collision, outlier), symmetry is not trusted and it falls back to coopSlack. No echo
        //   (older builds, beacons) also falls back.
        //   (UWB Case A is promoted separately after this block; reciprocal RSSI is Case B (RSSI) only and does
        //   not touch UWB judgment.)
        // Echo deviation aggregation (telemetry): fully independent of the cooperative escalation below (this
        //   tick's judgment does not use it).
        //   (Level2 uses the accumulated histogram for totalOffset in later ticks, separate from when it is
        //   recorded.)
        //   Only accumulates this tick's difference (my avgRssi ↔ the peer's measurement of me, peerEchoRssi)
        //   into the histogram. Recorded before and regardless of the 25dB gate (hasReciprocal); extreme
        //   asymmetry outside the gate is also observed.
        CalibrationEngine.recordEchoDiff(fx.myId, deviceId, avgRssi, peerEchoRssi)

        val coopFloor = effWarning - DevSettings.coopSlackDb
        val hasReciprocal = DevSettings.reciprocalRssiEnabled &&
            peerEchoRssi != BleConstants.NO_ECHO_RSSI &&
            kotlin.math.abs(avgRssi - peerEchoRssi) <= DevSettings.reciprocalMaxDisagreeDb
        val coopStrong = if (hasReciprocal) {
            (avgRssi + peerEchoRssi) / 2 >= effWarning       // symmetric measurement: single-baseline check
        } else {
            avgRssi >= coopFloor && avg1sec >= coopFloor      // fallback: coopSlack relaxed gate
        }
        if (rRisk > BleConstants.LEVEL_SAFE && rRisk > stableLevel && coopStrong) {
            val beforeCoop = stableLevel
            stableLevel = rRisk.coerceAtMost(BleConstants.LEVEL_DANGER)
            if (hasReciprocal) {
                Log.w(TAG, "협력 격상(상호RSSI): $deviceId rRisk=$rRisk sym=${(avgRssi + peerEchoRssi) / 2}(내pEma=$avgRssi+상대측정=$peerEchoRssi)/2 ≥effWarning=$effWarning → $beforeCoop→$stableLevel")
            } else {
                Log.w(TAG, "협력 격상(폴백·slack=${DevSettings.coopSlackDb}): $deviceId rRisk=$rRisk 내RSSI(pEma=$avgRssi raw=$avg1sec ≥$coopFloor) → $beforeCoop→$stableLevel")
            }
        }

        // ── Immediate raw escalation when close: don't wait for pEma smoothing lag ──────────────
        //   The authoritative distance (pEma) goes through multi-stage asymmetric smoothing (fall α < rise α at
        //   every stage), so its peak lags the physical closest point (CPA) by ~1s or more: at the closest moment
        //   (raw strongest) pEma is still below the threshold, stableLevel can't rise, and nothing rings even when
        //   right next to it. The medianValue-led (unsmoothed median-of-3) 2-frame confirmation fills this gap
        //   (dangerStreak/warningStreak, computed above with a medianValue-based gate): unless moving away
        //   (isDepartingNow=false, i.e. approach up to the CPA peak), reflect the raw danger zone in stableLevel
        //   immediately and alert without smoothing lag.
        //   Not applied while moving away → no re-escalation on the departing side (no 'ringing after passing').
        if (!isDepartingNow && stableLevel < BleConstants.LEVEL_DANGER && dangerStreak >= 2) {
            stableLevel = BleConstants.LEVEL_DANGER
            Log.w(TAG, "[v1.1.22 C] med 즉시 격상 DANGER: $deviceId (dangerStreak=$dangerStreak med=$medianValue raw1s=$avg1sec pEma=$avgRssi kfVel=%.2f)".format(kfVel))
        } else if (!isDepartingNow && stableLevel < BleConstants.LEVEL_WARNING && warningStreak >= 2) {
            stableLevel = BleConstants.LEVEL_WARNING
            Log.w(TAG, "[v1.1.22 C] med 즉시 격상 WARNING: $deviceId (warningStreak=$warningStreak med=$medianValue raw1s=$avg1sec pEma=$avgRssi kfVel=%.2f)".format(kfVel))
        }

        // ── UWB true-distance promotion by role pair (promote-only, default OFF) ────────────────────
        //   A single 3m DANGER threshold is already collision range for a forklift, so it is two-step by role pair:
        //   pairs with a forklift on either side promote at 15m warning / 8m danger, others (EPJ↔walker, etc.) at
        //   5m warning / 3m danger.
        //   Applied on top of the final value after all RSSI escalation/demotion (including stationary demotion and
        //   safeForceFloor forced SAFE), so measured distance covers blind spots where shielding weakens RSSI while
        //   the device is physically close. No suppression or demotion path (assigned only when promoteTo is higher
        //   than the current stableLevel — promote only); devices with no or a dropped UWB session behave as if this
        //   block did not exist (seamless; BLE alerting always runs).
        //   uwbD comes from freshUwbDistM — never read the uwbDistances map directly. Map entries are not removed
        //   immediately on session drop and linger until onDeviceLost/onSessionEnded, so an old close measurement
        //   (e.g. 2m from a forklift that has left) would stay as a zombie and re-promote to DANGER every frame.
        //   freshUwbDistM returns only samples passing the uwbSampleAtMsMap freshness check (≤1s); stale → null →
        //   this block does nothing → natural release via the RSSI fallback.
        //   distanceLevel is also raised, only up to the promoted level. On DANGER promotion this stops the departure
        //   guard below (isReceding) from misreading the shielding-lowered pEma distanceLevel<DANGER as 'departed
        //   from the danger zone' and silencing (early return). WARNING promotion is outside this guard, but if it is
        //   released by a mistaken departure while the measurement is still within the radius, this block
        //   re-promotes on the next frame → re-alert (no permanent silence).
        //   Real departures are still caught by the OR isDepartingNow clause, so departure-side logic is untouched.
        if (DevSettings.uwbPromoteEnabled && stableLevel < BleConstants.LEVEL_DANGER) {
            val uwbD = uwbDist.freshUwbDistM(deviceId)
            if (uwbD != null) {
                val forkliftPair = fx.myCategory == BleConstants.CAT_FORKLIFT ||
                        rCategory == BleConstants.CAT_FORKLIFT
                val warnM = if (forkliftPair) DevSettings.uwbForkliftWarnMeters else DevSettings.uwbPairWarnMeters
                val dangM = if (forkliftPair) DevSettings.uwbForkliftDangerMeters else DevSettings.uwbPairDangerMeters
                val promoteTo = when {
                    uwbD <= dangM -> BleConstants.LEVEL_DANGER
                    uwbD <= warnM -> BleConstants.LEVEL_WARNING
                    else          -> BleConstants.LEVEL_SAFE
                }
                if (promoteTo > stableLevel) {
                    stableLevel = promoteTo
                    if (distanceLevel < promoteTo) distanceLevel = promoteTo
                    val lvName = if (promoteTo == BleConstants.LEVEL_DANGER) "DANGER" else "WARNING"
                    Log.w(TAG, "[v1.1.33] UWB 승격 $lvName: $deviceId d=%.2fm 지게차쌍=$forkliftPair 임계=경고${warnM}m/위험${dangM}m (pEma=$avgRssi)".format(uwbD))
                }
            }
        }

        // ── UWB approach-speed promotion (promote-only, default OFF) ────────────────────────────────
        //   If the approach speed derived from measured distance is at or above the threshold (uwbApproachSpeedKmh,
        //   default 6km/h = forklift speed limit) for 2 samples and the smoothed speed rises with it (blocks false
        //   promotion from stationary multipath spikes), promote early to at least WARNING. DANGER is left to
        //   distance promotion (above); speed alone never raises it to the siren.
        //   No demotion path. Without kinematics (no session / dropped / reset) it behaves as if this block did not
        //   exist.
        //   ※ Not wired into closingSpeedKmh of the collision geometry (a dead input): its sideCourse path holds
        //     back the first alert, which would violate the safety invariant. Used only as an independent promote
        //     block.
        if (DevSettings.uwbVelPromoteEnabled && stableLevel < BleConstants.LEVEL_WARNING) {
            val kin = fx.uwbRanger?.uwbKinematics?.get(deviceId)
            val approachMps = DevSettings.uwbApproachSpeedKmh / 3.6f
            if (kin != null && now - kin.atMs <= 1500L &&   // live kinematics only (no leftovers after ranging stops)
                kin.approachStreak >= 2 && kin.closingMps >= approachMps * 0.6f) {
                stableLevel = BleConstants.LEVEL_WARNING
                if (distanceLevel < stableLevel) distanceLevel = stableLevel
                Log.w(TAG, "[v1.1.34] UWB 접근속도 승격 WARNING: $deviceId 평활v=%.1fkm/h streak=${kin.approachStreak} 임계=${DevSettings.uwbApproachSpeedKmh}km/h".format(kin.closingMps * 3.6f))
            }
        }

        // ── UWB sustained-departure release: alert off when moving apart ─────────────────
        //   An approved exception to the promote-only invariant (reduces over-alerting fatigue). With 3 consecutive
        //   separating samples and a negative smoothed speed, cap the alert by measured distance only: outside the
        //   warning radius = SAFE (full release; the SAFE block below does cleanup, broadcast and sound stop), in the
        //   warning band = WARNING (siren released only; the sound switch is handled by the fail-quiet demotion
        //   correction and canonical dispatch).
        //   Guards: ① opt-in (default off) ② measurement and kinematics live (session drop = entry removed = falls
        //   back to normal RSSI behavior) ③ no intervention inside the role-pair DANGER radius (uwbD ≤ dangM)
        //   ④ veto on strong RSSI approach (kfVel ≥ fastApproachBypassVelDbm): if measurement and RSSI conflict,
        //   keep the alert (fail-safe).
        //   The streak is mutually exclusive with the promote block above (approach/separation counts reset each
        //   other), so both cannot fire in the same frame.
        //   distanceLevel is lowered to the cap as well, so the natural fade-out of the departure guard (isReceding)
        //   applies.
        if (DevSettings.uwbVelReleaseEnabled && stableLevel > BleConstants.LEVEL_SAFE) {
            val kin = fx.uwbRanger?.uwbKinematics?.get(deviceId)
            val uwbNowD = uwbDist.freshUwbDistM(deviceId)
            if (kin != null && uwbNowD != null && now - kin.atMs <= 1500L &&
                kin.separatingStreak >= 3 && kin.closingMps < 0f &&
                kfVel < DevSettings.fastApproachBypassVelDbm) {
                val forkliftPair = fx.myCategory == BleConstants.CAT_FORKLIFT ||
                        rCategory == BleConstants.CAT_FORKLIFT
                val warnM = if (forkliftPair) DevSettings.uwbForkliftWarnMeters else DevSettings.uwbPairWarnMeters
                val dangM = if (forkliftPair) DevSettings.uwbForkliftDangerMeters else DevSettings.uwbPairDangerMeters
                val cap = when {
                    uwbNowD > warnM -> BleConstants.LEVEL_SAFE      // outside warning radius: full release
                    uwbNowD > dangM -> BleConstants.LEVEL_WARNING   // warning band: only DANGER drops to WARNING
                    else            -> stableLevel                  // inside DANGER radius: do not intervene
                }
                if (cap < stableLevel) {
                    Log.w(TAG, "[v1.1.34] UWB 이탈 해제 ${stableLevel}→${cap}: $deviceId d=%.1fm 평활v=%.1fkm/h streak=${kin.separatingStreak} kfVel=%.2f".format(uwbNowD, kin.closingMps * 3.6f, kfVel))
                    stableLevel = cap
                    if (distanceLevel > cap) distanceLevel = cap
                }
            }
        }

        // ── UWB primary distance authority: UWB sets distance only; promotion/release stay with the RSSI workflow ──
        //   Design principle: UWB replaces only the distance input. Promotion (TTC pre-alert below) and release
        //   (isReceding, isDepartingNow) logic stays in the RSSI pipeline. Do not overwrite stableLevel wholesale
        //   (that disables the departure and TTC logic).
        //   · Promote: if the UWB measurement is within the role-pair warning/danger radius, raise to that level.
        //     Covers blind spots where a metal cab or pallets shield RSSI while the device is physically close.
        //   · Release: demote by measured distance only while UWB kinematics say 'moving apart'
        //     (separatingStreak≥3 · closingMps<0): passing by (moving apart) turns it off. No demotion while
        //     approaching or stationary (keep danger = fail-safe). No kfVel veto: for UWB-active pairs the measured
        //     kinematics are the primary authority, so an RSSI multipath rebound (fake approach signal) cannot
        //     block release for seconds.
        //   · No promotion on approach speed alone: raising 'fast approach from far away' to a warning regardless of
        //     distance would false-alarm all day (ignores TTC). Early danger prediction is done by the TTC pre-alert
        //     below (warning zone entered + imminent collision TTC≤threshold).
        //   uwbPrimD comes from freshUwbDistM — only measurements passing the freshness check (≤1s); never read the
        //   map directly. Map entries linger after a session drop, so a stale close value from a device that has
        //   left would stay as a zombie and re-promote to DANGER every frame in (A). null (no fresh measurement) →
        //   this block does nothing (RSSI stableLevel above kept = seamless fallback). RSSI calibration learning
        //   (UwbCalibrator, above) continues independently.
        if (DevSettings.uwbPrimaryAuthorityEnabled) {
            val uwbPrimD = uwbDist.freshUwbDistM(deviceId)
            if (uwbPrimD != null) {
                val forkliftPair = fx.myCategory == BleConstants.CAT_FORKLIFT ||
                        rCategory == BleConstants.CAT_FORKLIFT
                val warnM = if (forkliftPair) DevSettings.uwbForkliftWarnMeters else DevSettings.uwbPairWarnMeters
                val dangM = if (forkliftPair) DevSettings.uwbForkliftDangerMeters else DevSettings.uwbPairDangerMeters
                val uwbLevel = when {
                    uwbPrimD <= dangM -> BleConstants.LEVEL_DANGER
                    uwbPrimD <= warnM -> BleConstants.LEVEL_WARNING
                    else              -> BleConstants.LEVEL_SAFE
                }
                if (uwbLevel > stableLevel) {
                    // (A) Promote: the measurement is closer. Lift a shielding-weakened RSSI level to the measured distance
                    //   (promote-only).
                    val lvName = if (uwbLevel == BleConstants.LEVEL_DANGER) "DANGER" else "WARNING"
                    Log.w(TAG, "[v1.1.36] UWB 거리 격상 ${lvName}: ${deviceId} d=%.2fm 지게차쌍=${forkliftPair} 임계=경고${warnM}m/위험${dangM}m (RSSI=${stableLevel} pEma=${avgRssi})".format(uwbPrimD))
                    stableLevel = uwbLevel
                    if (distanceLevel < uwbLevel) distanceLevel = uwbLevel
                } else if (uwbLevel < stableLevel) {
                    // (B) Departure demotion: lower to the measured distance only while 'moving apart' (passing by = off).
                    //     Approaching/stationary keeps the level. No kfVel veto: for UWB-active pairs the measured kinematics
                    //     are the primary authority, so an RSSI multipath rebound cannot block release for seconds (~1s).
                    val kin = fx.uwbRanger?.uwbKinematics?.get(deviceId)
                    if (kin != null && now - kin.atMs <= 1500L &&
                        kin.separatingStreak >= 3 && kin.closingMps < 0f) {
                        Log.w(TAG, "(v1.1.39) UWB 이탈 강등 ${stableLevel}->${uwbLevel}: ${deviceId} d=%.1fm 멀어짐v=%.1fkm/h streak=${kin.separatingStreak}".format(uwbPrimD, kin.closingMps * 3.6f))
                        stableLevel = uwbLevel
                        if (distanceLevel > uwbLevel) distanceLevel = uwbLevel
                    }
                }
            }
        }

        // Trend-release re-alert latch: after release, hold SAFE until ma rises TREND_REARM_DB above its trough
        trendTroughMap[deviceId]?.let { t0 ->
            val (ma, _) = trendStats(deviceId, now)
            val t = minOf(t0, ma)
            if (ma >= t + TREND_REARM_DB) trendTroughMap.remove(deviceId) else trendTroughMap[deviceId] = t
        }
        if (trendTroughMap.containsKey(deviceId) && !alertState.containsKey(deviceId)) stableLevel = BleConstants.LEVEL_SAFE

        // Peer-harmless check: while the peer declares IN_ZONE (touching a zone beacon at the configured strength),
        //   clamp the level to SAFE (suppression only — no upward override). The SAFE handling below cleans up.
        if (peerInZoneMap[deviceId] == true && stableLevel > BleConstants.LEVEL_SAFE) {
            if (alertState.containsKey(deviceId))
                Log.d(TAG, "(v1.1.62) 피어 존 무해 클램프: $deviceId level=$stableLevel -> SAFE")
            stableLevel = BleConstants.LEVEL_SAFE
        }

        // No per-device sendDetectedBroadcast: right after onDeviceDetected,
        // fx.broadcastDeviceList() sends the whole alertState at once (single source of truth).

        // Dwell tracking: if a device registered in alertState stays at the same level for DWELL_MUTE_MS
        //   continuously, auto-mute that level's sound/vibration (fx.updateDwellMute also re-syncs).
        //   SAFE frames are reset by fx.clearDwellMute in the SAFE handling below (zone exit = release,
        //   re-entry = normal alert).
        //   Only audible time counts (fx.updateDwellMute checks mutes, zone, priority and what is playing); quiet = a
        //   WARNING kept quiet by idle-idle, which still sets the sound level and so only this side knows.
        if (stableLevel >= BleConstants.LEVEL_WARNING && alertState.containsKey(deviceId))
            fx.updateDwellMute(deviceId, stableLevel, now, quiet = stableLevel == BleConstants.LEVEL_WARNING && idleIdleQuiet)

        // ── SAFE handling ───────────────────────────────────────────────────
        if (stableLevel == BleConstants.LEVEL_SAFE) {
            if (alertState.containsKey(deviceId)) {
                alertState.remove(deviceId)
                fx.rssiPreFilter.clear(deviceId)
                fx.medianFilter.clear(deviceId)
                fx.pEmaFilter.clear(deviceId)
                rushFrameMap.remove(deviceId)
                dangerContactStreakMap.remove(deviceId)
                warningContactStreakMap.remove(deviceId)
                warningMissRefMap.remove(deviceId)
                lastKfVelMap[deviceId] = LastKfVelState(kf.estimatedVel, android.os.SystemClock.elapsedRealtime())   // capture departure velocity before reset (for reseed, with timestamp)
                kf.reset()
                kalmanFilters.remove(deviceId)
                shadowFusionMap.remove(deviceId)   // clear shadow fusion state
                // Don't delete tracking maps (state, CROSSING/DEPARTING anchors) too early: always keep them for 3000ms
                //   after entering DEPARTING (Hold); otherwise delete only once pEma has departed enough (≤ warning
                //   threshold -5dBm). Prevents flapping where a shallow SAFE dip wipes the departure anchor and REGISTER
                //   re-alerts repeatedly (verified in simulation). Other cleanup, SAFE broadcast and sound stop run as usual.
                val inDepartHold = trackingStateMap[deviceId] == TrackingState.DEPARTING &&
                    departingStartMap[deviceId]?.let { now - it < 3000L } == true
                if (!inDepartHold && pEma <= effWarning - 5) {
                    trackingStateMap.remove(deviceId)
                    crossingStartMap.remove(deviceId)
                    departingStartMap.remove(deviceId)
                }
                approachStreakStartMap.remove(deviceId)   // drop stale start time so Time-Gate can't pass instantly on re-approach
                fastApproachStreakMap.remove(deviceId)    // drop stale counter so re-approach can't pass in a single frame
                forwardBiasLatchMap.remove(deviceId)      // SAFE demotion: reset latch (fresh on re-approach)
                approachLastSeenMap.remove(deviceId)      // drop stale grace time so dropout grace isn't misapplied on re-approach
                fx.clearDwellMute(deviceId)                  // SAFE = zone exit: reset dwell mute (re-entry alerts normally)
                peerInZoneMap.remove(deviceId)            // SAFE cleanup: next advert re-declares (no stale cache)
                wasStationaryMap.remove(deviceId)
                recedingStartMap.remove(deviceId)
                recedeRefMap.remove(deviceId)
                recedePeakMap.remove(deviceId)
                trendEntryMap.remove(deviceId)       // clear only in-progress trend release; keep the latch (buf/trough)
                trendPeakMap.remove(deviceId)
                trendDropStartMap.remove(deviceId)
                deviceRssiMap.remove(deviceId)
                mutedDevices.remove(deviceId)
                suddenLabelMap.remove(deviceId)
                deviceCategoryMap.remove(deviceId)
                deviceStateMap.remove(deviceId)
                deviceTurnMap.remove(deviceId); reverseRssiHist.remove(deviceId); reversePrepUntil.remove(deviceId)
                clearFbThrottle(deviceId)
                pendingDisplayMap.remove(deviceId)
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                if (alertState.isEmpty()) {
                    AlertSoundPlayer.stopSound()
                    fx.activeSoundLevel = BleConstants.LEVEL_SAFE
                    fx.stopVibration()
                    fx.collapseOverlay()
                } else {
                    fx.resyncSoundToRemaining()  // top device left: lower sound to the remaining max level
                    fx.updateFloatingOverlay()   // switch floating widget to another hazard device
                }
            }
            return
        }

        // Reaching here = non-SAFE (alert situation). Ensure combat mode (ACTIVE) at once, even when stationary.
        fx.bleScanner?.setEcoMode(false)

        // Record IMU stationary→moving transition
        val nowStationary  = ImuFusion.isStationary
        val prevStationary = wasStationaryMap.getOrDefault(deviceId, false)
        wasStationaryMap[deviceId] = nowStationary
        if (prevStationary && !nowStationary) {
            Log.d(TAG, "IMU 정지→이동 전환 [$deviceId]")
        }

        // ── Noise-robust departure detection: mid-smoothing EMA reference + slowly decaying peak ──────────────
        //   Why: measuring peak and drop by the absolute max of raw avg1sec (1s average) fails up close (danger
        //   zone): BLE multipath noise swings avg1sec ±5~10dBm, the peak sticks to a momentary max, (peak-avg1sec)
        //   almost always exceeds RECEDING_DBM_DROP and reads as a 'fake departure' → sound goes off while still
        //   dangerous.
        //   Instead, judge with recedeRef, avg1sec smoothed by EMA (RECEDE_REF_ALPHA), which absorbs the noise.
        //   The peak follows ref immediately when ref rises (approach) and decays slowly toward ref with
        //   PEAK_DECAY_ALPHA when flat/departing → 'came close once, moved a little away and settled' lets the peak
        //   adapt to the new distance and clears the fake departure.
        //   A real departure drops ref faster than the peak, widening (peak-ref), and is caught within ~1~2s.
        //   Independent of Kalman smoothing strength, so departure still releases under strong smoothing.
        //   (1) Danger-zone guard on the smoothed distanceLevel (see the guard below): no departure while it is
        //   DANGER, unless isDepartingNow. Departure only matters once out of the danger zone; after approaching and stopping inside it,
        //   the α asymmetry (ref 0.3 vs peak 0.05) keeps (peak-ref) above RECEDING_DBM_DROP for a long time and would make it
        //   'dangerous yet silent'. ★ A raw avg1sec guard would latch a fake departure whenever ±5~10dBm close-range
        //   noise dips below the danger threshold, again 'dangerous yet silent' — hence a smoothed level.
        //   (2) recede calculation/judgment applies only to devices already alerting (registered in alertState): an
        //   unregistered (new/pending) device must not call the global stopSound on a fake departure and silence
        //   other devices' alerts; unregistered devices get empty recede state, so re-registration starts clean
        //   (no stale peak reappearing → no instant fake departure on re-approach).
        val isReceding: Boolean
        val recedeRef: Double
        val recedePeak: Double
        if (alertState.containsKey(deviceId)) {
            val refPrev = recedeRefMap[deviceId]
            recedeRef =
                if (refPrev == null) avg1sec.toDouble()
                else refPrev + RECEDE_REF_ALPHA * (avg1sec - refPrev)
            recedeRefMap[deviceId] = recedeRef

            val peakPrev = recedePeakMap[deviceId]
            recedePeak = when {
                peakPrev == null     -> recedeRef                                          // first sample: init to current value
                recedeRef > peakPrev -> recedeRef                                          // approaching: peak rises immediately
                else                 -> peakPrev - PEAK_DECAY_ALPHA * (peakPrev - recedeRef)  // flat/departing: slow decay
            }
            recedePeakMap[deviceId] = recedePeak

            // Departure detection: drop of RECEDING_DBM_DROP from the smoothed peak + outside the danger zone
            //   (authoritative-distance guard).
            //   The danger-zone check uses the pEma-based smoothed authoritative value, not raw avg1sec (noise-robust):
            //   avg1sec (1s average) swings ±5~10dBm from close-range multipath, so one deep dip (~-61) would latch
            //   isReceding and the slow peak decay (~0.3dBm/frame) would leave several frames 'dangerous yet silent'.
            //   ★ The guard uses distanceLevel (= pEma distance level before demotion/departure hysteresis), not
            //   stableLevel. Even if demoteWhileStationary artificially demotes a physical DANGER to WARNING, no
            //   departure is accepted while distanceLevel==DANGER, which prevents total silence while close (the
            //   demoted WARNING alert keeps ringing via the canonical path). When really moving away pEma falls,
            //   distanceLevel<DANGER → departure accepted and released. distanceLevel is the pure pEma authoritative
            //   value (immune to raw dips).
            //   OR isDepartingNow also catches departure inside the danger zone (DANGER): distanceLevel<DANGER alone
            //   misses 'moving away' while pEma smoothing lag keeps the distance level at DANGER, allowing re-alerts on
            //   the departing side. Stationary proximity (kfVel≈0, not DEPARTING) has isDepartingNow=false and falls
            //   back to the distanceLevel guard → protection against silence while stopped in the danger zone is kept.
            isReceding = (recedePeak - recedeRef) >= RECEDING_DBM_DROP &&
                (distanceLevel < BleConstants.LEVEL_DANGER || isDepartingNow)
        } else {
            recedeRefMap.remove(deviceId)
            recedePeakMap.remove(deviceId)
            recedeRef = avg1sec.toDouble()
            recedePeak = avg1sec.toDouble()
            isReceding = false
        }

        // ── Trend release: signal turns down from its peak after crossing → SAFE at once, before hysteresis ──
        //   Release when, for 0.2s: the median 2s-window mean (ma) is 2dB↓ from the peak + slope≤0 + the peak rose
        //   ≥10dB above the entry value.
        //   DEPARTING/departingStartMap are not touched (the 5s re-entry cooldown does not apply). For the re-alert
        //   latch see the SAFE gate.
        if (alertState.containsKey(deviceId)) {
            val (ma, slope) = trendStats(deviceId, now)
            val entry = trendEntryMap.getOrPut(deviceId) { ma }
            val peak = maxOf(trendPeakMap[deviceId] ?: ma, ma)
            trendPeakMap[deviceId] = peak
            if (peak - ma >= TREND_DROP_DB && slope <= 0.0 && peak - entry >= TREND_RISE_DB)
                trendDropStartMap.putIfAbsent(deviceId, now)
            else
                trendDropStartMap.remove(deviceId)
            val dropMs = now - (trendDropStartMap[deviceId] ?: now)
            if (trendDropStartMap.containsKey(deviceId) && dropMs >= TREND_HOLD_MS) {
                alertState.remove(deviceId)
                fx.rssiPreFilter.clear(deviceId)
                fx.medianFilter.clear(deviceId)
                fx.pEmaFilter.clear(deviceId)
                rushFrameMap.remove(deviceId)
                dangerContactStreakMap.remove(deviceId)
                warningContactStreakMap.remove(deviceId)
                warningMissRefMap.remove(deviceId)
                kalmanFilters[deviceId]?.let { lastKfVelMap[deviceId] = LastKfVelState(it.estimatedVel, android.os.SystemClock.elapsedRealtime()) }
                kalmanFilters[deviceId]?.reset()
                kalmanFilters.remove(deviceId)
                shadowFusionMap.remove(deviceId)
                wasStationaryMap.remove(deviceId)
                recedingStartMap.remove(deviceId)
                recedeRefMap.remove(deviceId)
                recedePeakMap.remove(deviceId)
                crossingStartMap.remove(deviceId)
                approachStreakStartMap.remove(deviceId)
                fastApproachStreakMap.remove(deviceId)
                forwardBiasLatchMap.remove(deviceId)
                approachLastSeenMap.remove(deviceId)
                fx.clearDwellMute(deviceId)
                mutedDevices.remove(deviceId)   // the release ends the alert its Acknowledge covered (as the SAFE cleanup does)
                deviceRssiMap.remove(deviceId)
                clearFbThrottle(deviceId)
                pendingDisplayMap.remove(deviceId)
                trendEntryMap.remove(deviceId)
                trendPeakMap.remove(deviceId)
                trendDropStartMap.remove(deviceId)
                trendTroughMap[deviceId] = ma
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                if (alertState.isEmpty()) {
                    AlertSoundPlayer.stopSound()
                    fx.stopVibration()
                    fx.collapseOverlay()
                    fx.activeSoundLevel = BleConstants.LEVEL_SAFE
                } else {
                    fx.resyncSoundToRemaining()
                    fx.updateFloatingOverlay()
                }
                fx.sendStatusBroadcast("↘ 추세 하강 → 경보 해제: ${fx.extractDisplayName(deviceId)}")
                Log.d(TAG, "추세 경보 해제: $deviceId (peak=%.1f ma=%.1f entry=%.1f slope=%.2f dB/s, ${dropMs}ms)".format(peak, ma, entry, slope))
                return
            }
        } else {
            trendEntryMap.remove(deviceId)
            trendPeakMap.remove(deviceId)
            trendDropStartMap.remove(deviceId)
        }

        if (isReceding) {
            val justStartedReceding = !recedingStartMap.containsKey(deviceId)
            if (justStartedReceding) {
                recedingStartMap[deviceId] = now
                val others = alertState.filter { (id, pair) -> id != deviceId && pair.first >= BleConstants.LEVEL_WARNING }
                // Muted devices are silent, so they keep nothing sounding; they stay listed, so the sidebar stays open for them
                if (others.none { (id, pair) -> !fx.isDeviceMuted(id) && !fx.isDwellMuted(id, pair.first) }) {
                    AlertSoundPlayer.stopSound()
                    fx.stopVibration()
                    fx.activeSoundLevel = BleConstants.LEVEL_SAFE
                }
                if (others.isEmpty()) fx.collapseOverlay()
                Log.d(TAG, "이탈 감지 즉시 소리 중지: $deviceId (peak=%.1f, ref=%.1f, drop=%.1f dBm)".format(recedePeak, recedeRef, recedePeak - recedeRef))
                fx.sendStatusBroadcast("↗ 이탈 감지 → 경보 일시 해제: ${fx.extractDisplayName(deviceId)}")
            }
            val recedingMs = now - (recedingStartMap[deviceId] ?: now)
            if (recedingMs >= RECEDING_CLEAR_MS && alertState.containsKey(deviceId)) {
                alertState.remove(deviceId)
                fx.rssiPreFilter.clear(deviceId)
                fx.medianFilter.clear(deviceId)
                fx.pEmaFilter.clear(deviceId)
                rushFrameMap.remove(deviceId)
                dangerContactStreakMap.remove(deviceId)
                warningContactStreakMap.remove(deviceId)
                warningMissRefMap.remove(deviceId)
                kalmanFilters[deviceId]?.let { lastKfVelMap[deviceId] = LastKfVelState(it.estimatedVel, android.os.SystemClock.elapsedRealtime()) }   // capture velocity for reseed (with timestamp)
                kalmanFilters[deviceId]?.reset()
                kalmanFilters.remove(deviceId)
                shadowFusionMap.remove(deviceId)   // clear shadow fusion state
                wasStationaryMap.remove(deviceId)
                recedingStartMap.remove(deviceId)
                recedeRefMap.remove(deviceId)
                recedePeakMap.remove(deviceId)
                // Keep the departure cooldown: instead of deleting trackingStateMap/departingStartMap, reset them to
                //   DEPARTING / now, so on reacquisition the 5s applyDepartingHysteresis suppression covers re-alerts while
                //   passing back through the warning range on a 'danger→warning departure'. Departure cleanup means a
                //   confirmed departure, so anchor the cooldown at this moment (now) (force DEPARTING even if the state is
                //   still CROSSING → robust). Coming back within 5s, the speed gate releases it immediately.
                trackingStateMap[deviceId] = TrackingState.DEPARTING
                departingStartMap[deviceId] = now
                crossingStartMap.remove(deviceId)         // CROSSING anchor not needed (DEPARTING confirmed)
                approachStreakStartMap.remove(deviceId)
                fastApproachStreakMap.remove(deviceId)
                forwardBiasLatchMap.remove(deviceId)      // departure cleanup: reset latch
                approachLastSeenMap.remove(deviceId)
                fx.clearDwellMute(deviceId)                  // departure confirmed = zone exit: reset dwell mute
                mutedDevices.remove(deviceId)                // the release ends the alert its Acknowledge covered
                deviceRssiMap.remove(deviceId)
                clearFbThrottle(deviceId)
                pendingDisplayMap.remove(deviceId)
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                if (alertState.isEmpty()) {
                    AlertSoundPlayer.stopSound()
                    fx.stopVibration()
                    fx.collapseOverlay()
                    fx.activeSoundLevel = BleConstants.LEVEL_SAFE
                } else {
                    fx.resyncSoundToRemaining()  // top device departed: lower sound to the remaining max level
                    fx.updateFloatingOverlay()   // update floating widget to the remaining hazard device
                }
                fx.sendStatusBroadcast("↗ 이탈 확인 → 경보 해제: ${fx.extractDisplayName(deviceId)}")
                Log.d(TAG, "이탈 경보 해제: $deviceId (${recedingMs}ms 연속 이탈)")
                return
            }
        } else {
            // Reset the departure accumulator only on frames with confirmed approach. Resetting on every
            //   non-departing frame would let a single RSSI-noise frame that flips isReceding zero the accumulator,
            //   so RECEDING_CLEAR_MS would never be reached ('departure confirmed → alert released' would never fire).
            if (kfVel > CPA_VEL_THRESHOLD) recedingStartMap.remove(deviceId)
            // Fail-loud silence recovery is done in the shouldAlert gate below (!shouldAlert branch), not here.
            //   Re-alerting here could let the canonical alert call playDanger again in the same frame (escalation via
            //   levelEscalated, or cooldown elapsed) — not idempotent → siren stutter. The !shouldAlert branch is
            //   mutually exclusive with canonical alerting, so re-alerts happen only on frames that skip alerting.
        }

        // ── TTC-based pre-alert (uses RSSI-space vel directly, raw guard) ──────────
        // Suppressed in DEPARTING/CROSSING (departing = no collision risk).
        //   Not suppressed by isStationary: equipment rushing at a stationary worker is why TTC exists, so my IMU
        //   being stationary must not block the pre-alert. False alarms are still blocked by the double gate below,
        //   avg1sec (warning zone) and peak500ms.
        //   ★ estimateTTC uses kfRssi (Kalman); if Kalman sits too close from a spike/lag, remaining<=0 gives a
        //     'TTC 0s' false alarm. The pre-alert is allowed only when raw (avg1sec) is actually within the warning
        //     zone (effWarning), blocking far-away TTC-0s false alarms.
        //   ★ Urgent (DANGER) pre-alert gate: only when the max RSSI received in the last 0.5s (peak) is within a
        //     set distance, so a far-away Kalman velocity estimate alone cannot leak an urgent alert. (Falls back
        //     to avg1sec when the buffer is empty.)
        //   ★ The peak gate is tied to effWarning (warning-distance setting), not effDanger (right in front):
        //     requiring 'right in front' would defeat prediction. A fast approach inside the warning distance
        //     (TTC≤ttcThresholdSec, vel>minApproachVelDbm) alerts before reaching danger distance.
        //     False alarms are guarded by those two dials (developer-settings spinners).
        if (!warmingUp                                      // hold pre-alert from cold-start impulses
            && stableLevel == BleConstants.LEVEL_WARNING
            && newState == TrackingState.APPROACHING
            && avg1sec >= effWarning                                            // payload-shifted threshold
            && (fx.recentPeakRssi(deviceId, 500L) ?: avg1sec) >= effWarning) {      // peak gate tied to the warning-distance setting (effWarning)
            // Shadow TTC backup candidate: if the main Kalman candidate fails (slow convergence, approach speed too
            //   low) and I am stationary + peer FORWARD payload + 3 consecutive shadow approach frames, try once more
            //   with the shadow Kalman's (distance, velocity). This frame already passed the gates above (WARNING,
            //   avg1sec, peak).
            var ttc = estimateTTC(kfRssi, kfVel)
            if (ttc == null) {
                val sh2 = if (DevSettings.imuShadowFusionEnabled && payloadPresent &&
                    ImuFusion.isStationary) shadowFusionMap[deviceId] else null
                if (sh2 != null && sh2.apprStreak >= 3)
                    ttc = estimateTTC(sh2.kf.estimatedRssi, sh2.kf.estimatedVel)
            }
            if (ttc != null && ttc <= TTC_THRESHOLD_SEC) {
                alertState[deviceId] = Pair(BleConstants.LEVEL_DANGER, now)  // ★ update first so the list shows DANGER
                pendingDisplayMap.remove(deviceId)   // alert registered: clear pending display
                // A device newly detected approaching via TTC re-alerts through ACK mute (per-device and global).
                mutedDevices.remove(deviceId)
                Log.w(TAG, "TTC 선발령: $deviceId TTC=%.1fs kfVel=%.2fdBm/s".format(ttc, kfVel))
                fx.forceAlarmVolume()
                // TTC rapid-approach vibration: walkers and EPJ (workers) get a dedicated fast pattern
                //   (vibrateRapidApproach), distinguishable by touch from the normal DANGER vibration (equipment rushing at
                //   me = dodge now). Forklift drivers keep the strong pattern (vibrateDanger): too many patterns while
                //   driving only confuse.
                if (DevSettings.vibrationEnabled) {                                     // vibrate in the foreground too
                    if (fx.myCategory == BleConstants.CAT_FORKLIFT) fx.vibrateDanger()
                    else fx.vibrateRapidApproach()
                }
                if (DevSettings.soundEnabled)     fx.playDanger()
                fx.activeSoundLevel = BleConstants.LEVEL_DANGER   // sync siren level
                fx.updateFloatingOverlay()
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_DANGER)
                fx.sendStatusBroadcast("충돌 예측 %.0f초: ${fx.extractDisplayName(deviceId)}".format(ttc))
                return
            }
        }

        // This device's Acknowledge mute (sidebar row tap): keep state tracking only
        if (fx.isDeviceMuted(deviceId)) {
            alertState[deviceId] = Pair(stableLevel, alertState[deviceId]?.second ?: now)
            pendingDisplayMap.remove(deviceId)   // alert registered: clear pending display
            return
        }

        if (DevSettings.logVerbose)   // per-frame log only when verbose (battery)
            Log.d(TAG, ("RSSI raw=$rssi → med=$medianValue → pre=$preFiltered → kf=%.1f → pEma=$pEma " +
                "vel=%.2fdBm/s state=$newState stable=$stableLevel fast=$promoteFast warm=$warmingUp").format(kfRssi, kfVel))

        val prev = alertState[deviceId]
        val prevLevel     = prev?.first  ?: BleConstants.LEVEL_SAFE
        val lastAlertTime = prev?.second ?: 0L
        val baseCooldown  = if (stableLevel == BleConstants.LEVEL_DANGER) DANGER_COOLDOWN_MS else WARNING_COOLDOWN_MS
        // DEPARTING: double the cooldown (prevents ping-pong)
        val cooldown = if (isNowDepart) baseCooldown * 2 else baseCooldown

        val isFirstDetection = prev == null
        val levelEscalated   = stableLevel > prevLevel
        val cooldownPassed   = now - lastAlertTime >= cooldown
        // During warm-up (Median not yet filled) new/escalated alerts are held — guards against cold-start impulse contamination.
        // Exception: a contact confirmed for 2 frames by medianValue (median-of-3 in the danger zone) may fire once immediately,
        //   whether warming up or already tracking, and also bypasses the Time-Gate (0.5 s) below — a beacon that suddenly
        //   enters danger range alerts without waiting for the Median to fill. Excluded while isDepartingNow (no alerts on the
        //   departing side). stableLevel was already raised by the immediate-escalation block above when medianValue is in the
        //   danger zone, so this does not depend on smoothing lag.
        val fastDangerContact = !isDepartingNow && dangerStreak >= 2 && stableLevel >= BleConstants.LEVEL_DANGER
        // A first contact at WARNING range confirmed for 2 frames also bypasses warm-up and the Time-Gate and fires at once
        //   (stationary proximity alerts immediately).
        //   stableLevel>=WARNING & warningStreak>=2 covers the DANGER case (stableLevel>=DANGER & dangerStreak>=2) → superset.
        val fastContact = fastDangerContact ||
            (!isDepartingNow && warningStreak >= 2 && stableLevel >= BleConstants.LEVEL_WARNING)
        // Block escalation and re-alerts while moving away (isDepartingNow) — risk cannot grow while physically separating,
        //   so levelEscalated there is a pEma smoothing-lag artifact. Cooldown re-alarms are banned on the departing side too,
        //   otherwise alarms sound after the vehicle has passed. Only isFirstDetection (a brand-new contact) stays unguarded
        //   as a fail-safe.
        val shouldAlert = (!warmingUp || fastContact) &&
            (isFirstDetection ||
             (levelEscalated && !isDepartingNow) ||
             (cooldownPassed && !isReceding && !isDepartingNow))
        if (!shouldAlert) {
            // A new device whose alert is held (warm-up etc.) — not an alert, but it is still listed as detected.
            if (isFirstDetection) pendingDisplayMap[deviceId] = now
            // Fail-loud silence recovery — runs only on frames that skip alerting (we return right after).
            //   After a previous departure episode's immediate stopSound sets fx.activeSoundLevel=SAFE, a device still/again in the
            //   danger zone (stableLevel==DANGER) with shouldAlert=false (cooldown not elapsed) would stay silent for up to one
            //   cooldown (~2 s). Close that gap with an immediate DANGER re-alert (ignoring the cooldown) when the device is
            //   tracked + the smoothed authority value is in the danger zone + the current sound is below DANGER. The canonical
            //   alert runs after this return, so the two are mutually exclusive → no double playDanger (non-idempotent stutter).
            //   The criterion is the smoothed stableLevel (pEma-based), the same distance authority as the canonical path, so a
            //   raw noise dip can't leave a smoothed-DANGER device silent.
            //   !isReceding is an explicit guard: isReceding also includes isDepartingNow, so it can be true at
            //   stableLevel>=DANGER while moving away; without it this recovery would revive, in the same frame, a siren the
            //   departure branch just stopped (alerts persisting while departing).
            if (!fx.isDeviceMuted(deviceId) && alertState.containsKey(deviceId) &&
                !fx.isDwellMuted(deviceId, stableLevel) &&   // respect dwell mute — intentional silence is not 'recovered'
                !fx.myZoneInside &&                          // inside a zone = audible suppressed — fail-loud recovery won't revive it
                stableLevel >= BleConstants.LEVEL_DANGER && !isDepartingNow && !isReceding &&   // no silence-recovery re-alert on the departing side
                fx.activeSoundLevel < BleConstants.LEVEL_DANGER) {
                fx.forceAlarmVolume()
                fx.activeSoundLevel = BleConstants.LEVEL_DANGER
                if (DevSettings.vibrationEnabled) fx.vibrateDanger()
                if (DevSettings.soundEnabled)     fx.playDanger()
                fx.updateFloatingOverlay()
                Log.d(TAG, "위험권 유지·무음 감지 → 즉시 재발령(쿨다운 무시): $deviceId (stableLevel=$stableLevel avg1sec=$avg1sec)")
            }
            // Fail-quiet demotion fix — mirror image of the fail-loud re-alert above (also ignores the cooldown).
            //   When a tracked device is demoted DANGER→WARNING by demoteWhileStationary (stationary proximity) or hysteresis,
            //   the DANGER siren loop still playing goes stale. A frame whose cooldown hasn't elapsed never reaches the canonical
            //   stopSound, and playWarning is a no-op while the danger loop isPlaying, so the sound never switches to WARNING —
            //   the DANGER siren would last up to one cooldown (several seconds) or effectively forever. Stop it here immediately
            //   and replay the current (lower) level.
            //   Keep it if another device is still at an equal or higher level: alertState[deviceId] still holds the old level here
            //   (updated later on the canonical path), so decide with otherMax (excluding this device) instead of
            //   getCurrentMaxLevel(). Lowering the sound is always safe regardless of isDepartingNow (even preferable when departing).
            else if (!fx.isDeviceMuted(deviceId) && alertState.containsKey(deviceId) &&
                     fx.activeSoundLevel >= BleConstants.LEVEL_DANGER && stableLevel < fx.activeSoundLevel) {
                val otherMax = alertState.entries
                    .filter { it.key != deviceId && !fx.isDwellMuted(it.key, it.value.first) &&
                        !fx.isDeviceMuted(it.key) }   // muted devices don't own the sound
                    .maxOfOrNull { it.value.first } ?: BleConstants.LEVEL_SAFE
                if (otherMax < fx.activeSoundLevel) {
                    AlertSoundPlayer.stopSound()
                    fx.activeSoundLevel = stableLevel
                    if (stableLevel == BleConstants.LEVEL_WARNING && !idleIdleQuiet &&
                        !fx.isDwellMuted(deviceId, BleConstants.LEVEL_WARNING) &&   // skip re-alert when WARNING is dwell-muted (the demotion stop still applies)
                        !fx.myZoneInside) {   // inside a zone = audible suppressed — no corrective re-alert (still stops)
                        if (DevSettings.vibrationEnabled) fx.vibrateWarning()
                        if (DevSettings.soundEnabled)     fx.playWarning()
                    }
                    fx.updateFloatingOverlay()
                    Log.d(TAG, "강등 정정 → stale 상위 사이렌 즉시 정지(쿨다운 무시): $deviceId (stableLevel=$stableLevel < active, otherMax=$otherMax)")
                }
            }
            return
        }

        // ── Time-Gate + cornering extension · collision-geometry filter — first-detection alerts only ──
        // Reaching here = passed shouldAlert (new/escalated/cooldown elapsed). Only a new (first-detection) alert must also
        // pass this gate (escalations are exempt):
        //   (1) Time-Gate: the 2D Kalman derivative (kfVel) must keep 'closing' at ≥ 0.5dBm/s for a set time (default 0.5 s;
        //       1.0 s while my vehicle is cornering, as the signal fluctuates). A one-frame radio spike that briefly touches the
        //       danger zone triggers no sound/screen alert.
        //   (2) Fast approach: kfVel ≥ 2.0dBm/s for 2 frames in a row passes at once.
        //   (3) Collision-geometry filter (actual/expected closing ≥ 0.6 = head-on, passes at once; ≤ 0.3 = side course, held):
        //       inert for now — the payload carries no speed, so the combined closing speed is 0 and the plain Time-Gate applies.
        // ※ A new device isn't registered in alertState until it passes (the Pair assignment comes after this block), so
        //   every frame re-evaluates it with isFirstDetection=true and approachStreak accumulates naturally.
        // ※ TTC early alerts already fired and returned above → this gate doesn't affect them.
        //   Reverse/loading special alerts go through the same check (evalTimeGate) first on first detection; a switch while
        //   tracking fires immediately.
        // ※ Cooldown re-alarms (tracked, same level) and escalations (levelEscalated) are exempt — the gate applies to first detection only.
        // ※ The top hard gate (median of Kalman, raw 1 s average and Median output) was already passed above — this filter only
        //   adjusts the timing of a new alert; the alert level is decided solely by the RSSI gate.
        // The check lives in evalTimeGate; reuse the result if the special-alert pre-check already computed it (one update per frame).
        val gate = preGate ?: evalTimeGate(deviceId, kfVel, now, kf.updateCount)

        // The gate applies to new (first-detection) alerts only — escalations (levelEscalated) are exempt.
        //   Requiring sustained kfVel≥0.5 for a WARNING device's promotion to DANGER would hold promotion indefinitely on
        //   low-sensitivity phones (small RSSI dynamics → kfVel never gets there) even inside the danger zone (delayed danger
        //   alerts, per-device asymmetry). Spike false alarms are already blocked by the Median→EMA→Kalman→P-EMA multi-stage
        //   smoothing, the triple hard gate and the raw second line of defense, so gating escalations would be redundant.
        // A device restored on lost→rediscovery is exempt from the TimeGate once — the waiver is always consumed on reaching
        // here (so it can't linger).
        val timeGateWaived = timeGateWaiveSet.remove(deviceId)
        if (isFirstDetection && !fastContact && !timeGateWaived && (gate.side || !gate.sustained)) {   // 2-frame-confirmed WARNING/DANGER first contact skips the approach-speed gate
            pendingDisplayMap[deviceId] = now   // still listed as detected while held
            Log.d(TAG, "[v1.0.36] 경보 보류 ${fx.extractDisplayName(deviceId)}: side=${gate.side} 접근지속=${gate.streakMs}ms(<${gate.ms}) fast=${gate.fastFrames}/2 vel=%.2f".format(kfVel))
            return   // alert held; re-evaluated next frame (fires on sustained approach or head-on)
        }

        pendingDisplayMap.remove(deviceId)   // gate passed → clear the held display (alert registered below)

        alertState[deviceId] = Pair(stableLevel, now)

        // Dwell-mute gate — if this device/level is muted after a 5 s dwell, skip only sound and vibration (display,
        //   broadcast, Firebase and the rest of the alert recipe still run). Approved exception: a fast approach (kfVel≥2.0,
        //   the speed term of urgentBypass) ignores the mute. Only the speed term is used: adding the median>=effDanger term
        //   would bypass a device parked in the danger zone every frame so it would never mute (defeating the spec that
        //   danger range mutes the same way).
        // || fx.myZoneInside — while touching a zone beacon audible alerts are always suppressed (not even the speed term
        //   breaks through).
        // || isReceding || isDepartingNow — audible alerts are suppressed while moving away. This one line covers sound and
        //   vibration of both the DANGER (vibrateDanger/playDanger) and WARNING alerts below. Display and broadcast stay.
        val dwellSuppressed = (fx.isDwellMuted(deviceId, stableLevel) && kfVel < 2.0) || fx.myZoneInside ||
            isReceding || isDepartingNow
        if (!dwellSuppressed) fx.forceAlarmVolume()
        val globalMax = fx.getAudibleMaxLevel()   // excludes muted devices — a silenced device can't block new alerts
        if (stableLevel < globalMax) {
            Log.d(TAG, "우선순위 무시: $stableLevel < $globalMax (활성)")
            return
        }
        // Stop the stale higher siren on demotion too (stableLevel<active), not only on escalation — hence '!=', not '>'.
        //   On a DANGER→WARNING demotion (demoteWhileStationary, hysteresis) the playing DANGER loop (AlertSoundPlayer) must
        //   stop: playWarning in the WARNING branch below is a no-op while the danger loop isPlaying, so only
        //   fx.activeSoundLevel would drop to WARNING and the danger siren would play forever.
        //   Pairs with the fail-quiet fix in the !shouldAlert branch (frames whose cooldown hasn't elapsed).
        if (!dwellSuppressed) {
            if (stableLevel != fx.activeSoundLevel) AlertSoundPlayer.stopSound()
            fx.activeSoundLevel = stableLevel
        } else {
            // Clean up a leftover siren while suppressed — if this device still owns the sound (e.g. from an earlier kfVel
            //   bypass alert) and is suppressed again, resync to the audible max level (sound of unmuted devices is left alone =
            //   internal no-op).
            fx.resyncSoundToRemaining()
        }

        when (stableLevel) {
            // Distance-based DANGER commit branch — a slow approach (no TTC trigger, no special state) entering the danger
            //   zone still gets a danger alert and a Firebase record.
            BleConstants.LEVEL_DANGER -> {
                if (DevSettings.vibrationEnabled && !dwellSuppressed)
                    fx.vibrateDanger()
                if (DevSettings.soundEnabled && !dwellSuppressed)
                    fx.playDanger()
                if (DevSettings.autoSaveAlerts) {
                    val fbk = fbKey(deviceId, "DANGER")
                    val lastFbSave = firebaseLastSaveMap[fbk] ?: 0L
                    if (now - lastFbSave >= FIREBASE_SAVE_THROTTLE_MS) {
                        firebaseLastSaveMap[fbk] = now
                        FirebaseManager.saveAlert(deviceId, fx.myId, avgRssi, "DANGER",
                            BleConstants.categoryName(fx.myCategory), BleConstants.categoryName(rCategory))
                    }
                }
                val name = fx.extractDisplayName(deviceId)
                fx.updateFloatingOverlay()
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_DANGER)
                Log.w(TAG, "위험 발생: $deviceId ($name) avgRssi=$avgRssi state=$newState vel=%.2fdBm/s".format(kfVel))
            }
            BleConstants.LEVEL_WARNING -> {
                // IDLE-IDLE audible suppression — if my IMU is stationary and the peer broadcasts IDLE (both stopped), suppress
                //   only audible output (vibration, sound); display, overlay, list and widget stay. DANGER is never suppressed
                //   (this is the WARNING path).
                //   If either one moves, idleIdleQuiet=false on the next frame restores audio at once. The global switch is off by
                //   default (opt-in); EPJ↔EPJ and EPJ↔walker pairs are on by default.
                if (DevSettings.vibrationEnabled && !idleIdleQuiet && !dwellSuppressed)   // vibrate in the foreground (screen on) too
                    fx.vibrateWarning()
                if (DevSettings.soundEnabled && !idleIdleQuiet && !dwellSuppressed)
                    fx.playWarning()
                // Firebase alert-save throttle — once per minute per device (saves mobile data)
                if (DevSettings.autoSaveAlerts) {
                    val fbk = fbKey(deviceId, "WARNING")
                    val lastFbSave = firebaseLastSaveMap[fbk] ?: 0L
                    if (now - lastFbSave >= FIREBASE_SAVE_THROTTLE_MS) {
                        firebaseLastSaveMap[fbk] = now
                        FirebaseManager.saveAlert(deviceId, fx.myId, avgRssi, "WARNING",
                            BleConstants.categoryName(fx.myCategory), BleConstants.categoryName(rCategory))
                    }
                }
                val name = fx.extractDisplayName(deviceId)
                fx.updateFloatingOverlay()
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_WARNING)
                Log.d(TAG, "경고 발생: $deviceId ($name) avgRssi=$avgRssi state=$newState vel=%.2fdBm/s".format(kfVel))
            }
        }
    }

    fun judgeUwbOnly(deviceId: String, uwbD: Float, now: Long) {
        // Walker gate, second safety net — blocking session setup (onUwbAddressReceived) is the first line, but leftover
        //   samples from an already-open session arriving here must not revive a filtered device.
        if (fx.myMode == "WALKER" && deviceId.startsWith(BleConstants.WALKER_PREFIX)
            && !(deviceId.contains("BEA_") && !BeaconRegistry.isVisitorBeacon(deviceId)) && !DevSettings.walkerDetectsWalker) return
        val rCategory = deviceCategoryMap[deviceId]
        val rState    = deviceStateMap[deviceId]
        val forkliftPair = fx.myCategory == BleConstants.CAT_FORKLIFT ||
            rCategory == BleConstants.CAT_FORKLIFT
        val warnM = if (forkliftPair) DevSettings.uwbForkliftWarnMeters   else DevSettings.uwbPairWarnMeters
        val dangM = if (forkliftPair) DevSettings.uwbForkliftDangerMeters else DevSettings.uwbPairDangerMeters

        val prev      = alertState[deviceId]
        val prevLevel = prev?.first ?: BleConstants.LEVEL_SAFE

        // Level calculation — while a level is held, hysteresis (+0.5m) suppresses flicker at the boundary
        val rawLevel = when {
            uwbD <= dangM ||
                (prevLevel >= BleConstants.LEVEL_DANGER && uwbD <= dangM + UWB_RELEASE_HYST_M) ->
                BleConstants.LEVEL_DANGER
            uwbD <= warnM ||
                (prevLevel >= BleConstants.LEVEL_WARNING && uwbD <= warnM + UWB_RELEASE_HYST_M) ->
                BleConstants.LEVEL_WARNING
            else -> BleConstants.LEVEL_SAFE
        }

        // Escalate/hold = applied on one sample. Demote = confirmed by consecutive samples
        // (guards against single spikes) or at once on separating kinematics.
        var stableLevel: Int
        if (rawLevel >= prevLevel) {
            uwbSafeStreakMap[deviceId] = 0
            stableLevel = rawLevel
        } else {
            val streak = (uwbSafeStreakMap[deviceId] ?: 0) + 1
            uwbSafeStreakMap[deviceId] = streak
            val kin = fx.uwbRanger?.uwbKinematics?.get(deviceId)
            val separating = kin != null && now - kin.atMs <= 1500L &&
                kin.separatingStreak >= 3 && kin.closingMps < 0f
            if (streak >= UWB_DEMOTE_STREAK || separating) {
                uwbSafeStreakMap[deviceId] = 0
                stableLevel = rawLevel
            } else {
                stableLevel = prevLevel   // hold demotion — awaiting confirmation (~0.4s at FREQUENT)
            }
        }

        // Peer IN_ZONE harmless clamp — mirrors canonical (suppression only — never overrides upward).
        //   ★ Don't reset uwbSafeStreakMap here — it carries the 3-sample demotion confirmation above.
        if (peerInZoneMap[deviceId] == true && stableLevel > BleConstants.LEVEL_SAFE)
            stableLevel = BleConstants.LEVEL_SAFE

        // Special alert — a reversing/loading device measured within the warning radius is DANGER at once (mirrors the canonical special alert)
        if (rCategory != null && rState != null &&
            (rState == BleConstants.PSTATE_REVERSE || rState == BleConstants.PSTATE_LOADING) &&
            uwbD <= warnM &&
            peerInZoneMap[deviceId] != true) {   // peer declared IN_ZONE = harmless — blocks the special alert entirely
            suddenLabelMap[deviceId] = fx.makeStateLabel(fx.extractDisplayName(deviceId), rCategory, rState)
            alertState[deviceId] = Pair(BleConstants.LEVEL_DANGER, now)
            pendingDisplayMap.remove(deviceId)
            fx.bleScanner?.setEcoMode(false)
            fx.updateDwellMute(deviceId, BleConstants.LEVEL_DANGER, now)   // special alerts are dwell-tracked too
            if (fx.isDeviceMuted(deviceId) ||
                fx.isDwellMuted(deviceId, BleConstants.LEVEL_DANGER) ||
                fx.myZoneInside) {   // inside a zone = audible suppressed (state and display already updated above)
                fx.updateFloatingOverlay(); return
            }
            fx.forceAlarmVolume()
            if (DevSettings.vibrationEnabled) fx.vibrateDanger()
            if (DevSettings.soundEnabled)     fx.playDanger()
            fx.activeSoundLevel = BleConstants.LEVEL_DANGER
            fx.updateFloatingOverlay()
            fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_DANGER)
            fx.sendStatusBroadcast("${suddenLabelMap[deviceId]} UWB ${"%.1f".format(uwbD)}m")
            return
        }
        suddenLabelMap.remove(deviceId)

        // SAFE — mirrors the canonical SAFE cleanup (one-shot: only devices in alertState). The RSSI head keeps warming
        //   the filters, so clearing them reconverges within a few frames — the fallback stays seamless.
        if (stableLevel == BleConstants.LEVEL_SAFE) {
            uwbSafeStreakMap.remove(deviceId)
            if (alertState.containsKey(deviceId)) {
                alertState.remove(deviceId)
                fx.rssiPreFilter.clear(deviceId)
                fx.medianFilter.clear(deviceId)
                fx.pEmaFilter.clear(deviceId)
                rushFrameMap.remove(deviceId)
                dangerContactStreakMap.remove(deviceId)
                warningContactStreakMap.remove(deviceId)
                warningMissRefMap.remove(deviceId)
                kalmanFilters.remove(deviceId)
                lastKfVelMap.remove(deviceId)   // UWB-confirmed SAFE — no reseed needed, drop the snapshot
                shadowFusionMap.remove(deviceId)
                trackingStateMap.remove(deviceId)
                crossingStartMap.remove(deviceId)
                departingStartMap.remove(deviceId)
                approachStreakStartMap.remove(deviceId)
                fastApproachStreakMap.remove(deviceId)
                forwardBiasLatchMap.remove(deviceId)
                approachLastSeenMap.remove(deviceId)
                fx.clearDwellMute(deviceId)   // UWB-confirmed SAFE = left the alert zone — reset dwell mute
                peerInZoneMap.remove(deviceId)   // SAFE cleanup — the next advert sample re-declares (no stale cache)
                wasStationaryMap.remove(deviceId)
                recedingStartMap.remove(deviceId)
                recedeRefMap.remove(deviceId)
                recedePeakMap.remove(deviceId)
                clearTrend(deviceId)   // UWB-confirmed SAFE — clear all trend-release state and re-alert latches
                deviceRssiMap.remove(deviceId)
                mutedDevices.remove(deviceId)
                suddenLabelMap.remove(deviceId)
                deviceCategoryMap.remove(deviceId)
                deviceStateMap.remove(deviceId)
                deviceTurnMap.remove(deviceId); reverseRssiHist.remove(deviceId); reversePrepUntil.remove(deviceId)
                clearFbThrottle(deviceId)
                pendingDisplayMap.remove(deviceId)
                // ★ Keep uwbSampleAtMsMap — Case A freshness evidence (clearing it falls back to
                // RSSI until the next sample). peerUwbSeenMap is kept for diagnostics
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                if (alertState.isEmpty()) {
                    AlertSoundPlayer.stopSound()
                    fx.activeSoundLevel = BleConstants.LEVEL_SAFE
                    fx.stopVibration()
                    fx.collapseOverlay()
                } else {
                    fx.resyncSoundToRemaining()
                    fx.updateFloatingOverlay()
                }
            }
            return
        }

        // Non-SAFE — ensure combat mode (canonical mirror)
        fx.bleScanner?.setEcoMode(false)

        // Stationary↔stationary reduction (idle-idle quiet) — UWB peers always have a payload cache, so recompute from it
        // Role pairs (same semantics as the RSSI gate) — EPJ↔EPJ and EPJ↔walker pairs ON by default; pairs with a forklift excluded.
        val epjQuietPair = !forkliftPair &&
            (fx.myCategory == BleConstants.CAT_EPJ || rCategory == BleConstants.CAT_EPJ)
        val quietArmed = DevSettings.idleIdleSuppressEnabled ||
            (DevSettings.idleIdleSuppressEpjPairsEnabled && epjQuietPair)
        val idleIdleQuiet = quietArmed && ImuFusion.isStationary &&
            rState == BleConstants.PSTATE_IDLE

        // Dwell tracking — UWB mirror of the same rule as canonical (processAlert); a WARNING kept quiet by idle-idle is not
        //   audible time. Placed after the SAFE cleanup, which never reaches it, so idle-idle is only computed when needed.
        if (stableLevel >= BleConstants.LEVEL_WARNING && alertState.containsKey(deviceId))
            fx.updateDwellMute(deviceId, stableLevel, now, quiet = stableLevel == BleConstants.LEVEL_WARNING && idleIdleQuiet)

        // Muted — keep tracking the level only (alert time preserved, mirrors the canonical mute)
        if (fx.isDeviceMuted(deviceId)) {
            alertState[deviceId] = Pair(stableLevel, prev?.second ?: now)
            pendingDisplayMap.remove(deviceId)
            return
        }

        val lastAlertTime    = prev?.second ?: 0L
        val baseCooldown     = if (stableLevel == BleConstants.LEVEL_DANGER) DANGER_COOLDOWN_MS else WARNING_COOLDOWN_MS
        val isFirstDetection = prev == null
        val levelEscalated   = stableLevel > prevLevel
        val cooldownPassed   = now - lastAlertTime >= baseCooldown

        if (!(isFirstDetection || levelEscalated || cooldownPassed)) {
            // Non-alerting sample — update the level only (alert time preserved) + fail-quiet demotion fix (canonical mirror)
            alertState[deviceId] = Pair(stableLevel, lastAlertTime)
            if (fx.activeSoundLevel >= BleConstants.LEVEL_DANGER && stableLevel < fx.activeSoundLevel) {
                val otherMax = alertState.entries
                    .filter { it.key != deviceId && !fx.isDwellMuted(it.key, it.value.first) &&
                        !fx.isDeviceMuted(it.key) }   // muted devices don't own the sound
                    .maxOfOrNull { it.value.first } ?: BleConstants.LEVEL_SAFE
                if (otherMax < fx.activeSoundLevel) {
                    AlertSoundPlayer.stopSound()
                    fx.activeSoundLevel = stableLevel
                    if (stableLevel == BleConstants.LEVEL_WARNING && !idleIdleQuiet &&
                        !fx.isDwellMuted(deviceId, BleConstants.LEVEL_WARNING) &&   // skip re-alert when WARNING is dwell-muted (the demotion stop still applies)
                        !fx.myZoneInside) {   // inside a zone = audible suppressed — no corrective re-alert (still stops)
                        if (DevSettings.vibrationEnabled) fx.vibrateWarning()
                        if (DevSettings.soundEnabled)     fx.playWarning()
                    }
                    fx.updateFloatingOverlay()
                }
            }
            return
        }

        // Alert — mirrors the canonical alert recipe
        pendingDisplayMap.remove(deviceId)
        alertState[deviceId] = Pair(stableLevel, now)
        // Dwell-mute gate — canonical mirror. The UWB path has no kfVel (RSSI Kalman velocity), so no bypass look-alike
        //   is invented (don't reinterpret the spec) — if muted, skip only sound and vibration.
        // || fx.myZoneInside — while touching a zone beacon audible alerts are always suppressed.
        val dwellSuppressed = fx.isDwellMuted(deviceId, stableLevel) || fx.myZoneInside
        if (!dwellSuppressed) fx.forceAlarmVolume()
        val globalMax = fx.getAudibleMaxLevel()   // excludes muted devices
        if (stableLevel < globalMax) return   // a higher alert is playing — never downgrade the sound (priority)
        if (!dwellSuppressed) {
            if (stableLevel != fx.activeSoundLevel) AlertSoundPlayer.stopSound()
            fx.activeSoundLevel = stableLevel
        } else {
            fx.resyncSoundToRemaining()   // clean up a leftover siren — unmuted sound untouched (internal no-op)
        }
        when (stableLevel) {
            BleConstants.LEVEL_DANGER -> {
                if (DevSettings.vibrationEnabled && !dwellSuppressed) fx.vibrateDanger()
                if (DevSettings.soundEnabled && !dwellSuppressed)     fx.playDanger()
                if (DevSettings.autoSaveAlerts) {
                    val fbk = fbKey(deviceId, "DANGER")
                    val lastFbSave = firebaseLastSaveMap[fbk] ?: 0L
                    if (now - lastFbSave >= FIREBASE_SAVE_THROTTLE_MS) {
                        firebaseLastSaveMap[fbk] = now
                        FirebaseManager.saveAlert(deviceId, fx.myId, deviceRssiMap[deviceId] ?: 0, "DANGER",
                            BleConstants.categoryName(fx.myCategory), BleConstants.categoryName(rCategory))
                    }
                }
                fx.updateFloatingOverlay()
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_DANGER)
                Log.w(TAG, "UWB 위험 발생: $deviceId ${"%.1f".format(uwbD)}m (forkliftPair=$forkliftPair)")
            }
            BleConstants.LEVEL_WARNING -> {
                if (DevSettings.vibrationEnabled && !idleIdleQuiet && !dwellSuppressed) fx.vibrateWarning()
                if (DevSettings.soundEnabled && !idleIdleQuiet && !dwellSuppressed)     fx.playWarning()
                if (DevSettings.autoSaveAlerts) {
                    val fbk = fbKey(deviceId, "WARNING")
                    val lastFbSave = firebaseLastSaveMap[fbk] ?: 0L
                    if (now - lastFbSave >= FIREBASE_SAVE_THROTTLE_MS) {
                        firebaseLastSaveMap[fbk] = now
                        FirebaseManager.saveAlert(deviceId, fx.myId, deviceRssiMap[deviceId] ?: 0, "WARNING",
                            BleConstants.categoryName(fx.myCategory), BleConstants.categoryName(rCategory))
                    }
                }
                fx.updateFloatingOverlay()
                fx.sendAlertBroadcast(deviceId, BleConstants.LEVEL_WARNING)
                Log.d(TAG, "UWB 경고 발생: $deviceId ${"%.1f".format(uwbD)}m (forkliftPair=$forkliftPair)")
            }
        }
    }
}
