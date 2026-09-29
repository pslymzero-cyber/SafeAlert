package com.wf11.safealert.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Log
import com.wf11.safealert.utils.DevSettings

/**
 * 단독 작업자 확인·구조 요청 전용 소리·진동 (v1.1.99).
 *
 * 충돌 경보용 소리 재생기와 완전히 분리한다. 코드로 만든 PCM(SirenGenerator)을 정적 AudioTrack 으로
 * 반복 재생하고 USAGE_ALARM 스트림을 쓴다. 앱의 소리 끄기 설정·충돌 경보 음소거와 무관하게 울린다.
 * 볼륨 변경은 setAlarmVolume(BleService 의 보호 setter)로만 하므로 볼륨 버튼 음소거로 오인되지 않는다.
 */
class LoneWorkerAlarm(
    private val ctx: Context,
    private val setAlarmVolume: (Int) -> Unit
) {
    enum class Pattern { CHECK, SIREN }

    companion object {
        private const val TAG = "LoneWorkerAlarm"
        private const val RETRY_MS = 5_000L
        private const val FALLBACK_TONE_MS = 6_000
    }

    private var track: AudioTrack? = null
    private var playing: Pattern? = null
    private var savedVolume = -1
    private var fallback: ToneGenerator? = null
    private var fallbackFor: Pattern? = null
    private var failedAt = 0L

    private val audio: AudioManager?
        get() = runCatching { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }.getOrNull()

    fun play(p: Pattern) {
        if (playing == p) return
        val t0 = SystemClock.elapsedRealtime()
        // 대체음이 나는 동안에는 5초에 한 번만 트랙을 다시 시도한다
        if (fallbackFor == p && t0 - failedAt < RETRY_MS) {
            applyVolume(p)
            vibrate()
            return
        }
        releaseTrack()
        playing = p
        applyVolume(p)
        if (startTrack(p)) {
            stopFallback()
        } else {
            Log.e(TAG, "사이렌 트랙 재생 실패 — 대체음·진동 유지, 5초 뒤 재시도")
            releaseTrack()
            playing = null
            failedAt = t0
            startFallback(p)
        }
        vibrate()
    }

    /** 정적 트랙은 쓰기 전에는 초기화 상태가 아니다 — 만들고, 쓰고, 상태를 확인한 뒤 반복 지정·재생한다 (v1.1.99, F01). */
    private fun startTrack(p: Pattern): Boolean {
        val pcm = if (p == Pattern.SIREN) SirenGenerator.wailCycle() else SirenGenerator.checkBeepCycle()
        var t: AudioTrack? = null
        return try {
            t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SirenGenerator.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            val ok = t.write(pcm, 0, pcm.size) == pcm.size &&
                t.state == AudioTrack.STATE_INITIALIZED &&
                t.setLoopPoints(0, pcm.size, -1) == AudioTrack.SUCCESS
            if (ok) {
                t.play()
                track = t
            } else {
                t.release()
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "사이렌 트랙 예외: ${e.message}")
            runCatching { t?.release() }
            false
        }
    }

    private fun startFallback(p: Pattern) {
        stopFallback()
        fallbackFor = p
        val type = if (p == Pattern.SIREN) ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK else ToneGenerator.TONE_PROP_BEEP2
        fallback = runCatching {
            ToneGenerator(AudioManager.STREAM_ALARM, 100).also { it.startTone(type, FALLBACK_TONE_MS) }
        }.getOrNull()
    }

    private fun stopFallback() {
        fallback?.let { g ->
            runCatching { g.stopTone() }
            runCatching { g.release() }
        }
        fallback = null
        fallbackFor = null
    }

    /** 5초마다: 다른 곳에서 취소된 진동을 다시 걸고, 충돌 경보가 낮춘 볼륨을 되돌린다. 트랙 실패 중이면 재시도한다. */
    fun refresh() {
        val p = playing ?: fallbackFor ?: return
        if (playing == null) { play(p); return }
        vibrate()
        applyVolume(p)
    }

    fun stop() {
        if (playing == null && track == null && fallbackFor == null && savedVolume < 0) return
        releaseTrack()
        stopFallback()
        playing = null
        VibrationHelper.stopVibration(ctx)
        if (savedVolume >= 0) {
            val cur = audio?.getStreamVolume(AudioManager.STREAM_ALARM)
            if (cur != null && cur != savedVolume) setAlarmVolume(savedVolume)
            savedVolume = -1
        }
    }

    private fun releaseTrack() {
        track?.let { t ->
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        track = null
    }

    private fun applyVolume(p: Pattern) {
        val am = audio ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
        if (savedVolume < 0) savedVolume = cur
        val target = if (p == Pattern.SIREN) max else {
            val pref = Math.ceil(max * DevSettings.alarmVolume / 100.0).toInt().coerceIn(0, max)
            maxOf(cur, pref)
        }
        if (cur != target) setAlarmVolume(target)
    }

    private fun vibrate() = VibrationHelper.vibrateAlarmLoop(ctx)
}
