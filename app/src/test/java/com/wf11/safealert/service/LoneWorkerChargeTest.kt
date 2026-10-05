package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import com.wf11.safealert.service.LoneWorkerLogic.Rest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule 2 (no motion) runs only while the device is carried:
 *  - not charging: carried from the first distinct motion after start or unplug (or after 1 min of
 *    sensor silence); before that it waits. It is counted from the unplug edge whether the steps (or
 *    walking windows) are accepted before or after the report; a check's own step count starts when it
 *    opens and an unplug does not restart it.
 *  - charging: docked until 10 walking-shaped steps within 30 s (no step sensor: 5 walking-shaped
 *    windows within 30 s), then carried until the next real plug.
 *  - off in a settled zone. Power flaps shorter than 2 s are ignored; a real plug resets carrying
 *    and withdraws open checks.
 */
class LoneWorkerChargeTest {

    private val stillMs = 180_000L
    private val responseMs = 120_000L

    @Test fun start_without_charging_waits_for_first_distinct_motion() {
        val l = newLogic()
        assertEquals(Rest.WAIT, l.rest)
        assertEquals(Mode.WATCHING, l.modeAt(600_000))
        l.walk(600_000, 5)
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.WATCHING, l.seenAt(600_000 + stillMs - 1))
        assertEquals(Mode.CHECKING, l.seenAt(600_000 + stillMs))
        assertEquals("still", l.trigger)
    }

    @Test fun four_steps_do_not_end_the_wait() {
        val l = newLogic()
        l.walk(10_000, 4)
        assertEquals(Rest.WAIT, l.rest)
        assertEquals(Mode.WATCHING, l.modeAt(600_000))
    }

    @Test fun without_step_sensor_3s_strong_motion_ends_the_wait() {
        val l = newLogic()
        l.stepsAvailable = false
        l.strongRun(10_000, 2)
        assertEquals(Rest.WAIT, l.rest)
        l.strongRun(20_000, 3)
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.CHECKING, l.seenAt(22_000 + stillMs))
    }

    @Test fun unplug_waits_for_first_distinct_motion() {
        val l = newLogic(charging = true)
        assertEquals(Rest.DOCKED, l.rest)
        l.walk(9_000, 4)
        l.reportPower(false, 10_000)
        assertEquals(Rest.WAIT, l.rest)
        l.step(11_000)
        assertEquals(Rest.WAIT, l.rest)
        l.walk(15_000, 5)
        assertEquals(Rest.NONE, l.rest)
    }

    /**
     * Steps after the unplug edge reported before the debounce confirms it carry from the distinct motion;
     * the still deadline counts from the distinct motion after the unplug edge, not from one made with earlier steps.
     */
    @Test fun steps_before_unplug_report_carry_from_the_distinct_motion() {
        val l = newLogic(charging = true)
        l.step(9_300)
        l.step(9_700)
        for (i in 0..4) l.step(10_100L + i * 400)
        l.reportPower(false, 10_000)
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.WATCHING, l.seenAt(11_700 + stillMs - 1))
        assertEquals(Mode.CHECKING, l.seenAt(11_700 + stillMs))
        assertEquals("still", l.trigger)
        // only three steps after the unplug edge: steps before the unplug do not count
        val early = newLogic(charging = true)
        for (i in 0..4) early.step(9_300L + i * 400)
        early.reportPower(false, 10_000)
        assertEquals(Rest.WAIT, early.rest)
    }

    /** Four steps after the unplug edge and one after the fall check opened: carried, the check stays open. */
    @Test fun unplug_steps_carry_whenever_they_are_accepted() {
        bothOrders { late, m ->
            val l = newLogic(charging = true)
            l.onAccident(1_000)
            l.powerRaw(false, 29_000)
            for (t in listOf(29_200L, 29_600L, 30_000L, 30_400L)) if (late) l.onStep(t) else l.step(t)
            if (late) {
                assertEquals(m, Mode.WATCHING, l.modeAt(31_000))
                l.strongRun(30_000, 2)
            }
            assertEquals(m, Mode.CHECKING, l.seenAt(31_000))
            assertEquals(m, "fall", l.trigger)
            assertEquals(m, Rest.WAIT, l.rest)
            l.step(31_300)
            assertEquals(m, Mode.CHECKING, l.mode)
            assertEquals(m, "fall", l.trigger)
            assertEquals(m, Rest.NONE, l.rest)
        }
    }

    /** Three steps after the still check opened, an unplug, then two more: five steps close it in either arrival order. */
    @Test fun unplug_after_the_check_opened_keeps_its_step_count() {
        bothOrders { late, m ->
            val l = carriedWhileCharging()
            val open = 10_000 + l.stillMs
            assertEquals(m, Mode.CHECKING, l.seenAt(open))
            assertEquals(m, "still", l.trigger)
            l.step(open + 200)
            l.step(open + 500)
            l.step(open + 800)
            l.powerRaw(false, open + 1_100)
            if (!late) {
                l.step(open + 1_300)
                l.step(open + 1_700)
                l.modeAt(open + 1_100 + PowerDebounce.DEBOUNCE_MS)
            } else {
                l.onStep(open + 1_300)
                l.onStep(open + 1_700)
                assertEquals(m, Mode.CHECKING, l.modeAt(open + 1_100 + PowerDebounce.DEBOUNCE_MS))
                assertEquals(m, Rest.WAIT, l.rest)
                l.strongRun(open + 2_000, 1)
            }
            assertEquals(m, Mode.WATCHING, l.mode)
            assertEquals(m, Rest.WAIT, l.rest)
        }
    }

    /**
     * Without a step sensor, a 3 s walking run after the unplug edge (10 s) carries from its end in either arrival
     * order: a window starting at the unplug counts, one ending at it does not, and a gap or a still window
     * breaks the run.
     */
    @Test fun without_step_sensor_unplug_run_carries_whenever_it_is_accepted() {
        // window ends, a negative end is a still window
        for ((name, ends, carry) in listOf(
            Triple("run", listOf(11_000L, 12_000L, 13_000L), 13_000L),
            Triple("gap", listOf(11_000L, 12_000L, 14_000L, 15_000L, 16_000L), 16_000L),
            Triple("split", listOf(11_000L, 12_000L, -13_000L, 14_000L, 15_000L, 16_000L), 16_000L),
            Triple("edge", listOf(10_000L, 11_000L, 12_000L, -13_000L, 14_000L, 15_000L, 16_000L), 16_000L))) {
            bothOrders { late, o ->
                val m = "$name $o"
                val l = newLogic(charging = true)
                l.stepsAvailable = false
                fun feed(e: Long) = l.onWindow(MotionAnalyzer.Window(kotlin.math.abs(e), e > 0))
                ends.filter { kotlin.math.abs(it) <= 10_000 }.forEach(::feed)
                l.powerRaw(false, 10_000)
                val after = ends.filter { kotlin.math.abs(it) > 10_000 }
                if (late) {
                    l.modeAt(10_000 + PowerDebounce.DEBOUNCE_MS)
                    after.forEach(::feed)
                } else {
                    after.forEach(::feed)
                    l.modeAt(kotlin.math.abs(after.last()))
                }
                assertEquals(m, Rest.NONE, l.rest)
                assertEquals(m, Mode.WATCHING, l.seenAt(carry + stillMs - 1))
                assertEquals(m, Mode.CHECKING, l.seenAt(carry + stillMs))
                assertEquals(m, "still", l.trigger)
            }
        }
    }

    @Test fun sensor_silence_ends_wait_and_counts_from_there() {
        val l = newLogic()
        l.sensorSilent(100_000)
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.WATCHING, l.seenAt(100_000 + stillMs - 1))
        assertEquals(Mode.CHECKING, l.seenAt(100_000 + stillMs))
    }

    @Test fun charging_without_steps_is_docked_and_never_checks_still() {
        val l = newLogic(charging = true)
        l.walk(10_000, 9)
        assertEquals(Rest.DOCKED, l.rest)
        for (t in 60_000L..3_600_000L step 60_000L) assertEquals(Mode.WATCHING, l.modeAt(t))
    }

    /** Ten walking-shaped steps within 30 s while charging carry the device: the still count runs. */
    @Test fun charging_with_ten_steps_is_carried_and_still_check_opens() {
        val l = carriedWhileCharging()
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.WATCHING, l.seenAt(10_000 + stillMs - 1))
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.CHECKING, l.seenAt(10_000 + stillMs))
        assertEquals("still", l.trigger)
    }

    /** One feed of a carry row and the Rest expected after it (null = not checked after this feed). */
    private class Phase(val rest: Rest?, val feed: LoneWorkerLogic.() -> Unit)

    /** A carry row: the power at the start, whether a step sensor exists, then its feeds in order. */
    private class CarryRow(val name: String, val charging: Boolean, val stepSensor: Boolean, val phases: List<Phase>)

    private fun checkCarry(rows: List<CarryRow>) {
        for (r in rows) {
            val l = newLogic(charging = r.charging)
            if (!r.stepSensor) l.stepsAvailable = false
            for ((i, p) in r.phases.withIndex()) {
                p.feed(l)
                if (p.rest != null) assertEquals("${r.name}: after feed $i", p.rest, l.rest)
            }
        }
    }

    /** While charging, the device is carried only after 10 walking-shaped steps within 30 s, all after the plug. */
    @Test fun charging_carry_needs_10_walking_steps_within_30s_after_the_plug() = checkCarry(listOf(
        CarryRow("10 steps 3 s apart", charging = true, stepSensor = true,
            phases = listOf(Phase(Rest.NONE) { for (i in 0 until 10) step(1_000 + i * 3_000L) })),
        CarryRow("steps 3.5 s apart", charging = true, stepSensor = true,
            phases = (0 until 60).map { i -> Phase(Rest.DOCKED) { step(1_000 + i * 3_500L) } }),
        CarryRow("10, then 20 non-walking steps", charging = true, stepSensor = true,
            phases = listOf(Phase(Rest.DOCKED) { shuffle(10_000, 10) }, Phase(Rest.DOCKED) { shuffle(20_000, 20) })),
        CarryRow("9 steps before the plug, 1 after", charging = false, stepSensor = true,
            phases = listOf(Phase(null) { walk(9_000, 9) }, Phase(null) { reportPower(true, 10_000) },
                Phase(Rest.DOCKED) { step(11_000) }))))

    @Test fun replug_resets_step_carry() {
        val l = carriedWhileCharging()
        l.reportPower(false, 20_000)
        assertEquals(Rest.WAIT, l.rest)
        l.reportPower(true, 30_000)
        assertEquals(Rest.DOCKED, l.rest)
    }

    /**
     * No step sensor: carrying while charging = 5 walking-shaped windows within the last 30 s, not necessarily in a
     * row, all started after the plug. With a step sensor, windows alone do not carry.
     */
    @Test fun fallback_charging_carry_needs_5_walking_windows_within_30s_after_the_plug() = checkCarry(listOf(
        CarryRow("5 windows within 30 s", charging = true, stepSensor = false,
            phases = listOf(Phase(Rest.DOCKED) { strongWindows(5_000, 11_000, 17_000, 23_000) },
                Phase(Rest.NONE) { strongWindows(29_000) })),
        CarryRow("the same 5 windows with a step sensor", charging = true, stepSensor = true,
            phases = listOf(Phase(Rest.DOCKED) { strongWindows(5_000, 11_000, 17_000, 23_000, 29_000) })),
        CarryRow("4 windows, then still windows", charging = true, stepSensor = false,
            phases = listOf(Phase(null) { strongWindows(5_000, 11_000, 17_000, 23_000) }) +
                (24_000L..60_000L step 1_000L).map { t -> Phase(Rest.DOCKED) { onWindow(MotionAnalyzer.Window(t, false)) } }),
        CarryRow("windows 8 s apart", charging = true, stepSensor = false,
            phases = (0 until 20).map { i -> Phase(Rest.DOCKED) { strongWindows(5_000 + i * 8_000L) } }),
        CarryRow("windows before the plug", charging = false, stepSensor = false,
            phases = listOf(Phase(null) { strongWindows(2_000, 4_000, 6_000, 8_000, 10_500) },
                Phase(Rest.DOCKED) { reportPower(true, 10_000) },
                Phase(Rest.DOCKED) { strongWindows(11_000) },
                Phase(Rest.DOCKED) { strongWindows(14_000, 16_000, 18_000) },
                Phase(Rest.NONE) { strongWindows(20_000) }))))

    @Test fun real_plug_withdraws_open_still_check() {
        val l = newLogic()
        l.sensorSilent(0)
        assertEquals(Mode.CHECKING, l.seenAt(stillMs))
        l.reportPower(true, stillMs + 1_000)
        assertEquals(Mode.WATCHING, l.mode)
        assertEquals(Rest.DOCKED, l.rest)
        assertEquals(Mode.WATCHING, l.modeAt(stillMs + responseMs + 10_000))
    }

    @Test fun unplug_during_still_check_keeps_it_running_to_sos() {
        val l = carriedWhileCharging()
        assertEquals(Mode.CHECKING, l.seenAt(10_000 + stillMs))
        l.reportPower(false, 200_000)
        assertEquals(Rest.WAIT, l.rest)
        assertEquals(Mode.CHECKING, l.modeAt(10_000 + stillMs + responseMs - 1))
        assertEquals(Mode.SOS, l.seenAt(10_000 + stillMs + responseMs))
    }

    // -- PowerDebounce (2 s) --

    /**
     * PowerDebounce reports a raw power change only once it has held for 2 s, with the time of its first raw value: a
     * flap shorter than 2 s is dropped and a flap that comes back restarts the wait.
     * Rows: the seeded power, the raw values, then each poll with the report expected (null = none), and the
     * reported flag when checked.
     */
    @Test fun power_debounce_reports_only_a_change_stable_for_2s() {
        class Row(val name: String, val seed: Boolean, val raws: List<Pair<Boolean, Long>>,
                  val polls: List<Pair<Long, Pair<Boolean, Long>?>>, val reported: Boolean? = null)
        for (r in listOf(
            Row("on for 1 s", false, listOf(true to 0L, false to 1_000L), listOf(2_050L to null, 10_000L to null)),
            Row("off for 1.5 s", true, listOf(false to 0L, true to 1_500L), listOf(3_550L to null), reported = true),
            Row("on from 1 s", false, listOf(true to 1_000L, true to 1_500L),
                listOf(2_999L to null, 3_000L to (true to 1_000L), 3_001L to null), reported = true),
            Row("on, off, on again at 1 s", false, listOf(true to 0L, false to 500L, true to 1_000L),
                listOf(2_050L to null, 3_050L to (true to 1_000L))))) {
            val d = PowerDebounce()
            d.seed(r.seed)
            for ((on, t) in r.raws) d.raw(on, t)
            for ((t, want) in r.polls) assertEquals("${r.name}: poll at $t", want, d.poll(t))
            r.reported?.let { assertEquals("${r.name}: reported", it, d.reported) }
        }
    }

    /** The logic's settlePower tells whether it confirmed and applied a change, once. */
    @Test fun settle_power_applies_a_confirmed_change_once() {
        val l = newLogic(charging = true)
        l.powerRaw(false, 10_000)
        assertFalse(l.settlePower(10_000 + PowerDebounce.DEBOUNCE_MS - 1))
        assertTrue(l.settlePower(10_000 + PowerDebounce.DEBOUNCE_MS))
        assertEquals(Rest.WAIT, l.rest)
        assertFalse(l.settlePower(20_000))
    }

    /** A change already stable for 2 s is reported before a later raw value, with or without a tick in between. */
    @Test fun stable_power_change_is_applied_before_a_later_raw() {
        val l = newLogic(charging = true)
        l.powerRaw(false, 10_000)
        assertEquals(10_000 + PowerDebounce.CONFIRM_MS, l.nextCheckAt(10_000))
        // the confirmed change asks for a tick, and the opposite raw value starts a new wait
        assertTrue(l.powerRaw(true, 12_500))
        assertEquals(Rest.WAIT, l.rest)
        assertEquals(12_500 + PowerDebounce.CONFIRM_MS, l.nextCheckAt(12_500))
        l.modeAt(12_500 + PowerDebounce.DEBOUNCE_MS)
        assertEquals(Rest.DOCKED, l.rest)
        // a flap shorter than 2 s is dropped
        val d = newLogic(charging = true)
        d.powerRaw(false, 10_000)
        d.powerRaw(true, 11_500)
        assertEquals(Rest.DOCKED, d.rest)
        d.modeAt(20_000)
        assertEquals(Rest.DOCKED, d.rest)
    }
}
