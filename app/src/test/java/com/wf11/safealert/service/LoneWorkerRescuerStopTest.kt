package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(l.cancelSos(t, rescuer = true))
        assertEquals(t + LoneWorkerLogic.RESCUER_STOP_DELAY_MS, l.stopAt)
        val at = l.stopAt!!
        assertFalse(l.takeStop(at - 1))
        assertTrue(l.takeStop(at))
        assertFalse(l.takeStop(at))
        assertNull(l.stopAt)
        assertTrue(LoneWorkerLogic.RESCUER_STOP_DELAY_MS >= LoneWorkerPeers.PEER_BLE_FALL_MS)
    }

    @Test fun own_release_inside_the_first_minute_asks_for_no_stop() {
        val l = sosLogic()
        val t = l.modeSinceMs + LoneWorkerLogic.RESCUER_BUTTON_MS - 1
        assertTrue(l.cancelSos(t, rescuer = true))
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
        val block = sourceBlock(noComments(serviceSource("LoneWorkerMonitor.kt")), "    private fun releaseOldSos() {")
        assertTrue(block.contains("logic.cancelSos(t)"))
        assertFalse(block.contains("rescuer"))
    }

    @Test fun restored_sos_older_than_a_minute_asks_for_the_stop_when_released() {
        val now = 500_000L
        val old = newLogic(carried = true).apply { restoreSos("still", now, 61_000) }
        assertTrue(old.cancelSos(now, rescuer = true))
        assertEquals(now + LoneWorkerLogic.RESCUER_STOP_DELAY_MS, old.stopAt)
        val young = newLogic(carried = true).apply { restoreSos("still", now, 59_000) }
        assertTrue(young.cancelSos(now, rescuer = true))
        assertNull(young.stopAt)
    }

    @Test fun pending_stop_keeps_the_phone_awake_and_a_new_start_cancels_it() {
        val l = sosLogic()
        val t = l.modeSinceMs + LoneWorkerLogic.RESCUER_BUTTON_MS
        l.cancelSos(t, rescuer = true)
        assertTrue(l.wakeNeeded(false, t))
        l.clearStop(t)
        assertFalse(l.wakeNeeded(false, t))
        assertFalse(l.takeStop(t + LoneWorkerLogic.RESCUER_STOP_DELAY_MS))
        val idle = newLogic(carried = true)
        assertFalse(idle.cancelSos(1_000, rescuer = true))
        assertNull(idle.stopAt)
    }

    /** The requester's phone: while the monitoring stop is pending it gives no peer SOS output; a new start brings it back. */
    @Test fun pending_stop_holds_back_peer_sos_until_a_new_start() {
        val l = sosLogic()
        val since = l.modeSinceMs
        l.onPeerBle("P", true, since + 10_000, 1)
        assertNull(l.peer("P").ringStartMs)
        val t = since + LoneWorkerLogic.RESCUER_BUTTON_MS
        l.onPeerBle("P", true, t - 1_000, 1)
        assertTrue(l.cancelSos(t, rescuer = true))
        assertEquals(t + LoneWorkerLogic.RESCUER_STOP_DELAY_MS, l.stopAt)
        assertTrue(l.audiblePeers().isEmpty())
        assertTrue(l.soundingPeers().isEmpty())
        assertFalse(l.alarmVibrates)
        assertNull(l.peer("P").ringStartMs)
        assertTrue(l.wakeNeeded(false, t))
        l.onPeerBle("P", true, t + 1_000, 1)
        l.tick(t + 1_000)
        assertNull(l.peer("P").ringStartMs)
        assertTrue(l.audiblePeers().isEmpty())
        l.clearStop(t + 2_000)
        assertNull(l.stopAt)
        assertEquals(t + 2_000, l.peer("P").ringStartMs)
        assertEquals(1, l.audiblePeers().size)
        assertEquals(1, l.soundingPeers().size)
        assertTrue(l.alarmVibrates)
    }

    /** The requester's phone: only the rescuer confirm window schedules the stop, not the worker's own window. */
    @Test fun release_from_the_workers_dialog_after_a_minute_asks_for_no_stop() {
        val l = sosLogic()
        val t = l.modeSinceMs + LoneWorkerLogic.RESCUER_BUTTON_MS + 5
        assertTrue(l.rescuerButton(t))
        assertTrue(l.cancelSos(t, rescuer = false))
        assertNull(l.stopAt)
        assertFalse(l.takeStop(t + 10 * LoneWorkerLogic.RESCUER_STOP_DELAY_MS))
        val activity = noComments(repoFile("app/src/main/java/com/wf11/safealert/05_ui/LoneWorkerActivity.kt"))
        assertTrue(sourceBlock(activity, "    private fun showConfirm() {").contains("LoneWorkerMonitor.current?.cancelSos(rescuer)"))
        val cancel = sourceBlock(noComments(serviceSource("LoneWorkerMonitor.kt")), "    fun cancelSos(rescuer: Boolean) {")
        assertTrue(cancel.contains("logic.cancelSos(t, rescuer)"))
    }

    /** The wiring is pinned on the code without comments, so a comment cannot satisfy a check. */
    @Test fun rescuer_stop_is_wired_through_the_main_screen_stop_path() {
        val monitor = noComments(serviceSource("LoneWorkerMonitor.kt"))
        val ble = noComments(serviceSource("BleService.kt"))
        val main = noComments(repoFile("app/src/main/java/com/wf11/safealert/05_ui/MainActivity.kt"))
        val cancel = sourceBlock(monitor, "    fun cancelSos(rescuer: Boolean) {")
        assertTrue(cancel.contains("logic.cancelSos(t, rescuer)"))
        assertTrue(cancel.contains("resume.clear()"))
        assertTrue(cancel.contains("handler.postDelayed(stopRunnable"))
        assertTrue(cancel.indexOf("render()") in 0 until cancel.indexOf("resume.clear()"))
        assertTrue(monitor.contains("private val stopRunnable = Runnable { if (started && logic.takeStop(now())) onRescuerStop() }"))
        val keep = sourceBlock(monitor, "    fun keepRunning() {")
        assertTrue(keep.contains("logic.clearStop(now())"))
        assertTrue(keep.contains("handler.removeCallbacks(stopRunnable)"))
        assertTrue(keep.contains("render()"))
        assertTrue(monitor.contains("if (logic.stopAt == null) resume.save(logic.snapshot(t), t)"))
        assertTrue(Regex("""ACTION_STOP\s*->\s*requestStop\(\)""").containsMatchIn(ble))
        val stop = sourceBlock(ble, "    private fun requestStop(): Boolean {")
        assertTrue(stop.indexOf("LoneWorkerResume.clearOnUserStop(") in 0 until stop.indexOf("stopAll()"))
        val lambda = ble.substringAfter("onRescuerStop = {").substringBefore("\n    }")
        val check = lambda.indexOf("if (requestStop())")
        val flag = lambda.indexOf("LoneWorkerMonitor.rescuerStopped = true")
        val notice = lambda.indexOf("ServiceStopNotice.show(this, permission = false, rescuer = true)")
        assertTrue(check >= 0)
        assertTrue(flag > check)
        assertTrue(notice > flag)
        assertFalse(ble.contains("onRescuerRelease"))
        for (branch in listOf("ACTION_START_DEVICE -> {", "ACTION_START_WALKER -> {")) {
            val body = ble.substringAfter(branch).substringBefore("applyMode()")
            assertTrue(branch, body.contains("loneWorker.keepRunning()"))
            assertTrue(branch, body.contains("LoneWorkerMonitor.rescuerStopped = false"))
        }
        assertEquals(2, Regex(Regex.escape("loneWorker.keepRunning()")).findAll(ble).count())
        val start = sourceBlock(main, "    private fun startServiceWithCurrentMode() {")
        val returnAt = start.indexOf("currentMode ?: return")
        val resetAt = start.indexOf("LoneWorkerMonitor.rescuerStopped = false")
        val launchAt = start.indexOf("startForegroundService(intent)")
        assertTrue(returnAt >= 0)
        assertTrue(resetAt > returnAt)
        assertTrue(launchAt > resetAt)
        assertTrue(main.contains("if (LoneWorkerUi.takeRescuerStop(this@MainActivity) && currentMode != null) stopServiceImmediately(tellService = false)"))
        val activity = noComments(repoFile("app/src/main/java/com/wf11/safealert/05_ui/LoneWorkerActivity.kt"))
        assertTrue(sourceBlock(activity, "    private fun showConfirm() {").contains("감시가 중지돼요"))
        assertEquals("구조자 확인으로 감시를 중지했습니다", LoneWorkerNotifier.RESCUER_STOPPED)
    }
}
