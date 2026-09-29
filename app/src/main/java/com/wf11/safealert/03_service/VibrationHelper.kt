package com.wf11.safealert.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.wf11.safealert.utils.DevSettings

/**
 * 이 앱이 건 진동 구간(v1.1.99). 진동 모터의 흔들림을 가속도 센서가 움직임으로 읽지 않도록,
 * 시작~끝+200ms 에 든 센서 표본을 무동작 판정에서 뺀다. 순수 클래스(안드로이드 무관).
 * 다른 앱이 건 진동은 볼 수 없다(한계). 센서 이벤트 시각이 elapsedRealtime 기준이라고 가정한다.
 */
class VibrationWindow {
    companion object { const val GRACE_MS = 200L }

    private var start = Long.MAX_VALUE // 기록 없음: 아무 시각도 덮지 않는다
    private var end = 0L               // Long.MAX_VALUE 면 반복(끝 미정)

    /** durMs 가 음수면 반복 진동. 이전 구간이 아직 덮고 있으면 시작 시각은 유지한다. */
    @Synchronized fun onStart(nowMs: Long, durMs: Long) {
        if (!covers(nowMs)) start = nowMs
        end = if (durMs < 0) Long.MAX_VALUE else nowMs + durMs
    }

    @Synchronized fun onStop(nowMs: Long) {
        if (start != Long.MAX_VALUE) end = minOf(end, nowMs)
    }

    @Synchronized fun covers(tMs: Long): Boolean =
        tMs >= start && (end == Long.MAX_VALUE || tMs <= end + GRACE_MS)
}

object VibrationHelper {

    private const val TAG = "VibrationHelper"

    /** 이 앱의 진동 구간. LoneWorkerMonitor 가 센서 표본을 거를 때 쓴다. */
    val window = VibrationWindow()

    internal fun vibrator(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }.onFailure { Log.w(TAG, "Vibrator 획득 실패: ${it.message}") }.getOrNull()

    /** 경고 패턴: 중간 세기 2회, 펄스 길이는 개발자 설정(기본 500ms, 선택지 300/500/1000ms).
     * 손상 값은 100~1000ms 로 제한. */
    fun vibrateWarning(context: Context) {
        val pulse = DevSettings.vibrationWarningMs.coerceIn(100L, 1000L)
        vibe(context, longArrayOf(0, pulse, 200, pulse), intArrayOf(0, 200, 0, 200))
        Log.d(TAG, "경고 진동")
    }

    /** 위험 패턴: 150ms 강한 펄스를 개발자 설정 횟수(기본 3, 선택지 1/3/5)만큼, 펄스 사이 100ms.
     * 손상 값은 1~5회로 제한. */
    fun vibrateDanger(context: Context) {
        val n = DevSettings.vibrationDangerCount.coerceIn(1, 5)
        val pattern = LongArray(2 * n) { i -> if (i == 0) 0L else if (i % 2 == 1) 150L else 100L }
        val amplitudes = IntArray(2 * n) { i -> if (i % 2 == 1) 255 else 0 }
        vibe(context, pattern, amplitudes)
        Log.d(TAG, "위험 진동")
    }

    /** 급접근 패턴: 빠른 연속 버즈 — "위험 접근 중!" 느낌 */
    fun vibrateRapidApproach(context: Context) {
        vibe(context, longArrayOf(0, 100, 80, 100, 80, 100, 80, 200), intArrayOf(0, 255, 0, 255, 0, 255, 0, 255))
        Log.d(TAG, "급접근 진동")
    }

    /** 단독 작업자 확인·구조 요청 반복 진동(0.7초 켬·0.3초 끔, 알람 용도). 끌 때까지 반복. */
    @Suppress("DEPRECATION")
    fun vibrateAlarmLoop(context: Context) {
        runCatching {
            val vib = vibrator(context) ?: return
            window.onStart(SystemClock.elapsedRealtime(), -1L)
            vib.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 700, 300), 0),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
    }

    fun stopVibration(context: Context) {
        window.onStop(SystemClock.elapsedRealtime())
        runCatching { vibrator(context)?.cancel() }
    }

    private fun vibe(context: Context, pattern: LongArray, amplitudes: IntArray) {
        val vib = vibrator(context) ?: return
        if (!vib.hasVibrator()) return
        window.onStart(SystemClock.elapsedRealtime(), pattern.sum())
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vib.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
            else @Suppress("DEPRECATION") vib.vibrate(pattern, -1)
        }.onFailure { Log.e(TAG, "진동 실패: ${it.message}") }
    }
}
