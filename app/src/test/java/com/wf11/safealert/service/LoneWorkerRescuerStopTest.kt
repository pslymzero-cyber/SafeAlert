package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The requester's phone stops monitoring a short delay after the rescuer's button released its own SOS. All logic tests
 * here run on the requester's phone; the wiring is pinned in the sources.
 */
class LoneWorkerRescuerStopTest {

    private val stillMs = 180_000L
    private val responseMs = 120_000L

    /** An own SOS entered by the no-motion rule; modeSinceMs is its start. */
    private fun sosLogic(): LoneWorkerLogic = newLogic(carried = true).apply {
        seenAt(stillMs)
        seenAt(stillMs + responseMs)
        assertEquals(Mode.SOS, mode)
    }

    @Test fun rescuer_release_asks_once_for_a_monitoring_stop_after_the_ble_release_time() {
        val l = sosLogic()
        val t = l.modeSinceMs + LoneWorkerLogic.RESCUER_BUTTON_MS
        assertTrue(l.cancelSos(t, byUser = true))
        assertEquals(t + LoneWorkerLogic.RESCUER_STOP_DELAY_MS, l.stopAt)
        val at = l.stopAt!!
        assertFalse(l.takeStop(at - 1))
        assertTrue(l.takeStop(at))
        assertFalse(l.takeStop(at))
        assertNull(l.stopAt)
        assertEquals(LoneWorkerPeers.PEER_LIVE_AD_MS, LoneWorkerLogic.RESCUER_STOP_DELAY_MS)
        assertTrue(LoneWorkerLogic.RESCUER_STOP_DELAY_MS >= LoneWorkerPeers.PEER_BLE_FALL_MS)
    }

    @Test fun own_release_inside_the_first_minute_asks_for_no_stop() {
        val l = sosLogic()
        val t = l.modeSinceMs + LoneWorkerLogic.RESCUER_BUTTON_MS - 1
        assertTrue(l.cancelSos(t, byUser = true))
        assertNull(l.stopAt)
        assertFalse(l.takeStop(t))
        assertFalse(l.takeStop(t + 10 * LoneWorkerLogic.RESCUER_STOP_DELAY_MS))
    }

    @Test fun one_hour_auto_release_asks_for_no_stop() {
        val l = sosLogic()
        val t = l.modeSinceMs + 3_600_000L
        assertTrue(l.cancelSos(t))
        l.holdStill(t)
        assertNull(l.stopAt)
        assertFalse(l.takeStop(t + 10 * LoneWorkerLogic.RESCUER_STOP_DELAY_MS))
        val block = sourceBlock(serviceSource("LoneWorkerMonitor.kt"), "    private fun releaseOldSos() {")
        assertTrue(block.contains("logic.cancelSos(t)"))
        assertFalse(block.contains("byUser"))
    }

    @Test fun restored_sos_older_than_a_minute_asks_for_the_stop_when_released() {
        val now = 500_000L
        val old = newLogic(carried = true).apply { restoreSos("still", now, 61_000) }
        assertTrue(old.cancelSos(now, byUser = true))
        assertEquals(now + LoneWorkerLogic.RESCUER_STOP_DELAY_MS, old.stopAt)
        val young = newLogic(carried = true).apply { restoreSos("still", now, 59_000) }
        assertTrue(young.cancelSos(now, byUser = true))
        assertNull(young.stopAt)
    }

    @Test fun pending_stop_keeps_the_phone_awake_and_a_new_start_cancels_it() {
        val l = sosLogic()
        val t = l.modeSinceMs + LoneWorkerLogic.RESCUER_BUTTON_MS
        l.cancelSos(t, byUser = true)
        assertTrue(l.wakeNeeded(false, t))
        l.clearStop()
        assertFalse(l.wakeNeeded(false, t))
        assertFalse(l.takeStop(t + LoneWorkerLogic.RESCUER_STOP_DELAY_MS))
        val idle = newLogic(carried = true)
        assertFalse(idle.cancelSos(1_000, byUser = true))
        assertNull(idle.stopAt)
    }

    @Test fun rescuer_stop_is_wired_through_the_main_screen_stop_path() {
        val monitor = serviceSource("LoneWorkerMonitor.kt")
        val cancel = sourceBlock(monitor, "    fun cancelSos() {")
        assertTrue(cancel.contains("logic.cancelSos(t, byUser = true)"))
        assertTrue(cancel.contains("onRescuerRelease()"))
        assertTrue(cancel.contains("handler.postDelayed(stopRunnable"))
        assertTrue(cancel.indexOf("render()") in 0 until cancel.indexOf("onRescuerRelease()"))
        val ble = serviceSource("BleService.kt")
        assertTrue(Regex("""ACTION_STOP\s*->\s*requestStop\(\)""").containsMatchIn(ble))
        val stop = sourceBlock(ble, "    private fun requestStop(): Boolean {")
        assertTrue(stop.contains("LoneWorkerResume.clearOnUserStop("))
        assertTrue(stop.contains("stopAll()"))
        assertTrue(ble.contains("onRescuerStop"))
        assertTrue(ble.contains("onRescuerRelease"))
        assertEquals(2, Regex(Regex.escape("loneWorker.keepRunning()")).findAll(ble).count())
        val main = repoFile("app/src/main/java/com/wf11/safealert/05_ui/MainActivity.kt")
        assertTrue(main.contains("LoneWorkerUi.takeRescuerStop("))
        assertTrue(main.contains("stopServiceImmediately(tellService = false)"))
        assertTrue(repoFile("app/src/main/java/com/wf11/safealert/05_ui/LoneWorkerActivity.kt").contains("감시가 중지돼요"))
        assertNotNull(LoneWorkerNotifier.RESCUER_STOPPED)
    }
}
