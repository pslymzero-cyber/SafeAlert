package com.wf11.safealert.service

import java.net.URLEncoder

/**
 * Lone-worker SOS mail queue. Pure logic with no Android dependency; every entry point is on the main thread.
 *
 * Items are added only at the moment an SOS or resolve is confirmed as recorded on the server (SosLedger.onSaved).
 * Each is sent to the mail script and removed on success or duplicate; a transient
 * failure is resent with a backoff that doubles from 10 s (max 5 min).
 * Give up when more than 2 hours have passed since the server-save confirmation (enqueue time) or
 * the clock went backward by more than 1 minute (same freshness rule as the script).
 * While the same record's sos is still queued, its resolved is not sent; it is sent right after the sos is done.
 * The recipient address and no-movement minutes are fixed at enqueue time. A resolve mail
 * goes to the address used when the same record's SOS mail was enqueued
 * (kept per record for 7 days). If this device never enqueued an SOS mail for a record, no resolve mail is enqueued either.
 * If the SOS is definitively rejected, that record's resolve is not enqueued either. Address rows are not used after 7 days.
 * Mail is a secondary channel, so no alert or judgment ever waits on this class.
 * onQueue reports whether the queue has items or is empty (the scheduled job that keeps sending after monitoring stops follows it).
 */
class SosMail(
    private val kv: SosKv,
    private val post: (form: String, done: (String?) -> Unit) -> Unit,
    private val now: () -> Long,
    private val onQueue: (pending: Boolean) -> Unit = {},
    private val addressFor: (siteCode: String) -> String
) {
    enum class Outcome { DONE, RETRY, DROP }

    companion object {
        const val EVENT_SOS = "sos"
        const val EVENT_RESOLVED = "resolved"
        const val DEFAULT_TO = "pslymzero@coupangfs.com"
        const val GIVE_UP_MS = 2 * 3_600_000L
        const val CLOCK_BACK_MS = 60_000L
        const val K_LIST = "m.list"
        const val K_ADDR = "m.to"
        const val KEEP_ADDR_MS = 7 * 86_400_000L
        const val MAX_TO = 2

        private val SCRIPT_URL = Regex("https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]+/exec")
        private val ADDRESS = Regex("[A-Za-z0-9%+_-]+(\\.[A-Za-z0-9%+_-]+)*@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+")
        private val CODE = Regex("\"code\"\\s*:\\s*\"([a-z_]+)\"")

        fun validAddress(s: String): Boolean = s.length in 1..254 && ADDRESS.matches(s)

        // Separators of the stored recipient text. Not whitespace: 'a b@x.com' must stay one bad address, not become 'b@x.com'.
        private val SPLIT = Regex("[,;\r\n]+")

        /** Entries of the recipient text as typed: trimmed, empty ones and case-insensitive repeats dropped. */
        fun entries(raw: String): List<String> =
            raw.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

        /** Addresses the mail goes to: the valid entries, at most MAX_TO. */
        fun addresses(raw: String): List<String> = entries(raw).filter(::validAddress).take(MAX_TO)

        /**
         * Normalized value if it matches the web app deployment URL format
         * (https://script.google.com/macros/s/<id>/exec); otherwise empty (mail off).
         */
        fun scriptUrl(raw: String): String = raw.trim().let { if (SCRIPT_URL.matches(it)) it else "" }

        /** The code value of a script response (not secret, safe to log). null if absent. */
        fun code(body: String?): String? = body?.let { CODE.find(it)?.groupValues?.get(1) }

        /** Interprets a script response. Reads only the code; unknown codes and no response are retried. */
        fun outcome(body: String?): Outcome = when (code(body)) {
            "sent", "dup" -> Outcome.DONE
            "bad_request", "not_allowed", "stale" -> Outcome.DROP
            else -> Outcome.RETRY
        }

        fun form(site: String, sc: String, id: String, event: String, to: String, stillMin: Int): String =
            listOf("site" to site, "sc" to sc, "id" to id, "event" to event, "to" to to, "stillMin" to stillMin.toString())
                .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
    }

    private class Item(
        val event: String, val site: String, val sc: String, val id: String,
        val to: String, val stillMin: Int, val at: Long
    ) {
        val tag get() = "$event/$id/$to"
    }

    // In-flight state, failure count and next-allowed time live in memory only (a restart retries immediately).
    private val busy = HashSet<String>()
    private val fails = HashMap<String, Int>()
    private val next = HashMap<String, Long>()
    private var drainDone: ((Boolean) -> Unit)? = null

    // Even if a force stop wiped the scheduled job, the next construction schedules it again from the remaining queue.
    init {
        if (load().isNotEmpty()) onQueue(true)
    }

    /** Called the moment a server record is confirmed. path = "{root}/sos/{site code}". */
    fun enqueue(event: String, path: String, key: String, stillMin: Int) {
        if (event != EVENT_SOS && event != EVENT_RESOLVED) return
        val i = path.lastIndexOf("/sos/")
        if (i <= 0) return
        val site = path.substring(0, i).trim('/')
        if (site.isEmpty()) return
        val sc = path.substring(i + 5)
        if (sc.isEmpty() || key.isEmpty()) return
        val t = now()
        val addr = loadAddr().filter { t - it[2].toLong() <= KEEP_ADDR_MS }
        // An SOS goes to every address set now, one queue item each. A resolve doesn't re-read settings; it goes to the
        //   addresses the same record's SOS mail was queued for (none if missing or older than 7 days)
        val tos = if (event == EVENT_SOS) addresses(addressFor(sc))
            else addr.filter { it[0] == key }.map { it[1] }.filter(::validAddress)
        val list = load()
        val add = tos.filter { to -> list.none { it.event == event && it.id == key && it.to == to } }
        if (add.isEmpty()) return
        val rows = if (event == EVENT_SOS) addr.filter { it[0] != key } + tos.map { listOf(key, it, t.toString()) }
            else addr.filterNot { it[0] == key && it[1] in add }
        save(list + add.map { Item(event, site, sc, key, it, stillMin.coerceIn(1, 30), t) }, mapOf(K_ADDR to addrText(rows)))
        tick()
    }

    fun tick() {
        val t = now()
        val all = load()
        val live = all.filter { t - it.at <= GIVE_UP_MS && it.at - t <= CLOCK_BACK_MS }
        if (live.size != all.size) save(live)
        for (e in live) {
            val tag = e.tag
            if (e.event == EVENT_RESOLVED && live.any { it.event == EVENT_SOS && it.id == e.id && it.to == e.to }) continue
            if (tag in busy || (next[tag] ?: 0L) > t) continue
            busy.add(tag)
            post(form(e.site, e.sc, e.id, e.event, e.to, e.stillMin)) { body ->
                busy.remove(tag)
                val o = outcome(body)
                if (o == Outcome.RETRY) {
                    val n = (fails[tag] ?: 0) + 1
                    fails[tag] = n
                    next[tag] = now() + SosLedger.backoffMs(n)
                } else {
                    fails.remove(tag)
                    next.remove(tag)
                    val list = load()
                    if (o == Outcome.DROP && e.event == EVENT_SOS) {
                        // The SOS mail to this address definitely did not go out, so also remove the same record's resolve
                        //   and address row for this address (the record's other addresses are untouched)
                        val rest = list.filterNot { it.id == e.id && it.to == e.to }
                        val addr = loadAddr()
                        val rows = addr.filterNot { it[0] == e.id && it[1] == e.to }
                        if (rest.size != list.size || rows.size != addr.size) {
                            save(rest, if (rows.size != addr.size) mapOf(K_ADDR to addrText(rows)) else emptyMap())
                        }
                    } else if (list.any { it.tag == tag }) save(list.filterNot { it.tag == tag })
                    tick() // Send the same record's waiting resolve right away
                }
                checkDrain()
            }
        }
    }

    /** Sends what is due now; once every in-flight request has returned, calls done(items left?) once. */
    fun drain(done: (Boolean) -> Unit) {
        drainDone = done
        tick()
        checkDrain()
    }

    private fun checkDrain() {
        if (busy.isNotEmpty()) return
        val d = drainDone ?: return
        drainDone = null
        d(load().isNotEmpty())
    }

    // Storage format: one line per item, 7 tab-separated fields. Lines with a wrong field count or bad numbers are skipped.
    private fun load(): List<Item> {
        val raw = kv.get(K_LIST) ?: return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val c = line.split('\t')
            if (c.size != 7) return@mapNotNull null
            val still = c[5].toIntOrNull() ?: return@mapNotNull null
            val at = c[6].toLongOrNull() ?: return@mapNotNull null
            Item(c[0], c[1], c[2], c[3], c[4], still, at)
        }
    }

    // Per-record recipient: one 'record key \t address \t enqueue time' per line. Lines with a wrong field count or bad time are dropped.
    private fun loadAddr(): List<List<String>> = (kv.get(K_ADDR) ?: "").split('\n')
        .map { it.split('\t') }.filter { it.size == 3 && it[2].toLongOrNull() != null }

    private fun addrText(rows: List<List<String>>): String? =
        if (rows.isEmpty()) null else rows.joinToString("\n") { it.joinToString("\t") }

    private fun save(list: List<Item>, extra: Map<String, String?> = emptyMap()) {
        kv.put(extra + (K_LIST to if (list.isEmpty()) null else list.joinToString("\n") {
            listOf(it.event, it.site, it.sc, it.id, it.to, it.stillMin, it.at).joinToString("\t")
        }))
        onQueue(list.isNotEmpty())
    }
}
