package com.wf11.safealert.ble

import android.content.Context
import com.wf11.safealert.model.BeaconProfile
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/**
 * (v1.1.91) 비콘 fullId 에 전체 키를 싣고 역조회를 전체 일치로 바꾼 뒤의 판정·라벨 검증.
 * fullId 는 BleScanner 의 세 경로(iBeacon·Service UUID·MAC)와 같은 식으로 만든다.
 */
@RunWith(RobolectricTestRunner::class)
class BeaconFullIdTest {

    // 앞 8자(FDA50693)가 같은 UUID 두 개 — v1.1.90 startsWith 매칭에서 뒤바뀌던 조합
    private val equipUuid   = "FDA50693-A4E2-4FB1-AFCF-C6EB07647825"
    private val visitorUuid = "FDA50693-0000-0000-0000-000000000001"
    private val mac         = "AA:BB:CC:DD:EE:FF"

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        DevSettings.init(app)
        BeaconRegistry.init(app)
        BeaconRegistry.onChanged = null
        app.getSharedPreferences(DevSettings.sitePrefName("beacon_registry"), Context.MODE_PRIVATE)
            .edit().clear().commit()
        // 방문자용을 먼저 넣어 firstOrNull 이 접두사 매칭이면 장비용 판정이 틀리게 한다
        BeaconRegistry.add(BeaconProfile(uuid = visitorUuid, label = "방문자비콘", visitorBeacon = true, rssiOffset = 3))
        BeaconRegistry.add(BeaconProfile(uuid = equipUuid, label = "지게차비콘", visitorBeacon = false, rssiOffset = -7))
        BeaconRegistry.add(BeaconProfile(uuid = mac, label = "MAC비콘", type = "MAC", visitorBeacon = false, rssiOffset = 5))
    }

    // BleScanner.kt:202 — iBeacon 은 bytesToUuidString 의 대시 36자 대문자
    private fun iBeaconFullId(uuid: String) = BleConstants.WALKER_PREFIX + "BEA_" + uuid.replace("-", "")
    // BleScanner.kt:225 — Service UUID 는 parcelUuid.uuid.toString().uppercase()
    private fun serviceFullId(uuid: String) =
        BleConstants.WALKER_PREFIX + "BEA_" + UUID.fromString(uuid).toString().uppercase().replace("-", "")
    // BleScanner.kt:241 — MAC 은 콜론 제거 12hex
    private fun macFullId(m: String) = BleConstants.WALKER_PREFIX + "BEA_" + m.replace(":", "")

    @Test
    fun iBeacon_samePrefix_equipAndVisitorJudgedSeparately() {
        assertFalse(BeaconRegistry.isVisitorBeacon(iBeaconFullId(equipUuid)))
        assertTrue(BeaconRegistry.isVisitorBeacon(iBeaconFullId(visitorUuid)))
    }

    @Test
    fun serviceUuid_samePrefix_equipAndVisitorJudgedSeparately() {
        assertFalse(BeaconRegistry.isVisitorBeacon(serviceFullId(equipUuid)))
        assertTrue(BeaconRegistry.isVisitorBeacon(serviceFullId(visitorUuid)))
    }

    @Test
    fun mac_judgedByExactMatch() {
        assertFalse(BeaconRegistry.isVisitorBeacon(macFullId(mac)))
        assertEquals("MAC", BeaconRegistry.findProfileByFullId(macFullId(mac))?.type)
    }

    @Test
    fun label_shownOnAllThreePaths() {
        assertEquals("지게차비콘", BeaconRegistry.labelForFullId(iBeaconFullId(equipUuid)))
        assertEquals("방문자비콘", BeaconRegistry.labelForFullId(serviceFullId(visitorUuid)))
        assertEquals("MAC비콘", BeaconRegistry.labelForFullId(macFullId(mac)))
    }

    @Test
    fun rssiOffset_fromExactProfile() {
        assertEquals(-7, BeaconRegistry.getRssiOffsetForFullId(iBeaconFullId(equipUuid)))
        assertEquals(3, BeaconRegistry.getRssiOffsetForFullId(serviceFullId(visitorUuid)))
        assertEquals(5, BeaconRegistry.getRssiOffsetForFullId(macFullId(mac)))
    }

    @Test
    fun shortFullId_trimsOnlyUuidKey() {
        assertEquals(BleConstants.WALKER_PREFIX + "BEA_FDA50693", BeaconRegistry.shortFullId(iBeaconFullId(equipUuid)))
        assertEquals(macFullId(mac), BeaconRegistry.shortFullId(macFullId(mac)))
        val walker = BleConstants.WALKER_PREFIX + "1234"
        assertEquals(walker, BeaconRegistry.shortFullId(walker))
    }

    @Test
    fun unregistered_treatedAsEquipment() {
        val unknown = iBeaconFullId("FDA50693-FFFF-FFFF-FFFF-FFFFFFFFFFFF")
        assertNull(BeaconRegistry.findProfileByFullId(unknown))
        assertFalse(BeaconRegistry.isVisitorBeacon(unknown))
        assertEquals("BEA_FDA50693", BeaconRegistry.labelForFullId(unknown))
    }
}
