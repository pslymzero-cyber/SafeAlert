package com.wf11.safealert.service

import com.wf11.safealert.service.SosLedger.Remote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SosLedger: own SOS storage and retry state machine. In-memory kv and a capturing transport. */
class SosLedgerTest {

    private var now = 0L
    private fun ledger(kv: Kv, tr: FakeSosTransport) = SosLedger(kv, tr, { now })
    /** Ledger whose mail-queue hook appends "event:key" to [seen]. */
    private fun recording(kv: Kv, tr: FakeSosTransport, seen: MutableList<String>) =
        SosLedger(kv, tr, { now }, { e, _, k -> seen.add("$e:$k") })
    private fun rec(sid: Int = 0) = SosLedger.Record("BLE_ME", "n", "WALKER", "still", null, null, sid)

    // An SOS released by the one-hour limit is recorded on the server as automatic, and no resolve mail is reported:
    //   that mail tells the site the worker pressed "괜찮아요".
    @Test fun automatic_release_is_recorded_as_auto_without_a_resolve_mail() {
        val kv = Kv(); val tr = FakeSosTransport(); val seen = ArrayList<String>()
        val l = recording(kv, tr, seen)
        l.begin(rec())
        tr.creates[0].cb(true)
        l.resolve(auto = true)
        assertEquals(listOf(true), tr.autos)
        tr.resolves[0].cb(true)
        assertEquals(listOf("${SosMail.EVENT_SOS}:k1"), seen)
    }

    // The automatic mark of a resolve still waiting for the server survives a late create confirmation and a restart.
    @Test fun pending_automatic_release_stays_automatic_after_restart() {
        val kv = Kv(); val tr = FakeSosTransport()
        val l = ledger(kv, tr)
        l.begin(rec())
        l.resolve(auto = true)
        tr.creates[0].cb(true)
        tr.resolves[0].cb(false)
        tr.reads.last().cb(Remote.ERROR)
        ledger(kv, tr).tick()
        assertEquals(listOf(true, true), tr.autos)
    }

    // A confirmed automatic release waiting for the server is stored the way a version without automatic release reads a
    //   confirmed resolve (three fields, the third not "0"), so rolling back still resends it.
    @Test fun confirmed_automatic_release_stays_readable_by_an_older_version() {
        val kv = Kv(); val tr = FakeSosTransport()
        val l = ledger(kv, tr)
        l.begin(rec())
        tr.creates[0].cb(true)
        l.resolve(auto = true)
        val f = kv.m.getValue("r.list").split('\t')
        assertEquals(3, f.size)
        assertNotEquals("0", f[2])
    }

    // My SOS's age counts from the server's confirmation (one nobody was told about never ages), on the elapsed clock
    //   while running (a wall-clock jump changes nothing) and on the wall clock across a restart.
    @Test fun sos_age_counts_from_server_confirmation() {
        val kv = Kv(); val tr = FakeSosTransport(); var wall = 1_000L
        val l = SosLedger(kv, tr, { now }, wallClock = { wall })
        l.begin(rec())
        now += SosLedger.AUTO_RELEASE_MS
        assertNull("서버 확인 전에는 재지 않는다", l.activeForMs())
        tr.creates[0].cb(true)
        now += 5_000; wall += 3 * SosLedger.AUTO_RELEASE_MS
        assertEquals("켜져 있는 동안 벽시계가 뛰어도 그대로", 5_000L, l.activeForMs())
        wall = 1_000L + SosLedger.AUTO_RELEASE_MS
        val restored = SosLedger(kv, tr, { now }, wallClock = { wall })
        assertEquals(SosLedger.AUTO_RELEASE_MS, restored.activeForMs())
        restored.resolve()
        assertNull(restored.activeForMs())
    }

    @Test fun create_record_carries_episode() {
        val kv = Kv(); val tr = FakeSosTransport(); val l = ledger(kv, tr)
        l.begin(rec())
        assertEquals(1, tr.recs[0].ep)
        l.resolve()
        l.begin(rec())
        assertEquals(2, tr.recs[1].ep)
    }

    @Test fun offline_ok_then_new_sos_starts_at_once_and_late_acks_touch_only_their_slot() {
        val kv = Kv(); val tr = FakeSosTransport(); val l = ledger(kv, tr)
        l.begin(rec())
        assertEquals(1, tr.creates.size)
        val a = tr.creates[0]
        assertEquals("k1", a.key)
        assertEquals(1, l.episode())

        l.resolve()
        assertFalse(l.hasActive())
        assertEquals(1, tr.resolves.size)
        assertEquals("k1", tr.resolves[0].key)

        l.begin(rec())
        assertEquals(2, tr.creates.size)
        val b = tr.creates[1]
        assertEquals("k2", b.key)
        assertEquals(2, l.episode())

        a.cb(true)
        assertNotEquals(SosLedger.STATUS_SENT, l.statusText())
        tr.resolves[0].cb(true)
        assertNull(kv.get("r.list"))
        assertTrue(l.hasActive())
        assertNotEquals(SosLedger.STATUS_SENT, l.statusText())
        b.cb(true)
        assertEquals(SosLedger.STATUS_SENT, l.statusText())
    }

    @Test fun restored_sos_keeps_trigger_episode_hint_and_is_resent_with_same_key_and_begin_is_noop() {
        val kv = Kv(); val tr = FakeSosTransport()
        ledger(kv, tr).begin(rec(77))
        val first = tr.creates[0]

        val tr2 = FakeSosTransport(); val l2 = ledger(kv, tr2)
        assertEquals("still", l2.restoredTrigger())
        assertEquals(1, l2.episode())
        assertEquals(77, l2.hint())
        l2.begin(rec())
        assertEquals(0, tr2.creates.size)
        l2.tick()
        assertEquals(1, tr2.creates.size)
        assertEquals(first.key, tr2.creates[0].key)
        assertEquals(first.path, tr2.creates[0].path)
    }

    @Test fun episode_after_255_wraps_to_1() {
        val kv = Kv(); kv.m["ep.last"] = "255"
        val l = ledger(kv, FakeSosTransport())
        l.begin(rec())
        assertEquals(1, l.episode())
    }

    @Test fun denied_create_then_read_mine_marks_sent_without_second_create() {
        val kv = Kv(); val tr = FakeSosTransport(); val l = ledger(kv, tr)
        l.begin(rec())
        tr.creates[0].cb(false)
        assertEquals(1, tr.reads.size)
        tr.reads[0].cb(Remote.MINE_ACTIVE)
        assertEquals(SosLedger.STATUS_SENT, l.statusText())
        l.tick()
        now += 1_000_000L
        l.tick()
        assertEquals(1, tr.creates.size)
    }

    @Test fun create_retry_backs_off_exponentially() {
        val kv = Kv(); val tr = FakeSosTransport(); val l = ledger(kv, tr)
        l.begin(rec())
        tr.creates[0].cb(false); tr.reads[0].cb(Remote.ABSENT)
        assertEquals(SosLedger.STATUS_FAILED, l.statusText())
        now = 9_999L; l.tick()
        assertEquals(1, tr.creates.size)
        now = 10_000L; l.tick()
        assertEquals(2, tr.creates.size)
        tr.creates[1].cb(false); tr.reads[1].cb(Remote.ABSENT)
        now = 29_999L; l.tick()
        assertEquals(2, tr.creates.size)
        now = 30_000L; l.tick()
        assertEquals(3, tr.creates.size)
        tr.creates[2].cb(false); tr.reads[2].cb(Remote.ABSENT)
        now = 69_999L; l.tick()
        assertEquals(3, tr.creates.size)
        now = 70_000L; l.tick()
        assertEquals(4, tr.creates.size)
        assertEquals(10_000L, SosLedger.backoffMs(1))
        assertEquals(20_000L, SosLedger.backoffMs(2))
        assertEquals(40_000L, SosLedger.backoffMs(3))
        assertEquals(300_000L, SosLedger.backoffMs(6))
        assertEquals(300_000L, SosLedger.backoffMs(500))
    }

    @Test fun rejected_resolve_keeps_slot_retries_and_reports_failure() {
        val kv = Kv(); val tr = FakeSosTransport(); val l = ledger(kv, tr)
        l.begin(rec())
        tr.creates[0].cb(true)
        l.resolve()
        assertFalse(l.resolveFailing())
        tr.resolves[0].cb(false)
        tr.reads[0].cb(Remote.MINE_ACTIVE)
        assertTrue(l.resolveFailing())
        assertTrue(kv.get("r.list") != null)
        now = 9_999L; l.tick()
        assertEquals(1, tr.resolves.size)
        now = 10_000L; l.tick()
        assertEquals(2, tr.resolves.size)
        tr.resolves[1].cb(true)
        assertNull(kv.get("r.list"))
        assertFalse(l.resolveFailing())
    }

    @Test fun resolve_of_never_created_record_is_dropped_after_read_absent() {
        val kv = Kv(); val tr = FakeSosTransport(); val l = ledger(kv, tr)
        l.begin(rec())
        tr.creates[0].cb(false); tr.reads[0].cb(Remote.ABSENT)
        l.resolve()
        tr.resolves[0].cb(false)
        assertEquals(2, tr.reads.size)
        tr.reads[1].cb(Remote.ABSENT)
        assertNull(kv.get("r.list"))
        assertFalse(l.resolveFailing())
    }

    @Test fun pending_resolves_survive_restart() {
        val kv = Kv(); val tr = FakeSosTransport(); val l = ledger(kv, tr)
        l.begin(rec())
        tr.creates[0].cb(true)
        l.resolve()
        kv.m["r.list"] = "junk\n" + kv.m.getValue("r.list")

        val tr2 = FakeSosTransport(); val l2 = ledger(kv, tr2)
        assertFalse(l2.hasActive())
        l2.tick()
        assertEquals(1, tr2.resolves.size)
        assertEquals("k1", tr2.resolves[0].key)
    }

    // Mail queue is written before a.sent / pending removal, so a death in between is recovered by the re-check
    @Test fun on_saved_runs_before_sent_mark_and_pending_drop() {
        val kv = Kv(); val tr = FakeSosTransport()
        val seen = ArrayList<String>()
        val l = SosLedger(kv, tr, { now }, { e, _, k ->
            seen.add(e + ":" + (kv.m["a.sent"] ?: "-") + ":" + (kv.m["r.list"]?.contains(k) == true))
        })
        l.begin(rec())
        tr.creates[0].cb(true)
        assertEquals("1", kv.m["a.sent"])
        l.resolve()
        tr.resolves[0].cb(true)
        assertNull(kv.m["r.list"])
        l.begin(rec())
        tr.creates[1].cb(true)
        l.resolve()
        tr.resolves[1].cb(false)
        tr.reads[0].cb(Remote.MINE_RESOLVED)
        assertNull(kv.m["r.list"])
        assertEquals(listOf("sos:-:false", "resolved:-:true", "sos:-:false", "resolved:-:true"), seen)
    }

    // A resolve confirmed before the create was confirmed queues the SOS first, then the resolve, once each
    @Test fun unconfirmed_resolve_queues_sos_then_resolved_once_in_any_order() {
        val both = listOf("sos:k1", "resolved:k1")
        fun case(steps: (Kv, FakeSosTransport, SosLedger, MutableList<String>) -> Unit): List<String> {
            val kv = Kv(); val tr = FakeSosTransport(); val seen = ArrayList<String>()
            val l = recording(kv, tr, seen)
            l.begin(rec())
            steps(kv, tr, l, seen)
            return seen
        }
        assertEquals("create ack after resolve, before resolve ack", both, case { _, tr, l, _ ->
            l.resolve(); tr.creates[0].cb(true); tr.resolves[0].cb(true)
        })
        assertEquals("no create answer, restart, resolve ok", both, case { kv, _, l, seen ->
            l.resolve()
            assertEquals("root/site\tk1\t0", kv.m["r.list"])
            val tr2 = FakeSosTransport(); val l2 = recording(kv, tr2, seen)
            l2.tick(); tr2.resolves[0].cb(true)
            assertNull(kv.m["r.list"])
        })
        assertEquals("no create answer, restart, resolve rejected then read resolved", both, case { kv, _, l, seen ->
            l.resolve()
            val tr2 = FakeSosTransport(); val l2 = recording(kv, tr2, seen)
            l2.tick(); tr2.resolves[0].cb(false); tr2.reads[0].cb(Remote.MINE_RESOLVED)
        })
        assertEquals("create rejected, read error, then resolve ok", both, case { _, tr, l, _ ->
            tr.creates[0].cb(false); tr.reads[0].cb(Remote.ERROR)
            l.resolve(); tr.resolves[0].cb(true)
        })
        assertEquals("create rejected, resolve, create read resolved, then resolve ok", both, case { _, tr, l, _ ->
            tr.creates[0].cb(false); l.resolve()
            tr.reads[0].cb(Remote.MINE_RESOLVED); tr.resolves[0].cb(true)
        })
        assertEquals("create rejected, resolve, resolve ok, then late create read", both, case { _, tr, l, _ ->
            tr.creates[0].cb(false); l.resolve()
            tr.resolves[0].cb(true); tr.reads[0].cb(Remote.MINE_RESOLVED)
        })
    }

    // A confirmed record, or an old two-field pending line, queues only the resolve
    @Test fun confirmed_or_old_format_pending_queues_only_resolved() {
        val kv = Kv(); val tr = FakeSosTransport()
        val l = recording(kv, tr, ArrayList())
        l.begin(rec())
        tr.creates[0].cb(true)
        l.resolve()
        assertEquals("root/site\tk1", kv.m["r.list"])
        val seen = ArrayList<String>()
        val tr2 = FakeSosTransport(); val l2 = recording(kv, tr2, seen)
        l2.tick(); tr2.resolves[0].cb(true)
        assertEquals(listOf("resolved:k1"), seen)

        val kv3 = Kv(); val tr3 = FakeSosTransport(); val seen3 = ArrayList<String>()
        kv3.m["r.list"] = "root/site\tk9"
        val l3 = recording(kv3, tr3, seen3)
        l3.tick(); tr3.resolves[0].cb(false); tr3.reads[0].cb(Remote.MINE_RESOLVED)
        assertEquals(listOf("resolved:k9"), seen3)
        assertNull(kv3.m["r.list"])
    }
}
