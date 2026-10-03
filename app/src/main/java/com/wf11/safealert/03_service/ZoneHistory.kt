package com.wf11.safealert.service

/**
 * Raw safe-zone inside/outside history. A fall is judged by the raw safe-zone state at the impact moment (trigMs), not when FALL arrives.
 * Marks are stacked in time order; only the single state in effect KEEP_MS ago is kept and older marks are dropped.
 */
internal class ZoneHistory {
    private val marks = ArrayDeque<Pair<Long, Boolean>>()

    /** State of the last mark (outside if there are no marks). */
    val inside: Boolean get() = marks.lastOrNull()?.second ?: false

    /** Time of the last mark (0 if none). If inside, this is the entry time. */
    val since: Long get() = marks.lastOrNull()?.first ?: 0L

    fun reset(inside: Boolean, t: Long) {
        marks.clear()
        marks.addLast(t to inside)
    }

    fun mark(inside: Boolean, t: Long) {
        marks.addLast(t to inside)
        while (marks.size >= 2 && marks[1].first <= t - KEEP_MS) marks.removeFirst()
    }

    /** State in effect at t: the last mark at or before t; if t precedes every mark, the oldest mark. */
    fun insideAt(t: Long): Boolean = (marks.lastOrNull { it.first <= t } ?: marks.firstOrNull())?.second ?: false

    /**
     * Inside at the impact moment (t) and no exit confirmed within EXIT_LAG_MS after
     * it (after t, up to t + EXIT_LAG_MS) — the zone check for falls.
     */
    fun insideAtImpact(t: Long): Boolean = insideAt(t) && marks.none { !it.second && it.first > t && it.first <= t + EXIT_LAG_MS }

    companion object {
        /** FALL arrives 12~17 s after the impact, plus batched delivery and JudgeOrder deferral — 2 minutes to be safe. */
        const val KEEP_MS = 120_000L
        /**
         * BleService confirms an exit about 10 s late (3 weak samples or 10 s without
         * reception; the restart zone hold also ends at 10 s). So an exit confirmed
         * within 12 s after the impact counts as already outside at impact. Must be <=
         * MotionAnalyzer.POST_END_MS so that FALL arrives after this window has passed.
         */
        const val EXIT_LAG_MS = 12_000L
    }
}
