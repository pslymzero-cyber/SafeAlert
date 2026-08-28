package com.wf11.safealert.ble

import com.wf11.safealert.service.BleService
import com.wf11.safealert.support.BleServiceTestHarness
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

/**
 * BUG-02 저속 접근 지연 격상 회귀 골든 (02-golden Plan 04, D-3A/D-3B/D-3D).
 *
 * 배경: 지게차가 느린 속도로 다가오는 시퀀스에서 경보 등급이 늦게 뜬다는 현장 보고(BUG-02,
 * "느리니까 안 울린다" → 실제로는 코앞까지 붙고 나서야 뜬다).
 *
 * [D-3B 근본 원인 실측 결론 — 3회 반복 실측 후 확정] PROJECT.md 의 기존 가설("접근
 * Time-Gate가 저속 접근을 막는다", processAlert:2395-2465 부근 kfApproaching/timeGateMs)은
 * 부분 불일치로 판정한다. 실측 결과:
 *   1) 매끈한 단조 램프(잡음 없음, 0.25dBm/s): kfApproaching 이 끝까지 한 번도 true 가
 *      안 됐음에도(Time-Gate 정성적으로 확인) frame 42 에서 정상 격상 — Time-Gate 는
 *      raw RSSI 임계값 교차 streak 기반 우회로(fastContact, warnStreak>=2)로 매 프레임
 *      무력화된다. 단조 램프에서는 버그가 재현되지 않는다.
 *   2) 잡음 얹은 램프(4프레임 주기 ±2dBm, 0.25dBm/s 추세, 이 파일의 실제 시나리오):
 *      잡음이 threshold 부근 streak 를 반복적으로 끊어(frame 80-84 관측: warnStreak 가
 *      1↔0 을 4회 왕복) Time-Gate 우회로 자체를 지연시키는 데는 성공하지만, 완전히
 *      막지는 못한다 — 단조 추세가 잡음 진폭을 결국 앞질러 frame 85 에서 2연속
 *      임계값 통과가 우연히 정렬되며 fastContact 로 격상된다. 이후 isFirstDetection 이
 *      영구히 false 가 되어 Time-Gate 자체가 완전히 우회되고, 등급은 임계값 통과만으로
 *      계속 오른다.
 *   3) 따라서 실측된 결함은 "영원히 안 뜬다"가 아니라 "streak 확인 요구(2연속 프레임)가
 *      threshold 근접 잡음에 취약해 격상이 심각하게 지연된다"이다 — 근본 원인은 WARNING
 *      streak 하드리셋(단발 미달 즉시 0) 자체.
 *
 * [D-3A 완료 — 수정 전/후 골든 diff] BleService.kt 에 WARNING streak 전용 변화율(dBm/s)
 * 게이트를 추가(WARNING_DEPART_RATE_DBM_PER_SEC=3.0) — 미달 프레임이라도 직전 프레임 대비
 * 하강률이 이 임계 미만(완만한 잡음)이면 streak 를 보존하고, 임계 이상(진짜 이탈/급하강)이면
 * 원래대로 즉시 0 리셋한다. 이 시나리오(1000ms 프레임 간격)의 실측 최대 잡음 하강률은
 * -1.0dBm/s 로 임계 미만이라 streak 가 보존되고, AlertCascadeGoldenTest 의
 * release_goldenTimeline(120ms 간격, 실측 하강률 ≈ -8.3dBm/s)은 임계를 훨씬 초과해
 * 원래 동작(즉시 리셋)이 그대로 유지된다(D-3D 골든 무변화로 확인).
 *
 * 효과: 최초 격상(level 1/WARNING) 도달 프레임이 85 → 82 로 3프레임(3초) 단축됐다(아래
 * LOWSPEED_GOLDEN 참고, frame=000~080 은 수정 전과 동일 — 분기는 frame=081 부터 시작).
 * DANGER streak(dangerContactStreakMap)는 수정하지 않았다 — effDanger ⊂ effWarning
 * 상위호환 구조상 WARNING 만 완화해도 저속 접근의 최초 확증(경고 등급) 목표는 달성되고,
 * DANGER 쪽 즉시 억제(이탈 시 빠른 해제) 의미는 그대로 보존된다.
 *
 * record-then-freeze, 손 계산 금지 — 아래 골든 배열은 수정 후 1회 실제 구동 값 그대로
 * 캡처(T-02-05 계승).
 */
@RunWith(RobolectricTestRunner::class)
class LowSpeedApproachRegressionTest {

    @Test
    fun lowSpeedApproach_delayedEscalation_afterFix() {
        val service = BleServiceTestHarness.newService()
        BleServiceTestHarness.resetBetweenTests(service)
        val actual = runLowSpeedScenario(service, LOWSPEED_DEVICE_ID, LOWSPEED_RSSI)

        assertEquals("저속접근 프레임 수 불일치", FRAMES, actual.first.size)
        assertLowSpeedScenario(actual, LOWSPEED_GOLDEN, LOWSPEED_KFVEL)
    }
}

// ── 저속 접근 프레임별 골든 배선 (02-04 Task 1, D-3A/D-2E 계승) ──────────────────────
// [D-3C 파라미터 탐색 — 총 3회차, 최종 채택안] 1회차(FRAME_DT_MS=3000, 클램프 왜곡)·2회차
// (매끈한 단조 램프)는 재현 실패 — 위 클래스 KDoc D-3B 결론 참고. 3회차(현재 채택):
// baseline(4프레임당 +1dBm, 0.25dBm/s 추세)에 4프레임 주기 잡음패턴 [0,-1,+2,-1]
// (평균 0, 진폭 ±2dBm, 다중경로 페이딩 모사)을 얹어 threshold 부근 streak 를 반복
// 차단 — 완전 차단은 아니지만(수학적으로 단조 추세가 유한 잡음 진폭을 결국 앞지름)
// 실측 지연(frame 85 최초 격상)을 안정 재현한다.
private const val T0_MS = 1_000_000L
private const val FRAME_DT_MS = 1000L
private const val LOWSPEED_DEVICE_ID = "AA:BB:CC:DD:EE:CB"

private const val START_DBM = -95
private val NOISE_PATTERN = intArrayOf(0, -1, 2, -1)
private const val FRAMES = 264
private val LOWSPEED_RSSI = IntArray(FRAMES) { i ->
    START_DBM + i / NOISE_PATTERN.size + NOISE_PATTERN[i % NOISE_PATTERN.size]
}

/** BleService.kt private enum TrackingState + trackingStateMap — 리플렉션 전용(toString만 사용). */
@Suppress("UNCHECKED_CAST")
private fun trackingStateOf(service: BleService, deviceId: String): String {
    val map = ReflectionHelpers.getField(service, "trackingStateMap") as Map<String, *>
    return map[deviceId]?.toString() ?: "NONE"
}

/** dangerContactStreakMap/warningContactStreakMap/fastApproachStreakMap 공용 판독. */
@Suppress("UNCHECKED_CAST")
private fun streakOf(service: BleService, fieldName: String, deviceId: String): Int {
    val map = ReflectionHelpers.getField(service, fieldName) as Map<String, Int>
    return map[deviceId] ?: 0
}

/** private val kalmanFilters — KalmanFilter.estimatedVel(public)은 리플렉션 없이 직접 접근. */
@Suppress("UNCHECKED_CAST")
private fun kfVelOf(service: BleService, deviceId: String): Double {
    val map = ReflectionHelpers.getField(service, "kalmanFilters") as Map<String, KalmanFilter>
    return map[deviceId]?.estimatedVel ?: 0.0
}

/** BleService.kt:655 pendingDisplayMap — Time-Gate 에 걸려 최초 등록이 보류 중인지(D-3A 관측 컬럼). */
@Suppress("UNCHECKED_CAST")
private fun pendingOf(service: BleService, deviceId: String): Boolean {
    val map = ReflectionHelpers.getField(service, "pendingDisplayMap") as Map<String, Long>
    return map.containsKey(deviceId)
}

/** BleService.kt:657 approachStreakStartMap — 이번 프레임에 kfApproaching==true 였는지(D-3A 관측 컬럼). */
@Suppress("UNCHECKED_CAST")
private fun kfApproachingOf(service: BleService, deviceId: String): Boolean {
    val map = ReflectionHelpers.getField(service, "approachStreakStartMap") as Map<String, Long>
    return map.containsKey(deviceId)
}

/** 프레임 1개를 고정폭 한 줄로 직렬화 — AlertCascadeGoldenTest.renderFrame(D-2F) 확장, pending/kfAppr 2컬럼 추가. */
private fun renderFrame(service: BleService, deviceId: String, frameIdx: Int, rssi: Int): String {
    val level = BleServiceTestHarness.alertLevelOf(service, deviceId)
    val entryRel = BleServiceTestHarness.alertEntryMsOf(service, deviceId)?.minus(T0_MS)
    val track = trackingStateOf(service, deviceId)
    val dangerStreak = streakOf(service, "dangerContactStreakMap", deviceId)
    val warnStreak = streakOf(service, "warningContactStreakMap", deviceId)
    val fastStreak = streakOf(service, "fastApproachStreakMap", deviceId)
    val bcast = BleServiceTestHarness.alertBroadcasts().size
    val pending = if (pendingOf(service, deviceId)) "Y" else "N"
    val kfAppr = if (kfApproachingOf(service, deviceId)) "Y" else "N"
    return ("frame=%03d rssi=%4d level=%s entry=%s track=%-11s dangerStreak=%d warnStreak=%d " +
        "fastStreak=%d bcast=%d pending=%s kfAppr=%s")
        .format(
            frameIdx, rssi, level?.toString() ?: "null", entryRel?.toString() ?: "null", track,
            dangerStreak, warnStreak, fastStreak, bcast, pending, kfAppr
        )
}

/** AlertCascadeGoldenTest.runScenario(D-2E) 개명·이식 — FRAME_DT_MS 간격으로 nowMs 를 전진시킨다. */
private fun runLowSpeedScenario(
    service: BleService,
    deviceId: String,
    rssiSeq: IntArray,
): Pair<Array<String>, DoubleArray> {
    val frames = Array(rssiSeq.size) { "" }
    val kfVel = DoubleArray(rssiSeq.size)
    for (i in rssiSeq.indices) {
        val nowMs = T0_MS + i * FRAME_DT_MS
        BleServiceTestHarness.callProcessAlert(service, deviceId, rssiSeq[i], nowMs = nowMs)
        frames[i] = renderFrame(service, deviceId, i, rssiSeq[i])
        kfVel[i] = kfVelOf(service, deviceId)
    }
    return frames to kfVel
}

private fun assertLowSpeedScenario(
    actual: Pair<Array<String>, DoubleArray>,
    expectedFrames: Array<String>,
    expectedKfVel: DoubleArray,
) {
    val (frames, kfVel) = actual
    for (i in expectedFrames.indices) {
        assertEquals("lowSpeedApproach frame=$i stage=render", expectedFrames[i], frames[i])
        assertEquals("lowSpeedApproach frame=$i stage=kfVel", expectedKfVel[i], kfVel[i], 1e-9)
    }
}

// 아래 두 배열은 1회 실제 구동 캡처값(D-3A 3단계, 수정 후 — BleService.kt WARNING_DEPART_RATE_DBM_PER_SEC
// 변화율 게이트 적용 후 재캡처) — 손 계산 금지, 재동결은 수동 파일 편집만 허용(T-02-05 계승).
// [실측 요약] 최초 격상(level 1/WARNING) = frame 82(rssi=-73) — 수정 전 frame 85 대비 3프레임(3초)
// 단축. frame=000~080 은 수정 전과 완전 동일(공유 경로 무변화 확인, D-3D 취지의 로컬 대조).
// frame=081 부터 분기 시작 — 수정 전에는 이 프레임에서 warnStreak 가 0 으로 즉시 리셋됐으나(잡음
// 미달 프레임), 수정 후에는 직전 프레임 대비 하강률이 WARNING_DEPART_RATE_DBM_PER_SEC(3.0dBm/s)
// 미만이라 streak=1 로 보존되어 다음 2연속 통과(frame=82)에서 곧바로 격상된다. 이후 DANGER(level 2)
// 도달 프레임 및 그 이후 warnStreak 누적값도 이 3프레임 조기 격상의 연쇄 효과로 함께 이동한다
// (정확 값은 아래 골든 배열 자체가 기록 — record-then-freeze, 손으로 계산·역산하지 않음).
private val LOWSPEED_GOLDEN: Array<String> = arrayOf(
    "frame=000 rssi= -95 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=001 rssi= -96 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=002 rssi= -93 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=003 rssi= -96 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=004 rssi= -94 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=005 rssi= -95 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=006 rssi= -92 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=007 rssi= -95 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=008 rssi= -93 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=009 rssi= -94 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=010 rssi= -91 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=011 rssi= -94 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=012 rssi= -92 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=013 rssi= -93 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=014 rssi= -90 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=015 rssi= -93 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=016 rssi= -91 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=017 rssi= -92 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=018 rssi= -89 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=019 rssi= -92 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=020 rssi= -90 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=021 rssi= -91 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=022 rssi= -88 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=023 rssi= -91 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=024 rssi= -89 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=025 rssi= -90 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=026 rssi= -87 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=027 rssi= -90 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=028 rssi= -88 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=029 rssi= -89 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=030 rssi= -86 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=031 rssi= -89 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=032 rssi= -87 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=033 rssi= -88 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=034 rssi= -85 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=035 rssi= -88 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=036 rssi= -86 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=037 rssi= -87 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=038 rssi= -84 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=039 rssi= -87 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=040 rssi= -85 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=041 rssi= -86 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=042 rssi= -83 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=043 rssi= -86 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=044 rssi= -84 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=045 rssi= -85 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=046 rssi= -82 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=047 rssi= -85 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=048 rssi= -83 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=049 rssi= -84 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=050 rssi= -81 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=051 rssi= -84 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=052 rssi= -82 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=053 rssi= -83 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=054 rssi= -80 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=055 rssi= -83 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=056 rssi= -81 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=057 rssi= -82 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=058 rssi= -79 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=059 rssi= -82 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=060 rssi= -80 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=061 rssi= -81 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=062 rssi= -78 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=063 rssi= -81 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=064 rssi= -79 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=065 rssi= -80 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=066 rssi= -77 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=067 rssi= -80 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=068 rssi= -78 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=069 rssi= -79 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=070 rssi= -76 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=071 rssi= -79 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=072 rssi= -77 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=073 rssi= -78 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=074 rssi= -75 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=075 rssi= -78 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=076 rssi= -76 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=077 rssi= -77 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=078 rssi= -74 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=079 rssi= -77 level=null entry=null track=NONE        dangerStreak=0 warnStreak=0 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=080 rssi= -75 level=null entry=null track=NONE        dangerStreak=0 warnStreak=1 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=081 rssi= -76 level=null entry=null track=NONE        dangerStreak=0 warnStreak=1 fastStreak=0 bcast=0 pending=Y kfAppr=N",
    "frame=082 rssi= -73 level=1 entry=82000 track=NONE        dangerStreak=0 warnStreak=2 fastStreak=0 bcast=1 pending=N kfAppr=N",
    "frame=083 rssi= -76 level=1 entry=82000 track=NONE        dangerStreak=0 warnStreak=2 fastStreak=0 bcast=1 pending=N kfAppr=N",
    "frame=084 rssi= -74 level=1 entry=82000 track=NONE        dangerStreak=0 warnStreak=3 fastStreak=0 bcast=1 pending=N kfAppr=N",
    "frame=085 rssi= -75 level=1 entry=85000 track=NONE        dangerStreak=0 warnStreak=4 fastStreak=0 bcast=2 pending=N kfAppr=N",
    "frame=086 rssi= -72 level=1 entry=85000 track=NONE        dangerStreak=0 warnStreak=5 fastStreak=0 bcast=2 pending=N kfAppr=N",
    "frame=087 rssi= -75 level=1 entry=85000 track=NONE        dangerStreak=0 warnStreak=6 fastStreak=0 bcast=2 pending=N kfAppr=N",
    "frame=088 rssi= -73 level=1 entry=88000 track=NONE        dangerStreak=0 warnStreak=7 fastStreak=0 bcast=3 pending=N kfAppr=N",
    "frame=089 rssi= -74 level=1 entry=88000 track=NONE        dangerStreak=0 warnStreak=8 fastStreak=0 bcast=3 pending=N kfAppr=N",
    "frame=090 rssi= -71 level=1 entry=88000 track=NONE        dangerStreak=0 warnStreak=9 fastStreak=0 bcast=3 pending=N kfAppr=N",
    "frame=091 rssi= -74 level=1 entry=91000 track=NONE        dangerStreak=0 warnStreak=10 fastStreak=0 bcast=4 pending=N kfAppr=N",
    "frame=092 rssi= -72 level=1 entry=91000 track=NONE        dangerStreak=0 warnStreak=11 fastStreak=0 bcast=4 pending=N kfAppr=N",
    "frame=093 rssi= -73 level=1 entry=91000 track=NONE        dangerStreak=0 warnStreak=12 fastStreak=0 bcast=4 pending=N kfAppr=N",
    "frame=094 rssi= -70 level=1 entry=94000 track=NONE        dangerStreak=0 warnStreak=13 fastStreak=0 bcast=5 pending=N kfAppr=N",
    "frame=095 rssi= -73 level=1 entry=94000 track=NONE        dangerStreak=0 warnStreak=14 fastStreak=0 bcast=5 pending=N kfAppr=N",
    "frame=096 rssi= -71 level=1 entry=94000 track=NONE        dangerStreak=0 warnStreak=15 fastStreak=0 bcast=5 pending=N kfAppr=N",
    "frame=097 rssi= -72 level=1 entry=97000 track=NONE        dangerStreak=0 warnStreak=16 fastStreak=0 bcast=6 pending=N kfAppr=N",
    "frame=098 rssi= -69 level=1 entry=97000 track=NONE        dangerStreak=0 warnStreak=17 fastStreak=0 bcast=6 pending=N kfAppr=N",
    "frame=099 rssi= -72 level=1 entry=97000 track=NONE        dangerStreak=0 warnStreak=18 fastStreak=0 bcast=6 pending=N kfAppr=N",
    "frame=100 rssi= -70 level=1 entry=100000 track=NONE        dangerStreak=0 warnStreak=19 fastStreak=0 bcast=7 pending=N kfAppr=N",
    "frame=101 rssi= -71 level=1 entry=100000 track=NONE        dangerStreak=0 warnStreak=20 fastStreak=0 bcast=7 pending=N kfAppr=N",
    "frame=102 rssi= -68 level=1 entry=100000 track=NONE        dangerStreak=0 warnStreak=21 fastStreak=0 bcast=7 pending=N kfAppr=N",
    "frame=103 rssi= -71 level=1 entry=103000 track=NONE        dangerStreak=0 warnStreak=22 fastStreak=0 bcast=8 pending=N kfAppr=N",
    "frame=104 rssi= -69 level=1 entry=103000 track=NONE        dangerStreak=0 warnStreak=23 fastStreak=0 bcast=8 pending=N kfAppr=N",
    "frame=105 rssi= -70 level=1 entry=103000 track=NONE        dangerStreak=0 warnStreak=24 fastStreak=0 bcast=8 pending=N kfAppr=N",
    "frame=106 rssi= -67 level=1 entry=106000 track=NONE        dangerStreak=0 warnStreak=25 fastStreak=0 bcast=9 pending=N kfAppr=N",
    "frame=107 rssi= -70 level=1 entry=106000 track=NONE        dangerStreak=0 warnStreak=26 fastStreak=0 bcast=9 pending=N kfAppr=N",
    "frame=108 rssi= -68 level=1 entry=106000 track=NONE        dangerStreak=0 warnStreak=27 fastStreak=0 bcast=9 pending=N kfAppr=N",
    "frame=109 rssi= -69 level=1 entry=109000 track=NONE        dangerStreak=0 warnStreak=28 fastStreak=0 bcast=10 pending=N kfAppr=N",
    "frame=110 rssi= -66 level=1 entry=109000 track=NONE        dangerStreak=0 warnStreak=29 fastStreak=0 bcast=10 pending=N kfAppr=N",
    "frame=111 rssi= -69 level=1 entry=109000 track=NONE        dangerStreak=0 warnStreak=30 fastStreak=0 bcast=10 pending=N kfAppr=N",
    "frame=112 rssi= -67 level=1 entry=112000 track=NONE        dangerStreak=0 warnStreak=31 fastStreak=0 bcast=11 pending=N kfAppr=N",
    "frame=113 rssi= -68 level=1 entry=112000 track=NONE        dangerStreak=0 warnStreak=32 fastStreak=0 bcast=11 pending=N kfAppr=N",
    "frame=114 rssi= -65 level=1 entry=112000 track=NONE        dangerStreak=0 warnStreak=33 fastStreak=0 bcast=11 pending=N kfAppr=N",
    "frame=115 rssi= -68 level=1 entry=115000 track=NONE        dangerStreak=0 warnStreak=34 fastStreak=0 bcast=12 pending=N kfAppr=N",
    "frame=116 rssi= -66 level=1 entry=115000 track=NONE        dangerStreak=0 warnStreak=35 fastStreak=0 bcast=12 pending=N kfAppr=N",
    "frame=117 rssi= -67 level=1 entry=115000 track=NONE        dangerStreak=0 warnStreak=36 fastStreak=0 bcast=12 pending=N kfAppr=N",
    "frame=118 rssi= -64 level=1 entry=118000 track=NONE        dangerStreak=0 warnStreak=37 fastStreak=0 bcast=13 pending=N kfAppr=N",
    "frame=119 rssi= -67 level=1 entry=118000 track=NONE        dangerStreak=0 warnStreak=38 fastStreak=0 bcast=13 pending=N kfAppr=N",
    "frame=120 rssi= -65 level=1 entry=118000 track=NONE        dangerStreak=0 warnStreak=39 fastStreak=0 bcast=13 pending=N kfAppr=N",
    "frame=121 rssi= -66 level=1 entry=121000 track=NONE        dangerStreak=0 warnStreak=40 fastStreak=0 bcast=14 pending=N kfAppr=N",
    "frame=122 rssi= -63 level=1 entry=121000 track=NONE        dangerStreak=0 warnStreak=41 fastStreak=0 bcast=14 pending=N kfAppr=N",
    "frame=123 rssi= -66 level=1 entry=121000 track=NONE        dangerStreak=0 warnStreak=42 fastStreak=0 bcast=14 pending=N kfAppr=N",
    "frame=124 rssi= -64 level=1 entry=124000 track=NONE        dangerStreak=0 warnStreak=43 fastStreak=0 bcast=15 pending=N kfAppr=N",
    "frame=125 rssi= -65 level=1 entry=124000 track=NONE        dangerStreak=0 warnStreak=44 fastStreak=0 bcast=15 pending=N kfAppr=N",
    "frame=126 rssi= -62 level=1 entry=124000 track=NONE        dangerStreak=0 warnStreak=45 fastStreak=0 bcast=15 pending=N kfAppr=N",
    "frame=127 rssi= -65 level=1 entry=127000 track=NONE        dangerStreak=0 warnStreak=46 fastStreak=0 bcast=16 pending=N kfAppr=N",
    "frame=128 rssi= -63 level=1 entry=127000 track=NONE        dangerStreak=0 warnStreak=47 fastStreak=0 bcast=16 pending=N kfAppr=N",
    "frame=129 rssi= -64 level=1 entry=127000 track=NONE        dangerStreak=0 warnStreak=48 fastStreak=0 bcast=16 pending=N kfAppr=N",
    "frame=130 rssi= -61 level=1 entry=130000 track=NONE        dangerStreak=0 warnStreak=49 fastStreak=0 bcast=17 pending=N kfAppr=N",
    "frame=131 rssi= -64 level=1 entry=130000 track=NONE        dangerStreak=0 warnStreak=50 fastStreak=0 bcast=17 pending=N kfAppr=N",
    "frame=132 rssi= -62 level=1 entry=130000 track=NONE        dangerStreak=0 warnStreak=51 fastStreak=0 bcast=17 pending=N kfAppr=N",
    "frame=133 rssi= -63 level=1 entry=133000 track=NONE        dangerStreak=0 warnStreak=52 fastStreak=0 bcast=18 pending=N kfAppr=N",
    "frame=134 rssi= -60 level=1 entry=133000 track=NONE        dangerStreak=0 warnStreak=53 fastStreak=0 bcast=18 pending=N kfAppr=N",
    "frame=135 rssi= -63 level=1 entry=133000 track=NONE        dangerStreak=0 warnStreak=54 fastStreak=0 bcast=18 pending=N kfAppr=N",
    "frame=136 rssi= -61 level=1 entry=136000 track=NONE        dangerStreak=0 warnStreak=55 fastStreak=0 bcast=19 pending=N kfAppr=N",
    "frame=137 rssi= -62 level=1 entry=136000 track=NONE        dangerStreak=0 warnStreak=56 fastStreak=0 bcast=19 pending=N kfAppr=N",
    "frame=138 rssi= -59 level=1 entry=136000 track=NONE        dangerStreak=0 warnStreak=57 fastStreak=0 bcast=19 pending=N kfAppr=N",
    "frame=139 rssi= -62 level=1 entry=139000 track=NONE        dangerStreak=0 warnStreak=58 fastStreak=0 bcast=20 pending=N kfAppr=N",
    "frame=140 rssi= -60 level=1 entry=139000 track=NONE        dangerStreak=0 warnStreak=59 fastStreak=0 bcast=20 pending=N kfAppr=N",
    "frame=141 rssi= -61 level=1 entry=139000 track=NONE        dangerStreak=0 warnStreak=60 fastStreak=0 bcast=20 pending=N kfAppr=N",
    "frame=142 rssi= -58 level=1 entry=142000 track=NONE        dangerStreak=0 warnStreak=61 fastStreak=0 bcast=21 pending=N kfAppr=N",
    "frame=143 rssi= -61 level=1 entry=142000 track=NONE        dangerStreak=0 warnStreak=62 fastStreak=0 bcast=21 pending=N kfAppr=N",
    "frame=144 rssi= -59 level=1 entry=142000 track=NONE        dangerStreak=0 warnStreak=63 fastStreak=0 bcast=21 pending=N kfAppr=N",
    "frame=145 rssi= -60 level=1 entry=145000 track=NONE        dangerStreak=0 warnStreak=64 fastStreak=0 bcast=22 pending=N kfAppr=N",
    "frame=146 rssi= -57 level=1 entry=145000 track=NONE        dangerStreak=0 warnStreak=65 fastStreak=0 bcast=22 pending=N kfAppr=N",
    "frame=147 rssi= -60 level=1 entry=145000 track=NONE        dangerStreak=0 warnStreak=66 fastStreak=0 bcast=22 pending=N kfAppr=N",
    "frame=148 rssi= -58 level=1 entry=148000 track=NONE        dangerStreak=0 warnStreak=67 fastStreak=0 bcast=23 pending=N kfAppr=N",
    "frame=149 rssi= -59 level=1 entry=148000 track=NONE        dangerStreak=0 warnStreak=68 fastStreak=0 bcast=23 pending=N kfAppr=N",
    "frame=150 rssi= -56 level=1 entry=148000 track=NONE        dangerStreak=0 warnStreak=69 fastStreak=0 bcast=23 pending=N kfAppr=N",
    "frame=151 rssi= -59 level=1 entry=151000 track=NONE        dangerStreak=0 warnStreak=70 fastStreak=0 bcast=24 pending=N kfAppr=N",
    "frame=152 rssi= -57 level=1 entry=151000 track=NONE        dangerStreak=0 warnStreak=71 fastStreak=0 bcast=24 pending=N kfAppr=N",
    "frame=153 rssi= -58 level=1 entry=151000 track=NONE        dangerStreak=0 warnStreak=72 fastStreak=0 bcast=24 pending=N kfAppr=N",
    "frame=154 rssi= -55 level=1 entry=154000 track=NONE        dangerStreak=0 warnStreak=73 fastStreak=0 bcast=25 pending=N kfAppr=N",
    "frame=155 rssi= -58 level=1 entry=154000 track=NONE        dangerStreak=0 warnStreak=74 fastStreak=0 bcast=25 pending=N kfAppr=N",
    "frame=156 rssi= -56 level=1 entry=154000 track=NONE        dangerStreak=0 warnStreak=75 fastStreak=0 bcast=25 pending=N kfAppr=N",
    "frame=157 rssi= -57 level=1 entry=157000 track=NONE        dangerStreak=0 warnStreak=76 fastStreak=0 bcast=26 pending=N kfAppr=N",
    "frame=158 rssi= -54 level=1 entry=157000 track=NONE        dangerStreak=0 warnStreak=77 fastStreak=0 bcast=26 pending=N kfAppr=N",
    "frame=159 rssi= -57 level=1 entry=157000 track=NONE        dangerStreak=0 warnStreak=78 fastStreak=0 bcast=26 pending=N kfAppr=N",
    "frame=160 rssi= -55 level=1 entry=160000 track=NONE        dangerStreak=1 warnStreak=79 fastStreak=0 bcast=27 pending=N kfAppr=N",
    "frame=161 rssi= -56 level=1 entry=160000 track=NONE        dangerStreak=0 warnStreak=80 fastStreak=0 bcast=27 pending=N kfAppr=N",
    "frame=162 rssi= -53 level=1 entry=160000 track=NONE        dangerStreak=1 warnStreak=81 fastStreak=0 bcast=27 pending=N kfAppr=N",
    "frame=163 rssi= -56 level=2 entry=163000 track=NONE        dangerStreak=0 warnStreak=82 fastStreak=0 bcast=28 pending=N kfAppr=N",
    "frame=164 rssi= -54 level=2 entry=163000 track=NONE        dangerStreak=1 warnStreak=83 fastStreak=0 bcast=28 pending=N kfAppr=N",
    "frame=165 rssi= -55 level=2 entry=165000 track=NONE        dangerStreak=2 warnStreak=84 fastStreak=0 bcast=29 pending=N kfAppr=N",
    "frame=166 rssi= -52 level=2 entry=165000 track=NONE        dangerStreak=3 warnStreak=85 fastStreak=0 bcast=29 pending=N kfAppr=N",
    "frame=167 rssi= -55 level=2 entry=167000 track=NONE        dangerStreak=4 warnStreak=86 fastStreak=0 bcast=30 pending=N kfAppr=N",
    "frame=168 rssi= -53 level=2 entry=167000 track=NONE        dangerStreak=5 warnStreak=87 fastStreak=0 bcast=30 pending=N kfAppr=N",
    "frame=169 rssi= -54 level=2 entry=169000 track=NONE        dangerStreak=6 warnStreak=88 fastStreak=0 bcast=31 pending=N kfAppr=N",
    "frame=170 rssi= -51 level=2 entry=169000 track=NONE        dangerStreak=7 warnStreak=89 fastStreak=0 bcast=31 pending=N kfAppr=N",
    "frame=171 rssi= -54 level=2 entry=171000 track=NONE        dangerStreak=8 warnStreak=90 fastStreak=0 bcast=32 pending=N kfAppr=N",
    "frame=172 rssi= -52 level=2 entry=171000 track=NONE        dangerStreak=9 warnStreak=91 fastStreak=0 bcast=32 pending=N kfAppr=N",
    "frame=173 rssi= -53 level=2 entry=173000 track=NONE        dangerStreak=10 warnStreak=92 fastStreak=0 bcast=33 pending=N kfAppr=N",
    "frame=174 rssi= -50 level=2 entry=173000 track=NONE        dangerStreak=11 warnStreak=93 fastStreak=0 bcast=33 pending=N kfAppr=N",
    "frame=175 rssi= -53 level=2 entry=175000 track=NONE        dangerStreak=12 warnStreak=94 fastStreak=0 bcast=34 pending=N kfAppr=N",
    "frame=176 rssi= -51 level=2 entry=175000 track=NONE        dangerStreak=13 warnStreak=95 fastStreak=0 bcast=34 pending=N kfAppr=N",
    "frame=177 rssi= -52 level=2 entry=177000 track=NONE        dangerStreak=14 warnStreak=96 fastStreak=0 bcast=35 pending=N kfAppr=N",
    "frame=178 rssi= -49 level=2 entry=177000 track=NONE        dangerStreak=15 warnStreak=97 fastStreak=0 bcast=35 pending=N kfAppr=N",
    "frame=179 rssi= -52 level=2 entry=179000 track=NONE        dangerStreak=16 warnStreak=98 fastStreak=0 bcast=36 pending=N kfAppr=N",
    "frame=180 rssi= -50 level=2 entry=179000 track=NONE        dangerStreak=17 warnStreak=99 fastStreak=0 bcast=36 pending=N kfAppr=N",
    "frame=181 rssi= -51 level=2 entry=181000 track=NONE        dangerStreak=18 warnStreak=100 fastStreak=0 bcast=37 pending=N kfAppr=N",
    "frame=182 rssi= -48 level=2 entry=181000 track=NONE        dangerStreak=19 warnStreak=101 fastStreak=0 bcast=37 pending=N kfAppr=N",
    "frame=183 rssi= -51 level=2 entry=183000 track=NONE        dangerStreak=20 warnStreak=102 fastStreak=0 bcast=38 pending=N kfAppr=N",
    "frame=184 rssi= -49 level=2 entry=183000 track=NONE        dangerStreak=21 warnStreak=103 fastStreak=0 bcast=38 pending=N kfAppr=N",
    "frame=185 rssi= -50 level=2 entry=185000 track=NONE        dangerStreak=22 warnStreak=104 fastStreak=0 bcast=39 pending=N kfAppr=N",
    "frame=186 rssi= -47 level=2 entry=185000 track=NONE        dangerStreak=23 warnStreak=105 fastStreak=0 bcast=39 pending=N kfAppr=N",
    "frame=187 rssi= -50 level=2 entry=187000 track=NONE        dangerStreak=24 warnStreak=106 fastStreak=0 bcast=40 pending=N kfAppr=N",
    "frame=188 rssi= -48 level=2 entry=187000 track=NONE        dangerStreak=25 warnStreak=107 fastStreak=0 bcast=40 pending=N kfAppr=N",
    "frame=189 rssi= -49 level=2 entry=189000 track=NONE        dangerStreak=26 warnStreak=108 fastStreak=0 bcast=41 pending=N kfAppr=N",
    "frame=190 rssi= -46 level=2 entry=189000 track=NONE        dangerStreak=27 warnStreak=109 fastStreak=0 bcast=41 pending=N kfAppr=N",
    "frame=191 rssi= -49 level=2 entry=191000 track=NONE        dangerStreak=28 warnStreak=110 fastStreak=0 bcast=42 pending=N kfAppr=N",
    "frame=192 rssi= -47 level=2 entry=191000 track=NONE        dangerStreak=29 warnStreak=111 fastStreak=0 bcast=42 pending=N kfAppr=N",
    "frame=193 rssi= -48 level=2 entry=193000 track=NONE        dangerStreak=30 warnStreak=112 fastStreak=0 bcast=43 pending=N kfAppr=N",
    "frame=194 rssi= -45 level=2 entry=193000 track=NONE        dangerStreak=31 warnStreak=113 fastStreak=0 bcast=43 pending=N kfAppr=N",
    "frame=195 rssi= -48 level=2 entry=195000 track=NONE        dangerStreak=32 warnStreak=114 fastStreak=0 bcast=44 pending=N kfAppr=N",
    "frame=196 rssi= -46 level=2 entry=195000 track=NONE        dangerStreak=33 warnStreak=115 fastStreak=0 bcast=44 pending=N kfAppr=N",
    "frame=197 rssi= -47 level=2 entry=197000 track=NONE        dangerStreak=34 warnStreak=116 fastStreak=0 bcast=45 pending=N kfAppr=N",
    "frame=198 rssi= -44 level=2 entry=197000 track=NONE        dangerStreak=35 warnStreak=117 fastStreak=0 bcast=45 pending=N kfAppr=N",
    "frame=199 rssi= -47 level=2 entry=199000 track=NONE        dangerStreak=36 warnStreak=118 fastStreak=0 bcast=46 pending=N kfAppr=N",
    "frame=200 rssi= -45 level=2 entry=199000 track=NONE        dangerStreak=37 warnStreak=119 fastStreak=0 bcast=46 pending=N kfAppr=N",
    "frame=201 rssi= -46 level=2 entry=201000 track=NONE        dangerStreak=38 warnStreak=120 fastStreak=0 bcast=47 pending=N kfAppr=N",
    "frame=202 rssi= -43 level=2 entry=201000 track=NONE        dangerStreak=39 warnStreak=121 fastStreak=0 bcast=47 pending=N kfAppr=N",
    "frame=203 rssi= -46 level=2 entry=203000 track=NONE        dangerStreak=40 warnStreak=122 fastStreak=0 bcast=48 pending=N kfAppr=N",
    "frame=204 rssi= -44 level=2 entry=203000 track=NONE        dangerStreak=41 warnStreak=123 fastStreak=0 bcast=48 pending=N kfAppr=N",
    "frame=205 rssi= -45 level=2 entry=205000 track=NONE        dangerStreak=42 warnStreak=124 fastStreak=0 bcast=49 pending=N kfAppr=N",
    "frame=206 rssi= -42 level=2 entry=205000 track=NONE        dangerStreak=43 warnStreak=125 fastStreak=0 bcast=49 pending=N kfAppr=N",
    "frame=207 rssi= -45 level=2 entry=207000 track=NONE        dangerStreak=44 warnStreak=126 fastStreak=0 bcast=50 pending=N kfAppr=N",
    "frame=208 rssi= -43 level=2 entry=207000 track=NONE        dangerStreak=45 warnStreak=127 fastStreak=0 bcast=50 pending=N kfAppr=N",
    "frame=209 rssi= -44 level=2 entry=209000 track=NONE        dangerStreak=46 warnStreak=128 fastStreak=0 bcast=51 pending=N kfAppr=N",
    "frame=210 rssi= -41 level=2 entry=209000 track=NONE        dangerStreak=47 warnStreak=129 fastStreak=0 bcast=51 pending=N kfAppr=N",
    "frame=211 rssi= -44 level=2 entry=211000 track=NONE        dangerStreak=48 warnStreak=130 fastStreak=0 bcast=52 pending=N kfAppr=N",
    "frame=212 rssi= -42 level=2 entry=211000 track=NONE        dangerStreak=49 warnStreak=131 fastStreak=0 bcast=52 pending=N kfAppr=N",
    "frame=213 rssi= -43 level=2 entry=213000 track=NONE        dangerStreak=50 warnStreak=132 fastStreak=0 bcast=53 pending=N kfAppr=N",
    "frame=214 rssi= -40 level=2 entry=213000 track=NONE        dangerStreak=51 warnStreak=133 fastStreak=0 bcast=53 pending=N kfAppr=N",
    "frame=215 rssi= -43 level=2 entry=215000 track=NONE        dangerStreak=52 warnStreak=134 fastStreak=0 bcast=54 pending=N kfAppr=N",
    "frame=216 rssi= -41 level=2 entry=215000 track=NONE        dangerStreak=53 warnStreak=135 fastStreak=0 bcast=54 pending=N kfAppr=N",
    "frame=217 rssi= -42 level=2 entry=217000 track=NONE        dangerStreak=54 warnStreak=136 fastStreak=0 bcast=55 pending=N kfAppr=N",
    "frame=218 rssi= -39 level=2 entry=217000 track=NONE        dangerStreak=55 warnStreak=137 fastStreak=0 bcast=55 pending=N kfAppr=N",
    "frame=219 rssi= -42 level=2 entry=219000 track=NONE        dangerStreak=56 warnStreak=138 fastStreak=0 bcast=56 pending=N kfAppr=N",
    "frame=220 rssi= -40 level=2 entry=219000 track=NONE        dangerStreak=57 warnStreak=139 fastStreak=0 bcast=56 pending=N kfAppr=N",
    "frame=221 rssi= -41 level=2 entry=221000 track=NONE        dangerStreak=58 warnStreak=140 fastStreak=0 bcast=57 pending=N kfAppr=N",
    "frame=222 rssi= -38 level=2 entry=221000 track=NONE        dangerStreak=59 warnStreak=141 fastStreak=0 bcast=57 pending=N kfAppr=N",
    "frame=223 rssi= -41 level=2 entry=223000 track=NONE        dangerStreak=60 warnStreak=142 fastStreak=0 bcast=58 pending=N kfAppr=N",
    "frame=224 rssi= -39 level=2 entry=223000 track=NONE        dangerStreak=61 warnStreak=143 fastStreak=0 bcast=58 pending=N kfAppr=N",
    "frame=225 rssi= -40 level=2 entry=225000 track=NONE        dangerStreak=62 warnStreak=144 fastStreak=0 bcast=59 pending=N kfAppr=N",
    "frame=226 rssi= -37 level=2 entry=225000 track=NONE        dangerStreak=63 warnStreak=145 fastStreak=0 bcast=59 pending=N kfAppr=N",
    "frame=227 rssi= -40 level=2 entry=227000 track=NONE        dangerStreak=64 warnStreak=146 fastStreak=0 bcast=60 pending=N kfAppr=N",
    "frame=228 rssi= -38 level=2 entry=227000 track=NONE        dangerStreak=65 warnStreak=147 fastStreak=0 bcast=60 pending=N kfAppr=N",
    "frame=229 rssi= -39 level=2 entry=229000 track=NONE        dangerStreak=66 warnStreak=148 fastStreak=0 bcast=61 pending=N kfAppr=N",
    "frame=230 rssi= -36 level=2 entry=229000 track=NONE        dangerStreak=67 warnStreak=149 fastStreak=0 bcast=61 pending=N kfAppr=N",
    "frame=231 rssi= -39 level=2 entry=231000 track=NONE        dangerStreak=68 warnStreak=150 fastStreak=0 bcast=62 pending=N kfAppr=N",
    "frame=232 rssi= -37 level=2 entry=231000 track=NONE        dangerStreak=69 warnStreak=151 fastStreak=0 bcast=62 pending=N kfAppr=N",
    "frame=233 rssi= -38 level=2 entry=233000 track=NONE        dangerStreak=70 warnStreak=152 fastStreak=0 bcast=63 pending=N kfAppr=N",
    "frame=234 rssi= -35 level=2 entry=233000 track=NONE        dangerStreak=71 warnStreak=153 fastStreak=0 bcast=63 pending=N kfAppr=N",
    "frame=235 rssi= -38 level=2 entry=235000 track=NONE        dangerStreak=72 warnStreak=154 fastStreak=0 bcast=64 pending=N kfAppr=N",
    "frame=236 rssi= -36 level=2 entry=235000 track=NONE        dangerStreak=73 warnStreak=155 fastStreak=0 bcast=64 pending=N kfAppr=N",
    "frame=237 rssi= -37 level=2 entry=237000 track=NONE        dangerStreak=74 warnStreak=156 fastStreak=0 bcast=65 pending=N kfAppr=N",
    "frame=238 rssi= -34 level=2 entry=237000 track=NONE        dangerStreak=75 warnStreak=157 fastStreak=0 bcast=65 pending=N kfAppr=N",
    "frame=239 rssi= -37 level=2 entry=239000 track=NONE        dangerStreak=76 warnStreak=158 fastStreak=0 bcast=66 pending=N kfAppr=N",
    "frame=240 rssi= -35 level=2 entry=239000 track=NONE        dangerStreak=77 warnStreak=159 fastStreak=0 bcast=66 pending=N kfAppr=N",
    "frame=241 rssi= -36 level=2 entry=241000 track=NONE        dangerStreak=78 warnStreak=160 fastStreak=0 bcast=67 pending=N kfAppr=N",
    "frame=242 rssi= -33 level=2 entry=241000 track=NONE        dangerStreak=79 warnStreak=161 fastStreak=0 bcast=67 pending=N kfAppr=N",
    "frame=243 rssi= -36 level=2 entry=243000 track=NONE        dangerStreak=80 warnStreak=162 fastStreak=0 bcast=68 pending=N kfAppr=N",
    "frame=244 rssi= -34 level=2 entry=243000 track=NONE        dangerStreak=81 warnStreak=163 fastStreak=0 bcast=68 pending=N kfAppr=N",
    "frame=245 rssi= -35 level=2 entry=245000 track=NONE        dangerStreak=82 warnStreak=164 fastStreak=0 bcast=69 pending=N kfAppr=N",
    "frame=246 rssi= -32 level=2 entry=245000 track=NONE        dangerStreak=83 warnStreak=165 fastStreak=0 bcast=69 pending=N kfAppr=N",
    "frame=247 rssi= -35 level=2 entry=247000 track=NONE        dangerStreak=84 warnStreak=166 fastStreak=0 bcast=70 pending=N kfAppr=N",
    "frame=248 rssi= -33 level=2 entry=247000 track=NONE        dangerStreak=85 warnStreak=167 fastStreak=0 bcast=70 pending=N kfAppr=N",
    "frame=249 rssi= -34 level=2 entry=249000 track=NONE        dangerStreak=86 warnStreak=168 fastStreak=0 bcast=71 pending=N kfAppr=N",
    "frame=250 rssi= -31 level=2 entry=249000 track=NONE        dangerStreak=87 warnStreak=169 fastStreak=0 bcast=71 pending=N kfAppr=N",
    "frame=251 rssi= -34 level=2 entry=251000 track=NONE        dangerStreak=88 warnStreak=170 fastStreak=0 bcast=72 pending=N kfAppr=N",
    "frame=252 rssi= -32 level=2 entry=251000 track=NONE        dangerStreak=89 warnStreak=171 fastStreak=0 bcast=72 pending=N kfAppr=N",
    "frame=253 rssi= -33 level=2 entry=253000 track=NONE        dangerStreak=90 warnStreak=172 fastStreak=0 bcast=73 pending=N kfAppr=N",
    "frame=254 rssi= -30 level=2 entry=253000 track=NONE        dangerStreak=91 warnStreak=173 fastStreak=0 bcast=73 pending=N kfAppr=N",
    "frame=255 rssi= -33 level=2 entry=255000 track=NONE        dangerStreak=92 warnStreak=174 fastStreak=0 bcast=74 pending=N kfAppr=N",
    "frame=256 rssi= -31 level=2 entry=255000 track=NONE        dangerStreak=93 warnStreak=175 fastStreak=0 bcast=74 pending=N kfAppr=N",
    "frame=257 rssi= -32 level=2 entry=257000 track=NONE        dangerStreak=94 warnStreak=176 fastStreak=0 bcast=75 pending=N kfAppr=N",
    "frame=258 rssi= -29 level=2 entry=257000 track=NONE        dangerStreak=95 warnStreak=177 fastStreak=0 bcast=75 pending=N kfAppr=N",
    "frame=259 rssi= -32 level=2 entry=259000 track=NONE        dangerStreak=96 warnStreak=178 fastStreak=0 bcast=76 pending=N kfAppr=N",
    "frame=260 rssi= -30 level=2 entry=259000 track=NONE        dangerStreak=97 warnStreak=179 fastStreak=0 bcast=76 pending=N kfAppr=N",
    "frame=261 rssi= -31 level=2 entry=261000 track=NONE        dangerStreak=98 warnStreak=180 fastStreak=0 bcast=77 pending=N kfAppr=N",
    "frame=262 rssi= -28 level=2 entry=261000 track=NONE        dangerStreak=99 warnStreak=181 fastStreak=0 bcast=77 pending=N kfAppr=N",
    "frame=263 rssi= -31 level=2 entry=263000 track=NONE        dangerStreak=100 warnStreak=182 fastStreak=0 bcast=78 pending=N kfAppr=N",
)

private val LOWSPEED_KFVEL: DoubleArray = doubleArrayOf(
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
    0.0,
    0.0,
    0.0,
    0.0,
    -0.3437049358706983,
    0.0,
    -0.33949912425210405,
    -0.21652381885136102,
    -0.4446587893377848,
    -0.3853490395006556,
    -0.2900525409255296,
    -0.2069775173634989,
    -0.1401507868187531,
    -0.08784771160920543,
    -0.04852360588800191,
    -0.0208118520726699,
    0.14021681565012933,
    0.20648533423585344,
    0.21240007880461134,
    0.18510077423803484,
    0.2873311195038776,
    0.30092892319720776,
    0.2650856307739082,
    0.2078762695538865,
    0.29072029302749414,
    0.2934830578196671,
    0.2529049233713484,
    0.19489907792454772,
    0.2791728581897216,
    0.2843583204281599,
    0.2463870287280041,
    0.19071334429989556,
    0.2768374906140271,
    0.2833500611249449,
    0.24623502478776382,
    0.19104231772237096,
    0.27737720350191614,
    0.2839254650406609,
    0.2467471072570308,
    0.191447052676785,
    0.27766636659992605,
    0.2841112081771396,
    0.24685076890925062,
    0.19149183955692606,
    0.27767316108979423,
    0.2840966544364645,
    0.24682685224552314,
    0.19146632926651386,
    0.2776504520985154,
    0.28407870222015813,
    0.24681402378159534,
    0.19145808706118692,
    0.2776458506339699,
    0.2840767126691284,
    0.24681372008190428,
    0.19145873097805444,
    0.2776469104594648,
    0.2840778436529661,
    0.24681472714256963,
    0.19145952725647603,
    0.27764747958238595,
    0.2840782093939218,
    0.24681493139807267,
    0.19145961563778127,
    0.27764749315623466,
    0.2840781809045367,
    0.24681488443379898,
    0.19145956549631588,
    0.2776474484967963,
    0.2840781455852725,
    0.2468148591851984,
    0.19145954926687753,
    0.27764743943005726,
    0.2840781416591398,
    0.24681485857854812,
    0.19145955052734764,
    0.2776474415111931,
    0.28407814388213254,
    0.24681486055903348,
    0.19145955209395443,
    0.2776474426313277,
    0.2840781446023005,
    0.2468148609614949,
    0.1914595522683561,
    0.27764744265843067,
    0.28407814454652636,
    0.2468148608692698,
    0.19145955216980123,
    0.27764744257060664,
    0.28407814447704394,
    0.24681486081958104,
    0.19145955213784713,
    0.2776474425527437,
    0.28407814446929675,
    0.24681486081836826,
    0.19145955214031243,
    0.27764744255682605,
    0.28407814447366214,
    0.24681486082226117,
    0.19145955214339377,
    0.2776474425590317,
    0.2840781444750827,
    0.24681486082305645,
    0.19145955214374,
    0.2776474425590867,
    0.28407814447497376,
    0.24681486082287524,
    0.19145955214354654,
    0.2776474425589146,
    0.2840781444748373,
    0.24681486082277748,
    0.1914595521434824,
    0.27764744255887797,
    0.28407814447482,
    0.24681486082277343,
    0.19145955214348648,
    0.27764744255888607,
    0.28407814447483015,
    0.24681486082278256,
    0.19145955214349358,
    0.2776474425588912,
    0.2840781444748322,
    0.2468148608227826,
    0.19145955214349258,
    0.2776474425588892,
    0.2840781444748312,
    0.2468148608227826,
    0.1914595521434936,
    0.2776474425588912,
    0.2840781444748322,
    0.2468148608227826,
    0.19145955214349258,
    0.2776474425588892,
    0.2840781444748312,
    0.2468148608227826,
    0.1914595521434936,
    0.2776474425588912,
    0.2840781444748322,
    0.2468148608227826,
    0.19145955214349258,
    0.2776474425588892,
    0.2840781444748312,
    0.2468148608227826,
    0.1914595521434936,
    0.2776474425588912,
    0.2840781444748322,
    0.2468148608227826,
    0.19145955214349258,
    0.2776474425588892,
    0.2840781444748312,
    0.2468148608227826,
    0.1914595521434936,
    0.2776474425588912,
    0.2840781444748322,
    0.2468148608227826,
    0.19145955214349258,
    0.2776474425588892,
    0.2840781444748312,
    0.2468148608227826,
    0.1914595521434936,
    0.2776474425588912,
    0.2840781444748322,
    0.2468148608227826,
    0.19145955214349258,
    0.2776474425588892,
    0.2840781444748312,
    0.2468148608227826,
    0.1914595521434936,
    0.2776474425588912,
    0.2840781444748322,
    0.2468148608227826,
    0.19145955214349258,
    0.2776474425588892,
    0.2840781444748312,
    0.2468148608227826,
    0.1914595521434936,
    0.2776474425588912,
    0.2840781444748322,
    0.2468148608227826,
    0.19145955214349258,
    0.2776474425588892,
    0.2840781444748312,
    0.2468148608227826,
    0.1914595521434936,
    0.2776474425588912,
    0.2840781444748322,
    0.2468148608227826,
    0.19145955214349258,
    0.2776474425588892,
    0.2840781444748312,
    0.2468148608227826,
    0.1914595521434936,
    0.2776474425588912,
    0.2840781444748322,
    0.2468148608227826,
    0.19145955214349258,
    0.2776474425588892,
    0.2840781444748312,
    0.2468148608227826,
    0.1914595521434936,
    0.2776474425588912,
    0.2840781444748322,
    0.2468148608227826,
    0.19145955214349258,
    0.2776474425588892,
    0.2840781444748312,
    0.2468148608227826,
    0.1914595521434936,
    0.2776474425588912,
    0.2840781444748322,
    0.2468148608227826,
    0.19145955214349258,
    0.2776474425588892,
    0.2840781444748312,
    0.2468148608227826,
    0.1914595521434936,
    0.2776474425588912,
    0.2840781444748322,
    0.2468148608227826,
    0.19145955214349258,
    0.2776474425588892,
    0.2840781444748312,
    0.2468148608227831,
    0.1914595521434936,
    0.2776474425588907,
    0.2840781444748322,
    0.2468148608227831,
    0.19145955214349308,
)
