---
phase: 1
slug: ci
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-08-24
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

- [ ] `app/src/test/java/com/wf11/safealert/ble/` — JVM test source set directory (does not exist yet)
- [ ] `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` — golden cascade test, covers TEST-03
- [ ] `app/build.gradle` `testOptions` block — console diagnostics, covers CI-02 (D-20)
- [ ] `.github/workflows/release.yml` test-execution step — covers CI-01 (D-13/D-14/D-15)
- [ ] `.github/workflows/release.yml` artifact-upload step — covers CI-02 (D-17/D-18)
- [ ] No shared fixture file needed — JUnit 4 needs no `conftest` equivalent at this surface size; each test class is self-contained per D-11 (inline constants)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| CI blocks APK release when a test fails | CI-01 | Workflow-level property; not expressible as a local unit assertion | Break one golden expectation on a throwaway branch/tag, push, confirm the release job halts before the Build step and no APK is published |
| CI artifacts alone identify which test broke at which expected value | CI-02 | Requires inspecting the uploaded HTML report + JUnit XML from a real CI run | From the same red run, open the run's artifacts and confirm scenario name, frame index, and stage name are readable without a device |
| Shipped APK behaves identically to v1.1.70 | (출하 상태) | Requires a physical device | Install the built APK, confirm install succeeds and alert behavior is unchanged |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
