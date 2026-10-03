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
 */

/** One peer row used by the screen and the acknowledge gate. id = entry id, epId = episode ID. */
data class PeerRow(val id: String, val epId: String, val line: String, val active: Boolean)

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
        val lastBleMs: Long = Long.MIN_VALUE
    ) {
        val fromServer: Boolean get() = key != null
        /** Store key. */
        val id: String get() = if (key != null) "k:$key" else "b:$bleId#$episode"
        /** Acknowledge target ID. Unchanged when a server key is obtained. */
        val epId: String get() = "$bleId#$episode"
    }

    /** One server record. resolvedLocalMs = resolve time converted to this device's elapsed time (null if unknown). */
    data class ServerRec(
        val key: String, val bleId: String, val name: String, val role: String, val trigger: String,
        val beacon: String, val createdAtMs: Long, val active: Boolean, val ep: Int, val resolvedLocalMs: Long?
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
    }

    private val map = LinkedHashMap<String, Peer>()
    /** First false time per bleId. Recorded only while a BLE-only active entry exists. */
    private val falseSince = HashMap<String, Long>()
    /** Entry id / episode ID acknowledged while no entry existed -> expiry time. */
    private val pendingIds = HashMap<String, Long>()
    private val pendingEps = HashMap<String, Long>()
    /** Resolves rejected only because the advertisement was heard: BLE entry id -> converted resolve time. */
    private val pendingResolve = HashMap<String, Long>()

    val all: Collection<Peer> get() = map.values

    fun onServer(rec: ServerRec, nowMs: Long) {
        val (key, bleId, name, role, trigger, beacon, createdAtMs, active, ep) = rec
        val kId = "k:$key"
        val epId = "$bleId#$ep"
        val bId = "b:$epId"
        if (!active) {
            val cur = map[kId]
            val b = if (ep != 0) map[bId] else null
            val r = rec.resolvedLocalMs
            if (cur != null && cur.active) {
                map[kId] = resolve(cur, nowMs)
            } else if (b != null && b.active && r != null && r >= b.firstSeenMs) {
                if (adGone(b, nowMs)) map[bId] = resolve(b, nowMs) else pendingResolve[bId] = r
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
                    beacon = beacon.ifEmpty { cur.beacon }, createdAtMs = createdAtMs
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
                resolvedAtMs = 0L, episode = ep, lastBleMs = b.lastBleMs
            )
            return
        }
        map[kId] = Peer(
            bleId, key, name, role, trigger, beacon, createdAtMs,
            firstSeenMs = nowMs, active = true, silenced = coldServer(kId, epId, nowMs),
            resolvedAtMs = 0L, episode = ep
        )
    }

    fun onBle(bleId: String, sos: Boolean, nowMs: Long, ep: Int, beacon: String) {
        if (!sos) {
            if (map.values.none { it.bleId == bleId && it.key == null && it.active }) return
            falseSince.putIfAbsent(bleId, nowMs)
            settle(bleId, nowMs)
            return
        }
        falseSince.remove(bleId)
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

    fun audible(): List<Peer> = map.values.filter { it.active && !it.silenced }

    fun tick(nowMs: Long) {
        for (id in falseSince.keys.toList()) settle(id, nowMs)
        val pr = pendingResolve.entries.iterator()
        while (pr.hasNext()) {
            val (bId, r) = pr.next()
            val b = map[bId]
            if (b == null || !b.active || r < b.firstSeenMs) pr.remove()
            else if (adGone(b, nowMs)) { map[bId] = resolve(b, nowMs); pr.remove() }
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

    /** On resolution, lifts the mute so the "해제됨" row shows. */
    private fun resolve(p: Peer, nowMs: Long) = p.copy(active = false, resolvedAtMs = nowMs, silenced = false)

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
