package com.wf11.safealert.service

/**
 * Which proximity DANGER alerts get the distance question (SPEC_judgeB 2). Recording only - it never touches a
 * judgment. An alert is asked when it ended within [END_MS] of its start, the same peer was not asked in the last
 * [PEER_MS], and the phone asked fewer than [PER_HOUR] times in the last hour. A walker / cart / admin phone asks at
 * once; an equipment phone holds the question until the vehicle stops ([stopped]), for at most [STOP_MS].
 */
class JudgeAsk {

    data class Ask(val peer: String, val label: String, val alertT: Long, val slot: Int)

    private class Open(val label: String, val start: Long)

    private val open = HashMap<String, Open>()
    private val lastAsked = HashMap<String, Long>()
    private val asked = ArrayDeque<Long>()
    private val held = ArrayList<Pair<Ask, Long>>()
    private var n = 0

    /** A peer's alert rose to DANGER at t. */
    @Synchronized
    fun start(peer: String, label: String, t: Long) {
        open.values.removeAll { t - it.start > END_MS }
        open[peer] = Open(label, t)
    }

    /** The alert ended at t (dropped below DANGER or the peer left). Returns the question to post now, if any. */
    @Synchronized
    fun end(peer: String, t: Long, equipment: Boolean, stopped: Boolean): Ask? {
        val o = open.remove(peer) ?: return null
        if (t - o.start > END_MS) return null
        lastAsked.values.removeAll { t - it >= PEER_MS }
        lastAsked[peer]?.let { if (o.start - it < PEER_MS) return null }
        while (asked.isNotEmpty() && t - asked.first() >= HOUR_MS) asked.removeFirst()
        if (asked.size >= PER_HOUR) return null
        // ponytail: the caps count when the question is decided, so a held ask that later expires still used its slot; count at post time if that matters
        lastAsked[peer] = o.start
        asked.addLast(t)
        val ask = Ask(peer, o.label, o.start, n++ % PER_HOUR)
        if (!equipment || stopped) return ask
        held.add(ask to t)
        return null
    }

    /** The equipment phone stopped at t: the held questions that ended within [STOP_MS] go out now. */
    @Synchronized
    fun stopped(t: Long): List<Ask> {
        val out = held.filter { t - it.second <= STOP_MS }.map { it.first }
        held.clear()
        return out
    }

    companion object {
        const val END_MS = 120_000L
        const val PEER_MS = 30 * 60 * 1000L
        const val HOUR_MS = 60 * 60 * 1000L
        const val PER_HOUR = 4
        const val STOP_MS = 30 * 60 * 1000L
    }
}
