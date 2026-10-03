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

// Regression test for a race: escalating to DANGER within 1 s after WARNING must not let the warning tone's
//   release timer cut off the repeating danger tone.
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
