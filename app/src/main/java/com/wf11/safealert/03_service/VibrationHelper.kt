package com.wf11.safealert.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object VibrationHelper {

    private const val TAG = "VibrationHelper"

    private fun vibrator(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }.getOrNull()

    /** 경고 패턴: 중간 진동 2회 (탐∙탐) — "주의" 느낌 */
    fun vibrateWarning(context: Context) {
        vibe(context, longArrayOf(0, 400, 200, 400), intArrayOf(0, 200, 0, 200))
        Log.d(TAG, "경고 진동")
    }

    /** 위험 패턴: 짧고 강한 3연타 (탕탕탕) — "즉시 멈춰!" 느낌 */
    fun vibrateDanger(context: Context) {
        vibe(context, longArrayOf(0, 150, 100, 150, 100, 150), intArrayOf(0, 255, 0, 255, 0, 255))
        Log.d(TAG, "위험 진동")
    }

    /** 급접근 패턴: 빠른 연속 버즈 — "위험 접근 중!" 느낌 */
    fun vibrateRapidApproach(context: Context) {
        vibe(context, longArrayOf(0, 100, 80, 100, 80, 100, 80, 200), intArrayOf(0, 255, 0, 255, 0, 255, 0, 255))
        Log.d(TAG, "급접근 진동")
    }

    /** 단순 1회 진동 (기존 호환) */
    fun vibrateOnce(context: Context, duration: Long = 500L) {
        val vib = vibrator(context) ?: return
        if (!vib.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vib.vibrate(VibrationEffect.createOneShot(duration, 255))
            else @Suppress("DEPRECATION") vib.vibrate(duration)
        }
    }

    /** N회 반복 진동 (기존 호환) */
    fun vibrateRepeat(context: Context, count: Int = 3) {
        val pattern = mutableListOf(0L)
        repeat(count) { pattern.add(300L); pattern.add(200L) }
        val amps = IntArray(pattern.size) { i -> if (i % 2 == 0) 0 else 255 }
        vibe(context, pattern.toLongArray(), amps)
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
