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
 * power. A zone report after the zone hold limit first leaves the zone at the limit. The held check opens
 * only after sensor data up to the hold end has arrived (at most LATE_MS later), counting steps from the
 * hold end.
 * held = restored and kept hidden until the restart power is confirmed; bounce = the charger contact flips back
 * and forth around the restart (LoneWorkerTestKit lists these words and the timing constants).
 */
class LoneWorkerHoldTest : RestartKit() {

    /** Carried on the dock, still check open at 190 s, restarted unplugged: the still check is held. */
    private fun heldStill(): LoneWorkerLogic {
        val old = carriedWhileCharging()
        assertEquals(Mode.CHECKING, old.seenAt(190_000))
        assertEquals("still", old.trigger)
        return restart(old, savedAt = 200_000, now = 5_000, wallGap = 20_000)
    }

    @Test fun held_check_dropped_when_zone_settles() {
        val old = carriedWhileCharging()
        old.onZone(true, 150_000)
        assertEquals(Mode.CHECKING, old.seenAt(190_000))
        assertFalse(old.zoneSettled)
        val l = restart(old, savedAt = 200_000, now = 5_000, wallGap = 600_000, zoneInside = true)
        assertEquals(Mode.WATCHING, l.modeAt(5_000))
        assertTrue(l.zoneSettled)
        assertEquals("", l.snapshot(5_000).check)
        assertEquals(Mode.WATCHING, l.seenAt(7_600))
    }

    @Test fun held_still_dropped_by_distinct_motion() {
        val l = heldStill()
        assertEquals("still", l.snapshot(5_000).check)
        l.walk(7_500, 5)
        assertEquals("", l.snapshot(7_500).check)
        assertEquals(Mode.WATCHING, l.seenAt(7_600))
        // five steps after the restart unplug edge (5 s): carried from the distinct motion once the unplug is confirmed
        assertEquals(Rest.NONE, l.rest)
    }

    @Test fun held_fall_dropped_by_motion_keeps_suspicion() {
        val old = newLogic(charging = true)
        old.onAccident(1_000)
        assertEquals(Mode.CHECKING, old.seenAt(31_000))
        assertEquals("fall", old.trigger)
        val l = restart(old, savedAt = 41_000, now = 5_000, wallGap = 20_000)
        l.walk(7_500, 5)
        assertEquals(Mode.WATCHING, l.seenAt(7_600))
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
        assertEquals(Mode.WATCHING, l.seenAt(7_600))
    }

    @Test fun held_check_dropped_by_restored_sos() {
        val l = heldStill()
        l.restoreSos("still", 5_000)
        assertTrue(l.cancelSos(6_000))
        assertEquals("", l.snapshot(6_000).check)
        assertEquals(Mode.WATCHING, l.seenAt(7_600))
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
        val l = reboot(newLogic(charging = true, zoneInside = true), savedAt = 20_000, now = 5_000, wallGap = 20_000, zoneInside = true)
        l.seenAt(17_500)
        l.onAccident(5_500)
        assertEquals(Mode.WATCHING, l.seenAt(35_499))
        assertEquals(Mode.CHECKING, l.seenAt(35_500))
        assertEquals("fall", l.trigger)
        // carried in the zone, restarted on the dock: a docked fall in the zone is ignored
        val p = reboot(newLogic(zoneInside = true, carried = true), savedAt = 20_000, now = 5_000, wallGap = 20_000, charging = true, zoneInside = true)
        p.seenAt(17_500)
        p.onAccident(5_500)
        assertEquals(Mode.WATCHING, p.seenAt(35_500))
        assertNull(p.snapshot(35_500).accidentUntil)
    }

    @Test fun restart_unplug_bounce_shows_check_at_the_report() {
        for (gapTick in listOf(false, true)) {
            val m = "gapTick=$gapTick"
            val l = heldStill()
            l.bounceRestartPower(now = false, gapTick = gapTick, m = m)
            assertEquals(m, Mode.WATCHING, l.seenAt(5_800 + PowerDebounce.DEBOUNCE_MS - 1))
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
            val l = restart(accidentCheck(), savedAt = 41_000, now = 5_000, wallGap = 20_000, charging = true)
            // the first wait starts at the restart time
            assertEquals(m, 5_000 + PowerDebounce.CONFIRM_MS, l.nextCheckAt(5_000))
            assertEquals(m, Mode.WATCHING, l.modeAt(5_000))
            l.powerRaw(false, 5_500)
            l.powerRaw(true, 7_000)
            assertEquals(m, 7_000 + PowerDebounce.CONFIRM_MS, l.nextCheckAt(7_000))
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
        val l = restart(accidentCheck(), savedAt = 41_000, now = 5_000, wallGap = 20_000, charging = true)
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

    /**
     * Only the first power change reported after a restart is the restart change, not a docking motion: a fall after a
     * restart on the dock counts. A later plug is real, even inside the hold window, and as a docking motion it drops
     * the fall. Rows: the saved logic, the power at the restart, an optional later plug, the Rest when checked, the
     * fall, then the modes seen at the listed times, the trigger when checked, and when the suspicion must be gone.
     */
    @Test fun only_the_first_power_report_after_a_restart_is_not_a_docking_motion() {
        class Row(val name: String, val old: () -> LoneWorkerLogic, val charging: Boolean, val plugAt: Long?,
                  val rest: Rest?, val fallAt: Long, val seen: List<Pair<Long, Mode>>, val trigger: String?,
                  val noSuspicionAt: Long?)
        for (r in listOf(
            Row("restart on the dock", { newLogic(carried = true) }, charging = true, plugAt = null,
                rest = Rest.DOCKED, fallAt = 9_000, seen = listOf(38_999L to Mode.WATCHING, 39_000L to Mode.CHECKING),
                trigger = "fall", noSuspicionAt = null),
            Row("unplugged restart, plug at 7.5 s", { newLogic(charging = true) }, charging = false, plugAt = 7_500,
                rest = null, fallAt = 9_800, seen = listOf(39_800L to Mode.WATCHING), trigger = null,
                noSuspicionAt = 39_800),
            Row("unplugged restart, plug at 8.1 s", { newLogic(charging = true) }, charging = false, plugAt = 8_100,
                rest = null, fallAt = 9_800, seen = listOf(39_800L to Mode.WATCHING), trigger = null,
                noSuspicionAt = 39_800))) {
            val l = reboot(r.old(), savedAt = 20_000, now = 5_000, wallGap = 20_000, charging = r.charging)
            r.plugAt?.let {
                l.powerRaw(true, it)
                l.seenAt(it + PowerDebounce.DEBOUNCE_MS)
            }
            r.rest?.let { assertEquals("${r.name}: rest", it, l.rest) }
            l.onAccident(r.fallAt)
            for ((t, want) in r.seen) assertEquals("${r.name}: seen at $t", want, l.seenAt(t))
            r.trigger?.let { assertEquals("${r.name}: trigger", it, l.trigger) }
            r.noSuspicionAt?.let { assertNull("${r.name}: suspicion", l.snapshot(it).accidentUntil) }
        }
    }

    /** The held check opens only after data up to the hold end arrives, counting steps from the hold end. */
    @Test fun held_check_opens_after_data_to_the_hold_end() {
        // a fifth step after the unplug edge is accepted late: the held check drops and the unplug carries
        val l = heldStill()
        assertEquals(Mode.WATCHING, l.modeAt(5_000))
        l.step(5_100)
        l.step(5_400)
        l.step(5_700)
        l.step(5_900)
        l.onStep(6_500)
        val report = 5_000 + PowerDebounce.CONFIRM_MS
        assertEquals(Mode.WATCHING, l.modeAt(report))
        assertEquals("still", l.snapshot(report).check)
        assertTrue(l.waitingOnSensors(report))
        assertEquals(Rest.WAIT, l.rest)
        l.onWindow(MotionAnalyzer.Window(7_000, true))
        l.stepsFlushed(7_100)
        assertEquals(Mode.WATCHING, l.modeAt(7_100))
        assertEquals(Rest.NONE, l.rest)
        assertEquals("", l.snapshot(7_100).check)
        // a step after the hold end but before the check opens counts toward closing it
        val k = heldStill()
        k.modeAt(5_000)
        assertEquals(Mode.WATCHING, k.modeAt(report))
        k.step(7_300)
        assertEquals(Mode.CHECKING, k.modeAt(8_000))
        assertEquals("still", k.trigger)
        assertEquals(k.responseMs, k.responseLeftMs(8_000))
        k.walk(10_000, 4)
        assertEquals(Mode.WATCHING, k.seenAt(10_000))
    }

    /** Without sensor data the held check opens at the hold end + LATE_MS with the full response time. */
    @Test fun held_check_opens_at_the_backstop_without_data() {
        val l = heldStill()
        assertEquals(Mode.WATCHING, l.modeAt(5_000))
        val report = 5_000 + PowerDebounce.CONFIRM_MS
        assertEquals(Mode.WATCHING, l.modeAt(report))
        val holdEnd = 5_000 + PowerDebounce.DEBOUNCE_MS
        assertTrue(l.waitingToJudge(report))
        assertTrue(l.waitingOnSensors(report))
        assertEquals(holdEnd + LoneWorkerLogic.LATE_MS, l.nextCheckAt(report))
        assertEquals(Mode.WATCHING, l.modeAt(holdEnd + LoneWorkerLogic.LATE_MS - 1))
        assertEquals(Mode.CHECKING, l.modeAt(holdEnd + LoneWorkerLogic.LATE_MS))
        assertEquals("still", l.trigger)
        assertEquals(l.responseMs, l.responseLeftMs(holdEnd + LoneWorkerLogic.LATE_MS))
    }

    /** Steps after the restart arriving late still count from the restart: they drop the held check. */
    @Test fun restart_bounce_late_steps_drop_the_held_check() {
        val l = heldStill()
        l.bounceRestartPower(now = false, gapTick = false, m = "late steps")
        for (t in listOf(5_200L, 5_500L, 5_700L, 6_000L, 6_400L)) l.onStep(t)
        assertEquals(Mode.WATCHING, l.modeAt(5_800 + PowerDebounce.DEBOUNCE_MS))
        assertEquals("still", l.snapshot(5_800 + PowerDebounce.DEBOUNCE_MS).check)
        l.strongRun(6_000, 2)
        assertEquals("", l.snapshot(7_000).check)
        assertEquals(Mode.WATCHING, l.seenAt(8_000))
        assertEquals(Rest.WAIT, l.rest)
    }

    @Test fun zone_report_after_hold_expiry_leaves_first() {
        val old = newLogic(carried = true)
        old.onMoved(100_000)
        old.onZone(true, 100_000)
        assertEquals(Mode.WATCHING, old.seenAt(150_000))
        assertFalse(old.zoneSettled)
        // zone hold until 210 s; the first report comes at 230 s with no tick in between
        val l = reboot(old, savedAt = 150_000, now = 200_000, wallGap = 50_000, bootNow = boot)
        l.onZone(true, 230_000)
        assertFalse(l.zoneSettled)
        l.tick(290_000)
        assertTrue(l.zoneSettled)
    }
}
