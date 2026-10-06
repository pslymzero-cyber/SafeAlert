package com.wf11.safealert.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import com.wf11.safealert.R
import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/**
 * The developer settings screen as a site manager meets it: the site settings and the per-site tuning (TX/RX, sound,
 * alert thresholds with role offsets) are shown, the internal judgment sections open with 7 taps on the version, the
 * site code falls back to the site entered when monitoring started, and changing the site, switching lone-worker checks
 * off or resetting asks first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevSettingsSiteSetupTest {

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("dev_settings", Context.MODE_PRIVATE).edit().clear().commit()
        DevSettings.init(app)
        DevSettings.homeSiteCode = "HOME1"
        DevSettings.siteCode = "HOME1"
        ShadowDialog.reset()
    }

    /** Leaves no switched-off safety setting behind for test classes that run later in this JVM. */
    @After
    fun tearDown() {
        RuntimeEnvironment.getApplication().getSharedPreferences("dev_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun screen(): DevSettingsActivity = Robolectric.buildActivity(DevSettingsActivity::class.java).setup().get()
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Presses a button of the dialog on screen (BUTTON_POSITIVE / BUTTON_NEGATIVE) and lets it run. */
    private fun answer(which: Int) {
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(which).performClick()
        idle()
    }

    /** Types into the site code field and leaves it, as the screen commits it on focus loss. */
    private fun DevSettingsActivity.enterSite(text: String) {
        val et = findViewById<EditText>(R.id.et_dev_site_code)
        et.setText(text)
        et.onFocusChangeListener.onFocusChange(et, false)
        idle()
    }

    private fun View.isInside(group: View): Boolean {
        var v: View? = parent as? View
        while (v != null) {
            if (v === group) return true
            v = v.parent as? View
        }
        return false
    }

    @Test
    fun `사업장마다 맞추는 송수신·소리·경보 기준은 바로 보이고 판정 내부값은 숨김 옵션에 있다`() {
        val a = screen()
        for (id in listOf(R.id.et_dev_site_code, R.id.et_sos_mail_to, R.id.switch_lone_worker, R.id.et_lw_still_min,
                          R.id.et_lw_response_min, R.id.seek_dev_alarm_volume)) {
            assertTrue("현장 설정은 바로 보인다", a.findViewById<View>(id).isShown)
        }
        for (id in listOf(R.id.sec_txrx_header, R.id.sec_sound_header, R.id.sec_alert_header)) {
            assertTrue("사업장 조정 구역은 바로 보인다", a.findViewById<View>(id).isShown)
        }
        // The sections start collapsed, so what matters is that these controls sit outside the hidden group
        val hidden = a.findViewById<View>(R.id.hidden_advanced_group)
        for (id in listOf(R.id.seek_dev_warn_rssi, R.id.seek_dev_dang_rssi, R.id.seek_walker_equip_bias,
                          R.id.seek_walker_epj_bias, R.id.seek_equip_equip_bias, R.id.et_wake_rssi,
                          R.id.spinner_scan_period, R.id.switch_vibration)) {
            assertFalse("사업장 조정 항목은 숨김 묶음 밖", a.findViewById<View>(id).isInside(hidden))
        }
        for (id in listOf(R.id.sec_param_header, R.id.sec_coop_header, R.id.sec_uwbadv_header, R.id.sec_state_header)) {
            assertFalse("판정 내부값·협력·UWB 고급·상태는 숨김", a.findViewById<View>(id).isShown)
        }
        val version = a.findViewById<TextView>(R.id.tv_app_version)
        repeat(7) { version.performClick() }
        assertTrue("버전 7번이면 숨은 구역이 나온다", a.findViewById<View>(R.id.sec_param_header).isShown)
    }

    // The hidden options open on the 7th tap on the version when each tap comes within 3 s of the previous one (slow taps
    //   are fine); a longer pause starts the count again.
    @Test
    fun `버전을 3초 안 간격으로 7번 누르면 숨김 옵션이 열린다`() {
        val a = screen()
        val version = a.findViewById<TextView>(R.id.tv_app_version)
        val hidden = a.findViewById<View>(R.id.hidden_advanced_group)
        repeat(6) { version.performClick(); idle(2_900) }
        idle(200)   // 3.1 s since the 6th tap: the count starts again
        version.performClick()
        assertEquals(View.GONE, hidden.visibility)
        repeat(6) { idle(2_900); version.performClick() }
        assertEquals(View.VISIBLE, hidden.visibility)
    }

    @Test
    fun `사업장 칸을 비우면 감시 시작 때 넣은 사업장으로 돌아간다`() {
        DevSettings.siteCode = "OTHER"
        screen().enterSite("")
        answer(AlertDialog.BUTTON_POSITIVE)
        assertEquals("HOME1", DevSettings.siteCode)
    }

    @Test
    fun `영문·숫자가 없는 사업장 코드는 받지 않는다`() {
        val a = screen()
        a.enterSite("물류센터")
        assertNull("확인 창도 뜨지 않는다", ShadowDialog.getLatestDialog())
        assertEquals("HOME1", DevSettings.siteCode)
        assertEquals("HOME1", a.findViewById<EditText>(R.id.et_dev_site_code).text.toString())
    }

    @Test
    fun `사업장은 확인해야 바뀐다`() {
        val a = screen()
        a.enterSite("new2")
        answer(AlertDialog.BUTTON_NEGATIVE)
        assertEquals("취소하면 그대로", "HOME1", DevSettings.siteCode)
        assertEquals("HOME1", a.findViewById<EditText>(R.id.et_dev_site_code).text.toString())
        a.enterSite("new2")
        answer(AlertDialog.BUTTON_POSITIVE)
        assertEquals("NEW2", DevSettings.siteCode)
        assertEquals("감시 시작 사업장은 그대로", "HOME1", DevSettings.homeSiteCode)
    }

    @Test
    fun `기본값 초기화는 확인 뒤 값을 되돌리고 사업장은 감시 시작 사업장으로 돌린다`() {
        DevSettings.siteCode = "OTHER"
        DevSettings.alarmVolume = 60
        val a = screen()
        a.findViewById<View>(R.id.btn_reset).performClick()
        answer(AlertDialog.BUTTON_NEGATIVE)
        assertEquals("취소하면 그대로", 60, DevSettings.alarmVolume)
        a.findViewById<View>(R.id.btn_reset).performClick()
        answer(AlertDialog.BUTTON_POSITIVE)
        assertEquals(100, DevSettings.alarmVolume)
        assertEquals("빈 사업장이 되지 않는다", "HOME1", DevSettings.siteCode)
    }

    @Test
    fun `무동작·넘어짐 확인을 끌 때는 물어본다`() {
        val sw = screen().findViewById<Switch>(R.id.switch_lone_worker)
        sw.isChecked = false
        answer(AlertDialog.BUTTON_NEGATIVE)
        assertTrue("취소하면 다시 켜진다", sw.isChecked)
        assertTrue(DevSettings.lwEnabled)
        sw.isChecked = false
        answer(AlertDialog.BUTTON_POSITIVE)
        assertFalse(DevSettings.lwEnabled)
    }

    @Test
    fun `꺼진 안전 스위치는 첫 화면 단독 작업 줄에 이름이 나온다`() {
        DevSettings.lwEnabled = false
        DevSettings.soundEnabled = false
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val status = TextView(activity).apply { visibility = View.GONE }
        LoneWorkerUi.onPoll(activity, status, View(activity), TextView(activity), true, { _, _ -> }) {}
        assertEquals(View.VISIBLE, status.visibility)
        assertEquals("꺼짐: 무동작·넘어짐 확인 · 경보음 (개발자 설정)", status.text.toString())
        assertEquals(activity.getColor(R.color.sa_warning), status.currentTextColor)
    }
}
