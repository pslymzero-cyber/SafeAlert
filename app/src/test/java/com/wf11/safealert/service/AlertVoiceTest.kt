package com.wf11.safealert.service

import android.media.ToneGenerator
import android.os.Looper
import com.wf11.safealert.R
import com.wf11.safealert.support.BleServiceTestHarness
import com.wf11.safealert.utils.DevSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** What the recording tone generator saw. */
internal object Rec {
    /** "new" for every construction, "start:<tone type>" for every startTone, "stop" for every stopTone (a hush). */
    val tones = mutableListOf<String>()

    fun clear() = tones.clear()
}

/** ToneGenerator that records which tone started, every stopTone and every construction. */
@Implements(ToneGenerator::class)
class RecordingTone {
    @Implementation
    fun __constructor__(streamType: Int, volume: Int) {
        Rec.tones += "new"
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

/** A player that records its calls (the clip numbers) and answers what the test says; alive stays true until the test changes it. */
internal class FakePlayer(private val lengths: LongArray = longArrayOf(3_660, 1_440, 1_990)) : ClipPlayer {
    val calls = mutableListOf<Int>()
    var playResult = true
    var playThrows = false
    var stops = 0
    var released = false
    override var alive = false

    override fun play(clip: Int): Boolean {
        calls += clip
        if (playThrows) throw IllegalStateException("scripted")
        alive = playResult
        return playResult
    }

    override fun stop() { stops++; alive = false }
    override fun durationMs(clip: Int) = lengths[clip]
    override fun release() { released = true; alive = false }
}

/** Shared setup: fake players, recording tones, the clock only moves when the test moves it, no state leaks. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [RecordingTone::class])
abstract class VoiceTestBase {
    protected val app get() = RuntimeEnvironment.getApplication()
    private val defaultFactory = AlertVoice.playerFactory

    /** Every player the factory made, in order; a null answer of [makeNext] makes none. */
    internal val made = mutableListOf<FakePlayer>()
    internal var makeNext: () -> FakePlayer? = { FakePlayer() }
    protected var factoryCalls = 0

    @Before fun setUp() = reset()

    @After fun tearDown() {
        reset()
        AlertVoice.playerFactory = defaultFactory
    }

    private fun reset() {
        AlertVoice.shutdown()
        AlertSoundPlayer.stopSound()
        DevSettings.init(app)
        DevSettings.soundEnabled = true
        DevSettings.voiceAlertEnabled = true
        AlarmVolumeShare.sosSounding = false
        AlertVoice.onChange = null
        Rec.clear()
        ShadowAudioTrack.resetTest()
        made.clear()
        makeNext = { FakePlayer() }
        factoryCalls = 0
        AlertVoice.playerFactory = {
            factoryCalls++
            makeNext()?.also { made += it }
        }
    }

    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()
    protected fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    protected fun sirens() = Rec.tones.count { it == "start:${ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK}" }
    protected fun beeps() = Rec.tones.count { it == "start:${ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD}" }
    protected fun toneObjects() = Rec.tones.count { it == "new" }

    protected fun ready() { AlertVoice.start(app); idle() }
    internal fun player() = made.last()
    internal fun clips() = player().calls.toList()
}

/**
 * The tone is the baseline (same instant, same code as before); a recording hides it only while the player really
 * renders it. The players are fakes, the tone generator records, and the clock is the looper's.
 */
class AlertVoiceTest : VoiceTestBase() {

    private val CW = AlertVoice.CLIP_WARNING
    private val CD = AlertVoice.CLIP_DANGER
    private val CR = AlertVoice.CLIP_RAPID

    /** A call that must play the tone and ask no player for anything (not started, no player, setting off, rescue siren). */
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
        assertTrue(made.all { it.calls.isEmpty() })
    }

    @Test fun `감시 전에는 경고음과 사이렌만 나고 재생 요청이 없다`() {
        assertEquals(AlertVoice.STATUS_IDLE, AlertVoice.status)
        toneOnly()
    }

    @Test fun `재생기를 만들지 못하면 경고음과 사이렌만 나고 재생 요청이 없다`() {
        makeNext = { null }
        ready()
        assertEquals(AlertVoice.STATUS_NOT_READY, AlertVoice.status)
        assertTrue(AlertVoice.degraded)
        toneOnly()
    }

    @Test fun `음성 설정이 꺼져 있으면 경고음과 사이렌만 나고 재생 요청이 없다`() {
        ready()
        DevSettings.voiceAlertEnabled = false
        toneOnly()
    }

    @Test fun `구조 요청 사이렌이 울리는 동안은 경고음과 사이렌만 나고 재생 요청이 없다`() {
        ready()
        AlarmVolumeShare.sosSounding = true
        toneOnly()
    }

    @Test fun `경고는 문장을 한 번 재생하고 그 호출에서 삑이 없고 끝난 뒤에 다시 안내한다`() {
        ready()
        AlertSoundPlayer.playWarning(app)
        assertEquals(listOf(CW), clips())
        assertEquals(0, beeps())
        assertEquals("a covered warning still obtains the tone generator", 1, toneObjects())
        assertTrue(AlertVoice.covering())

        idleFor(2_000)
        AlertSoundPlayer.playWarning(app)  // still playing: not restarted, no beep
        assertEquals(1, player().calls.size)
        assertEquals(0, beeps())

        idleFor(1_700)  // 3.7 s after the start: the sentence is over
        assertFalse(AlertVoice.covering())
        AlertSoundPlayer.playWarning(app)
        assertEquals(listOf(CW, CW), clips())
        assertEquals(0, beeps())
    }

    @Test fun `위험은 문장을 반복 재생하고 사이렌 조각은 소리 없이 건너뛰며 재진입에 다시 걸지 않는다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(listOf(CD), clips())
        assertTrue(AlertVoice.covering())
        assertEquals(0, sirens())
        assertEquals(1, toneObjects())

        idleFor(2_000)
        assertEquals("the repeat keeps ticking without a sound", 0, sirens())
        AlertSoundPlayer.playDanger(app)  // re-entry
        idle()
        assertEquals(1, player().calls.size)
        assertEquals("no new tone generator for a re-entry", 1, toneObjects())
        assertEquals(0, sirens())

        AlertSoundPlayer.stopSound()
        assertTrue(player().stops >= 1)
        assertFalse(AlertVoice.covering())
        idleFor(3_000)
        assertEquals(0, sirens())
        assertEquals(1, player().calls.size)
    }

    @Test fun `급접근은 급접근 문장 한 번 뒤 0점4초 쉬고 위험 반복으로 이어지며 그 사이 사이렌이 없다`() {
        ready()
        AlertSoundPlayer.playDanger(app, rapid = true)
        idle()
        assertEquals(listOf(CR), clips())
        idleFor(1_989 + 399)
        assertEquals(1, player().calls.size)
        assertEquals(0, sirens())
        assertTrue(AlertVoice.covering())
        idleFor(2)
        assertEquals(listOf(CR, CD), clips())
        idleFor(3_000)
        assertEquals(0, sirens())
        assertTrue(AlertVoice.covering())
    }

    @Test fun `급접근 문장은 끝까지 나온 뒤 시작부터 5초 안에는 끼어들지 않고 5초 뒤에는 위험 반복을 교체한다`() {
        ready()
        AlertSoundPlayer.playDanger(app, rapid = true)
        idleFor(3_000)  // the danger repeat runs since 2.39 s
        AlertSoundPlayer.playDanger(app, rapid = true)
        assertEquals(listOf(CR, CD), clips())
        idleFor(2_200)  // 5.2 s after the start of the RAPID sentence
        AlertSoundPlayer.playDanger(app, rapid = true)
        assertEquals(listOf(CR, CD, CR), clips())
    }

    @Test fun `급접근 문장이 나오는 동안 들어온 급접근 요청은 쌓이지 않아 문장 뒤 위험 반복에서 급접근이 다시 나오지 않는다`() {
        ready()
        AlertSoundPlayer.playDanger(app, rapid = true)
        idleFor(500)
        AlertSoundPlayer.playDanger(app, rapid = true)  // during the sentence: nothing is queued
        idleFor(2_000)
        AlertSoundPlayer.playDanger(app)
        idleFor(1_000)
        assertEquals(listOf(CR, CD), clips())
    }

    @Test fun `경고 재생 중 급접근이 오면 즉시 급접근 문장으로 교체한다`() {
        ready()
        AlertSoundPlayer.playWarning(app)
        idleFor(500)
        AlertSoundPlayer.playDanger(app, rapid = true)
        assertEquals(listOf(CW, CR), clips())
        assertEquals(0, beeps())
    }

    @Test fun `급접근 문장 도중 소리가 멈추면 위험 전환 타이머도 지워져 아무것도 재생되지 않는다`() {
        ready()
        AlertSoundPlayer.playDanger(app, rapid = true)
        idleFor(1_000)
        AlertSoundPlayer.stopSound()
        val stopsAfter = player().stops
        assertTrue(stopsAfter >= 1)
        idleFor(5_000)
        assertEquals(1, player().calls.size)
        assertEquals(stopsAfter, player().stops)
    }

    @Test fun `시스템 결함 삑은 재생을 요청하지 않는다`() {
        ready()
        AlertSoundPlayer.warningTone(app)
        assertEquals(1, beeps())
        assertTrue(player().calls.isEmpty())
    }

    // ---- what a started clip does to a sounding tone ----------------------------------------------------

    @Test fun `경고 문장은 먼저 울린 결함 삑을 끊지 않고 위험 문장은 울리던 사이렌 조각을 끈다`() {
        ready()
        AlertSoundPlayer.warningTone(app)  // the one-time system-fault beep
        idleFor(200)
        AlertSoundPlayer.playWarning(app)
        assertEquals(listOf(CW), clips())
        assertFalse("a warning sentence must not cut the fault beep", Rec.tones.contains("stop"))

        AlertSoundPlayer.stopSound()
        idleFor(2_000)
        DevSettings.voiceAlertEnabled = false
        AlertSoundPlayer.playDanger(app)  // a siren piece is sounding
        idle()
        assertEquals(1, sirens())
        DevSettings.voiceAlertEnabled = true
        AlertSoundPlayer.playDanger(app)  // re-entry: the danger clip starts
        assertEquals(listOf(CW, CD), clips())
        assertEquals("stop", Rec.tones.last())

        AlertSoundPlayer.stopSound()
        idleFor(2_000)
        Rec.clear()
        AlertSoundPlayer.playDanger(app, rapid = true)
        idleFor(2_389)
        assertEquals("the hand-over from RAPID to DANGER has not happened yet", 0, Rec.tones.count { it == "stop" })
        idleFor(1)
        assertEquals("the hand-over hushes the tone once", 1, Rec.tones.count { it == "stop" })
    }

    @Test fun `위험 반복 중의 경고 요청은 재생기를 건드리지 않는다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idleFor(1_000)
        AlertSoundPlayer.playWarning(app)
        assertEquals(1, player().calls.size)
        assertEquals(0, player().stops)
        assertEquals(0, beeps())
    }

    @Test fun `혼자 끝난 경고 문장은 재생기를 한 번 멈춘다`() {
        ready()
        AlertSoundPlayer.playWarning(app)
        idleFor(3_660)
        assertEquals(1, player().stops)
        idleFor(2_000)
        assertFalse(AlertVoice.covering())
        assertEquals(1, player().stops)
    }

    @Test fun `가림은 타이머가 돌지 않아도 시각만으로 끝난다`() {
        ready()
        AlertSoundPlayer.playWarning(app)
        ShadowSystemClock.advanceBy(Duration.ofMillis(3_659))  // the looper does not run
        assertTrue(AlertVoice.covering())
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        assertFalse(AlertVoice.covering())

        AlertSoundPlayer.stopSound()
        idle()
        AlertSoundPlayer.playDanger(app, rapid = true)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_990 + 400 + 299))
        assertTrue(AlertVoice.covering())
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        assertFalse(AlertVoice.covering())
    }

    // ---- the recorded files ----------------------------------------------------------------------------

    private fun wav(id: Int) = TrackPlayer.parseWav(app.resources.openRawResource(id).use { it.readBytes() })

    @Test fun `res raw 의 음성 파일 3개는 16비트 모노이고 길이가 0점5초에서 6초 사이다`() {
        for (id in listOf(R.raw.voice_warning, R.raw.voice_danger, R.raw.voice_rapid)) {
            val w = wav(id)
            assertNotNull(w)
            assertTrue("rate ${w!!.sampleRate}", w.sampleRate in 8_000..48_000)
            assertTrue("length ${w.durationMs}", w.durationMs in 500..6_000)
        }
    }

    @Test fun `WAV 해석은 형식이 틀리거나 크기가 파일을 넘으면 null 이다`() {
        fun le(v: Int, n: Int) = ByteArray(n) { ((v shr (8 * it)) and 0xFF).toByte() }
        fun chunk(id: String, body: ByteArray) =
            id.toByteArray(Charsets.US_ASCII) + le(body.size, 4) + body + (if (body.size % 2 == 1) byteArrayOf(0) else byteArrayOf())
        fun fmt(channels: Int = 1, bits: Int = 16, format: Int = 1) =
            chunk("fmt ", le(format, 2) + le(channels, 2) + le(24_000, 4) + le(24_000 * 2, 4) + le(2, 2) + le(bits, 2))
        fun riff(vararg chunks: ByteArray) = "RIFF".toByteArray() + le(4 + chunks.sumOf { it.size }, 4) + "WAVE".toByteArray() +
            chunks.fold(byteArrayOf()) { a, c -> a + c }

        val pcm = ByteArray(4800)
        val ok = TrackPlayer.parseWav(riff(fmt(), chunk("data", pcm)))
        assertNotNull(ok)
        assertEquals(2400, ok!!.frames)
        assertEquals(100L, ok.durationMs)
        // a chunk before data (odd size, padded) is skipped
        assertNotNull(TrackPlayer.parseWav(riff(fmt(), chunk("LIST", ByteArray(5)), chunk("data", pcm))))
        assertNull(TrackPlayer.parseWav(riff(fmt(channels = 2), chunk("data", pcm))))
        assertNull(TrackPlayer.parseWav(riff(fmt(bits = 8), chunk("data", pcm))))
        assertNull(TrackPlayer.parseWav(riff(fmt(format = 3), chunk("data", pcm))))
        assertNull(TrackPlayer.parseWav(riff(chunk("data", pcm))))                    // no fmt
        assertNull(TrackPlayer.parseWav(riff(fmt())))                                  // no data
        val whole = riff(fmt(), chunk("data", pcm))
        assertNull(TrackPlayer.parseWav(whole.copyOf(whole.size - 10)))               // data longer than the file
        val huge = whole.copyOf()
        huge[huge.size - 4800 - 1] = 0x80.toByte()                                     // top bit of the data size
        assertNull(TrackPlayer.parseWav(huge))
        assertNull(TrackPlayer.parseWav("RIFX".toByteArray() + whole.copyOfRange(4, whole.size)))
        assertNull(TrackPlayer.parseWav(ByteArray(5)))
    }

    // ---- the real player: the playback position is the proof ---------------------------------------------

    @Test fun `재생 위치 판정은 지연 허용 안에서는 참이고 위치가 시계를 못 따라오면 거짓이다`() {
        val rate = 24_000
        val total = 87_840
        fun once(head: Long, ms: Long) = TrackPlayer.keepsUp(head, ms, rate, total, repeats = false)
        fun repeat(head: Long, ms: Long, len: Int = 44_040) = TrackPlayer.keepsUp(head, ms, rate, len, repeats = true)
        assertTrue("inside the lag, nothing rendered yet", once(0, 400))
        assertTrue("on time", once(24_000, 1_000))
        assertFalse("frozen for the lag plus 100 ms", once(24_000, 1_600))
        assertFalse("nothing rendered after 600 ms", once(0, 600))
        assertTrue("a finished one-shot long after its end", once(total.toLong(), 60_000))
        assertFalse("a one-shot that stopped short of its end", once(75_839, 60_000))
        assertTrue("a repeat far past its own length with the head on time", repeat(1_440_000, 60_000))
        assertFalse("a repeat whose head froze at its length", repeat(44_040, 60_000))
    }

    @Test fun `실제 재생기는 파일 3개로 만들어지고 재생 상태와 정지를 따른다`() {
        val p = TrackPlayer.create(app)
        assertNotNull(p)
        p!!
        for (c in listOf(CW, CD, CR)) assertTrue("clip $c ${p.durationMs(c)}", p.durationMs(c) in 500..6_000)
        assertFalse(p.alive)

        assertTrue(p.play(CW))
        assertTrue(p.alive)
        assertTrue(p.play(CD))  // replaces the warning, repeats
        assertTrue(p.alive)
        assertTrue(p.play(CR))
        assertTrue(p.play(CD))  // played again after a stop and reload
        p.stop()
        assertFalse(p.alive)

        assertFalse(p.play(7))

        p.release()
        assertFalse(p.alive)
        assertFalse(p.play(CW))
    }

    /**
     * Under Robolectric the shadow's playbackHeadPosition is the number of frames written to the track (the whole
     * clip, from the start, and it does not move with the clock). So a one-shot always looks fully rendered, and the
     * danger repeat is alive until the clock passes its length (sentence + REPEAT_GAP_MS) + RENDER_LAG_MS.
     */
    @Test fun `실제 재생기는 재생 직후 살아 있고 위험 반복은 재생 위치가 시계를 따라오지 못하면 죽은 것으로 본다`() {
        val p = TrackPlayer.create(app)!!
        assertTrue(p.play(CD))
        assertTrue("alive right after play", p.alive)
        val lifeMs = p.durationMs(CD) + AlertVoice.REPEAT_GAP_MS + AlertVoice.RENDER_LAG_MS
        idleFor(lifeMs - 20)
        assertTrue("still inside sentence + pause + lag", p.alive)
        idleFor(40)
        assertFalse("the position fell behind the clock", p.alive)

        assertTrue(p.play(CD))  // a new start time
        assertTrue(p.alive)

        assertTrue(p.play(CW))
        idleFor(60_000)
        assertTrue("a one-shot that was rendered to its end is over, not dead", p.alive)
        p.stop()
        assertFalse(p.alive)
        p.release()
    }

    @Test fun `실제 재생기는 재생 시작에서 예외가 나도 밖으로 새지 않고 거짓을 돌려준다`() {
        val p = TrackPlayer.create(app)!!
        ShadowAudioTrack.enableIllegalStateOnPlay(true)
        assertFalse(p.play(CW))
        assertFalse(p.alive)
        p.release()
    }

    // ---- wiring (comments stripped) --------------------------------------------------------------------

    @Test fun `배선 - 음성 객체는 엔진 의존이 없고 톤 쪽으로는 정해진 세 진입점만 부른다`() {
        val voice = noComments(serviceSource("AlertVoice.kt"))
        // anchors: the things that must be there, so the "must not" checks below cannot pass on an empty body
        for (must in listOf(
            "AlertSoundPlayer.hushForSpeech()", "R.raw.voice_warning", "R.raw.voice_danger", "R.raw.voice_rapid",
            "USAGE_ALARM", "CONTENT_TYPE_SPEECH", "MODE_STATIC", "reloadStaticData", "setLoopPoints(0, frames[clip], -1)",
            "playbackHeadPosition",
        )) assertTrue(must, voice.contains(must))
        for (never in listOf(
            "android.speech", "TextToSpeech", "playWarning(", "playDanger(", "stopSound(", "playState",
        )) assertFalse("AlertVoice has $never", voice.contains(never))
        val refs = Regex("AlertSoundPlayer\\.(\\w+\\()").findAll(voice).map { it.groupValues[1] }.toList()
        assertTrue(refs.isNotEmpty())
        assertTrue("tone-side calls: $refs", refs.all { it == "hushForSpeech(" || it == "warningTone(" || it == "dangerTone(" })
        assertEquals("the late beep is called in one place", 1, refs.count { it == "warningTone(" })
        assertTrue(sourceBlock(voice, "private val proof").contains("AlertSoundPlayer.warningTone("))
        val dangerCalls = refs.count { it == "dangerTone(" }
        assertTrue(dangerCalls >= 1)
        assertEquals(
            "the siren is started directly only where the hand-over from RAPID cannot start",
            dangerCalls, Regex("AlertSoundPlayer\\.dangerTone\\(").findAll(sourceBlock(voice, "private fun startDanger()")).count(),
        )
        // say() does not hush: it must not cut the one-time fault beep
        assertFalse(sourceBlock(voice, "fun say()").contains("hush()"))
    }

    @Test fun `배선 - 급접근 호출과 시작 정리 알림 톤 진입점 설정 화면 매니페스트 변경 사항 문구`() {
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
        assertTrue(ble.contains("AlertVoice.degraded && DevSettings.soundEnabled"))
        assertTrue(ble.contains("음성 대신 경고음"))

        val asp = noComments(serviceSource("AlertSoundPlayer.kt"))
        assertTrue(asp.contains("if (!AlertVoice.covering()) tg.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 400)"))
        val warningEntry = sourceBlock(asp, "fun playWarning(")
        val say = warningEntry.indexOf("AlertVoice.say()")
        val covering = warningEntry.indexOf("AlertVoice.covering()")
        val tone = warningEntry.indexOf("getOrCreateTone()")
        val beep = warningEntry.indexOf("warningTone(context)")
        assertTrue("say, covering, getOrCreateTone, warningTone in this order: $warningEntry", say in 0 until covering)
        assertTrue(covering < tone && tone < beep)
        val dangerEntry = sourceBlock(asp, "fun playDanger(")
        assertTrue(dangerEntry.contains("AlertVoice.loop(rapid)"))
        assertTrue(dangerEntry.contains("if (AlertVoice.covering() && sirenRunning()) return"))
        assertTrue(dangerEntry.contains("dangerTone(context)"))
        assertTrue(asp.contains("private fun sirenRunning() = isPlaying && repeatRunnable != null"))
        assertTrue(sourceBlock(asp, "internal fun hushForSpeech").contains("stopTone()"))
        val warning = sourceBlock(asp, "internal fun warningTone")
        assertTrue(warning.contains("TONE_CDMA_ALERT_CALL_GUARD"))
        assertFalse("warningTone asks the voice", warning.contains("AlertVoice"))
        val danger = sourceBlock(asp, "internal fun dangerTone")
        assertTrue(danger.contains("TONE_CDMA_EMERGENCY_RINGBACK"))
        for (never in listOf("AlertVoice.say(", "AlertVoice.loop(", "AlertVoice.stop(", "stopSound(")) {
            assertFalse("dangerTone has $never", danger.contains(never))
        }
        assertFalse(sourceBlock(asp, "private fun stopTone").contains("AlertVoice"))

        val settings = noComments(repoFile("app/src/main/java/com/wf11/safealert/05_ui/DevSettingsActivity.kt"))
        assertTrue(Regex("AlertVoice\\.changed\\(\\)").findAll(settings).count() >= 2)
        assertTrue(sourceBlock(settings, "private val uwbDiagPoller").contains("AlertVoice.status"))

        val layout = repoFile("app/src/main/res/layout/activity_dev_settings.xml")
        assertTrue(layout.contains("경보 소리 사용 (경고음 · 음성)"))
        assertFalse(layout.contains("\"경보음 사용\""))

        val manifest = repoFile("app/src/main/AndroidManifest.xml")
        assertFalse(manifest.contains("<queries>"))
        assertFalse(manifest.contains("TTS_SERVICE"))

        val news = repoFile("app/src/main/res/values/strings.xml").let { it.substring(it.indexOf("name=\"whats_new\"")) }
        val first = news.substringBefore("\\n- ")
        assertTrue(first.contains(AlertVoice.WARNING) && first.contains(AlertVoice.DANGER) && first.contains(AlertVoice.RAPID))
        assertTrue(first.contains("앱에 들어 있는 음성"))
        assertTrue(first.contains("음성 안내 사용") && first.contains("경보 소리 사용"))
        assertFalse(first.contains("한국어 음성"))
    }

    // ---- the real judgement ----------------------------------------------------------------------------

    @Test fun `실제 판정에 정속 접근 프레임을 넣으면 처음 시작되는 음성이 급접근 문장 한 번이다`() {
        val service = BleServiceTestHarness.newService()
        DevSettings.soundEnabled = true  // the harness silences the sound; this test needs the real path
        try {
            ready()
            BleServiceTestHarness.resetBetweenTests(service)
            val frames = mutableListOf<List<Int>>()
            var seen = 0
            for (f in 0 until 50) {
                BleServiceTestHarness.callProcessAlert(
                    service, "AA:BB:CC:DD:09:14", minOf(-90 + (f * 3) / 2, -45), nowMs = 1_000_000L + f * 120L,
                )
                idle()
                val all = made.flatMap { it.calls }
                frames += all.drop(seen)
                seen = all.size
            }
            // The judgement may legitimately sound a warning first; what matters is that the rapid sentence comes
            //   as one request in the frame where it first appears, with no danger clip started before it.
            val at = frames.indexOfFirst { CR in it }
            assertTrue("급접근 문장 재생 요청 없음, 프레임별 새 재생 요청: $frames", at >= 0)
            assertEquals("프레임별 새 재생 요청: $frames", listOf(CR), frames[at])
            assertFalse("급접근 앞에 위험 문장: $frames", frames.take(at).any { CD in it })
            assertEquals("프레임별 새 재생 요청: $frames", 1, frames.sumOf { f -> f.count { it == CR } })
        } finally {
            DevSettings.soundEnabled = false
        }
    }
}
