package com.wf11.safealert.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.89 (SA-1) display name = equipment number as printed on the machine label.
 *   forklift C/B : 8FB25-40604, 8FB25-40577
 *   reach truck  : 8FBR18-20427, 8FBR18-20432
 * Person names are rejected so no PII reaches BLE advertising or the Firebase alert log.
 */
class DeviceIdValidationTest {

    // Non-ASCII as unicode escapes: Windows local Kotlin compile reads sources as MS949
    private val KIM = "\uAE40\uC601\uC0DD"
    private val KIM1 = "\uAE40\uC601\uC0DD1"
    private val GA5 = "\uAC00\uB098\uB2E4\uB77C\uB9C8"
    private val GA6 = "\uAC00\uB098\uB2E4\uB77C\uB9C8\uBC14"
    private val GA = "\uAC00"
    private val EMOJI = "\uD83D\uDE00" // surrogate pair

    /** the four numbers actually in the field must pass verbatim */
    @Test
    fun acceptsFieldEquipmentNumbers() {
        listOf("8FB25-40604", "8FB25-40577", "8FBR18-20427", "8FBR18-20432")
            .forEach { assertTrue(it, FirebaseManager.isValidDeviceId(it)) }
    }

    @Test
    fun acceptsOtherAssetShapes() {
        listOf("EPJ03", "FL07", "PDA001", "7FB20-12345", "8FG25-1", "AB1", "", "   ")
            .forEach { assertTrue(it, FirebaseManager.isValidDeviceId(it)) }
    }

    /** lowercase input is normalized to uppercase, same rule as the site code field */
    @Test
    fun acceptsLowercaseAndPadding() {
        listOf("8fb25-40604", " 8fbr18-20427 ", "8Fb25-40577")
            .forEach { assertTrue(it, FirebaseManager.isValidDeviceId(it)) }
        assertEquals("8FB25-40604", FirebaseManager.normalizeDeviceId(" 8fb25-40604 "))
    }

    @Test
    fun rejectsPersonNamesAndFreeText() {
        listOf(
            KIM, KIM1, GA5, GA6, EMOJI,         // Hangul / emoji - the PII path being closed
            "KIM", "PARK", "IAN",               // letters only: no digit
            "KIM YS1", "8FB25 40604",           // space
            "01012345678",                      // digits only: phone number
            "12345"                             // digits only
        ).forEach { assertFalse(it, FirebaseManager.isValidDeviceId(it)) }
    }

    @Test
    fun rejectsMalformedSeparatorsAndLength() {
        listOf(
            "-8FB25", "8FB25-",                 // leading / trailing hyphen
            "8FB25--40604",                     // doubled hyphen
            "8FB25_40604", "8FB25.40604",       // wrong separator
            "A1",                               // shorter than ASSET_ID_MIN_LEN
            "8FB25-40604-9999",                 // 16 chars, over the BLE budget
            "a.b", "a/b", "a#b", "a\$b", "a[b]"
        ).forEach { assertFalse(it, FirebaseManager.isValidDeviceId(it)) }
    }

    /** every accepted value fits the 15-byte BLE advertising budget by construction */
    @Test
    fun acceptedValuesFitBleBudget() {
        listOf("8FB25-40604", "8FBR18-20427", "EPJ03", "ABCDEFGH1234567")
            .forEach {
                assertTrue(it, FirebaseManager.isValidDeviceId(it))
                assertEquals(it, it.length, it.toByteArray(Charsets.UTF_8).size)  // ASCII only
                assertTrue(it, it.toByteArray(Charsets.UTF_8).size <= FirebaseManager.DEVICE_ID_MAX_BYTES)
            }
    }

    /**
     * migration guard: an advertised id is an equipment number or an auto-issued SA-xxxxxxxx.
     * The auto id is matched by its own pattern - UUID hex can come out all-letters (no digit),
     * which the equipment rule would reject and the migration would then reissue forever.
     */
    @Test
    fun usableAdvertisedId() {
        listOf("SA-1A2B3C4D", "SA-ABCDEFAB", "SA-00000000", "8FB25-40604", "8fbr18-20427")
            .forEach { assertTrue(it, FirebaseManager.isUsableAdvertisedId(it)) }
        // not the auto-id shape, but still a non-PII alphanumeric id - the migration leaves it alone
        listOf("SA-1A2B3C4", "SA-1A2B3C4DE")
            .forEach { assertTrue(it, FirebaseManager.isUsableAdvertisedId(it)) }
        // SA-DEFAULT / SA-GGGGGGGG carry no digit, so neither rule accepts them
        listOf("", "   ", "SA-DEFAULT", "SA-GGGGGGGG", KIM, "KIM")
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
