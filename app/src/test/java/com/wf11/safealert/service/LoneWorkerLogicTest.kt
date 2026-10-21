package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoneWorkerLogic contract tests. The clock is an injected elapsed ms; no Android dependency.
 */
class LoneWorkerLogicTest {

    private val stillMs = 180_000L
    private val responseMs = 120_000L

    private fun LoneWorkerLogic.toChecking() { seenAt(stillMs) }
    private fun LoneWorkerLogic.toSos() { seenAt(stillMs); seenAt(stillMs + responseMs) }

    // -- own state --

    /**
     * The still check opens at 180 s (not 179.999 s) and, unanswered for the response time, becomes SOS; in SOS
     * [OK] is ignored, cancelSos ends it once. (The [OK] and fidget closing of a check:
     * LoneWorkerStillCountTest.still_check_closed_by_ok_or_distinct_motion_not_by_moved.)
     */
    @Test fun unanswered_check_escalates_to_sos() {
        val l = newLogic(carried = true)
        l.seenAt(179_999)
        assertEquals(Mode.WATCHING, l.mode)
        l.seenAt(180_000)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("still", l.trigger)
        l.seenAt(180_000 + responseMs - 1)
        assertEquals(Mode.CHECKING, l.mode)
        l.seenAt(180_000 + responseMs)
        assertEquals(Mode.SOS, l.mode)
        assertTrue(l.sosActive)
        assertFalse(l.ackWorking(400_000))
        assertEquals(Mode.SOS, l.mode)
        assertTrue(l.cancelSos(400_000))
        assertEquals(Mode.WATCHING, l.mode)
        assertFalse(l.cancelSos(400_001))
    }

    // An SOS ended by the one-hour limit (holdStill) leaves the phone waiting as after an unplug: lying still raises no
    //   new check however long, and walking starts the no-motion count again.
    //   A mounted equipment device (equipment role on the charger) waits the same way, and a turn starts its count again.
    @Test fun sos_ended_by_the_limit_waits_for_movement() {
        class Row(val name: String, val make: () -> LoneWorkerLogic, val resume: LoneWorkerLogic.() -> Unit,
                  val opensAt: Long, val rest: LoneWorkerLogic.Rest?)
        val walked = 400_000 + 10 * stillMs
        for (r in listOf(
            Row("carried: walking", { newLogic(carried = true) }, { walk(walked + 10_000, 10) },
                walked + 10_000 + stillMs, LoneWorkerLogic.Rest.NONE),
            Row("mounted equipment: turn", { newLogic(charging = true).apply { setEquipment(true, 0L) } },
                { onTurn(2_300_000) }, 2_480_000, null))) {
            val l = r.make()
            l.toSos()
            assertEquals("${r.name}: SOS", Mode.SOS, l.mode)
            assertTrue("${r.name}: cancel", l.cancelSos(400_000))
            l.holdStill(400_000)
            assertEquals("${r.name}: waits", LoneWorkerLogic.Rest.WAIT, l.rest)
            assertEquals("${r.name}: no new check", Mode.WATCHING, l.seenAt(walked))
            r.resume(l)
            r.rest?.let { assertEquals("${r.name}: rest", it, l.rest) }
            assertEquals("${r.name}: before the count ends", Mode.WATCHING, l.seenAt(r.opensAt - 1))
            assertEquals("${r.name}: checks again", Mode.CHECKING, l.seenAt(r.opensAt))
            assertEquals("${r.name}: trigger", "still", l.trigger)
        }
    }

    /**
     * Disabled: no check opens (still or fall), an open check (still or fall) is withdrawn, an SOS stays. Enabling
     * again restarts the still count from the enable time.
     */
    @Test fun disabled_never_checks_and_cancels_open_check_but_keeps_sos() {
        val off = newLogic(carried = true)
        off.setEnabled(false, 0)
        off.tick(600_000)
        assertEquals(Mode.WATCHING, off.mode)
        off.setEnabled(true, 600_000)
        off.seenAt(600_000 + stillMs - 1)
        assertEquals(Mode.WATCHING, off.mode)
        off.seenAt(600_000 + stillMs)
        assertEquals(Mode.CHECKING, off.mode)

        val chk = newLogic(carried = true)
        chk.toChecking()
        chk.setEnabled(false, 181_000)
        assertEquals(Mode.WATCHING, chk.mode)

        val sos = newLogic(carried = true)
        sos.toSos()
        sos.setEnabled(false, 400_000)
        assertEquals(Mode.SOS, sos.mode)

        // the fall rule: no fall check opens while disabled; an open fall check is withdrawn and a re-enable does not revive it
        val fallOff = fallRuleOnly()
        fallOff.setEnabled(false, 0)
        fallOff.onAccident(10_000)
        assertEquals(Mode.WATCHING, fallOff.seenAt(40_000))

        val fallChk = fallRuleOnly()
        fallChk.onAccident(10_000)
        assertEquals(Mode.CHECKING, fallChk.seenAt(40_000))
        fallChk.setEnabled(false, 41_000)
        assertEquals(Mode.WATCHING, fallChk.mode)
        fallChk.setEnabled(true, 42_000)
        for (t in 50_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, fallChk.seenAt(t))
    }

    // -- safe zone --

    @Test fun settled_zone_cancels_still_check_but_not_accident() {
        val l = newLogic(carried = true)
        l.toChecking()
        l.onZone(true, 180_000)
        l.tick(239_999)
        assertEquals(Mode.CHECKING, l.mode)
        l.tick(240_000)
        assertTrue(l.zoneSettled)
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(900_000)
        assertEquals(Mode.WATCHING, l.mode)
        l.onAccident(900_100)
        l.seenAt(930_100)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("fall", l.trigger)
        l.tick(931_000)
        assertEquals(Mode.CHECKING, l.mode)
    }

    /** A zone that never settles (inside 59 s, then out; flapping in and out) does not touch the still count. */
    @Test fun inside_59s_then_out_does_not_reset_still_count() {
        val l = newLogic(carried = true)
        l.onZone(true, 100_000)
        l.onZone(false, 159_000)
        l.seenAt(179_999)
        assertEquals(Mode.WATCHING, l.mode)
        l.seenAt(180_000)
        assertEquals(Mode.CHECKING, l.mode)

        val f = newLogic(carried = true)
        var t = 0L
        while (t < 175_000) {
            f.onZone(true, t)
            f.tick(t)
            f.onZone(false, t + 1_000)
            f.tick(t + 1_000)
            t += 5_000
        }
        assertFalse(f.zoneSettled)
        f.seenAt(180_000)
        assertEquals(Mode.CHECKING, f.mode)
    }

    // -- beacon hint (peer SOS: LoneWorkerPeerTest) --

    @Test fun beacon_hint_takes_strongest_within_60s() {
        val l = newLogic(carried = true)
        l.noteBeacon("A", -50, 0)
        l.noteBeacon("B", -70, 30_000)
        assertEquals("A" to -50, l.beaconHint(50_000))
        assertEquals("B" to -70, l.beaconHint(70_000))
        assertNull(l.beaconHint(100_000))

        // the registered sid follows the same 60 s window: the strongest registered sample
        val s = newLogic(carried = true)
        assertEquals(0, s.beaconSid(0))
        s.noteBeacon("A", -40, 0, 0)
        s.noteBeacon("B", -60, 10_000, 11)
        s.noteBeacon("C", -50, 20_000, 22)
        assertEquals(22, s.beaconSid(30_000))
        assertEquals(0, s.beaconSid(90_000))
    }

    @Test fun restore_sos_survives_settle_disable_and_ack() {
        val l = newLogic(carried = true)
        l.restoreSos("fall", 5_000)
        assertEquals(Mode.SOS, l.mode)
        assertEquals("fall", l.trigger)
        assertTrue(l.sosActive)
        l.onZone(true, 6_000)
        l.tick(70_000)
        assertTrue(l.zoneSettled)
        assertEquals(Mode.SOS, l.mode)
        l.setEnabled(false, 71_000)
        assertFalse(l.ackWorking(72_000))
        assertEquals(Mode.SOS, l.mode)
        assertTrue(l.cancelSos(73_000))
        assertEquals(Mode.WATCHING, l.mode)

        // a restored SOS also holds against a new fall (a separate instance: disabling would hide a fall that got through)
        val n = fallRuleOnly()
        n.restoreSos("still", 1_000)
        n.onAccident(10_000)
        assertEquals(Mode.SOS, n.seenAt(40_000))
        n.cancelSos(41_000)
        for (t in 50_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, n.seenAt(t))
    }

    // From RESCUER_BUTTON_MS after the SOS began the own SOS button is the rescuer's; the switch is a time cue for the monitor.
    @Test fun own_sos_button_turns_to_the_rescuers_after_60s() {
        val l = newLogic(carried = true)
        assertFalse(l.rescuerButton(10_000_000))
        l.toChecking()
        assertFalse(l.rescuerButton(l.modeSinceMs + 10 * LoneWorkerLogic.RESCUER_BUTTON_MS))
        l.seenAt(stillMs + responseMs)
        assertEquals(Mode.SOS, l.mode)
        val t = l.modeSinceMs
        assertFalse(l.rescuerButton(t + 59_999))
        assertTrue(l.rescuerButton(t + 60_000))
        assertEquals(t + 60_000, l.nextCueAt(t + 1))
        assertNull(l.nextCueAt(t + 60_000))
        assertTrue(l.cancelSos(t + 61_000))
        assertFalse(l.rescuerButton(t + 62_000))
        assertEquals(l.stillCueAt(), l.nextCueAt(t + 62_000)) // no rescuer cue left; only the restarted no-motion count's cue

        // A restored SOS keeps its age: the stored start decides, not the restart.
        val a = newLogic(carried = true)
        a.restoreSos("still", 5_000, 45_000)
        assertFalse(a.rescuerButton(19_999))
        assertTrue(a.rescuerButton(20_000))
        val b = newLogic(carried = true)
        b.restoreSos("still", 5_000, 60_000)
        assertTrue(b.rescuerButton(5_000))
        val c = newLogic(carried = true)
        c.restoreSos("still", 5_000)
        assertFalse(c.rescuerButton(5_000))
        assertTrue(c.rescuerButton(65_000))
    }

    @Test fun own_sos_button_is_wired_through_the_monitor() {
        val src = serviceSource("LoneWorkerMonitor.kt")
        assertTrue(src.contains("logic.restoreSos(it, now(), sync.sosBeganAgoMs())"))
        val ui = src.substring(src.indexOf("return UiState("))
        assertTrue(ui.substringBefore("\n    }").contains("logic.rescuerButton(t)"))
        val update = src.substring(src.indexOf("notifier.update(")).lineSequence().first()
        assertTrue(update, update.contains("logic.rescuerButton(t)"))
        val activity = repoFile("app/src/main/java/com/wf11/safealert/05_ui/LoneWorkerActivity.kt")
        assertTrue(activity.contains("LoneWorkerNotifier.sosExitLabel("))
        // an open confirm dialog is rebuilt when the button becomes the rescuer's
        val render = activity.substring(activity.indexOf("if (st.mode == LoneWorkerLogic.Mode.SOS) {"))
        val before = render.substring(0, render.indexOf("if (pendingConfirm) showConfirm()"))
        assertTrue(before.contains("confirmRescuer != st.rescuerButton"))
        assertTrue(before.contains("confirmDialog?.dismiss()"))
        assertTrue(before.contains("pendingConfirm = true"))
        assertTrue(sourceBlock(activity, "    private fun showConfirm() {").contains("confirmRescuer = rescuer"))
    }

    @Test fun sos_exit_texts_come_from_one_helper() {
        assertEquals("괜찮아요", LoneWorkerNotifier.sosExitLabel(false))
        assertEquals(LoneWorkerNotifier.RESCUER_OK, LoneWorkerNotifier.sosExitLabel(true))
        assertEquals("[괜찮아요]를 눌러야 해제돼요", LoneWorkerNotifier.sosExitHint(false))
        assertEquals("[구조자 확인]을 눌러야 해제돼요", LoneWorkerNotifier.sosExitHint(true))
        assertEquals("[괜찮아요]로 먼저 해제하세요", LoneWorkerNotifier.sosExitFirst(false))
        assertEquals("[구조자 확인]으로 먼저 해제하세요", LoneWorkerNotifier.sosExitFirst(true))
        val ui = "app/src/main/java/com/wf11/safealert/05_ui/"
        for (src in listOf(repoFile(ui + "LoneWorkerActivity.kt"), repoFile(ui + "LoneWorkerUi.kt"), serviceSource("BleService.kt"))) {
            assertFalse(src.contains("눌러야 해제돼요"))
            assertFalse(src.contains("먼저 해제하세요"))
        }
        val kv = Kv()
        assertEquals(0L, SosLedger.beganAgoMs(kv, 100_000L))
        kv.put(mapOf(SosLedger.K_TRIGGER to "still", "a.began" to "40000"))
        assertEquals(60_000L, SosLedger.beganAgoMs(kv, 100_000L))
        assertEquals(0L, SosLedger.beganAgoMs(kv, 30_000L))
    }
}
