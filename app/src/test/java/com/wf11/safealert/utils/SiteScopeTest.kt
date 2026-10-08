package com.wf11.safealert.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Floor/process scope helpers (pure, no Android). */
class SiteScopeTest {

    // active, recFloor, recProc, myFloor, myProc, all -> expected
    private class Row(
        val active: Boolean, val rf: String, val rp: String, val mf: String, val mp: String, val all: Boolean, val want: Boolean
    )

    @Test
    fun receives_truthTable() {
        val rows = listOf(
            Row(true, "2F", "IB", "", "", false, true),     // I have no scope: receive everything
            Row(true, "", "", "1F", "OB", false, true),     // sender that never writes floor/proc
            Row(true, "1F", "OB", "1F", "OB", false, true),
            Row(true, "2F", "IB", "1F", "OB", false, false),
            Row(true, "1F", "IB", "1F", "OB", false, false),
            Row(true, "2F", "OB", "1F", "OB", false, false),
            Row(true, "1F", "IB", "1F", "", false, true),   // floor only: all processes on my floor
            Row(true, "2F", "IB", "1F", "", false, false),
            Row(true, "1F", "", "1F", "OB", false, true),   // record without process
            Row(true, "2F", "IB", "1F", "OB", true, true),  // checkbox: whole site
            Row(false, "2F", "IB", "1F", "OB", false, true) // resolved always passes
        )
        rows.forEachIndexed { i, r ->
            assertEquals("row $i", r.want, SiteScope.receives(r.active, r.rf, r.rp, r.mf, r.mp, r.all))
        }
    }

    @Test
    fun builtInLists_areTheFixedValidCodes() {
        assertEquals((1..10).map { "${it}F" }, SiteScope.FLOORS)
        assertEquals(listOf("OB", "IB", "ICQA", "HUB", "EHS", "HR"), SiteScope.PROCS)
        for (c in SiteScope.FLOORS + SiteScope.PROCS) assertEquals(c, SiteScope.code(c), c)
    }

    @Test
    fun label_joinsNonEmptyParts() {
        assertEquals("WF11-1F-OB", SiteScope.label("WF11", "1F", "OB"))
        assertEquals("WF11-OB", SiteScope.label("WF11", "", "OB"))
        assertEquals("WF11", SiteScope.label("WF11", "", ""))
    }

    @Test
    fun fields_onlyValidValues() {
        assertEquals(mapOf("floor" to "1F", "proc" to "OB"), SiteScope.fields("1F", "OB"))
        assertTrue(SiteScope.fields("", "").isEmpty())
        assertEquals(mapOf("proc" to "OB"), SiteScope.fields("toolong", "OB"))
    }
}
