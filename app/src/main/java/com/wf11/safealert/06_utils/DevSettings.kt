package com.wf11.safealert.utils

import android.content.Context
import android.content.SharedPreferences

object DevSettings {

    private const val PREF_NAME = "dev_settings"

    // Keys
    private const val KEY_RSSI_WARNING          = "rssi_warning"
    private const val KEY_RSSI_DANGER           = "rssi_danger"
    private const val KEY_SCAN_PERIOD_MS        = "scan_period_ms"
    private const val KEY_ADVERTISE_INTERVAL    = "advertise_interval"
    private const val KEY_VIBRATION_ENABLED     = "vibration_enabled"
    private const val KEY_VIBRATION_WARNING_MS  = "vibration_warning_ms"
    private const val KEY_VIBRATION_DANGER_COUNT= "vibration_danger_count"
    private const val KEY_SOUND_ENABLED         = "sound_enabled"
    private const val KEY_FIREBASE_ROOT         = "firebase_root"
    private const val KEY_AUTO_SAVE_ALERTS      = "auto_save_alerts"

    // 감지 모드
    private const val KEY_DETECTION_MODE = "detection_mode"
    const val MODE_KALMAN    = "kalman"     // 칼만 필터 + 거리 기반
    const val MODE_FIXED_AVG = "fixed_avg"  // 1초 평균 + 고정 RSSI 임계값

    var detectionMode: String
        get() = prefs.getString(KEY_DETECTION_MODE, MODE_KALMAN) ?: MODE_KALMAN
        set(v) = prefs.edit().putString(KEY_DETECTION_MODE, v).apply()

    // ── 칼만 필터 강도 프리셋 ──────────────────────────────────────
    // 숫자가 낮을수록 RSSI 변화에 빠르게 반응 (노이즈↑), 높을수록 부드럽지만 느림
    //   FAST  (0): Q=1.0 R=8.0  — 빠른 반응 (빠르게 접근하는 장비 추적에 적합)
    //   NORMAL(1): Q=0.3 R=15.0 — 균형 (기본값, 일반 창고 환경)
    //   SMOOTH(2): Q=0.1 R=25.0 — 강한 평활 (노이즈 심한 환경, 이전 기본값)
    const val KALMAN_PRESET_FAST   = 0
    const val KALMAN_PRESET_NORMAL = 1
    const val KALMAN_PRESET_SMOOTH = 2
    private const val KEY_KALMAN_PRESET = "kalman_preset"
    var kalmanPreset: Int
        get() = prefs.getInt(KEY_KALMAN_PRESET, KALMAN_PRESET_NORMAL)
        set(v) = prefs.edit().putInt(KEY_KALMAN_PRESET, v.coerceIn(0, 2)).apply()

    // 보조 모드 기여도 (0~50%)
    // 0 = 순수 주 모드, 30 = 주 70% + 보조 30%
    // 칼만 모드: 칼만(주) + 1초평균(보조) / 고정값 모드: 1초평균(주) + 칼만(보조)
    private const val KEY_BLEND_RATIO = "blend_ratio"
    var blendRatio: Int
        get() = prefs.getInt(KEY_BLEND_RATIO, 0)
        set(v) = prefs.edit().putInt(KEY_BLEND_RATIO, v.coerceIn(0, 50)).apply()

    // 고정값 모드 임계값 (절댓값으로 저장, 예: 65 → RSSI -65)
    private const val KEY_FIXED_DANGER_ABS  = "fixed_danger_abs"   // 기본 65  → RSSI ≥ -65
    private const val KEY_FIXED_WARNING_ABS = "fixed_warning_abs"  // 기본 80  → RSSI ≥ -80

    var fixedDangerAbs: Int
        get() = prefs.getInt(KEY_FIXED_DANGER_ABS, 65)
        set(v) = prefs.edit().putInt(KEY_FIXED_DANGER_ABS, v.coerceIn(30, 100)).apply()

    var fixedWarningAbs: Int
        get() = prefs.getInt(KEY_FIXED_WARNING_ABS, 80)
        set(v) = prefs.edit().putInt(KEY_FIXED_WARNING_ABS, v.coerceIn(30, 100)).apply()

    // 알람 볼륨 게인 (0-100%)
    private const val KEY_ALARM_VOLUME = "alarm_volume"

    // BLE 거리 교정
    private const val KEY_CALIB_RSSI    = "calib_rssi_at_1m"
    private const val KEY_PATH_LOSS_EXP = "path_loss_exp"
    private const val KEY_CALIB_VER     = "calib_version"
    // v3: 실측 기반 기본값 확정 (5m=-56, 10m=-63, 15m=-69 회귀)
    // v4: DEFAULT_CALIB -38→-50 보정 (실측 대비 3배 과대 추정 수정)
    // 버전이 낮으면 자동으로 기본값 적용 (이전 잘못된 교정값 제거)
    private const val CALIB_VERSION     = 4

    // 송수신 모드 설정
    private const val KEY_DEVICE_TX  = "device_tx"   // 장비 작업자 송신 여부
    private const val KEY_DEVICE_RX  = "device_rx"   // 장비 작업자 수신 여부
    private const val KEY_WALKER_TX  = "walker_tx"   // 보행자 송신 여부
    private const val KEY_WALKER_RX  = "walker_rx"   // 보행자 수신 여부
    private const val KEY_DEBUG_MODE            = "debug_mode"
    private const val KEY_SIMULATED_RSSI        = "simulated_rssi"
    private const val KEY_LOG_VERBOSE           = "log_verbose"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // 이전 버전의 잘못된 교정값 자동 초기화
        if (prefs.getInt(KEY_CALIB_VER, 0) < CALIB_VERSION) {
            prefs.edit()
                .remove(KEY_CALIB_RSSI)
                .remove(KEY_PATH_LOSS_EXP)
                .remove(KEY_APPLIED_PROFILE_MODEL)  // 버전 업 시 프로파일도 재적용
                .putInt(KEY_CALIB_VER, CALIB_VERSION)
                .apply()
        }
        // 기기 모델별 RSSI 수신 감도 프로파일 자동 주입
        // (수동 교정이 없는 경우, 처음 실행 시 1회만 적용)
        DeviceProfileManager.autoApplyIfNeeded()
    }

    // BLE 거리 설정 — 미터 단위 저장, RSSI는 교정값으로 자동 계산
    private const val KEY_WARNING_DIST = "warning_dist_m"
    private const val KEY_DANGER_DIST  = "danger_dist_m"

    var warningDistM: Float
        get() = prefs.getFloat(KEY_WARNING_DIST, 10f).coerceIn(1f, 50f)  // 기본 10m
        set(v) = prefs.edit().putFloat(KEY_WARNING_DIST, v.coerceIn(1f, 50f)).apply()

    var dangerDistM: Float
        get() = prefs.getFloat(KEY_DANGER_DIST, 5f).coerceIn(1f, 50f)    // 기본 5m
        set(v) = prefs.edit().putFloat(KEY_DANGER_DIST, v.coerceIn(1f, 50f)).apply()

    // 거리 → RSSI 변환 (교정값 반영) — 교정이 달라져도 "5m는 항상 5m"
    // rssi = calibRssiAt1m - 10 * n * log10(distance_m)
    val rssiWarning: Int
        get() {
            val dist = warningDistM.toDouble().coerceAtLeast(0.5)
            val n    = pathLossExp.toDouble()
            return (calibRssiAt1m.toDouble() - 10.0 * n * Math.log10(dist)).toInt()
        }

    val rssiDanger: Int
        get() {
            val dist = dangerDistM.toDouble().coerceAtLeast(0.5)
            val n    = pathLossExp.toDouble()
            return (calibRssiAt1m.toDouble() - 10.0 * n * Math.log10(dist)).toInt()
        }

    var scanPeriodMs: Long
        get() = prefs.getLong(KEY_SCAN_PERIOD_MS, 3000L)
        set(v) = prefs.edit().putLong(KEY_SCAN_PERIOD_MS, v).apply()

    var advertiseInterval: Int
        get() = prefs.getInt(KEY_ADVERTISE_INTERVAL, 200)
        set(v) = prefs.edit().putInt(KEY_ADVERTISE_INTERVAL, v).apply()

    // 경보 동작
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

    // Firebase 설정
    var firebaseRoot: String
        get() = prefs.getString(KEY_FIREBASE_ROOT, "wf11") ?: "wf11"
        set(v) = prefs.edit().putString(KEY_FIREBASE_ROOT, v).apply()

    var autoSaveAlerts: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SAVE_ALERTS, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_SAVE_ALERTS, v).apply()

    // 디버그 설정
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

    // 1m 기준 RSSI
    // 기본값 -50 dBm: -38에서 3배 과대 추정 보정 (30m→10m, 60m→20m 매칭)
    // 보정 수식: new = old - 10*n*log10(3) = -38 - 10*2.53*log10(3) ≈ -50
    // DeviceProfileManager 자동 주입 비교 기준값
    const val DEFAULT_CALIB = -50

    var calibRssiAt1m: Int
        get() = prefs.getInt(KEY_CALIB_RSSI, DEFAULT_CALIB)
        set(v) = prefs.edit().putInt(KEY_CALIB_RSSI, v.coerceIn(-90, 0)).apply()

    // 경로손실지수 n
    // 기본값 2.53: 실측 (5m=-56, 10m=-63, 15m=-69) 3점 회귀 결과
    var pathLossExp: Float
        get() {
            val v = prefs.getFloat(KEY_PATH_LOSS_EXP, 2.53f)
            return if (v < 1.5f || v > 4.5f) { prefs.edit().remove(KEY_PATH_LOSS_EXP).apply(); 2.53f } else v
        }
        set(v) = prefs.edit().putFloat(KEY_PATH_LOSS_EXP, v.coerceIn(1.5f, 4.5f)).apply()

    fun resetCalibration() {
        prefs.edit()
            .remove(KEY_CALIB_RSSI)
            .remove(KEY_PATH_LOSS_EXP)
            .putInt(KEY_CALIB_VER, CALIB_VERSION)  // 초기화 후도 최신 버전 유지
            .apply()
        // 이후 get 시 기본값 -50 dBm, n=2.53 사용됨
    }

    // 알람 볼륨 게인 (0~100%)
    var alarmVolume: Int
        get() = prefs.getInt(KEY_ALARM_VOLUME, 100)
        set(v) = prefs.edit().putInt(KEY_ALARM_VOLUME, v.coerceIn(0, 100)).apply()

    // 송수신 설정
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

    // 기기 모델 프로파일 자동 주입 이력
    // DeviceProfileManager가 이 모델에 이미 적용했는지 기억 (재부팅 후 불필요한 재적용 방지)
    private const val KEY_APPLIED_PROFILE_MODEL = "applied_profile_model"
    var appliedProfileModel: String
        get() = prefs.getString(KEY_APPLIED_PROFILE_MODEL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_APPLIED_PROFILE_MODEL, v).apply()

    // 보행자끼리 경보 여부 (기본: OFF — 보행자는 장비만 감지)
    private const val KEY_WALKER_DETECTS_WALKER = "walker_detects_walker"
    var walkerDetectsWalker: Boolean
        get() = prefs.getBoolean(KEY_WALKER_DETECTS_WALKER, false)
        set(v) = prefs.edit().putBoolean(KEY_WALKER_DETECTS_WALKER, v).apply()

    fun toDebugString(): String =
        "rssiWarning=$rssiWarning | rssiDanger=$rssiDanger | scanPeriod=${scanPeriodMs}ms | " +
        "advertise=${advertiseInterval}ms | vib=$vibrationEnabled | sound=$soundEnabled | " +
        "fbRoot=$firebaseRoot | debug=$debugMode | simRssi=$simulatedRssi"
}
