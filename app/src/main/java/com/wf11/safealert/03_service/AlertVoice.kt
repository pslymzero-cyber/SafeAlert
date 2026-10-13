package com.wf11.safealert.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.wf11.safealert.utils.DevSettings
import java.util.Locale

/**
 * Spoken guidance for proximity alerts, on top of the warning beep / danger siren.
 *
 * The tone is the baseline: AlertSoundPlayer starts the beep / siren exactly as it always did, whatever this
 * object does. Speech only hides the tone (covering) while it is PROVEN to be synthesizing, that is from the
 * first onAudioAvailable (bytes > 0) of the sentence until the sentence ends, or at most MAX_MS of the sentence.
 * Anything that breaks the proof (no audio, the engine died mid-sentence, speak refused, an error) simply lets
 * the tone continue by itself. This object never starts a tone; its only call into the tone side is
 * AlertSoundPlayer.hushForSpeech(), which switches the sounding tone piece off.
 *
 * onStart is NOT a proof: engines send it before any sound. speak() returning SUCCESS only means "queued".
 *
 * Threads: all state lives on the main thread. Every call into the engine (create, speak, stop, shutdown,
 * setLanguage, voice lookup) runs on the worker thread (io), so a hung engine binder never blocks the main
 * thread; results come back through the main handler and carry the generation / utterance id so stale ones drop.
 *
 * Only the three fixed phrases below are ever handed to the engine, and only a voice that is on the device
 * (not network, not marked not-installed, Korean) is used.
 *
 * Known limits (intended): the rapid-approach hold-off is one clock for all devices, so a rapid approach of
 * another device within RAPID_HOLDOFF_MS of the last one skips the phrase (siren and rapid vibration stay).
 * If a real engine never delivers onAudioAvailable, speech cannot hide the tone and both are heard together
 * (safe, but messy) - to be checked on the device.
 */
object AlertVoice {

    const val WARNING = "주변에 작업자가 있습니다. 주의해 주세요"
    const val DANGER = "충돌에 주의해 주세요"
    const val RAPID = "작업자가 급접근 중입니다"

    const val STATUS_IDLE = "감시를 시작하면 확인됩니다"
    const val STATUS_CHECKING = "음성 엔진 확인 중"
    const val STATUS_NO_ENGINE = "이 기기에서는 음성 엔진을 쓸 수 없어 경고음 · 사이렌으로 울립니다"
    const val STATUS_NO_KOREAN = "이 기기에는 기기 안에서 쓸 한국어 음성이 없어 경고음 · 사이렌으로 울립니다"
    const val STATUS_OFF = "음성 안내 꺼짐 — 경고음 · 사이렌으로 울립니다"
    const val STATUS_COOLING = "음성 재생에 실패해 경고음 · 사이렌으로 울립니다(잠시 뒤 다시 시도)"
    const val STATUS_READY = "음성 안내 사용 가능"

    enum class State { IDLE, CHECKING, READY, NO_ENGINE, NO_KOREAN }

    private enum class Want { NONE, ONCE, LOOP }

    private class Spoken(val id: String, val text: String, val requestedAt: Long) {
        var provedAt = NONE
    }

    private const val TAG = "AlertVoice"
    private const val START_WATCH_MS = 1500L
    private const val MAX_DANGER_MS = 3500L
    private const val MAX_WARNING_MS = 6000L
    private const val MIN_SPOKEN_MS = 400L
    private const val REPEAT_GAP_MS = 400L
    private const val NEXT_GRACE_MS = 800L
    private const val RAPID_HOLDOFF_MS = 5000L
    private const val COOL_MS = 60000L
    private const val CHURN_MIN_MS = 700L
    private const val CHURN_BACKOFF_MS = 1200L
    private const val RETRY_INIT_MS = 300000L
    private const val NONE = -1L

    /** Called when the state or the degraded flag may have changed (the ongoing notification follows it). */
    var onChange: (() -> Unit)? = null

    @Volatile var state = State.IDLE
        private set

    /** Test seam: where engine calls run. Default = the worker thread (dropped when there is none). */
    @VisibleForTesting
    internal var io: (() -> Unit) -> Unit = { block -> workHandler?.post(block) }

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var worker: HandlerThread? = null
    @Volatile private var workHandler: Handler? = null

    /** The engine; touched only inside io blocks (the worker thread orders create -> configure -> shutdown). */
    @Volatile private var ttsRef: TextToSpeech? = null
    private var appCtx: Context? = null
    private var gen = 0
    private var seq = 0

    private var want = Want.NONE
    private var cur: Spoken? = null
    private var rapidPending = false
    private var lastRapidAt = NONE
    private var coverUntil = 0L
    @Volatile private var coolUntil = 0L
    private var failCount = 0
    private var churnUntil = 0L
    private var nextScheduled = false

    /** Binder thread only: the last utterance id whose first audio was already reported. */
    @Volatile private var lastAudioId: String? = null

    private val startWatch = Runnable {
        val c = cur
        if (c != null && c.provedAt == NONE) failed("시작 안 됨", false)
    }
    private val endWatch = Runnable {
        if (cur != null) failed("끝나지 않음", false)
    }
    private val coolEnd = Runnable { changed() }
    private val retryInit = Runnable {
        if (state == State.NO_ENGINE || state == State.NO_KOREAN) begin()
    }
    private val nextSentence = Runnable {
        nextScheduled = false
        if (want != Want.LOOP) return@Runnable
        if (!usable()) { halt(); return@Runnable }
        if (cur != null) return@Runnable
        speakNext(true)
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) { post { onFinished(utteranceId) } }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { post { onBroken(utteranceId) } }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { post { onBroken(utteranceId) } }
        override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
            // One call per buffer on a binder thread: only the first non-empty one of a sentence goes to main.
            if (utteranceId == null || audio == null || audio.isEmpty() || utteranceId == lastAudioId) return
            lastAudioId = utteranceId
            post { onProof(utteranceId) }
        }
    }

    private fun post(block: () -> Unit) { handler.post { block() } }
    private fun now() = SystemClock.elapsedRealtime()

    // ---- lifecycle -------------------------------------------------------------------------------------

    /** Starts the worker and creates the engine (asynchronous). Never throws: a failure leaves the tones alone. */
    fun start(context: Context) {
        if (state != State.IDLE) return
        appCtx = context.applicationContext
        worker = HandlerThread("AlertVoice").also {
            it.start()
            workHandler = Handler(it.looper)
        }
        begin()
    }

    fun shutdown() {
        if (state == State.IDLE && worker == null) return
        for (r in listOf(startWatch, endWatch, nextSentence, retryInit, coolEnd)) handler.removeCallbacks(r)
        nextScheduled = false
        want = Want.NONE
        rapidPending = false
        cur = null
        coverUntil = 0L
        coolUntil = 0L
        churnUntil = 0L
        failCount = 0
        lastRapidAt = NONE
        gen++
        io { dropEngine() }
        worker?.quitSafely()
        worker = null
        workHandler = null
        state = State.IDLE
        changed()
    }

    /** Fresh engine (also the rebuild after an engine fault). Cooldown and the rapid clock are kept. */
    private fun begin() {
        val app = appCtx ?: return
        val g = ++gen
        setState(State.CHECKING)
        io {
            dropEngine()
            val t = runCatching { TextToSpeech(app) { code -> post { onInit(g, code) } } }.getOrNull()
            ttsRef = t
            if (t == null) post { onInit(g, TextToSpeech.ERROR) }
        }
    }

    /** io only. */
    private fun dropEngine() {
        val t = ttsRef
        ttsRef = null
        if (t != null) runCatching { t.shutdown() }
    }

    private fun onInit(g: Int, code: Int) {
        if (g != gen || state != State.CHECKING) return
        if (code != TextToSpeech.SUCCESS) {
            io { dropEngine() }
            unusable(State.NO_ENGINE)
            return
        }
        io {
            val t = ttsRef
            val result = if (t == null) State.NO_ENGINE else configure(t)
            if (result != State.READY) dropEngine()
            post { configured(g, result) }
        }
    }

    /** io only. Korean, on the device, then the audio attributes (alarm stream); fixed speed and pitch. */
    private fun configure(t: TextToSpeech): State {
        val lang = runCatching { t.setLanguage(Locale.KOREAN) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (lang < TextToSpeech.LANG_AVAILABLE) return State.NO_KOREAN
        val v = runCatching { t.voice }.getOrNull()
        if (v != null) {
            val network = runCatching { v.isNetworkConnectionRequired }.getOrDefault(false)
            val missing = runCatching { v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
                .getOrDefault(false)
            val korean = runCatching { v.locale?.language == "ko" }.getOrDefault(true)
            if (network || missing || !korean) return State.NO_KOREAN
        }
        runCatching {
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        runCatching { t.setSpeechRate(1.0f) }
        runCatching { t.setPitch(1.0f) }
        runCatching { t.setOnUtteranceProgressListener(listener) }
        return State.READY
    }

    private fun configured(g: Int, result: State) {
        if (g != gen || state != State.CHECKING) return
        if (result == State.READY) setState(State.READY) else unusable(result)
    }

    private fun unusable(s: State) {
        setState(s)
        handler.removeCallbacks(retryInit)
        handler.postDelayed(retryInit, RETRY_INIT_MS)
    }

    private fun setState(s: State) {
        if (state == s) return
        state = s
        changed()
    }

    fun changed() { runCatching { onChange?.invoke() } }

    // ---- requests (main thread, never wait for the engine) ---------------------------------------------

    /** One warning sentence. Never cuts a danger repeat or a sentence in progress. */
    fun say() {
        if (!usable()) { halt(); return }
        if (want == Want.LOOP || cur != null) return
        if (now() < churnUntil) return
        want = Want.ONCE
        speak(WARNING, false)
    }

    /**
     * Danger sentence repeating until stop(). Called again every few seconds while the alert lasts: a sentence in
     * progress (or a scheduled next one) is never restarted. A warning sentence is replaced at once (escalation);
     * a rapid approach speaks RAPID first and replaces a danger sentence only when the last RAPID is older than
     * RAPID_HOLDOFF_MS.
     */
    fun loop(rapid: Boolean) {
        if (!usable()) { halt(); return }
        val t = now()
        want = Want.LOOP
        if (rapid && (lastRapidAt == NONE || t - lastRapidAt >= RAPID_HOLDOFF_MS)) rapidPending = true
        val c = cur
        if (c != null) {
            if (c.text == WARNING || (rapidPending && c.text == DANGER)) speakNext(false)
            return
        }
        if (nextScheduled) return
        if (t < churnUntil) { scheduleNext(churnUntil - t); return }
        speakNext(false)
    }

    /**
     * Called from every decision frame: returns at once unless something is wanted or being spoken. A sentence cut
     * within CHURN_MIN_MS of its request means the judgement is flickering: speech stays off for CHURN_BACKOFF_MS
     * (the tone alone covers that time).
     */
    fun stop() {
        if (want == Want.NONE && cur == null && !nextScheduled) return
        val c = cur
        if (c != null && now() - c.requestedAt < CHURN_MIN_MS) churnUntil = now() + CHURN_BACKOFF_MS
        halt()
    }

    /** True while speech is proven to be sounding: the tone side then does not start a new piece. */
    fun covering(): Boolean {
        val u = coverUntil
        return u != 0L && now() < u
    }

    // ---- state text ------------------------------------------------------------------------------------

    private fun cooling() = coolUntil != 0L && now() < coolUntil

    /** Why speech is (not) available; shown in the developer settings. */
    val status: String
        get() = when (state) {
            State.IDLE -> STATUS_IDLE
            State.CHECKING -> STATUS_CHECKING
            State.NO_ENGINE -> STATUS_NO_ENGINE
            State.NO_KOREAN -> STATUS_NO_KOREAN
            State.READY -> when {
                !DevSettings.voiceAlertEnabled -> STATUS_OFF
                cooling() -> STATUS_COOLING
                else -> STATUS_READY
            }
        }

    /** The setting is on but the tones are doing the job (no engine, no Korean voice, or cooling after a failure). */
    val degraded: Boolean
        get() = when (state) {
            State.NO_ENGINE, State.NO_KOREAN -> DevSettings.voiceAlertEnabled
            State.READY -> cooling() && DevSettings.voiceAlertEnabled
            else -> false
        }

    // ---- internals -------------------------------------------------------------------------------------

    /** Evaluation order matters: pure JVM tests reach here with no engine and no settings. */
    private fun usable() =
        state == State.READY && DevSettings.voiceAlertEnabled && !AlarmVolumeShare.sosSounding && now() >= coolUntil

    /** Stops everything wanted or spoken (not a failure: no cooldown, no churn guard). */
    private fun halt() {
        if (want == Want.NONE && cur == null && !nextScheduled) return
        val had = cur != null
        want = Want.NONE
        rapidPending = false
        coverUntil = 0L
        cur = null
        nextScheduled = false
        handler.removeCallbacks(startWatch)
        handler.removeCallbacks(endWatch)
        handler.removeCallbacks(nextSentence)
        if (had) io { runCatching { ttsRef?.stop() } }
    }

    private fun scheduleNext(delayMs: Long) {
        nextScheduled = true
        handler.removeCallbacks(nextSentence)
        handler.postDelayed(nextSentence, delayMs)
    }

    private fun speakNext(continuation: Boolean) {
        val text = if (rapidPending) {
            rapidPending = false
            lastRapidAt = now()
            RAPID
        } else DANGER
        speak(text, continuation)
    }

    /** A new sentence is not proven yet (coverUntil 0, the tone plays); only a repeat continuation keeps it. */
    private fun speak(text: String, continuation: Boolean) {
        val id = "av${++seq}"
        handler.removeCallbacks(startWatch)
        handler.removeCallbacks(endWatch)
        handler.removeCallbacks(nextSentence)
        nextScheduled = false
        cur = Spoken(id, text, now())
        if (!continuation) coverUntil = 0L
        handler.postDelayed(startWatch, START_WATCH_MS)
        val g = gen
        io {
            val t = ttsRef
            val code = if (t == null) TextToSpeech.ERROR
            else runCatching { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) }.getOrDefault(TextToSpeech.ERROR)
            if (code != TextToSpeech.SUCCESS) post { onRejected(g, id) }
        }
    }

    private fun onRejected(g: Int, id: String) {
        if (g != gen || cur?.id != id) return
        failed("speak 거부", true)
    }

    private fun maxMs(text: String) = if (text == WARNING) MAX_WARNING_MS else MAX_DANGER_MS

    /** First audio of the current sentence: speech is real now, so it hides the tone for at most maxMs. */
    private fun onProof(id: String) {
        val c = cur ?: return
        if (c.id != id || c.provedAt != NONE) return
        val t = now()
        val max = maxMs(c.text)
        c.provedAt = t
        coverUntil = t + max
        handler.removeCallbacks(startWatch)
        handler.removeCallbacks(endWatch)
        handler.postDelayed(endWatch, max)
        AlertSoundPlayer.hushForSpeech()
    }

    private fun onFinished(id: String?) {
        val c = cur ?: return
        if (id == null || c.id != id) return
        handler.removeCallbacks(startWatch)
        handler.removeCallbacks(endWatch)
        val t = now()
        if (c.provedAt == NONE || t - c.provedAt < MIN_SPOKEN_MS) { failed("소리 없이 끝남", false); return }
        failCount = 0
        cur = null
        if (want == Want.LOOP) {
            coverUntil = t + REPEAT_GAP_MS + NEXT_GRACE_MS
            scheduleNext(REPEAT_GAP_MS)
        } else {
            want = Want.NONE
            coverUntil = 0L
        }
    }

    private fun onBroken(id: String?) {
        val c = cur ?: return
        if (id == null || c.id != id) return
        failed("엔진 오류", false)
    }

    /** The tone is already the baseline, so a failure only drops the sentence and keeps speech off for COOL_MS. */
    private fun failed(reason: String, rebuild: Boolean) {
        cur = null
        coverUntil = 0L
        nextScheduled = false
        handler.removeCallbacks(startWatch)
        handler.removeCallbacks(endWatch)
        handler.removeCallbacks(nextSentence)
        io { runCatching { ttsRef?.stop() } }
        failCount++
        coolUntil = now() + COOL_MS
        if (want == Want.ONCE) want = Want.NONE
        Log.w(TAG, "음성 실패(${reason}) — ${COOL_MS / 1000}초간 경고음 · 사이렌만")
        handler.removeCallbacks(coolEnd)
        handler.postDelayed(coolEnd, COOL_MS)
        if (rebuild || failCount >= 2) begin()
        changed()
    }
}
