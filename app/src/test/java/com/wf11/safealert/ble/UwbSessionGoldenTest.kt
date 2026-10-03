package com.wf11.safealert.ble

import com.wf11.safealert.service.AlertStateMachine
import com.wf11.safealert.service.BleService
import com.wf11.safealert.support.BleServiceTestHarness
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.UwbRanger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * End-to-end golden (tracer) for UwbRanger injection + the Case A (UWB↔UWB exclusive judging) early branch.
 *
 * Targets: UwbDistanceManager.uwbJudgeModeExclusive and AlertStateMachine.judgeUwbOnly (reached through BleService's
 * private delegates), UwbDistanceManager.freshUwbDistM (no delegate; reached only inside AlertStateMachine.processAlert,
 * via uwbDist), and the UwbRanger constructor (06_utils/UwbRanger.kt). BleService.processAlert is private, so it is
 * driven only through BleServiceTestHarness.
 *
 * ── Two-clock rule ──────────────────────────────────────────────────────────
 * processAlert's nowMs is a seam (an explicit argument of BleServiceTestHarness.callProcessAlert),
 * but freshUwbDistM reads System.currentTimeMillis() directly (no seam; Robolectric may replace this clock).
 * Sample times are chosen against nowMs: T0_MS is the test start time (arbitrary constant), fresh samples
 * use T0_MS+FRESH_OFFSET_MS (future offset), stale samples use T0_MS-STALE_OFFSET_MS (past offset). These offsets
 * decide uwbJudgeModeExclusive, which compares against nowMs, but not freshUwbDistM, so behavior 10, which depends on
 * freshUwbDistM, uses extreme sample times that are stale/fresh under either clock. Millisecond boundary checks
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

    @Suppress("UNCHECKED_CAST")
    private fun dangerContactStreakMapOf(service: BleService): MutableMap<String, Int> =
        ReflectionHelpers.getField(service, "dangerContactStreakMap") as MutableMap<String, Int>

    @Suppress("UNCHECKED_CAST")
    private fun warningContactStreakMapOf(service: BleService): MutableMap<String, Int> =
        ReflectionHelpers.getField(service, "warningContactStreakMap") as MutableMap<String, Int>

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

    private fun asmOf(service: BleService): AlertStateMachine = ReflectionHelpers.getField(service, "asm")

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

    // ── Behavior 2: uwbRanger == null → Case B(judgeMode false) ─────────────────────────
    @Test
    fun behavior2_nullRanger_fallsBackToCaseB() {
        val service = BleServiceTestHarness.newService()
        injectRanger(service, null)
        assertFalse(judgeMode(service, DEVICE_ID, T0_MS))
    }

    // ── Behavior 3+4: injected ranger + fresh sample → processAlert takes the Case A early branch ──
    @Test
    fun behavior3and4_freshSample_triggersCaseAEarlyReturnInProcessAlert() {
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
        assertEquals(0, dangerContactStreakMapOf(service)[DEVICE_ID] ?: -1)
        assertEquals(0, warningContactStreakMapOf(service)[DEVICE_ID] ?: -1)
        assertNull(BleServiceTestHarness.alertLevelOf(service, DEVICE_ID))
    }

    // ── Behavior 5: judgeUwbOnly over 4 frames — immediate escalation, then a demotion confirmed by 3 samples ──
    // 2.0m (≤dangM 3.0) → DANGER at once. 6.0m (>warnM+hyst 5.5) ×3 in a row: streak 1 and 2 hold
    // (DANGER kept), streak 3 confirms the demotion (SAFE). Golden DevSettings radii: uwbPairWarnMeters=5.0f,
    // uwbPairDangerMeters=3.0f (BleServiceTestHarness.applyGoldenDevSettings), hyst=UWB_RELEASE_HYST_M=0.5f,
    // demoteStreak=UWB_DEMOTE_STREAK=3 (both internal vals of AlertStateMachine — plain arithmetic, so computed by hand,
    // no record-then-freeze needed).
    @Test
    fun behavior5_escalateImmediately_demoteAfterConfirmStreak() {
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

    // ── Behavior 6: freshness window boundary at 3 points — window-1/window/window+1
    // (inclusive `<=` comparison in UwbDistanceManager.uwbJudgeModeExclusive) ──
    // FRESH_WINDOW_MS must be kept in sync by hand with production UwbDistanceManager.UWB_MEAS_FRESH_MS (1_000L) —
    // it is not followed by reflection; this comment only pins down that the two values must be equal.
    @Test
    fun behavior6_freshnessBoundary_threePoints() {
        val service = BleServiceTestHarness.newService()
        val ranger = newRanger()
        injectRanger(service, ranger)
        val sampleAt = T0_MS
        injectUwbSample(service, ranger, DEVICE_ID, 4.0f, sampleAt)

        assertTrue(judgeMode(service, DEVICE_ID, sampleAt + FRESH_WINDOW_MS - 1))
        assertTrue(judgeMode(service, DEVICE_ID, sampleAt + FRESH_WINDOW_MS))
        assertFalse(judgeMode(service, DEVICE_ID, sampleAt + FRESH_WINDOW_MS + 1))
    }

    // ── Behavior 7: no uwbSampleAtMsMap entry → Case B even with a uwbDistances entry ──────
    @Test
    fun behavior7_missingSampleTimestamp_fallsBackToCaseB() {
        val service = BleServiceTestHarness.newService()
        val ranger = newRanger()
        injectRanger(service, ranger)
        ranger.uwbDistances[DEVICE_ID] = 4.0f  // uwbSampleAtMsMap is deliberately left empty.

        assertFalse(judgeMode(service, DEVICE_ID, T0_MS))
    }

    // ── Behavior 8: uwbDistances entry removed → Case B at once, even with a fresh sample time ──────
    // (Design reason in UwbDistanceManager.uwbJudgeModeExclusive: prevents misjudging on a stale timestamp left behind
    //  alone after an end event removed the pair's entry — uwbJudgeModeExclusive checks containsKey before comparing times.)
    @Test
    fun behavior8_missingDistanceEntry_fallsBackToCaseBEvenWithFreshTimestamp() {
        val service = BleServiceTestHarness.newService()
        val ranger = newRanger()
        injectRanger(service, ranger)
        injectUwbSample(service, ranger, DEVICE_ID, 4.0f, T0_MS)
        ranger.uwbDistances.remove(DEVICE_ID)  // the fresh timestamp in uwbSampleAtMsMap is kept.

        assertFalse(judgeMode(service, DEVICE_ID, T0_MS))
    }

    // ── Behavior 9: stale sample → processAlert does not take the Case A early branch, and the RSSI path
    //    actually decides the level. Under Case A both streaks would be forced to 0 forever and
    //    alertLevelOf would stay null forever (contrast with behavior3and4). A stale sample escapes that forcing.
    @Test
    fun behavior9_staleSample_rssiPathDecidesLevel() {
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

    // ── Behavior 10 (the most important case — blocking zombie DANGER): 1.5m, inside the danger radius, stays in
    //    uwbDistances with only its sample time stale, and RSSI is replayed strong enough to pass the weak-signal early return
    //    (taken below the warning threshold) but weaker than the danger threshold — an input that really reaches processAlert's
    //    UWB escalation block (uwbPrimaryAuthorityEnabled, golden true). With the stale sample no frame may be DANGER; with the
    //    same input and a fresh sample (control) it must reach DANGER. freshUwbDistM reads System.currentTimeMillis()
    //    (Robolectric may replace this clock), so the sample times are extremes that are surely stale/fresh
    //    under either clock. The control turns the kill switch off so Case A (UWB-exclusive judging) does not take the frames.
    @Test
    fun behavior10_staleNearDangerDistance_neverProducesZombieDanger() {
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

    // ── Behavior 12: device lost with no RSSI snapshot (cold) — calls BleService's real signal-lost handler
    //    (handleDeviceLost, which the scan callback delegates to). It must call uwbRanger.onDeviceLost (the measured distance goes) and
    //    asm.registry.purge(cold = true): that device's UWB state (sample time, 0x9ABC sighting, demotion streak), its 1 s
    //    average buffer and, being cold, its Kalman, median, EMA and P-EMA state are cleared and SAFE is broadcast for it;
    //    the other device is untouched. Once a fresh sample arrives again, Case A returns at once in that same frame.
    @Test
    fun behavior12_deviceLost_coldPurgeClearsOnlyThatDevicesState() {
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

    // ── Behavior 13: device lost with an RSSI snapshot (warm), then rediscovered — the real handler keeps the last RSSI in
    //    filterPreserveMap and purges with cold = false: the per-device judgment state and the 1 s average buffer go, the
    //    Kalman, median, EMA and P-EMA state stays. The first processAlert frame of a rediscovery within 30 s and within
    //    ±filterPreserveBandDb of that RSSI consumes the snapshot, carries on with the kept filters (same Kalman, median
    //    window now holding the old sample and the new one) and grants the one-time Time-Gate waiver. That frame is still
    //    in the median warm-up and returns before the first-detection gate, so the waiver is still pending afterwards.
    @Test
    fun behavior13_deviceLost_warmSnapshotKeepsFiltersForRediscovery() {
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
}
