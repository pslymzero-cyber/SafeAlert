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
  - "로컬 레드 트라이얼 2건: Kalman(1e-6)·Median(+1) 기대값 변조가 각각 non-zero exit + D-19 4요소 실패 메시지를 유발함을 실증(Task 1)"
  - "프로덕션 diff 감사(KalmanFilter.kt 단일 시임) — 사람이 Task 2 체크포인트에서 승인 완료"
  - "release.yml 에 citest 태그용 Firebase 갱신 가드 추가 — 검증 태그가 프로덕션 자동업데이트 포인터를 오염시키지 않음(Task 3 사전조치, 배포됨)"
  - "실제 GitHub Actions 태그 push 2건(그린 v0.0.1-citest1 / 레드 v0.0.1-citest2)으로 CI-01(게이트 위치·차단)·CI-02(아티팩트 기반 진단) 종단 실증 완료 — 사람 최종 승인 대기 중(Task 3 체크포인트)"
  - "D-21 문서 정정 상태 재확인 — REQUIREMENTS.md/ROADMAP.md 모두 이미 정정된 문구로 확인, 추가 편집 불필요"
affects: ["01-ci 완료 여부", "CI-01/CI-02 요구사항 최종 충족 판정", "이 SUMMARY 를 requires 하는 모든 후속 phase"]

# Actuals (#2632)
actuals:
  tokens: 20   # 실질 net 코드 변경은 release.yml 1줄 추가(Firebase 가드)뿐. RssiCascadeTest.kt 는 변조→커밋→원복→커밋으로 최종 net diff 0(git diff e41a97d..HEAD 확인, 무출력)
  tasks: 3     # Task 1/2/3 모두 실행 완료. Task 3 는 checkpoint:human-verify 로, 증거 수집까지가 실행 범위이고 최종 승인만 남음
  commits: 3   # e41a97d(Firebase 가드), 3ee1f4a(레드 변조), 9068696(원복) — 문서/메타데이터 커밋 제외

tech-stack:
  added: []
  patterns:
    - "레드 트라이얼 프로토콜(로컬, Task 1): 기대값 1개 변조 → gradle 테스트 실행(non-zero 확인) → 리포트 산출물 존재 확인 → 즉시 git checkout 원복 → porcelain 베이스라인 일치 확인"
    - "레드 트라이얼 프로토콜(CI, Task 3): 로컬과 동일한 변조를 이번엔 커밋해 실제 태그(v0.0.1-citest2)로 push → Actions 레드 실행 관찰 → 원인 규명 → 원복 커밋으로 되돌림(net diff 0, 커밋 이력엔 두 커밋 모두 남음 — 로컬 트라이얼과 달리 실제 커밋 쌍으로 실행)"
    - "검증 전용 태그 전략(b): v0.0.1-citest1(그린)/v0.0.1-citest2(레드) 를 실제 배포 태그와 분리해 사용, 릴리즈 이력엔 임시 항목 허용(T-01-11 accept), 정리는 사람이 수동으로 수행(D-16/P-07)"

key-files:
  created: []
  modified:
    - ".github/workflows/release.yml — Update Firebase Realtime DB 스텝에 `if: ${{ !contains(github.ref_name, 'citest') }}` 가드 1줄 추가(영구 반영, 프로덕션 자동업데이트 오염 방지)"
    - "app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt — CI 레드 트라이얼용 1e-6 변조 커밋 후 즉시 원복 커밋(net diff 0, e41a97d 기준 재확인 완료)"

key-decisions:
  - "Task 1 precondition '작업 트리가 깨끗해야 한다'를 baseline-porcelain-equivalence로 해석(Task 1 시점 결정, 유지)"
  - "Task 2 diff 감사 기준 커밋으로 945c729 채택(01-01 선례와 일관성 유지, Task 1 시점 결정, 유지)"
  - "Task 2 체크포인트(프로덕션 diff 감사, gate=blocking-human)를 사람이 승인함 — Task 3(실제 태그 push)로 진행 허가"
  - "Task 3 착수 전 필수 선조치로 release.yml 에 Firebase 갱신 가드 1줄을 추가하고 단독 커밋(e41a97d)함 — 계획이 사전 공개한 비용은 '임시 릴리즈 이력이 남는다'뿐이었고, 프로덕션 Firebase 버전 포인터(wf11/version.json, 앱의 실사용자 자동업데이트 판단 근거)가 검증용 태그로 잠깐이라도 덮어써지는 것은 계획에 명시되지 않은 별도 비용이므로 Rule 2(누락된 필수 안전장치) 편차로 자동 추가함. 다른 어떤 release.yml 스텝도 건드리지 않음 — `git diff e41a97d^ e41a97d -- .github/workflows/release.yml` 로 1줄 추가만 확인"
  - "태그 전략은 (b) 검증 전용 태그(v0.0.1-citest1/v0.0.1-citest2) 채택 — 스폰 시점에 이미 결정된 사항, 이 SUMMARY 는 실행 결과만 기록"
  - "CI 레드 트라이얼은 Task 1 의 로컬 기법(EXPECTED_APPROACH_COLD_KALMAN[10] 을 1e-6 이동)을 재사용하되, 로컬 트라이얼과 달리 실제로 커밋(3ee1f4a)해 태그가 진짜 실패 커밋을 가리키게 한 뒤, 증거 수집 완료 후 별도 원복 커밋(9068696)으로 되돌림 — `git diff e41a97d..HEAD -- app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` 무출력으로 net diff 0 재확인"
  - "태그·GitHub Release 삭제는 이 세션에서 수행하지 않음 — 사람이 수동으로 정리(D-16/P-07, 스폰 시점 제약)"

patterns-established: []

requirements-completed: []  # CI-01/CI-02 는 이 SUMMARY 의 CHECKPOINT REACHED 섹션에 대한 사람의 최종 "승인" 이후 완료 처리 — 이 시점에는 아직 미완료

coverage:
  - id: D1
    description: "레드 트라이얼 2건(로컬) — Kalman 기대값 1e-6 변조와 Median 기대값 +1 변조가 각각 non-zero exit + D-19 4요소(scenario/startState/frame=/stage=) 실패 메시지를 유발하고, 실패 시에도 테스트 리포트 산출물이 생성되며, 원복 후 작업 트리가 베이스라인과 완전히 일치함"
    requirement: "CI-01"
    verification:
      - kind: unit
        ref: "./gradlew testDebugUnitTest (로컬 Kalman 1e-6 변조 실행) — approach_coldStart_matchesGolden"
        status: pass
      - kind: unit
        ref: "./gradlew testDebugUnitTest (로컬 Median +1 변조 실행) — approach_coldStart_matchesGolden, approach_warmStart_matchesGolden"
        status: pass
    human_judgment: false
  - id: D2
    description: "프로덕션 diff 감사 — KalmanFilter.kt 가 nowMs 시임 1건뿐이며 APK 동작이 불변임을 사람이 승인(D-04/D-03). 이 체크포인트는 이미 사람이 승인 완료함"
    requirement: "CI-02"
    verification: []
    human_judgment: true
    rationale: "실기기 스모크 테스트가 불가능(ADB 0대 연결, P-09)하므로 판단 근거는 diff 리뷰뿐이었다. 사람이 4개 확인 항목(파일 범위·시임 범위·상수 불변·호출부 불변)을 직접 검토·승인함 — 자동화로 대체 불가능한 gate=blocking-human 체크포인트였고 이미 통과함"
  - id: D3
    description: "GitHub Actions 실제 태그 push 기반 그린(v0.0.1-citest1)·레드(v0.0.1-citest2) 종단 실증 완료 — CI-01(스텝 순서·차단 위치)·CI-02(아티팩트만으로 진단) 증거 수집 완료. 아래 CHECKPOINT REACHED 섹션에 전체 증거 기록. 사람의 최종 승인만 남음"
    requirement: "CI-01, CI-02"
    verification:
      - kind: e2e
        ref: "GitHub Actions run 32700966688 (그린, tag v0.0.1-citest1) — 13개 스텝 전부 success, Update Firebase Realtime DB 만 skipped(가드 정상 동작)"
        status: pass
      - kind: e2e
        ref: "GitHub Actions run 32701255911 (레드, tag v0.0.1-citest2) — Run unit tests 만 failure, 이후 5개 스텝 전부 skipped, GitHub Release 미생성(gh release view v0.0.1-citest2 → release not found)"
        status: pass
    human_judgment: true
    rationale: "실제 태그 push 는 릴리즈 파이프라인을 건드리는 되돌리기 비싼 행위이며 plan 이 명시적으로 gate=blocking-human 으로 지정함(auto 모드에서도 자동승인 금지). 증거는 모두 수집·검증되었으나 CI-01/CI-02 를 요구사항 충족으로 최종 확정하는 것은 사람의 판단이다"

duration: ~2h (Task 1/2 는 이전 세션, Task 3 는 이번 세션 — 세션 압축으로 정확한 누적 시각 유실, STATE.md 세션 타임스탬프 대비 근사치)
completed: 2026-08-24
status: halted
---

# Phase 01 Plan 2: 테스트 하네스와 CI 회귀 게이트 — CI 종단 실증 완료, 최종 승인 대기 Summary

**로컬 레드 트라이얼(Task 1)과 프로덕션 diff 감사(Task 2, 승인됨)에 이어, 실제 GitHub Actions 태그 push 2건(그린/레드)으로 CI-01 게이트 차단 위치와 CI-02 아티팩트 기반 진단 가능성을 실증 — Firebase 갱신 가드를 선행 배포한 뒤 진행했고, 최종 승인만 남은 상태로 정지**

## Performance

- **Duration:** ~2h (근사치)
- **Completed (Task 1/2):** 2026-08-24 (이전 세션)
- **Completed (Task 3 증거 수집):** 2026-08-24T07:26:00Z 경 (레드 실행 07:24:49Z 실패 로그 기준)
- **Tasks:** 3/3 실행 완료 (Task 3 는 checkpoint 자체가 done 조건 — 증거 수집까지 완료, 최종 "승인"만 대기)
- **Files modified (committed):** 2 (release.yml 순net, RssiCascadeTest.kt net 0)

## Accomplishments

**Task 1 (이전 세션, 요약 유지):**
- 그린 베이스라인 재확인, Kalman/Median 로컬 레드 트라이얼 2건 각각 non-zero exit + D-19 4요소 실패 메시지 확인, 원복 후 porcelain 베이스라인 일치 확인
- D-21 문서 재확인 — REQUIREMENTS.md/ROADMAP.md 편집 불필요

**Task 2 (이전 세션, 요약 유지):**
- 프로덕션 diff(KalmanFilter.kt nowMs 시임) 감사 자료를 사람에게 제시 → 승인 획득

**Task 3 (이번 세션):**
- **필수 선조치**: `.github/workflows/release.yml` 의 `Update Firebase Realtime DB` 스텝에 `if: ${{ !contains(github.ref_name, 'citest') }}` 가드 1줄 추가, 단독 커밋(`e41a97d`). 다른 스텝은 전혀 건드리지 않음
- **그린 실행 검증**: `git push origin v0.0.1-citest1` → Actions run [32700966688](https://github.com/pslymzero-cyber/SafeAlert/actions/runs/32700966688) — 13개 스텝(Checkout ~ Complete job) 전부 `success`, 유일한 예외는 `Update Firebase Realtime DB` = `skipped`(가드가 정확히 의도대로 동작). 스텝 순서 확인: `Restore google-services.json` → `Grant execute permission` → `Run unit tests (golden RSSI cascade)` → `Extract version` → `Build debug APK` — 계획이 요구한 3개 경계 모두 일치. `Run unit tests` 로그에 `> Task :app:testDebugUnitTest` 및 `BUILD SUCCESSFUL in 1m 33s` 확인(설정 오류가 아닌 실제 테스트 실행). Artifacts 에 `unit-test-reports` 존재, 다운로드 후 `reports/tests/testDebugUnitTest/index.html` 및 `test-results/testDebugUnitTest/TEST-*.xml` 2건 파일시스템에서 실존 확인. GitHub Release "SafeAlert v0.0.1-citest1" 생성됨(`publishedAt: 2026-08-24T07:21:39Z`, T-01-11 accept 대로 임시 릴리즈 이력 허용)
- **레드 트라이얼 준비**: Task 1 과 동일 기법 재사용 — `EXPECTED_APPROACH_COLD_KALMAN[10]` 을 `-83.44452861835482` → `-83.44452761835482`(1e-6 이동), 이번엔 실제 커밋(`3ee1f4a`)
- **레드 실행 검증**: `git push origin v0.0.1-citest2` → Actions run [32701255911](https://github.com/pslymzero-cyber/SafeAlert/actions/runs/32701255911) — `Run unit tests (golden RSSI cascade)` 만 `failure`, 이후 5개 스텝(`Extract version`, `Build debug APK`, `Rename APK`, `Create GitHub Release & Upload APK`, `Update Firebase Realtime DB`) 전부 `skipped`. `Upload test reports` 는 `if: always()` 대로 `success`(D-18 확인). `gh release view v0.0.1-citest2` → `release not found` — GitHub Release 미생성 확인. Firebase 갱신도 스텝 자체가 skip 이므로 미갱신
- **아티팩트만으로 진단(CI-02/D-19)**: 다운로드한 `unit-test-reports` 아티팩트의 `test-results/testDebugUnitTest/TEST-com.wf11.safealert.ble.RssiCascadeTest.xml` 의 `message` 속성에서 실기 없이 원문 추출:
  ```
  java.lang.AssertionError: approach/coldStart frame=10 stage=kalman expected:<-83.44452761835483> but was:<-83.44452861835482>
  ```
  Actions 원본 로그(`gh run view 32701255911 --log`)에서도 동일 메시지 확인 — 콘솔과 아티팩트 두 경로 모두 동일한 결론(시나리오=`approach`, 시작상태=`coldStart`, frame=10, stage=`kalman`)에 도달, 아티팩트 단독으로도 진단 가능함을 실증
- **원복**: `RssiCascadeTest.kt` 의 변조를 원래 값(`-83.44452861835482`)으로 되돌리고 커밋(`9068696`)
- **net diff 재확인**: `git diff e41a97d..HEAD -- app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` 무출력(완전 원복 확인), `grep -c 'tag --delete\|push --delete origin' .github/workflows/release.yml` == 0(P-07 확인, D-16 복구 절차가 CI 워크플로 자체에는 없음을 재확인), `git status --porcelain` 이 이 플랜 착수 전 14건 베이스라인과 일치(추가 오염 없음), 로컬 `./gradlew testDebugUnitTest --no-daemon --console=plain --rerun-tasks` 최종 재확인 — `BUILD SUCCESSFUL in 38s`

## Task Commits

- **Task 1: 레드 트라이얼(로컬)** — 커밋 없음(P-08, net diff 0). 증빙은 이전 세션 SUMMARY 콘솔 출력
- **Task 2: 프로덕션 diff 감사** — 체크포인트 승인 완료(사람 승인, 커밋 없음)
- **Task 3: GitHub Actions 종단 실증**
  1. `e41a97d` — ci(01-ci-02): guard production Firebase update against citest tags (선행 안전조치, Rule 2 편차, 영구 반영)
  2. `3ee1f4a` — test(01-ci-02): red-trial mutation for CI-01 end-to-end gate proof (레드 태그용 커밋)
  3. `9068696` — revert(01-ci-02): restore golden Kalman value after red-trial CI proof (원복)

**Plan metadata:** (이 SUMMARY.md + STATE.md + ROADMAP.md 커밋 예정, self-check 이후)

## Files Created/Modified
- `.github/workflows/release.yml` — `Update Firebase Realtime DB` 스텝에 `citest` 태그 가드 1줄 추가(영구)
- `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` — 레드 트라이얼 커밋 쌍(변조+원복), net diff 0
- `.planning/phases/01-ci/01-02-SUMMARY.md` — 본 문서 (갱신)

## Decisions Made
- Task 2 체크포인트 승인됨(사람) — frontmatter key-decisions 참조
- Task 3 착수 전 Firebase 갱신 가드를 Rule 2 편차로 선추가 — frontmatter key-decisions 참조, 아래 Deviations 섹션에 상세
- 태그 전략 (b) 채택, 검증 전용 태그 2건 사용 — frontmatter key-decisions 참조
- CI 레드 트라이얼은 로컬과 달리 실제 커밋 쌍(변조/원복)으로 수행 — frontmatter key-decisions 참조

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - 누락된 필수 안전장치] Firebase Realtime DB 갱신에 citest 태그 가드 추가**
- **Found during:** Task 3 착수 직전(태그 push 전 사전 점검)
- **Issue:** `release.yml` 의 `Update Firebase Realtime DB` 스텝은 태그 이름과 무관하게 항상 실행되어, `wf11/version.json`(앱의 실사용자 자동업데이트 판단 근거, `UpdateManager.kt`의 `isNewer()` 가 소비)을 검증 전용 태그(`v0.0.1-citest1`)로도 덮어쓴다. 계획이 사전 공개한 비용은 "임시 릴리즈 이력이 남는다"(T-01-11, GitHub Release 노출)뿐이었고, 프로덕션 자동업데이트 포인터가 `0.0.1` 같은 낮은 버전으로(잠깐이라도) 덮어써지는 것은 별도의, 계획에 명시되지 않은 위험이었다. 실사용자에게 다운그레이드/이상 버전 팝업이 뜰 수 있는 가능성은 검증 절차 자체의 비용으로 받아들이기엔 과도함
- **Fix:** `Update Firebase Realtime DB` 스텝에 `if: ${{ !contains(github.ref_name, 'citest') }}` 한 줄만 추가. 다른 11개 스텝은 전혀 건드리지 않음
- **Files modified:** `.github/workflows/release.yml`
- **Verification:** `git diff e41a97d^ e41a97d -- .github/workflows/release.yml` 로 추가된 줄이 정확히 1줄뿐임을 확인. 그린 실행(run 32700966688)에서 해당 스텝이 `skipped` 로 관측되어 가드가 실제로 동작함을 실증
- **Committed in:** `e41a97d` (Task 3 선행 단독 커밋)

---

**Total deviations:** 1 자동수정(Rule 2 — 프로덕션 안전장치 누락)
**Impact on plan:** 계획의 실질적 목표(CI-01/CI-02 종단 실증)에는 영향 없음. 오히려 실증 과정 자체가 프로덕션에 부수피해를 남기지 않도록 하는 필수 전제조건이었음. 이 가드는 임시적 조치가 아니라 영구 반영으로 남김(향후 어떤 `citest` 태그 실험에도 재사용 가능)

## Issues Encountered
None — Task 3 의 그린·레드 두 실행 모두 계획의 예측(step order, 차단 위치, 아티팩트 존재, D-19 메시지 포맷)과 정확히 일치했다. ADB 기기 0대 연결 상태는 이 Task 와 무관(실기기 스모크 테스트를 요구하지 않음, P-09).

## User Setup Required
None - no external service configuration required.

## D-16 차단된 태그 복구 절차 (기록, 이번 세션엔 미필요 — 레드 태그는 검증 전용이라 재push 대상 아님)

테스트 실패로 (실 배포) 태그가 차단되면 CI 는 아무것도 자동 정리하지 않는다. 사람이 수동으로 처리:
1. 로컬 태그 삭제: `git tag -d v<버전>`
2. 원격 태그 삭제: `git push --delete origin v<버전>`
3. 원인 수정 커밋 작성
4. 같은 태그 재생성·재push: `git tag v<버전> && git push origin v<버전>`
5. 부분 생성된 GitHub Release 가 있으면 Releases 화면에서 함께 수동 삭제

CI 워크플로에 원격 태그를 지우는 스텝을 넣지 않는 이유: 자동 태그 정리가 릴리즈 이력을 조용히 재작성해 사후 감사를 불가능하게 만들기 때문(P-07). `grep -c 'tag --delete\|push --delete origin' .github/workflows/release.yml` == 0 으로 이 워크플로에 그런 스텝이 없음을 재확인함.

## 정리 필요 항목 (사람이 수동 수행 — 이 세션에서 삭제하지 않음)

- 원격 태그: `v0.0.1-citest1`, `v0.0.1-citest2`
- GitHub Release: "SafeAlert v0.0.1-citest1" (`v0.0.1-citest2` 는 Release 자체가 생성되지 않았으므로 정리 대상 아님)
- 로컬 태그(있다면): `git tag -l 'v0.0.1-citest*'` 로 확인 후 `git tag -d`

## Next Phase Readiness

**정지 지점:** Task 3 (`gate="blocking-human"` 체크포인트, 이 계획의 마지막 task) — 아래 CHECKPOINT REACHED 섹션 참조.

이 플랜은 완료되지 않았다(`status: halted`). 계획 원문: "승인 시: Phase 1 완료. CI-01 과 CI-02 가 실증으로 충족됩니다." — 사람의 명시적 "승인" 없이는 CI-01/CI-02 요구사항이 완료 처리되지 않으며, 이 SUMMARY 에 의존하는 후속 phase 는 이 플랜이 `status: complete` 로 재작성되기 전까지 차단된 것으로 보고되어야 한다.

---

## CHECKPOINT REACHED

**Type:** human-verify
**Gate:** blocking-human
**Plan:** 01-ci-02
**Progress:** 3/3 tasks 실행 완료 (Task 3 는 최종 승인 대기)

### Completed Tasks

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | 로컬 레드 트라이얼 (Kalman/Median) | (커밋 없음, net diff 0) | RssiCascadeTest.kt (변조 후 원복) |
| 2 | 프로덕션 diff 감사 승인 | (커밋 없음, 사람 승인) | KalmanFilter.kt (변경 없음, 감사만) |
| 3 | CI 종단 실증 — Firebase 가드 | `e41a97d` | `.github/workflows/release.yml` |
| 3 | CI 종단 실증 — 레드 변조 | `3ee1f4a` | `RssiCascadeTest.kt` |
| 3 | CI 종단 실증 — 원복 | `9068696` | `RssiCascadeTest.kt` |

### Current Task

**Task 3:** 실제 태그 push 로 CI-01 / CI-02 종단 실증
**Status:** 증거 수집 완료, 최종 승인 대기
**Blocked by:** `gate="blocking-human"` — 사람의 명시적 "승인" 필요 (auto 모드에서도 자동승인 대상 아님)

### Checkpoint Details

**그린 실행 — CI-01 위치 검증**
- Run: https://github.com/pslymzero-cyber/SafeAlert/actions/runs/32700966688 (tag `v0.0.1-citest1`)
- 스텝 순서: `Restore google-services.json` → `Grant execute permission` → `Run unit tests (golden RSSI cascade)` → `Extract version` → `Build debug APK` — 계획이 요구한 3개 경계 모두 일치
- `Run unit tests`: `success`, 로그에 `> Task :app:testDebugUnitTest` + `BUILD SUCCESSFUL in 1m 33s` (설정 오류 아님, 실제 테스트 실행 확인)
- `unit-test-reports` 아티팩트 존재·다운로드 성공, `index.html` 및 `TEST-*.xml` 2건 파일시스템 확인
- `Update Firebase Realtime DB` = `skipped` (가드 정상 동작), `Create GitHub Release & Upload APK` = `success` (Release "SafeAlert v0.0.1-citest1" 생성, T-01-11 accept)

**레드 실행 — CI-01 차단 실증 (핵심 증거)**
- Run: https://github.com/pslymzero-cyber/SafeAlert/actions/runs/32701255911 (tag `v0.0.1-citest2`)
- `Run unit tests (golden RSSI cascade)` = `failure` — job 이 여기서 멈춤
- 이후 스텝 전부 `skipped`: `Extract version`, `Build debug APK`, `Rename APK`, `Create GitHub Release & Upload APK`, `Update Firebase Realtime DB`
- `gh release view v0.0.1-citest2` → `release not found` (GitHub Release 미생성 확인)
- `Upload test reports` = `success` (`if: always()` 대로 실패해도 실행됨, D-18)
- `unit-test-reports` 아티팩트 존재·다운로드 성공(실패한 실행인데도)

**CI-02 — 아티팩트만으로 진단 (D-19)**
- 다운로드한 `TEST-com.wf11.safealert.ble.RssiCascadeTest.xml` 의 `message` 속성 원문:
  ```
  java.lang.AssertionError: approach/coldStart frame=10 stage=kalman expected:<-83.44452761835483> but was:<-83.44452861835482>
  ```
- 진단: **시나리오=approach, 시작상태=coldStart, frame=10, stage=kalman** — 실기 없이, 아티팩트만으로 정확히 지목됨. Actions 원본 로그의 동일 메시지와 대조해 일치 확인

**D-16 복구 절차** — 본 SUMMARY 상단 "D-16 차단된 태그 복구 절차" 섹션에 전문 기록

**P-07 확인** — `grep -c 'tag --delete\|push --delete origin' .github/workflows/release.yml` == `0`

**정리 필요 항목(사람 수동)** — 본 SUMMARY 상단 "정리 필요 항목" 섹션 참조 (`v0.0.1-citest1`/`v0.0.1-citest2` 태그, "SafeAlert v0.0.1-citest1" Release)

### Awaiting

계획 원문의 8개 acceptance_criteria 를 모두 충족했다고 판단합니다(위 증거 참조). 아래를 확인 후 "승인"이라고 입력해 주시면 CI-01/CI-02 를 요구사항 충족으로 확정하고 이 플랜을 완료 처리합니다:
1. 그린 실행 스텝 순서·결과가 예상대로인지
2. 레드 실행에서 차단이 올바른 위치(테스트 직후)에서 일어났는지, Release 가 정말 생성 안 됐는지
3. 레드 실행 아티팩트만으로 원인(approach/coldStart/frame=10/kalman)을 지목한 것이 납득되는지
4. 프로덕션 Firebase 갱신 가드(`e41a97d`, Rule 2 편차)를 계획 밖 추가로서 받아들이는지
5. 태그/Release 정리(`v0.0.1-citest1`, `v0.0.1-citest2`)를 언제/어떻게 직접 수행하실지(선택 — 지금 안 하셔도 무방)

어긋난 항목이 있으면 번호와 내용을 알려 주십시오 — 01-01 의 `release.yml` 배선으로 되돌아갑니다.

---
*Phase: 01-ci*
*Halted at Task 3 checkpoint (final): 2026-08-24*

## Self-Check: PASSED

- FOUND: commit `e41a97d`
- FOUND: commit `3ee1f4a`
- FOUND: commit `9068696`
- FOUND: `.github/workflows/release.yml`
- FOUND: `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt`
- FOUND: `.planning/phases/01-ci/01-02-SUMMARY.md`
