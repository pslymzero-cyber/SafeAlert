package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import com.wf11.safealert.service.LoneWorkerLogic.Rest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The restored check held during the restart power hold follows the open check's close rules (settling,
 * distinct motion, turning off, a restored own SOS, a plug). The hold is a fixed window after the restart
 * that the debounce report ends early; a bounce neither ends nor extends it, and a change whose first
 * edge falls in the window is a restart change. A fall is reported 12 s after the impact, after the
 * hold, so it sees the confirmed power. A zone report after the zone hold limit first leaves the zone
 * at the limit.
 */
class LoneWorkerHoldTest : RestartKit() {

    /** Carried on the dock, still check open at 190 s, restarted unplugged: the still check is held. */
    private fun heldStill(): LoneWorkerLogic {
        val old = carriedWhileCharging()
        assertEquals(Mode.CHECKING, old.seenAt(190_000))
        assertEquals("still", old.trigger)
        return restart(old, 200_000, 5_000, 20_000)
    }

    @Test fun held_check_dropped_when_zone_settles() {
        val old = carriedWhileCharging()
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
        // five steps after the restart unplug edge (5 s): carried from the distinct motion once the unplug is confirmed
        assertEquals(Rest.NONE, l.rest)
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

    @Test fun fall_after_restart_power_uses_confirmed_power_in_zone() {
        // a fall is reported 12 s after the impact: docked in the zone, restarted unplugged, the fall counts
        val l = restart(newLogic(charging = true, zoneInside = true), 20_000, 5_000, 20_000, zoneInside = true)
        l.monitorTick(5_000 + PowerDebounce.CONFIRM_MS)
        l.seenAt(17_500)
        l.onAccident(5_500)
        assertEquals(Mode.WATCHING, l.seenAt(35_499))
        assertEquals(Mode.CHECKING, l.seenAt(35_500))
        assertEquals("fall", l.trigger)
        // carried in the zone, restarted on the dock: a docked fall in the zone is ignored
        val p = restart(newLogic(zoneInside = true, carried = true), 20_000, 5_000, 20_000, charging = true, zoneInside = true)
        p.monitorTick(5_000 + PowerDebounce.CONFIRM_MS)
        p.seenAt(17_500)
        p.onAccident(5_500)
        assertEquals(Mode.WATCHING, p.seenAt(35_500))
        assertNull(p.snapshot(35_500).accidentUntil)
    }

    @Test fun restart_unplug_rebounce_shows_check_at_the_report() {
        for (gapTick in listOf(false, true)) {
            val m = "gapTick=$gapTick"
            val l = heldStill()
            assertEquals(m, Mode.WATCHING, l.monitorTick(5_000))
            power.raw(true, 5_500)
            if (gapTick) assertEquals(m, Mode.WATCHING, l.monitorTick(5_600))
            power.raw(false, 5_800)
            assertEquals(m, Mode.WATCHING, l.monitorTick(7_799))
            assertEquals(m, Mode.CHECKING, l.monitorTick(7_800))
            assertEquals(m, "still", l.trigger)
            assertEquals(m, l.responseMs, l.responseLeftMs(7_800))
            assertEquals(m, Rest.WAIT, l.rest)
        }
    }

    @Test fun restart_power_bounce_past_the_hold_is_a_restart_change() {
        // the bounce keeps the debounce waiting past the hold: the hold still ends at the window end
        val end = 5_000 + RestartHold.POWER_HOLD_MS
        val l = restart(accidentCheck(), 41_000, 5_000, 20_000, charging = true)
        l.monitorTick(5_000)
        power.raw(false, 5_500)
        power.raw(true, 7_000)
        assertEquals(Mode.WATCHING, l.monitorTick(end - 1))
        assertEquals(Mode.CHECKING, l.monitorTick(end))
        assertEquals("fall", l.trigger)
        assertEquals(LoneWorkerLogic.ACCIDENT_RESPONSE_MS, l.responseLeftMs(end))
        // first edge 7 s is inside the window: a restart plug, it closes the check and the suspicion
        assertEquals(Mode.WATCHING, l.monitorTick(9_050))
        assertEquals(Rest.DOCKED, l.rest)
        assertNull(l.snapshot(9_050).accidentHold)
        l.onAccident(9_500)
        assertEquals(Mode.WATCHING, l.seenAt(39_499))
        assertEquals(Mode.CHECKING, l.seenAt(39_500))
        assertEquals("fall", l.trigger)
    }

    @Test fun restart_power_window_classifies_changes() {
        // the restart unplug is confirmed first; a plug starting inside the window is a restart change
        val inside = restart(newLogic(charging = true), 20_000, 5_000, 20_000)
        inside.monitorTick(5_000 + PowerDebounce.CONFIRM_MS)
        power.raw(true, 7_500)
        inside.monitorTick(9_550)
        inside.onAccident(9_800)
        assertEquals(Mode.WATCHING, inside.seenAt(39_799))
        assertEquals(Mode.CHECKING, inside.seenAt(39_800))
        assertEquals("fall", inside.trigger)
        // a plug starting after the window is a real plug: a docking motion drops the fall
        val after = restart(newLogic(charging = true), 20_000, 5_000, 20_000)
        after.monitorTick(5_000 + PowerDebounce.CONFIRM_MS)
        power.raw(true, 8_100)
        after.monitorTick(10_150)
        after.onAccident(9_800)
        assertEquals(Mode.WATCHING, after.seenAt(39_800))
        assertNull(after.snapshot(39_800).accidentUntil)
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
