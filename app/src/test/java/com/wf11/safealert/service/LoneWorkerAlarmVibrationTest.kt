package com.wf11.safealert.service

import android.os.SystemClock
import android.os.Vibrator
import com.wf11.safealert.utils.DevSettings
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The vibration of a coworker rescue request goes on without the siren once the siren's ring time is over: the alarm
 * vibrates with no sound playing, a plain stop ends it, and the 5 s refresh re-applies it.
 * SDK pinned to 34 because the only locally cached Robolectric SDK jar is API 34.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoneWorkerAlarmVibrationTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var alarm: LoneWorkerAlarm

    private fun vibrating() = shadowOf(app.getSystemService(Vibrator::class.java)).isVibrating

    @Before
    fun setUp() {
        DevSettings.init(app)
        VibrationHelper.stopVibration(app) // Ends a one-shot vibration another test class left in the helper's static state
        alarm = LoneWorkerAlarm(app) { }
    }

    @After
    fun tearDown() {
        alarm.stop(final = true)
    }

    @Test
    fun vibration_goes_on_without_the_siren_and_a_plain_stop_ends_it() {
        alarm.stop(vibrate = true)
        assertTrue("no siren, vibration only", vibrating())
        // the 5 s refresh re-applies a vibration that was cancelled while no siren plays
        app.getSystemService(Vibrator::class.java).cancel()
        assertFalse("cancelled", vibrating())
        alarm.refresh()
        assertTrue("refresh re-applies the vibration", vibrating())
        alarm.stop()
        assertFalse("a plain stop ends the vibration", vibrating())

        alarm.play(LoneWorkerAlarm.Pattern.SIREN, true)
        alarm.stop(vibrate = true)
        assertTrue("the siren time ends, the vibration stays", vibrating())
        alarm.stop()
        assertFalse(vibrating())

        // the weak buzz at the start of my own count: vibrates, and its window keeps the sensors from counting it as movement
        VibrationHelper.vibrateCountdown(app)
        assertTrue("weak buzz", vibrating())
        assertTrue("sensors mask the buzz", VibrationHelper.window.covers(SystemClock.elapsedRealtime()))
        VibrationHelper.stopVibration(app)
    }
}
