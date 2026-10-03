package com.wf11.safealert.service

/**
 * Walking-shape step evidence layer. Pure logic with no Android dependency. All times are ms on the elapsedRealtime clock.
 *
 * A step from the step sensor is accepted only if the closed 1-second accelerometer
 * window covering its time (end-1000 <= t < end) is walking-shaped.
 * A step that arrives before its window closes is held and judged when that window
 * closes; if a later window closes with no covering window, it is dropped.
 * Steps while the app itself is vibrating are dropped. Windows ending at or before an already-closed end are ignored.
 * closedTo (closed window end) and stepSeenTo (step delivery time) are the sensor-data arrival bounds that deadline judgments wait for.
 */
class WalkingSteps {

    private class Win(val endMs: Long, val walking: Boolean)

    private companion object {
        const val WINDOW_MS = MotionAnalyzer.WINDOW_MS
        const val KEEP_MS = 60_000L
    }

    private val windows = ArrayDeque<Win>()
    private val pending = ArrayDeque<Long>()
    private val steps = ArrayDeque<Long>()

    var closedTo = Long.MIN_VALUE
        private set
    var stepSeenTo = Long.MIN_VALUE
        private set

    fun reset() {
        windows.clear()
        pending.clear()
        steps.clear()
        closedTo = Long.MIN_VALUE
        stepSeenTo = Long.MIN_VALUE
    }

    /**
     * A closed 1-second window. null if its end is at or before an already-closed end;
     * otherwise the held step times accepted now that this window settles them.
     */
    fun onWindow(endMs: Long, walking: Boolean): List<Long>? {
        if (endMs <= closedTo) return null
        closedTo = endMs
        windows.addLast(Win(endMs, walking))
        while (endMs - windows.first().endMs > KEEP_MS) windows.removeFirst()
        val judged = pending.filter { it < endMs }
        if (judged.isEmpty()) return emptyList()
        pending.removeAll { it < endMs }
        return judged.filter { walking && it >= endMs - WINDOW_MS }.onEach { keep(it) }
    }

    /** One step. Returns its time if accepted immediately, null if held or dropped. */
    fun onStep(tMs: Long, vibrating: Boolean): Long? {
        if (tMs > stepSeenTo) stepSeenTo = tMs
        if (vibrating) return null
        if (tMs >= closedTo) {
            pending.addLast(tMs)
            while (tMs - pending.first() > KEEP_MS) pending.removeFirst()
            return null
        }
        val w = windows.lastOrNull { tMs >= it.endMs - WINDOW_MS && tMs < it.endMs } ?: return null
        if (!w.walking) return null
        keep(tMs)
        return tMs
    }

    /** Step sensor flush complete: every step up to the request time has arrived. */
    fun stepsFlushed(tMs: Long) {
        if (tMs > stepSeenTo) stepSeenTo = tMs
    }

    /** Accepted steps within from..to (both ends inclusive). */
    fun stepsIn(from: Long, to: Long): Int = steps.count { it in from..to }

    /**
     * n steps within the last windowMs up to t, counting only steps after `after` (a reference time that is itself not counted).
     */
    fun within(after: Long, t: Long, n: Int, windowMs: Long): Boolean =
        t > after && stepsIn(maxOf(after + 1, t - windowMs), t) >= n

    /** Time of the first step at which within is satisfied using steps after `after`. null if none. */
    fun firstWithin(after: Long, n: Int, windowMs: Long): Long? = steps.firstOrNull { within(after, it, n, windowMs) }

    /** Number of walking-shaped windows whose start is >= from and whose end is <= to. */
    fun strongIn(from: Long, to: Long): Int =
        windows.count { it.walking && it.endMs - WINDOW_MS >= from && it.endMs <= to }

    /**
     * End of the window at which an unbroken run of walking-shaped windows starting at or after from first reaches ms. null if none.
     * Real-time (first met in this window), unplug catch-up and FALL recount all use the same calculation.
     */
    fun firstRunEnd(from: Long, ms: Long): Long? {
        var n = 0
        var prev = Long.MIN_VALUE
        for (w in windows) {
            n = if (!w.walking || w.endMs - WINDOW_MS < from) 0 else if (w.endMs == prev + WINDOW_MS) n + 1 else 1
            prev = w.endMs
            if (n * WINDOW_MS >= ms) return w.endMs
        }
        return null
    }

    private fun keep(t: Long) {
        steps.addLast(t)
        while (t - steps.first() > KEEP_MS) steps.removeFirst()
    }
}

/**
 * Whether the CPU must be kept awake while monitoring: if any registered sensor is non-wake-up, its events pile up in the FIFO or are
 * dropped while the screen is off, so PARTIAL_WAKE_LOCK is needed (not needed with wake-up sensors only).
 */
fun sensorsNeedCpuWake(accelOn: Boolean, accelWake: Boolean, stepOn: Boolean, stepWake: Boolean): Boolean =
    (accelOn && !accelWake) || (stepOn && !stepWake)
