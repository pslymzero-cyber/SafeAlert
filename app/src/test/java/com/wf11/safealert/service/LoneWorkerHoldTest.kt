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
 * distinct motion, turning off, a restored own SOS, a plug). The hold ends when the debounce reports the
 * restart change, else at the window end; a wait that started in the window and is still pending at the
 * window end extends it once until it reports or drops. Only the first change reported during the hold is
 * a restart change. A fall is reported 12 s after the impact, after the hold, so it sees the confirmed
 * power. A zone report after the zone hold limit first leaves the zone at the limit.
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
        assertEquals(Mode.WATCHING, l.modeAt(5_000))
        assertTrue(l.zoneSettled)
        assertEquals("", l.snapshot(5_000).check)
        assertEquals(Mode.WATCHING, l.modeAt(7_600))
    }

    @Test fun held_still_dropped_by_distinct_motion() {
        val l = heldStill()
        assertEquals("still", l.snapshot(5_000).check)
        l.walk(7_500, 5)
        assertEquals("", l.snapshot(7_500).check)
        assertEquals(Mode.WATCHING, l.modeAt(7_600))
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
        assertEquals(Mode.WATCHING, l.modeAt(7_600))
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
        assertEquals(Mode.WATCHING, l.modeAt(7_600))
    }

    @Test fun held_check_dropped_by_restored_sos() {
        val l = heldStill()
        l.restoreSos("still", 5_000)
        assertTrue(l.cancelSos(6_000))
        assertEquals(Mode.WATCHING, l.modeAt(7_600))
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
        val l = reboot(newLogic(charging = true, zoneInside = true), 20_000, 5_000, 20_000, zoneInside = true)
        l.seenAt(17_500)
        l.onAccident(5_500)
        assertEquals(Mode.WATCHING, l.seenAt(35_499))
        assertEquals(Mode.CHECKING, l.seenAt(35_500))
        assertEquals("fall", l.trigger)
        // carried in the zone, restarted on the dock: a docked fall in the zone is ignored
        val p = reboot(newLogic(zoneInside = true, carried = true), 20_000, 5_000, 20_000, charging = true, zoneInside = true)
        p.seenAt(17_500)
        p.onAccident(5_500)
        assertEquals(Mode.WATCHING, p.seenAt(35_500))
        assertNull(p.snapshot(35_500).accidentUntil)
    }

    @Test fun restart_unplug_rebounce_shows_check_at_the_report() {
        for (gapTick in listOf(false, true)) {
            val m = "gapTick=$gapTick"
            val l = heldStill()
            l.rebounce(now = false, gapTick = gapTick, m = m)
            assertEquals(m, Mode.WATCHING, l.modeAt(5_800 + PowerDebounce.DEBOUNCE_MS - 1))
            assertEquals(m, Mode.CHECKING, l.seenAt(5_800 + PowerDebounce.DEBOUNCE_MS))
            assertEquals(m, "still", l.trigger)
            assertEquals(m, l.responseMs, l.responseLeftMs(5_800 + PowerDebounce.DEBOUNCE_MS))
            assertEquals(m, Rest.WAIT, l.rest)
        }
    }

    /** A wait that started in the window extends the hold once: the check never shows and then closes. */
    @Test fun restart_power_wait_in_the_window_extends_the_hold() {
        val end = 5_000 + RestartHold.POWER_HOLD_MS
        for (gapTick in listOf(false, true)) {
            val m = "gapTick=$gapTick"
            val l = restart(accidentCheck(), 41_000, 5_000, 20_000, charging = true)
            // the first wait starts at the restart time
            assertEquals(m, 5_000 + PowerDebounce.CONFIRM_MS, l.nextCheckAt(5_000))
            assertEquals(m, Mode.WATCHING, l.modeAt(5_000))
            l.powerRaw(false, 5_500)
            l.powerRaw(true, 7_000)
            assertEquals(m, 7_000 + PowerDebounce.CONFIRM_MS, l.nextCheckAt(7_000))
            assertTrue(m, 7_000 + PowerDebounce.CONFIRM_MS < end + PowerDebounce.CONFIRM_MS)
            if (gapTick) assertEquals(m, Mode.WATCHING, l.seenAt(end))
            assertEquals(m, Mode.WATCHING, l.seenAt(7_000 + PowerDebounce.DEBOUNCE_MS - 1))
            // the restart plug reports: the held accident check and the suspicion end
            assertEquals(m, Mode.WATCHING, l.seenAt(7_000 + PowerDebounce.DEBOUNCE_MS))
            assertEquals(m, Rest.DOCKED, l.rest)
            assertNull(m, l.snapshot(7_000 + PowerDebounce.DEBOUNCE_MS).accidentHold)
            assertEquals(m, "", l.snapshot(7_000 + PowerDebounce.DEBOUNCE_MS).check)
            // a restart plug is not a docking motion
            l.onAccident(9_500)
            assertEquals(m, Mode.WATCHING, l.seenAt(39_499))
            assertEquals(m, Mode.CHECKING, l.seenAt(39_500))
            assertEquals(m, "fall", l.trigger)
        }
    }

    /** The window wait drops after the window end: the hold ends there, and a later wait does not hold again. */
    @Test fun restart_power_wait_dropped_after_the_window_ends_the_hold() {
        val end = 5_000 + RestartHold.POWER_HOLD_MS
        val l = restart(accidentCheck(), 41_000, 5_000, 20_000, charging = true)
        l.modeAt(5_000)
        l.powerRaw(false, 5_500)
        l.powerRaw(true, 7_000)
        assertEquals(Mode.WATCHING, l.seenAt(end))
        l.powerRaw(false, 8_500)
        assertEquals(Mode.CHECKING, l.seenAt(8_500))
        assertEquals("fall", l.trigger)
        assertEquals(LoneWorkerLogic.ACCIDENT_RESPONSE_MS, l.responseLeftMs(8_500))
        assertEquals(Rest.NONE, l.rest)
        l.powerRaw(true, 8_700)
        assertEquals(8_700 + PowerDebounce.CONFIRM_MS, l.nextCheckAt(8_700))
        assertEquals(Mode.CHECKING, l.seenAt(8_800))
        // a real plug: it closes the check and the suspicion
        assertEquals(Mode.WATCHING, l.seenAt(8_700 + PowerDebounce.DEBOUNCE_MS))
        assertEquals(Rest.DOCKED, l.rest)
        // a docking motion drops the fall
        l.onAccident(12_000)
        assertEquals(Mode.WATCHING, l.seenAt(42_000))
        assertNull(l.snapshot(42_000).accidentUntil)
    }

    /** Only the first change reported during the hold is a restart change; a later plug is real even inside the window. */
    @Test fun restart_power_changes_after_the_first_report_are_real() {
        for (plugAt in listOf(7_500L, 8_100L)) {
            val m = "plugAt=$plugAt"
            val l = reboot(newLogic(charging = true), 20_000, 5_000, 20_000)
            l.powerRaw(true, plugAt)
            l.seenAt(plugAt + PowerDebounce.DEBOUNCE_MS)
            // a docking motion drops the fall
            l.onAccident(9_800)
            assertEquals(m, Mode.WATCHING, l.seenAt(39_800))
            assertNull(m, l.snapshot(39_800).accidentUntil)
        }
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
