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
 * A fall candidate waiting for the 12 s decision can be read (pendingImpactMs, pendingShape) for the on-screen count only;
 * the judgment uses the FALL signal alone.
 *
 * After-impact judgment (onPost, 1.2.28): from every impact right after free fall (candidate or not) it follows the phone
 * for up to 30 s. Walking (acceleration peaks 0.3-1.0 s apart, 4 in a row) ends it quietly ("walk"); no walking and a posture
 * 30 degrees or more off the pre-fall one with 3-5 s of stillness reports "post5"; at 10 s a changed posture ("post10") or 8-10 s
 * of stillness ("still10"); then every second 2 s of stillness ("still"), at 30 s "cap30". After a free fall of 300 ms or more
 * (a dropped phone, or a person from a height - the length cannot tell them apart) there is no quick alert: only signs that
 * someone has the phone again end it quietly - walking ("walk"), the phone lifted 0.5 m or more ("lift"), the screen unlocked
 * after the phone was moving ("screen", onScreen) - and without one the alert comes at 30 s ("phone30"). Values come from the
 * simulation on field data (not settings). The result decides when the accident window opens (LoneWorkerLogic.onPost); the
 * FALL / IMPACT signals and candidates are unchanged.
 * Impacts that come while a judgment is still running do not start another one; the first judgment goes on, and the earliest
 * later impact is kept: when the running one ends without an alert (the result is marked superseded) the kept one is judged next
 * from the stored samples, catching up on the checks already past; an alert drops it (the window covers it). After a long free
 * fall the new impact simply takes over.
 */
class MotionAnalyzer(
    private val impactG: Double = IMPACT_G,
    private val onCandidate: (Candidate) -> Unit = {},
    private val onPost: (Post) -> Unit = {},
    // onWindow stays last: existing callers pass it as a trailing lambda
    private val onWindow: (Window) -> Unit = {}
) {

    /** IMPACT = a decided impact candidate the rule calls not-a-fall (post_none / post_active / posture); eventMs and fallShape are set for it. */
    enum class Signal { NONE, MOVED, FALL, IMPACT }

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

    /** The 12 s decision of one impact candidate (pass, or the drop reason), for the judge-data record only. */
    data class Candidate(
        val impactT: Long, val ffMs: Long, val peakG: Double, val impactG: Double, val postN: Int, val postActive: Int,
        val deg: Double?, val preValid: Boolean, val pass: Boolean, val why: String
    )

    /**
     * The after-impact judgment of one impact (see class KDoc), reported once when it ends. out = post5 | post10 | still10 |
     * still | cap30 | phone30 (alerts) | walk | lift | screen (quiet ends). atMs = the judged moment after the impact; tiltDeg /
     * stdMg = posture change over the last 1 s and |a| standard deviation (mg) over the last 2 s at that moment; run = longest
     * walking-step run; ffMs = free fall. superseded = a quiet end while a later impact was waiting (its judgment follows at once).
     */
    data class Post(
        val impT: Long, val out: String, val atMs: Long, val tiltDeg: Double?, val stdMg: Int?, val run: Int,
        val ffMs: Long, val peakG: Double, val superseded: Boolean = false
    )

    private fun cand(deg: Double?, why: String) {
        runCatching { onCandidate(Candidate(impactT, candFfMs, candPeakG, impactG, postN, postActive, deg, candPreValid, why.isEmpty(), why)) }
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

        // After-impact judgment (simulation values, not settings). All times are ms after the impact.
        const val POST_BASE_FROM_MS = 3000L      // pre-fall posture = mean over impact-3000 .. impact-1000
        const val POST_BASE_TO_MS = 1000L
        const val POST_WALK_FROM_MS = 2000L
        const val POST_T1_MS = 5000L
        const val POST_T2_MS = 10000L
        const val POST_T3_MS = 30000L
        const val POST_STEP_MS = 1000L           // check interval after the first judgment
        const val POST_DELAY_MS = 400L           // peak half width 300 ms + 2 smoothing samples, judged this late
        const val POST_TILT_DEG = 30.0
        const val POST_STILL_STD_G = 0.02
        const val POST_TILT_SPAN_MS = 1000L
        const val POST_STILL_SPAN_MS = 2000L
        const val POST_PEAK_G = 1.15
        const val POST_RISE_G = 0.15
        const val POST_PEAK_HALF_MS = 300.0
        const val POST_GAP_MIN_MS = 300L
        const val POST_GAP_MAX_MS = 1000L
        const val POST_WALK_RUN = 3
        const val POST_SMOOTH = 5
        /** Free fall of this length or more: a dropped phone or a person from a height - no quick alert, only signs of someone (walk, lift, screen). */
        const val PHONE_FF_MS = 300L
        /** Lift sign: look from this long after the impact; a still second (|a| std 0.02 G or less), then 1.5 s of movement rising 0.5 m or more. */
        const val POST_LIFT_FROM_MS = 1000L
        const val POST_LIFT_SPAN_MS = 1500L
        const val POST_LIFT_REST_G = 0.05
        const val POST_LIFT_M = 0.5
        /** Judge-data only: how long the phone must stay still after its last active window before its stop is reported (takeStop). */
        const val STOP_AFTER_MS = 30_000L
        /** Samples kept for the after-impact judgment: baseline 3 s + 30 s + delay is about 1,700 at 50 Hz. */
        private const val POST_RING = 8192

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

    /** Sensor time (ms) of the impact sample of the last FALL or IMPACT signal. */
    var eventMs = 0L
        private set

    /** Fall shape of the last FALL or IMPACT signal. ANY before the first one. */
    var fallShape = FallShape.ANY
        private set

    /**
     * Sensor time (ms) of the impact while a fall candidate waits for its 12 s decision, else null. Read-only, for the
     * on-screen count (the judgment never uses it).
     */
    val pendingImpactMs: Long? get() = if (candidate) impactT else null

    /**
     * What is known about the pending candidate so far: free-fall length and max G (posture is known only at the
     * decision, so null). Meaningful only while pendingImpactMs is not null.
     */
    val pendingShape: FallShape get() = FallShape(candFfMs, candPeakG, null)

    /**
     * Sensor time (ms) of the impact while its after-impact judgment is still running (up to 30 s), else null. Read-only, for
     * the on-screen count (the judgment never uses it).
     */
    val pendingPostMs: Long? get() = if (trackImp >= 0) trackImp else null

    /** Shape of the impact whose after-impact judgment is running (posture unknown). Meaningful only while pendingPostMs is not null. */
    val pendingPostShape: FallShape get() = FallShape(trackFf, trackPeak, null)

    /**
     * Sensor time (ms) of the end of the last active 1-second window: when the phone last really moved. MOVED keeps firing
     * for up to ~7 s after the phone stops (3 of the last 10 windows), so this, not the MOVED sample time, says when it
     * stopped. At a fall decision it drops to the impact time, and the decision window and the one after it (a settling
     * window) never raise it: motion up to the judgment reads as the impact. 0 before any active window.
     */
    var lastActiveMs = 0L
        private set
    private var quietIdx = -1L // last window index that cannot raise lastActiveMs (decision window + 1)
    private var stopTaken = 0L // lastActiveMs value takeStop already returned

    /**
     * Judge-data recording only (never used by a judgment): the moment the phone stopped (lastActiveMs), once, when it has stayed
     * still for STOP_AFTER_MS since — null otherwise. A new stop after more movement is returned again.
     */
    fun takeStop(tMs: Long): Long? {
        val s = lastActiveMs
        if (s <= 0L || tMs - s < STOP_AFTER_MS || s == stopTaken) return null
        stopTaken = s
        return s
    }

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

    /** A not-a-fall decision waiting to be reported as IMPACT (kept one sample when that sample closes a MOVED window). */
    private var impactDue = false

    // After-impact judgment: recent samples (g) and the one impact being followed (trackImp < 0 = none)
    private val ring = SampleRing(POST_RING)
    private var trackImp = -1L
    private var trackFf = 0L
    private var trackPeak = 0.0
    private var trackImpactEnd = 0L
    private var trackNext = POST_T1_MS
    private var trackArmedEnd = -1L // free fall (armedEnd) the running or last track started from; one track per free fall
    // The earliest impact that came while a (short free fall) judgment was running; judged when that one ends without an alert
    private var deferImp = -1L
    private var deferFf = 0L
    private var deferPeak = 0.0
    private var deferImpactEnd = 0L

    fun reset() {
        curIdx = -1L; n = 0; sumM = 0.0; sumM2 = 0.0; sx = 0.0; sy = 0.0; sz = 0.0
        lastClosedIdx = -1L; hasPrev = false; activeMask = 0L
        ffStart = -1L; ffPreValid = false; armedEnd = -1L; armedPreValid = false
        candidate = false; postN = 0; postX = 0.0; postY = 0.0; postZ = 0.0; postActive = 0
        lowRuns.clear(); impactDue = false
        lastActiveMs = 0L; quietIdx = -1L
        ring.clear(); trackImp = -1L; trackArmedEnd = -1L; deferImp = -1L; stopTaken = 0L
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

        ring.dropBefore((if (trackImp >= 0) trackImp else tMs) - POST_BASE_FROM_MS)
        ring.push(tMs, ax / G, ay / G, az / G, mag / G)

        val fell = detectFall(tMs, ax, ay, az, mag)
        if (deferImp >= 0 && tMs <= deferImpactEnd) deferPeak = maxOf(deferPeak, mag / G)
        if (trackImp >= 0) {
            if (tMs <= trackImpactEnd) trackPeak = maxOf(trackPeak, mag / G)
            checkPost(tMs)
        }
        if (fell) {
            eventMs = impactT
            return Signal.FALL
        }
        // IMPACT never replaces MOVED (no-motion detection keeps every movement): it waits one sample when both fall on one sample
        if (moved) return Signal.MOVED
        if (impactDue) { impactDue = false; return Signal.IMPACT }
        return Signal.NONE
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
        if (active && curIdx > quietIdx) lastActiveMs = (curIdx + 1) * WINDOW_MS
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
            } else if (mag > impactG * G) {
                // The after-impact judgment follows an impact (also while a candidate waits). A running one is not replaced: it keeps the
                // first impact's pre-fall posture and its 30 s limit; a later impact is kept (the earliest) and judged when it ends
                // without an alert. After a long free fall (dropped phone or a person from a height) the new impact takes over.
                if (armedEnd != trackArmedEnd) {
                    if (trackImp < 0 || trackFf >= PHONE_FF_MS) {
                        trackArmedEnd = armedEnd
                        trackImp = t
                        trackFf = lowGMsBefore(t)
                        trackImpactEnd = armedEnd + IMPACT_WINDOW_MS
                        trackPeak = mag / G
                        trackNext = POST_T1_MS
                        deferImp = -1L
                    } else if (deferImp < 0) {
                        trackArmedEnd = armedEnd
                        deferImp = t
                        deferFf = lowGMsBefore(t)
                        deferImpactEnd = armedEnd + IMPACT_WINDOW_MS
                        deferPeak = mag / G
                    }
                }
                if (!candidate) {
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
        if (lastActiveMs > impactT) lastActiveMs = impactT; quietIdx = curIdx + 1
        if (postN == 0 || postActive >= POST_MAX_ACTIVE) {
            fallShape = FallShape(candFfMs, candPeakG, null); eventMs = impactT; impactDue = true
            cand(null, if (postN == 0) "post_none" else "post_active")
            return false
        }
        // If the pre-fall posture is unknown (right after service start), treat it as a posture change — missing a fall is worse
        val deg = if (candPreValid) angleDeg(postX / postN, postY / postN, postZ / postN, candPreX, candPreY, candPreZ) else null
        if (deg != null && !(deg >= POSTURE_DEG)) { // A NaN angle is not a tip-over
            fallShape = FallShape(candFfMs, candPeakG, deg); eventMs = impactT; impactDue = true
            cand(deg, "posture")
            return false
        }
        fallShape = FallShape(candFfMs, candPeakG, deg)
        cand(deg, "")
        return true
    }

    /**
     * Runs the after-impact checks that are due at sample time tMs (each check waits POST_DELAY_MS past its moment so the
     * peaks and the smoothing around it exist), then reports and ends the track at the first result.
     */
    private fun checkPost(tMs: Long) {
        while (trackImp >= 0 && tMs >= trackImp + trackNext + POST_DELAY_MS) {
            val d = trackNext
            val run = walkRun(d)
            val out: String? = if (run >= POST_WALK_RUN) "walk"
            else if (trackFf >= PHONE_FF_MS) {
                // dropped phone or a person from a height: only signs of someone with the phone end it; else the alert at 30 s
                if (lifted()) "lift" else if (d >= POST_T3_MS) "phone30" else null
            } else {
                val tilted = (tiltDeg(d - POST_TILT_SPAN_MS, d) ?: 0.0) >= POST_TILT_DEG
                val std = stdG(d - POST_STILL_SPAN_MS, d)
                val still = std != null && std <= POST_STILL_STD_G
                when {
                    d == POST_T1_MS -> if (tilted && still) "post5" else null
                    d == POST_T2_MS -> if (tilted) "post10" else if (still) "still10" else null
                    d > POST_T2_MS -> if (still) "still" else if (d >= POST_T3_MS) "cap30" else null
                    else -> null
                }
            }
            if (out == null) { trackNext += POST_STEP_MS; continue }
            endTrack(out, d, run)
        }
    }

    /** Reports the running judgment's result and ends it; a waiting impact is judged next after a quiet end, dropped after an alert. */
    private fun endTrack(out: String, d: Long, run: Int) {
        val alert = out != "walk" && out != "lift" && out != "screen"
        val sup = !alert && deferImp >= 0
        val std = stdG(d - POST_STILL_SPAN_MS, d)
        val post = Post(trackImp, out, d, tiltDeg(d - POST_TILT_SPAN_MS, d)?.let { Math.round(it * 10) / 10.0 },
            std?.let { Math.round(it * 1000).toInt() }, run, trackFf, trackPeak, sup)
        trackImp = -1L
        runCatching { onPost(post) }
        if (sup) {
            trackImp = deferImp
            trackFf = deferFf
            trackImpactEnd = deferImpactEnd
            trackPeak = deferPeak
            trackNext = POST_T1_MS
        }
        deferImp = -1L
    }

    /**
     * The screen was unlocked (a secure device; the caller checks). Counts as someone with the phone only after a long free fall
     * and when the phone was moving in the 2 s before (not lying still).
     */
    fun onScreen() {
        if (trackImp < 0 || trackFf < PHONE_FF_MS || ring.size < 2) return
        val now = ring.t(ring.size - 1)
        if (now < trackImp + POST_LIFT_FROM_MS) return
        val sd = stdAbs(now - POST_STILL_SPAN_MS, now) ?: return
        if (sd <= POST_STILL_STD_G) return
        endTrack("screen", now - trackImp, walkRun(now - trackImp))
        checkPost(now)
    }

    /**
     * Lift sign (simulation rise()): from impact+1 s, scanning in half-second steps, a second of stillness (|a| std 0.02 G or less) sets
     * the rest level g0; from the first sample after it that leaves g0 by more than 0.05 G, 1.5 s of integrating (|a| - g0) twice gives the
     * rise in metres; 0.5 m or more is a lift (field: falls up to 0.09 m, a phone picked up at once 1.0-1.7 m).
     */
    private fun lifted(): Boolean {
        val n = ring.size
        if (n < 12) return false
        val dtMs = (ring.t(n - 1) - ring.t(0)).toDouble() / (n - 1)
        val step = maxOf(1, Math.round(1000 / dtMs / 2).toInt())
        var i = idxFrom(trackImp + POST_LIFT_FROM_MS)
        while (i < n) {
            val t0 = ring.t(i)
            var e = i
            var sum = 0.0
            while (e < n && ring.t(e) < t0 + 1000) { sum += ring.m(e); e++ }
            val cnt = e - i
            var still = false
            if (cnt > 10) {
                val mean = sum / cnt
                var sq = 0.0
                for (j in i until e) { val x = ring.m(j) - mean; sq += x * x }
                still = sqrt(sq / cnt) <= POST_STILL_STD_G
            }
            if (still) {
                val g0 = sum / cnt
                var k = e - 1
                while (k < n - 1 && Math.abs(ring.m(k) - g0) <= POST_LIFT_REST_G) k++
                val k0 = k
                var v = 0.0
                var h = 0.0
                for (q in k0 until n) {
                    if (ring.t(q) - ring.t(k0) > POST_LIFT_SPAN_MS) break
                    v += (ring.m(q) - g0) * G * dtMs / 1000
                    h += v * dtMs / 1000
                    if (h >= POST_LIFT_M) return true
                }
                i = k0 + step
            } else i += step
        }
        return false
    }

    /** Index of the first stored sample at or after sensor time tMs. */
    private fun idxFrom(tMs: Long): Int {
        var lo = 0
        var hi = ring.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (ring.t(mid) < tMs) lo = mid + 1 else hi = mid }
        return lo
    }

    /** Angle (degrees) between the pre-fall posture and the mean posture of impact+lo .. impact+hi; null if either has no samples. */
    private fun tiltDeg(lo: Long, hi: Long): Double? {
        val b0 = idxFrom(trackImp - POST_BASE_FROM_MS); val b1 = idxFrom(trackImp - POST_BASE_TO_MS)
        val v0 = idxFrom(trackImp + lo); val v1 = idxFrom(trackImp + hi)
        if (b1 <= b0 || v1 <= v0) return null
        var bx = 0.0; var by = 0.0; var bz = 0.0; var vx = 0.0; var vy = 0.0; var vz = 0.0
        for (i in b0 until b1) { bx += ring.x(i); by += ring.y(i); bz += ring.z(i) }
        for (i in v0 until v1) { vx += ring.x(i); vy += ring.y(i); vz += ring.z(i) }
        return angleDeg(vx, vy, vz, bx, by, bz)
    }

    /** Population standard deviation (g) of |a| over impact+lo .. impact+hi; null under 2 samples. */
    private fun stdG(lo: Long, hi: Long): Double? = stdAbs(trackImp + lo, trackImp + hi)

    /** Population standard deviation (g) of |a| over sensor time from .. to; null under 2 samples. */
    private fun stdAbs(from: Long, to: Long): Double? {
        val i0 = idxFrom(from); val i1 = idxFrom(to)
        val n = i1 - i0
        if (n < 2) return null
        var sum = 0.0
        for (i in i0 until i1) sum += ring.m(i)
        val mean = sum / n
        var sq = 0.0
        for (i in i0 until i1) { val d = ring.m(i) - mean; sq += d * d }
        return sqrt(sq / n)
    }

    /**
     * Longest run of consecutive step gaps (0.3-1.0 s) between acceleration peaks in impact+POST_WALK_FROM_MS .. impact+hi.
     * |a| is smoothed with a centered 5-sample mean; a peak is over 1.15 G, at least as high as both neighbors and more than
     * 0.15 G above the lowest smoothed value within 300 ms either side (the simulation's definition; the first and last
     * sample of the span never count).
     */
    private fun walkRun(hi: Long): Int {
        val n = ring.size
        if (n < 3) return 0
        val i0 = idxFrom(trackImp + POST_WALK_FROM_MS); val i1 = idxFrom(trackImp + hi)
        if (i1 - i0 < 3) return 0
        val avgDt = (ring.t(n - 1) - ring.t(0)).toDouble() / (n - 1)
        val h = maxOf(1, (POST_PEAK_HALF_MS / avgDt).toInt())
        val sm = DoubleArray(n) { i ->
            var s = 0.0
            for (j in i - POST_SMOOTH / 2..i + POST_SMOOTH / 2) if (j in 0 until n) s += ring.m(j)
            s / POST_SMOOTH
        }
        var prevT = Long.MIN_VALUE
        var run = 0
        var best = 0
        for (i in i0 + 1 until i1 - 1) {
            if (!(sm[i] > POST_PEAK_G && sm[i] >= sm[i - 1] && sm[i] >= sm[i + 1])) continue
            var low = Double.MAX_VALUE
            for (j in maxOf(0, i - h) until minOf(n, i + h)) low = minOf(low, sm[j])
            if (!(sm[i] - low > POST_RISE_G)) continue
            val t = ring.t(i)
            if (prevT != Long.MIN_VALUE) {
                run = if (t - prevT in POST_GAP_MIN_MS..POST_GAP_MAX_MS) run + 1 else 0
                best = maxOf(best, run)
            }
            prevT = t
        }
        return best
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

/** Recent accelerometer samples (sensor time ms, g units, |a|) in a ring; the oldest drop first. Index 0 = oldest. */
private class SampleRing(private val cap: Int) {
    private val ts = LongArray(cap)
    private val xs = DoubleArray(cap)
    private val ys = DoubleArray(cap)
    private val zs = DoubleArray(cap)
    private val ms = DoubleArray(cap)
    private var head = 0
    var size = 0
        private set

    private fun at(i: Int) = (head + i) % cap

    fun push(t: Long, x: Double, y: Double, z: Double, m: Double) {
        if (size == cap) { head = (head + 1) % cap; size-- }
        val j = at(size)
        ts[j] = t; xs[j] = x; ys[j] = y; zs[j] = z; ms[j] = m
        size++
    }

    fun dropBefore(tMin: Long) {
        while (size > 0 && ts[head] < tMin) { head = (head + 1) % cap; size-- }
    }

    fun clear() { head = 0; size = 0 }
    fun t(i: Int) = ts[at(i)]
    fun x(i: Int) = xs[at(i)]
    fun y(i: Int) = ys[at(i)]
    fun z(i: Int) = zs[at(i)]
    fun m(i: Int) = ms[at(i)]
}
