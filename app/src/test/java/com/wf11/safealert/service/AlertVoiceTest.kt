package com.wf11.safealert.service

import android.os.Looper
import android.speech.tts.TextToSpeech
import com.wf11.safealert.utils.DevSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.Duration
import java.util.Locale

/**
 * Speech replaces the beep / siren when it can be produced; whenever it cannot, the tone plays (never silence).
 * Uses Robolectric's TextToSpeech shadow, which completes every utterance when the looper is idled.
 */
@RunWith(RobolectricTestRunner::class)
class AlertVoiceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val isPlaying = AlertSoundPlayer::class.java.getDeclaredField("isPlaying").apply { isAccessible = true }

    @Before fun setUp() = reset()
    @After fun tearDown() = reset()

    private fun reset() {
        DevSettings.init(app)
        DevSettings.voiceAlertEnabled = true
        AlertSoundPlayer.stopSound()
        AlertVoice.shutdown()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun tone() = isPlaying.getBoolean(AlertSoundPlayer)
    private fun engine() = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
    private fun spoken(): List<String> = engine().spokenTextList.map { it.toString() }

    private fun ready(korean: Boolean = true, code: Int = TextToSpeech.SUCCESS) {
        if (korean) ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        AlertVoice.start(app)
        engine().onInitListener.onInit(code)
        idle()
    }

    private fun noComments(s: String) = s.lines().joinToString("\n") { it.substringBefore("//") }

    @Test fun `준비 전에는 경고음이 울리고 말하지 않는다`() {
        assertEquals(AlertVoice.STATUS_IDLE, AlertVoice.status)
        AlertSoundPlayer.playWarning(app)
        assertTrue(tone())
        AlertSoundPlayer.stopSound()

        AlertVoice.start(app)  // engine created, onInit not yet delivered
        AlertSoundPlayer.playWarning(app)
        assertTrue(tone())
        assertTrue(spoken().isEmpty())
        assertEquals(AlertVoice.STATUS_IDLE, AlertVoice.status)
    }

    @Test fun `준비되면 경고는 문구를 한 번 말하고 경고음은 없다`() {
        ready()
        assertEquals(AlertVoice.STATUS_READY, AlertVoice.status)
        AlertSoundPlayer.playWarning(app)
        assertEquals(listOf(AlertVoice.WARNING), spoken())
        assertFalse(tone())
        AlertSoundPlayer.playWarning(app)  // still speaking
        assertEquals(listOf(AlertVoice.WARNING), spoken())
        assertFalse(tone())
    }

    @Test fun `위험은 문구를 반복하고 다시 불려도 끊지 않으며 정지 뒤에는 말하지 않는다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        assertEquals(listOf(AlertVoice.DANGER), spoken())
        assertFalse(tone())
        idle()
        assertEquals(1, spoken().size)
        AlertSoundPlayer.playDanger(app)  // the 2 s re-alert must not restart the sentence
        assertEquals(1, spoken().size)
        idleFor(400)
        assertEquals(listOf(AlertVoice.DANGER, AlertVoice.DANGER), spoken())
        AlertSoundPlayer.playDanger(app)
        assertEquals(2, spoken().size)
        assertFalse(tone())

        AlertSoundPlayer.stopSound()
        assertTrue(engine().isStopped)
        idleFor(3_000)
        assertEquals(2, spoken().size)
        assertFalse(tone())
    }

    @Test fun `경고 도중 위험이 오면 위험 문구로 바뀌어 반복한다`() {
        ready()
        AlertSoundPlayer.playWarning(app)
        AlertSoundPlayer.playDanger(app)
        assertEquals(listOf(AlertVoice.WARNING, AlertVoice.DANGER), spoken())
        AlertSoundPlayer.playWarning(app)  // danger repeat in progress keeps the floor
        assertEquals(2, spoken().size)
        assertFalse(tone())
    }

    @Test fun `설정을 끄면 경고도 위험도 톤이 울린다`() {
        ready()
        DevSettings.voiceAlertEnabled = false
        AlertSoundPlayer.playDanger(app)
        assertTrue(tone())
        assertTrue(spoken().isEmpty())
        AlertSoundPlayer.stopSound()
        AlertSoundPlayer.playWarning(app)
        assertTrue(tone())
        assertTrue(spoken().isEmpty())
    }

    @Test fun `반복 중에 설정을 끄면 남은 음성을 끊고 사이렌을 울린다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idle()
        DevSettings.voiceAlertEnabled = false
        AlertSoundPlayer.playDanger(app)
        assertTrue(engine().isStopped)
        assertTrue(tone())
        idleFor(3_000)
        assertEquals(1, spoken().size)
    }

    @Test fun `급접근은 급접근 문구를 먼저 말하고 위험 문구로 이어지며 5초 안에는 끼어들지 않는다`() {
        ready()
        AlertSoundPlayer.playDanger(app, rapid = true)
        assertEquals(listOf(AlertVoice.RAPID), spoken())
        assertFalse(tone())
        idle()
        idleFor(400)
        assertEquals(listOf(AlertVoice.RAPID, AlertVoice.DANGER), spoken())

        AlertSoundPlayer.playDanger(app, rapid = true)  // within 5 s of the last RAPID
        assertEquals(1, spoken().count { it == AlertVoice.RAPID })
        idleFor(4_000)
        AlertSoundPlayer.playDanger(app, rapid = true)
        assertEquals(1, spoken().count { it == AlertVoice.RAPID })

        idleFor(1_500)  // now older than 5 s
        AlertSoundPlayer.playDanger(app, rapid = true)
        assertEquals(2, spoken().count { it == AlertVoice.RAPID })
        assertEquals(AlertVoice.RAPID, spoken().last())
        idle()
        idleFor(400)
        assertEquals(AlertVoice.DANGER, spoken().last())
        assertFalse(tone())
    }

    @Test fun `한국어 음성이 없으면 톤이 울리고 상태 문구가 알려 준다`() {
        ready(korean = false)
        assertEquals(AlertVoice.STATUS_NO_KOREAN, AlertVoice.status)
        AlertSoundPlayer.playWarning(app)
        assertTrue(tone())
        assertTrue(spoken().isEmpty())
        AlertSoundPlayer.stopSound()
        AlertSoundPlayer.playDanger(app)
        assertTrue(tone())
        assertTrue(spoken().isEmpty())
    }

    @Test fun `엔진 초기화 실패도 톤이 울린다`() {
        ready(code = TextToSpeech.ERROR)
        assertEquals(AlertVoice.STATUS_NO_KOREAN, AlertVoice.status)
        AlertSoundPlayer.playWarning(app)
        assertTrue(tone())
        assertTrue(spoken().isEmpty())
    }

    @Test fun `결함 삑은 음성 모드에서도 톤이다`() {
        ready()
        AlertSoundPlayer.playWarning(app, voice = false)
        assertTrue(tone())
        assertTrue(spoken().isEmpty())
    }

    @Test fun `배선 - 급접근 호출 · 시작과 정리 · 톤 진입점 · 매니페스트 · 안내 문구`() {
        val asm = noComments(serviceSource("AlertStateMachine.kt"))
        val ttc = repoFile("app/src/main/java/com/wf11/safealert/03_service/AlertStateMachine.kt").let { raw ->
            val from = raw.indexOf("TTC 선발령")
            assertTrue(from >= 0)
            raw.substring(from, raw.indexOf("fx.updateFloatingOverlay()", from))
        }
        assertTrue(noComments(ttc).contains("fx.playRapidApproach()"))
        assertEquals(1, Regex("fx\\.playRapidApproach\\(\\)").findAll(asm).count())
        assertTrue(asm.contains("fun playRapidApproach() = playDanger()"))

        val ble = noComments(serviceSource("BleService.kt"))
        assertTrue(ble.contains("AlertVoice.start(this)"))
        assertTrue(sourceBlock(ble, "private fun stopAll()").contains("AlertVoice.shutdown()"))
        assertTrue(ble.contains("override fun playRapidApproach() = AlertSoundPlayer.playDanger(this@BleService, rapid = true)"))
        assertTrue(ble.contains("AlertSoundPlayer.playWarning(this, voice = false)"))

        val asp = noComments(serviceSource("AlertSoundPlayer.kt"))
        for (head in listOf("internal fun warningTone", "internal fun dangerTone", "private fun stopTone")) {
            val block = sourceBlock(asp, head)
            assertFalse("$head asks the voice again", block.contains("AlertVoice"))
            assertFalse("$head stops the voice too", block.contains("stopSound("))
        }

        val manifest = repoFile("app/src/main/AndroidManifest.xml")
        val queries = manifest.substring(manifest.indexOf("<queries>"), manifest.indexOf("</queries>"))
        assertTrue(queries.contains("android.intent.action.TTS_SERVICE"))

        val news = repoFile("app/src/main/res/values/strings.xml").let { it.substring(it.indexOf("name=\"whats_new\"")) }
        assertTrue(news.contains(AlertVoice.WARNING) && news.contains(AlertVoice.DANGER) && news.contains(AlertVoice.RAPID))
    }
}
