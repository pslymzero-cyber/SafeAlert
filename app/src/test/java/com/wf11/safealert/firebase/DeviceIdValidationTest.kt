package com.wf11.safealert.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FirebaseManager.isUsableAdvertisedId: an advertised id is usable when it is a PIT id (TYPE-NN)
 * or an auto-issued SA-xxxxxxxx, after trim and uppercase (a stored value may arrive lowercase or padded).
 */
class DeviceIdValidationTest {

    // Non-ASCII as unicode escapes: Windows local Kotlin compile reads sources as MS949
    private val KIM = "\uAE40\uC601\uC0DD"

    /** The auto id needs its own pattern - it is not a PIT id and never will be. */
    @Test
    fun usableAdvertisedId() {
        listOf("SA-1A2B3C4D", "SA-ABCDEFAB", "SA-00000000", "CB-01", "rt-07", " rt-07 ", " SA-1A2B3C4D ")
            .forEach { assertTrue(it, FirebaseManager.isUsableAdvertisedId(it)) }
        listOf("", "   ", "SA-DEFAULT", "SA-1A2B3C4", "SA-1A2B3C4DE", "SA-GGGGGGGG",
               KIM, "KIM", "8FB25-40604")
            .forEach { assertFalse(it, FirebaseManager.isUsableAdvertisedId(it)) }
        assertEquals("CB-01", FirebaseManager.normalizeDeviceId(" cb-01 "))
    }
}
