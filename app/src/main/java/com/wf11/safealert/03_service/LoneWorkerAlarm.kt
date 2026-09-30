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
 * 충돌 경보 경로와 단독 작업자 알람이 STREAM_ALARM 볼륨을 함께 쓴다. 구조 요청 알람이 울리는 동안에는
 * 충돌 경로가 볼륨을 낮추지 않는다. 알람을 끝낼 때는 현재 볼륨이 우리가 올려 둔 값 그대로일 때만 원래 값으로
 * 되돌리되, 충돌 경로가 10초 안에 볼륨을 요청했으면 되돌리기를 미룬다(저장값은 정리될 때까지 남는다).
 */
object AlarmVolumeShare {
    const val COLLISION_HOLD_MS = 10_000L

    enum class Restore { RESTORE, WAIT, DROP }

    @Volatile var sosSounding = false

    @Volatile var collisionAtMs = -COLLISION_HOLD_MS
        private set

    /** 충돌 경로의 볼륨 요청마다 부른다(실제로 바뀌었는지와 무관). */
    fun noteCollision(nowMs: Long) {
        collisionAtMs = nowMs
    }

    fun collisionTarget(target: Int, current: Int, sosSounding: Boolean) =
        if (sosSounding) maxOf(target, current) else target

    /**
     * 되돌리기 판정. 현재 값을 모르거나 저장값이 없거나 누가 바꿨거나 이미 원래 값이면 DROP(저장값만 버림),
     * 아직 우리 값인데 충돌 요청이 10초 안에 있었으면 WAIT, 아니면 RESTORE. final(감시 정지)이면 미루지 않는다.
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
 * 단독 작업자 확인·구조 요청 전용 소리·진동 (v1.1.99).
 *
 * 진동은 동료 구조 요청 사이렌에서만 건다. 본인 확인 창·본인 SOS(요구조자 의심 기기)는 소리·화면만 쓴다.
 *
 * 충돌 경보용 소리 재생기와 완전히 분리한다. 코드로 만든 PCM(SirenGenerator)을 정적 AudioTrack 으로
 * 반복 재생하고 USAGE_ALARM 스트림을 쓴다. 앱의 소리 끄기 설정·충돌 경보 음소거와 무관하게 울린다.
 * 볼륨 변경은 setAlarmVolume(BleService 의 보호 setter)로만 하므로 볼륨 버튼 음소거로 오인되지 않는다.
 */
class LoneWorkerAlarm(
    private val ctx: Context,
    private val setAlarmVolume: (Int) -> Unit
) {
    enum class Pattern {
        CHECK, SIREN;

        companion object {
            /** 소리 우선순위(F2, 순수): 본인 SOS → 사이렌, 확인 창 → 확인음, 동료 SOS 들림 → 사이렌, 아니면 없음. */
            fun of(mode: LoneWorkerLogic.Mode, peerAudible: Boolean): Pattern? = when {
                mode == LoneWorkerLogic.Mode.SOS -> SIREN
                mode == LoneWorkerLogic.Mode.CHECKING -> CHECK
                peerAudible -> SIREN
                else -> null
            }
        }
    }

    companion object {
        private const val TAG = "LoneWorkerAlarm"
        private const val RETRY_MS = 5_000L
        private const val FALLBACK_TONE_MS = 6_000
        private const val PREFS = "lone_worker_alarm"
        private const val K_ORIG = "orig"   // 사이렌 전 원래 볼륨 (프로세스가 죽어도 남는다)
        private const val K_OURS = "ours"   // 이 앱이 올려 둔 볼륨
        private const val FAULT_TEXT = "경보음 볼륨을 올리지 못했습니다 — 방해 금지·음량 제한을 확인하세요"
    }

    /** 볼륨을 올리지 못했을 때 화면에 보일 안내. 문제가 없으면 null. */
    var volumeFault: String? = null
        private set

    private val prefs get() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var track: AudioTrack? = null
    private var playing: Pattern? = null
    private var fallback: ToneGenerator? = null
    private var fallbackFor: Pattern? = null
    private var failedAt = 0L
    private var vibrating = false
    private var restoreWaiting = false   // 볼륨 되돌리기를 충돌 경보 때문에 미루는 중 (재생은 이미 멈춤)

    private val audio: AudioManager?
        get() = runCatching { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }.getOrNull()

    /** vibrate 는 동료 구조 요청 사이렌일 때만 true(LoneWorkerLogic.alarmVibrates). */
    fun play(p: Pattern, vibrate: Boolean = false) {
        setVibration(vibrate)
        if (playing == p) return
        AlarmVolumeShare.sosSounding = true
        val t0 = SystemClock.elapsedRealtime()
        // 대체음이 나는 동안에는 5초에 한 번만 트랙을 다시 시도한다
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

    /** 5초마다: 다른 곳에서 취소된 진동을 다시 걸고(동료 사이렌일 때만), 충돌 경보가 낮춘 볼륨을 되돌린다. 트랙 실패 중이면 재시도한다. */
    fun refresh() {
        val p = playing ?: fallbackFor ?: return
        if (vibrating) vibrate()
        if (playing == null) { play(p, vibrating); return }
        applyVolume(p)
    }

    /**
     * 재생을 멈추고 볼륨을 정리한다. 되돌리기를 미루는 동안에는 주기 호출(유휴 렌더)마다 다시 판정한다.
     * final 은 감시 정지(뒤에 주기 호출이 없음): 미루지 않고 되돌린다.
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

    /** 저장해 둔 원래 볼륨을 판정대로 처리한다. 미루면 false(저장값 유지), 되돌렸거나 버렸으면 true. */
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
        if (!pf.contains(K_ORIG)) pf.edit().putInt(K_ORIG, cur).apply()   // 재시작 중에도 진짜 원래 값을 유지
        val target = if (p == Pattern.SIREN) max else {
            val pref = Math.ceil(max * DevSettings.alarmVolume / 100.0).toInt().coerceIn(0, max)
            maxOf(cur, pref)
        }
        var now = cur
        if (cur != target) {
            setAlarmVolume(target)
            now = am.getStreamVolume(AudioManager.STREAM_ALARM)   // 읽어 보고 확인
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
