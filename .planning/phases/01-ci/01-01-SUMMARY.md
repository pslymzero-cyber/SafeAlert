---
phase: 01-ci
plan: 01
subsystem: testing
tags: [junit4, gradle, kotlin, kalman-filter, ble-rssi, golden-tests, github-actions, ci]

# Dependency graph
requires: []
provides:
  - JVM-only golden regression harness for the 3-stage RSSI filter cascade (MedianFilter -> RssiPreFilter -> KalmanFilter)
  - Deterministic fake-clock seam in KalmanFilter (nowMs injection) with zero call-site changes
  - 8 frozen scenario/start-state combinations (approach/departure/impulse/stationary x cold/warm) as record-then-freeze golden fixtures
  - Cross-device filter state isolation proof for MedianFilter/RssiPreFilter shared maps (D-07)
  - CI gate in release.yml: tag push runs unit tests + uploads JUnit/HTML reports before APK build/release/Firebase update
affects: [02-refactor, ci]

# Actuals (#2632)
actuals:
  tokens: 6180
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Time-seam injection: KalmanFilter(nowMs: () -> Long = { System.currentTimeMillis() }) — only production change, zero call-site edits"
    - "Record-then-freeze (D-09): expected values collected once via a temporary print-based JUnit test parsed from the JUnit XML <system-out>, pasted as frozen inline constants, temp code deleted — never hand-calculated"
    - "Shared private helper (runCascade/assertCascade) reused by all 8 golden tests to keep wiring DRY"
    - "Relational (non-frozen) expected values for isolation tests: 'matches a solo-run baseline' instead of literal constants, since the property under test is non-interference, not a fixed number"
    - "Failure message convention (D-19): \"<scenario>/<startState> frame=<i> stage=<median|prefilter|kalman>\" pinpoints exact break location from CI console alone"

key-files:
  created:
    - app/src/test/java/com/wf11/safealert/ble/RssiCascadeIsolationTest.kt
  modified:
    - app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt
    - app/build.gradle
    - app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt
    - .github/workflows/release.yml

key-decisions:
  - "Kept DRY shared-helper design for Task 2's 8 tests (mandated by the task's own <action> prose) over literal per-test duplication implied by grep-count acceptance heuristics"
  - "KalmanFilter excluded from Task 3's isolation scope — it is instantiated per-device (no shared map), so cross-device leakage is structurally impossible there; only MedianFilter/RssiPreFilter (deviceId-keyed maps) are tested"
  - "Isolation tests use relational assertions (match a solo-run baseline) rather than record-then-freeze literals, since the invariant under test is non-interference, not a specific numeric output"

patterns-established:
  - "Golden regression tests for stateful signal-processing cascades: freeze frame-by-frame arrays per scenario, compare Int stages exactly and Double stages with 1e-9 delta"
  - "Cross-device state isolation verification: build a solo-run baseline, then interleave a second device's pushes and assert the target device's output is byte-for-byte unchanged"

requirements-completed: [TEST-03, TEST-04, CI-01, CI-02]

coverage:
  - id: D1
    description: "MedianFilter -> RssiPreFilter -> KalmanFilter cascade frame-by-frame output frozen for 4 scenarios (approach/departure/impulse/stationary) x 2 start states (cold/warm)"
    requirement: "TEST-03"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt#8 golden tests (approach/departure/impulse/stationary x coldStart/warmStart)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Golden cascade tests run on JVM with no Android framework or real device required"
    requirement: "TEST-04"
    verification:
      - kind: unit
        ref: "./gradlew testDebugUnitTest --no-daemon --console=plain (2 consecutive runs, both BUILD SUCCESSFUL)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Cross-device filter state isolation: interleaved push invariance, selective clear(), and clearAll() cold-start reset all verified against a solo-run baseline"
    requirement: "TEST-03"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/wf11/safealert/ble/RssiCascadeIsolationTest.kt#interleavedPush_deviceOneMatchesSoloBaseline, selectiveClear_onlyAffectsTargetDevice, clearAll_resetsAllDevicesToColdStart"
        status: pass
    human_judgment: false
  - id: D4
    description: "Tag-push CI gate: unit tests run and reports upload before APK build/release/Firebase update; workflow YAML parses cleanly"
    requirement: "CI-01"
    verification:
      - kind: other
        ref: "grep step order in .github/workflows/release.yml (Restore google-services.json < Grant execute permission < Run unit tests < Upload test reports < Extract version < Build debug APK) + python yaml.safe_load parse check"
        status: pass
    human_judgment: true
    rationale: "Actual tag-push CI execution (job actually blocking APK build on test failure) is only proven in practice by 01-02's real pipeline run — this plan's evidence is static step ordering + local mutation-test proof, not a live GitHub Actions execution"
  - id: D5
    description: "Test failure messages identify scenario/start-state/frame/stage; JUnit XML + HTML reports preserved as artifacts on failure"
    requirement: "CI-02"
    verification:
      - kind: unit
        ref: "manual mutation test: corrupted EXPECTED_APPROACH_COLD_KALMAN[2], reran, confirmed non-zero exit + message 'approach/coldStart frame=2 stage=kalman expected:<-999.0> but was:<-91.65266500958131>', then reverted cleanly"
        status: pass
    human_judgment: false
  - id: D6
    description: "Production runtime behavior unchanged — only change is KalmanFilter constructor default-argument seam, zero call-site edits"
    requirement: "TEST-03"
    verification:
      - kind: other
        ref: "git diff --stat 945c729 HEAD -- app/src/main/ => 1 file changed, 9 insertions(+), 6 deletions(-) (KalmanFilter.kt only)"
        status: pass
    human_judgment: false

duration: ~26min (commit-to-commit span across a multi-session continuation; wall-clock time across sessions was longer)
completed: 2026-08-24
status: complete
---

# Phase 01 Plan 01: Test Harness and CI Regression Gate Summary

**JVM-only golden regression harness for the 3-stage BLE RSSI filter cascade (Median->PreFilter->Kalman), covering 4 scenarios x 2 start states x cross-device isolation, gated into the release CI pipeline before APK build.**

## Performance

- **Duration:** ~26 min (commit span 12:59:43 -> 13:25:33 KST, across a compacted multi-session continuation)
- **Started:** 2026-08-24T12:59:43+09:00 (Task 1 commit)
- **Completed:** 2026-08-24 (this session)
- **Tasks:** 3/3
- **Files modified:** 4 (1 production, 3 test/CI)

## Accomplishments
- Injected a deterministic fake-clock seam into `KalmanFilter` (`nowMs: () -> Long`) with zero production call-site changes, enabling reproducible time-dependent Kalman math in JVM tests
- Froze 8 golden regression tests covering all 4 required scenarios (approach, departure, impulse, stationary) x 2 start states (cold start, `injectWarmup`) via strict record-then-freeze discipline — no hand-calculated expected values
- Empirically proved the 3-window median filter structurally absorbs single-frame outliers (impulse scenario's injected -45/-105 spikes never surface in median output)
- Proved cross-device filter state isolation for `MedianFilter`/`RssiPreFilter`'s shared `deviceId`-keyed maps: interleaved pushes, selective `clear()`, and `clearAll()` all leave a target device's output identical to its solo-run baseline
- Wired the release CI workflow to run `testDebugUnitTest` and upload JUnit XML + HTML reports before APK build, release, and Firebase update — a failing test now blocks the entire release chain
- Ran a live mutation test (corrupt one frozen value, confirm non-zero exit + `"<scenario>/<startState> frame=<i> stage=<...>"` failure message, revert cleanly) to prove the CI-02 diagnosability requirement in practice, not just in theory

## Task Commits

Each task was committed atomically:

1. **Task 1: 트레이서 — 시임 → JVM 골든 1시나리오 → Gradle 로깅 → CI 게이트** - `0f1fd50` (feat)
2. **Task 2: 시나리오 확장 — 4종 × 2 시작 상태 전량 채집·동결** - `6caf291` (test)
3. **Task 3: 기기 간 필터 상태 격리 테스트 (D-07)** - `953fab3` (test)

**Plan metadata:** (this commit, made immediately after this summary)

_Note: all 3 tasks were `type="auto"`/`type="tracer"` with `tdd="true"`; each was implemented, verified, and committed as a single atomic commit rather than separate RED/GREEN/REFACTOR commits, since the tests were built directly against the already-existing (Task 1 seam only) implementation rather than driving new production behavior._

## Files Created/Modified
- `app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt` - Added `nowMs: () -> Long = { System.currentTimeMillis() }` constructor parameter (time seam); `update()`/`injectWarmup()` now read `nowMs()` instead of `System.currentTimeMillis()` directly
- `app/build.gradle` - `testOptions` additions for CI failure visibility (console output on failure, JUnit XML)
- `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` - Golden regression suite: 4 input sequences (`INPUT_APPROACH`/`INPUT_DEPARTURE`/`INPUT_IMPULSE`/`INPUT_STATIONARY`), 21 frozen `EXPECTED_*` constant arrays (7 scenario/start combos x up to 3 stages, with aliasing where median/prefilter don't depend on start state), 8 `@Test` methods sharing a `runCascade`/`assertCascade` private helper
- `app/src/test/java/com/wf11/safealert/ble/RssiCascadeIsolationTest.kt` (new) - 3 tests proving `MedianFilter`/`RssiPreFilter` per-device state isolation via a `soloBaseline()` relational comparison (interleaved push invariance, selective `clear()`, `clearAll()` cold-start reset)
- `.github/workflows/release.yml` - Added `Run unit tests (golden RSSI cascade)` and `Upload test reports` steps between `Grant execute permission` and `Extract version`, so a failing test blocks APK build/release/Firebase update

## Decisions Made
- Kept the DRY shared-helper (`runCascade`/`assertCascade`) design for Task 2's 8 golden tests, since Task 2's own `<action>` text explicitly mandates a single shared private helper reused across tests, and this matches Task 1's already-approved pattern. This is a deliberate deviation from a literal reading of the acceptance-criteria grep counts (see Deviations below).
- Scoped `KalmanFilter` out of Task 3's isolation test file: it is instantiated per-device in the production wiring (no shared map), so cross-device state leakage is structurally impossible there — testing it would be testing something that cannot fail by construction. Documented via KDoc in the isolation test file itself.
- Used relational ("matches solo-run baseline") rather than record-then-freeze literal expected values in the isolation tests, since the property under test (non-interference between devices) is defined by a relationship between two runs, not a fixed numeric output — freezing numbers here would test nothing extra and would break if the cascade math itself ever legitimately changes.

## Deviations from Plan

### Auto-fixed Issues

None — no bugs, missing critical functionality, or blocking issues were encountered. All three tasks executed as specified without requiring Rule 1/2/3 auto-fixes.

### Documented Non-blocking Judgment Calls

**1. Acceptance-criteria literal grep-count mismatch vs. explicit DRY mandate (Task 2)**
- **Found during:** Task 2 (scenario expansion)
- **Issue:** Task 2's acceptance criteria include `grep -c 'injectWarmup'` expecting 4 occurrences and `grep -c '1e-9'` expecting >=8, which assume each of the 8 tests inlines its own wiring code. Task 2's own `<action>` prose explicitly instructs the opposite: "배선 코드는 private 헬퍼 하나로 뽑아 8개 테스트가 공유" (wiring code should be extracted into one private helper shared by all 8 tests).
- **Resolution:** Kept the shared-helper design (matches the explicit instruction and Task 1's already-approved pattern). `injectWarmup` appears once (in the shared helper, parameterized by a boolean), and `1e-9` appears once (in the shared `assertCascade` delta comparison), not 4/8 times respectively. The underlying intent — delta-comparison applied to every Double value across all 4 warm-start tests — is fully satisfied; only the literal source-line count differs from what the grep heuristic assumed.
- **Files affected:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt`
- **Verification:** All 8 tests pass; `assertEquals(message, expected, actual, 1e-9)` is invoked once per Double array element at runtime via the shared helper, satisfying P-04 in substance.

**2. Pre-existing `updateGolden` substring in KDoc (inherited from Task 1, not introduced this session)**
- **Found during:** Task 2 verification pass
- **Issue:** `grep -c 'updateGolden' RssiCascadeTest.kt` returns 1 (an acceptance criterion expects 0), because the file's header KDoc — written and already committed in Task 1's approved commit `0f1fd50`, untouched by Task 2/3 — contains the literal string `-PupdateGolden` while documenting the P-02 prohibition itself (i.e., explaining what NOT to build).
- **Resolution:** Left as-is. This is documentation prose describing a prohibition, not an operative auto-refreeze mechanism (no Gradle property, env var switch, or auto-rewrite task exists anywhere in the codebase). P-02's actual intent (no auto-refreeze path) is satisfied; the grep heuristic cannot distinguish "documents X" from "implements X."
- **Files affected:** None (pre-existing, unmodified)
- **Verification:** `grep -rn 'updateGolden\|-PupdateGolden'` across the test sourceset shows only the one KDoc mention; no Gradle task, property, or conditional logic references it.

---

**Total deviations:** 0 auto-fixed. 2 documented judgment calls (both favor explicit task-level `<action>` prose and pre-existing approved Task 1 content over literal grep-count heuristics; both verified to satisfy the underlying intent).
**Impact on plan:** None on functionality or scope. Both items are heuristic-vs-substance mismatches in acceptance-criteria checks, not defects in the delivered code.

## Issues Encountered
None. Every `./gradlew testDebugUnitTest` invocation across this plan's execution (temp-collect run, full-class runs, 2x determinism rerun, full-suite run after Task 3, isolation-class run with device02 pushes manually commented out, mutation test, final clean-revert verification) returned the expected result (`BUILD SUCCESSFUL` or, for the deliberate mutation test, `BUILD FAILED` with exit 1 and the correct diagnostic message) on the first attempt.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- The RSSI filter cascade now has a frozen, JVM-only regression net (12 tests total: 8 golden + 3 isolation + 1 pre-existing from Task 1's tracer... actually 8 golden supersede the tracer's original 1) covering exactly the behavior that Phase 2/3's `processAlert` decomposition must not silently change — this was the explicit blocking precondition noted in STATE.md's Blockers/Concerns ("유닛·통합 테스트 0건 상태에서 안전 크리티컬 로직 분해 금지").
- `KalmanFilter`'s time seam (`nowMs`) is a reusable pattern for any future test needing deterministic time-dependent behavior elsewhere in the BLE stack (e.g., `ImuFusion`, `UwbRanger` state machines in later phases), without touching production call sites.
- CI-01/CI-02 are structurally wired (step order, YAML validity, live mutation-test proof of failure diagnosability) but their end-to-end proof in an actual GitHub Actions run is deferred to plan 01-02, as the plan's own success criteria note ("실증은 01-02").
- No blockers for Phase 2.

---
*Phase: 01-ci*
*Completed: 2026-08-24*

## Self-Check: PASSED

- FOUND: app/src/test/java/com/wf11/safealert/ble/RssiCascadeIsolationTest.kt
- FOUND: app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt
- FOUND: app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt
- FOUND: app/build.gradle
- FOUND: .github/workflows/release.yml
- FOUND commit: 0f1fd50
- FOUND commit: 6caf291
- FOUND commit: 953fab3
