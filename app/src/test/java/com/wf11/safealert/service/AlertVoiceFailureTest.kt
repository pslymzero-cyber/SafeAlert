package com.wf11.safealert.service

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every way speech can break leaves the tone sounding (it was never stopped for good) and keeps speech off for 60 s.
 * The tone must come back by itself: nothing here is started by AlertVoice.
 */
class AlertVoiceFailureTest : VoiceTestBase() {

    /** Siren pieces keep coming: every 600 ms tick adds one, for [steps] ticks. */
    private fun assertSirenKeepsGoing(steps: Int) {
        repeat(steps) {
            val before = sirens()
            idleFor(600)
            assertTrue("tick ${it + 1} of $steps made no siren piece", sirens() > before)
        }
    }

    @Test fun `문장 도중 엔진이 죽으면 사이렌이 저절로 돌아온다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idle()
        audio()  // proof, then the engine dies: no more callbacks
        val covered = sirens()
        idleFor(3_400)
        assertEquals(covered, sirens())

        idleFor(100)  // 3,5 s after the proof: the cover ends and the end watch fails the sentence
        idleFor(600)
        assertTrue(sirens() > covered)
        assertTrue(AlertVoice.degraded)
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)

        AlertSoundPlayer.playDanger(app)  // inside the 60 s: tone only
        idle()
        assertEquals(1, said().size)
        idleFor(55_000)
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(1, said().size)

        idleFor(5_000)  // the 60 s counted from the failure are over
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(2, said().size)
        assertFalse(AlertVoice.degraded)
    }

    @Test fun `증명 없는 완료는 실패이고 사이렌은 한 번도 끊기지 않으며 60초 동안 말하지 않는다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idle()
        started()
        done()  // finished without any audio
        assertEquals(0, hushes())
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        assertSirenKeepsGoing(3)

        AlertSoundPlayer.playDanger(app)
        idleFor(30_000)
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(1, said().size)
        assertEquals(0, hushes())

        idleFor(30_000)
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(2, said().size)
    }

    @Test fun `증명 뒤 400ms 안에 끝난 완료도 실패이다`() {
        ready()
        AlertSoundPlayer.playWarning(app)
        audio()
        done()  // proof and done in the same instant: too short to be a spoken sentence
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        assertFalse(AlertVoice.covering())
    }

    @Test fun `시작 안 됨 - 1,5초 동안에도 사이렌이 계속 나고 그 뒤 냉각이다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(1, sirens())
        assertSirenKeepsGoing(2)  // 1,2 s, no audio so far
        assertEquals(AlertVoice.STATUS_READY, AlertVoice.status)
        idleFor(300)  // 1,5 s: the start watch gives up
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        assertSirenKeepsGoing(1)
        assertEquals(0, hushes())
    }

    @Test fun `speak 거절 - 첫 문장에서 사이렌이 이어지고 엔진을 다시 만든다`() {
        ready()
        val old = newest()
        Rec.speakResult = TextToSpeech.ERROR
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(1, said().size)
        assertTrue(sirens() >= 1)
        assertTrue(old !== newest())
        assertTrue(engineOfOldIsShutdown(old))
        assertSirenKeepsGoing(2)

        // the new engine comes up; speech returns after the cooldown
        engine().onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals(AlertVoice.State.READY, AlertVoice.state)
        Rec.speakResult = TextToSpeech.SUCCESS
        idleFor(60_000)
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(2, said().size)
    }

    private fun engineOfOldIsShutdown(old: TextToSpeech) = org.robolectric.Shadows.shadowOf(old).isShutdown

    @Test fun `speak 거절 - 반복 도중 다음 문장에서도 사이렌이 이어지고 엔진을 다시 만든다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idle()
        audio()
        idleFor(500)
        done()
        val old = newest()
        Rec.speakResult = TextToSpeech.ERROR
        idleFor(400)  // the next sentence is refused
        assertEquals(2, said().size)
        assertFalse(AlertVoice.covering())
        assertTrue(old !== newest())
        assertSirenKeepsGoing(2)
    }

    @Test fun `연속 2회 실패면 엔진을 다시 만들고 1인자 오류와 2인자 오류가 모두 실패이다`() {
        ready()
        val first = newest()
        AlertSoundPlayer.playDanger(app)
        idle()
        started()
        error()  // one-argument onError
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        assertTrue(first === newest())  // one failure: same engine

        idleFor(60_000)
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(2, said().size)
        started()
        error2()  // two-argument onError
        assertTrue(first !== newest())  // second in a row: a new engine
        assertSirenKeepsGoing(1)
    }

    @Test fun `엔진이 멈춘 신호 onStop 도 실패이다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idle()
        started()
        audio()
        stopped()
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        assertFalse(AlertVoice.covering())
        assertSirenKeepsGoing(1)
    }

    @Test fun `낡은 문장의 콜백은 아무것도 바꾸지 않는다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idle()
        val old = lastId()
        AlertSoundPlayer.stopSound()
        val tones = Rec.tones.size

        audio(old)
        done(old)
        error(old)
        error2(old)
        stopped(old)
        assertEquals(tones, Rec.tones.size)
        assertEquals(AlertVoice.STATUS_READY, AlertVoice.status)
        assertFalse(AlertVoice.degraded)
        assertFalse(AlertVoice.covering())
    }

    @Test fun `이미 죽은 문장의 첫 오디오가 새 문장을 증명하지 않는다`() {
        ready()
        AlertSoundPlayer.playWarning(app)
        val warning = lastId()
        AlertSoundPlayer.playDanger(app)  // escalation replaces the sentence
        idle()
        val danger = lastId()
        assertTrue(warning != danger)
        audio(warning)  // late audio of the replaced sentence
        assertEquals(0, hushes())
        assertFalse(AlertVoice.covering())
        audio(danger)
        assertEquals(1, hushes())
        assertTrue(AlertVoice.covering())
    }
}
