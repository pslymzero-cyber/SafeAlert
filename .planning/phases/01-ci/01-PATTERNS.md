# Phase 1: 테스트 하네스와 CI 회귀 게이트 - Pattern Map

**Mapped:** 2026-08-24
**Files analyzed:** 6 (1 modified production, 1 modified build config, 1 modified CI workflow, 2-3 new test files, 1 new test directory)
**Analogs found:** 3 / 6 (production code has strong analogs; test files have none — this phase creates the first test infra in the repo)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|--------------------|------|-----------|-----------------|---------------|
| `app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt` (modify: add `nowMs` seam) | service (pure calc) | transform | itself (existing file, minimal diff) | exact — self-analog |
| `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` (or per-class) | test | transform (golden/record-replay) | **none in repo** — first JVM unit test | no analog (repo has 0 tests) |
| `app/src/test/java/com/wf11/safealert/ble/RssiCascadeIsolationTest.kt` (D-07 cross-device) | test | transform | **none in repo** | no analog |
| `app/build.gradle` (add `testOptions` block) | config | — | itself (existing file, additive block) | exact — self-analog |
| `.github/workflows/release.yml` (insert 2 steps) | config (CI workflow) | request-response (job pipeline) | `Verify keystore fingerprint` step (same file, lines 34-39) | exact — same-file precedent for a hard-gate step |

## Pattern Assignments

### `app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt` (production, time-seam only)

**Analog:** itself — this is a targeted, minimal-diff edit, not a new-file-from-analog situation.

**Current constructor and wall-clock call sites** (lines 27, 78, 88, 143):
```kotlin
class KalmanFilter(private var preset: Int = DevSettings.KALMAN_PRESET_NORMAL) {
    ...
    fun update(filteredRssi: Int, imuQScale: Double = 1.0): Pair<Double, Double> {
        val meas  = filteredRssi.toDouble()
        val nowMs = System.currentTimeMillis()   // line 78 — seam target #1
        ...
            lastTsMs    = nowMs                   // line 88 — set from local var, no second call needed
        ...
    }
    fun injectWarmup(rssiVal: Int, initVel: Double = 0.0) {
        ...
        lastTsMs    = System.currentTimeMillis()  // line 143 — seam target #2
    }
}
```

**Required change (D-01, exact signature from CONTEXT.md/RESEARCH.md):**
```kotlin
class KalmanFilter(
    private var preset: Int = DevSettings.KALMAN_PRESET_NORMAL,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    ...
    fun update(filteredRssi: Int, imuQScale: Double = 1.0): Pair<Double, Double> {
        val meas  = filteredRssi.toDouble()
        val now   = nowMs()          // was: System.currentTimeMillis()
        ...
            lastTsMs    = now
        ...
    }
    fun injectWarmup(rssiVal: Int, initVel: Double = 0.0) {
        ...
        lastTsMs    = nowMs()        // was: System.currentTimeMillis()
    }
}
```

**Rule:** D-03 — this is the *only* production file touched in Phase 1. No other renames, no visibility changes, no logic changes. `updateCnt`, `reset()`, `updatePreset()`, `estimatedRssi`/`estimatedVel`/`isInitialized`/`updateCount` getters, and all numeric constants (`pRR=5.0`/`pVV=5.0` cold-start, `pRR=25.0`/`pVV=5.0` warmup, `dt.coerceIn(0.05, 2.0)`, `dt.pow(4)` etc.) stay byte-for-byte identical — the golden fixtures freeze these values as-is (D-09).

**Two starting-state divergence the golden tests MUST cover separately (D-06):**
```kotlin
// Cold start (inside update(), !initialized branch, lines 81-90):
pRR = 5.0; pRV = 0.0; pVV = 5.0

// injectWarmup() (lines 136-144):
pRR = 25.0; pRV = 0.0; pVV = 5.0   // (v1.1.29) intentionally different
```

---

### `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` (new, no analog)

**No existing analog** — `app/src/test/` and `app/src/androidTest/` do not exist in this repo (confirmed via RESEARCH.md environment probe: `ls app/src/` shows neither directory). This is the first JVM unit test file ever added to SafeAlert. Use RESEARCH.md's `Code Examples` section as the structural template instead of a codebase analog.

**Package placement (load-bearing, not discretionary):** mirrors the **package** declaration `com.wf11.safealert.ble` found at the top of all 3 target source files, NOT the `02_ble` **directory** name:
```kotlin
// app/src/main/java/com/wf11/safealert/02_ble/MedianFilter.kt:1
// app/src/main/java/com/wf11/safealert/02_ble/RssiPreFilter.kt:1
// app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt:1
package com.wf11.safealert.ble
```
→ Test file path: `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` (directory `ble/`, not `02_ble/`).

**Core cascade wiring to replicate exactly** (verified against `BleService.kt:1473-1519`, this is TEST-03's contract):
```kotlin
val medianValue  = medianFilter.push(deviceId, inputRssi)
val preFiltered  = rssiPreFilter.push(deviceId, medianValue, prevVel = 0.0, fallBoost = false)  // D-05: prevVel fixed 0.0
val (kfRssi, kfVel) = kf.update(preFiltered, imuQScale = 1.0)                                    // D-05: imuQScale fixed 1.0
// pEmaFilter stage explicitly excluded per D-05
```

**Time-seam usage in tests (D-01, D-02 — 120ms fixed dt):**
```kotlin
var fakeNow = 0L
val kf = KalmanFilter(nowMs = { fakeNow })
INPUT_SEQUENCE.forEachIndexed { i, rssi ->
    fakeNow += 120L   // D-02: fixed 120ms/frame, sourced from BleService.kt:682 "정상 주기 ~120ms"
    val medianValue = medianFilter.push(DEVICE_ID, rssi)
    val preFiltered = rssiPreFilter.push(DEVICE_ID, medianValue, 0.0, false)
    val (kfRssi, kfVel) = kf.update(preFiltered)
    assertEquals("approach/coldStart frame=$i stage=kalman", EXPECTED_RSSI[i], kfRssi, 1e-9)  // D-08, D-19
}
```

**Assertion tolerance split by return type (D-08):**
```kotlin
// MedianFilter / RssiPreFilter: Int, exact match, no delta
assertEquals("approach/coldStart frame=$i stage=median", EXPECTED_MEDIAN[i], medianValue)
assertEquals("approach/coldStart frame=$i stage=prefilter", EXPECTED_PREFILTER[i], preFiltered)

// KalmanFilter: Double, 1e-9 delta (JUnit 4's 4-arg assertEquals(String, double, double, double))
assertEquals("approach/coldStart frame=$i stage=kalman", EXPECTED_KALMAN_RSSI[i], kfRssi, 1e-9)
```

**Failure message convention (D-19, mandatory format):** `"<scenario>/<startState> frame=<i> stage=<median|prefilter|kalman>"` — e.g. `"impulseSpike/warmup frame=4 stage=median"`. This is the only way to satisfy CI-02's "which test broke and why" requirement without opening the HTML report.

**Cold-start bypass pitfall (frame 0 of any fresh deviceId):**
```kotlin
// RssiPreFilter.kt lines 84-89 — first push for a deviceId returns raw input unchanged, bypasses EMA
val prev = emaState[deviceId] ?: run {
    emaState[deviceId] = rssi.toDouble()
    pushCount[deviceId] = 1
    return rssi   // frame 0 expected == input exactly
}
```

---

### `app/src/test/java/com/wf11/safealert/ble/RssiCascadeIsolationTest.kt` (new, D-07 cross-device fixture)

**No analog** — same rationale as above. Structural template: reuse `RssiCascadeTest.kt`'s cascade-wiring pattern but drive **two `deviceId` values through the same filter instances** to prove state does not leak across devices (`MedianFilter`, `RssiPreFilter`, `KalmanFilter` all key their internal maps/state by `deviceId` — see `buffers`/`emaState`/`pushCount` map declarations in each source file; `KalmanFilter` itself has no internal `deviceId` map because `BleService` instantiates one `KalmanFilter` per device — confirm this instantiation-per-device assumption against `BleService.kt` if the isolation test needs a shared-instance scenario).

---

### `app/build.gradle` (add `testOptions` block, D-20)

**Analog:** itself — additive block inside the existing `android { }` closure, no restructuring.

**Insertion point** (after `buildFeatures` block, lines 47-50, still inside `android { }`):
```gradle
android {
    // ... namespace, compileSdk, defaultConfig, signingConfigs, buildTypes, compileOptions, kotlinOptions, buildFeatures unchanged ...

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
**Convention match:** Groovy DSL, method-call-style property assignment (`exceptionFormat 'full'`, no `=`) — matches this file's existing style (`storeFile file(...)`, `minifyEnabled false`) at lines 24, 36. No dependency changes needed — `testImplementation 'junit:junit:4.13.2'` already present at line 71.

---

### `.github/workflows/release.yml` (insert test-run + artifact-upload steps)

**Analog:** `Verify keystore fingerprint` step (lines 34-39) — same file, established "assert-and-exit-1-on-failure" gate precedent:
```yaml
      # 복원된 keystore 가 로컬 키와 다르면 즉시 실패 — 잘못된 서명 APK 배포 차단
      - name: Verify keystore fingerprint
        run: |
          OUT=$(keytool -list -keystore $HOME/.android/debug.keystore -storepass android -alias androiddebugkey)
          echo "$OUT"
          echo "$OUT" | grep -q "4C:40:F0:35:E4:2C:78:D0:71:34:5F:EB:B6:23:4E:F7:56:F9:11:6B:7C:92:05:00:0A:14:5B:D0:F7:DF:19:21" \
            || { echo "::error::복원된 keystore 가 로컬 키와 다름 — DEBUG_KEYSTORE secret 확인 필요"; exit 1; }
```
This establishes: plain shell step, no `continue-on-error`, no custom action needed, default non-zero exit halts the job — the exact mechanism CI-01 needs.

**Exact insertion point** (verified full-file read this session): NOT immediately after `Restore google-services.json` (lines 25-26) as CONTEXT.md's literal wording might suggest — it must come after `Grant execute permission` (line 41-42, `chmod +x gradlew`) since the new step invokes `./gradlew`. Both constraints (after google-services.json restore, before Build debug APK) remain satisfied because `chmod +x gradlew` sits between them:

```yaml
      - name: Restore google-services.json
        run: echo "${{ secrets.GOOGLE_SERVICES_JSON }}" | base64 --decode > app/google-services.json

      - name: Restore debug keystore (로컬과 동일 서명 → 덮어쓰기 설치 가능)
        run: |
          mkdir -p $HOME/.android
          echo "${{ secrets.DEBUG_KEYSTORE }}" | base64 --decode > $HOME/.android/debug.keystore

      - name: Verify keystore fingerprint
        run: |
          ...

      - name: Grant execute permission
        run: chmod +x gradlew

      # ↓↓↓ NEW — insert here (D-14 refined: after chmod +x gradlew, before Extract version/Build) ↓↓↓
      - name: Run unit tests (golden RSSI cascade)
        run: ./gradlew testDebugUnitTest --no-daemon --console=plain

      - name: Upload test reports
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: unit-test-reports
          path: |
            app/build/reports/tests/testDebugUnitTest/
            app/build/test-results/testDebugUnitTest/
          retention-days: 14
      # ↑↑↑ NEW ↑↑↑

      - name: Extract version
        id: ver
        run: echo "name=${GITHUB_REF_NAME#v}" >> $GITHUB_OUTPUT

      - name: Build debug APK
        run: ./gradlew assembleDebug --no-daemon --console=plain
```

**Version-pin convention match:** repo already pins current-major first-party actions — `actions/checkout@v7`, `actions/setup-java@v5` (lines 17, 20). Use `actions/upload-artifact@v7` to match, not the training-data-stale `@v4`.

## Shared Patterns

### CI hard-gate (exit-1-on-failure, no soft-fail)
**Source:** `.github/workflows/release.yml` lines 34-39 (`Verify keystore fingerprint`)
**Apply to:** the new `Run unit tests` step
**Rule:** never add `continue-on-error: true` or `|| true` — GitHub Actions' default non-zero-exit-halts-job behavior IS the CI-01 requirement. This mirrors the keystore-verify step's `exit 1` pattern exactly (implicit here since `./gradlew testDebugUnitTest` already exits non-zero on any failed assertion).

### `if: always()` for diagnostic steps that must survive a red job
**Source:** no prior example in this repo's `release.yml` (all existing steps are unconditional/serial) — RESEARCH.md Code Examples is the primary source here, not a codebase analog.
```yaml
      - name: Upload test reports
        if: always()
```
**Apply to:** artifact-upload step only. Do not add `if: always()` to the test-run step itself or any step after Build debug APK.

### Korean inline comments explaining WHY, not WHAT (project-wide convention, `CLAUDE.md`)
**Source:** every file read in this phase (`KalmanFilter.kt`, `RssiPreFilter.kt`, `MedianFilter.kt`, `release.yml`) uses `// [vX.Y.Z] rationale` or `// 설명` style comments explaining design tradeoffs.
**Apply to:** golden test files should carry a header comment per D-09 disclosing "record-then-freeze — current output frozen as-is, including any pre-existing bugs" and per D-02 a comment citing `BleService.kt:682` as the 120ms source, so a future maintainer doing a refreeze (D-12) doesn't "round it off."

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` | test | transform (golden) | Repo has zero unit or instrumentation tests (`app/src/test/`, `app/src/androidTest/` both absent). Use RESEARCH.md `Code Examples` section as the structural template; JUnit 4 4-arg `assertEquals` API is the only "pattern source" available. |
| `app/src/test/java/com/wf11/safealert/ble/RssiCascadeIsolationTest.kt` | test | transform (golden, cross-device) | Same — no analog; derive from `RssiCascadeTest.kt`'s own structure once written (self-analog after wave 1). |

## Metadata

**Analog search scope:** `app/src/main/java/com/wf11/safealert/02_ble/`, `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (targeted line ranges), `app/build.gradle`, `.github/workflows/release.yml`, `app/src/test/` (confirmed absent), `app/src/androidTest/` (confirmed absent)
**Files scanned:** 5 (3 filter classes, 1 build.gradle, 1 release.yml) + directory-existence checks for test source sets
**Pattern extraction date:** 2026-08-24
