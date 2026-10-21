package com.wf11.safealert.service

/**
 * Records another phone's SOS on the server when only Bluetooth carried it (pure logic, no Android dependency, main thread).
 *
 * A peer SOS heard over Bluetooth only (episode 1..255, an SOS ID in its signal, no server record of that request) for
 * RELAY_WAIT_MS gives the sender time to upload it itself. After that a phone that is connected writes one record under the key
 * PREFIX + SOS ID. Every phone builds the same key, so the first phone to write wins: the rules refuse a second create under an
 * existing key, and that refusal is read back and counted as "already relayed", never retried and never an error. A sender inside a
 * dead zone is covered by any phone of the site that still has data. An SOS without an SOS ID in its signal (an older app) is not
 * relayed. The record carries trigger relay and relayBy (this phone's device ID) so the mail and the control page can say it came
 * through a nearby device; its floor, process, zone and worker code are the sender's, taken from the signal (empty = the whole site). The SOS mail is
 * queued through onSaved like any other SOS record.
 *
 * This phone does not receive its own relayed record (the receive path skips records with my uid), so it judges the rest from
 * its own Bluetooth entry, which is why only the peer entries are passed in. A relayed record is released only when the entry has
 * ended, never because another record joined it:
 * - the SOS ends on air (the entry's usual 10 s of false), or a released record of the same SOS ID arrives (the sender's own, or
 *   another phone's relayed one): this record is released and the release mail queued (the mail script sends one mail per SOS ID);
 * - the one-hour limit: released with reason auto and no release mail, as for any other record;
 * - the entry is gone from the list: nothing is written; the other phones' one-hour release closes the record.
 * A job whose write was settled stays until its entry leaves the list, so the same SOS heard again is not uploaded twice.
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
    fun tick(peers: Collection<LoneWorkerPeers.Peer>, nowMs: Long, online: Boolean, myId: String) {
        val bleOnly = peers.filter { it.active && it.key == null && it.episode != 0 && it.sosId.isNotEmpty() && it.bleId != myId }
        heard = bleOnly.mapTo(HashSet()) { it.epId }
        if (myId.isNotEmpty()) {
            for (p in bleOnly) {
                if (p.epId in jobs || nowMs - p.firstSeenMs < RELAY_WAIT_MS) continue
                val rec = SosLedger.Record(
                    p.bleId, p.displayName(), "", TRIGGER, p.beacon.ifEmpty { null }, null, 0, p.episode, p.floor, p.proc,
                    relayBy = myId, sosId = p.sosId, zone = p.zone, worker = p.worker
                )
                jobs[p.epId] = Job(p.epId, PREFIX + p.sosId, rec)
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
            // Recorded: the entry still going (heard, or joined by the sender's own record) = keep waiting; the entry ended =
            // released on air or by a released record of the same SOS ID (release mail) or by the one-hour limit (reason auto, no mail)
            if (p.active) continue
            end(job, auto = p.autoEnded, mail = !p.autoEnded)
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
