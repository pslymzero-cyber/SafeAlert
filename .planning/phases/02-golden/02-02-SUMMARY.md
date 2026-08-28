---
phase: 02-golden
plan: 02
subsystem: testing
tags: [golden-test, record-then-freeze, junit, robolectric, kotlin, ble, rssi, red-trial]

# Dependency graph
requires:
  - phase: 02-golden
    plan: 01
    provides: "BleServiceTestHarness(processAlert 리플렉션 구동 + nowMs 주입 + 골든 DevSettings 프로파일), AlertCascadeGoldenTest 스켈레톤"
provides:
  - "격상 골든 — SAFE->WARNING->DANGER 42프레임 전량 동결(record-then-freeze)"
  - "해제 골든 — DANGER->WARNING->SAFE 48프레임 역방향 램프 전량 동결"
  - "급접촉 서브 시나리오 골든 — 워밍업 5프레임(-95) 후 -54dBm 계단, fastContact 경로 관측"
  - "renderFrame 직렬화 규약 — 프레임당 고정폭 1줄(rssi/level/entry/median/pEma/streak), entry 는 T0_MS 상대 ms"
  - "재생 결정성 단언 — 동일 시퀀스 2회 구동 결과 완전 일치"
affects: [02-03, 02-04, 03-refactor]

# Actuals (#2632) — pairs with the plan's `estimate` to calibrate future estimates.
# Same estimateTokens scale (chars/4 over the realized diff), never a harness token count.
actuals:
  tokens: 7521
  tasks: 3
  commits: 5

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "record-then-freeze: 기대값은 1회 실구동 캡처값만 동결. 손 계산 금지, 재동결은 사람이 파일을 직접 편집하는 경로 하나뿐"
    - "프레임 직렬화: 관측면 전체를 고정폭 한 줄로 렌더 -> diff 가 어느 프레임/어느 필드인지 즉시 지목"
    - "시간 이음매 고정: nowMs = T0_MS + frameIdx * FRAME_DT_MS 로 KalmanFilter dt 를 결정화"

key-files:
  created: []
  modified:
    - app/src/test/java/com/wf11/safealert/ble/AlertCascadeGoldenTest.kt
    - app/src/test/java/com/wf11/safealert/support/BleServiceTestHarness.kt

key-decisions:
  - "FRAME_DT_MS=120L 고정 — KalmanFilter 가 실시각 dt 를 소비하므로 시간 이음매를 명시 고정하지 않으면 골든이 비결정적. 73f4145 에서 격상 골든 전량 재동결"
  - "급접촉 시나리오 진폭을 -30 -> -54dBm 로 재교정(bf03518) — -30 은 낙차 65dB 로 pEma 단독 DANGER 를 만들어 표적 분기를 무력화. 오버라이드 발화 창 x1 in (-55, -32.5) 계산으로 도출"
  - "red-trial 을 4차에서 중단(사용자 결정) — 5차 표적 BleService.kt:2330 섭동은 사용자 승인 대기. 지시 7항(안 깨지면 재시도 금지, 원인 규명 후 정지) 준수"
  - "골든 주석 오기(:285-292, :604-610) 정정은 이번 plan 에서 하지 않음 — 프로덕션 무변경 원칙과 무관한 테스트 주석이나, 사용자 판단 대기 항목으로 남김"

patterns-established:
  - "red-trial 증거 규약: 섭동 전 git status 베이스라인 -> 섭동 1건 -> gradle exit code -> JUnit XML timestamp/counts 로 실제 실행 확인(UP-TO-DATE 는 증거 아님) -> 원복 후 zero exit -> status 베이스라인 일치"
  - "미검출 시 다른 상수로 재시도 금지 — 코드 경로 도달성부터 규명. 필요하면 임시 진단 테스트로 실측 후 삭제(커밋 없음)"

requirements-completed: [TEST-01]

coverage:
  - id: D1
    description: "격상 골든 — SAFE->WARNING->DANGER 42프레임 매 프레임 관측면 전량 동결"
    requirement: "TEST-01"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/wf11/safealert/ble/AlertCascadeGoldenTest.kt#escalation_goldenTimeline"
        status: pass
    human_judgment: false
  - id: D2
    description: "해제 골든 — DANGER->WARNING->SAFE 48프레임 역방향 램프 동결"
    requirement: "TEST-01"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/wf11/safealert/ble/AlertCascadeGoldenTest.kt#release_goldenTimeline"
        status: pass
    human_judgment: false
  - id: D3
    description: "재생 결정성 — 동일 시퀀스 2회 구동 결과 완전 일치"
    requirement: "TEST-01"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/wf11/safealert/ble/AlertCascadeGoldenTest.kt#sameSequence_replaysIdentically"
        status: pass
    human_judgment: false
  - id: D4
    description: "급접촉 서브 시나리오 — 워밍업 5프레임(-95) 후 -54dBm 계단, fastContact 발령 경로 동결"
    requirement: "TEST-01"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/wf11/safealert/ble/AlertCascadeGoldenTest.kt#suddenContact_dangerOverride_bypassesPEmaLag"
        status: pass
    human_judgment: false
  - id: D5
    description: "프로덕션 코드 무변경 — red-trial 4회 전부 섭동 후 원복, app/src/main diff 0"
    requirement: "TEST-01"
    verification:
      - kind: other
        ref: "git diff --exit-code -- app/src/main (bf03518 기준 변경 없음) + git status --porcelain -- app/ 공란"
        status: pass
    human_judgment: false
  - id: D6
    description: "must_haves.truths 표적 상수 섭동 시 골든 실패 — 이 plan 에서 증명하지 못함. red-trial 4회 전부 미검출이며, 4회 모두 표적이 no-op(도달 불가 또는 논리 포섭)임을 코드/실측으로 규명. Task 3 acceptance_criteria 5건 중 2건(non-zero exit 콘솔 증거, 4요소 실패 메시지) 미충족"
    requirement: "TEST-01"
    verification:
      - kind: manual_procedural
        ref: "red-trial 1~4차 (RESUME.md 13~18절) — EXIT=0, tests=8 failures=0 errors=0 (XML timestamp 갱신으로 실제 실행 확인)"
        status: fail
    human_judgment: true
    rationale: "미검출의 원인이 골든의 결함이 아니라 표적 선정의 결함임을 4회 모두 코드 수준에서 확정했다. BleService.kt:1885 는 워밍업 -95 가 FILTER_PRESERVE_BAND 밖이라 매 프레임 필터가 clear 되어 stableLevel 이 진입 전에 이미 DANGER — 진입조건 거짓(도달 불가, PROBE 실측 확정). BleService.kt:2326 fastDangerContact 는 :2330 warning 항에 논리적으로 완전 포섭되는 데드코드. 급접촉 프레임 발령을 지배하는 단일 상수는 BleService.kt:2330 의 warningStreak >= 2 하나로 좁혀졌고, 이를 표적으로 한 5차 시도는 사용자 지시 7항에 따라 승인 대기 상태로 정지했다. 자동 검증 불가 — 사람 판단으로 라우팅한다."

duration: 159min
completed: 2026-08-28
status: complete
---

# Phase 2 Plan 02: 경보 캐스케이드 골든 타임라인 Summary

**격상/해제/급접촉 3개 시나리오를 record-then-freeze 로 동결했고, 레드 트라이얼은 4회 시도 전부 미검출 — 4회 모두 표적 상수가 no-op 임을 코드/실측으로 규명한 뒤 사용자 지시에 따라 정지했다.**

## Performance

- Started: 2026-08-28 08:56 KST (02-01 문서 커밋 6760f60 직후)
- Completed: 2026-08-28 11:35 KST (red-trial 4차 원복 검증 완료)
- Duration: 159min
- Tasks: 3 (Task 1 완료, Task 2 완료, Task 3 부분 완료)
- Files modified: 2 (전부 app/src/test — 프로덕션 diff 0)

## Accomplishments

- 격상 골든 동결: START_DBM=-95, STEP_DBM=1, FRAMES=42 램프. 매 프레임 rssi/level/entry/medianValue/pEma/dangerStreak/warningStreak 를 고정폭 1줄로 직렬화해 전량 기대값에 박아넣음. frame=022 에서 WARNING, frame=039 에서 DANGER 전환.
- KalmanFilter 시간 이음매 결함 발견·수정: nowMs 를 고정하지 않으면 dt 가 실시각에 따라 흔들려 골든이 비결정적. nowMs = T0_MS + frameIdx * FRAME_DT_MS (T0_MS=1_000_000L, FRAME_DT_MS=120L) 로 고정하고 격상 골든 전량 재동결(73f4145).
- 해제 골든 동결: 격상 마지막 값에서 1dB 씩 48프레임 하강. 해제 방향 히스테리시스와 alertState 소거 시점을 프레임 단위로 고정.
- 급접촉 서브 시나리오 동결: 워밍업 5프레임 -95 후 -54dBm 계단(CONTACT_RSSI). 진폭 1차 안(-30)은 낙차가 65dB 라 pEma 단독으로 DANGER 를 만들어 표적 분기를 무력화함을 계산으로 규명하고 -54 로 재교정(bf03518).
- 재생 결정성 단언: 동일 시퀀스를 두 번 구동해 렌더 결과가 완전히 같음을 단언 — 골든이 시각/전역 상태에 오염되지 않음을 기계 검증.
- 레드 트라이얼 4회 실행 및 미검출 원인 전량 규명(아래 Deviations 참조). 4회 모두 프로덕션 트리를 섭동 전 상태로 원복했고 커밋은 0건.

## Task Commits

1. **Task 1 (격상 골든)** — `4bca296` feat(02-02): 격상(SAFE->WARNING->DANGER) 골든 프레임별 기록·동결 (AlertCascadeGoldenTest.kt +198)
2. **Task 1 후속 (시간 이음매 수정)** — `73f4145` fix(02-02): re-freeze escalation golden under corrected KalmanFilter time seam (AlertCascadeGoldenTest.kt, BleServiceTestHarness.kt — 2 files, +110 -32)
3. **Task 2 (해제 골든)** — `f31edf0` test(02-02): freeze release golden DANGER->SAFE reverse ramp (+116 -3)
4. **Task 3 준비 (급접촉 시나리오 동결)** — `5e0c68c` test(02-02): 급접촉 서브 시나리오 골든 동결 — dangerStreak 오버라이드 증명 (+59)
5. **Task 3 준비 (진폭 재교정)** — `bf03518` test(02-02): 급접촉 서브 시나리오 진폭 재교정(-30->-54dBm) — 오버라이드 발화 창 확보 (+28 -22)

**Plan metadata:** files_modified 는 1건(AlertCascadeGoldenTest.kt)으로 선언되었으나 실제로는 BleServiceTestHarness.kt 도 수정됐다(73f4145, nowMs 주입 경로). 둘 다 app/src/test 이므로 prohibitions 위반은 아니다.

_Note: Task 3(레드 트라이얼)은 비-TDD 태스크이며 커밋되는 코드 변경이 없다. 커밋 4·5 는 Task 3 의 표적 분기를 살리기 위한 시나리오 준비 작업이다._

## Files Created/Modified

- `app/src/test/java/com/wf11/safealert/ble/AlertCascadeGoldenTest.kt` — @Test 8건(02-01 산출 4건 + 이번 4건), 골든 상수(T0_MS, FRAME_DT_MS, START_DBM, STEP_DBM, FRAMES, RELEASE_FRAMES, CONTACT_WARMUP_FRAMES, CONTACT_RSSI), renderFrame/runScenario 헬퍼, 채택 파라미터 실측 근거 주석
- `app/src/test/java/com/wf11/safealert/support/BleServiceTestHarness.kt` — nowMs 명시 주입 경로 추가(+29)
- `app/src/main/**` — 변경 없음 (red-trial 섭동은 4회 전부 원복 검증)

## Decisions Made

- **기대값은 실구동 캡처값만 동결.** 손 계산으로 기대값을 짓지 않는다. 프로덕션 동작이 바뀌면 골든이 깨지고, 재동결은 사람이 파일을 직접 편집하는 경로 하나뿐이다(자동 갱신 경로 생성 금지 — prohibitions 준수).
- **시간 이음매 명시 고정.** KalmanFilter 가 실시각 dt 를 소비하므로 nowMs 를 프레임 인덱스에서 계산해 결정화. 이 결정이 없으면 골든 자체가 재현 불가.
- **관측면은 프로덕션에 이미 있는 값만.** medianValue/avgRssi 관측면 추가 금지(prohibitions)를 지켜, 하네스가 읽는 필드만으로 렌더한다.
- **red-trial 미검출 시 표적 교체 금지, 도달성부터 규명.** 사용자 지시 7항. 3차에서는 임시 진단 테스트로 pEma 실측(PROBE)까지 수행한 뒤 삭제했다(커밋 없음).

## Deviations from Plan

### 1. red-trial 1차 미검출 — 완만 램프가 표적 분기 영향권을 통과하지 않음

- **Found during:** Task 3, 1차 시도
- **Issue:** `BleService.kt:1885` 의 `dangerStreak >= 2` 를 `>= 3` 으로 섭동했으나 BUILD SUCCESSFUL(exit 0). 격상 램프는 1dB/프레임이라 DANGER 전환 시점의 dangerStreak 가 0이다.
  - frame=022 rssi=-73 level=1 dangerStreak=0 warnStreak=2
  - frame=038 rssi=-57 level=1 dangerStreak=0 warnStreak=18
  - frame=039 rssi=-56 level=2 dangerStreak=0 warnStreak=19 (DANGER 전환)
  - frame=041 rssi=-54 level=2 dangerStreak=1 warnStreak=21
- **Fix:** 사용자 결정 A — 급접촉 서브 시나리오를 추가해 dangerStreak 가 실제로 쌓이는 구간을 만든다.
- **Files modified:** AlertCascadeGoldenTest.kt (5e0c68c)
- **Verification:** 급접촉 골든 동결 후 8/8 통과
- **Committed in:** `5e0c68c`

### 2. agent 보고 정정 2건

- **Found during:** Task 3, 1차 시도 보고 검증
- **Issue:** executor 보고가 (a) 이 시점까지의 커밋을 2건으로 표기했으나 실제 3건(`4bca296` 누락), (b) Task 1 을 "BleService.kt 계열"로 표기했으나 실측상 세 커밋 모두 테스트 파일만 건드렸고 프로덕션 diff 는 0.
- **Fix:** 오케스트레이터가 `git log` / `git diff --stat` 실측으로 정정. 이 SUMMARY 의 Task Commits 절이 정정본이다.
- **Files modified:** 없음(기록 정정)
- **Verification:** `git diff --stat 6760f60..bf03518` = 2 files changed, 454 insertions(+), 전부 app/src/test
- **Committed in:** 해당 없음

### 3. red-trial 2차 미검출 — 급접촉 진폭 과다로 pEma 단독 DANGER

- **Found during:** Task 3, 2차 시도
- **Issue:** 급접촉 진폭을 -95 -> -30(65dB 낙차)으로 잡자 `dangerStreak >= 2 -> 3` 도, 진단용 `>= 100` 도 미검출. executor 는 "dead-branch" 로 결론지었다.
- **Fix:** 오케스트레이터가 코드 계산으로 반증 — frame=006 의 pEma = -95 + (1 - 0.6^2) * 65 = -53.4 >= -55 이므로 pEma 단독으로 DANGER 가 성립해 오버라이드 분기가 무력화된다. 오버라이드 발화 창은 x1 in (-55, -32.5). 사용자 결정 A' 로 진폭을 -30 -> -54 로 재교정.
- **Files modified:** AlertCascadeGoldenTest.kt (bf03518)
- **Verification:** 재교정 후 8/8 통과, 급접촉 골든 재동결
- **Committed in:** `bf03518`

### 4. executor 프로세스 정지 — 프로덕션 트리가 섭동 상태로 방치

- **Found during:** Task 3, 3차 시도 진행 중
- **Issue:** executor `a307a8959da8ed1bd` 가 섭동 상태에서 4.5분 무활동. TaskStop 으로 종료(killed). dirty 2파일(BleService.kt:1885 섭동, AlertCascadeGoldenTest.kt 임시 `suddenContact_CAPTURE_temp` 8줄)이 잔존했다.
- **Fix:** 오케스트레이터가 `git checkout --` 로 2파일을 원복하고 3차 본실행을 직접 수행.
- **Files modified:** 없음(원복)
- **Verification:** `git status --porcelain -- app/` 공란 복귀
- **Committed in:** 해당 없음

### 5. red-trial 3차 미검출 — BleService.kt:1885 는 이 시나리오에서 도달 불가

- **Found during:** Task 3, 3차 시도
- **Issue:** 재교정된 급접촉 시나리오에서도 `:1885` 섭동이 미검출(EXIT=0, tests=8 failures=0, XML timestamp 02:14:51 갱신 확인 -> 원복 후 02:15:27).
- **Fix:** 임시 진단 테스트로 실측(실행 후 삭제, 커밋 없음). PROBE 결과 pEma == raw, lag = 0:
  - `PROBE effWarning=-75 effDanger=-55`
  - `PROBE frame=000..004 rssi=-95 deviceRssiMap=-95 alertLevel=null`
  - `PROBE frame=005 rssi=-54 deviceRssiMap=-54 alertLevel=null`
  - `PROBE frame=006..009 rssi=-54 deviceRssiMap=-54 alertLevel=2`

  원인: 워밍업 -95 가 보존 밴드 하한(-75 - FILTER_PRESERVE_BAND_DB 10 = -85) 밖이라 `BleService.kt:1706-1712` 가 매 프레임 모든 필터/streak 를 clear 한다. 그래서 frame=005 가 전 필터의 첫 샘플이 되어 pEma=-54 -> stableLevel 이 이미 DANGER -> `:1885` 진입조건 `stableLevel < LEVEL_DANGER` 가 거짓. 즉 이 표적은 어떤 진폭으로도 살릴 수 없다(결정 A' 의 전제 자체가 무효). 협력 격상(`:1870`, 하네스 remoteState 기본 0x00 -> rRisk=SAFE)과 UWB 경로(`:1923/1976/2016/2026`, 세션·실측 없음)도 배제했다.
- **Files modified:** 없음(임시 진단 파일 삭제)
- **Verification:** PROBE 실측 출력 + 원복 후 zero exit
- **Committed in:** 해당 없음

### 6. red-trial 4차 미검출 — BleService.kt:2326 은 논리적으로 포섭된 데드코드

- **Found during:** Task 3, 4차 시도
- **Issue:** 표적을 `:2326` `fastDangerContact` 의 `dangerStreak >= 2 -> >= 3` 으로 바꿔 섭동(1 file, +1 -1). 결과 EXIT=0, tests=8 failures=0 errors=0, XML 02:34:26 신규 -> 원복 후 02:35:05.
- **Fix:** 원인 규명 — inDangerRaw 는 inWarningRaw 의 부분집합이고 두 streak 가 동시에 누적되며 `isDepartingNow` 항도 동일하므로, `fastDangerContact` 가 참이면 `:2330` 의 warning 항도 항상 참이다. `fastDangerContact` 는 단락평가 선행 항일 뿐 동작에 기여하지 않는 데드코드다. 급접촉 frame=006 발령을 지배하는 단일 상수는 `BleService.kt:2330` 의 `warningStreak >= 2` 하나뿐임이 확정됐다.
- **Files modified:** 없음(원복)
- **Verification:** `git status --porcelain -- app/` 베이스라인 일치, 원복 후 8/0 zero exit
- **Committed in:** 해당 없음

### 7. 증거 오염 발견·수습 — stale JUnit XML

- **Found during:** Task 3, 4차 시도 착수 직전
- **Issue:** 3차 원복 실행의 XML(02:15:27)이 임시 진단 테스트 실행(02:25:37)에 덮여 소실. XML timestamp 를 실행 증거로 쓰는 절차가 오염될 수 있는 상태였다.
- **Fix:** stale `TmpPEmaProbeTest.xml` 을 삭제하고 4차를 클린 상태에서 시작. 이후 XML timestamp 는 전부 신규 생성분이다.
- **Files modified:** 없음(build 산출물 정리)
- **Verification:** 4차 XML 02:34:26 이 신규임을 확인
- **Committed in:** 해당 없음

### 8. 골든 테스트 주석 오기 미정정(보류)

- **Found during:** Task 3, 3차 원인 규명 후
- **Issue:** `AlertCascadeGoldenTest.kt:285-292`, `:604-610` 의 "pEma 약 -68.8", "frame=006 의 DANGER 는 :1885 단독 산물" 서술이 실측과 다르다(실제 pEma=-54, lag=0, `:1885` 는 도달 불가).
- **Fix:** 이번 plan 에서는 정정하지 않음. red-trial 재개 여부에 따라 최종 문안이 달라지므로 사용자 판단 대기 항목으로 남긴다.
- **Files modified:** 없음
- **Verification:** 해당 없음
- **Committed in:** 해당 없음

**Total deviations:** 8 (자동 수정 3, 원인 규명 후 정지 3, 기록 정정 1, 보류 1)
**Impact on plan:** Task 1·2 는 계획대로 완료. Task 3 은 acceptance_criteria 5건 중 3건(원복 후 zero exit, `git diff --exit-code -- app/src/main` 무변경, 섭동 상수명·원래값·섭동값 기록)을 충족했고 2건(non-zero exit 콘솔 증거, 4요소 실패 메시지)은 미충족이다. 미충족의 원인은 골든의 결함이 아니라 표적 상수 4개가 전부 no-op 이었다는 점이며, 유효 표적(`BleService.kt:2330`)이 특정된 상태로 사용자 승인 대기다.

## Issues Encountered

- **레드 트라이얼 회귀 검출력 미증명.** 골든이 프로덕션 상수 섭동을 실제로 잡는다는 증명이 아직 없다. 유효 표적은 `BleService.kt:2330` 의 `warningStreak >= 2` 하나로 특정됐고, 5차 시도는 사용자 지시 7항에 따라 승인 대기로 정지 상태다.
- **부수 발견(별건).** `BleService.kt:2326` 의 `fastDangerContact` 는 동작 변화가 0인 데드코드다. 프로덕션 정리 후보이나 이 plan 의 범위 밖이다(prohibitions: 신규 기능 추가·로직 이동 금지).

## User Setup Required

없음.

## Next Phase Readiness

- 02-03 진행 가능. 격상/해제/급접촉 골든 3종이 동결됐고 8/8 통과 상태, 프로덕션 diff 0, app/ working tree clean.
- red-trial 재개 조건: 사용자가 `BleService.kt:2330` 섭동을 승인하면 5차를 즉시 실행할 수 있다(절차·베이스라인 그대로 재사용).
- 골든 주석 정정(Deviation 8)은 red-trial 종결 후 일괄 처리를 권장한다.

## Threat Flags

없음. 프로덕션 코드 변경 0, 신규 의존성 0, 판정 반경·UWB·iBeacon·Firebase 관련 prohibitions 12건 전부 미저촉.

---
*Phase: 02-golden*
*Completed: 2026-08-28*

## Self-Check: PARTIAL

- FOUND: 격상 골든 동결 (escalation_goldenTimeline)
- FOUND: 해제 골든 동결 (release_goldenTimeline)
- FOUND: 급접촉 서브 시나리오 골든 동결 (suddenContact_dangerOverride_bypassesPEmaLag)
- FOUND: 재생 결정성 단언 (sameSequence_replaysIdentically)
- FOUND: entry 는 T0_MS 상대 ms 로 렌더 (renderFrame)
- FOUND: 매 프레임 관측면 전량 직렬화 (renderFrame 고정폭 1줄)
- FOUND: 프로덕션 코드 무변경 (git diff --exit-code -- app/src/main)
- NOT FOUND: 표적 상수 섭동 시 골든 실패 (red-trial 4회 미검출, 원인 규명 완료·유효 표적 특정·사용자 승인 대기)
