package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/*
 * Shared helpers for the lone worker logic tests. Times are ms on one clock. Accelerometer windows
 * are 1 s long and end on whole seconds; a step at t is covered by the window that ends at the next
 * whole second after t.
 */

/** carried = not charging and already past the first-motion wait. */
internal fun newLogic(charging: Boolean = false, zoneInside: Boolean = false, carried: Boolean = false) =
    LoneWorkerLogic("SAFEALERT_WALKER_ME").apply {
        start(0L, zoneInside, charging)
        if (carried) sensorSilent(0L)
    }

private const val WIN = MotionAnalyzer.WINDOW_MS

internal fun coverEnd(t: Long) = t / WIN * WIN + WIN

/** One step at t, then the window covering it closes (walking-shaped or not). */
internal fun LoneWorkerLogic.step(t: Long, walking: Boolean = true, vibrating: Boolean = false) {
    onStep(t, vibrating)
    onWindow(MotionAnalyzer.Window(coverEnd(t), walking))
}

/** n steps 500 ms apart, the last at lastMs, covered by walking-shaped windows. */
internal fun LoneWorkerLogic.walk(lastMs: Long, n: Int) {
    for (i in n - 1 downTo 0) step(lastMs - i * 500L)
}

/** Same steps, but the covering windows are not walking-shaped (device shaken, lying, docked). */
internal fun LoneWorkerLogic.shuffle(lastMs: Long, n: Int) {
    for (i in n - 1 downTo 0) step(lastMs - i * 500L, walking = false)
}

/** count consecutive walking-shaped windows, the first ending at firstEnd. */
internal fun LoneWorkerLogic.strongRun(firstEnd: Long, count: Int) {
    for (i in 0 until count) onWindow(MotionAnalyzer.Window(firstEnd + i * WIN, true))
}

internal fun LoneWorkerLogic.strongWindows(vararg ends: Long) {
    for (e in ends) onWindow(MotionAnalyzer.Window(e, true))
}

/** Sensor data up to t has arrived: a still window closes at t and the step sensor is flushed to t. */
internal fun LoneWorkerLogic.sensed(t: Long) {
    onWindow(MotionAnalyzer.Window(t, false))
    stepsFlushed(t)
}

/** Acknowledge every peer entry (the [OK] button on all of them). */
internal fun LoneWorkerLogic.ackAll(now: Long) = silencePeers(now, peers.associate { it.id to it.epId })

internal fun LoneWorkerLogic.modeAt(t: Long): Mode { tick(t); return mode }

/** Sensor data covers t, then tick at t. */
internal fun LoneWorkerLogic.seenAt(t: Long): Mode { sensed(t); return modeAt(t) }

/** Charging, then carried by 10 steps ending at 10 s. */
internal fun carriedWhileCharging() = newLogic(charging = true).apply { walk(10_000, 10) }

/** Peer id sounds its siren every second from..to and the logic keeps watching with sensor data each second. */
internal fun LoneWorkerLogic.peerSiren(from: Long, to: Long, id: String = "P") {
    for (t in from..to step 1_000L) {
        onPeerBle(id, true, t)
        assertEquals(Mode.WATCHING, seenAt(t))
    }
}

/**
 * Restart like the monitor: startFrom feeds the current raw power to the logic's own debounce, and every
 * tick first applies a change stable for 2 s (modeAt = a monitor tick without sensor data, seenAt = with
 * data up to t).
 */
abstract class RestartKit {
    protected val wall0 = 1_000_000_000L
    protected val boot = 7

    /** Modes seen by the two ticks before the debounce reports (the monitor ticks on sensor data and its 10 s loop). */
    protected var beforePower: List<Mode> = emptyList()

    /** Save old at savedAt (wall wall0, bootSaved), then decode at elapsed now with wall0 + wallGap on bootNow. */
    protected fun saved(old: LoneWorkerLogic, savedAt: Long, now: Long, wallGap: Long,
                        bootNow: Int = boot + 1, bootSaved: Int = boot): LoneWorkerResume.State {
        val raw = LoneWorkerResume.encode(old.snapshot(savedAt), savedAt, wall0, bootSaved)
        val s = LoneWorkerResume.decode(raw, now, wall0 + wallGap, bootNow)
        assertNotNull(s)
        return s!!
    }

    /** Carried, fall at 1 s, accident check open at 31 s. */
    protected fun accidentCheck(): LoneWorkerLogic = newLogic(carried = true).apply {
        onAccident(1_000)
        assertEquals(Mode.CHECKING, seenAt(31_000))
        assertEquals("fall", trigger)
    }

    /** startFrom with the current raw power (charging). No tick. */
    protected fun restart(old: LoneWorkerLogic, savedAt: Long, now: Long, wallGap: Long, charging: Boolean = false,
                          bootNow: Int = boot + 1, bootSaved: Int = boot, zoneInside: Boolean = false): LoneWorkerLogic =
        LoneWorkerLogic("SAFEALERT_WALKER_ME").apply {
            startFrom(now, zoneInside, charging, saved(old, savedAt, now, wallGap, bootNow, bootSaved))
        }

    /**
     * restart, tick at the restart, flip the raw power at each of flips (between now and the next tick),
     * tick 1 ms before the debounce can report, then tick at its confirm check (CONFIRM_MS).
     */
    protected fun reboot(old: LoneWorkerLogic, savedAt: Long, now: Long, wallGap: Long, charging: Boolean = false,
                         bootNow: Int = boot + 1, bootSaved: Int = boot, zoneInside: Boolean = false,
                         flips: List<Long> = emptyList()): LoneWorkerLogic {
        val l = restart(old, savedAt, now, wallGap, charging, bootNow, bootSaved, zoneInside)
        val first = l.modeAt(now)
        var raw = charging
        for (f in flips) { raw = !raw; l.powerRaw(raw, f) }
        beforePower = listOf(first, l.modeAt(now + PowerDebounce.DEBOUNCE_MS - 1))
        l.modeAt(now + PowerDebounce.CONFIRM_MS)
        return l
    }

    /**
     * The raw power at the restart (now) bounces back to the saved value at 5.5 s and to now again at 5.8 s;
     * with gapTick the logic ticks at 5.6 s in between. Watching throughout.
     */
    protected fun LoneWorkerLogic.rebounce(now: Boolean, gapTick: Boolean, m: String) {
        assertEquals(m, Mode.WATCHING, modeAt(5_000))
        powerRaw(!now, 5_500)
        if (gapTick) assertEquals(m, Mode.WATCHING, modeAt(5_600))
        powerRaw(now, 5_800)
    }
}
