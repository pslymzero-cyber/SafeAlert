package com.wf11.safealert.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.wf11.safealert.utils.DevSettings

object VibrationHelper {

    private const val TAG = "VibrationHelper"

    /** Alarm loop vibration: 0.7 s on, 0.3 s off. The vibration window record uses the same values. */
    const val ALARM_ON_MS = 700L
    const val ALARM_PERIOD_MS = 1_000L
    private const val RESUME_PAD_MS = 50L

    /** This app's vibration windows. LoneWorkerMonitor uses them to filter sensor samples. */
    val window = VibrationWindow()

    // The vibrator service cancels only vibrations started with the same caller token. Tokens are per Context, so always obtain it
    // via applicationContext so that start, pause, resume and cancel all use the same instance.
    internal fun vibrator(context: Context): Vibrator? = runCatching {
        val app = context.applicationContext ?: context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }.onFailure { Log.w(TAG, "Vibrator 획득 실패: ${it.message}") }.getOrNull()

    /**
     * Warning pattern: 2 medium-strength pulses; pulse length from developer settings (default 500ms, options 300/500/1000ms).
     * Corrupted values are clamped to 100~1000ms.
     */
    fun vibrateWarning(context: Context) {
        val pulse = DevSettings.vibrationWarningMs.coerceIn(100L, 1000L)
        vibe(context, longArrayOf(0, pulse, 200, pulse), intArrayOf(0, 200, 0, 200))
        Log.d(TAG, "경고 진동")
    }

    /**
     * Danger pattern: strong 150ms pulses, as many as the developer setting (default 3, options 1/3/5), 100ms apart.
     * Corrupted values are clamped to 1~5.
     */
    fun vibrateDanger(context: Context) {
        val n = DevSettings.vibrationDangerCount.coerceIn(1, 5)
        val pattern = LongArray(2 * n) { i -> if (i == 0) 0L else if (i % 2 == 1) 150L else 100L }
        val amplitudes = IntArray(2 * n) { i -> if (i % 2 == 1) 255 else 0 }
        vibe(context, pattern, amplitudes)
        Log.d(TAG, "위험 진동")
    }

    /** Fast-approach pattern: rapid consecutive buzzes — an approaching-danger feel */
    fun vibrateRapidApproach(context: Context) {
        vibe(context, longArrayOf(0, 100, 80, 100, 80, 100, 80, 200), intArrayOf(0, 255, 0, 255, 0, 255, 0, 255))
        Log.d(TAG, "급접근 진동")
    }

    // Pauses the alarm loop vibration during a collision vibration. All calls on the main thread.
    // AOSP ignores a lower-importance one-shot vibration while a repeating alarm vibration runs, so a collision vibration pauses the loop,
    // plays, and restarts the loop when it ends.
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var loopCtx: Context? = null   // Non-null = alarm loop vibration requested
    private var oneShotEnd = 0L             // End time (elapsedRealtime) of the ongoing one-shot vibration, 0 if none
    private val resume = Runnable {
        oneShotEnd = 0L
        loopCtx?.let { startLoop(it) }
    }

    private fun alarmAttrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Lone-worker check/SOS loop vibration (0.7 s on, 0.3 s off, alarm usage). Repeats until stopAlarmLoop. */
    fun vibrateAlarmLoop(context: Context) {
        loopCtx = context.applicationContext
        val left = oneShotEnd - SystemClock.elapsedRealtime()
        if (left > 0) {   // Collision vibration running: the 5 s refresh won't cut it; resume after it ends
            handler.removeCallbacks(resume)
            handler.postDelayed(resume, left + RESUME_PAD_MS)
            return
        }
        startLoop(context)
    }

    @Suppress("DEPRECATION")
    private fun startLoop(context: Context) {
        runCatching {
            val vib = vibrator(context) ?: return
            if (!vib.hasVibrator()) return   // Phones without a vibrator record no window (masks nothing)
            window.loopStart(SystemClock.elapsedRealtime(), ALARM_ON_MS, ALARM_PERIOD_MS)
            vib.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, ALARM_ON_MS, ALARM_PERIOD_MS - ALARM_ON_MS), 0),
                alarmAttrs()
            )
        }
    }

    /** Stop for the lone-worker alarm only. A collision vibration in progress is not cut. */
    fun stopAlarmLoop(context: Context) {
        loopCtx = null
        handler.removeCallbacks(resume)
        val now = SystemClock.elapsedRealtime()
        window.loopStop(now)
        if (oneShotEnd <= now) runCatching { vibrator(context)?.cancel() }
    }

    /**
     * Vibration stop for the collision alert path. If the alarm loop is requested,
     * restart it at once (so a collision stop never cuts the alarm vibration).
     */
    fun stopVibration(context: Context) {
        val now = SystemClock.elapsedRealtime()
        // If there is no collision vibration to cut, leave the alarm loop alone.
        if (loopCtx != null && oneShotEnd <= now) return
        handler.removeCallbacks(resume)
        window.cut(now)
        oneShotEnd = 0L
        runCatching { vibrator(context)?.cancel() }
        loopCtx?.let { startLoop(it) }
    }

    @Suppress("DEPRECATION")
    private fun vibe(context: Context, pattern: LongArray, amplitudes: IntArray) {
        val vib = vibrator(context) ?: return
        if (!vib.hasVibrator()) return
        val now = SystemClock.elapsedRealtime()
        val dur = pattern.sum()
        val loop = loopCtx != null
        if (loop) {
            handler.removeCallbacks(resume)
            runCatching { vib.cancel() }
            window.loopStop(now)
        }
        window.oneShot(now, dur)
        oneShotEnd = now + dur
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val fx = VibrationEffect.createWaveform(pattern, amplitudes, -1)
                // While the alarm loop is requested, vibrate with the same importance (ALARM) so it is not displaced in a restart race.
                if (loop) vib.vibrate(fx, alarmAttrs()) else vib.vibrate(fx)
            } else vib.vibrate(pattern, -1)
        }.onFailure { Log.e(TAG, "진동 실패: ${it.message}") }
        if (loop) handler.postDelayed(resume, dur + RESUME_PAD_MS)
    }
}
