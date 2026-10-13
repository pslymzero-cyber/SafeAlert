package com.wf11.safealert.service

import android.media.ToneGenerator
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.wf11.safealert.support.BleServiceTestHarness
import com.wf11.safealert.utils.DevSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.Duration
import java.util.Locale

/** What the scripted engine and the recording tone generator saw, and what the engine answers. */
internal object Rec {
    class Said(val id: String, val text: String, val mode: Int)

    val said = mutableListOf<Said>()

    /** "start:<tone type>" for every startTone, "stop" for every stopTone (a hush). */
    val tones = mutableListOf<String>()
    var speakResult = TextToSpeech.SUCCESS
    var voice: Voice? = null

    fun clear() {
        said.clear(); tones.clear(); speakResult = TextToSpeech.SUCCESS; voice = null
    }
}

/** TextToSpeech whose speak() records the call and answers Rec.speakResult. It never calls back: the test does. */
@Implements(TextToSpeech::class)
class ScriptedTts : ShadowTextToSpeech() {
    @Implementation
    override fun speak(text: CharSequence?, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
        Rec.said += Rec.Said(utteranceId.orEmpty(), text.toString(), queueMode)
        return Rec.speakResult
    }

    @Implementation
    override fun getVoice(): Voice? = Rec.voice
}

/** ToneGenerator that records which tone started and every stopTone (the default shadow records no stops). */
@Implements(ToneGenerator::class)
class RecordingTone {
    @Implementation
    fun __constructor__(streamType: Int, volume: Int) {
    }

    @Implementation
    fun startTone(toneType: Int, durationMs: Int): Boolean {
        Rec.tones += "start:$toneType"
        return true
    }

    @Implementation
    fun stopTone() {
        Rec.tones += "stop"
    }

    @Implementation
    fun release() {
    }
}

/** Shared setup: scripted engine, recording tones, the engine calls run in place, no state leaks between tests. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ScriptedTts::class, RecordingTone::class])
abstract class VoiceTestBase {
    protected val app get() = RuntimeEnvironment.getApplication()
    private val defaultIo = AlertVoice.io

    @Before fun setUp() = reset()

    @After fun tearDown() {
        reset()
        AlertVoice.io = defaultIo
    }

    private fun reset() {
        AlertVoice.io = { it() }
        DevSettings.init(app)
        DevSettings.voiceAlertEnabled = true
        AlarmVolumeShare.sosSounding = false
        AlertVoice.onChange = null
        AlertSoundPlayer.stopSound()
        AlertVoice.shutdown()
        Rec.clear()
    }

    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()
    protected fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    protected fun engine() = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
    protected fun newest(): TextToSpeech = ShadowTextToSpeech.getLastTextToSpeechInstance()

    protected fun sirens() = Rec.tones.count { it == "start:${ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK}" }
    protected fun beeps() = Rec.tones.count { it == "start:${ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD}" }
    protected fun hushes() = Rec.tones.count { it == "stop" }
    protected fun said(): List<String> = Rec.said.map { it.text }
    protected fun lastId(): String = Rec.said.last().id

    private fun cb() = engine().utteranceProgressListener

    protected fun ready(korean: Boolean = true, code: Int = TextToSpeech.SUCCESS) {
        if (korean) ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        AlertVoice.start(app)
        engine().onInitListener.onInit(code)
        idle()
    }

    protected fun started(id: String = lastId()) { cb().onStart(id); idle() }
    protected fun audio(id: String = lastId()) { cb().onAudioAvailable(id, ByteArray(64)); idle() }
    protected fun done(id: String = lastId()) { cb().onDone(id); idle() }
    protected fun error(id: String = lastId()) { @Suppress("DEPRECATION") cb().onError(id); idle() }
    protected fun error2(id: String = lastId()) { cb().onError(id, -1); idle() }
    protected fun stopped(id: String = lastId()) { cb().onStop(id, false); idle() }

    /** The current sentence ends 500 ms after its proof, the repeat gap passes, the next one is proven at once. */
    protected fun nextSentenceProven() { idleFor(500); done(); idleFor(400); audio() }
}

/**
 * The tone is the baseline (same instant, same code as before); speech hides it only while the first audio of the
 * sentence has proven it is sounding. Every engine callback is issued by the test.
 */
class AlertVoiceTest : VoiceTestBase() {

    /** A call that must play the tone and say nothing (engine not ready, setting off, rescue siren sounding). */
    private fun toneOnly() {
        val b = beeps()
        val s = sirens()
        AlertSoundPlayer.playWarning(app)
        assertEquals(b + 1, beeps())
        AlertSoundPlayer.stopSound()
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(s + 1, sirens())
        AlertSoundPlayer.stopSound()
        assertTrue(said().isEmpty())
    }

    @Test fun `준비 전에는 경고음과 사이렌만 나고 말하지 않는다`() {
        assertEquals(AlertVoice.STATUS_IDLE, AlertVoice.status)
        toneOnly()
        AlertVoice.start(app)  // engine created, onInit not delivered yet
        assertEquals(AlertVoice.STATUS_CHECKING, AlertVoice.status)
        toneOnly()
    }

    @Test fun `음성 설정이 꺼져 있으면 경고음과 사이렌만 나고 말하지 않는다`() {
        ready()
        DevSettings.voiceAlertEnabled = false
        toneOnly()
    }

    @Test fun `구조 요청 사이렌이 울리는 동안은 경고음과 사이렌만 나고 말하지 않는다`() {
        ready()
        AlarmVolumeShare.sosSounding = true
        toneOnly()
    }

    @Test fun `위험 - 사이렌이 먼저 울리고 음성이 증명된 동안만 가린다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(listOf(AlertVoice.DANGER), said())
        assertEquals(TextToSpeech.QUEUE_FLUSH, Rec.said[0].mode)
        assertEquals(1, sirens())
        assertEquals(0, hushes())
        val id = lastId()

        started(id)  // onStart is not a proof
        idleFor(600)
        assertEquals(2, sirens())
        assertEquals(0, hushes())

        audio(id)
        assertEquals(1, hushes())
        val sirensWhileCovered = sirens()
        idleFor(1800)  // three repeat ticks, none of them sounds
        assertEquals(sirensWhileCovered, sirens())

        AlertSoundPlayer.playDanger(app)  // the 2 s re-alert must not restart the sentence or the siren
        idle()
        assertEquals(1, said().size)
        assertEquals(sirensWhileCovered, sirens())

        done(id)
        idleFor(399)
        assertEquals(1, said().size)
        idleFor(1)
        assertEquals(listOf(AlertVoice.DANGER, AlertVoice.DANGER), said())
        assertEquals(sirensWhileCovered, sirens())  // no siren in the 400 ms between sentences

        AlertSoundPlayer.stopSound()
        val tonesAfterStop = Rec.tones.size
        idleFor(5_000)
        assertEquals(2, said().size)
        assertEquals(tonesAfterStop, Rec.tones.size)
    }

    @Test fun `경고 - 삑이 그 호출에서 나고 첫 오디오에 끄고 문장 중에는 다시 울리지 않는다`() {
        ready()
        AlertSoundPlayer.playWarning(app)
        assertEquals(1, beeps())
        assertEquals(listOf(AlertVoice.WARNING), said())
        assertEquals(0, hushes())
        val id = lastId()
        audio(id)
        assertEquals(1, hushes())

        idleFor(1_100)  // the beep guard (1 s) is over
        AlertSoundPlayer.playWarning(app)
        assertEquals(1, beeps())
        assertEquals(1, said().size)

        done(id)  // proven for more than 400 ms
        AlertSoundPlayer.playWarning(app)
        assertEquals(2, beeps())
        assertEquals(listOf(AlertVoice.WARNING, AlertVoice.WARNING), said())
    }

    @Test fun `급접근 - 급접근 문구가 먼저이고 위험 문구로 이어지며 5초 안에는 끼어들지 않는다`() {
        ready()
        AlertSoundPlayer.playDanger(app, rapid = true)
        idle()
        assertEquals(listOf(AlertVoice.RAPID), said())
        audio()
        nextSentenceProven()
        assertEquals(listOf(AlertVoice.RAPID, AlertVoice.DANGER), said())

        AlertSoundPlayer.playDanger(app, rapid = true)  // about 1 s after the last RAPID
        idle()
        assertEquals(2, said().size)
        repeat(4) { nextSentenceProven() }  // 4.5 s after the first RAPID
        assertEquals(1, said().count { it == AlertVoice.RAPID })

        idleFor(600)  // now older than 5 s
        AlertSoundPlayer.playDanger(app, rapid = true)
        idle()
        assertEquals(2, said().count { it == AlertVoice.RAPID })
        assertEquals(AlertVoice.RAPID, said().last())
    }

    @Test fun `경고 문장이 가리는 중에 급접근이 오면 그 자리에서 바꾸고 사이렌이 예전대로 시작한다`() {
        ready()
        AlertSoundPlayer.playWarning(app)
        audio()
        assertTrue(AlertVoice.covering())
        assertEquals(0, sirens())

        AlertSoundPlayer.playDanger(app, rapid = true)
        idle()
        assertEquals(listOf(AlertVoice.WARNING, AlertVoice.RAPID), said())
        assertFalse(AlertVoice.covering())
        assertEquals(1, sirens())
    }

    @Test fun `판정이 깜박이는 동안은 톤만 나고 1,2초 뒤에 다시 말한다`() {
        ready()
        AlertSoundPlayer.playDanger(app, rapid = true)
        idle()
        assertEquals(1, said().size)
        idleFor(200)
        AlertSoundPlayer.stopSound()  // cut within 700 ms of the request: flicker guard for 1,2 s
        repeat(3) {
            val b = beeps()
            val s = sirens()
            AlertSoundPlayer.playWarning(app)
            assertEquals(b + 1, beeps())
            AlertSoundPlayer.playDanger(app, rapid = true)
            idle()
            assertEquals(s + 1, sirens())
            idleFor(200)
            AlertSoundPlayer.stopSound()
        }
        assertEquals(1, said().size)

        idleFor(700)  // past the 1,2 s counted from the first stop
        AlertSoundPlayer.playDanger(app, rapid = true)
        idle()
        assertEquals(listOf(AlertVoice.RAPID, AlertVoice.DANGER), said())  // the rapid clock survived the flicker
    }

    @Test fun `반복 문장 경계에서 설정을 끄면 다음 문장은 없고 사이렌이 이어지며 냉각은 없다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idle()
        audio()
        idleFor(500)
        DevSettings.voiceAlertEnabled = false
        done()
        val before = sirens()
        idleFor(400)
        assertEquals(1, said().size)
        idleFor(600)
        assertTrue(sirens() > before)
        assertFalse(AlertVoice.degraded)

        DevSettings.voiceAlertEnabled = true
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(2, said().size)
    }

    @Test fun `반복 중 재진입에서 설정을 끄면 음성이 멈추고 사이렌이 이어지며 냉각은 없다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idle()
        audio()
        DevSettings.voiceAlertEnabled = false
        AlertSoundPlayer.playDanger(app)
        idle()
        assertTrue(engine().isStopped)
        assertFalse(AlertVoice.covering())
        assertEquals(1, said().size)
        assertTrue(sirens() >= 2)
        assertFalse(AlertVoice.degraded)

        DevSettings.voiceAlertEnabled = true
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(2, said().size)
    }

    private fun gate(voice: Voice?, korean: Boolean = true): AlertVoice.State {
        Rec.voice = voice
        ready(korean)
        return AlertVoice.state
    }

    private fun koVoice(network: Boolean = false, features: Set<String> = emptySet(), locale: Locale = Locale.KOREA) =
        Voice("ko-test", locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, network, features)

    @Test fun `네트워크 음성은 쓰지 않는다`() {
        assertEquals(AlertVoice.State.NO_KOREAN, gate(koVoice(network = true)))
        assertEquals(AlertVoice.STATUS_NO_KOREAN, AlertVoice.status)
        assertTrue(engine().isShutdown)
    }

    @Test fun `설치되지 않은 것으로 표시된 음성은 쓰지 않는다`() {
        val missing = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        assertEquals(AlertVoice.State.NO_KOREAN, gate(koVoice(features = missing)))
        assertTrue(engine().isShutdown)
    }

    @Test fun `한국어가 아닌 음성은 쓰지 않는다`() {
        assertEquals(AlertVoice.State.NO_KOREAN, gate(koVoice(locale = Locale.ENGLISH)))
    }

    @Test fun `한국어 지원이 없으면 쓰지 않는다`() {
        assertEquals(AlertVoice.State.NO_KOREAN, gate(null, korean = false))
        assertTrue(engine().isShutdown)
    }

    @Test fun `음성 속성을 알 수 없으면 통과하고 기기 안 한국어 음성은 준비 완료이다`() {
        assertEquals(AlertVoice.State.READY, gate(null))
        AlertVoice.shutdown()
        Rec.clear()
        assertEquals(AlertVoice.State.READY, gate(koVoice()))
        assertFalse(engine().isShutdown)
    }

    @Test fun `엔진 초기화 실패는 엔진 없음이고 문구가 한국어 없음과 다르며 5분 뒤 다시 만든다`() {
        ready(code = TextToSpeech.ERROR)
        assertEquals(AlertVoice.State.NO_ENGINE, AlertVoice.state)
        assertEquals(AlertVoice.STATUS_NO_ENGINE, AlertVoice.status)
        assertNotEquals(AlertVoice.STATUS_NO_KOREAN, AlertVoice.STATUS_NO_ENGINE)
        assertTrue(engine().isShutdown)
        val first = newest()
        idleFor(299_000)
        assertTrue(first === newest())
        idleFor(1_001)
        assertTrue(first !== newest())
        assertEquals(AlertVoice.State.CHECKING, AlertVoice.state)
    }

    @Test fun `한국어 음성이 없는 상태도 5분 뒤 다시 확인한다`() {
        ready(korean = false)
        assertEquals(AlertVoice.State.NO_KOREAN, AlertVoice.state)
        val first = newest()
        idleFor(300_001)
        assertTrue(first !== newest())
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)  // the voice data was downloaded meanwhile
        engine().onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals(AlertVoice.State.READY, AlertVoice.state)
    }

    @Test fun `상태 문구와 degraded와 onChange가 설정 냉각 상태를 따라간다`() {
        var changes = 0
        AlertVoice.onChange = { changes++ }
        assertEquals(AlertVoice.STATUS_IDLE, AlertVoice.status)
        assertFalse(AlertVoice.degraded)
        ready()
        assertEquals(AlertVoice.STATUS_READY, AlertVoice.status)
        assertTrue(changes >= 2)  // CHECKING, READY

        DevSettings.voiceAlertEnabled = false
        assertEquals(AlertVoice.STATUS_OFF, AlertVoice.status)
        assertFalse(AlertVoice.degraded)
        DevSettings.voiceAlertEnabled = true
        val c0 = changes
        AlertVoice.changed()
        assertEquals(c0 + 1, changes)

        AlertSoundPlayer.playDanger(app)  // no audio ever: the start watch fails it after 1,5 s
        idleFor(1_500)
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        assertTrue(AlertVoice.degraded)
        assertTrue(changes > c0 + 1)
        DevSettings.voiceAlertEnabled = false
        assertFalse(AlertVoice.degraded)
        DevSettings.voiceAlertEnabled = true

        val c1 = changes
        idleFor(60_000)
        assertTrue(changes > c1)  // the cooldown ended
        assertEquals(AlertVoice.STATUS_READY, AlertVoice.status)
        assertFalse(AlertVoice.degraded)
    }

    @Test fun `시스템 결함 삑은 말하지 않는다`() {
        ready()
        AlertSoundPlayer.warningTone(app)
        assertEquals(1, beeps())
        assertTrue(said().isEmpty())
    }

    @Test fun `실제 판정에 정속 접근 프레임을 넣으면 급접근 문구가 말하기와 사이렌에 닿는다`() {
        val service = BleServiceTestHarness.newService()
        DevSettings.soundEnabled = true  // the harness silences the sound; this test needs the real path
        try {
            ready()
            BleServiceTestHarness.resetBetweenTests(service)
            for (f in 0 until 50) {
                BleServiceTestHarness.callProcessAlert(
                    service, "AA:BB:CC:DD:09:14", minOf(-90 + (f * 3) / 2, -45), nowMs = 1_000_000L + f * 120L,
                )
                idle()
            }
            assertTrue("급접근 문구가 말하기에 닿지 않았다: ${said()}", said().contains(AlertVoice.RAPID))
            assertTrue(sirens() >= 1)
        } finally {
            DevSettings.soundEnabled = false
        }
    }

    /** Is the occurrence at idx inside an io block (or inside one of the functions that only run in io)? */
    private fun inIo(body: String, idx: Int): Boolean {
        var depth = 0
        var i = idx - 1
        while (i >= 0) {
            val c = body[i]
            if (c == '}') depth++
            else if (c == '{') {
                if (depth > 0) depth--
                else {
                    val head = body.substring(0, i).substringAfterLast('\n').trimEnd()
                    if (head.endsWith("io")) return true
                    if (Regex("""fun (dropEngine|configure)\(""").containsMatchIn(head)) return true
                }
            }
            i--
        }
        return false
    }

    @Test fun `배선 - 엔진 호출은 전부 작업 스레드 블록 안이고 톤을 시작하는 호출이 없다`() {
        val voice = noComments(serviceSource("AlertVoice.kt"))
        // anchors: the things that must be there, so the "must not" checks below cannot pass on an empty body
        for (must in listOf(
            "AlertSoundPlayer.hushForSpeech()", "USAGE_ALARM", "CONTENT_TYPE_SPEECH", "QUEUE_FLUSH",
            "setSpeechRate(1.0f)", "setPitch(1.0f)", "isNetworkConnectionRequired", "KEY_FEATURE_NOT_INSTALLED",
            "onAudioAvailable", "HandlerThread(\"AlertVoice\")",
        )) assertTrue(must, voice.contains(must))
        for (never in listOf("dangerTone(", "warningTone(", "playWarning(", "playDanger(", "stopSound(")) {
            assertFalse("AlertVoice starts a tone with $never", voice.contains(never))
        }
        assertEquals(1, Regex("AlertSoundPlayer\\.").findAll(voice).count())

        val engineCalls = Regex("""\b(t|ttsRef\??)\.(speak|stop|shutdown|setLanguage|voice|setAudioAttributes|setSpeechRate|setPitch|setOnUtteranceProgressListener)\b|TextToSpeech\(app\)""")
            .findAll(voice).toList()
        assertTrue(engineCalls.size >= 9)
        for (m in engineCalls) assertTrue("engine call outside io: ${m.value}", inIo(voice, m.range.first))
        val helpers = Regex("""(?<!fun )\b(dropEngine|configure)\(""").findAll(voice).toList()
        assertTrue(helpers.size >= 4)
        for (m in helpers) assertTrue("engine helper outside io: ${m.value}", inIo(voice, m.range.first))
    }

    @Test fun `배선 - 급접근 호출과 시작 정리 알림 톤 진입점 설정 화면 문구`() {
        val asm = noComments(serviceSource("AlertStateMachine.kt"))
        val ttc = noComments(repoFile("app/src/main/java/com/wf11/safealert/03_service/AlertStateMachine.kt").let { raw ->
            val from = raw.indexOf("TTC 선발령")
            assertTrue(from >= 0)
            raw.substring(from, raw.indexOf("fx.updateFloatingOverlay()", from))
        })
        assertTrue(ttc.contains("fx.playRapidApproach()"))
        assertEquals(1, Regex("fx\\.playRapidApproach\\(\\)").findAll(asm).count())
        assertTrue(asm.contains("fun playRapidApproach() = playDanger()"))

        val ble = noComments(serviceSource("BleService.kt"))
        assertTrue(ble.contains("AlertVoice.start(this)"))
        assertTrue(ble.contains("AlertVoice.onChange = { refreshNotification() }"))
        val stopAll = sourceBlock(ble, "private fun stopAll()")
        assertTrue(stopAll.contains("AlertVoice.shutdown()"))
        assertTrue(stopAll.contains("AlertVoice.onChange = null"))
        assertTrue(ble.contains("override fun playRapidApproach() = AlertSoundPlayer.playDanger(this@BleService, rapid = true)"))
        assertTrue(ble.contains("runCatching { AlertSoundPlayer.warningTone(this) }"))
        assertFalse(ble.contains("playWarning(this, voice"))
        assertTrue(ble.contains("AlertVoice.degraded && DevSettings.soundEnabled"))
        assertTrue(ble.contains("음성 대신 경고음"))

        val asp = noComments(serviceSource("AlertSoundPlayer.kt"))
        assertTrue(asp.contains("if (!AlertVoice.covering()) tg.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 400)"))
        assertTrue(sourceBlock(asp, "fun playWarning(").contains("AlertVoice.say()"))
        assertTrue(sourceBlock(asp, "fun playDanger(").contains("AlertVoice.loop(rapid)"))
        assertTrue(sourceBlock(asp, "internal fun hushForSpeech").contains("stopTone()"))
        val warning = sourceBlock(asp, "internal fun warningTone")
        assertTrue(warning.contains("TONE_CDMA_ALERT_CALL_GUARD"))
        assertFalse("warningTone asks the voice", warning.contains("AlertVoice"))
        val danger = sourceBlock(asp, "internal fun dangerTone")
        assertTrue(danger.contains("TONE_CDMA_EMERGENCY_RINGBACK"))
        for (never in listOf("AlertVoice.say(", "AlertVoice.loop(", "AlertVoice.stop(", "stopSound(")) {
            assertFalse("dangerTone has $never", danger.contains(never))
        }
        val stopTone = sourceBlock(asp, "private fun stopTone")
        assertFalse(stopTone.contains("AlertVoice"))

        val settings = noComments(repoFile("app/src/main/java/com/wf11/safealert/05_ui/DevSettingsActivity.kt"))
        assertTrue(Regex("AlertVoice\\.changed\\(\\)").findAll(settings).count() >= 2)
        assertTrue(sourceBlock(settings, "private val uwbDiagPoller").contains("AlertVoice.status"))

        val layout = repoFile("app/src/main/res/layout/activity_dev_settings.xml")
        assertTrue(layout.contains("경보 소리 사용 (경고음 · 음성)"))
        assertFalse(layout.contains("\"경보음 사용\""))

        val manifest = repoFile("app/src/main/AndroidManifest.xml")
        val queries = manifest.substring(manifest.indexOf("<queries>"), manifest.indexOf("</queries>"))
        assertTrue(queries.contains("android.intent.action.TTS_SERVICE"))

        val news = repoFile("app/src/main/res/values/strings.xml").let { it.substring(it.indexOf("name=\"whats_new\"")) }
        val first = news.substringBefore("\\n- ")
        assertTrue(first.contains(AlertVoice.WARNING) && first.contains(AlertVoice.DANGER) && first.contains(AlertVoice.RAPID))
        assertTrue(first.contains("음성 안내 사용") && first.contains("경보 소리 사용"))
    }
}
