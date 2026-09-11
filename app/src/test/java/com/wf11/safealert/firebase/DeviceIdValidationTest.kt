package com.wf11.safealert.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.1.87 display name (BLE ID): Hangul 5 / ASCII 15 (UTF-8 15 bytes), Firebase key chars rejected. */
class DeviceIdValidationTest {

    // Non-ASCII as unicode escapes: Windows local Kotlin compile reads sources as MS949
    private val KIM = "\uAE40\uC601\uC0DD"
    private val NAIN = "\uB098\uC778"
    private val GA5 = "\uAC00\uB098\uB2E4\uB77C\uB9C8"
    private val GA6 = "\uAC00\uB098\uB2E4\uB77C\uB9C8\uBC14"
    private val GA = "\uAC00"
    private val EMOJI = "\uD83D\uDE00" // surrogate pair

    @Test
    fun accepts() {
        listOf(KIM, NAIN, "SA-1A2B3C4D", "wf_11", "", "   ", GA5, "a".repeat(15))
            .forEach { assertTrue(it, FirebaseManager.isValidDeviceId(it)) }
    }

    @Test
    fun rejects() {
        listOf("a.b", "a/b", "a#b", "a\$b", "a[b]", "a\u0001b", GA6, "a".repeat(16))
            .forEach { assertFalse(it, FirebaseManager.isValidDeviceId(it)) }
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
