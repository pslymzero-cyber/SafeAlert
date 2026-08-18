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

    // (v1.1.64 패치3-6) ALARM 스트림 생성이 실패해 MUSIC 스트림으로 폴백한 상태인지.
    //   BleService.forceAlarmVolume() 의 음량 하한 보정은 STREAM_ALARM 전용이라,
    //   폴백 중에는 미디어 음량이 0 이어도 "재생은 성공"으로 보이는 무성 실패가 된다.
    @Volatile private var usingMusicFallback = false

    // (v1.1.64 패치3-5) 소리를 아예 낼 수 없게 된 사유. 정상이면 null.
    //   BleService 가 이 값을 상시 알림으로 승격해 사용자가 무음 상태를 인지하게 한다.
    @Volatile var soundFaultReason: String? = null
        private set

    /** 경보음 이상/복구 통지 콜백. 인자가 null 이면 복구. */
    var onSoundFault: ((String?) -> Unit)? = null

    val isUsingMusicFallback: Boolean get() = usingMusicFallback

    private fun setFault(reason: String?) {
        if (soundFaultReason == reason) return
        soundFaultReason = reason
        runCatching { onSoundFault?.invoke(reason) }
    }

    /**
     * ToneGenerator 확보. (v1.1.64 패치3-5) 어떤 경우에도 예외를 밖으로 던지지 않는다.
     * 기존 구현은 runCatching{...}.recover{...} 형태라 recover 블록 안의 생성자가 던지면
     * 그대로 호출부로 전파돼, 소리도 안 나고 경보 처리 경로까지 함께 죽었다.
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
     * (v1.1.64 패치3-6) MUSIC 스트림 폴백 중일 때 ALARM 과 동등한 음량 하한을 건다.
     * DevSettings.alarmVolume 은 이미 50~100 으로 하한이 걸려 있으므로 그 비율을 그대로 쓴다.
     * 사용자가 올려 둔 미디어 음량은 내리지 않는다(현재 값이 목표보다 낮을 때만 올림).
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
            Handler(Looper.getMainLooper()).postDelayed({
                isPlaying = false
                // ToneGenerator 유지 (재사용) — stopSound에서만 해제
            }, 1000)
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

        // (v1.1.64 패치3-5) 톤 확보가 try 밖에 있으면 여기서 던진 예외가 서비스까지 올라가
        //   경보 처리 전체가 죽는다. 확보 실패는 조용히 넘기지 말고 사유를 남긴다.
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
                    // 기존 ToneGenerator 재사용 (재생성 제거 → CPU/메모리 절약)
                    tg.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 400)
                } catch (e: Exception) {
                    Log.e(TAG, "위험음 반복 실패: ${e.message}")
                    toneGenerator = null  // 오류 시만 null 처리
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
        runCatching { toneGenerator?.release() }
        toneGenerator = null  // 정지 시에만 해제
        usingMusicFallback = false
        Log.d(TAG, "소리 중지")
    }
}
