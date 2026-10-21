package com.wf11.safealert.service

import com.wf11.safealert.utils.SiteScope

/**
 * Peer rescue-request store (pure). No Android dependency; times are elapsed ms passed in by the caller.
 *
 * Peer rescue requests are handled as entries per episode (bleId, ep). A rescue request made with an SOS ID (the sender's
 * signal and its server records carry it; see the SOS ID paragraph below) is one entry per SOS ID.
 * - A server record and a BLE advertisement of the same episode form one entry; a different episode or server key is a
 *   different entry. Each entry has its own name, beacon, mute and resolution.
 * - Episode-0 records are handled by server key only (they never take over or end a BLE entry).
 * - 30 s gap rule: with or without a server record, when the advertisement is heard for the first time or again after a
 *   gap of 30 s or more, only the mute is lifted (the start time is kept). Other phones using the same ID and episode are
 *   covered by this rule and the 30 s guard after resolution.
 * - When a server record takes over a BLE entry, the mute is not inherited (it alerts again, now with the name); its siren
 *   sounds again only for what is left of the entry's ring time (see the ring rule).
 * - Ring rule: the siren of an entry sounds on this phone for PEER_RING_MS from when the entry first alerted here with a siren
 *   that could sound (the phone had no check window, no SOS of its own and no monitoring stop pending after the rescuer's
 *   release: canSound), per entry.
 *   After that the entry keeps alerting by vibration, row and notification without the siren, so the requester's phone is
 *   the only one that can be found by ear. A takeover inherits the ring time; an entry shown again by the gap rule alerts as
 *   before, its siren only for what is left of the ring time; a different episode or server key is a different entry with its
 *   own ring time.
 * - An untracked server resolve record ends the same-episode BLE entry when the resolve time (converted to this device's
 *   elapsed) is at or after the time that entry was first heard and the advertisement has not been heard for 15 s or more.
 *   A resolve rejected only because the advertisement is heard is kept and re-judged on tick. A replayed old resolve
 *   (before the first-heard time) never ends an entry that is being heard.
 * - A BLE-only entry is resolved only after false is seen for 10 s straight.
 * - Resolved entries are kept for 60 s; meanwhile same-episode advertisement residue (within 30 s after resolution) is ignored.
 * - An acknowledgement mutes only the passed entry ids (other entries of the same episode keep alerting).
 *   An acknowledgement for an entry that does not exist yet stays as a pending mute for 60 s: a new BLE entry is muted if it
 *   has the same episode; a server entry (created, absorbed, or first heard over BLE) is muted only if its server key or the
 *   absorbed BLE entry has a pending mute.
 * - A server entry still active SosLedger.AUTO_RELEASE_MS after its record was made (on the known server clock, otherwise
 *   from when first heard here) is over: it ends as automatic (autoEnded, also set by a record released with reason auto,
 *   even on an entry that already ended on air), its episode's adverts open no entry until they turn false (not episode 0:
 *   an old sender's next SOS cannot be told apart), and its record is queued (takeAutoReleased) to be marked released on
 *   the server, where its writer may be gone. A BLE-only entry may never have reached the server (nobody was told), so it
 *   ends that way only once its adverts have stopped as well.
 * - A server record outside this phone's floor/process/zone scope is a quiet entry: hidden and silent, but it merges either way
 *   with its episode's BLE entry (which then alerts with the name) and runs the one-hour release. It becomes a normal entry
 *   when heard over BLE or delivered again in scope, and stays hidden after it ends. An advert of an SOS that already ended
 *   on air only refreshes when it was last heard within PEER_RESOLVE_GUARD_MS of that end: it wakes nothing and lifts
 *   no acknowledgement.
 * - SOS ID paragraph: the sender makes an SOS ID for every rescue request. Records and adverts with the same SOS ID are one
 *   entry: a record joins the active entry of its SOS ID (a Bluetooth entry gets the key; an entry a relayed record opened is
 *   taken over by the sender's own record, same ring time, the name alerts again; an entry that already holds a record ignores
 *   a relayed one). A released record with that SOS ID ends the active entry of the SOS ID whatever its key is, by the same rule
 *   as an untracked resolve (the advert must have been silent for 15 s, else it ends once it is). The SOS ID also arrives late,
 *   from the scan response, so a record that finds no entry of its SOS ID joins the (bleId, episode) Bluetooth entry and gives it
 *   its SOS ID. Records without an SOS ID (older app) keep the rules above and below.
 * - A record without an SOS ID made by another phone for a Bluetooth-only SOS (key r_...) never makes a second entry: if the same
 *   episode already has a server entry it is dropped, and the sender's own record that arrives later takes the relayed entry over
 *   (same ring time, the name alerts again). Its release ends only the entry under its own key, and an entry it opened
 *   also ends when the advert has been false for 10 s, as a Bluetooth-only entry does.
 */

/** One peer row used by the screen and the acknowledge gate. id = entry id, epId = episode ID. */
data class PeerRow(val id: String, val epId: String, val line: String, val active: Boolean, val autoEnded: Boolean = false)

/**
 * canSound = this phone can sound a peer siren now: it is watching, with no check window or SOS of its own and no
 * monitoring stop pending after the rescuer's release.
 */
class LoneWorkerPeers(private val canSound: () -> Boolean) {

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
        /** Floor, process and zone the sender gave ("" = none), shown on the row. */
        val floor: String = "",
        val proc: String = "",
        val zone: String = "",
        /** The sender's worker code ("" = unknown); for equipment it is the driver. Not used to judge quiet or ringing. */
        val worker: String = "",
        /** SOS ID the sending phone made for this SOS, from its signal or its server record ("" = unknown). */
        val sosId: String = "",
        /**
         * Out-of-scope server entry not heard over Bluetooth: kept only to merge with its adverts and to run the one-hour
         * release; not listed, not audible, no notification; stays hidden after it ends.
         */
        val quiet: Boolean = false,
        /**
         * When this server entry (in or out of scope) took over a Bluetooth entry that had already ended on air: the time that
         * entry ended. Leftover adverts within PEER_RESOLVE_GUARD_MS of it only refresh lastBleMs: they neither wake a quiet
         * entry nor undo an acknowledgement, and adverts going on past the guard do not look like a new hearing after a gap.
         * Null = never ended on air.
         */
        val endedOnAirMs: Long? = null,
        /** When this entry first alerted on this phone with a siren that could sound (null = not yet). Carried over when a server record takes over. */
        val ringStartMs: Long? = null,
        /** The entry's PEER_RING_MS are up: its siren stays off. Vibration, row and notification are not affected. */
        val ringOver: Boolean = false
    ) {
        val fromServer: Boolean get() = key != null
        /** The server record under this key was made by another phone for a Bluetooth-only SOS (SosRelay). */
        val relayed: Boolean get() = SosRelay.isRelay(key)
        /** Store key. */
        val id: String get() = if (key != null) "k:$key" else "b:$bleId#$episode"
        /** Acknowledge target ID. Unchanged when a server key is obtained. */
        val epId: String get() = "$bleId#$episode"
        /** "1F-OB-Z1", "" without floor/process/zone. */
        val scopeLabel: String get() = SiteScope.label("", floor, proc, zone)
        /** Name plus " · 1F-OB" for a notification. */
        fun nameWithScope(): String = displayName().let { n -> scopeLabel.let { if (it.isEmpty()) n else "$n · $it" } }
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
        val floor: String = "", val proc: String = "", val inScope: Boolean = true,
        /** SOS ID the sender made (SosAdvert.SOS_ID_PATTERN), "" = a record without one (older app). */
        val sosId: String = "",
        val zone: String = "", val worker: String = ""
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
         * How long the siren of a peer SOS sounds on this phone, counted from when the entry first alerted here while the phone
         * could sound it (a check window or SOS of its own delays the start to its end). Once started it runs on in any mode. After it only
         * the requester's phone sounds, so it can be found by ear; the vibration is not limited by it.
         */
        const val PEER_RING_MS = 30_000L

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

    /** Every entry including the quiet ones, for SosRelay to judge which Bluetooth-only SOS still has no server record. */
    val entries: Collection<Peer> get() = map.values

    /** Change signature of the visible entries (id, active, silenced, ring over), without building a list. */
    fun sig(): Int {
        if (map.isEmpty()) return 0
        var h = 0
        for (p in map.values) {
            if (p.quiet) continue
            h = h * 31 + p.id.hashCode()
            h = h * 31 + p.scopeLabel.hashCode()   // a scope filled in later by a scan response redraws the row
            h = h * 31 + p.worker.hashCode()   // so does a driver filled in later
            h = h * 31 + (if (p.active) 1 else 0) + (if (p.silenced) 2 else 0) + (if (p.ringOver) 4 else 0)
        }
        return h
    }

    fun onServer(rec: ServerRec, nowMs: Long) {
        server(rec, nowMs)
        ring(nowMs)
    }

    private fun server(rec: ServerRec, nowMs: Long) {
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
            // Same SOS ID = same rescue request: a released record ends that request's active entry whatever its key is. A record
            // without an SOS ID keeps the old rule: it ends the Bluetooth entry of the same bleId and episode, except that a relayed
            // record's release ends only the entry opened under its own key (the sender's own record may still be live). An entry
            // that already has an SOS ID is not ended by a record that has none of its own.
            val t = (if (rec.sosId.isNotEmpty()) map.values.firstOrNull { it.active && it.sosId == rec.sosId } else null)
                ?: (if (ep != 0 && !SosRelay.isRelay(key)) map[bId]?.takeIf { rec.sosId.isEmpty() || it.sosId.isEmpty() } else null)
            val r = rec.resolvedLocalMs
            if (cur != null && cur.active) {
                map[kId] = resolve(cur, nowMs, rec.auto)
            } else if (t != null && t.active && r != null && r >= t.firstSeenMs) {
                if (adGone(t, nowMs)) map[t.id] = resolve(t, nowMs, rec.auto) else pendingResolve[t.id] = r to rec.auto
            } else if (t != null && !t.active && rec.auto) {
                map[t.id] = t.copy(autoEnded = true) // It already ended on air; the record says why
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
                    floor = rec.floor, proc = rec.proc, zone = rec.zone, quiet = cur.quiet && !rec.inScope,
                    sosId = cur.sosId.ifEmpty { rec.sosId }, worker = rec.worker.ifEmpty { cur.worker }
                )
            }
            return
        }
        if (rec.sosId.isNotEmpty()) {
            // One SOS ID, one entry: the record joins the active entry of its request (see the class comment)
            val g = map.values.firstOrNull { it.active && it.sosId == rec.sosId }
            if (g != null) {
                when {
                    g.key == null -> attach(g, rec, nowMs)
                    g.relayed && !SosRelay.isRelay(key) -> takeOver(g, rec, nowMs)
                    // else: the entry already holds a server record of this request (the sender's own, or another phone's relayed one)
                }
                return
            }
        } else if (ep != 0) {
            val twins = map.values.filter { it.bleId == bleId && it.episode == ep && it.key != null }
            if (SosRelay.isRelay(key)) {
                if (twins.isNotEmpty()) return   // The sender's own record (or another phone's relayed one) already holds this SOS
            } else {
                twins.firstOrNull { it.relayed && it.active }?.let { o -> takeOver(o, rec, nowMs); return }
            }
        }
        val b = if (ep != 0) map[bId]?.takeIf { rec.sosId.isEmpty() || it.sosId.isEmpty() || it.sosId == rec.sosId } else null
        if (b != null) {
            attach(b, rec, nowMs)
            return
        }
        map[kId] = Peer(
            bleId, key, name, role, trigger, beacon, createdAtMs,
            firstSeenMs = nowMs, active = true, silenced = coldServer(kId, epId, nowMs),
            resolvedAtMs = 0L, episode = ep, startMs = rec.startLocalMs ?: nowMs,
            floor = rec.floor, proc = rec.proc, sosId = rec.sosId, quiet = !rec.inScope, zone = rec.zone, worker = rec.worker
        )
    }

    /** The sender's own record takes over the entry a relayed record opened: one SOS, one entry, the ring time goes on. */
    private fun takeOver(o: Peer, rec: ServerRec, nowMs: Long) {
        val kId = "k:${rec.key}"
        map.remove(o.id)
        map[kId] = o.copy(
            key = rec.key, name = rec.name, role = rec.role, trigger = rec.trigger, beacon = rec.beacon.ifEmpty { o.beacon },
            createdAtMs = rec.createdAtMs, startMs = rec.startLocalMs ?: o.startMs, floor = rec.floor, proc = rec.proc,
            zone = rec.zone, worker = rec.worker.ifEmpty { o.worker },
            quiet = o.quiet && !rec.inScope, silenced = coldServer(kId, "${rec.bleId}#${rec.ep}", nowMs),
            sosId = rec.sosId.ifEmpty { o.sosId }
        )
    }

    /**
     * A server record joins the Bluetooth entry of its request (seen over BLE first): attach the server key and ring again with the
     * name. Rings while the server record is active even if BLE resolved it.
     */
    private fun attach(b: Peer, rec: ServerRec, nowMs: Long) {
        val kId = "k:${rec.key}"
        val epId = "${rec.bleId}#${rec.ep}"
        map.remove(b.id)
        map[kId] = Peer(
            rec.bleId, rec.key, rec.name, rec.role, rec.trigger, rec.beacon.ifEmpty { b.beacon }, rec.createdAtMs,
            // A relayed record brings no new name, so an acknowledgement already given stays
            firstSeenMs = b.firstSeenMs, active = true,
            silenced = coldServer(kId, epId, nowMs) || (SosRelay.isRelay(rec.key) && b.silenced),
            resolvedAtMs = 0L, episode = rec.ep, lastBleMs = b.lastBleMs, startMs = rec.startLocalMs ?: b.startMs,
            floor = rec.floor, proc = rec.proc, sosId = rec.sosId.ifEmpty { b.sosId },
            zone = rec.zone, worker = rec.worker.ifEmpty { b.worker },
            // Already over on air: an out-of-scope record stays quiet; in or out of scope, that SOS's leftover adverts
            // within the guard time only refresh lastBleMs (no wake, no undoing an acknowledgement). Still heard: it keeps alerting.
            quiet = !rec.inScope && !b.active,
            endedOnAirMs = if (!b.active) b.resolvedAtMs else null,
            ringStartMs = b.ringStartMs, ringOver = b.ringOver
        )
    }

    fun onBle(bleId: String, sos: Boolean, nowMs: Long, ep: Int, beacon: String, sosId: String = "", floor: String = "", proc: String = "", zone: String = "", worker: String = "") {
        ble(bleId, sos, nowMs, ep, beacon, sosId, floor, proc, zone, worker)
        ring(nowMs)
    }

    private fun ble(bleId: String, sos: Boolean, nowMs: Long, ep: Int, beacon: String, sosId: String, floor: String, proc: String, zone: String, worker: String) {
        if (!sos) {
            expiredEps.removeAll { it.startsWith("$bleId#") }   // its SOS ended on air: a later one is new
            if (map.values.none { it.bleId == bleId && (it.key == null || it.relayed) && it.active }) return
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
                if (p.endedOnAirMs != null && nowMs - p.endedOnAirMs <= PEER_RESOLVE_GUARD_MS) {
                    // A leftover advert of an SOS already over on air still counts as heard, so adverts that go on past the guard do
                    // not look like a new hearing after a gap; it wakes nothing and lifts no acknowledgement
                    map[p.id] = p.copy(lastBleMs = nowMs)
                    continue
                }
                val first = p.lastBleMs == Long.MIN_VALUE
                val gap = first || nowMs - p.lastBleMs >= PEER_BLE_GAP_MS
                // Even on first hearing, keep the mute if an acknowledgement from before the service restart (pending mute) points to this server entry.
                val keep = !gap || (first && p.key != null && coldServer(p.id, p.epId, nowMs))
                map[p.id] = p.copy(
                    lastBleMs = nowMs,
                    quiet = false,
                    silenced = p.silenced && keep,
                    beacon = if (p.key == null) p.beacon.ifEmpty { beacon } else p.beacon,
                    // A scan response can arrive after the first advert: fill what is still empty, never overwrite
                    sosId = p.sosId.ifEmpty { sosId }, floor = p.floor.ifEmpty { floor }, proc = p.proc.ifEmpty { proc },
                    zone = p.zone.ifEmpty { zone }, worker = p.worker.ifEmpty { worker }
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
            resolvedAtMs = 0L, episode = ep, lastBleMs = nowMs, floor = floor, proc = proc, sosId = sosId,
            zone = zone, worker = worker
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

    /**
     * Entries alerting on this phone: active, not acknowledged, not quiet — whether their siren is over or not. The vibration,
     * the no-motion pause that follows it, the wake lock, the screen opening and the notification all follow this list.
     */
    fun audible(): List<Peer> = map.values.filter { it.active && !it.silenced && !it.quiet }

    /** Entries whose siren sounds on this phone: the alerting ones still inside their ring time. */
    fun sounding(): List<Peer> = audible().filter { !it.ringOver }

    /** When the first sounding siren runs out of ring time (null = no siren sounds). */
    fun ringEndAt(): Long? = sounding().mapNotNull { it.ringStartMs }.minOrNull()?.plus(PEER_RING_MS)

    /**
     * Starts the ring time of every entry that alerts for the first time while this phone can sound it (canSound) and ends it
     * once PEER_RING_MS have passed, in any mode. Runs as the last step of each clocked entry point so the flags are exact at
     * every event; the values are replaced in place (no list copy) because it runs on every scan result.
     */
    internal fun ring(nowMs: Long) {
        if (map.isEmpty()) return
        for (e in map.entries) {
            val p = e.value
            if (!p.active || p.silenced || p.quiet || p.ringOver) continue
            val start = p.ringStartMs ?: if (canSound()) nowMs else continue
            val over = nowMs - start >= PEER_RING_MS
            if (p.ringStartMs == null || over) e.setValue(p.copy(ringStartMs = start, ringOver = over))
        }
    }

    fun tick(nowMs: Long) {
        tickEntries(nowMs)
        ring(nowMs)
    }

    private fun tickEntries(nowMs: Long) {
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
            if (p.bleId == bleId && (p.key == null || p.relayed) && p.active) map[p.id] = resolve(p, nowMs)
        }
        falseSince.remove(bleId)
    }

    /** Whether the advertisement has been silent for 15 s or more. */
    private fun adGone(b: Peer, nowMs: Long) = b.lastBleMs == Long.MIN_VALUE || nowMs - b.lastBleMs >= PEER_LIVE_AD_MS

    /** Server keys released by the one-hour limit since the last call, to be marked released on the server. */
    fun takeAutoReleased(): List<String> = autoReleased.toList().also { autoReleased.clear() }

    /**
     * The one-hour limit ends this entry: resolved here; a server entry's episode is then ignored on air (not episode 0) and
     * its record queued. A BLE-only entry ends only once silent, so its episode heard again alerts as live.
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
fun LoneWorkerLogic.onPeerBle(
    bleId: String, sos: Boolean, nowMs: Long, episode: Int = 0, beacon: String = "",
    sosId: String = "", floor: String = "", proc: String = "", zone: String = "", worker: String = ""
) = peerStore.onBle(bleId, sos, nowMs, episode, beacon, sosId, floor, proc, zone, worker)
/**
 * The "확인" button. targets = entry id -> episode ID. Mutes only those entries —
 * delegated from LoneWorkerLogic, kept here due to the 500-line limit.
 */
fun LoneWorkerLogic.silencePeers(nowMs: Long, targets: Map<String, String>) = peerStore.silence(nowMs, targets)
/**
 * Peer entries alerting on this phone (siren on or off) — delegated from LoneWorkerLogic, kept here due to the 500-line limit.
 * Empty while a monitoring stop is pending after the rescuer's release: a phone about to stop monitoring gives no peer SOS output.
 */
fun LoneWorkerLogic.audiblePeers(): List<LoneWorkerPeers.Peer> = if (stopAt != null) emptyList() else peerStore.audible()
/** Peer entries whose siren sounds (alerting ones still inside their ring time) — delegated from LoneWorkerLogic. */
fun LoneWorkerLogic.soundingPeers(): List<LoneWorkerPeers.Peer> = audiblePeers().filter { !it.ringOver }
/** When the siren of the first sounding peer entry runs out of ring time — delegated from LoneWorkerLogic. */
fun LoneWorkerLogic.peerRingEndAt(): Long? = peerStore.ringEndAt()
/** Server keys of peer SOS released by the one-hour limit — delegated from LoneWorkerLogic. */
fun LoneWorkerLogic.takeAutoReleasedPeers(): List<String> = peerStore.takeAutoReleased()
/** Every peer entry including the quiet ones, for SosRelay — delegated from LoneWorkerLogic. */
fun LoneWorkerLogic.peerEntries(): Collection<LoneWorkerPeers.Peer> = peerStore.entries
