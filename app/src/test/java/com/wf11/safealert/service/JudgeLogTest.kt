package com.wf11.safealert.service

import com.wf11.safealert.firebase.JudgeLog
import com.wf11.safealert.service.LoneWorkerLogic.JudgeEvent
import com.wf11.safealert.service.LoneWorkerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            JudgeLog.on = true; JudgeLog.accOn = true; JudgeLog.session = "test:fall:mat1"
            JudgeLog.env = { if (signedIn) env else null }
            JudgeLog.sink = { kind, rec -> out += kind to rec }
            body(out)
        } finally {
            JudgeLog.on = on; JudgeLog.accOn = accOn; JudgeLog.session = session
            JudgeLog.env = oldEnv; JudgeLog.sink = oldSink
        }
    }

    private fun frame(peer: String, peerRole: String, t: Long, ema: Int) =
        JudgeLog.frame(peer, "WALKER", peerRole, t, -61, -62, -62, -62, 1.26, ema, -63, 8, 0, 0, 1, -87, -74)

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
            assertEquals("2F", r["floor"]); assertEquals("1.2.20", r["app"]); assertEquals("test:fall:mat1", r["sess"])
            assertEquals("U1", r["uid"]); assertEquals("WF11-JL1", r["peer"]); assertEquals("FORKLIFT", r["peer_role"])
            assertEquals(1, r["prev"]); assertEquals(2, r["level"])
            assertEquals(-61, r["rssi"]); assertEquals(-62, r["med"]); assertEquals(1.26, r["kf_vel"])
            assertEquals(-59, r["ema"]); assertEquals(8, r["off_pl"]); assertEquals(-74, r["eff_d"])
            // Ring keeps the last 10 one-second summaries (ema -68 .. -59), kf_vel with one decimal
            assertEquals((-68..-59).joinToString(";") { "$it,1.3" }, r["seq"])

            // level: 2 s gap per peer; uwb: 1 s gap, pair thresholds by role
            JudgeLog.level("ME", "JL1", 2, 1, 13_999); assertEquals(1, out.size)
            JudgeLog.level("ME", "JL1", 2, 1, 14_000); assertEquals(2, out.size)
            JudgeLog.uwb("ME", "JL1", 7.123f, 2, 20_000)
            JudgeLog.uwb("ME", "JL1", 7.0f, 2, 20_999); assertEquals(3, out.size)
            val fk = out.last().second
            assertEquals("uwb", fk["k"]); assertEquals(7.12, fk["uwb_m"]); assertEquals(15.0, fk["warn_m"]); assertEquals(8.0, fk["dang_m"])
            frame("JL2", "WALKER", 30_000, -70)
            JudgeLog.uwb("ME", "JL2", 2.5f, 1, 30_000)
            val wk = out.last().second
            assertEquals(5.0, wk["warn_m"]); assertEquals(3.0, wk["dang_m"]); assertEquals(1, wk["level"])

            // fall records: absent values are left out
            out.clear()
            JudgeLog.fallCand("ME", "WALKER", 5_000, 300, 4.0, 2.5, null, 500, 1, true, false, "post_active", null, null, null, t = 9L)
            val c = out.single().second
            assertEquals("fall", out.single().first); assertEquals("cand", c["k"]); assertEquals("post_active", c["why"])
            assertFalse(c.containsKey("acc") || c.containsKey("acc_t0") || c.containsKey("acc_dt") || c.containsKey("deg"))
            JudgeLog.mode("ME", "WALKER", "WATCHING", "CHECKING", "fall", "", 7, null, t = 9L)
            val m = out.last().second
            assertFalse(m.containsKey("why") || m.containsKey("imp_t") || m.containsKey("truth"))

            // Site code: valid kept, otherwise NOSITE
            assertEquals("WF11", JudgeLog.scOf("WF11"))
            assertEquals("NOSITE", JudgeLog.scOf("wf11")); assertEquals("NOSITE", JudgeLog.scOf(""))
        }
    }

    @Test fun logic_reports_takes_and_close_reasons() {
        // Fall: watch, then CHECKING, a second FALL during the fall check is shut, ack closes with why ack
        val ev = mutableListOf<JudgeEvent>()
        val l = fallRuleOnly().apply { onJudge = { ev += it } }
        l.onAccident(6_000)
        assertEquals(JudgeEvent.Fall(6_000, "watch", false, false, null, null), ev.single())
        assertEquals(Mode.CHECKING, l.seenAt(36_000))
        val open = ev.last() as JudgeEvent.ModeChange
        assertEquals(Mode.WATCHING, open.from); assertEquals(Mode.CHECKING, open.to)
        assertEquals("fall", open.trig); assertEquals(6_000L, open.impT)
        l.onAccident(40_000)
        assertEquals("shut", (ev.last() as JudgeEvent.Fall).take)
        assertTrue(l.ackWorking(45_000))
        val ack = ev.last() as JudgeEvent.ModeChange
        assertEquals(JudgeEvent.ModeChange(Mode.CHECKING, Mode.WATCHING, "fall", "ack", 9_000, 6_000), ack)

        // Still check closed by walking: why walk, no impact time
        ev.clear()
        val s = fallRuleOnly().apply { stillMs = 180_000L; onJudge = { ev += it } }
        assertEquals(Mode.CHECKING, s.seenAt(180_000))
        s.walk(185_000, 10)
        val walk = ev.last() as JudgeEvent.ModeChange
        assertEquals(Mode.WATCHING, walk.to); assertEquals("still", walk.trig); assertEquals("walk", walk.why); assertNull(walk.impT)

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
    }
}
