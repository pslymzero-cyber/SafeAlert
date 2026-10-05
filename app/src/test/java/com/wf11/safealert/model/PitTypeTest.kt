package com.wf11.safealert.model

import com.wf11.safealert.ble.BleConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PIT type / number selection for display names (instead of free text). */
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
     * The picked equipment decides the role, so every type is a forklift or an EPJ, never anything else (an unmapped
     * or reserved category would start the service with a radius that does not match the machine). The EPJ class
     * (EP, WK) keeps the legacy EPJ category: payload, EPJ biases and radii all key off this int.
     */
    @Test
    fun everyTypeIsAForkliftOrAnEpj() {
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
        assertEquals(
            emptyList<String>(),
            PitType.values().filter { it.category != BleConstants.CAT_FORKLIFT && it.category != BleConstants.CAT_EPJ }
                .map { it.code }
        )
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

    /** A registered type code, a dash and a two-digit number, ignoring case and outer spaces; anything else is null. */
    @Test
    fun parseAcceptsOnlyARegisteredTypeAndATwoDigitNumberIgnoringCaseAndPadding() {
        val rows: List<Pair<String, Pair<PitType, Int>?>> = listOf(
            " cb-01 " to (PitType.COUNTER_BALANCE to 1),
            "Rt-07" to (PitType.REACH to 7),
            "ZZ-01" to null,            // well formed but not a registered type
            "CB-00" to null,            // number below NO_MIN
            "CB01" to null, "CB-1" to null, "CB-001" to null, "C-01" to null, "CBX-01" to null,
            "8FB25-40604" to null,      // raw equipment number
            "SA-1A2B3C4D" to null,      // auto id
            KIM to null, "KIM" to null, "" to null, "   " to null
        )
        for ((i, row) in rows.withIndex()) {
            val (input, want) = row
            assertEquals("row $i '$input'", want, PitType.parse(input))
        }
    }
}
