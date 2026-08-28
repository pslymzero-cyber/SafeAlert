---
phase: 02-golden
plan: 04
subsystem: testing
tags: [android, kotlin, robolectric, golden-test, regression, safety-critical, ci]
requires: [02-01, 02-02, 02-03]
provides: [BUG-02-fix, low-speed-approach-golden, ci-golden-required-list-v2]
affects: [app/src/main/java/com/wf11/safealert/03_service/BleService.kt, .github/workflows/release.yml]
tech-stack:
  added: []
  patterns:
    - "WARNING 전용 변화율(dBm/s) 게이트로 streak 하드리셋을 완화 — DANGER 는 즉시 리셋 유지 (effDanger ⊂ effWarning 상위호환)"
key-files:
  created:
    - app/src/test/java/com/wf11/safealert/ble/LowSpeedApproachRegressionTest.kt
  modified:
    - app/src/main/java/com/wf11/safealert/03_service/BleService.kt
    - .github/workflows/release.yml
    - .planning/REQUIREMENTS.md
key-decisions:
  - "D-3B: PROJECT.md 의 injectWarmup 프리셋 포화 가설은 코드와 불일치 — 실측 근본 원인은 WARNING 접촉 streak 의 단발 미달 즉시 하드리셋"
  - "DANGER streak(dangerContactStreakMap)는 수정하지 않음 — effDanger ⊂ effWarning 이라 WARNING 완화만으로 목표 달성, 이탈 시 즉시 해제 의미 보존"
  - "현장 확인 4항목은 Phase 완료 차단 게이트가 아님 — REQUIREMENTS.md BUG-02 각주로 인계, 미수행 상태 명시"
requirements-completed: [BUG-02]
actuals:
  tokens: 16936
  tasks: 3
  commits: 3
duration: "약 50분 (9d20507 14:21 ~ ffa560c 15:10, KST)"
completed: 2026-08-28
status: complete
---

# Phase 2 Plan 4: 저속 접근 지연 격상(BUG-02) 골든 포착·수정·CI 등재 Summary

WARNING 접촉 streak 하드리셋이 저속 접근 시 threshold 근접 잡음에 끊겨 격상이 지연되던 근본 원인을 규명하고, WARNING 전용 변화율 게이트로 고쳐 골든 diff(frame 85→82)로 증명했다.

## Performance

- 태스크: 3/3 완료
- 실제 비용(actuals): ~16,936 tokens (chars/4, 실현 diff 67,745자 기준) — 계획 추정 75,000 tokens 대비 약 22.6%
- 커밋: 3건 (test → fix → docs)

## Accomplishments

**1. 합성 시퀀스 파라미터 탐색 (Task 1, D-3A 1단계)**
저속 접근 시나리오는 4프레임 주기 ±2dBm 잡음을 얹은 0.25dBm/s 단조 램프로 구성했다. 첫 시도로 매끈한 단조 램프(잡음 없음)를 먼저 재생해봤으나 frame 42에서 정상 격상해 버그가 재현되지 않았다 — Time-Gate가 raw RSSI 임계값 교차 streak 기반 우회로(fastContact, warnStreak>=2)로 매 프레임 무력화되기 때문. 잡음을 얹은 두 번째 시도(이 파일의 실제 시나리오)에서 threshold 부근 streak가 반복적으로 끊기는 현상(frame 80-84, warnStreak 1↔0 4회 왕복)이 재현되어 이 파라미터를 채택했다.

**2. BUG-02 근본 원인과 증거 (Task 1, D-3B)**
- 근본 원인: WARNING 접촉 연속 카운터(streak)가 단발 미달 프레임만으로 즉시 0으로 하드리셋됨 — threshold 근접 잡음이 "2연속 프레임 통과" 조건을 반복적으로 끊어 격상이 지연된다.
- 증거: 수정 전 골든의 frame 80~84 구간에서 warnStreak 열이 1↔0을 4회 왕복하다가 frame 85에서 우연히 2연속 정렬되어서야 fastContact로 격상됨(`LowSpeedApproachRegressionTest.kt` KDoc에 프레임 단위로 기록).

**3. PROJECT.md 기존 진단과의 일치/불일치 판정 (Task 1, D-3B)**
**불일치(MISMATCH)로 판정.** PROJECT.md는 "접근 Time-Gate(`kfApproaching`/`timeGateMs`)가 저속 접근을 막는다"고 진단했으나, 코드 조사 결과 Time-Gate는 raw RSSI 임계값 교차 streak 우회로로 매 프레임 무력화되며, 매끈한 단조 램프에서는 버그가 전혀 재현되지 않았다. 실측된 결함은 "Time-Gate가 막는다"가 아니라 "WARNING streak 하드리셋이 threshold 근접 잡음에 취약해 격상이 지연된다"이다. 이 정정은 `LowSpeedApproachRegressionTest.kt`의 KDoc과 `REQUIREMENTS.md`의 BUG-02 각주 두 곳 모두에 기록했다.

**4. 수정 diff 요약과 국소화 근거 (Task 2)**
`BleService.kt`에 `warningMissRefMap: MutableMap<String, Pair<Int, Long>>`(기기별 직전 medianValue+타임스탬프)와 상수 `WARNING_DEPART_RATE_DBM_PER_SEC = 3.0`을 추가했다. WARNING 임계 미달 프레임에서 직전 프레임 대비 하강률이 3.0dBm/s 미만(완만한 잡음)이면 streak를 보존하고, 3.0dBm/s 이상(급한 하강/실이탈)이면 원래대로 즉시 0 리셋한다. `dangerContactStreakMap`(DANGER 레벨 streak)은 수정하지 않았다 — effDanger ⊂ effWarning 상위호환 구조상 WARNING만 완화해도 저속 접근의 최초 확증(경고 등급) 목표가 달성되고, DANGER 쪽 즉시 억제(이탈 시 빠른 해제) 의미는 보존된다. `warningMissRefMap` 정리는 7개 호출부(6곳 `.remove(deviceId)` + 1곳 전체 `.clear()`)에 배선해 좀비 엔트리를 방지했다.

**5. D-3D 게이트 결과 (전 태스크 공통 검증)**
**PASS.** `AlertCascadeGoldenTest.kt`(8개, 02-02)와 `UwbSessionGoldenTest.kt`(16개, 02-03)는 `git diff --stat f95e651..HEAD` 기준 **바이트 단위로 무변화**(diff 없음) — 단순히 "테스트가 통과한다"보다 강한 증거다. `AlertCascadeGoldenTest`의 `release_goldenTimeline`(120ms 간격, 실측 하강률 ≈ -8.3dBm/s)은 새 3.0dBm/s 임계를 훨씬 초과해 원래 동작(즉시 리셋)이 그대로 유지된다.

**6. 저속 골든 수정 전↔후 diff 요약 (Task 2, D-3A 3단계)**
frame 000~080은 수정 전후 동일. frame 081부터 분기해 최초 격상(WARNING) 도달 프레임이 **85 → 82**로 3프레임(3초, 1000ms 간격 시나리오) 단축됐다.

**7. release.yml 등재 클래스 목록과 상향된 하한 (Task 3)**
CI 필수 리포트 클래스 목록에 `AlertCascadeGoldenTest`, `UwbSessionGoldenTest`, `LowSpeedApproachRegressionTest` 3개를 추가해 기존 3개(`RssiCascadeTest`, `RssiCascadeIsolationTest`, `MedianFilterWarmupTest`)와 합쳐 총 6개로 확장했다. `MIN_TOTAL`을 17 → 42(8+3+6+8+16+1)로 상향해 골든 테스트가 소스셋 이탈이나 삭제로 조용히 CI에서 빠지는 것을 차단한다.

**8. 현장 확인 4항목 인계 위치와 수행 여부 (Task 3)**
`REQUIREMENTS.md`의 BUG-02 각주로 인계했다(출하 후 관찰, Phase 완료 차단 게이트 아님). 4항목: (1) 지게차가 걷는 속도보다 느리게 접근 (2) 경고 반경 진입 전후 보행자 기기에 경고 표시 (3) 평소 속도 접근과 경보 거리 차이가 크지 않음 (4) 지게차가 멀어지면 경보 정상 해제. **미수행** — 이번 세션에는 실기(보행자+지게차 2대) 현장 환경이 없어 "코드 수정 완료·현장 관측 대기" 상태로 기록했으며, 2번 항목이 실패로 보고되면 BUG-02를 재개봉한다는 조건도 명시했다.

## Task Commits

| Task | Type | Commit | Message |
|------|------|--------|---------|
| 1 | test | `9d20507` | test(02-04): capture pre-fix low-speed approach delayed-escalation golden |
| 2 | fix | `f5fe81f` | fix(02-04): rate-gate WARNING streak reset to catch low-speed BUG-02 approach |
| 3 | docs | `ffa560c` | docs(02-04): register golden classes in CI gate, footnote BUG-02/TEST-02 |

## Files Created/Modified

- `app/src/test/java/com/wf11/safealert/ble/LowSpeedApproachRegressionTest.kt` - 저속 접근 골든 (record-then-freeze, 수정 후 동결)
- `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` - WARNING streak 변화율 게이트 추가 (BUG-02 근본 수정)
- `.github/workflows/release.yml` - CI 필수 골든 클래스 목록·MIN_TOTAL 갱신
- `.planning/REQUIREMENTS.md` - TEST-02/BUG-02 각주 (진단 정정, 현장 확인 인계)

## Decisions Made

- PROJECT.md의 `injectWarmup` 진단 가설은 코드 불일치로 판정하고 폐기 — 재조사 결과인 WARNING streak 하드리셋으로 대체.
- DANGER streak는 손대지 않음 — 수정 범위를 WARNING 경로로 국한해 D-3D 게이트(기존 골든 무변화)와 프로덕션 diff 국소화(`BleService.kt` 1파일)를 동시에 만족.
- 현장 확인 4항목은 이번 세션에서 수행하지 않고 REQUIREMENTS.md 각주로 인계 — 계획이 명시적으로 이를 Phase 완료 차단 게이트가 아니라고 지정했기 때문.

## Deviations from Plan

None - plan executed exactly as written. `warningMissRefMap`/`WARNING_DEPART_RATE_DBM_PER_SEC` 추가는 계획 Task 2의 action 텍스트가 명시적으로 요구한 구현 세부사항이며, 편차 규칙(Rule 1-4) 적용 대상이 아니다.

## Issues Encountered

None - Gradle 빌드·테스트 실행이 첫 시도에 성공했고, 이전 세션에서 겪은 auto-mode 분류기 차단(`compileDebugUnitTestKotlin` 단독 호출)도 `--tests` 필터 패턴 사용으로 재발하지 않았다.

## Known Stubs

None - 이번 플랜에서 신규 스텁·placeholder 없음.

## Threat Flags

None - 이번 플랜이 만든 신규 표면(`warningMissRefMap`, `WARNING_DEPART_RATE_DBM_PER_SEC`, CI 필수 목록 확장)은 계획의 threat_model(T-02-16~T-02-21)이 이미 다뤘고, 그 범위를 벗어난 신규 네트워크·인증·파일접근·스키마 경계는 없다.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

BUG-02 코드 수정은 완료됐고 골든으로 증명됐다. 현장 확인 4항목(REQUIREMENTS.md BUG-02 각주)은 출하 후 실기 관측이 필요 — 다음 현장 세션에서 2대(보행자+지게차)로 확인 후 결과에 따라 완료 확정 또는 재개봉한다. Phase 02-golden의 나머지 플랜(있다면)이나 Phase 3(REFACTOR)로 진행 가능한 상태.

---
*Phase: 02-golden*
*Completed: 2026-08-28*

## Self-Check: PASSED
