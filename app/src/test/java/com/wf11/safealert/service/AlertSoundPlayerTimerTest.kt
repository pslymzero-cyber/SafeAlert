package com.wf11.safealert.service

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

// (v1.1.96 코드검토) WARNING 뒤 1초 안에 DANGER 로 오르면 경고음 해제 타이머가
//   위험음 반복을 끊던 경쟁의 회귀 테스트.
@RunWith(RobolectricTestRunner::class)
class AlertSoundPlayerTimerTest {
    @Before fun setUp() = AlertSoundPlayer.stopSound()
    @After fun tearDown() = AlertSoundPlayer.stopSound()

    @Test fun dangerRepeatSurvivesWarningResetTimer() {
        val ctx = RuntimeEnvironment.getApplication()
        AlertSoundPlayer.playWarning(ctx)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        AlertSoundPlayer.playDanger(ctx)
        val f = AlertSoundPlayer::class.java.getDeclaredField("isPlaying").apply { isAccessible = true }
        assertTrue("ToneGenerator 미생성 — 판정 무효", f.getBoolean(AlertSoundPlayer))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        assertTrue(f.getBoolean(AlertSoundPlayer))
    }
}
