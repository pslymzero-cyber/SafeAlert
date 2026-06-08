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

    private fun getOrCreateTone(): ToneGenerator? {
        if (toneGenerator == null) {
            toneGenerator = runCatching {
                ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            }.recover {
                ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
            }.getOrNull()
        }
        return toneGenerator
    }

    fun playWarning(context: Context) {
        if (isPlaying) return
        isPlaying = true
        try {
            getOrCreateTone()?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
            Handler(Looper.getMainLooper()).postDelayed({
                isPlaying = false
                // ToneGenerator 유지 (재사용) — stopSound에서만 해제
            }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "경고음 실패: ${e.message}")
            isPlaying = false
        }
    }

    fun playDanger(context: Context) {
        stopSound()
        isPlaying = true

        // ToneGenerator 1회 생성 후 재사용 — 600ms마다 재생성 하지 않음
        val tg = getOrCreateTone()

        repeatHandler = Handler(Looper.getMainLooper())
        repeatRunnable = object : Runnable {
            override fun run() {
                if (!isPlaying) return
                try {
                    // 기존 ToneGenerator 재사용 (재생성 제거 → CPU/메모리 절약)
                    tg?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 400)
                } catch (e: Exception) {
                    Log.e(TAG, "위험음 반복 실패: ${e.message}")
                    toneGenerator = null  // 오류 시만 null 처리
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
        runCatching { toneGenerator?.release() }
        toneGenerator = null  // 정지 시에만 해제
        Log.d(TAG, "소리 중지")
    }
}
