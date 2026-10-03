package com.wf11.safealert.service

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Judges active seconds, walk-like windows and falls from accelerometer samples (gravity included, m/s^2).
 *
 * Pure JVM logic. Active second: a 1 s window is active if its |a| standard deviation is at least ACTIVE_STD or its mean
 * vector differs in angle from the previous window's by at least ACTIVE_ANGLE_DEG. MOVED when 3 or more of the last 10
 * windows are active. A window without samples counts as still. Sample gaps are pushed in as empty windows before the MOVED
 * judgment.
 * Walk-like window: |a| standard deviation at walking level (STRONG_STD) or above. Angle is ignored — rolling over and posture
 * changes while lying down are excluded.
 *
 * A fall (FALL) is the only accident-detection signal: an impact (over 2.5 G) within 1 s right after free fall (under 0.5 G
 * for 60 ms or more), then, in the span 2–12 s after the impact, a posture change of 45 degrees or more and fewer than 3
 * active seconds. Thresholds are conservative values within the literature range and need field calibration.
 * On devices whose sensor range is below 2.5 G (2 G sensors), the fall impact threshold is lowered to fit the range (impactGFor).
 * eventMs records the impact sample's sensor time (the impact time, not the judgment time).
 * fallShape records the fall's shape (free-fall length, max G in the impact window, posture change) — for the in-safe-zone
 * judgment.
 *
 * Samples during this app's own vibration (masked) are excluded from activity stats only. Fall judgment sees every sample —
 * vibration motor acceleration is far below the impact threshold, and missing a fall during an alarm is worse.
 *
 * Reports whether each closed 1 s window is walk-like via onWindow.
 */
class MotionAnalyzer(
    private val impactG: Double = IMPACT_G,
    private val onWindow: (Window) -> Unit = {}
) {

    enum class Signal { NONE, MOVED, FALL }

    /** A closed 1 s window: endMs (sensor time), and whether it had samples and shook at walking level (walk-like). */
    data class Window(val endMs: Long, val strong: Boolean)

    /**
     * Fall shape: total time below 0.5 G within the 1 s before the impact sample (ms; summed even across
     * spiking samples in between), max |a| (G) in the impact window (free-fall end + 1 s),
     * posture change (degrees; null if the pre-fall posture is unknown).
     */
    data class FallShape(val freeFallMs: Long, val peakG: Double, val postureDeg: Double?) {
        companion object {
            /** A shape that exceeds every threshold — the default for onAccident(trigMs) called without a shape. */
            val ANY = FallShape(Long.MAX_VALUE, Double.MAX_VALUE, null)
        }
    }

    /**
     * In-safe-zone fall thresholds: it is a fall only when free-fall length, max impact and posture change all exceed them.
     * impactG is already sensor-range corrected (impactGFor). Unknown posture counts as exceeded.
     * Defaults 247 ms (30 cm), 2.5 G and 60 degrees must match the DevSettings defaults.
     */
    data class ZoneFall(val freeFallMs: Long = 247L, val impactG: Double = IMPACT_G, val postureDeg: Double = 60.0) {
        fun passes(s: FallShape): Boolean =
            s.freeFallMs >= freeFallMs && s.peakG >= impactG && (s.postureDeg == null || s.postureDeg >= postureDeg)
    }

    companion object {
        const val G = 9.80665
        const val ACTIVE_STD = 0.3
        const val ACTIVE_ANGLE_DEG = 10.0
        /** Walking-level shake (m/s^2, |a| standard deviation). Needs field calibration. */
        const val STRONG_STD = 1.5
        const val MOVE_WINDOWS = 10
        const val MOVE_MIN_ACTIVE = 3
        const val FREE_FALL_G = 0.5
        const val FREE_FALL_MIN_MS = 60L
        /** Span over which fall-shape free-fall time is summed: the 1 s before the impact sample. */
        const val FREE_FALL_SPAN_MS = 1000L
        const val IMPACT_G = 2.5
        /**
         * If the measurement range divided by G is below this, the range is taken as reported in g: 2, 4, 8 and 16, commonly
         * reported in g, are all read as g (16 m/s^2 too is read as 16g), while a normal
         * ±2g report of 19.61 m/s^2 stays as is. Needs field calibration.
         */
        const val MIN_RANGE_G = 1.75
        /** Window length for movement / walk-like judgment (sensor-time ms). */
        const val WINDOW_MS = 1_000L
        const val IMPACT_WINDOW_MS = 1000L
        const val POST_START_MS = 2000L
        const val POST_END_MS = 12000L
        const val POSTURE_DEG = 45.0
        const val POST_MAX_ACTIVE = 3

        /**
         * Fall impact threshold (G). base = 90% of the sensor range if the range is below IMPACT_G, otherwise IMPACT_G.
         * Result = max(base, min(wantG, 90% of range)) — raising wantG never lowers it
         * (monotonic), it never drops below base, and it never exceeds 90% of the range.
         * If the range is unknown (below MIN_RANGE_G; a NaN range counts as unknown), max(IMPACT_G, wantG). If
         * the range divided by G is below MIN_RANGE_G, the range is taken as reported in g units.
         * wantG defaults to IMPACT_G = the default fall threshold. The safe-zone G threshold goes through the same correction.
         */
        fun impactGFor(maxRangeMs2: Float, wantG: Double = IMPACT_G): Double {
            val rangeG = (maxRangeMs2 / G).let { if (it < MIN_RANGE_G) maxRangeMs2.toDouble() else it }
            if (!(rangeG >= MIN_RANGE_G)) return maxOf(IMPACT_G, wantG)
            val base = if (rangeG < IMPACT_G) 0.9 * rangeG else IMPACT_G
            return maxOf(base, minOf(wantG, 0.9 * rangeG))
        }

        /** Free-fall time (ms) for a drop height (cm) = round(1000 * sqrt(2h / g)). 30 cm = 247 ms. */
        fun freeFallMsFor(cm: Int): Long = Math.round(1000 * sqrt(2 * cm / 100.0 / G))
    }

    /** Sensor time (ms) of the impact sample of the last FALL signal. */
    var eventMs = 0L
        private set

    /** Fall shape of the last FALL signal. ANY before the first FALL. */
    var fallShape = FallShape.ANY
        private set

    // 1-second window accumulators
    private var curIdx = -1L
    private var n = 0
    private var sumM = 0.0
    private var sumM2 = 0.0
    private var sx = 0.0
    private var sy = 0.0
    private var sz = 0.0

    // Previous closed window
    private var lastClosedIdx = -1L
    private var hasPrev = false
    private var prevX = 0.0
    private var prevY = 0.0
    private var prevZ = 0.0
    private var activeMask = 0L

    // Free-fall tracking
    private var ffStart = -1L
    private var ffPreValid = false
    private var ffPreX = 0.0
    private var ffPreY = 0.0
    private var ffPreZ = 0.0
    private var armedEnd = -1L
    private var armedPreValid = false
    private var armedPreX = 0.0
    private var armedPreY = 0.0
    private var armedPreZ = 0.0
    /** Runs below 0.5 G (start, end), including blips under 60 ms; only the last 1 s is kept. */
    private val lowRuns = ArrayDeque<Pair<Long, Long>>()

    // Impact candidate
    private var candidate = false
    private var impactT = 0L
    private var candPreValid = false
    private var candPreX = 0.0
    private var candPreY = 0.0
    private var candPreZ = 0.0
    private var postN = 0
    private var postX = 0.0
    private var postY = 0.0
    private var postZ = 0.0
    private var postActive = 0
    private var candFfMs = 0L
    private var candImpactEnd = 0L
    private var candPeakG = 0.0

    fun reset() {
        curIdx = -1L; n = 0; sumM = 0.0; sumM2 = 0.0; sx = 0.0; sy = 0.0; sz = 0.0
        lastClosedIdx = -1L; hasPrev = false; activeMask = 0L
        ffStart = -1L; ffPreValid = false; armedEnd = -1L; armedPreValid = false
        candidate = false; postN = 0; postX = 0.0; postY = 0.0; postZ = 0.0; postActive = 0
        lowRuns.clear()
    }

    fun add(tMs: Long, x: Float, y: Float, z: Float, masked: Boolean = false): Signal {
        val ax = x.toDouble(); val ay = y.toDouble(); val az = z.toDouble()
        val mag = sqrt(ax * ax + ay * ay + az * az)

        var moved = false
        val idx = tMs / WINDOW_MS
        if (curIdx < 0) {
            curIdx = idx
        } else if (idx > curIdx) {
            moved = closeWindow(idx)
            curIdx = idx
        }
        if (!masked) { n++; sumM += mag; sumM2 += mag * mag; sx += ax; sy += ay; sz += az }

        val fell = detectFall(tMs, ax, ay, az, mag)
        if (fell) {
            eventMs = impactT
            return Signal.FALL
        }
        return if (moved) Signal.MOVED else Signal.NONE
    }

    /**
     * Closes the current window and returns whether the MOVED condition is met. nextIdx is the index of the window the new sample opens.
     */
    private fun closeWindow(nextIdx: Long): Boolean {
        // A window with no samples (all inside own-vibration windows) counts as still and leaves the previous mean vector unchanged
        val cnt = n.toDouble()
        val has = n > 0
        val mx = if (has) sx / cnt else prevX
        val my = if (has) sy / cnt else prevY
        val mz = if (has) sz / cnt else prevZ
        val mm = if (has) sumM / cnt else 0.0
        val std = if (has) sqrt((sumM2 / cnt - mm * mm).coerceAtLeast(0.0)) else 0.0
        var active = has && std >= ACTIVE_STD
        if (!active && has && hasPrev) active = angleDeg(mx, my, mz, prevX, prevY, prevZ) >= ACTIVE_ANGLE_DEG
        onWindow(Window((curIdx + 1) * WINDOW_MS, has && std >= STRONG_STD))

        if (lastClosedIdx >= 0) {
            val gap = curIdx - lastClosedIdx
            activeMask = if (gap >= 64) 0L else activeMask shl gap.toInt()
        }
        if (active) activeMask = activeMask or 1L
        // Empty windows between the closed window and the new one are pushed in as still before judging
        val empty = nextIdx - curIdx - 1
        if (empty > 0) activeMask = if (empty >= 64) 0L else activeMask shl empty.toInt()

        if (candidate) {
            val start = curIdx * WINDOW_MS
            if (active && start >= impactT + POST_START_MS && start + WINDOW_MS <= impactT + POST_END_MS) postActive++
        }

        if (has) { hasPrev = true; prevX = mx; prevY = my; prevZ = mz }
        lastClosedIdx = nextIdx - 1
        n = 0; sumM = 0.0; sumM2 = 0.0; sx = 0.0; sy = 0.0; sz = 0.0
        val mask = (1L shl MOVE_WINDOWS) - 1
        return java.lang.Long.bitCount(activeMask and mask) >= MOVE_MIN_ACTIVE
    }

    private fun detectFall(t: Long, x: Double, y: Double, z: Double, mag: Double): Boolean {
        // Track free-fall runs
        if (mag < FREE_FALL_G * G) {
            if (ffStart < 0) {
                ffStart = t
                ffPreValid = hasPrev
                ffPreX = prevX; ffPreY = prevY; ffPreZ = prevZ
            }
        } else if (ffStart >= 0) {
            lowRuns.addLast(ffStart to t)
            while (lowRuns.isNotEmpty() && lowRuns.first().second <= t - FREE_FALL_SPAN_MS) lowRuns.removeFirst()
            if (t - ffStart >= FREE_FALL_MIN_MS) {
                armedEnd = t
                armedPreValid = ffPreValid
                armedPreX = ffPreX; armedPreY = ffPreY; armedPreZ = ffPreZ
            }
            ffStart = -1L
        }

        // Impact right after free fall
        if (armedEnd >= 0) {
            if (t - armedEnd > IMPACT_WINDOW_MS) {
                armedEnd = -1L
            } else if (mag > impactG * G && !candidate) {
                candidate = true
                impactT = t
                candPreValid = armedPreValid
                candPreX = armedPreX; candPreY = armedPreY; candPreZ = armedPreZ
                postN = 0; postX = 0.0; postY = 0.0; postZ = 0.0; postActive = 0
                candFfMs = lowGMsBefore(t)
                candImpactEnd = armedEnd + IMPACT_WINDOW_MS
                candPeakG = mag / G
                armedEnd = -1L
            }
        }
        if (candidate && t <= candImpactEnd) candPeakG = maxOf(candPeakG, mag / G)

        if (!candidate) return false
        if (t >= impactT + POST_START_MS && t < impactT + POST_END_MS) {
            postN++; postX += x; postY += y; postZ += z
            return false
        }
        if (t < impactT + POST_END_MS) return false

        // Decision point
        candidate = false
        if (postN == 0 || postActive >= POST_MAX_ACTIVE) return false
        // If the pre-fall posture is unknown (right after service start), treat it as a posture change — missing a fall is worse
        val deg = if (candPreValid) angleDeg(postX / postN, postY / postN, postZ / postN, candPreX, candPreY, candPreZ) else null
        if (deg != null && !(deg >= POSTURE_DEG)) return false // A NaN angle is not a tip-over
        fallShape = FallShape(candFfMs, candPeakG, deg)
        return true
    }

    /** Total time below 0.5 G within FREE_FALL_SPAN_MS before impact sample t. */
    private fun lowGMsBefore(t: Long): Long =
        lowRuns.sumOf { (a, b) -> maxOf(0L, minOf(b, t) - maxOf(a, t - FREE_FALL_SPAN_MS)) }
}

private fun angleDeg(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
    val na = sqrt(ax * ax + ay * ay + az * az)
    val nb = sqrt(bx * bx + by * by + bz * bz)
    if (na < 1e-6 || nb < 1e-6) return 0.0
    val c = ((ax * bx + ay * by + az * bz) / (na * nb)).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(c))
}
