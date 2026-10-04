package com.wf11.safealert.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import android.os.Looper
import com.wf11.safealert.model.BeaconProfile
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * [Simulation] Beacon registry change -> scanner update. Reproduces two on-device checks without hardware.
 *
 *   s1  UUID removed -> state entries vanish at once (no zombie leftovers)
 *   s2  UUID added   -> HW ScanFilter rebuilt (the scan restart adds it to the detection targets)
 *
 * Causal isolation: the TTL sweep (BleScanner.timeoutChecker) compares against the System.currentTimeMillis()
 * wall clock, so advancing Robolectric's virtual looper never makes it expire a device; in s1 every
 * onDeviceLost comes from forceLoseAll(). The callback wiring is not copied by hand: the real
 * startScanning() installs it.
 *
 * Output: build/sim_registry_<name>.log
 */
@RunWith(RobolectricTestRunner::class)
class BeaconRegistryChangeSimulationTest {

    private lateinit var hw: BluetoothLeScanner
    private lateinit var scanner: BleScanner

    private val lost = mutableListOf<String>()
    private val errors = mutableListOf<Int>()

    private val cb = object : BleScanCallback {
        override fun onDeviceDetected(
            deviceId: String, rssi: Int, remoteState: Int,
            remoteTurn: Int, payloadPresent: Boolean, peerEchoRssi: Int, peerInZone: Boolean
        ) = Unit

        override fun onDeviceLost(deviceId: String) { lost += deviceId }
        override fun onScanError(errorCode: Int) { errors += errorCode }
    }

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        DevSettings.init(app)
        BeaconRegistry.init(app)
        // object singleton: keep prefs/callbacks from leaking between tests
        BeaconRegistry.onChanged = null
        BleScanner.resetStartLog()
        app.getSharedPreferences("beacon_registry", Context.MODE_PRIVATE).edit().clear().commit()

        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setEnabled(true)
        hw = adapter.bluetoothLeScanner
        scanner = BleScanner(hw)
    }

    @After fun tearDown() {
        runCatching { scanner.stopScanning() }
        BeaconRegistry.onChanged = null
    }

    /** Deleting a registered UUID clears the detected state on the spot. */
    @Test fun s1_deleteUuid_purgesDetectedState() {
        val uuid = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
        assertTrue("등록 실패", BeaconRegistry.add(BeaconProfile(uuid = uuid, label = "삭제대상")))

        scanner.startScanning(cb)
        idle()

        // Seed 3 devices as currently detected (the same map the scan callback fills).
        val detected = ReflectionHelpers.getField<MutableMap<String, Long>>(scanner, "detectedDevices")
        val seeded = listOf("SAFEALERT_WALKER_BEA_AAAAAAAA", "SAFEALERT_FORK_0001", "SAFEALERT_EPJ_0002")
        val now = System.currentTimeMillis()
        seeded.forEach { detected[it] = now }
        assertEquals(3, detected.size)

        lost.clear()
        BeaconRegistry.remove(uuid)
        idle()

        val log = StringBuilder("step\tregistered\tdetected\tlost\n")
            .append("삭제전\t1\t3\t0\n")
            .append("삭제후\t${BeaconRegistry.count()}\t${detected.size}\t${lost.size}\n")
            .append("SUMMARY\tlost=${lost.sorted()}\tcontainsUuid=${BeaconRegistry.containsUuid(uuid)}\n")
        write("s1_delete", log)

        assertEquals("소실 통지가 시드한 전량에 도달해야 한다", seeded.toSet(), lost.toSet())
        assertEquals("좀비 엔트리 잔류", 0, detected.size)
        assertFalse(BeaconRegistry.containsUuid(uuid))
        assertEquals(emptyList<Int>(), errors)
    }

    /** Registering a new UUID rebuilds the HW filter without restarting the service. */
    @Test fun s2_addUuid_rebuildsHardwareFilter() {
        scanner.startScanning(cb)
        idle()

        val before = shadowOf(hw).activeScans.last().scanFilters()

        // Dash-less 32-hex: also checks the normUuid round trip (without normalization the HW filter would be silently dropped).
        val raw = "11223344556677889900AABBCCDDEEFF"
        assertTrue("등록 실패", BeaconRegistry.add(BeaconProfile(uuid = raw, label = "신규비콘")))
        idle()

        val active = shadowOf(hw).activeScans
        assertEquals("스캔은 1건만 활성이어야 한다", 1, active.size)
        val after = active.last().scanFilters()

        val u = UUID.fromString(BeaconRegistry.normUuid(raw))
        val expect = byteArrayOf(0x02, 0x15) + ByteBuffer.allocate(16)
            .putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
        val hit = after.any { it.manufacturerId == 0x004C && it.manufacturerData?.contentEquals(expect) == true }

        val log = StringBuilder("step\tfilters\tibeaconFilters\n")
            .append("등록전\t${before.size}\t${before.count { it.manufacturerId == 0x004C }}\n")
            .append("등록후\t${after.size}\t${after.count { it.manufacturerId == 0x004C }}\n")
            .append("SUMMARY\tnormUuid=${BeaconRegistry.normUuid(raw)}\tmatched=$hit\n")
        write("s2_add", log)

        assertFalse("등록 전에는 iBeacon 필터가 없어야 한다", before.any { it.manufacturerId == 0x004C })
        assertEquals("필터가 1개 늘어야 한다", before.size + 1, after.size)
        assertTrue("신규 UUID 의 제조사데이터 필터가 없다", hit)
        assertEquals(emptyList<Int>(), errors)
    }

    // restartScan restarts after a 300ms delay, so advance 400ms.
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)

    private fun write(name: String, body: CharSequence) {
        val base = listOf(File("build"), File("app/build")).firstOrNull { it.isDirectory }
            ?: File("build").apply { mkdirs() }
        File(base, "sim_registry_$name.log").writeText(body.toString())
    }
}
