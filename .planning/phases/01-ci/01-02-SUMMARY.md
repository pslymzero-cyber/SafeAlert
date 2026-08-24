---
phase: 01-ci
plan: 02
subsystem: testing
tags: [kotlin, gradle, junit, ci, jvm-test, github-actions]

# Dependency graph
requires:
  - phase: 01-ci (01-01)
    provides: RssiCascadeTest.kt 골든 테스트 하네스, release.yml 의 Run unit tests / upload-artifact 배선
provides:
  - "로컬 레드 트라이얼 2건: Kalman(1e-6)·Median(+1) 기대값 변조가 각각 non-zero exit + D-19 4요소 실패 메시지를 유발함을 실증"
  - "그린 베이스라인 재확인: --rerun-tasks 로 캐시 우회, 실제 실행 확인"
  - "프로덕션 diff 감사 자료 준비 완료 (KalmanFilter.kt 단일 시임, 1파일/9삽입/6삭제) — 사람 승인 대기 중"
  - "D-21 문서 정정 상태 재확인 — REQUIREMENTS.md/ROADMAP.md 모두 이미 정정된 문구로 확인, 추가 편집 불필요"
affects: ["01-ci Task 2/3 계속 실행", "CI-01/CI-02 요구사항 최종 충족 판정"]

# Actuals (#2632)
actuals:
  tokens: 0   # RssiCascadeTest.kt 변조는 전부 원복(P-08) — net diff 0. 코드 커밋 없음
  tasks: 1    # Task 1만 완료. Task 2는 체크포인트에서 정지, Task 3 미시작
  commits: 0  # 코드 커밋 없음 (Task 1 net diff 0). 이 SUMMARY 자체의 메타데이터 커밋은 별도

tech-stack:
  added: []
  patterns:
    - "레드 트라이얼 프로토콜: 기대값 1개 변조 → gradle 테스트 실행(non-zero 확인) → 리포트 산출물 존재 확인 → 즉시 git checkout 원복 → porcelain 베이스라인 일치 확인. 다음 변조로 넘어가기 전 원복 완결을 반드시 확인(P-08)"

key-files:
  created: []
  modified: []

key-decisions:
  - "Task 1 precondition '작업 트리가 깨끗해야 한다'를 문자 그대로의 완전 공백이 아니라 baseline-porcelain-equivalence로 해석: 실행 전 `git status --porcelain` 을 베이스라인으로 캡처하고, 각 변조/원복 사이클 후 porcelain 출력이 그 베이스라인과 byte-identical 한지로 판정. 리포지토리에는 이 플랜과 무관한 14건의 기존 미커밋 항목(.gitignore, .planning/config.json 수정 2건 + .gsd/, .planning/graphs/ 등 미추적 12건)이 있어 문자 그대로의 '출력 없음'은 이 플랜의 범위 밖 항목까지 스테이징/커밋/원복하지 않고서는 만족 불가능했음"
  - "Task 1 sub-step (4) D-21 문서 재확인 결과: REQUIREMENTS.md TEST-03 문구(line 16)와 ROADMAP.md Phase 1 성공기준 2번·출하상태 문구가 이미 'MedianFilter(3샘플) → RssiPreFilter → KalmanFilter' 정정된 캐스케이드 순서로 기록되어 있음을 grep으로 확인. 두 파일 모두 편집 불필요 — 순수 검증 통과"
  - "Task 1은 변조→확인→원복이 한 작업 단위(P-08)이므로 최종 net diff가 0. RssiCascadeTest.kt에 대한 개별 task 커밋을 생성하지 않음 — 증빙은 이 SUMMARY의 레드 트라이얼 콘솔 출력 원문으로 대체"
  - "Task 2 diff 감사의 기준 커밋으로 01-01-SUMMARY.md D6 항목이 사용한 945c729(v1.1.70 태그 커밋, phase 01-ci 기획 문서 이전 지점)를 채택 — 0f1fd50^(830a20d, 기획 문서 커밋 이후·Task1 실행 직전)이 아닌 phase 진짜 시작점을 사용해 01-01 선례와 일관성 유지. 두 후보 커밋의 관계를 git merge-base --is-ancestor 로 확인"

patterns-established: []

requirements-completed: []  # CI-01/CI-02는 Task 2(사람 승인)·Task 3(실사 태그 push)까지 완료되어야 충족 — 이 SUMMARY 시점에서는 미완료

coverage:
  - id: D1
    description: "레드 트라이얼 2건 — Kalman 기대값 1e-6 변조와 Median 기대값 +1 변조가 각각 non-zero exit + D-19 4요소(scenario/startState/frame=/stage=) 실패 메시지를 유발하고, 실패 시에도 테스트 리포트 산출물이 생성되며, 원복 후 작업 트리가 베이스라인과 완전히 일치함"
    requirement: "CI-01"
    verification:
      - kind: unit
        ref: "./gradlew testDebugUnitTest (Kalman 1e-6 변조 실행) — approach_coldStart_matchesGolden"
        status: pass
      - kind: unit
        ref: "./gradlew testDebugUnitTest (Median +1 변조 실행) — approach_coldStart_matchesGolden, approach_warmStart_matchesGolden"
        status: pass
    human_judgment: false
  - id: D2
    description: "프로덕션 diff 감사 — KalmanFilter.kt 가 nowMs 시임 1건뿐이며 APK 동작이 불변임을 사람이 승인(D-04/D-03)"
    requirement: "CI-02"
    verification: []
    human_judgment: true
    rationale: "실기기 스모크 테스트가 불가능(ADB 0대 연결, P-09)하므로 판단 근거는 diff 리뷰뿐이다. 사람이 직접 4개 확인 항목(파일 범위·시임 범위·상수 불변·호출부 불변)에 대해 승인해야 하며, 이는 자동화로 대체할 수 없는 gate=blocking-human 체크포인트다"
  - id: D3
    description: "GitHub Actions 실제 태그 push 기반 그린·레드 종단 실증 (CI-01 CI 계층 차단 확인, CI-02 아티팩트 기반 진단 확인, D-16 복구 절차 기록)"
    verification: []
    human_judgment: true
    rationale: "Task 3는 시작되지 않았다. 실제 태그 push는 릴리즈 파이프라인을 건드리는 되돌리기 비싼 행위이며, 태그 전략(a/b) 선택부터 사람의 명시적 결정이 필요하다(A-TAG-TRIGGER, gate=blocking-human)"

duration: ~1h (context compaction으로 정확한 시작 시각 유실 — STATE.md last_updated 04:36:08Z 대비 완료 시각 05:19:51Z 기준 근사치)
completed: 2026-08-24
status: halted
---

# Phase 01 Plan 2: 테스트 하네스와 CI 회귀 게이트 — 레드 트라이얼 실증 (Task 1 완료, Task 2 체크포인트 정지) Summary

**로컬 레드 트라이얼로 게이트가 실제로 막는다는 것을 실패 경로로 증명하고(Kalman 1e-6/Median +1 변조 각각 non-zero exit + D-19 4요소 메시지), 프로덕션 diff(KalmanFilter.kt 단일 시임)를 감사 준비까지 마친 뒤 사람 승인이 필요한 체크포인트에서 정지**

## Performance

- **Duration:** ~1h (근사치, 세션 압축으로 정확한 시작 시각 유실)
- **Completed (Task 1):** 2026-08-24T05:19:51Z 경
- **Tasks:** 1/3 완료 (Task 2 체크포인트 정지, Task 3 미시작)
- **Files modified (committed):** 0 (Task 1 net diff 0 — P-08)

## Accomplishments
- 그린 베이스라인 재확인: `./gradlew testDebugUnitTest --no-daemon --console=plain --rerun-tasks` exit 0, `app/build/reports/tests/testDebugUnitTest/index.html` 및 `TEST-*.xml` 2건(RssiCascadeTest, RssiCascadeIsolationTest) 파일시스템에서 실존 확인
- Kalman 레드 트라이얼: `EXPECTED_APPROACH_COLD_KALMAN[10]` 을 `-83.44452861835482` → `-83.44452761835482` (1e-6) 로 변조 → exit 1 → 실패 메시지에 `frame=10 stage=kalman` 포함 확인 → 리포트 HTML 실패 후에도 존재 확인 → 원복 → porcelain 베이스라인 일치 확인
- Median 레드 트라이얼: `EXPECTED_APPROACH_COLD_MEDIAN[10]` 을 `-77` → `-76` (+1) 로 변조 → exit 1 → 실패 메시지에 `frame=10 stage=median` 포함 확인(coldStart·warmStart 둘 다 실패 — `EXPECTED_APPROACH_WARM_MEDIAN` 이 COLD 배열의 별칭이므로 예상된 부수 효과) → 리포트 HTML 실패 후에도 존재 확인 → 원복 → porcelain 베이스라인 일치 확인
- 원복 후 최종 그린 재확인: exit 0
- D-21 문서 재확인: `grep -c 'MedianFilter(3샘플) → RssiPreFilter → KalmanFilter' .planning/REQUIREMENTS.md` == 1 확인, ROADMAP.md 도 이미 정정된 문구 — 편집 불필요
- Task 2 체크포인트용 프로덕션 diff 3종(full/--stat/--name-only) 준비 완료, 아래 CHECKPOINT REACHED 섹션에 원문 포함

## Task Commits

- **Task 1: 레드 트라이얼** — 커밋 없음. P-08(변조→확인→즉시 원복이 한 작업 단위)에 따라 최종 net diff가 0이므로 스테이징할 코드 변경이 없음. 증빙은 본 SUMMARY의 콘솔 출력 원문(위 Accomplishments) 및 아래 Deviations 섹션
- **Task 2: 프로덕션 diff 감사** — 체크포인트(`gate="blocking-human"`)에서 정지. 사람 승인 대기 중이므로 커밋 없음
- **Task 3: GitHub Actions 종단 실증** — 미시작

**Plan metadata:** (이 SUMMARY.md + STATE.md + ROADMAP.md 커밋 예정, 아래 self-check 이후)

## Files Created/Modified
- (코드 파일 변경 없음 — Task 1은 변조 후 완전 원복, net diff 0)
- `.planning/phases/01-ci/01-02-SUMMARY.md` - 본 문서 (신규)

## Decisions Made
- Task 1 precondition("작업 트리가 깨끗해야 한다")을 baseline-porcelain-equivalence로 해석 — frontmatter key-decisions 참조
- D-21 문서 재확인 결과 REQUIREMENTS.md/ROADMAP.md 편집 불필요 — frontmatter key-decisions 참조
- Task 2 diff 기준 커밋으로 945c729 채택(01-01 선례와 일관성 유지) — frontmatter key-decisions 참조

## Deviations from Plan

### Auto-fixed Issues

None — Rule 1/2/3에 해당하는 버그·누락 기능·차단 이슈는 발견되지 않았음.

### Interpretation Deviations (문서화 필요, 코드 변경 아님)

**1. Precondition 해석 — baseline-porcelain-equivalence**
- **Found during:** Task 1 진입 전 precondition 점검
- **Issue:** 리포지토리에 이 플랜과 무관한 14건의 기존 미커밋 항목(`.gitignore`, `.planning/config.json` 수정 2건 + `.gsd/`, `.planning/graphs/`, `.planning/milestone.lock`, `.planning/research/`, `SafeAlert_source.txt`, 한글 문서 4건, `epj.png`, `보행자.png`, `장비.png` 등 미추적 12건)이 이미 존재하여, "`git status --porcelain` 출력 없음"을 문자 그대로 만족시키려면 이 플랜의 범위 밖 항목까지 건드려야 함
- **Fix:** 실행 전 `git status --porcelain` 을 스크래치패드에 베이스라인으로 캡처하고, Task 1의 변조/원복 사이클마다 porcelain 출력이 그 베이스라인과 byte-identical 한지를 판정 기준으로 채택. 범위 밖 14건은 스테이징·커밋·원복·정리 전혀 하지 않음
- **Files modified:** 없음 (판정 기준 해석 변경일 뿐, 코드/설정 변경 없음)
- **Verification:** `diff baseline.txt post_kalman_revert.txt` 및 `diff baseline.txt post_median_revert.txt` 둘 다 무출력(일치) 확인

---

**Total deviations:** 1 해석 편차 (코드 변경 아님, Rule 1-4 어느 것도 트리거하지 않음 — precondition 문구의 합리적 해석 문제)
**Impact on plan:** Task 1의 P-08 원복 완결성 판정에 영향, 계획의 실질적 목표(레드 트라이얼 증명)에는 영향 없음

## Issues Encountered
None — 모든 단계가 예상대로 동작함. ADB 기기 0대 연결 상태는 사전에 알려진 조건이며 Task 1/Task 2 어느 것도 실기기 스모크 테스트를 요구하지 않음(P-09).

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness

**정지 지점:** Task 2 (`gate="blocking-human"` 체크포인트) — 아래 CHECKPOINT REACHED 섹션 참조.

이 플랜은 완료되지 않았다(`status: halted`). Task 2의 사람 승인("승인" 입력 또는 불일치 항목 지적) 없이는 Task 3로 진행할 수 없으며, Task 3(실제 태그 push 기반 CI 종단 실증) 완료 없이는 CI-01/CI-02 요구사항이 충족 처리되지 않는다. 이 halted SUMMARY에 의존하는 후속 계획은 이 플랜이 `status: complete` 로 재작성되기 전까지 차단된 것으로 보고되어야 한다.

---
*Phase: 01-ci*
*Halted at Task 2 checkpoint: 2026-08-24*
