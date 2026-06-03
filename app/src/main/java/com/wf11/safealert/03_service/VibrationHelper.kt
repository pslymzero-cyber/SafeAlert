package com.wf11.safealert.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object VibrationHelper {

    private const val TAG = "VibrationHelper"

    private fun vibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        } catch (e: Exception) {
            Log.e(TAG, "진동기 획득 실패: ${e.message}")
            null
        }
    }

    fun vibrateOnce(context: Context, duration: Long = 500L) {
        val vib = vibrator(context) ?: return
        if (!vib.hasVibrator()) { Log.w(TAG, "진동 모터 없음"); return }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // DEFAULT_AMPLITUDE(-1) 대신 최대 진폭(255) 명시
                vib.vibrate(VibrationEffect.createOneShot(duration, 255))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(duration)
            }
            Log.d(TAG, "진동 1회 ($duration ms)")
        } catch (e: Exception) {
            Log.e(TAG, "vibrateOnce 실패: ${e.message}")
        }
    }

    fun vibrateRepeat(context: Context, count: Int = 3) {
        val vib = vibrator(context) ?: return
        if (!vib.hasVibrator()) { Log.w(TAG, "진동 모터 없음"); return }
        try {
            // 패턴: [대기, 진동, 정지, 진동, 정지, ...]
            val delays = mutableListOf<Long>()
            delays.add(0L) // 첫 대기 없음
            repeat(count) {
                delays.add(300L) // 진동
                delays.add(200L) // 정지
            }
            val pattern = delays.toLongArray()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 각 구간 진폭: 대기=0, 진동=255, 정지=0 ...
                val amplitudes = IntArray(pattern.size) { i ->
                    if (i == 0 || i % 2 == 0) 0 else 255
                }
                vib.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, -1)
            }
            Log.d(TAG, "진동 ${count}회 반복")
        } catch (e: Exception) {
            Log.e(TAG, "vibrateRepeat 실패: ${e.message}")
        }
    }

    fun stopVibration(context: Context) {
        try { vibrator(context)?.cancel() } catch (e: Exception) {
            Log.e(TAG, "진동 중지 실패: ${e.message}")
        }
    }
}
