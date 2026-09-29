package com.wf11.safealert.service

import com.wf11.safealert.service.SosLedger.Remote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SosLedger: own SOS storage and retry state machine (v1.1.99). In-memory kv and a capturing transport. */
class SosLedgerTest {

    private class Kv : SosKv {
        val m = HashMap<String, String>()
        override fun get(k: String): String? = m[k]
        override fun put(changes: Map<String, String?>) {
            for ((k, v) in changes) if (v == null) m.remove(k) else m[k] = v
        }
    }

    private class Call<T>(val path: String, val key: String, val cb: (T) -> Unit)

    private class Tr : SosTransport {
        var uid: String? = "u1"
        var site: String? = "root/site"
        var keyN = 0
        val creates = ArrayList<Call<Boolean>>()
        val resolves = ArrayList<Call<Boolean>>()
        val reads = ArrayList<Call<Remote>>()
        override fun uid() = uid
        override fun sitePath() = site
        override fun newKey(path: String) = "k" + (++keyN)
        override fun create(path: String, key: String, rec: SosLedger.Record, uid: String, done: (Boolean) -> Unit) {
            creates.add(Call(path, key, done))
        }
        override fun resolve(path: String, key: String, done: (Boolean) -> Unit) {
            resolves.add(Call(path, key, done))
        }
        override fun read(path: String, key: String, done: (Remote) -> Unit) {
            reads.add(Call(path, key, done))
        }
    }

    private var now = 0L
    private fun ledger(kv: Kv, tr: Tr) = SosLedger(kv, tr, { now })
    private fun rec(sid: Int = 0) = SosLedger.Record("BLE_ME", "n", "WALKER", "still", null, null, sid)

    @Test fun offline_ok_then_new_sos_starts_at_once_and_late_acks_touch_only_their_slot() {
        val kv = Kv(); val tr = Tr(); val l = ledger(kv, tr)
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

    @Test fun restored_sos_is_resent_with_same_key_and_begin_is_noop() {
        val kv = Kv(); val tr = Tr()
        ledger(kv, tr).begin(rec(77))
        val first = tr.creates[0]

        val tr2 = Tr(); val l2 = ledger(kv, tr2)
        assertEquals("still", l2.restoredTrigger())
        assertEquals(1, l2.episode())
        assertEquals(77, l2.hint())
        l2.begin(rec())
        assertEquals(0, tr2.creates.size)
        l2.tick()
        assertEquals(1, tr2.creates.size)
        assertEquals(first.key, tr2.creates[0].key)
        assertEquals(first.path, tr2.creates[0].path)

        val kv3 = Kv(); kv3.m["ep.last"] = "255"
        val l3 = ledger(kv3, Tr())
        l3.begin(rec())
        assertEquals(1, l3.episode())
    }

    @Test fun denied_create_then_read_mine_marks_sent_without_second_create() {
        val kv = Kv(); val tr = Tr(); val l = ledger(kv, tr)
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
        val kv = Kv(); val tr = Tr(); val l = ledger(kv, tr)
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
        val kv = Kv(); val tr = Tr(); val l = ledger(kv, tr)
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
        val kv = Kv(); val tr = Tr(); val l = ledger(kv, tr)
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
        val kv = Kv(); val tr = Tr(); val l = ledger(kv, tr)
        l.begin(rec())
        tr.creates[0].cb(true)
        l.resolve()
        kv.m["r.list"] = "junk\n" + kv.m.getValue("r.list")

        val tr2 = Tr(); val l2 = ledger(kv, tr2)
        assertFalse(l2.hasActive())
        l2.tick()
        assertEquals(1, tr2.resolves.size)
        assertEquals("k1", tr2.resolves[0].key)
    }
}
