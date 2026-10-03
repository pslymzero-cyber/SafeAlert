package com.wf11.safealert.service

/**
 * Record of the last 10 s of vibration windows started by this app. Pure class (no Android).
 *
 * So the accelerometer does not read vibration-motor shaking as movement, sensor
 * samples that fall in windows where vibration was actually on (+200ms)
 * are excluded from activity statistics. Sensor samples arrive batched up to 5 s
 * late, so instead of asking "vibrating now?" this checks whether
 * a sample time fell in a past vibration window — hence recent windows are remembered for 10 s.
 * A repeating vibration covers only its on parts, not the rests. Assumes sensor event times use the elapsedRealtime clock.
 * Vibrations started by other apps are invisible (limitation).
 */
class VibrationWindow {
    companion object {
        const val GRACE_MS = 200L
        const val KEEP_MS = 10_000L
    }

    /** end == Long.MAX_VALUE means an open-ended repeat. A one-shot vibration has period = Long.MAX_VALUE. */
    private class Seg(val start: Long, var end: Long, val onMs: Long, val periodMs: Long)

    private val segs = ArrayList<Seg>()

    @Synchronized fun oneShot(nowMs: Long, durMs: Long) {
        prune(nowMs)
        segs.add(Seg(nowMs, nowMs + durMs, durMs, Long.MAX_VALUE))
    }

    /** Calling again restarts the phase (same as restarting the waveform). */
    @Synchronized fun loopStart(nowMs: Long, onMs: Long, periodMs: Long) {
        prune(nowMs)
        closeOpenLoops(nowMs)
        segs.add(Seg(nowMs, Long.MAX_VALUE, onMs, periodMs))
    }

    @Synchronized fun loopStop(nowMs: Long) = closeOpenLoops(nowMs)

    /** Time the vibration was cancelled. Ends every window still running at that point. */
    @Synchronized fun cut(nowMs: Long) {
        for (s in segs) if (s.end > nowMs) s.end = nowMs
    }

    @Synchronized fun covers(tMs: Long): Boolean {
        for (s in segs) {
            if (tMs < s.start) continue
            if (s.end != Long.MAX_VALUE && tMs > s.end + GRACE_MS) continue
            // For a one-shot vibration the end-time check is enough. A repeat covers only its on parts (+margin).
            if (s.periodMs == Long.MAX_VALUE || (tMs - s.start) % s.periodMs < s.onMs + GRACE_MS) return true
        }
        return false
    }

    private fun closeOpenLoops(nowMs: Long) {
        for (s in segs) if (s.end == Long.MAX_VALUE) s.end = nowMs
    }

    private fun prune(nowMs: Long) {
        segs.removeAll { it.end != Long.MAX_VALUE && it.end + GRACE_MS < nowMs - KEEP_MS }
    }
}
