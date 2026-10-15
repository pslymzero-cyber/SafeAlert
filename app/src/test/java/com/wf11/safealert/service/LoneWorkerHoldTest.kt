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

        // a resume that is settled in the zone never opens the still check, plugged or not
        val s = LoneWorkerResume.State(null, null, "still", false, true, 0L, true, 0L)
        for (plugged in listOf(false, true)) {
            val m = "plugged=$plugged"
            val r = LoneWorkerLogic("SAFEALERT_WALKER_ME")
            r.startFrom(5_000, true, plugged, s)
            assertEquals(m, Mode.WATCHING, r.modeAt(5_000))
            assertTrue(m, r.zoneSettled)
            assertEquals(m, "", r.snapshot(5_000).check)
        }
    }

    /**
     * A held still check is dropped by what closes an open check: distinct motion (5 steps after the restart unplug edge
     * at 5 s: carried from the distinct motion once the unplug is confirmed), turning the app off, or a restored own SOS
     * that ends.
     * Rows: the closing event, when the dropped check is read, an event after it, and the Rest when checked.
     */
    @Test fun held_still_dropped_by_distinct_motion() {
        class Row(val name: String, val checkAt: Long, val close: LoneWorkerLogic.() -> Unit,
                  val after: LoneWorkerLogic.() -> Unit, val rest: Rest?)
        for (r in listOf(
            Row("distinct motion", 7_500, { walk(7_500, 5) }, {}, Rest.NONE),
            Row("turned off", 6_000, { setEnabled(false, 6_000) }, { setEnabled(true, 6_500) }, null),
            Row("restored SOS", 6_000, { restoreSos("still", 5_000); assertTrue(cancelSos(6_000)) }, {}, null))) {
            val l = heldStill()
            assertEquals("${r.name}: held", "still", l.snapshot(5_000).check)
            r.close(l)
            assertEquals("${r.name}: dropped", "", l.snapshot(r.checkAt).check)
            r.after(l)
            assertEquals("${r.name}: watching", Mode.WATCHING, l.seenAt(7_600))
            r.rest?.let { assertEquals("${r.name}: rest", it, l.rest) }
        }
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

    /**
     * The restart power bounces: the held still check shows at the report (with or without a tick in the gap). The restart
     * steps arriving late still count from the restart and drop it instead.
     */
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
        // late steps: they drop the held check
        val d = heldStill()
        d.bounceRestartPower(now = false, gapTick = false, m = "late steps")
        for (t in listOf(5_200L, 5_500L, 5_700L, 6_000L, 6_400L)) d.onStep(t)
        assertEquals("late steps", Mode.WATCHING, d.modeAt(5_800 + PowerDebounce.DEBOUNCE_MS))
        assertEquals("late steps", "still", d.snapshot(5_800 + PowerDebounce.DEBOUNCE_MS).check)
        d.strongRun(6_000, 2)
        assertEquals("late steps", "", d.snapshot(7_000).check)
        assertEquals("late steps", Mode.WATCHING, d.seenAt(8_000))
        assertEquals("late steps", Rest.WAIT, d.rest)
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

        // the window wait drops after the window end: the hold ends there, and a later wait does not hold again
        val w = restart(accidentCheck(), savedAt = 41_000, now = 5_000, wallGap = 20_000, charging = true)
        w.modeAt(5_000)
        w.powerRaw(false, 5_500)
        w.powerRaw(true, 7_000)
        assertEquals("dropped wait", Mode.WATCHING, w.seenAt(end))
        w.powerRaw(false, 8_500)
        assertEquals("dropped wait", Mode.CHECKING, w.seenAt(8_500))
        assertEquals("dropped wait", "fall", w.trigger)
        assertEquals("dropped wait", LoneWorkerLogic.ACCIDENT_RESPONSE_MS, w.responseLeftMs(8_500))
        assertEquals("dropped wait", Rest.NONE, w.rest)
        w.powerRaw(true, 8_700)
        assertEquals("dropped wait", 8_700 + PowerDebounce.CONFIRM_MS, w.nextCheckAt(8_700))
        assertEquals("dropped wait", Mode.CHECKING, w.seenAt(8_800))
        // a real plug: it closes the check and the suspicion
        assertEquals("dropped wait", Mode.WATCHING, w.seenAt(8_700 + PowerDebounce.DEBOUNCE_MS))
        assertEquals("dropped wait", Rest.DOCKED, w.rest)
        // a docking motion drops the fall
        w.onAccident(12_000)
        assertEquals("dropped wait", Mode.WATCHING, w.seenAt(42_000))
        assertNull("dropped wait", w.snapshot(42_000).accidentUntil)
    }

    /**
     * Only the first power change reported after a restart is the restart change, not a docking motion: a fall after a
     * restart on the dock counts. A later plug is real, even inside the hold window, and as a docking motion it drops
     * the fall. Rows: the saved logic, the power at the restart, an optional later plug, the Rest when checked, the
     * fall, then the modes seen at the listed times, the trigger when checked, and when the suspicion must be gone. A
     * fall is reported 12 s after the impact, so a row may restart in the zone and report the fall after a tick at
     * reportAt: it sees the confirmed power (docked in the zone, restarted unplugged: the fall counts; carried in
     * the zone, restarted on the dock: a docked fall in the zone is ignored).
     */
    @Test fun only_the_first_power_report_after_a_restart_is_not_a_docking_motion() {
        class Row(val name: String, val old: () -> LoneWorkerLogic, val charging: Boolean, val plugAt: Long?,
                  val rest: Rest?, val fallAt: Long, val seen: List<Pair<Long, Mode>>, val trigger: String?,
                  val noSuspicionAt: Long?, val zoneInside: Boolean = false, val reportAt: Long? = null)
        for (r in listOf(
            Row("zone, restart unplugged from the dock", { newLogic(charging = true, zoneInside = true) },
                charging = false, plugAt = null, rest = null, fallAt = 5_500,
                seen = listOf(35_499L to Mode.WATCHING, 35_500L to Mode.CHECKING), trigger = "fall",
                noSuspicionAt = null, zoneInside = true, reportAt = 17_500),
            Row("zone, restart on the dock", { newLogic(zoneInside = true, carried = true) },
                charging = true, plugAt = null, rest = null, fallAt = 5_500,
                seen = listOf(35_500L to Mode.WATCHING), trigger = null,
                noSuspicionAt = 35_500, zoneInside = true, reportAt = 17_500),
            Row("restart on the dock", { newLogic(carried = true) }, charging = true, plugAt = null,
                rest = Rest.DOCKED, fallAt = 9_000, seen = listOf(38_999L to Mode.WATCHING, 39_000L to Mode.CHECKING),
                trigger = "fall", noSuspicionAt = null),
            Row("unplugged restart, plug at 7.5 s", { newLogic(charging = true) }, charging = false, plugAt = 7_500,
                rest = null, fallAt = 9_800, seen = listOf(39_800L to Mode.WATCHING), trigger = null,
                noSuspicionAt = 39_800),
            Row("unplugged restart, plug at 8.1 s", { newLogic(charging = true) }, charging = false, plugAt = 8_100,
                rest = null, fallAt = 9_800, seen = listOf(39_800L to Mode.WATCHING), trigger = null,
                noSuspicionAt = 39_800))) {
            val l = reboot(r.old(), savedAt = 20_000, now = 5_000, wallGap = 20_000, charging = r.charging,
                zoneInside = r.zoneInside)
            r.reportAt?.let { l.seenAt(it) }
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

        // without sensor data the held check opens at the hold end + LATE_MS with the full response time
        val b = heldStill()
        assertEquals(Mode.WATCHING, b.modeAt(5_000))
        assertEquals(Mode.WATCHING, b.modeAt(report))
        val holdEnd = 5_000 + PowerDebounce.DEBOUNCE_MS
        assertTrue(b.waitingToJudge(report))
        assertTrue(b.waitingOnSensors(report))
        assertEquals(holdEnd + LoneWorkerLogic.LATE_MS, b.nextCheckAt(report))
        assertEquals(Mode.WATCHING, b.modeAt(holdEnd + LoneWorkerLogic.LATE_MS - 1))
        assertEquals(Mode.CHECKING, b.modeAt(holdEnd + LoneWorkerLogic.LATE_MS))
        assertEquals("still", b.trigger)
        assertEquals(b.responseMs, b.responseLeftMs(holdEnd + LoneWorkerLogic.LATE_MS))
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
