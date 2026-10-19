package com.wf11.safealert.service

import com.wf11.safealert.ble.SosAdvert

/**
 * Records another phone's SOS on the server when only Bluetooth carried it (pure logic, no Android dependency, main thread).
 *
 * A peer SOS heard over Bluetooth only (episode 1..255, no server record of the same bleId and episode) for RELAY_WAIT_MS gives
 * the sender time to upload it itself. After that a phone that is connected writes one record under a deterministic key (see key);
 * a sender inside a dead zone is covered by any phone of the site that still has data. The first phone to write wins: the rules
 * refuse a second create under an existing key, and that refusal is read back and counted as "already relayed", never retried
 * and never an error. The record carries trigger relay and relayBy (this phone's device ID) so the mail and the control page can
 * say it came through a nearby device. The SOS mail is queued through onSaved like any other SOS record.
 *
 * This phone does not receive its own relayed record (the receive path skips records with my uid), so it judges the rest from
 * its own Bluetooth entry, which is why only the peer entries are passed in:
 * - the SOS ends on air (the entry's usual 10 s of false): the record is released and the release mail queued;
 * - the sender's own record of the same episode arrives (the entry gets a server key), or another phone's relayed record
 *   under another day's key does: this record is released without mail, so one active record remains;
 * - the one-hour limit: released with reason auto and no release mail, as for any other record;
 * - the entry is gone from the list: nothing is written; the other phones' one-hour release closes the record.
 * A job whose write was settled stays until its entry leaves the list, so the same episode heard again is not uploaded twice.
 */
class SosRelay(
    private val transport: SosTransport,
    private val clock: () -> Long,
    private val onSaved: (event: String, path: String, key: String) -> Unit
) {
    companion object {
        /** How long a Bluetooth-only SOS is heard before this phone records it (the sender may upload itself meanwhile). */
        const val RELAY_WAIT_MS = 15_000L
        const val PREFIX = "r_"
        const val TRIGGER = "relay"
        private const val DAY_MS = 86_400_000L

        /**
         * Record key: PREFIX, the first SosAdvert.EXT_ID_MAX UTF-8 bytes of the device ID in lowercase hex, the episode, and the
         * UTC day of the server time (episodes wrap at 255 and the rules never let a key be written twice, so without the day the
         * same device and number could never be relayed again). At most 36 characters, valid as a Firebase key and for the mail script.
         */
        fun key(bleId: String, ep: Int, serverNowMs: Long): String {
            val hex = bleId.toByteArray(Charsets.UTF_8).take(SosAdvert.EXT_ID_MAX).joinToString("") { "%02x".format(it) }
            return "$PREFIX${hex}_${ep}_${serverNowMs / DAY_MS}"
        }

        /** Push keys start with '-' and fallback UUID keys with hex, so neither can start with PREFIX. */
        fun isRelay(key: String?): Boolean = key != null && key.startsWith(PREFIX)
    }

    private class Job(val epId: String, val key: String, val rec: SosLedger.Record) {
        var path: String? = null
        var live = false
        var done = false
        var busy = false
        var fails = 0
        var next = 0L
    }

    private val jobs = LinkedHashMap<String, Job>()
    /** Episode IDs of the Bluetooth-only SOS alive at the last tick. */
    private var heard: Set<String> = emptySet()

    /** Called every 10 s. online = signed in with the database connection up (writes made offline would upload after the SOS is over). */
    fun tick(
        peers: Collection<LoneWorkerPeers.Peer>, nowMs: Long, online: Boolean, myId: String,
        floor: String, proc: String, serverNowMs: Long
    ) {
        val bleOnly = peers.filter { it.active && it.key == null && it.episode != 0 && it.bleId != myId }
        heard = bleOnly.mapTo(HashSet()) { it.epId }
        if (myId.isNotEmpty()) {
            for (p in bleOnly) {
                if (p.epId in jobs || nowMs - p.firstSeenMs < RELAY_WAIT_MS) continue
                val rec = SosLedger.Record(
                    p.bleId, p.displayName(), "", TRIGGER, p.beacon.ifEmpty { null }, null, 0, p.episode, floor, proc, relayBy = myId
                )
                jobs[p.epId] = Job(p.epId, key(p.bleId, p.episode, serverNowMs), rec)
            }
        }
        for (job in jobs.values.toList()) {
            val same = peers.filter { it.epId == job.epId }
            val p = same.firstOrNull { it.active } ?: same.firstOrNull()
            if (p == null) {
                if (!job.busy) jobs.remove(job.epId)
                continue
            }
            if (job.done || job.busy || job.next > nowMs) continue
            if (!job.live) {
                // Ended, or a server record got attached meanwhile: nothing to record any more
                if (job.epId !in heard) jobs.remove(job.epId) else if (online) create(job)
                continue
            }
            // Recorded: still heard with nothing attached = keep waiting; a key attached = merged into the sender's record
            // (no mail); the entry ended = released on air (release mail) or by the one-hour limit (reason auto, no mail)
            if (p.active && p.key == null) continue
            if (p.active) end(job, auto = false, mail = false)
            else end(job, auto = p.autoEnded, mail = !p.autoEnded)
        }
    }

    private fun end(job: Job, auto: Boolean, mail: Boolean) {
        val path = job.path ?: return
        job.busy = true
        transport.resolve(path, job.key, auto) { ok ->
            if (ok) {
                job.busy = false
                finish(job, path, mail)
            } else {
                transport.read(path, job.key) { r ->
                    job.busy = false
                    when (r) {
                        // Closed meanwhile (the other phones' one-hour release may come first)
                        SosLedger.Remote.MINE_RESOLVED -> finish(job, path, mail)
                        SosLedger.Remote.ABSENT, SosLedger.Remote.OTHER -> job.done = true
                        else -> retryLater(job)
                    }
                }
            }
        }
    }

    private fun finish(job: Job, path: String, mail: Boolean) {
        job.done = true
        if (mail) onSaved(SosMail.EVENT_RESOLVED, path, job.key)
    }

    private fun create(job: Job) {
        val path = job.path ?: transport.sitePath() ?: return
        val uid = transport.uid() ?: return
        job.path = path
        job.busy = true
        transport.create(path, job.key, job.rec, uid) { ok ->
            if (ok) {
                job.busy = false
                live(job, path)
            } else {
                // Refused: another phone (or an earlier run of this one) may have created the key already
                transport.read(path, job.key) { r ->
                    job.busy = false
                    when (r) {
                        SosLedger.Remote.MINE_ACTIVE -> live(job, path)
                        SosLedger.Remote.MINE_RESOLVED, SosLedger.Remote.OTHER -> job.done = true
                        else -> retryLater(job)
                    }
                }
            }
        }
    }

    private fun retryLater(job: Job) {
        job.fails++
        job.next = clock() + SosLedger.backoffMs(job.fails)
    }

    /** The record is up. The SOS mail is queued only while the SOS is still being heard, else the release mail would be refused too. */
    private fun live(job: Job, path: String) {
        job.live = true
        job.fails = 0
        job.next = 0L
        if (job.epId in heard) onSaved(SosMail.EVENT_SOS, path, job.key)
    }
}
