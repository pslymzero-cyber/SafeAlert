package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import com.wf11.safealert.service.LoneWorkerLogic.Rest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The restored check held during the restart power hold follows the open check's close rules (settling,
 * distinct motion, turning off, a restored own SOS, a plug). A fall during the hold uses the raw power
 * at the restart, a restart plug is not a docking motion, and a zone report after the zone hold limit
 * first leaves the zone at the limit.
 */
class LoneWorkerHoldTest : RestartKit() {

    /** Carried on the dock, still check open at 190 s, restarted unplugged: the still check is held. */
    private fun heldStill(): LoneWorkerLogic {
        val old = newLogic(charging = true)
        old.walk(10_000, 10)
        assertEquals(Mode.CHECKING, old.seenAt(190_000))
        assertEquals("still", old.trigger)
        return restart(old, 200_000, 5_000, 20_000)
    }

    @Test fun held_check_dropped_when_zone_settles() {
        val old = newLogic(charging = true)
        old.walk(10_000, 10)
        old.onZone(true, 150_000)
        assertEquals(Mode.CHECKING, old.seenAt(190_000))
        assertFalse(old.zoneSettled)
        val l = restart(old, 200_000, 5_000, 600_000, zoneInside = true)
        assertEquals(Mode.WATCHING, l.monitorTick(5_000))
        assertTrue(l.zoneSettled)
        assertEquals("", l.snapshot(5_000).check)
        assertEquals(Mode.WATCHING, l.monitorTick(7_600))
    }

    @Test fun held_still_dropped_by_distinct_motion() {
        val l = heldStill()
        assertEquals("still", l.snapshot(5_000).check)
        l.walk(7_500, 5)
        assertEquals("", l.snapshot(7_500).check)
        assertEquals(Mode.WATCHING, l.monitorTick(7_600))
        assertEquals(Rest.WAIT, l.rest)
    }

    @Test fun held_fall_dropped_by_motion_keeps_suspicion() {
        val old = newLogic(charging = true)
        old.onAccident(1_000)
        assertEquals(Mode.CHECKING, old.seenAt(31_000))
        assertEquals("fall", old.trigger)
        val l = restart(old, 41_000, 5_000, 20_000)
        l.walk(7_500, 5)
        assertEquals(Mode.WATCHING, l.monitorTick(7_600))
        assertEquals(7_500L, l.snapshot(7_600).accidentHold)
        assertEquals(Mode.WATCHING, l.seenAt(37_499))
        assertEquals(Mode.CHECKING, l.seenAt(37_500))
        assertEquals("fall", l.trigger)
    }

    @Test fun held_check_dropped_when_turned_off() {
        val l = heldStill()
        l.setEnabled(false, 6_000)
        assertEquals("", l.snapshot(6_000).check)
        l.setEnabled(true, 6_500)
        assertEquals(Mode.WATCHING, l.monitorTick(7_600))
    }

    @Test fun held_check_dropped_by_restored_sos() {
        val l = heldStill()
        l.restoreSos("still", 5_000)
        assertTrue(l.cancelSos(6_000))
        assertEquals(Mode.WATCHING, l.monitorTick(7_600))
    }

    @Test fun settled_resume_does_not_open_still_check() {
        val s = LoneWorkerResume.State(null, null, "still", false, true, 0L, true, 0L)
        for (plugged in listOf(false, true)) {
            val l = LoneWorkerLogic("SAFEALERT_WALKER_ME")
            l.startFrom(5_000, true, plugged, s)
            assertEquals(Mode.WATCHING, l.modeAt(5_000))
            assertTrue(l.zoneSettled)
            assertEquals("", l.snapshot(5_000).check)
        }
    }

    @Test fun fall_during_hold_uses_restart_power_in_zone() {
        // docked in the zone, restarted unplugged: a fall before the unplug is confirmed is not a docked fall
        val l = restart(newLogic(charging = true, zoneInside = true), 20_000, 5_000, 20_000)
        l.onAccident(5_500)
        l.monitorTick(7_050)
        assertEquals(Mode.WATCHING, l.seenAt(35_499))
        assertEquals(Mode.CHECKING, l.seenAt(35_500))
        assertEquals("fall", l.trigger)
    }

    @Test fun restart_plug_is_not_a_docking_motion() {
        val l = reboot(newLogic(carried = true), 20_000, 5_000, 20_000, charging = true)
        assertEquals(Rest.DOCKED, l.rest)
        l.onAccident(9_000)
        assertEquals(Mode.WATCHING, l.seenAt(38_999))
        assertEquals(Mode.CHECKING, l.seenAt(39_000))
        assertEquals("fall", l.trigger)
    }

    @Test fun zone_report_after_hold_expiry_leaves_first() {
        val old = newLogic(carried = true)
        old.onMoved(100_000)
        old.onZone(true, 100_000)
        assertEquals(Mode.WATCHING, old.seenAt(150_000))
        assertFalse(old.zoneSettled)
        // zone hold until 210 s; the first report comes at 230 s with no tick in between
        val l = reboot(old, 150_000, 200_000, 50_000, bootNow = boot)
        l.onZone(true, 230_000)
        assertFalse(l.zoneSettled)
        l.tick(290_000)
        assertTrue(l.zoneSettled)
    }
}
