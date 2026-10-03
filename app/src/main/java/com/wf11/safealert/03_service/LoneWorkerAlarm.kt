package com.wf11.safealert.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.app.NotificationManager
import android.os.SystemClock
import android.util.Log
import com.wf11.safealert.utils.DevSettings

/**
 * The collision alert path and the lone-worker alarm share the STREAM_ALARM volume. While the rescue-request
 * alarm sounds, the collision path does not lower the volume. When the alarm ends, the volume is restored to
 * the original only if it is still the value we raised it to, but the restore is deferred if the collision
 * path requested volume within 10s (the saved value stays until cleaned up).
 */
object AlarmVolumeShare {
    const val COLLISION_HOLD_MS = 10_000L

    enum class Restore { RESTORE, WAIT, DROP }

    @Volatile var sosSounding = false

    @Volatile var collisionAtMs = -COLLISION_HOLD_MS
        private set

    /** Called on every collision-path volume request (whether or not it actually changed anything). */
    fun noteCollision(nowMs: Long) {
        collisionAtMs = nowMs
    }

    fun collisionTarget(target: Int, current: Int, sosSounding: Boolean) =
        if (sosSounding) maxOf(target, current) else target

    /**
     * Restore decision. DROP (discard only the saved value) if the current value is unknown, nothing is saved,
     * someone else changed it, or it is already the original; WAIT if it is still our value but a collision
     * request came within 10s; otherwise RESTORE. Never defers when final (monitoring stopped).
     */
    fun restoreAction(
        cur: Int?, orig: Int, ours: Int, nowMs: Long, collisionAtMs: Long, final: Boolean = false
    ): Restore = when {
        cur == null || orig < 0 || ours < 0 || cur != ours || cur == orig -> Restore.DROP
        !final && nowMs - collisionAtMs < COLLISION_HOLD_MS -> Restore.WAIT
        else -> Restore.RESTORE
    }
}

/**
 * Sound and vibration dedicated to lone-worker check-ins and rescue requests.
 *
 * Vibration is used only for a coworker's rescue-request siren. My own check window and my own SOS
 * (the device suspected to need rescue) use sound and screen only.
 *
 * Fully separate from the collision alert sound player. Loops code-generated PCM (SirenGenerator) on a
 * static AudioTrack using the USAGE_ALARM stream. Sounds regardless of the app's sound-off setting or
 * collision-alert mute. Volume is changed only through setAlarmVolume (BleService's protected setter),
 * so it is not mistaken for a volume-button mute.
 */
class LoneWorkerAlarm(
    private val ctx: Context,
    private val setAlarmVolume: (Int) -> Unit
) {
    enum class Pattern {
        CHECK, SIREN;

        companion object {
            /**
             * Sound priority (pure): my SOS → siren, check window → check tone, coworker SOS heard while watching → siren,
             * otherwise none. Vibration only for a coworker siren while watching (LoneWorkerLogic.alarmVibrates).
             * Adding a Mode causes a compile error here.
             */
            fun of(mode: LoneWorkerLogic.Mode, peerAudible: Boolean): Pattern? = when (mode) {
                LoneWorkerLogic.Mode.SOS -> SIREN
                LoneWorkerLogic.Mode.CHECKING -> CHECK
                LoneWorkerLogic.Mode.WATCHING -> if (peerAudible) SIREN else null
            }
        }
    }

    companion object {
        private const val TAG = "LoneWorkerAlarm"
        private const val RETRY_MS = 5_000L
        private const val FALLBACK_TONE_MS = 6_000
        private const val PREFS = "lone_worker_alarm"
        private const val K_ORIG = "orig"   // original volume before the siren (survives process death)
        private const val K_OURS = "ours"   // the volume this app raised it to
        private const val FAULT_TEXT = "경보음 볼륨을 올리지 못했습니다 — 방해 금지·음량 제한을 확인하세요"
    }

    /** Notice shown on screen when the volume could not be raised. null if there is no problem. */
    var volumeFault: String? = null
        private set

    private val prefs get() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var track: AudioTrack? = null
    private var playing: Pattern? = null
    private var fallback: ToneGenerator? = null
    private var fallbackFor: Pattern? = null
    private var failedAt = 0L
    private var vibrating = false
    private var restoreWaiting = false   // volume restore deferred because of a collision alert (playback already stopped)

    private val audio: AudioManager?
        get() = runCatching { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }.getOrNull()

    /** vibrate is true only for a coworker rescue-request siren (LoneWorkerLogic.alarmVibrates). */
    fun play(p: Pattern, vibrate: Boolean = false) {
        setVibration(vibrate)
        if (playing == p) return
        AlarmVolumeShare.sosSounding = true
        val t0 = SystemClock.elapsedRealtime()
        // While the fallback tone plays, retry the track only once every 5s
        if (fallbackFor == p && t0 - failedAt < RETRY_MS) {
            applyVolume(p)
            return
        }
        releaseTrack()
        playing = p
        applyVolume(p)
        if (startTrack(p)) {
            stopFallback()
        } else {
            Log.e(TAG, "사이렌 트랙 재생 실패 — 대체음 유지, 5초 뒤 재시도")
            releaseTrack()
            playing = null
            failedAt = t0
            startFallback(p)
        }
    }

    /** A static track is not initialized until written — create, write, check the state, then set looping and play. */
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

    /**
     * Every 5s: re-applies vibration cancelled elsewhere (coworker siren only) and restores
     * volume lowered by a collision alert. Retries the track while it is failing.
     */
    fun refresh() {
        val p = playing ?: fallbackFor ?: return
        if (vibrating) vibrate()
        if (playing == null) { play(p, vibrating); return }
        applyVolume(p)
    }

    /**
     * Stops playback and settles the volume. While a restore is deferred, it is re-decided on every periodic call (idle render).
     * final = monitoring stopped (no periodic calls follow): restores without deferring.
     */
    fun stop(final: Boolean = false) {
        AlarmVolumeShare.sosSounding = false
        val live = playing != null || track != null || fallbackFor != null
        if (!live && !prefs.contains(K_ORIG)) return
        if (live || !restoreWaiting) {
            releaseTrack()
            stopFallback()
            playing = null
            vibrating = false
            VibrationHelper.stopAlarmLoop(ctx)
            volumeFault = null
        }
        restoreWaiting = !settleVolume(final)
    }

    /** Applies the decision to the saved original volume. false if deferred (saved value kept), true if restored or dropped. */
    private fun settleVolume(final: Boolean): Boolean {
        val p = prefs
        val cur = audio?.getStreamVolume(AudioManager.STREAM_ALARM)
        val orig = p.getInt(K_ORIG, -1)
        val act = AlarmVolumeShare.restoreAction(
            cur, orig, p.getInt(K_OURS, -1), SystemClock.elapsedRealtime(), AlarmVolumeShare.collisionAtMs, final)
        if (act == AlarmVolumeShare.Restore.WAIT) return false
        if (act == AlarmVolumeShare.Restore.RESTORE) setAlarmVolume(orig)
        p.edit().remove(K_ORIG).remove(K_OURS).apply()
        return true
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
        val pf = prefs
        if (!pf.contains(K_ORIG)) pf.edit().putInt(K_ORIG, cur).apply()   // keep the true original value even across restarts
        val target = if (p == Pattern.SIREN) max else {
            val pref = Math.ceil(max * DevSettings.alarmVolume / 100.0).toInt().coerceIn(0, max)
            maxOf(cur, pref)
        }
        var now = cur
        if (cur != target) {
            setAlarmVolume(target)
            now = am.getStreamVolume(AudioManager.STREAM_ALARM)   // read back to verify
            if (now != cur) pf.edit().putInt(K_OURS, now).apply()
        }
        val silent = runCatching {
            am.isStreamMute(AudioManager.STREAM_ALARM) ||
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter ==
                NotificationManager.INTERRUPTION_FILTER_NONE
        }.getOrDefault(false)
        val fault = if (now < target || silent) FAULT_TEXT else null
        if (fault != volumeFault) Log.w(TAG, "경보음 볼륨 상태 변경: ${fault ?: "정상"} (요청 ${target}, 실제 ${now})")
        volumeFault = fault
    }

    private fun setVibration(on: Boolean) {
        if (on == vibrating) return
        vibrating = on
        if (on) vibrate() else VibrationHelper.stopAlarmLoop(ctx)
    }

    private fun vibrate() = VibrationHelper.vibrateAlarmLoop(ctx)
}
