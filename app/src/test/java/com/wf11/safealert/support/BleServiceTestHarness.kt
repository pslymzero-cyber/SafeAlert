package com.wf11.safealert.support

import android.content.Intent
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.ble.BleScanner
import com.wf11.safealert.ble.KalmanFilter
import com.wf11.safealert.service.AlertStateMachine
import com.wf11.safealert.service.BleService
import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertEquals
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Golden cascade harness: the minimal wiring to drive BleService.processAlert() through reflection on Robolectric.
 * processAlert is private, so this is the only place that calls it by reflection. app/src/main carries no test-only
 * logic, but BleService keeps a few private alias fields of AlertStateMachine and UwbDistanceManager state (e.g.
 * dangerContactStreakMap, trackingStateMap, uwbSafeStreakMap) that only reflection tests read.
 *
 * Scenarios (input sequences and expectations) do not live here; they stay visible in the tests using them.
 * This file holds how to drive processAlert, how to read the private state the tests observe, and the per-frame
 * golden rendering shared by AlertCascadeGoldenTest and LowSpeedApproachRegressionTest.
 */
object BleServiceTestHarness {

    /**
     * Creates a service instance without running onCreate() (only get() is called; onCreate starts receivers, sensors
     * and the TX loop). DevSettings.prefs is lateinit, so touching any property before init() throws immediately;
     * processAlert reads DevSettings, so it must be initialized here first.
     * Then applyGoldenDevSettings() pins the golden profile so every golden test inherits the same configuration, and the
     * two state steps of onCreate run on the new instance: registerDeviceState (BleService's per-device maps and filters
     * join asm.registry, so the lost path clears them as in production) and applyEmaAlphas (the front-end EMA alphas and
     * warm-up count come from DevSettings).
     */
    fun newService(): BleService {
        DevSettings.init(RuntimeEnvironment.getApplication())
        applyGoldenDevSettings()
        BleScanner.resetStartLog()   // process-wide scan start log: starts of earlier tests must not hold this one's back
        val service = Robolectric.buildService(BleService::class.java).get()
        ReflectionHelpers.callInstanceMethod<Unit>(service, "registerDeviceState")
        ReflectionHelpers.callInstanceMethod<Unit>(service, "applyEmaAlphas")
        return service
    }

    /**
     * Golden DevSettings profile: explicitly assigns, in alphabetical order, every DevSettings var that AlertStateMachine
     * reads (in processAlert, its helpers and its getter properties), plus the ones it reaches through other helpers:
     * rssiDanger (BleConstants), uwbExclusiveJudgeEnabled (UwbDistanceManager.uwbJudgeModeExclusive) and echoCal*
     * (CalibrationEngine.echoCalAppliedDb). Values are pinned as literals equal to today's shipped defaults rather than
     * references to the default constants, so these pins stay the same if those defaults change.
     * GoldenProfileDefaultsTest compares every DevSettings value before and after this profile: only the side-effect flags
     * listed there may differ, so a changed shipped default fails it by name instead of hiding behind a pin here.
     * Not assigned: KALMAN_PRESET_FAST (a constant, not a setting) and beaconGainDbm (a val, set indirectly through
     * beaconGainPercent). The EMA settings newService copies into the filters through applyEmaAlphas (emaAlpha*,
     * emaWarmupPushes) are not pinned either; they ride on the shipped defaults. The four side-effect flags
     * (vibrationEnabled, soundEnabled, autoSaveAlerts, uwbProbeUploadEnabled) are pinned to false to block vibration,
     * sound and Firebase writes (both alerts and UWB samples); the overlay is already harmless because canDrawOverlays()
     * defaults to false. When uwbProbeUploadEnabled is on, processAlert calls FirebaseManager.saveUwbProbe; it already
     * ships off, so GoldenProfileDefaultsTest lists only the other three flags as deviations.
     */
    fun applyGoldenDevSettings() {
        DevSettings.autoSaveAlerts = false                 // side effect off — blocks FirebaseManager.saveAlert
        DevSettings.beaconGainPercent = 100                // sets beaconGainDbm (val) indirectly — shipped default (+0 dB)
        DevSettings.categoryBiasEnabled = true
        DevSettings.coopSlackDb = 8
        DevSettings.corneringTimeGateMs = 1000L
        DevSettings.dangerCooldownMs = 2000L
        DevSettings.departingHysteresisDbm = 8
        DevSettings.echoAutoCalibEnabled = true
        DevSettings.echoCalClampDb = 6
        DevSettings.echoCalMaxIqrDb = 6
        DevSettings.echoCalMinTicks = 3000
        DevSettings.epjVsEpjBiasDb = -2
        DevSettings.equipVsEquipBiasDb = 8
        DevSettings.fastApproachBypassVelDbm = 2.0
        DevSettings.filterPreserveBandDb = 10
        DevSettings.firebaseThrottleMs = 60_000L           // read only behind autoSaveAlerts (off)
        DevSettings.forwardApproachBiasDb = 3
        DevSettings.hysteresisDbm = 5
        DevSettings.idleIdleSuppressEnabled = false
        DevSettings.idleIdleSuppressEpjPairsEnabled = true
        DevSettings.imuShadowFusionEnabled = true
        DevSettings.kalmanPreset = DevSettings.KALMAN_PRESET_NORMAL  // pinned explicitly, same as the shipped default (normal warehouse)
        DevSettings.logVerbose = false
        DevSettings.minApproachVelDbm = 0.5
        DevSettings.recedingClearMs = 1500L
        DevSettings.recedingDbmDrop = 4
        DevSettings.reciprocalMaxDisagreeDb = 25
        DevSettings.reciprocalRssiEnabled = true
        DevSettings.reversePrepEnabled = true
        DevSettings.reversePrepHoldMs = 4000L
        DevSettings.reverseRiseDbm = 6
        DevSettings.reverseStableTolDb = 2
        DevSettings.reverseWindowMs = 1200L
        DevSettings.rssiDanger = -65
        DevSettings.rssiWarning = -78
        DevSettings.soundEnabled = false                   // side effect off — blocks sound playback
        DevSettings.stateModulationEnabled = true
        DevSettings.timeGateMs = 500L
        DevSettings.timeGateVelDbm = 0.5
        DevSettings.ttcThresholdSec = 3.0
        DevSettings.uwbApproachSpeedKmh = 6.0f
        DevSettings.uwbExclusiveJudgeEnabled = true
        DevSettings.uwbForkliftDangerMeters = 8.0f
        DevSettings.uwbForkliftWarnMeters = 15.0f
        DevSettings.uwbPairDangerMeters = 3.0f
        DevSettings.uwbPairWarnMeters = 5.0f
        DevSettings.uwbPrimaryAuthorityEnabled = true
        DevSettings.uwbProbeUploadEnabled = false      // side effect off — blocks FirebaseManager.saveUwbProbe
        DevSettings.uwbPromoteEnabled = false
        DevSettings.uwbVelPromoteEnabled = false
        DevSettings.uwbVelReleaseEnabled = false
        DevSettings.vibrationEnabled = false                // side effect off — blocks vibration
        DevSettings.walkerDetectsWalker = false
        DevSettings.walkerVsEpjBiasDb = 2
        DevSettings.walkerVsEquipBiasDb = 6
        DevSettings.warningCooldownMs = 3000L
    }

    /**
     * BleService's real signal-lost handler (the scan callback built in applyMode delegates to it), called by reflection
     * so a test can drive it without running applyMode (which starts the scanner, LoneWorker and sensors).
     */
    fun deviceLost(service: BleService, deviceId: String) {
        ReflectionHelpers.callInstanceMethod<Unit>(service, "handleDeviceLost", ClassParameter.from(String::class.java, deviceId))
    }

    /**
     * KalmanFilter seam: KalmanFilter uses its nowMs default (the real System.currentTimeMillis()) when created. Both
     * creation sites in AlertStateMachine (the kalmanFilters getOrPut cold start and the ShadowFusion default) omit nowMs,
     * so dt would come from the real wall clock regardless of the frame-time seam injected into processAlert itself, and
     * the kfVel golden would vary from run to run.
     * Production code is left as is: right after each call the harness uses reflection to align nowMs/lastTsMs of the
     * kalmanFilters entry for deviceId with the injected time, only when this call newly created it. It is a minimal
     * mechanical seam that only matches the frame-interval arithmetic the golden itself encodes; it does not touch decision
     * logic or initialization paths (including injectWarmup).
     * The ShadowFusion Kalman filter (created when imuShadowFusionEnabled && payloadPresent) is not re-timed and still runs
     * on wall-clock time; no golden depends on its output today.
     */
    private var liveNowMs: Long = 0L
    private val liveNowMsFn: () -> Long = { liveNowMs }

    @Suppress("UNCHECKED_CAST")
    private fun kalmanFiltersFieldOf(service: BleService): MutableMap<String, KalmanFilter> =
        ReflectionHelpers.getField(service, "kalmanFilters") as MutableMap<String, KalmanFilter>

    /**
     * Calls private fun processAlert(...) by reflection, in the production signature's parameter order
     * (deviceId, rssi, remoteState, remoteTurn, payloadPresent, peerEchoRssi, nowMs).
     * nowMs is the seam: golden tests inject fixed frame intervals to remove the dependency on
     * System.currentTimeMillis(). It is always required (no default).
     */
    fun callProcessAlert(
        service: BleService,
        deviceId: String,
        rssi: Int,
        remoteState: Int = 0x00,
        remoteTurn: Int = BleConstants.TURN_STRAIGHT,
        payloadPresent: Boolean = false,
        peerEchoRssi: Int = BleConstants.NO_ECHO_RSSI,
        nowMs: Long
    ) {
        liveNowMs = nowMs
        val kalmanFilters = kalmanFiltersFieldOf(service)
        val alreadyPresent = kalmanFilters.containsKey(deviceId)

        ReflectionHelpers.callInstanceMethod<Any?>(
            service,
            "processAlert",
            ClassParameter.from(String::class.java, deviceId),
            ClassParameter.from(Int::class.javaPrimitiveType, rssi),
            ClassParameter.from(Int::class.javaPrimitiveType, remoteState),
            ClassParameter.from(Int::class.javaPrimitiveType, remoteTurn),
            ClassParameter.from(Boolean::class.javaPrimitiveType, payloadPresent),
            ClassParameter.from(Int::class.javaPrimitiveType, peerEchoRssi),
            ClassParameter.from(kotlin.jvm.functions.Function0::class.java) { nowMs }
        )

        if (!alreadyPresent) {
            kalmanFilters[deviceId]?.let { kf ->
                ReflectionHelpers.setField(kf, "nowMs", liveNowMsFn)
                ReflectionHelpers.setField(kf, "lastTsMs", liveNowMs)
            }
        }
    }

    /** Reads BleService's private alertState, an alias of AlertStateMachine.alertState (MutableMap<String, Pair<Int, Long>>). */
    @Suppress("UNCHECKED_CAST")
    private fun alertStateFieldOf(service: BleService): MutableMap<String, Pair<Int, Long>> =
        ReflectionHelpers.getField(service, "alertState") as MutableMap<String, Pair<Int, Long>>

    fun alertStateOf(service: BleService): Map<String, Pair<Int, Long>> = alertStateFieldOf(service)

    fun alertLevelOf(service: BleService, deviceId: String): Int? =
        alertStateFieldOf(service)[deviceId]?.first

    /** Reads the alertState entry time (second) — used to prove the injected nowMs seam value is recorded as is. */
    fun alertEntryMsOf(service: BleService, deviceId: String): Long? =
        alertStateFieldOf(service)[deviceId]?.second

    /** Filters shadowOf(Application).broadcastIntents down to BROADCAST_ALERT, keeping the order. */
    fun alertBroadcasts(): List<Intent> =
        shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.filter {
            it.action == BleService.BROADCAST_ALERT
        }

    /** Reset for isolation between tests — clears the broadcast log and alertState. */
    fun resetBetweenTests(service: BleService) {
        shadowOf(RuntimeEnvironment.getApplication()).clearBroadcastIntents()
        alertStateFieldOf(service).clear()
    }

    // ── Reading private decision state ───────────────────────────────────────────────────

    /** The AlertStateMachine behind the service (BleService's private field asm). */
    fun asmOf(service: BleService): AlertStateMachine = ReflectionHelpers.getField(service, "asm")

    /**
     * Reads a private field by name, cast to the caller's type. A missing or renamed field fails the test at once
     * (no fallback), so a failure here means the field moved, not that the decision changed.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> fieldOf(owner: Any, name: String): T = ReflectionHelpers.getField<Any>(owner, name) as T

    /** trackingStateMap entry as text, "NONE" when the device has none. */
    fun trackingStateOf(service: BleService, deviceId: String): String =
        fieldOf<Map<String, *>>(service, "trackingStateMap")[deviceId]?.toString() ?: "NONE"

    /** A per-device counter map (dangerContactStreakMap, warningContactStreakMap, fastApproachStreakMap), 0 when absent. */
    fun streakOf(service: BleService, fieldName: String, deviceId: String): Int =
        fieldOf<Map<String, Int>>(service, fieldName)[deviceId] ?: 0

    /** Kalman velocity estimate (dBm/s, positive = approaching), 0.0 before the device has a filter. */
    fun kfVelOf(service: BleService, deviceId: String): Double =
        fieldOf<Map<String, KalmanFilter>>(service, "kalmanFilters")[deviceId]?.estimatedVel ?: 0.0

    /** remoteState byte of a forklift advertising [state] (a BleConstants.PSTATE_* value), as processAlert receives it. */
    fun forkliftPayload(state: Int): Int = BleConstants.encodePayload(BleConstants.CAT_FORKLIFT, state).toInt() and 0xFF

    /** ±3 dB noise with a 6-frame period, added to synthetic approach ramps. */
    val NOISE_6 = intArrayOf(0, -3, 2, -1, 3, -2)

    /** ±1 dB noise with a 4-frame period, added to synthetic slow ramps. */
    val NOISE_4 = intArrayOf(0, -1, 1, 0)

    // ── Per-frame goldens (AlertCascadeGoldenTest, LowSpeedApproachRegressionTest) ────────

    /**
     * One frame as one fixed-width line: alert level, entry time relative to [t0Ms] ("null" when absent), tracking state,
     * the three streak counters and the cumulative BROADCAST_ALERT count. Kalman velocity is kept apart (a double).
     */
    fun renderGoldenFrame(service: BleService, deviceId: String, frameIdx: Int, rssi: Int, t0Ms: Long): String {
        val level = alertLevelOf(service, deviceId)
        val entryRel = alertEntryMsOf(service, deviceId)?.minus(t0Ms)
        return "frame=%03d rssi=%4d level=%s entry=%s track=%-11s dangerStreak=%d warnStreak=%d fastStreak=%d bcast=%d".format(
            frameIdx, rssi, level?.toString() ?: "null", entryRel?.toString() ?: "null", trackingStateOf(service, deviceId),
            streakOf(service, "dangerContactStreakMap", deviceId), streakOf(service, "warningContactStreakMap", deviceId),
            streakOf(service, "fastApproachStreakMap", deviceId), alertBroadcasts().size,
        )
    }

    /**
     * Feeds [rssiSeq] one frame every [frameDtMs] and returns each frame's [render] line with its Kalman velocity.
     * Frame numbers (and nowMs = t0Ms + frame * frameDtMs) continue from [startFrame], so a second run can carry on
     * from the end state of the first.
     */
    fun runGoldenScenario(
        service: BleService,
        deviceId: String,
        rssiSeq: IntArray,
        t0Ms: Long,
        frameDtMs: Long,
        startFrame: Int = 0,
        render: (frameIdx: Int, rssi: Int) -> String = { f, r -> renderGoldenFrame(service, deviceId, f, r, t0Ms) },
    ): Pair<Array<String>, DoubleArray> {
        val frames = Array(rssiSeq.size) { "" }
        val kfVel = DoubleArray(rssiSeq.size)
        for (i in rssiSeq.indices) {
            val frameIdx = startFrame + i
            callProcessAlert(service, deviceId, rssiSeq[i], nowMs = t0Ms + frameIdx * frameDtMs)
            frames[i] = render(frameIdx, rssiSeq[i])
            kfVel[i] = kfVelOf(service, deviceId)
        }
        return frames to kfVel
    }

    /**
     * Compares a run with its golden. The frame count and the readable milestones (first WARNING, first DANGER, first
     * release) are asserted first, so a timing change reads as "first DANGER frame=032 → frame=035" before the per-frame
     * diff; then two assertions per frame: the render line, and kfVel within 1e-9.
     */
    fun assertGoldenScenario(
        scenario: String,
        actual: Pair<Array<String>, DoubleArray>,
        expectedFrames: Array<String>,
        expectedKfVel: DoubleArray,
    ) {
        val (frames, kfVel) = actual
        assertEquals("$scenario frame count", expectedFrames.size, frames.size)
        assertEquals("$scenario milestones", milestones(expectedFrames), milestones(frames))
        for (i in expectedFrames.indices) {
            assertEquals("$scenario frame=$i stage=render", expectedFrames[i], frames[i])
            assertEquals("$scenario frame=$i stage=kfVel", expectedKfVel[i], kfVel[i], 1e-9)
        }
    }

    private fun milestones(frames: Array<String>): String {
        val levels = frames.map { Regex("""level=(\S+)""").find(it)?.groupValues?.get(1) }
        fun at(i: Int?) = if (i == null || i < 0) "never" else frames[i].substringBefore(' ')
        val release = (1 until levels.size).firstOrNull { levels[it] == "null" && levels[it - 1] != "null" }
        return "first WARNING ${at(levels.indexOf("1"))}, first DANGER ${at(levels.indexOf("2"))}, first release ${at(release)}"
    }
}
