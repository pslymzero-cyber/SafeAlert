package com.wf11.safealert.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.os.Looper
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.ble.BleScanCallback
import com.wf11.safealert.ble.BleScanner
import com.wf11.safealert.ble.SosAdvert
import com.wf11.safealert.support.BleServiceTestHarness
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.TimeUnit

/**
 * No proximity judgment while a rescue request runs: on the phone that requests rescue (alerts already up come down at once
 * and nothing new is judged), and on every other phone for an advert that carries the rescue request bit (it never reaches
 * processAlert, and an alert it had raised goes down by the timeout loss).
 */
@RunWith(RobolectricTestRunner::class)
class SosProximityGateTest {

    private val sosId = "0123456789abcdef"
    private val a = BleConstants.DEVICE_PREFIX + "A1"
    private val b = BleConstants.DEVICE_PREFIX + "B2"
    private val c = BleConstants.DEVICE_PREFIX + "C3"
    private var scanner: BleScanner? = null

    @After fun tearDown() {
        runCatching { scanner?.stopScanning() }
        BleService.lastStatus = ""
    }

    private fun detect(service: BleService, id: String, rssi: Int, peerSos: Boolean) =
        ReflectionHelpers.callInstanceMethod<Unit>(
            service, "handleDeviceDetected",
            ClassParameter.from(String::class.java, id), ClassParameter.from(Int::class.javaPrimitiveType, rssi),
            ClassParameter.from(Int::class.javaPrimitiveType, 0), ClassParameter.from(Int::class.javaPrimitiveType, BleConstants.TURN_STRAIGHT),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false), ClassParameter.from(Int::class.javaPrimitiveType, BleConstants.NO_ECHO_RSSI),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false), ClassParameter.from(Boolean::class.javaPrimitiveType, peerSos)
        )

    private fun lastLevelSent(id: String): Int? =
        BleServiceTestHarness.alertBroadcasts().lastOrNull { it.getStringExtra(BleService.EXTRA_ID) == id }
            ?.getIntExtra(BleService.EXTRA_ALERT_LEVEL, -1)

    @Test fun sos_phone_drops_raised_alerts_and_judges_no_proximity_while_its_sos_runs() {
        val service = BleServiceTestHarness.newService()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setEnabled(true)
        val sc = BleScanner(adapter.bluetoothLeScanner).also { scanner = it }
        sc.startScanning(object : BleScanCallback {
            override fun onDeviceDetected(deviceId: String, rssi: Int, remoteState: Int, remoteTurn: Int,
                                          payloadPresent: Boolean, peerEchoRssi: Int, peerInZone: Boolean, peerSos: Boolean) = Unit
            override fun onDeviceLost(deviceId: String) = BleServiceTestHarness.deviceLost(service, deviceId)
            override fun onScanError(errorCode: Int) = Unit
        })
        ReflectionHelpers.setField(service, "bleScanner", sc)
        val detected = ReflectionHelpers.getField<MutableMap<String, Long>>(sc, "detectedDevices")
        var t = 1_000_000L
        repeat(6) {
            for (id in listOf(a, b)) {
                detected[id] = System.currentTimeMillis()
                BleServiceTestHarness.callProcessAlert(service, id, rssi = -50, nowMs = t)
            }
            t += 120
        }
        assertNotNull(BleServiceTestHarness.alertLevelOf(service, a))
        assertNotNull(BleServiceTestHarness.alertLevelOf(service, b))

        // My rescue request starts: both raised alerts go down through the loss path and the sound is lowered
        val monitor = ReflectionHelpers.getField<Lazy<LoneWorkerMonitor>>(service, "loneWorker\$delegate").value
        ReflectionHelpers.setField(monitor, "logic", newLogic(carried = true).apply { restoreSos("still", 500_000L, 61_000) })
        ReflectionHelpers.setField(monitor, "started", true)
        assertTrue(monitor.sosActive)
        ReflectionHelpers.callInstanceMethod<Unit>(
            service, "onOwnSos",
            ClassParameter.from(Boolean::class.javaPrimitiveType, true), ClassParameter.from(Int::class.javaPrimitiveType, 1),
            ClassParameter.from(Int::class.javaPrimitiveType, 0), ClassParameter.from(String::class.java, sosId),
            ClassParameter.from(Int::class.javaPrimitiveType, 0), ClassParameter.from(String::class.java, "Z1"),
            ClassParameter.from(String::class.java, "G6628767")
        )
        assertTrue(BleServiceTestHarness.alertStateOf(service).isEmpty())
        assertEquals(BleConstants.LEVEL_SAFE, lastLevelSent(a))
        assertEquals(BleConstants.LEVEL_SAFE, lastLevelSent(b))
        assertEquals(BleConstants.LEVEL_SAFE, ReflectionHelpers.getField<Int>(service, "activeSoundLevel"))

        // While it runs, close and strong adverts are not judged (a device never judged before leaves no filter behind)
        repeat(3) { detect(service, c, -45, peerSos = false) }
        assertTrue(BleServiceTestHarness.alertStateOf(service).isEmpty())
        assertFalse(BleServiceTestHarness.asmOf(service).kalmanFilters.containsKey(c))

        // Control: once the rescue request is over the judgment runs again
        ReflectionHelpers.setField(monitor, "started", false)
        detect(service, c, -45, peerSos = false)
        assertTrue(BleServiceTestHarness.asmOf(service).kalmanFilters.containsKey(c))
    }

    private fun ad(type: Int, vararg data: Byte) = byteArrayOf((data.size + 1).toByte(), type.toByte()) + data

    /** One advert: service data (state, extension flags, optional episode), device ID, and for a rescue request the 0x5053 info. */
    private fun advert(sos: Boolean, info: ByteArray? = null, uwb: Boolean = false): ByteArray {
        val svc = SosAdvert.serviceData(0, BleConstants.encodeExt(false, sos), if (sos) 3 else 0, 0)
        var out = ad(0x16, 0x34, 0x12, *svc) + ad(0xFF, 0x34, 0x12, 'D'.code.toByte(), '1'.code.toByte())
        if (info != null) out += ad(0xFF, 0x53, 0x50, *info)
        if (uwb) out += ad(0xFF, 0xBC.toByte(), 0x9A.toByte(), 1, 2, 3, 4)
        return out
    }

    @Test fun a_peer_sos_advert_never_reaches_proximity_and_its_raised_alert_goes_down_on_timeout() {
        val app = RuntimeEnvironment.getApplication()
        DevSettings.init(app)
        BeaconRegistry.init(app)
        BleScanner.resetStartLog()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setEnabled(true)
        val sc = BleScanner(adapter.bluetoothLeScanner).also { scanner = it }
        val detections = ArrayList<Pair<String, Boolean>>()
        val peerSos = ArrayList<Triple<Boolean, String, Int>>()
        val uwb = ArrayList<String>()
        val lost = ArrayList<String>()
        sc.startScanning(object : BleScanCallback {
            override fun onDeviceDetected(deviceId: String, rssi: Int, remoteState: Int, remoteTurn: Int,
                                          payloadPresent: Boolean, peerEchoRssi: Int, peerInZone: Boolean, peerSos: Boolean) { detections += deviceId to peerSos }
            override fun onDeviceLost(deviceId: String) { lost += deviceId }
            override fun onScanError(errorCode: Int) = Unit
            override fun onPeerSos(deviceId: String, sos: Boolean, episode: Int, hint: Int, sosId: String, scope: Int, zone: String, worker: String) { peerSos += Triple(sos, sosId, scope) }
            override fun onUwbAddressReceived(deviceId: String, uwbAddress: ByteArray) { uwb += deviceId }
        })
        val detected = ReflectionHelpers.getField<MutableMap<String, Long>>(sc, "detectedDevices")
        val cb = ReflectionHelpers.getField<ScanCallback>(sc, "bleScanCallback")
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        fun feed(bytes: ByteArray) {
            val rec = ReflectionHelpers.callStaticMethod<ScanRecord>(ScanRecord::class.java, "parseFromBytes", ClassParameter.from(ByteArray::class.java, bytes))
            val result = ReflectionHelpers.callConstructor(
                ScanResult::class.java,
                ClassParameter.from(android.bluetooth.BluetoothDevice::class.java, device), ClassParameter.from(ScanRecord::class.java, rec),
                ClassParameter.from(Int::class.javaPrimitiveType, -50), ClassParameter.from(Long::class.javaPrimitiveType, 0L)
            )
            cb.onScanResult(1, result)
        }
        val id = BleConstants.DEVICE_PREFIX + "D1"

        // Plain advert: judged and tracked
        feed(advert(sos = false))
        assertEquals(listOf(id to false), detections)
        assertTrue(id in detected)
        assertEquals(listOf(Triple(false, "", 0)), peerSos)

        // Rescue request advert (with a UWB address riding along): flagged, SOS ID and scope handed on, no UWB address, and the
        //   tracked time is not refreshed
        val scope = SosAdvert.scopeByte("3F", "IB")
        val old = System.currentTimeMillis() - 10_000L
        detected[id] = old
        feed(advert(sos = true, info = SosAdvert.info(sosId, scope), uwb = true))
        assertEquals(id to true, detections.last())
        assertEquals(Triple(true, sosId, scope), peerSos.last())
        assertTrue(uwb.isEmpty())
        assertEquals(old, detected[id])

        // Rescue request adverts keep coming, yet the device is lost by the timeout (the raised alert comes down)
        shadowOf(Looper.getMainLooper()).idleFor(7, TimeUnit.SECONDS)
        assertEquals(listOf(id), lost)

        // On the service: a rescue request advert never reaches processAlert
        val service = BleServiceTestHarness.newService()
        detect(service, b, -45, peerSos = true)
        assertFalse(BleServiceTestHarness.asmOf(service).kalmanFilters.containsKey(b))
        assertNull(BleServiceTestHarness.alertLevelOf(service, b))
    }
}
