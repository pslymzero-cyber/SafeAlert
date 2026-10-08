package com.wf11.safealert.service

import com.wf11.safealert.utils.DevSettings
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

        // The site-code form by which the page finds centers and reads ADMIN_EMAILS entries: the app's normalizeSite
        assertEquals("^[A-Z0-9_-]{1,${DevSettings.SITE_CODE_MAX_LEN}}$", gs().substringAfter("var SC_RE = /").substringBefore("/;"))
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

    // The page cleans SOS records with the script's own helpers and reads only settings doGet sends: a renamed or dropped
    // one would fall back silently (no equipment names, every floor/process blank, no stale note).
    @Test fun page_helpers_and_settings_match_code_gs() {
        val html = page()
        val g = gs()
        fun flat(s: String) = s.replace(Regex("""\s+"""), "")
        fun noComments(s: String) = s.lines().joinToString("\n") { it.substringBefore("//") }   // keys only, not words of comments
        assertEquals(flat(g.substringAfter("function str_(v) {").substringBefore("}")), flat(html.substringAfter("function str(v) {").substringBefore("}")))
        assertEquals(flat(g.substringAfter("function code_(v) {").substringBefore("\n}")), flat(html.substringAfter("function code(v) {").substringBefore("}")))
        val sent = Regex("""(\w+):""").findAll(noComments(g.substringAfter("t.fb = JSON.stringify({").substringBefore("})"))).map { it.groupValues[1] }.toSet()
        val read = Regex("""FB\.(\w+)""").findAll(html).map { it.groupValues[1] }.toSet()
        assertTrue("페이지가 읽는 설정 $read 은 doGet 이 보내는 것 $sent", read.isNotEmpty() && sent.containsAll(read))
        // Every record field the page reads reaches it on the server path too (a missing one shows only while live is down)
        val uses = Regex("""\br\.(\w+)""").findAll(html.substringAfter("function toSos(sc, key, r) {").substringBefore("\n  }")).map { it.groupValues[1] }.toSet()
        val keeps = Regex("""(\w+):""").findAll(noComments(g.substringAfter("function sosRecs_(node) {").substringBefore("\n}"))).map { it.groupValues[1] }.toSet()
        assertTrue("서버 경로가 보내는 필드 $keeps 에 페이지가 읽는 $uses 가 다 있다", uses.isNotEmpty() && keeps.containsAll(uses))
        // Devices and alerts read live are named from the same record fields as on the server path
        fun fields(src: String, v: String) = Regex("""\b$v\.(\w+)""").findAll(src).map { it.groupValues[1] }.toSet()
        val gsSessions = fields(g.substringAfter("function sessions_(node) {").substringBefore("\n}"), "s")
        val pageSessions = fields(html.substringAfter("function toSessions(node) {").substringBefore("\n  }"), "s")
        assertTrue("세션 필드 $gsSessions = $pageSessions", gsSessions.isNotEmpty() && gsSessions == pageSessions)
        val gsAlerts = fields(g.substringAfter("function alerts_(node, sc) {").substringBefore("\n}"), "a")
        val pageAlerts = fields(html.substringAfter("function toAlerts(node, sc) {").substringBefore("\n  }"), "a")
        assertTrue("경보 필드 $gsAlerts = $pageAlerts", gsAlerts.isNotEmpty() && gsAlerts == pageAlerts)
    }
}
