package com.wf11.safealert.service

import com.wf11.safealert.service.SosLedger.Remote
import com.wf11.safealert.service.SosMail.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SosMail: rescue mail queue fed by the ledger's server-confirmed points. No network. */
class SosMailTest {

    private class Post(val form: String, val done: (String?) -> Unit)

    private var now = 1_000_000_000L
    private var to = "wfspt@coupangfs.com"
    private val posts = ArrayList<Post>()
    private val asked = ArrayList<String>()
    private val queue = ArrayList<Boolean>()

    private fun mail(kv: Kv) =
        SosMail(kv, { f, d -> posts.add(Post(f, d)) }, { now }, { queue.add(it) }) { sc -> asked.add(sc); to }

    private fun pair(kv: Kv, tr: FakeSosTransport): Pair<SosLedger, SosMail> {
        val m = mail(kv)
        val l = SosLedger(kv, tr, { now }, { e, p, k -> m.enqueue(e, p, k, 3) })
        return l to m
    }

    private fun rec() = SosLedger.Record("BLE_ME", "n", "WALKER", "still", null, null, 0)

    private val sent = "{\"ok\":true,\"code\":\"sent\"}"
    private val form1 = "site=root&sc=WF11&id=k1&event=sos&to=wfspt%40coupangfs.com&stillMin=3"

    @Test fun server_confirm_posts_sos_then_resolved_in_order_to_the_address_of_the_site_code() {
        val kv = Kv(); val tr = FakeSosTransport(site = "root/sos/WF11"); val (l, m) = pair(kv, tr)
        l.begin(rec())
        assertEquals(0, posts.size)
        tr.creates[0].cb(true)
        assertEquals(1, posts.size)
        assertEquals(form1, posts[0].form)
        assertEquals(listOf("WF11"), asked)

        l.resolve()
        tr.resolves[0].cb(true)
        assertEquals(1, posts.size)

        posts[0].done(sent)
        m.tick()
        assertEquals(2, posts.size)
        assertEquals(form1.replace("event=sos", "event=resolved"), posts[1].form)
        posts[1].done("{\"ok\":true,\"code\":\"dup\"}")
        assertNull(kv.m[SosMail.K_LIST])
    }

    @Test fun nothing_before_server_confirm_and_read_fallbacks() {
        val kv = Kv(); val tr = FakeSosTransport(site = "root/sos/WF11"); val (l, _) = pair(kv, tr)
        l.begin(rec())
        assertEquals(0, posts.size)
        tr.creates[0].cb(false)
        assertEquals(0, posts.size)
        tr.reads[0].cb(Remote.MINE_ACTIVE)
        assertEquals(1, posts.size)
        posts[0].done(sent)

        l.resolve()
        tr.resolves[0].cb(false)
        tr.reads[1].cb(Remote.ABSENT)
        assertEquals(1, posts.size)
        assertNull(kv.m[SosMail.K_LIST])

        l.begin(rec())
        tr.creates[1].cb(true)
        posts[1].done(sent)
        l.resolve()
        tr.resolves[1].cb(false)
        tr.reads[2].cb(Remote.MINE_RESOLVED)
        assertEquals(3, posts.size)
        assertTrue(posts[2].form.contains("id=k2&event=resolved"))
    }

    @Test fun valid_address_rules() {
        assertTrue(SosMail.validAddress("wfspt@coupangfs.com"))
        assertTrue(SosMail.validAddress("a.b+c@x.co.kr"))
        assertFalse(SosMail.validAddress(""))
        assertFalse(SosMail.validAddress("a@b"))
        assertFalse(SosMail.validAddress("a b@x.com"))
        assertFalse(SosMail.validAddress("@x.com"))
        assertFalse(SosMail.validAddress("a@x..com"))
        assertTrue(SosMail.validAddress("a.b@x.com"))
        assertFalse(SosMail.validAddress(".a@x.com"))
        assertFalse(SosMail.validAddress("a.@x.com"))
        assertFalse(SosMail.validAddress("a..b@x.com"))
        assertFalse(SosMail.validAddress("a".repeat(246) + "@x.com.kr"))
    }

    @Test fun enqueue_ignores_blank_or_bad_address_bad_path_and_unknown_event() {
        val kv = Kv(); val m = mail(kv)
        to = ""
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k1", 3)
        to = "not an address"
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k1", 3)
        to = SosMail.DEFAULT_TO
        m.enqueue(SosMail.EVENT_SOS, "root/WF11", "k1", 3)
        m.enqueue(SosMail.EVENT_SOS, "root/sos/", "k1", 3)
        m.enqueue("other", "root/sos/WF11", "k1", 3)
        assertEquals(0, posts.size)
        assertNull(kv.m[SosMail.K_LIST])
    }

    @Test fun enqueue_ignores_a_duplicate_key_and_caps_still_minutes_at_30() {
        val kv = Kv(); val m = mail(kv)
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k1", 99)
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k1", 3)
        assertEquals(1, posts.size)
        assertTrue(posts[0].form.endsWith("stillMin=30"))
        assertEquals(1, kv.m[SosMail.K_LIST]!!.split('\n').size)
    }

    @Test fun outcome_table() {
        for (c in listOf("sent", "dup")) assertEquals(Outcome.DONE, SosMail.outcome("{\"ok\":true,\"code\":\"$c\"}"))
        for (c in listOf("bad_request", "not_allowed", "stale")) {
            assertEquals(Outcome.DROP, SosMail.outcome("{\"ok\":false,\"code\":\"$c\"}"))
        }
        for (c in listOf("not_ready", "busy", "quota", "error", "whatever")) {
            assertEquals(Outcome.RETRY, SosMail.outcome("{\"ok\":false,\"code\":\"$c\"}"))
        }
        assertEquals(Outcome.RETRY, SosMail.outcome(null))
        assertEquals(Outcome.RETRY, SosMail.outcome("<html>sign in</html>"))
        assertEquals(Outcome.DONE, SosMail.outcome("{ \"ok\" : true, \"code\" : \"dup\" }"))
        assertEquals("busy", SosMail.code("{\"ok\":false,\"code\":\"busy\"}"))
        assertNull(SosMail.code(null))
        assertNull(SosMail.code("<html>sign in</html>"))
    }

    @Test fun retry_backoff_then_done_or_drop_removes() {
        val kv = Kv(); val m = mail(kv)
        val t0 = now
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k1", 3)
        posts[0].done(null)
        now = t0 + 9_000; m.tick()
        assertEquals(1, posts.size)
        now = t0 + 10_000; m.tick()
        assertEquals(2, posts.size)
        posts[1].done("{\"code\":\"busy\"}")
        val t1 = now
        now = t1 + 19_000; m.tick()
        assertEquals(2, posts.size)
        now = t1 + 20_000; m.tick()
        assertEquals(3, posts.size)
        posts[2].done("{\"code\":\"not_allowed\"}")
        assertNull(kv.m[SosMail.K_LIST])
    }

    @Test fun in_flight_is_not_sent_twice() {
        val kv = Kv(); val m = mail(kv)
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k1", 3)
        m.tick(); m.tick()
        assertEquals(1, posts.size)
    }

    @Test fun gives_up_after_2_hours_or_clock_back() {
        val kv = Kv(); val m = mail(kv)
        val t0 = now
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k1", 3)
        posts[0].done(null)
        now = t0 + 119 * 60_000L; m.tick()
        assertEquals(2, posts.size)
        posts[1].done(null)
        now = t0 + 2 * 3_600_000L + 1; m.tick()
        assertEquals(2, posts.size)
        assertNull(kv.m[SosMail.K_LIST])

        now = t0
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k2", 3)
        posts[2].done(null)
        now = t0 - 120_000; m.tick()
        assertEquals(3, posts.size)
        assertNull(kv.m[SosMail.K_LIST])
    }

    @Test fun site_root_slashes_trimmed_and_slash_only_root_ignored() {
        val kv = Kv(); val m = mail(kv)
        m.enqueue(SosMail.EVENT_SOS, "/wf11/sos/WF11", "k1", 3)
        m.enqueue(SosMail.EVENT_SOS, "wf11//sos/WF11", "k2", 3)
        m.enqueue(SosMail.EVENT_SOS, "//sos/WF11", "k3", 3)
        assertEquals(2, posts.size)
        assertTrue(posts.all { it.form.startsWith("site=wf11&sc=WF11&") })
    }

    @Test fun storage_written_only_when_queue_changes() {
        val kv = Kv(); val m = mail(kv)
        m.tick()
        assertEquals(0, kv.puts)
        val t0 = now
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k1", 3)
        assertEquals(1, kv.puts)
        posts[0].done(null)
        m.tick()
        assertEquals(1, kv.puts)
        // given up while in flight: the late answer finds nothing to remove
        now = t0 + 10_000; m.tick()
        now = t0 + 2 * 3_600_000L + 1; m.tick()
        assertEquals(2, kv.puts)
        posts[1].done(sent)
        assertEquals(2, kv.puts)
    }

    @Test fun drain_reports_once_after_last_answer() {
        val kv = Kv(); val m = mail(kv)
        val got = ArrayList<Boolean>()
        m.drain { got.add(it) }
        assertEquals(listOf(false), got)

        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k1", 3)
        m.drain { got.add(it) }
        assertEquals(1, got.size)
        posts[0].done(null)
        assertEquals(listOf(false, true), got)
        m.drain { got.add(it) }
        assertEquals(listOf(false, true, true), got)

        now += 10_000
        m.drain { got.add(it) }
        assertEquals(3, got.size)
        posts[1].done(sent)
        assertEquals(listOf(false, true, true, false), got)
        m.tick()
        assertEquals(4, got.size)
    }

    @Test fun resolved_goes_right_after_sos_is_done() {
        val kv = Kv(); val m = mail(kv)
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k1", 3)
        m.enqueue(SosMail.EVENT_RESOLVED, "root/sos/WF11", "k1", 3)
        assertEquals(1, posts.size)
        posts[0].done(sent)
        assertEquals(2, posts.size)
        assertTrue(posts[1].form.contains("event=resolved"))
    }

    @Test fun on_queue_true_when_filled_false_when_emptied() {
        val kv = Kv(); val m = mail(kv)
        m.tick()
        assertTrue(queue.isEmpty())
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k1", 3)
        assertEquals(listOf(true), queue)
        posts[0].done(sent)
        assertEquals(listOf(true, false), queue)
    }

    @Test fun restart_resumes_from_storage_and_skips_broken_lines() {
        val kv = Kv()
        mail(kv).enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k1", 3)
        kv.m[SosMail.K_LIST] = "garbage\nsos\troot\tWF11\tkx\tx@y.com\tnope\t1\n" + kv.m[SosMail.K_LIST]
        posts.clear()
        queue.clear()
        val m2 = mail(kv)
        assertEquals(listOf(true), queue)
        m2.tick()
        assertEquals(1, posts.size)
        assertEquals(form1, posts[0].form)
    }

    @Test fun resolved_goes_to_address_the_sos_was_queued_with() {
        val kv = Kv()
        to = "a@coupangfs.com"
        mail(kv).enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k1", 3)
        posts[0].done(sent)
        assertEquals(1, asked.size)
        to = "b@coupangfs.com"
        val m = mail(kv)
        m.enqueue(SosMail.EVENT_RESOLVED, "root/sos/WF11", "k1", 3)
        assertEquals(2, posts.size)
        assertTrue(posts[1].form.contains("event=resolved&to=a%40coupangfs.com&"))
        assertNull(kv.m[SosMail.K_ADDR])
        assertEquals(1, asked.size)
    }

    @Test fun resolved_without_a_sos_mail_queued_on_this_device_is_not_sent() {
        val kv = Kv(); val m = mail(kv)
        m.enqueue(SosMail.EVENT_RESOLVED, "root/sos/WF11", "k9", 3)
        assertEquals(0, posts.size)
        assertNull(kv.m[SosMail.K_LIST])
    }

    @Test fun address_rows_older_than_7_days_are_dropped_when_a_new_sos_is_queued() {
        val kv = Kv(); val m = mail(kv)
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k2", 3)
        posts[0].done(sent)
        now += SosMail.KEEP_ADDR_MS + 1
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k3", 3)
        assertEquals(listOf("k3"), kv.m[SosMail.K_ADDR]!!.split('\n').map { it.substringBefore('\t') })
    }

    @Test fun script_url_only_web_app_exec_form() {
        val ok = "https://script.google.com/macros/s/AKfy_c-1/exec"
        assertEquals(ok, SosMail.scriptUrl(ok))
        assertEquals(ok, SosMail.scriptUrl("  $ok \n"))
        for (bad in listOf(
            "https://script.google.com/macros/s/AKfy_c-1/dev",
            "https://script.google.com/macros/s/AKfy_c-1/edit",
            "http://script.google.com/macros/s/AKfy_c-1/exec",
            "https://script.google.com/macros/s/AKfy_c-1/exec?x=1",
            "https://script.google.com/a/macros/dom/s/x/exec",
            "https://script.google.com/",
            ""
        )) assertEquals(bad, "", SosMail.scriptUrl(bad))
    }

    @Test fun resolve_lookup_skips_address_rows_older_than_7_days() {
        val kv = Kv(); val m = mail(kv)
        val t0 = now
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k1", 3)
        posts[0].done(sent)
        now = t0 + SosMail.KEEP_ADDR_MS
        m.enqueue(SosMail.EVENT_RESOLVED, "root/sos/WF11", "k1", 3)
        assertEquals(2, posts.size)
        assertTrue(posts[1].form.contains("id=k1&event=resolved&"))
        posts[1].done(sent)

        val t1 = now
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k2", 3)
        posts[2].done(sent)
        now = t1 + SosMail.KEEP_ADDR_MS + 1
        m.enqueue(SosMail.EVENT_RESOLVED, "root/sos/WF11", "k2", 3)
        assertEquals(3, posts.size)
        assertNull(kv.m[SosMail.K_LIST])
    }

    @Test fun dropped_sos_also_drops_its_waiting_resolve_and_address() {
        val kv = Kv(); val m = mail(kv)
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k1", 3)
        m.enqueue(SosMail.EVENT_RESOLVED, "root/sos/WF11", "k1", 3)
        assertEquals(1, posts.size)
        posts[0].done("{\"code\":\"stale\"}")
        assertEquals(1, posts.size)
        assertNull(kv.m[SosMail.K_LIST])
        assertNull(kv.m[SosMail.K_ADDR])
    }

    @Test fun dropped_sos_forgets_its_address_so_a_later_resolve_is_not_queued() {
        val kv = Kv(); val m = mail(kv)
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k3", 3)
        posts[0].done("{\"code\":\"not_allowed\"}")
        assertNull(kv.m[SosMail.K_LIST])
        assertNull(kv.m[SosMail.K_ADDR])
        m.enqueue(SosMail.EVENT_RESOLVED, "root/sos/WF11", "k3", 3)
        assertEquals(1, posts.size)
    }

    // Giving up after 2 hours keeps the address row: the mail may have gone out, so its resolve must follow.
    @Test fun given_up_sos_keeps_its_address_so_a_later_resolve_is_sent() {
        val kv = Kv(); val m = mail(kv)
        val t0 = now
        m.enqueue(SosMail.EVENT_SOS, "root/sos/WF11", "k2", 3)
        posts[0].done(null)
        now = t0 + SosMail.GIVE_UP_MS + 1; m.tick()
        assertEquals(1, posts.size)
        assertNull(kv.m[SosMail.K_LIST])
        assertTrue(kv.m[SosMail.K_ADDR]!!.startsWith("k2\t"))
        m.enqueue(SosMail.EVENT_RESOLVED, "root/sos/WF11", "k2", 3)
        assertEquals(2, posts.size)
        assertTrue(posts[1].form.contains("id=k2&event=resolved&"))
    }
}
