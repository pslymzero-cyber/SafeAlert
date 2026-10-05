package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import com.wf11.safealert.service.LoneWorkerLogic.Rest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Equipment-docked no-movement watch. Equipment mode (FORKLIFT/EPJ) + charging + not carried = mounted,
 * which does not rest; the count starts at the role switch or the plug-in. MOVED and turns restart the count.
 * Same stillMs / responseMs as a carried device; a settled safe zone counts nothing. An open still window closes
 * on a turn, a 3 s walking-level window run or the ack, not on steps. Walker docking, carried devices and the fall
 * rule are unchanged. stillMs = 180_000, responseMs = 120_000.
 */
class MountedStillTest {

    private fun mounted() = newLogic(charging = true).apply { setEquipment(true, 0L) }

    private fun LoneWorkerLogic.opensAt(t: Long) {
        assertEquals(Mode.WATCHING, seenAt(t - 1))
        assertEquals(Mode.CHECKING, seenAt(t))
        assertEquals("still", trigger)
    }

    @Test fun mounted_still_check_opens_after_still_ms() {
        val l = mounted()
        assertEquals(Rest.NONE, l.rest)
        l.opensAt(180_000)
        assertEquals(120_000L, l.responseLeftMs(180_000))
    }

    @Test fun moved_restarts_mounted_count() {
        val l = mounted()
        l.onMoved(100_000)
        l.opensAt(280_000)
    }

    @Test fun turn_restarts_mounted_count() {
        val l = mounted()
        l.onTurn(100_000)
        l.opensAt(280_000)
    }

    // A mount whose SOS ended by the one-hour limit waits as well: no new check while the equipment stands still, and a
    //   turn starts the count again.
    @Test fun mount_after_an_sos_ended_by_the_limit_waits_for_a_turn() {
        val l = mounted()
        l.opensAt(180_000)
        assertEquals(Mode.SOS, l.seenAt(300_000))
        l.cancelSos(400_000)
        l.holdStill(400_000)
        assertEquals(Rest.WAIT, l.rest)
        assertEquals(Mode.WATCHING, l.seenAt(2_200_000))
        l.onTurn(2_300_000)
        l.opensAt(2_480_000)
    }

    /** A mounted still window closes on a turn or on [OK] (a 3 s shake: strong_run_closes_mounted_still_check). */
    @Test fun turn_or_ack_closes_mounted_still_check() {
        val l = mounted()
        l.opensAt(180_000)
        l.onTurn(200_000)
        assertEquals(Mode.WATCHING, l.mode)
        l.opensAt(380_000)
        assertTrue(l.ackWorking(390_000))
        assertEquals(Mode.WATCHING, l.mode)
    }

    @Test fun strong_run_closes_mounted_still_check() {
        val l = mounted()
        l.opensAt(180_000)
        l.strongWindows(182_000, 183_000)
        assertEquals(Mode.CHECKING, l.mode)
        l.strongWindows(184_000)
        assertEquals(Mode.WATCHING, l.mode)
    }

    private fun LoneWorkerLogic.stepsWithoutRun() {
        step(181_100); step(181_400); step(181_700)
        onWindow(MotionAnalyzer.Window(183_000, false))
        step(183_100); step(183_400)
    }

    @Test fun steps_do_not_close_mounted_still_check() {
        val l = mounted()
        l.opensAt(180_000)
        l.stepsWithoutRun()
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("still", l.trigger)

        val c = newLogic(carried = true)
        c.opensAt(180_000)
        c.stepsWithoutRun()
        assertEquals(Mode.WATCHING, c.mode)
    }

    @Test fun settled_zone_counts_nothing_when_mounted() {
        val l = newLogic(charging = true, zoneInside = true).apply { setEquipment(true, 0L) }
        for (t in 60_000L..600_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    @Test fun plug_in_starts_mounted_count() {
        val l = newLogic().apply { setEquipment(true, 0L) }
        l.reportPower(true, 400_000)
        assertEquals(Mode.WATCHING, l.seenAt(400_000 + PowerDebounce.CONFIRM_MS))
        l.opensAt(580_000)
    }

    @Test fun role_switch_starts_count_at_switch() {
        val l = newLogic(charging = true)
        assertEquals(Mode.WATCHING, l.seenAt(300_000))
        l.setEquipment(true, 300_000)
        l.opensAt(480_000)
    }

    @Test fun turn_ignored_when_not_mounted() {
        for (l in listOf(newLogic(carried = true), newLogic(carried = true).apply { setEquipment(true, 0L) })) {
            l.onTurn(100_000)
            assertEquals(Mode.CHECKING, l.seenAt(180_000))
            assertEquals("still", l.trigger)
        }
    }

    @Test fun turn_keeps_fall_check_open() {
        val l = mounted()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        assertEquals("fall", l.trigger)
        l.onTurn(50_000)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("fall", l.trigger)
    }

    /** An equipment device on the charger is not carried by steps or by strong windows; turns keep counting. */
    @Test fun mounted_device_not_carried_by_steps_or_strong_windows() {
        val a = mounted()
        a.walk(60_000, 12)
        assertTrue(a.mounted)
        assertEquals(Rest.NONE, a.rest)
        a.onTurn(100_000)
        a.opensAt(280_000)

        val b = mounted()
        b.stepsAvailable = false
        b.strongWindows(50_000, 52_000, 54_000, 56_000, 58_000)
        assertTrue(b.mounted)
        assertEquals(Rest.NONE, b.rest)
        b.onTurn(100_000)
        b.opensAt(280_000)
    }

    /** Steps during a shake move the step floor but not the shake-close floor (3 s run 181..184 s). */
    @Test fun steps_during_shake_do_not_block_mounted_close() {
        val l = mounted()
        l.opensAt(180_000)
        l.step(181_100); l.step(181_400); l.step(181_700); l.step(182_100); l.step(182_400)
        assertEquals(Mode.CHECKING, l.mode)
        l.strongWindows(184_000)
        assertEquals(Mode.WATCHING, l.mode)
    }

    /** A turn stamped after a passed SOS deadline (still waiting on sensor data) does not cancel the SOS. */
    @Test fun turn_after_passed_sos_deadline_does_not_cancel_sos() {
        val l = mounted()
        l.opensAt(180_000)
        l.sensed(299_000)
        assertEquals(Mode.CHECKING, l.modeAt(300_500))
        l.onTurn(301_500)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals(Mode.SOS, l.seenAt(302_000))
    }

    /** A mounted shake run that ends after a passed SOS deadline does not cancel the SOS. */
    @Test fun shake_after_passed_sos_deadline_does_not_cancel_sos() {
        val l = mounted()
        l.opensAt(180_000)
        l.strongWindows(301_000, 302_000, 303_000)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals(Mode.SOS, l.seenAt(304_000))
    }

    /** Only a mounted still window says it closes by turning, shaking or the ack; the text has one source. */
    @Test fun mounted_still_check_says_turn_or_shake_closes_it() {
        val m = mounted()
        assertFalse(m.closesByTurn)
        m.opensAt(180_000)
        assertTrue(m.closesByTurn)

        val f = mounted()
        f.onAccident(10_000)
        assertEquals(Mode.CHECKING, f.seenAt(40_000))
        assertEquals("fall", f.trigger)
        assertFalse(f.closesByTurn)

        val c = newLogic(carried = true)
        c.opensAt(180_000)
        assertFalse(c.closesByTurn)

        assertTrue(LoneWorkerNotifier.TURN_CLOSE_HINT.contains("${LoneWorkerLogic.STRONG_RUN_MS / 1000}"))
        assertTrue(LoneWorkerNotifier.TURN_CLOSE_HINT.contains("["))
        // wiring: the notification and both screen spots pick the hint by closesByTurn (negated or dropped = fail)
        assertEquals(1, serviceSource("LoneWorkerNotifier.kt").split("if (closesByTurn) TURN_CLOSE_HINT else").size - 1)
        val activity = repoFile("app/src/main/java/com/wf11/safealert/05_ui/LoneWorkerActivity.kt")
        assertEquals(2, activity.split("if (st.closesByTurn) LoneWorkerNotifier.TURN_CLOSE_HINT else").size - 1)
    }

    /** The wake lock and its 5 s renew loop also run while mounted and the watch is on (screen-off turn poll). */
    @Test fun mounted_keeps_wake_lock_and_renew_loop() {
        val m = mounted()
        assertTrue(m.wakeNeeded(false, 1_000))
        assertTrue(m.loopNeeded(false))
        m.setEnabled(false, 2_000)
        assertEquals(Mode.WATCHING, m.mode)
        assertFalse(m.wakeNeeded(false, 2_000))
        assertFalse(m.loopNeeded(false))

        // a docked walker (charging, no equipment mode) needs neither
        val docked = newLogic(charging = true)
        assertFalse(docked.wakeNeeded(false, 1_000))
        assertFalse(docked.loopNeeded(false))

        // a passed deadline waiting for its power judgment keeps the CPU awake
        val w = newLogic(carried = true)
        assertFalse(w.wakeNeeded(false, 1_000))
        w.powerRaw(true, 179_500)
        assertEquals(Mode.WATCHING, w.seenAt(180_000))
        assertTrue(w.waitingToJudge(180_000))
        assertTrue(w.wakeNeeded(false, 180_000))
    }
}
