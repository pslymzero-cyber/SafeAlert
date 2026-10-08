package com.wf11.safealert.service

import com.wf11.safealert.utils.SiteScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control page (scripts/control-page) copies values from the app and from the rescue mail script because it runs on
 * Google's side and cannot import them. This pins every copy so a change in one place fails here instead of showing a
 * different list, rule or name on the control screen.
 */
class ControlPageParityTest {

    private fun page() = repoFile("scripts/control-page/Index.html")
    private fun gs() = repoFile("scripts/control-page/Code.gs")
    private fun mail() = repoFile("scripts/sos-mail/Code.gs")

    private fun quoted(list: String) = Regex("""'([^']*)'""").findAll(list).map { it.groupValues[1] }.toList()

    private fun norm(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }

    // Floor and process lists in the page's pickers are the app's built-in lists.
    @Test fun page_floor_and_process_lists_match_the_app() {
        val html = page()
        val floors = html.substringAfter("var FLOORS = [").substringBefore("];")
        val procs = html.substringAfter("var PROCS = [").substringBefore("];")
        assertEquals(SiteScope.FLOORS, quoted(floors))
        assertEquals(SiteScope.PROCS, quoted(procs))
    }

    // The code rule (floor / process) and the one-hour release are the app's, and the rule is the mail script's too.
    @Test fun code_rule_and_release_time_match_the_app_and_the_mail_script() {
        val code = gs().substringAfter("var CODE_RE = /").substringBefore("/;")
        assertEquals(SiteScope.CODE_PATTERN, code)
        assertTrue("메일 스크립트도 같은 규칙", mail().contains("/$code/.test(rec[k])"))

        val release = Regex("""var SOS_RELEASE_MS = (\d+) \* (\d+);""").find(gs())!!.groupValues
        assertEquals(SosLedger.AUTO_RELEASE_MS, release[1].toLong() * release[2].toLong())
    }

    // Role names: the page's server script, the page itself (it names live SOS) and the mail use the same rule; the equipment table lives in Code.gs only (the page gets it from doGet).
    @Test fun role_names_match_the_app_and_the_mail_script() {
        val g = gs()
        val m = mail()
        val body = g.substringAfter("function roleName_(r, name) {").substringBefore("\n}")
        val mailBody = m.substringAfter("function roleName(r, name) {").substringBefore("\n}")
            .replace("str(name)", "name")   // the mail script sanitises the text there; the page does it before the call
        assertEquals(mailBody, body)
        assertEquals(m.substringAfter("var PIT_NAMES = {").substringBefore("};"), g.substringAfter("var PIT_NAMES = {").substringBefore("};"))

        val html = page()
        assertEquals("알 수 없음", sosRoleLabel("X", ""))
        val pageBody = html.substringAfter("function roleName(r, name) {").substringBefore("\n  }")
        assertEquals("페이지의 역할 규칙 = Code.gs roleName_", norm(body), norm(pageBody))
        assertFalse("장비 이름표는 Code.gs 하나(페이지는 doGet 에서 받음)", html.contains("Counterbalance"))
        assertFalse("옛 표기 '미상'", html.contains("미상"))
    }

    // The tab title is set by doGet only: a script-set title would replace it with the latest SOS count in a hidden tab.
    @Test fun page_does_not_set_the_tab_title() {
        assertFalse(page().contains("document.title"))
    }

    // The auto-ended label on the page is the app's own wording (LoneWorkerNotifier.AUTO_ENDED).
    @Test fun ended_reason_label_matches_the_app() {
        assertTrue(page().contains("s.auto ? '" + LoneWorkerNotifier.AUTO_ENDED + "'"))
    }
}
