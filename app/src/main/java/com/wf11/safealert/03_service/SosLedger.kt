package com.wf11.safealert.service

/** String key-value store. put applies all changes at once and removes null values. */
interface SosKv {
    fun get(k: String): String?
    fun put(changes: Map<String, String?>)
}

/**
 * Server transport. Implementations deliver callbacks on the main thread.
 * uid() null = not signed in yet; sitePath() null = no site code.
 */
interface SosTransport {
    fun uid(): String?
    fun sitePath(): String?
    fun newKey(path: String): String
    fun create(path: String, key: String, rec: SosLedger.Record, uid: String, done: (Boolean) -> Unit)
    /** auto = released by the one-hour limit rather than by "괜찮아요" (recorded on the server as reason auto). */
    fun resolve(path: String, key: String, auto: Boolean, done: (Boolean) -> Unit)
    fun read(path: String, key: String, done: (SosLedger.Remote) -> Unit)
}

/**
 * Storage and send state machine for my own SOS. Pure logic with no Android dependency; every entry point is on the main thread.
 *
 * The active slot (a.*) and the pending-resolve list (r.list) are kept separately. Pressing "괜찮아요" empties the active slot
 * on the spot, and the resolve is retried from the pending list until confirmed, so a new SOS raised right after an offline
 * resolve is recorded immediately and a late response to the old resolve removes only its own entry.
 *
 * When a write fails, read once. If the SDK resends a write left on disk after a restart, the
 * record already exists on the server and re-creating it under the same key is blocked by the
 * create-only rule. If a record with my uid already exists, it counts as sent.
 * Other failures are retried with a backoff that doubles from 10 s (max 5 min). Fields the rules do not allow are never written.
 * A failed resolve keeps its slot and keeps resending while the record is still alive.
 * onSaved fires the moment the server save is confirmed — the SOS mail starts here.
 * If a resolve is confirmed for a record whose SOS was not yet confirmed, the SOS is reported first; the
 * script then sends the late SOS mail as "해제됨". Already-confirmed records report only the resolve.
 * An SOS whose server record has been up AUTO_RELEASE_MS is released automatically (reason auto on the server); one the
 * server never confirmed is not, since nobody was told about it. Its resolve mail is not sent: that mail tells the site the
 * worker pressed "괜찮아요", which an automatic release is not.
 */
class SosLedger(
    private val kv: SosKv,
    private val transport: SosTransport,
    private val clock: () -> Long,
    private val onSaved: (event: String, path: String, key: String) -> Unit = { _, _, _ -> },
    private val onChange: () -> Unit = {},
    private val wallClock: () -> Long = System::currentTimeMillis
) {
    data class Record(
        val bleId: String, val name: String, val role: String, val trigger: String,
        val beacon: String?, val beaconRssi: Int?, val sid: Int, val ep: Int = 0
    )

    enum class Remote { ABSENT, MINE_ACTIVE, MINE_RESOLVED, OTHER, ERROR }

    companion object {
        /** Storage keys. Prefixed so differently typed keys left by earlier dev builds are never read. Present = active. */
        const val K_TRIGGER = "a.trigger"
        private const val K_BLE = "a.ble"
        private const val K_NAME = "a.name"
        private const val K_ROLE = "a.role"
        private const val K_BEACON = "a.beacon"
        private const val K_RSSI = "a.rssi"
        private const val K_SID = "a.sid"
        private const val K_EP = "a.ep"
        private const val K_KEY = "a.key"
        private const val K_PATH = "a.path"
        private const val K_SENT = "a.sent"
        private const val K_AT = "a.at"   // wall-clock time the server confirmed the SOS (survives restarts and reboots)
        private const val K_PENDING = "r.list"
        private const val K_EP_LAST = "ep.last"
        private val ACTIVE_KEYS = listOf(
            K_TRIGGER, K_BLE, K_NAME, K_ROLE, K_BEACON, K_RSSI, K_SID, K_EP, K_KEY, K_PATH, K_SENT, K_AT
        )

        /**
         * An SOS still active this long after its server record was made is released automatically, on the SOS phone and on
         * every other phone: the site was alerted and mailed long before, and a rescue has come and gone. The database rules
         * hold the same number for the other phones' server record.
         */
        const val AUTO_RELEASE_MS = 60 * 60_000L

        const val STATUS_SENT = "서버 전송됨"
        const val STATUS_SENDING = "서버 전송 중"
        const val STATUS_FAILED = "서버 전송 실패 — 재시도 중"

        private const val BACKOFF_BASE_MS = 10_000L
        private const val BACKOFF_MAX_MS = 300_000L

        /** Retry delay by failure count (from 1): 10 s, 20 s, 40 s ... up to 5 min. */
        fun backoffMs(failures: Int): Long {
            if (failures <= 1) return BACKOFF_BASE_MS
            return minOf(BACKOFF_MAX_MS, BACKOFF_BASE_MS shl minOf(failures - 1, 20))
        }

        /** Next episode number: 1..255; 0 means none and is never used, and 255 wraps to 1. */
        fun nextEpisode(prev: Int): Int = (prev.coerceAtLeast(0) % 255) + 1
    }

    /**
     * sent = whether the SOS was confirmed on the server at resolve time (set later if the create confirmation arrives late);
     * auto = released by the one-hour limit.
     */
    private class Pending(val path: String, val key: String, val sent: Boolean, val auto: Boolean = false)

    // In-flight state, failures and next-allowed time live in memory only (a restart
    // retries immediately). Create and resolve are counted separately.
    private val createBusy = HashSet<String>()
    private val createFails = HashMap<String, Int>()
    private val createNext = HashMap<String, Long>()
    private val resolveBusy = HashSet<String>()
    private val resolveFails = HashMap<String, Int>()
    private val resolveNext = HashMap<String, Long>()
    /** clock() when the active SOS's server record was confirmed: set then, or anchored from a.at once after a restart. */
    private var sentAt: Long? = null

    fun hasActive(): Boolean = kv.get(K_TRIGGER) != null
    fun restoredTrigger(): String? = kv.get(K_TRIGGER)
    fun episode(): Int = kv.get(K_EP)?.toIntOrNull() ?: 0
    fun hint(): Int = kv.get(K_SID)?.toIntOrNull() ?: 0

    /**
     * How long the active SOS's server record has been up, or null without one or before the server confirmed it. Counted
     * on clock(), so a wall-clock change while running is ignored; across a restart the gap comes from the wall clock (a
     * backward step counts as none). A record confirmed before a.at was kept counts from now.
     */
    fun activeForMs(): Long? {
        if (!hasActive() || kv.get(K_SENT) == null) return null
        val at = sentAt ?: run {
            val wallAt = kv.get(K_AT)?.toLongOrNull() ?: wallClock().also { kv.put(mapOf(K_AT to it.toString())) }
            clock() - (wallClock() - wallAt).coerceAtLeast(0L)
        }.also { sentAt = it }
        return clock() - at
    }

    /** SOS entered. If a stored SOS (restored) already exists, keep it so no second record is created. */
    fun begin(rec: Record) {
        if (hasActive()) return
        val ep = nextEpisode(kv.get(K_EP_LAST)?.toIntOrNull() ?: 0)
        val ch = HashMap<String, String?>()
        for (k in ACTIVE_KEYS) ch[k] = null
        ch[K_TRIGGER] = rec.trigger
        ch[K_BLE] = rec.bleId
        ch[K_NAME] = rec.name
        ch[K_ROLE] = rec.role
        ch[K_BEACON] = rec.beacon
        ch[K_RSSI] = rec.beaconRssi?.toString()
        ch[K_SID] = rec.sid.toString()
        ch[K_EP] = ep.toString()
        ch[K_EP_LAST] = ep.toString()
        kv.put(ch)
        sentAt = null
        trySend()
        onChange()
    }

    fun tick() {
        trySend()
        sendResolves()
    }

    /** Server send status text. null when I have no SOS. */
    fun statusText(): String? {
        if (!hasActive()) return null
        if (kv.get(K_SENT) != null) return STATUS_SENT
        val key = kv.get(K_KEY)
        if (key != null && key in createBusy && (createFails[key] ?: 0) == 0) return STATUS_SENDING
        return STATUS_FAILED
    }

    /**
     * Resolve: "괜찮아요", or auto = the one-hour limit. Empties the active slot at once and moves the resolve to the pending
     * list.
     */
    fun resolve(auto: Boolean = false) {
        if (!hasActive()) return
        val key = kv.get(K_KEY)
        val path = kv.get(K_PATH)
        val ch = HashMap<String, String?>()
        for (k in ACTIVE_KEYS) ch[k] = null
        if (key != null && path != null) {
            val list = pending()
            if (list.none { it.key == key && it.path == path }) {
                ch[K_PENDING] = encode(list + Pending(path, key, kv.get(K_SENT) != null, auto))
            }
        }
        kv.put(ch)
        sentAt = null
        sendResolves()
        onChange()
    }

    /** Whether any resolve is being resent because it has not reached the server. */
    fun resolveFailing(): Boolean = pending().any { (resolveFails[it.key] ?: 0) > 0 }

    // ── Create ──────────────────────────────────────────────────

    private fun trySend() {
        if (!hasActive() || kv.get(K_SENT) != null) return
        var key = kv.get(K_KEY)
        var path = kv.get(K_PATH)
        if (key == null || path == null) {
            val p = transport.sitePath() ?: return
            if (transport.uid() == null) return
            path = p
            key = transport.newKey(p)
            kv.put(mapOf(K_PATH to path, K_KEY to key))
        }
        val uid = transport.uid() ?: return
        if (key in createBusy || (createNext[key] ?: 0L) > clock()) return
        createBusy.add(key)
        val sentKey = key
        val sentPath = path
        transport.create(sentPath, sentKey, currentRecord(), uid) { ok ->
            if (ok) {
                createBusy.remove(sentKey)
                onCreated(sentPath, sentKey)
                onChange()
            } else {
                // If my record already exists on the server (a pre-restart write landed late), don't write again; treat it as sent
                transport.read(sentPath, sentKey) { r ->
                    createBusy.remove(sentKey)
                    if (r == Remote.MINE_ACTIVE || r == Remote.MINE_RESOLVED) {
                        onCreated(sentPath, sentKey)
                    } else {
                        val n = (createFails[sentKey] ?: 0) + 1
                        createFails[sentKey] = n
                        createNext[sentKey] = clock() + backoffMs(n)
                    }
                    onChange()
                }
            }
        }
    }

    private fun onCreated(path: String, key: String) {
        createFails.remove(key)
        createNext.remove(key)
        // A late response that arrives after a resolve or replacement changed the active key is not written to the active slot
        val active = kv.get(K_KEY) == key && hasActive()
        val list = pending()
        val waiting = list.any { it.key == key && it.path == path && !it.sent }
        // If this confirmation arrives after the resolve confirmation already reported the SOS and removed the row, don't report again
        if (!active && !waiting) return
        // Report even a late confirmation, since the record now exists on the server.
        // The mail queue entry must be saved first so that, if the process dies in between, a re-check reports it again
        onSaved(SosMail.EVENT_SOS, path, key)
        if (active) {
            kv.put(mapOf(K_SENT to "1", K_AT to wallClock().toString()))
            sentAt = clock()
        }
        if (waiting) {
            val marked = list.map { if (it.key == key && it.path == path) Pending(it.path, it.key, true, it.auto) else it }
            kv.put(mapOf(K_PENDING to encode(marked)))
        }
    }

    private fun currentRecord() = Record(
        kv.get(K_BLE).orEmpty(), kv.get(K_NAME).orEmpty(), kv.get(K_ROLE).orEmpty(),
        kv.get(K_TRIGGER).orEmpty(), kv.get(K_BEACON), kv.get(K_RSSI)?.toIntOrNull(),
        kv.get(K_SID)?.toIntOrNull() ?: 0, kv.get(K_EP)?.toIntOrNull() ?: 0
    )

    // ── Resolve ─────────────────────────────────────────────────

    private fun sendResolves() {
        for (e in pending()) {
            val key = e.key
            if (key in resolveBusy || (resolveNext[key] ?: 0L) > clock()) continue
            resolveBusy.add(key)
            transport.resolve(e.path, key, e.auto) { ok ->
                if (ok) {
                    resolveBusy.remove(key)
                    resolved(e)
                    onChange()
                } else {
                    transport.read(e.path, key) { r ->
                        resolveBusy.remove(key)
                        if (r == Remote.MINE_RESOLVED) {
                            resolved(e)
                        } else if (r == Remote.ABSENT) {
                            drop(e)
                        } else {
                            val n = (resolveFails[key] ?: 0) + 1
                            resolveFails[key] = n
                            resolveNext[key] = clock() + backoffMs(n)
                        }
                        onChange()
                    }
                }
            }
        }
    }

    /**
     * The resolve is confirmed by the server. Re-read the row now; if the record
     * was resolved before its SOS was confirmed, report the SOS first
     * (the create confirmation may have arrived after sending and changed the flag). The mail queue entry must be saved first so that,
     * if the process dies in between, a re-check reports it again.
     */
    private fun resolved(e: Pending) {
        val now = pending().firstOrNull { it.key == e.key && it.path == e.path }
        if (now != null && !now.sent) onSaved(SosMail.EVENT_SOS, e.path, e.key)
        if (!e.auto) onSaved(SosMail.EVENT_RESOLVED, e.path, e.key)
        drop(e)
    }

    private fun drop(e: Pending) {
        val left = pending().filterNot { it.key == e.key && it.path == e.path }
        kv.put(mapOf(K_PENDING to if (left.isEmpty()) null else encode(left)))
        resolveFails.remove(e.key)
        resolveNext.remove(e.key)
    }

    // List storage format: one "path TAB key" per line; an entry resolved before the SOS was confirmed gets "TAB 0", and an
    // automatic release gets "TAB auto" after that (alone when confirmed, which a version without automatic release reads
    // as a confirmed resolve and still resends). Lines without a third field are read as confirmed. Broken lines are skipped.
    private fun pending(): List<Pending> {
        val raw = kv.get(K_PENDING) ?: return emptyList()
        val out = ArrayList<Pending>()
        for (line in raw.split('\n')) {
            val f = line.split('\t')
            if (f.size !in 2..4 || f[0].isEmpty() || f[1].isEmpty()) continue
            out.add(Pending(f[0], f[1], f.getOrNull(2) != "0", "auto" in f.drop(2)))
        }
        return out
    }

    private fun encode(list: List<Pending>): String =
        list.joinToString("\n") {
            it.path + "\t" + it.key + when {
                it.auto && it.sent -> "\tauto"
                it.auto -> "\t0\tauto"
                it.sent -> ""
                else -> "\t0"
            }
        }
}
