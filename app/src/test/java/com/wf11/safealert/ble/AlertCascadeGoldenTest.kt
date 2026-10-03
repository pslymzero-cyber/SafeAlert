package com.wf11.safealert.ble

import android.app.NotificationManager
import androidx.lifecycle.Lifecycle
import com.wf11.safealert.service.BleService
import com.wf11.safealert.service.BootRestoreReceiver
import com.wf11.safealert.support.BleServiceTestHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

/**
 * Golden cascade regression test — record-then-freeze.
 * processAlert() is private, so it is entered by reflection (via support/BleServiceTestHarness.kt).
 * Expected values are snapshots frozen by hand here; there is no auto-regeneration path (manual re-freezing only).
 *
 * The biggest risk is checked first: Robolectric.buildService(...).get() returns the instance without
 * running onCreate(). If that premise breaks, the whole harness/seam design is invalid, so this check
 * is decided before anything else.
 *
 * Note on the "one-frame call": a new detection must pass the shouldAlert gate in AlertStateMachine.processAlert,
 * which requires "!warmingUp" (median filled with 3 samples) or "fastContact" (danger/warning streak ≥ 2).
 * With MedianFilter.DEFAULT_WINDOW = 3, a truly single first call can never pass this gate (warmingUp is always
 * true, the streak is always 1). stableLevel itself, though, comes immediately from calcLevelWithHysteresis,
 * which compares against the thresholds every frame regardless of streak, so two calls in a row with the same
 * strong RSSI bring the danger streak to 2 and pass the gate via fastContact on the second call. That is
 * production's own "2-frame confirmation" design, so the smoke test checks the observed frame (the last call):
 * call 1 = no alert yet, call 2 = alertState + BROADCAST_ALERT observed.
 */
@RunWith(RobolectricTestRunner::class)
class AlertCascadeGoldenTest {

    @Test
    fun assumptionA1_getWithoutCreate_staysInitialized() {
        // Smoke 1: buildService(...).get() must only attach, without running onCreate().
        val controller = Robolectric.buildService(BleService::class.java)
        val service = controller.get()
        assertNotNull(service)
        assertEquals(
            "Robolectric.buildService(...).get() 이 onCreate() 를 실행했다 — Assumption A1 위반",
            Lifecycle.State.INITIALIZED,
            service.lifecycle.currentState
        )
        // If onCreate() had run, the companion isRunning would be true (set in BleService.onCreate) — a second check.
        assertFalse("companion isRunning=true — onCreate() 부작용 감지", BleService.isRunning)

        // Check that none of onCreate()'s three side effects happened (no receiver registered, no notification channel created, no broadcast).
        // Manifest-declared static receivers (Firebase AppMeasurementReceiver, androidx profileinstaller, etc.) are already
        // registered when the Application starts and show up as baseline noise unrelated to BleService.onCreate(). Only receivers our
        // own app components registered dynamically matter, so keep just the ones in the com.wf11.safealert package.
        val appShadow = shadowOf(RuntimeEnvironment.getApplication())
        val ownReceivers = appShadow.registeredReceivers.filter {
            it.broadcastReceiver::class.java.name.startsWith("com.wf11.safealert") &&
                it.broadcastReceiver !is BootRestoreReceiver   // manifest-declared static receiver
        }
        assertTrue(
            "onCreate() 미실행인데 앱 자체 BroadcastReceiver 가 등록됐다: $ownReceivers",
            ownReceivers.isEmpty()
        )
        val nm = RuntimeEnvironment.getApplication()
            .getSystemService(NotificationManager::class.java)
        assertTrue(
            "onCreate() 미실행인데 알림채널이 생성됐다",
            shadowOf(nm).notificationChannels.isEmpty()
        )
        assertTrue(
            "onCreate() 미실행인데 브로드캐스트가 발생했다",
            appShadow.broadcastIntents.isEmpty()
        )
    }

    @Test
    fun processAlert_strongDangerContact_setsAlertStateAndBroadcasts() {
        val service = BleServiceTestHarness.newService()
        val deviceId = "AA:BB:CC:DD:EE:99"
        val dangerRssi = -30   // far stronger than the default rssiDanger (-65) → DANGER level at once on every frame
        var clockMs = 1_000L

        // 1st call: median/streak warm-up not met → shouldAlert=false → not registered yet (the
        // observed frame is the 2nd call below; see the note in the class KDoc).
        BleServiceTestHarness.callProcessAlert(service, deviceId, dangerRssi, nowMs = clockMs)
        assertNull(
            "워밍업 1콜만으로 alertState 가 등록됐다 — shouldAlert 게이트가 조기 통과함",
            BleServiceTestHarness.alertLevelOf(service, deviceId)
        )
        assertEquals(0, BleServiceTestHarness.alertBroadcasts().size)

        // 2nd call: dangerStreak=2 → shouldAlert passes via fastContact → alertState registered + BROADCAST_ALERT.
        clockMs += 120L
        BleServiceTestHarness.callProcessAlert(service, deviceId, dangerRssi, nowMs = clockMs)
        assertEquals(
            BleConstants.LEVEL_DANGER,
            BleServiceTestHarness.alertLevelOf(service, deviceId)
        )
        // Proves the injected nowMs seam value (clockMs) lands unchanged in the alertState entry time.
        assertEquals(clockMs, BleServiceTestHarness.alertEntryMsOf(service, deviceId))
        assertEquals(1, BleServiceTestHarness.alertBroadcasts().size)
        assertEquals(deviceId, BleServiceTestHarness.alertBroadcasts().first().getStringExtra(BleService.EXTRA_ID))
    }

    /**
     * Release (DANGER→SAFE) golden, replayed straight on from the end state of the escalation run.
     * record-then-freeze: [RELEASE_GOLDEN]/[RELEASE_KFVEL] are values captured from one real run, never
     * computed by hand. Re-freeze only by editing these arrays in this file by hand (there is no automatic
     * update path).
     *
     * Parameters: RELEASE_FRAMES=48 (-1dBm per frame back down from the escalation end rssi=-54,
     * frame=042~089), recorded with the default thresholds (DANGER -65, WARNING -78) and filter-keep band 10.
     * Observed: starts in DANGER (level=2, entry=3840) at frame=042, switches to level=null (SAFE) at
     * frame=059 (rssi=-71) and stays SAFE to the end, which meets the acceptance criterion ("starts DANGER,
     * ends SAFE"). **WARNING (level=1) is never passed through in this release window**: SAFE comes from the
     * trend release in AlertStateMachine.processAlert, not from level hysteresis. Its condition (the 2 s mean
     * of medianValue TREND_DROP_DB = 2 dB below its peak, slope <= 0) holds from frame=057 and, after
     * TREND_HOLD_MS (200 ms), clears the alert straight to SAFE at frame=059. The trend trough latch then holds
     * SAFE until that mean rises TREND_REARM_DB above its trough, so the warnStreak that rebuilds from
     * frame=060 (>= 2 at frames 061-063, while not yet departing) cannot re-enter WARNING.
     * At frame=049 (rssi=-61) entry changes 3840→5880 and bcast 2→3 while level stays 2: this is the DANGER
     * cooldown re-alarm (DEFAULT_DANGER_COOLDOWN_MS = 2000; 5880 - 3840 = 2040 ≥ 2000), which re-broadcasts and
     * restamps the entry time while DANGER is held.
     */
    @Test
    fun release_goldenTimeline() {
        val service = BleServiceTestHarness.newService()
        BleServiceTestHarness.resetBetweenTests(service)
        val escalation = runScenario(service, CASCADE_DEVICE_ID, ESCALATION_RSSI, startFrame = 0)
        assertEquals("escalation 프레임 수 불일치", FRAMES, escalation.first.size)
        assertScenario("escalation", escalation, ESCALATION_GOLDEN, ESCALATION_KFVEL)
        val actual = runScenario(service, CASCADE_DEVICE_ID, RELEASE_RSSI, startFrame = FRAMES)
        assertEquals("release 프레임 수 불일치", RELEASE_FRAMES, actual.first.size)
        assertScenario("release", actual, RELEASE_GOLDEN, RELEASE_KFVEL)
    }

    /**
     * Sudden first-contact sub-scenario, the counterpart of the gentle 1dBm/frame escalation ramp
     * (ESCALATION_RSSI): hold -86dBm for 5 frames, then step to -62dBm.
     *
     * Pins the dangerStreak>=2 immediate escalation in AlertStateMachine.processAlert. -86dBm is inside the
     * filter-keep band (effWarning - filterPreserveBandDb = -88), so the far frames keep the median, pEma and
     * Kalman state warm at -86 and pEma has to climb from there: at frame=007, when medianValue (unsmoothed
     * median-of-3) brings dangerStreak to 2, pEma is still near -83.5 (measured), below even the warning
     * threshold. DANGER registers at frame=007 only because the override lifts stableLevel; without it the same
     * frame registers WARNING through warningStreak>=2 (the TTC pre-alert stays shut there: about 3.7s against the
     * 3s threshold) and DANGER waits for the TTC pre-alert at frame=008. The -62dBm step keeps that TTC margin;
     * a bigger step speeds kfVel up and lets the TTC pre-alert hide the override. The override's own log line
     * (dangerStreak=2) is asserted before the golden, so a re-freeze cannot silently stop exercising it.
     * release_goldenTimeline reaches the override too (DANGER at escalation frame=032), but only when its loop runs
     * fast: avg1sec reads the wall clock, so a pause of about 1s can move that first DANGER.
     */
    @Test
    fun suddenContact_dangerOverride_bypassesPEmaLag() {
        val service = BleServiceTestHarness.newService()
        BleServiceTestHarness.resetBetweenTests(service)
        val actual = runScenario(service, CONTACT_DEVICE_ID, CONTACT_RSSI, startFrame = 0)
        assertEquals("suddenContact 프레임 수 불일치", CONTACT_FRAMES, actual.first.size)
        assertTrue("dangerStreak>=2 즉시 격상이 이 기기에 실제로 걸려야 한다",
            ShadowLog.getLogs().any { it.msg?.contains("즉시 격상 DANGER: $CONTACT_DEVICE_ID (dangerStreak=2") == true })
        assertScenario("suddenContact", actual, CONTACT_GOLDEN, CONTACT_KFVEL)
    }
}

// ── Per-frame golden cascade wiring ─────────────────────────────────────────────────────
// Observed per frame: alertState (level + entry time relative to T0), tracking state, the 3
// streak maps and the cumulative broadcast count, rendered as one full line; kfVel
// (estimatedVel) is kept in a separate DoubleArray, apart from the string.
// medianValue and avgRssi (= pEma) are not observed here. RssiCascadeTest pins the median, pre-filter
// and Kalman stages, but pEma is outside its golden as well, so no test pins pEma values directly:
// these goldens constrain it only through level timing.

private const val T0_MS = 1_000_000L
private const val FRAME_DT_MS = 120L
private const val CASCADE_DEVICE_ID = "AA:BB:CC:DD:EE:CA"

private const val START_DBM = -95
private const val STEP_DBM = 1
private const val FRAMES = 42
private val ESCALATION_RSSI = IntArray(FRAMES) { START_DBM + it * STEP_DBM }

/** Release ramp: -1dBm per frame back down from the escalation end value. */
private const val RELEASE_FRAMES = 48
private val RELEASE_RSSI = IntArray(RELEASE_FRAMES) { ESCALATION_RSSI.last() - it }

private val RELEASE_GOLDEN: Array<String> = arrayOf(
    "frame=042 rssi= -54 level=2 entry=3840 track=NONE        dangerStreak=12 warnStreak=25 fastStreak=2 bcast=2",
    "frame=043 rssi= -55 level=2 entry=3840 track=NONE        dangerStreak=13 warnStreak=26 fastStreak=2 bcast=2",
    "frame=044 rssi= -56 level=2 entry=3840 track=NONE        dangerStreak=14 warnStreak=27 fastStreak=2 bcast=2",
    "frame=045 rssi= -57 level=2 entry=3840 track=NONE        dangerStreak=15 warnStreak=28 fastStreak=2 bcast=2",
    "frame=046 rssi= -58 level=2 entry=3840 track=NONE        dangerStreak=16 warnStreak=29 fastStreak=2 bcast=2",
    "frame=047 rssi= -59 level=2 entry=3840 track=NONE        dangerStreak=17 warnStreak=30 fastStreak=2 bcast=2",
    "frame=048 rssi= -60 level=2 entry=3840 track=NONE        dangerStreak=18 warnStreak=31 fastStreak=2 bcast=2",
    "frame=049 rssi= -61 level=2 entry=5880 track=NONE        dangerStreak=19 warnStreak=32 fastStreak=3 bcast=3",
    "frame=050 rssi= -62 level=2 entry=5880 track=NONE        dangerStreak=20 warnStreak=33 fastStreak=3 bcast=3",
    "frame=051 rssi= -63 level=2 entry=5880 track=NONE        dangerStreak=21 warnStreak=34 fastStreak=3 bcast=3",
    "frame=052 rssi= -64 level=2 entry=5880 track=NONE        dangerStreak=22 warnStreak=35 fastStreak=3 bcast=3",
    "frame=053 rssi= -65 level=2 entry=5880 track=NONE        dangerStreak=23 warnStreak=36 fastStreak=3 bcast=3",
    "frame=054 rssi= -66 level=2 entry=5880 track=NONE        dangerStreak=24 warnStreak=37 fastStreak=3 bcast=3",
    "frame=055 rssi= -67 level=2 entry=5880 track=NONE        dangerStreak=0 warnStreak=38 fastStreak=3 bcast=3",
    "frame=056 rssi= -68 level=2 entry=5880 track=NONE        dangerStreak=0 warnStreak=39 fastStreak=3 bcast=3",
    "frame=057 rssi= -69 level=2 entry=5880 track=NONE        dangerStreak=0 warnStreak=40 fastStreak=3 bcast=3",
    "frame=058 rssi= -70 level=2 entry=5880 track=NONE        dangerStreak=0 warnStreak=41 fastStreak=3 bcast=3",
    "frame=059 rssi= -71 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=060 rssi= -72 level=null entry=null track=NONE        dangerStreak=0 warnStreak=1 fastStreak=0 bcast=4",
    "frame=061 rssi= -73 level=null entry=null track=NONE        dangerStreak=0 warnStreak=2 fastStreak=0 bcast=4",
    "frame=062 rssi= -74 level=null entry=null track=NONE        dangerStreak=0 warnStreak=3 fastStreak=0 bcast=4",
    "frame=063 rssi= -75 level=null entry=null track=NONE        dangerStreak=0 warnStreak=4 fastStreak=0 bcast=4",
    "frame=064 rssi= -76 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=5 fastStreak=0 bcast=4",
    "frame=065 rssi= -77 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=6 fastStreak=0 bcast=4",
    "frame=066 rssi= -78 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=7 fastStreak=0 bcast=4",
    "frame=067 rssi= -79 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=8 fastStreak=0 bcast=4",
    "frame=068 rssi= -80 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=069 rssi= -81 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=070 rssi= -82 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=071 rssi= -83 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=072 rssi= -84 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=073 rssi= -85 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=074 rssi= -86 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=075 rssi= -87 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=076 rssi= -88 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=077 rssi= -89 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=078 rssi= -90 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=079 rssi= -91 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=080 rssi= -92 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=081 rssi= -93 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=082 rssi= -94 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=083 rssi= -95 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=084 rssi= -96 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=085 rssi= -97 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=086 rssi= -98 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=087 rssi= -99 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=088 rssi=-100 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=089 rssi=-101 level=null entry=null track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
)

private val RELEASE_KFVEL: DoubleArray = doubleArrayOf(
    8.176264778604308,
    8.197260212185327,
    8.147084501746823,
    8.037364425122673,
    7.810583147250153,
    7.485685707259224,
    7.079036292165381,
    6.604872068102426,
    6.075661876420641,
    5.502389185031138,
    4.894774896098205,
    4.261452618120042,
    3.610106620601832,
    2.9475807588432854,
    2.279965078260135,
    1.6126655040908302,
    1.355294278404182,
    0.0,
    0.0,
    0.0,
    -0.12639157844764148,
    -0.24224475207899904,
    -0.5562398915174208,
    -0.8066096526487592,
    -1.2431282992051726,
    -1.8044854006461972,
    -2.4258482204254355,
    -3.0535781272663263,
    -3.4210443846646057,
    -3.8094348117184595,
    -3.9967843909602707,
    -4.22480476128912,
    -4.305429966072178,
    -4.437077448014632,
    -4.598393505768758,
    -4.774869836896757,
    -4.8401442984012375,
    -4.934602359995415,
    -5.047290679861809,
    -5.170512790586491,
    -5.298903177864456,
    -5.42875972083353,
    -5.4834582179735625,
    -5.550901995155586,
    -5.626962277003681,
    0.0,
    0.0,
    0.0,
)

/**
 * BleService's private trackingStateMap (alias of
 * AlertStateMachine.trackingStateMap), read by reflection; values are used only
 * through toString().
 */
@Suppress("UNCHECKED_CAST")
private fun trackingStateOf(service: BleService, deviceId: String): String {
    val map = ReflectionHelpers.getField(service, "trackingStateMap") as Map<String, *>
    return map[deviceId]?.toString() ?: "NONE"
}

/**
 * Shared reader for BleService's private
 * dangerContactStreakMap/warningContactStreakMap/fastApproachStreakMap (aliases of
 * the AlertStateMachine maps).
 */
@Suppress("UNCHECKED_CAST")
private fun streakOf(service: BleService, fieldName: String, deviceId: String): Int {
    val map = ReflectionHelpers.getField(service, fieldName) as Map<String, Int>
    return map[deviceId] ?: 0
}

/**
 * BleService's private kalmanFilters (alias of AlertStateMachine.kalmanFilters), read by
 * reflection; KalmanFilter.estimatedVel is public, so it is read directly.
 */
@Suppress("UNCHECKED_CAST")
private fun kfVelOf(service: BleService, deviceId: String): Double {
    val map = ReflectionHelpers.getField(service, "kalmanFilters") as Map<String, KalmanFilter>
    return map[deviceId]?.estimatedVel ?: 0.0
}

/** Serializes one frame into one fixed-width line. entry is relative to T0_MS, "null" when absent. */
private fun renderFrame(service: BleService, deviceId: String, frameIdx: Int, rssi: Int): String {
    val level = BleServiceTestHarness.alertLevelOf(service, deviceId)
    val entryRel = BleServiceTestHarness.alertEntryMsOf(service, deviceId)?.minus(T0_MS)
    val track = trackingStateOf(service, deviceId)
    val dangerStreak = streakOf(service, "dangerContactStreakMap", deviceId)
    val warnStreak = streakOf(service, "warningContactStreakMap", deviceId)
    val fastStreak = streakOf(service, "fastApproachStreakMap", deviceId)
    val bcast = BleServiceTestHarness.alertBroadcasts().size
    return "frame=%03d rssi=%4d level=%s entry=%s track=%-11s dangerStreak=%d warnStreak=%d fastStreak=%d bcast=%d"
        .format(frameIdx, rssi, level?.toString() ?: "null", entryRel?.toString() ?: "null", track, dangerStreak, warnStreak, fastStreak, bcast)
}

/**
 * Advances nowMs by FRAME_DT_MS on every frame and calls callProcessAlert, returning the renderFrame
 * string array together with the kfVel DoubleArray. startFrame is the global frame number (the release
 * run continues the numbering).
 */
private fun runScenario(
    service: BleService,
    deviceId: String,
    rssiSeq: IntArray,
    startFrame: Int,
): Pair<Array<String>, DoubleArray> {
    val frames = Array(rssiSeq.size) { "" }
    val kfVel = DoubleArray(rssiSeq.size)
    for (i in rssiSeq.indices) {
        val frameIdx = startFrame + i
        val nowMs = T0_MS + frameIdx * FRAME_DT_MS
        BleServiceTestHarness.callProcessAlert(service, deviceId, rssiSeq[i], nowMs = nowMs)
        frames[i] = renderFrame(service, deviceId, frameIdx, rssiSeq[i])
        kfVel[i] = kfVelOf(service, deviceId)
    }
    return frames to kfVel
}

/** Two assertions per frame: the render string, and kfVel within delta 1e-9. */
private fun assertScenario(
    scenario: String,
    actual: Pair<Array<String>, DoubleArray>,
    expectedFrames: Array<String>,
    expectedKfVel: DoubleArray,
) {
    val (frames, kfVel) = actual
    for (i in expectedFrames.indices) {
        assertEquals("$scenario frame=$i stage=render", expectedFrames[i], frames[i])
        assertEquals("$scenario frame=$i stage=kfVel", expectedKfVel[i], kfVel[i], 1e-9)
    }
}

/**
 * Escalation (SAFE→WARNING→DANGER) golden. release_goldenTimeline asserts it first, before replaying the release.
 * record-then-freeze: [ESCALATION_GOLDEN]/[ESCALATION_KFVEL] are values captured from one real run,
 * never computed by hand. Re-freeze only by editing these arrays in this file by hand (there is no
 * automatic update path).
 *
 * Parameters: START_DBM=-95, STEP_DBM=+1, FRAMES=42, recorded with the default thresholds (DANGER -65,
 * WARNING -78) and filter-keep band 10. Observed (not predicted): warnStreak starts at frame=018 and
 * WARNING is first entered at frame=019 (rssi=-76, entry=2280); dangerStreak starts at frame=031
 * (rssi=-64) and DANGER is first entered at frame=032 (rssi=-63, entry=3840). Each entry lands 2 frames
 * after the raw threshold crossing (WARNING -78 at frame=017, DANGER -65 at frame=030): the streaks
 * count medianValue, and the median-of-3 trails a rising ramp by 1 frame, so a streak starts one frame
 * after the crossing and the entry lands when it reaches 2. Level hysteresis only holds a level on the
 * way down and plays no part in entry. pEma lags further still, so DANGER at frame=032 is raised by the
 * dangerStreak>=2 immediate escalation in processAlert. No tuning needed (all three stages
 * SAFE/WARNING/DANGER are observed, rising monotonically).
 *
 * Kalman clock: KalmanFilter computes dt from its own nowMs, which defaults to the real
 * System.currentTimeMillis() and ignores the frame-time seam injected into processAlert. In this tight
 * in-memory loop the real time between calls is usually under 50ms, so dt would mostly sit at its
 * coerceIn floor of 0.05s instead of FRAME_DT_MS=120L (0.12s), and kfVel and alert re-entry timing
 * would jitter between runs. The harness therefore aligns nowMs/lastTsMs of each newly created
 * KalmanFilter with the injected time by reflection, leaving production code untouched (see
 * BleServiceTestHarness.kt).
 */
// The two arrays below are captured from one real run: never compute them by hand; re-freeze only by editing this file by hand.
private val ESCALATION_GOLDEN: Array<String> = arrayOf(
    "frame=000 rssi= -95 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=001 rssi= -94 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=002 rssi= -93 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=003 rssi= -92 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=004 rssi= -91 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=005 rssi= -90 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=006 rssi= -89 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=007 rssi= -88 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=008 rssi= -87 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=009 rssi= -86 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=010 rssi= -85 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=011 rssi= -84 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=012 rssi= -83 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=013 rssi= -82 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=014 rssi= -81 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=015 rssi= -80 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=016 rssi= -79 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=017 rssi= -78 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=018 rssi= -77 level=null entry=null track=NONE        dangerStreak=0 warnStreak=1 fastStreak=0 bcast=0",
    "frame=019 rssi= -76 level=1 entry=2280 track=NONE        dangerStreak=0 warnStreak=2 fastStreak=1 bcast=1",
    "frame=020 rssi= -75 level=1 entry=2280 track=NONE        dangerStreak=0 warnStreak=3 fastStreak=1 bcast=1",
    "frame=021 rssi= -74 level=1 entry=2280 track=NONE        dangerStreak=0 warnStreak=4 fastStreak=1 bcast=1",
    "frame=022 rssi= -73 level=1 entry=2280 track=NONE        dangerStreak=0 warnStreak=5 fastStreak=1 bcast=1",
    "frame=023 rssi= -72 level=1 entry=2280 track=NONE        dangerStreak=0 warnStreak=6 fastStreak=1 bcast=1",
    "frame=024 rssi= -71 level=1 entry=2280 track=NONE        dangerStreak=0 warnStreak=7 fastStreak=1 bcast=1",
    "frame=025 rssi= -70 level=1 entry=2280 track=NONE        dangerStreak=0 warnStreak=8 fastStreak=1 bcast=1",
    "frame=026 rssi= -69 level=1 entry=2280 track=NONE        dangerStreak=0 warnStreak=9 fastStreak=1 bcast=1",
    "frame=027 rssi= -68 level=1 entry=2280 track=NONE        dangerStreak=0 warnStreak=10 fastStreak=1 bcast=1",
    "frame=028 rssi= -67 level=1 entry=2280 track=NONE        dangerStreak=0 warnStreak=11 fastStreak=1 bcast=1",
    "frame=029 rssi= -66 level=1 entry=2280 track=NONE        dangerStreak=0 warnStreak=12 fastStreak=1 bcast=1",
    "frame=030 rssi= -65 level=1 entry=2280 track=NONE        dangerStreak=0 warnStreak=13 fastStreak=1 bcast=1",
    "frame=031 rssi= -64 level=1 entry=2280 track=NONE        dangerStreak=1 warnStreak=14 fastStreak=1 bcast=1",
    "frame=032 rssi= -63 level=2 entry=3840 track=NONE        dangerStreak=2 warnStreak=15 fastStreak=2 bcast=2",
    "frame=033 rssi= -62 level=2 entry=3840 track=NONE        dangerStreak=3 warnStreak=16 fastStreak=2 bcast=2",
    "frame=034 rssi= -61 level=2 entry=3840 track=NONE        dangerStreak=4 warnStreak=17 fastStreak=2 bcast=2",
    "frame=035 rssi= -60 level=2 entry=3840 track=NONE        dangerStreak=5 warnStreak=18 fastStreak=2 bcast=2",
    "frame=036 rssi= -59 level=2 entry=3840 track=NONE        dangerStreak=6 warnStreak=19 fastStreak=2 bcast=2",
    "frame=037 rssi= -58 level=2 entry=3840 track=NONE        dangerStreak=7 warnStreak=20 fastStreak=2 bcast=2",
    "frame=038 rssi= -57 level=2 entry=3840 track=NONE        dangerStreak=8 warnStreak=21 fastStreak=2 bcast=2",
    "frame=039 rssi= -56 level=2 entry=3840 track=NONE        dangerStreak=9 warnStreak=22 fastStreak=2 bcast=2",
    "frame=040 rssi= -55 level=2 entry=3840 track=NONE        dangerStreak=10 warnStreak=23 fastStreak=2 bcast=2",
    "frame=041 rssi= -54 level=2 entry=3840 track=NONE        dangerStreak=11 warnStreak=24 fastStreak=2 bcast=2",
)

private val ESCALATION_KFVEL: DoubleArray = doubleArrayOf(
    0.0,
    0.0,
    0.0,
    0.0,
    0.0,
    0.0,
    0.0,
    0.0,
    0.0,
    0.0,
    0.17665215421634556,
    0.32990777832613877,
    0.6948125914891918,
    1.2274236473495013,
    1.8637019724022759,
    2.5393200275920087,
    3.2033483413287653,
    4.3554105773725516,
    5.042121161874165,
    5.533440642814644,
    5.917497821859853,
    6.231974066660194,
    6.496135421965668,
    6.721312185003059,
    6.914978721445221,
    7.082529602461218,
    7.228117210445694,
    7.355075068244827,
    7.466148223068631,
    7.563629953419711,
    7.649450992594242,
    7.725243261751794,
    7.792388779185007,
    7.852059051472556,
    7.905247708607793,
    7.95279793807564,
    7.995425695494777,
    8.03373938439023,
    8.068256545525596,
    8.09941800337512,
    8.127599850917493,
    8.153123600097405,
)

/**
 * Sudden-contact sub-scenario input: 5 frames at -86dBm, then 5 frames at -62dBm (a 24dB step).
 * -86dBm is inside the filter-keep band, so the far frames keep the median, pEma and Kalman state and the
 * step starts from warm filters sitting at -86.
 * Uses its own device ID and service instance, separate from CASCADE_DEVICE_ID, so no
 * escalation/release state leaks in.
 */
private const val CONTACT_DEVICE_ID = "AA:BB:CC:DD:EE:FC"
private const val CONTACT_WARMUP_FRAMES = 5
private const val CONTACT_STRONG_FRAMES = 5
private const val CONTACT_FRAMES = CONTACT_WARMUP_FRAMES + CONTACT_STRONG_FRAMES
private val CONTACT_RSSI = IntArray(CONTACT_FRAMES) { if (it < CONTACT_WARMUP_FRAMES) -86 else -62 }

// The two arrays below are captured from one real run: never compute them by hand; re-freeze only by
// editing this file by hand.
// At frame=005 the median still holds two -86 samples, so the streaks start at frame=006 (1) and reach 2
// at frame=007, where DANGER registers (entry=840) through the immediate escalation and the 2-frame
// fast-contact confirmation. That first-detection frame also evaluates the Time-Gate (kfVel above 2.0, so
// fastStreak=1); later frames return before it. kfVel leaves 0 once the median steps up at frame=006.
private val CONTACT_GOLDEN: Array<String> = arrayOf(
    "frame=000 rssi= -86 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=001 rssi= -86 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=002 rssi= -86 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=003 rssi= -86 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=004 rssi= -86 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=005 rssi= -62 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=006 rssi= -62 level=null entry=null track=NONE        dangerStreak=1 warnStreak=1 fastStreak=0 bcast=0",
    "frame=007 rssi= -62 level=2 entry=840 track=NONE        dangerStreak=2 warnStreak=2 fastStreak=1 bcast=1",
    "frame=008 rssi= -62 level=2 entry=840 track=NONE        dangerStreak=3 warnStreak=3 fastStreak=1 bcast=1",
    "frame=009 rssi= -62 level=2 entry=840 track=NONE        dangerStreak=4 warnStreak=4 fastStreak=1 bcast=1",
)

private val CONTACT_KFVEL: DoubleArray = doubleArrayOf(
    0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
    1.814031575639786,
    4.473953659225993,
    7.610992256714553,
    12.804873720349933,
)
