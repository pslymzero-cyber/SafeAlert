package com.wf11.safealert.service

import android.os.Bundle
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.Duration
import java.util.Locale

/** What the scripted engine saw and what it answers. It never calls back by itself: the test does. */
private object Script {
    val ids = mutableListOf<String>()
    var result = TextToSpeech.SUCCESS
}

/** TextToSpeech whose speak() records the call and answers `Script.result` without any callback. */
@Implements(TextToSpeech::class)
class ScriptedTts : ShadowTextToSpeech() {
    @Implementation
    override fun speak(text: CharSequence?, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
        Script.ids += utteranceId.orEmpty()
        return Script.result
    }
}

/** Every way speech can fail hands over to the tone at once and stays off for 60 s. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ScriptedTts::class])
class AlertVoiceFailureTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val isPlaying = AlertSoundPlayer::class.java.getDeclaredField("isPlaying").apply { isAccessible = true }

    @Before fun setUp() = reset()
    @After fun tearDown() = reset()

    private fun reset() {
        Script.ids.clear()
        Script.result = TextToSpeech.SUCCESS
        DevSettings.init(app)
        DevSettings.voiceAlertEnabled = true
        AlertSoundPlayer.stopSound()
        AlertVoice.shutdown()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun tone() = isPlaying.getBoolean(AlertSoundPlayer)
    private fun engine() = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())

    private fun ready() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        AlertVoice.start(app)
        engine().onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
    }

    @Test fun `섀도가 적용되어 말하기 요청이 기록된다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        assertEquals(1, Script.ids.size)
        assertFalse(tone())
    }

    @Test fun `말하기 요청이 바로 거절되면 그 호출에서 사이렌이고 60초 뒤 다시 음성`() {
        ready()
        Script.result = TextToSpeech.ERROR
        AlertSoundPlayer.playDanger(app)
        assertEquals(1, Script.ids.size)
        assertTrue(tone())

        Script.result = TextToSpeech.SUCCESS
        idleFor(30_000)
        AlertSoundPlayer.playDanger(app)
        assertEquals(1, Script.ids.size)
        assertTrue(tone())

        idleFor(30_000)
        AlertSoundPlayer.playDanger(app)
        assertEquals(2, Script.ids.size)
        assertFalse(tone())
    }

    @Test fun `엔진이 오류를 알리면 사이렌으로 넘어가고 60초 동안 톤이다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        assertFalse(tone())
        engine().utteranceProgressListener.onError(Script.ids.last(), TextToSpeech.ERROR)
        idle()
        assertTrue(tone())
        val before = Script.ids.size

        idleFor(59_000)
        AlertSoundPlayer.playDanger(app)
        assertEquals(before, Script.ids.size)
        assertTrue(tone())

        idleFor(1_000)
        AlertSoundPlayer.playDanger(app)
        assertEquals(before + 1, Script.ids.size)
        assertFalse(tone())
    }

    @Test fun `1,5초 안에 시작하지 않으면 사이렌`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idleFor(1_400)
        assertFalse(tone())
        idleFor(100)
        assertTrue(tone())
    }

    @Test fun `시작한 뒤 8초 안에 끝나지 않으면 사이렌`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        engine().utteranceProgressListener.onStart(Script.ids.last())
        idle()
        idleFor(7_900)
        assertFalse(tone())
        idleFor(200)
        assertTrue(tone())
    }

    @Test fun `경고 한 번이 실패하면 삑이 울린다`() {
        ready()
        AlertSoundPlayer.playWarning(app)
        assertFalse(tone())
        idleFor(1_500)
        assertTrue(tone())
    }

    @Test fun `낡은 문장의 오류 콜백은 지금 문장을 끊지 않는다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        val old = Script.ids.last()
        AlertSoundPlayer.stopSound()
        AlertSoundPlayer.playDanger(app)
        assertEquals(2, Script.ids.size)
        engine().utteranceProgressListener.onError(old, TextToSpeech.ERROR)
        idle()
        assertFalse(tone())
        engine().utteranceProgressListener.onStart(Script.ids.last())
        idle()
        assertFalse(tone())
    }
}
