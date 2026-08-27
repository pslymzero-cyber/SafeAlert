package com.wf11.safealert.ble

import com.wf11.safealert.service.BleService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
}
