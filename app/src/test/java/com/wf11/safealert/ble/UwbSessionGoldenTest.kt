package com.wf11.safealert.ble

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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * 02-03 Task 1 — UwbRanger 주입 + Case A(UWB↔UWB 배타 판정) 조기 분기 종단 골든(tracer).
 *
 * 대상: BleService.uwbJudgeModeExclusive/freshUwbDistM/judgeUwbOnly(BleService.kt:2545-2727),
 * UwbRanger 생성자(06_utils/UwbRanger.kt:50-58). processAlert(BleService.kt:1406-2543) 는
 * private 이므로 BleServiceTestHarness 를 통해서만 구동한다(02-01/02-02 와 동일 규율).
 *
 * ── 두 시계 규율 ──────────────────────────────────────────────────────────
 * processAlert 의 nowMs 는 시임(BleServiceTestHarness.callProcessAlert 의 명시 인자)이지만,
 * freshUwbDistM(BleService.kt:2566-2570)은 System.currentTimeMillis() 를 직접 읽는다(시임 없음).
 * 두 시계의 '차이'만 문제되므로: T0_MS 는 테스트 시작 시각(임의 상수), 신선 표본은
 * T0_MS+FRESH_OFFSET_MS(미래 오프셋 — 느린 CI 런 대비 마진), 스테일 표본은 T0_MS-STALE_OFFSET_MS
 * (과거 오프셋)를 쓴다. 밀리초 경계(윈도우-1/윈도우/윈도우+1) 검사는 judgeMode()/callJudgeUwbOnly()
 * 로 두 시계를 모두 우회하고 now 를 직접 넘긴다(Task 2 담당).
 *
 * ── 역할쌍/기기ID 설계 결정 (최소 시임) ────────────────────────────────────
 * myMode(BleService.kt:212, default "")·myCategory(BleService.kt:214, default CAT_WALKER)는
 * onCreate() 가 실행되지 않으므로(Assumption A1) 리플렉션 없이도 이미 원하는 값이다. 테스트
 * 기기ID 는 BleConstants.DEVICE_PREFIX 를 써서 WALKER_PREFIX 게이트(judgeUwbOnly:2598)도 자연히
 * 우회한다. deviceCategoryMap/deviceStateMap 도 세팅하지 않아(둘 다 null) forkliftPair=false
 * (myCategory=CAT_WALKER, rCategory=null) → 일반 역할쌍 반경(5.0/3.0m, 골든 DevSettings)로
 * 라우팅되고, 특수경보 블록(2646행, rCategory!=null && rState!=null 요구)도 자동 스킵된다 —
 * Task 1 <action> 이 요구하는 시임(uwbRanger 주입 + uwbSampleAtMsMap 리플렉션) 이상은 불필요.
 *
 * ── 안전 불변식 ────────────────────────────────────────────────────────────
 * UwbRanger.initSession() 은 이 파일 어디서도 호출하지 않는다(실 UWB 하드웨어/권한 요구 —
 * CI 행 위험). newRanger() 는 생성자만 호출하고 candidates 맵이 항상 비어 있으므로
 * computeDesiredLocked()(UwbRanger.kt:320-321)가 즉시 Desired(Role.NONE)을 반환해
 * scope.launch 경로(scheduleRestartLocked)에 진입하지 않는다 — 코루틴 스코프는 주입되지만
 * 이 파일의 어떤 헬퍼도 실제로 코루틴을 기동시키지 않는다.
 *
 * 기록 시점: versionName=1.1.70 versionCode=126, commit=f9a6417, 2026-08-28.
 * 채택 값: T0_MS=2_000_000L(임의 기준시), FRESH_OFFSET_MS=+500L(느린 CI 마진),
 * FRAME_DT_MS=400L(캐스케이드 프레임 간격과 무관 — kinematics 미사용이라 임의값),
 * DEVICE_ID 접두사=BleConstants.DEVICE_PREFIX(WALKER_PREFIX 아님 — walker 게이트 자연 우회),
 * 역할쌍=일반쌍(지게차 아님, deviceCategoryMap 미설정) → warnM=5.0f/dangM=3.0f
 * (DevSettings.uwbPairWarnMeters/uwbPairDangerMeters, 골든 프로파일 고정값).
 */
@RunWith(RobolectricTestRunner::class)
class UwbSessionGoldenTest {

    companion object {
        private const val T0_MS = 2_000_000L
        private const val FRESH_OFFSET_MS = 500L    // 미래 오프셋 — 느린 CI 런 대비 마진(D-4C)
        private const val FRAME_DT_MS = 400L

        // production BleService.UWB_MEAS_FRESH_MS(BleService.kt:682, private val 1_000L)와
        // 반드시 손수 동기화 — 생산 상수가 바뀌면 이 값도 함께 고칠 것.
        private const val FRESH_WINDOW_MS = 1_000L

        // production UwbRanger.MULTICAST_MAX(06_utils/UwbRanger.kt:77, companion 상수 6)와
        // 반드시 손수 동기화.
        private const val MAX_SESSION_DEVICES = 6

        // 과거 오프셋(Task 2) — 신선 창(FRESH_WINDOW_MS)을 확실히 벗어나는 스테일 표본 시각을 만든다.
        private const val STALE_OFFSET_MS = FRESH_WINDOW_MS + 500L

        private const val DEVICE_ID = BleConstants.DEVICE_PREFIX + "UWBTEST01"
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    /** initSession() 은 절대 호출하지 않는다 — candidates 가 비어 있어 scope.launch 미기동(안전 불변식 상단 참고). */
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

    /** uwbDistances 는 UwbRanger 의 public 프로퍼티라 직접 대입, uwbSampleAtMsMap 은 private 필드라 리플렉션. */
    private fun injectUwbSample(service: BleService, ranger: UwbRanger, id: String, distM: Float, sampleAtMs: Long) {
        ranger.uwbDistances[id] = distM
        uwbSampleAtMsMapOf(service)[id] = sampleAtMs
    }

    private fun dropUwbEntry(service: BleService, ranger: UwbRanger, id: String) {
        ranger.uwbDistances.remove(id)
        uwbSampleAtMsMapOf(service).remove(id)
    }

    private fun judgeMode(service: BleService, deviceId: String, now: Long): Boolean =
        ReflectionHelpers.callInstanceMethod(
            service,
            "uwbJudgeModeExclusive",
            ClassParameter.from(String::class.java, deviceId),
            ClassParameter.from(Long::class.javaPrimitiveType, now)
        )

    /** judgeUwbOnly 는 Unit 반환(BleService.kt:2595) — 호출 후 alertState 를 읽어 레벨을 판독한다(부재=SAFE, 원본 prevLevel 관례와 동일). */
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

    /** 하네스 미설정 2키(BleServiceTestHarness.applyGoldenDevSettings 는 손대지 않음) 를 이 파일에서 명시 고정. */
    private fun newUwbGoldenService(): BleService {
        val service = BleServiceTestHarness.newService()
        DevSettings.uwbExclusiveJudgeEnabled = true
        DevSettings.walkerDetectsWalker = false
        return service
    }

    // ── Task 2 헬퍼 ──────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun uwbSafeStreakMapOf(service: BleService): MutableMap<String, Int> =
        ReflectionHelpers.getField(service, "uwbSafeStreakMap") as MutableMap<String, Int>

    private fun levelName(level: Int?): String = when (level) {
        null -> "NONE"
        BleConstants.LEVEL_SAFE -> "SAFE"
        BleConstants.LEVEL_WARNING -> "WARN"
        BleConstants.LEVEL_DANGER -> "DANGER"
        else -> "?"
    }

    /**
     * 02-02 와 같은 형식 규율의 고정폭 1행 프레임 직렬화 — 값 하나가 틀리면 어느 프레임의 어느 열인지
     * 눈으로 짚인다. frame=프레임 번호, distM=주입 거리, sampleAgeMs=표본 경과시간(now-sampleAt),
     * caseA=Case A/B 판정, level=판정 후 등급, demoteStreak=격하 확증 카운터,
     * dangerStreak/warningStreak=RSSI 경로 접촉 streak.
     */
    private fun renderUwbFrame(
        frame: Int,
        distM: Float,
        sampleAgeMs: Long,
        caseA: Boolean,
        level: Int?,
        demoteStreak: Int,
        dangerStreak: Int,
        warningStreak: Int
    ): String = "F%02d dist=%5.2f age=%5d case=%s level=%-6s demote=%d dS=%d wS=%d".format(
        frame, distM, sampleAgeMs, if (caseA) "A" else "B", levelName(level), demoteStreak, dangerStreak, warningStreak
    )

    // ── Behavior 1: newRanger() 는 예외 없이 생성되고 세션 상태맵이 비어 있다 ──────────────
    @Test
    fun behavior1_rangerConstructsCleanly() {
        val ranger = newRanger()
        assertTrue(ranger.uwbDistances.isEmpty())
        assertTrue(ranger.uwbKinematics.isEmpty())
        assertFalse(ranger.isSupported)
    }

    // ── Behavior 2: uwbRanger == null → Case B(judgeMode false) ─────────────────────────
    @Test
    fun behavior2_nullRanger_fallsBackToCaseB() {
        val service = newUwbGoldenService()
        injectRanger(service, null)
        assertFalse(judgeMode(service, DEVICE_ID, T0_MS))
    }

    // ── Behavior 3+4: ranger 주입 + 신선 표본 → processAlert 가 Case A 조기 분기(BleService.kt:1615-1619) ──
    @Test
    fun behavior3and4_freshSample_triggersCaseAEarlyReturnInProcessAlert() {
        val service = newUwbGoldenService()
        val ranger = newRanger()
        injectRanger(service, ranger)
        val sampleAt = T0_MS + FRESH_OFFSET_MS
        injectUwbSample(service, ranger, DEVICE_ID, 4.0f, sampleAt)

        assertTrue(judgeMode(service, DEVICE_ID, T0_MS))

        // Case A 조기분기 확증: RSSI 경로 streak 카운터가 0 리셋되고(BleService.kt:1616-1617),
        // processAlert 는 RSSI 기반 alertState 기록부(1626행 이후)에 도달하지 않는다 — RSSI 는
        // 절대 개입하지 않는다(judgeUwbOnly 를 별도로 호출하지 않는 한 alertState 도 그대로 비어 있다).
        BleServiceTestHarness.callProcessAlert(service, DEVICE_ID, rssi = -50, nowMs = T0_MS)
        assertEquals(0, dangerContactStreakMapOf(service)[DEVICE_ID] ?: -1)
        assertEquals(0, warningContactStreakMapOf(service)[DEVICE_ID] ?: -1)
        assertNull(BleServiceTestHarness.alertLevelOf(service, DEVICE_ID))
    }

    // ── Behavior 5: judgeUwbOnly 4프레임 — 즉시 승격 후 3표본 확증 격하(BleService.kt:2611-2638) ──
    // 2.0m(≤dangM 3.0) → DANGER 즉시 승격. 6.0m(>warnM+hyst 5.5) ×3 연속: streak 1·2 는 보류
    // (DANGER 유지), streak 3 에서 확증 격하(SAFE). 골든 DevSettings 반경: uwbPairWarnMeters=5.0f,
    // uwbPairDangerMeters=3.0f(BleServiceTestHarness.applyGoldenDevSettings), hyst=UWB_RELEASE_HYST_M=0.5f,
    // demoteStreak=UWB_DEMOTE_STREAK=3(둘 다 BleService.kt private val — 순수 산술이라 손계산 가능,
    // record-then-freeze 불요).
    @Test
    fun behavior5_escalateImmediately_demoteAfterConfirmStreak() {
        val service = newUwbGoldenService()

        val l1 = callJudgeUwbOnly(service, DEVICE_ID, 2.0f, T0_MS)
        assertEquals(BleConstants.LEVEL_DANGER, l1)

        val l2 = callJudgeUwbOnly(service, DEVICE_ID, 6.0f, T0_MS + FRAME_DT_MS)
        assertEquals(BleConstants.LEVEL_DANGER, l2)

        val l3 = callJudgeUwbOnly(service, DEVICE_ID, 6.0f, T0_MS + FRAME_DT_MS * 2)
        assertEquals(BleConstants.LEVEL_DANGER, l3)

        val l4 = callJudgeUwbOnly(service, DEVICE_ID, 6.0f, T0_MS + FRAME_DT_MS * 3)
        assertEquals(BleConstants.LEVEL_SAFE, l4)
    }

    // ── Task 2 / Behavior 1: 신선 창 경계 3점 — 창-1/창/창+1 (BleService.kt:2560, `<=` 포함 비교) ──
    // FRESH_WINDOW_MS 는 프로덕션 UWB_MEAS_FRESH_MS(BleService.kt:682, 1_000L)와 반드시 손수 동기화한다 —
    // 반사로 따라가지 않고 두 값이 같아야 한다는 사실만 주석으로 못박는다(action 지시).
    @Test
    fun behavior6_freshnessBoundary_threePoints() {
        val service = newUwbGoldenService()
        val ranger = newRanger()
        injectRanger(service, ranger)
        val sampleAt = T0_MS
        injectUwbSample(service, ranger, DEVICE_ID, 4.0f, sampleAt)

        assertTrue(judgeMode(service, DEVICE_ID, sampleAt + FRESH_WINDOW_MS - 1))
        assertTrue(judgeMode(service, DEVICE_ID, sampleAt + FRESH_WINDOW_MS))
        assertFalse(judgeMode(service, DEVICE_ID, sampleAt + FRESH_WINDOW_MS + 1))
    }

    // ── Task 2 / Behavior 2: uwbSampleAtMsMap 항목 없음 → uwbDistances 만 있어도 Case B ──────
    @Test
    fun behavior7_missingSampleTimestamp_fallsBackToCaseB() {
        val service = newUwbGoldenService()
        val ranger = newRanger()
        injectRanger(service, ranger)
        ranger.uwbDistances[DEVICE_ID] = 4.0f  // uwbSampleAtMsMap 은 의도적으로 채우지 않는다.

        assertFalse(judgeMode(service, DEVICE_ID, T0_MS))
    }

    // ── Task 2 / Behavior 3: uwbDistances 엔트리 제거 → 표본 시각이 신선해도 즉시 Case B ──────
    // (BleService.kt:2545-2553 설계 사유: 종료 이벤트로 엔트리가 걷힌 페어의 스테일 timestamp 단독
    //  잔존으로 인한 오판을 막는다 — uwbJudgeModeExclusive 는 containsKey 를 시각 비교보다 먼저 본다.)
    @Test
    fun behavior8_missingDistanceEntry_fallsBackToCaseBEvenWithFreshTimestamp() {
        val service = newUwbGoldenService()
        val ranger = newRanger()
        injectRanger(service, ranger)
        injectUwbSample(service, ranger, DEVICE_ID, 4.0f, T0_MS)
        ranger.uwbDistances.remove(DEVICE_ID)  // uwbSampleAtMsMap 의 신선 timestamp 는 그대로 남긴다.

        assertFalse(judgeMode(service, DEVICE_ID, T0_MS))
    }

    // ── Task 2 / Behavior 4: 낡은 표본 → processAlert 가 Case A 조기분기를 타지 않고 RSSI 경로가
    //    실제로 등급을 결정한다(D-4A(a)(b)). Case A 라면 두 streak 는 영원히 0 으로 강제되고
    //    alertLevelOf 는 영원히 null 이다(behavior3and4 대조). 낡은 표본은 그 강제를 우회한다.
    @Test
    fun behavior9_staleSample_rssiPathDecidesLevel() {
        val service = newUwbGoldenService()
        val ranger = newRanger()
        injectRanger(service, ranger)
        val staleSampleAt = T0_MS - STALE_OFFSET_MS
        injectUwbSample(service, ranger, DEVICE_ID, 2.0f, staleSampleAt)

        assertFalse(judgeMode(service, DEVICE_ID, T0_MS))  // Case A 미발동 확인 — 표본이 낡았다.

        // 강한 RSSI(danger 임계 -55 보다 강한 -50) 프레임을 재생 — RSSI 경로가 실제로 등급을 기록한다.
        BleServiceTestHarness.callProcessAlert(service, DEVICE_ID, rssi = -50, nowMs = T0_MS)
        BleServiceTestHarness.callProcessAlert(service, DEVICE_ID, rssi = -50, nowMs = T0_MS + FRAME_DT_MS)
        BleServiceTestHarness.callProcessAlert(service, DEVICE_ID, rssi = -50, nowMs = T0_MS + FRAME_DT_MS * 2)
        BleServiceTestHarness.callProcessAlert(service, DEVICE_ID, rssi = -50, nowMs = T0_MS + FRAME_DT_MS * 3)

        assertTrue(BleServiceTestHarness.alertLevelOf(service, DEVICE_ID) != null)
    }

    // ── Task 2 / Behavior 5 (계획에서 가장 중요한 항목): 위험 반경 안쪽 거리가 uwbDistances 에
    //    남아 있고 표본 시각만 낡은 상태에서, 약한 RSSI 를 여러 프레임 재생해도 위험 등급이 나오지
    //    않는다(D-4A(c) 좀비 DANGER 부재, 위협모델 T-02-13). 프레임 시계열을 기록·동결한다.
    @Test
    fun behavior10_staleNearDangerDistance_neverProducesZombieDanger() {
        val service = newUwbGoldenService()
        val ranger = newRanger()
        injectRanger(service, ranger)
        val staleSampleAt = T0_MS - STALE_OFFSET_MS
        // uwbPairDangerMeters=3.0f(골든) 보다 확실히 안쪽인 1.5m — 낡지 않았다면 즉시 DANGER 감이다.
        injectUwbSample(service, ranger, DEVICE_ID, 1.5f, staleSampleAt)
        assertFalse(judgeMode(service, DEVICE_ID, T0_MS))  // Case A 미발동 확인.

        val timeline = StringBuilder()
        for (frame in 0..5) {
            val now = T0_MS + FRAME_DT_MS * frame
            // rssiWarning=-75(골든) 보다 뚜렷이 약한 -90 — 경고 임계에도 못 미치는 약한 신호.
            BleServiceTestHarness.callProcessAlert(service, DEVICE_ID, rssi = -90, nowMs = now)
            val level = BleServiceTestHarness.alertLevelOf(service, DEVICE_ID)
            assertTrue(level == null || level < BleConstants.LEVEL_DANGER)
            timeline.appendLine(
                renderUwbFrame(
                    frame = frame,
                    distM = 1.5f,
                    sampleAgeMs = now - staleSampleAt,
                    caseA = false,
                    level = level,
                    demoteStreak = uwbSafeStreakMapOf(service)[DEVICE_ID] ?: 0,
                    dangerStreak = dangerContactStreakMapOf(service)[DEVICE_ID] ?: 0,
                    warningStreak = warningContactStreakMapOf(service)[DEVICE_ID] ?: 0
                )
            )
        }

        // record-then-freeze: 최초 실행 결과를 그대로 동결한다. 동결 후 값이 움직이면 고치지 말고 보고한다.
        val expected = """
            F00 dist= 1.50 age= 1500 case=B level=NONE   demote=0 dS=0 wS=0
            F01 dist= 1.50 age= 1900 case=B level=NONE   demote=0 dS=0 wS=0
            F02 dist= 1.50 age= 2300 case=B level=NONE   demote=0 dS=0 wS=0
            F03 dist= 1.50 age= 2700 case=B level=NONE   demote=0 dS=0 wS=0
            F04 dist= 1.50 age= 3100 case=B level=NONE   demote=0 dS=0 wS=0
            F05 dist= 1.50 age= 3500 case=B level=NONE   demote=0 dS=0 wS=0

        """.trimIndent()
        assertEquals(expected.trim(), timeline.toString().trim())
    }

    // ── Task 2 / Behavior 6: Case A 상태에서 반경 밖 거리를 연속 주입 — 확증 임계(UWB_DEMOTE_STREAK=3,
    //    BleService.kt:684) 미만 프레임에서는 이전 등급 유지, 임계 프레임에서 정확히 한 번 하강.
    //    이탈 운동학 우회(uwbKinematics)를 주입하지 않아 확증 경로만 격리한다.
    @Test
    fun behavior11_confirmStreakDemotion_isolatedFromKinematicsBypass() {
        val service = newUwbGoldenService()
        val demoteStreak = 3 // UWB_DEMOTE_STREAK(BleService.kt:684) 손 동기화 — 반사로 읽지 않는다.

        val timeline = StringBuilder()
        val distances = listOf(2.0f, 6.0f, 6.0f, 6.0f)
        for (frame in distances.indices) {
            val now = T0_MS + FRAME_DT_MS * frame
            val level = callJudgeUwbOnly(service, DEVICE_ID, distances[frame], now)
            timeline.appendLine(
                renderUwbFrame(
                    frame = frame,
                    distM = distances[frame],
                    sampleAgeMs = 0,
                    caseA = true,
                    level = level,
                    demoteStreak = uwbSafeStreakMapOf(service)[DEVICE_ID] ?: 0,
                    dangerStreak = dangerContactStreakMapOf(service)[DEVICE_ID] ?: 0,
                    warningStreak = warningContactStreakMapOf(service)[DEVICE_ID] ?: 0
                )
            )
            if (frame < demoteStreak) {
                assertEquals(BleConstants.LEVEL_DANGER, level)
            } else {
                assertEquals(BleConstants.LEVEL_SAFE, level)
            }
        }
    }
}
