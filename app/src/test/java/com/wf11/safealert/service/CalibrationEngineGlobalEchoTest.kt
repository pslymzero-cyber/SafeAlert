package com.wf11.safealert.service

import android.content.Context
import com.wf11.safealert.utils.DevSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Echo calibration is a device/model property (a two-way difference over the same path at the same moment, so
 * path loss cancels out and only the device/model property remains), so it accumulates in one global file regardless of site.
 * Switching sites neither splits the statistics nor clears the live map; at startup the current site's echo
 * file is handed over once to the global file (site values win, fb_* cache and stamps copied along, site file
 * emptied after the handover, idempotent on repeat calls).
 */
@RunWith(RobolectricTestRunner::class)
class CalibrationEngineGlobalEchoTest {

    private fun prefs(name: String) =
        RuntimeEnvironment.getApplication().getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun sitePrefs(code: String) = prefs(CalibrationEngine.ECHO_PREFS + "_" + code)

    private fun stats(total: Int): CalibrationEngine.EchoDiffStats {
        val s = CalibrationEngine.EchoDiffStats()
        s.totalTicks = total
        s.echoTicks = 1
        s.buckets[8] = 1
        return s
    }

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        DevSettings.init(app)
        DevSettings.siteCode = "SITEA"
        prefs(CalibrationEngine.ECHO_PREFS).edit().clear().commit()
        sitePrefs("SITEA").edit().clear().commit()
        sitePrefs("SITEB").edit().clear().commit()
        CalibrationEngine.init(app)
        CalibrationEngine.echoDiffLive.clear()
    }

    @After
    fun tearDown() {
        CalibrationEngine.echoDiffLive.clear()
        DevSettings.siteCode = ""
    }

    @Test
    fun siteSwitchKeepsEchoInGlobalFile() {
        CalibrationEngine.echoDiffLive["DEVX"] = stats(7)
        CalibrationEngine.persistEchoAll("")

        DevSettings.siteCode = "SITEB"
        assertTrue("site switch cleared live map", CalibrationEngine.echoDiffLive.containsKey("DEVX"))

        CalibrationEngine.echoDiffLive["DEVY"] = stats(5)
        CalibrationEngine.persistEchoAll("")

        val globalMap = CalibrationEngine.parseEchoBlob(
            prefs(CalibrationEngine.ECHO_PREFS).getString(CalibrationEngine.ECHO_KEY, "") ?: ""
        )
        assertTrue("global echo file missing DEVX", globalMap.containsKey("DEVX"))
        assertTrue("global echo file missing DEVY", globalMap.containsKey("DEVY"))
        assertEquals(7, globalMap["DEVX"]!!.totalTicks)

        assertNull("SITEA echo file has data", sitePrefs("SITEA").getString(CalibrationEngine.ECHO_KEY, null))
        assertNull("SITEB echo file has data", sitePrefs("SITEB").getString(CalibrationEngine.ECHO_KEY, null))
    }

    @Test
    fun migratesCurrentSiteFileOnce() {
        prefs(CalibrationEngine.ECHO_PREFS).edit()
            .putString(
                CalibrationEngine.ECHO_KEY,
                CalibrationEngine.serializeEchoBlob(mapOf("DEVX" to stats(1), "DEVW" to stats(2)))
            )
            .commit()
        sitePrefs("SITEA").edit()
            .putString(
                CalibrationEngine.ECHO_KEY,
                CalibrationEngine.serializeEchoBlob(mapOf("DEVX" to stats(9), "DEVZ" to stats(3)))
            )
            .putString("fb_priors", "SM-TEST|1.5|4000")
            .putLong("fb_fetched_at", 123L)
            .commit()

        CalibrationEngine.init(RuntimeEnvironment.getApplication())

        val globalMapAfter = CalibrationEngine.parseEchoBlob(
            prefs(CalibrationEngine.ECHO_PREFS).getString(CalibrationEngine.ECHO_KEY, "") ?: ""
        )
        assertTrue("global echo file missing DEVZ", globalMapAfter.containsKey("DEVZ"))
        assertTrue("global echo file missing DEVW", globalMapAfter.containsKey("DEVW"))
        assertEquals(9, globalMapAfter["DEVX"]!!.totalTicks)
        assertEquals("SM-TEST|1.5|4000", prefs(CalibrationEngine.ECHO_PREFS).getString("fb_priors", null))
        assertEquals(123L, prefs(CalibrationEngine.ECHO_PREFS).getLong("fb_fetched_at", 0L))
        assertTrue("site file not cleared", sitePrefs("SITEA").all.isEmpty())

        val globalBlobAfterFirstInit = prefs(CalibrationEngine.ECHO_PREFS).getString(CalibrationEngine.ECHO_KEY, "")

        CalibrationEngine.init(RuntimeEnvironment.getApplication())

        assertEquals(
            globalBlobAfterFirstInit,
            prefs(CalibrationEngine.ECHO_PREFS).getString(CalibrationEngine.ECHO_KEY, "")
        )
        assertTrue("site file not cleared after second init", sitePrefs("SITEA").all.isEmpty())
    }
}
