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

/** A player that records its calls and answers what the test says. A finished one-shot clip stays alive, like a static track. */
internal class FakePlayer(private val lengths: LongArray = longArrayOf(3_660, 1_440, 1_990)) : ClipPlayer {
    class Call(val clip: Int, val gap: Int)

    val calls = mutableListOf<Call>()
    var playResult = true
    var playThrows = false
    var stops = 0
    var released = false
    override var alive = false

    override fun play(clip: Int, loopGapMs: Int): Boolean {
        calls += Call(clip, loopGapMs)
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
    internal fun clips() = player().calls.map { it.clip to it.gap }
}

/**
 * The tone is the baseline (same instant, same code as before); a recording hides it only while the player really
 * plays it. The players are fakes, the tone generator records, and the clock is the looper's.
 */
class AlertVoiceTest : VoiceTestBase() {

    private val CW = AlertVoice.CLIP_WARNING
    private val CD = AlertVoice.CLIP_DANGER
    private val CR = AlertVoice.CLIP_RAPID

    /** A call that must play the tone and ask no player for anything (not started, no files, setting off, rescue siren). */
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

    @Test fun `음성 파일을 읽지 못하면 경고음과 사이렌만 나고 재생 요청이 없다`() {
        makeNext = { null }
        ready()
        assertEquals(AlertVoice.STATUS_NO_FILES, AlertVoice.status)
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
        assertEquals(listOf(CW to -1), clips())
        assertEquals(0, beeps())
        assertTrue(AlertVoice.covering())

        idleFor(2_000)
        AlertSoundPlayer.playWarning(app)  // still playing: not restarted, no beep
        assertEquals(1, player().calls.size)
        assertEquals(0, beeps())

        idleFor(1_700)  // 3.7 s after the start: the sentence is over
        assertFalse(AlertVoice.covering())
        AlertSoundPlayer.playWarning(app)
        assertEquals(listOf(CW to -1, CW to -1), clips())
        assertEquals(0, beeps())
    }

    @Test fun `위험은 문장을 반복 재생하고 사이렌 조각은 소리 없이 건너뛰며 재진입에 다시 걸지 않는다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(listOf(CD to AlertVoice.REPEAT_GAP_MS), clips())
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
        assertEquals(listOf(CR to -1), clips())
        idleFor(1_989 + 399)
        assertEquals(1, player().calls.size)
        assertEquals(0, sirens())
        assertTrue(AlertVoice.covering())
        idleFor(2)
        assertEquals(listOf(CR to -1, CD to AlertVoice.REPEAT_GAP_MS), clips())
        idleFor(3_000)
        assertEquals(0, sirens())
        assertTrue(AlertVoice.covering())
    }

    @Test fun `급접근 문장은 5초 안에는 끼어들지 않고 5초 뒤에는 위험 반복을 교체한다`() {
        ready()
        AlertSoundPlayer.playDanger(app, rapid = true)
        idleFor(3_000)  // the danger repeat runs since 2.39 s
        AlertSoundPlayer.playDanger(app, rapid = true)
        assertEquals(listOf(CR to -1, CD to AlertVoice.REPEAT_GAP_MS), clips())
        idleFor(2_200)  // 5.2 s after the last RAPID
        AlertSoundPlayer.playDanger(app, rapid = true)
        assertEquals(listOf(CR to -1, CD to AlertVoice.REPEAT_GAP_MS, CR to -1), clips())
    }

    @Test fun `경고 재생 중 급접근이 오면 즉시 급접근 문장으로 교체한다`() {
        ready()
        AlertSoundPlayer.playWarning(app)
        idleFor(500)
        AlertSoundPlayer.playDanger(app, rapid = true)
        assertEquals(listOf(CW to -1, CR to -1), clips())
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

    // ---- the recorded files ----------------------------------------------------------------------------

    private fun wav(id: Int) = TrackPlayer.parseWav(app.resources.openRawResource(id).use { it.readBytes() })

    @Test fun `res raw 의 음성 파일 3개는 16비트 모노 24000Hz 이고 길이가 문장에 맞다`() {
        val warning = wav(R.raw.voice_warning)
        val danger = wav(R.raw.voice_danger)
        val rapid = wav(R.raw.voice_rapid)
        assertNotNull(warning); assertNotNull(danger); assertNotNull(rapid)
        for (w in listOf(warning!!, danger!!, rapid!!)) assertEquals(24_000, w.sampleRate)
        assertTrue("warning ${warning.durationMs}", warning.durationMs in 3_400..3_900)
        assertTrue("danger ${danger.durationMs}", danger.durationMs in 1_300..1_600)
        assertTrue("rapid ${rapid.durationMs}", rapid.durationMs in 1_800..2_200)
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

    // ---- the real player on Robolectric's AudioTrack shadow -------------------------------------------

    @Test fun `실제 재생기는 파일 3개로 만들어지고 재생 중 상태와 정지를 따른다`() {
        val p = TrackPlayer.create(app)
        assertNotNull(p)
        p!!
        assertTrue(p.durationMs(CW) in 3_400..3_900)
        assertTrue(p.durationMs(CD) in 1_300..1_600)
        assertTrue(p.durationMs(CR) in 1_800..2_200)
        assertFalse(p.alive)

        assertTrue(p.play(CW, -1))
        assertTrue(p.alive)
        assertTrue(p.play(CD, AlertVoice.REPEAT_GAP_MS))  // replaces the warning, repeats
        assertTrue(p.alive)
        assertTrue(p.play(CR, -1))
        assertTrue(p.play(CD, AlertVoice.REPEAT_GAP_MS))  // played again after a stop and reload
        p.stop()
        assertFalse(p.alive)

        // only the pairs the tracks were built for
        assertFalse(p.play(CD, -1))
        assertFalse(p.play(CW, AlertVoice.REPEAT_GAP_MS))
        assertFalse(p.play(7, -1))

        p.release()
        assertFalse(p.alive)
        assertFalse(p.play(CW, -1))
    }

    @Test fun `실제 재생기는 재생 시작에서 예외가 나도 밖으로 새지 않고 거짓을 돌려준다`() {
        val p = TrackPlayer.create(app)!!
        ShadowAudioTrack.enableIllegalStateOnPlay(true)
        assertFalse(p.play(CW, -1))
        assertFalse(p.alive)
        p.release()
    }

    // ---- wiring (comments stripped) --------------------------------------------------------------------

    @Test fun `배선 - 음성 객체는 엔진 의존이 없고 톤 쪽으로는 hushForSpeech 만 부른다`() {
        val voice = noComments(serviceSource("AlertVoice.kt"))
        // anchors: the things that must be there, so the "must not" checks below cannot pass on an empty body
        for (must in listOf(
            "AlertSoundPlayer.hushForSpeech()", "R.raw.voice_warning", "R.raw.voice_danger", "R.raw.voice_rapid",
            "USAGE_ALARM", "CONTENT_TYPE_SPEECH", "MODE_STATIC", "reloadStaticData", "setLoopPoints",
        )) assertTrue(must, voice.contains(must))
        for (never in listOf(
            "android.speech", "TextToSpeech", "dangerTone(", "warningTone(", "playWarning(", "playDanger(", "stopSound(",
        )) assertFalse("AlertVoice has $never", voice.contains(never))
        assertEquals(1, Regex("AlertSoundPlayer\\.").findAll(voice).count())
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
        assertTrue(warningEntry.contains("AlertVoice.say()"))
        assertTrue(warningEntry.contains("if (AlertVoice.covering()) return"))
        assertTrue(warningEntry.contains("warningTone(context)"))
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

    @Test fun `실제 판정에 정속 접근 프레임을 넣으면 급접근 재생 요청이 기록된다`() {
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
            val rapid = made.sumOf { p -> p.calls.count { it.clip == CR } }
            assertTrue("급접근 문장 재생 요청: ${made.map { p -> p.calls.map { it.clip } }}", rapid >= 1)
        } finally {
            DevSettings.soundEnabled = false
        }
    }
}
