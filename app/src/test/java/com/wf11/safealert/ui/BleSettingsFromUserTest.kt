package com.wf11.safealert.ui

import android.widget.SeekBar
import com.wf11.safealert.R
import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression: the BLE settings screen must not save on re-read. Programmatic updates (the onResume re-read) only
 * refresh the display; only user input is saved to DevSettings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleSettingsFromUserTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        DevSettings.init(app)
    }

    @Test
    fun `onResume 되읽기는 저장하지 않고 요약만 갱신한다`() {
        DevSettings.rssiWarning = -80
        DevSettings.rssiDanger = -60
        val controller = Robolectric.buildActivity(BleSettingsActivity::class.java).setup()
        val activity = controller.get()

        DevSettings.rssiWarning = -55
        DevSettings.rssiDanger = -50
        controller.pause().resume()

        assertEquals("되읽기 후 rssiWarning 이 바뀌면 안 된다", -55, DevSettings.rssiWarning)
        assertEquals("되읽기 후 rssiDanger 가 바뀌면 안 된다", -50, DevSettings.rssiDanger)
        val summary = activity.findViewById<android.widget.TextView>(R.id.sec_alert_summary).text.toString()
        assertTrue("헤더 요약이 되읽은 값으로 갱신돼야 한다: $summary", summary.contains("경고 -55") && summary.contains("위험 -50"))
    }

    @Test
    fun `잠긴 사업장 칸에 setText 해도 siteCode 는 바뀌지 않는다`() {
        DevSettings.siteCode = "S1"
        val controller = Robolectric.buildActivity(BleSettingsActivity::class.java).setup()
        val activity = controller.get()

        activity.findViewById<android.widget.EditText>(R.id.et_uwb_site).setText("ZZ")

        assertEquals("사업장 칸 setText 는 저장을 부르면 안 된다", "S1", DevSettings.siteCode)
    }

    @Test
    fun `프로그램 progress 는 무저장, 사용자 조작만 저장된다`() {
        DevSettings.beaconGainPercent = 100
        val controller = Robolectric.buildActivity(BleSettingsActivity::class.java).setup()
        val activity = controller.get()
        val seek = activity.findViewById<SeekBar>(R.id.seek_beacon_gain)

        seek.progress = 20 // programmatic update

        assertEquals("프로그램 progress 변경은 저장되면 안 된다", 100, DevSettings.beaconGainPercent)

        shadowOf(seek).onSeekBarChangeListener.onProgressChanged(seek, 20, true) // user input

        assertEquals("사용자 조작은 저장돼야 한다", 200, DevSettings.beaconGainPercent)
    }
}
