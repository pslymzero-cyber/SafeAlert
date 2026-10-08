package com.wf11.safealert.service

/**
 * Peer rescue-request store (pure). No Android dependency; times are elapsed ms passed in by the caller.
 *
 * Peer rescue requests are handled as entries per episode (bleId, ep).
 * - A server record and a BLE advertisement of the same episode form one entry; a different episode or server key is a
 *   different entry. Each entry has its own name, beacon, mute and resolution.
 * - Episode-0 records are handled by server key only (they never take over or end a BLE entry).
 * - 30 s gap rule: with or without a server record, when the advertisement is heard for the first time or again after a
 *   gap of 30 s or more, only the mute is lifted (the start time is kept). Other phones using the same ID and episode are
 *   covered by this rule and the 30 s guard after resolution.
 * - When a server record takes over a BLE entry, the mute is not inherited (it rings again, now with the name).
 * - An untracked server resolve record ends the same-episode BLE entry when the resolve time (converted to this device's
 *   elapsed) is at or after the time that entry was first heard and the advertisement has not been heard for 15 s or more.
 *   A resolve rejected only because the advertisement is heard is kept and re-judged on tick. A replayed old resolve
 *   (before the first-heard time) never ends an entry that is being heard.
 * - A BLE-only entry is resolved only after false is seen for 10 s straight.
 * - Resolved entries are kept for 60 s; meanwhile same-episode advertisement residue (within 30 s after resolution) is ignored.
 * - An acknowledgement mutes only the passed entry ids (other entries of the same episode keep ringing).
 *   An acknowledgement for an entry that does not exist yet stays as a pending mute for 60 s: a new BLE entry is muted if it
 *   has the same episode; a server entry (created, absorbed, or first heard over BLE) is muted only if its server key or the
 *   absorbed BLE entry has a pending mute.
 * - A server entry still active SosLedger.AUTO_RELEASE_MS after its record was made (on the known server clock, otherwise
 *   from when first heard here) is over: it ends as automatic (autoEnded, also set by a record released with reason auto,
 *   even on an entry that already ended on air), its episode's adverts open no entry until they turn false (not episode 0:
 *   an old sender's next SOS cannot be told apart), and its record is queued (takeAutoReleased) to be marked released on
 *   the server, where its writer may be gone. A BLE-only entry may never have reached the server (nobody was told), so it
 *   ends that way only once its adverts have stopped as well.
 * - A server record outside this phone's floor/process scope is a quiet entry: hidden and silent, but it merges either way
 *   with its episode's BLE entry (which then rings with the name) and runs the one-hour release. It becomes a normal entry
 *   when heard over BLE or delivered again in scope, and stays hidden after it ends.
 */

/** One peer row used by the screen and the acknowledge gate. id = entry id, epId = episode ID. */
data class PeerRow(val id: String, val epId: String, val line: String, val active: Boolean, val autoEnded: Boolean = false)

class LoneWorkerPeers {

    data class Peer(
        val bleId: String,
        val key: String?,
        val name: String,
        val role: String,
        val trigger: String,
        val beacon: String,
        val createdAtMs: Long,
        val firstSeenMs: Long,
        val active: Boolean,
        val silenced: Boolean,
        val resolvedAtMs: Long,
        /** SOS episode number (1..255, 0 = unknown). */
        val episode: Int,
        /** Last time BLE showed sos=true for this entry. */
        val lastBleMs: Long = Long.MIN_VALUE,
        /** When the SOS began on this device's clock: the server record's time, or when first heard over BLE. */
        val startMs: Long = firstSeenMs,
        /** Ended by the one-hour limit, not by its worker. */
        val autoEnded: Boolean = false,
        /** Floor and process the sender gave ("" = none), shown on the row. */
        val floor: String = "",
        val proc: String = "",
        /**
         * Out-of-scope server entry not heard over Bluetooth: kept only to merge with its adverts and to run the one-hour
         * release; not listed, not audible, no notification; stays hidden after it ends.
         */
        val quiet: Boolean = false
    ) {
        val fromServer: Boolean get() = key != null
        /** Store key. */
        val id: String get() = if (key != null) "k:$key" else "b:$bleId#$episode"
        /** Acknowledge target ID. Unchanged when a server key is obtained. */
        val epId: String get() = "$bleId#$episode"
    }

    /**
     * One server record. resolvedLocalMs / startLocalMs = resolve and creation times converted to this device's elapsed
     * time (null if unknown); auto = released by the one-hour limit (reason auto). inScope = false means the record is
     * outside this phone's floor/process scope.
     */
    data class ServerRec(
        val key: String, val bleId: String, val name: String, val role: String, val trigger: String,
        val beacon: String, val createdAtMs: Long, val active: Boolean, val ep: Int, val resolvedLocalMs: Long?,
        val startLocalMs: Long? = null, val auto: Boolean = false,
        val floor: String = "", val proc: String = "", val inScope: Boolean = true
    )

    companion object {
        const val PEER_RESOLVE_GUARD_MS = 30_000L
        const val RESOLVED_KEEP_MS = 60_000L
        const val PEER_BLE_GAP_MS = 30_000L
        const val PEER_BLE_FALL_MS = 10_000L
        const val PENDING_SILENCE_MS = 60_000L
        /** The advertisement must be silent this long for an untracked resolve to end a BLE entry. */
        const val PEER_LIVE_AD_MS = 15_000L
        /** Margin added to time comparisons when the server time offset is unknown. */
        const val CLOCK_SLACK_MS = 10_000L

        /**
         * Converts a server resolve time to elapsed time. If the server time is unknown, treats it as wall-clock time and delays it by the margin.
         */
        fun resolvedLocalMs(resolvedAtMs: Long, serverNowMs: Long?, wallNowMs: Long, nowMs: Long): Long? =
            if (resolvedAtMs <= 0L) null
            else nowMs - ((serverNowMs ?: wallNowMs) - resolvedAtMs) - (if (serverNowMs == null) CLOCK_SLACK_MS else 0L)

        /**
         * Converts a server creation time to elapsed time on a known server time only: the age can end a ringing SOS, so this
         * phone's wall clock is never used (null when unknown; the SOS is then timed from when it is first heard here).
         */
        fun startLocalMs(createdAtMs: Long, serverNowMs: Long?, nowMs: Long): Long? =
            if (createdAtMs <= 0L || serverNowMs == null) null else nowMs - (serverNowMs - createdAtMs)
    }

    private val map = LinkedHashMap<String, Peer>()
    /** First false time per bleId. Recorded only while a BLE-only active entry exists. */
    private val falseSince = HashMap<String, Long>()
    /** Entry id / episode ID acknowledged while no entry existed -> expiry time. */
    private val pendingIds = HashMap<String, Long>()
    private val pendingEps = HashMap<String, Long>()
    /**
     * Resolves rejected only because the advertisement was heard: BLE entry id -> converted resolve time and whether the
     * record was released by the one-hour limit.
     */
    private val pendingResolve = HashMap<String, Pair<Long, Boolean>>()
    /** Server episodes released by the one-hour limit: their adverts open no entry until they turn false. */
    private val expiredEps = HashSet<String>()
    /** Server keys released by the one-hour limit, waiting to be marked released on the server. */
    private val autoReleased = ArrayList<String>()

    /** Entries the screen and notifications see (quiet ones are left out). */
    val all: Collection<Peer> get() = map.values.filter { !it.quiet }

    fun onServer(rec: ServerRec, nowMs: Long) {
        val (key, bleId, name, role, trigger, beacon, createdAtMs, active, ep) = rec
        val kId = "k:$key"
        val epId = "$bleId#$ep"
        val bId = "b:$epId"
        if (active && rec.startLocalMs != null && nowMs - rec.startLocalMs >= SosLedger.AUTO_RELEASE_MS) {
            for (p in listOfNotNull(map[kId], if (ep != 0) map[bId] else null)) expire(p, nowMs)
            if (ep != 0) expiredEps.add(epId)
            if (key !in autoReleased) autoReleased.add(key)
            return
        }
        if (!active) {
            val cur = map[kId]
            val b = if (ep != 0) map[bId] else null
            val r = rec.resolvedLocalMs
            if (cur != null && cur.active) {
                map[kId] = resolve(cur, nowMs, rec.auto)
            } else if (b != null && b.active && r != null && r >= b.firstSeenMs) {
                if (adGone(b, nowMs)) map[bId] = resolve(b, nowMs, rec.auto) else pendingResolve[bId] = r to rec.auto
            } else if (b != null && !b.active && rec.auto) {
                map[bId] = b.copy(autoEnded = true) // It already ended on air; the record says why
            }
            // An untracked resolve (old record replayed on connect) does not create an entry.
            return
        }
        val cur = map[kId]
        if (cur != null) {
            // A late duplicate delivery of the same resolved record does not revive it.
            if (cur.active) {
                map[kId] = cur.copy(
                    name = name, role = role, trigger = trigger,
                    beacon = beacon.ifEmpty { cur.beacon }, createdAtMs = createdAtMs,
                    startMs = rec.startLocalMs ?: cur.startMs,
                    floor = rec.floor, proc = rec.proc, quiet = cur.quiet && !rec.inScope
                )
            }
            return
        }
        val b = if (ep != 0) map[bId] else null
        if (b != null) {
            // Same episode seen over BLE first: attach the server key and ring again with the
            // name. Rings while the server record is active even if BLE resolved it.
            map.remove(bId)
            map[kId] = Peer(
                bleId, key, name, role, trigger, beacon.ifEmpty { b.beacon }, createdAtMs,
                firstSeenMs = b.firstSeenMs, active = true, silenced = coldServer(kId, epId, nowMs),
                resolvedAtMs = 0L, episode = ep, lastBleMs = b.lastBleMs, startMs = rec.startLocalMs ?: b.startMs,
                floor = rec.floor, proc = rec.proc
            )
            return
        }
        map[kId] = Peer(
            bleId, key, name, role, trigger, beacon, createdAtMs,
            firstSeenMs = nowMs, active = true, silenced = coldServer(kId, epId, nowMs),
            resolvedAtMs = 0L, episode = ep, startMs = rec.startLocalMs ?: nowMs,
            floor = rec.floor, proc = rec.proc, quiet = !rec.inScope
        )
    }

    fun onBle(bleId: String, sos: Boolean, nowMs: Long, ep: Int, beacon: String) {
        if (!sos) {
            expiredEps.removeAll { it.startsWith("$bleId#") }   // its SOS ended on air: a later one is new
            if (map.values.none { it.bleId == bleId && it.key == null && it.active }) return
            falseSince.putIfAbsent(bleId, nowMs)
            settle(bleId, nowMs)
            return
        }
        falseSince.remove(bleId)
        if ("$bleId#$ep" in expiredEps) return
        val matches = map.values.filter { it.bleId == bleId && it.episode == ep }
        if (matches.any { it.active }) {
            for (p in matches) {
                if (!p.active) continue
                val first = p.lastBleMs == Long.MIN_VALUE
                val gap = first || nowMs - p.lastBleMs >= PEER_BLE_GAP_MS
                // Even on first hearing, keep the mute if an acknowledgement from before the service restart (pending mute) points to this server entry.
                val keep = !gap || (first && p.key != null && coldServer(p.id, p.epId, nowMs))
                map[p.id] = p.copy(
                    lastBleMs = nowMs,
                    quiet = false,
                    silenced = p.silenced && keep,
                    beacon = if (p.key == null) p.beacon.ifEmpty { beacon } else p.beacon
                )
            }
            return
        }
        // Ignore advertisement residue right after resolution for the guard time.
        if (matches.any { nowMs - it.resolvedAtMs <= PEER_RESOLVE_GUARD_MS }) return
        val bId = "b:$bleId#$ep"
        map.remove(bId)
        map[bId] = Peer(
            bleId, null, "", "", "", beacon, nowMs,
            firstSeenMs = nowMs, active = true, silenced = live(pendingEps["$bleId#$ep"], nowMs),
            resolvedAtMs = 0L, episode = ep, lastBleMs = nowMs
        )
    }

    /**
     * targets = entry id -> episode ID. Existing entries: mute only that id (resolved entries are
     * not removed either — keeps the residue guard); missing ones: pending mute.
     */
    fun silence(nowMs: Long, targets: Map<String, String>) {
        for ((id, epId) in targets) {
            val p = map[id]
            if (p != null) {
                map[id] = p.copy(silenced = true)
            } else {
                pendingIds[id] = nowMs + PENDING_SILENCE_MS
                pendingEps[epId] = nowMs + PENDING_SILENCE_MS
            }
        }
    }

    fun audible(): List<Peer> = map.values.filter { it.active && !it.silenced && !it.quiet }

    fun tick(nowMs: Long) {
        for (p in map.values.toList()) {
            if (p.active && nowMs - p.startMs >= SosLedger.AUTO_RELEASE_MS && (p.key != null || adGone(p, nowMs))) expire(p, nowMs)
        }
        for (id in falseSince.keys.toList()) settle(id, nowMs)
        val pr = pendingResolve.entries.iterator()
        while (pr.hasNext()) {
            val (bId, v) = pr.next()
            val (r, auto) = v
            val b = map[bId]
            if (b == null || r < b.firstSeenMs) pr.remove()
            else if (!b.active) { if (auto) map[bId] = b.copy(autoEnded = true); pr.remove() } // Ended on air meanwhile
            else if (adGone(b, nowMs)) { map[bId] = resolve(b, nowMs, auto); pr.remove() }
        }
        map.values.removeAll { !it.active && nowMs - it.resolvedAtMs >= RESOLVED_KEEP_MS }
        val ids = map.values.mapTo(HashSet()) { it.bleId }
        falseSince.keys.retainAll(ids)
        pendingIds.values.removeAll { it <= nowMs }
        pendingEps.values.removeAll { it <= nowMs }
    }

    private fun settle(bleId: String, nowMs: Long) {
        val since = falseSince[bleId] ?: return
        if (nowMs - since < PEER_BLE_FALL_MS) return
        for (p in map.values.toList()) {
            if (p.bleId == bleId && p.key == null && p.active) map[p.id] = resolve(p, nowMs)
        }
        falseSince.remove(bleId)
    }

    /** Whether the advertisement has been silent for 15 s or more. */
    private fun adGone(b: Peer, nowMs: Long) = b.lastBleMs == Long.MIN_VALUE || nowMs - b.lastBleMs >= PEER_LIVE_AD_MS

    /** Server keys released by the one-hour limit since the last call, to be marked released on the server. */
    fun takeAutoReleased(): List<String> = autoReleased.toList().also { autoReleased.clear() }

    /**
     * The one-hour limit ends this entry: resolved here; a server entry's episode is then ignored on air (not episode 0) and
     * its record queued. A BLE-only entry ends only once silent, so its episode heard again rings as live.
     */
    private fun expire(p: Peer, nowMs: Long) {
        if (p.active) map[p.id] = resolve(p, nowMs, auto = true)
        val key = p.key ?: return
        if (p.episode != 0) expiredEps.add(p.epId)
        if (key !in autoReleased) autoReleased.add(key)
    }

    /** On resolution, lifts the mute so the ended row shows; auto = ended by the one-hour limit. */
    private fun resolve(p: Peer, nowMs: Long, auto: Boolean = false) =
        p.copy(active = false, resolvedAtMs = nowMs, silenced = false, autoEnded = auto)

    private fun live(until: Long?, nowMs: Long) = until != null && until > nowMs

    /** Pending mute for a server entry: whether its server key or the same-episode BLE entry id was acknowledged. */
    private fun coldServer(kId: String, epId: String, nowMs: Long) =
        live(pendingIds[kId], nowMs) || live(pendingIds["b:$epId"], nowMs)
}

// ── Peer SOS delegated from LoneWorkerLogic: LoneWorkerPeers owns per-episode entries ──────────
/** Server record received — delegated from LoneWorkerLogic, kept here due to the 500-line limit. */
fun LoneWorkerLogic.onPeerServer(rec: LoneWorkerPeers.ServerRec, nowMs: Long) = peerStore.onServer(rec, nowMs)
/** BLE bit received — delegated from LoneWorkerLogic, kept here due to the 500-line limit. */
fun LoneWorkerLogic.onPeerBle(bleId: String, sos: Boolean, nowMs: Long, episode: Int = 0, beacon: String = "") =
    peerStore.onBle(bleId, sos, nowMs, episode, beacon)
/**
 * The "확인" button. targets = entry id -> episode ID. Mutes only those entries —
 * delegated from LoneWorkerLogic, kept here due to the 500-line limit.
 */
fun LoneWorkerLogic.silencePeers(nowMs: Long, targets: Map<String, String>) = peerStore.silence(nowMs, targets)
/** Ringing peer entries — delegated from LoneWorkerLogic, kept here due to the 500-line limit. */
fun LoneWorkerLogic.audiblePeers(): List<LoneWorkerPeers.Peer> = peerStore.audible()
/** Server keys of peer SOS released by the one-hour limit — delegated from LoneWorkerLogic. */
fun LoneWorkerLogic.takeAutoReleasedPeers(): List<String> = peerStore.takeAutoReleased()
