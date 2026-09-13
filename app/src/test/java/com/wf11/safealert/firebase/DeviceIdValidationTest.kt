package com.wf11.safealert.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.89 (SA-1) display name = asset number only: 2-4 letters + 1-3 digits (EPJ03, FL07).
 * Person names / nicknames are rejected so no PII reaches BLE advertising or the Firebase alert log.
 */
class DeviceIdValidationTest {

    // Non-ASCII as unicode escapes: Windows local Kotlin compile reads sources as MS949
    private val KIM = "\uAE40\uC601\uC0DD"
    private val GA5 = "\uAC00\uB098\uB2E4\uB77C\uB9C8"
    private val GA6 = "\uAC00\uB098\uB2E4\uB77C\uB9C8\uBC14"
    private val GA = "\uAC00"
    private val EMOJI = "\uD83D\uDE00" // surrogate pair

    @Test
    fun acceptsAssetNumbers() {
        listOf("EPJ03", "FL07", "FL7", "PDA001", "AB1", "WFAB999", "", "   ")
            .forEach { assertTrue(it, FirebaseManager.isValidDeviceId(it)) }
    }

    /** lowercase input is normalized to uppercase, same rule as the site code field */
    @Test
    fun acceptsLowercaseAndPadding() {
        listOf("epj03", " fl07 ", "Fl07")
            .forEach { assertTrue(it, FirebaseManager.isValidDeviceId(it)) }
        assertEquals("EPJ03", FirebaseManager.normalizeDeviceId(" epj03 "))
    }

    @Test
    fun rejectsNonAssetNumbers() {
        listOf(
            KIM, GA5, GA6, EMOJI,           // person names / Hangul / emoji - the PII path being closed
            "A1",                           // 1 letter
            "ABCDE1",                       // 5 letters
            "EPJ0304",                      // 4 digits
            "EPJ",                          // no digits
            "03",                           // no letters
            "EPJ 03", "EPJ-03", "EPJ_03",   // separators
            "EPJ03A",                       // trailing letter
            "a.b", "a/b", "a#b", "a\$b", "a[b]", "ab",
            "a".repeat(16)
        ).forEach { assertFalse(it, FirebaseManager.isValidDeviceId(it)) }
    }

    /** every accepted value fits the 15-byte BLE advertising budget by construction */
    @Test
    fun acceptedValuesFitBleBudget() {
        listOf("EPJ03", "FL7", "WFAB999")
            .forEach {
                assertTrue(it, it.toByteArray(Charsets.UTF_8).size <= FirebaseManager.DEVICE_ID_MAX_BYTES)
                assertTrue(it, it.length <= FirebaseManager.ASSET_ID_MAX_LEN)
            }
    }

    /** migration guard: an advertised id is an asset number or an auto-issued SA-xxxxxxxx, nothing else */
    @Test
    fun usableAdvertisedId() {
        listOf("SA-1A2B3C4D", "SA-00000000", "EPJ03", "fl07")
            .forEach { assertTrue(it, FirebaseManager.isUsableAdvertisedId(it)) }
        listOf("", "SA-1A2B3C4", "SA-1A2B3C4DE", "SA-GGGGGGGG", KIM, "EPJ-03")
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
