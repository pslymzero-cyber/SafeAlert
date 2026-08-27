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
}
