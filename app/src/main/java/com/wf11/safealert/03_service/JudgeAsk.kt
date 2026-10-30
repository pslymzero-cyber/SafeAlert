package com.wf11.safealert.service

import com.wf11.safealert.ble.BleConstants

/**
 * Which proximity alerts get the distance question (SPEC_judgeE). Recording only - it never touches a judgment.
 * Every WARNING or DANGER start is asked ([alertStart], user decision 2026-10-30: every alert, all phones, no per-peer
 * or per-hour cap), one question at a time, each question open for [OPEN_MS] from the alert start. An alert that starts
 * while another question is still open is not asked (never queued).
 */
class JudgeAsk {

    /** start = the values of the alert-start moment (JudgeLog.markDanger + start_lv); shownAt = first shown time, null = not yet shown. */
    data class Ask(
        val peer: String,
        val label: String,
        val alertT: Long,
        val start: Map<String, Any?>?,
        val shownAt: Long? = null,
    )

    private var cur: Ask? = null

    val isOpen: Boolean @Synchronized get() = cur != null

    /** A peer's alert rose to WARNING or DANGER at t. True = a question is now open. */
    @Synchronized
    fun start(peer: String, label: String, t: Long, start: Map<String, Any?>?): Boolean {
        cur?.let { if (t - it.alertT <= OPEN_MS) return false }   // one at a time, never queued
        cur = Ask(peer, label, t, start)
        return true
    }

    /**
     * The open question if its block should be on screen at t, else null. Closes it once [OPEN_MS] has passed.
     * canShow false only hides it (the question stays open). The first visible call stamps shownAt.
     */
    @Synchronized
    fun visible(t: Long, canShow: Boolean): Ask? {
        val a = cur ?: return null
        if (t - a.alertT > OPEN_MS) { cur = null; return null }
        if (!canShow) return null
        if (a.shownAt != null) return a
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

        /** A rise into WARNING or DANGER (a new alert sounds); a fall back down is not asked. */
        fun alertStart(before: Int, after: Int) = after >= BleConstants.LEVEL_WARNING && after > before

        /**
         * Whether the block may be on screen now. An equipment phone (forklift / EPJ, not a cart) only while stopped;
         * walker, cart and admin phones at once. Without the sidebar on screen nothing can be shown.
         */
        fun showNow(category: Int, cart: Boolean, stationary: Boolean, sidebar: Boolean) =
            sidebar && (stationary || cart || (category != BleConstants.CAT_EPJ && category != BleConstants.CAT_FORKLIFT))
    }
}
