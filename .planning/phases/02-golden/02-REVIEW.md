---
phase: 02-golden
reviewed: 2026-08-28T00:00:00Z
depth: standard
files_reviewed: 7
files_reviewed_list:
  - .github/workflows/release.yml
  - app/build.gradle
  - app/src/main/java/com/wf11/safealert/03_service/BleService.kt
  - app/src/test/java/com/wf11/safealert/ble/AlertCascadeGoldenTest.kt
  - app/src/test/java/com/wf11/safealert/ble/LowSpeedApproachRegressionTest.kt
  - app/src/test/java/com/wf11/safealert/ble/UwbSessionGoldenTest.kt
  - app/src/test/java/com/wf11/safealert/support/BleServiceTestHarness.kt
findings:
  critical: 0
  warning: 3
  info: 1
  total: 4
status: issues_found
---

# Phase 02-golden: Code Review Report

**Reviewed:** 2026-08-28T00:00:00Z
**Depth:** standard
**Files Reviewed:** 7
**Status:** issues_found

## Summary

`5830acf^..HEAD` 범위의 diff를 대상으로 골든 테스트 인프라(신규 3개 파일)와 `BleService.kt`의 WARNING streak 완화 로직(v1.1.71 BUG-02 픽스), CI 게이트(`release.yml`), 의존성 변경(`build.gradle`)을 검토했다.

CI 워크플로와 `build.gradle`은 변경 폭이 작고(테스트 의존성 scope 정상, keystore/secret 처리 기존 패턴 유지, `MIN_TOTAL=42` 산술 검증 완료) 결함을 찾지 못했다. 반면 `BleService.kt`의 신규 WARNING streak 완화 로직은 의도(저속 접근 시 단발 잡음으로 인한 streak 리셋 억제)는 타당하지만, 시간차 계산의 경계 조건과 streak 보존의 상한 부재로 인해 원래 노리던 "2프레임 연속 확증" 불변식을 정반대 방향(과소 확증)으로 약화시킬 수 있는 지점이 있다. 골든 테스트 세트 자체에서도 세션 상한 초과 케이스를 검증한다고 주장하는 테스트 하나가 실제로는 프로덕션 코드를 전혀 호출하지 않는 항진명제(tautology) 어서션으로만 구성되어 있어, CI의 `MIN_TOTAL` 플로어 개수만 채울 뿐 실질적 회귀 방어력이 없다.

## Warnings

### WR-01: WARNING streak 완화 로직이 동일 밀리초 충돌 시 0 나눗셈 방지 로직 때문에 오히려 노이즈에 취약해짐

**File:** `app/src/main/java/com/wf11/safealert/03_service/BleService.kt:1653-1663`

**Issue:**
```kotlin
val (prevMedianForWarning, prevAtMsForWarning) = warningMissRefMap[deviceId] ?: (medianValue to now)
val warningStreak = when {
    inWarningRaw -> (warningContactStreakMap[deviceId] ?: 0) + 1
    else -> {
        val dtSec = (now - prevAtMsForWarning).coerceAtLeast(1L) / 1000.0
        val rateDbmPerSec = (medianValue - prevMedianForWarning) / dtSec
        if (rateDbmPerSec <= -WARNING_DEPART_RATE_DBM_PER_SEC) 0 else (warningContactStreakMap[deviceId] ?: 0)
    }
}
```
`dtSec`은 `(now - prevAtMsForWarning).coerceAtLeast(1L)`로 0 나눗셈만 방지하고, 실제 두 스캔 콜백이 동일 밀리초에 들어오면 `dt`가 1ms로 강제 절하된다. 이 경우 `rateDbmPerSec = Δmedian / 0.001`이 되어, 단 1dBm의 잡음성 하강만으로도 `-1000 dBm/s` 수준의 값이 산출되고, 이는 새로 도입된 `WARNING_DEPART_RATE_DBM_PER_SEC = 3.0` 문턱을 항상 초과해 streak를 0으로 리셋시킨다. 즉 "완만한 잡음성 미달은 streak를 보존한다"는 이번 픽스의 핵심 목적이, 동일 밀리초에 두 번째 스캔 결과가 들어오는 타이밍(Android BLE 스캔 콜백은 배치·중복 결과 조건에 따라 동일 ms에 재호출될 수 있음)에서 정확히 무력화된다. 골든 테스트(`LowSpeedApproachRegressionTest`)는 `FRAME_DT_MS`로 프레임 간격을 고정 제어하기 때문에 이 경계 조건을 노출하지 않는다.

**Fix:** 최소 프레임 간격을 실측 스캔 주기(예: 100ms) 단위로 보장하거나, `dt`가 비정상적으로 작을 때는 rate 계산 자체를 건너뛰고 이전 streak를 그대로 유지하도록 분리한다.
```kotlin
val dtMs = now - prevAtMsForWarning
val warningStreak = when {
    inWarningRaw -> (warningContactStreakMap[deviceId] ?: 0) + 1
    dtMs < MIN_RATE_SAMPLE_INTERVAL_MS -> (warningContactStreakMap[deviceId] ?: 0)  // 표본 간격 부족 → rate 계산 보류, streak 보존
    else -> {
        val rateDbmPerSec = (medianValue - prevMedianForWarning) / (dtMs / 1000.0)
        if (rateDbmPerSec <= -WARNING_DEPART_RATE_DBM_PER_SEC) 0 else (warningContactStreakMap[deviceId] ?: 0)
    }
}
```

### WR-02: WARNING streak 보존에 상한이 없어 장시간 미달 후 단일 프레임만으로 에스컬레이션 가능

**File:** `app/src/main/java/com/wf11/safealert/03_service/BleService.kt:1654-1663`

**Issue:** `else` 분기는 하강률이 `-WARNING_DEPART_RATE_DBM_PER_SEC`를 넘지 않는 한 기존 `warningContactStreakMap[deviceId]` 값을 그대로 유지한다. 리셋 조건이 "프레임 간 하강률"뿐이라, 문턱 근처에서 아주 완만하게(또는 정체된 채) 오래 머무르는 기기는 미달 프레임이 수백 개 누적돼도 streak가 리셋되지 않는다(디바이스가 완전히 사라지는 정리 블록 — `warningMissRefMap.remove`/`.clear()` — 을 타지 않는 한). 이 상태에서 신호가 잠깐이라도 문턱을 넘으면 `inWarningRaw -> streak + 1`로 단 한 프레임만에 기존에 쌓여있던 streak(예: 이전에 이미 ≥2였던 값)에 얹혀 즉시 에스컬레이션 조건을 만족할 수 있다. 이는 원래 "2연속 프레임 확증"으로 단발 잡음 스파이크의 오탐을 막으려던 설계(v1.1.16/v1.1.22 계보)의 반대 방향 구멍이다 — 지금은 "오래된 완만한 배회 + 순간 스파이크 1프레임"이 "진짜 2연속 접근"과 동일하게 취급된다. `isDepartingNow`(속도 기반) 게이트가 일부 상황을 덮지만, streak 누적 자체에는 상한/타임아웃이 없어 완전한 방어가 아니다.

**Fix:** streak 보존에 시간 상한(예: 수 초) 또는 최대 보존치를 두어, 미달 상태가 일정 시간 이상 지속되면 강제로 리셋되도록 한다.
```kotlin
else -> {
    val dtMs = now - prevAtMsForWarning
    val rateDbmPerSec = (medianValue - prevMedianForWarning) / (dtMs.coerceAtLeast(1L) / 1000.0)
    when {
        rateDbmPerSec <= -WARNING_DEPART_RATE_DBM_PER_SEC -> 0
        dtMs > WARNING_MISS_HOLD_TIMEOUT_MS -> 0   // 완만한 미달이라도 무한정 보존하지 않음
        else -> warningContactStreakMap[deviceId] ?: 0
    }
}
```

### WR-03: `behavior16b_overSessionCap_rejectedAtRuntime`이 프로덕션 코드를 전혀 검증하지 않는 항진명제 테스트

**File:** `app/src/test/java/com/wf11/safealert/ble/UwbSessionGoldenTest.kt:542-548`

**Issue:**
```kotlin
fun behavior16b_overSessionCap_rejectedAtRuntime() {
    val overCapCount = MAX_SESSION_DEVICES + 1
    ...
    require(overCapCount <= MAX_SESSION_DEVICES) {
        "상한($MAX_SESSION_DEVICES) 초과 기기 수($overCapCount) 는 골든 대상이 아니다 — BUG-03 v2 이월."
    }
```
테스트 이름과 CI 주석(release.yml의 `MIN_TOTAL=42` 산정에 `16(UwbSessionGoldenTest)`로 이 테스트도 포함됨)은 "세션 상한 초과 시 런타임에 거부된다"는 실제 동작을 검증하는 것처럼 보이지만, 실제 어서션 대상은 테스트가 로컬로 만든 `overCapCount`(`= MAX_SESSION_DEVICES + 1`, 상수로부터 항상 7)와 동일 파일의 `MAX_SESSION_DEVICES` 상수(6) 비교뿐이다. `require(7 <= 6)`는 입력값과 무관하게 항상 거짓이므로, 이 `require`는 `UwbRanger`나 `BleService`의 실제 세션 상한 로직을 단 한 줄도 거치지 않고 무조건 실패 → `IllegalArgumentException` 발생 → 테스트 통과, 라는 흐름을 매번 반복할 뿐이다. `UwbRanger.MULTICAST_MAX`(실제 프로덕션 상한)가 바뀌거나 세션 상한 로직 자체가 깨져도 이 테스트는 항상 그린이다. 주석에 "BUG-03 v2 이월"이라 스스로 명시해 실질 커버리지 부재를 인지하고 있으나, 테스트명·설명 주석이 실제로 검증되지 않는 동작("런타임 거부")을 검증하는 것처럼 오도하고, CI의 `MIN_TOTAL` 하한 산정에도 실질 회귀 방어력 없이 개수만 더해진다.

**Fix:** 실제 검증 없이 개수만 채우는 플레이스홀더라면 `@Ignore("BUG-03 v2 이월 — 상한 초과 시 UwbRanger 실동작 검증 아직 미구현")`로 명시하거나, 최소한 `UwbRanger` 인스턴스에 6대 초과 controlee를 실제로 추가해 몇 번째부터 거부되는지(리스트 크기, 반환값 등)를 검증하도록 다시 작성한다. 현재 형태로 두려면 테스트명에서 "rejectedAtRuntime" 같은 실동작 암시 문구를 제거하고 "항상 실패하는 로컬 assert일 뿐 프로덕션 미검증"임을 KDoc에 명시한다.

## Info

### IN-01: `resetBetweenTests()`가 이름과 달리 일부 상태 맵만 초기화함

**File:** `app/src/test/java/com/wf11/safealert/support/BleServiceTestHarness.kt:157-160`

**Issue:**
```kotlin
fun resetBetweenTests(service: BleService) {
    shadowOf(RuntimeEnvironment.getApplication()).clearBroadcastIntents()
    alertStateFieldOf(service).clear()
}
```
함수명은 "테스트 간 완전 초기화"를 암시하지만 실제로는 브로드캐스트 로그와 `alertState` 맵 두 가지만 비운다. `warningContactStreakMap`, `warningMissRefMap`, `dangerContactStreakMap`, `kalmanFilters`, `trackingStateMap` 등 다른 상태 맵은 그대로 남는다. 현재는 `AlertCascadeGoldenTest.kt`/`LowSpeedApproachRegressionTest.kt`의 모든 테스트가 `newService()`로 매번 새 `BleService` 인스턴스를 생성하고 JUnit4도 테스트 메서드마다 새 테스트 클래스 인스턴스를 만들기 때문에, 이 함수가 실제로 인스턴스를 재사용하는 시나리오에서 호출되는 사례는 없어 현재는 실질적 결함(테스트 신뢰성 저하)을 일으키지 않는다. 다만 향후 이 헬퍼를 "완전 초기화"로 오인해 서비스 인스턴스를 재사용하는 테스트를 작성하면 잔존 streak/필터 상태로 인해 은근한 flaky 테스트가 생길 수 있는 이름-동작 불일치(footgun)다.

**Fix:** 함수명을 `resetAlertStateAndBroadcasts()` 등으로 좁히거나, KDoc에 "alertState/브로드캐스트만 초기화 — 다른 상태 맵은 인스턴스 재사용 시 남는다"를 명시한다.

---

_Reviewed: 2026-08-28T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
