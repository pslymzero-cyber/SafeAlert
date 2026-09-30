package com.wf11.safealert.service

import com.wf11.safealert.service.LoneWorkerLogic.Mode

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
