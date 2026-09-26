package com.wf11.safealert.service

import android.content.Context
import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 2026-09-24 코드 검토 ⑤ 회귀 — setter 가 siteCode 를 먼저 바꿔도 떠나는 사업장 통계는
 * 떠나는 사업장 파일로 가야 한다(새 사업장 파일로 흘러들어가면 안 된다).
 */
@RunWith(RobolectricTestRunner::class)
class CalibrationEngineSiteSwitchTest {

    private fun sitePrefs(code: String) =
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(CalibrationEngine.ECHO_PREFS + "_" + code, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        DevSettings.init(app)
        DevSettings.siteCode = "SITEA"
        CalibrationEngine.init(app)
        CalibrationEngine.echoDiffLive.clear()
        sitePrefs("SITEA").edit().clear().commit()
        sitePrefs("SITEB").edit().clear().commit()
    }

    @Test
    fun leavingSiteStatsSavedToLeavingSiteFile() {
        val stats = CalibrationEngine.EchoDiffStats()
        stats.totalTicks = 7
        stats.echoTicks = 1
        stats.buckets[8] = 1
        CalibrationEngine.echoDiffLive["DEV1"] = stats

        DevSettings.siteCode = "SITEB"
        CalibrationEngine.applySite("")

        val siteAMap = CalibrationEngine.parseEchoBlob(
            sitePrefs("SITEA").getString(CalibrationEngine.ECHO_KEY, "") ?: ""
        )
        assertTrue("leaving site SITEA file missing DEV1", siteAMap.containsKey("DEV1"))
        assertEquals(7, siteAMap["DEV1"]!!.totalTicks)

        val siteBMap = CalibrationEngine.parseEchoBlob(
            sitePrefs("SITEB").getString(CalibrationEngine.ECHO_KEY, "") ?: ""
        )
        assertFalse("new site SITEB file got DEV1", siteBMap.containsKey("DEV1"))

        assertTrue(CalibrationEngine.echoDiffLive.isEmpty())
    }
}
