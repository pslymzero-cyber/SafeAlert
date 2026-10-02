package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import com.wf11.safealert.service.LoneWorkerLogic.Rest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Equipment-docked no-movement watch. B1: equipment mode (FORKLIFT/EPJ) + charging + not carried = mounted,
 * which does not rest; the count starts at the role switch or the plug-in. B2: MOVED and turns restart the count.
 * B3: same stillMs / responseMs, a settled safe zone counts nothing. B4: an open still window closes on a turn,
 * a 3 s walking-level window run or the ack, not on steps. B5: walker docking, carried devices and the fall rule
 * are unchanged. stillMs = 180_000, responseMs = 120_000.
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

    @Test fun walker_docked_still_rests() {
        val l = newLogic(charging = true)
        assertEquals(Rest.DOCKED, l.rest)
        for (t in 10_000L..600_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
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

    @Test fun turn_closes_mounted_still_check() {
        val l = mounted()
        l.opensAt(180_000)
        l.onTurn(200_000)
        assertEquals(Mode.WATCHING, l.mode)
        l.opensAt(380_000)
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

    @Test fun ack_closes_mounted_still_check() {
        val l = mounted()
        l.opensAt(180_000)
        assertTrue(l.ackWorking(190_000))
        assertEquals(Mode.WATCHING, l.mode)
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
}
