package com.wf11.safealert.ui

import android.view.MotionEvent
import android.view.View
import com.wf11.safealert.BuildConfig
import com.wf11.safealert.R
import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** (v1.1.97) BLE 설정 화면을 열면 비콘 수신 강도·UWB 사용이 잠겨 있다(PIN 확인 전). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleSettingsPinGateTest {

    @Before
    fun setUp() {
        DevSettings.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `수신 강도와 UWB 스위치는 처음에 잠겨 있다`() {
        val activity = Robolectric.buildActivity(BleSettingsActivity::class.java).setup().get()
        for (id in listOf(R.id.seek_beacon_gain, R.id.sw_uwb)) {
            val v = activity.findViewById<View>(id)
            assertFalse("잠금 전에는 조작할 수 없다", v.isEnabled)
            assertEquals(0.4f, v.alpha, 0.001f)
        }
        assertFalse("잠긴 스위치는 터치를 행으로 넘긴다", activity.findViewById<View>(R.id.sw_uwb).isClickable)
        // (v1.1.98) 잠긴 컨트롤이 터치를 먹지 않아야 부모(묶음·행)가 받아 PIN 창을 띄운다
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        for (id in listOf(R.id.seek_beacon_gain, R.id.sw_uwb)) {
            assertFalse("잠긴 컨트롤은 터치를 부모로 넘긴다", activity.findViewById<View>(id).onTouchEvent(down))
        }
        down.recycle()
    }

    @Test
    fun `잠긴 동안 수신 강도 묶음이나 UWB 행을 누르면 PIN 창이 뜬다`() {
        val activity = Robolectric.buildActivity(BleSettingsActivity::class.java).setup().get()
        for (id in listOf(R.id.group_beacon_gain, R.id.row_uwb)) {
            ShadowDialog.reset()
            activity.findViewById<View>(id).performClick()
            assertTrue("PIN 창이 떠야 한다", ShadowDialog.getLatestDialog()?.isShowing == true)
        }
    }

    /** (v1.1.98) PIN 을 맞히면 풀리고, 풀린 뒤 행은 누를 수 있는 항목으로 안내되지 않는다. */
    @Test
    fun `PIN 을 맞히면 풀리고 행은 더 이상 클릭 대상이 아니다`() {
        val activity = Robolectric.buildActivity(BleSettingsActivity::class.java).setup().get()
        activity.findViewById<View>(R.id.group_beacon_gain).performClick()
        val dialog = ShadowDialog.getLatestDialog()
        val keys = listOf(R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
                          R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9)
        BuildConfig.DEV_PIN.forEach { dialog.findViewById<View>(keys[it - '0']).performClick() }
        assertTrue("수신 강도가 풀린다", activity.findViewById<View>(R.id.seek_beacon_gain).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.group_beacon_gain).isClickable)
        assertFalse(activity.findViewById<View>(R.id.row_uwb).isClickable)
    }
}
