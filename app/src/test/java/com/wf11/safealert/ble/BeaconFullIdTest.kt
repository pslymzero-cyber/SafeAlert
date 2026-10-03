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
 * Checks beacon judgment and labels with fullId carrying the full key and reverse lookup by exact match.
 * fullId is built the same way as BleScanner's three paths (iBeacon, Service UUID, MAC).
 */
@RunWith(RobolectricTestRunner::class)
class BeaconFullIdTest {

    // Two UUIDs sharing the first 8 chars (FDA50693): a pair that prefix (startsWith) matching would mix up
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
        // Add the visitor beacon first, so that a prefix match in firstOrNull would get the equipment beacon wrong
        BeaconRegistry.add(BeaconProfile(uuid = visitorUuid, label = "방문자비콘", visitorBeacon = true, rssiOffset = 3))
        BeaconRegistry.add(BeaconProfile(uuid = equipUuid, label = "지게차비콘", visitorBeacon = false, rssiOffset = -7))
        BeaconRegistry.add(BeaconProfile(uuid = mac, label = "MAC비콘", type = "MAC", visitorBeacon = false, rssiOffset = 5))
    }

    // BleScanner iBeacon path: the 36-char dashed uppercase form from bytesToUuidString
    private fun iBeaconFullId(uuid: String) = BleConstants.WALKER_PREFIX + "BEA_" + uuid.replace("-", "")
    // BleScanner Service UUID path: parcelUuid.uuid.toString().uppercase()
    private fun serviceFullId(uuid: String) =
        BleConstants.WALKER_PREFIX + "BEA_" + UUID.fromString(uuid).toString().uppercase().replace("-", "")
    // BleScanner MAC path: colons removed, 12 hex
    private fun macFullId(m: String) = BleConstants.WALKER_PREFIX + "BEA_" + m.replace(":", "")

    @Test
    fun iBeacon_samePrefix_equipAndVisitorJudgedSeparately() {
        assertFalse(BeaconRegistry.isVisitorBeacon(iBeaconFullId(equipUuid)))
        assertTrue(BeaconRegistry.isVisitorBeacon(iBeaconFullId(visitorUuid)))
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

    // One corrupt entry (missing label) does not take the others down (zone beacons included)
    @Test
    fun getAll_corruptedItemSkipped_othersSurvive() {
        val raw = """[{"uuid":"FDA50693-A4E2-4FB1-AFCF-C6EB07647825","label":"앞"},{"uuid":"11111111-1111-1111-1111-111111111111"},{"uuid":"AA:BB:CC:DD:EE:FF","label":"뒤","type":"MAC","zoneMute":true}]"""
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences(DevSettings.sitePrefName("beacon_registry"), Context.MODE_PRIVATE)
            .edit().putString("beacon_profiles", raw).commit()

        val all = BeaconRegistry.getAll()
        assertEquals(2, all.size)
        assertEquals(listOf("앞", "뒤"), all.map { it.label })
        assertTrue(all[1].zoneMute)
    }
}
