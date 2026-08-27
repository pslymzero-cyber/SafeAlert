# Phase 2: golden - Pattern Map

**Mapped:** 2026-08-27
**Files analyzed:** 6 (신규 4 + 수정 2)
**Analogs found:** 6 / 6

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `app/src/test/java/com/wf11/safealert/ble/AlertCascadeGoldenTest.kt` | test | transform (record-then-freeze golden) | `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` | exact — 동일 패키지·동일 골든 관례, 대상 함수만 다름 |
| `app/src/test/java/com/wf11/safealert/ble/LowSpeedApproachRegressionTest.kt` | test | transform (회귀 golden) | `RssiCascadeTest.kt` | exact — 동일 record-then-freeze 형식, 시나리오 1개 |
| `app/src/test/java/com/wf11/safealert/ble/UwbSessionGoldenTest.kt` | test | event-driven (세션 상태 전환) | `RssiCascadeTest.kt`(형식) + `UwbRanger.kt`(대상 필드) | role-match — 골든 형식은 동일, 대상이 시계열 필터가 아니라 세션 상태이므로 부분 매치 |
| `app/src/test/java/com/wf11/safealert/support/BleServiceTestHarness.kt` | utility (테스트 하네스) | request-response (서비스 인스턴스화 + 리플렉션 접근 래퍼) | 없음(신규 계층) — `RssiCascadeTest.kt`의 `runCascade`/`assertCascade` private 헬퍼가 형태상 가장 가까움 | role-match — Phase 1 은 파일 내부 private 헬퍼였고, 이번엔 별도 파일로 분리·공유하는 것이 CONTEXT.md 지정 구조 |
| `app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt`(수정 없음, 참고용) | model/utility | transform | 이미 `nowMs` 시임 보유(27-29행) — 신규 시임의 원형 | exact — 그대로 복제 |
| `app/src/main/java/com/wf11/safealert/03_service/BleService.kt`(processAlert 시그니처 1건 + 1512행 1줄 수정) | service (LifecycleService) | request-response(스캔 콜백 트리거) + streaming(연속 프레임 판정) | `KalmanFilter.kt`(nowMs 패턴 공급원) | exact — 패턴은 이미 같은 코드베이스에 존재, 이식만 하면 됨 |
| `app/build.gradle`(수정 — Robolectric testImplementation 추가) | config | — | 기존 `dependencies` 블록(79-81행) | exact — 같은 블록에 라인 추가 |

## Pattern Assignments

### `app/src/test/java/com/wf11/safealert/ble/AlertCascadeGoldenTest.kt` (test, transform)

**Analog:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` (246줄, 전체 승계 대상)

**패키지·임포트 패턴** (1-4행):
```kotlin
package com.wf11.safealert.ble

import org.junit.Assert.assertEquals
import org.junit.Test
```
TEST-01/TEST-02 는 Robolectric 이 필요하므로 여기에 다음이 추가된다(RssiCascadeTest.kt 에는 없는 신규 임포트):
```kotlin
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.util.ReflectionHelpers
import org.robolectric.Shadows.shadowOf
```

**record-then-freeze 파일 헤더 주석 관례** (6-17행) — 그대로 복제, 대상 함수명만 교체:
```kotlin
/**
 * ... 골든 회귀 테스트.
 *
 * 이 파일의 기대값은 v1.1.70 현행 구현의 **실제 출력을 채집해 그대로 동결**한 것이다
 * (record-then-freeze, D-09) — 손으로 계산한 값이 아니다. ...
 * 기대값 재동결은 항상 사람이 diff 를 검토한 뒤 **수동으로만** 한다 ...
 */
```

**입력 상수 관례** (companion object, 20-42행) — `intArrayOf`/`doubleArrayOf` 인라인 상수 + 각 배열 위에 그 배열의 의미를 설명하는 1줄 한국어 주석. `FRAME_DT_MS = 120L` 같은 "왜 이 값인가" 근거 주석(24-28행)도 승계 대상.

**runCascade 패턴 → runScenario 로 개명 이식** (130-156행):
```kotlin
private fun runCascade(input: IntArray, warmStart: Boolean, deviceId: String = DEVICE_ID): Triple<IntArray, IntArray, DoubleArray> {
    var fakeNow = 1_000_000L
    val medianFilter = MedianFilter()
    val rssiPreFilter = RssiPreFilter()
    val kf = KalmanFilter(nowMs = { fakeNow })
    ...
    for (i in input.indices) {
        fakeNow += FRAME_DT_MS
        ...
    }
    return Triple(medianOut, prefilterOut, kalmanOut)
}
```
TEST-01 용으로는 `KalmanFilter(nowMs = {...})` 대신 `BleService`(D-2C nowMs 시임 통일 후) 를 이 방식으로 감싸 `service.processAlert(deviceId, rssi, nowMs = { fakeNow })` 형태로 매 프레임 호출한다.

**assertCascade 실패 메시지 규약** (158-173행) — 그대로 승계:
```kotlin
assertEquals("$scenario/$startState frame=$i stage=median", expectedMedian[i], median[i])
```
TEST-01 은 `"$scenario frame=$i stage=level"` / `"$scenario frame=$i stage=broadcast"` 형태로 stage 라벨만 확장.

**개별 @Test 메서드 관례** (175-245행) — 시나리오 × 상태 조합마다 짧은 `@Test fun scenario_state_matchesGolden()` 하나, 본문은 `runCascade` 호출 + `assertCascade` 호출 2줄. TEST-01 도 `escalation_matchesGolden()` / `deescalation_matchesGolden()` 형태로 승계.

---

### `app/src/test/java/com/wf11/safealert/ble/LowSpeedApproachRegressionTest.kt` (test, transform)

**Analog:** `RssiCascadeTest.kt` 와 동일 패턴, 다만 시나리오가 1개뿐이므로 companion object 상수도 1세트만 필요.

**D-3A record-then-freeze 순서** — 1차 커밋: 버그 있는 현재 값으로 EXPECTED_* 동결(주석에 "BUG-02 현상 동결, 수정 전"이라고 명시). 2차 커밋: BUG-02 수정 후 `AlertCascadeGoldenTest`/`UwbSessionGoldenTest` 값이 그대로인지 먼저 확인한 뒤 이 파일만 재동결(D-3D 게이트).

**D-3C 합성 시퀀스 형태** (CONTEXT.md 원문) — 약 -95dBm 근방 시작 + 프레임당 0.1~0.3dBm 상승, FRAME_DT_MS=120L 기준 약 117프레임. `RssiCascadeTest.kt` 의 `INPUT_APPROACH`(20프레임, 명시적 단조 상승 intArrayOf)와 동일한 인라인 배열 스타일로 작성하되 길이만 다르다 — 반복 상승 시퀀스는 루프로 생성한 뒤 상수로 굳히는 방식도 허용(가독성 우선).

---

### `app/src/test/java/com/wf11/safealert/ble/UwbSessionGoldenTest.kt` (test, event-driven)

**Analog(형식):** `RssiCascadeTest.kt` 의 companion 상수 + assertCascade 규약.
**Analog(대상 API):** `app/src/main/java/com/wf11/safealert/06_utils/UwbRanger.kt`

**UwbRanger 생성자·핵심 필드** (58, 73, 77, 123, 137행):
```kotlin
class UwbRanger( /* CoroutineScope 등 파라미터 — plan 단계에서 실제 시그니처 확인 */ )
...
companion object {
    private const val REJOIN_DELAY_MS = 250L   // [v1.1.44]
    private const val MULTICAST_MAX = 6        // 골든 범위 상한(D-4D) 근거
}
val uwbDistances: MutableMap<String, Float> = ConcurrentHashMap()   // 실측 직접 주입 통로(D-4B)
private var uwbManager: UwbManager? = null   // initSession() 호출 전까지 null — 코루틴 미시작 확인점(D-4B)
```

**BleService 쪽 Case A/B 판정 대상 코드** (BleService.kt:2554-2570, exact line 확인됨):
```kotlin
private fun uwbJudgeModeExclusive(deviceId: String, now: Long): Boolean {
    // 킬스위치·내UWB가동 체크 후:
    val sampleAt = uwbSampleAtMsMap[deviceId] ?: return false
    return now - sampleAt <= UWB_MEAS_FRESH_MS   // UWB_MEAS_FRESH_MS = 1_000L (680-682행)
}

private fun freshUwbDistM(deviceId: String): Float? {
    val d = uwbDistances[deviceId] ?: return null
    val at = uwbSampleAtMsMap[deviceId] ?: return null
    return if (System.currentTimeMillis() - at <= UWB_MEAS_FRESH_MS) d else null
}
```
테스트는 `ReflectionHelpers.setField(service, "uwbSampleAtMsMap", ...)` 로 `uwbSampleAtMsMap[id]` 를 과거(2000L 전) 또는 현재로 세팅해 Case A/B 를 결정론적으로 전환한다(D-4C).

**BLE 타임아웃 재연결 경로(D-4D 범위)** (BleService.kt:1171-1173):
```kotlin
uwbRanger?.onDeviceLost(deviceId)    // (v1.1.30) UWB 후보·세션 정리
uwbSampleAtMsMap.remove(deviceId)
```
검증은 이 두 호출·맵 상태까지만 — `UwbRanger` 내부 `scheduleRestartLocked(REJOIN_DELAY_MS)` 코루틴 재시작은 범위 밖(D-4D).

---

### `app/src/test/java/com/wf11/safealert/support/BleServiceTestHarness.kt` (utility, request-response)

**Analog:** 신규 계층 — `RssiCascadeTest.kt` 에는 대응 파일이 없다(Phase 1 은 단일 파일 내 private 헬퍼로 충분했음). 이 파일은 그 헬퍼 패턴을 재사용 가능한 형태로 승격한 것.

**서비스 인스턴스화 패턴(D-2B, 프로덕션 무접촉)**:
```kotlin
val controller = Robolectric.buildService(BleService::class.java, intent)
val service: BleService = controller.get()   // .create() 호출 금지 — onCreate() 부작용 회피
```

**리플렉션 읽기 래퍼(D-2E)** — `RssiCascadeTest.kt` 는 필드가 전부 public/private-in-scope 라 리플렉션이 필요 없었다. 이 신규 헬퍼가 최초의 `ReflectionHelpers` 사용처:
```kotlin
val trackingMap: Map<String, Any> = ReflectionHelpers.getField(service, "trackingStateMap")
```

**브로드캐스트 관찰 래퍼(D-2E)**:
```kotlin
val alertIntents = shadowOf(ApplicationProvider.getApplicationContext<Context>())
    .broadcastIntents
    .filter { it.action == BleService.BROADCAST_ALERT }
```
`BROADCAST_ALERT`/`EXTRA_ALERT_LEVEL` 상수는 BleService.kt:63-70 에 정의됨(코드 확인).

**UwbRanger 주입(D-4B)**:
```kotlin
val uwbRanger = UwbRanger(/* 실제 생성자 인자 — plan 단계에서 UwbRanger.kt:58 시그니처 재확인 */)
ReflectionHelpers.setField(service, "uwbRanger", uwbRanger)
uwbRanger.uwbDistances[deviceId] = 2.0f   // public 맵 — 리플렉션 불필요
```

**t0 상대 오프셋 기록(D-2G)** — `RssiCascadeTest.kt` 의 `fakeNow = 1_000_000L` 시작 관례를 그대로 쓰되, alertState 진입시각 기록 시 `(entryMs - t0)` 로 변환하는 헬퍼 함수를 이 파일에 둔다.

---

### `app/build.gradle` (config)

**Analog:** 기존 `dependencies` 블록(79-81행, 현재 상태 확인됨).

**현재 상태**:
```gradle
testImplementation 'junit:junit:4.13.2'
androidTestImplementation 'androidx.test.ext:junit:1.1.5'
androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
```

**추가할 패턴** — 같은 스코프 스타일로 1줄:
```gradle
testImplementation 'org.robolectric:robolectric:4.15.1'
```
(RESEARCH.md Pitfall 4 — `androidx.test.ext:junit` 은 `androidTestImplementation` 스코프뿐이라 `src/test/` 에서 `AndroidJUnit4` 러너가 필요하면 `testImplementation` 에 별도로 추가해야 한다. `@RunWith(RobolectricTestRunner::class)` 만 쓴다면 불필요 — plan 단계에서 러너 선택 확정.)

## Shared Patterns

### nowMs 기본 인자 시임 (D-2C/D-2H)
**Source:** `app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt:27-29`
```kotlin
class KalmanFilter(
    ...
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    ...
    val now = nowMs()   // 81행
```
**Apply to:** `BleService.kt` 의 `processAlert` 시그니처(1406행) + 1422행 `val now`, 1512행 `lastApproachAtMs` 기록. 기본값이 `System.currentTimeMillis()` 이므로 호출부(BleService.kt:1115) 무변경, 런타임 동작 변화 0. `RssiCascadeTest.kt:134` 의 `KalmanFilter(nowMs = { fakeNow })` 가 테스트 측 사용 예시.

### record-then-freeze 골든 관례 (Phase 1 D-09, D-12)
**Source:** `RssiCascadeTest.kt:6-17` 헤더 주석 + 파일 전체 구조(companion 상수 → runCascade → assertCascade → @Test 목록).
**Apply to:** `AlertCascadeGoldenTest.kt`, `LowSpeedApproachRegressionTest.kt`, `UwbSessionGoldenTest.kt` 전부 — 자동 재동결 경로(`-PupdateGolden` 등)를 만들지 않는다는 규칙도 동일 적용.

### 실패 메시지 규약 (D-19, RssiCascadeTest.kt:158)
**Source:** `"$scenario/$startState frame=$i stage=<median|prefilter|kalman>"`
**Apply to:** 신규 3개 테스트 파일의 assertion 메시지 — stage 라벨만 도메인에 맞게 교체(`level`/`broadcast`/`caseAB`/`streak` 등).

### 로깅/주석 컨벤션 (CLAUDE.md 프로젝트 관례)
**Source:** 코드베이스 전역 — `companion object { const val TAG = "ClassName" }`, 한국어 주석, `runCatching { }.getOrDefault()`.
**Apply to:** `BleServiceTestHarness.kt` 가 유일하게 로직을 갖는 신규 프로덕션급 파일이므로 TAG 상수·한국어 주석 관례를 따른다(단, 테스트 코드라 `runCatching` 방어적 파싱은 통상 불필요 — 실패를 그대로 드러내는 것이 골든 테스트의 목적).

## No Analog Found

없음 — 6개 파일 모두 코드베이스 내 강한 매치(`RssiCascadeTest.kt` 형식) 또는 이식 가능한 기존 패턴(`KalmanFilter.kt` nowMs)을 찾음. `BleServiceTestHarness.kt` 만 role-match(신규 계층)이나 대상 API(`ReflectionHelpers`, `ServiceController`, `ShadowApplication`)는 RESEARCH.md Code Examples 섹션의 CITED 패턴을 그대로 따르면 된다.

## Metadata

**Analog search scope:** `app/src/test/java/com/wf11/safealert/`, `app/src/main/java/com/wf11/safealert/02_ble/`, `app/src/main/java/com/wf11/safealert/03_service/`, `app/src/main/java/com/wf11/safealert/06_utils/`, `app/build.gradle`
**Files scanned:** RssiCascadeTest.kt(전체 246줄 1회 Read), KalmanFilter.kt(Grep으로 nowMs/injectWarmup 위치 특정), BleService.kt(Grep 다회, 비중복 라인 범위만 확인 — processAlert 시그니처·now·lastApproachAtMs·uwb 판정 함수·streak 맵·브로드캐스트 상수), UwbRanger.kt(Grep으로 생성자·필드·상수 위치 특정), DevSettings.kt(70줄 Read로 var 선언 관례 확인), app/build.gradle(전체 82줄 Read)
**Pattern extraction date:** 2026-08-27
