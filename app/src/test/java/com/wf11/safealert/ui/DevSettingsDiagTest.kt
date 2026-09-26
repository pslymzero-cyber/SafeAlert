package com.wf11.safealert.ui

import com.wf11.safealert.R
import com.wf11.safealert.service.DeviceStateRegistry
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.UwbRanger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import android.os.Looper
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * (quick-260927-bn9 결정 2·3) UWB 재구성 실패 사유 기록·해제, 상태 섹션 헤더 요약 채우기 회귀.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevSettingsDiagTest {

    @Test
    fun `noteRebuild 실패는 사유를 남기고 성공은 지운다`() {
        UwbRanger.noteRebuild("대기 스코프", IllegalStateException("boom"))
        assertEquals("재구성 실패(대기 스코프): boom", UwbRanger.liveInitError)

        UwbRanger.noteRebuild(null)
        assertNull(UwbRanger.liveInitError)
    }

    @Test
    fun `서비스 정지 상태에서 상태 요약이 서비스 정지를 보인다`() {
        DevSettings.init(RuntimeEnvironment.getApplication())
        DeviceStateRegistry.live = null
        val controller = Robolectric.buildActivity(DevSettingsActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()

        val summary = controller.get().findViewById<android.widget.TextView>(R.id.sec_state_summary).text.toString()
        assertEquals("서비스 정지", summary)

        controller.pause()
    }
}
