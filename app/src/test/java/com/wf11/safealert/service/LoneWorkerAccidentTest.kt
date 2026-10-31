package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule 1 (accident): a fall signal alone (no prior-motion condition, no impact-only trigger) opens a
 * 5 min suspicion; 30 s without distinct motion inside it opens a 1 min accident check that becomes
 * SOS. A fall while charging inside the zone (raw inside, right after entering) is ignored. A real
 * plug up to 10 s before the trigger ignores it; a real plug during the suspicion ends it. Distinct
 * motion = 5 walking-shaped steps within 10 s, or (no step sensor) 3 s of walking-shaped windows.
 * Deadlines are judged once sensor data covers them, or LATE_MS after them.
 * Calls in each test run in arrival order.
 */
class LoneWorkerAccidentTest {

    private val late = LoneWorkerLogic.LATE_MS

    /**
     * A fall alone opens the suspicion; 30 s without distinct motion after it opens the accident check, which left
     * unanswered for 1 min becomes SOS (the setting responseMs is not used for a fall check).
     */
    @Test fun accident_check_unanswered_for_1min_becomes_sos() {
        val l = fallRuleOnly()
        l.responseMs = 600_000L
        l.onAccident(6_000)
        // the screen count (read only): 30 s from the impact, one buzz key per accepted impact
        assertEquals(LoneWorkerLogic.Countdown(LoneWorkerLogic.CountdownKind.FALL, 30_000L, 6_000L), l.countdown(6_000))
        assertEquals(Mode.WATCHING, l.modeAt(18_000))
        assertEquals(18_000L, l.countdown(18_000)!!.leftMs)
        assertEquals(Mode.WATCHING, l.seenAt(35_999))
        assertEquals(Mode.CHECKING, l.seenAt(36_000))
        assertEquals("fall", l.trigger)
        assertEquals(60_000L, l.responseLeftMs(36_000))
        assertEquals(LoneWorkerLogic.Countdown(LoneWorkerLogic.CountdownKind.RESPONSE, 60_000L, null), l.countdown(36_000))
        assertEquals(Mode.CHECKING, l.seenAt(95_999))
        assertEquals(1L, l.countdown(95_999)!!.leftMs)
        assertEquals(Mode.SOS, l.seenAt(96_000))
        assertEquals("fall", l.trigger)
        assertEquals(LoneWorkerLogic.Countdown(LoneWorkerLogic.CountdownKind.SOS, 0L, null), l.countdown(96_000))

        // a second passed fall inside the same suspicion is a new count: a new buzz key; a not-a-fall impact in the same
        // watch keeps the key (one buzz per watch)
        val twice = fallRuleOnly()
        twice.onAccident(6_000)
        twice.onAccident(10_000)
        assertEquals(10_000L, twice.countdown(10_000)!!.buzzKey)
        twice.onAccident(14_000, pass = false)
        assertEquals(10_000L, twice.countdown(14_000)!!.buzzKey)
    }

    /**
     * A fall is ignored when the phone was docked at the impact: charging inside the zone (also right after entering
     * it), a real plug within 10 s before the impact anywhere, or a real unplug within 10 s before the impact inside
     * the zone (it fell off the cradle). A fall before an unplug:
     * LoneWorkerOrderTest.fall_before_an_unplug_is_ignored_whenever_it_is_processed.
     * Rows: the fall, the modes seen at the listed times, the trigger when checked, then the power and zone at the
     * start, an optional zone entry, an optional power report and the fall shape.
     */
    @Test fun fall_is_ignored_when_docked_at_the_impact() {
        class Row(val name: String, val fallAt: Long, val seen: List<Pair<Long, Mode>>, val trigger: String? = null,
                  val charging: Boolean = false, val zoneInside: Boolean = false, val zoneEnteredAt: Long? = null,
                  val power: Pair<Boolean, Long>? = null, val zoneExitAt: Long? = null,
                  val shape: MotionAnalyzer.FallShape = MotionAnalyzer.FallShape.ANY)
        fun watching(from: Long, to: Long) = (from..to step 10_000L).map { it to Mode.WATCHING }
        for (r in listOf(
            Row("charging, zone entered 1 s before", 6_000, watching(40_000, 400_000),
                charging = true, zoneEnteredAt = 5_000),
            // a full-shape fall (long drop, hard impact, big tilt) is ignored the same way
            Row("charging, zone entered 1 s before, full-shape fall", 6_000, watching(40_000, 400_000),
                charging = true, zoneEnteredAt = 5_000, shape = MotionAnalyzer.FallShape(400, 5.0, 90.0)),
            // the zone exit confirmed within 12 s of the impact uses the outside rule, so the fall counts; later it does not
            Row("charging, zone entered 1 s before, exit 2 s after the impact", 6_000, listOf(36_000L to Mode.CHECKING), "fall",
                charging = true, zoneEnteredAt = 5_000, zoneExitAt = 8_000, shape = MotionAnalyzer.FallShape(400, 5.0, 90.0)),
            Row("charging, zone entered 1 s before, exit 15 s after the impact", 6_000, watching(40_000, 400_000),
                charging = true, zoneEnteredAt = 5_000, zoneExitAt = 21_000, shape = MotionAnalyzer.FallShape(400, 5.0, 90.0)),
            Row("charging outside the zone", 20_000, listOf(50_000L to Mode.CHECKING), charging = true),
            Row("plug 10 s before", 20_000, watching(50_000, 330_000), power = true to 10_000L),
            Row("plug 10.001 s before", 20_000, listOf(50_000L to Mode.CHECKING), power = true to 9_999L),
            Row("unplug 0.4 s before, inside the zone", 100_400, watching(130_400, 430_400),
                charging = true, zoneInside = true, power = false to 100_000L),
            Row("unplug exactly UNPLUG_FALL_MS before, inside the zone", 100_000 + LoneWorkerLogic.UNPLUG_FALL_MS,
                watching(140_000, 440_000), charging = true, zoneInside = true, power = false to 100_000L),
            Row("unplug 11 s before, inside the zone", 111_000,
                listOf(140_999L to Mode.WATCHING, 141_000L to Mode.CHECKING), "fall",
                charging = true, zoneInside = true, power = false to 100_000L),
            Row("unplug 0.4 s before, outside the zone", 100_400, listOf(130_400L to Mode.CHECKING), "fall",
                charging = true, power = false to 100_000L))) {
            val l = fallRuleOnly(charging = r.charging, zoneInside = r.zoneInside)
            r.zoneEnteredAt?.let { l.onZone(true, it) }
            r.zoneExitAt?.let { l.onZone(false, it) }
            r.power?.let { (on, at) -> l.reportPower(on, at) }
            val preview = l.previewFall(r.fallAt, r.shape)
            l.onAccident(r.fallAt, r.shape)
            for ((t, want) in r.seen) assertEquals("${r.name}: seen at $t", want, l.seenAt(t))
            assertEquals("${r.name}: preview", r.seen.any { it.second == Mode.CHECKING }, preview)
            r.trigger?.let { assertEquals("${r.name}: trigger", it, l.trigger) }
        }
    }

    @Test fun plug_during_suspicion_ends_it_without_check() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.WATCHING, l.seenAt(29_000))
        l.reportPower(true, 30_000)
        for (t in 40_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
        // also after steps closed the accident check (suspicion still running)
        val s = fallRuleOnly()
        s.onAccident(10_000)
        assertEquals(Mode.CHECKING, s.seenAt(40_000))
        s.walk(45_000, 5)
        assertEquals(Mode.WATCHING, s.mode)
        s.reportPower(true, 50_000)
        for (t in 60_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, s.seenAt(t))
        // a real plug with the accident check still open closes it and ends the suspicion
        val c = fallRuleOnly()
        c.onAccident(10_000)
        assertEquals(Mode.CHECKING, c.seenAt(40_000))
        c.reportPower(true, 50_000)
        assertEquals(Mode.WATCHING, c.mode)
        for (t in 60_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, c.seenAt(t))
    }

    // the unplug during an accident check: LoneWorkerChargeTest.unplug_during_still_check_keeps_it_running_to_sos

    @Test fun power_changes_during_sos_do_not_end_sos() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        assertEquals(Mode.SOS, l.seenAt(100_000))
        l.reportPower(true, 110_000)
        assertEquals(Mode.SOS, l.mode)
        assertEquals(Mode.SOS, l.seenAt(120_000))
        l.reportPower(false, 130_000)
        assertEquals(Mode.SOS, l.mode)
        for (t in 140_000L..600_000L step 20_000L) assertEquals(Mode.SOS, l.seenAt(t))
        assertEquals("fall", l.trigger)
    }

    /**
     * Distinct motion after the fall holds the accident check: 5 walking-shaped steps within 10 s taken while the app
     * did not vibrate, or without a step sensor a 3 s run of walking-shaped windows that start after the fall.
     * Rows: fall time, step sensor, the motion, then the modes seen at the listed times.
     */
    @Test fun only_distinct_motion_after_the_fall_delays_the_accident_check() {
        class Row(val name: String, val fallAt: Long, val stepSensor: Boolean, val motion: LoneWorkerLogic.() -> Unit,
                  val seen: List<Pair<Long, Mode>>)
        val open = listOf(40_000L to Mode.CHECKING)
        for (r in listOf(
            Row("5 non-walking steps", 10_000, true, { shuffle(38_000, 5) }, open),
            Row("5 steps while the app vibrates", 10_000, true,
                { for (i in 4 downTo 0) step(38_000 - i * 500L, vibrating = true) }, open),
            Row("4 steps", 10_000, true, { walk(30_000, 4) }, open),
            Row("5 steps over 10.004 s", 10_000, true, { for (i in 0 until 5) step(12_000 + i * 2_501L) }, open),
            Row("5 steps over exactly 10 s", 10_000, true, { for (i in 0 until 5) step(12_000 + i * 2_500L) },
                listOf(51_999L to Mode.WATCHING, 52_000L to Mode.CHECKING)),
            Row("step sensor present, 5 walking windows alone", 10_000, true, { strongRun(20_000, 5) }, open),
            Row("no step sensor, 3 s walking run", 10_000, false, { strongRun(36_000, 3) },
                listOf(67_999L to Mode.WATCHING, 68_000L to Mode.CHECKING)),
            Row("no step sensor, 2 s walking run", 10_000, false, { strongRun(30_000, 2) }, open),
            // windows 10-11 s, 11-12 s, 12-13 s: only two start after the fall
            Row("no step sensor, run starting with the window that holds the fall", 10_500, false,
                { strongRun(11_000, 3) }, listOf(40_500L to Mode.CHECKING)))) {
            val l = fallRuleOnly()
            if (!r.stepSensor) l.stepsAvailable = false
            l.onAccident(r.fallAt)
            val key = l.countdown(r.fallAt)!!.buzzKey
            r.motion(l)
            for ((t, want) in r.seen) {
                assertEquals("${r.name}: seen at $t", want, l.seenAt(t))
                // a count restarted by motion is still the same impact: no second buzz
                if (want == Mode.WATCHING) assertEquals("${r.name}: count at $t", key, l.countdown(t)?.buzzKey)
            }
        }
    }

    @Test fun late_step_before_sos_deadline_closes_check() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        // deadline 100 s passes before the batch carrying steps up to 99.9 s arrives
        assertEquals(Mode.CHECKING, l.modeAt(100_000))
        assertEquals(Mode.CHECKING, l.modeAt(102_000))
        l.walk(99_900, 5)
        assertEquals(Mode.WATCHING, l.mode)
        assertEquals(Mode.WATCHING, l.modeAt(103_000))
    }

    /**
     * The accident check's opening (40 s) and its SOS (100 s) wait for the sensor data that covers them, at most LATE_MS;
     * data that covers the deadline judges it at once, but only when the accelerometer and the step sensor both arrived.
     */
    @Test fun sos_waits_for_sensor_data_up_to_6s() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        assertEquals(Mode.CHECKING, l.modeAt(100_000))
        assertEquals(Mode.CHECKING, l.modeAt(100_000 + late - 1))
        assertEquals(Mode.SOS, l.modeAt(100_000 + late))

        // the opening waits the same way
        val open = fallRuleOnly()
        open.onAccident(10_000)
        assertEquals(Mode.WATCHING, open.modeAt(40_000))
        assertEquals(Mode.WATCHING, open.modeAt(40_000 + late - 1))
        assertEquals(Mode.CHECKING, open.modeAt(40_000 + late))
        assertEquals(60_000L, open.responseLeftMs(40_000 + late))

        val seen = fallRuleOnly()
        seen.onAccident(10_000)
        assertEquals(Mode.CHECKING, seen.seenAt(40_000))

        // data that covers the SOS deadline fires it at the deadline
        val data = fallRuleOnly()
        data.onAccident(10_000)
        assertEquals(Mode.CHECKING, data.seenAt(40_000))
        assertEquals(Mode.CHECKING, data.seenAt(99_999))
        // accelerometer covers the deadline but the step sensor has not been flushed yet
        data.onWindow(MotionAnalyzer.Window(100_000, false))
        assertEquals(Mode.CHECKING, data.modeAt(100_000))
        data.stepsFlushed(100_000)
        assertEquals(Mode.SOS, data.modeAt(100_000))
    }

    @Test fun fall_check_replacing_a_still_check_counts_steps_from_the_switch() {
        val l = fallRuleOnly()
        l.stillMs = 180_000L
        assertEquals(Mode.CHECKING, l.seenAt(180_000))
        assertEquals("still", l.trigger)
        l.onAccident(175_000)
        l.walk(202_500, 4)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals(Mode.CHECKING, l.seenAt(205_000))
        assertEquals("fall", l.trigger)
        // four steps before the switch plus one after it are not five steps after the new floor
        l.step(206_000)
        assertEquals(Mode.CHECKING, l.modeAt(207_000))
        assertEquals("fall", l.trigger)
    }

    /**
     * Walking every 25 s keeps the check closed until the 5 min suspicion ends; a new fall inside it (at 100 s) moves
     * the end of the suspicion, so the check opens 30 s after the last walk (315 s).
     */
    @Test fun still_period_must_complete_within_5min() {
        for (secondFall in listOf(false, true)) {
            val m = "secondFall=$secondFall"
            val l = fallRuleOnly()
            l.onAccident(10_000)
            for (last in 35_000L..285_000L step 25_000L) {
                l.walk(last, 5)
                assertEquals("$m: walk at $last", Mode.WATCHING, l.modeAt(last))
                if (secondFall && last == 85_000L) l.onAccident(100_000)
            }
            if (secondFall) {
                assertEquals(m, Mode.WATCHING, l.seenAt(314_999))
                assertEquals(m, Mode.CHECKING, l.seenAt(315_000))
            } else {
                assertEquals(m, Mode.WATCHING, l.seenAt(310_000))
                assertEquals(m, Mode.WATCHING, l.seenAt(320_000))
                assertEquals(m, Mode.WATCHING, l.seenAt(400_000))
            }
        }
    }

    @Test fun sporadic_single_steps_never_close_accident_check() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        for (t in 41_000L..98_000L step 3_000L) {
            l.step(t)
            assertEquals(Mode.CHECKING, l.modeAt(t))
        }
        assertEquals(Mode.SOS, l.seenAt(100_000))
    }

    /** [OK] on an accident check, and on a still check with a fall window open, closes it and ends the suspicion. */
    @Test fun ok_closes_accident_check_and_ends_suspicion() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        assertEquals(true, l.ackWorking(45_000))
        for (t in 50_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))

        val s = fallRuleOnly()
        s.stillMs = 180_000L
        assertEquals(Mode.CHECKING, s.seenAt(180_000))
        assertEquals("still", s.trigger)
        s.onAccident(190_000)
        assertEquals(true, s.ackWorking(195_000))
        for (t in 200_000L..370_000L step 5_000L) assertEquals(Mode.WATCHING, s.seenAt(t))
    }

    @Test fun steps_close_accident_check_and_watching_continues_until_5min() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        l.walk(50_000, 5)
        assertEquals(Mode.WATCHING, l.mode)
        assertEquals(Mode.WATCHING, l.seenAt(79_999))
        assertEquals(Mode.CHECKING, l.seenAt(80_000))
        l.walk(285_000, 5)
        assertEquals(Mode.WATCHING, l.mode)
        for (t in 290_000L..400_000L step 10_000L) assertEquals(Mode.WATCHING, l.seenAt(t))
    }

    /**
     * A fall during an open still check: the earlier deadline wins (a fall at 175 s: its check at 205 s replaces the still
     * check, SOS at 265 s; a fall at 215 s cannot beat the still check, whose SOS stays due at 300 s). The pending
     * queries (nextCheckAt, waitingOnSensors, dueNow) show a deadline that cannot beat the still check is not pending.
     */
    @Test fun earlier_deadline_wins_when_accident_fires_during_still_check() {
        val l = fallRuleOnly()
        l.stillMs = 180_000L
        assertEquals(Mode.CHECKING, l.seenAt(180_000))
        assertEquals("still", l.trigger)
        l.onAccident(175_000)
        assertEquals(205_000L, l.nextCheckAt(190_000))
        assertTrue(l.waitingOnSensors(205_000))
        assertFalse(l.dueNow(205_000))
        l.sensed(205_000)
        assertTrue(l.dueNow(205_000))
        assertEquals(Mode.CHECKING, l.modeAt(205_000))
        assertEquals("fall", l.trigger)
        assertFalse(l.dueNow(205_000))
        assertEquals(Mode.CHECKING, l.seenAt(205_000))
        assertEquals("fall", l.trigger)
        assertEquals(Mode.CHECKING, l.seenAt(264_999))
        assertEquals(Mode.SOS, l.seenAt(265_000))

        val later = fallRuleOnly()
        later.stillMs = 180_000L
        assertEquals(Mode.CHECKING, later.seenAt(180_000))
        later.onAccident(215_000)
        assertEquals(300_000L, later.nextCheckAt(230_000))
        assertFalse(later.waitingOnSensors(245_000))
        later.sensed(245_000)
        assertFalse(later.dueNow(245_000))
        assertEquals(Mode.CHECKING, later.modeAt(245_000))
        assertEquals("still", later.trigger)
        assertEquals(Mode.SOS, later.seenAt(300_000))
        assertEquals("still", later.trigger)
    }

    // An SOS in progress ignores a new fall: LoneWorkerLogicTest.restore_sos_survives_settle_disable_and_ack.
    // Disabled: no fall check opens, an open one is withdrawn: LoneWorkerLogicTest.disabled_never_checks_and_cancels_open_check_but_keeps_sos.

    /**
     * The screen count from the impact moment (a fall candidate): 30 s from the impact, the buzz key equals the confirmed
     * fall's key, it takes only the no-count / last-minute-of-no-motion place, and an impact the judgment would drop
     * (shut, SOS, check open) or one older than 30 s is not shown.
     */
    @Test fun fall_count_from_the_impact() {
        val fall = LoneWorkerLogic.CountdownKind.FALL
        val s = MotionAnalyzer.FallShape(300, 3.0, null)
        val l = fallRuleOnly()
        assertEquals(LoneWorkerLogic.Countdown(fall, 30_000L, 6_000L), l.countdown(6_000, 6_000, s))
        assertEquals(LoneWorkerLogic.Countdown(fall, 18_000L, 6_000L), l.countdown(18_000, 6_000, s))
        assertNull(l.countdown(36_000, 6_000, s))
        assertNull(l.countdown(18_000))
        l.onAccident(6_000, s)
        assertEquals(6_000L, l.countdown(18_000)!!.buzzKey)   // the candidate's key: confirming does not buzz again
        assertEquals(l.countdown(20_000), l.countdown(20_000, 19_000, s))   // the judgment's fall count comes first
        assertEquals(Mode.CHECKING, l.seenAt(36_000))
        assertEquals(LoneWorkerLogic.CountdownKind.RESPONSE, l.countdown(36_000, 35_000, s)!!.kind)
        assertFalse(l.previewFall(36_000, s))
        assertEquals(Mode.SOS, l.seenAt(96_000))
        assertEquals(LoneWorkerLogic.CountdownKind.SOS, l.countdown(96_000, 95_000, s)!!.kind)
        assertFalse(l.previewFall(96_000, s))

        // Not mounted, the count goes on after the 12 s decision until the after-impact judgment ends (30 s from the impact
        // at the latest); walking hides it, an alert continues it as the window's count with the same buzz key; a mount has no such count
        val any = MotionAnalyzer.FallShape.ANY
        val post = fallRuleOnly()
        assertEquals(LoneWorkerLogic.Countdown(fall, 18_000L, 6_000L), post.countdown(18_000, null, any, 6_000, s))
        post.onAccident(6_000, s, byPost = true) // the 12 s decision: a watch owned by the judgment; no result yet, so the safety net (impact + 32 s) times it
        assertEquals(LoneWorkerLogic.Countdown(fall, 14_000L, 6_000L), post.countdown(24_000, null, any, 6_000, s))
        post.onPost(6_000, "walk", 11_000, s) // someone with the phone: no window, nothing shown
        assertNull(post.countdown(24_000))
        assertNull(post.countdown(36_000, null, any, 6_000, s))
        post.onPost(6_000, "post10", 16_000, s)
        assertEquals(LoneWorkerLogic.Countdown(fall, 6_000L, 6_000L), post.countdown(10_000))
        val mount = newLogic(charging = true).apply { setEquipment(true, 0) }
        assertNull(mount.countdown(24_000, null, any, 6_000, s))

        val still = newLogic(carried = true)
        assertEquals(LoneWorkerLogic.CountdownKind.STILL, still.countdown(130_000)!!.kind)
        assertEquals(LoneWorkerLogic.Countdown(fall, 25_000L, 125_000L), still.countdown(130_000, 125_000, s))

        val offEv = mutableListOf<LoneWorkerLogic.JudgeEvent>()
        val off = fallRuleOnly().apply { setEnabled(false, 1_000); onJudge = { offEv += it } }
        assertFalse(off.previewFall(6_000, s))
        assertNull(off.countdown(6_000, 6_000, s))
        off.onAccident(6_000)
        val offTake = offEv.single() as LoneWorkerLogic.JudgeEvent.Fall
        assertEquals("shut", offTake.take); assertTrue(offTake.asks)
    }

    /** A second fall while a fall check is open neither reopens nor moves that check: SOS stays due 60 s after it opened. */
    @Test fun second_fall_during_open_fall_check_does_not_move_it() {
        val l = fallRuleOnly()
        l.onAccident(10_000)
        assertEquals(Mode.CHECKING, l.seenAt(40_000))
        l.onAccident(45_000)
        assertEquals(Mode.CHECKING, l.seenAt(50_000))
        assertEquals(50_000L, l.responseLeftMs(50_000))
        assertEquals(Mode.SOS, l.seenAt(100_000))
    }

    /**
     * An impact the fall rule calls not-a-fall (pass = false) runs the same watch, exclusions and timings as a fall; only the
     * window texts differ ("충격 감지" / "폰을 떨어뜨렸나요?"), and a passed fall in the same watch brings the fall texts.
     */
    @Test fun not_a_fall_impact_asks_drop_then_sos() {
        val fall = LoneWorkerLogic.CountdownKind.FALL
        val l = fallRuleOnly()
        l.onAccident(1_000, pass = false)
        assertEquals(LoneWorkerLogic.Countdown(fall, 30_000L, 1_000L, true), l.countdown(1_000))
        assertEquals("충격 감지 — 30초 동안 움직임이 없으면 '폰을 떨어뜨렸나요?' 창", countdownBanner(l.countdown(1_000)!!))
        assertFalse(l.dropWindow)
        assertEquals(Mode.CHECKING, l.seenAt(31_000))
        assertEquals("fall", l.trigger)
        assertTrue(l.dropWindow)
        val resp = l.countdown(31_000)!!
        assertEquals(LoneWorkerLogic.Countdown(LoneWorkerLogic.CountdownKind.RESPONSE, 60_000L, null, true), resp)
        assertEquals("'폰을 떨어뜨렸나요?' 응답 대기 — 60초 뒤 구조 요청", countdownBanner(resp))
        assertEquals(Mode.SOS, l.seenAt(91_000))
        assertEquals("fall", l.trigger)

        // walking after the impact keeps watching: no window
        val w = fallRuleOnly()
        w.onAccident(1_000, pass = false)
        w.walk(20_000, 20)
        assertEquals(Mode.WATCHING, w.seenAt(31_000))

        // safe zone + charging: dropped, with the rule result kept in the record, no window
        val ev = mutableListOf<LoneWorkerLogic.JudgeEvent>()
        val z = fallRuleOnly(charging = true, zoneInside = true).apply { onJudge = { ev += it } }
        z.onAccident(1_000, pass = false)
        val took = ev.single() as LoneWorkerLogic.JudgeEvent.Fall
        assertEquals("dropped", took.take); assertFalse(took.pass); assertTrue(took.asks)
        assertEquals(Mode.WATCHING, z.seenAt(31_000))

        // a deferred impact (power change pending) keeps its rule result
        ev.clear()
        val d = newLogic(carried = true).apply { stillMs = 180_000L; onJudge = { ev += it } }
        d.powerRaw(true, 179_500)   // a still deadline at 180 s waits for this power change: input is held meanwhile
        d.seenAt(181_000)
        d.onAccident(175_000, pass = false)
        assertTrue(ev.none { it is LoneWorkerLogic.JudgeEvent.Fall })
        d.tick(179_500 + PowerDebounce.CONFIRM_MS)
        val late = ev.filterIsInstance<LoneWorkerLogic.JudgeEvent.Fall>().single()
        assertEquals("dropped", late.take); assertFalse(late.pass)

        // mixed watch: a passed fall in either order brings the fall texts (fall wins); only not-a-fall impacts keep the drop texts
        for ((first, second, drop) in listOf(Triple(true, false, false), Triple(false, true, false), Triple(false, false, true))) {
            val mev = mutableListOf<LoneWorkerLogic.JudgeEvent>()
            val m = fallRuleOnly().apply { onJudge = { mev += it } }
            m.onAccident(1_000, pass = first)
            m.onAccident(5_000, pass = second)
            // question and weak buzz once per watch: the opening impact, plus a passed fall
            assertEquals("$first/$second", listOf(true, second), mev.filterIsInstance<LoneWorkerLogic.JudgeEvent.Fall>().map { it.asks })
            assertEquals("$first/$second", if (second) 5_000L else 1_000L, m.countdown(5_000)!!.buzzKey)
            assertEquals(Mode.CHECKING, m.seenAt(40_000))
            assertEquals("$first/$second", drop, m.dropWindow)
        }
        // a passed fall inside the open drop window also brings the fall texts
        val iev = mutableListOf<LoneWorkerLogic.JudgeEvent>()
        val inWindow = fallRuleOnly().apply { onJudge = { iev += it } }
        inWindow.onAccident(1_000, pass = false)
        assertEquals(Mode.CHECKING, inWindow.seenAt(31_000))
        inWindow.onAccident(35_000)
        assertFalse(inWindow.dropWindow)
        // inside an open window a passed fall is asked at once; the window's close question still asks the impact that opened it
        val inTake = iev.filterIsInstance<LoneWorkerLogic.JudgeEvent.Fall>().last()
        assertEquals("shut", inTake.take); assertTrue(inTake.asks)
        assertTrue(inWindow.ackWorking(36_000))
        val inClose = iev.last() as LoneWorkerLogic.JudgeEvent.ModeChange
        assertEquals(1_000L, inClose.impT); assertTrue(inClose.asksFall)
        // a new watch is decided by its own opening impact
        val n = fallRuleOnly()
        n.onAccident(1_000)
        assertEquals(Mode.CHECKING, n.seenAt(31_000))
        assertTrue(n.ackWorking(35_000))
        n.onAccident(100_000, pass = false)
        assertEquals(Mode.CHECKING, n.seenAt(130_000))
        assertTrue(n.dropWindow)
    }

    /**
     * A watch opened only by not-a-fall impacts also counts phone motion (MOVED): the window opens 30 s after the last
     * MOVED, at most 60 s after the watch base unless equipment-mounted (then no cap); a fall watch ignores MOVED. A MOVED
     * at or before the deadline that arrives before the data covers it postpones the window; one later than LATE_MS does
     * not close a window that already opened.
     */
    @Test fun drop_watch_waits_for_the_phone_to_stop_moving() {
        fun LoneWorkerLogic.moving(from: Long, to: Long) { for (t in from..to step 1_000L) onMoved(t) }

        // (a) stops moving: the window opens 30 s after the phone last moved (1.2.25 opened it at 31 s); MOVED keeps coming a
        // few seconds after it stops, carrying that time (MotionAnalyzer.lastActiveMs)
        val a = fallRuleOnly()
        a.onAccident(1_000, pass = false)
        a.moving(2_000, 20_000)
        for (t in 21_000L..27_000L step 1_000L) a.onMoved(t, 20_000)
        assertEquals("a 31s", Mode.WATCHING, a.seenAt(31_000))
        assertEquals("a 49.999s", Mode.WATCHING, a.seenAt(49_999))
        assertEquals("a 50s", Mode.CHECKING, a.seenAt(50_000))
        assertTrue("a drop", a.dropWindow)

        // (b) keeps moving, carried: capped at base + 60 s; MOVED never closes an open window
        val b = fallRuleOnly()
        b.onAccident(1_000, pass = false)
        b.moving(2_000, 60_000)
        assertEquals("b 60.999s", Mode.WATCHING, b.seenAt(60_999))
        assertEquals("b 61s", Mode.CHECKING, b.seenAt(61_000))
        assertTrue("b drop", b.dropWindow)
        b.onMoved(62_000)
        assertEquals("b after MOVED", Mode.CHECKING, b.seenAt(70_000))

        // (c) mounted: no cap, the window waits until the equipment stops
        val c = newLogic(charging = true).apply { setEquipment(true, 0L); stillMs = 3_600_000L }
        assertTrue(c.mounted)
        c.onAccident(20_000, pass = false)
        c.moving(21_000, 120_000)
        assertEquals("c 80s", Mode.WATCHING, c.seenAt(80_000))
        assertEquals("c 149.999s", Mode.WATCHING, c.seenAt(149_999))
        assertEquals("c 150s", Mode.CHECKING, c.seenAt(150_000))
        assertTrue("c drop", c.dropWindow)

        // (d) a fall watch ignores MOVED
        val d = fallRuleOnly()
        d.onAccident(1_000)
        d.moving(2_000, 20_000)
        assertEquals("d 30.999s", Mode.WATCHING, d.seenAt(30_999))
        assertEquals("d 31s", Mode.CHECKING, d.seenAt(31_000))
        assertFalse("d fall", d.dropWindow)

        // (e) a passed fall joins a postponed drop watch after its 30 s point: the fall rule applies at once
        val e = fallRuleOnly()
        e.onAccident(1_000, pass = false)
        e.moving(2_000, 35_000)
        assertEquals("e 35s", Mode.WATCHING, e.seenAt(35_000))
        e.moving(36_000, 41_000)
        e.onAccident(30_000)
        assertEquals("e 42s", Mode.CHECKING, e.seenAt(42_000))
        assertFalse("e fall", e.dropWindow)

        // (f) judge order: the deadline (40 s) waits for sensor data; a MOVED arriving before the data covers it postpones it
        val f = fallRuleOnly()
        f.onAccident(1_000, pass = false)
        f.moving(2_000, 10_000)
        f.seenAt(20_000)
        assertEquals("f 41s", Mode.WATCHING, f.modeAt(41_000))
        assertTrue("f waits", f.waitingOnSensors(41_000))
        f.onMoved(25_000)
        f.sensed(41_000)
        assertEquals("f after MOVED", Mode.WATCHING, f.modeAt(41_000))
        assertEquals("f 54.999s", Mode.WATCHING, f.seenAt(54_999))
        assertEquals("f 55s", Mode.CHECKING, f.seenAt(55_000))
        // counterpart: the data never covers the deadline, it is judged LATE_MS after it; a later MOVED does not close the window
        val g = fallRuleOnly()
        g.onAccident(1_000, pass = false)
        g.moving(2_000, 10_000)
        g.seenAt(20_000)
        assertEquals("g 41s", Mode.WATCHING, g.modeAt(41_000))
        assertEquals("g late", Mode.CHECKING, g.modeAt(40_000 + late))
        g.onMoved(25_000)
        g.sensed(46_000)
        assertEquals("g after late MOVED", Mode.CHECKING, g.modeAt(46_000))

        // (h) motion up to the not-a-fall judgment (a struggle after the impact) counts only to the impact; the left-over MOVED
        // after the judgment carries the impact time (MotionAnalyzer reads motion up to the decision as the impact): window 30 s after the impact
        val h = fallRuleOnly()
        h.moving(2_000, 11_000)            // struggles after the impact at 1 s
        h.onMoved(12_000, 11_000)
        h.onAccident(1_000, pass = false)  // judged at 13 s: not a fall
        for (t in 14_000L..18_000L step 1_000L) h.onMoved(t, 1_000) // left-over MOVED
        assertEquals("h 30.999s", Mode.WATCHING, h.seenAt(30_999))
        assertEquals("h 31s", Mode.CHECKING, h.seenAt(31_000))
        assertTrue("h drop", h.dropWindow)

        // (i) a second drop in a continuing drop watch: handled until it dropped again at 20 s; motion up to that impact's
        // judgment (32 s) counts only to the impact, so the window opens 30 s after it (not after the left-over MOVED)
        val i = fallRuleOnly()
        i.onAccident(1_000, pass = false)
        i.moving(2_000, 20_000)
        for (t in 21_000L..27_000L step 1_000L) i.onMoved(t, 21_000)
        i.onAccident(20_000, pass = false)
        for (t in 33_000L..39_000L step 1_000L) i.onMoved(t, 20_000)
        assertEquals("i 49.999s", Mode.WATCHING, i.seenAt(49_999))
        assertEquals("i 50s", Mode.CHECKING, i.seenAt(50_000))
        assertTrue("i drop", i.dropWindow)
    }
}
