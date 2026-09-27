package com.wf11.safealert.utils

import com.wf11.safealert.model.BeaconProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** (v1.1.97) 받은 공유 비콘 세트 검증과 받기 전 변경 내역. */
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
            p(U1),                                       // 일반
            p(U2, zone = true, enter = -70),             // 존
            p(MAC, zone = true, enter = -70, visitor = true)
        )
        val incoming = listOf(
            p(U1, offset = 10, zone = true),             // 보정값 변경 + 안전구역 지정
            p(U2, zone = false),                         // 안전구역 해제
            p(MAC, zone = true, enter = -90),            // 반경 넓어짐 + 방문자용 해제
            p("11:22:33:44:55:66", zone = true),         // 신규 + 안전구역 지정
            p("11:22:33:44:55:77")                       // 신규
        )
        val c = BeaconRegistry.summarizeChanges(local, incoming)
        assertEquals(BeaconRegistry.ChangeSummary(
            added = 2, offsetChanged = 1, zoneOn = 2, zoneOff = 1, zoneWidened = 1, visitorChanged = 1), c)
    }
}
