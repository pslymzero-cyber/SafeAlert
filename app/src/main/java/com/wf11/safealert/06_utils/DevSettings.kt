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

    // 알람 볼륨 게인 (0-100%)
    private const val KEY_ALARM_VOLUME = "alarm_volume"

    // BLE 거리 교정: 내 폰의 1m 기준 RSSI (기본 -65, 최신 플래그십은 -45~-55)
    private const val KEY_CALIB_RSSI = "calib_rssi_at_1m"

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

    // BLE 거리 설정 — 미터 단위 저장, RSSI는 교정값으로 자동 계산
    private const val KEY_WARNING_DIST = "warning_dist_m"
    private const val KEY_DANGER_DIST  = "danger_dist_m"

    var warningDistM: Float
        get() = prefs.getFloat(KEY_WARNING_DIST, 15f)   // 기본 15m
        set(v) = prefs.edit().putFloat(KEY_WARNING_DIST, v.coerceIn(1f, 50f)).apply()

    var dangerDistM: Float
        get() = prefs.getFloat(KEY_DANGER_DIST, 5f)    // 기본 5m
        set(v) = prefs.edit().putFloat(KEY_DANGER_DIST, v.coerceIn(1f, 50f)).apply()

    // 거리 → RSSI 변환 (교정값 반영) — 교정이 달라져도 "5m는 항상 5m"
    // rssi = calibRssiAt1m - 20 * log10(distance_m)
    val rssiWarning: Int
        get() {
            val dist = warningDistM.toDouble().coerceAtLeast(0.5)
            return (calibRssiAt1m.toDouble() - 20.0 * Math.log10(dist)).toInt()
        }

    val rssiDanger: Int
        get() {
            val dist = dangerDistM.toDouble().coerceAtLeast(0.5)
            return (calibRssiAt1m.toDouble() - 20.0 * Math.log10(dist)).toInt()
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

    // 거리 교정값 (-90 ~ -30, 기본 -65)
    var calibRssiAt1m: Int
        get() = prefs.getInt(KEY_CALIB_RSSI, -65)
        set(v) = prefs.edit().putInt(KEY_CALIB_RSSI, v.coerceIn(-90, -30)).apply()

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
