package com.wf11.safealert.utils

import android.content.Context
import android.content.SharedPreferences

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

    // ── 칼만 필터 강도 프리셋 ──────────────────────────────────────
    // 숫자가 낮을수록 RSSI 변화에 빠르게 반응 (노이즈↑), 높을수록 부드럽지만 느림
    //   (실제 적용값은 KalmanFilter.kt processNoise/measureNoise 게터)
    //   FAST  (0): q=0.50 R=2.0  — 빠른 반응 (빠르게 접근하는 장비 추적에 적합)
    //   NORMAL(1): q=0.15 R=5.0  — 균형 (기본값, 일반 창고 환경)
    //   SMOOTH(2): q=0.05 R=10.0 — 강한 평활 (노이즈 심한 환경, 이전 기본값)
    const val KALMAN_PRESET_FAST   = 0
    const val KALMAN_PRESET_NORMAL = 1
    const val KALMAN_PRESET_SMOOTH = 2
    private const val KEY_KALMAN_PRESET = "kalman_preset"
    var kalmanPreset: Int
        get() = prefs.getInt(KEY_KALMAN_PRESET, KALMAN_PRESET_NORMAL)
        set(v) = prefs.edit().putInt(KEY_KALMAN_PRESET, v.coerceIn(0, 2)).apply()

    // [v1.0.42 Req5] Time-Gate(민감도 지연) 지연 시간 — 신규/격상 경보 전 최소 연속 접근시간(ms).
    //   BleService.APPROACH_TIMEGATE_MS 가 이 값을 매 프레임 라이브로 읽어 앱 재시작 없이 반영한다.
    //   기본 500L 은 기존 하드코딩값과 동일(거동 보존). 0=즉시통과 ~ 3000ms 범위로 clamp.
    private const val KEY_TIMEGATE_MS = "timegate_ms"
    const val DEFAULT_TIMEGATE_MS = 500L
    var timeGateMs: Long
        get() = prefs.getLong(KEY_TIMEGATE_MS, DEFAULT_TIMEGATE_MS).coerceIn(0L, 3000L)
        set(v) = prefs.edit().putLong(KEY_TIMEGATE_MS, v.coerceIn(0L, 3000L)).apply()

    // [v1.1.8] 고정값(1초 평균 고정) 모드 임계값(fixedDangerAbs/fixedWarningAbs) 전면 제거 — 칼만 단일화.

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

    // 비콘 수신 강도(%) — 등록된 모든 비콘에 공통으로 가산하는 dBm 보정으로 환산한다.
    //   100% = 0dBm(기존과 완전 동일), 10%당 2dBm. 200% = +20dBm(더 멀리), 0% = -20dBm(거의 차단).
    //   per-beacon rssiOffset 과 합산되어 BleService 의 totalOffset 에 반영(비콘만, offset 0 비콘 포함).
    const val DEFAULT_BEACON_GAIN_PERCENT = 100
    const val BEACON_GAIN_MIN = 0
    const val BEACON_GAIN_MAX = 300
    var beaconGainPercent: Int
        get() = prefs.getInt(KEY_BEACON_GAIN_PERCENT, DEFAULT_BEACON_GAIN_PERCENT).coerceIn(BEACON_GAIN_MIN, BEACON_GAIN_MAX)
        set(v) = prefs.edit().putInt(KEY_BEACON_GAIN_PERCENT, v.coerceIn(BEACON_GAIN_MIN, BEACON_GAIN_MAX)).apply()
    /** 비콘 수신 강도(%)를 공통 dBm 보정으로 환산: 100%→0, 200%→+20, 0%→-20 (10%당 2dBm). */
    val beaconGainDbm: Int
        get() = (beaconGainPercent - 100) / 5

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

    // [v1.1.21] 빠른 정면접근 Time-Gate 즉시통과 임계(dBm/s) — kfVel(칼만 접근속도)이 이 값 이상이면
    //   정면 돌진으로 간주해 Time-Gate 를 건너뛰고 즉시 발령(2프레임 연속 확증으로 단발 spike 방어).
    //   1바이트 페이로드엔 합산속도(km/h) 비트가 없어 headOnCourse 가 영구 false 였던 공백을 메운다.
    //   기본 2.0 = COLLISION_ABS_SAFE_VEL_DBM 과 동일한 '확실한 빠른 접근' 기준. 낮출수록 더 일찍 발령.
    private const val KEY_FAST_APPROACH_BYPASS_VEL = "fast_approach_bypass_vel_dbm"
    const val DEFAULT_FAST_APPROACH_BYPASS_VEL = 2.0
    var fastApproachBypassVelDbm: Double
        get() = prefs.getFloat(KEY_FAST_APPROACH_BYPASS_VEL, DEFAULT_FAST_APPROACH_BYPASS_VEL.toFloat())
                    .toDouble().coerceIn(0.5, 5.0)
        set(v) = prefs.edit().putFloat(KEY_FAST_APPROACH_BYPASS_VEL, v.coerceIn(0.5, 5.0).toFloat()).apply()

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
    // [v1.1.14] 교행 후 잔존(~1~2s) 단축 — 이탈 확인 후 완전 해제까지 2500→1500ms.
    //   위험권 가드(isReceding 의 distanceLevel<DANGER)는 불변 → '근접 무음' 회귀 없음.
    private const val KEY_RECEDING_CLEAR_MS = "receding_clear_ms"
    const val DEFAULT_RECEDING_CLEAR_MS = 1500L
    var recedingClearMs: Long
        get() = prefs.getLong(KEY_RECEDING_CLEAR_MS, DEFAULT_RECEDING_CLEAR_MS).coerceIn(500L, 10_000L)
        set(v) = prefs.edit().putLong(KEY_RECEDING_CLEAR_MS, v.coerceIn(500L, 10_000L)).apply()

    // [v1.1.14] 이탈 인정 하락폭 5→4dBm — 위험권을 벗어난 직후 더 빨리 이탈 인정(소리 멈춤).
    private const val KEY_RECEDING_DBM_DROP = "receding_dbm_drop"
    const val DEFAULT_RECEDING_DBM_DROP = 4
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

    // [v1.1.29] EMA 워밍업 대칭 푸시 수 — 기기별 첫 N개 푸시 동안 하강 알파를 상승 알파와 동일하게
    //   적용(대칭화). 앱 재시작 시 첫 표본 앵커가 우연히 높게 잡히면 하강α(0.05)의 느린 회복(약
    //   11.7초)이 세션 기준선을 물고 늘어지던 '재시작 편차'의 교정. 0=끄기(기존 동작).
    //   전단(rssiPreFilter)·후처리 P-EMA(pEmaFilter) 양쪽 공통 적용.
    private const val KEY_EMA_WARMUP_PUSHES = "ema_warmup_pushes"
    const val DEFAULT_EMA_WARMUP_PUSHES = 10
    var emaWarmupPushes: Int
        get() = prefs.getInt(KEY_EMA_WARMUP_PUSHES, DEFAULT_EMA_WARMUP_PUSHES).coerceIn(0, 30)
        set(v) = prefs.edit().putInt(KEY_EMA_WARMUP_PUSHES, v.coerceIn(0, 30)).apply()

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
    const val DEFAULT_SPEED_PUSH_INTERVAL_MS = 1000L   // [v1.1.18] 1500→1000 STATE 폴링 가속(개발자설정서 라이브 조절 가능, coerce 500~10000)
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

    // ===== [v1.1.10] 16진수(역할·상태) 적극 활용 — 페이로드 기반 경보 임계 비대칭 =====
    //   디코드된 CAT(역할)·STATE(상태) 비트로 경보 임계를 역할쌍·상태에 따라 비대칭 시프트한다.
    //   양(+) 오프셋 = 더 약한 신호(먼 거리)에서 조기 경보(fail-safe 방향). BleService 가 매 프레임
    //   같은 이름의 게터로 라이브로 읽어 앱 재시작 없이 반영(timeGateMs·reversePrep 선례).

    // [Phase1] 역할 비대칭 on/off — 보행자 ↔ 중장비(지게차/EPJ) 쌍은 더 일찍 경보(상호 보호)
    private const val KEY_CATEGORY_BIAS_ENABLED = "category_bias_enabled"
    var categoryBiasEnabled: Boolean
        get() = prefs.getBoolean(KEY_CATEGORY_BIAS_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_CATEGORY_BIAS_ENABLED, v).apply()

    // [Phase1] 보행자↔지게차 조기경보 오프셋(dB) — 경고·위험 임계를 이만큼 먼 거리로 당김. 0=비활성과 동일
    //   [v1.1.14] 의미 명확화: 이 값은 '보행자↔지게차' 전용. EPJ 는 아래 walkerVsEpjBiasDb 로 분리.
    private const val KEY_WALKER_VS_EQUIP_BIAS_DB = "walker_vs_equip_bias_db"
    const val DEFAULT_WALKER_VS_EQUIP_BIAS_DB = 6
    var walkerVsEquipBiasDb: Int
        get() = prefs.getInt(KEY_WALKER_VS_EQUIP_BIAS_DB, DEFAULT_WALKER_VS_EQUIP_BIAS_DB).coerceIn(0, 15)
        set(v) = prefs.edit().putInt(KEY_WALKER_VS_EQUIP_BIAS_DB, v.coerceIn(0, 15)).apply()

    // [Phase1/v1.1.14] 보행자↔EPJ 전용 조기경보 오프셋(dB) — 지게차와 분리(완화).
    //   EPJ(전동 파레트 잭)는 저속·동일 공간 협업이 많아 지게차 기준(+6)을 그대로 쓰면 과경보.
    //   기본 +2 로 더 가까이서만 울리게 한다. 0=중립(일반 임계와 동일). categoryBiasEnabled 토글 공유.
    private const val KEY_WALKER_VS_EPJ_BIAS_DB = "walker_vs_epj_bias_db"
    const val DEFAULT_WALKER_VS_EPJ_BIAS_DB = 2
    var walkerVsEpjBiasDb: Int
        get() = prefs.getInt(KEY_WALKER_VS_EPJ_BIAS_DB, DEFAULT_WALKER_VS_EPJ_BIAS_DB).coerceIn(0, 15)
        set(v) = prefs.edit().putInt(KEY_WALKER_VS_EPJ_BIAS_DB, v.coerceIn(0, 15)).apply()

    // [v1.1.24] 장비↔장비(지게차·EPJ 상호) 조기경보 오프셋(dB) — 보행자 오프셋(+6/+2)이 모두 보행자 전용이라
    //   지게차끼리·EPJ끼리·지게차↔EPJ 쌍은 오프셋 0이었음(사각지대). 금속 캐빈 차폐로 RSSI가 약해지면
    //   effWarning 부근을 배회 → 간헐/무음. 이 오프셋으로 장비쌍에도 조기경보를 부여한다. 0=중립(일반 임계).
    //   기본 +8(시뮬: 강차폐 -78~-82 신뢰 포착, 먼 -88 과경보 1/20 이하). categoryBiasEnabled 토글 공유.
    private const val KEY_EQUIP_VS_EQUIP_BIAS_DB = "equip_vs_equip_bias_db"
    const val DEFAULT_EQUIP_VS_EQUIP_BIAS_DB = 8
    var equipVsEquipBiasDb: Int
        get() = prefs.getInt(KEY_EQUIP_VS_EQUIP_BIAS_DB, DEFAULT_EQUIP_VS_EQUIP_BIAS_DB).coerceIn(0, 15)
        set(v) = prefs.edit().putInt(KEY_EQUIP_VS_EQUIP_BIAS_DB, v.coerceIn(0, 15)).apply()

    // [v1.1.25] EPJ↔EPJ 전용 오프셋(dB) — 장비↔장비(equipVsEquipBiasDb=+8)에서 EPJ끼리만 분리.
    //   EPJ 는 금속 캐빈이 없어 차폐가 약하고 3km/h 저속이라 5m 공존이 정상 → +8(지게차 강차폐 보정)을
    //   그대로 쓰면 5m·8m 에서도 과경보. 거리 변별(3m 발령/5m 무음)을 위해 0 근처·음수까지 허용한다.
    //   음수 = effWarning 을 더 강한 신호(가까운 거리)로 당겨 3m 진입 시에만 발령(시뮬: 개활 -7/표준 -3 → 약차폐 EPJ 기본 -2).
    //   지게차가 한쪽이라도 끼면 기존 equipVsEquipBiasDb(강차폐·위험원 보수적) 유지. categoryBiasEnabled 토글 공유.
    private const val KEY_EPJ_VS_EPJ_BIAS_DB = "epj_vs_epj_bias_db"
    const val DEFAULT_EPJ_VS_EPJ_BIAS_DB = -2
    var epjVsEpjBiasDb: Int
        get() = prefs.getInt(KEY_EPJ_VS_EPJ_BIAS_DB, DEFAULT_EPJ_VS_EPJ_BIAS_DB).coerceIn(-10, 15)
        set(v) = prefs.edit().putInt(KEY_EPJ_VS_EPJ_BIAS_DB, v.coerceIn(-10, 15)).apply()

    // [Phase2] 상태 기반 가감 on/off — 상대 FORWARD(전진) 접근 시 추가 조기경보, IDLE-IDLE 가청 억제
    private const val KEY_STATE_MODULATION_ENABLED = "state_modulation_enabled"
    var stateModulationEnabled: Boolean
        get() = prefs.getBoolean(KEY_STATE_MODULATION_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_STATE_MODULATION_ENABLED, v).apply()

    // [Phase2] 상대 전진(FORWARD)+접근 시 추가 조기경보 오프셋(dB). categoryBias 와 합산. 0=비활성과 동일
    private const val KEY_FORWARD_APPROACH_BIAS_DB = "forward_approach_bias_db"
    const val DEFAULT_FORWARD_APPROACH_BIAS_DB = 3
    var forwardApproachBiasDb: Int
        get() = prefs.getInt(KEY_FORWARD_APPROACH_BIAS_DB, DEFAULT_FORWARD_APPROACH_BIAS_DB).coerceIn(0, 12)
        set(v) = prefs.edit().putInt(KEY_FORWARD_APPROACH_BIAS_DB, v.coerceIn(0, 12)).apply()

    // [Phase2] IDLE-IDLE 가청 억제 — 내 IMU 정지 + 상대 IDLE 송신이면 WARNING 가청경보를 억제(표시만).
    //   안전상 기본 OFF(옵트인). 켜도 DANGER 는 절대 억제하지 않고, 둘 중 하나라도 움직이면 즉시 해제.
    private const val KEY_IDLE_IDLE_SUPPRESS_ENABLED = "idle_idle_suppress_enabled"
    var idleIdleSuppressEnabled: Boolean
        get() = prefs.getBoolean(KEY_IDLE_IDLE_SUPPRESS_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_IDLE_IDLE_SUPPRESS_ENABLED, v).apply()

    // ── [v1.1.26] 백그라운드 콜드스타트 지연 해소 ─────────────────────────────
    //   증상: "자다 깨어 정신 못 차리는" 느낌 — 한 번 인식되면 잘 되는데 첫 깨어남이 느림.
    //   시속 6km 지게차 2대가 마주 와도(closing 3.33m/s) 첫 알람이 늦음.
    // [A] 이동 중(IMU 비정지)에는 광고를 슬립(LOW_POWER ~1s)시키지 않고 유지 → 첫 접촉 즉시 송신.
    //   콜드스타트 사슬의 최대 레버(시뮬 sa_wakeup_burst_sim: 알람거리 +3.35m, 지연 ≈절반).
    //   정지 5초 후 evaluateAdvertiserPower 가 평소대로 다시 슬립(배터리 영향 최소).
    private const val KEY_KEEP_ADV_WHILE_MOVING = "keep_adv_while_moving"
    var keepAdvertiseWhileMoving: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ADV_WHILE_MOVING, true)
        set(v) = prefs.edit().putBoolean(KEY_KEEP_ADV_WHILE_MOVING, v).apply()

    // [B] 상대 근접 신호(rssi≥WAKE) 수신 시 내 광고를 LOW_LATENCY(100ms) 버스트로 가속 →
    //   상대가 나를 더 빨리 발견(상호 보호). 근접 지속 동안 hold 만큼 연장, 멀어지면 정상 복귀.
    //   시뮬 sa_burst_param_sweep: 트리거=WAKE(-89, 경고권보다 먼저 도달해 게이트 선예열),
    //   간격=100ms(LOW_LATENCY 내재), hold=1500ms(1000~3000 동등·미발령 0).
    private const val KEY_BURST_ENABLED = "burst_enabled"
    var burstEnabled: Boolean
        get() = prefs.getBoolean(KEY_BURST_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_BURST_ENABLED, v).apply()

    private const val KEY_BURST_HOLD_MS = "burst_hold_ms"
    const val DEFAULT_BURST_HOLD_MS = 1500L
    var burstHoldMs: Long
        get() = prefs.getLong(KEY_BURST_HOLD_MS, DEFAULT_BURST_HOLD_MS).coerceIn(500L, 5000L)
        set(v) = prefs.edit().putLong(KEY_BURST_HOLD_MS, v.coerceIn(500L, 5000L)).apply()

    // (v1.1.30) UWB 정밀 거리 측정 — 지원 기기끼리 UWB 세션으로 실거리(m)를 측정(기본 ON).
    //   OFF 또는 미지원이면 기존 BLE RSSI 경로 그대로(경보 로직 무접촉).
    private const val KEY_UWB_ENABLED = "uwb_enabled"
    var uwbEnabled: Boolean
        get() = prefs.getBoolean(KEY_UWB_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_UWB_ENABLED, v).apply()

    // (v1.1.38 B) UWB 강제 활성화 — HW·권한·시스템 토글이 충족된 상태에서 RSSI 시작 게이트(거리 문턱)
    //   와 uwbEnabled 토글을 우회해 UWB 세션을 무조건 띄운다. HW 미지원·권한 거부·시스템 OFF 는
    //   물리적으로 우회 불가(강제해도 세션을 못 연다). 기본 OFF — 디버그/현장 검증용.
    private const val KEY_UWB_FORCE = "uwb_force"
    var uwbForce: Boolean
        get() = prefs.getBoolean(KEY_UWB_FORCE, false)
        set(v) = prefs.edit().putBoolean(KEY_UWB_FORCE, v).apply()

    // (v1.1.31) UWB 델타 보정 학습 — UWB 실거리로 페어별 RSSI 편차(Δ)를 학습해 경보 임계에
    //   보정(조기 +10dB / 지연 −3dB 비대칭 클램프 + 24h 감쇠)으로 반영. OFF = 보정 항상 0(기본 ON).
    private const val KEY_UWB_CALIB_ENABLED = "uwb_calib_enabled"
    var uwbCalibEnabled: Boolean
        get() = prefs.getBoolean(KEY_UWB_CALIB_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_UWB_CALIB_ENABLED, v).apply()

    // (v1.1.31) 거리 표시 방식 — 감지 목록·플로팅 위젯의 신호 표기.
    //   0 = dBm만 / 1 = UWB 실측 페어만 미터 / 2 = 전부 미터(비UWB 는 RSSI 역산 '약 X m', 기본).
    //   경보 임계 슬라이더는 dBm 그대로 — 표시 전용 설정이라 경보 로직에 영향 없음.
    private const val KEY_DISTANCE_DISPLAY_MODE = "distance_display_mode"
    var distanceDisplayMode: Int
        get() = prefs.getInt(KEY_DISTANCE_DISPLAY_MODE, 2).coerceIn(0, 2)
        set(v) = prefs.edit().putInt(KEY_DISTANCE_DISPLAY_MODE, v.coerceIn(0, 2)).apply()

    // (v1.1.36) UWB 주 경보 권위 — 활성 UWB 세션이 있는 페어는 UWB 실측 거리로 경보 레벨(안전/경고/
    //   위험)을 '직접' 판정하고, 접근속도 승격·이탈(경고 반경 밖=해제)까지 UWB 기준으로 따른다.
    //   promote-only 예외 — 올림뿐 아니라 내림도 허용(사용자 요청 "UWB 폰끼리는 UWB 로 측정").
    //   UWB 세션이 없거나 끊긴 페어는 RSSI 파이프라인으로 폴백(무봉합 — 세션 드랍 시 그 프레임부터 즉시
    //   RSSI 판정 복귀). 역할쌍 차등 임계(지게차 낀 쌍=uwbForkliftWarn/Danger, 그 외=uwbPairWarn/Danger)
    //   와 접근속도 임계(uwbApproachSpeedKmh)를 재사용한다.
    //   ★ RSSI 거리 보정(UwbCalibrator)은 이 스위치와 무관하게 항상 가동 — 활성 세션의 UWB 실측을
    //     기준으로 RSSI 신호세기 편차를 누적 학습(offsetDbFor)해, 세션이 끊긴 뒤의 RSSI 폴백을 더
    //     정확하게 만든다. 즉 세션 중엔 UWB 가 판정하면서 동시에 RSSI 를 교정해 둔다.
    //   기본 ON — 사용자 요청. 끄면 RSSI 주도 + 아래 promote-only(옵트인)로 복귀.
    private const val KEY_UWB_PRIMARY_AUTHORITY_ENABLED = "uwb_primary_authority_enabled"
    var uwbPrimaryAuthorityEnabled: Boolean
        get() = prefs.getBoolean(KEY_UWB_PRIMARY_AUTHORITY_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_UWB_PRIMARY_AUTHORITY_ENABLED, v).apply()

    // (v1.1.32) UWB 위험 승격(promote-only) — UWB 실측거리가 승격 반경 이하면 해당 페어의 경보를
    //   DANGER 로 '승격만' 한다(억제·격하 방향 개입은 코드 경로 자체가 없음 — 안전불변식).
    //   RSSI 파이프라인은 전 페어 상시 가동 — UWB 세션이 없거나 끊긴 기기는 이 기능이 없던 것과 동일.
    //   신규 개입이므로 안전상 기본 OFF(옵트인) — BLE 감지 설정의 UWB 카드 토글.
    private const val KEY_UWB_PROMOTE_ENABLED = "uwb_promote_enabled"
    var uwbPromoteEnabled: Boolean
        get() = prefs.getBoolean(KEY_UWB_PROMOTE_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_UWB_PROMOTE_ENABLED, v).apply()

    // (v1.1.33) UWB 승격 임계(m) — 역할쌍 차등. v1.1.32 의 단일 3m(uwb_danger_meters)는 지게차
    //   제동거리 기준 이미 충돌권이라 폐기(구 키는 미참조 방치 — 마이그레이션 불필요).
    //   지게차가 한쪽이라도 낀 쌍 = 15m 경고 / 8m 위험, 그 외(EPJ↔보행자 등) = 5m 경고 / 3m 위험.
    //   UI 미노출(DevSettings 키만). 경고<위험으로 역설정해도 promote-only 라 위험 분기가 우선(무해).
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

    // (v1.1.32) UWB 세션 시작 RSSI 게이트(dBm) — BLE 평활 RSSI 가 이 값 이상인 후보만 레인징
    //   세션을 시작(배터리 듀티사이클). 원거리·미추적 페어는 세션을 쉬고, 접근하면 스캔 응답마다
    //   재평가돼 자연 개시. 경보(BLE RSSI)는 세션 유무와 무관하게 상시 가동. UI 미노출(기본 -80).
    private const val KEY_UWB_START_RSSI_GATE = "uwb_start_rssi_gate"
    const val DEFAULT_UWB_START_RSSI_GATE = -80
    var uwbStartRssiGate: Int
        get() = prefs.getInt(KEY_UWB_START_RSSI_GATE, DEFAULT_UWB_START_RSSI_GATE).coerceIn(-100, -50)
        set(v) = prefs.edit().putInt(KEY_UWB_START_RSSI_GATE, v.coerceIn(-100, -50)).apply()

    // (v1.1.33) 지게차 낀 쌍 전용 시작 게이트(dBm) — 기본 게이트(-80 ≈ 수 m~10m권)로는 지게차의
    //   15m 경고 반경 밖에서 UWB 세션이 안 열려 승격이 무력화된다. 지게차 쌍만 -90 까지 완화해
    //   경고 반경 밖에서도 레인징을 개시(배터리 듀티사이클은 비지게차 쌍에서 그대로 유효).
    private const val KEY_UWB_START_RSSI_GATE_FORKLIFT = "uwb_start_rssi_gate_forklift"
    const val DEFAULT_UWB_START_RSSI_GATE_FORKLIFT = -90
    var uwbStartRssiGateForklift: Int
        get() = prefs.getInt(KEY_UWB_START_RSSI_GATE_FORKLIFT, DEFAULT_UWB_START_RSSI_GATE_FORKLIFT).coerceIn(-100, -50)
        set(v) = prefs.edit().putInt(KEY_UWB_START_RSSI_GATE_FORKLIFT, v.coerceIn(-100, -50)).apply()

    // (v1.1.34) UWB 접근속도 승격 — UWB 실측 접근속도가 임계(기본 6km/h) 이상 2샘플 지속이면
    //   해당 페어를 최소 WARNING 으로 조기 승격. 승격만 — 격하 경로 없음. 신규 개입이라 기본 OFF(옵트인).
    private const val KEY_UWB_VEL_PROMOTE_ENABLED = "uwb_vel_promote_enabled"
    var uwbVelPromoteEnabled: Boolean
        get() = prefs.getBoolean(KEY_UWB_VEL_PROMOTE_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_UWB_VEL_PROMOTE_ENABLED, v).apply()

    // (v1.1.34) UWB 이탈 해제 — UWB 실측 3샘플 연속 이탈 페어의 경보를 실측 거리 기준으로 하향
    //   (경고 반경 밖=SAFE 해제, 경고대=WARNING 캡). '멀어질 때 알림 꺼짐' 사용자 명시 요청 —
    //   promote-only 불변식의 최초 승인 예외. 위험 반경 안·RSSI 강접근·세션 없음이면 개입하지
    //   않으며, 신규 개입이라 기본 OFF(옵트인).
    private const val KEY_UWB_VEL_RELEASE_ENABLED = "uwb_vel_release_enabled"
    var uwbVelReleaseEnabled: Boolean
        get() = prefs.getBoolean(KEY_UWB_VEL_RELEASE_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_UWB_VEL_RELEASE_ENABLED, v).apply()

    // (v1.1.34) UWB 접근속도 임계(km/h) — 사업장 지게차 제한속도 기준(기본 6). UI 미노출(키만).
    private const val KEY_UWB_APPROACH_SPEED_KMH = "uwb_approach_speed_kmh"
    const val DEFAULT_UWB_APPROACH_SPEED_KMH = 6.0f
    var uwbApproachSpeedKmh: Float
        get() = prefs.getFloat(KEY_UWB_APPROACH_SPEED_KMH, DEFAULT_UWB_APPROACH_SPEED_KMH).coerceIn(1f, 30f)
        set(v) = prefs.edit().putFloat(KEY_UWB_APPROACH_SPEED_KMH, v.coerceIn(1f, 30f)).apply()

    // (v1.1.34) 사업장 코드 — UWB Δ보정 학습 프로파일 네임스페이스 키(예: "WF11"). 빈 값=공용
    //   (현행과 완전 동일). 변경 즉시 UwbCalibrator.applySite 가 현재 프로파일을 저장하고 해당
    //   사업장 프로파일로 전환한다(각 사업장 학습 보존 — 지워지지 않음).
    private const val KEY_UWB_SITE_CODE = "uwb_site_code"
    var uwbSiteCode: String
        get() = prefs.getString(KEY_UWB_SITE_CODE, "")?.trim() ?: ""
        set(v) = prefs.edit().putString(KEY_UWB_SITE_CODE, v.trim()).apply()

    // (v1.1.40) 섀도우 IMU 융합 — 정지(IMU)+상대 FORWARD 페이로드일 때 median 스트림 전용 섀도우
    //   칼만으로 접근을 병렬 추적, DANGER 이탈 프레임의 EMA 하강 알파 부스트(0.4)와 TTC 예비
    //   후보를 제공. 끄면(false) 섀도우 경로 전체 우회 = 기존 거동과 완전 동일.
    private const val KEY_IMU_SHADOW_FUSION = "imu_shadow_fusion_enabled"
    var imuShadowFusionEnabled: Boolean
        get() = prefs.getBoolean(KEY_IMU_SHADOW_FUSION, true)
        set(v) = prefs.edit().putBoolean(KEY_IMU_SHADOW_FUSION, v).apply()

    fun toDebugString(): String =
        "rssiWarning=$rssiWarning | rssiDanger=$rssiDanger | scanPeriod=${scanPeriodMs}ms | " +
        "advertise=${advertiseInterval}ms | vib=$vibrationEnabled | sound=$soundEnabled | " +
        "fbRoot=$firebaseRoot | debug=$debugMode | simRssi=$simulatedRssi"
}
