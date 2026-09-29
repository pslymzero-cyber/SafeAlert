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

    /** 알람 반복 진동: 0.7초 켬·0.3초 끔. 진동 구간 기록(window)도 같은 값을 쓴다. */
    const val ALARM_ON_MS = 700L
    const val ALARM_PERIOD_MS = 1_000L
    private const val RESUME_PAD_MS = 50L

    /** 이 앱의 진동 구간. LoneWorkerMonitor 가 센서 표본을 거를 때 쓴다. */
    val window = VibrationWindow()

    // 진동 서비스는 같은 호출자 토큰으로 건 진동만 취소한다. 토큰은 Context 마다 따로 생기므로
    // 걸기·일시정지·재개·취소가 모두 같은 인스턴스를 쓰도록 항상 applicationContext 로 얻는다.
    internal fun vibrator(context: Context): Vibrator? = runCatching {
        val app = context.applicationContext ?: context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
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

    // 충돌 진동 중 알람 반복 진동 일시정지 (v1.1.99, RR04). 모든 호출은 메인 스레드.
    // AOSP 는 반복 알람 진동이 도는 동안 더 낮은 중요도의 한 번 진동을 무시하므로, 충돌 진동은 반복을 잠시 멈추고
    // 재생한 뒤 끝나면 반복을 다시 건다.
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var loopCtx: Context? = null   // null 이 아니면 알람 반복 진동이 요청된 상태
    private var oneShotEnd = 0L             // 진행 중인 한 번 진동의 끝 시각(elapsedRealtime), 없으면 0
    private val resume = Runnable {
        oneShotEnd = 0L
        loopCtx?.let { startLoop(it) }
    }

    private fun alarmAttrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** 단독 작업자 확인·구조 요청 반복 진동(0.7초 켬·0.3초 끔, 알람 용도). stopAlarmLoop 까지 반복. */
    fun vibrateAlarmLoop(context: Context) {
        loopCtx = context.applicationContext
        val left = oneShotEnd - SystemClock.elapsedRealtime()
        if (left > 0) {   // 충돌 진동이 도는 중 — 5초 새로고침이 끊지 않는다. 끝난 뒤 이어서 건다.
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
            if (!vib.hasVibrator()) return   // 진동기 없는 폰은 구간을 기록하지 않는다 (아무것도 가리지 않음)
            window.loopStart(SystemClock.elapsedRealtime(), ALARM_ON_MS, ALARM_PERIOD_MS)
            vib.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, ALARM_ON_MS, ALARM_PERIOD_MS - ALARM_ON_MS), 0),
                alarmAttrs()
            )
        }
    }

    /** 단독 작업자 알람 전용 정지. 충돌 진동이 도는 중이면 그 진동은 끊지 않는다. */
    fun stopAlarmLoop(context: Context) {
        loopCtx = null
        handler.removeCallbacks(resume)
        val now = SystemClock.elapsedRealtime()
        window.loopStop(now)
        if (oneShotEnd <= now) runCatching { vibrator(context)?.cancel() }
    }

    /** 충돌 경보 경로의 진동 정지. 알람 반복이 요청된 상태면 곧바로 다시 건다(충돌 정지가 알람 진동을 끊지 않게). */
    fun stopVibration(context: Context) {
        val now = SystemClock.elapsedRealtime()
        // 자를 충돌 진동이 없으면 알람 반복을 건드리지 않는다.
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
                // 알람 반복이 요청된 동안에는 같은 중요도(ALARM)로 건다 — 재시작 경합에도 밀리지 않는다.
                if (loop) vib.vibrate(fx, alarmAttrs()) else vib.vibrate(fx)
            } else vib.vibrate(pattern, -1)
        }.onFailure { Log.e(TAG, "진동 실패: ${it.message}") }
        if (loop) handler.postDelayed(resume, dur + RESUME_PAD_MS)
    }
}
