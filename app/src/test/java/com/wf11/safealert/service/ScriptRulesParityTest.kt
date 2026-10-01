package com.wf11.safealert.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** App rules vs. the mail script (Code.gs) and the database rules: they must agree (v1.2.2). */
class ScriptRulesParityTest {

    private fun repoFile(rel: String): String =
        listOf(File(rel), File("../$rel")).first { it.exists() }.readText().replace("\r\n", "\n")

    private fun hbBlock(): String {
        val s = repoFile("database.rules.json")
        val a = s.indexOf("\"hb\": {")
        val b = s.indexOf("\"echo_calib\": {", a)
        assertTrue(a >= 0 && b > a)
        return s.substring(a, b)
    }

    @Test fun address_rule_matches_script_to_re() {
        val gs = repoFile("scripts/sos-mail/Code.gs")
        val toRe = Regex(gs.substringAfter("var TO_RE = /").substringBefore("/;\n"))
        val samples = listOf(
            "a@b.co", "a%b@x.com", "a.b+c%d_e-f@x-y.co.kr", ".a@x.com", "a.@x.com", "a..b@x.com", "a b@x.com",
            "a@b", "a@b..com", "a@.b.com", "@x.com", "a@", "", "a@b.co\n", "\u00fc@x.com", "a@b_c.com",
            "a@" + "b".repeat(248) + ".com", "a@" + "b".repeat(249) + ".com"
        )
        val app = samples.map { SosMail.validAddress(it) }
        assertEquals(samples.map { it.length <= 254 && toRe.matches(it) }, app)
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
        assertEquals(4, Regex("<= now \\+ 60000").findAll(hb).count())
        assertEquals(0, Regex("<= now(?! \\+ 60000)").findAll(hb).count())
        assertTrue(hb.substringAfter("\"g\": {").trimStart().startsWith("\".validate\": \"newData.hasChildren()\""))
    }
}
