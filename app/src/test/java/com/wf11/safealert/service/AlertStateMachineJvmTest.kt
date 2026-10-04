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
import java.lang.reflect.Modifier

/**
 * Standalone JVM test for AlertStateMachine.
 *
 * Runs only the judging logic on the plain JVM without Robolectric - no BleService instance, only a
 * fake Effects is injected to check SAFE -> WARNING -> DANGER in UWB-only judging (judgeUwbOnly).
 * Proves the judging logic really runs apart from the service, and is a minimal safety net against judging regressions.
 */
class AlertStateMachineJvmTest {

    /**
     * DevSettings is an object singleton and prefs is lateinit, so it can't be read without init(Context).
     * A fake SharedPreferences is plugged in by reflection so every getter returns the app default as is.
     * (Only autoSaveAlerts is forced to false to block the Firebase path - external I/O unrelated to judging.)
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

    /** All side effects are no-ops. Only the lookups judging uses return real values. */
    private class FakeEffects(
        override val myCategory: Int,
        override val myMode: String,
    ) : AlertStateMachine.Effects {
        override val myId: String = "SAFEALERT_DEVICE_ME"
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
        override fun updateDwellMute(deviceId: String, level: Int, now: Long, quiet: Boolean) {}
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
     * With the forklift-pair default radii (WARNING 15m / DANGER 8m), shrinks only the distance to check the 3-step escalation.
     * Escalation applies on a single sample at once, so one call per step is deterministic.
     */
    @Test
    fun judgeUwbOnly_forkliftPair_safeToWarningToDanger() {
        val fx = FakeEffects(myCategory = BleConstants.CAT_FORKLIFT, myMode = "FORKLIFT")
        val asm = AlertStateMachine(fx, UwbDistanceManager { null })
        val id = "SAFEALERT_DEVICE_TEST01"

        // 20m - outside the warning radius (15m) = SAFE, no state recorded
        asm.judgeUwbOnly(id, 20f, 1_000L)
        assertNull("경고 반경 밖은 상태가 잡히면 안 된다", asm.alertState[id])

        // 12m - inside the warning radius / outside the danger radius (8m) = WARNING
        asm.judgeUwbOnly(id, 12f, 2_000L)
        assertEquals(
            "경고 반경 진입은 WARNING",
            BleConstants.LEVEL_WARNING.toLong(),
            (asm.alertState[id]?.first ?: -1).toLong(),
        )

        // 5m - inside the danger radius = DANGER (escalation on a single sample)
        asm.judgeUwbOnly(id, 5f, 3_000L)
        assertEquals(
            "위험 반경 진입은 DANGER",
            BleConstants.LEVEL_DANGER.toLong(),
            (asm.alertState[id]?.first ?: -1).toLong(),
        )
    }

    /**
     * Single path for removing state: a cold registry.purge clears that device's immediate and deferred slots (a warm
     * purge leaves the deferred ones to the TTL prune). Teardown slots such as filterPreserveMap are cleared only by
     * clearAll; judgeUwbOnly writes none of them, so a cold purge must bring the entry count back to the baseline.
     * entryCount counts only registered slots, so a map that was never registered is invisible to the purge residue comparison.
     * So the test also scans every Map/Set field of AlertStateMachine and UwbDistanceManager by reflection and checks
     * each is a registered slot (sizeOf != null) - adding a new per-device map without registering it fails here.
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

        // Maps not keyed by device ID - keyed by role pair (a small fixed set), so not cleared when a device is lost.
        val notPerDevice = setOf("uwbProbeLastSaveMap")
        val unregistered = listOf(AlertStateMachine::class.java, UwbDistanceManager::class.java)
            .flatMap { it.declaredFields.toList() }
            .filter { !Modifier.isStatic(it.modifiers) }
            .filter { Map::class.java.isAssignableFrom(it.type) || Set::class.java.isAssignableFrom(it.type) }
            .map { it.name }
            .filter { it !in notPerDevice && asm.registry.sizeOf(it) == null }
        assertTrue("레지스트리에 등록되지 않은 기기별 맵: $unregistered", unregistered.isEmpty())
    }
}
