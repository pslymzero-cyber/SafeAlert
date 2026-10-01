package com.wf11.safealert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** LoneWorkerHeartbeat: alive-session record while lone worker watch is on (v1.2.2). Record only, no mail. */
class LoneWorkerHeartbeatTest {

    private class W(val path: String, val key: String, val fields: Map<String, Any>, val done: (Boolean) -> Unit)

    private class Fake : HbRemote {
        var uid: String? = "u1"
        var path: String? = "root/hb/WF11"
        var server = 1_700_000_000_000L
        var keyN = 0
        val writes = ArrayList<W>()
        override fun uid() = uid
        override fun path() = path
        override fun newKey(path: String) = "s" + (++keyN)
        override fun serverNow() = server
        override fun update(path: String, key: String, fields: Map<String, Any>, done: (Boolean) -> Unit) {
            writes.add(W(path, key, fields, done))
        }
    }

    private val min = 60_000L
    private var t = 100_000L
    private val r = Fake()
    private val hb = LoneWorkerHeartbeat(r) { t }
    private val s0 = r.server

    private fun adv(ms: Long) { t += ms; r.server += ms }
    private fun gaps() = r.writes.filter { w -> w.fields.keys.any { it.startsWith("g/") } }

    @Test fun session_start_refresh_gap_end() {
        hb.tick(true, "WALKER")
        assertEquals(1, r.writes.size)
        val w0 = r.writes[0]
        assertEquals("root/hb/WF11", w0.path)
        assertEquals("s1", w0.key)
        assertEquals(mapOf("uid" to "u1", "role" to "WALKER", "start" to s0, "last" to s0), w0.fields)
        w0.done(true)

        adv(5 * min); hb.tick(true, "WALKER")
        assertEquals(mapOf<String, Any>("last" to s0 + 5 * min), r.writes[1].fields)
        // offline: three more refreshes queue up, then the first one lands 20 minutes after the start
        for (i in 0 until 3) { adv(5 * min); hb.tick(true, "WALKER") }
        assertEquals(5, r.writes.size)
        r.writes[1].done(true)
        assertEquals(6, r.writes.size)
        assertEquals(mapOf("g/0" to mapOf("from" to s0, "to" to s0 + 20 * min)), r.writes[5].fields)
        for (i in 2..4) r.writes[i].done(true)
        assertEquals(6, r.writes.size)

        adv(min); hb.end()
        assertEquals(mapOf<String, Any>("last" to s0 + 21 * min, "end" to s0 + 21 * min), r.writes[6].fields)
        assertTrue(r.writes.all { it.key == "s1" && it.path == "root/hb/WF11" })
        hb.end()
        assertEquals(7, r.writes.size)
    }

    @Test fun refresh_waits_full_interval() {
        hb.tick(true, "EPJ")
        r.writes[0].done(true)
        adv(5 * min - 1_000); hb.tick(true, "EPJ")
        assertEquals(1, r.writes.size)
        adv(1_000); hb.tick(true, "EPJ")
        assertEquals(2, r.writes.size)
        adv(10_000); hb.tick(true, "EPJ")
        assertEquals(2, r.writes.size)
    }

    @Test fun gap_only_over_15_minutes_and_late_start_counts() {
        hb.tick(true, "WALKER")
        r.writes[0].done(true)
        for (i in 0 until 3) { adv(5 * min); hb.tick(true, "WALKER") }
        r.writes[3].done(true) // exactly 15 minutes after the last success
        assertEquals(0, gaps().size)
        for (i in 0 until 3) { adv(5 * min); hb.tick(true, "WALKER") }
        adv(1); r.writes[6].done(true)
        assertEquals(1, gaps().size)

        hb.end()
        val n = r.writes.size
        hb.tick(true, "WALKER") // new session, start write stays offline
        val start = r.writes[n]
        val st = r.server
        for (i in 0 until 3) { adv(5 * min); hb.tick(true, "WALKER") }
        adv(min); start.done(true)
        assertEquals(mapOf("g/0" to mapOf("from" to st, "to" to st + 16 * min)), gaps().last().fields)
        assertEquals("s2", gaps().last().key)
    }

    @Test fun gaps_capped_at_50() {
        hb.tick(true, "FORKLIFT")
        r.writes[0].done(true)
        for (i in 0 until 52) {
            adv(16 * min); hb.tick(true, "FORKLIFT")
            r.writes.last().done(true)
        }
        val g = gaps()
        assertEquals(LoneWorkerHeartbeat.MAX_GAPS, g.size)
        assertEquals((0 until 50).map { "g/$it" }, g.map { it.fields.keys.single() })
    }

    @Test fun closed_session_ignores_late_success_and_next_on_opens_new_key() {
        hb.tick(true, "WALKER")
        val first = r.writes[0]
        adv(min); hb.tick(false, "WALKER")
        assertEquals(setOf("last", "end"), r.writes[1].fields.keys)
        assertEquals("s1", r.writes[1].key)
        hb.tick(false, "WALKER")
        assertEquals(2, r.writes.size)

        hb.tick(true, "WALKER")
        assertEquals("s2", r.writes[2].key)
        for (i in 0 until 3) { adv(5 * min); hb.tick(true, "WALKER") }
        adv(min); first.done(true) // late answer of the closed session
        assertEquals(0, gaps().size)
    }

    @Test fun role_or_site_change_restarts_session() {
        hb.tick(true, "WALKER")
        hb.tick(true, "EPJ")
        assertEquals(listOf("s1", "s1", "s2"), r.writes.map { it.key })
        assertEquals(setOf("last", "end"), r.writes[1].fields.keys)
        assertEquals("EPJ", r.writes[2].fields["role"])
        r.path = "root/hb/WF12"
        hb.tick(true, "EPJ")
        assertEquals("s2", r.writes[3].key)
        assertEquals("root/hb/WF12", r.writes[4].path)
        assertEquals("s3", r.writes[4].key)
        hb.tick(true, "BOGUS")
        assertEquals("UNKNOWN", r.writes.last().fields["role"])
    }

    @Test fun nothing_without_site_or_login_and_failed_start_retries_after_5_minutes() {
        r.path = null
        hb.tick(true, "WALKER")
        r.path = "root/hb/WF11"; r.uid = null
        hb.tick(true, "WALKER")
        assertEquals(0, r.writes.size)
        r.uid = "u1"
        hb.tick(true, "WALKER")
        r.writes[0].done(false)
        adv(10_000); hb.tick(true, "WALKER")
        adv(5 * min - 20_000); hb.tick(true, "WALKER")
        assertEquals(1, r.writes.size)
        adv(10_000); hb.tick(true, "WALKER")
        assertEquals(2, r.writes.size)
        assertEquals("s2", r.writes[1].key)
        r.writes[1].done(true)
        adv(5 * min); hb.tick(true, "WALKER")
        r.writes[2].done(false) // refresh failure is ignored
        adv(5 * min); hb.tick(true, "WALKER")
        assertEquals(listOf("s2", "s2"), r.writes.drop(2).map { it.key })
    }
}
