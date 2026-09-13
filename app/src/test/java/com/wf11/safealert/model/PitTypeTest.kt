package com.wf11.safealert.model

import com.wf11.safealert.ble.BleConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.1.89 (SA-1) PIT type / number selection that replaced free-text display names. */
class PitTypeTest {

    private val KIM = "\uAE40\uC601\uC0DD"

    /** codes go out over BLE - a change breaks identification against already-deployed devices */
    @Test
    fun codesAreStableTwoLetterAscii() {
        PitType.values().forEach {
            assertTrue(it.name, it.code.matches(Regex("^[A-Z]{2}$")))
        }
        assertEquals(PitType.values().size, PitType.values().map { it.code }.toSet().size)
    }

    /** a card must only offer equipment of its own category, or the alert radius is wrong */
    @Test
    fun categorySplit() {
        assertEquals(
            listOf("CB", "RT", "HR", "OP", "ST", "TT"),
            PitType.forCategory(BleConstants.CAT_FORKLIFT).map { it.code }
        )
        assertEquals(
            listOf("EP", "WK"),
            PitType.forCategory(BleConstants.CAT_EPJ).map { it.code }
        )
        // walkers carry no equipment - the caller falls back to the full list
        assertTrue(PitType.forCategory(BleConstants.CAT_WALKER).isEmpty())
    }

    @Test
    fun buildIdPadsToTwoDigits() {
        assertEquals("CB-01", PitType.buildId(PitType.COUNTER_BALANCE, 1))
        assertEquals("RT-07", PitType.buildId(PitType.REACH, 7))
        assertEquals("HR-99", PitType.buildId(PitType.HIGH_REACH, 99))
        assertEquals("EP-10", PitType.buildId(PitType.EPJ, 10))
    }

    @Test
    fun buildIdAlwaysFitsBleBudget() {
        PitType.values().forEach { t ->
            (PitType.NO_MIN..PitType.NO_MAX).forEach { n ->
                val id = PitType.buildId(t, n)
                assertEquals(id, 5, id.toByteArray(Charsets.UTF_8).size)
            }
        }
    }

    /** round-trip: every id the dialog can produce must parse back to the same selection */
    @Test
    fun parseRoundTrip() {
        PitType.values().forEach { t ->
            (PitType.NO_MIN..PitType.NO_MAX).forEach { n ->
                val p = PitType.parse(PitType.buildId(t, n))
                assertNotNull(PitType.buildId(t, n), p)
                assertEquals(t, p!!.first)
                assertEquals(n, p.second)
            }
        }
    }

    @Test
    fun parseAcceptsLowercaseAndPadding() {
        assertEquals(PitType.COUNTER_BALANCE to 1, PitType.parse(" cb-01 "))
        assertEquals(PitType.REACH to 7, PitType.parse("Rt-07"))
    }

    @Test
    fun parseRejectsUnknownAndMalformed() {
        listOf(
            "ZZ-01",            // well formed but not a registered type
            "CB-00",            // number below NO_MIN
            "CB01", "CB-1", "CB-001", "C-01", "CBX-01",
            "8FB25-40604",      // raw equipment number
            "SA-1A2B3C4D",      // auto id
            KIM, "KIM", "", "   "
        ).forEach { assertNull(it, PitType.parse(it)) }
    }
}
