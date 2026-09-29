package com.wf11.safealert.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.wf11.safealert.utils.DevSettings

object VibrationHelper {

    private const val TAG = "VibrationHelper"

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

    fun stopVibration(context: Context) {
        runCatching { vibrator(context)?.cancel() }
    }

    private fun vibe(context: Context, pattern: LongArray, amplitudes: IntArray) {
        val vib = vibrator(context) ?: return
        if (!vib.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vib.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
            else @Suppress("DEPRECATION") vib.vibrate(pattern, -1)
        }.onFailure { Log.e(TAG, "진동 실패: ${it.message}") }
    }
}
