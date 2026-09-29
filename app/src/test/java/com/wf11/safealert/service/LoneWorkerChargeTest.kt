package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import com.wf11.safealert.service.LoneWorkerLogic.Rest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * External power rule: docked = rest, carried is decided by posture (never by vibration),
 * only a human-handled plug resets carried, unplug from the dock waits for a pickup posture.
 */
class LoneWorkerChargeTest {

    private val stillMs = 180_000L
    private val responseMs = 120_000L
    private val g = MotionAnalyzer.G

    private fun newLogic() = LoneWorkerLogic("SAFEALERT_WALKER_ME").apply { start(0L, false) }

    /** One closed 1 s window whose mean vector is tilted deg degrees around the x axis. */
    private fun win(endMs: Long, deg: Double, active: Boolean = false): MotionAnalyzer.Window {
        val r = deg * PI / 180.0
        return MotionAnalyzer.Window(endMs, true, 0.0, g * sin(r), g * cos(r), active)
    }

    private fun LoneWorkerLogic.feed(endMs: Long, deg: Double, active: Boolean = false) =
        onWindow(endMs, win(endMs, deg, active))

    /** Handling motion right before the plug: one active window ending at t, then plug at t. */
    private fun LoneWorkerLogic.humanPlug(t: Long) {
        feed(t, 0.0, active = true)
        setCharging(true, t)
    }

    /** Human plug at 1 s, dock baseline at 17 s, two tilted windows at 18 s and 19 s: carried. */
    private fun carriedLogic(): LoneWorkerLogic = newLogic().apply {
        humanPlug(1_000)
        feed(17_000, 0.0)
        feed(18_000, 40.0)
        feed(19_000, 40.0)
    }

    /** Human plug at 1 s, dock baseline at 17 s, unplug at 30 s: waiting for pickup. */
    private fun pickupLogic(): LoneWorkerLogic = newLogic().apply {
        humanPlug(1_000)
        feed(17_000, 0.0)
        setCharging(false, 30_000)
    }

    @Test fun vibration_only_keeps_dock() {
        val l = newLogic()
        var now = 0L
        val a = MotionAnalyzer { l.onWindow(now, it) }
        val end = 5_000 + stillMs + 60_000
        var plugged = false
        var moved = 0
        var t = 0L
        while (t <= end) {
            now = t
            if (!plugged && t >= 5_000) { l.setCharging(true, t); plugged = true }
            val s = t / 1000.0
            val z = when {
                t < 5_000 -> 9.81 + 3.0 * sin(2 * PI * 2.0 * s)
                t >= 25_000 && t < 85_000 -> 9.81 + 1.5 * sin(2 * PI * 20.0 * s)
                else -> 9.81
            }
            when (a.add(t, 0f, 0f, z.toFloat())) {
                MotionAnalyzer.Signal.MOVED -> { l.onMoved(t); if (t >= 25_000) moved++ }
                MotionAnalyzer.Signal.FALL -> l.onFall(t)
                MotionAnalyzer.Signal.NONE -> {}
            }
            if (t % 1000 == 0L) {
                l.tick(t)
                assertEquals(Mode.WATCHING, l.mode)
                if (t >= 5_000) assertEquals(Rest.DOCKED, l.rest)
            }
            t += 20
        }
        assertTrue(moved > 0)
    }

    @Test fun posture_lift_after_settle_marks_carried() {
        val l = carriedLogic()
        assertEquals(Rest.NONE, l.rest)
        l.tick(19_000 + stillMs - 1)
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(19_000 + stillMs)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("still", l.trigger)
    }

    @Test fun single_tilted_window_does_not_lift() {
        val l = newLogic()
        l.humanPlug(1_000)
        l.feed(17_000, 0.0)
        l.feed(18_000, 40.0)
        l.feed(19_000, 0.0)
        l.feed(20_000, 40.0)
        l.feed(21_000, 0.0)
        assertEquals(Rest.DOCKED, l.rest)
        l.tick(21_000 + stillMs + 60_000)
        assertEquals(Mode.WATCHING, l.mode)
    }

    @Test fun posture_before_settle_is_ignored() {
        val l = newLogic()
        l.humanPlug(1_000)
        for (s in 3L..10L) l.feed(s * 1000, 40.0)
        assertEquals(Rest.DOCKED, l.rest)
        l.tick(10_000 + stillMs + 60_000)
        assertEquals(Mode.WATCHING, l.mode)
    }

    @Test fun fall_while_docked_after_settle_opens_fall_check() {
        val l = newLogic()
        l.humanPlug(1_000)
        l.onFall(20_000)
        assertEquals(Rest.NONE, l.rest)
        l.tick(20_000)
        assertEquals(Mode.CHECKING, l.mode)
        assertEquals("fall", l.trigger)

        val j = newLogic()
        j.humanPlug(1_000)
        j.onFall(6_000)
        assertEquals(Rest.DOCKED, j.rest)
        j.tick(7_000)
        assertEquals(Mode.WATCHING, j.mode)
    }

    @Test fun moved_alone_never_sets_carried() {
        val l = newLogic()
        l.humanPlug(1_000)
        for (s in 16L..60L) l.onMoved(s * 1000)
        assertEquals(Rest.DOCKED, l.rest)
        l.tick(60_000 + stillMs)
        assertEquals(Mode.WATCHING, l.mode)
    }

    @Test fun plug_without_motion_keeps_watch_check_and_fall() {
        val l = carriedLogic()
        l.tick(19_000 + stillMs)
        assertEquals(Mode.CHECKING, l.mode)
        l.setCharging(false, 19_000 + stillMs + 1_000)
        l.setCharging(true, 19_000 + stillMs + 2_000)
        assertEquals(Rest.NONE, l.rest)
        assertEquals(Mode.CHECKING, l.mode)
        l.tick(19_000 + stillMs + responseMs)
        assertEquals(Mode.SOS, l.mode)

        val f = carriedLogic()
        f.onFall(25_000)
        f.setCharging(false, 26_000)
        f.setCharging(true, 27_000)
        assertEquals(Rest.NONE, f.rest)
        f.tick(28_000)
        assertEquals(Mode.CHECKING, f.mode)
        assertEquals("fall", f.trigger)
    }

    @Test fun human_plug_resets() {
        val l = carriedLogic()
        l.tick(19_000 + stillMs)
        assertEquals(Mode.CHECKING, l.mode)
        val u = 19_000 + stillMs + 1_000
        l.setCharging(false, u)
        l.feed(u + 5_000, 0.0, active = true)
        l.setCharging(true, u + 6_000)
        assertEquals(Rest.DOCKED, l.rest)
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(u + 6_000 + responseMs + stillMs)
        assertEquals(Mode.WATCHING, l.mode)

        // Late batched window: ended 3 s before a motionless plug, arrives 2 s after it
        val b = carriedLogic()
        b.setCharging(false, 60_000)
        b.setCharging(true, 70_000)
        assertEquals(Rest.NONE, b.rest)
        b.onWindow(72_000, win(67_000, 0.0, active = true))
        assertEquals(Rest.DOCKED, b.rest)
    }

    @Test fun unplug_from_dock_waits_for_pickup_through_vibration() {
        val l = pickupLogic()
        assertEquals(Rest.PICKUP, l.rest)
        var t = 31_000L
        while (t <= 30_000 + stillMs + 60_000) {
            l.feed(t, 0.0, active = true)
            l.onMoved(t)
            l.tick(t)
            assertEquals(Mode.WATCHING, l.mode)
            assertEquals(Rest.PICKUP, l.rest)
            t += 1_000
        }
    }

    @Test fun pickup_posture_ends_wait() {
        val l = pickupLogic()
        l.feed(40_000, 45.0, active = true)
        assertEquals(Rest.PICKUP, l.rest)
        l.feed(41_000, 45.0)
        assertEquals(Rest.NONE, l.rest)
        l.tick(41_000 + stillMs - 1)
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(41_000 + stillMs)
        assertEquals(Mode.CHECKING, l.mode)
    }

    @Test fun sensor_silence_ends_wait_after_60s() {
        val l = LoneWorkerLogic("SAFEALERT_WALKER_ME").apply { start(0L, false, charging = true) }
        assertEquals(Rest.DOCKED, l.rest)
        l.setCharging(false, 100_000)
        l.tick(159_999)
        assertEquals(Rest.PICKUP, l.rest)
        l.tick(160_000)
        assertEquals(Rest.NONE, l.rest)
        l.tick(160_000 + stillMs - 1)
        assertEquals(Mode.WATCHING, l.mode)
        l.tick(160_000 + stillMs)
        assertEquals(Mode.CHECKING, l.mode)
    }

    @Test fun boot_restore_starts_waiting_for_pickup() {
        val l = LoneWorkerLogic("SAFEALERT_WALKER_ME").apply { start(0L, false, charging = false, awaitPickup = true) }
        assertEquals(Rest.PICKUP, l.rest)
        for (s in 1L..5L) l.feed(s * 1000, 0.0)
        for (s in 6L..30L) { l.feed(s * 1000, 3.0, active = true); l.onMoved(s * 1000) }
        l.tick(30_000)
        assertEquals(Rest.PICKUP, l.rest)
        l.feed(31_000, 50.0, active = true)
        l.feed(32_000, 50.0)
        assertEquals(Rest.NONE, l.rest)

        val d = LoneWorkerLogic("SAFEALERT_WALKER_ME").apply { start(0L, false, charging = true) }
        assertEquals(Rest.DOCKED, d.rest)
    }

    @Test fun unplug_while_carried_keeps_watching_and_human_replug_rests_again() {
        val l = carriedLogic()
        l.setCharging(false, 30_000)
        assertEquals(Rest.NONE, l.rest)
        l.tick(19_000 + stillMs)
        assertEquals(Mode.CHECKING, l.mode)

        val r = carriedLogic()
        r.setCharging(false, 30_000)
        r.humanPlug(31_000)
        assertEquals(Rest.DOCKED, r.rest)
        r.tick(31_000 + stillMs + 60_000)
        assertEquals(Mode.WATCHING, r.mode)
    }

    @Test fun charging_keeps_sos() {
        val l = newLogic()
        l.tick(stillMs)
        l.tick(stillMs + responseMs)
        assertEquals(Mode.SOS, l.mode)
        l.setCharging(true, stillMs + responseMs + 1_000)
        assertEquals(Mode.SOS, l.mode)
        l.setCharging(false, stillMs + responseMs + 2_000)
        l.tick(stillMs + responseMs + 3_000)
        assertEquals(Mode.SOS, l.mode)
    }
}
