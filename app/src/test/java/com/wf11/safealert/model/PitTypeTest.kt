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

    /**
     * the picked equipment decides the role, so every type must map to a real alert category.
     * An unmapped type would start the service with a radius that does not match the machine.
     */
    @Test
    fun everyTypeMapsToAnAlertCategory() {
        val valid = setOf(BleConstants.CAT_FORKLIFT, BleConstants.CAT_EPJ)
        PitType.values().forEach { assertTrue(it.name, it.category in valid) }
    }

    @Test
    fun categoryAssignment() {
        assertEquals(
            listOf("CB", "RT", "HR", "OP", "ST", "TT"),
            PitType.values().filter { it.category == BleConstants.CAT_FORKLIFT }.map { it.code }
        )
        assertEquals(
            listOf("EP", "WK"),
            PitType.values().filter { it.category == BleConstants.CAT_EPJ }.map { it.code }
        )
        // walkers carry no equipment - they never reach the picker
        assertTrue(PitType.values().none { it.category == BleConstants.CAT_WALKER })
    }

    @Test
    fun buildIdPadsToTwoDigits() {
        assertEquals("CB-01", PitType.buildId(PitType.COUNTER_BALANCE, 1))
        assertEquals("RT-07", PitType.buildId(PitType.REACH, 7))
        assertEquals("HR-99", PitType.buildId(PitType.HIGH_REACH, 99))
        assertEquals("EP-10", PitType.buildId(PitType.EPJ, 10))
        assertEquals("WK-45", PitType.buildId(PitType.WALKIE, 45))
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
