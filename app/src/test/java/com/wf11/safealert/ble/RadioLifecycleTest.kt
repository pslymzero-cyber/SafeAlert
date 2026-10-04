package com.wf11.safealert.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.os.Looper
import android.os.SystemClock
import com.wf11.safealert.service.BleService
import com.wf11.safealert.support.BleServiceTestHarness
import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

/** The BLE radios have one owner at a time: a repeated start must not leave the previous advertiser or scanner running. */
@RunWith(RobolectricTestRunner::class)
class RadioLifecycleTest {

    // A start that arrives while monitoring runs rebuilds the radios. The previous pair must be retired: left alone it keeps
    //   advertising a frozen payload and judging every packet twice, and nothing would ever stop it.
    @Test
    fun repeatedStartRetiresThePreviousAdvertiserAndScanner() {
        val service = BleServiceTestHarness.newService()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setEnabled(true)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "applyMode")
            val firstScanner = ReflectionHelpers.getField<BleScanner?>(service, "bleScanner")
            val firstAdvertiser = ReflectionHelpers.getField<BleAdvertiser?>(service, "bleAdvertiser")
            assertNotNull(firstScanner)
            assertNotNull(firstAdvertiser)

            ReflectionHelpers.callInstanceMethod<Unit>(service, "applyMode")

            assertNotSame(firstScanner, ReflectionHelpers.getField<BleScanner?>(service, "bleScanner"))
            assertTrue("앞선 송신기를 멈춰야 한다", ReflectionHelpers.getField<Boolean>(firstAdvertiser, "stopped"))
            assertFalse("앞선 수신기를 멈춰야 한다", ReflectionHelpers.getField<Boolean>(firstScanner, "isScanning"))
            assertEquals("스캔은 하나만", 1, shadowOf(adapter.bluetoothLeScanner).activeScans.size)
        } finally {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "stopAll")
        }
    }

    // Android refuses an app's 6th scan start within 30 s (silently up to Android 12), and reception stays dead until a
    //   later start. A burst of restart requests (screen off, hazard-near flips, health-check restarts) must stay within
    //   the limit, keep the running scan while a restart is postponed, and run the postponed restart later.
    @Test
    fun scanRestartsStayWithinAndroidsStartLimit() {
        val hw = enabledScanner()
        val scanner = startedScanner(hw)
        try {
            scanner.notifyScreenOff()
            repeat(12) { i ->
                scanner.setHazardNear(i % 2 == 0)
                scanner.restartScan()
                idle(1)
            }
            assertTrue("30초 안 스캔 시작은 5번까지: ${starts().size}", starts().size <= 5)
            assertEquals("재시작을 미루는 동안에도 스캔은 켜져 있어야 한다", 1, shadowOf(hw).activeScans.size)

            idle(31)
            assertEquals(1, shadowOf(hw).activeScans.size)
            assertTrue("미룬 재시작이 나중에 돌아야 한다", SystemClock.elapsedRealtime() - starts().last() < 31_000)
        } finally {
            scanner.stopScanning()
        }
    }

    // Android counts starts per app: a scanner that replaced another one, and the beacon manager's discovery scan, share
    //   one start log, so the new scanner waits for a free start instead of making a refused 6th one.
    @Test
    fun scanStartsAreCountedAcrossScannersAndTheDiscoveryScan() {
        val hw = enabledScanner()
        val first = startedScanner(hw)
        first.notifyScreenOff(); idle(1)
        first.notifyScreenOn(); idle(1)
        first.notifyScreenOff(); idle(1)
        first.stopScanning()
        BleScanner.noteScanStart()   // the beacon manager's own discovery scan
        val second = startedScanner(hw)
        try {
            assertEquals("새 스캐너도 앱 전체 한도를 지켜야 한다", 5, starts().size)
            assertEquals(0, shadowOf(hw).activeScans.size)
            idle(31)
            assertEquals("자리가 나면 시작한다", 1, shadowOf(hw).activeScans.size)
        } finally {
            second.stopScanning()
        }
    }

    // A beacon discovery lifts the hardware filters, and its end restores them, at once — using the start the budget holds
    //   back: the lift must land within the 15 s discovery, and with the screen off Android delivers nothing to the
    //   unfiltered discovery scan.
    @Test
    fun discoveryFilterSwitchesAreNotPostponed() {
        val hw = enabledScanner()
        val scanner = startedScanner(hw)
        try {
            repeat(3) { BleScanner.noteScanStart() }   // watchdog restarts and the discovery scan itself
            BleScanner.setDiscoveryMode(true); idle(1)
            assertEquals("필터 해제 재시작은 미루지 않는다", 5, starts().size)

            idle(31)
            repeat(4) { BleScanner.noteScanStart() }
            BleScanner.setDiscoveryMode(false); idle(1)
            assertEquals("필터 복구 재시작은 미루지 않는다", 5, starts().size)
        } finally {
            BleScanner.setDiscoveryMode(false)
            scanner.stopScanning()
        }
    }

    // A start Android rejected (onScanFailed) never counted, and after it nothing scans: the 2 s retry is not held back,
    //   even when the failed start was the last one the limit allows.
    @Test
    fun aFailedStartDoesNotHoldBackTheRetry() {
        val hw = enabledScanner()
        val scanner = startedScanner(hw)
        try {
            repeat(4) { BleScanner.noteScanStart() }
            ReflectionHelpers.getField<ScanCallback>(scanner, "bleScanCallback")
                .onScanFailed(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
            val failedAt = SystemClock.elapsedRealtime()
            idle(3)
            assertTrue("실패 뒤 재시도가 바로 돌아야 한다", starts().last() > failedAt)
        } finally {
            scanner.stopScanning()
        }
    }

    // With the screen off, a hazard coming near switches to immediate delivery at once, using the start the budget holds
    //   back for this; screen toggles that leave the batching as it is cost no start at all.
    @Test
    fun hazardPromotionIsNotPostponedAndNeedlessRestartsAreSkipped() {
        val hw = enabledScanner()
        val scanner = startedScanner(hw)
        try {
            scanner.setHazardNear(true)
            scanner.notifyScreenOff(); idle(1)
            scanner.notifyScreenOn(); idle(1)
            assertEquals("배칭이 그대로면 재시작하지 않는다", 1, starts().size)

            scanner.setHazardNear(false)
            scanner.notifyScreenOff(); idle(1)
            repeat(2) { BleScanner.noteScanStart() }
            assertEquals(4, starts().size)
            scanner.setHazardNear(true)
            assertEquals("위험 근접 승격은 미루지 않는다", 5, starts().size)
            assertEquals(0L, ReflectionHelpers.getField<Long>(scanner, "runningBatchDelay"))
        } finally {
            scanner.stopScanning()
        }
    }

    private fun enabledScanner(): BluetoothLeScanner {
        BleScanner.resetStartLog()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setEnabled(true)
        return adapter.bluetoothLeScanner
    }

    private fun startedScanner(hw: BluetoothLeScanner) = BleScanner(hw).apply {
        startScanning(object : BleScanCallback {
            override fun onDeviceDetected(deviceId: String, rssi: Int, remoteState: Int, remoteTurn: Int,
                                          payloadPresent: Boolean, peerEchoRssi: Int, peerInZone: Boolean) = Unit
            override fun onDeviceLost(deviceId: String) = Unit
            override fun onScanError(errorCode: Int) = Unit
        })
    }

    private fun starts() = ReflectionHelpers.getStaticField<ArrayDeque<Long>>(BleScanner::class.java, "recentStarts")
    private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    // A peer hovering around the wake level must not flip scan batching every evaluation cycle (each flip restarts the
    //   scan): a near sample within the stale window keeps it promoted even if the peer's latest sample dipped below.
    @Test
    fun peerHoveringAroundTheWakeLevelKeepsScanBatchingPromoted() {
        val service = BleServiceTestHarness.newService()
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true)
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "applyMode")
            val scanner = ReflectionHelpers.getField<BleScanner>(service, "bleScanner")
            val wake = DevSettings.wakeRssiDbm
            noteRssiForWake(service, wake + 1)
            scanner.setHazardNear(true)   // what the scan callback does for a sample at or above wake
            noteRssiForWake(service, wake - 1)

            ReflectionHelpers.callInstanceMethod<Unit>(service, "evaluateAdvertiserPower")

            assertTrue("방금 가까웠던 신호가 있으면 배칭 승격을 유지", ReflectionHelpers.getField<Boolean>(scanner, "hazardNear"))
        } finally {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "stopAll")
        }
    }

    private fun noteRssiForWake(service: BleService, rssi: Int) =
        ReflectionHelpers.callInstanceMethod<Unit>(service, "noteRssiForWake",
            ClassParameter.from(String::class.java, PEER), ClassParameter.from(Int::class.javaPrimitiveType, rssi))

    private companion object {
        const val PEER = BleConstants.DEVICE_PREFIX + "HOVER01"
    }
}
