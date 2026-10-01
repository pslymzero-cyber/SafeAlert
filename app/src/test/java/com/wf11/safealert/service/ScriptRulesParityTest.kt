package com.wf11.safealert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** App rules vs. the mail script (Code.gs) and the database rules: they must agree (v1.2.2). */
class ScriptRulesParityTest {

    private fun hbBlock(): String {
        val s = repoFile("database.rules.json")
        val a = s.indexOf("\"hb\": {")
        val b = s.indexOf("\"echo_calib\": {", a)
        assertTrue(a >= 0 && b > a)
        return s.substring(a, b)
    }

    private fun script() = repoFile("scripts/sos-mail/Code.gs")

    private fun scriptMaxTo(gs: String) = Regex("""to\.length > (\d+)""").find(gs)!!.groupValues[1].toInt()

    @Test fun address_rule_matches_script_to_re() {
        val gs = script()
        val max = scriptMaxTo(gs)
        val toRe = Regex(gs.substringAfter("var TO_RE = /").substringBefore("/;\n"))
        val samples = listOf(
            "a@b.co", "a%b@x.com", "a.b+c%d_e-f@x-y.co.kr", ".a@x.com", "a.@x.com", "a..b@x.com", "a b@x.com",
            "a@b", "a@b..com", "a@.b.com", "@x.com", "a@", "", "a@b.co\n", "\u00fc@x.com", "a@b_c.com",
            "a@" + "b".repeat(248) + ".com", "a@" + "b".repeat(249) + ".com"
        )
        val app = samples.map { SosMail.validAddress(it) }
        assertEquals(samples.map { it.length <= max && toRe.matches(it) }, app)
        assertTrue(app.contains(true) && app.contains(false))
    }

    @Test fun gap_key_rule_matches_max_gaps() {
        val re = Regex(hbBlock().substringAfter("\$n.matches(/").substringBefore("/)"))
        for (n in 0..LoneWorkerHeartbeat.MAX_GAPS + 10) {
            assertEquals("g/$n", n < LoneWorkerHeartbeat.MAX_GAPS, re.matches(n.toString()))
        }
        assertTrue(!re.matches("-1") && !re.matches("00"))
    }

    @Test fun hb_rules_clock_slack_and_g_object() {
        val hb = hbBlock()
        assertEquals(4, Regex("""<= now \+ 60000(?!\d)""").findAll(hb).count())
        assertEquals(0, Regex("""<= now(?! \+ 60000(?!\d))""").findAll(hb).count())
        assertTrue(hb.substringAfter("\"g\": {").trimStart().startsWith("\".validate\": \"newData.hasChildren()\""))
        // a gap may start before its session start (baseline carried over a restart)
        assertTrue(!hb.substringAfter("\"g\": {").substringBefore("\"\$other\"").contains("start"))
    }

    @Test fun keep_fresh_and_address_length_match_script() {
        val gs = script()
        assertTrue(gs.contains("var DAY_MS = 24 * 3600 * 1000;"))
        val keepDays = Regex("""var KEEP_MS = (\d+) \* DAY_MS;""").find(gs)!!.groupValues[1].toLong()
        assertEquals(SosMail.KEEP_ADDR_MS, keepDays * 86_400_000L)
        val freshHours = Regex("""var FRESH_MS = (\d+) \* 3600 \* 1000;""").find(gs)!!.groupValues[1].toLong()
        assertEquals(SosMail.GIVE_UP_MS, freshHours * 3_600_000L)
        val max = scriptMaxTo(gs)
        // 254 = maxLength of the address field in activity_dev_settings.xml
        assertEquals(254, max)
    }

    @Test fun digest_gap_matches_app_gap() {
        val py = repoFile(".github/scripts/hb_digest.py")
        val minutes = Regex("""(?m)^GAP_MS = (\d+) \* 60_000""").find(py)!!.groupValues[1].toLong()
        assertEquals(LoneWorkerHeartbeat.GAP_MS, minutes * 60_000L)
    }
}
