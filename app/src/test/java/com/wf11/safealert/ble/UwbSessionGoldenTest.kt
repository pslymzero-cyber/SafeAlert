package com.wf11.safealert.ble

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Looper
import com.wf11.safealert.service.BleService
import com.wf11.safealert.service.UwbDistanceManager
import com.wf11.safealert.support.BleServiceTestHarness
import com.wf11.safealert.support.BleServiceTestHarness.asmOf
import com.wf11.safealert.support.BleServiceTestHarness.fieldOf
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.UwbRanger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBluetoothLeScanner
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

/**
 * End-to-end golden (tracer) for UwbRanger injection + the Case A (UWB↔UWB exclusive judging) early branch.
 *
 * Targets: UwbDistanceManager.uwbJudgeModeExclusive and AlertStateMachine.judgeUwbOnly (reached through BleService's
 * private delegates), UwbDistanceManager.freshUwbDistM (no delegate; reached only inside AlertStateMachine.processAlert,
 * via uwbDist), and the UwbRanger constructor (06_utils/UwbRanger.kt). BleService.processAlert is private, so it is
 * driven only through BleServiceTestHarness.
 *
 * Case A = UWB-exclusive judging (judgeUwbOnly decides, RSSI never takes part); Case B = the regular RSSI path.
 *
 * ── Two-clock rule ──────────────────────────────────────────────────────────
 * processAlert's nowMs is a seam (an explicit argument of BleServiceTestHarness.callProcessAlert),
 * but freshUwbDistM reads System.currentTimeMillis() directly (no seam; Robolectric may replace this clock).
 * Sample times are chosen against nowMs: T0_MS is the test start time (arbitrary constant), fresh samples
 * use T0_MS+FRESH_OFFSET_MS (future offset), stale samples use T0_MS-STALE_OFFSET_MS (past offset). These offsets
 * decide uwbJudgeModeExclusive, which compares against nowMs, but not freshUwbDistM, so staleNearDistance_neverRaisesZombieDanger,
 * which depends on freshUwbDistM, uses extreme sample times that are stale/fresh under either clock. Millisecond boundary checks
 * (window-1/window/window+1) bypass both clocks through judgeMode()/callJudgeUwbOnly() and pass now directly.
 *
 * ── Role pair / device ID design (minimal seams) ────────────────────────────
 * BleService.myMode (default "") and myCategory (default CAT_WALKER) already hold the wanted values without reflection,
 * because they are assigned only in onStartCommand, which the harness never runs. Test device IDs use
 * BleConstants.DEVICE_PREFIX, so they also bypass the WALKER_PREFIX gate in judgeUwbOnly. deviceCategoryMap/deviceStateMap
 * are not set either (both null), so forkliftPair=false (myCategory=CAT_WALKER, rCategory=null) → routed to the regular
 * role-pair radii (5.0/3.0m, golden DevSettings), and the special-alert block (needs rCategory!=null && rState!=null)
 * is skipped automatically — no seams are needed beyond uwbRanger injection + uwbSampleAtMsMap reflection.
 *
 * ── Safety invariant ────────────────────────────────────────────────────────
 * UwbRanger.initSession() is never called anywhere in this file (it needs real UWB hardware/permissions —
 * risk of hanging CI). newRanger() only calls the constructor and the candidates map always stays empty, so
 * computeDesiredLocked() returns Desired(Role.NONE) at once and the scope.launch path (scheduleRestartLocked)
 * is never entered — a coroutine scope is injected, but no helper in this file actually starts a coroutine.
 *
 * Chosen values: T0_MS=2_000_000L (arbitrary base time), FRESH_OFFSET_MS=+500L (future offset),
 * FRAME_DT_MS=400L (unrelated to the cascade frame interval — arbitrary, since kinematics are unused),
 * DEVICE_ID prefix=BleConstants.DEVICE_PREFIX (not WALKER_PREFIX — bypasses the walker gate),
 * role pair=regular pair (not a forklift, deviceCategoryMap unset) → warnM=5.0f/dangM=3.0f
 * (DevSettings.uwbPairWarnMeters/uwbPairDangerMeters, fixed values in the golden profile).
 */
@RunWith(RobolectricTestRunner::class)
class UwbSessionGoldenTest {

    companion object {
        private const val T0_MS = 2_000_000L
        private const val FRESH_OFFSET_MS = 500L    // Future offset: the sample is newer than nowMs, so it counts as fresh
        private const val FRAME_DT_MS = 400L

        // Must be kept in sync by hand with production UwbDistanceManager.UWB_MEAS_FRESH_MS (private val 1_000L) —
        // if the production constant changes, change this value too.
        private const val FRESH_WINDOW_MS = 1_000L

        // Past offset — gives a stale sample time safely outside the freshness window (FRESH_WINDOW_MS).
        private const val STALE_OFFSET_MS = FRESH_WINDOW_MS + 500L

        private const val DEVICE_ID = BleConstants.DEVICE_PREFIX + "UWBTEST01"

        // Second device for the device-lost scenario — checks that the other device's state is untouched.
        private const val OTHER_DEVICE_ID = BleConstants.DEVICE_PREFIX + "UWBTEST02"
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** initSession() is never called — candidates stays empty, so scope.launch never starts (see the safety invariant above). */
    private fun newRanger(): UwbRanger =
        UwbRanger(
            context = RuntimeEnvironment.getApplication(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            myFullId = "SAFEALERT_WALKER_SELFTEST",
            myIsVehicle = false
        )

    private fun injectRanger(service: BleService, ranger: UwbRanger?) {
        ReflectionHelpers.setField(service, "uwbRanger", ranger)
    }

    @Suppress("UNCHECKED_CAST")
    private fun uwbSampleAtMsMapOf(service: BleService): MutableMap<String, Long> =
        ReflectionHelpers.getField(service, "uwbSampleAtMsMap") as MutableMap<String, Long>

    /**
     * uwbDistances is a public UwbRanger property, so it is assigned directly;
     * uwbSampleAtMsMap is a private field, so it is set via reflection.
     */
    private fun injectUwbSample(service: BleService, ranger: UwbRanger, id: String, distM: Float, sampleAtMs: Long) {
        ranger.uwbDistances[id] = distM
        uwbSampleAtMsMapOf(service)[id] = sampleAtMs
    }

    private fun judgeMode(service: BleService, deviceId: String, now: Long): Boolean =
        ReflectionHelpers.callInstanceMethod(
            service,
            "uwbJudgeModeExclusive",
            ClassParameter.from(String::class.java, deviceId),
            ClassParameter.from(Long::class.javaPrimitiveType, now)
        )

    /**
     * judgeUwbOnly returns Unit — read alertState after the call to get the level
     * (absent = SAFE, same as the production prevLevel convention).
     */
    private fun callJudgeUwbOnly(service: BleService, deviceId: String, distM: Float, now: Long): Int {
        ReflectionHelpers.callInstanceMethod<Any?>(
            service,
            "judgeUwbOnly",
            ClassParameter.from(String::class.java, deviceId),
            ClassParameter.from(Float::class.javaPrimitiveType, distM),
            ClassParameter.from(Long::class.javaPrimitiveType, now)
        )
        return BleServiceTestHarness.alertLevelOf(service, deviceId) ?: BleConstants.LEVEL_SAFE
    }

    @Suppress("UNCHECKED_CAST")
    private fun uwbSafeStreakMapOf(service: BleService): MutableMap<String, Int> =
        ReflectionHelpers.getField(service, "uwbSafeStreakMap") as MutableMap<String, Int>

    @Suppress("UNCHECKED_CAST")
    private fun peerUwbSeenMapOf(service: BleService): MutableMap<String, Long> =
        ReflectionHelpers.getField(service, "peerUwbSeenMap") as MutableMap<String, Long>

    @Suppress("UNCHECKED_CAST")
    private fun oneSecBufferOf(service: BleService): Map<String, *> =
        ReflectionHelpers.getField<Any>(service, "oneSecBuffer") as Map<String, *>

    /** BleService's median, front-end EMA and P-EMA filters, each with the private map that holds a device's state. */
    private val filterStateMaps = mapOf("medianFilter" to "buffers", "rssiPreFilter" to "emaState", "pEmaFilter" to "emaState")

    @Suppress("UNCHECKED_CAST")
    private fun filterStateOf(service: BleService, filter: String): Map<String, *> =
        ReflectionHelpers.getField<Any>(ReflectionHelpers.getField(service, filter), filterStateMaps.getValue(filter)) as Map<String, *>

    /** Names of the filters that hold state for the device. */
    private fun filtersHolding(service: BleService, id: String): Set<String> =
        filterStateMaps.keys.filter { filterStateOf(service, it).containsKey(id) }.toSet()

    /** Level of the last BROADCAST_ALERT sent for that device (null if none). */
    private fun lastAlertLevelSentFor(id: String): Int? =
        BleServiceTestHarness.alertBroadcasts().lastOrNull { it.getStringExtra(BleService.EXTRA_ID) == id }
            ?.getIntExtra(BleService.EXTRA_ALERT_LEVEL, -1)

    // ── No UWB ranger → RSSI judging (uwbJudgeModeExclusive false) ──
    @Test
    fun noRanger_judgesByRssi() {
        val service = BleServiceTestHarness.newService()
        injectRanger(service, null)
        assertFalse(judgeMode(service, DEVICE_ID, T0_MS))
    }

    // ── Ranger + fresh sample → processAlert takes the UWB-only early branch (Case A) ──
    @Test
    fun freshUwbSample_takesJudgingAwayFromRssi() {
        val service = BleServiceTestHarness.newService()
        val ranger = newRanger()
        injectRanger(service, ranger)
        val sampleAt = T0_MS + FRESH_OFFSET_MS
        injectUwbSample(service, ranger, DEVICE_ID, 4.0f, sampleAt)

        assertTrue(judgeMode(service, DEVICE_ID, T0_MS))

        // Confirms the Case A early branch: the RSSI-path streak counters are reset to 0, and processAlert
        // never reaches the RSSI-based alertState writes — RSSI never takes part (alertState also stays
        // empty unless judgeUwbOnly is called separately).
        BleServiceTestHarness.callProcessAlert(service, DEVICE_ID, rssi = -50, nowMs = T0_MS)
        assertEquals(0, fieldOf<Map<String, Int>>(service, "dangerContactStreakMap")[DEVICE_ID] ?: -1)
        assertEquals(0, fieldOf<Map<String, Int>>(service, "warningContactStreakMap")[DEVICE_ID] ?: -1)
        assertNull(BleServiceTestHarness.alertLevelOf(service, DEVICE_ID))
    }

    // ── judgeUwbOnly over 4 frames — immediate escalation, then a demotion confirmed by 3 samples ──
    // 2.0m (≤dangM 3.0) → DANGER at once. 6.0m (>warnM+hyst 5.5) ×3 in a row: streak 1 and 2 hold
    // (DANGER kept), streak 3 confirms the demotion (SAFE). Golden DevSettings radii: uwbPairWarnMeters=5.0f,
    // uwbPairDangerMeters=3.0f (BleServiceTestHarness.applyGoldenDevSettings), hyst=UWB_RELEASE_HYST_M=0.5f,
    // demoteStreak=UWB_DEMOTE_STREAK=3 (both internal vals of AlertStateMachine — plain arithmetic, so computed by hand,
    // no record-then-freeze needed).
    @Test
    fun uwbOnly_escalatesAtOnce_demotesAfterThreeFarSamples() {
        val service = BleServiceTestHarness.newService()

        val l1 = callJudgeUwbOnly(service, DEVICE_ID, 2.0f, T0_MS)
        assertEquals(BleConstants.LEVEL_DANGER, l1)

        val l2 = callJudgeUwbOnly(service, DEVICE_ID, 6.0f, T0_MS + FRAME_DT_MS)
        assertEquals(BleConstants.LEVEL_DANGER, l2)

        val l3 = callJudgeUwbOnly(service, DEVICE_ID, 6.0f, T0_MS + FRAME_DT_MS * 2)
        assertEquals(BleConstants.LEVEL_DANGER, l3)

        val l4 = callJudgeUwbOnly(service, DEVICE_ID, 6.0f, T0_MS + FRAME_DT_MS * 3)
        assertEquals(BleConstants.LEVEL_SAFE, l4)
    }

    // ── Freshness window boundary at 3 points — window-1/window/window+1
    // (inclusive `<=` comparison in UwbDistanceManager.uwbJudgeModeExclusive) ──
    // FRESH_WINDOW_MS must be kept in sync by hand with production UwbDistanceManager.UWB_MEAS_FRESH_MS (1_000L) —
    // it is not followed by reflection; this comment only pins down that the two values must be equal.
    @Test
    fun uwbSample_staysFreshForExactlyOneSecond() {
        val service = BleServiceTestHarness.newService()
        val ranger = newRanger()
        injectRanger(service, ranger)
        val sampleAt = T0_MS
        injectUwbSample(service, ranger, DEVICE_ID, 4.0f, sampleAt)

        assertTrue(judgeMode(service, DEVICE_ID, sampleAt + FRESH_WINDOW_MS - 1))
        assertTrue(judgeMode(service, DEVICE_ID, sampleAt + FRESH_WINDOW_MS))
        assertFalse(judgeMode(service, DEVICE_ID, sampleAt + FRESH_WINDOW_MS + 1))
    }

    // ── No uwbSampleAtMsMap entry → RSSI judging (Case B) even with a uwbDistances entry ──
    @Test
    fun missingSampleTime_judgesByRssi() {
        val service = BleServiceTestHarness.newService()
        val ranger = newRanger()
        injectRanger(service, ranger)
        ranger.uwbDistances[DEVICE_ID] = 4.0f  // uwbSampleAtMsMap is deliberately left empty.

        assertFalse(judgeMode(service, DEVICE_ID, T0_MS))
    }

    // ── uwbDistances entry removed → RSSI judging (Case B) at once, even with a fresh sample time ──
    // (Design reason in UwbDistanceManager.uwbJudgeModeExclusive: prevents misjudging on a stale timestamp left behind
    //  alone after an end event removed the pair's entry — uwbJudgeModeExclusive checks containsKey before comparing times.)
    @Test
    fun missingDistance_judgesByRssiEvenWithFreshSampleTime() {
        val service = BleServiceTestHarness.newService()
        val ranger = newRanger()
        injectRanger(service, ranger)
        injectUwbSample(service, ranger, DEVICE_ID, 4.0f, T0_MS)
        ranger.uwbDistances.remove(DEVICE_ID)  // the fresh timestamp in uwbSampleAtMsMap is kept.

        assertFalse(judgeMode(service, DEVICE_ID, T0_MS))
    }

    // ── Stale sample → processAlert does not take the Case A early branch, and the RSSI path
    //    actually decides the level. Under Case A both streaks would be forced to 0 forever and
    //    alertLevelOf would stay null forever (contrast with freshUwbSample_takesJudgingAwayFromRssi). A stale sample escapes
    //    that forcing. The sample is only 1.5 s old, so this is also the only test that catches processAlert judging
    //    freshness against the wrong clock (sample times are wall-clock).
    @Test
    fun staleUwbSample_handsJudgingBackToRssi() {
        val service = BleServiceTestHarness.newService()
        val ranger = newRanger()
        injectRanger(service, ranger)
        val staleSampleAt = T0_MS - STALE_OFFSET_MS
        injectUwbSample(service, ranger, DEVICE_ID, 2.0f, staleSampleAt)

        assertFalse(judgeMode(service, DEVICE_ID, T0_MS))  // Case A not triggered — the sample is stale.

        // Replay strong-RSSI frames (-50, stronger than the default danger threshold -65) — the RSSI path actually records a level.
        BleServiceTestHarness.callProcessAlert(service, DEVICE_ID, rssi = -50, nowMs = T0_MS)
        BleServiceTestHarness.callProcessAlert(service, DEVICE_ID, rssi = -50, nowMs = T0_MS + FRAME_DT_MS)
        BleServiceTestHarness.callProcessAlert(service, DEVICE_ID, rssi = -50, nowMs = T0_MS + FRAME_DT_MS * 2)
        BleServiceTestHarness.callProcessAlert(service, DEVICE_ID, rssi = -50, nowMs = T0_MS + FRAME_DT_MS * 3)

        assertTrue(BleServiceTestHarness.alertLevelOf(service, DEVICE_ID) != null)
    }

    // ── The most important case — blocking zombie DANGER: 1.5m, inside the danger radius, stays in
    //    uwbDistances with only its sample time stale, and RSSI is replayed strong enough to pass the weak-signal early return
    //    (taken below the warning threshold) but weaker than the danger threshold — an input that really reaches processAlert's
    //    UWB escalation block (uwbPrimaryAuthorityEnabled, golden true). With the stale sample no frame may be DANGER; with the
    //    same input and a fresh sample (control) it must reach DANGER. freshUwbDistM reads System.currentTimeMillis()
    //    (Robolectric may replace this clock), so the sample times are extremes that are surely stale/fresh
    //    under either clock. The control turns the kill switch off so Case A (UWB-exclusive judging) does not take the frames.
    @Test
    fun staleNearDistance_neverRaisesZombieDanger() {
        val rssi = BleConstants.rssiDanger - 5   // 5dB weaker than the danger threshold, stronger than the warning threshold (-78)
        assertTrue(rssi > BleConstants.rssiWarning)

        fun replay(sampleAtMs: Long, exclusiveJudge: Boolean): List<Int?> {
            val service = BleServiceTestHarness.newService()
            DevSettings.uwbExclusiveJudgeEnabled = exclusiveJudge
            val ranger = newRanger()
            injectRanger(service, ranger)
            injectUwbSample(service, ranger, DEVICE_ID, 1.5f, sampleAtMs)   // inside the golden 3.0m danger radius
            return (0 until 12).map { frame ->
                BleServiceTestHarness.callProcessAlert(service, DEVICE_ID, rssi, nowMs = T0_MS + FRAME_DT_MS * frame)
                BleServiceTestHarness.alertLevelOf(service, DEVICE_ID)
            }
        }

        val stale = replay(Long.MIN_VALUE / 4, exclusiveJudge = true)
        assertTrue("RSSI 경로가 약신호 조기 반환을 지나 등급을 내야 한다 $stale", stale.any { it != null })
        assertTrue("낡은 UWB 표본이 DANGER 를 만들었다(좀비) $stale", stale.none { it == BleConstants.LEVEL_DANGER })

        val fresh = replay(Long.MAX_VALUE / 4, exclusiveJudge = false)
        assertTrue("신선한 1.5m 표본은 DANGER 로 올려야 한다(대조군) $fresh", fresh.any { it == BleConstants.LEVEL_DANGER })
    }

    // ── Device lost with no RSSI snapshot (cold) — calls BleService's real signal-lost handler
    //    (handleDeviceLost, which the scan callback delegates to). It must call uwbRanger.onDeviceLost (the measured distance goes) and
    //    asm.registry.purge(cold = true): that device's UWB state (sample time, 0x9ABC sighting, demotion streak), its 1 s
    //    average buffer and, being cold, its Kalman, median, EMA and P-EMA state are cleared and SAFE is broadcast for it;
    //    the other device is untouched. Once a fresh sample arrives again, Case A returns at once in that same frame.
    @Test
    fun coldDeviceLoss_clearsOnlyThatDevicesState() {
        val service = BleServiceTestHarness.newService()
        val ranger = newRanger()
        injectRanger(service, ranger)
        val asm = asmOf(service)
        for (id in listOf(DEVICE_ID, OTHER_DEVICE_ID)) {
            BleServiceTestHarness.callProcessAlert(service, id, rssi = -60, nowMs = T0_MS)   // fills the filters and the 1 s buffer
            injectUwbSample(service, ranger, id, 4.0f, T0_MS)
            peerUwbSeenMapOf(service)[id] = T0_MS
            uwbSafeStreakMapOf(service)[id] = 1
            assertTrue(judgeMode(service, id, T0_MS))
        }
        asm.deviceRssiMap.remove(DEVICE_ID)   // no RSSI snapshot → the handler must purge cold

        BleServiceTestHarness.deviceLost(service, DEVICE_ID)

        assertFalse(judgeMode(service, DEVICE_ID, T0_MS))
        assertFalse(uwbSampleAtMsMapOf(service).containsKey(DEVICE_ID))
        assertFalse(peerUwbSeenMapOf(service).containsKey(DEVICE_ID))
        assertFalse(uwbSafeStreakMapOf(service).containsKey(DEVICE_ID))
        assertFalse("uwbRanger.onDeviceLost 가 실측 거리를 지워야 한다", ranger.uwbDistances.containsKey(DEVICE_ID))
        assertFalse("스냅숏이 없으면 cold 정리 — 칼만도 지운다", asm.kalmanFilters.containsKey(DEVICE_ID))
        assertEquals("스냅숏이 없으면 cold 정리 — 필터도 지운다", emptySet<String>(), filtersHolding(service, DEVICE_ID))
        assertFalse("1초 평균 버퍼를 지운다", oneSecBufferOf(service).containsKey(DEVICE_ID))
        assertFalse(asm.filterPreserveMap.containsKey(DEVICE_ID))
        assertEquals(BleConstants.LEVEL_SAFE, lastAlertLevelSentFor(DEVICE_ID))

        assertTrue(judgeMode(service, OTHER_DEVICE_ID, T0_MS))
        assertEquals(4.0f, ranger.uwbDistances[OTHER_DEVICE_ID])
        assertEquals(T0_MS, uwbSampleAtMsMapOf(service)[OTHER_DEVICE_ID])
        assertTrue(asm.kalmanFilters.containsKey(OTHER_DEVICE_ID))
        assertEquals("다른 기기의 필터는 그대로", filterStateMaps.keys, filtersHolding(service, OTHER_DEVICE_ID))
        assertTrue("다른 기기의 1초 평균 버퍼는 그대로", oneSecBufferOf(service).containsKey(OTHER_DEVICE_ID))

        val resumeAt = T0_MS + FRAME_DT_MS
        injectUwbSample(service, ranger, DEVICE_ID, 4.0f, resumeAt)
        assertTrue(judgeMode(service, DEVICE_ID, resumeAt))
    }

    // ── Device lost with an RSSI snapshot (warm), then rediscovered — the real handler keeps the last RSSI in
    //    filterPreserveMap and purges with cold = false: the per-device judgment state and the 1 s average buffer go, the
    //    Kalman, median, EMA and P-EMA state stays. The first processAlert frame of a rediscovery within 30 s and within
    //    ±filterPreserveBandDb of that RSSI consumes the snapshot, carries on with the kept filters (same Kalman, median
    //    window now holding the old sample and the new one) and grants the one-time Time-Gate waiver. That frame is still
    //    in the median warm-up and returns before the first-detection gate, so the waiver is still pending afterwards.
    @Test
    fun warmDeviceLoss_keepsFiltersForRediscovery() {
        val service = BleServiceTestHarness.newService()
        val asm = asmOf(service)
        BleServiceTestHarness.callProcessAlert(service, DEVICE_ID, rssi = -50, nowMs = T0_MS)
        val last = asm.deviceRssiMap[DEVICE_ID]
        val kf = asm.kalmanFilters[DEVICE_ID]
        assertTrue(last != null && kf != null)

        BleServiceTestHarness.deviceLost(service, DEVICE_ID)

        assertEquals("마지막 RSSI 를 보존 스냅숏으로 남긴다", last, asm.filterPreserveMap[DEVICE_ID]?.refRssi)
        assertSame("스냅숏이 있으면 warm 정리 — 칼만은 남긴다", kf, asm.kalmanFilters[DEVICE_ID])
        assertEquals("스냅숏이 있으면 warm 정리 — 필터도 남긴다", filterStateMaps.keys, filtersHolding(service, DEVICE_ID))
        assertFalse("1초 평균 버퍼는 warm 이어도 지운다", oneSecBufferOf(service).containsKey(DEVICE_ID))
        assertFalse(asm.deviceRssiMap.containsKey(DEVICE_ID))   // immediate group is purged either way
        assertEquals(BleConstants.LEVEL_SAFE, lastAlertLevelSentFor(DEVICE_ID))

        // Rediscovery 2 dB off the snapshot; the Robolectric clock has not moved, so it is well within 30 s.
        BleServiceTestHarness.callProcessAlert(service, DEVICE_ID, rssi = last!! - 2, nowMs = T0_MS + FRAME_DT_MS)
        assertFalse("재발견 첫 프레임이 스냅숏을 소비한다", asm.filterPreserveMap.containsKey(DEVICE_ID))
        assertSame("warm 칼만을 그대로 이어 쓴다", kf, asm.kalmanFilters[DEVICE_ID])
        assertEquals("중앙값 창이 소실 전 표본에 이어진다", 2, (filterStateOf(service, "medianFilter")[DEVICE_ID] as Collection<*>).size)
        assertTrue("Time-Gate 1회 면제가 주어진다", DEVICE_ID in asm.timeGateWaiveSet)
    }

    /**
     * A service with a real scanner wired the way applyMode wires it (loss callback to the real handler, UWB hold by the
     * production freshness check) and an idle ranger. DEVICE_ID and OTHER_DEVICE_ID are detected and alerting; OTHER also
     * has a fresh UWB sample, dated ahead so it stays fresh under either clock. Returns the scanner (the caller stops it),
     * its detected map and its shadow hardware scanner.
     */
    private fun startBtScene(service: BleService, ranger: UwbRanger): Triple<BleScanner, MutableMap<String, Long>, ShadowBluetoothLeScanner> {
        injectRanger(service, ranger)
        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setEnabled(true)
        val hw = adapter.bluetoothLeScanner
        val scanner = BleScanner(hw)
        scanner.startScanning(object : BleScanCallback {
            override fun onDeviceDetected(deviceId: String, rssi: Int, remoteState: Int, remoteTurn: Int,
                                          payloadPresent: Boolean, peerEchoRssi: Int, peerInZone: Boolean) = Unit
            override fun onDeviceLost(deviceId: String) = BleServiceTestHarness.deviceLost(service, deviceId)
            override fun onScanError(errorCode: Int) = Unit
        })
        val uwbDist = ReflectionHelpers.getField<UwbDistanceManager>(service, "uwbDist")
        scanner.uwbMeasuringCheck = { id -> uwbDist.freshUwbDistM(id) != null }
        ReflectionHelpers.setField(service, "bleScanner", scanner)
        val detected = ReflectionHelpers.getField<MutableMap<String, Long>>(scanner, "detectedDevices")
        var t = T0_MS
        repeat(6) {
            for (id in listOf(DEVICE_ID, OTHER_DEVICE_ID)) {
                detected[id] = System.currentTimeMillis()
                BleServiceTestHarness.callProcessAlert(service, id, rssi = -50, nowMs = t)
            }
            t += FRAME_DT_MS
        }
        injectUwbSample(service, ranger, OTHER_DEVICE_ID, 2.0f, System.currentTimeMillis() + 3_600_000)
        assertNotNull(BleServiceTestHarness.alertLevelOf(service, DEVICE_ID))
        assertNotNull(BleServiceTestHarness.alertLevelOf(service, OTHER_DEVICE_ID))
        return Triple(scanner, detected, shadowOf(hw))
    }

    // ── Bluetooth off. Nothing is forgotten on the spot and the radio stays off (no rescan, not even for a
    //    beacon change). The scanner's loss sweep keeps retiring devices the normal way: once the BLE timeout passes, a
    //    device without fresh UWB goes (alert cleared, SAFE broadcast); one still UWB-ranged stays, and so does the ranger,
    //    because the session also protects the other phone. It goes when its UWB goes stale, and the status then shows the
    //    fault, not "기기 이탈". So no alert outlives its device; such an alert would keep the siren, overlay and RISK
    //    broadcast going until monitoring stops.
    @Test
    fun bluetoothOff_devicesStillLeaveTheNormalWay() {
        val service = BleServiceTestHarness.newService()
        val ranger = newRanger()
        val (scanner, detected, hw) = startBtScene(service, ranger)
        try {
            val off = Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
            ReflectionHelpers.getField<BroadcastReceiver>(service, "btStateReceiver").onReceive(RuntimeEnvironment.getApplication(), off)
            assertNotNull("꺼지는 순간에 잊지 않는다", BleServiceTestHarness.alertLevelOf(service, DEVICE_ID))
            assertSame("UWB 는 그대로 — 세션이 상대 기기도 지킨다", ranger, ReflectionHelpers.getField<UwbRanger?>(service, "uwbRanger"))
            assertTrue("스캔을 멈춘다", hw.activeScans.isEmpty())
            scanner.restartScan()
            BeaconRegistry.onChanged?.invoke()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))   // restarts and sweeps run; no timeout yet
            assertTrue("꺼진 동안 다시 스캔하지 않는다", hw.activeScans.isEmpty())
            assertNotNull("비콘 변경으로 한꺼번에 잃지 않는다", BleServiceTestHarness.alertLevelOf(service, DEVICE_ID))
            assertNotNull(BleServiceTestHarness.alertLevelOf(service, OTHER_DEVICE_ID))

            detected.replaceAll { _, _ -> System.currentTimeMillis() - 60_000 }   // no advertisement since
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertNull("BLE 시간 초과로 소실돼야 경보가 남지 않는다", BleServiceTestHarness.alertLevelOf(service, DEVICE_ID))
            assertEquals(BleConstants.LEVEL_SAFE, lastAlertLevelSentFor(DEVICE_ID))
            assertNotNull("UWB 실측이 이어지는 기기는 남는다", BleServiceTestHarness.alertLevelOf(service, OTHER_DEVICE_ID))

            val fault = "블루투스 꺼짐 — 감지 중단"
            ReflectionHelpers.setField(service, "systemFault", fault)   // checkSystemHealth sets it on a running service
            ranger.uwbDistances.remove(OTHER_DEVICE_ID)                   // its UWB goes stale too
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertNull(BleServiceTestHarness.alertLevelOf(service, OTHER_DEVICE_ID))
            assertEquals("고장 중에는 '기기 이탈' 대신 고장 내용", fault, BleService.lastStatus)
        } finally {
            scanner.stopScanning()
            BleService.lastStatus = ""
        }
    }

    // ── Bluetooth back on. STATE_ON's restart runs stopBle before applyMode: UWB stops first, so no late sample
    //    can bring a device back, then every device still held (here also the UWB-kept one) is reported lost before the
    //    scanner is dropped. None keeps its alert past the restart.
    @Test
    fun bluetoothBackOn_stopBleLosesEveryHeldDevice() {
        val service = BleServiceTestHarness.newService()
        val (scanner, _, _) = startBtScene(service, newRanger())
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "stopBle")
            assertNull(ReflectionHelpers.getField<UwbRanger?>(service, "uwbRanger"))
            assertNull(ReflectionHelpers.getField<BleScanner?>(service, "bleScanner"))
            for (id in listOf(DEVICE_ID, OTHER_DEVICE_ID)) {
                assertNull("재시작 뒤까지 경보가 남지 않는다", BleServiceTestHarness.alertLevelOf(service, id))
                assertEquals(BleConstants.LEVEL_SAFE, lastAlertLevelSentFor(id))
            }
        } finally {
            scanner.stopScanning()
        }
    }

    // ── A UWB sample counts only for a device the scanner still tracks. A controller drops a lost peer a moment
    //    late; without this gate one more sample would bring the device back as a first detection that no loss can end.
    @Test
    fun uwbSample_judgedOnlyWhileTheScannerTracksTheDevice() {
        val service = BleServiceTestHarness.newService()
        val ranger = newRanger()
        injectRanger(service, ranger)
        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setEnabled(true)
        val scanner = BleScanner(adapter.bluetoothLeScanner)
        ReflectionHelpers.setField(service, "bleScanner", scanner)
        ranger.uwbDistances[DEVICE_ID] = 2.0f
        val sample = {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onUwbSampleReceived",
                ClassParameter.from(String::class.java, DEVICE_ID), ClassParameter.from(Float::class.javaPrimitiveType, 2.0f))
        }

        sample()
        assertNull("스캐너가 잃은 기기의 UWB 표본은 판정하지 않는다", BleServiceTestHarness.alertLevelOf(service, DEVICE_ID))

        ReflectionHelpers.getField<MutableMap<String, Long>>(scanner, "detectedDevices")[DEVICE_ID] = System.currentTimeMillis()
        sample()
        assertNotNull("추적 중인 기기는 그대로 판정한다", BleServiceTestHarness.alertLevelOf(service, DEVICE_ID))
    }
}
