package com.wf11.safealert.service

import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every way the recording can break leaves the tone sounding in the same call or on the next 600 ms tick (it was
 * never stopped for good) and keeps the voice off for 60 s. Nothing here is started by AlertVoice.
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
        assertTrue("the old player is released", first.released)
        assertEquals("a new player is made", 2, factoryCalls)
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        assertTrue(AlertVoice.degraded)

        val second = player()
        idleFor(30_000)
        AlertSoundPlayer.stopSound()
        AlertSoundPlayer.playWarning(app)
        assertEquals(2, beeps())
        idleFor(29_000)
        AlertSoundPlayer.stopSound()
        AlertSoundPlayer.playWarning(app)
        assertEquals(3, beeps())
        assertTrue("no request inside the 60 s", second.calls.isEmpty())

        idleFor(1_000)  // 60 s after the failure
        AlertSoundPlayer.stopSound()
        AlertSoundPlayer.playWarning(app)
        assertEquals(3, beeps())
        assertEquals(listOf(CW to -1), second.calls.map { it.clip to it.gap })
        assertFalse(AlertVoice.degraded)
    }

    @Test fun `위험 재생을 시작하지 못하면 같은 호출에서 사이렌이 난다`() {
        ready()
        player().playResult = false
        AlertSoundPlayer.playDanger(app)
        idle()
        assertTrue(sirens() >= 1)
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        idleFor(1_200)
        assertTrue("the siren repeat keeps sounding", sirens() >= 3)
        assertTrue(player().calls.isEmpty())
    }

    @Test fun `급접근 재생을 시작하지 못해도 같은 호출에서 사이렌이 난다`() {
        ready()
        player().playResult = false
        AlertSoundPlayer.playDanger(app, rapid = true)
        idle()
        assertTrue(sirens() >= 1)
        assertTrue(AlertVoice.degraded)
    }

    @Test fun `재생 요청이 예외를 던져도 밖으로 새지 않고 실패로 처리된다`() {
        ready()
        player().playThrows = true
        AlertSoundPlayer.playWarning(app)
        assertEquals(1, beeps())
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        AlertSoundPlayer.stopSound()
        player().playThrows = true
        idleFor(60_000)
        AlertSoundPlayer.playDanger(app)
        idle()
        assertTrue(sirens() >= 1)
    }

    @Test fun `위험 반복 도중 재생기가 죽으면 600ms 틱에서 사이렌이 나고 재진입에서 실패 처리된다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idleFor(1_500)
        assertEquals(0, sirens())
        player().alive = false  // the track dies; nobody calls playDanger
        val before = sirens()
        idleFor(600)
        assertTrue("the next tick sounds the siren", sirens() > before)

        idleFor(1_400)  // a re-entry about 2 s later
        val dead = player()
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        assertTrue(AlertVoice.degraded)
        assertTrue(dead.released)
        assertEquals(2, factoryCalls)
        val s = sirens()
        idleFor(1_200)
        assertTrue("the siren goes on", sirens() >= s + 2)
        assertTrue("no request while cooling", player().calls.isEmpty())
    }

    @Test fun `경고 문장 도중 재생기가 죽으면 다음 경고 호출에서 삑이 난다`() {
        ready()
        AlertSoundPlayer.playWarning(app)
        assertEquals(0, beeps())
        player().alive = false
        idleFor(1_000)
        AlertSoundPlayer.playWarning(app)
        assertEquals(1, beeps())
    }

    @Test fun `급접근 문장이 끝난 뒤 틈에서는 재생기가 정지 상태여도 실패로 보지 않고 위험 반복이 예정대로 시작된다`() {
        ready()
        AlertSoundPlayer.playDanger(app, rapid = true)
        idleFor(2_000)  // 10 ms past the end of RAPID, inside the 0.4 s pause
        player().alive = false  // some devices leave a finished static track stopped
        AlertSoundPlayer.playDanger(app, rapid = true)  // re-entry in the pause
        idle()
        assertEquals(AlertVoice.STATUS_READY, AlertVoice.status)
        assertFalse(AlertVoice.degraded)
        assertEquals(1, factoryCalls)
        assertTrue("the pause is covered by time alone", AlertVoice.covering())
        assertEquals(0, sirens())

        idleFor(400)
        assertEquals(listOf(CR to -1, CD to AlertVoice.REPEAT_GAP_MS), player().calls.map { it.clip to it.gap })
        assertTrue(AlertVoice.covering())
        idleFor(1_500)
        assertEquals(0, sirens())
        assertEquals(AlertVoice.STATUS_READY, AlertVoice.status)
    }

    @Test fun `급접근 문장 도중에 재생기가 죽으면 실패로 처리된다`() {
        ready()
        AlertSoundPlayer.playDanger(app, rapid = true)
        idleFor(1_000)
        player().alive = false
        AlertSoundPlayer.playDanger(app, rapid = true)
        idle()
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        assertTrue(sirens() >= 1)
    }

    @Test fun `재생기를 다시 만들지 못해도 냉각이 끝나는 시점에 다시 만들어 음성이 돌아온다`() {
        ready()
        player().playResult = false
        makeNext = { null }
        AlertSoundPlayer.playWarning(app)
        assertEquals(1, beeps())
        assertEquals(2, factoryCalls)
        assertEquals(1, made.size)
        assertEquals(AlertVoice.STATUS_NO_FILES, AlertVoice.status)
        assertTrue(AlertVoice.degraded)

        idleFor(60_000)  // the cooldown is over: one more try, which fails again
        assertEquals(3, factoryCalls)
        assertEquals(AlertVoice.STATUS_NO_FILES, AlertVoice.status)

        makeNext = { FakePlayer() }
        idleFor(60_000)  // the next cooldown cycle
        assertEquals(4, factoryCalls)
        assertEquals(2, made.size)
        assertEquals(AlertVoice.STATUS_READY, AlertVoice.status)
        assertFalse(AlertVoice.degraded)
        AlertSoundPlayer.stopSound()
        AlertSoundPlayer.playWarning(app)
        assertEquals(listOf(CW to -1), player().calls.map { it.clip to it.gap })
        assertEquals(1, beeps())
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

        idleFor(300)  // 1.3 s after the cut: the held-back repeat starts
        assertEquals(listOf(CR to -1, CD to AlertVoice.REPEAT_GAP_MS), player().calls.map { it.clip to it.gap })
    }

    @Test fun `끊김 보호로 미뤄 둔 위험 반복은 그 사이 소리가 멈추면 시작하지 않는다`() {
        ready()
        AlertSoundPlayer.playDanger(app, rapid = true)
        idleFor(200)
        AlertSoundPlayer.stopSound()
        AlertSoundPlayer.playDanger(app)
        idle()
        idleFor(300)
        AlertSoundPlayer.stopSound()
        idleFor(3_000)
        assertEquals(1, player().calls.size)
    }

    @Test fun `반복 중 음성 설정을 끄면 다음 재진입에서 재생을 멈추고 사이렌이 나며 냉각 없이 다시 켜면 바로 재생한다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(0, sirens())
        DevSettings.voiceAlertEnabled = false
        AlertSoundPlayer.playDanger(app)
        idle()
        assertTrue(player().stops >= 1)
        assertTrue(sirens() >= 1)
        assertEquals(AlertVoice.STATUS_OFF, AlertVoice.status)
        assertFalse(AlertVoice.degraded)
        assertEquals("no rebuild: it was not a failure", 1, factoryCalls)

        DevSettings.voiceAlertEnabled = true
        AlertSoundPlayer.playDanger(app)  // the next re-entry plays at once
        idle()
        assertEquals(2, player().calls.size)
        val s = sirens()
        idleFor(1_200)
        assertEquals("the repeat is silent again", s, sirens())
    }

    @Test fun `반복 중 구조 요청 사이렌이 켜지면 같은 방식으로 재생을 멈추고 끝나면 바로 돌아온다`() {
        ready()
        AlertSoundPlayer.playDanger(app)
        idle()
        AlarmVolumeShare.sosSounding = true
        AlertSoundPlayer.playDanger(app)
        idle()
        assertTrue(player().stops >= 1)
        assertTrue(sirens() >= 1)
        AlarmVolumeShare.sosSounding = false
        AlertSoundPlayer.playDanger(app)
        idle()
        assertEquals(2, player().calls.size)
    }

    @Test fun `경고 중에 설정을 끄면 다음 경고 호출에서 재생을 멈추고 삑이 난다`() {
        ready()
        AlertSoundPlayer.playWarning(app)
        DevSettings.voiceAlertEnabled = false
        idleFor(500)
        AlertSoundPlayer.playWarning(app)
        assertTrue(player().stops >= 1)
        assertEquals(1, beeps())
    }

    @Test fun `상태 문구 5종과 degraded 와 onChange`() {
        var changes = 0
        AlertVoice.onChange = { changes++ }
        assertEquals(AlertVoice.STATUS_IDLE, AlertVoice.status)
        assertFalse(AlertVoice.degraded)

        makeNext = { null }
        ready()
        assertEquals(AlertVoice.STATUS_NO_FILES, AlertVoice.status)
        assertTrue(AlertVoice.degraded)
        DevSettings.voiceAlertEnabled = false
        assertFalse("setting off: nothing is degraded", AlertVoice.degraded)
        DevSettings.voiceAlertEnabled = true
        AlertVoice.shutdown()
        assertEquals(AlertVoice.STATUS_IDLE, AlertVoice.status)

        makeNext = { FakePlayer() }
        val c0 = changes
        ready()
        assertTrue("start redraws", changes > c0)
        assertEquals(AlertVoice.STATUS_READY, AlertVoice.status)
        assertFalse(AlertVoice.degraded)
        DevSettings.voiceAlertEnabled = false
        assertEquals(AlertVoice.STATUS_OFF, AlertVoice.status)
        DevSettings.voiceAlertEnabled = true

        val c1 = changes
        player().playResult = false
        AlertSoundPlayer.playWarning(app)
        assertTrue("entering the cooldown redraws", changes > c1)
        assertEquals(AlertVoice.STATUS_COOLING, AlertVoice.status)
        assertTrue(AlertVoice.degraded)
        DevSettings.voiceAlertEnabled = false
        assertFalse(AlertVoice.degraded)
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
