package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import com.wf11.safealert.service.LoneWorkerLogic.Rest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Judgment order (JudgeOrder): the same sensor values and raw power values with their times give the same result
 * whenever the sensor batches arrive and whenever the power confirm tick runs. The power wait rule itself:
 * LoneWorkerPowerWaitTest. Words (held, sticky, floor): LoneWorkerTestKit.
 */
class LoneWorkerOrderTest : RestartKit() {

    /** Charging, carried by walking-shaped windows (no step sensor), still check open at 200.95 s: SOS due 320.95 s. */
    private fun chargingStillCheckOpen(): LoneWorkerLogic = newLogic(charging = true).apply {
        stepsAvailable = false
        strongWindows(4_000, 8_000, 12_000, 16_000, 20_000)
        sensed(200_000)
        assertEquals(Mode.CHECKING, modeAt(200_950))
    }

    /** One accelerometer window callback every second from..to, walking-shaped when its end is in walking. */
    private fun windows(from: Long, to: Long, walking: LongRange): List<Feed> = (from..to step 1_000L).map { s ->
        Feed(s, sense = { onWindow(MotionAnalyzer.Window(s, s in walking)) })
    }

    /**
     * Unplug at 320.9 s, 50 ms before the SOS deadline, then walking windows 320..322 s (3 s run). The walking
     * after the deadline waits for the confirm (or the bounce back) and the SOS goes out, delivered live or late.
     */
    @Test fun inputs_after_a_power_blocked_deadline_wait_for_its_judgment() {
        val sensors = windows(201_000, 324_000, 320_000L..322_000L)
        val confirm = listOf(Feed(320_900, on = false))
        val drop = confirm + Feed(322_500, on = true)
        for ((power, late, want) in listOf(
            Triple(confirm, 320_000L..322_950L, listOf("SOS still @322950", "end SOS still WAIT")),
            Triple(drop, 320_000L..322_500L, listOf("SOS still @322500", "end SOS still NONE")))) {
            val feeds = (sensors + power).sortedBy { it.at }
            val live = chargingStillCheckOpen().drive(200_950, feeds, 324_000)
            val later = chargingStillCheckOpen().drive(200_950, feeds, 324_000, late, late.last)
            assertEquals(listOf("CHECKING still @200950") + want, live)
            assertEquals(live, later)
        }
    }

    /** A change at the restart is applied first even when the saved still deadline has passed. */
    @Test fun restart_change_is_not_deferred_by_an_old_deadline() {
        for (plugged in listOf(true, false)) {
            val m = "plugged=$plugged"
            val old = if (plugged) newLogic(carried = true) else carriedWhileCharging()
            val s = saved(old, savedAt = 100_000, now = 5_000, wallGap = 300_000)
            assertTrue(m, s.carried && s.stillBase + old.stillMs < 5_000)
            val l = restart(old, savedAt = 100_000, now = 5_000, wallGap = 300_000, charging = plugged)
            assertEquals(m, Mode.WATCHING, l.seenAt(5_000 + RestartHold.POWER_HOLD_MS + 1_000))
            assertEquals(m, if (plugged) Rest.DOCKED else Rest.WAIT, l.rest)
        }
    }

    /**
     * Charging inside the zone, impact at 89 s, unplug at 100 s: the fall happened while charging and is ignored
     * whether it is processed before or after the unplug confirms.
     */
    @Test fun fall_before_an_unplug_is_ignored_whenever_it_is_processed() {
        bothOrders { late, m ->
            val l = newLogic(charging = true, zoneInside = true)
            assertEquals(m, Mode.WATCHING, l.seenAt(89_000))
            l.powerRaw(false, 100_000)
            if (!late) l.onAccident(89_000)
            assertEquals(m, Mode.WATCHING, l.modeAt(101_000))
            assertEquals(m, Mode.WATCHING, l.modeAt(100_000 + PowerDebounce.CONFIRM_MS))
            if (late) l.onAccident(89_000)
            assertEquals(m, Mode.WATCHING, l.seenAt(119_000))
            assertNull(m, l.snapshot(119_000).accidentUntil)
        }
    }

    /**
     * Outside the safe zone a real plug 5 s after the impact is the mounting action (power connected within 10 s
     * before or after the impact): the fall is ignored whether it is processed before or after the plug confirms.
     * Without the plug the same fall opens the suspicion.
     */
    @Test fun plug_shortly_after_impact_outside_zone_ignores_the_fall() {
        bothOrders { late, m ->
            val l = newLogic(carried = true)
            if (!late) l.onAccident(20_000)
            l.reportPower(true, 25_000)
            if (late) l.onAccident(20_000)
            assertNull(m, l.snapshot(32_000).accidentUntil)
            assertEquals(m, Mode.WATCHING, l.seenAt(60_000))
        }
        val control = newLogic(carried = true)
        control.onAccident(20_000)
        assertNotNull(control.snapshot(32_000).accidentUntil)
    }

    /** Steps before the impact at 20 s do not count for the accident: check at 50 s in either processing order. */
    @Test fun fall_counts_only_motion_after_the_impact_in_either_order() {
        bothOrders { late, m ->
            val l = newLogic(carried = true)
            for (t in listOf(17_600L, 18_200L, 18_800L, 19_400L)) l.step(t)
            if (late) {
                l.step(27_500)
                l.onAccident(20_000)
            } else {
                l.onAccident(20_000)
                l.step(27_500)
            }
            assertEquals(m, Mode.WATCHING, l.seenAt(49_999))
            assertEquals(m, Mode.CHECKING, l.seenAt(50_000))
            assertEquals(m, "fall", l.trigger)
        }
    }

    @Test fun next_check_never_returns_a_past_time() {
        val l = newLogic(carried = true)
        l.powerRaw(true, 1_000)
        assertEquals(180_000L, l.nextCheckAt(5_000))
    }

    /** The 10 s power poll (sticky) that only confirms a stable change still asks the monitor for a tick. */
    @Test fun power_poll_that_only_confirms_a_change_asks_for_a_tick() {
        val l = newLogic(carried = true)
        l.powerRaw(true, 10_000)
        assertTrue(l.powerRaw(true, 12_000, sticky = true))
        assertEquals(Rest.DOCKED, l.rest)
    }

    // ---- determinism table: deadline kind x plug/unplug x before/after x confirm/drop x steps after the deadline ----

    /** One table row: a fresh logic each call, drive start, deadline D, raw power feeds, time the power wait resolves. */
    private class Row(val make: () -> LoneWorkerLogic, val from: Long, val d: Long, val power: List<Feed>, val resolve: Long)

    /** Still, fall and SOS rows: the raw change 0.5 s before (or after) D, dropped by the opposite value at D + 1.2 s. */
    private fun row(kind: String, plug: Boolean, after: Boolean, drop: Boolean): Row {
        val d = when (kind) {
            "still" -> if (plug) 180_000L else 190_000L
            "fall" -> 40_000L
            else -> if (plug) 300_000L else 310_000L
        }
        val make = {
            val l = when (kind) {
                "still" -> if (plug) newLogic(carried = true) else carriedWhileCharging()
                "fall" -> (if (plug) newLogic(carried = true) else newLogic(charging = true)).apply { onAccident(10_000) }
                else -> if (plug) newLogic(carried = true).apply { seenAt(180_000) }
                    else carriedWhileCharging().apply { seenAt(190_000) }
            }
            l.seenAt(d - 3_000)
            l
        }
        val p = if (after) d + 500 else d - 500
        val power = listOf(Feed(p, on = plug)) + if (drop) listOf(Feed(d + 1_200, on = !plug)) else emptyList()
        return Row(make, d - 3_000, d, power, if (drop) d + 1_200 else p + PowerDebounce.CONFIRM_MS)
    }

    /**
     * Restored check rows (restart at 5 s with the other power, the saved still check held): before = the restart raw value
     * is the change (confirmed: D = 7 s, dropped at 6.2 s: D = 8.05 s); after = the restart value bounces back at 6.2 s
     * (D = 8.05 s) and a real change starts at 8.55 s (dropped at 9.25 s).
     */
    private fun restoredRow(plug: Boolean, after: Boolean, drop: Boolean): Row {
        val old = if (plug) newLogic(carried = true).apply { seenAt(180_000) } else carriedWhileCharging().apply { seenAt(190_000) }
        val savedAt = if (plug) 181_000L else 191_000L
        val power = when {
            !after && !drop -> emptyList()
            !after -> listOf(Feed(6_200, on = !plug))
            !drop -> listOf(Feed(6_200, on = !plug), Feed(8_550, on = plug))
            else -> listOf(Feed(6_200, on = !plug), Feed(8_550, on = plug), Feed(9_250, on = !plug))
        }
        val d = if (!after && !drop) 7_000L else 8_050L
        val resolve = when { !after && !drop -> 7_050L; !after -> 6_200L; !drop -> 10_600L; else -> 9_250L }
        return Row({ restart(old, savedAt = savedAt, now = 5_000, wallGap = 20_000, charging = plug) }, 5_000, d, power, resolve)
    }

    /**
     * Every second after from: the accelerometer callback closing that window (walking when a step is in it), then the
     * step flush callback. Each step is an accelerometer callback reporting MOVED, then the step callback.
     */
    private fun sensors(from: Long, until: Long, steps: List<Long>): List<Feed> =
        (from + 1_000..until step 1_000L).flatMap { s ->
            listOf(Feed(s, sense = { onWindow(MotionAnalyzer.Window(s, steps.any { it in s - 1_000 until s })) }),
                Feed(s, sense = { stepsFlushed(s) }))
        } + steps.flatMap { t -> listOf(Feed(t, sense = { onMoved(t) }), Feed(t, sense = { onStep(t) })) }

    /** "W DOCKED", "C still NONE", "S still WAIT" to the last drive line. */
    private fun end(code: String): String {
        val t = code.split(" ")
        return when (t[0]) {
            "W" -> "end WATCHING  ${t[1]}"
            "C" -> "end CHECKING ${t[1]} ${t[2]}"
            else -> "end SOS ${t[1]} ${t[2]}"
        }
    }

    /**
     * Runs the 16 rows of kind live and late (sensor data from D - 1.5 s held back until the power wait resolves).
     * P <= D rows give the same record with times, P > D rows the same final state. want = plug then unplug, each
     * "before confirm|before drop|after confirm|after drop" with "no steps/steps" per cell. A cell is the end state:
     * W = WATCHING, C = CHECKING, S = SOS, then the trigger, then the Rest (NONE = carried, DOCKED, WAIT).
     */
    private fun table(kind: String, want: List<String>) {
        for ((i, plug) in listOf(true, false).withIndex()) {
            val cells = want[i].split("|")
            for ((j, side) in listOf(false to false, false to true, true to false, true to true).withIndex()) {
                val (after, drop) = side
                val r = if (kind == "restored") restoredRow(plug, after, drop) else row(kind, plug, after, drop)
                for ((k, walk) in listOf(false, true).withIndex()) {
                    val name = "$kind/${if (plug) "plug" else "unplug"}/${if (after) "after" else "before"}/" +
                        "${if (drop) "drop" else "confirm"}/${if (walk) "walk" else "no"}"
                    val until = r.d + 12_000
                    val steps = if (walk) (0..5).map { r.d + 300 + 500L * it } else emptyList()
                    val feeds = (sensors(r.from, until, steps) + r.power).sortedBy { it.at }
                    val live = r.make().drive(r.from, feeds, until)
                    val late = r.make().drive(r.from, feeds, until, (r.d - 1_500)..r.resolve, r.resolve)
                    if (after) assertEquals(name, live.last(), late.last()) else assertEquals(name, live, late)
                    assertEquals(name, end(cells[j].split("/")[k]), live.last())
                }
            }
        }
    }

    /** Still, fall, restored (held at the restart) and SOS deadlines: each gives the same result for every delivery. */
    @Test fun every_deadline_is_the_same_for_every_delivery() {
        for ((kind, want) in listOf(
            "still" to listOf(
                "W DOCKED/W DOCKED|C still NONE/W NONE|W DOCKED/W DOCKED|C still NONE/W NONE",
                "W WAIT/W NONE|C still NONE/W NONE|C still WAIT/W NONE|C still NONE/W NONE"),
            "fall" to listOf(
                "W DOCKED/W DOCKED|C fall NONE/W NONE|W DOCKED/W DOCKED|C fall NONE/W NONE",
                "C fall WAIT/W NONE|C fall DOCKED/W DOCKED|C fall WAIT/W NONE|C fall DOCKED/W DOCKED"),
            "restored" to listOf(
                "W DOCKED/W DOCKED|C still NONE/W NONE|W DOCKED/W DOCKED|C still NONE/W NONE",
                "C still WAIT/W NONE|C still NONE/W NONE|C still WAIT/W NONE|C still NONE/W NONE"),
            "sos" to listOf(
                "W DOCKED/W DOCKED|S still NONE/S still NONE|S still DOCKED/S still DOCKED|S still NONE/S still NONE",
                "S still WAIT/S still NONE|S still NONE/S still NONE|S still WAIT/S still NONE|S still NONE/S still NONE"))) {
            table(kind, want)
        }
    }
}
