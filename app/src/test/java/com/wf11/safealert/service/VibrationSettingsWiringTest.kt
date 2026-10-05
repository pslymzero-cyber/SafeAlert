package com.wf11.safealert.service

import android.content.Context
import android.os.Vibrator
import com.wf11.safealert.utils.DevSettings
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Checks with ShadowVibrator that the 2 vibration settings (warning pulse length, danger repeat count) reach the actual waveform.
 * SDK pinned to 34 because the only locally cached Robolectric SDK jar is API 34.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VibrationSettingsWiringTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        DevSettings.init(app)
    }

    @After
    fun tearDown() {
        DevSettings.vibrationWarningMs = 500L
        DevSettings.vibrationDangerCount = 3
    }

    private fun pattern(): LongArray? =
        shadowOf(app.getSystemService(Vibrator::class.java)).pattern

    /** Empties the settings file, so both vibration settings read their defaults as on a fresh install. */
    private fun clearSettings() {
        app.getSharedPreferences("dev_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // In both tables each row starts from an empty settings file (null = leave the setting unset), and adjacent rows
    //   expect different waveforms, so a row can only pass through its own vibration.

    /** The warning pulse is vibrationWarningMs clamped to 100..1000 ms; unset, it is 500 ms. */
    @Test
    fun `warning pulse follows the setting within 100 to 1000 ms and defaults to 500`() {
        val rows: List<Triple<String, Long?, LongArray>> = listOf(
            Triple("기본값(500)에서 경고 진동 펄스는 500ms 여야 한다", null, longArrayOf(0, 500, 200, 500)),
            Triple("vibrationWarningMs=1000 이면 펄스가 1000ms 여야 한다", 1000L, longArrayOf(0, 1000, 200, 1000)),
            Triple("warningMs=10 은 100ms 로 제한돼야 한다", 10L, longArrayOf(0, 100, 200, 100)),
            Triple("warningMs=5000 은 1000ms 로 제한돼야 한다", 5000L, longArrayOf(0, 1000, 200, 1000))
        )
        for ((i, row) in rows.withIndex()) {
            val (label, setting, want) = row
            clearSettings()
            if (setting != null) DevSettings.vibrationWarningMs = setting
            VibrationHelper.vibrateWarning(app)
            assertArrayEquals("row $i $label", want, pattern())
        }
    }

    /** The danger waveform repeats a 150 ms pulse vibrationDangerCount times, clamped to 1..5; unset, 3 times. */
    @Test
    fun `danger pulse count follows the setting within 1 to 5 and defaults to 3`() {
        val rows: List<Triple<String, Int?, LongArray>> = listOf(
            Triple("기본값(3)에서 위험 진동 파형은 변경 전과 같아야 한다", null, longArrayOf(0, 150, 100, 150, 100, 150)),
            Triple("vibrationDangerCount=5 이면 150ms 펄스가 5회여야 한다", 5,
                longArrayOf(0, 150, 100, 150, 100, 150, 100, 150, 100, 150)),
            Triple("dangerCount=0 은 1회로 제한돼야 한다", 0, longArrayOf(0, 150)),
            Triple("dangerCount=9 는 5회로 제한돼야 한다", 9, longArrayOf(0, 150, 100, 150, 100, 150, 100, 150, 100, 150))
        )
        for ((i, row) in rows.withIndex()) {
            val (label, setting, want) = row
            clearSettings()
            if (setting != null) DevSettings.vibrationDangerCount = setting
            VibrationHelper.vibrateDanger(app)
            assertArrayEquals("row $i $label", want, pattern())
        }
    }
}
