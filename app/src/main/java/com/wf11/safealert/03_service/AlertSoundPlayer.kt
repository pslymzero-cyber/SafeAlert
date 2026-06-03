package com.wf11.safealert.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

object AlertSoundPlayer {

    private const val TAG = "AlertSoundPlayer"
    private var toneGenerator: ToneGenerator? = null
    private var repeatHandler: Handler? = null
    private var repeatRunnable: Runnable? = null
    private var isPlaying = false

    // 알람 스트림 볼륨을 최대로 설정
    private fun setMaxAlarmVolume(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        } catch (e: Exception) {
            Log.w(TAG, "볼륨 설정 실패: ${e.message}")
        }
    }

    private fun getTone(): ToneGenerator {
        return try {
            ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
        } catch (e: Exception) {
            ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
        }
    }

    fun playWarning(context: Context) {
        if (isPlaying) return
        isPlaying = true
        setMaxAlarmVolume(context)
        try {
            toneGenerator = getTone()
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
            Handler(Looper.getMainLooper()).postDelayed({
                toneGenerator?.release()
                toneGenerator = null
                isPlaying = false
            }, 1000)
            Log.d(TAG, "경고음 재생")
        } catch (e: Exception) {
            Log.e(TAG, "경고음 실패: ${e.message}")
            isPlaying = false
        }
    }

    fun playDanger(context: Context) {
        stopSound()
        isPlaying = true
        setMaxAlarmVolume(context)

        // 위험: 0.5초 간격으로 반복 경보
        repeatHandler = Handler(Looper.getMainLooper())
        repeatRunnable = object : Runnable {
            override fun run() {
                if (!isPlaying) return
                try {
                    toneGenerator?.release()
                    toneGenerator = getTone()
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 400)
                } catch (e: Exception) {
                    Log.e(TAG, "위험음 반복 실패: ${e.message}")
                }
                if (isPlaying) repeatHandler?.postDelayed(this, 600)
            }
        }
        repeatHandler?.post(repeatRunnable!!)
        Log.d(TAG, "위험음 반복 시작")
    }

    fun stopSound() {
        isPlaying = false
        repeatRunnable?.let { repeatHandler?.removeCallbacks(it) }
        repeatHandler = null
        repeatRunnable = null
        try {
            toneGenerator?.release()
        } catch (_: Exception) {}
        toneGenerator = null
        Log.d(TAG, "소리 중지")
    }
}
