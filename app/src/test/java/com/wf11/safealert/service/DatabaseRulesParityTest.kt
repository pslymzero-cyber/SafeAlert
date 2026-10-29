package com.wf11.safealert.service

import com.wf11.safealert.ble.SosAdvert
import com.wf11.safealert.utils.SitePin
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
        // floor / process / zone and the worker code are unchanged on resolve as well (listed with the other fixed fields)
        for (f in listOf("uid", "name", "role", "trigger", "beacon", "beaconRssi", "ep", "floor", "proc", "zone", "worker", "relayBy", "sosId")) {
            assertTrue(f, w.contains("newData.child('$f').val() === data.child('$f').val()"))
        }
        // A phone may record another phone's SOS (SosRelay): trigger relay is allowed, and relayBy is a short string that never changes
        val rules = repoFile("database.rules.json")
        assertTrue(rules.contains("newData.val() === '${SosRelay.TRIGGER}'"))
        val relayBy = rules.substringAfter("\"relayBy\": {").substringBefore("}")
        assertTrue(relayBy, relayBy.contains("length <= 64") && relayBy.contains("!data.exists() || newData.val() === data.val()"))
        // The SOS ID: the app's pattern, a string, and never changed once written
        val sosIdRule = rules.substringAfter("\"sosId\": {").substringBefore("\"\$other\"")
        assertTrue(sosIdRule, sosIdRule.contains(".matches(/${SosAdvert.SOS_ID_PATTERN}/)") && sosIdRule.contains("!data.exists() || newData.val() === data.val()"))
        // The worker code: the app's pattern, a string, and never changed once written
        val workerRule = rules.substringAfter("\"worker\": {").substringBefore("\"\$other\"")
        assertTrue(workerRule, workerRule.contains(".matches(/${com.wf11.safealert.utils.WorkerCode.PATTERN}/)") && workerRule.contains("!data.exists() || newData.val() === data.val()"))
        // Floor / process / zone: the same pattern as the app's SiteScope in sos, hb and alerts (three fields each); there is no
        //   site list node (the lists are built into the app).
        val s = repoFile("database.rules.json")
        val rule = ".matches(/${SiteScope.CODE_PATTERN}/)"
        assertTrue(rule, Regex(Regex.escape(rule)).findAll(s).count() >= 9)
        assertTrue("sites 노드가 없어야 한다", !s.contains("\"sites\""))
    }

    @Test fun gap_key_rule_matches_max_gaps() {
        val re = Regex(hbBlock().substringAfter("\$n.matches(/").substringBefore("/)"))
        for (n in 0..LoneWorkerHeartbeat.MAX_GAPS + 10) {
            assertEquals("g/$n", n < LoneWorkerHeartbeat.MAX_GAPS, re.matches(n.toString()))
        }
        assertTrue(!re.matches("-1") && !re.matches("00"))

        // the hb clock slack and the g object
        val hb = hbBlock()
        assertEquals(4, Regex("""<= now \+ 60000(?!\d)""").findAll(hb).count())
        assertEquals(0, Regex("""<= now(?! \+ 60000(?!\d))""").findAll(hb).count())
        assertTrue(hb.substringAfter("\"g\": {").trimStart().startsWith("\".validate\": \"newData.hasChildren()\""))
        // a gap may start before its session start (baseline carried over a restart)
        assertTrue(!hb.substringAfter("\"g\": {").substringBefore("\"\$other\"").contains("start"))
    }

    // The control page listens to hb/<center> by 'last' and alerts/<center>/<day> by 'timestamp' itself: a signed-in reader
    //   must be let in there and those keys indexed (a refused listen leaves the page on the slow server reads).
    @Test fun control_page_live_reads_match_the_rules() {
        val s = repoFile("database.rules.json")
        val page = repoFile("scripts/control-page/Index.html")
        val hb = hbBlock().substringAfter("\"\$sc\": {")
        val alerts = s.substringAfter("\"alerts\": {").substringAfter("\"\$b\": {")
        for ((node, key) in listOf(hb to "last", alerts to "timestamp")) {
            val own = node.substringBefore("{")   // the node's own rules, before its first child
            assertTrue(own, own.contains("\".read\": \"auth != null\""))
            assertTrue(own, own.contains("\".indexOn\": [\"$key\"]"))
            assertTrue(key, page.contains("orderByChild('$key')"))
        }
    }

    // The site PIN nodes: only the check value (pub) is readable; the gate, the proof and the change log are not; the iteration
    //   limits and hex lengths match SitePin; a change needs the stored gate as proof and the next version; the log is append-only.
    @Test fun site_pin_rules_match_the_app() {
        val s = repoFile("database.rules.json")
        val a = s.indexOf("\"pin\": {")
        val b = s.indexOf("\"pinlog\": {")
        assertTrue(a >= 0 && b > a)
        val pin = s.substring(a, b)
        val log = s.substring(b)
        assertTrue(pin.contains(">= ${SitePin.MIN_ITER}") && pin.contains("<= ${SitePin.MAX_ITER}"))
        assertTrue(pin.contains("{32}") && pin.contains("{64}"))
        assertTrue(pin.contains("newData.child('proof').val() === data.child('sec/gate').val()"))
        assertTrue(pin.contains("data.child('pub/ver').val() + 1"))
        val pub = pin.substring(pin.indexOf("\"pub\": {"), pin.indexOf("\"sec\": {"))
        assertTrue(pub.contains("\".read\""))
        assertTrue(!pin.substring(pin.indexOf("\"sec\": {")).contains("\".read\""))
        assertTrue(!log.contains("\".read\""))
        assertTrue(log.contains("!data.exists()"))
    }
}
