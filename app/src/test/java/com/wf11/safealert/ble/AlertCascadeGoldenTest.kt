package com.wf11.safealert.ble

import android.app.NotificationManager
import androidx.lifecycle.Lifecycle
import com.wf11.safealert.service.BleService
import com.wf11.safealert.support.BleServiceTestHarness
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.OverlayManager
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
import org.robolectric.util.ReflectionHelpers

/**
 * 02-01 골든 캐스케이드 회귀 테스트 — record-then-freeze(D-09/D-12/P-02).
 * processAlert() 는 private 이라 리플렉션으로 진입한다(support/BleServiceTestHarness.kt 경유).
 * 기대값은 여기 수기 동결한 스냅샷이며, 자동 재생성 경로는 두지 않는다(수동 재동결만 허용).
 *
 * Assumption A1 트라이얼(먼저 판정) — 02-RESEARCH.md 최대 리스크: Robolectric.buildService(...).get()
 * 이 onCreate() 를 실행하지 않고 인스턴스만 반환하는지. 이 전제가 깨지면 이후(harness/seam) 설계
 * 전체가 무효이므로 Wave 1 에서 다른 코드보다 먼저 이 트라이얼만 판정한다.
 *
 * [Task 2 스모크] "1프레임 호출" 해석 노트 — must_haves.truths #1 은 문면상 단일 호출을 말하나,
 * shouldAlert 게이트(BleService.kt:2334)는 신규 감지에 대해 "!warmingUp"(median 3표본 충족) 또는
 * "fastContact"(danger/warning streak≥2, v1.1.18)를 요구한다. MedianFilter.DEFAULT_WINDOW=3 이라
 * 진짜 첫 단일 호출은 수학적으로 이 게이트를 통과할 수 없다(warmingUp 항상 true, streak 항상 1).
 * 대신 stableLevel 자체는 calcLevelWithHysteresis 가 streak 와 무관하게 매 프레임 raw 임계 비교로
 * 즉시 산출하므로(BleService.kt:1228), 동일 강한 RSSI 로 2회 연속 호출하면 danger streak 가 2 에
 * 도달해 fastContact 로 두 번째 호출에서 게이트를 통과한다 — 이것이 production 자체의 "2프레임
 * 확증" 설계(v1.1.16/v1.1.22 계보)이므로, 이 스모크는 "관측 대상 프레임(마지막 1콜)" 을 검증하는
 * 것으로 truth #1 을 해석한다. 1콜째=미발령 확인, 2콜째=alertState+BROADCAST_ALERT 관측.
 */
@RunWith(RobolectricTestRunner::class)
class AlertCascadeGoldenTest {

    @Test
    fun assumptionA1_getWithoutCreate_staysInitialized() {
        // 스모크 1: buildService(...).get() 은 attach 만 하고 onCreate() 는 실행하지 않아야 한다.
        val controller = Robolectric.buildService(BleService::class.java)
        val service = controller.get()
        assertNotNull(service)
        assertEquals(
            "Robolectric.buildService(...).get() 이 onCreate() 를 실행했다 — Assumption A1 위반",
            Lifecycle.State.INITIALIZED,
            service.lifecycle.currentState
        )
        // onCreate() 가 실행됐다면 companion isRunning=true 로 바뀐다(BleService.kt:850) — 이중 확인.
        assertFalse("companion isRunning=true — onCreate() 부작용 감지", BleService.isRunning)

        // 스모크 1-b: onCreate() 부작용 3종 부재 확인(리시버 미등록·알림채널 미생성·브로드캐스트 없음).
        // 매니페스트 정적 리시버(Firebase AppMeasurementReceiver, androidx profileinstaller 등)는
        // Application 기동 시점에 이미 등록되어 baseline noise 로 존재 — BleService.onCreate() 와
        // 무관하다. 검증 대상은 "우리 앱 컴포넌트가 동적으로 새로 등록한 리시버"뿐이므로
        // com.wf11.safealert 패키지 소속 리시버만 필터링한다(실측: DEBUG 로 baseline 확인 완료).
        val appShadow = shadowOf(RuntimeEnvironment.getApplication())
        val ownReceivers = appShadow.registeredReceivers.filter {
            it.broadcastReceiver::class.java.name.startsWith("com.wf11.safealert")
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
        val dangerRssi = -30   // rssiDanger 기본 -55 보다 훨씬 강함 → 매 프레임 즉시 DANGER 레벨
        var clockMs = 1_000L

        // 1콜: median/streak 워밍업 미충족 → shouldAlert=false → 아직 미등록(D-2B 의 "1프레임"은
        // 아래 2콜째 관측 프레임을 가리킨다 — 클래스 헤더 해석 노트 참조).
        BleServiceTestHarness.callProcessAlert(service, deviceId, dangerRssi, nowMs = clockMs)
        assertNull(
            "워밍업 1콜만으로 alertState 가 등록됐다 — shouldAlert 게이트가 조기 통과함",
            BleServiceTestHarness.alertLevelOf(service, deviceId)
        )
        assertEquals(0, BleServiceTestHarness.alertBroadcasts().size)

        // 2콜: dangerStreak=2 → fastContact 로 shouldAlert 통과 → alertState 등록 + BROADCAST_ALERT.
        clockMs += 120L
        BleServiceTestHarness.callProcessAlert(service, deviceId, dangerRssi, nowMs = clockMs)
        assertEquals(
            BleConstants.LEVEL_DANGER,
            BleServiceTestHarness.alertLevelOf(service, deviceId)
        )
        // 스모크 4: nowMs seam 주입값(clockMs)이 alertState 등록시각에 그대로 반영됐는지 증명.
        assertEquals(clockMs, BleServiceTestHarness.alertEntryMsOf(service, deviceId))
        assertEquals(1, BleServiceTestHarness.alertBroadcasts().size)
        assertEquals(deviceId, BleServiceTestHarness.alertBroadcasts().first().getStringExtra(BleService.EXTRA_ID))
    }

    @Test
    fun goldenProfile_neutralizesSideEffects() {
        // Task 3 부작용 무해화 단언(D-2D) — newService() 가 적용한 골든 프로파일이 진동·소리·
        // Firebase 자동저장·오버레이 4종을 모두 차단하는 구성인지 확인한다. 완전성(31개 심볼 중
        // 30줄 대입 커버)은 plan Task 3 <verify> 의 grep-count 비교 커맨드가 담당(테스트 코드 중복 방지).
        BleServiceTestHarness.newService()
        val context = RuntimeEnvironment.getApplication()

        assertFalse("골든 프로파일인데 vibrationEnabled=true — 진동 무해화 실패", DevSettings.vibrationEnabled)
        assertFalse("골든 프로파일인데 soundEnabled=true — 소리 무해화 실패", DevSettings.soundEnabled)
        assertFalse("골든 프로파일인데 autoSaveAlerts=true — Firebase 저장 무해화 실패", DevSettings.autoSaveAlerts)
        assertFalse(
            "골든 프로파일인데 canDrawOverlays=true — OverlayManager.showSidebar 게이트가 무력화된다",
            OverlayManager.canDrawOverlays(context)
        )
    }

    @Test
    fun goldenProfile_isDeterministic() {
        // Task 3 결정성 단언 — applyGoldenDevSettings() 재호출이 구성을 바꾸지 않고, 동일 스모크
        // 시나리오가 두 개의 독립 서비스 인스턴스에서 동일 결과(레벨·등록시각·브로드캐스트 수)를
        // 낸다는 것을 확인한다.
        BleServiceTestHarness.newService() // 골든 기준선 확립(DevSettings.init 포함) — 이전 테스트 잔존 상태에 의존하지 않는다.
        val configBefore = goldenConfigSnapshot()
        BleServiceTestHarness.applyGoldenDevSettings()
        val configAfter = goldenConfigSnapshot()
        assertEquals("applyGoldenDevSettings() 재호출이 구성을 바꿨다 — 비결정적", configBefore, configAfter)

        val deviceId = "AA:BB:CC:DD:EE:77"
        val dangerRssi = -30

        val serviceA = BleServiceTestHarness.newService()
        var clockA = 1_000L
        BleServiceTestHarness.callProcessAlert(serviceA, deviceId, dangerRssi, nowMs = clockA)
        clockA += 120L
        BleServiceTestHarness.callProcessAlert(serviceA, deviceId, dangerRssi, nowMs = clockA)
        val resultA = BleServiceTestHarness.alertLevelOf(serviceA, deviceId) to BleServiceTestHarness.alertEntryMsOf(serviceA, deviceId)
        val broadcastsA = BleServiceTestHarness.alertBroadcasts().size
        BleServiceTestHarness.resetBetweenTests(serviceA)

        val serviceB = BleServiceTestHarness.newService()
        var clockB = 1_000L
        BleServiceTestHarness.callProcessAlert(serviceB, deviceId, dangerRssi, nowMs = clockB)
        clockB += 120L
        BleServiceTestHarness.callProcessAlert(serviceB, deviceId, dangerRssi, nowMs = clockB)
        val resultB = BleServiceTestHarness.alertLevelOf(serviceB, deviceId) to BleServiceTestHarness.alertEntryMsOf(serviceB, deviceId)
        val broadcastsB = BleServiceTestHarness.alertBroadcasts().size

        assertEquals("동일 골든 구성인데 스모크 결과(레벨/등록시각)가 다르다", resultA, resultB)
        assertEquals("동일 골든 구성인데 브로드캐스트 수가 다르다", broadcastsA, broadcastsB)
    }

    /** 골든 프로파일 30줄 대입 전체를 순서 무관 비교용 목록으로 스냅샷(결정성 단언 전용). */
    private fun goldenConfigSnapshot(): List<Any> = listOf(
        DevSettings.autoSaveAlerts,
        DevSettings.beaconGainPercent,
        DevSettings.coopSlackDb,
        DevSettings.debugMode,
        DevSettings.echoAutoCalibEnabled,
        DevSettings.fastApproachBypassVelDbm,
        DevSettings.idleIdleSuppressEnabled,
        DevSettings.idleIdleSuppressEpjPairsEnabled,
        DevSettings.imuShadowFusionEnabled,
        DevSettings.kalmanPreset,
        DevSettings.logVerbose,
        DevSettings.reciprocalMaxDisagreeDb,
        DevSettings.reciprocalRssiEnabled,
        DevSettings.reversePrepEnabled,
        DevSettings.reversePrepHoldMs,
        DevSettings.reverseRiseDbm,
        DevSettings.reverseStableTolDb,
        DevSettings.reverseWindowMs,
        DevSettings.rssiWarning,
        DevSettings.soundEnabled,
        DevSettings.uwbApproachSpeedKmh,
        DevSettings.uwbForkliftDangerMeters,
        DevSettings.uwbForkliftWarnMeters,
        DevSettings.uwbPairDangerMeters,
        DevSettings.uwbPairWarnMeters,
        DevSettings.uwbPrimaryAuthorityEnabled,
        DevSettings.uwbPromoteEnabled,
        DevSettings.uwbVelPromoteEnabled,
        DevSettings.uwbVelReleaseEnabled,
        DevSettings.vibrationEnabled
    )

    /**
     * 02-02 Task 1 — 격상(SAFE→WARNING→DANGER) 골든, 프레임별 기록·동결(D-2E/D-2F/D-2G).
     * record-then-freeze: [ESCALATION_GOLDEN]/[ESCALATION_KFVEL] 는 실제 1회 구동에서 캡처된 값이며
     * 손으로 계산하지 않는다. 재동결은 이 배열의 수동 파일 편집만 허용(자동 갱신 경로 없음, T-02-05).
     *
     * 기록 시점: versionName=1.1.70 versionCode=126, commit=6760f60, 2026-08-28T00:19:42Z.
     * 채택 파라미터: START_DBM=-95, STEP_DBM=+1, FRAMES=42 — 실측(사전 예측 아님): WARNING 최초
     * 진입 frame=22(rssi=-73), DANGER 최초 진입 frame=39(rssi=-56). streak/hysteresis 게이트로
     * 인해 raw 임계 교차(rssiWarning=-75 @ i=20, rssiDanger=-55 @ i=40)보다 1~2프레임 지연 — 별도
     * 조정 불필요(SAFE/WARNING/DANGER 3단계 모두 관측, 단조 증가).
     *
     * 재동결(D-2E 근본원인 수정, 2026-08-28): KalmanFilter.update() 가 생성자 기본 nowMs
     * (real System.currentTimeMillis())를 그대로 써서 dt 를 실제 벽시계로 계산 — processAlert 에
     * 주입한 프레임 시각 seam 과 무관했다(BleServiceTestHarness.kt 참고). 실행마다 tight in-memory
     * 루프의 real elapsed time 이 대개 50ms 미만이라 dt 가 coerceIn 하한 0.05s 로 대부분 바닥
     * 고정되던 값이 최초 동결본(kfVel, 그리고 frame=40 entry 재진입 흔들림)에 섞여 있었다 —
     * FRAME_DT_MS=120L(0.12s) 을 의도한 golden 이 아니었다. 하네스에 리플렉션 시임을 추가해
     * 콜드스타트로 새로 생성된 KalmanFilter 인스턴스의 nowMs/lastTsMs 를 주입 시각에 정렬한 뒤
     * (production 코드 무수정) 재구동·재동결 — kfVel 전 구간 값 변경 + frame=40/41 entry 가
     * 4800→4680 로 안정화(재진입 흔들림 제거, 더 이상 관측 노이즈가 아님).
     */
    @Test
    fun escalation_goldenTimeline() {
        val service = BleServiceTestHarness.newService()
        BleServiceTestHarness.resetBetweenTests(service)
        val actual = runScenario(service, CASCADE_DEVICE_ID, ESCALATION_RSSI, startFrame = 0)
        assertEquals("escalation 프레임 수 불일치", FRAMES, actual.first.size)
        assertScenario("escalation", actual, ESCALATION_GOLDEN, ESCALATION_KFVEL)
    }

    /**
     * 02-02 Task 2 — 해제(DANGER→WARNING→SAFE) 골든, 격상 종단 상태에서 이어 재생(D-2E/D-2F/D-2G).
     * record-then-freeze: [RELEASE_GOLDEN]/[RELEASE_KFVEL] 캡처 전까지는 CAPTURE PLACEHOLDER.
     */
    @Test
    fun release_goldenTimeline() {
        val service = BleServiceTestHarness.newService()
        BleServiceTestHarness.resetBetweenTests(service)
        val escalation = runScenario(service, CASCADE_DEVICE_ID, ESCALATION_RSSI, startFrame = 0)
        assertScenario("escalation", escalation, ESCALATION_GOLDEN, ESCALATION_KFVEL)
        val actual = runScenario(service, CASCADE_DEVICE_ID, RELEASE_RSSI, startFrame = FRAMES)
        assertEquals("release 프레임 수 불일치", RELEASE_FRAMES, actual.first.size)
        org.junit.Assert.fail(actual.first.joinToString("\n") + "\n---KFVEL---\n" + actual.second.joinToString(","))
    }

    /**
     * 02-02 Task 2 — 동일 입력 재생 결정성 확인(acceptance: 격상+해제 전체를 두 번 재생해 완전 동일).
     */
    @Test
    fun sameSequence_replaysIdentically() {
        fun playFull(): Pair<Array<String>, DoubleArray> {
            val service = BleServiceTestHarness.newService()
            BleServiceTestHarness.resetBetweenTests(service)
            val esc = runScenario(service, CASCADE_DEVICE_ID, ESCALATION_RSSI, startFrame = 0)
            val rel = runScenario(service, CASCADE_DEVICE_ID, RELEASE_RSSI, startFrame = FRAMES)
            return (esc.first + rel.first) to (esc.second + rel.second)
        }
        val run1 = playFull()
        val run2 = playFull()
        assertEquals("재생1·재생2 프레임 배열 불일치", run1.first.toList(), run2.first.toList())
        for (i in run1.second.indices) {
            assertEquals("재생1·재생2 kfVel[$i] 불일치", run1.second[i], run2.second[i], 1e-9)
        }
    }
}

// ── 프레임별 골든 캐스케이드 배선 (02-02 D-2E/D-2F/D-2G) ────────────────────────────────
// 관측면: alertState(레벨+등록시각 t0상대)·트래킹상태·3종 streak맵·누적 브로드캐스트 수를 한 줄로
// 렌더링(D-2F 풀프레임 기록) + kfVel(estimatedVel)은 D-2E 지시대로 문자열과 분리된 별도 DoubleArray.
// medianValue/avgRssi 는 관측면에서 제외한다(Phase 1 커버 영역, 중복 금지 — must_haves.prohibitions).

private const val T0_MS = 1_000_000L
private const val FRAME_DT_MS = 120L
private const val CASCADE_DEVICE_ID = "AA:BB:CC:DD:EE:CA"

private const val START_DBM = -95
private const val STEP_DBM = 1
private const val FRAMES = 42
private val ESCALATION_RSSI = IntArray(FRAMES) { START_DBM + it * STEP_DBM }

/** 02-02 Task 2 — 해제 램프: 격상 종단값에서 -1dBm/프레임 역방향(동결 전 조정 허용, plan Task 2 action). */
private const val RELEASE_FRAMES = 48
private val RELEASE_RSSI = IntArray(RELEASE_FRAMES) { ESCALATION_RSSI.last() - it }

/** BleService.kt:384 private val alertState — Pair(level, entryMs) 판독은 하네스가 이미 제공. */
/** BleService.kt:490-493 private enum TrackingState + trackingStateMap — 리플렉션 전용(private 타입이라 캐스트 없이 toString만 사용). */
@Suppress("UNCHECKED_CAST")
private fun trackingStateOf(service: BleService, deviceId: String): String {
    val map = ReflectionHelpers.getField(service, "trackingStateMap") as Map<String, *>
    return map[deviceId]?.toString() ?: "NONE"
}

/** BleService.kt:477/480/658 — dangerContactStreakMap/warningContactStreakMap/fastApproachStreakMap 공용 판독. */
@Suppress("UNCHECKED_CAST")
private fun streakOf(service: BleService, fieldName: String, deviceId: String): Int {
    val map = ReflectionHelpers.getField(service, fieldName) as Map<String, Int>
    return map[deviceId] ?: 0
}

/** BleService.kt:423 private val kalmanFilters — KalmanFilter.estimatedVel(public)은 리플렉션 없이 직접 접근. */
@Suppress("UNCHECKED_CAST")
private fun kfVelOf(service: BleService, deviceId: String): Double {
    val map = ReflectionHelpers.getField(service, "kalmanFilters") as Map<String, KalmanFilter>
    return map[deviceId]?.estimatedVel ?: 0.0
}

/** 프레임 1개를 고정폭 한 줄로 직렬화(D-2F). entry 는 T0_MS 상대(D-2G), 부재시 "null". */
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
 * Phase 1 RssiCascadeTest.kt 의 runCascade(:130-156) 개명·이식 — 매 프레임 nowMs 를
 * FRAME_DT_MS 간격으로 전진시키며 callProcessAlert 를 호출하고, renderFrame 문자열 배열과
 * kfVel DoubleArray 를 함께 반환한다. startFrame 은 전역 프레임 번호(release 구간은 이어서 번호를 매김).
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

/** Phase 1 assertCascade(:158-173) 개명·이식 — 프레임별 2단언(render 문자열 / kfVel delta 1e-9). */
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

// 아래 두 배열은 1회 실제 구동 캡처값 — 손 계산 금지, 재동결은 수동 파일 편집만 허용(T-02-05).
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
    "frame=018 rssi= -77 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=019 rssi= -76 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=020 rssi= -75 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=021 rssi= -74 level=null entry=null track=NONE        dangerStreak=0 warnStreak=1 fastStreak=0 bcast=0",
    "frame=022 rssi= -73 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=2 fastStreak=1 bcast=1",
    "frame=023 rssi= -72 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=3 fastStreak=1 bcast=1",
    "frame=024 rssi= -71 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=4 fastStreak=1 bcast=1",
    "frame=025 rssi= -70 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=5 fastStreak=1 bcast=1",
    "frame=026 rssi= -69 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=6 fastStreak=1 bcast=1",
    "frame=027 rssi= -68 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=7 fastStreak=1 bcast=1",
    "frame=028 rssi= -67 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=8 fastStreak=1 bcast=1",
    "frame=029 rssi= -66 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=9 fastStreak=1 bcast=1",
    "frame=030 rssi= -65 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=10 fastStreak=1 bcast=1",
    "frame=031 rssi= -64 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=11 fastStreak=1 bcast=1",
    "frame=032 rssi= -63 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=12 fastStreak=1 bcast=1",
    "frame=033 rssi= -62 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=13 fastStreak=1 bcast=1",
    "frame=034 rssi= -61 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=14 fastStreak=1 bcast=1",
    "frame=035 rssi= -60 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=15 fastStreak=1 bcast=1",
    "frame=036 rssi= -59 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=16 fastStreak=1 bcast=1",
    "frame=037 rssi= -58 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=17 fastStreak=1 bcast=1",
    "frame=038 rssi= -57 level=1 entry=2640 track=NONE        dangerStreak=0 warnStreak=18 fastStreak=1 bcast=1",
    "frame=039 rssi= -56 level=2 entry=4680 track=NONE        dangerStreak=0 warnStreak=19 fastStreak=1 bcast=2",
    "frame=040 rssi= -55 level=2 entry=4680 track=NONE        dangerStreak=0 warnStreak=20 fastStreak=1 bcast=2",
    "frame=041 rssi= -54 level=2 entry=4680 track=NONE        dangerStreak=1 warnStreak=21 fastStreak=1 bcast=2",
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
    0.0,
    0.0,
    0.12639157844764148,
    0.24224475207899904,
    0.5562398915174208,
    0.8066096526487592,
    1.2431282992051726,
    1.8044854006461972,
    2.4258482204254355,
    3.0535781272663263,
    4.16475007211213,
    4.8406269992439315,
    5.332957473565471,
    5.723714481429383,
    6.047799729391954,
    6.322992108418827,
    6.559748101029502,
    6.765007436263532,
    6.943837298134349,
    7.100196573329795,
    7.237313846632162,
    7.357887878267561,
    7.464203916114533,
    7.558208847965029,
    7.641565242572638,
    7.7156936519290005,
    7.781807575476359,
    7.840943201891307,
    7.893985019290374,
    7.941687939337548,
    7.9846963918009815,
    8.023560763714093,
)
