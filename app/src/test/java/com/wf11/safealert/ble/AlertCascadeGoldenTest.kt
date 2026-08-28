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
     * 02-02 Task 2 — 해제(DANGER→SAFE) 골든, 격상 종단 상태에서 이어 재생(D-2E/D-2F/D-2G).
     * record-then-freeze: [RELEASE_GOLDEN]/[RELEASE_KFVEL] 는 실제 1회 구동에서 캡처된 값이며
     * 손으로 계산하지 않는다. 재동결은 이 배열의 수동 파일 편집만 허용(자동 갱신 경로 없음, T-02-05).
     *
     * 기록 시점: versionName=1.1.70 versionCode=126, commit=73f4145, 2026-08-28.
     * 채택 파라미터: RELEASE_FRAMES=48(격상 종단 rssi=-54 에서 -1dBm/프레임 역방향, frame=042~089).
     * 실측: DANGER(level=2) 로 시작해 frame=078(rssi=-90)에서 level=null(SAFE)로 전환, 이후
     * 끝까지 SAFE 유지 — acceptance("DANGER 시작·SAFE 종료") 충족. **WARNING(level=1) 은 이 해제
     * 구간에서 경유하지 않았다** — DANGER 히스테리시스가 SAFE 로 바로 떨어질 만큼 빠르게 풀렸다는
     * 뜻이며, 시퀀스를 넓혀도 바뀌는 성질이 아니라(레벨 재진입 로직 자체의 특성) 그대로 동결한다.
     * frame=056(rssi=-68)에서 level=2 유지 중 entry 가 4680→6720 로, bcast 가 2→3 로 변하는데
     * 이는 DANGER 레벨을 유지한 채로 재진입(재브로드캐스트)이 발생한 실측 동작이며 버그 조사
     * 대상이 아니다(record-then-freeze 원칙 — 관측된 그대로 동결).
     */
    @Test
    fun release_goldenTimeline() {
        val service = BleServiceTestHarness.newService()
        BleServiceTestHarness.resetBetweenTests(service)
        val escalation = runScenario(service, CASCADE_DEVICE_ID, ESCALATION_RSSI, startFrame = 0)
        assertScenario("escalation", escalation, ESCALATION_GOLDEN, ESCALATION_KFVEL)
        val actual = runScenario(service, CASCADE_DEVICE_ID, RELEASE_RSSI, startFrame = FRAMES)
        assertEquals("release 프레임 수 불일치", RELEASE_FRAMES, actual.first.size)
        assertScenario("release", actual, RELEASE_GOLDEN, RELEASE_KFVEL)
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

    /**
     * 02-02 Task 3 checkpoint 재개(Option A — 시퀀스 확장) — 급접촉(sudden first-contact) 서브
     * 시나리오. escalation_goldenTimeline 은 1dBm/프레임 완만한 램프라 dangerStreak 가 2 에 도달하기
     * 전에 pEma(BleService.kt:1790-1814, calcLevelWithHysteresis)가 먼저 DANGER 문턱을 넘어
     * (frame=039 dangerStreak=0 로 확인됨) :1885 즉시격상 오버레이가 관여할 기회가 없었다(1차
     * red-trial: dangerStreak>=2→3 변경에도 BUILD SUCCESSFUL — 오버레이 미관여 증명).
     *
     * 이 시나리오는 median 필터(window=3, MedianFilter.kt) 완전 충전 정지(-95dBm 5프레임) 후
     * 강한 근접(-30dBm, 스모크 테스트 processAlert_strongDangerContact... 와 동일 강도)으로 계단형
     * 점프시킨다 — medianValue(무평활 median-of-3)는 window 가 강한 표본으로 채워지는 1~2 프레임
     * 만에 effDanger 를 넘지만, pEma(다단 비대칭 평활)는 -95→-30 65dB 낙차를 그만큼 빨리 못
     * 쫓아간다. 그 간극에서 dangerStreak>=2 가 stableLevel 을 raw 로 강제 격상하는 :1885 분기가
     * 실제로 발화한다 — 골든의 level 전이 프레임과 dangerStreak 값이 같은 프레임에서 함께
     * 확인되면 pEma 단독 경로가 아니라 오버레이가 원인임을 시각적으로 증명한다(최종 확증은
     * red-trial 재시도: :1885 dangerStreak>=2→3).
     */
    @Test
    fun suddenContact_dangerOverride_bypassesPEmaLag() {
        val service = BleServiceTestHarness.newService()
        BleServiceTestHarness.resetBetweenTests(service)
        val actual = runScenario(service, CONTACT_DEVICE_ID, CONTACT_RSSI, startFrame = 0)
        assertEquals("suddenContact 프레임 수 불일치", CONTACT_FRAMES, actual.first.size)
        assertScenario("suddenContact", actual, CONTACT_GOLDEN, CONTACT_KFVEL)
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

private val RELEASE_GOLDEN: Array<String> = arrayOf(
    "frame=042 rssi= -54 level=2 entry=4680 track=NONE        dangerStreak=2 warnStreak=22 fastStreak=1 bcast=2",
    "frame=043 rssi= -55 level=2 entry=4680 track=NONE        dangerStreak=3 warnStreak=23 fastStreak=1 bcast=2",
    "frame=044 rssi= -56 level=2 entry=4680 track=NONE        dangerStreak=4 warnStreak=24 fastStreak=1 bcast=2",
    "frame=045 rssi= -57 level=2 entry=4680 track=NONE        dangerStreak=0 warnStreak=25 fastStreak=1 bcast=2",
    "frame=046 rssi= -58 level=2 entry=4680 track=NONE        dangerStreak=0 warnStreak=26 fastStreak=1 bcast=2",
    "frame=047 rssi= -59 level=2 entry=4680 track=NONE        dangerStreak=0 warnStreak=27 fastStreak=1 bcast=2",
    "frame=048 rssi= -60 level=2 entry=4680 track=NONE        dangerStreak=0 warnStreak=28 fastStreak=1 bcast=2",
    "frame=049 rssi= -61 level=2 entry=4680 track=NONE        dangerStreak=0 warnStreak=29 fastStreak=1 bcast=2",
    "frame=050 rssi= -62 level=2 entry=4680 track=NONE        dangerStreak=0 warnStreak=30 fastStreak=1 bcast=2",
    "frame=051 rssi= -63 level=2 entry=4680 track=NONE        dangerStreak=0 warnStreak=31 fastStreak=1 bcast=2",
    "frame=052 rssi= -64 level=2 entry=4680 track=NONE        dangerStreak=0 warnStreak=32 fastStreak=1 bcast=2",
    "frame=053 rssi= -65 level=2 entry=4680 track=NONE        dangerStreak=0 warnStreak=33 fastStreak=1 bcast=2",
    "frame=054 rssi= -66 level=2 entry=4680 track=NONE        dangerStreak=0 warnStreak=34 fastStreak=1 bcast=2",
    "frame=055 rssi= -67 level=2 entry=4680 track=NONE        dangerStreak=0 warnStreak=35 fastStreak=1 bcast=2",
    "frame=056 rssi= -68 level=2 entry=6720 track=NONE        dangerStreak=0 warnStreak=36 fastStreak=2 bcast=3",
    "frame=057 rssi= -69 level=2 entry=6720 track=NONE        dangerStreak=0 warnStreak=37 fastStreak=2 bcast=3",
    "frame=058 rssi= -70 level=2 entry=6720 track=NONE        dangerStreak=0 warnStreak=38 fastStreak=2 bcast=3",
    "frame=059 rssi= -71 level=2 entry=6720 track=NONE        dangerStreak=0 warnStreak=39 fastStreak=2 bcast=3",
    "frame=060 rssi= -72 level=2 entry=6720 track=NONE        dangerStreak=0 warnStreak=40 fastStreak=2 bcast=3",
    "frame=061 rssi= -73 level=2 entry=6720 track=NONE        dangerStreak=0 warnStreak=41 fastStreak=2 bcast=3",
    "frame=062 rssi= -74 level=2 entry=6720 track=NONE        dangerStreak=0 warnStreak=42 fastStreak=2 bcast=3",
    "frame=063 rssi= -75 level=2 entry=6720 track=NONE        dangerStreak=0 warnStreak=43 fastStreak=2 bcast=3",
    "frame=064 rssi= -76 level=2 entry=6720 track=NONE        dangerStreak=0 warnStreak=44 fastStreak=2 bcast=3",
    "frame=065 rssi= -77 level=2 entry=6720 track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=2 bcast=3",
    "frame=066 rssi= -78 level=2 entry=6720 track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=2 bcast=3",
    "frame=067 rssi= -79 level=2 entry=6720 track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=2 bcast=3",
    "frame=068 rssi= -80 level=2 entry=6720 track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=2 bcast=3",
    "frame=069 rssi= -81 level=2 entry=6720 track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=2 bcast=3",
    "frame=070 rssi= -82 level=2 entry=6720 track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=2 bcast=3",
    "frame=071 rssi= -83 level=2 entry=6720 track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=2 bcast=3",
    "frame=072 rssi= -84 level=2 entry=6720 track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=2 bcast=3",
    "frame=073 rssi= -85 level=2 entry=6720 track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=2 bcast=3",
    "frame=074 rssi= -86 level=2 entry=6720 track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=2 bcast=3",
    "frame=075 rssi= -87 level=2 entry=6720 track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=2 bcast=3",
    "frame=076 rssi= -88 level=2 entry=6720 track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=2 bcast=3",
    "frame=077 rssi= -89 level=2 entry=6720 track=CROSSING    dangerStreak=0 warnStreak=0 fastStreak=2 bcast=3",
    "frame=078 rssi= -90 level=null entry=null track=DEPARTING   dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=079 rssi= -91 level=null entry=null track=DEPARTING   dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=080 rssi= -92 level=null entry=null track=DEPARTING   dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=081 rssi= -93 level=null entry=null track=DEPARTING   dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=082 rssi= -94 level=null entry=null track=DEPARTING   dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=083 rssi= -95 level=null entry=null track=DEPARTING   dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=084 rssi= -96 level=null entry=null track=DEPARTING   dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=085 rssi= -97 level=null entry=null track=DEPARTING   dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=086 rssi= -98 level=null entry=null track=DEPARTING   dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=087 rssi= -99 level=null entry=null track=DEPARTING   dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=088 rssi=-100 level=null entry=null track=DEPARTING   dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
    "frame=089 rssi=-101 level=null entry=null track=DEPARTING   dangerStreak=0 warnStreak=0 fastStreak=0 bcast=4",
)

private val RELEASE_KFVEL: DoubleArray = doubleArrayOf(
    8.058751514661529,
    8.090671269134937,
    8.046375607362467,
    7.939644629242456,
    7.711480393372777,
    7.3834060513187,
    6.973511578129396,
    6.4971214034248685,
    5.967319696583539,
    5.39536316956089,
    4.7910049199743945,
    4.162747955024065,
    3.5180432524903456,
    2.8634442569728837,
    2.2047273741338973,
    1.5469861611305957,
    1.2938577177654136,
    1.03514840932566,
    0.7449012001289531,
    0.45894235128688954,
    0.14904297716491777,
    -0.1467526086054729,
    -0.458841722681477,
    -0.7826618360375853,
    -1.1138467924993884,
    -1.41439588315446,
    -1.7177344027638062,
    -2.02082368232901,
    -2.3210849460549805,
    -2.6163974893672948,
    -2.905074294295255,
    -3.1542269119441224,
    -3.397781124244453,
    -3.6348845824768494,
    -3.8649257132129473,
    -4.08749516286249,
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
)

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

/**
 * 02-02 Task 3(checkpoint Option A) — 급접촉 서브 시나리오 입력. 5프레임 -95dBm(median 필터
 * 완전 충전 + pEma 정지수렴) 후 5프레임 -30dBm 계단 점프(processAlert_strongDangerContact...
 * 스모크 테스트와 동일 강도 — BleService.kt:1878-1891 오버라이드가 확실히 사거리 안에 들도록).
 * CASCADE_DEVICE_ID 와 별도 기기 ID/서비스 인스턴스로 격리(escalation/release 상태 오염 없음).
 */
private const val CONTACT_DEVICE_ID = "AA:BB:CC:DD:EE:FC"
private const val CONTACT_WARMUP_FRAMES = 5
private const val CONTACT_STRONG_FRAMES = 5
private const val CONTACT_FRAMES = CONTACT_WARMUP_FRAMES + CONTACT_STRONG_FRAMES
private val CONTACT_RSSI = IntArray(CONTACT_FRAMES) { if (it < CONTACT_WARMUP_FRAMES) -95 else -30 }

// 아래 두 배열은 1회 실제 구동 캡처값(2026-08-28, commit=f31edf0 베이스) — 손 계산 금지,
// 재동결은 수동 파일 편집만 허용(T-02-05). frame=005 dangerStreak=1(raw 진입 1프레임째, 오버라이드
// 미발화 — stableLevel 그대로 null) → frame=006 dangerStreak=2 '동일 프레임'에서 level=null→2
// (DANGER) 로 직행(WARNING 경유 없음) — :1885 raw 즉시격상이 pEma 를 기다리지 않고 stableLevel 을
// 강제한 증거(65dB 낙차 직후 pEma 는 아직 -95 인근이라 calcLevelWithHysteresis 단독으론 DANGER 불가).
private val CONTACT_GOLDEN: Array<String> = arrayOf(
    "frame=000 rssi= -95 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=001 rssi= -95 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=002 rssi= -95 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=003 rssi= -95 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=004 rssi= -95 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0",
    "frame=005 rssi= -30 level=null entry=null track=NONE        dangerStreak=1 warnStreak=1 fastStreak=0 bcast=0",
    "frame=006 rssi= -30 level=2 entry=720 track=NONE        dangerStreak=2 warnStreak=2 fastStreak=0 bcast=1",
    "frame=007 rssi= -30 level=2 entry=720 track=NONE        dangerStreak=3 warnStreak=3 fastStreak=0 bcast=1",
    "frame=008 rssi= -30 level=2 entry=720 track=NONE        dangerStreak=4 warnStreak=4 fastStreak=0 bcast=1",
    "frame=009 rssi= -30 level=2 entry=720 track=NONE        dangerStreak=5 warnStreak=5 fastStreak=0 bcast=1",
)

private val CONTACT_KFVEL: DoubleArray = doubleArrayOf(
    0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
)
