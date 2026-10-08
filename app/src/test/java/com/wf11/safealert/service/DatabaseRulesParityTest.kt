package com.wf11.safealert.service

import com.wf11.safealert.utils.SiteScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** App rules vs. the database rules (database.rules.json): they must agree. */
class DatabaseRulesParityTest {

    private fun hbBlock(): String {
        val s = repoFile("database.rules.json")
        val a = s.indexOf("\"hb\": {")
        val b = s.indexOf("\"echo_calib\": {", a)
        assertTrue(a >= 0 && b > a)
        return s.substring(a, b)
    }

    private fun sosWrite(): String {
        val s = repoFile("database.rules.json")
        return s.substring(s.indexOf("\"sos\": {")).substringAfter("\".write\": \"").substringBefore("\",")
    }

    // Other phones may mark an SOS released only as automatic and only after the app's one-hour limit (exactly that
    //   number, not a longer one); the writer may resolve at any time, and a resolve may change or drop nothing else of
    //   the record (whose it is, who, where, why).
    @Test fun sos_auto_release_rule_matches_the_app_limit() {
        val w = sosWrite()
        val auto = "newData.child('reason').val() === 'auto' && now >= data.child('createdAt').val() + " +
            SosLedger.AUTO_RELEASE_MS
        assertTrue(w, Regex(Regex.escape(auto) + "(?!\\d)").containsMatchIn(w))
        assertTrue(w.contains("data.child('uid').val() === auth.uid ||"))
        for (f in listOf("uid", "name", "role", "trigger", "beacon", "beaconRssi", "ep")) {
            assertTrue(f, w.contains("newData.child('$f').val() === data.child('$f').val()"))
        }
    }

    @Test fun gap_key_rule_matches_max_gaps() {
        val re = Regex(hbBlock().substringAfter("\$n.matches(/").substringBefore("/)"))
        for (n in 0..LoneWorkerHeartbeat.MAX_GAPS + 10) {
            assertEquals("g/$n", n < LoneWorkerHeartbeat.MAX_GAPS, re.matches(n.toString()))
        }
        assertTrue(!re.matches("-1") && !re.matches("00"))
    }

    // Floor / process: the same pattern as the app's SiteScope in sos, hb and alerts (two fields each), unchanged on
    //   resolve; there is no site list node (the lists are built into the app).
    @Test fun floor_proc_rules_match_the_app_pattern() {
        val s = repoFile("database.rules.json")
        val rule = ".matches(/${SiteScope.CODE_PATTERN}/)"
        assertTrue(rule, Regex(Regex.escape(rule)).findAll(s).count() >= 6)
        for (k in listOf("floor", "proc")) {
            assertTrue(k, sosWrite().contains("newData.child('$k').val() === data.child('$k').val()"))
        }
        assertTrue("sites 노드가 없어야 한다", !s.contains("\"sites\""))
    }

    @Test fun hb_rules_clock_slack_and_g_object() {
        val hb = hbBlock()
        assertEquals(4, Regex("""<= now \+ 60000(?!\d)""").findAll(hb).count())
        assertEquals(0, Regex("""<= now(?! \+ 60000(?!\d))""").findAll(hb).count())
        assertTrue(hb.substringAfter("\"g\": {").trimStart().startsWith("\".validate\": \"newData.hasChildren()\""))
        // a gap may start before its session start (baseline carried over a restart)
        assertTrue(!hb.substringAfter("\"g\": {").substringBefore("\"\$other\"").contains("start"))
    }
}
