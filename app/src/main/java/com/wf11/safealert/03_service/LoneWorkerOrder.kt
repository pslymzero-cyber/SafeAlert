package com.wf11.safealert.service

/**
 * Judge-order unit (pure). Keeps the order of deadline judgments, power change application and sensor input in this one
 * place — the same inputs (sensor values, raw power values and their times) give the same judgment regardless of when sensor
 * batches arrive or when check ticks run.
 *
 * Rule 1: a deadline is judged after every power change that started before it (same time included) has been confirmed or
 * discarded and applied. If all data up to the deadline has arrived and only a pending power change blocks it, sensor input
 * arriving afterwards is held per callback in arrival order and replayed after the judgment.
 * Rule 2: a power change that started later than a passed, not-yet-judged deadline is applied after that judgment even once
 * stable for 2 s (effective time = first change time). Changes that started inside the restart window do not wait.
 * Rule 3: a deadline is judged after sensor data up to that time has arrived (or after LATE_MS).
 * No deadlock: a change P blocks deadlines D ≥ P and a deadline D blocks changes P > D, so they are strictly ordered by time;
 * the only remaining block is a debounce pending for under 2 s, released at the confirm check (confirmAt) or on discard.
 */
class JudgeOrder(
    private val hold: RestartHold,
    private val deadlines: (Long) -> List<Long>,
    private val sensedTo: () -> Long,
    private val apply: (Boolean, Long) -> Unit
) {
    val power = PowerDebounce()
    /** Power changes stable for 2 s but not yet applied to the logic (value, first change time) — applied in order. */
    private val stable = ArrayDeque<Pair<Boolean, Long>>()
    /** Held sensor input. end marks the end of one sensor callback. */
    private val held = ArrayDeque<() -> Unit>()
    private val end: () -> Unit = {}
    /** Holding sensor input because a deadline is blocked only by a pending power change. */
    var holding = false
        private set

    fun reset(charging: Boolean) {
        power.seed(charging)
        stable.clear()
        held.clear()
        holding = false
    }

    /** A power change that started at or before at has not been applied yet (pending, or stable but deferred). */
    private fun unappliedBy(at: Long): Boolean = power.pendingBy(at) || stable.any { it.second <= at }

    /** Passed, and data up to the deadline has arrived or LATE_MS has elapsed. */
    private fun ready(at: Long, nowMs: Long): Boolean =
        nowMs >= at && (sensedTo() >= at || nowMs >= at + LoneWorkerLogic.LATE_MS)

    /** Whether deadline at may be judged now (single judge-time gate). */
    fun due(at: Long, nowMs: Long): Boolean = ready(at, nowMs) && !unappliedBy(at)

    /** Some deadline has its data ready but is blocked only by a pending power change. */
    private fun blocked(nowMs: Long): Boolean = (power.pending || stable.isNotEmpty()) &&
        deadlines(nowMs).any { ready(it, nowMs) && unappliedBy(it) }

    /**
     * Next time a tick is needed: the earliest time after now among deadlines (the deadline
     * itself if not yet due, LATE_MS after it if passed) and confirm checks.
     */
    fun nextCheckAt(nowMs: Long): Long? = (deadlines(nowMs).map { if (nowMs < it) it else it + LoneWorkerLogic.LATE_MS } +
        listOfNotNull(power.confirmAt)).filter { it > nowMs }.minOrNull()

    fun waitingToJudge(nowMs: Long): Boolean = deadlines(nowMs).any { nowMs >= it && !due(it, nowMs) }

    fun waitingOnSensors(nowMs: Long): Boolean =
        deadlines(nowMs).any { nowMs >= it && !due(it, nowMs) && sensedTo() < it }

    fun dueNow(nowMs: Long): Boolean = deadlines(nowMs).any { due(it, nowMs) }

    /**
     * Takes changes stable for 2 s out of the debounce and applies, in order, those needing no deferral (rule 2). True if any was applied.
     */
    fun settle(nowMs: Long): Boolean {
        power.poll(nowMs)?.let { stable.addLast(it) }
        var applied = false
        while (true) {
            val (on, at) = stable.firstOrNull() ?: break
            if (!hold.inWindow(at) && deadlines(nowMs).any { nowMs >= it && it < at }) break
            stable.removeFirst()
            apply(on, at)
            applied = true
        }
        return applied
    }

    /**
     * Raw power value: first takes out and applies stable changes (so an opposite value cannot discard them
     * as bounce), then feeds the raw value. True if applied or the pending state changed.
     */
    fun raw(on: Boolean, tMs: Long, sticky: Boolean): Boolean {
        val settled = settle(tMs)
        val changed = power.raw(on, tMs, sticky)
        if (changed) hold.powerWait(power, tMs)
        return settled || changed
    }

    /** While holding, stores the sensor input and returns true (the caller does not apply it). */
    fun keep(input: () -> Unit): Boolean {
        if (holding) held.addLast(input)
        return holding
    }

    /** One sensor callback finished: if blocked, start or keep holding; if unblocked, replay after the judgment. */
    fun eventEnd(nowMs: Long, decide: (Long) -> Unit) {
        if (holding && held.lastOrNull() !== end) held.addLast(end)
        release(nowMs, decide)
    }

    /** Tick judgment: judge → apply released deferred changes → judge again … then replay held input. */
    fun judge(nowMs: Long, decide: (Long) -> Unit) {
        decideAll(nowMs, decide)
        release(nowMs, decide)
    }

    private fun decideAll(nowMs: Long, decide: (Long) -> Unit) {
        do decide(nowMs) while (settle(nowMs))
    }

    /** If blocked, hold (start / keep); if unblocked, repeat (judge → replay one callback) and judge once more at the end. */
    private fun release(nowMs: Long, decide: (Long) -> Unit) {
        if (!holding) {
            holding = blocked(nowMs)
            return
        }
        while (true) {
            holding = blocked(nowMs)
            if (holding) return
            decideAll(nowMs, decide)
            if (held.isEmpty()) return
            while (true) {
                val input = held.removeFirstOrNull() ?: break
                if (input === end) break
                input()
            }
        }
    }
}
