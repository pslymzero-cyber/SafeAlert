package com.wf11.safealert.utils

import com.wf11.safealert.model.BeaconProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Validation of a received shared beacon set, and the change summary before accepting it. */
class BeaconShareValidationTest {

    private val U1 = "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"
    private val U2 = "FDA50693-A4E2-4FB1-AFCF-C6EB07647825"
    private val MAC = "AA:BB:CC:DD:EE:FF"

    private fun p(uuid: String, offset: Int = 0, zone: Boolean = false, enter: Int = -80, visitor: Boolean = false) =
        BeaconProfile(uuid, "b-$uuid", rssiOffset = offset, zoneMute = zone, zoneEnterRssi = enter, visitorBeacon = visitor)

    @Test
    fun 경계값은_받는다() {
        assertNull(BeaconRegistry.validateShared(listOf(p(U1, offset = 0, enter = -100), p(U2, offset = 20, enter = -30))))
    }

    @Test
    fun 범위_밖_항목이_하나라도_있으면_세트_전체를_거부한다() {
        for (bad in listOf(p(MAC, offset = 21), p(MAC, offset = -1), p(MAC, enter = -101), p(MAC, enter = -29))) {
            assertNotNull("정상 항목과 섞여 있어도 거부: $bad", BeaconRegistry.validateShared(listOf(p(U1), bad, p(U2))))
        }
    }

    @Test
    fun 같은_비콘이_두_번_들어_있으면_거부한다() {
        assertNotNull(BeaconRegistry.validateShared(listOf(p(U1), p(U1.lowercase()))))
    }

    @Test
    fun 받기_전_변경_내역을_센다() {
        val local = listOf(
            p(U1),                                       // regular
            p(U2, zone = true, enter = -70),             // zone
            p(MAC, zone = true, enter = -70, visitor = true)
        )
        val incoming = listOf(
            p(U1, offset = 10, zone = true),             // offset changed + safe zone on
            p(U2, zone = false),                         // safe zone off
            p(MAC, zone = true, enter = -90),            // radius widened + visitor flag off
            p("11:22:33:44:55:66", zone = true),         // new + safe zone on
            p("11:22:33:44:55:77")                       // new
        )
        val c = BeaconRegistry.summarizeChanges(local, incoming)
        assertEquals(BeaconRegistry.ChangeSummary(
            added = 2, offsetChanged = 1, zoneOn = 2, zoneOff = 1, zoneWidened = 1, visitorChanged = 1), c)
    }
}
