package com.wf11.safealert.ui

import com.wf11.safealert.utils.UwbRanger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression: UWB rebuild failure reasons are recorded and cleared, (the state header summary is checked in DevSettingsSiteSetupTest).
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
}
