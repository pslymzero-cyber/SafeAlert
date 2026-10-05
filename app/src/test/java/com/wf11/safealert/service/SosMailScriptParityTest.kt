package com.wf11.safealert.service

import com.wf11.safealert.model.PitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** App rules vs. the rescue mail script (scripts/sos-mail/Code.gs): they must agree. */
class SosMailScriptParityTest {

    private fun script() = repoFile("scripts/sos-mail/Code.gs")

    private fun scriptMaxTo(gs: String) = Regex("""to\.length > (\d+)""").find(gs)!!.groupValues[1].toInt()

    private fun roleNameBody(gs: String) = gs.substringAfter("function roleName(r, name) {").substringBefore("\n}")

    @Test fun address_rule_matches_script_to_re() {
        val gs = script()
        val max = scriptMaxTo(gs)
        val toRe = Regex(gs.substringAfter("var TO_RE = /").substringBefore("/;\n"))
        val samples = listOf(
            "a@b.co", "a%b@x.com", "a.b+c%d_e-f@x-y.co.kr", ".a@x.com", "a.@x.com", "a..b@x.com", "a b@x.com",
            "a@b", "a@b..com", "a@.b.com", "@x.com", "a@", "", "a@b.co\n", "ü@x.com", "a@b_c.com",
            "a@" + "b".repeat(248) + ".com", "a@" + "b".repeat(249) + ".com"
        )
        val app = samples.map { SosMail.validAddress(it) }
        assertEquals(samples.map { it.length <= max && toRe.matches(it) }, app)
        assertTrue(app.contains(true) && app.contains(false))
    }

    // The address field on the developer settings screen, the app and the script all stop at the same length.
    @Test fun keep_fresh_and_address_length_match_script_and_address_field() {
        val gs = script()
        assertTrue(gs.contains("var DAY_MS = 24 * 3600 * 1000;"))
        val keepDays = Regex("""var KEEP_MS = (\d+) \* DAY_MS;""").find(gs)!!.groupValues[1].toLong()
        assertEquals(SosMail.KEEP_ADDR_MS, keepDays * 86_400_000L)
        val freshHours = Regex("""var FRESH_MS = (\d+) \* 3600 \* 1000;""").find(gs)!!.groupValues[1].toLong()
        assertEquals(SosMail.GIVE_UP_MS, freshHours * 3_600_000L)
        val max = scriptMaxTo(gs)
        assertEquals(254, max)

        val layout = repoFile("app/src/main/res/layout/activity_dev_settings.xml")
        val id = "android:id=\"@+id/et_sos_mail_to\""
        assertTrue(layout.contains(id))
        val field = layout.substringAfter(id).substringBefore("/>")
        assertEquals(max, Regex("""android:maxLength="(\d+)"""").find(field)!!.groupValues[1].toInt())
    }

    @Test fun pit_names_match_pit_type() {
        val gs = script()
        val block = gs.substringAfter("var PIT_NAMES = {").substringBefore("};")
        val map = Regex("""([A-Z]{2}): '([^']+)'""").findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(PitType.values().associate { it.code to it.label }, map)

        // roleName's id rule (regex on trim().toUpperCase(), number '00' refused, code must be named) vs PitType.parse
        val body = roleNameBody(gs)
        val re = Regex(body.substringAfter("var m = /").substringBefore("/.exec(str(name).trim().toUpperCase())"))
        assertTrue(body.contains("m[2] !== '00'") && body.contains("PIT_NAMES[m[1]]"))
        fun scriptLabel(id: String): String? =
            re.find(id.trim().uppercase())?.takeIf { it.groupValues[2] != "00" }?.let { map[it.groupValues[1]] }
        val ids = listOf("CB-01", "RT-99", "CB-00", "CB-100", "cb-01", " rt-07 ", "XX-01", "CB01", "RT-7", "")
        val labels = ids.map { PitType.parse(it)?.first?.label }
        assertEquals(ids.map { scriptLabel(it) }, labels)
        assertTrue(labels.contains(null) && labels.any { it != null })
    }

    // The role shown for an SOS: walker always "보행자", otherwise the equipment name from a valid id, otherwise the
    //   role's own label. The script's roleName returns the same per-role labels.
    @Test fun sos_role_label_rules_match_script_role_name() {
        val walker = "보행자"
        val forklift = "지게차"
        val unknown = "알 수 없음"
        val body = roleNameBody(script())
        val byRole = Regex("""if \(r === '([A-Z]+)'\) return '([^']+)';""").findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(mapOf("WALKER" to walker, "FORKLIFT" to forklift, "EPJ" to "EPJ"), byRole)
        assertTrue(body.trimEnd().endsWith("return '$unknown';"))
        // Same order as the app: walker first, then the equipment name, then the per-role label
        val at = listOf("if (r === 'WALKER')", "PIT_NAMES[", "if (r === 'FORKLIFT')", "if (r === 'EPJ')").map { body.indexOf(it) }
        assertTrue("roleName order $at", at.all { it >= 0 } && at.zipWithNext().all { (a, b) -> a < b })

        val cases = listOf(
            Triple("FORKLIFT", "RT-07", "Reach Truck"), Triple("EPJ", "WK-02", "Walkie Stacker"),
            Triple("FORKLIFT", " rt-07 ", "Reach Truck"), Triple("WALKER", "RT-07", walker),
            Triple("FORKLIFT", "Lee", forklift), Triple("FORKLIFT", "RT-00", forklift),
            Triple("FORKLIFT", "XX-01", forklift), Triple("EPJ", "Kim", "EPJ"), Triple("UNKNOWN", "Kim", unknown)
        )
        for ((role, name, want) in cases) assertEquals("$role $name", want, sosRoleLabel(role, name))
    }
}
