package com.wf11.safealert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LoneWorkerHeartbeat: alive-session record while the lone-worker watch is on. Record only, no mail. */
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

    // One device: same server, same storage. hb() = a new process (old callbacks never come back).
    private class Rig {
        var t = 100_000L
        val t0 = t
        val r = Fake()
        val kv = Kv()
        val s0 = r.server
        fun hb() = LoneWorkerHeartbeat(r, kv) { t }
        fun adv(ms: Long) { t += ms; r.server += ms }
        fun gaps() = r.writes.filter { w -> w.fields.keys.any { it.startsWith("g/") } }
    }

    private val min = 60_000L

    // The single-process tests use one rig and one heartbeat; restart tests make their own Rig and call hb() again.
    private val rig = Rig()
    private val r = rig.r
    private val kv = rig.kv
    private val s0 = rig.s0
    private val hb = rig.hb()

    private fun adv(ms: Long) = rig.adv(ms)
    private fun gaps() = rig.gaps()

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
        adv(16 * min); hb.end()
        assertEquals(setOf("last", "end"), r.writes.last().fields.keys)
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
    }

    @Test fun end_records_gap_when_silent_over_15_minutes() {
        hb.tick(true, "WALKER")
        r.writes[0].done(true)
        for (i in 0 until 3) { adv(5 * min); hb.tick(true, "WALKER") } // refreshes never answered
        hb.end() // exactly 15 minutes after the last success
        assertEquals(setOf("last", "end"), r.writes.last().fields.keys)

        hb.tick(true, "WALKER")
        val st = r.server
        r.writes.last().done(true)
        for (i in 0 until 9) { adv(5 * min); hb.tick(true, "WALKER") }
        hb.end()
        val s = r.server
        assertEquals(mapOf("last" to s, "end" to s, "g/0" to mapOf("from" to st, "to" to s)), r.writes.last().fields)
        assertEquals("s2", r.writes.last().key)
        assertEquals(1, gaps().size)
    }

    @Test fun rejected_refresh_opens_new_key_after_5_minutes() {
        hb.tick(true, "WALKER")
        r.writes[0].done(true)
        adv(5 * min); hb.tick(true, "WALKER")
        r.writes[1].done(false)
        adv(5 * min - 10_000); hb.tick(true, "WALKER")
        assertEquals(2, r.writes.size)
        adv(10_000); hb.tick(true, "WALKER")
        assertEquals(3, r.writes.size)
        assertEquals("s2", r.writes[2].key)
        assertEquals(setOf("uid", "role", "start", "last"), r.writes[2].fields.keys)
    }

    @Test fun restart_during_outage_keeps_baseline_for_gap() {
        for ((outEnd, dead) in listOf(25 to 12, 70 to 62, 120 to 12)) {
            val g = Rig()
            var hb = g.hb()
            hb.tick(true, "WALKER")
            g.r.writes[0].done(true)
            while (g.t - g.t0 < dead * min) { g.adv(30_000); hb.tick(true, "WALKER") } // no answers
            g.adv(30_000)
            hb = g.hb()
            hb.tick(true, "WALKER")
            val start = g.r.writes.last()
            assertEquals(setOf("uid", "role", "start", "last"), start.fields.keys)
            while (g.t - g.t0 < outEnd * min) { g.adv(30_000); hb.tick(true, "WALKER") }
            start.done(true)
            val gs = g.gaps()
            assertEquals("case $outEnd/$dead", 1, gs.size)
            assertEquals("s2", gs[0].key)
            assertEquals(mapOf("g/0" to mapOf("from" to g.s0, "to" to g.s0 + outEnd * min)), gs[0].fields)
        }
    }

    @Test fun no_false_gap_after_online_restart_dead_battery_or_normal_stop() {
        // (a) killed 2 minutes after a good refresh, back 30 s later
        // assumes the last refresh was acked before the kill; a kill between server apply and ack can still add one
        // false gap (inherent, see keep())
        var g = Rig()
        var hb = g.hb()
        hb.tick(true, "WALKER"); g.r.writes[0].done(true)
        g.adv(5 * min); hb.tick(true, "WALKER"); g.r.writes[1].done(true)
        g.adv(2 * min + 30_000)
        hb = g.hb(); hb.tick(true, "WALKER"); g.r.writes.last().done(true)
        assertEquals(0, g.gaps().size)

        // (b) battery dead after a good refresh, reboot 5 hours later (clock starts again)
        g = Rig()
        hb = g.hb()
        hb.tick(true, "WALKER"); g.r.writes[0].done(true)
        g.adv(5 * min); hb.tick(true, "WALKER"); g.r.writes[1].done(true)
        g.t = 50_000; g.r.server += 5 * 60 * min
        hb = g.hb(); hb.tick(true, "WALKER"); g.r.writes.last().done(true)
        assertEquals(0, g.gaps().size)

        // (c) normal stop during an outage keeps its gap on the end write only
        g = Rig()
        hb = g.hb()
        hb.tick(true, "WALKER"); g.r.writes[0].done(true)
        repeat(40) { g.adv(30_000); hb.tick(true, "WALKER") }
        hb.end()
        assertEquals(1, g.gaps().size)
        assertNull(g.kv.m[LoneWorkerHeartbeat.K_CARRY])
        g.adv(min); hb.tick(true, "WALKER"); g.r.writes.last().done(true)
        assertEquals(1, g.gaps().size)

        // (d) stopped while no session was open (refresh rejected): the next start is a fresh baseline
        g = Rig()
        hb = g.hb()
        hb.tick(true, "WALKER"); g.r.writes[0].done(true)
        repeat(4) { g.adv(5 * min); hb.tick(true, "WALKER") }
        g.r.writes[1].done(false)
        hb.end()
        assertNull(g.kv.m[LoneWorkerHeartbeat.K_CARRY])
        g.adv(5 * min); hb.tick(true, "WALKER"); g.r.writes.last().done(true)
        assertEquals(0, g.gaps().size)
    }

    @Test fun rejected_refresh_drops_carry_and_next_session_starts_fresh() {
        hb.tick(true, "WALKER")
        r.writes[0].done(true)
        for (i in 0 until 8) { adv(5 * min); hb.tick(true, "WALKER") }
        r.writes[1].done(false)
        assertNull(kv.m[LoneWorkerHeartbeat.K_CARRY])
        adv(5 * min); hb.tick(true, "WALKER")
        val start = r.writes.last()
        assertEquals("s2", start.key)
        assertEquals(setOf("uid", "role", "start", "last"), start.fields.keys)
        start.done(true)
        assertEquals(0, gaps().size)
    }

    @Test fun carry_needs_same_center_same_uid_within_15_minutes_of_last_sent_stamp() {
        fun run(center: String, uid: String, backMin: Long): List<W> {
            val g = Rig()
            var hb = g.hb()
            hb.tick(true, "WALKER"); g.r.writes[0].done(true) // baseline s0
            g.adv(5 * min); hb.tick(true, "WALKER") // last sent stamp s0+5, never answered
            g.adv(backMin * min)
            g.r.path = "root/hb/" + center; g.r.uid = uid
            hb = g.hb(); hb.tick(true, "WALKER") // restart
            val start = g.r.writes.last()
            g.adv(g.t0 + 25 * min - g.t)
            start.done(true)
            return g.gaps()
        }
        // 14 min after the last sent stamp is 19 min after the last ack: the window follows the sent stamp
        val gs = run("WF11", "u1", 14)
        assertEquals(1, gs.size)
        assertEquals("s2", gs[0].key)
        assertEquals(mapOf("g/0" to mapOf("from" to s0, "to" to s0 + 25 * min)), gs[0].fields)
        assertEquals(0, run("WF11", "u1", 16).size)
        assertEquals(0, run("WF12", "u1", 14).size)
        assertEquals(0, run("WF11", "u2", 14).size)
    }

    @Test fun carried_baseline_is_placed_at_first_ack_in_server_time() {
        val g = Rig()
        var hb = g.hb()
        hb.tick(true, "WALKER"); g.r.writes[0].done(true)
        g.adv(5 * min); hb.tick(true, "WALKER") // no answer
        g.adv(9 * min)
        g.r.server -= 10 * min // slow wall clock, no server anchor yet
        hb = g.hb(); hb.tick(true, "WALKER")
        val start = g.r.writes.last()
        g.adv(8 * min)
        g.r.server += 10 * min // anchor arrives
        start.done(true)
        assertEquals(mapOf("g/0" to mapOf("from" to g.s0, "to" to g.s0 + 22 * min)), g.gaps().single().fields)
    }
}
