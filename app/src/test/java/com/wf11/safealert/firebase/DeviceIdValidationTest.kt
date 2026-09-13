package com.wf11.safealert.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.89 (SA-1) display name = PIT id chosen from two dropdowns: TYPE-NN (CB-01, RT-07).
 * Free text entry is gone from the screen, so this validator is the second line of defence -
 * it rejects what older versions stored and anything arriving from outside.
 */
class DeviceIdValidationTest {

    // Non-ASCII as unicode escapes: Windows local Kotlin compile reads sources as MS949
    private val KIM = "\uAE40\uC601\uC0DD"
    private val GA5 = "\uAC00\uB098\uB2E4\uB77C\uB9C8"
    private val GA6 = "\uAC00\uB098\uB2E4\uB77C\uB9C8\uBC14"
    private val GA = "\uAC00"
    private val EMOJI = "\uD83D\uDE00" // surrogate pair

    @Test
    fun acceptsPitIds() {
        listOf("CB-01", "RT-07", "HR-99", "OP-12", "EP-03", "WK-45", "", "   ")
            .forEach { assertTrue(it, FirebaseManager.isValidDeviceId(it)) }
    }

    /** the field is selection-only, but a stored value may still arrive lowercase or padded */
    @Test
    fun normalizesCaseAndPadding() {
        listOf("cb-01", " rt-07 ", "Hr-99")
            .forEach { assertTrue(it, FirebaseManager.isValidDeviceId(it)) }
        assertEquals("CB-01", FirebaseManager.normalizeDeviceId(" cb-01 "))
    }

    @Test
    fun rejectsPersonNamesAndFreeText() {
        listOf(
            KIM, GA5, GA6, EMOJI,               // Hangul / emoji - the PII path being closed
            "KIM", "PARK", "IAN", "KIM YS1",
            "01012345678",                      // phone number
            "8FB25-40604",                      // raw equipment number: not the chosen scheme
            "a.b", "a/b", "a#b", "a\$b", "a[b]"
        ).forEach { assertFalse(it, FirebaseManager.isValidDeviceId(it)) }
    }

    @Test
    fun rejectsMalformedIds() {
        listOf(
            "CB01", "CB_01", "CB.01",           // wrong / missing separator
            "C-01", "CBX-01",                   // type code not 2 letters
            "CB-1", "CB-001",                   // number not 2 digits
            "CB-01-02", "-CB-01", "CB-01-"
        ).forEach { assertFalse(it, FirebaseManager.isValidDeviceId(it)) }
    }

    /** 5 ASCII bytes fixed - leaves 10 of the 15-byte advertising budget for echo data */
    @Test
    fun pitIdFitsBleBudget() {
        listOf("CB-01", "HR-99", "WK-45").forEach {
            assertEquals(it, 5, it.toByteArray(Charsets.UTF_8).size)
            assertTrue(it, it.toByteArray(Charsets.UTF_8).size <= FirebaseManager.DEVICE_ID_MAX_BYTES)
        }
    }

    /**
     * migration guard: an advertised id is a PIT id or an auto-issued SA-xxxxxxxx.
     * The auto id needs its own pattern - it is not a PIT id and never will be.
     */
    @Test
    fun usableAdvertisedId() {
        listOf("SA-1A2B3C4D", "SA-ABCDEFAB", "SA-00000000", "CB-01", "rt-07")
            .forEach { assertTrue(it, FirebaseManager.isUsableAdvertisedId(it)) }
        listOf("", "   ", "SA-DEFAULT", "SA-1A2B3C4", "SA-1A2B3C4DE", "SA-GGGGGGGG",
               KIM, "KIM", "8FB25-40604")
            .forEach { assertFalse(it, FirebaseManager.isUsableAdvertisedId(it)) }
    }

    @Test
    fun utf8PrefixLen() {
        assertEquals(5, FirebaseManager.utf8PrefixLen(GA6, 15))
        assertEquals(4, FirebaseManager.utf8PrefixLen(GA5, 14))
        assertEquals(15, FirebaseManager.utf8PrefixLen("a".repeat(20), 15))
        assertEquals(3, FirebaseManager.utf8PrefixLen("ab${GA}c", 5))     // 1+1+3 = 5
        assertEquals(0, FirebaseManager.utf8PrefixLen(GA, 2))
        assertEquals(0, FirebaseManager.utf8PrefixLen(EMOJI, 3))          // never split a surrogate pair
        assertEquals(2, FirebaseManager.utf8PrefixLen(EMOJI, 4))
    }
}
