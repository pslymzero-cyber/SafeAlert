package com.wf11.safealert.service

/** Channel through which 'alive' records reach the server. The Android implementation is in LoneWorkerSosSync. */
interface HbRemote {
    /** Anonymous sign-in uid. null before sign-in. */
    fun uid(): String?
    /** "{root}/hb/{center code}". null if there is no site code. */
    fun path(): String?
    fun newKey(path: String): String
    /**
     * Estimated server time (wall clock if unknown). Stamped when the event happens, so a late delivery does not change the time.
     */
    fun serverNow(): Long
    /** floor / proc fields written once at session start (empty = none). */
    fun scope(): Map<String, Any> = emptyMap()
    /** Partially updates the session node. done is called on the main thread with the server's confirmation result. */
    fun update(path: String, key: String, fields: Map<String, Any>, done: (Boolean) -> Unit)
}

/**
 * 'Alive' session record during lone-worker monitoring. Pure logic with no Android dependency; every entry point is on the main thread.
 * While monitoring is on, opens one session ({uid, role, start, last}), updates last every 5 min, and writes end on a normal stop.
 * If more than 15 min pass between successful server writes, records the gap g/{n} = {from, to}, up to 50 per session.
 * Stores no names or device identifiers. Recording only — no mail or notifications (unrelated to alerts/decisions).
 * If a start or update is rejected, opens a new session 5 min later and clears the carry-over. Failed end/gap writes are ignored.
 * If the process died without an end mark and restarts with the same uid and same center within 15 min of the last sent
 * start/update stamp, it inherits the previous success server time. This baseline is set at the new session's first success
 * response (or end), so a gap spanning the restart is recorded too (cleared on a normal stop).
 */
class LoneWorkerHeartbeat(private val remote: HbRemote, private val kv: SosKv, private val clock: () -> Long) {
    companion object {
        const val INTERVAL_MS = 5 * 60_000L
        const val GAP_MS = 15 * 60_000L
        const val MAX_GAPS = 50
        /**
         * Carry-over value stored on the device: 'path \t baseline server time (last
         * success; before the first success, session start or the inherited value)
         * \t server time of the last sent start/update \t uid'.
         */
        const val K_CARRY = "hb.carry"
        private val ROLES = setOf("WALKER", "EPJ", "FORKLIFT", "UNKNOWN")
    }

    private var key: String? = null
    private var path = ""
    private var role = ""
    private var uid = ""
    private var lastTry = 0L
    private var failedAt: Long? = null
    private var okAt = 0L       // clock of last success (session start at first; if inherited, re-set at ok/end)
    private var okServer = 0L   // estimated server time at that moment
    // Estimated server time of the last sent start/update — equals the server's
    // last, so it matches the aggregation's restart-pairing criterion
    private var sentAt = 0L
    // Inherited last-success server time. Becomes the baseline at the first success where the server time is known
    private var carried: Long? = null
    private var gaps = 0
    private var gen = 0

    /** Called every monitoring tick (10s). on = whether lone-worker monitoring is on. */
    fun tick(on: Boolean, role: String) {
        if (!on) { end(); return }
        val r = if (role in ROLES) role else "UNKNOWN"
        val p = remote.path()
        if (key != null && (p != path || r != this.role)) end()
        val t = clock()
        val k = key
        if (k == null) { start(p, r, t); return }
        if (t - lastTry < INTERVAL_MS) return
        lastTry = t
        sentAt = remote.serverNow()
        keep()
        write(k, mapOf("last" to sentAt))
    }

    /** Normal stop ("중지", monitoring turned off, role or site change). Does not wait for the result. */
    fun end() {
        keep(null) // clear carry-over even when stopping with no session (after a rejected update)
        val k = key ?: return
        key = null
        gen++
        val s = remote.serverNow()
        val f = mutableMapOf<String, Any>("last" to s, "end" to s)
        // Even if it ends in a dead zone, record the interval the server missed in the same write as the end
        gap(clock(), s)?.let { f += it }
        remote.update(path, k, f) {}
    }

    private fun start(p: String?, r: String, t: Long) {
        if (p == null) return
        failedAt?.let { if (t - it < INTERVAL_MS) return }
        val u = remote.uid() ?: return
        val k = remote.newKey(p)
        val s = remote.serverNow()
        key = k; path = p; role = r; uid = u
        lastTry = t; okAt = t; okServer = s; gaps = 0; failedAt = null; sentAt = s; carried = null
        // Inherit the success server time of the previous session that stopped without an end mark (same uid, same center,
        // within 15 min of the last sent stamp). okServer is changed at once so the same baseline carries
        // over even if it dies again before the first success; the baseline time is set in gap()
        val c = kv.get(K_CARRY)?.split('\t')
        val ok = c?.getOrNull(1)?.toLongOrNull()
        val last = c?.getOrNull(2)?.toLongOrNull()
        if (c?.size == 4 && c[0] == p && c[3] == u && ok != null && ok > 0 && last != null && s - last <= GAP_MS) {
            okServer = ok
            carried = ok
        }
        keep()
        val base = mapOf<String, Any>("uid" to u, "role" to r, "start" to s, "last" to s)
        val scope = remote.scope()
        val g = ++gen
        remote.update(p, k, base + scope) { sent ->
            // Rules that predate floor/proc refuse the scoped start: go again without them, but only while this session is
            // still the current one (after end() a late retry would create a session nobody ends).
            if (!sent && scope.isNotEmpty() && g == gen) remote.update(p, k, base, answer(g, k)) else answer(g, k)(sent)
        }
    }

    private fun write(k: String, fields: Map<String, Any>) = remote.update(path, k, fields, answer(gen, k))

    // Start/update result. Stale generations are ignored; if rejected, start over with a new key 5 min later
    private fun answer(g: Int, k: String): (Boolean) -> Unit = { ok ->
        if (g == gen) {
            if (ok) onOk(k) else {
                key = null; gen++; failedAt = clock()
                keep(null) // a rejected write's stamp never reached the server, so it is not carried over
            }
        }
    }

    // If more than 15 min pass between successes, record that interval (also catches writes queued offline that arrived late)
    private fun onOk(k: String) {
        val t = clock()
        val s = remote.serverNow()
        gap(t, s)?.let {
            remote.update(path, k, mapOf(it)) {}
            gaps++
        }
        okAt = t
        okServer = s
        keep()
    }

    // The inherited baseline is set at the first success where the server time is known (onOk) or at the end (end)
    private fun gap(t: Long, s: Long): Pair<String, Any>? {
        carried?.let { okAt = t - (s - it); carried = null }
        return if (t - okAt > GAP_MS && gaps < MAX_GAPS) "g/$gaps" to mapOf("from" to okServer, "to" to s) else null
    }

    // Writes only on change (apply). If the process dies before handling the response or this write is lost, a baseline one
    // cycle early remains, and a restart may record one false gap (the device can't tell whether the server got the write)
    private fun keep(v: String? = "$path\t$okServer\t$sentAt\t$uid") {
        if (kv.get(K_CARRY) != v) kv.put(mapOf(K_CARRY to v))
    }
}
