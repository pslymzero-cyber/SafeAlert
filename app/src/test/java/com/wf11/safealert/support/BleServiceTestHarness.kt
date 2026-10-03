package com.wf11.safealert.support

import android.content.Intent
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.ble.KalmanFilter
import com.wf11.safealert.service.BleService
import com.wf11.safealert.utils.DevSettings
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
 * Scenario repetition (multi-device, multi-frame loops) does not live here; that belongs to the golden tests using it.
 * This file is only responsible for "how to drive and observe a single processAlert call".
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
     * defaults to false. When uwbProbeUploadEnabled is on, processAlert calls FirebaseManager.saveUwbProbe.
     */
    fun applyGoldenDevSettings() {
        DevSettings.autoSaveAlerts = false                 // side effect off — blocks FirebaseManager.saveAlert
        DevSettings.beaconGainPercent = 100                // sets beaconGainDbm (val) indirectly — shipped default (+0 dB)
        DevSettings.categoryBiasEnabled = true
        DevSettings.closingKmhToDbms = 0.5
        DevSettings.collisionHeadOnRatio = 0.6
        DevSettings.collisionSideRatio = 0.3
        DevSettings.coopSlackDb = 8
        DevSettings.corneringTimeGateMs = 1000L
        DevSettings.dangerCooldownMs = 2000L
        DevSettings.debugMode = false
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
}
