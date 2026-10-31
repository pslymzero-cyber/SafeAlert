package com.wf11.safealert.service

import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.firebase.JudgeLog
import com.wf11.safealert.service.LoneWorkerLogic.JudgeEvent
import com.wf11.safealert.ui.JudgeQuestion
import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Judge-data records (JudgeLog): the record format and rate limits, and the events the lone-worker logic and the
 * motion analyzer hand to it. Recording only — nothing here may change a judgment.
 */
class JudgeLogTest {

    private val env = JudgeLog.Env("WF11", mapOf("floor" to "2F"), "1.2.20", "U1", 15.0, 8.0, 5.0, 3.0)

    /** Runs body with JudgeLog on and a collecting sink; restores the shared object afterwards. */
    private fun withLog(signedIn: Boolean = true, body: (MutableList<Pair<String, Map<String, Any>>>) -> Unit) {
        val on = JudgeLog.on; val accOn = JudgeLog.accOn; val session = JudgeLog.session
        val oldEnv = JudgeLog.env; val oldSink = JudgeLog.sink
        val out = mutableListOf<Pair<String, Map<String, Any>>>()
        try {
            JudgeLog.on = true; JudgeLog.accOn = true; JudgeLog.session = "field"
            JudgeLog.env = { if (signedIn) env else null }
            JudgeLog.sink = { kind, rec -> out += kind to rec }
            body(out)
        } finally {
            JudgeLog.on = on; JudgeLog.accOn = accOn; JudgeLog.session = session
            JudgeLog.env = oldEnv; JudgeLog.sink = oldSink
        }
    }

    private fun frame(peer: String, peerRole: String, t: Long, ema: Int) =
        JudgeLog.frame("ME", peer, "WALKER", peerRole, t, -61, -62, -62, -62, 1.26, ema, -63, 8, 0, 0, 1, -87, -74)

    @Test fun records_carry_the_format_fields_with_rate_limits() {
        withLog { out ->
            // Off: nothing reaches the sink
            JudgeLog.on = false
            frame("JL1", "FORKLIFT", 1_000, -70)
            JudgeLog.level("ME", "JL1", 0, 1, 1_000)
            JudgeLog.mode("ME", "WALKER", "WATCHING", "CHECKING", "fall", "", 5, 1L)
            assertTrue(out.isEmpty())
            JudgeLog.on = true

            // Not signed in: dropped
            withLog(signedIn = false) { none ->
                JudgeLog.fallTake("ME", "WALKER", 1L, "watch", false, false, null, null)
                assertTrue(none.isEmpty())
            }

            // No frame memory for a peer: level / uwb dropped
            JudgeLog.level("ME", "JL-none", 0, 1, 1_000)
            JudgeLog.uwb("ME", "JL-none", 2f, 2, 1_000)
            assertTrue(out.isEmpty())

            // level: format fields, last frame values, seq oldest first, no nulls
            for (i in 0 until 12) frame("JL1", "FORKLIFT", 1_000L * (i + 1), -70 + i)
            JudgeLog.level("ME", "JL1", 1, 2, 12_000)
            val (kind, r) = out.single()
            assertEquals("prox", kind)
            assertEquals(1, r["v"]); assertEquals("level", r["k"]); assertEquals(12_000L, r["t"])
            assertEquals("WF11-ME", r["dev"]); assertEquals("WALKER", r["role"]); assertEquals("WF11", r["site"])
            assertEquals("2F", r["floor"]); assertEquals("1.2.20", r["app"]); assertEquals("field", r["sess"])
            assertEquals("U1", r["uid"]); assertEquals("WF11-JL1", r["peer"]); assertEquals("FORKLIFT", r["peer_role"])
            assertEquals(1, r["prev"]); assertEquals(2, r["level"])
            assertEquals(-61, r["rssi"]); assertEquals(-62, r["med"]); assertEquals(1.26, r["kf_vel"])
            assertEquals(-59, r["ema"]); assertEquals(8, r["off_pl"]); assertEquals(-74, r["eff_d"])
            assertTrue("no AI model loaded: no ai_* keys", r.keys.none { it.startsWith("ai_") })
            // mdl (phone model) only when the snapshot has one
            assertFalse(r.containsKey("mdl"))
            assertEquals("samsung SM-S918N", JudgeLog.rec("level", 1L, "ME", "WALKER", env.copy(mdl = "samsung SM-S918N"), emptyMap())["mdl"])
            // Ring keeps the last 10 one-second summaries (ema -68 .. -59), kf_vel with one decimal
            assertEquals((-68..-59).joinToString(";") { "$it,1.3" }, r["seq"])

            // level: 2 s gap per peer; uwb: 1 s gap, pair thresholds by role
            JudgeLog.level("ME", "JL1", 2, 1, 13_999); assertEquals(1, out.size)
            JudgeLog.level("ME", "JL1", 2, 1, 14_000); assertEquals(2, out.size)
            JudgeLog.uwb("ME", "JL1", 7.123f, 2, 20_000)
            JudgeLog.uwb("ME", "JL1", 7.0f, 2, 20_999); assertEquals(3, out.size)
            val fk = out.last().second
            assertEquals("uwb", fk["k"]); assertEquals(7.12, fk["uwb_m"]); assertEquals(15.0, fk["warn_m"]); assertEquals(8.0, fk["dang_m"])
            assertEquals("judge", fk["use"]); assertEquals(8_000L, fk["frame_age_ms"])   // last JL1 frame was at 12_000
            frame("JL2", "WALKER", 30_000, -70)
            JudgeLog.uwb("ME", "JL2", 2.5f, 1, 30_000, use = "measure")
            val wk = out.last().second
            assertEquals(5.0, wk["warn_m"]); assertEquals(3.0, wk["dang_m"]); assertEquals(1, wk["level"])
            assertEquals("measure", wk["use"]); assertEquals(0L, wk["frame_age_ms"])

            // fall records: absent values are left out
            out.clear()
            JudgeLog.fallCand("ME", "WALKER", 5_000, 300, 4.0, 2.5, null, 500, 1, true, false, "post_active", null, null, null, t = 9L)
            val c = out.single().second
            assertEquals("fall", out.single().first); assertEquals("cand", c["k"]); assertEquals("post_active", c["why"])
            assertFalse(c.containsKey("acc") || c.containsKey("acc_t0") || c.containsKey("acc_dt") || c.containsKey("deg"))
            JudgeLog.mode("ME", "WALKER", "WATCHING", "CHECKING", "fall", "", 7, null, t = 9L)
            val m = out.last().second
            assertFalse(m.containsKey("why") || m.containsKey("imp_t") || m.containsKey("truth") || m.containsKey("pass"))
            // The fall rule's result rides on F2 (always) and on F3 when given
            JudgeLog.fallTake("ME", "WALKER", 5_000, "watch", false, false, null, null, false, t = 10L)
            assertEquals(false, out.last().second["pass"])
            JudgeLog.mode("ME", "WALKER", "WATCHING", "CHECKING", "fall", "", 7, null, pass = true, t = 11L)
            assertEquals(true, out.last().second["pass"])

            // Site code: valid kept, otherwise NOSITE
            assertEquals("WF11", JudgeLog.scOf("WF11"))
            assertEquals("NOSITE", JudgeLog.scOf("wf11")); assertEquals("NOSITE", JudgeLog.scOf(""))

            // Fall question answer
            out.clear()
            JudgeLog.truth("ME", "WALKER", 6_000, "fall", "self", 4_000, "ack", t = 50_000)
            val (tk, tr) = out.single()
            assertEquals("fall", tk); assertEquals("truth", tr["k"]); assertEquals(6_000L, tr["imp_t"]); assertEquals("fall", tr["truth"])
            assertEquals("self", tr["by"]); assertEquals(4_000L, tr["after_ms"]); assertEquals("ack", tr["close_why"]); assertEquals("WF11-ME", tr["dev"])
            assertFalse(tr.containsKey("act"))
            JudgeLog.truth("ME", "WALKER", 6_000, "no", "self", 9_000, "cand", t = 51_000, act = "put_down")
            val tr2 = out.last().second
            assertEquals("put_down", tr2["act"]); assertEquals("no", tr2["truth"]); assertEquals("cand", tr2["close_why"])
            // Recording turned off after the question was posted: a plain call records nothing, the answer (force) still does
            out.clear()
            JudgeLog.on = false
            JudgeLog.truth("ME", "WALKER", 6_000, "no", "self", 9_000, "cand", t = 52_000, act = "walk")
            assertTrue(out.isEmpty())
            JudgeLog.truth("ME", "WALKER", 6_000, "no", "self", 9_000, "cand", t = 52_000, act = "walk", force = true)
            assertEquals("walk", out.single().second["act"])
            JudgeLog.on = true
            out.clear()

            // Distance answer, DANGER-start values: markDanger returns four keys (uwb only when at most 1 s old) and emits nothing
            frame("JL3", "FORKLIFT", 60_000, -55)
            JudgeLog.uwb("ME", "JL3", 1.234f, 2, 60_500)
            out.clear()
            val start = JudgeLog.markDanger("JL3", 61_000)!!
            assertEquals(-61, start["start_rssi"]); assertEquals(-55, start["start_ema"])
            assertEquals(1_000L, start["start_frame_age_ms"]); assertEquals(1.23, start["start_uwb_m"]); assertEquals(4, start.size)
            assertTrue(out.isEmpty())
            assertNull(JudgeLog.markDanger("JL-none", 61_000))

            // The tap-moment values (frame, uwb) plus the times and the start values, in one record
            frame("JL3", "FORKLIFT", 66_000, -50)
            JudgeLog.uwb("ME", "JL3", 0.8f, 2, 66_200, use = "measure")
            out.clear()
            JudgeLog.dist("ME", "WALKER", "JL3", 61_000, 63_000, "1to3", start, t = 67_000)
            val (dk, d) = out.single()
            assertEquals("prox", dk); assertEquals("dist", d["k"]); assertEquals("WF11-JL3", d["peer"]); assertEquals("FORKLIFT", d["peer_role"])
            assertEquals(61_000L, d["alert_t"]); assertEquals(2_000L, d["shown_ms"]); assertEquals(6_000L, d["after_ms"]); assertEquals("1to3", d["ans"])
            assertEquals(-50, d["ema"]); assertEquals(1_000L, d["frame_age_ms"]); assertEquals(0.8, d["uwb_m"]); assertEquals("measure", d["uwb_use"])
            assertTrue(d.containsKey("seq"))
            assertEquals(-55, d["start_ema"]); assertEquals(1.23, d["start_uwb_m"]); assertEquals(1_000L, d["start_frame_age_ms"]); assertEquals(-61, d["start_rssi"])
            // A UWB sample older than 1 s is left out; no start values means no start_ keys
            out.clear()
            JudgeLog.dist("ME", "WALKER", "JL3", 70_000, 70_000, "gt5", null, t = 71_000)
            val g = out.single().second
            assertFalse(g.containsKey("uwb_m") || g.containsKey("uwb_use")); assertTrue(g.keys.none { it.startsWith("start_") }); assertTrue(g.containsKey("ema"))
            // A peer without frame memory still records the answer, without frame values
            out.clear()
            JudgeLog.dist("ME", "WALKER", "JL-none", 80_000, 80_000, "lt1", null, t = 81_000)
            val n = out.single().second
            assertEquals("lt1", n["ans"]); assertTrue(n.containsKey("alert_t")); assertFalse(n.containsKey("ema"))
            // Recording off: dist emits nothing and markDanger returns null
            out.clear()
            JudgeLog.on = false
            JudgeLog.dist("ME", "WALKER", "JL3", 90_000, 90_000, "lt1", null, t = 91_000)
            assertTrue(out.isEmpty()); assertNull(JudgeLog.markDanger("JL3", 90_000))
            JudgeLog.on = true

            // P5 sig: experiment sessions only, one per peer per second, pair warn_m / dang_m, uwb_m when a sample at most 1 s old exists
            out.clear()
            frame("SG1", "FORKLIFT", 100_000, -60); frame("SG1", "FORKLIFT", 101_000, -60)
            assertTrue("field session: no sig", out.isEmpty())
            JudgeLog.session = "test:sweep:hall:a"
            frame("SG1", "FORKLIFT", 102_000, -60); frame("SG1", "FORKLIFT", 102_500, -60); assertEquals(1, out.size)
            val s1 = out.single().second
            assertEquals("sig", s1["k"]); assertEquals("test:sweep:hall:a", s1["sess"]); assertEquals("WF11-SG1", s1["peer"]); assertEquals("FORKLIFT", s1["peer_role"])
            assertEquals(0L, s1["frame_age_ms"]); assertEquals(15.0, s1["warn_m"]); assertEquals(8.0, s1["dang_m"]); assertEquals(-60, s1["ema"]); assertTrue(s1.containsKey("seq"))
            assertFalse(s1.containsKey("uwb_m") || s1.containsKey("uwb_use"))
            frame("SG1", "FORKLIFT", 103_000, -60); assertEquals(2, out.size)
            frame("SG2", "WALKER", 103_100, -60); assertEquals(3, out.size)   // each peer has its own second
            assertEquals(5.0, out.last().second["warn_m"]); assertEquals(3.0, out.last().second["dang_m"])
            JudgeLog.uwb("ME", "SG2", 2.5f, 1, 104_000, use = "measure"); assertEquals(4, out.size)
            frame("SG2", "WALKER", 104_100, -60); assertEquals(5, out.size)
            val s2 = out.last().second
            assertEquals("sig", s2["k"]); assertEquals(2.5, s2["uwb_m"]); assertEquals("measure", s2["uwb_use"])
            JudgeLog.session = "field"
            frame("SG2", "WALKER", 106_000, -60); assertEquals(5, out.size)
        }
    }

    @Test fun logic_reports_takes_and_close_reasons() {
        // Fall: watch, then CHECKING, a second FALL during the fall check is shut, ack closes with why ack
        val ev = mutableListOf<JudgeEvent>()
        val l = fallRuleOnly().apply { onJudge = { ev += it } }
        l.onAccident(6_000)
        assertEquals(JudgeEvent.Fall(6_000, "watch", false, false, null, null), ev.single())
        assertTrue((ev.single() as JudgeEvent.Fall).pass)
        assertEquals(Mode.CHECKING, l.seenAt(36_000))
        val open = ev.last() as JudgeEvent.ModeChange
        assertEquals(Mode.WATCHING, open.from); assertEquals(Mode.CHECKING, open.to)
        assertEquals("fall", open.trig); assertEquals(6_000L, open.impT); assertEquals(true, open.pass)
        l.onAccident(40_000)
        assertEquals("shut", (ev.last() as JudgeEvent.Fall).take)
        assertTrue((ev.last() as JudgeEvent.Fall).asks) // a passed fall is asked at once even inside an open window
        // the close question asks the impact that opened the window, not the later fall (asked at once above as 'shut', never late)
        assertNull(l.lateAskWhy(6_000)); assertEquals("open", l.lateAskWhy(40_000))
        assertTrue(l.ackWorking(45_000))
        val ack = ev.last() as JudgeEvent.ModeChange
        assertEquals(JudgeEvent.ModeChange(Mode.CHECKING, Mode.WATCHING, "fall", "ack", 9_000, 6_000, true), ack)
        assertEquals("moved", l.lateAskWhy(6_000)) // WATCHING again: no window will ask it
        // two passed falls before the window opens: the window's close question asks the later one (so the earlier delayed question goes out as 'open')
        val two = fallRuleOnly()
        two.onAccident(6_000); two.onAccident(20_000)
        assertEquals("moved", two.lateAskWhy(20_000)) // still WATCHING
        assertEquals(Mode.CHECKING, two.seenAt(36_000))
        assertNull(two.lateAskWhy(20_000)); assertEquals("open", two.lateAskWhy(6_000))
        // A not-a-fall impact (pass = false) reports the rule result on its take and on the window's mode changes
        val dev = mutableListOf<JudgeEvent>()
        val dl = fallRuleOnly().apply { onJudge = { dev += it } }
        dl.onAccident(6_000, pass = false)
        assertEquals(JudgeEvent.Fall(6_000, "watch", false, false, null, null, false), dev.single())
        assertEquals(Mode.CHECKING, dl.seenAt(36_000))
        assertEquals(false, (dev.last() as JudgeEvent.ModeChange).pass)
        assertTrue(dl.ackWorking(45_000))
        assertEquals(false, (dev.last() as JudgeEvent.ModeChange).pass)
        // Which closes ask the fall question: ack yes; a walk close (no impact time) and the feature turned off no
        assertTrue(ack.asksFall)
        assertFalse(JudgeEvent.ModeChange(Mode.CHECKING, Mode.WATCHING, "fall", "off", 1, 6_000).asksFall)
        // A restored SOS is marked restore with since 0
        ev.clear()
        newLogic().apply { onJudge = { ev += it } }.restoreSos("fall", 5_000, 45_000)
        val restored = ev.last() as JudgeEvent.ModeChange
        assertEquals(Mode.SOS, restored.to); assertEquals("restore", restored.why); assertEquals(0L, restored.sinceMs)
        assertFalse(JudgeEvent.ModeChange(Mode.CHECKING, Mode.WATCHING, "fall", "restore", 1, 6_000).asksFall)

        // Still check closed by walking: why walk, no impact time
        ev.clear()
        val s = fallRuleOnly().apply { stillMs = 180_000L; onJudge = { ev += it } }
        assertEquals(Mode.CHECKING, s.seenAt(180_000))
        s.walk(185_000, 10)
        val walk = ev.last() as JudgeEvent.ModeChange
        assertEquals(Mode.WATCHING, walk.to); assertEquals("still", walk.trig); assertEquals("walk", walk.why); assertNull(walk.impT)
        assertFalse(walk.asksFall)

        // SOS closed by cancelSos: sos_cancel, or the named reason (one-hour release)
        for ((why, expect) in listOf("" to "sos_cancel", "auto_1h" to "auto_1h")) {
            ev.clear()
            val o = newLogic(carried = true).apply { onJudge = { ev += it } }
            for (t in 180_000L..400_000L step 10_000L) o.seenAt(t)
            assertEquals(Mode.SOS, o.mode)
            assertEquals(Mode.SOS, (ev.last() as JudgeEvent.ModeChange).to)
            assertTrue(o.cancelSos(400_000, why = why))
            assertEquals(expect, (ev.last() as JudgeEvent.ModeChange).why)
        }

        // Motion analyzer: one candidate per 12 s decision — pass (lying after the fall) or posture drop (still upright)
        for ((after, pass, why) in listOf(Triple(LYING_STILL, true, ""), Triple(UPRIGHT_STILL, false, "posture"))) {
            val cands = mutableListOf<MotionAnalyzer.Candidate>()
            val a = MotionAnalyzer(onCandidate = { cands += it })
            val before = a.feed(0, 3_000) { UPRIGHT_STILL }.size + a.feed(3_000, 3_260) { FREE_FALL_SAMPLE }.size +
                a.feed(3_260, 3_320) { IMPACT_SAMPLE }.size
            val tail = a.feed(3_320, 18_320) { after }
            assertEquals(0, before)
            // A rejected candidate is reported once as IMPACT (never FALL); both carry the impact time and shape
            assertEquals(if (pass) 1 else 0, tail.count { it.second == MotionAnalyzer.Signal.FALL })
            assertEquals(if (pass) 0 else 1, tail.count { it.second == MotionAnalyzer.Signal.IMPACT })
            val c = cands.single()
            assertEquals(pass, c.pass); assertEquals(why, c.why)
            assertTrue(c.ffMs >= 200); assertTrue(c.peakG > 2.5); assertTrue(c.postN > 0)
            assertEquals(a.eventMs, c.impactT)
            assertEquals(c.ffMs, a.fallShape.freeFallMs)
        }
    }

    @Test fun ask_rules_pick_which_alerts_get_a_question() {
        // Every rise into WARNING or DANGER is asked; staying or falling back is not
        assertTrue(JudgeAsk.alertStart(0, 1)); assertTrue(JudgeAsk.alertStart(0, 2)); assertTrue(JudgeAsk.alertStart(1, 2))
        assertFalse(JudgeAsk.alertStart(1, 1)); assertFalse(JudgeAsk.alertStart(2, 2)); assertFalse(JudgeAsk.alertStart(2, 1))
        assertFalse(JudgeAsk.alertStart(1, 0))

        // When the block may be on screen: equipment (forklift / EPJ, not a cart) only while stopped; nobody without the sidebar
        val w = BleConstants.CAT_WALKER; val ep = BleConstants.CAT_EPJ; val fk = BleConstants.CAT_FORKLIFT
        assertTrue(JudgeAsk.showNow(w, false, false, true)); assertFalse(JudgeAsk.showNow(fk, false, false, true))
        assertTrue(JudgeAsk.showNow(fk, false, true, true)); assertFalse(JudgeAsk.showNow(ep, false, false, true))
        assertTrue(JudgeAsk.showNow(ep, true, false, true))
        assertFalse(JudgeAsk.showNow(w, false, false, false)); assertFalse(JudgeAsk.showNow(fk, false, true, false))

        // One at a time (never queued); closes 30 s after the alert start
        val a = JudgeAsk()
        val st = mapOf<String, Any?>("start_ema" to -55, "start_lv" to 1)
        assertTrue(a.start("A", "A (지게차)", 0, st)); assertTrue(a.isOpen)
        assertFalse(a.start("B", "B (지게차)", 5_000, null))
        assertEquals(0L, a.visible(0, true)!!.shownAt); assertEquals(0L, a.visible(30_000, true)!!.shownAt)
        assertNull(a.visible(30_001, true)); assertFalse(a.isOpen); assertNull(a.answer(30_002))

        // No per-peer or per-hour cap: the same peer is asked again as soon as the last question is closed,
        // and every alert of the hour gets its question (user decision 2026-10-30)
        assertTrue(a.start("A", "A (지게차)", 31_000, null))
        assertEquals(31_000L, a.visible(31_000, true)!!.shownAt); a.answer(32_000)
        val b = JudgeAsk()
        for ((i, p) in listOf("A", "A", "B", "A", "C", "A", "D").withIndex()) {
            val t = i * 40_000L
            assertTrue(b.start(p, p, t, st)); b.visible(t + 1_000, true); assertEquals(p, b.answer(t + 2_000)!!.peer)
        }
        assertTrue(b.start("E", "E", 400_000, st))
        val shown = b.visible(401_000, true)!!
        val got = b.answer(402_000)!!
        assertEquals("E", got.peer); assertEquals(shown.shownAt, got.shownAt); assertEquals(st, got.start)
        assertNull(b.answer(403_000)); assertFalse(b.isOpen)
        // Answering after 30 s from the alert start gets nothing and closes it
        val c = JudgeAsk()
        assertTrue(c.start("A", "A", 0, null)); c.visible(1_000, true)
        assertNull(c.answer(30_001)); assertFalse(c.isOpen)

        // Equipment phone: hidden while moving (answer impossible, question stays open); never shown within 30 s = not asked
        val f = JudgeAsk()
        assertTrue(f.start("F", "F", 0, null))
        assertNull(f.visible(10_000, false)); assertTrue(f.isOpen); assertNull(f.answer(11_000)); assertTrue(f.isOpen)
        assertNull(f.visible(30_001, true)); assertFalse(f.isOpen)
        assertTrue(f.start("F", "F", 40_000, null))
        assertEquals(45_000L, f.visible(45_000, true)!!.shownAt)
        assertNull(f.visible(46_000, false)); assertTrue(f.isOpen)
        assertEquals(45_000L, f.visible(47_000, true)!!.shownAt)
        assertEquals(40_000L, f.answer(50_000)!!.alertT)
        assertTrue(f.start("F", "F", 100_000, null))   // the same peer's next alert is asked again
    }

    @Test
    fun act_answers_map_to_truth_and_close_after_five_minutes() {
        assertEquals(listOf("walk", "run", "put_down", "drop", "charge", "fall", "unknown"), JudgeQuestion.ACTS.map { it.second })
        assertEquals("fall", JudgeQuestion.truthOf("fall")); assertEquals("unknown", JudgeQuestion.truthOf("unknown"))
        for (a in listOf("walk", "run", "put_down", "drop", "charge")) assertEquals("no", JudgeQuestion.truthOf(a))
        assertFalse(JudgeQuestion.actExpired(0, 300_000)); assertTrue(JudgeQuestion.actExpired(0, 300_001))
        // One question per impact: the first ask of an impact time wins, the same time is not asked again, the 16 most recent are kept
        assertTrue(JudgeQuestion.firstAsk(9_000_001L)); assertFalse(JudgeQuestion.firstAsk(9_000_001L))
        for (i in 2L..17L) assertTrue(JudgeQuestion.firstAsk(9_000_000L + i))
        assertTrue(JudgeQuestion.firstAsk(9_000_001L))   // evicted by then: asked once more
        assertFalse(JudgeQuestion.firstAsk(9_000_017L))
        // Unanswered impact questions are kept: newest first, the answered one and ones past 5 minutes are dropped, the same impact replaces
        fun ask(t: Long, posted: Long, why: String = "moved") = JudgeQuestion.ActAsk("ME", "WALKER", t, 0L, posted, why)
        JudgeQuestion.addPending(ask(8_000_001, 1_000)); JudgeQuestion.addPending(ask(8_000_002, 2_000)); JudgeQuestion.addPending(ask(8_000_003, 3_000))
        JudgeQuestion.addPending(ask(8_000_002, 2_000, "open"))
        assertEquals(ask(8_000_002, 2_000, "open"), JudgeQuestion.nextPending(8_000_003, 4_000))
        assertNull(JudgeQuestion.nextPending(8_000_002, 301_500)) // 8_000_001 is past 5 minutes by then
        assertNull(JudgeQuestion.nextPending(0, 301_500)) // empty
    }
}
