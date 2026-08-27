package com.wf11.safealert.ble

import com.wf11.safealert.service.BleService
import com.wf11.safealert.support.BleServiceTestHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

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
        val controller = Robolectric.buildService(BleService::class.java)
        val service = controller.get()
        assertNotNull(service)
        // onCreate() 가 실행됐다면 companion isRunning=true 로 바뀐다(BleService.kt:850).
        // get() 만으로는 attach 만 되고 onCreate() 는 미실행이어야 A1 이 성립한다.
        assertFalse("Robolectric.buildService(...).get() 이 onCreate() 를 실행했다 — Assumption A1 위반", BleService.isRunning)
    }

    @Test
    fun processAlert_strongDangerContact_setsAlertStateAndBroadcasts() {
        val service = BleServiceTestHarness.newService()
        val deviceId = "AA:BB:CC:DD:EE:99"
        val dangerRssi = -30   // rssiDanger 기본 -55 보다 훨씬 강함 → 매 프레임 즉시 DANGER 레벨
        var clockMs = 1_000L

        // 1콜: median/streak 워밍업 미충족 → shouldAlert=false → 아직 미등록(D-2B 의 "1프레임"은
        // 아래 2콜째 관측 프레임을 가리킨다 — 클래스 헤더 해석 노트 참조).
        BleServiceTestHarness.callProcessAlert(service, deviceId, dangerRssi, clockMs)
        assertNull(
            "워밍업 1콜만으로 alertState 가 등록됐다 — shouldAlert 게이트가 조기 통과함",
            BleServiceTestHarness.alertLevelOf(service, deviceId)
        )
        assertEquals(0, BleServiceTestHarness.alertBroadcastCount(deviceId))

        // 2콜: dangerStreak=2 → fastContact 로 shouldAlert 통과 → alertState 등록 + BROADCAST_ALERT.
        clockMs += 120L
        BleServiceTestHarness.callProcessAlert(service, deviceId, dangerRssi, clockMs)
        assertEquals(
            BleConstants.LEVEL_DANGER,
            BleServiceTestHarness.alertLevelOf(service, deviceId)
        )
        assertEquals(1, BleServiceTestHarness.alertBroadcastCount(deviceId))
    }
}
