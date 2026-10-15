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

    /** The still check opens exactly at t; m (a row label) prefixes the assertion messages. */
    private fun LoneWorkerLogic.opensAt(t: Long, m: String? = null) {
        assertEquals(m, Mode.WATCHING, seenAt(t - 1))
        assertEquals(m, Mode.CHECKING, seenAt(t))
        assertEquals(m, "still", trigger)
    }

    /**
     * What starts or restarts the mounted count: the check opens stillMs after it. MOVED and a turn (while mounted), the
     * plug-in of an equipment device, the switch to the equipment role on the charger. A turn does nothing when not mounted
     * (a carried device, or equipment off the charger): the check opens at 180 s.
     * Rows: the device, the starting event, when the check opens.
     */
    @Test fun moved_or_turn_restarts_the_mounted_count() {
        class Row(val name: String, val make: () -> LoneWorkerLogic, val restart: LoneWorkerLogic.() -> Unit,
                  val opensAt: Long)
        for (r in listOf(
            Row("MOVED at 100 s", { mounted() }, { onMoved(100_000) }, 280_000),
            Row("turn at 100 s", { mounted() }, { onTurn(100_000) }, 280_000),
            Row("plug-in at 400 s", { newLogic().apply { setEquipment(true, 0L) } }, {
                reportPower(true, 400_000)
                assertEquals("plug-in at 400 s", Mode.WATCHING, seenAt(400_000 + PowerDebounce.CONFIRM_MS))
            }, 580_000),
            Row("role switch at 300 s", { newLogic(charging = true) }, {
                assertEquals("role switch at 300 s", Mode.WATCHING, seenAt(300_000))
                setEquipment(true, 300_000)
            }, 480_000),
            Row("turn, carried device", { newLogic(carried = true) }, { onTurn(100_000) }, 180_000),
            Row("turn, equipment off the charger", { newLogic(carried = true).apply { setEquipment(true, 0L) } },
                { onTurn(100_000) }, 180_000))) {
            val l = r.make()
            r.restart(l)
            l.opensAt(r.opensAt, r.name)
        }
    }

    /** A mounted still window closes on a turn or on [OK] (a 3 s shake: strong_run_closes_mounted_still_check). */
    @Test fun turn_or_ack_closes_mounted_still_check() {
        val l = mounted()
        assertEquals(Rest.NONE, l.rest)
        l.opensAt(180_000)
        assertEquals(120_000L, l.responseLeftMs(180_000))
        l.onTurn(200_000)
        assertEquals(Mode.WATCHING, l.mode)
        l.opensAt(380_000)
        assertTrue(l.ackWorking(390_000))
        assertEquals(Mode.WATCHING, l.mode)
    }

    private fun LoneWorkerLogic.stepsWithoutRun() {
        step(181_100); step(181_400); step(181_700)
        onWindow(MotionAnalyzer.Window(183_000, false))
        step(183_100); step(183_400)
    }

    /**
     * A mounted still window closes on a 3 s run of walking-level windows (181..184 s), not on steps: steps during a shake
     * move the step floor but not the shake-close floor, and steps alone do not close it (a carried device's do).
     */
    @Test fun strong_run_closes_mounted_still_check() {
        val l = mounted()
        l.opensAt(180_000)
        l.strongWindows(182_000, 183_000)
        assertEquals(Mode.CHECKING, l.mode)
        l.strongWindows(184_000)
        assertEquals(Mode.WATCHING, l.mode)

        val s = mounted()
        s.opensAt(180_000)
        s.step(181_100); s.step(181_400); s.step(181_700); s.step(182_100); s.step(182_400)
        assertEquals("steps during a shake", Mode.CHECKING, s.mode)
        s.strongWindows(184_000)
        assertEquals("steps during a shake", Mode.WATCHING, s.mode)

        val steps = mounted()
        steps.opensAt(180_000)
        steps.stepsWithoutRun()
        assertEquals("steps alone", Mode.CHECKING, steps.mode)
        assertEquals("steps alone", "still", steps.trigger)

        val c = newLogic(carried = true)
        c.opensAt(180_000)
        c.stepsWithoutRun()
        assertEquals("carried control", Mode.WATCHING, c.mode)
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

    /**
     * A closing input stamped after a passed SOS deadline (still waiting on sensor data) does not cancel the SOS: a turn,
     * or a mounted shake run that ends after it. Rows: the input, when the SOS is seen.
     */
    @Test fun turn_after_passed_sos_deadline_does_not_cancel_sos() {
        class Row(val name: String, val input: LoneWorkerLogic.() -> Unit, val sosAt: Long)
        for (r in listOf(
            Row("turn", {
                sensed(299_000)
                assertEquals("turn", Mode.CHECKING, modeAt(300_500))
                onTurn(301_500)
            }, 302_000),
            Row("shake run", { strongWindows(301_000, 302_000, 303_000) }, 304_000))) {
            val l = mounted()
            l.opensAt(180_000)
            r.input(l)
            assertEquals("${r.name}: still checking", Mode.CHECKING, l.mode)
            assertEquals("${r.name}: SOS", Mode.SOS, l.seenAt(r.sosAt))
        }
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
        // a turn does not close the fall check
        f.onTurn(50_000)
        assertEquals(Mode.CHECKING, f.mode)
        assertEquals("fall", f.trigger)

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
