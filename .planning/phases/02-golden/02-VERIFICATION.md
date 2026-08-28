---
phase: 02-golden
verified: 2026-08-28T00:00:00Z
status: gaps_found
score: 3/4 must-haves verified
behavior_unverified: 0
overrides_applied: 0
gaps:
  - truth: "판정 상수를 하나 건드리면 골든 테스트가 실패하고, 실패 메시지로 어떤 프레임의 무엇이 어떻게 달라졌는지 실기 없이 판별할 수 있다 (ROADMAP Phase 2 성공기준 1 후반부, TEST-01)"
    status: failed
    reason: "02-02 실행 중 레드 트라이얼(판정 상수 섭동) 4회 시도 전부 골든 테스트 실패를 유발하지 못함(매번 BUILD SUCCESSFUL, 8/8 pass). 시도한 4개 상수(:1885 dangerStreak>=2→3 2회, :2326 fastDangerContact dangerStreak>=2→3, 관련 진단 1회) 모두 무영향 지점으로 판명(golden 시나리오의 워밍업 대역이 걸러내거나 논리적으로 도달 불가). 유일하게 부하가 걸리는 후보로 특정된 :2330 warningStreak>=2 는 사용자 승인 대기 상태로 남아 5차 시도가 재개되지 않음. 02-02-SUMMARY.md 자체가 'Self-Check: PARTIAL / NOT FOUND' 로 명시하고 있고, AlertCascadeGoldenTest.kt KDoc(274-292행)에도 미검출 결과가 그대로 기록되어 있음 — 이번 검증에서 코드를 직접 읽어 확인함."
    artifacts:
      - path: "app/src/test/java/com/wf11/safealert/ble/AlertCascadeGoldenTest.kt"
        issue: "레드 트라이얼 시도·미검출 결과가 KDoc 주석(274-292행)에 기록만 되어 있을 뿐, '상수를 건드리면 테스트가 실패한다'를 실제로 증명하는 자동화된 증거나 성공 기록이 없음"
    missing:
      - "warningStreak>=2 (BleService.kt:~2330) 섭동 시 골든 테스트가 실패하는지 실측하고, 실패 메시지가 어떤 프레임·어떤 값이 달라졌는지 보여주는지 확인"
      - "사용자 승인 획득 후 5차 레드 트라이얼 재개, 또는 이 must_have 를 충족할 대체 증명 수단 확정"
human_verification:
  - test: "지게차 1대 + 보행자 1대 실기로, 지게차가 걷는 속도보다 느리게 경고 반경 밖에서 안으로 접근할 때 보행자 기기에 경고가 뜨는지, 평소 속도 접근과 경고 거리 차이가 크지 않은지, 지게차가 멀어지면 경보가 정상 해제되는지 확인"
    expected: "4항목(접근 시 WARNING 표시·평소 속도와 큰 차이 없음·이탈 시 정상 해제) 모두 성립"
    why_human: "ROADMAP Phase 2 성공기준 4(BUG-02 현장 관측)는 실기·현장 환경이 필요해 코드 검토로 판별 불가. REQUIREMENTS.md BUG-02 각주에 '출하 후 관찰, Phase 완료 차단 게이트 아님'으로 명시적으로 비차단 처리됨 — 미수행 상태가 이번 세션의 의도된 인계이며, 2번 항목(경고 반경 진입 전후 표시) 실패 시 BUG-02 재개봉 조건이 이미 문서화되어 있음"
---

# Phase 2: 안전 크리티컬 경로 골든 테스트 Verification Report

**Phase Goal:** 경보 격상·해제 전 경로와 UWB 세션 전환의 현재 동작이 기대값으로 고정되고, 그 안전망이 저속 접근 WARNING 미도달 버그를 실제로 잡아낸다
**Verified:** 2026-08-28
**Status:** gaps_found
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

ROADMAP.md Phase 2 성공기준(Success Criteria) 4개를 계약으로 삼고, PLAN must_haves 를 병합해 아래 4개 진실로 정리했다.

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | 고정 RSSI 시퀀스 재생 시 SAFE→WARNING→DANGER 격상과 역방향 해제의 등급·전이 시점이 기대값(golden)과 일치한다 (TEST-01 전반부) | VERIFIED | `AlertCascadeGoldenTest.kt`(8개 `@Test`, 44/83/112/129/219/243/257/294행)를 직접 읽어 확인. `release_goldenTimeline`(escalation+release), `sameSequence_replaysIdentically`(결정성), `suddenContact_dangerOverride_bypassesPEmaLag`(급접촉) 모두 `assertScenario`로 동결된 golden 배열과 프레임 단위 비교. 오케스트레이터 측정 결과 8/8 pass |
| 2 | 판정 상수를 하나 건드리면 골든 테스트가 실패하고, 실패 메시지로 무엇이 어떻게 달라졌는지 실기 없이 판별할 수 있다 (TEST-01 후반부, ROADMAP 성공기준 1) | ✗ FAILED | `AlertCascadeGoldenTest.kt` 274-292행 KDoc 및 `02-02-SUMMARY.md` Self-Check 에서 직접 확인 — 레드 트라이얼 4회 시도 전부 미검출(BUILD SUCCESSFUL). 유일한 유효 후보(`:2330 warningStreak>=2`)는 사용자 승인 대기로 미시도. 상세는 아래 Gaps Summary |
| 3 | UWB 세션 6대 이하 범위에서 Case A/B 전환·신선도 게이트 기반 판정 권위 강등(재해석된 '좀비 워치독')·재연결 경로가 기대값과 일치한다 (TEST-02) | VERIFIED | `UwbSessionGoldenTest.kt`(16개 `@Test`, behavior1~16b) 직접 읽어 함수명·구조 확인. `newRanger`/`injectUwbSample`/`judgeMode` 헬퍼로 Case A/B 전환(behavior3~9)·낡은 근접 실측의 좀비 DANGER 부재(behavior10)·재연결 경계(behavior12~14)·6대 이하 다기기 비오염(behavior15~16) 모두 실체 있는 어서션. 오케스트레이터 측정 16/16 pass. 단, behavior16b 는 프로덕션 코드를 전혀 건드리지 않는 항진명제 — Anti-Patterns 절 참고(진실 3의 필수 범위인 ≤6대 축 자체는 behavior16 이 별도로 커버해 이 truth 를 무효화하지 않음) |
| 4 | 약한 콜드스타트 RSSI 에서 저속 접근 시퀀스가 WARNING 등급에 도달하며, 이 시나리오가 골든 테스트에 회귀 케이스로 남는다 (BUG-02 코드측) | VERIFIED | `LowSpeedApproachRegressionTest.kt` 직접 읽음 — `LOWSPEED_GOLDEN[82]` = `"frame=082 rssi=-73 level=1 entry=82000 ... bcast=1 pending=N"` 로 WARNING(level=1) 도달을 golden 배열 자체에서 확인. 프로덕션 diff `git diff --stat f95e651..HEAD -- app/src/main` = `BleService.kt` 1파일, 36삽입/1삭제로 국소화 확인. `warningMissRefMap`/`WARNING_DEPART_RATE_DBM_PER_SEC=3.0` 코드 직접 확인(1418, 1434, 1652-1663행) |

**Score:** 3/4 truths verified (진실 2는 FAILED — Gaps 절 참고, 진실 5(현장 관측)는 별도 사유로 human_verification 으로 라우팅)

ROADMAP 성공기준 4("사용자가 현장에서... 확인한다")는 실기 필요 항목이라 위 표에 포함하지 않고 Human Verification Required 절로 분리했다 — REQUIREMENTS.md BUG-02 각주가 이를 "Phase 완료 차단 게이트 아님"으로 이미 명시했기 때문에 gaps 목록에는 넣지 않았다.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `app/src/test/java/com/wf11/safealert/support/BleServiceTestHarness.kt` | Robolectric 하네스(무-`onCreate()` 서비스 생성, `processAlert` 리플렉션 호출, 상태 판독 헬퍼) | ✓ VERIFIED | `newService()`(31행) = `Robolectric.buildService(BleService::class.java).get()`(assumption A1 그대로), `callProcessAlert`/`alertLevelOf`/`alertEntryMsOf`/`alertBroadcasts`/`applyGoldenDevSettings`/`resetBetweenTests` 전부 실체 확인 |
| `app/src/test/java/com/wf11/safealert/ble/AlertCascadeGoldenTest.kt` | 격상/해제 캐스케이드 golden, 결정성, 급접촉 서브시나리오 | ✓ VERIFIED | 8 `@Test`, 42/48/N 프레임 golden 배열 존재, 오케스트레이터 8/8 pass |
| `app/src/test/java/com/wf11/safealert/ble/UwbSessionGoldenTest.kt` | Case A/B·신선도 경계·재연결·6대 이하 다기기 golden | ✓ VERIFIED (behavior16b 예외) | 16 `@Test`, 16/16 pass. behavior16b 는 실체 없는 항진명제 — Anti-Patterns 참고 |
| `app/src/test/java/com/wf11/safealert/ble/LowSpeedApproachRegressionTest.kt` | 저속 접근 골든(수정 후 동결), WARNING 도달 프레임 회귀 케이스 | ✓ VERIFIED | 1 `@Test`(계획상 2개 명명 → 1개 golden 배열로 정당하게 통합 확인, frame=082 에서 level=1 직접 확인), 1/1 pass |
| `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (수정분) | WARNING streak 변화율 게이트 (BUG-02 수정) | ✓ VERIFIED (강건성 경고 있음) | `warningMissRefMap`/`WARNING_DEPART_RATE_DBM_PER_SEC` 존재·배선(7개 정리 호출부) 확인. WR-01/WR-02 강건성 경고는 Anti-Patterns 참고 |
| `.github/workflows/release.yml` | 골든 테스트 6개 클래스 CI 필수 등재, MIN_TOTAL=42 | ✓ VERIFIED | 66-67행에 `REQUIRED="RssiCascadeTest RssiCascadeIsolationTest MedianFilterWarmupTest AlertCascadeGoldenTest UwbSessionGoldenTest LowSpeedApproachRegressionTest"`, `MIN_TOTAL=42` 확인 |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| `BleServiceTestHarness.newService()` | `BleService` (LifecycleService) | `Robolectric.buildService(...).get()` | WIRED | `.get()` 확인(assumption A1: `onCreate()` 미실행, `.create()` 아님) |
| `processAlert(..., nowMs: () -> Long = { System.currentTimeMillis() })` | 골든 테스트의 결정론적 프레임 시각 주입 | 기본인자 시간 시임 | WIRED | 1418, 1434행 확인, 프로덕션 기본 경로는 `System.currentTimeMillis()` 그대로라 배포 동작 불변 |
| `.github/workflows/release.yml` REQUIRED 목록 | 실제 4개 골든 테스트 클래스 파일명 | 문자열 비교(테스트 리포트 XML) | WIRED | 6개 클래스명이 실제 파일의 클래스명과 정확히 일치, MIN_TOTAL=42 = 8+3+6+8+16+1 실측치와 정확히 일치 |
| `warningMissRefMap` 갱신 | `processAlert` WARNING streak 판정(1654-1663행) | 직전 프레임 대비 하강률(dBm/s) 게이트 | WIRED (WR-01 강건성 경고) | 코드 배선 자체는 정상이나, 동시각(0ms 간격) 스캔 콜백 시 `coerceAtLeast(1L)`이 하강률을 인위적으로 부풀려 게이트가 상시 우회될 수 있음(아래 Anti-Patterns) |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|--------------|--------|----------|
| TEST-01 | 02-02-PLAN.md | 격상/해제 golden + 판정 상수 레드 트라이얼 | ⚠ PARTIAL | 격상/해제 golden 자체는 SATISFIED(진실 1). 레드 트라이얼(진실 2, ROADMAP 성공기준 1 후반부)은 BLOCKED — 자체 self-check NOT FOUND |
| TEST-02 | 02-03-PLAN.md | UWB 세션 ≤6대 Case A/B·신선도게이트·재연결 golden | ✓ SATISFIED | 진실 3, 16/16 pass, 3축 매핑표 함수명 실체 확인. behavior16b 항진명제는 ≤6대 축 자체를 무효화하지 않음(≤6대는 behavior16 이 커버, over-cap 은 BUG-03 v2 이월로 TEST-02 범위 밖) |
| BUG-02 | 02-04-PLAN.md | 저속 접근 WARNING 미도달 수정 + golden 회귀 케이스 + CI 등재 | ✓ SATISFIED (코드측) | 진실 4, frame=082 WARNING 확인, 프로덕션 diff 국소화 확인, CI 등재 확인. 현장 확인 4항목은 REQUIREMENTS.md 각주로 명시적 비차단 인계(Human Verification 참고) |

ORPHANED 없음 — REQUIREMENTS.md traceability 표(83-100행)가 Phase 2 에 매핑한 3개(TEST-01/TEST-02/BUG-02)와 4개 PLAN 이 선언한 requirements 필드가 정확히 일치.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `UwbSessionGoldenTest.kt` | 541-553 | `behavior16b_overSessionCap_rejectedAtRuntime` — `require(overCapCount <= MAX_SESSION_DEVICES)` 에서 `overCapCount = MAX_SESSION_DEVICES + 1` 이 로컬 상수라, 이 테스트는 항상(무조건) `IllegalArgumentException` 을 던짐. `UwbRanger`/`BleService` 의 실제 세션 상한 로직을 전혀 호출하지 않는 항진명제(tautology) | ⚠ Warning | 테스트명이 "런타임에 거부됨"을 암시하지만 실제로는 아무 프로덕션 코드도 검증하지 않음. `MIN_TOTAL=42` 카운트에 조용히 기여해 CI 커버리지를 과대평가시킬 위험(리뷰 WR-03, 코드 직접 읽어 확인) |
| `BleService.kt` | 1657 | `val dtSec = (now - prevAtMsForWarning).coerceAtLeast(1L) / 1000.0` — 동일 밀리초 내 중복 스캔 콜백(현실적인 BLE 배치/중복 스캔 결과 상황) 시 `dtSec` 이 0.001초로 바닥, 1dBm 잡음도 rateDbmPerSec≈1000 으로 부풀려 `WARNING_DEPART_RATE_DBM_PER_SEC=3.0` 게이트를 상시 우회 | ⚠ Warning | BUG-02 수정의 핵심 메커니즘(잡음 흡수)이 이 타이밍 조건에서 무력화될 수 있음 — golden 은 고정 `FRAME_DT_MS` 를 쓰므로 이 경로를 노출하지 못함(리뷰 WR-01, 코드 직접 읽어 확인) |
| `BleService.kt` | 1654-1663 | WARNING streak 보존(완만한 하강)에 시간·횟수 상한이 없음 — "오래 정체하다 급 상승 1프레임"도 "진짜 2연속 접촉"과 동일하게 즉시 격상될 수 있음 | ⚠ Warning | 과거(v1.1.16/v1.1.22) streak 확증 게이트가 막으려던 성급한 격상 리스크가 반대 방향으로 재발할 여지(리뷰 WR-02, 구조 확인 완료) |
| `BleServiceTestHarness.kt` | 157-160 | `resetBetweenTests()` 이름은 전체 리셋을 암시하지만 실제로는 `broadcastIntents`+`alertState` 2개만 초기화. 다른 상태 Map(≥5개)은 남음 | ℹ Info | 현재는 모든 테스트가 `newService()` 로 매번 새 인스턴스를 만들어 무해하나, 향후 인스턴스 재사용 시 잔여 상태로 인한 오탐 위험(리뷰 IN-01, 구조 확인 완료) |

TBD/FIXME/XXX/TODO/HACK/PLACEHOLDER 디버트 마커: `BleService.kt`, `AlertCascadeGoldenTest.kt`, `UwbSessionGoldenTest.kt`, `LowSpeedApproachRegressionTest.kt`, `BleServiceTestHarness.kt` 5개 파일 전체 grep 결과 0건 — 디버트 마커 게이트 통과.

### Behavioral Spot-Checks

오케스트레이터가 이미 `./gradlew testDebugUnitTest --rerun-tasks` 를 방금 재실행해 42/42 pass(0 failures/errors/skipped)를 JUnit XML 로 확인했다고 명시했으므로, 전체 스위트를 이번 검증에서 다시 돌리지 않았다(중복 실행 금지 원칙). 대신 개별 테스트 파일을 직접 읽어 golden 배열·어서션의 실체를 코드 수준에서 확인하는 방식으로 대체했다 — 위 Required Artifacts/Observable Truths 절 참고.

### Probe Execution

`find . -path '*/tests/probe-*.sh' -type f` 결과 0건 — Android/Gradle 프로젝트라 probe 스크립트 컨벤션 대상 아님. **Step 7c: SKIPPED (no probe scripts found, not applicable to this project type)**

### Human Verification Required

#### 1. 저속 접근 지게차 현장 경보 확인 (BUG-02, ROADMAP 성공기준 4)

**Test:** 수정된 앱을 기기 2대(보행자 1 + 지게차 1)에 설치, 지게차가 걷는 속도보다 느리게 경고 반경 밖에서 안으로 접근
**Expected:** (1) 경고 반경 진입 전후로 보행자 기기에 경고 표시 (2) 평소 속도 접근과 비교해 경고 뜨는 거리 차이가 크지 않음 (3) 지게차가 멀어지면 경보가 정상 해제됨
**Why human:** 실기·현장(2대 동시) 환경이 필요해 코드 검토로 판별 불가. REQUIREMENTS.md BUG-02 각주가 "출하 후 관찰, Phase 완료 차단 게이트 아님"으로 명시적으로 비차단 처리 — 미수행 상태가 이번 세션의 의도된 인계이며, 항목 (1) 이 실패로 보고되면 BUG-02 를 재개봉한다는 조건이 이미 REQUIREMENTS.md/02-04-SUMMARY.md 양쪽에 문서화되어 있음

### Gaps Summary

ROADMAP Phase 2 성공기준 1은 두 절로 구성된다: (a) 격상/해제 전이 시점이 golden 과 일치, (b) 판정 상수를 건드리면 테스트가 실패한다. (a)는 `AlertCascadeGoldenTest.kt` 8개 테스트로 명확히 충족되었으나, (b)는 02-02 실행에서 시도한 레드 트라이얼 4회가 전부 미검출로 끝났고, 유일하게 유효할 것으로 특정된 후보(`warningStreak>=2`, `BleService.kt:~2330`)는 사용자 승인 대기 상태로 남아 검증이 재개되지 않았다. 이는 SUMMARY 가 스스로 "Self-Check: PARTIAL/NOT FOUND"로 보고한 항목이며, 이번 검증에서 `AlertCascadeGoldenTest.kt`의 KDoc(274-292행)을 직접 읽어 그 실측 실패 내역을 재확인했다 — 은폐되거나 축소 보고된 것은 아니지만, 요구된 산출물(상수 섭동 시 실패 + 4요소 실패 메시지) 자체는 코드베이스에 존재하지 않는다.

이 gap 은 "안전망이 실제로 잡아낸다"는 phase goal 의 절반(저속 접근 BUG-02 특정 시나리오)에는 영향이 없다 — 그 절반은 frame=082 golden 으로 구체적으로 실증되었다. 하지만 다른 절반(판정 로직 전반의 회귀 민감도, TEST-01의 명시적 요구사항이자 CI-02 선례를 잇는 항목)은 미완성 상태로 남아 있다.

부수적으로, 프로덕션 코드 리뷰(WR-01/WR-02)에서 확인된 두 가지는 이 gap과는 별개로 BUG-02 수정 자체의 강건성에 관한 것이다 — 동시각 스캔 콜백 시 게이트가 우회될 수 있는 조건(WR-01)이 golden 시나리오의 고정 프레임 간격으로는 노출되지 않는다는 점은, "안전망이 실제로 잡아낸다"는 phase goal이 테스트된 시나리오 범위 밖에서는 아직 증명되지 않았음을 보여준다. 이 항목들은 truth-level FAIL 로 채점하지 않았지만(테스트된 시나리오 자체는 통과), gaps_found 상태와 별개로 프로덕션 강건성 개선 항목으로 인계할 가치가 있다.

---

*Verified: 2026-08-28*
*Verifier: Claude (gsd-verifier)*
