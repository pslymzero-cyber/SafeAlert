package com.wf11.safealert.service

/**
 * Pauses no-motion counting while a peer siren vibrates on this device (pure).
 * Start and end are the first tick that observes them.
 * Cap: once the pause reaches stillMs, the baseline is moved as if the pause ended at the cap time, even if the siren keeps
 * sounding (time accrued before the pause is kept), and it does not pause again until this device's siren vibration turns
 * off once. Turning on again starts a new pause.
 */
class SirenPause {
    /** Pause start time; MIN_VALUE when not paused. */
    private var at = Long.MIN_VALUE
    /** The cap ended the pause and the vibration has not turned off yet. */
    private var spent = false

    private val active: Boolean get() = at != Long.MIN_VALUE

    /**
     * The pause masks this deadline — a past deadline at or before the pause start is not masked (it is judged once its data arrives).
     */
    fun covers(deadline: Long): Boolean = active && deadline > at

    fun reset() {
        at = Long.MIN_VALUE
        spent = false
    }

    /**
     * No-motion baseline as if the pause ended now (at the cap time if the cap comes first): keeps only the time accrued before
     * the pause (from the end time if the baseline rose during the pause). A deadline the
     * pause does not mask (passed before the pause) keeps the baseline.
     */
    fun base(stillBase: Long, nowMs: Long, stillMs: Long): Long =
        if (!covers(stillBase + stillMs)) stillBase
        else maxOf(stillBase, minOf(nowMs, at + stillMs) - maxOf(0L, at - stillBase))

    /**
     * Starts the pause when vibration turns on; ends it when vibration turns off or the cap is reached. Returns the new no-motion baseline.
     */
    fun update(vibrating: Boolean, nowMs: Long, stillBase: Long, stillMs: Long): Long {
        if (!vibrating) spent = false
        if (vibrating && !active && !spent) {
            at = nowMs
        } else if (active && (!vibrating || nowMs - at >= stillMs)) {
            val b = base(stillBase, nowMs, stillMs)
            at = Long.MIN_VALUE
            spent = vibrating
            return b
        }
        return stillBase
    }
}

/**
 * Holds after a service restart (pure).
 * Power hold: if the saved charging value differs from the current power, then from restart to restart + POWER_HOLD_MS no new
 * check window opens and no restored check window is shown. If the debounce confirms, the hold ends at that confirm time
 * (first change + DEBOUNCE_MS), otherwise at the window end — if a pending change that started inside the window remains at
 * the window end, the hold is extended once until that change is confirmed or discarded (at the latest before window end +
 * CONFIRM_MS; a pending change that started after the window end never extends it). The restart change is the single first
 * change confirmed during the hold; later changes are real changes even inside the window.
 * A restored window is held by kind only and opens with the hold end time as its step-count floor once sensor data up to
 * that time has arrived (at most LATE_MS) — a held window is discarded by the same rules as an open one. Meanwhile other
 * deadlines are judged together with that window.
 * Zone hold: how long the restored inside-safe-zone state is kept without a zone report. No promotion to settled during the hold.
 */
class RestartHold {
    companion object {
        /** Restart power window length: debounce confirm delay (CONFIRM_MS) + 1 s margin. */
        const val POWER_HOLD_MS = PowerDebounce.CONFIRM_MS + 1_000L
    }

    /** Power hold end (moved by extension, confirmation or discard). The time is kept after the hold ends. */
    private var powerUntil = Long.MIN_VALUE
    private var zoneUntil = Long.MIN_VALUE
    /** Restart power window end — reference for the extension; cleared on the first confirmation. */
    private var powerWindowEnd = Long.MIN_VALUE

    /** Kind of the restored check window held during the power hold ("still" or "fall"); empty string if none. */
    var check = ""
        private set

    fun reset() {
        powerUntil = Long.MIN_VALUE
        zoneUntil = Long.MIN_VALUE
        powerWindowEnd = Long.MIN_VALUE
        check = ""
    }

    fun holdPower(nowMs: Long) {
        powerUntil = nowMs + POWER_HOLD_MS
        powerWindowEnd = powerUntil
    }

    fun holdCheck(kind: String) {
        check = kind
    }

    fun powerHeld(nowMs: Long): Boolean = nowMs < powerUntil

    /**
     * Gate for new check windows (single place): while the power hold lasts or a restored window is held, the hold end time
     * (a future time during the hold; once it ended, the deadline that opens that window), otherwise null. Judge timing: JudgeOrder.due.
     */
    fun gate(nowMs: Long): Long? = powerUntil.takeIf { powerHeld(nowMs) || check.isNotEmpty() }

    /**
     * A raw value changed the pending debounce — only during the hold, re-set the end (to that
     * change's confirm check if it started inside the window, otherwise to now).
     */
    fun powerWait(p: PowerDebounce, tMs: Long) {
        if (!powerHeld(tMs)) return
        powerUntil = maxOf(powerWindowEnd, p.confirmAt?.takeIf { p.pendingAt < powerWindowEnd } ?: tMs)
    }

    /**
     * Power change confirmed by the debounce (atMs = first change time) — true (restart change) if it is the first
     * confirmation and the first change is inside the window; the hold ends at atMs + DEBOUNCE_MS.
     */
    fun powerSettled(atMs: Long): Boolean {
        val restart = inWindow(atMs)
        powerWindowEnd = Long.MIN_VALUE
        if (restart) powerUntil = atMs + PowerDebounce.DEBOUNCE_MS
        return restart
    }

    /** A change that started inside the restart window — until the first confirmation. */
    fun inWindow(atMs: Long): Boolean = atMs < powerWindowEnd

    /** Returns the held check window kind once and clears it. */
    fun takeCheck(): String? = check.ifEmpty { null }?.also { check = "" }

    /** Discards the held check window (only that kind if kind is given). */
    fun dropCheck(kind: String) {
        if (kind.isEmpty() || check == kind) check = ""
    }

    fun holdZone(untilMs: Long) {
        zoneUntil = untilMs
    }

    val zoneHeld: Boolean get() = zoneUntil != Long.MIN_VALUE

    fun zoneReported() {
        zoneUntil = Long.MIN_VALUE
    }

    /** If the zone hold limit has passed, returns its end time once and clears it. */
    fun zoneExpired(nowMs: Long): Long? =
        if (zoneHeld && nowMs >= zoneUntil) zoneUntil.also { zoneUntil = Long.MIN_VALUE } else null
}
