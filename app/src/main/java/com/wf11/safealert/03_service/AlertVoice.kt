package com.wf11.safealert.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.wf11.safealert.utils.DevSettings
import java.util.Locale

/**
 * Spoken guidance for proximity alerts, in place of the warning beep / danger siren.
 *
 * Silence is never an option: whenever speech cannot be produced (engine not ready, no Korean voice, speak
 * failure, no start within START_WATCH_MS, no end within END_WATCH_MS, setting off) the caller falls back to
 * the tones. A failure after the sentence was accepted hands over to the tone entry points immediately and
 * keeps speech off for COOL_MS.
 *
 * Only the three fixed phrases below are ever handed to the engine. All state lives on the main thread.
 */
object AlertVoice {

    const val WARNING = "주변에 작업자가 있습니다. 주의해 주세요"
    const val DANGER = "충돌에 주의해 주세요"
    const val RAPID = "작업자가 급접근 중입니다"

    const val STATUS_IDLE = "감시를 시작하면 확인됩니다"
    const val STATUS_READY = "음성 안내 사용 가능"
    const val STATUS_NO_KOREAN = "이 기기에는 한국어 음성이 없어 경고음 · 사이렌으로 울립니다"

    private const val TAG = "AlertVoice"
    private const val START_WATCH_MS = 1500L
    private const val END_WATCH_MS = 8000L
    private const val REPEAT_GAP_MS = 400L
    private const val RAPID_HOLDOFF_MS = 5000L
    private const val COOL_MS = 60000L
    private const val NONE = -1L

    /** Why speech is (not) available; shown read-only in the developer settings. */
    var status: String = STATUS_IDLE
        private set

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private var tts: TextToSpeech? = null
    private var appCtx: Context? = null
    private var ready = false
    private var initGen = 0
    private var seq = 0

    /** Id of the sentence being spoken; null = nothing is being spoken. Callbacks of any other id are stale. */
    private var currentId: String? = null

    /** Danger sentence repeating until stop(). */
    private var looping = false
    private var lastRapidAt = NONE
    private var coolUntil = 0L

    private val startWatch = Runnable { if (currentId != null) fail("시작 안 됨") }
    private val endWatch = Runnable { if (currentId != null) fail("끝나지 않음") }
    private val nextSentence = Runnable {
        if (looping) {
            if (usable() && speak(DANGER)) return@Runnable
            fail("반복 이어가기 실패")
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { handler.post { onStarted(utteranceId) } }
        override fun onDone(utteranceId: String?) { handler.post { onFinished(utteranceId) } }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { handler.post { onFailed(utteranceId) } }
        override fun onError(utteranceId: String?, errorCode: Int) { handler.post { onFailed(utteranceId) } }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { handler.post { onFailed(utteranceId) } }
    }

    /** Creates the engine (asynchronous). Never throws: a failure leaves the tones in charge. */
    fun start(context: Context) {
        if (tts != null) return
        val app = context.applicationContext
        appCtx = app
        val gen = ++initGen
        tts = runCatching { TextToSpeech(app) { code -> handler.post { onInit(gen, code) } } }.getOrNull()
        if (tts == null) status = STATUS_NO_KOREAN
    }

    fun shutdown() {
        stop()
        initGen++
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        lastRapidAt = NONE
        coolUntil = 0L
        status = STATUS_IDLE
    }

    /** True = speech took the warning (no beep). False = the caller plays the beep. */
    fun warning(): Boolean {
        if (!usable()) return false
        if (looping || currentId != null) return true
        return speakOrCool(WARNING)
    }

    /**
     * True = speech took the danger alert (no siren): the danger phrase repeats until stop(). Called again every
     * few seconds while the alert lasts, so a repeat in progress is never restarted. A rapid approach speaks
     * RAPID first, and interrupts a repeat in progress only when the last RAPID is older than RAPID_HOLDOFF_MS.
     */
    fun danger(rapid: Boolean): Boolean {
        if (!usable()) { stop(); return false }
        val now = SystemClock.elapsedRealtime()
        if (looping) {
            if (!rapid || (lastRapidAt != NONE && now - lastRapidAt < RAPID_HOLDOFF_MS)) return true
            handler.removeCallbacks(nextSentence)
            lastRapidAt = now
            return speakOrCool(RAPID)
        }
        looping = true
        if (rapid) lastRapidAt = now
        return speakOrCool(if (rapid) RAPID else DANGER)
    }

    /** Called from every decision frame: returns at once unless something is being spoken. */
    fun stop() {
        if (currentId == null && !looping) return
        clearState()
        runCatching { tts?.stop() }
    }

    private fun usable() = ready && DevSettings.voiceAlertEnabled && SystemClock.elapsedRealtime() >= coolUntil

    private fun clearState() {
        looping = false
        currentId = null
        handler.removeCallbacks(startWatch)
        handler.removeCallbacks(endWatch)
        handler.removeCallbacks(nextSentence)
    }

    private fun speak(text: String): Boolean {
        val t = tts ?: return false
        val id = "av${++seq}"
        handler.removeCallbacks(startWatch)
        handler.removeCallbacks(endWatch)
        currentId = id
        val code = runCatching { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) }.getOrDefault(TextToSpeech.ERROR)
        if (code != TextToSpeech.SUCCESS) {
            currentId = null
            return false
        }
        handler.postDelayed(startWatch, START_WATCH_MS)
        return true
    }

    /** Synchronous refusal: the caller plays the tone itself (returns false), so no tone is started here. */
    private fun speakOrCool(text: String): Boolean {
        if (speak(text)) return true
        clearState()
        coolUntil = SystemClock.elapsedRealtime() + COOL_MS
        Log.w(TAG, "음성 시작 거부 — ${COOL_MS / 1000}초간 경고음 사용")
        return false
    }

    /** Asynchronous failure: the tone that speech replaced starts right now. */
    private fun fail(reason: String) {
        val wasDanger = looping
        clearState()
        runCatching { tts?.stop() }
        coolUntil = SystemClock.elapsedRealtime() + COOL_MS
        Log.w(TAG, "음성 실패(${reason}) — ${COOL_MS / 1000}초간 경고음 사용")
        val ctx = appCtx ?: return
        if (wasDanger) AlertSoundPlayer.dangerTone(ctx) else AlertSoundPlayer.warningTone(ctx)
    }

    private fun onInit(gen: Int, code: Int) {
        val t = tts
        if (gen != initGen || t == null) return
        if (code != TextToSpeech.SUCCESS) { status = STATUS_NO_KOREAN; return }
        val lang = runCatching { t.setLanguage(Locale.KOREAN) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (lang < TextToSpeech.LANG_AVAILABLE) { status = STATUS_NO_KOREAN; return }
        runCatching {
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        runCatching { t.setOnUtteranceProgressListener(listener) }
        ready = true
        status = STATUS_READY
    }

    private fun onStarted(id: String?) {
        if (id == null || id != currentId) return
        handler.removeCallbacks(startWatch)
        handler.postDelayed(endWatch, END_WATCH_MS)
    }

    private fun onFinished(id: String?) {
        if (id == null || id != currentId) return
        handler.removeCallbacks(startWatch)
        handler.removeCallbacks(endWatch)
        currentId = null
        if (looping) handler.postDelayed(nextSentence, REPEAT_GAP_MS)
    }

    private fun onFailed(id: String?) {
        if (id == null || id != currentId) return
        fail("엔진 오류")
    }
}
