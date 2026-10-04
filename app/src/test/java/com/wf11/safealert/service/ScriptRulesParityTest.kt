package com.wf11.safealert.service

import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.model.PitType
import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** App rules vs. the mail script (Code.gs) and the database rules: they must agree. */
class ScriptRulesParityTest {

    private fun hbBlock(): String {
        val s = repoFile("database.rules.json")
        val a = s.indexOf("\"hb\": {")
        val b = s.indexOf("\"echo_calib\": {", a)
        assertTrue(a >= 0 && b > a)
        return s.substring(a, b)
    }

    private fun script() = repoFile("scripts/sos-mail/Code.gs")

    private fun sosWrite(): String {
        val s = repoFile("database.rules.json")
        return s.substring(s.indexOf("\"sos\": {")).substringAfter("\".write\": \"").substringBefore("\",")
    }

    // Other phones may mark an SOS released only as automatic and only after the app's one-hour limit; the writer may
    //   resolve at any time, and a resolve may change or drop nothing else of the record (whose it is, who, where, why).
    @Test fun sos_auto_release_rule_matches_the_app_limit() {
        val w = sosWrite()
        assertTrue(w.contains("newData.child('reason').val() === 'auto' && now >= data.child('createdAt').val() + " +
            SosLedger.AUTO_RELEASE_MS))
        assertTrue(w.contains("data.child('uid').val() === auth.uid ||"))
        for (f in listOf("uid", "name", "role", "trigger", "beacon", "beaconRssi", "ep")) {
            assertTrue(f, w.contains("newData.child('$f').val() === data.child('$f').val()"))
        }
    }

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

    /** The weekly digest converts the app's default thresholds and role-pair offsets to meters; they must match. */
    @Test fun digest_thresholds_match_app_defaults() {
        val py = repoFile(".github/scripts/uwb_probe.py")
        fun nums(src: String, name: String) = Regex("""(?m)^$name = (.+)$""").find(src)!!.groupValues[1]
            .substringBefore("#").let { line -> Regex("""-?\d+""").findAll(line).map { it.value.toInt() }.toList() }
        assertEquals(listOf(DevSettings.DEFAULT_RSSI_WARNING_ABS, DevSettings.DEFAULT_RSSI_DANGER_ABS), nums(py, "THRESHOLDS"))
        assertEquals(listOf(DevSettings.DEFAULT_FORWARD_APPROACH_BIAS_DB), nums(py, "FORWARD_BIAS_DB"))

        // One entry per probe pairKey, spelled as the app builds it, with the offset computePayloadRiskOffset adds
        val block = py.substringAfter("PAIR_BIAS_DB = {").substringBefore("}")
        val actual = Regex(""""([^"]+)": (-?\d+)""").findAll(block).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        fun key(a: Int, b: Int) = CalibrationEngine.uwbPairKeyFor(a, b)
        val w = BleConstants.CAT_WALKER; val f = BleConstants.CAT_FORKLIFT; val e = BleConstants.CAT_EPJ
        assertEquals(mapOf(
            key(w, w) to 0,
            key(w, e) to DevSettings.DEFAULT_WALKER_VS_EPJ_BIAS_DB,
            key(w, f) to DevSettings.DEFAULT_WALKER_VS_EQUIP_BIAS_DB,
            key(e, e) to DevSettings.DEFAULT_EPJ_VS_EPJ_BIAS_DB,
            key(e, f) to DevSettings.DEFAULT_EQUIP_VS_EQUIP_BIAS_DB,
            key(f, f) to DevSettings.DEFAULT_EQUIP_VS_EQUIP_BIAS_DB), actual)

        val digest = repoFile(".github/scripts/analyze_alerts.py")
        assertEquals(DevSettings.DEFAULT_FIREBASE_THROTTLE_MS, nums(digest, "THROTTLE_DEFAULT_S").single() * 1000L)
    }

    @Test fun pit_names_match_pit_type() {
        val gs = script()
        val block = gs.substringAfter("var PIT_NAMES = {").substringBefore("};")
        val map = Regex("""([A-Z]{2}): '([^']+)'""").findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(PitType.values().associate { it.code to it.label }, map)

        // roleName's id rule (regex on trim().toUpperCase(), number '00' refused, code must be named) vs PitType.parse
        val body = gs.substringAfter("function roleName(r, name) {").substringBefore("\n}")
        val re = Regex(body.substringAfter("var m = /").substringBefore("/.exec(str(name).trim().toUpperCase())"))
        assertTrue(body.contains("m[2] !== '00'") && body.contains("PIT_NAMES[m[1]]"))
        fun scriptLabel(id: String): String? =
            re.find(id.trim().uppercase())?.takeIf { it.groupValues[2] != "00" }?.let { map[it.groupValues[1]] }
        val ids = listOf("CB-01", "RT-99", "CB-00", "CB-100", "cb-01", " rt-07 ", "XX-01", "CB01", "RT-7", "")
        val labels = ids.map { PitType.parse(it)?.first?.label }
        assertEquals(ids.map { scriptLabel(it) }, labels)
        assertTrue(labels.contains(null) && labels.any { it != null })
    }

    @Test fun sos_role_label_matches_mail_rule() {
        val walker = "\uBCF4\uD589\uC790"
        val forklift = "\uC9C0\uAC8C\uCC28"
        val unknown = "\uC54C \uC218 \uC5C6\uC74C"
        val cases = listOf(
            Triple("FORKLIFT", "RT-07", "Reach Truck"), Triple("EPJ", "WK-02", "Walkie Stacker"),
            Triple("FORKLIFT", " rt-07 ", "Reach Truck"), Triple("WALKER", "RT-07", walker),
            Triple("FORKLIFT", "Lee", forklift), Triple("FORKLIFT", "RT-00", forklift),
            Triple("FORKLIFT", "XX-01", forklift), Triple("EPJ", "Kim", "EPJ"), Triple("UNKNOWN", "Kim", unknown)
        )
        for ((role, name, want) in cases) assertEquals("$role $name", want, sosRoleLabel(role, name))
    }
}
