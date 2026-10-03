package com.wf11.safealert.ui

import android.content.pm.PackageManager
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** When the BLE settings screen opens, beacon reception strength and UWB use are locked (until the PIN is confirmed). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleSettingsPinGateTest {

    @Before
    fun setUp() {
        DevSettings.init(RuntimeEnvironment.getApplication())
        uwbHardware(true)   // so a disabled UWB switch is the lock's doing, not missing hardware
    }

    private fun uwbHardware(present: Boolean) =
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_UWB, present)

    private fun unlockedActivity(): BleSettingsActivity {
        val activity = Robolectric.buildActivity(BleSettingsActivity::class.java).setup().get()
        activity.findViewById<View>(R.id.group_beacon_gain).performClick()
        val dialog = ShadowDialog.getLatestDialog()
        val keys = listOf(R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
                          R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9)
        BuildConfig.DEV_PIN.forEach { dialog.findViewById<View>(keys[it - '0']).performClick() }
        return activity
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
        // A locked control must not consume touches, so its parent (group/row) gets them and opens the PIN dialog
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

    /** The correct PIN unlocks both controls (enabled, clickable, full alpha) and makes both rows non-clickable. */
    @Test
    fun `PIN 을 맞히면 풀리고 행은 더 이상 클릭 대상이 아니다`() {
        val activity = unlockedActivity()
        val gain = activity.findViewById<View>(R.id.seek_beacon_gain)
        val sw = activity.findViewById<View>(R.id.sw_uwb)
        assertTrue("수신 강도가 풀린다", gain.isEnabled)
        assertTrue("UWB 스위치가 풀린다", sw.isEnabled)
        assertTrue("풀린 스위치는 직접 누를 수 있다", sw.isClickable)
        for (v in listOf(gain, sw)) assertEquals(1f, v.alpha, 0.001f)
        assertFalse(activity.findViewById<View>(R.id.group_beacon_gain).isClickable)
        assertFalse(activity.findViewById<View>(R.id.row_uwb).isClickable)
    }

    @Test
    fun `UWB 하드웨어가 없으면 PIN 을 맞혀도 UWB 스위치는 꺼져 있다`() {
        uwbHardware(false)
        val sw = unlockedActivity().findViewById<View>(R.id.sw_uwb)
        assertFalse("미지원 기기는 PIN 뒤에도 꺼져 있다", sw.isEnabled)
        assertTrue("터치는 행이 아니라 스위치가 받는다", sw.isClickable)
    }
}
