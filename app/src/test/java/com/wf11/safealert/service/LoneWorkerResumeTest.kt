package com.wf11.safealert.service

import android.content.SharedPreferences
import com.wf11.safealert.service.LoneWorkerLogic.Mode
import com.wf11.safealert.service.LoneWorkerLogic.Rest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Resume after a service restart. Times are saved as elapsed time with the boot count, the saved
 * elapsed time and the saved wall clock. On the same boot the elapsed values are used as they are
 * (a wall clock change does not move them); on another boot (or an unknown boot count) they move by
 * the wall clock time that passed (never negative). The safe zone state (settled, entry time) is
 * kept and held for ZONE_RESUME_HOLD_MS until a zone report arrives. The logic starts its power
 * debounce on the saved charging value and feeds the current raw value at the restart time, so a
 * difference applies as a real plug/unplug at the restart time only after 2 s of stable power. Until
 * the debounce reports, at most RestartHold.POWER_HOLD_MS (extended once while a wait that started in
 * that window is pending), no check opens and a restored check is held; the held check follows the
 * open check's close rules (plug, distinct motion, settling, turning off, a restored own SOS), an
 * unplug opens it with the full response time. Only the first change reported during the hold is a
 * restart change: neither a docking motion nor a cradle unplug. A restored zone settles only on an
 * inside report. A stored own SOS wins after.
 */
class LoneWorkerResumeTest : RestartKit() {

    /** Carried, entered the zone at 0 and settled at 61 s, saved at 100 s. */
    private fun settledInZone(): LoneWorkerLogic = newLogic(zoneInside = true, carried = true).apply {
        assertEquals(Mode.WATCHING, seenAt(61_000))
        assertTrue(zoneSettled)
        assertEquals(Mode.WATCHING, seenAt(100_000))
    }

    @Test fun resume_reopens_accident_check_with_full_response() {
        val old = accidentCheck()
        assertEquals(50_000L, old.responseLeftMs(41_000))
        val l = reboot(old, 41_000, 5_000, 20_000)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("fall", l.trigger)
        assertEquals(LoneWorkerLogic.ACCIDENT_RESPONSE_MS, l.responseLeftMs(5_000))
    }

    @Test fun resume_reopens_still_check_with_full_response() {
        val old = newLogic(carried = true)
        assertEquals(Mode.CHECKING, old.seenAt(180_000))
        assertEquals("still", old.trigger)
        val l = reboot(old, 200_000, 5_000, 20_000)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("still", l.trigger)
        assertEquals(l.responseMs, l.responseLeftMs(5_000))
    }

    @Test fun resume_drops_suspicion_older_than_5min() {
        val old = newLogic()
        old.onAccident(1_000)
        // saved 9 s after the trigger, restarted 300 s of wall time later: 309 s after the trigger
        val l = reboot(old, 10_000, 5_000, 300_000)
        assertNull(l.snapshot(5_000).accidentUntil)
        assertEquals(Mode.WATCHING, l.seenAt(65_000))
    }

    @Test fun resume_keeps_suspicion_within_5min() {
        val old = newLogic()
        old.onAccident(1_000)
        // restarted 119 s after the trigger: the 30 s deadline has passed, sensor data after it is gone
        val l = reboot(old, 10_000, 5_000, 110_000)
        assertNotNull(l.snapshot(5_000).accidentUntil)
        assertEquals(Mode.CHECKING, l.modeAt(5_000 + LoneWorkerLogic.LATE_MS))
        assertEquals("fall", l.trigger)
    }

    @Test fun resume_keeps_carried_latch_while_charging() {
        val old = newLogic(charging = true)
        assertEquals(Rest.DOCKED, old.rest)
        old.walk(10_000, 10)
        assertEquals(Rest.NONE, old.rest)
        val l = reboot(old, 12_000, 5_000, 20_000, charging = true)
        assertEquals(Rest.NONE, l.rest)
    }

    @Test fun resume_keeps_wait_and_still_base() {
        val waiting = reboot(newLogic(), 10_000, 5_000, 20_000)
        assertEquals(Rest.WAIT, waiting.rest)

        val old = newLogic(carried = true)
        old.onMoved(50_000)
        val l = reboot(old, 100_000, 5_000, 20_000)
        assertEquals(Rest.NONE, l.rest)
        // still since wall0 - 50 s, restarted at wall0 + 20 s: 70 s already still, the check opens 110 s after restart
        val due = l.snapshot(5_000).stillBase + l.stillMs
        assertEquals(115_000L, due)
        assertEquals(Mode.WATCHING, l.modeAt(due - 1))
        assertEquals(Mode.CHECKING, l.seenAt(due))
        assertEquals("still", l.trigger)
    }

    @Test fun power_change_during_downtime_applies_as_real_change() {
        val old = newLogic(carried = true)
        old.onAccident(1_000)
        assertNotNull(old.snapshot(10_000).accidentUntil)
        val l = reboot(old, 10_000, 5_000, 20_000, charging = true)
        assertEquals(Rest.DOCKED, l.rest)
        assertNull(l.snapshot(5_000).accidentUntil)
        assertEquals(Mode.WATCHING, l.seenAt(65_000))
    }

    @Test fun sos_is_not_saved_as_check() {
        val l = newLogic(carried = true)
        l.restoreSos("fall", 1_000)
        assertEquals(Mode.SOS, l.mode)
        assertEquals("", l.snapshot(1_000).check)
    }

    @Test fun decode_rejects_garbage() {
        val ok = LoneWorkerResume.encode(accidentCheck().snapshot(40_000), 40_000, wall0, boot)
        assertNotNull(LoneWorkerResume.decode(ok, 40_000, wall0, boot))
        assertEquals(accidentCheck().snapshot(40_000), LoneWorkerResume.decode(ok, 40_000, wall0, boot))
        for (bad in listOf(null, "", "v1|1|2", "v3" + ok.drop(2), ok.replace("|fall|", "|x|"),
            ok.replaceFirst("|", "|abc"), ok.dropLast(1) + "x", ok + "|1")) {
            assertNull(bad, LoneWorkerResume.decode(bad, 40_000, wall0, boot))
        }
    }

    @Test fun clear_on_user_stop_removes_resume_and_running_keys() {
        val removed = ArrayList<String>()
        val editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?): SharedPreferences.Editor { removed.add(key!!); return this }
            override fun clear() = this
            override fun commit() = true
            override fun apply() {}
        }
        val back = LoneWorkerResume.clearOnUserStop(editor)
        assertEquals(editor, back)
        assertEquals(setOf("running_mode", "running_since", "running_category", LoneWorkerResume.KEY), removed.toSet())
    }

    // -- safe zone state (C3) --

    @Test fun restart_in_settled_zone_keeps_zone_until_zone_report() {
        val l = reboot(settledInZone(), 100_000, 5_000, 20_000).apply { stillMs = 60_000 }
        assertTrue(l.zoneSettled)
        assertEquals(Mode.WATCHING, l.seenAt(5_000))
        assertEquals(Mode.WATCHING, l.seenAt(7_000))
        l.onZone(true, 8_000)
        for (t in 8_000L..205_000L step 1_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
        assertTrue(l.zoneSettled)
    }

    @Test fun restored_zone_without_report_leaves_after_hold() {
        val l = reboot(settledInZone(), 100_000, 5_000, 20_000)
        for (t in 5_000L..14_000L step 1_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
        assertTrue(l.zoneSettled)
        // no zone report within ZONE_RESUME_HOLD_MS: left the zone at restart + 10 s, counting starts there
        assertEquals(Mode.WATCHING, l.seenAt(15_000))
        assertFalse(l.zoneSettled)
        for (t in 16_000L..194_000L step 1_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
        assertEquals(Mode.CHECKING, l.seenAt(195_000))
        assertEquals("still", l.trigger)
    }

    @Test fun restored_unsettled_zone_settles_on_saved_entry_time() {
        val old = newLogic(carried = true)
        old.onZone(true, 10_000)
        assertEquals(Mode.WATCHING, old.seenAt(40_000))
        assertFalse(old.zoneSettled)
        // entered 30 s before the save, restarted 20 s of wall time later: settles 10 s after the restart
        val l = reboot(old, 40_000, 5_000, 20_000)
        assertEquals(Mode.WATCHING, l.seenAt(7_000))
        l.onZone(true, 7_000)
        assertEquals(Mode.WATCHING, l.seenAt(14_000))
        assertFalse(l.zoneSettled)
        assertEquals(Mode.WATCHING, l.seenAt(15_000))
        assertTrue(l.zoneSettled)
        for (t in 16_000L..400_000L step 1_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    @Test fun restored_zone_hold_keeps_open_check_without_settling() {
        val old = newLogic(carried = true)
        old.onZone(true, 100_000)
        assertEquals(Mode.WATCHING, old.seenAt(150_000))
        assertFalse(old.zoneSettled)
        // same boot, 50 s later: the still deadline (180 s) passed more than LATE_MS ago, no zone report yet
        val l = reboot(old, 150_000, 200_000, 50_000, bootNow = boot)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("still", l.trigger)
        assertFalse(l.zoneSettled)
        assertEquals(Mode.CHECKING, l.seenAt(211_000))
        assertEquals("still", l.trigger)
        assertFalse(l.zoneSettled)
    }

    @Test fun restored_zone_hold_expiry_keeps_still_count() {
        val old = newLogic(carried = true)
        old.onMoved(100_000)
        old.onZone(true, 100_000)
        assertEquals(Mode.WATCHING, old.seenAt(150_000))
        assertFalse(old.zoneSettled)
        // no zone report: left at the hold end without settling, still counted from 100 s
        val l = reboot(old, 150_000, 200_000, 50_000, bootNow = boot)
        assertEquals(Mode.WATCHING, l.seenAt(211_000))
        assertFalse(l.zoneSettled)
        assertEquals(Mode.WATCHING, l.seenAt(279_000))
        assertEquals(Mode.CHECKING, l.seenAt(280_000))
        assertEquals("still", l.trigger)
    }

    @Test fun restored_zone_hold_outside_report_keeps_open_check() {
        val old = newLogic(carried = true)
        old.onZone(true, 100_000)
        assertEquals(Mode.WATCHING, old.seenAt(150_000))
        val l = reboot(old, 150_000, 200_000, 50_000, bootNow = boot)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("still", l.trigger)
        // an outside report during the zone hold leaves the zone and keeps the open check and its deadline
        l.onZone(false, 205_000)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("still", l.trigger)
        assertFalse(l.zoneSettled)
        assertEquals(l.responseMs - 5_000, l.responseLeftMs(205_000))
    }

    // -- clock changes --

    @Test fun same_boot_ignores_wall_clock_jump_back() {
        val old = newLogic(carried = true)
        old.onMoved(50_000)
        // restarted 10 s later on the same boot, the wall clock was set back 1 h
        val l = reboot(old, 100_000, 110_000, 10_000 - 3_600_000, bootNow = boot)
        assertEquals(Mode.WATCHING, l.seenAt(229_000))
        assertEquals(Mode.CHECKING, l.seenAt(230_000))
        assertEquals("still", l.trigger)
    }

    @Test fun same_boot_ignores_wall_clock_jump_forward() {
        val old = newLogic(carried = true)
        old.onMoved(95_000)
        old.onAccident(90_000)
        // restarted 10 s later on the same boot, the wall clock was set forward 1 day
        val l = reboot(old, 100_000, 110_000, 10_000 + 86_400_000, bootNow = boot)
        assertEquals(390_000L, l.snapshot(110_000).accidentUntil)
        assertEquals(Mode.WATCHING, l.seenAt(110_000))
        assertEquals(Mode.CHECKING, l.seenAt(120_000))
        assertEquals("fall", l.trigger)
    }

    @Test fun other_boot_clamps_negative_wall_gap() {
        val old = newLogic(carried = true)
        old.onMoved(50_000)
        // another boot and the wall clock went back: no time passed, 50 s were still, 130 s are left
        val l = reboot(old, 100_000, 5_000, -60_000)
        assertTrue(l.snapshot(5_000).stillBase <= 5_000)
        assertEquals(Mode.WATCHING, l.seenAt(134_000))
        assertEquals(Mode.CHECKING, l.seenAt(135_000))
    }

    @Test fun unknown_boot_uses_wall_clock() {
        val old = newLogic(carried = true)
        old.onMoved(50_000)
        // boot count unreadable on both sides: 20 s of wall time count although elapsed says 10 s
        val l = reboot(old, 100_000, 110_000, 20_000, bootNow = -1, bootSaved = -1)
        assertEquals(Mode.WATCHING, l.seenAt(219_000))
        assertEquals(Mode.CHECKING, l.seenAt(220_000))
    }

    // -- power at the restart --

    @Test fun restart_power_bounce_keeps_check() {
        // cradle contact bounced back at the restart: the debounce drops the change, the hold still runs to the window end, then the check opens
        val end = 5_000 + RestartHold.POWER_HOLD_MS
        val l = reboot(accidentCheck(), 41_000, 5_000, 20_000, charging = true, flips = listOf(5_500))
        assertEquals(listOf(Mode.WATCHING, Mode.WATCHING), beforePower)
        assertEquals(Mode.WATCHING, l.mode)
        assertEquals(end, l.nextCheckAt(5_000 + PowerDebounce.CONFIRM_MS))
        assertEquals(Mode.WATCHING, l.modeAt(end - 1))
        assertEquals(Mode.CHECKING, l.seenAt(end))
        assertEquals("fall", l.trigger)
        assertEquals(Rest.NONE, l.rest)
        assertEquals(LoneWorkerLogic.ACCIDENT_RESPONSE_MS, l.responseLeftMs(end))
    }

    @Test fun restart_plug_rebounce_ignores_gap_ticks() {
        // the contact came back, then the new power again: a tick in the gap changes nothing, the plug drops the held check
        val end = 5_000 + RestartHold.POWER_HOLD_MS
        for (gapTick in listOf(false, true)) {
            val m = "gapTick=$gapTick"
            val l = restart(accidentCheck(), 41_000, 5_000, 20_000, charging = true)
            l.rebounce(now = true, gapTick = gapTick, m = m)
            assertEquals(m, 5_800 + PowerDebounce.CONFIRM_MS, l.nextCheckAt(5_900))
            assertEquals(m, Mode.WATCHING, l.modeAt(5_800 + PowerDebounce.DEBOUNCE_MS - 1))
            assertEquals(m, Mode.WATCHING, l.modeAt(5_800 + PowerDebounce.DEBOUNCE_MS))
            assertEquals(m, Rest.DOCKED, l.rest)
            assertNull(m, l.snapshot(5_800 + PowerDebounce.DEBOUNCE_MS).accidentHold)
            assertEquals(m, Mode.WATCHING, l.modeAt(end))
            // the restart plug is not a docking motion
            l.onAccident(9_000)
            assertEquals(m, Mode.WATCHING, l.seenAt(38_999))
            assertEquals(m, Mode.CHECKING, l.seenAt(39_000))
            assertEquals(m, "fall", l.trigger)
        }
    }

    @Test fun restart_power_change_applies_after_debounce() {
        val l = reboot(accidentCheck(), 41_000, 5_000, 20_000, charging = true)
        // the restored accident check is not shown before the plug is confirmed, then the plug drops it
        assertEquals(listOf(Mode.WATCHING, Mode.WATCHING), beforePower)
        assertEquals(Mode.WATCHING, l.mode)
        assertEquals(Rest.DOCKED, l.rest)
        assertNull(l.snapshot(5_000 + PowerDebounce.CONFIRM_MS).accidentHold)
    }

    @Test fun restart_on_dock_after_dead_battery_opens_no_check() {
        // still since 0, restarted 10 min later on the dock: the still deadline has long passed
        val l = reboot(newLogic(carried = true), 100_000, 5_000, 600_000, charging = true)
        assertEquals(listOf(Mode.WATCHING, Mode.WATCHING), beforePower)
        assertEquals(Mode.WATCHING, l.mode)
        assertEquals(Rest.DOCKED, l.rest)
        assertEquals("", l.snapshot(5_000 + PowerDebounce.CONFIRM_MS).check)
    }

    @Test fun restart_after_power_bank_unplug_waits_without_check() {
        val old = carriedWhileCharging()
        assertEquals(Rest.NONE, old.rest)
        val l = reboot(old, 12_000, 5_000, 600_000)
        assertEquals(listOf(Mode.WATCHING, Mode.WATCHING), beforePower)
        assertEquals(Mode.WATCHING, l.mode)
        assertEquals(Rest.WAIT, l.rest)
        assertEquals(Mode.WATCHING, l.seenAt(300_000))
    }

    @Test fun restored_check_waits_for_power_then_opens_with_full_response() {
        val old = carriedWhileCharging()
        assertEquals(Mode.CHECKING, old.seenAt(190_000))
        assertEquals("still", old.trigger)
        val l = reboot(old, 200_000, 5_000, 20_000)
        assertEquals(listOf(Mode.WATCHING, Mode.WATCHING), beforePower)
        // the unplug keeps the check (E9): it opens once the power is confirmed, the response counts from there
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("still", l.trigger)
        assertEquals(Rest.WAIT, l.rest)
        assertEquals(l.responseMs, l.responseLeftMs(5_000 + PowerDebounce.CONFIRM_MS))
    }

    @Test fun restart_unplug_is_not_a_cradle_fall() {
        // docked in the zone, restarted unplugged: the unplug applied at the restart does not hide a fall
        val l = reboot(newLogic(charging = true, zoneInside = true), 20_000, 5_000, 20_000)
        l.onAccident(9_000)
        assertEquals(Mode.WATCHING, l.seenAt(38_999))
        assertEquals(Mode.CHECKING, l.seenAt(39_000))
        assertEquals("fall", l.trigger)
    }

    // -- format and saving --

    @Test fun v1_record_is_dropped() {
        val v1 = "v1|999990000|999999000|999995000|fall|999991000|0|1|999980000"
        assertNull(LoneWorkerResume.decode(v1, 5_000, wall0, boot))
    }

    @Test fun malformed_field_is_dropped() {
        val f = LoneWorkerResume.encode(settledInZone().snapshot(100_000), 100_000, wall0, boot).split("|")
        for (i in 1 until f.size) {
            val bad = f.toMutableList().also { it[i] = "x" + it[i] }.joinToString("|")
            assertNull(bad, LoneWorkerResume.decode(bad, 100_000, wall0, boot))
        }
    }

    @Test fun save_skips_still_only_change_unless_carried_outside_settled_zone() {
        val carried = LoneWorkerResume.State(null, null, "", false, true, 0L, false, null)
        val waiting = carried.copy(carried = false)
        val docked = waiting.copy(charging = true)
        val settled = carried.copy(zoneSettled = true, zoneSince = 0L)
        assertTrue(LoneWorkerResume.shouldSave(null, carried))
        assertTrue(LoneWorkerResume.shouldSave(carried, carried.copy(stillBase = 10_000)))
        assertFalse(LoneWorkerResume.shouldSave(carried, carried.copy(stillBase = 9_000)))
        assertFalse(LoneWorkerResume.shouldSave(waiting, waiting.copy(stillBase = 60_000)))
        assertFalse(LoneWorkerResume.shouldSave(docked, docked.copy(stillBase = 60_000)))
        assertFalse(LoneWorkerResume.shouldSave(settled, settled.copy(stillBase = 60_000)))
        assertTrue(LoneWorkerResume.shouldSave(carried, carried.copy(check = "still")))
        assertTrue(LoneWorkerResume.shouldSave(settled, settled.copy(zoneSettled = false, stillBase = 1_000)))
    }

    // -- peer siren pause (C2) --

    @Test fun snapshot_during_siren_excludes_paused_time() {
        val old = newLogic(carried = true)
        old.peerSiren(60_000L, 230_000L)
        assertTrue(old.alarmVibrates)
        // 60 s were still before the siren; restarted without it, 120 s are left
        val l = reboot(old, 230_000, 5_000, 0)
        for (t in 5_000L..124_000L step 1_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
        assertEquals(Mode.CHECKING, l.seenAt(125_000))
    }

    /**
     * F1: a siren that goes on after a restart starts a new pause with a new cap. Restart base -55 s (60 s
     * still), pause at 8 s (63 s still), cap at 188 s moves the base to 125 s, deadline 305 s.
     */
    @Test fun siren_after_restart_starts_a_new_pause() {
        val old = newLogic(carried = true)
        old.peerSiren(60_000L, 230_000L)
        val l = reboot(old, 230_000, 5_000, 0)
        l.peerSiren(8_000L, 304_000L)
        assertEquals(Mode.CHECKING, l.seenAt(305_000))
        assertEquals("still", l.trigger)
    }
}
