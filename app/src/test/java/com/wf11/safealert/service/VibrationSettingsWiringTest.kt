package com.wf11.safealert.service

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
 * 진동 설정 2개(경고 펄스 길이, 위험 반복 횟수)가 실제 파형에 연결됐는지 ShadowVibrator 로 확인.
 * 사용자 결정 2026-09-25·09-27. SDK 34 고정 이유: 로컬 캐시된 Robolectric SDK jar 가 API 34 하나뿐.
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

    @Test
    fun `danger default 3 pulses unchanged`() {
        VibrationHelper.vibrateDanger(app)
        assertArrayEquals(
            "기본값(3)에서 위험 진동 파형은 변경 전과 같아야 한다",
            longArrayOf(0, 150, 100, 150, 100, 150),
            pattern()
        )
    }

    @Test
    fun `warning default pulse is 500ms`() {
        VibrationHelper.vibrateWarning(app)
        assertArrayEquals(
            "기본값(500)에서 경고 진동 펄스는 500ms 여야 한다",
            longArrayOf(0, 500, 200, 500),
            pattern()
        )
    }

    @Test
    fun `warning pulse follows setting`() {
        DevSettings.vibrationWarningMs = 1000L
        VibrationHelper.vibrateWarning(app)
        assertArrayEquals(
            "vibrationWarningMs=1000 이면 펄스가 1000ms 여야 한다",
            longArrayOf(0, 1000, 200, 1000),
            pattern()
        )
    }

    @Test
    fun `danger count follows setting`() {
        DevSettings.vibrationDangerCount = 5
        VibrationHelper.vibrateDanger(app)
        assertArrayEquals(
            "vibrationDangerCount=5 이면 150ms 펄스가 5회여야 한다",
            longArrayOf(0, 150, 100, 150, 100, 150, 100, 150, 100, 150),
            pattern()
        )
    }

    @Test
    fun `out of range settings are clamped`() {
        DevSettings.vibrationWarningMs = 5000L
        VibrationHelper.vibrateWarning(app)
        assertArrayEquals(
            "warningMs=5000 은 1000ms 로 제한돼야 한다",
            longArrayOf(0, 1000, 200, 1000),
            pattern()
        )

        DevSettings.vibrationWarningMs = 10L
        VibrationHelper.vibrateWarning(app)
        assertArrayEquals(
            "warningMs=10 은 100ms 로 제한돼야 한다",
            longArrayOf(0, 100, 200, 100),
            pattern()
        )

        DevSettings.vibrationDangerCount = 9
        VibrationHelper.vibrateDanger(app)
        assertArrayEquals(
            "dangerCount=9 는 5회로 제한돼야 한다",
            longArrayOf(0, 150, 100, 150, 100, 150, 100, 150, 100, 150),
            pattern()
        )

        DevSettings.vibrationDangerCount = 0
        VibrationHelper.vibrateDanger(app)
        assertArrayEquals(
            "dangerCount=0 은 1회로 제한돼야 한다",
            longArrayOf(0, 150),
            pattern()
        )
    }
}
