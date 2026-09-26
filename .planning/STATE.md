---
gsd_state_version: 1.0
milestone: v1.1.70
current_phase: 04
current_phase_name: 기기 상태 단일화
status: planned
stopped_at: 04-state-01-PLAN.md 수립 완료 (STATE-01 미착수)
last_updated: "2026-09-26T00:00:00.000Z"
last_activity: 2026-09-26
last_activity_desc: Phase 04 계획 수립 - STATE-01 규모 v1.1.96 기준 685 사이트 실측, 실행 시점 2경로 제시
state_head: 0de98dade218467c210f18b6181619f2cb574ff6
progress:
  total_phases: 5
  completed_phases: 3
  total_plans: 8
  completed_plans: 7
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-08-23)

**Core value:** BLE RSSI 근접 판정이 같은 상황에서 같은 결과를 낸다. 경보가 떠야 할 때 뜨고, 꺼져야 할 때 꺼지며, 한 번 고친 증상이 다시 돌아오지 않는다.
**Current focus:** Phase 04 T1~T3 완료(STATE-02·STATE-03·BUG-01 = Complete). 잔여 = STATE-01 1건 — 04-01-PLAN.md 수립됨, 실행 시점 사용자 판단 대기

## Current Position

Phase: 04 (기기 상태 단일화) — PARTIAL (요구사항 3/4 Complete)
Plan: 04-01-PLAN.md 수립 완료, 미실행
Status: STATE-01 실행 시점 사용자 판단 대기 (경로 A = Phase 5 흡수 권장 / 경로 B = 독립 완료)
Last activity: 2026-09-26 — Phase 04 계획 수립, STATE-01 규모 실측
Prior activity: 2026-09-14 — quick 260914-lqs: context-budget 실측으로 CLAUDE.md 읽기 금지 표 갱신 (커밋 ada5825)

**STATE-01 실측 (v1.1.96 / versionCode 152 기준, 2026-09-26):**

| 구분 | Map | 접근 사이트 |
|---|---|---|
| AlertStateMachine.kt | 37 | 447 |
| BleService.kt | 5 | 153 |
| UwbDistanceManager.kt | 3 | 5 |
| 기타 main | — | 9 |
| 테스트 (골든·회귀 5파일 60 포함) | — | 71 |
| **합계** | **45** | **685** |

v1.1.72 시점 실측(37 Map / 620)과 비교하면 24개 버전 동안 Map 6개(`trend*` 5 + `approachLastSeenMap`)가 늘었다.
**등록 누락은 0건** — 신규 Map 전부가 `DeviceStateRegistry` 슬롯에 등록됐고, 슬롯은 43 → 49 로 따라 늘었다.
`uwbProbeLastSaveMap` 1건만 미등록이나 키가 `pairKey`(역할쌍, 유한 카디널리티)라 좀비 대상이 아니다.
현 체계가 규율을 유지하고 있다는 실증이며, STATE-01 의 컴파일 타임 강제는 여전히 이론적 이득이다.

Progress: [███████░░░] 70%

## Performance Metrics

**Velocity:**

- Total plans completed: 7
- Average duration: —
- Total execution time: —

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| - | - | - | - |

**Recent Trend:**

- Last 5 plans: —
- Trend: —

*Updated after each plan completion*
**Per-Plan Metrics:**

| Plan | Duration | Tasks | Files |
|------|----------|-------|-------|
| Phase 01-ci P01 | 26min | 3 tasks | 4 files |
| Phase 02-golden P01 | 61min | 3 tasks | 4 files |
| Phase 02-golden P03 | 29min | 3 tasks | 1 files |
| Phase 02-golden P04 | 50min | 3 tasks | 4 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [Roadmap]: 테스트(TEST-01~04) + CI 게이트(CI-01/02)를 분해보다 앞선 Phase 1~2 로 배치. 유닛·통합 테스트 0건 상태에서 안전 크리티컬 로직 분해 금지가 PROJECT.md Active 항목의 명시 선행 조건
- [Roadmap]: REFACTOR-04 를 Phase 3 의 수용 게이트로 편입(별도 후속 Phase 아님). 기대값이 하나라도 바뀌면 분해 미통과로 판정
- [Roadmap]: PERF-01 을 REFACTOR-01 과 분리해 Phase 5 단독 배치. 동시성 변경을 다른 변경과 섞으면 현장 회귀의 원인 귀속이 불가능
- [Roadmap]: BUG-02 를 Phase 2 에 편입. 현장에 나가 있는 미탐지이며, 여기서 고쳐야 REFACTOR-04 가 버그가 아닌 올바른 동작을 보존
- [Roadmap]: BUG-03(UWB 6대 초과 플립)은 v2 이월. TEST-02 를 6대 이하로 한정한 이유가 이것
- [Phase 01]: Task 2 골든 8테스트는 shared-helper(runCascade/assertCascade)로 DRY 유지 — grep 리터럴카운트 휴리스틱보다 명시적 <action> 지시 우선
- [Phase 01]: KalmanFilter는 기기별 별도 인스턴스(공유맵 없음)이므로 D-07 격리 테스트 범위에서 제외 — MedianFilter/RssiPreFilter만 검증
- [Phase 01]: 격리 테스트는 record-then-freeze 대신 관계형(soloBaseline 일치) 기대값 사용 — 비간섭 속성은 관계로 정의되므로
- [Phase 01]: Task 1 precondition('작업 트리가 깨끗해야 한다')을 baseline-porcelain-equivalence로 해석 — 범위 밖 14건 기존 미커밋 항목 존재로 문자 그대로의 공백 불가능, git status --porcelain 베이스라인 일치로 판정
- [Phase 01]: 01-02 Task 1 레드 트라이얼 2건(Kalman 1e-6/Median +1) non-zero exit + D-19 4요소 메시지 확인, D-21 문서 재확인 결과 REQUIREMENTS.md/ROADMAP.md 편집 불필요
- [Phase 01]: 01-ci-02 Task 3 착수 전 release.yml Firebase 갱신 스텝에 citest 태그 가드(1줄) 선추가 — Rule 2 편차, 검증 태그가 프로덕션 자동업데이트 포인터를 오염시키지 않도록 함
- [Phase 02]: Robolectric 4.15.1(testImplementation) 사람 승인 채택 — 좌표/저장소/스코프 확인
- [Phase 02]: 골든 스모크 '1프레임'을 '2프레임 연속(마지막 콜 관측)'으로 재해석 — production 게이트(streak/warmingUp) 수학적 제약
- [Phase 02]: 골든 DevSettings 프로파일 30줄 명시 대입, kalmanPreset=KALMAN_PRESET_NORMAL 명시 고정(출하 기본과 동일)
- [Phase 02]: onDeviceLost 는 이 테스트 스위트 전체에서 '그 외'(else) 분기(dropServedLocked+reconcileLocked)만 타며, uwbManager==null 로 reconcileLocked 가 즉시 반환되어 coroutine 진입 없음을 확인(T-02-11)
- [Phase 02]: BLE 타임아웃 UWB 정리 목록(peerUwbSeenMap/uwbSampleAtMsMap/uwbSafeStreakMap + onDeviceLost)을 BleService.kt 직독으로 확정 후 재현(replicateBleTimeoutBoundary)
- [Phase 02]: 세션 상한 초과 경로는 golden 으로 얼리지 않고 require() 거부만 증명 — BUG-03 을 스펙으로 승격시키지 않음(v2 이월)
- [Phase 02]: D-3B: PROJECT.md의 injectWarmup 프리셋 포화 가설은 코드와 불일치 — 실측 근본 원인은 WARNING 접촉 streak의 단발 미달 즉시 하드리셋
- [Phase 02]: DANGER streak는 수정하지 않음 — effDanger ⊂ effWarning이라 WARNING 완화만으로 BUG-02 목표 달성
- [Phase 02]: 현장 확인 4항목은 Phase 완료 차단 게이트가 아님 — REQUIREMENTS.md BUG-02 각주로 인계, 미수행 상태 명시

### Pending Todos

None yet.

### Blockers/Concerns

- **테스트 시임 확보 난이도**: TEST-01/02 는 `processAlert`(BleService.kt:1406-2554, 약 1,000줄)와 UWB 세션 상태머신을 JVM 에서 재생해야 성립한다(TEST-04). 분해 전에 테스트를 쓰려면 동작을 바꾸지 않는 최소한의 기계적 시임만 허용되며, 로직 이동은 Phase 3 의 몫이다. Phase 1~2 계획 시 이 경계를 명시할 것
- **실기 검증 사이클**: 회귀 확인 수단이 사용자 현장 가용 시간에 묶여 있다. 모든 Phase 는 출하 가능·현장 검증 가능 상태로 끝나야 하며, 반쯤 분해된 검증 불가 상태로 오래 머무는 것이 실패 모드
- **`androidx.core.uwb:1.0.0-alpha09` 프리릴리스**: 경보 로직의 30~40% 가 의존. API 파괴 변경 리스크 상존 (REFACTOR-02 / TEST-02 에 직접 영향)
- **BLE 페이로드 호환성**: 1바이트 비트팩 레이아웃은 현장 배포된 구버전 기기와 통신해야 하므로 변경 불가
- 01-ci-02 Task 2 체크포인트(gate=blocking-human) 정지 중: 프로덕션 diff(KalmanFilter.kt 단일 시임, 945c729..HEAD 기준 1파일/9삽입/6삭제) 사람 승인 대기. 승인 시 Task 3(실제 태그 push 종단 실증)로 진행 가능
- 01-ci-02 Task 3 (checkpoint:human-verify gate=blocking-human): CI-01/CI-02 종단 실증 증거(그린 run 32700966688, 레드 run 32701255911) 수집 완료, 사람의 '승인' 입력 대기 중. 승인 전까지 01-ci 는 완료 처리되지 않음. 상세는 .planning/phases/01-ci/01-02-SUMMARY.md CHECKPOINT REACHED 섹션

## Quick Tasks Completed

| Date | Task | Status |
|------|------|--------|
| 2026-09-10 | 260910-se6 — v1.1.84 익명 로그인 도입 + echo_calib 앱 버전 필드 (DB 규칙 잠금 1단계) | complete |
| 2026-09-10 | 260910-vw5 — v1.1.85 echo_calib 전역 경로 이동 (사업장 분할 해제) | complete |
| 2026-09-11 | 260911-k7r — v1.1.87 릴리스 서명 + 업데이트 APK 해시 + 표시 이름 15바이트 (Phase B, abd158a) | complete |
| 2026-09-12 | 260912-32e — v1.1.88 규칙 잠금(Phase D) + 첫 실행 변경 사항 창 + 다운로드 완료 수신기 EXPORTED + 캐시 APK 재사용 (0de98da, 태그 v1.1.88) | complete |
| 2026-09-14 | 260914-lqs — context-budget 실측으로 CLAUDE.md 읽기 금지 표 갱신 (ada5825) | complete |

## Deferred Items

Items acknowledged and deferred at milestone close, most recent first:

| Category | Item | Status | Deferred At | Milestone |
|----------|------|--------|-------------|-----------|
| Bug | BUG-03 — UWB 세션 6대 초과 플립으로 인한 사이렌 1~2초 끊김 | v2 이월 | 2026-08-24 (요구사항 정의) | v1 |

## Session Continuity

Last session: 2026-08-28T06:15:46.521Z
Stopped at: Completed 02-golden-04-PLAN.md
Resume file: None
