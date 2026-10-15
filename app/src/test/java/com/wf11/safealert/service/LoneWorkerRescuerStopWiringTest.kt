package com.wf11.safealert.service

import android.content.Context
import com.wf11.safealert.support.BleServiceTestHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers

/**
 * Behavior that needs Android storage, on the requester's phone: the rescuer's release clears the saved restart state and the
 * save cache with it, and a stop request during the phone's own SOS is refused and saves the running state again.
 */
@RunWith(RobolectricTestRunner::class)
class LoneWorkerRescuerStopWiringTest {

    private val keys = listOf("running_mode", "running_since", "running_category", LoneWorkerResume.KEY)

    @Test fun rescuer_release_clear_makes_the_next_save_write_again() {
        val app = RuntimeEnvironment.getApplication()
        val sp = app.getSharedPreferences("safealert_prefs", Context.MODE_PRIVATE)
        sp.edit().putString("running_mode", "WALKER").putLong("running_since", 1L).putInt("running_category", 0)
            .putString("other_setting", "kept").commit()
        val resume = LoneWorkerResume(app)
        val s = LoneWorkerResume.State(null, null, "", false, false, 0L, false, null)
        resume.save(s, 1_000)
        assertNotNull(sp.getString(LoneWorkerResume.KEY, null))
        resume.clear()
        keys.forEach { assertFalse(it, sp.contains(it)) }
        // exactly those four keys go: any other setting survives a user stop
        assertEquals(setOf("other_setting"), sp.all.keys)
        resume.save(s, 2_000)
        assertNotNull(sp.getString(LoneWorkerResume.KEY, null))
        // clearOnUserStop hands back the same editor it was given (the caller commits)
        val editor = sp.edit()
        assertSame(editor, LoneWorkerResume.clearOnUserStop(editor))
    }

    @Test fun stop_request_during_an_sos_is_refused_and_saves_the_running_state_again() {
        val service = BleServiceTestHarness.newService()
        val monitor = ReflectionHelpers.getField<Lazy<LoneWorkerMonitor>>(service, "loneWorker\$delegate").value
        ReflectionHelpers.setField(monitor, "logic", newLogic(carried = true).apply { restoreSos("still", 500_000L, 61_000) })
        ReflectionHelpers.setField(monitor, "started", true)
        ReflectionHelpers.setField(service, "myMode", "WALKER")
        val sp = RuntimeEnvironment.getApplication().getSharedPreferences("safealert_prefs", Context.MODE_PRIVATE)
        sp.edit().clear().commit() // The main screen's stop button clears the running state before the service is asked
        try {
            assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(service, "requestStop"))
        } finally {
            BleService.lastStatus = ""
        }
        assertEquals("WALKER", sp.getString("running_mode", null))
        assertTrue(sp.getLong("running_since", 0L) > 0L)
        assertTrue(monitor.sosActive)
    }
}
