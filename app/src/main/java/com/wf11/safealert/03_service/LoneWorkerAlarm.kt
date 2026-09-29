package com.wf11.safealert.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.VibrationEffect
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
    }

    private var track: AudioTrack? = null
    private var playing: Pattern? = null
    private var savedVolume = -1

    private val audio: AudioManager?
        get() = runCatching { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }.getOrNull()

    fun play(p: Pattern) {
        if (playing == p) return
        releaseTrack()
        playing = p
        applyVolume(p)
        val pcm = if (p == Pattern.SIREN) SirenGenerator.wailCycle() else SirenGenerator.checkBeepCycle()
        runCatching {
            val t = AudioTrack.Builder()
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
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "사이렌 트랙 초기화 실패 — 진동·화면만 유지")
                t.release()
                return@runCatching
            }
            t.write(pcm, 0, pcm.size)
            t.setLoopPoints(0, pcm.size, -1)
            t.play()
            track = t
        }.onFailure { Log.e(TAG, "사이렌 재생 실패: ${it.message}") }
        vibrate()
    }

    /** 5초마다: 다른 곳에서 취소된 진동을 다시 걸고, 충돌 경보가 낮춘 볼륨을 되돌린다. */
    fun refresh() {
        val p = playing ?: return
        vibrate()
        applyVolume(p)
    }

    fun stop() {
        if (playing == null && track == null && savedVolume < 0) return
        releaseTrack()
        playing = null
        runCatching { VibrationHelper.vibrator(ctx)?.cancel() }
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

    @Suppress("DEPRECATION")
    private fun vibrate() {
        runCatching {
            VibrationHelper.vibrator(ctx)?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 700, 300), 0),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
    }
}
