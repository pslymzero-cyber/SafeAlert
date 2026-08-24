# Phase 1: 테스트 하네스와 CI 회귀 게이트 - Research

**Researched:** 2026-08-24
**Domain:** JVM unit testing (JUnit 4) for pure Kotlin filter logic + GitHub Actions CI gating
**Confidence:** HIGH

## Summary

Phase 1 is narrowly scoped and low-risk: freeze the current (possibly imperfect) output of the 3-stage RSSI filter cascade (`MedianFilter` → `RssiPreFilter` → `KalmanFilter`) as golden JVM unit tests, then wire those tests into the existing single-job `release.yml` so a broken cascade blocks the APK build instead of shipping. The only production code change permitted is a default-argument time-seam parameter on `KalmanFilter`'s constructor (`nowMs: () -> Long = { System.currentTimeMillis() }`), which is a zero-behavior-change refactor at the two wall-clock call sites (`update()` line 78, `injectWarmup()` line 143).

All three target classes (`MedianFilter.kt`, `RssiPreFilter.kt`, `KalmanFilter.kt`) live under the `02_ble` directory but declare `package com.wf11.safealert.ble` — the numeric directory prefix is a filesystem-only organizational convention and does NOT appear in the package name. New test sources must go under `app/src/test/java/com/wf11/safealert/ble/`, matching the package, not the directory name. Neither `app/src/test/` nor `app/src/androidTest/` exists yet in this repo — this phase creates the JVM test source set from scratch. `junit:junit:4.13.2` is already declared in `app/build.gradle`; no new test framework dependency needs to be added.

The CI gate is a single-step insertion into the existing `release` job in `.github/workflows/release.yml`, positioned after "Restore google-services.json" (required — Gradle needs that file present to configure the `com.google.gms.google-services` plugin during any Gradle invocation, including `testDebugUnitTest`) and before "Build debug APK". The default failure behavior of a GitHub Actions step (non-zero exit halts the job, skips subsequent steps) already satisfies CI-01's "빌드가 차단된다" requirement with no extra `if:` conditions needed on the gate step itself — only the artifact-upload step needs `if: always()` so failure diagnostics survive a red build.

**Primary recommendation:** Add one JUnit 4 test class per cascade stage (or one combined `RssiCascadeTest.kt`) using inline `Int`/`Double` array constants as fixtures (no JSON/resource files, no auto-regeneration tooling — per CONTEXT.md D-09/D-11, this is manual record-then-freeze), insert a `./gradlew testDebugUnitTest --no-daemon --console=plain` step into `release.yml` between the two named steps above, add a `testOptions { unitTests.all { testLogging { events 'failed'; exceptionFormat 'full' } } }` block to `app/build.gradle` for CI-readable failure diagnostics, and upload both `app/build/reports/tests/testDebugUnitTest/` (HTML) and `app/build/test-results/testDebugUnitTest/` (JUnit XML) via `actions/upload-artifact@v7` with `if: always()`.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01**: `KalmanFilter` 의 벽시계 의존을 생성자 주입 시임으로 끊는다 — `KalmanFilter(preset, private val nowMs: () -> Long = { System.currentTimeMillis() })`. 기본인자가 현행 동작이라 호출부는 0줄 변경이고, `update`(78행)·`injectWarmup`(143행) 두 경로를 한 번에 커버한다.
- **D-02**: dt 는 120ms 고정 상수를 쓴다. 근거는 `BleService.kt:682` 주석 "정상 주기 ~120ms".
- **D-03**: 프로덕션 코드 변경 범위는 시임 1건뿐이다.
- **D-04**: v1.1.70 동작 동일성은 diff 리뷰만으로 확인한다 (ADB 기기 0대 연결).
- **D-05**: 골든 테스트 경계는 순수 3단 직렬 캐스케이드다. `prevVel` 은 0.0 고정, `adaptiveQFactor` 는 1.0 고정, preset 은 `KALMAN_PRESET_NORMAL` 고정, `pEmaFilter` 는 제외한다 (Reversibility: costly).
- **D-06**: 시작 상태는 2세트다 (cold start + injectWarmup) — `pRR` 5.0 vs 25.0 분기 때문.
- **D-07**: 단일 `deviceId` 골든이 기본이다. 기기 간 오염은 별도 격리 테스트로 다룬다.
- **D-08**: Kalman 의 `Double` 출력 허용오차는 1e-9. MedianFilter/RssiPreFilter 는 `Int` 정확히 일치.
- **D-09**: record-then-freeze 방식이다 (Reversibility: costly).
- **D-10**: 합성 시나리오 4종 — 접근(approach)·이탈(departure)·임펄스 튐(impulse spike)·정지(stationary). 실기 로그 캡처는 없다.
- **D-11**: 기대값은 인라인 Kotlin 배열 상수다. 리소스 파일·JSON 은 쓰지 않는다.
- **D-12**: 수동 refreeze + 필수 diff 리뷰. 자동 재작성 경로는 없다.
- **D-13**: 게이트는 `release.yml` 에만 존재한다 (단일 `master` 브랜치, merge commit 0건 — 이 리포는 PR 트리거가 발화하지 않는다).
- **D-14**: 게이트 위치는 동일한 `release` job 내, "Restore google-services.json" 이후, "Build debug APK" 이전 (하드 제약: google-services.json 복원 이후여야 하는 이유는 플러그인 설정 요구사항).
- **D-15**: CI 커맨드는 `./gradlew testDebugUnitTest --no-daemon --console=plain` (플레이버 없음·buildType 2종뿐이므로 `assembleDebug` 와 정확히 동일 variant).
- **D-16**: 실패한 릴리즈 태그는 수동으로 삭제·재푸시한다. CI 자동 삭제는 없다.
- **D-17**: 아티팩트는 `app/build/reports/tests/testDebugUnitTest`(HTML) + `app/build/test-results/testDebugUnitTest`(JUnit XML) 전체 업로드.
- **D-18**: 업로드 조건은 `if: always()`.
- **D-19**: assert 실패 메시지 컨벤션은 "scenario/frame=N stage=X" 형태, 예: `approach/coldStart frame=17 stage=kalman`.
- **D-20**: `app/build.gradle` 에 `testOptions { unitTests.all { testLogging { events 'failed'; exceptionFormat 'full' } } }` 추가.
- **D-21**: 문서 정정 3건 — ROADMAP.md 출하상태 노트, ROADMAP.md 성공기준 2번 캐스케이드 순서 오기, REQUIREMENTS.md TEST-03 동일 오기. (이 세션에서 직접 읽은 결과 ROADMAP.md·REQUIREMENTS.md 모두 이미 정정 반영된 상태로 확인됨 — 계획 단계에서 별도 작업 불필요할 가능성 높음, 계획자가 재확인.)

**Canonical refs (from CONTEXT.md, cross-verified this session):**
- `MedianFilter.kt`: `DEFAULT_WINDOW = 3` (line 22)
- `RssiPreFilter.kt`: `push` returns `Int`
- `KalmanFilter.kt`: wall-clock calls at lines 78 (`update`) and 143 (`injectWarmup`)
- `BleService.kt`: cascade call order at lines 1473~1519; dt basis comment at line 682; debug log at line 2306 unsuitable as fixture source due to `%.1f` rounding
- `DevSettings.kt`: presets and kill switches
- `release.yml`: single job, keystore-verify step as an existing precedent for adding a verification/gating step
- `app/build.gradle`: no product flavors, 2 buildTypes, `junit:junit:4.13.2` present, no `testOptions`/`lint` block, 74 lines

### Claude's Discretion

- 테스트 클래스·파일 이름
- 시나리오 상수명
- 시나리오당 프레임 수
- 아티팩트 이름·보존기간
- `actions/upload-artifact` 버전
- D-07 격리 테스트의 구체 시나리오 구성
- 합성 시나리오 4종의 실제 RSSI 수치

### Deferred Ideas (OUT OF SCOPE)

- 가변 dt 프로필 골든
- `pEmaFilter` 골든
- `prevVel` 폐루프 전체 통합 골든 (Phase 2 범위)
- 6대 초과 UWB 세션 플립 (BUG-03 / v2)
- `.planning/graphs/` 커밋 여부
- `/gsd-profile-user`
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| TEST-03 | 유지보수자는 MedianFilter(3샘플) → RssiPreFilter → KalmanFilter 3단 캐스케이드가 동일 입력 시퀀스에 동일 출력을 내는지 확인할 수 있다 | Code Examples section shows exact cascade wiring order (`medianFilter.push` → `rssiPreFilter.push` → `kf.update`) verified against `BleService.kt:1473-1511`; Common Pitfalls covers Double-vs-Int tolerance mismatch and cold-start/warmup state divergence that a naive golden test would miss |
| TEST-04 | 위 테스트들은 Android 프레임워크·실기기 없이 JVM 유닛 테스트로 실행된다 | Architecture Patterns confirms all 3 target classes are pure Kotlin with zero `android.*` imports — eligible for `src/test/` (JVM unit test), not `src/androidTest/` (instrumentation); Environment Availability confirms local + CI JDK 17 toolchain resolves correctly for `testDebugUnitTest` |
| CI-01 | 골든 테스트가 GitHub Actions 빌드에서 자동 실행되고, 실패 시 빌드가 차단된다 | Architecture Patterns / Code Examples show exact `release.yml` insertion point and step syntax; Common Pitfalls covers why placement before "Build debug APK" and after "Restore google-services.json" is load-bearing, not arbitrary |
| CI-02 | 유지보수자는 CI 실행 결과에서 어떤 테스트가 왜 깨졌는지 실기 없이 판별할 수 있다 (테스트 리포트 아티팩트 보존) | Code Examples shows `testOptions.unitTests.all.testLogging` block (console-visible failure detail) + `actions/upload-artifact@v7` step with `if: always()` for both HTML and JUnit XML reports |
</phase_requirements>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| 3단 RSSI 필터 캐스케이드 결정성 고정 (TEST-03) | Application logic (pure Kotlin, no Android framework) | — | `MedianFilter`/`RssiPreFilter`/`KalmanFilter` have zero `android.*` imports; they are pure functions over `Int`/`Double` state, making them JVM-testable in isolation from the BLE/service tier that calls them |
| JVM 실행 격리 (TEST-04) | Build tooling (Gradle test source set) | — | Test source set (`src/test/`) vs instrumentation source set (`src/androidTest/`) is a Gradle/AGP build-tier concern, not application logic |
| CI 게이트 (CI-01) | CI/CD pipeline (GitHub Actions) | Build tooling (Gradle) | The gate itself is a workflow-orchestration concern (step ordering, exit-code propagation) layered on top of the Gradle task that actually runs the tests |
| 실패 진단 아티팩트 보존 (CI-02) | CI/CD pipeline (GitHub Actions: artifact upload) | Build tooling (Gradle: report generation) | Gradle generates the HTML/XML reports as a build-tier side effect; GitHub Actions is responsible for persisting them past job teardown |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| JUnit 4 | 4.13.2 (already in `app/build.gradle:71` `[VERIFIED: app/build.gradle:71]`) | Test framework for JVM unit tests | Already the project's declared test dependency; AGP's `testDebugUnitTest` task is built around JUnit 4/JUnit Platform out of the box — no reason to introduce JUnit 5 or a second framework for a 3-class, ~20-scenario test surface |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `actions/upload-artifact` | v7 (v7.0.1, released 2026-04-10) `[CITED: web — see Sources]` | Persist test reports past job teardown for CI-02 | Use `@v7` to match the repo's existing convention of pinning current-major GitHub Actions (`actions/checkout@v7`, `actions/setup-java@v5` `[VERIFIED: .github/workflows/release.yml]`). `v4.x` remains functional but is several majors behind; picking it now would mean starting Phase 1 already on a stale pin. |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Inline Kotlin array constants for fixtures (D-11, locked) | JSON/resource-file fixtures | User explicitly locked out resource files — inline constants keep the diff-reviewable, git-blame-friendly property D-12 requires for manual refreeze review. Not a discretionary choice; documented for planner context only. |
| JUnit 4 | JUnit 5 (Jupiter) | JUnit 5 needs an additional Gradle test engine dependency and `useJUnitPlatform()`; project already has zero test infrastructure and JUnit 4.13.2 declared — adding JUnit 5 now is unjustified scope for a 3-class golden-test phase |
| Manual `testDebugUnitTest` step in `release.yml` | A separate `ci.yml` workflow triggered on `pull_request` | D-13 is locked: no PR workflow exists in this repo (single `master` branch, tag-triggered releases only) — a PR-triggered workflow would never fire |

**Installation:**
No new dependencies to install — `junit:junit:4.13.2` is already present in `app/build.gradle`. The only `build.gradle` change is the `testOptions` block (D-20, config, not a dependency).

**Version verification:** `junit:junit:4.13.2` confirmed present via direct file read `[VERIFIED: app/build.gradle:71]` — this session did not re-query the Maven registry for a newer JUnit 4.x patch since the version is already pinned and locked by existing project usage (out of scope to bump it in this phase). `actions/upload-artifact@v7` confirmed via WebSearch this session `[CITED: web]`, cross-referenced against the repo's own existing pins for `actions/checkout@v7` and `actions/setup-java@v5` `[VERIFIED: .github/workflows/release.yml]` for internal consistency.

## Package Legitimacy Audit

This phase installs **no new external packages**. `junit:junit:4.13.2` is an existing dependency, not a new install (Maven/Gradle coordinate, not npm/PyPI/crates — outside the `package-legitimacy check` tool's ecosystem scope, and not newly introduced regardless). `actions/upload-artifact` is a GitHub Actions marketplace action, not a language-ecosystem package — also outside the npm/pypi/crates legitimacy-check tool scope. It is Anthropic/Microsoft's own first-party `actions/*` org action (same publisher as `actions/checkout` and `actions/setup-java`, both already trusted in this repo's workflow), so no separate legitimacy audit is warranted.

| Package | Registry | Age | Downloads | Source Repo | Verdict | Disposition |
|---------|----------|-----|-----------|-------------|---------|-------------|
| — | — | — | — | — | — | No new packages — N/A |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

## Architecture Patterns

### System Architecture Diagram

```
[Golden test source, inline Kotlin arrays: raw RSSI ints]
        │
        ▼
  MedianFilter.push(deviceId, rssi) ──► medianValue: Int
        │
        ▼
  RssiPreFilter.push(deviceId, medianValue, prevVel=0.0, fallBoost=false) ──► preFiltered: Int
        │
        ▼
  KalmanFilter(preset=NORMAL, nowMs = { fixedFakeClock }).update(preFiltered, imuQScale=1.0)
        │                                              ▲
        │                                    injected time seam (D-01) —
        │                                    advances by fixed 120ms/frame (D-02),
        │                                    NOT real wall clock
        ▼
  Pair(kfRssi: Double, kfVel: Double) ──► compared against frozen expected array (D-08 tolerance)
        │
        ▼
  [assertEquals per frame, failure message "scenario/frame=N stage=X" (D-19)]

──────────────────────────────────────────────────────────────────
CI path (separate from the above — orchestration, not data flow):

  git push tag 'v*'
        │
        ▼
  release.yml job "release" (ubuntu-latest)
        │
        ├─ Checkout, Set up JDK 17, Restore google-services.json
        │
        ▼
  [NEW] Run unit tests: ./gradlew testDebugUnitTest --no-daemon --console=plain
        │
        ├─ FAIL ──► job halts here (default GH Actions behavior) ──► "Build debug APK" never runs
        │              │
        │              ▼
        │        [NEW] Upload test reports (if: always()) ──► HTML + JUnit XML persisted as CI artifact
        │
        └─ PASS ──► Restore debug keystore → Verify fingerprint → Build debug APK → Rename → GitHub Release → Firebase PATCH
```

### Recommended Project Structure
```
app/src/test/java/com/wf11/safealert/
└── ble/
    ├── MedianFilterTest.kt        # or a combined RssiCascadeTest.kt per D-19/discretion
    ├── RssiPreFilterTest.kt
    └── KalmanFilterTest.kt        # or RssiCascadeTest.kt covering all 3 stages serially
```
Note: mirrors `com.wf11.safealert.ble` (the **package**, not the `02_ble` **directory** name) `[VERIFIED: app/src/main/java/com/wf11/safealert/02_ble/MedianFilter.kt:1, KalmanFilter.kt:1, RssiPreFilter.kt:1 — package com.wf11.safealert.ble]`. `.planning/codebase/TESTING.md` independently documents this same mapping (Source `.../02_ble/MedianFilter.kt` → Test `.../ble/MedianFilterTest.kt`), corroborating it `[CITED: .planning/codebase/TESTING.md]`.

### Pattern 1: Constructor time-seam for deterministic Kalman tests (D-01)
**What:** Replace direct `System.currentTimeMillis()` calls inside `KalmanFilter` with a constructor-injected lambda defaulting to the same call, so production behavior is unchanged but tests can pass a fake, monotonically-advancing clock.
**When to use:** Any class whose testable output depends on wall-clock delta (`dt`) between calls.
**Example:**
```kotlin
// Source: app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt (verified this session, lines 27, 76-121, 136-144)
class KalmanFilter(
    private var preset: Int = DevSettings.KALMAN_PRESET_NORMAL,
    private val nowMs: () -> Long = { System.currentTimeMillis() }   // NEW seam, D-01
) {
    fun update(filteredRssi: Int, imuQScale: Double = 1.0): Pair<Double, Double> {
        val meas  = filteredRssi.toDouble()
        val now   = nowMs()   // was: System.currentTimeMillis()
        // ... unchanged body ...
    }
    fun injectWarmup(rssiVal: Int, initVel: Double = 0.0) {
        // ... unchanged body ...
        lastTsMs = nowMs()   // was: System.currentTimeMillis()
    }
}

// Test usage — fixed 120ms/frame advance per D-02:
class KalmanFilterTest {
    @Test
    fun `approach scenario cold start`() {
        var fakeNow = 0L
        val kf = KalmanFilter(nowMs = { fakeNow })
        val expected = doubleArrayOf(/* frozen values, D-11 */)
        APPROACH_RSSI_SEQUENCE.forEachIndexed { i, rssi ->
            fakeNow += 120L   // D-02: fixed 120ms dt per frame
            val (r, _) = kf.update(rssi)
            assertEquals("approach/coldStart frame=$i stage=kalman", expected[i], r, 1e-9) // D-08, D-19
        }
    }
}
```

### Anti-Patterns to Avoid
- **Reading `System.currentTimeMillis()` inside a test without the seam:** produces a non-reproducible `dt` per test run, defeating the entire purpose of D-01/D-08's determinism requirement. Always drive `dt` via the injected fake clock, never rely on wall-clock jitter between test statements.
- **Placing the test step after "Build debug APK" in `release.yml`:** would build (and potentially publish) a broken APK before the test failure is even known — defeats CI-01's "차단" (blocking) requirement. Must be strictly before the build step.
- **Placing the test step before "Restore google-services.json":** Gradle's `com.google.gms.google-services` plugin reads `app/google-services.json` during project configuration; without it present, even `testDebugUnitTest` (which doesn't need the file's contents) can fail at Gradle's configuration phase depending on plugin apply order `[CITED: D-14, locked constraint from CONTEXT.md]`.
- **Using `assertEquals` with default `Double` overload for Kalman outputs:** JUnit 4's `assertEquals(double, double)` (no delta) is deprecated and imprecise for floating point; must always pass the explicit delta parameter, D-08's 1e-9, given `dt.pow(4)` at `KalmanFilter.kt:100` `[VERIFIED: app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt:100]` introduces `Math.pow`-based platform variance.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Test report parsing / CI-readable failure output | Custom log-scraping script to extract which assertion failed | AGP's built-in `testOptions.unitTests.all.testLogging { events 'failed'; exceptionFormat 'full' }` (D-20) + JUnit XML report already generated by `testDebugUnitTest` at `app/build/test-results/testDebugUnitTest/` | Gradle/AGP already produces both a human-readable console trace and a machine-readable JUnit XML; a custom scraper would duplicate this and risk missing edge cases (multi-failure classes, parameterized test names) |
| CI artifact retention/download UI | Custom S3 upload + presigned-URL page | `actions/upload-artifact@v7` (GitHub-native, downloadable from the Actions run UI) | GitHub Actions already provides a first-party artifact store with a UI, retention policy, and CLI/API download — no reason to build a parallel one for a report that's only needed by the maintainer diagnosing a CI failure |
| Floating-point golden comparison | Custom epsilon-comparison helper function | JUnit 4's `assertEquals(String, double, double, double)` 4-arg overload (message, expected, actual, delta) | Already exists in the framework already in use; D-08's exact tolerance (1e-9) plugs directly into this without any wrapper |

**Key insight:** This phase's entire tooling surface (JUnit 4, Gradle `testOptions`, `actions/upload-artifact`) is already either present in the project or a first-party GitHub Action — there is no gap that justifies introducing new tooling, only wiring.

## Common Pitfalls

### Pitfall 1: `dt` clamping silently changes expected behavior for extreme fixtures
**What goes wrong:** `KalmanFilter.update()` computes `dt = ((nowMs - lastTsMs) / 1000.0).coerceIn(0.05, 2.0)` `[VERIFIED: app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt — coerceIn(0.05, 2.0), read this session in prior turn]`. If a test author accidentally advances the fake clock by less than 50ms or more than 2000ms per frame, `dt` silently clamps rather than reflecting the intended step size, producing fixtures that don't actually exercise the "normal ~120ms" path D-02 specifies.
**Why it happens:** The clamp exists to guard production code against clock jumps (app backgrounding, etc.) — it is correct production behavior, but a test author unaware of it may pick a convenient round fake-clock increment (e.g. 100ms or 1000ms) that happens to fall inside the clamp range without realizing it isn't testing the actual 120ms cadence.
**How to avoid:** Always advance the fake clock by exactly 120ms per synthetic frame (D-02, locked), and add a comment referencing `BleService.kt:682`'s "정상 주기 ~120ms" as the source of that constant so future maintainers don't "round it off" during a refreeze.
**Warning signs:** A test that passes with both 100ms and 120ms increments interchangeably indicates the assertions aren't sensitive to `dt` at all — likely a sign the covariance-driven Kalman gain terms aren't actually being exercised across frames.

### Pitfall 2: Cold-start vs `injectWarmup` covariance divergence breaks a single shared fixture set
**What goes wrong:** `pRR` initializes to `5.0` on first `update()` call (cold start, `KalmanFilter.kt` inside the `!initialized` branch) but to `25.0` inside `injectWarmup()` `[VERIFIED: app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt — pRR = 5.0 cold-start vs pRR = 25.0 in injectWarmup, comment "(v1.1.29) 1.0 → 25.0", read this session in prior turn]`. A single golden fixture array reused for both starting paths will silently diverge in Kalman gain (`kR`, `kV`) for the first several frames, producing different `rssi`/`vel` trajectories even for identical input sequences.
**Why it happens:** These are two intentionally different entry points for two different real-world situations (first-ever observation of a device vs. a warm-restart re-inject after a brief signal loss) — the covariance values are tuned differently on purpose (v1.1.29 changelog note in-code confirms this was a deliberate tuning decision, not an oversight).
**How to avoid:** D-06 already locks this — maintain 2 separate starting-state fixture sets (cold-start and injectWarmup), each with its own frozen expected-output array. Do not attempt to unify them into one parameterized fixture without also parameterizing the initial `pRR`/`pVV` values and re-deriving separate expected outputs for each.
**Warning signs:** A refreeze that "accidentally" produces identical expected arrays for both starting states is a sign the fixture is not actually driving two different code paths — check that the test actually calls `injectWarmup()` before the loop for the warmup variant.

### Pitfall 3: `RssiPreFilter`'s cold-start bypass returns raw input, not EMA output
**What goes wrong:** `RssiPreFilter.push()`'s first call for a given `deviceId` initializes internal state to the raw `rssi` and returns it directly, bypassing the EMA math entirely (`app/src/main/java/com/wf11/safealert/02_ble/RssiPreFilter.kt` lines 85-89, read in prior turn). A test author assuming every call runs through `alphaRise`/`alphaFall` smoothing will get a mismatched first-frame expected value.
**Why it happens:** This is a standard cold-start bootstrap pattern (there's no prior EMA state to blend with on the very first sample) — correct and intentional, but easy to overlook when hand-deriving expected values instead of capturing them by actually running the code once.
**How to avoid:** Since D-09 locks record-then-freeze (capture actual current output, don't hand-calculate expected math), this pitfall is naturally avoided IF the test author runs the fixture through the real code once and copies the output — the risk is only if someone tries to shortcut by hand-computing expected EMA values instead.
**Warning signs:** A hand-derived expected value for frame 0 that doesn't equal the raw input RSSI is a red flag — frame 0 of any fresh `deviceId` should always equal the input exactly for `RssiPreFilter`.

### Pitfall 4: Test step placement relative to `google-services.json` restore is a hard ordering constraint, not a style choice
**What goes wrong:** Moving the new test step earlier in the workflow (e.g., right after checkout, to "fail fast" before spending time on JDK setup) breaks the Gradle configuration phase because the `google-services` plugin expects `app/google-services.json` to exist when Gradle evaluates the project, which happens for ANY Gradle task invocation including `testDebugUnitTest`.
**Why it happens:** AGP/Gradle plugins run configuration-phase code for the whole project graph regardless of which specific task is requested — you can't invoke `testDebugUnitTest` in isolation from plugin configuration that assumes `google-services.json` is present.
**How to avoid:** D-14 already locks the exact position (`[VERIFIED: .github/workflows/release.yml:16-26]` "Restore google-services.json" step, then immediately the new test step, before `[VERIFIED: .github/workflows/release.yml:49]` "Build debug APK"). Do not "optimize" by moving it earlier.
**Warning signs:** A CI failure log showing a Gradle configuration-phase error (not a test-execution error) mentioning `google-services.json` missing is the signature of this pitfall.

## Code Examples

### `release.yml` insertion (D-14, D-15, D-17, D-18)
```yaml
# Source: .github/workflows/release.yml, verified this session (full 70-line read)
# Existing steps for context (verbatim, lines 22-49):
      - name: Restore google-services.json
        run: echo "${{ secrets.GOOGLE_SERVICES_JSON }}" | base64 --decode > app/google-services.json

      # ↓↓↓ NEW STEP — insert here, per D-14 ↓↓↓
      - name: Run unit tests (golden RSSI cascade)
        run: ./gradlew testDebugUnitTest --no-daemon --console=plain     # D-15, exact string

      - name: Upload test reports
        if: always()                                                     # D-18
        uses: actions/upload-artifact@v7
        with:
          name: unit-test-reports
          path: |
            app/build/reports/tests/testDebugUnitTest/
            app/build/test-results/testDebugUnitTest/
          retention-days: 14                                             # discretionary (D-17 only mandates full-report upload; retention value is Claude's Discretion)
      # ↑↑↑ NEW STEP ↑↑↑

      - name: Grant execute permission
        run: chmod +x gradlew
      # ... existing "Extract version" step ...
      - name: Build debug APK
        run: ./gradlew assembleDebug --no-daemon --console=plain
```
Note: `chmod +x gradlew` currently runs AFTER "Restore google-services.json" and BEFORE "Extract version"/"Build debug APK" `[VERIFIED: .github/workflows/release.yml — full read this session]`. The new test step invokes `./gradlew`, so it must be placed AFTER `chmod +x gradlew` grants execute permission — i.e., the actual insertion point is between "Grant execute permission" and "Extract version"/"Build debug APK", not literally immediately after "Restore google-services.json" as a same-instant step. This does not conflict with D-14 (still same job, still after google-services.json restore, still before Build debug APK) — it is a necessary refinement the planner must account for.

### `app/build.gradle` testOptions addition (D-20)
```gradle
// Source: app/build.gradle, verified this session (74-line full read, no existing testOptions block)
android {
    // ... existing namespace, compileSdk, defaultConfig, buildTypes blocks unchanged ...

    testOptions {
        unitTests.all {
            testLogging {
                events 'failed'
                exceptionFormat 'full'
            }
        }
    }
}
```
Note: this repo's Gradle files are Groovy DSL (`build.gradle`, not `build.gradle.kts`) `[VERIFIED: build.gradle (root) — Groovy `plugins { id '...' version '...' apply false }` syntax, no `.kts` file present]`, so method-call-style property assignment (`exceptionFormat 'full'`, no `=`) is valid; a Kotlin-DSL project would require `exceptionFormat = "full"` instead — not applicable here, but worth flagging if this pattern is ever copy-pasted into a `.kts` file.

### Cascade wiring reference (for TEST-03 correctness, not a change site)
```kotlin
// Source: app/src/main/java/com/wf11/safealert/03_service/BleService.kt, verified this session (prior turn, lines 1473-1519)
val medianValue  = medianFilter.push(deviceId, inputRssi)                                    // line 1473
// ... shadow IMU fusion logic, out of D-05 scope, lines 1475-1500 ...
val preFiltered  = rssiPreFilter.push(deviceId, medianValue, prevVel, fallBoost = shadowBoost) // line 1507
val (kfRssi, kfVel) = kf.update(preFiltered, ImuFusion.adaptiveQFactor)                        // line 1511
// pEmaFilter stage (line 1519) is a 4th stage explicitly OUT of Phase 1 scope per D-05
```
This confirms the golden test's call order (`MedianFilter.push` → `RssiPreFilter.push` → `KalmanFilter.update`) matches production wiring exactly, and confirms `prevVel`/`adaptiveQFactor`/`fallBoost` are the exact three parameters D-05 fixes to constants (0.0, 1.0, and — implicitly — `false`, matching the "cascade in isolation" boundary).

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| `actions/upload-artifact@v3` (download-incompatible with v4+) | `actions/upload-artifact@v7` | v4.0.0 was the last breaking change for the v3→v4 download-API switch; v5.0.0 added Node24 runtime requirement; current is v7.0.1 (2026-04-10) `[CITED: web]` | Any planner guidance or training-data memory defaulting to "`@v4`" is now 3 majors behind; use `@v7` to match this repo's existing `actions/checkout@v7`/`actions/setup-java@v5` convention |
| Manual `println`/log-scrape for JVM test failure diagnosis | `testOptions.unitTests.all.testLogging { events 'failed'; exceptionFormat 'full' }` | Standard AGP/Gradle DSL feature, not a recent change — confirmed valid syntax for this repo's AGP 8.3.2 `[VERIFIED: build.gradle (root) — com.android.application version 8.3.2]` | Ensures CI console output shows full exception detail without requiring a maintainer to download and open the HTML report for every failure |

**Deprecated/outdated:**
- `actions/upload-artifact@v3`: fully deprecated (GitHub sunset v3 uploads); do not use even as a "safe conservative" fallback.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `actions/upload-artifact@v7` is the correct current major to pin | Standard Stack, Code Examples | Low — if a newer major ships between research and execution, the planner/executor should re-check via `npm`-equivalent (GitHub releases page) before pinning; using a stale-but-functional major (v6 or v7) would not break the phase's functional requirements (CI-02), only miss a convention-consistency nicety |
| A2 | `retention-days: 14` for uploaded test reports | Code Examples | None functional — D-17 only mandates the artifact be uploaded in full; retention length is explicitly Claude's Discretion per CONTEXT.md and does not affect CI-01/CI-02 satisfaction |
| A3 | D-21's 3 documentation corrections are already applied and require no further action | User Constraints / Summary | Low — verified via direct read of current ROADMAP.md and REQUIREMENTS.md this session showing corrected text already in place; if the planner's independent re-check disagrees, only a trivial doc-edit task would need to be added, not a rework of any code task |

## Open Questions

1. **Should the golden tests be one combined `RssiCascadeTest.kt` or three separate per-class test files?**
   - What we know: CONTEXT.md leaves file/class naming to Claude's Discretion; D-19's message convention (`scenario/frame=N stage=X`) implies a single logical test flow spans all 3 `stage` values (median/prefilter/kalman) per scenario, which reads naturally as one combined cascade test class per scenario category, with possibly a fourth file for D-07's isolated cross-device contamination test.
   - What's unclear: whether the planner prefers granular per-class files (better for isolating which single stage broke, independent of the others) vs. one cascade file (simpler to reason about "same input sequence, same output" end-to-end per TEST-03's literal wording).
   - Recommendation: One `RssiCascadeTest.kt` covering all 4 synthetic scenarios (D-10) × 2 starting states (D-06) = up to 8 test methods, each asserting all 3 stages per frame with the D-19 message format identifying which stage failed. This directly matches TEST-03's "3단 캐스케이드가 동일 입력 시퀀스에 동일 출력을 내는지" framing (the requirement is about the whole cascade, not each stage in isolation) while still pinpointing the failing stage via the message convention. A separate `RssiCascadeIsolationTest.kt` (or similarly named) can hold the D-07 cross-device fixture.

2. **Exact RSSI numeric values for the 4 synthetic scenarios (D-10) are Claude's Discretion — should research propose concrete numbers, or leave this to planning/execution?**
   - What we know: D-10 requires 4 scenario categories (approach, departure, impulse spike, stationary) with no real-device log capture; D-11 requires the actual expected outputs to be captured by running the real code once (record-then-freeze, D-09), not hand-calculated.
   - What's unclear: the specific input RSSI integers per frame per scenario aren't determinable by research alone — they must be authored during plan execution (a task action, not a research finding), since the "expected" half of each fixture can only be produced by actually executing the current `MedianFilter`/`RssiPreFilter`/`KalmanFilter` code against chosen inputs.
   - Recommendation: The planner should include an explicit task step that (a) writes a small throwaway JVM script or test-driven capture harness feeding representative RSSI sequences (e.g., monotonically decreasing ints for approach, increasing for departure, one large jump-and-return for impulse spike, near-constant with ±1 noise for stationary) through the real cascade, (b) captures the printed/asserted output, and (c) pastes those captured values into the frozen inline array constants — this two-step "generate then freeze" sequence is implied by D-09/D-11 but should be made an explicit plan task so the executor doesn't try to hand-derive Kalman math instead.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Gradle wrapper | `./gradlew testDebugUnitTest` locally and in CI | Yes `[VERIFIED: gradlew, gradlew.bat present in repo root]` | 8.6 `[VERIFIED: gradle/wrapper/gradle-wrapper.properties — distributionUrl=https\://services.gradle.org/distributions/gradle-8.6-bin.zip]` | — |
| JDK 17 (local dev machine) | Gradle toolchain (`sourceCompatibility VERSION_17`) | Yes, via `JAVA_HOME` | Eclipse Adoptium 17.0.19 `[VERIFIED: this session — JAVA_HOME env var and direct `java -version` invocation at that path both confirmed]` | Bare `java` on PATH resolves to an older Oracle 1.8.0_501 first — this is NOT a blocker since Gradle honors `JAVA_HOME` over PATH, but is worth a note so a future maintainer isn't confused by a naive `java -version` check on this machine |
| JDK 17 (CI runner) | Same Gradle toolchain requirement | Yes | Installed fresh each run via `actions/setup-java@v5` with `java-version: '17'`, `distribution: 'temurin'` `[VERIFIED: .github/workflows/release.yml]` | — |
| ADB-connected physical/emulated device | Real-device smoke verification of the seam (D-04) | No — 0 devices connected `[per CONTEXT.md D-04, re-confirmed this session's environment: no device probing tools invoked since D-04 already locks diff-review-only verification]` | — | D-04 already locks the fallback: diff review only, no real-device run required for this phase |
| `app/src/test/` source set | TEST-04 (JVM unit test execution) | No — does not exist yet | — | This phase creates it; not a blocker, but the planner must include directory/file creation as a task, not assume it pre-exists |

**Missing dependencies with no fallback:**
- None — all required tooling (Gradle wrapper, JDK 17 locally and in CI) is present and functional.

**Missing dependencies with fallback:**
- ADB-connected device: fallback is diff-review-only verification (D-04, locked decision, not a research-proposed workaround).

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4.13.2 `[VERIFIED: app/build.gradle:71]` |
| Config file | none yet — `testOptions` block to be added per D-20; no `junit-platform.properties` needed (JUnit 4, not 5) |
| Quick run command | `./gradlew testDebugUnitTest --console=plain --tests "com.wf11.safealert.ble.*"` |
| Full suite command | `./gradlew testDebugUnitTest --no-daemon --console=plain` (D-15, exact CI command) |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| TEST-03 | 3-stage cascade produces same output for same input sequence | unit (golden) | `./gradlew testDebugUnitTest --tests "com.wf11.safealert.ble.RssiCascadeTest"` | ❌ Wave 0 — must be created |
| TEST-04 | Tests run as JVM unit tests, no Android framework/device | unit (infrastructure) | `./gradlew testDebugUnitTest` (task existing by AGP default, no test files yet) | ❌ Wave 0 — `app/src/test/` source directory does not exist |
| CI-01 | GitHub Actions runs tests, blocks build on failure | CI/workflow | N/A — verified by workflow YAML structure + a deliberate red-test trial run during execution/verification, not a `pytest`-style command | ❌ Wave 0 — `release.yml` step not yet added |
| CI-02 | Maintainer can diagnose failure from CI artifacts alone | CI/workflow | N/A — verified by inspecting uploaded artifact contents after a deliberate red-test trial run | ❌ Wave 0 — `testOptions` block + artifact upload step not yet added |

### Sampling Rate
- **Per task commit:** `./gradlew testDebugUnitTest --console=plain` (fast — 3 pure Kotlin classes, no Android instrumentation, expect sub-10-second runs)
- **Per wave merge:** same command (this phase has no separate "full suite" distinct from the quick run — the entire test surface IS this phase's golden tests)
- **Phase gate:** Full suite green locally before pushing a tag that triggers `release.yml`; then a deliberate CI dry-run (push a throwaway tag, or temporarily break a fixture) is the only way to prove CI-01/CI-02's actual blocking/diagnostic behavior, since these are workflow-level properties not expressible as a single local unit-test assertion.

### Wave 0 Gaps
- [ ] `app/src/test/java/com/wf11/safealert/ble/` — directory does not exist yet, must be created
- [ ] `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` (or per-class equivalents) — covers TEST-03
- [ ] `app/build.gradle` `testOptions` block — covers CI-02 (console diagnostics), D-20
- [ ] `.github/workflows/release.yml` test-execution step — covers CI-01
- [ ] `.github/workflows/release.yml` artifact-upload step — covers CI-02 (persisted reports)
- [ ] No shared `conftest`-equivalent needed — JUnit 4 has no cross-file fixture-sharing requirement for this small a surface; each test class is self-contained per D-11's inline-constants approach

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No | Phase touches no auth surface |
| V3 Session Management | No | Phase touches no session surface |
| V4 Access Control | No | Phase touches no access-control surface |
| V5 Input Validation | No | Filter cascade already exists in production; this phase adds tests for existing logic, not new input-handling code, and the only production change (time seam) has a fixed default that preserves current behavior |
| V6 Cryptography | No | No cryptographic code touched |
| V14 Configuration (CI/CD & secrets hygiene — closest applicable ASVS category for this phase) | Yes | See Known Threat Patterns below |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| CI artifact accidentally leaking secrets (e.g., a stray `println` of an env var ending up in a test report) | Information Disclosure | The new test step and its uploaded artifacts (`app/build/reports/tests/testDebugUnitTest/`, `app/build/test-results/testDebugUnitTest/`) contain only test names, assertion messages, and stack traces from pure `MedianFilter`/`RssiPreFilter`/`KalmanFilter` logic — none of these classes read secrets, environment variables, or the restored `google-services.json`/keystore files `[VERIFIED: app/src/main/java/com/wf11/safealert/02_ble/MedianFilter.kt, RssiPreFilter.kt, KalmanFilter.kt — no env/secret reads in any of the three files as read this session]`. No new secret-handling code is introduced by this phase; existing `GOOGLE_SERVICES_JSON`, `DEBUG_KEYSTORE`, `FIREBASE_DB_SECRET` secrets are untouched — the new step is inserted between existing steps and does not reference any secret. |
| A permissive CI gate that appears to block but doesn't (e.g., a test step configured to always exit 0) | Tampering (of the build's integrity guarantee) | Do not add `continue-on-error: true` or `|| true` to the test-execution step — GitHub Actions' default (non-zero exit halts the job) is exactly the CI-01 requirement; any explicit "always succeed" override would silently defeat the entire phase's purpose. |
| Artifact retention exposing internals longer than necessary | Information Disclosure (low severity — test reports contain no secrets per above, but general hygiene) | A finite `retention-days` (not the repo-wide 90-day default) is a reasonable, non-mandatory hardening; D-17 does not require a specific retention value, so this is Claude's Discretion, not a security-blocking finding. |

No High-severity ASVS findings apply to this phase; `security_block_on: "high"` `[VERIFIED: .planning/config.json]` is not triggered by any finding above.

## Sources

### Primary (HIGH confidence)
- Direct file reads this session and prior turn of this session: `app/src/main/java/com/wf11/safealert/02_ble/MedianFilter.kt`, `RssiPreFilter.kt`, `KalmanFilter.kt`, `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (targeted slices), `app/src/main/java/com/wf11/safealert/06_utils/DevSettings.kt` (grep, lines 29-35), `.github/workflows/release.yml` (full, read twice), `app/build.gradle` (full), `build.gradle` (root, full), `.planning/config.json` (full), `.planning/ROADMAP.md` (full), `.planning/REQUIREMENTS.md` (full), `.planning/STATE.md` (full), `.planning/phases/01-ci/01-CONTEXT.md` (full), `.planning/codebase/TESTING.md` (full)
- Environment probes this session: `JAVA_HOME` env var, direct JDK 17 `java -version` invocation, `gradle-wrapper.properties` read, `ls app/src/` confirming no `test`/`androidTest` directories yet

### Secondary (MEDIUM confidence)
- WebSearch (provider: brave, verified this session) — `actions/upload-artifact` current major version (v7.0.1) and retention-days behavior
- WebSearch (provider: brave, verified this session) — Gradle/AGP `testOptions.unitTests.all.testLogging` syntax for AGP 8.3

### Tertiary (LOW confidence)
- None — all findings this session reached at least MEDIUM (WebSearch verified) or HIGH (direct file read) confidence; no unverified training-only claims remain in this document outside the explicitly tagged Assumptions Log entries.

## Metadata

**Confidence breakdown:**
- Standard Stack: HIGH — JUnit 4.13.2 confirmed via direct file read; `actions/upload-artifact@v7` confirmed via verified WebSearch this session (corrected from a stale training-data assumption of v4)
- Architecture: HIGH — cascade wiring, package/directory naming mismatch, and time-seam insertion point all confirmed via direct source reads with verbatim quotes captured
- Pitfalls: HIGH — all 4 pitfalls trace to specific verified code lines (dt clamp, pRR divergence, cold-start bypass, workflow ordering), not speculative concerns

**Research date:** 2026-08-24
**Valid until:** 2026-09-23 (30 days — stable domain: JUnit 4/Gradle/GitHub Actions conventions change slowly, but `actions/upload-artifact`'s major version should be re-checked if this research is reused after that window)
