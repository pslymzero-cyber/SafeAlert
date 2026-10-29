package com.wf11.safealert.service

import com.wf11.safealert.ble.BleConstants

/**
 * Which proximity DANGER alerts get the distance question (SPEC_judgeE). Recording only - it never touches a judgment.
 * Only a DANGER start is asked ([dangerStart]), one question at a time, each question open for [OPEN_MS] from the
 * alert start. The same peer is asked at most once per [PEER_MS] and the phone at most [PER_HOUR] times per hour; both
 * caps are counted when the block is first shown ([visible]), not when the question opens, so an alert that never
 * got a visible block (equipment phone that never stopped, no sidebar) uses no slot.
 */
class JudgeAsk {

    /** start = the values of the DANGER-start moment (JudgeLog.markDanger); shownAt = first shown time, null = not yet shown. */
    data class Ask(
        val peer: String,
        val label: String,
        val alertT: Long,
        val start: Map<String, Any?>?,
        val shownAt: Long? = null,
    )

    private var cur: Ask? = null
    private val lastAsked = HashMap<String, Long>()
    private val asked = ArrayDeque<Long>()

    val isOpen: Boolean @Synchronized get() = cur != null

    /** A peer's alert rose to DANGER at t. True = a question is now open (not yet counted). */
    @Synchronized
    fun start(peer: String, label: String, t: Long, start: Map<String, Any?>?): Boolean {
        cur?.let { if (t - it.alertT <= OPEN_MS) return false }   // one at a time, never queued
        lastAsked.values.removeAll { t - it >= PEER_MS }
        while (asked.isNotEmpty() && t - asked.first() >= HOUR_MS) asked.removeFirst()
        if (lastAsked.containsKey(peer) || asked.size >= PER_HOUR) return false
        cur = Ask(peer, label, t, start)
        return true
    }

    /**
     * The open question if its block should be on screen at t, else null. Closes it once [OPEN_MS] has passed.
     * canShow false only hides it (the question stays open). The first visible call counts the two caps.
     */
    @Synchronized
    fun visible(t: Long, canShow: Boolean): Ask? {
        val a = cur ?: return null
        if (t - a.alertT > OPEN_MS) { cur = null; return null }
        if (!canShow) return null
        if (a.shownAt != null) return a
        lastAsked[a.peer] = t
        asked.addLast(t)
        return a.copy(shownAt = t).also { cur = it }
    }

    /** An answer at t. Returns the closed question, or null if none is open, it was never shown, or it timed out. */
    @Synchronized
    fun answer(t: Long): Ask? {
        val a = cur ?: return null
        if (a.shownAt == null) return null
        cur = null
        return if (t - a.alertT > OPEN_MS) null else a
    }

    companion object {
        const val OPEN_MS = 30_000L
        const val PEER_MS = 30 * 60 * 1000L
        const val HOUR_MS = 60 * 60 * 1000L
        const val PER_HOUR = 4

        /** Only a rise to DANGER is asked; a warning is not. */
        fun dangerStart(before: Int, after: Int) = before < BleConstants.LEVEL_DANGER && after == BleConstants.LEVEL_DANGER

        /**
         * Whether the block may be on screen now. An equipment phone (forklift / EPJ, not a cart) only while stopped;
         * walker, cart and admin phones at once. Without the sidebar on screen nothing can be shown.
         */
        fun showNow(category: Int, cart: Boolean, stationary: Boolean, sidebar: Boolean) =
            sidebar && (stationary || cart || (category != BleConstants.CAT_EPJ && category != BleConstants.CAT_FORKLIFT))
    }
}
