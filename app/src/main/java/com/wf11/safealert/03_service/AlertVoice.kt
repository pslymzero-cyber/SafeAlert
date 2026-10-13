package com.wf11.safealert.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.wf11.safealert.R
import com.wf11.safealert.utils.DevSettings

/**
 * One AudioTrack-sized player for the three recorded clips (the seam the logic is tested through).
 * play(clip, loopGapMs): starts the clip now, replacing what plays; loopGapMs >= 0 repeats it with that pause
 * until stop(), a negative value plays it once. It answers false when it could not start. alive is true while the
 * track reports that it is playing.
 */
internal interface ClipPlayer {
    fun play(clip: Int, loopGapMs: Int): Boolean
    fun stop()
    val alive: Boolean
    fun durationMs(clip: Int): Long
    fun release()
}

/**
 * Spoken guidance for proximity alerts, played from three recorded clips (res/raw: voice_warning, voice_danger,
 * voice_rapid) by the app itself. The phone's speech engine is not used.
 *
 * The beep / siren is the baseline: AlertSoundPlayer always keeps its repeat running, whatever this object does.
 * The clip hides the tone (covering) only while it is really playing: the player started it and the track still
 * reports playing, and for a one-shot clip only until its own end. A player that fails to start gives the tone at
 * once, in the same call; one that dies while playing gives the tone on the next 600 ms tick of the siren repeat.
 * This object never starts a tone; its only call into the tone side is AlertSoundPlayer.hushForSpeech(), which
 * switches the sounding tone piece off.
 *
 * Phases (all on the main thread): WARNING = the warning sentence once; RAPID = the rapid-approach sentence once,
 * then DANGER after a pause; DANGER = the danger sentence repeated until stop(). The length of a clip is read from
 * its file, so replacing the three files changes nothing in the code.
 *
 * Known limits (intended): the rapid-approach hold-off is one clock for all devices, so a rapid approach of
 * another device within RAPID_HOLDOFF_MS of the last one skips the phrase (siren and rapid vibration stay), and
 * stop() does not clear it. alive is the track state on the app side, so a fault inside the audio server is only
 * seen through the return codes of play.
 */
object AlertVoice {

    const val WARNING = "주변에 작업자가 있습니다. 주의해 주세요"
    const val DANGER = "충돌에 주의해 주세요"
    const val RAPID = "작업자가 급접근 중입니다"

    const val STATUS_IDLE = "감시를 시작하면 확인됩니다"
    const val STATUS_NO_FILES = "음성 파일을 읽지 못해 경고음 · 사이렌으로 울립니다"
    const val STATUS_OFF = "음성 안내 꺼짐 — 경고음 · 사이렌으로 울립니다"
    const val STATUS_COOLING = "음성 재생에 실패해 경고음 · 사이렌으로 울립니다(잠시 뒤 다시 시도)"
    const val STATUS_READY = "음성 안내 사용 가능"

    internal const val CLIP_WARNING = 0
    internal const val CLIP_DANGER = 1
    internal const val CLIP_RAPID = 2

    private enum class Phase { NONE, WARNING, RAPID, DANGER }

    private const val TAG = "AlertVoice"
    internal const val REPEAT_GAP_MS = 400
    private const val NEXT_GRACE_MS = 300L
    private const val RAPID_HOLDOFF_MS = 5000L
    private const val COOL_MS = 60000L
    private const val CHURN_MIN_MS = 700L
    private const val CHURN_BACKOFF_MS = 1200L
    private const val NONE = -1L

    /** Called when the state or the degraded flag may have changed (the ongoing notification follows it). */
    var onChange: (() -> Unit)? = null

    /** Test seam: how the player is made. null = the clip files could not be read. */
    @VisibleForTesting
    internal var playerFactory: (Context) -> ClipPlayer? = { TrackPlayer.create(it) }

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var appCtx: Context? = null
    private var started = false
    private var player: ClipPlayer? = null

    private var phase = Phase.NONE
    private var rapidPending = false
    private var delayed = false
    private var lastRapidAt = NONE
    private var startedAt = 0L
    private var clipEnd = 0L
    private var coverUntil = 0L
    private var coolUntil = 0L
    private var churnUntil = 0L

    /** End of a one-shot clip (WARNING: back to NONE; RAPID: the danger repeat follows). */
    private val advance = Runnable {
        when (phase) {
            Phase.WARNING -> { phase = Phase.NONE; coverUntil = 0L }
            Phase.RAPID -> startDanger()
            else -> {}
        }
    }

    /** A start that was held back by the flicker guard. */
    private val delayedStart = Runnable {
        delayed = false
        if (phase != Phase.NONE) return@Runnable
        if (!usable()) { halt(); return@Runnable }
        begin()
    }

    /** The cooldown is over: bring the player back if it could not be rebuilt (once per cooldown), then redraw. */
    private val coolEnd = object : Runnable {
        override fun run() {
            if (started && player == null) {
                player = makePlayer()
                if (player == null) handler.postDelayed(this, COOL_MS)
            }
            changed()
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun makePlayer(): ClipPlayer? {
        val ctx = appCtx ?: return null
        return runCatching { playerFactory(ctx) }.getOrNull()
    }

    // ---- lifecycle -------------------------------------------------------------------------------------

    /** Reads the clips and prepares the player. Never throws: a failure leaves the tones alone. */
    fun start(context: Context) {
        if (started) return
        appCtx = context.applicationContext
        started = true
        player = makePlayer()
        changed()
    }

    fun shutdown() {
        if (!started) return
        for (r in listOf(advance, delayedStart, coolEnd)) handler.removeCallbacks(r)
        val p = player
        player = null
        if (p != null) runCatching { p.stop(); p.release() }
        phase = Phase.NONE
        rapidPending = false
        delayed = false
        coverUntil = 0L
        coolUntil = 0L
        churnUntil = 0L
        lastRapidAt = NONE
        started = false
        changed()
    }

    fun changed() { runCatching { onChange?.invoke() } }

    // ---- requests (main thread) ------------------------------------------------------------------------

    /** One warning sentence. Never cuts a clip in progress. */
    fun say() {
        if (!usable()) { halt(); return }
        settle()
        if (phase != Phase.NONE || delayed) return
        val t = now()
        if (t < churnUntil) return
        val p = player ?: return
        if (!playOk(p, CLIP_WARNING, -1)) { failed("경고 재생 시작 안 됨"); return }
        val d = p.durationMs(CLIP_WARNING)
        phase = Phase.WARNING
        startedAt = t
        clipEnd = t + d
        coverUntil = clipEnd
        after(advance, d)
        hush()
    }

    /**
     * Danger sentence repeating until stop(). Called again every few seconds while the alert lasts: a repeat that
     * is running is never restarted. A warning sentence is replaced at once (escalation); a rapid approach plays
     * RAPID first and replaces a running danger repeat only when the last RAPID is older than RAPID_HOLDOFF_MS.
     * A player found dead in the middle of a clip is a failure (cooldown, new player).
     */
    fun loop(rapid: Boolean) {
        if (!usable()) { halt(); return }
        settle()
        val t = now()
        if (dead()) { failed("재생 중 멈춤"); return }
        if (rapid && (lastRapidAt == NONE || t - lastRapidAt >= RAPID_HOLDOFF_MS)) rapidPending = true
        when (phase) {
            Phase.WARNING -> begin()
            Phase.RAPID -> {}
            Phase.DANGER -> if (rapidPending) begin()
            Phase.NONE -> {
                if (delayed) return
                if (t < churnUntil) {
                    delayed = true
                    after(delayedStart, churnUntil - t)
                    return
                }
                begin()
            }
        }
    }

    /**
     * Called from every decision frame: returns at once unless something is playing or held back. A clip cut within
     * CHURN_MIN_MS of its start means the judgement is flickering: speech stays off for CHURN_BACKOFF_MS (the tone
     * alone covers that time).
     */
    fun stop() {
        if (phase == Phase.NONE && !delayed) return
        if (phase != Phase.NONE && now() - startedAt < CHURN_MIN_MS) churnUntil = now() + CHURN_BACKOFF_MS
        halt()
    }

    /** True while a clip is really playing: the tone side then does not start a new piece. */
    fun covering(): Boolean {
        if (phase == Phase.NONE) return false
        return now() < coverUntil && !dead()
    }

    // ---- state text ------------------------------------------------------------------------------------

    private fun cooling() = coolUntil != 0L && now() < coolUntil

    /** Why speech is (not) available; shown in the developer settings. */
    val status: String
        get() = when {
            !started -> STATUS_IDLE
            player == null -> STATUS_NO_FILES
            !DevSettings.voiceAlertEnabled -> STATUS_OFF
            cooling() -> STATUS_COOLING
            else -> STATUS_READY
        }

    /** The setting is on but the tones are doing the job (clip files not read, or cooling after a failure). */
    val degraded: Boolean
        get() = started && DevSettings.voiceAlertEnabled && (player == null || cooling())

    // ---- internals -------------------------------------------------------------------------------------

    /** Evaluation order matters: pure JVM tests reach here with no player and no settings. */
    private fun usable() =
        player != null && DevSettings.voiceAlertEnabled && !AlarmVolumeShare.sosSounding && now() >= coolUntil

    /**
     * The clip that should be playing is not. A repeat must always be playing; a one-shot clip only has to be
     * playing inside its own length (some devices leave a finished static track in a stopped state, which is
     * normal and must not look like a failure in the pause that follows RAPID).
     */
    private fun dead(): Boolean {
        val p = player ?: return false
        return when (phase) {
            Phase.NONE -> false
            Phase.DANGER -> !p.alive
            else -> now() < clipEnd && !p.alive
        }
    }

    /** A finished warning is over even if its timer was late. */
    private fun settle() {
        if (phase == Phase.WARNING && now() >= clipEnd) { phase = Phase.NONE; coverUntil = 0L }
    }

    private fun playOk(p: ClipPlayer, clip: Int, gap: Int) = runCatching { p.play(clip, gap) }.getOrDefault(false)

    private fun after(r: Runnable, ms: Long) {
        handler.removeCallbacks(r)
        handler.postDelayed(r, ms)
    }

    private fun hush() { AlertSoundPlayer.hushForSpeech() }

    /** Starts RAPID (when pending) or the danger repeat, replacing a warning clip. */
    private fun begin() {
        val p = player ?: return
        val t = now()
        if (rapidPending) {
            rapidPending = false
            if (!playOk(p, CLIP_RAPID, -1)) { failed("급접근 재생 시작 안 됨"); return }
            val d = p.durationMs(CLIP_RAPID)
            phase = Phase.RAPID
            startedAt = t
            lastRapidAt = t
            clipEnd = t + d
            coverUntil = clipEnd + REPEAT_GAP_MS + NEXT_GRACE_MS
            after(advance, d + REPEAT_GAP_MS)
            hush()
        } else {
            if (!playOk(p, CLIP_DANGER, REPEAT_GAP_MS)) { failed("위험 재생 시작 안 됨"); return }
            handler.removeCallbacks(advance)
            phase = Phase.DANGER
            startedAt = t
            coverUntil = Long.MAX_VALUE
            hush()
        }
    }

    /** RAPID is over: the danger repeat follows (its start time is not a new request, startedAt stays). */
    private fun startDanger() {
        val p = player
        if (p == null || !usable()) { halt(); return }
        if (!playOk(p, CLIP_DANGER, REPEAT_GAP_MS)) { failed("위험 재생 시작 안 됨"); return }
        phase = Phase.DANGER
        coverUntil = Long.MAX_VALUE
        hush()
    }

    /** Stops everything wanted or playing (not a failure: no cooldown, no churn guard). */
    private fun halt() {
        if (phase == Phase.NONE && !delayed) { rapidPending = false; return }
        phase = Phase.NONE
        rapidPending = false
        delayed = false
        coverUntil = 0L
        handler.removeCallbacks(advance)
        handler.removeCallbacks(delayedStart)
        player?.let { p -> runCatching { p.stop() } }
    }

    /**
     * The tone is already the baseline, so a failure only drops the clip, keeps speech off for COOL_MS and builds a
     * new player (the old one is released).
     */
    private fun failed(reason: String) {
        halt()
        coolUntil = now() + COOL_MS
        val old = player
        player = null
        if (old != null) runCatching { old.stop(); old.release() }
        player = makePlayer()
        Log.w(TAG, "음성 실패(${reason}) — ${COOL_MS / 1000}초간 경고음 · 사이렌만")
        handler.removeCallbacks(coolEnd)
        handler.postDelayed(coolEnd, COOL_MS)
        changed()
    }
}

/** The real player: one static AudioTrack per clip, the danger track holds its pause as trailing silence. */
internal class TrackPlayer private constructor(
    private val tracks: List<AudioTrack>,
    private val durations: LongArray,
    private val frames: IntArray,
) : ClipPlayer {

    private var current: AudioTrack? = null
    private var released = false

    override fun play(clip: Int, loopGapMs: Int): Boolean {
        if (released || clip !in tracks.indices) return false
        // The pause of a repeat is part of the danger track, so only that pair is valid.
        if (loopGapMs != (if (clip == AlertVoice.CLIP_DANGER) AlertVoice.REPEAT_GAP_MS else -1)) return false
        val t = tracks[clip]
        return runCatching {
            current?.stop()
            current = null
            t.stop()
            if (t.reloadStaticData() != AudioTrack.SUCCESS) return@runCatching false
            if (loopGapMs >= 0 && t.setLoopPoints(0, frames[clip], -1) != AudioTrack.SUCCESS) {
                return@runCatching false
            }
            t.play()
            current = t
            t.playState == AudioTrack.PLAYSTATE_PLAYING
        }.getOrDefault(false)
    }

    override fun stop() {
        runCatching { current?.stop() }
        current = null
    }

    override val alive: Boolean
        get() = !released && runCatching { current?.playState == AudioTrack.PLAYSTATE_PLAYING }.getOrDefault(false)

    override fun durationMs(clip: Int): Long = durations.getOrElse(clip) { 0L }

    override fun release() {
        released = true
        current = null
        for (t in tracks) runCatching { t.stop() }
        for (t in tracks) runCatching { t.release() }
    }

    internal class Wav(val sampleRate: Int, val pcm: ByteArray) {
        val frames get() = pcm.size / 2
        val durationMs get() = frames * 1000L / sampleRate
    }

    companion object {
        private val CLIP_RES = intArrayOf(R.raw.voice_warning, R.raw.voice_danger, R.raw.voice_rapid)

        /** null = a file could not be read or is not 16 bit mono PCM (the tracks built so far are released). */
        fun create(context: Context): TrackPlayer? {
            val built = ArrayList<AudioTrack>()
            val p = runCatching { build(context, built) }.getOrNull()
            if (p == null) for (t in built) runCatching { t.release() }
            return p
        }

        private fun build(context: Context, built: MutableList<AudioTrack>): TrackPlayer? {
            val wavs = CLIP_RES.map { id ->
                parseWav(context.resources.openRawResource(id).use { it.readBytes() }) ?: return null
            }
            val frames = IntArray(wavs.size)
            for ((clip, w) in wavs.withIndex()) {
                val pcm = if (clip == AlertVoice.CLIP_DANGER) {
                    w.pcm + ByteArray(w.sampleRate * AlertVoice.REPEAT_GAP_MS / 1000 * 2)
                } else w.pcm
                frames[clip] = pcm.size / 2
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(w.sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                built += t
                if (t.write(pcm, 0, pcm.size) != pcm.size || t.state != AudioTrack.STATE_INITIALIZED) return null
            }
            return TrackPlayer(ArrayList(built), LongArray(wavs.size) { wavs[it].durationMs }, frames)
        }

        /** RIFF / WAVE chunks; only 16 bit mono PCM is accepted. A size that runs past the file fails the parse. */
        fun parseWav(b: ByteArray): Wav? {
            if (b.size < 12 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var pos = 12
            var rate = 0
            var data: ByteArray? = null
            var fmtOk = false
            while (pos + 8 <= b.size) {
                val id = String(b, pos, 4, Charsets.US_ASCII)
                val size = le32(b, pos + 4)
                val body = pos + 8
                if (size < 0 || size > b.size - body) return null
                when (id) {
                    "fmt " -> {
                        if (size < 16) return null
                        rate = le32(b, body + 4)
                        fmtOk = le16(b, body) == 1 && le16(b, body + 2) == 1 && le16(b, body + 14) == 16 && rate > 0
                        if (!fmtOk) return null
                    }
                    "data" -> data = b.copyOfRange(body, body + size)
                }
                pos = body + size + (size and 1)
            }
            val d = data ?: return null
            if (!fmtOk || d.size < 2) return null
            return Wav(rate, if (d.size % 2 == 0) d else d.copyOf(d.size - 1))
        }

        private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

        /** Negative when the top bit is set, which every caller treats as too large. */
        private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)
    }
}
