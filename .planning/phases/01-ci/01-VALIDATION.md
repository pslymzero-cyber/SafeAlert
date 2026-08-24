---
phase: 1
slug: ci
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: validated
nyquist_compliant: true
wave_0_complete: true
created: 2026-08-24
validated: 2026-08-24 (phase-close 소급 확정 — /gsd-validate-phase 실행이 아니라 01-VERIFICATION.md 의 file:line 증거로 확인)
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4.13.2 (already declared, `app/build.gradle:71` — no new dependency) |
| **Config file** | none yet — `testOptions { unitTests { all { testLogging ... } } }` block added by this phase (D-20) |
| **Quick run command** | `./gradlew testDebugUnitTest --console=plain --tests "com.wf11.safealert.ble.*"` |
| **Full suite command** | `./gradlew testDebugUnitTest --no-daemon --console=plain` (D-15 — exact CI command) |
| **Estimated runtime** | ~10 seconds (3 pure Kotlin classes, no Android instrumentation) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew testDebugUnitTest --console=plain`
- **After every plan wave:** Run `./gradlew testDebugUnitTest --no-daemon --console=plain`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 30 seconds

*This phase has no "full suite" distinct from the quick run — the entire JVM test surface IS this phase's golden tests.*

---

## Per-Task Verification Map

> Task IDs are assigned by the planner; this map is seeded per requirement and refined once PLAN.md files exist.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| TBD | TBD | 0 | TEST-04 | — | N/A | unit (infrastructure) | `./gradlew testDebugUnitTest` | ❌ W0 — `app/src/test/` absent | ⬜ pending |
| TBD | TBD | 1 | TEST-03 | — | N/A | unit (golden) | `./gradlew testDebugUnitTest --tests "com.wf11.safealert.ble.RssiCascadeTest"` | ❌ W0 | ⬜ pending |
| TBD | TBD | 1 | CI-01 | — | N/A | CI/workflow | N/A — verified by `release.yml` step ordering + a deliberate red-test trial run | ❌ W0 | ⬜ pending |
| TBD | TBD | 1 | CI-02 | — | N/A | CI/workflow | N/A — verified by inspecting uploaded artifact contents after a deliberate red-test trial run | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [x] `app/src/test/java/com/wf11/safealert/ble/` — JVM test source set directory — 생성됨 (`0f1fd50`)
- [x] `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` — golden cascade test, covers TEST-03 — 8 `@Test`, `:44-121` 동결 리터럴 21개
- [x] `app/build.gradle` `testOptions` block — console diagnostics, covers CI-02 (D-20) — `:51-57` (`events 'failed'`, `exceptionFormat 'full'`)
- [x] `.github/workflows/release.yml` test-execution step — covers CI-01 (D-13/D-14/D-15) — `:44-45`, Build APK(`:62`)보다 앞
- [x] `.github/workflows/release.yml` artifact-upload step — covers CI-02 (D-17/D-18) — `:47-56` (`if: always()`)
- [x] No shared fixture file needed — 확인됨: 두 테스트 클래스 모두 자기완결(inline constants), 공유 fixture 파일 0개

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| CI blocks APK release when a test fails | CI-01 | Workflow-level property; not expressible as a local unit assertion | Break one golden expectation on a throwaway branch/tag, push, confirm the release job halts before the Build step and no APK is published |
| CI artifacts alone identify which test broke at which expected value | CI-02 | Requires inspecting the uploaded HTML report + JUnit XML from a real CI run | From the same red run, open the run's artifacts and confirm scenario name, frame index, and stage name are readable without a device |
| Shipped APK behaves identically to v1.1.70 | (출하 상태) | Requires a physical device | Install the built APK, confirm install succeeds and alert behavior is unchanged |

### 수행 결과 (2026-08-24)

| Behavior | 결과 | 증거 |
|----------|------|------|
| CI blocks APK release when a test fails | **완료** | 레드 태그 `v0.0.1-citest2` → run `32701255911` failure. `Run unit tests` 에서 정지 후 `Extract version`/`Build debug APK`/`Rename APK`/`Create GitHub Release & Upload APK`/`Update Firebase Realtime DB` 5스텝 skipped. `gh release view v0.0.1-citest2` → `release not found` |
| CI artifacts alone identify which test broke at which expected value | **완료** | 레드 런 아티팩트 `TEST-com.wf11.safealert.ble.RssiCascadeTest.xml` 원문에서 `approach/coldStart frame=10 stage=kalman expected:<-83.44452761835483> but was:<-83.44452861835482>` — 시나리오·시작상태·프레임·스테이지 4요소 실기 없이 판독 |
| Shipped APK behaves identically to v1.1.70 | **미완 (human_needed)** | ADB 연결 기기 0대로 수행 불가. 코드 근거만 확정 — `KalmanFilter.kt:27-30` 기본 인자 = `System.currentTimeMillis()`, 호출부 `BleService.kt:450`·`:1454` 변경 0곳(런타임 동작 불변). 사람이 직접 설치 검증해야 함 |

*SC(Success Criteria) 4건은 이 미완 1건과 무관하게 전부 달성 — 실기 스모크는 ROADMAP 이 별도 배정한 현장 검증 항목이다.*

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references — 6/6 충족
- [x] No watch-mode flags — `--continuous`/watch 플래그 0건
- [x] Feedback latency < 30s — 실측 로컬 `testDebugUnitTest` ~10s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** 2026-08-24 — phase-close 시점 소급 확정. 근거 = `01-VERIFICATION.md` (GOAL_ACHIEVED 4/4) + Task 3 blocking-human 체크포인트 사용자 승인.
