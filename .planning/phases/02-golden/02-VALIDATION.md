---
phase: 2
slug: golden
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-08-27
---

# Phase 2 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4.13.2 (기존) + Robolectric 4.15.1 (신규 — 이번 Phase에서 추가) |
| **Config file** | `app/build.gradle` — 신규 `testImplementation 'org.robolectric:robolectric:4.15.1'` 라인 추가 필요 (Wave 0) |
| **Quick run command** | `./gradlew testDebugUnitTest --tests "*.AlertCascadeGoldenTest"` (신규 테스트 클래스명은 계획 단계에서 확정) |
| **Full suite command** | `./gradlew testDebugUnitTest` |
| **Estimated runtime** | ~120 seconds (Gradle + Robolectric 초기 구동 포함) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew testDebugUnitTest --tests "<해당 신규 테스트 클래스>"`
- **After every plan wave:** Run `./gradlew testDebugUnitTest`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 180 seconds

---

## Per-Task Verification Map

> 태스크 ID는 플래너가 PLAN.md 생성 시 채운다. 아래는 요구사항 단위 시드 행.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| TBD | TBD | TBD | TEST-01 | — | N/A | unit (Robolectric) | `./gradlew testDebugUnitTest --tests "*.AlertCascadeGoldenTest"` | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | TEST-02 | — | N/A | unit (Robolectric) | `./gradlew testDebugUnitTest --tests "*.UwbSessionGoldenTest"` | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | BUG-02 | — | N/A | unit (Robolectric) | `./gradlew testDebugUnitTest --tests "*.LowSpeedApproachRegressionTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `app/src/test/java/com/wf11/safealert/ble/AlertCascadeGoldenTest.kt` — TEST-01 커버
- [ ] `app/src/test/java/com/wf11/safealert/uwb/UwbSessionGoldenTest.kt` — TEST-02 커버
- [ ] `app/src/test/java/com/wf11/safealert/ble/LowSpeedApproachRegressionTest.kt` — BUG-02 커버
- [ ] `app/src/test/java/com/wf11/safealert/support/BleServiceTestHarness.kt` — ServiceController 생성·리플렉션 래퍼·shared helper (runCascade/assertCascade)
- [ ] Framework install: `app/build.gradle`에 `testImplementation 'org.robolectric:robolectric:4.15.1'` 추가 — 현재 없음
- [ ] `.github/workflows/release.yml:65` `MIN_TOTAL=17` 상향 — 신규 테스트 수만큼 (CI 최소 테스트 수 플로어 실효성 유지)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| 현장에서 지게차가 천천히 다가올 때 경고 발령 확인 | BUG-02 | 실기기 BLE/UWB 전파 환경·실제 이동 속도는 시뮬레이션 불가 (성공 기준 4) | 저속 접근 WARNING 수정이 포함된 APK 배포 후, 현장에서 지게차 저속 접근 시 WARNING 이상 경보가 뜨는지 단일 항목 확인 |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 180s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
