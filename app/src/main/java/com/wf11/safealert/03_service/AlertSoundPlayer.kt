package com.wf11.safealert.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wf11.safealert.utils.DevSettings

object AlertSoundPlayer {

    private const val TAG = "AlertSoundPlayer"
    private var toneGenerator: ToneGenerator? = null
    private var repeatHandler: Handler? = null
    private var repeatRunnable: Runnable? = null
    private var isPlaying = false

    // 1 s release timer for the warning tone — kept as a field so stopSound can cancel it.
    //   An anonymous postDelayed cannot be cancelled, so escalating to DANGER within 1 s after WARNING
    //   would cut off the repeating tone.
    private var warnHandler: Handler? = null
    private val warnReset = Runnable { isPlaying = false }

    // Whether ALARM stream creation failed and playback fell back to the MUSIC stream.
    //   The volume floor in BleService.forceAlarmVolume() applies only to STREAM_ALARM, so during the fallback
    //   a media volume of 0 would be a silent failure that still looks like successful playback.
    @Volatile private var usingMusicFallback = false

    // Why sound cannot be played at all; null when healthy.
    //   BleService raises this to the persistent notification so the user knows alerts are silent.
    @Volatile var soundFaultReason: String? = null
        private set

    /** Callback for alarm-sound failure/recovery. A null argument means recovered. */
    var onSoundFault: ((String?) -> Unit)? = null

    val isUsingMusicFallback: Boolean get() = usingMusicFallback

    private fun setFault(reason: String?) {
        if (soundFaultReason == reason) return
        soundFaultReason = reason
        runCatching { onSoundFault?.invoke(reason) }
    }

    /**
     * Obtains the ToneGenerator. Never throws, under any circumstances.
     * With a runCatching{...}.recover{...} shape, a constructor throwing inside the recover block propagates to
     * the caller, killing both the sound and the alert-processing path.
     */
    private fun getOrCreateTone(): ToneGenerator? {
        toneGenerator?.let { return it }

        val alarm = runCatching {
            ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
        }.getOrNull()
        if (alarm != null) {
            toneGenerator = alarm
            usingMusicFallback = false
            setFault(null)
            return alarm
        }

        val music = runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
        }.getOrNull()
        if (music != null) {
            toneGenerator = music
            usingMusicFallback = true
            Log.w(TAG, "ALARM 스트림 생성 실패 — MUSIC 스트림 폴백")
            setFault(null)
            return music
        }

        usingMusicFallback = false
        Log.e(TAG, "톤 생성 실패 — 경보음 재생 불가")
        setFault("경보음 장치 사용 불가")
        return null
    }

    /**
     * While falling back to the MUSIC stream, applies the same volume floor as ALARM.
     * DevSettings.alarmVolume is already floored to 50~100, so that ratio is used as is.
     * Never lowers a media volume the user has raised (only raises it when the current value is below the target).
     */
    private fun enforceFallbackVolume(context: Context) {
        if (!usingMusicFallback) return
        runCatching {
            val am     = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (maxVol * DevSettings.alarmVolume / 100f).toInt().coerceIn(1, maxVol)
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) < target) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                Log.w(TAG, "폴백 음량 보정: MUSIC ${target}/${maxVol} (${DevSettings.alarmVolume}%)")
            }
        }.onFailure { Log.w(TAG, "폴백 음량 보정 실패: ${it.message}") }
    }

    fun playWarning(context: Context) {
        if (isPlaying) return
        isPlaying = true
        try {
            val tg = getOrCreateTone()
            if (tg == null) { isPlaying = false; return }
            enforceFallbackVolume(context)
            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
            // Keep the ToneGenerator (reused) — released only in stopSound
            warnHandler = Handler(Looper.getMainLooper()).apply { postDelayed(warnReset, 1000) }
        } catch (e: Exception) {
            Log.e(TAG, "경고음 실패: ${e.message}")
            isPlaying = false
            toneGenerator = null
            setFault("경보음 재생 실패")
        }
    }

    fun playDanger(context: Context) {
        stopSound()
        isPlaying = true

        // If obtaining the tone were outside the try, an exception thrown here would reach the service and kill
        //   all alert processing. Do not swallow an acquisition failure silently; record the reason.
        val tg = try {
            getOrCreateTone()
        } catch (e: Exception) {
            Log.e(TAG, "위험음 톤 확보 실패: ${e.message}")
            setFault("경보음 장치 사용 불가")
            null
        }
        if (tg == null) {
            isPlaying = false
            Log.e(TAG, "위험음 재생 불가 — 진동·화면 경보만 동작")
            return
        }
        enforceFallbackVolume(context)

        repeatHandler = Handler(Looper.getMainLooper())
        repeatRunnable = object : Runnable {
            override fun run() {
                if (!isPlaying) return
                try {
                    // Reuse the existing ToneGenerator (no re-creation → saves CPU/memory)
                    tg.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 400)
                } catch (e: Exception) {
                    Log.e(TAG, "위험음 반복 실패: ${e.message}")
                    toneGenerator = null  // Null only on error
                    setFault("경보음 재생 실패")
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
        warnHandler?.removeCallbacks(warnReset)
        warnHandler = null
        runCatching { toneGenerator?.release() }
        toneGenerator = null  // Release only on stop
        usingMusicFallback = false
        Log.d(TAG, "소리 중지")
    }
}
