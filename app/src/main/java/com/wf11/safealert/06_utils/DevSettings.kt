package com.wf11.safealert.utils

import android.content.Context
import android.content.SharedPreferences
import com.wf11.safealert.service.MotionAnalyzer

object DevSettings {

    private const val PREF_NAME = "dev_settings"

    // Keys
    private const val KEY_RSSI_WARNING          = "rssi_warning"
    private const val KEY_RSSI_DANGER           = "rssi_danger"
    private const val KEY_BEACON_GAIN_PERCENT   = "beacon_gain_percent"
    private const val KEY_SCAN_PERIOD_MS        = "scan_period_ms"
    private const val KEY_ADVERTISE_INTERVAL    = "advertise_interval"
    private const val KEY_VIBRATION_ENABLED     = "vibration_enabled"
    private const val KEY_VIBRATION_WARNING_MS  = "vibration_warning_ms"
    private const val KEY_VIBRATION_DANGER_COUNT= "vibration_danger_count"
    private const val KEY_SOUND_ENABLED         = "sound_enabled"
    private const val KEY_FIREBASE_ROOT         = "firebase_root"
    private const val KEY_AUTO_SAVE_ALERTS      = "auto_save_alerts"

    // ── Kalman filter strength presets ──────────────────────────────────────
    // Lower number = faster response to RSSI changes (more noise); higher = smoother but slower
    //   (actual values: KalmanFilter.kt processNoise/measureNoise getters)
    //   FAST  (0): q=0.50 R=2.0  — fast response (good for tracking fast-approaching equipment)
    //   NORMAL(1): q=0.15 R=5.0  — balanced (default, typical warehouse)
    //   SMOOTH(2): q=0.05 R=10.0 — heavy smoothing (noisy environments)
    const val KALMAN_PRESET_FAST   = 0
    const val KALMAN_PRESET_NORMAL = 1
    const val KALMAN_PRESET_SMOOTH = 2
    private const val KEY_KALMAN_PRESET = "kalman_preset"
    var kalmanPreset: Int
        get() = prefs.getInt(KEY_KALMAN_PRESET, KALMAN_PRESET_NORMAL)
        set(v) = prefs.edit().putInt(KEY_KALMAN_PRESET, v.coerceIn(0, 2)).apply()

    // Time-Gate (sensitivity delay) — minimum continuous approach time (ms) before a new/escalated alert.
    //   AlertStateMachine.APPROACH_TIMEGATE_MS reads it live every frame, so changes apply without an app restart.
    //   Default 500L keeps the original hard-coded behavior. Clamped to 0 (pass immediately) ~ 3000ms.
    private const val KEY_TIMEGATE_MS = "timegate_ms"
    const val DEFAULT_TIMEGATE_MS = 500L
    var timeGateMs: Long
        get() = prefs.getLong(KEY_TIMEGATE_MS, DEFAULT_TIMEGATE_MS).coerceIn(0L, 3000L)
        set(v) = prefs.edit().putLong(KEY_TIMEGATE_MS, v.coerceIn(0L, 3000L)).apply()


    // Alarm volume gain (0-100%)
    private const val KEY_ALARM_VOLUME = "alarm_volume"

    // No BLE distance calibration keys (KEY_CALIB_RSSI/KEY_PATH_LOSS_EXP/KEY_CALIB_VER):
    //   alerts compare filtered RSSI with dBm thresholds (UWB-measured pairs use meters) — no calibration value,
    //   path-loss exponent or calibration wizard.

    // TX/RX mode settings
    private const val KEY_DEVICE_TX  = "device_tx"   // Equipment operator TX on/off
    private const val KEY_DEVICE_RX  = "device_rx"   // Equipment operator RX on/off
    private const val KEY_WALKER_TX  = "walker_tx"   // Walker TX on/off
    private const val KEY_WALKER_RX  = "walker_rx"   // Walker RX on/off
    private const val KEY_DEBUG_MODE            = "debug_mode"
    private const val KEY_SIMULATED_RSSI        = "simulated_rssi"
    private const val KEY_LOG_VERBOSE           = "log_verbose"

    private lateinit var prefs: SharedPreferences
    private var appCtx: Context? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // One-time migration of the EMA fall alpha default 0.05→0.12 — the spinner's initial programmatic
        //   selection is also saved via putFloat, so changing DEFAULT alone would not reach existing installs. Once per marker,
        //   the stored value is overwritten with the new default (a value the user picked in
        //   the spinner is overwritten this once too; later changes are respected).
        if (!prefs.getBoolean(KEY_EMA_FALL_MIGR_V1156, false)) {
            prefs.edit().putFloat(KEY_EMA_ALPHA_FALL, DEFAULT_EMA_ALPHA_FALL.toFloat())
                .putBoolean(KEY_EMA_FALL_MIGR_V1156, true).apply()
        }
        // One-time migration of the danger threshold default -55→-65 — on existing installs the stored value hides the default,
        //   so once per marker it is overwritten with danger -65 / warning -78 (values changed later in developer settings are respected).
        if (!prefs.getBoolean(KEY_RSSI_THRESH_MIGR_V1195, false)) {
            prefs.edit().putInt(KEY_RSSI_DANGER, DEFAULT_RSSI_DANGER_ABS)
                .putInt(KEY_RSSI_WARNING, DEFAULT_RSSI_WARNING_ABS)
                .putBoolean(KEY_RSSI_THRESH_MIGR_V1195, true).apply()
        }
    }

    // Live settings propagation — register/unregister a dev_settings change listener (applies without an app restart).
    //   Encapsulates only the registration path without exposing prefs. BleService holds the listener by a strong reference
    //   (SharedPreferences holds listeners as WeakReferences, so a weakly referenced one gets cut by GC).
    fun registerOnChange(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(l)
    fun unregisterOnChange(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    // Danger/warning RSSI thresholds — set directly as signal strength (dBm) with the dBm sliders in developer
    //   settings (BLE settings shows them read-only).
    //   Stored as negative dBm. The sliders show the absolute value (positive 30~100) and negate it on save.
    //   Danger should be closer than warning = less negative = smaller absolute value (e.g. danger -65 > warning -78);
    //   the developer settings sliders do not enforce it.
    //   No distance calculation or calibration (calibRssiAt1m/pathLossExp/warningDistM/dangerDistM/calibration
    //     wizard): distance estimation uses the Kalman filter (RSSI) only; only the dBm thresholds are tuned.
    //   prefs keys reuse KEY_RSSI_WARNING / KEY_RSSI_DANGER (top of file).
    //   Danger -65: in the field, a warning at -75 sounded at 15~25m, so -65 is about 5~10m.
    //   Warning -78: starts the warning a little farther out (about 20~35m).
    const val DEFAULT_RSSI_WARNING_ABS = -78   // WARNING: RSSI >= -78  (-66~-78 band)
    const val DEFAULT_RSSI_DANGER_ABS  = -65   // DANGER : RSSI >= -65  (0~-65 band)
    private const val KEY_RSSI_THRESH_MIGR_V1195 = "rssi_thresh_migrated_v1195"
    const val RSSI_THRESH_MIN = -100           // Slider min (farthest, absolute 100)
    const val RSSI_THRESH_MAX = -30            // Slider max (closest, absolute 30)

    var rssiWarning: Int
        get() = prefs.getInt(KEY_RSSI_WARNING, DEFAULT_RSSI_WARNING_ABS).coerceIn(RSSI_THRESH_MIN, RSSI_THRESH_MAX)
        set(v) = prefs.edit().putInt(KEY_RSSI_WARNING, v.coerceIn(RSSI_THRESH_MIN, RSSI_THRESH_MAX)).apply()

    var rssiDanger: Int
        get() = prefs.getInt(KEY_RSSI_DANGER, DEFAULT_RSSI_DANGER_ABS).coerceIn(RSSI_THRESH_MIN, RSSI_THRESH_MAX)
        set(v) = prefs.edit().putInt(KEY_RSSI_DANGER, v.coerceIn(RSSI_THRESH_MIN, RSSI_THRESH_MAX)).apply()

    // Beacon reception strength (%) — converted to a dBm offset added to every registered beacon.
    //   100% = 0dBm (no change), 2dBm per 10%. 200% = +20dBm (farther), 0% = -20dBm (almost blocked).
    //   Summed with the per-beacon rssiOffset into AlertStateMachine's totalOffset (beacons only, including offset-0 beacons).
    const val DEFAULT_BEACON_GAIN_PERCENT = 100
    const val BEACON_GAIN_MIN = 0
    const val BEACON_GAIN_MAX = 300
    var beaconGainPercent: Int
        get() = prefs.getInt(KEY_BEACON_GAIN_PERCENT, DEFAULT_BEACON_GAIN_PERCENT).coerceIn(BEACON_GAIN_MIN, BEACON_GAIN_MAX)
        set(v) = prefs.edit().putInt(KEY_BEACON_GAIN_PERCENT, v.coerceIn(BEACON_GAIN_MIN, BEACON_GAIN_MAX)).apply()
    /** Convert beacon reception strength (%) to the common dBm offset: 100%→0, 200%→+20, 0%→-20 (2dBm per 10%). */
    val beaconGainDbm: Int
        get() = (beaconGainPercent - 100) / 5

    // Default scan period 1000ms. BleScanner.mapScanMode maps ≤1000ms to
    //   LOW_LATENCY (near-continuous scanning) → no detection blind window (prevents delayed/missed alarms).
    var scanPeriodMs: Long
        get() = prefs.getLong(KEY_SCAN_PERIOD_MS, 1000L)
        set(v) = prefs.edit().putLong(KEY_SCAN_PERIOD_MS, v).apply()

    var advertiseInterval: Int
        get() = prefs.getInt(KEY_ADVERTISE_INTERVAL, 200)
        set(v) = prefs.edit().putInt(KEY_ADVERTISE_INTERVAL, v).apply()

    // Alert behavior
    var vibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, v).apply()

    var vibrationWarningMs: Long
        get() = prefs.getLong(KEY_VIBRATION_WARNING_MS, 500L)
        set(v) = prefs.edit().putLong(KEY_VIBRATION_WARNING_MS, v).apply()

    var vibrationDangerCount: Int
        get() = prefs.getInt(KEY_VIBRATION_DANGER_COUNT, 3)
        set(v) = prefs.edit().putInt(KEY_VIBRATION_DANGER_COUNT, v).apply()

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_SOUND_ENABLED, v).apply()

    // Firebase settings
    /** Firebase child() throws DatabaseException on . # $ [ ] → guards against a service start crash loop. */
    private fun normalizeFirebaseRoot(raw: String?): String =
        if (raw == null || raw.isBlank() || raw.any { it in ".#\$[]" }) "wf11" else raw.trim()

    var firebaseRoot: String
        get() = normalizeFirebaseRoot(prefs.getString(KEY_FIREBASE_ROOT, "wf11"))
        set(v) = prefs.edit().putString(KEY_FIREBASE_ROOT, normalizeFirebaseRoot(v)).apply()

    var autoSaveAlerts: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SAVE_ALERTS, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_SAVE_ALERTS, v).apply()

    // Debug settings
    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, false)
        set(v) = prefs.edit().putBoolean(KEY_DEBUG_MODE, v).apply()

    var simulatedRssi: Int
        get() = prefs.getInt(KEY_SIMULATED_RSSI, -75)
        set(v) = prefs.edit().putInt(KEY_SIMULATED_RSSI, v).apply()

    var logVerbose: Boolean
        get() = prefs.getBoolean(KEY_LOG_VERBOSE, false)
        set(v) = prefs.edit().putBoolean(KEY_LOG_VERBOSE, v).apply()

    fun resetToDefault() {
        prefs.edit().clear().apply()
    }

    // No calibRssiAt1m / pathLossExp / resetCalibration() / DEFAULT_CALIB:
    //   alert decisions use no RSSI→distance formula; the only path-loss model is UwbCalibrator's fixed one, used
    //   for the distance display.

    // Alarm volume gain (50~100%) — set in developer settings ("소리·진동 세부" section); BLE detection settings shows it
    //   locked in its "경보 기본" (alarm basics) section. Floor fixed at 50% (can't be set below 50%). Old stored
    //   values (<50) are read as 50 by the getter.
    var alarmVolume: Int
        get() = prefs.getInt(KEY_ALARM_VOLUME, 100).coerceIn(50, 100)
        set(v) = prefs.edit().putInt(KEY_ALARM_VOLUME, v.coerceIn(50, 100)).apply()

    // TX/RX settings
    var deviceTx: Boolean
        get() = prefs.getBoolean(KEY_DEVICE_TX, true)
        set(v) = prefs.edit().putBoolean(KEY_DEVICE_TX, v).apply()

    var deviceRx: Boolean
        get() = prefs.getBoolean(KEY_DEVICE_RX, true)
        set(v) = prefs.edit().putBoolean(KEY_DEVICE_RX, v).apply()

    var walkerTx: Boolean
        get() = prefs.getBoolean(KEY_WALKER_TX, true)
        set(v) = prefs.edit().putBoolean(KEY_WALKER_TX, v).apply()

    var walkerRx: Boolean
        get() = prefs.getBoolean(KEY_WALKER_RX, true)
        set(v) = prefs.edit().putBoolean(KEY_WALKER_RX, v).apply()


    // Walker-to-walker alerts (default OFF — walkers detect equipment only)
    private const val KEY_WALKER_DETECTS_WALKER = "walker_detects_walker"
    var walkerDetectsWalker: Boolean
        get() = prefs.getBoolean(KEY_WALKER_DETECTS_WALKER, false)
        set(v) = prefs.edit().putBoolean(KEY_WALKER_DETECTS_WALKER, v).apply()

    // Lone-worker no-motion/fall check — enable / no-motion minutes / response wait minutes. Thresholds are code constants.
    const val KEY_LW_ENABLED = "lw_enabled"
    const val KEY_LW_STILL_MIN = "lw_still_min"
    const val KEY_LW_RESPONSE_MIN = "lw_response_min"
    var lwEnabled: Boolean
        get() = prefs.getBoolean(KEY_LW_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_LW_ENABLED, v).apply()
    var lwStillMin: Int
        get() = prefs.getInt(KEY_LW_STILL_MIN, 3).coerceIn(1, 30)
        set(v) = prefs.edit().putInt(KEY_LW_STILL_MIN, v.coerceIn(1, 30)).apply()
    var lwResponseMin: Int
        get() = prefs.getInt(KEY_LW_RESPONSE_MIN, 2).coerceIn(1, 10)
        set(v) = prefs.edit().putInt(KEY_LW_RESPONSE_MIN, v.coerceIn(1, 10)).apply()
    // Fall criteria inside the safe zone — all three must be exceeded to count as a fall. Outside, the code defaults apply as is
    const val KEY_LW_ZONE_FALL_CM = "lw_zone_fall_cm"
    const val KEY_LW_ZONE_FALL_G = "lw_zone_fall_g"
    const val KEY_LW_ZONE_FALL_DEG = "lw_zone_fall_deg"
    var lwZoneFallCm: Int
        get() = prefs.getInt(KEY_LW_ZONE_FALL_CM, 30).coerceIn(2, 100)
        set(v) = prefs.edit().putInt(KEY_LW_ZONE_FALL_CM, v.coerceIn(2, 100)).apply()
    var lwZoneFallG: Double
        get() = (Math.round(prefs.getFloat(KEY_LW_ZONE_FALL_G, MotionAnalyzer.IMPACT_G.toFloat()) * 10) / 10.0).coerceIn(MotionAnalyzer.IMPACT_G, 8.0)
        set(v) = prefs.edit().putFloat(KEY_LW_ZONE_FALL_G, v.coerceIn(MotionAnalyzer.IMPACT_G, 8.0).toFloat()).apply()
    var lwZoneFallDeg: Int
        get() = prefs.getInt(KEY_LW_ZONE_FALL_DEG, 60).coerceIn(MotionAnalyzer.POSTURE_DEG.toInt(), 90)
        set(v) = prefs.edit().putInt(KEY_LW_ZONE_FALL_DEG, v.coerceIn(MotionAnalyzer.POSTURE_DEG.toInt(), 90)).apply()

    // ── Judgment parameters — hard-coded decision constants exposed as settings ─────────────────
    //   Defaults started as the original hard-coded values; some were retuned later (e.g. EMA fall alpha, wake RSSI).
    //   The service side (mostly AlertStateMachine, some BleService) reads them live through getters named after the
    //   old constants (e.g. TTC_THRESHOLD_SEC, WAKE_RSSI_DBM), so they apply without an app restart (as timeGateMs does).
    //   Decimal (Double) values are stored as Float (SharedPreferences limitation) and exposed as Double.
    //   resetToDefault()'s prefs.clear() also restores the items below to their defaults.

    // [Judgment gate] TTC early-alert threshold (s) — urgent alert when a collision is imminent within this value
    private const val KEY_TTC_THRESHOLD_SEC = "ttc_threshold_sec"
    const val DEFAULT_TTC_THRESHOLD_SEC = 3.0
    var ttcThresholdSec: Double
        get() = prefs.getFloat(KEY_TTC_THRESHOLD_SEC, DEFAULT_TTC_THRESHOLD_SEC.toFloat())
                    .toDouble().coerceIn(0.5, 10.0)
        set(v) = prefs.edit().putFloat(KEY_TTC_THRESHOLD_SEC, v.coerceIn(0.5, 10.0).toFloat()).apply()

    // Minimum approach speed (dBm/s) for TTC — slower than this, no TTC is computed
    private const val KEY_MIN_APPROACH_VEL = "min_approach_vel_dbm"
    const val DEFAULT_MIN_APPROACH_VEL = 0.5
    var minApproachVelDbm: Double
        get() = prefs.getFloat(KEY_MIN_APPROACH_VEL, DEFAULT_MIN_APPROACH_VEL.toFloat())
                    .toDouble().coerceIn(0.1, 3.0)
        set(v) = prefs.edit().putFloat(KEY_MIN_APPROACH_VEL, v.coerceIn(0.1, 3.0).toFloat()).apply()

    // Minimum approach speed (dBm/s) for Time-Gate to judge 'getting closer'
    private const val KEY_TIMEGATE_VEL = "timegate_vel_dbm"
    const val DEFAULT_TIMEGATE_VEL = 0.5
    var timeGateVelDbm: Double
        get() = prefs.getFloat(KEY_TIMEGATE_VEL, DEFAULT_TIMEGATE_VEL.toFloat())
                    .toDouble().coerceIn(0.1, 3.0)
        set(v) = prefs.edit().putFloat(KEY_TIMEGATE_VEL, v.coerceIn(0.1, 3.0).toFloat()).apply()

    // Time-Gate extension while cornering (ms) — guards against signal swings in sharp turns
    private const val KEY_TIMEGATE_CORNERING_MS = "timegate_cornering_ms"
    const val DEFAULT_TIMEGATE_CORNERING_MS = 1000L
    var corneringTimeGateMs: Long
        get() = prefs.getLong(KEY_TIMEGATE_CORNERING_MS, DEFAULT_TIMEGATE_CORNERING_MS).coerceIn(0L, 5000L)
        set(v) = prefs.edit().putLong(KEY_TIMEGATE_CORNERING_MS, v.coerceIn(0L, 5000L)).apply()

    // Fast head-on approach Time-Gate bypass threshold (dBm/s) — if kfVel (Kalman approach speed) is at or above this,
    //   treat it as a head-on rush: skip the Time-Gate and alert immediately (2 consecutive frames confirm, guarding against single spikes).
    //   The 1-byte payload has no combined-speed (km/h) bits, so headOnCourse is always false; this fills that gap.
    //   Default 2.0 = the same 'clearly fast approach' bar as COLLISION_ABS_SAFE_VEL_DBM. Lower values alert earlier.
    private const val KEY_FAST_APPROACH_BYPASS_VEL = "fast_approach_bypass_vel_dbm"
    const val DEFAULT_FAST_APPROACH_BYPASS_VEL = 2.0
    var fastApproachBypassVelDbm: Double
        get() = prefs.getFloat(KEY_FAST_APPROACH_BYPASS_VEL, DEFAULT_FAST_APPROACH_BYPASS_VEL.toFloat())
                    .toDouble().coerceIn(0.5, 5.0)
        set(v) = prefs.edit().putFloat(KEY_FAST_APPROACH_BYPASS_VEL, v.coerceIn(0.5, 5.0).toFloat()).apply()

    // [Cooldown/release] Warning/danger re-alarm cooldown (ms)
    private const val KEY_WARNING_COOLDOWN_MS = "warning_cooldown_ms"
    const val DEFAULT_WARNING_COOLDOWN_MS = 3000L
    var warningCooldownMs: Long
        get() = prefs.getLong(KEY_WARNING_COOLDOWN_MS, DEFAULT_WARNING_COOLDOWN_MS).coerceIn(500L, 10_000L)
        set(v) = prefs.edit().putLong(KEY_WARNING_COOLDOWN_MS, v.coerceIn(500L, 10_000L)).apply()

    private const val KEY_DANGER_COOLDOWN_MS = "danger_cooldown_ms"
    const val DEFAULT_DANGER_COOLDOWN_MS = 2000L
    var dangerCooldownMs: Long
        get() = prefs.getLong(KEY_DANGER_COOLDOWN_MS, DEFAULT_DANGER_COOLDOWN_MS).coerceIn(500L, 10_000L)
        set(v) = prefs.edit().putLong(KEY_DANGER_COOLDOWN_MS, v.coerceIn(500L, 10_000L)).apply()

    // Alert downgrade hysteresis (dB) — within this margin below the threshold the current level is kept (prevents chattering)
    private const val KEY_HYSTERESIS_DBM = "hysteresis_dbm"
    const val DEFAULT_HYSTERESIS_DBM = 5
    var hysteresisDbm: Int
        get() = prefs.getInt(KEY_HYSTERESIS_DBM, DEFAULT_HYSTERESIS_DBM).coerceIn(0, 15)
        set(v) = prefs.edit().putInt(KEY_HYSTERESIS_DBM, v.coerceIn(0, 15)).apply()

    // Extra re-alert margin (dB) while DEPARTING
    private const val KEY_DEPARTING_HYSTERESIS_DBM = "departing_hysteresis_dbm"
    const val DEFAULT_DEPARTING_HYSTERESIS_DBM = 8
    var departingHysteresisDbm: Int
        get() = prefs.getInt(KEY_DEPARTING_HYSTERESIS_DBM, DEFAULT_DEPARTING_HYSTERESIS_DBM).coerceIn(0, 20)
        set(v) = prefs.edit().putInt(KEY_DEPARTING_HYSTERESIS_DBM, v.coerceIn(0, 20)).apply()

    // Fade-out: release the alert when the drop from the peak (dB) lasts (ms)
    // Shortens lingering after passing each other (~1~2s): 1500ms from confirmed departure to full release.
    //   The danger-zone guard (distanceLevel<DANGER in isReceding) still applies, so no 'silent while close' regression.
    private const val KEY_RECEDING_CLEAR_MS = "receding_clear_ms"
    const val DEFAULT_RECEDING_CLEAR_MS = 1500L
    var recedingClearMs: Long
        get() = prefs.getLong(KEY_RECEDING_CLEAR_MS, DEFAULT_RECEDING_CLEAR_MS).coerceIn(500L, 10_000L)
        set(v) = prefs.edit().putLong(KEY_RECEDING_CLEAR_MS, v.coerceIn(500L, 10_000L)).apply()

    // Departure drop 4dBm — recognizes departure sooner right after leaving the danger zone (sound stops).
    private const val KEY_RECEDING_DBM_DROP = "receding_dbm_drop"
    const val DEFAULT_RECEDING_DBM_DROP = 4
    var recedingDbmDrop: Int
        get() = prefs.getInt(KEY_RECEDING_DBM_DROP, DEFAULT_RECEDING_DBM_DROP).coerceIn(1, 20)
        set(v) = prefs.edit().putInt(KEY_RECEDING_DBM_DROP, v.coerceIn(1, 20)).apply()

    // [Collision geometry] Conversion factor from combined speed (km/h) to expected approach speed (dBm/s)
    private const val KEY_CLOSING_KMH_TO_DBMS = "closing_kmh_to_dbms"
    const val DEFAULT_CLOSING_KMH_TO_DBMS = 0.5
    var closingKmhToDbms: Double
        get() = prefs.getFloat(KEY_CLOSING_KMH_TO_DBMS, DEFAULT_CLOSING_KMH_TO_DBMS.toFloat())
                    .toDouble().coerceIn(0.1, 2.0)
        set(v) = prefs.edit().putFloat(KEY_CLOSING_KMH_TO_DBMS, v.coerceIn(0.1, 2.0).toFloat()).apply()

    // Actual/expected approach ratio — at or above: head-on (Time-Gate passes immediately)
    private const val KEY_COLLISION_HEAD_ON_RATIO = "collision_head_on_ratio"
    const val DEFAULT_COLLISION_HEAD_ON_RATIO = 0.6
    var collisionHeadOnRatio: Double
        get() = prefs.getFloat(KEY_COLLISION_HEAD_ON_RATIO, DEFAULT_COLLISION_HEAD_ON_RATIO.toFloat())
                    .toDouble().coerceIn(0.1, 1.0)
        set(v) = prefs.edit().putFloat(KEY_COLLISION_HEAD_ON_RATIO, v.coerceIn(0.1, 1.0).toFloat()).apply()

    // Actual/expected approach ratio — at or below: side/parallel (hold candidate)
    private const val KEY_COLLISION_SIDE_RATIO = "collision_side_ratio"
    const val DEFAULT_COLLISION_SIDE_RATIO = 0.3
    var collisionSideRatio: Double
        get() = prefs.getFloat(KEY_COLLISION_SIDE_RATIO, DEFAULT_COLLISION_SIDE_RATIO.toFloat())
                    .toDouble().coerceIn(0.0, 0.9)
        set(v) = prefs.edit().putFloat(KEY_COLLISION_SIDE_RATIO, v.coerceIn(0.0, 0.9).toFloat()).apply()

    // [Pre-filter] Front-stage EMA asymmetric alphas (rise/fall/D-Boost) — only for the RssiPreFilter front-stage instance
    private const val KEY_EMA_ALPHA_RISE = "ema_alpha_rise"
    const val DEFAULT_EMA_ALPHA_RISE = 0.3
    var emaAlphaRise: Double
        get() = prefs.getFloat(KEY_EMA_ALPHA_RISE, DEFAULT_EMA_ALPHA_RISE.toFloat())
                    .toDouble().coerceIn(0.05, 1.0)
        set(v) = prefs.edit().putFloat(KEY_EMA_ALPHA_RISE, v.coerceIn(0.05, 1.0).toFloat()).apply()

    private const val KEY_EMA_ALPHA_FALL = "ema_alpha_fall"
    // 0.12: faster tracking of departure (fall) — suppresses re-registration flapping on shallow SAFE dips (verified in simulation).
    //   Installs that opened developer settings have the old default saved by the
    //   spinner via putFloat, so a one-time init() migration overwrites it.
    private const val KEY_EMA_FALL_MIGR_V1156 = "ema_fall_migrated_v1156"
    const val DEFAULT_EMA_ALPHA_FALL = 0.12
    var emaAlphaFall: Double
        get() = prefs.getFloat(KEY_EMA_ALPHA_FALL, DEFAULT_EMA_ALPHA_FALL.toFloat())
                    .toDouble().coerceIn(0.01, 1.0)
        set(v) = prefs.edit().putFloat(KEY_EMA_ALPHA_FALL, v.coerceIn(0.01, 1.0).toFloat()).apply()

    private const val KEY_EMA_ALPHA_DBOOST = "ema_alpha_dboost"
    const val DEFAULT_EMA_ALPHA_DBOOST = 0.4
    var emaAlphaDBoost: Double
        get() = prefs.getFloat(KEY_EMA_ALPHA_DBOOST, DEFAULT_EMA_ALPHA_DBOOST.toFloat())
                    .toDouble().coerceIn(0.05, 1.0)
        set(v) = prefs.edit().putFloat(KEY_EMA_ALPHA_DBOOST, v.coerceIn(0.05, 1.0).toFloat()).apply()

    // EMA warm-up symmetric push count — for each device's first N pushes, the fall alpha equals the rise alpha
    //   (symmetric). On app restart, if the first sample anchors high by chance, the slower fall-α recovery would drag
    //   the session baseline ('restart deviation'); this corrects it. 0 = off.
    //   Applies to both the front stage (rssiPreFilter) and the post-processing P-EMA (pEmaFilter).
    private const val KEY_EMA_WARMUP_PUSHES = "ema_warmup_pushes"
    const val DEFAULT_EMA_WARMUP_PUSHES = 10
    var emaWarmupPushes: Int
        get() = prefs.getInt(KEY_EMA_WARMUP_PUSHES, DEFAULT_EMA_WARMUP_PUSHES).coerceIn(0, 30)
        set(v) = prefs.edit().putInt(KEY_EMA_WARMUP_PUSHES, v.coerceIn(0, 30)).apply()

    // Filter-keep band outside the warning zone (dB) — keep filter state within this margin even below rssiWarning
    private const val KEY_FILTER_PRESERVE_BAND_DB = "filter_preserve_band_db"
    const val DEFAULT_FILTER_PRESERVE_BAND_DB = 10
    var filterPreserveBandDb: Int
        get() = prefs.getInt(KEY_FILTER_PRESERVE_BAND_DB, DEFAULT_FILTER_PRESERVE_BAND_DB).coerceIn(0, 30)
        set(v) = prefs.edit().putInt(KEY_FILTER_PRESERVE_BAND_DB, v.coerceIn(0, 30)).apply()

    // [Power/comms] Advertising wake RSSI (dBm) — wake immediately if any signal is at or above this
    //   (the sleep boundary SLEEP_RSSI_DBM is unused in real code — only the wake threshold decides, so it isn't exposed)
    private const val KEY_WAKE_RSSI_DBM = "wake_rssi_dbm"
    const val DEFAULT_WAKE_RSSI_DBM = -95   // Opens the screen-off wake/batching gate farther out: no first-contact delay
    var wakeRssiDbm: Int
        get() = prefs.getInt(KEY_WAKE_RSSI_DBM, DEFAULT_WAKE_RSSI_DBM).coerceIn(-100, -60)
        set(v) = prefs.edit().putInt(KEY_WAKE_RSSI_DBM, v.coerceIn(-100, -60)).apply()

    // Signal-absent time (ms) — RSSI samples older than this count as 'no signal'
    private const val KEY_SIGNAL_STALE_MS = "signal_stale_ms"
    const val DEFAULT_SIGNAL_STALE_MS = 6000L
    var signalStaleMs: Long
        get() = prefs.getLong(KEY_SIGNAL_STALE_MS, DEFAULT_SIGNAL_STALE_MS).coerceIn(2000L, 30_000L)
        set(v) = prefs.edit().putLong(KEY_SIGNAL_STALE_MS, v.coerceIn(2000L, 30_000L)).apply()

    // Firebase alert save throttle (ms) — minimum interval between re-uploads for the same device
    private const val KEY_FIREBASE_THROTTLE_MS = "firebase_throttle_ms"
    const val DEFAULT_FIREBASE_THROTTLE_MS = 60_000L
    var firebaseThrottleMs: Long
        get() = prefs.getLong(KEY_FIREBASE_THROTTLE_MS, DEFAULT_FIREBASE_THROTTLE_MS).coerceIn(5000L, 600_000L)
        set(v) = prefs.edit().putLong(KEY_FIREBASE_THROTTLE_MS, v.coerceIn(5000L, 600_000L)).apply()

    // Speed TX polling period (ms) — interval for pushing ImuFusion speed to the advertiser
    private const val KEY_SPEED_PUSH_INTERVAL_MS = "speed_push_interval_ms"
    const val DEFAULT_SPEED_PUSH_INTERVAL_MS = 500L   // Faster STATE polling; live-tunable in dev settings (coerced 500~10000)
    var speedPushIntervalMs: Long
        get() = prefs.getLong(KEY_SPEED_PUSH_INTERVAL_MS, DEFAULT_SPEED_PUSH_INTERVAL_MS).coerceIn(500L, 10_000L)
        set(v) = prefs.edit().putLong(KEY_SPEED_PUSH_INTERVAL_MS, v.coerceIn(500L, 10_000L)).apply()

    // ===== Reverse (forward) prep — RX-side RSSI trend reversal detection =====
    //   If an approaching vehicle A's signal, while flat/weakening, suddenly gets stronger within ~1 s,
    //   treat it as the other vehicle starting to reverse (or move forward after stopping) and show "후진(전진)을 대비해주세요".

    // Detection on/off
    private const val KEY_REVERSE_PREP_ENABLED = "reverse_prep_enabled"
    var reversePrepEnabled: Boolean
        get() = prefs.getBoolean(KEY_REVERSE_PREP_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_REVERSE_PREP_ENABLED, v).apply()

    // Reversal trigger rise (dB) — a candidate when the current avg1sec is this much stronger than the window's first-half minimum
    private const val KEY_REVERSE_RISE_DBM = "reverse_rise_dbm"
    const val DEFAULT_REVERSE_RISE_DBM = 6
    var reverseRiseDbm: Int
        get() = prefs.getInt(KEY_REVERSE_RISE_DBM, DEFAULT_REVERSE_RISE_DBM).coerceIn(2, 20)
        set(v) = prefs.edit().putInt(KEY_REVERSE_RISE_DBM, v.coerceIn(2, 20)).apply()

    // Trend observation window (ms) — split by time into first/second halves to judge a reversal
    private const val KEY_REVERSE_WINDOW_MS = "reverse_window_ms"
    const val DEFAULT_REVERSE_WINDOW_MS = 1200L
    var reverseWindowMs: Long
        get() = prefs.getLong(KEY_REVERSE_WINDOW_MS, DEFAULT_REVERSE_WINDOW_MS).coerceIn(500L, 3000L)
        set(v) = prefs.edit().putLong(KEY_REVERSE_WINDOW_MS, v.coerceIn(500L, 3000L)).apply()

    // First-half stability tolerance (dB) — first-half change must be at or below
    // this to count as flat/weakening (excludes monotonic approach)
    private const val KEY_REVERSE_STABLE_TOL_DB = "reverse_stable_tol_db"
    const val DEFAULT_REVERSE_STABLE_TOL_DB = 2
    var reverseStableTolDb: Int
        get() = prefs.getInt(KEY_REVERSE_STABLE_TOL_DB, DEFAULT_REVERSE_STABLE_TOL_DB).coerceIn(0, 10)
        set(v) = prefs.edit().putInt(KEY_REVERSE_STABLE_TOL_DB, v.coerceIn(0, 10)).apply()

    // Label hold after detection (ms) — keep the "후진(전진) 대비" label this long from the trigger
    private const val KEY_REVERSE_PREP_HOLD_MS = "reverse_prep_hold_ms"
    const val DEFAULT_REVERSE_PREP_HOLD_MS = 4000L
    var reversePrepHoldMs: Long
        get() = prefs.getLong(KEY_REVERSE_PREP_HOLD_MS, DEFAULT_REVERSE_PREP_HOLD_MS).coerceIn(1000L, 10_000L)
        set(v) = prefs.edit().putLong(KEY_REVERSE_PREP_HOLD_MS, v.coerceIn(1000L, 10_000L)).apply()

    // ===== Payload-based asymmetric alert thresholds (active use of the role/state hex bits) =====
    //   Decoded CAT (role) and STATE bits shift the alert thresholds asymmetrically by role pair and state.
    //   Positive (+) offset = earlier alert at a weaker signal (farther), the fail-safe direction. BleService reads them
    //   live every frame through getters of the same name, applied without an app restart (as timeGateMs and reversePrep do).

    // Role asymmetry on/off — walker ↔ heavy equipment (forklift/EPJ) pairs alert earlier (mutual protection)
    private const val KEY_CATEGORY_BIAS_ENABLED = "category_bias_enabled"
    var categoryBiasEnabled: Boolean
        get() = prefs.getBoolean(KEY_CATEGORY_BIAS_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_CATEGORY_BIAS_ENABLED, v).apply()

    // Walker↔forklift early-alert offset (dB) — moves the warning/danger thresholds this much farther out. 0 = same as disabled
    //   This value is walker↔forklift only; EPJ uses walkerVsEpjBiasDb below.
    private const val KEY_WALKER_VS_EQUIP_BIAS_DB = "walker_vs_equip_bias_db"
    const val DEFAULT_WALKER_VS_EQUIP_BIAS_DB = 6
    var walkerVsEquipBiasDb: Int
        get() = prefs.getInt(KEY_WALKER_VS_EQUIP_BIAS_DB, DEFAULT_WALKER_VS_EQUIP_BIAS_DB).coerceIn(0, 15)
        set(v) = prefs.edit().putInt(KEY_WALKER_VS_EQUIP_BIAS_DB, v.coerceIn(0, 15)).apply()

    // Walker↔EPJ early-alert offset (dB) — separate from (milder than) the forklift one.
    //   An EPJ (electric pallet jack) is slow and often works in the same space, so the forklift value (+6) would over-alert.
    //   Default +2 so it sounds only when closer. 0 = neutral (same as the normal thresholds). Shares the categoryBiasEnabled toggle.
    private const val KEY_WALKER_VS_EPJ_BIAS_DB = "walker_vs_epj_bias_db"
    const val DEFAULT_WALKER_VS_EPJ_BIAS_DB = 2
    var walkerVsEpjBiasDb: Int
        get() = prefs.getInt(KEY_WALKER_VS_EPJ_BIAS_DB, DEFAULT_WALKER_VS_EPJ_BIAS_DB).coerceIn(0, 15)
        set(v) = prefs.edit().putInt(KEY_WALKER_VS_EPJ_BIAS_DB, v.coerceIn(0, 15)).apply()

    // Equipment↔equipment (forklift/EPJ) early-alert offset (dB) — the walker offsets (+6/+2) apply only to walkers, so
    //   forklift pairs, EPJ pairs and forklift↔EPJ pairs would otherwise get 0 (blind spot). When metal-cab shielding weakens RSSI it
    //   hovers around effWarning → intermittent/silent. This offset gives equipment pairs an early alert too. 0 = neutral (normal thresholds).
    //   Default +8 (simulation: reliably catches heavy shielding at -78~-82; over-alerts at a
    //   distant -88 in 1/20 or fewer). Shares the categoryBiasEnabled toggle.
    private const val KEY_EQUIP_VS_EQUIP_BIAS_DB = "equip_vs_equip_bias_db"
    const val DEFAULT_EQUIP_VS_EQUIP_BIAS_DB = 8
    var equipVsEquipBiasDb: Int
        get() = prefs.getInt(KEY_EQUIP_VS_EQUIP_BIAS_DB, DEFAULT_EQUIP_VS_EQUIP_BIAS_DB).coerceIn(0, 15)
        set(v) = prefs.edit().putInt(KEY_EQUIP_VS_EQUIP_BIAS_DB, v.coerceIn(0, 15)).apply()

    // EPJ↔EPJ offset (dB) — splits EPJ pairs off from equipment↔equipment (equipVsEquipBiasDb=+8).
    //   An EPJ has no metal cab (weak shielding) and runs slow at 3km/h, so coexisting at 5m is normal → using +8 (forklift heavy-shielding
    //   compensation) would over-alert even at 5m and 8m. Values near 0 and negative
    //   are allowed to separate distances (alert at 3m / silent at 5m).
    //   Negative = pulls effWarning to a stronger signal (closer), alerting only on entering
    //   3m (simulation: open -7 / standard -3 → lightly shielded EPJ default -2).
    //   If a forklift is on either side, equipVsEquipBiasDb (heavy shielding, conservative
    //   for the hazard source) applies. Shares the categoryBiasEnabled toggle.
    private const val KEY_EPJ_VS_EPJ_BIAS_DB = "epj_vs_epj_bias_db"
    const val DEFAULT_EPJ_VS_EPJ_BIAS_DB = -2
    var epjVsEpjBiasDb: Int
        get() = prefs.getInt(KEY_EPJ_VS_EPJ_BIAS_DB, DEFAULT_EPJ_VS_EPJ_BIAS_DB).coerceIn(-10, 15)
        set(v) = prefs.edit().putInt(KEY_EPJ_VS_EPJ_BIAS_DB, v.coerceIn(-10, 15)).apply()

    // ── Cooperative alert relaxation slack (dB) — offsets per-phone TX/RX asymmetry ──
    //   (my phone stays silent while the peer's sounds)
    //   Cause: RSSI is reciprocal in theory, but phones differ in TX power and RX sensitivity, so the A→B and B→A
    //   paths differ and at the same physical distance only one side reaches the warning zone (effWarning).
    //   Cooperative escalation accepts the peer's broadcast risk (rRisk) only when my own RSSI is also ≥ effWarning,
    //   so the weakly receiving phone ignores the peer's DANGER and stays silent.
    //   Fix: lower only the cooperative accept threshold from effWarning by this slack (e.g. -78→-86), so a phone
    //   that falls slightly short still sounds along.
    //   The normal alert threshold (effWarning) is unchanged — only the peer-broadcast acceptance gate yields.
    //   Truly distant false alarms (beyond the slack) stay blocked.
    //   Case B (RSSI) only — Case A (fresh UWB) is symmetric two-way ToF, so it does not intervene.
    //   0 = no relaxation.
    private const val KEY_COOP_SLACK_DB = "coop_slack_db"
    const val DEFAULT_COOP_SLACK_DB = 8
    var coopSlackDb: Int
        get() = prefs.getInt(KEY_COOP_SLACK_DB, DEFAULT_COOP_SLACK_DB).coerceIn(0, 20)
        set(v) = prefs.edit().putInt(KEY_COOP_SLACK_DB, v.coerceIn(0, 20)).apply()

    // ── Reciprocal RSSI exchange — symmetric decision with a baseline; coopSlack is fallback only ──
    //   How: each device echoes 'the RSSI at which I heard you' back in its scan response (0xE0C0). A and B both
    //   compute the same value sym=(rssi_A→B + rssi_B→A)/2 and judge it against one effWarning →
    //   per-phone TX/RX asymmetry cancels in the mean, so both phones always reach the same conclusion
    //   (both sound or both stay silent).
    //   This structurally removes coopSlack's baseline-less downward relaxation (double correction).
    //   Falls back to coopSlack only when there is no echo (bootstrap, peer on an older build, packet loss).
    //   Case B (RSSI) only — Case A (fresh two-way UWB ToF) is already symmetric, so it does not intervene.
    private const val KEY_RECIPROCAL_RSSI_ENABLED = "reciprocal_rssi_enabled"
    var reciprocalRssiEnabled: Boolean
        get() = prefs.getBoolean(KEY_RECIPROCAL_RSSI_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_RECIPROCAL_RSSI_ENABLED, v).apply()

    // Reciprocal RSSI consistency gate (dB) — if my measurement and the peer's echo differ by more than this,
    //   the echo is not trusted and coopSlack is used as fallback. Keeps hash collisions, momentary outliers and
    //   one-sided multipath gaps from polluting sym.
    private const val KEY_RECIPROCAL_MAX_DISAGREE_DB = "reciprocal_max_disagree_db"
    const val DEFAULT_RECIPROCAL_MAX_DISAGREE_DB = 25
    var reciprocalMaxDisagreeDb: Int
        get() = prefs.getInt(KEY_RECIPROCAL_MAX_DISAGREE_DB, DEFAULT_RECIPROCAL_MAX_DISAGREE_DB).coerceIn(5, 60)
        set(v) = prefs.edit().putInt(KEY_RECIPROCAL_MAX_DISAGREE_DB, v.coerceIn(5, 60)).apply()

    // State-based modulation on/off — extra early warning when the peer approaches moving FORWARD,
    //   audible suppression for IDLE-IDLE
    private const val KEY_STATE_MODULATION_ENABLED = "state_modulation_enabled"
    var stateModulationEnabled: Boolean
        get() = prefs.getBoolean(KEY_STATE_MODULATION_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_STATE_MODULATION_ENABLED, v).apply()

    // Extra early-warning offset (dB) when the peer moves FORWARD and approaches. Added to categoryBias.
    //   0 = same as disabled
    private const val KEY_FORWARD_APPROACH_BIAS_DB = "forward_approach_bias_db"
    const val DEFAULT_FORWARD_APPROACH_BIAS_DB = 3
    var forwardApproachBiasDb: Int
        get() = prefs.getInt(KEY_FORWARD_APPROACH_BIAS_DB, DEFAULT_FORWARD_APPROACH_BIAS_DB).coerceIn(0, 12)
        set(v) = prefs.edit().putInt(KEY_FORWARD_APPROACH_BIAS_DB, v.coerceIn(0, 12)).apply()

    // IDLE-IDLE audible suppression — if my IMU is stationary and the peer broadcasts IDLE, the audible WARNING
    //   is suppressed (display only). Default OFF for safety (opt-in). Even when on, DANGER is never suppressed,
    //   and it lifts immediately once either side moves.
    private const val KEY_IDLE_IDLE_SUPPRESS_ENABLED = "idle_idle_suppress_enabled"
    var idleIdleSuppressEnabled: Boolean
        get() = prefs.getBoolean(KEY_IDLE_IDLE_SUPPRESS_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_IDLE_IDLE_SUPPRESS_ENABLED, v).apply()

    // IDLE-IDLE suppression for EPJ role pairs — default ON for EPJ↔EPJ and EPJ↔walker (no forklift in the pair).
    //   EPJs work close together all day, so stationary↔stationary WARNING beeps are the main fatigue source
    //   (sim sim_epj.py quiet: 42~48% suppressed at duty30 normal work; worst-case audible delay standing→dash
    //   +0.01s, 0% misses, DANGER unchanged).
    //   The global flag above still suppresses all pairs (superset); this key false = these pairs follow only
    //   the global flag (kill switch).
    private const val KEY_IDLE_IDLE_SUPPRESS_EPJ_PAIRS_ENABLED = "idle_idle_suppress_epj_pairs_enabled"
    var idleIdleSuppressEpjPairsEnabled: Boolean
        get() = prefs.getBoolean(KEY_IDLE_IDLE_SUPPRESS_EPJ_PAIRS_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_IDLE_IDLE_SUPPRESS_EPJ_PAIRS_ENABLED, v).apply()

    // ── Background cold-start latency ─────────────────────────────
    //   Problem: once detected it works well, but the first wake-up is slow — even two 6 km/h forklifts
    //   approaching head-on (closing 3.33m/s) get a late first alarm.
    // Keep advertising while moving (IMU not stationary) instead of sleeping it (LOW_POWER ~1s)
    //   → transmit on first contact.
    //   Biggest lever in the cold-start chain (sim sa_wakeup_burst_sim: alarm distance +3.35m, latency ≈ half).
    //   Once stationary, evaluateAdvertiserPower lets it sleep again as usual (equipment after
    //   DEVICE_SLEEP_GRACE_MS), so battery impact stays small.
    private const val KEY_KEEP_ADV_WHILE_MOVING = "keep_adv_while_moving"
    var keepAdvertiseWhileMoving: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ADV_WHILE_MOVING, true)
        set(v) = prefs.edit().putBoolean(KEY_KEEP_ADV_WHILE_MOVING, v).apply()

    // On a nearby peer signal (rssi≥WAKE), burst my advertising at LOW_LATENCY (100ms) →
    //   the peer finds me sooner (mutual protection). Extended by hold while the peer stays close;
    //   back to normal once it moves away.
    //   Sim sa_burst_param_sweep: trigger=WAKE (-89, reached before the warning zone, pre-warming the gate),
    //   interval=100ms (inherent to LOW_LATENCY), hold=1500ms (1000~3000 equivalent, 0 missed alerts).
    private const val KEY_BURST_ENABLED = "burst_enabled"
    var burstEnabled: Boolean
        get() = prefs.getBoolean(KEY_BURST_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_BURST_ENABLED, v).apply()

    private const val KEY_BURST_HOLD_MS = "burst_hold_ms"
    const val DEFAULT_BURST_HOLD_MS = 1500L
    var burstHoldMs: Long
        get() = prefs.getLong(KEY_BURST_HOLD_MS, DEFAULT_BURST_HOLD_MS).coerceIn(500L, 5000L)
        set(v) = prefs.edit().putLong(KEY_BURST_HOLD_MS, v.coerceIn(500L, 5000L)).apply()

    // UWB precise ranging — supported devices measure true distance (m) over a UWB session (default ON).
    //   Off or unsupported: the BLE RSSI path runs unchanged (alert logic untouched).
    private const val KEY_UWB_ENABLED = "uwb_enabled"
    var uwbEnabled: Boolean
        get() = prefs.getBoolean(KEY_UWB_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_UWB_ENABLED, v).apply()

    // Force-enable UWB — with HW, permission and the system toggle satisfied, starts the UWB session even
    //   when the uwbEnabled toggle is off. Unsupported HW, denied permission or system OFF cannot be bypassed
    //   (forcing still can't open a session). Default OFF — for debugging/field verification.
    private const val KEY_UWB_FORCE = "uwb_force"
    var uwbForce: Boolean
        get() = prefs.getBoolean(KEY_UWB_FORCE, false)
        set(v) = prefs.edit().putBoolean(KEY_UWB_FORCE, v).apply()

    // UWB measurement sample upload — pairs UWB true distance (m) with the same frame's BLE RSSI and writes them
    //   to uwb_probe/<yyyyMMdd>. It is the only evidence of how many meters the thresholds (-78/-65dBm) really are,
    //   so turn it on only in field measurement sessions. OFF = no upload at all (default). It only records,
    //   independent of learning (onSample), so alert behavior is identical either way.
    //   Throttled to one record per pair per second.
    private const val KEY_UWB_PROBE_UPLOAD = "uwb_probe_upload"
    var uwbProbeUploadEnabled: Boolean
        get() = prefs.getBoolean(KEY_UWB_PROBE_UPLOAD, false)
        set(v) = prefs.edit().putBoolean(KEY_UWB_PROBE_UPLOAD, v).apply()

    // Distance display mode — signal label in the detection list and floating widget.
    //   0 = dBm only (default) / 1 = meters for UWB-measured pairs only /
    //   2 = meters for all (non-UWB shows the RSSI estimate "약 X m").
    //   Alert threshold sliders stay in dBm — display-only setting, no effect on alert logic.
    //   A value already saved is kept; the default applies only when nothing is saved.
    private const val KEY_DISTANCE_DISPLAY_MODE = "distance_display_mode"
    var distanceDisplayMode: Int
        get() = prefs.getInt(KEY_DISTANCE_DISPLAY_MODE, 0).coerceIn(0, 2)
        set(v) = prefs.edit().putInt(KEY_DISTANCE_DISPLAY_MODE, v.coerceIn(0, 2)).apply()

    // UWB primary distance authority — for pairs with a fresh UWB measurement (≤1 s), the measured distance is
    //   compared with the role-pair radii (forklift pairs = uwbForkliftWarn/Danger, others = uwbPairWarn/Danger):
    //   it promotes when UWB says closer, and demotes to the UWB level only while UWB kinematics show the pair
    //   moving apart (no demotion while approaching or stationary) — an exception to promote-only. Other
    //   promotion/release logic stays in the RSSI workflow; approach speed alone never promotes here.
    //   Pairs without a fresh measurement fall back to the RSSI pipeline seamlessly.
    //   ★ UWB distance learning (UwbCalibrator) keeps accumulating from fresh UWB measurements regardless
    //     of this switch (AlertStateMachine calls UwbCalibrator.onSample). The learned calibration is kept out of
    //     the RSSI decision (totalOffset) and only affects on-screen distance; the RSSI fallback uses pure RSSI.
    //   Default ON. When off, RSSI leads, plus the opt-in promote-only below.
    private const val KEY_UWB_PRIMARY_AUTHORITY_ENABLED = "uwb_primary_authority_enabled"
    var uwbPrimaryAuthorityEnabled: Boolean
        get() = prefs.getBoolean(KEY_UWB_PRIMARY_AUTHORITY_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_UWB_PRIMARY_AUTHORITY_ENABLED, v).apply()

    // UWB danger promotion (promote-only) — if the UWB-measured distance is within the promotion radius,
    //   the pair's alert is only promoted to DANGER (there is no code path for suppressing or demoting —
    //   safety invariant). The RSSI pipeline always runs for every pair — for devices without a UWB session,
    //   or with a dropped one, it is as if this feature did not exist. Default OFF for safety as a new
    //   intervention (opt-in) — toggle in the "UWB 고급" section of developer settings.
    private const val KEY_UWB_PROMOTE_ENABLED = "uwb_promote_enabled"
    var uwbPromoteEnabled: Boolean
        get() = prefs.getBoolean(KEY_UWB_PROMOTE_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_UWB_PROMOTE_ENABLED, v).apply()

    // UWB decision radii (m) — differ by role pair. A single 3m radius is already inside forklift braking distance,
    //   so it is not used (the old key uwb_danger_meters is left unreferenced — no migration needed).
    //   Pairs with at least one forklift = 15m warning / 8m danger;
    //   others (EPJ↔walker etc.) = 5m warning / 3m danger.
    //   Also the warning/danger thresholds of Case A (UWB-measured decision); judgeUwbOnly reads them on every call.
    //   Edited with four sliders in the "UWB 고급" section of developer settings (0.5m steps, applied immediately);
    //   the UWB card of BLE detection settings shows them read-only.
    //   An inverted setting (warning < danger) is left as-is (no auto clamp) — the danger branch is evaluated
    //   first, so danger wins (harmless).
    private const val KEY_UWB_FORKLIFT_WARN_METERS = "uwb_forklift_warn_meters"
    const val DEFAULT_UWB_FORKLIFT_WARN_METERS = 15.0f
    var uwbForkliftWarnMeters: Float
        get() = prefs.getFloat(KEY_UWB_FORKLIFT_WARN_METERS, DEFAULT_UWB_FORKLIFT_WARN_METERS).coerceIn(1f, 40f)
        set(v) = prefs.edit().putFloat(KEY_UWB_FORKLIFT_WARN_METERS, v.coerceIn(1f, 40f)).apply()

    private const val KEY_UWB_FORKLIFT_DANGER_METERS = "uwb_forklift_danger_meters"
    const val DEFAULT_UWB_FORKLIFT_DANGER_METERS = 8.0f
    var uwbForkliftDangerMeters: Float
        get() = prefs.getFloat(KEY_UWB_FORKLIFT_DANGER_METERS, DEFAULT_UWB_FORKLIFT_DANGER_METERS).coerceIn(0.5f, 30f)
        set(v) = prefs.edit().putFloat(KEY_UWB_FORKLIFT_DANGER_METERS, v.coerceIn(0.5f, 30f)).apply()

    private const val KEY_UWB_PAIR_WARN_METERS = "uwb_pair_warn_meters"
    const val DEFAULT_UWB_PAIR_WARN_METERS = 5.0f
    var uwbPairWarnMeters: Float
        get() = prefs.getFloat(KEY_UWB_PAIR_WARN_METERS, DEFAULT_UWB_PAIR_WARN_METERS).coerceIn(1f, 20f)
        set(v) = prefs.edit().putFloat(KEY_UWB_PAIR_WARN_METERS, v.coerceIn(1f, 20f)).apply()

    private const val KEY_UWB_PAIR_DANGER_METERS = "uwb_pair_danger_meters"
    const val DEFAULT_UWB_PAIR_DANGER_METERS = 3.0f
    var uwbPairDangerMeters: Float
        get() = prefs.getFloat(KEY_UWB_PAIR_DANGER_METERS, DEFAULT_UWB_PAIR_DANGER_METERS).coerceIn(0.5f, 15f)
        set(v) = prefs.edit().putFloat(KEY_UWB_PAIR_DANGER_METERS, v.coerceIn(0.5f, 15f)).apply()

    // UWB session start RSSI gate (dBm) — dead key: nothing reads it. The live start gate is the constant
    //   UwbRanger.UWB_START_RSSI_GATE_DBM. Leftover prefs values from older installs are harmless. Not shown in UI.
    private const val KEY_UWB_START_RSSI_GATE = "uwb_start_rssi_gate"
    const val DEFAULT_UWB_START_RSSI_GATE = -80
    var uwbStartRssiGate: Int
        get() = prefs.getInt(KEY_UWB_START_RSSI_GATE, DEFAULT_UWB_START_RSSI_GATE).coerceIn(-100, -50)
        set(v) = prefs.edit().putInt(KEY_UWB_START_RSSI_GATE, v.coerceIn(-100, -50)).apply()

    // Start gate (dBm) for forklift pairs — dead key: like uwbStartRssiGate above, nothing reads it.
    private const val KEY_UWB_START_RSSI_GATE_FORKLIFT = "uwb_start_rssi_gate_forklift"
    const val DEFAULT_UWB_START_RSSI_GATE_FORKLIFT = -90
    var uwbStartRssiGateForklift: Int
        get() = prefs.getInt(KEY_UWB_START_RSSI_GATE_FORKLIFT, DEFAULT_UWB_START_RSSI_GATE_FORKLIFT).coerceIn(-100, -50)
        set(v) = prefs.edit().putInt(KEY_UWB_START_RSSI_GATE_FORKLIFT, v.coerceIn(-100, -50)).apply()

    // UWB approach-speed promotion — if the UWB-measured approach speed stays at or above the threshold
    //   (default 6km/h) for 2 samples, the pair is promoted early to at least WARNING. Promote only — no demotion
    //   path. Default OFF (opt-in) as a new intervention.
    private const val KEY_UWB_VEL_PROMOTE_ENABLED = "uwb_vel_promote_enabled"
    var uwbVelPromoteEnabled: Boolean
        get() = prefs.getBoolean(KEY_UWB_VEL_PROMOTE_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_UWB_VEL_PROMOTE_ENABLED, v).apply()

    // UWB separation release — for a pair separating over 3 consecutive UWB samples, lowers the alert by
    //   measured distance (outside the warning radius = SAFE, within the warning band = capped at WARNING).
    //   Approved exception to the promote-only invariant (alerts must turn off when moving apart). Does not
    //   intervene inside the danger radius, on strong RSSI approach, or without a session; default OFF (opt-in)
    //   as a new intervention.
    private const val KEY_UWB_VEL_RELEASE_ENABLED = "uwb_vel_release_enabled"
    var uwbVelReleaseEnabled: Boolean
        get() = prefs.getBoolean(KEY_UWB_VEL_RELEASE_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_UWB_VEL_RELEASE_ENABLED, v).apply()

    // UWB approach-speed threshold (km/h) — site forklift speed limit (default 6). Not shown in UI (key only).
    private const val KEY_UWB_APPROACH_SPEED_KMH = "uwb_approach_speed_kmh"
    const val DEFAULT_UWB_APPROACH_SPEED_KMH = 6.0f
    var uwbApproachSpeedKmh: Float
        get() = prefs.getFloat(KEY_UWB_APPROACH_SPEED_KMH, DEFAULT_UWB_APPROACH_SPEED_KMH).coerceIn(1f, 30f)
        set(v) = prefs.edit().putFloat(KEY_UWB_APPROACH_SPEED_KMH, v.coerceIn(1f, 30f)).apply()

    // Site code — namespace separating alerts, the beacon registry and UWB Δ calibration (UwbCalibrator keeps
    //   per-site profiles), e.g. "WF11". Empty = common.
    //   The main screen accepts only the first entry while it is empty; later changes only in developer settings
    //   (behind the PIN). The UWB section of BLE settings shows it read-only. Case-insensitive (normalized to
    //   uppercase); characters outside [A-Z0-9_-] are dropped so it can be used as-is in Firebase paths and
    //   SharedPreferences file names.
    //   sitePrefName() has one consumer, BeaconRegistry — the moment a code is first set, adoptCommonPrefs()
    //   hands the common file's contents over to that site once. Echo calibration is a device property, so it
    //   uses one global, site-independent file (CalibrationEngine.ECHO_PREFS).
    private const val KEY_UWB_SITE_CODE = "uwb_site_code"   // legacy key name kept (no migration needed)
    const val SITE_CODE_MAX_LEN = 12
    var siteCode: String
        get() = normalizeSite(prefs.getString(KEY_UWB_SITE_CODE, "") ?: "")
        set(v) {
            val next = normalizeSite(v)
            if (next == siteCode) return
            prefs.edit().putString(KEY_UWB_SITE_CODE, next).apply()
            adoptCommonPrefs(next)
        }

    // Stores split per site (common file names). With a code they become base_CODE.
    private val SITE_PREF_BASES = listOf("beacon_registry")

    /**
     * When a site code is first set, hands the common file's existing registrations over to that site's file once.
     * Switching without the handover would leave the registered beacon list (zone beacons included) empty
     * and silently disable safe-zone muting. A target file that is already non-empty is left alone
     * (that site's registrations win — round trips between sites never overwrite them).
     */
    private fun adoptCommonPrefs(site: String) {
        val ctx = appCtx ?: return
        if (site.isEmpty()) return
        SITE_PREF_BASES.forEach { base ->
            val dst = ctx.getSharedPreferences(base + "_" + site, Context.MODE_PRIVATE)
            if (dst.all.isNotEmpty()) return@forEach
            val src = ctx.getSharedPreferences(base, Context.MODE_PRIVATE)
            if (src.all.isEmpty()) return@forEach
            val e = dst.edit()
            src.all.forEach { (k, v) ->
                when (v) {
                    is String  -> e.putString(k, v)
                    is Int     -> e.putInt(k, v)
                    is Long    -> e.putLong(k, v)
                    is Float   -> e.putFloat(k, v)
                    is Boolean -> e.putBoolean(k, v)
                    is Set<*>  -> @Suppress("UNCHECKED_CAST") e.putStringSet(k, v as Set<String>)
                }
            }
            e.apply()
            android.util.Log.i("DevSettings", "센터 전환 인계: ${base} -> ${base}_${site} (${src.all.size}건)")
        }
    }

    /** Normalizes input to the site-code form — uppercase, keep only [A-Z0-9_-], max 12 chars. */
    fun normalizeSite(raw: String): String =
        raw.trim().uppercase()
            .filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
            .take(SITE_CODE_MAX_LEN)

    /**
     * Per-site SharedPreferences file name — without a code, the common file (same as older builds) is used as-is.
     * siteCode is already normalized to [A-Z0-9_-], so no file-name escaping is needed.
     */
    fun sitePrefName(base: String): String =
        if (siteCode.isEmpty()) base else base + "_" + siteCode

    // Shadow IMU fusion — when I am stationary (IMU) and the peer's payload says FORWARD, a shadow Kalman on the
    //   median stream tracks the approach in parallel, providing an EMA descent alpha boost (0.4) on DANGER-exit
    //   frames and a backup TTC candidate. Off (false) bypasses the whole shadow path.
    private const val KEY_IMU_SHADOW_FUSION = "imu_shadow_fusion_enabled"
    var imuShadowFusionEnabled: Boolean
        get() = prefs.getBoolean(KEY_IMU_SHADOW_FUSION, true)
        set(v) = prefs.edit().putBoolean(KEY_IMU_SHADOW_FUSION, v).apply()

    // Exclusive UWB decision (Case A) — for pairs with UWB running on both sides and a fresh measurement (≤1 s),
    //   RSSI is excluded entirely and alerts are decided by UWB distance only (each UWB sample triggers a decision,
    //   so the decision period follows the UWB report period). When measurements go stale, it returns to RSSI
    //   decision (Case B) immediately. Off (false) disables Case A.
    private const val KEY_UWB_EXCLUSIVE_JUDGE = "uwb_exclusive_judge_enabled"
    var uwbExclusiveJudgeEnabled: Boolean
        get() = prefs.getBoolean(KEY_UWB_EXCLUSIVE_JUDGE, true)
        set(v) = prefs.edit().putBoolean(KEY_UWB_EXCLUSIVE_JUDGE, v).apply()

    // Level 2 echo-deviation auto-calibration — cancels systematic TX/RX asymmetry with the per-device-pair
    //   median of the echo histogram. Adds echoCal = clamp(−median/2, ±clampDb) to totalOffset
    //   (half: each side of the mirrored pair backs off by half, converging on the symmetric point).
    //   Default ON. The switch and tunables are edited in the "판정 파라미터" section of developer settings; the
    //   "경보 기본" section of BLE detection settings shows them read-only along with the diagnostics.
    //   Safeguards: median, clamp, gate, kill switch.
    //   Unlike uwbCalibOffset (removed from totalOffset for learning contamination), echo deviation is a two-way
    //   differential measurement, so NLOS cancels to first order as common mode.
    private const val KEY_ECHO_AUTO_CALIB = "echo_auto_calib_enabled"
    var echoAutoCalibEnabled: Boolean
        get() = prefs.getBoolean(KEY_ECHO_AUTO_CALIB, true)
        set(v) = prefs.edit().putBoolean(KEY_ECHO_AUTO_CALIB, v).apply()

    // Minimum echo ticks before calibration applies — at the ~120ms decision period, 3,000 ticks ≈ 6 minutes of
    //   close-range measurement (accumulated across sessions). Also used for the Σn validity check of the
    //   Firebase model-pair prior.
    private const val KEY_ECHO_CAL_MIN_TICKS = "echo_cal_min_ticks"
    const val DEFAULT_ECHO_CAL_MIN_TICKS = 3000
    var echoCalMinTicks: Int
        get() = prefs.getInt(KEY_ECHO_CAL_MIN_TICKS, DEFAULT_ECHO_CAL_MIN_TICKS).coerceIn(500, 30_000)
        set(v) = prefs.edit().putInt(KEY_ECHO_CAL_MIN_TICKS, v.coerceIn(500, 30_000)).apply()

    // Spread (±IQR/2) cap — above it, that device's calibration is 0 (channel noise too high to trust the median).
    private const val KEY_ECHO_CAL_MAX_IQR = "echo_cal_max_iqr_db"
    const val DEFAULT_ECHO_CAL_MAX_IQR = 6
    var echoCalMaxIqrDb: Int
        get() = prefs.getInt(KEY_ECHO_CAL_MAX_IQR, DEFAULT_ECHO_CAL_MAX_IQR).coerceIn(1, 15)
        set(v) = prefs.edit().putInt(KEY_ECHO_CAL_MAX_IQR, v.coerceIn(1, 15)).apply()

    // Calibration clamp (±dB) — no median, however large, moves the threshold further than this (runaway cap).
    private const val KEY_ECHO_CAL_CLAMP = "echo_cal_clamp_db"
    const val DEFAULT_ECHO_CAL_CLAMP = 6
    var echoCalClampDb: Int
        get() = prefs.getInt(KEY_ECHO_CAL_CLAMP, DEFAULT_ECHO_CAL_CLAMP).coerceIn(1, 12)
        set(v) = prefs.edit().putInt(KEY_ECHO_CAL_CLAMP, v.coerceIn(1, 12)).apply()

    fun toDebugString(): String =
        "rssiWarning=$rssiWarning | rssiDanger=$rssiDanger | scanPeriod=${scanPeriodMs}ms | " +
        "advertise=${advertiseInterval}ms | vib=$vibrationEnabled | sound=$soundEnabled | " +
        "fbRoot=$firebaseRoot | debug=$debugMode | simRssi=$simulatedRssi"
}
