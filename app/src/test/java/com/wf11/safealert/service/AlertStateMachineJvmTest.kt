package com.wf11.safealert.service

import android.content.SharedPreferences
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.ble.BleScanner
import com.wf11.safealert.ble.MedianFilter
import com.wf11.safealert.ble.RssiPreFilter
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.UwbRanger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AlertStateMachine 단독 JVM 테스트 (Phase 3 T3, REFACTOR-04).
 *
 * Robolectric 없이 순수 JVM 에서 판정부만 돌린다 - BleService 인스턴스를 만들지 않고
 * Effects 페이크만 주입해 UWB 전용 판정(judgeUwbOnly) 의 SAFE -> WARNING -> DANGER 를 확인한다.
 * 분해가 실제로 판정부를 서비스에서 떼어냈다는 증거이자, 이후 판정 회귀의 최소 안전망.
 */
class AlertStateMachineJvmTest {

    /**
     * DevSettings 는 object 싱글턴이고 prefs 가 lateinit 이라 init(Context) 없이는 접근 불가.
     * 페이크 SharedPreferences 를 리플렉션으로 꽂아 모든 게터가 앱 기본값을 그대로 돌려주게 한다.
     * (autoSaveAlerts 만 false 로 눌러 Firebase 경로를 차단 - 판정과 무관한 외부 I/O)
     */
    private val fakePrefs = object : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            if (key == "auto_save_alerts") false else defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException("read-only fake")
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    /** 부작용 전부 no-op. 판정에 쓰이는 조회값만 실제 값을 돌려준다. */
    private class FakeEffects(
        override val myCategory: Int,
        override val myMode: String,
    ) : AlertStateMachine.Effects {
        override val myId: String = "SAFEALERT_DEVICE_ME"
        override val isMuted: Boolean = false
        override val myZoneInside: Boolean = false
        override var activeSoundLevel: Int = BleConstants.LEVEL_SAFE
        override var lastApproachAtMs: Long = 0L
        override val bleScanner: BleScanner? = null
        override val uwbRanger: UwbRanger? = null
        override val rssiPreFilter = RssiPreFilter()
        override val medianFilter = MedianFilter()
        override val pEmaFilter = RssiPreFilter()
        override fun getAudibleMaxLevel(): Int = BleConstants.LEVEL_SAFE
        override fun uwbPairKeyFor(deviceId: String): String = "TEST"
        override fun resyncSoundToRemaining() {}
        override fun forceAlarmVolume() {}
        override fun isDeviceMuted(deviceId: String): Boolean = false
        override fun updateDwellMute(deviceId: String, level: Int, now: Long) {}
        override fun isDwellMuted(deviceId: String, level: Int): Boolean = false
        override fun clearDwellMute(deviceId: String) {}
        override fun updateFloatingOverlay() {}
        override fun collapseOverlay() {}
        override fun sendStatusBroadcast(status: String) {}
        override fun extractDisplayName(deviceId: String): String = deviceId
        override fun makeStateLabel(name: String, category: Int, state: Int): String = name
        override fun sendAlertBroadcast(deviceId: String, level: Int) {}
        override fun broadcastDeviceList() {}
        override fun oneSecAvgRssi(deviceId: String, rssi: Int): Int = rssi
        override fun recentPeakRssi(deviceId: String, windowMs: Long): Int? = null
        override fun vibrateDanger() {}
        override fun vibrateWarning() {}
        override fun vibrateRapidApproach() {}
        override fun stopVibration() {}
        override fun playDanger() {}
        override fun playWarning() {}
    }

    @Before
    fun injectPrefs() {
        DevSettings::class.java.getDeclaredField("prefs")
            .apply { isAccessible = true }
            .set(DevSettings, fakePrefs)
    }

    /**
     * 지게차 쌍 기본 반경(경고 15m / 위험 8m) 에서 거리만 줄여가며 3단 승격을 확인한다.
     * 격상은 표본 1개 즉시 반영이므로 각 호출 1회로 결정적이다.
     */
    @Test
    fun judgeUwbOnly_forkliftPair_safeToWarningToDanger() {
        val fx = FakeEffects(myCategory = BleConstants.CAT_FORKLIFT, myMode = "FORKLIFT")
        val asm = AlertStateMachine(fx, UwbDistanceManager { null })
        val id = "SAFEALERT_DEVICE_TEST01"

        // 20m - 경고 반경(15m) 밖 = SAFE, 상태 미등록
        asm.judgeUwbOnly(id, 20f, 1_000L)
        assertNull("경고 반경 밖은 상태가 잡히면 안 된다", asm.alertState[id])

        // 12m - 경고 반경 안 / 위험 반경(8m) 밖 = WARNING
        asm.judgeUwbOnly(id, 12f, 2_000L)
        assertEquals(
            "경고 반경 진입은 WARNING",
            BleConstants.LEVEL_WARNING.toLong(),
            (asm.alertState[id]?.first ?: -1).toLong(),
        )

        // 5m - 위험 반경 안 = DANGER (격상은 표본 1개 즉시)
        asm.judgeUwbOnly(id, 5f, 3_000L)
        assertEquals(
            "위험 반경 진입은 DANGER",
            BleConstants.LEVEL_DANGER.toLong(),
            (asm.alertState[id]?.first ?: -1).toLong(),
        )
    }

    /**
     * STATE-02 - 상태 제거 단일 경로. registry.purge 한 번이 그 기기의 모든 슬롯을 비운다.
     * 판정으로 상태를 실제로 채운 뒤 지우므로, 등록이 누락된 슬롯이 있으면 잔여로 드러난다.
     */
    @Test
    fun registryPurge_leavesNoResidueForDevice() {
        val fx = FakeEffects(myCategory = BleConstants.CAT_FORKLIFT, myMode = "FORKLIFT")
        val asm = AlertStateMachine(fx, UwbDistanceManager { null })
        val baseline = asm.registry.entryCount()

        val ids = listOf("SAFEALERT_DEVICE_A1", "SAFEALERT_DEVICE_A2", "SAFEALERT_DEVICE_A3")
        ids.forEachIndexed { i, id -> asm.judgeUwbOnly(id, 5f, 1_000L + i * 100L) }
        assertTrue(
            "판정이 상태를 남겨야 이 테스트가 의미를 가진다",
            asm.registry.entryCount() > baseline,
        )

        ids.forEach { asm.registry.purge(it, cold = true) }

        assertEquals(
            "purge 후 잔여 엔트리는 기저선으로 돌아와야 한다",
            baseline.toLong(),
            asm.registry.entryCount().toLong(),
        )
    }

    /**
     * BUG-01 - 기기 진입/소멸을 반복해도 상태 엔트리가 단조 증가하지 않는다.
     * 매 사이클 새 id 를 쓰므로, 어느 슬롯이든 제거에서 빠지면 100배로 누적돼 실패한다.
     */
    @Test
    fun repeatedDeviceChurn_doesNotGrowState() {
        val fx = FakeEffects(myCategory = BleConstants.CAT_FORKLIFT, myMode = "FORKLIFT")
        val asm = AlertStateMachine(fx, UwbDistanceManager { null })
        val baseline = asm.registry.entryCount()

        repeat(100) { cycle ->
            val id = "SAFEALERT_DEVICE_CHURN_$cycle"
            val t = 1_000L + cycle * 200L
            asm.judgeUwbOnly(id, 12f, t)
            asm.judgeUwbOnly(id, 5f, t + 100L)
            asm.registry.purge(id, cold = true)
        }

        assertEquals(
            "100회 진입/소멸 후에도 엔트리는 기저선이어야 한다",
            baseline.toLong(),
            asm.registry.entryCount().toLong(),
        )
    }
}
