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
 * [시뮬레이션] 비콘 레지스트리 변경 -> 스캐너 반영. 커밋 8447150 의 실기 검증 2건을 하드웨어 없이 재현한다.
 *
 *   s1  UUID 삭제  -> 상태 엔트리 즉시 소멸(좀비 잔류 없음)
 *   s2  UUID 등록  -> HW ScanFilter 갱신(스캔 재시작으로 감지 대상 편입)
 *
 * 인과 격리: TTL 스윕(BleScanner.kt:253)은 System.currentTimeMillis() 벽시계를 쓰므로
 * Robolectric 가상 루퍼를 아무리 전진시켜도 발화하지 않는다. s1 의 onDeviceLost 는 forceLoseAll() 뿐이다.
 * 콜백 배선은 손으로 복사하지 않고 실제 startScanning() 이 설치하게 둔다.
 *
 * 출력: build/sim_registry_<name>.log
 */
@RunWith(RobolectricTestRunner::class)
class BeaconRegistryChangeSimulationTest {

    private lateinit var hw: BluetoothLeScanner
    private lateinit var scanner: BleScanner

    private val lost = mutableListOf<String>()
    private val errors = mutableListOf<Int>()

    private val cb = object : BleScanCallback {
        override fun onDeviceDetected(
            deviceId: String, rssi: Int, alertLevel: Int, remoteState: Int,
            remoteTurn: Int, payloadPresent: Boolean, peerEchoRssi: Int, peerInZone: Boolean
        ) = Unit

        override fun onDeviceLost(deviceId: String) { lost += deviceId }
        override fun onScanError(errorCode: Int) { errors += errorCode }
    }

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        DevSettings.init(app)
        BeaconRegistry.init(app)
        // object 싱글턴 - 테스트 간 prefs/콜백 누수 차단
        BeaconRegistry.onChanged = null
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

    /** 등록된 UUID 를 지우면 감지 상태가 그 자리에서 비워진다. */
    @Test fun s1_deleteUuid_purgesDetectedState() {
        val uuid = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
        assertTrue("등록 실패", BeaconRegistry.add(BeaconProfile(uuid = uuid, label = "삭제대상")))

        scanner.startScanning(cb)
        idle()

        // 감지 중이던 기기 3대를 심는다(스캔 콜백이 채우는 자리와 동일한 맵).
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

    /** 신규 UUID 를 등록하면 서비스 재시작 없이 HW 필터가 다시 만들어진다. */
    @Test fun s2_addUuid_rebuildsHardwareFilter() {
        scanner.startScanning(cb)
        idle()

        val before = shadowOf(hw).activeScans.last().scanFilters()

        // 대시 없는 32-hex - normUuid 왕복까지 함께 검증한다(정규화 실패 시 필터가 조용히 누락됐던 자리).
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

    // restartScan 의 재시작 지연이 300ms 이므로 400ms 를 돌린다.
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)

    private fun write(name: String, body: CharSequence) {
        val base = listOf(File("build"), File("app/build")).firstOrNull { it.isDirectory }
            ?: File("build").apply { mkdirs() }
        File(base, "sim_registry_$name.log").writeText(body.toString())
    }
}
