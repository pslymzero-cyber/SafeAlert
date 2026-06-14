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

    // [v1.0.42 Req5] Time-Gate(민감도 지연) 지연 시간 — 신규/격상 경보 전 최소 연속 접근시간(ms).
    //   BleService.APPROACH_TIMEGATE_MS 가 이 값을 매 프레임 라이브로 읽어 앱 재시작 없이 반영한다.
    //   기본 500L 은 기존 하드코딩값과 동일(거동 보존). 0=즉시통과 ~ 3000ms 범위로 clamp.
    private const val KEY_TIMEGATE_MS = "timegate_ms"
    const val DEFAULT_TIMEGATE_MS = 500L
    var timeGateMs: Long
        get() = prefs.getLong(KEY_TIMEGATE_MS, DEFAULT_TIMEGATE_MS).coerceIn(0L, 3000L)
        set(v) = prefs.edit().putLong(KEY_TIMEGATE_MS, v.coerceIn(0L, 3000L)).apply()

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

    // [v1.0.42] BLE 거리 교정 키(KEY_CALIB_RSSI/KEY_PATH_LOSS_EXP/KEY_CALIB_VER) 전면 제거.
    //   거리 추정은 칼만 필터(RSSI)만으로 수행 — 교정값·경로손실지수·거리 교정 마법사 폐지.

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
    }

    // [v1.0.42 Req5] 설정 라이브 전파 — dev_settings 변경 리스너 등록/해제(앱 재시작 없이 반영).
    //   prefs 를 외부에 노출하지 않고 등록 경로만 캡슐화한다. BleService 가 강한 참조로 리스너를
    //   보관(SharedPreferences 는 리스너를 WeakReference 로 들고 있어 약참조면 GC 로 끊긴다).
    fun registerOnChange(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(l)
    fun unregisterOnChange(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    // [v1.0.40] 위험/경보 RSSI 임계 — 신호세기(dBm)로 직접 제어 (사용자 조정 가능)
    //   v1.0.39 에서 거리계산 파생을 폐지하고 절대 고정(-75/-55)했고, v1.0.40 부터는
    //   BLE 설정의 dBm 슬라이더로 직접 저장/조정한다(고정값 모드 절댓값 슬라이더와 통일).
    //   저장은 음수 dBm 그대로. UI 슬라이더는 절댓값(양수 30~100)으로 표시 후 음수화해 저장.
    //   제약(UI): 위험은 경고보다 가까움 = 덜 음수 = 절댓값이 더 작다 (예 위험 -55 > 경고 -75).
    //   [v1.0.42] 거리계산·교정(calibRssiAt1m/pathLossExp/warningDistM/dangerDistM/거리 교정
    //     마법사) 전면 폐지 → 거리 추정은 칼만 필터(RSSI)만으로 수행. dBm 임계만 직접 조정한다.
    //   prefs 키는 기존 KEY_RSSI_WARNING / KEY_RSSI_DANGER (상단 L11-12) 재사용.
    const val DEFAULT_RSSI_WARNING_ABS = -75   // 경보(WARNING): RSSI >= -75  (-56~-75 구간)
    const val DEFAULT_RSSI_DANGER_ABS  = -55   // 위험(DANGER) : RSSI >= -55  (0~-55 구간)
    const val RSSI_THRESH_MIN = -100           // 슬라이더 하한(가장 멂, 절댓값 100)
    const val RSSI_THRESH_MAX = -30            // 슬라이더 상한(가장 가까움, 절댓값 30)

    var rssiWarning: Int
        get() = prefs.getInt(KEY_RSSI_WARNING, DEFAULT_RSSI_WARNING_ABS).coerceIn(RSSI_THRESH_MIN, RSSI_THRESH_MAX)
        set(v) = prefs.edit().putInt(KEY_RSSI_WARNING, v.coerceIn(RSSI_THRESH_MIN, RSSI_THRESH_MAX)).apply()

    var rssiDanger: Int
        get() = prefs.getInt(KEY_RSSI_DANGER, DEFAULT_RSSI_DANGER_ABS).coerceIn(RSSI_THRESH_MIN, RSSI_THRESH_MAX)
        set(v) = prefs.edit().putInt(KEY_RSSI_DANGER, v.coerceIn(RSSI_THRESH_MIN, RSSI_THRESH_MAX)).apply()

    // [v1.1.7 #3] 기본 스캔 주기 3000ms→1000ms. BleScanner.mapScanMode 가 ≤1000ms 를
    //   LOW_LATENCY(거의 연속 스캔)로 매핑 → 감지 blind window 제거(알람 지연/누락 방지).
    var scanPeriodMs: Long
        get() = prefs.getLong(KEY_SCAN_PERIOD_MS, 1000L)
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

    // [v1.0.42] calibRssiAt1m / pathLossExp / resetCalibration() / DEFAULT_CALIB 전면 제거.
    //   거리 추정은 칼만 필터(RSSI)만으로 수행 — RSSI→거리 환산식·경로손실 모델 폐지.

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

    // [v1.0.42] 기기 모델 프로파일 자동 주입 이력(appliedProfileModel) 제거 — DeviceProfileManager 폐지.

    // 보행자끼리 경보 여부 (기본: OFF — 보행자는 장비만 감지)
    private const val KEY_WALKER_DETECTS_WALKER = "walker_detects_walker"
    var walkerDetectsWalker: Boolean
        get() = prefs.getBoolean(KEY_WALKER_DETECTS_WALKER, false)
        set(v) = prefs.edit().putBoolean(KEY_WALKER_DETECTS_WALKER, v).apply()

    // ── 판정 파라미터 — BleService 하드코딩 상수의 설정 전환 ─────────────────
    //   기본값은 모두 '기존 하드코딩값과 동일'(거동 보존). BleService 가 같은 이름의
    //   private 게터로 매 프레임 라이브로 읽어 앱 재시작 없이 반영한다(timeGateMs 선례).
    //   소수(Double) 값은 SharedPreferences 제약상 Float 로 저장하고 Double 로 노출.
    //   resetToDefault()의 prefs.clear() 가 아래 항목도 일괄 기본값 복원한다.

    // [판정 게이트] TTC 선발령 임계(s) — 이 값 이하로 충돌 임박 시 긴급 발령
    private const val KEY_TTC_THRESHOLD_SEC = "ttc_threshold_sec"
    const val DEFAULT_TTC_THRESHOLD_SEC = 3.0
    var ttcThresholdSec: Double
        get() = prefs.getFloat(KEY_TTC_THRESHOLD_SEC, DEFAULT_TTC_THRESHOLD_SEC.toFloat())
                    .toDouble().coerceIn(0.5, 10.0)
        set(v) = prefs.edit().putFloat(KEY_TTC_THRESHOLD_SEC, v.coerceIn(0.5, 10.0).toFloat()).apply()

    // TTC 계산 최소 접근속도(dBm/s) — 이보다 느리면 TTC 산출 안 함
    private const val KEY_MIN_APPROACH_VEL = "min_approach_vel_dbm"
    const val DEFAULT_MIN_APPROACH_VEL = 0.5
    var minApproachVelDbm: Double
        get() = prefs.getFloat(KEY_MIN_APPROACH_VEL, DEFAULT_MIN_APPROACH_VEL.toFloat())
                    .toDouble().coerceIn(0.1, 3.0)
        set(v) = prefs.edit().putFloat(KEY_MIN_APPROACH_VEL, v.coerceIn(0.1, 3.0).toFloat()).apply()

    // Time-Gate '가까워짐' 판정 최소 접근속도(dBm/s)
    private const val KEY_TIMEGATE_VEL = "timegate_vel_dbm"
    const val DEFAULT_TIMEGATE_VEL = 0.5
    var timeGateVelDbm: Double
        get() = prefs.getFloat(KEY_TIMEGATE_VEL, DEFAULT_TIMEGATE_VEL.toFloat())
                    .toDouble().coerceIn(0.1, 3.0)
        set(v) = prefs.edit().putFloat(KEY_TIMEGATE_VEL, v.coerceIn(0.1, 3.0).toFloat()).apply()

    // 코너링 중 Time-Gate 연장 시간(ms) — 급회전 시 전파 출렁임 방어
    private const val KEY_TIMEGATE_CORNERING_MS = "timegate_cornering_ms"
    const val DEFAULT_TIMEGATE_CORNERING_MS = 1000L
    var corneringTimeGateMs: Long
        get() = prefs.getLong(KEY_TIMEGATE_CORNERING_MS, DEFAULT_TIMEGATE_CORNERING_MS).coerceIn(0L, 5000L)
        set(v) = prefs.edit().putLong(KEY_TIMEGATE_CORNERING_MS, v.coerceIn(0L, 5000L)).apply()

    // [쿨다운·해제] 경고/위험 재알람 쿨다운(ms)
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

    // 경보 격하 히스테리시스(dB) — 임계 미만 이 폭 안은 현 등급 유지(채터링 방지)
    private const val KEY_HYSTERESIS_DBM = "hysteresis_dbm"
    const val DEFAULT_HYSTERESIS_DBM = 5
    var hysteresisDbm: Int
        get() = prefs.getInt(KEY_HYSTERESIS_DBM, DEFAULT_HYSTERESIS_DBM).coerceIn(0, 15)
        set(v) = prefs.edit().putInt(KEY_HYSTERESIS_DBM, v.coerceIn(0, 15)).apply()

    // DEPARTING(이탈) 중 재경보 추가 마진(dB)
    private const val KEY_DEPARTING_HYSTERESIS_DBM = "departing_hysteresis_dbm"
    const val DEFAULT_DEPARTING_HYSTERESIS_DBM = 8
    var departingHysteresisDbm: Int
        get() = prefs.getInt(KEY_DEPARTING_HYSTERESIS_DBM, DEFAULT_DEPARTING_HYSTERESIS_DBM).coerceIn(0, 20)
        set(v) = prefs.edit().putInt(KEY_DEPARTING_HYSTERESIS_DBM, v.coerceIn(0, 20)).apply()

    // 페이드아웃: 피크 대비 하락(dB)이 지속(ms)되면 경보 해제
    private const val KEY_RECEDING_CLEAR_MS = "receding_clear_ms"
    const val DEFAULT_RECEDING_CLEAR_MS = 2500L
    var recedingClearMs: Long
        get() = prefs.getLong(KEY_RECEDING_CLEAR_MS, DEFAULT_RECEDING_CLEAR_MS).coerceIn(500L, 10_000L)
        set(v) = prefs.edit().putLong(KEY_RECEDING_CLEAR_MS, v.coerceIn(500L, 10_000L)).apply()

    private const val KEY_RECEDING_DBM_DROP = "receding_dbm_drop"
    const val DEFAULT_RECEDING_DBM_DROP = 5
    var recedingDbmDrop: Int
        get() = prefs.getInt(KEY_RECEDING_DBM_DROP, DEFAULT_RECEDING_DBM_DROP).coerceIn(1, 20)
        set(v) = prefs.edit().putInt(KEY_RECEDING_DBM_DROP, v.coerceIn(1, 20)).apply()

    // [충돌 기하학] 합산속도(km/h) → 예상 접근속도(dBm/s) 환산계수
    private const val KEY_CLOSING_KMH_TO_DBMS = "closing_kmh_to_dbms"
    const val DEFAULT_CLOSING_KMH_TO_DBMS = 0.5
    var closingKmhToDbms: Double
        get() = prefs.getFloat(KEY_CLOSING_KMH_TO_DBMS, DEFAULT_CLOSING_KMH_TO_DBMS.toFloat())
                    .toDouble().coerceIn(0.1, 2.0)
        set(v) = prefs.edit().putFloat(KEY_CLOSING_KMH_TO_DBMS, v.coerceIn(0.1, 2.0).toFloat()).apply()

    // 실제/예상 접근비 — 이상이면 정면충돌(Time-Gate 즉시통과)
    private const val KEY_COLLISION_HEAD_ON_RATIO = "collision_head_on_ratio"
    const val DEFAULT_COLLISION_HEAD_ON_RATIO = 0.6
    var collisionHeadOnRatio: Double
        get() = prefs.getFloat(KEY_COLLISION_HEAD_ON_RATIO, DEFAULT_COLLISION_HEAD_ON_RATIO.toFloat())
                    .toDouble().coerceIn(0.1, 1.0)
        set(v) = prefs.edit().putFloat(KEY_COLLISION_HEAD_ON_RATIO, v.coerceIn(0.1, 1.0).toFloat()).apply()

    // 실제/예상 접근비 — 이하면 측면/나란히(보류 후보)
    private const val KEY_COLLISION_SIDE_RATIO = "collision_side_ratio"
    const val DEFAULT_COLLISION_SIDE_RATIO = 0.3
    var collisionSideRatio: Double
        get() = prefs.getFloat(KEY_COLLISION_SIDE_RATIO, DEFAULT_COLLISION_SIDE_RATIO.toFloat())
                    .toDouble().coerceIn(0.0, 0.9)
        set(v) = prefs.edit().putFloat(KEY_COLLISION_SIDE_RATIO, v.coerceIn(0.0, 0.9).toFloat()).apply()

    // [전처리 필터] 전단 EMA 비대칭 알파(상승/하강/D-Boost) — RssiPreFilter 전단 인스턴스 전용
    private const val KEY_EMA_ALPHA_RISE = "ema_alpha_rise"
    const val DEFAULT_EMA_ALPHA_RISE = 0.3
    var emaAlphaRise: Double
        get() = prefs.getFloat(KEY_EMA_ALPHA_RISE, DEFAULT_EMA_ALPHA_RISE.toFloat())
                    .toDouble().coerceIn(0.05, 1.0)
        set(v) = prefs.edit().putFloat(KEY_EMA_ALPHA_RISE, v.coerceIn(0.05, 1.0).toFloat()).apply()

    private const val KEY_EMA_ALPHA_FALL = "ema_alpha_fall"
    const val DEFAULT_EMA_ALPHA_FALL = 0.05
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

    // 경고권 밖 필터 보존 밴드(dB) — rssiWarning 미달이라도 이 폭 안이면 필터 상태 보존
    private const val KEY_FILTER_PRESERVE_BAND_DB = "filter_preserve_band_db"
    const val DEFAULT_FILTER_PRESERVE_BAND_DB = 10
    var filterPreserveBandDb: Int
        get() = prefs.getInt(KEY_FILTER_PRESERVE_BAND_DB, DEFAULT_FILTER_PRESERVE_BAND_DB).coerceIn(0, 30)
        set(v) = prefs.edit().putInt(KEY_FILTER_PRESERVE_BAND_DB, v.coerceIn(0, 30)).apply()

    // [전력·통신] 광고 웨이크 RSSI(dBm) — 하나라도 이 값 이상이면 즉시 웨이크
    //   (슬립 경계 SLEEP_RSSI_DBM 은 실코드 미사용 — 웨이크 임계 단일 판정이라 함께 노출 안 함)
    private const val KEY_WAKE_RSSI_DBM = "wake_rssi_dbm"
    const val DEFAULT_WAKE_RSSI_DBM = -89
    var wakeRssiDbm: Int
        get() = prefs.getInt(KEY_WAKE_RSSI_DBM, DEFAULT_WAKE_RSSI_DBM).coerceIn(-100, -60)
        set(v) = prefs.edit().putInt(KEY_WAKE_RSSI_DBM, v.coerceIn(-100, -60)).apply()

    // 신호 부재 간주 시간(ms) — 이보다 오래된 RSSI 표본은 '신호 없음'
    private const val KEY_SIGNAL_STALE_MS = "signal_stale_ms"
    const val DEFAULT_SIGNAL_STALE_MS = 6000L
    var signalStaleMs: Long
        get() = prefs.getLong(KEY_SIGNAL_STALE_MS, DEFAULT_SIGNAL_STALE_MS).coerceIn(2000L, 30_000L)
        set(v) = prefs.edit().putLong(KEY_SIGNAL_STALE_MS, v.coerceIn(2000L, 30_000L)).apply()

    // Firebase 경보 저장 스로틀(ms) — 같은 기기 재업로드 최소 간격
    private const val KEY_FIREBASE_THROTTLE_MS = "firebase_throttle_ms"
    const val DEFAULT_FIREBASE_THROTTLE_MS = 60_000L
    var firebaseThrottleMs: Long
        get() = prefs.getLong(KEY_FIREBASE_THROTTLE_MS, DEFAULT_FIREBASE_THROTTLE_MS).coerceIn(5000L, 600_000L)
        set(v) = prefs.edit().putLong(KEY_FIREBASE_THROTTLE_MS, v.coerceIn(5000L, 600_000L)).apply()

    // 속도 송신 폴링 주기(ms) — ImuFusion 속도를 advertiser 에 push 하는 간격
    private const val KEY_SPEED_PUSH_INTERVAL_MS = "speed_push_interval_ms"
    const val DEFAULT_SPEED_PUSH_INTERVAL_MS = 1500L
    var speedPushIntervalMs: Long
        get() = prefs.getLong(KEY_SPEED_PUSH_INTERVAL_MS, DEFAULT_SPEED_PUSH_INTERVAL_MS).coerceIn(500L, 10_000L)
        set(v) = prefs.edit().putLong(KEY_SPEED_PUSH_INTERVAL_MS, v.coerceIn(500L, 10_000L)).apply()

    // ===== [v1.1.7 #2] 후진(전진) 대비 — RX측 RSSI 추세 반전 감지 =====
    //   접근 중인 차량 A의 신호가 '정체/약화' 상태에서 ~1초 내 갑자기 강해지면
    //   상대 차량의 후진(또는 정지 후 전진) 출발로 보고 "후진(전진)을 대비해주세요" 표시.

    // 감지 on/off
    private const val KEY_REVERSE_PREP_ENABLED = "reverse_prep_enabled"
    var reversePrepEnabled: Boolean
        get() = prefs.getBoolean(KEY_REVERSE_PREP_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_REVERSE_PREP_ENABLED, v).apply()

    // 반전 트리거 상승폭(dB) — 윈도 전반 최저점 대비 현재 avg1sec 가 이만큼 강해지면 후보
    private const val KEY_REVERSE_RISE_DBM = "reverse_rise_dbm"
    const val DEFAULT_REVERSE_RISE_DBM = 6
    var reverseRiseDbm: Int
        get() = prefs.getInt(KEY_REVERSE_RISE_DBM, DEFAULT_REVERSE_RISE_DBM).coerceIn(2, 20)
        set(v) = prefs.edit().putInt(KEY_REVERSE_RISE_DBM, v.coerceIn(2, 20)).apply()

    // 추세 관측 윈도(ms) — 이 구간을 시간 기준 전/후반으로 나눠 반전 판정
    private const val KEY_REVERSE_WINDOW_MS = "reverse_window_ms"
    const val DEFAULT_REVERSE_WINDOW_MS = 1200L
    var reverseWindowMs: Long
        get() = prefs.getLong(KEY_REVERSE_WINDOW_MS, DEFAULT_REVERSE_WINDOW_MS).coerceIn(500L, 3000L)
        set(v) = prefs.edit().putLong(KEY_REVERSE_WINDOW_MS, v.coerceIn(500L, 3000L)).apply()

    // 전반 추세 안정 허용치(dB) — 전반부 변화량이 이하라야 '정체/약화'로 인정(단조 접근 자동 제외)
    private const val KEY_REVERSE_STABLE_TOL_DB = "reverse_stable_tol_db"
    const val DEFAULT_REVERSE_STABLE_TOL_DB = 2
    var reverseStableTolDb: Int
        get() = prefs.getInt(KEY_REVERSE_STABLE_TOL_DB, DEFAULT_REVERSE_STABLE_TOL_DB).coerceIn(0, 10)
        set(v) = prefs.edit().putInt(KEY_REVERSE_STABLE_TOL_DB, v.coerceIn(0, 10)).apply()

    // 감지 후 라벨 유지(ms) — 트리거 시점부터 이 시간 동안 "후진(전진) 대비" 라벨 유지
    private const val KEY_REVERSE_PREP_HOLD_MS = "reverse_prep_hold_ms"
    const val DEFAULT_REVERSE_PREP_HOLD_MS = 4000L
    var reversePrepHoldMs: Long
        get() = prefs.getLong(KEY_REVERSE_PREP_HOLD_MS, DEFAULT_REVERSE_PREP_HOLD_MS).coerceIn(1000L, 10_000L)
        set(v) = prefs.edit().putLong(KEY_REVERSE_PREP_HOLD_MS, v.coerceIn(1000L, 10_000L)).apply()

    fun toDebugString(): String =
        "rssiWarning=$rssiWarning | rssiDanger=$rssiDanger | scanPeriod=${scanPeriodMs}ms | " +
        "advertise=${advertiseInterval}ms | vib=$vibrationEnabled | sound=$soundEnabled | " +
        "fbRoot=$firebaseRoot | debug=$debugMode | simRssi=$simulatedRssi"
}
