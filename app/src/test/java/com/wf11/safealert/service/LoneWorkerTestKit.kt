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

/**
 * Restart like the monitor. Each monitor tick first polls the power debounce (a confirmed change is
 * applied there and nowhere else), then ticks.
 */
abstract class RestartKit {
    protected val wall0 = 1_000_000_000L
    protected val boot = 7

    /** The monitor debounce of the last restart. */
    protected var power = PowerDebounce()

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

    /** startFrom, seed the debounce with the started charging value and feed the raw power (charging). No tick. */
    protected fun restart(old: LoneWorkerLogic, savedAt: Long, now: Long, wallGap: Long, charging: Boolean = false,
                          bootNow: Int = boot + 1, bootSaved: Int = boot, zoneInside: Boolean = false): LoneWorkerLogic {
        val l = LoneWorkerLogic("SAFEALERT_WALKER_ME")
        power = PowerDebounce()
        power.seed(l.startFrom(now, zoneInside, charging, saved(old, savedAt, now, wallGap, bootNow, bootSaved)))
        power.raw(charging, now)
        return l
    }

    /** One monitor tick at t. */
    protected fun LoneWorkerLogic.monitorTick(t: Long): Mode {
        power.poll(t)?.let { (on, at) -> setCharging(on, at) }
        return modeAt(t)
    }

    /**
     * restart, tick at the restart, flip the raw power at each of flips (between now and the next tick),
     * tick 1 ms before the debounce can report, then tick when the monitor polls it (CONFIRM_MS).
     */
    protected fun reboot(old: LoneWorkerLogic, savedAt: Long, now: Long, wallGap: Long, charging: Boolean = false,
                         bootNow: Int = boot + 1, bootSaved: Int = boot, zoneInside: Boolean = false,
                         flips: List<Long> = emptyList()): LoneWorkerLogic {
        val l = restart(old, savedAt, now, wallGap, charging, bootNow, bootSaved, zoneInside)
        val first = l.monitorTick(now)
        var raw = charging
        for (f in flips) { raw = !raw; power.raw(raw, f) }
        beforePower = listOf(first, l.monitorTick(now + PowerDebounce.DEBOUNCE_MS - 1))
        l.monitorTick(now + PowerDebounce.CONFIRM_MS)
        return l
    }
}
