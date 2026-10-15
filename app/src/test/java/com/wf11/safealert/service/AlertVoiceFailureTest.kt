package com.wf11.safealert.service

import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every way the recording can break leaves the tone sounding: in the same call (a clip that does not start), on the
 * next 600 ms tick of the siren repeat (a danger repeat that stops rendering), PROOF_MS after its start (a warning
 * sentence that is not coming out) or at once from the timer (the hand-over from RAPID to DANGER that cannot start).
 * The voice then stays off for 60 s and the new player is made after that, never in the failing call.
 */
class AlertVoiceFailureTest : VoiceTestBase() {

    private val CW = AlertVoice.CLIP_WARNING
    private val CD = AlertVoice.CLIP_DANGER
    private val CR = AlertVoice.CLIP_RAPID

    @Test fun `경고 재생을 시작하지 못하면 같은 호출에서 삑이 나고 60초 동안 재생 요청이 없다`() {
        ready()
        val first = player()
        first.playResult = false
        AlertSoundPlayer.playWarning(app)
        assertEquals("the beep sounds in the same call", 1, beeps())
        assertEquals(1, first.calls.size)
        assertFalse("the old player is released after the tone, not before", first.released)
        assertEquals("no new player in the failing call", 1, factoryCalls)
        idle()
        assertTrue("the old player is released", first.released)
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        assertTrue(AlertVoice.degraded)

        idleFor(30_000)
        AlertSoundPlayer.stopSound()
        AlertSoundPlayer.playWarning(app)
        assertEquals(2, beeps())
        idleFor(29_000)
        AlertSoundPlayer.stopSound()
        AlertSoundPlayer.playWarning(app)
        assertEquals(3, beeps())
        assertEquals("the new player comes only after the 60 s", 1, made.size)

        idleFor(1_000)  // 60 s after the failure
        assertEquals(2, factoryCalls)
        val second = player()
        assertTrue(second.calls.isEmpty())
        AlertSoundPlayer.stopSound()
        AlertSoundPlayer.playWarning(app)
        assertEquals(3, beeps())
        assertEquals(listOf(CW), second.calls)
        assertFalse(AlertVoice.degraded)
    }

    @Test fun `위험 재생을 시작하지 못하면 같은 호출에서 사이렌이 난다`() {
        ready()
        val first = player()
        first.playResult = false
        AlertSoundPlayer.playDanger(app)
        idle()
        assertTrue(sirens() >= 1)
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        idleFor(1_200)
        assertTrue("the siren repeat keeps sounding", sirens() >= 3)
        assertEquals(listOf(CD), first.calls)
        assertEquals(1, factoryCalls)

        // the same failure on the RAPID sentence, after the cooldown (relative counts: the steps share the test)
        AlertSoundPlayer.stopSound()
        idleFor(60_000)
        assertEquals("the new player after the cooldown", 2, factoryCalls)
        assertEquals(AlertVoice.STATUS_READY, AlertVoice.status)
        val second = player()
        second.playResult = false
        val s = sirens()
        AlertSoundPlayer.playDanger(app, rapid = true)
        idle()
        assertTrue("RAPID start failure: the siren sounds in the same call", sirens() > s)
        assertTrue(AlertVoice.degraded)
        assertEquals(listOf(CR), second.calls)
    }

    @Test fun `재생 요청이 예외를 던져도 밖으로 새지 않고 실패로 처리된다`() {
        ready()
        player().playThrows = true
        AlertSoundPlayer.playWarning(app)
        assertEquals(1, beeps())
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        AlertSoundPlayer.stopSound()
        makeNext = { FakePlayer().also { it.playThrows = true } }  // the new player is made after the 60 s
        idleFor(60_000)
        assertEquals(2, factoryCalls)
        AlertSoundPlayer.playDanger(app)
        idle()
        assertTrue(sirens() >= 1)
    }

    @Test fun `위험 반복 도중 소리가 멈추면 다음 600ms 틱에서 사이렌이 나고 음성을 멈추며 60초 뒤 한 번만 재생기를 다시 만든다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idleFor(1_500)
        assertEquals(0, sirens())
        val dead = player()
        dead.alive = false  // the track stops rendering; nobody calls playDanger
        idleFor(300)  // the 1800 ms tick of the siren repeat
        assertEquals("the next tick sounds the siren", 1, sirens())
        assertEquals(1, dead.stops)
        idle()
        assertTrue(dead.released)
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        assertTrue(AlertVoice.degraded)
        assertEquals(1, factoryCalls)

        val s = sirens()
        idleFor(59_999)
        assertEquals("no new player before the cooldown is over", 1, factoryCalls)
        assertTrue("the siren goes on", sirens() > s)
        idleFor(1)
        assertEquals(2, factoryCalls)
        assertEquals(AlertVoice.STATUS_READY, AlertVoice.status)
        idleFor(120_000)
        assertEquals(2, factoryCalls)
    }

    @Test fun `경고 문장이 0점6초 확인 뒤에 멈추면 다음 경고 호출에서 삑이 난다`() {
        ready()
        AlertSoundPlayer.playWarning(app)
        idleFor(1_000)  // the look at 0.6 s passed
        assertEquals(0, beeps())
        player().alive = false
        idleFor(2_000)
        assertEquals(0, beeps())
        AlertSoundPlayer.playWarning(app)
        assertEquals(1, beeps())
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
    }

    @Test fun `경고 문장이 소리로 나오지 않으면 시작 0점6초 뒤에 삑이 한 번 난다`() {
        ready()
        // a stop that comes before the 0.6 s look: no confirmation beep, even though the clip is dead
        AlertSoundPlayer.playWarning(app)
        player().alive = false
        idleFor(300)
        AlertSoundPlayer.stopSound()
        idleFor(2_000)  // past the 1.2 s flicker protection
        assertEquals("stop before the look: no beep", 0, beeps())

        val stops0 = player().stops
        AlertSoundPlayer.playWarning(app)  // the same player: a dead sentence now has to be caught by the look
        assertEquals("the beep is not started in the call", 0, beeps())
        player().alive = false
        idleFor(AlertVoice.PROOF_MS - 1)
        assertEquals(0, beeps())
        idleFor(1)
        assertEquals(1, beeps())
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        assertEquals(stops0 + 1, player().stops)
        idleFor(3_000)
        assertEquals(1, beeps())
    }

    @Test fun `급접근 문장 도중에 소리가 멈추면 실패로 처리된다`() {
        ready()
        AlertSoundPlayer.playDanger(app, rapid = true)
        idleFor(1_000)
        player().alive = false
        AlertSoundPlayer.playDanger(app, rapid = true)
        idle()
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        assertTrue(sirens() >= 1)
    }

    @Test fun `재생기를 다시 만들지 못해도 냉각이 끝날 때마다 다시 만들어 음성이 돌아온다`() {
        // step 1: the player could not be made when the watch started; it is made again 60 s later
        makeNext = { null }
        ready()
        assertEquals(AlertVoice.STATUS_NOT_READY, AlertVoice.status)
        assertTrue(AlertVoice.degraded)
        assertEquals(1, factoryCalls)

        makeNext = { FakePlayer() }
        idleFor(59_999)
        assertEquals("start failure: no retry before 60 s", 1, factoryCalls)
        idleFor(1)
        assertEquals(2, factoryCalls)
        assertEquals(AlertVoice.STATUS_READY, AlertVoice.status)
        assertFalse(AlertVoice.degraded)
        AlertSoundPlayer.playWarning(app)
        assertEquals(listOf(CW), player().calls)
        idleFor(3_700)  // the sentence is over before the next step starts a new warning

        // step 2: the player fails, and the rebuild fails too; the cooldown end tries again every time
        val f0 = factoryCalls
        val m0 = made.size
        val b0 = beeps()
        player().playResult = false
        makeNext = { null }
        AlertSoundPlayer.playWarning(app)
        assertEquals("failure: the beep sounds", b0 + 1, beeps())
        assertEquals("no rebuild in the failing call", f0, factoryCalls)
        assertEquals(m0, made.size)
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        assertTrue(AlertVoice.degraded)

        idleFor(60_000)  // the cooldown is over: one more try, which fails again
        assertEquals(f0 + 1, factoryCalls)
        assertEquals(AlertVoice.STATUS_NOT_READY, AlertVoice.status)
        assertTrue(AlertVoice.degraded)

        makeNext = { FakePlayer() }
        idleFor(60_000)  // the next cycle
        assertEquals(f0 + 2, factoryCalls)
        assertEquals(m0 + 1, made.size)
        assertEquals(AlertVoice.STATUS_READY, AlertVoice.status)
        assertFalse(AlertVoice.degraded)
        AlertSoundPlayer.stopSound()
        AlertSoundPlayer.playWarning(app)
        assertEquals(listOf(CW), player().calls)
        assertEquals("the voice is back: no beep", b0 + 1, beeps())
    }

    @Test fun `되풀이로 끊기면 1점2초 동안 재생을 요청하지 않고 매 호출에서 톤이 난다`() {
        ready()
        AlertSoundPlayer.playDanger(app, rapid = true)
        idleFor(200)
        AlertSoundPlayer.stopSound()  // cut 200 ms after the start: flicker
        assertEquals(1, player().calls.size)

        AlertSoundPlayer.playWarning(app)
        assertEquals(1, beeps())
        AlertSoundPlayer.playDanger(app, rapid = true)
        idle()
        assertTrue(sirens() >= 1)
        idleFor(1_000)
        assertEquals("nothing is requested inside the 1.2 s", 1, player().calls.size)

        idleFor(300)  // 1.3 s after the cut: the held-back request starts, and the cut RAPID is asked again
        assertEquals(listOf(CR, CR), player().calls)

        // the RAPID asked again is cut at 0.2 s as well; the danger request held back by the flicker rule must not
        //   start once the sound has been stopped in the meantime
        idleFor(200)
        AlertSoundPlayer.stopSound()
        AlertSoundPlayer.playDanger(app)
        idle()
        idleFor(300)
        AlertSoundPlayer.stopSound()
        idleFor(3_000)
        assertEquals("the held-back danger repeat does not start after a stop", listOf(CR, CR), player().calls)
    }

    @Test fun `경고 0점3초 뒤 위험으로 올라가면 그 호출에서 위험 문장이 시작되고 0점7초 안에 끊긴 위험 문장은 1점2초 미뤄진다`() {
        ready()
        AlertSoundPlayer.playWarning(app)
        idleFor(300)
        AlertSoundPlayer.stopSound()  // the escalation cuts the warning
        AlertSoundPlayer.playDanger(app)
        assertEquals("an escalation is not flicker: the danger sentence starts in the same call", listOf(CW, CD), player().calls)
        idle()
        assertEquals(0, sirens())

        idleFor(200)
        AlertSoundPlayer.stopSound()  // the danger clip is cut inside 700 ms: flicker
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(listOf(CW, CD), player().calls)
        assertTrue(sirens() >= 1)
        idleFor(1_199)
        assertEquals(2, player().calls.size)
        idleFor(1)
        assertEquals(listOf(CW, CD, CD), player().calls)
    }

    @Test fun `급접근 뒤 위험 문장으로 넘어가지 못하면 그 자리에서 사이렌이 난다`() {
        makeNext = { FakePlayer(longArrayOf(3_660, 1_440, 1_700)) }  // the hand-over at 2100 ms, between the ticks 1800 and 2400
        ready()
        AlertSoundPlayer.playDanger(app, rapid = true)
        idleFor(2_000)
        assertEquals(0, sirens())
        player().playResult = false
        idleFor(100)
        assertEquals("the siren sounds at the hand-over, not at the next tick", 1, sirens())
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)

        AlertSoundPlayer.stopSound()
        idleFor(60_000)
        assertEquals(2, factoryCalls)
        AlertSoundPlayer.playDanger(app, rapid = true)
        idleFor(1_900)
        val s = sirens()
        AlarmVolumeShare.sosSounding = true
        idleFor(200)  // the hand-over finds the rescue siren sounding
        assertEquals(s + 1, sirens())
        assertTrue(player().stops >= 1)
        AlarmVolumeShare.sosSounding = false
        assertEquals(AlertVoice.STATUS_READY, AlertVoice.status)
        assertEquals("not a failure: no rebuild", 2, factoryCalls)
    }

    @Test fun `경고·위험 중 설정을 끄거나 구조 요청 사이렌이 켜지면 다음 요청에서 음성을 멈추고 톤이 나며 냉각 없이 돌아온다`() {
        ready()
        // step 1: a warning sentence is playing and the setting goes off; the next warning request stops it with a beep
        AlertSoundPlayer.playWarning(app)
        DevSettings.voiceAlertEnabled = false
        idleFor(500)
        AlertSoundPlayer.playWarning(app)
        assertTrue("warning + setting off: the player stops", player().stops >= 1)
        assertEquals("warning + setting off: the beep sounds", 1, beeps())

        // step 2: the danger repeat plays, the setting goes off; the next re-entry stops it with a siren; no cooldown
        DevSettings.voiceAlertEnabled = true
        val s1 = sirens()
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals("danger + setting back on: the clip plays without a siren", s1, sirens())
        assertEquals(listOf(CW, CD), clips())
        val stops2 = player().stops
        DevSettings.voiceAlertEnabled = false
        AlertSoundPlayer.playDanger(app)
        idle()
        assertTrue("danger + setting off: the player stops", player().stops > stops2)
        assertTrue("danger + setting off: the siren sounds", sirens() > s1)
        assertEquals("danger + setting off", AlertVoice.STATUS_OFF, AlertVoice.status)
        assertFalse(AlertVoice.degraded)
        assertEquals("no rebuild: it was not a failure", 1, factoryCalls)

        DevSettings.voiceAlertEnabled = true
        AlertSoundPlayer.playDanger(app)  // the next re-entry plays at once
        idle()
        assertEquals("danger + setting back on: plays at once", listOf(CW, CD, CD), clips())
        val s2 = sirens()
        idleFor(1_200)
        assertEquals("the repeat is silent again", s2, sirens())

        // step 3: the rescue siren starts during the danger repeat; the next re-entry stops the voice, the siren sounds
        val stops3 = player().stops
        AlarmVolumeShare.sosSounding = true
        AlertSoundPlayer.playDanger(app)
        idle()
        assertTrue("danger + rescue siren: the player stops", player().stops > stops3)
        assertTrue("danger + rescue siren: the siren sounds", sirens() > s2)
        AlarmVolumeShare.sosSounding = false
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals("danger + rescue siren over: back at once", listOf(CW, CD, CD, CD), clips())
        assertEquals("no rebuild at any step", 1, factoryCalls)
    }

    @Test fun `반복 중 구조 요청 사이렌이 켜지거나 음성 설정을 끄면 재진입 없이 다음 틱에서 음성이 멈추고 사이렌이 난다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idleFor(1_500)
        assertEquals(0, sirens())
        AlarmVolumeShare.sosSounding = true
        idleFor(300)  // the 1800 ms tick, nobody calls playDanger
        assertEquals(1, player().stops)
        assertEquals(1, sirens())
        assertEquals(1, factoryCalls)
        assertFalse(AlertVoice.degraded)

        AlarmVolumeShare.sosSounding = false
        AlertSoundPlayer.playDanger(app)  // re-entry: the next request, no cooldown
        assertEquals(2, player().calls.size)
        DevSettings.voiceAlertEnabled = false
        idleFor(600)
        assertEquals(2, player().stops)
        assertEquals(2, sirens())
        assertEquals(AlertVoice.STATUS_OFF, AlertVoice.status)
    }

    @Test fun `상태 문구 6종과 degraded 와 onChange`() {
        var changes = 0
        AlertVoice.onChange = { changes++ }
        assertEquals(AlertVoice.STATUS_IDLE, AlertVoice.status)
        assertFalse(AlertVoice.degraded)

        /** The sound master switch off beats everything but "not started". */
        fun soundOff(expected: String) {
            DevSettings.soundEnabled = false
            assertEquals(expected, AlertVoice.status)
            DevSettings.soundEnabled = true
        }

        makeNext = { null }
        ready()
        assertEquals(AlertVoice.STATUS_NOT_READY, AlertVoice.status)
        assertTrue(AlertVoice.degraded)
        soundOff(AlertVoice.STATUS_SOUND_OFF)
        DevSettings.voiceAlertEnabled = false
        assertFalse("setting off: nothing is degraded", AlertVoice.degraded)
        assertEquals(AlertVoice.STATUS_OFF, AlertVoice.status)
        DevSettings.voiceAlertEnabled = true
        AlertVoice.shutdown()
        assertEquals(AlertVoice.STATUS_IDLE, AlertVoice.status)

        makeNext = { FakePlayer() }
        val c0 = changes
        ready()
        assertTrue("start redraws", changes > c0)
        assertEquals(AlertVoice.STATUS_READY, AlertVoice.status)
        assertFalse(AlertVoice.degraded)
        soundOff(AlertVoice.STATUS_SOUND_OFF)
        DevSettings.voiceAlertEnabled = false
        assertEquals(AlertVoice.STATUS_OFF, AlertVoice.status)
        DevSettings.voiceAlertEnabled = true

        val c1 = changes
        player().playResult = false
        AlertSoundPlayer.playWarning(app)
        idle()
        assertTrue("entering the cooldown redraws", changes > c1)
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        assertTrue(AlertVoice.degraded)
        soundOff(AlertVoice.STATUS_SOUND_OFF)
        DevSettings.voiceAlertEnabled = false
        assertFalse(AlertVoice.degraded)
        assertEquals("switched off beats cooling", AlertVoice.STATUS_OFF, AlertVoice.status)
        DevSettings.voiceAlertEnabled = true

        val c2 = changes
        idleFor(60_000)
        assertTrue("leaving the cooldown redraws", changes > c2)
        assertEquals(AlertVoice.STATUS_READY, AlertVoice.status)
        assertFalse(AlertVoice.degraded)
    }

    @Test fun `감시를 끝내면 재생기를 풀고 재생하던 것을 멈추고 상태가 처음으로 돌아간다`() {
        ready()
        AlertSoundPlayer.playDanger(app, rapid = true)
        val p = player()
        AlertVoice.shutdown()
        assertTrue(p.released)
        assertFalse(AlertVoice.covering())
        assertEquals(AlertVoice.STATUS_IDLE, AlertVoice.status)
        assertFalse(AlertVoice.degraded)
        idleFor(5_000)
        assertEquals("the rapid-to-danger timer was removed", 1, p.calls.size)
    }
}
