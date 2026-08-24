---
phase: 01-ci
reviewed: 2026-08-24T08:08:47Z
depth: standard
files_reviewed: 5
files_reviewed_list:
  - .github/workflows/release.yml
  - app/build.gradle
  - app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt
  - app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt
  - app/src/test/java/com/wf11/safealert/ble/RssiCascadeIsolationTest.kt
findings:
  critical: 3
  warning: 12
  info: 9
  total: 24
status: resolved
resolved: 2026-08-24
critical_resolved: 3   # CR-01/CR-02/CR-03 전건 수정 반영
---

# Phase 01: 코드 리뷰 보고서

**리뷰 시각:** 2026-08-24T08:08:47Z
**깊이:** standard
**리뷰 파일 수:** 5
**상태:** resolved (Critical 3건 전건 수정 — 아래 '해소 기록' 참조)

## 요약

phase 01 은 RSSI 3단 캐스케이드(MedianFilter → RssiPreFilter → KalmanFilter)에 대한
record-then-freeze 골든 회귀 하네스와, 태그 push 시 이를 강제하는 CI 게이트를 추가한다.
프로덕션 변경은 `KalmanFilter` 의 시간 seam(`nowMs: () -> Long`) 주입 1건뿐이다.

### 최우선 검증 항목 결과 (요청 사항)

| 검증 항목 | 판정 | 근거 |
|---|---|---|
| 골든이 동어반복(구현 재계산)인가 | **아니오 — 통과** | Kotlin 의미론(Int 절단 나눗셈, `roundToInt`=floor(x+0.5))을 그대로 옮긴 **독립 Python 재구현**으로 동결 상수 전량을 비트 단위 재현. `-91.65266500958131`, `-83.44452861835482`, `-68.00385608534748` 등 모든 double 이 일치. `-PupdateGolden` 류 자동 재동결 경로 없음(D-12/P-02 준수). 즉 기대값은 실제 v1.1.70 출력의 진짜 하드코딩 기록이며, 동시에 CI 가 헛되이 빨개지지 않음도 확인됨. |
| float delta/허용오차 결정성 | 통과 | `1e-9` 절대오차. 20프레임 전파 후 `Math.pow` 1-ulp 편차의 누적은 < 1e-15 수준이라 JVM 구현 차를 흡수하면서도 의미 있는 회귀 대비로는 충분히 조임. |
| wall clock 독립성 | 통과 | `var fakeNow = 1_000_000L` 캡처 참조를 `nowMs` 로 주입, 프레임마다 `+120L`. `System.currentTimeMillis()` 는 테스트 경로에 남지 않음. |
| locale / timezone 독립성 | 통과 | 테스트·프로덕션 대상 3단 필터 어디에도 `String.format`, 날짜 포맷, locale 의존 파싱 없음. |
| 실행 순서 독립성 | 대체로 통과 (IN-02 잠재) | 테스트마다 필터 인스턴스를 새로 생성. 단 companion 의 `IntArray`/`DoubleArray` 는 가변 공유 상태이고 WARM 배열이 COLD 배열의 **별칭**이라 잠재 결합 존재. |
| KalmanFilter seam 의 프로덕션 동작 무변경 | 통과 | `nowMs` 참조는 파일 내 3곳(29·81·146)뿐이고 `reset()` 은 `lastTsMs` 를 건드리지 않아 seam 누락 지점 없음. 기본 인자는 **생성 시점마다** 평가되어 기존 인라인 `System.currentTimeMillis()` 와 의미 동일. 프로덕션 호출부 2곳(`BleService.kt:450`, `BleService.kt:1473` 부근)은 preset 을 위치 인자로 넘기므로 영향 없음. |
| release.yml 우회 구멍 | **CR-02 / CR-03 발견** | `continue-on-error` 없음, 테스트 스텝이 빌드보다 앞이라 실패 시 downstream 전면 중단 — 여기까지는 정상. 그러나 "0건 실행 = 통과"(CR-03), citest 가드 fail-open(CR-02). |
| citest 가드 오탐/누락 | **CR-02** | `!contains(github.ref_name, 'citest')` 는 대소문자 구분 부분문자열 **거부목록**이라 정상 릴리스 태그를 잘못 막을 위험보다 **테스트 태그를 놓칠 위험(fail-open)** 이 압도적으로 크다. |

### 변이(mutation) 생존 매트릭스 — 골든의 실제 검출력

독립 재구현 모델에 상수를 변이시켜 8개 골든 테스트 중 몇 개가 **눈치채지 못하는지** 측정했다.

| 변이 | 미검출 테스트 수 | 미검출 대상 |
|---|---|---|
| `dt.coerceIn(0.05, 2.0)` → `(0.001, 60.0)` | **8 / 8** | 전부 (WR-03) |
| `KALMAN_Q` 0.15 → 0.50 (FAST 값) | 4 / 8 | impulse·stationary 전부 (WR-05) |
| `KALMAN_R` 5.0 → 2.0 | 4 / 8 | impulse·stationary 전부 (WR-05) |
| dt 하한 0.05 → 0.5 | 4 / 8 | impulse·stationary 전부 |
| cold `pRR` 5.0 → 50.0 | 6 / 8 | + approach/warm, departure/warm |
| warm `pRR` 25.0 → 5.0 | 6 / 8 | + approach/cold, departure/cold (WR-06) |
| `ALPHA_FALL` 0.12 → 0.05 | 6 / 8 | + approach cold·warm (WR-07) |
| `WARMUP_SYMMETRIC_PUSHES` 10 → 0 | 6 / 8 | + approach cold·warm (WR-07) |

즉 골든 자체는 진짜지만 **입력 시나리오의 다양성이 부족**해서, 안전에 직결되는 Kalman
파라미터 변경이 절반의 테스트를 그대로 통과한다. dt 클램프는 어떤 테스트로도 못 잡는다.

---

## Narrative Findings (AI reviewer)

## 해소 기록 (2026-08-24, phase-close)

사용자 승인("지금 수정 후 마감")에 따라 Critical 3건을 전건 수정했다. Warning 12 / Info 9 는
범위 밖으로 미착수 — 아래 원문 그대로 보존해 후속 마일스톤의 입력으로 남긴다.

| ID | 조치 | 산출물 | 검증 |
|----|------|--------|------|
| CR-01 | `isFull()` 참 경로 전용 테스트 클래스 신설. 항상-false·항상-true 두 변이를 모두 사살 | `app/src/test/java/com/wf11/safealert/ble/MedianFilterWarmupTest.kt` (6 `@Test`) | 로컬 `testDebugUnitTest` 그린, 스위트 총계 8+3+6=17 |
| CR-02 | Firebase 가드를 거부목록 → **허용목록**으로 반전. `Extract version` 이 `^v[0-9]+\.[0-9]+\.[0-9]+$` 정규식으로 `is_release` 를 산출하고, Firebase 스텝은 `steps.ver.outputs.is_release == 'true'` 일 때만 실행 | `.github/workflows/release.yml:87-99`, `:115` | 태그 12종 표로 실측 — `-citest*`/`-ci-test`/`-citst`/`-CITest`/`-rc1`/`v1.1`/`v1.1.70.1`/`release-1.1.70` 전부 skip, 정확한 `vX.Y.Z` 만 RELEASE |
| CR-03 | 결과 XML 로 실행 건수를 직접 실증하는 `Assert golden tests actually ran` 스텝 신설. `Upload test reports` **뒤**(실패해도 아티팩트 보존) · `Build debug APK` **앞**(릴리스 실차단)에 배치 | `.github/workflows/release.yml:57-85` | 스텝 본문을 실제 XML 에 그대로 실행 — 정상 `discovered tests = 17 (floor 17)` exit 0, 디렉터리 부재·클래스 누락·건수 미달 3케이스 전부 exit 1 |

**미해소 잔여 리스크(고지):** CR-02 허용목록 전환으로 `v0.0.1` 같은 접미사 없는 태그는 이제
정식 릴리스로 판정되어 Firebase 프로덕션에 기록된다. 기존 테스트 태그는 모두 `-citestN`
접미사를 달고 있어 무영향이나, 향후 실험 태그에는 접미사가 필수다.

**CI 실증 상태:** CR-02/CR-03 은 로컬 실측으로 검증했다. GitHub Actions 실런 확인은 다음
릴리스 태그 push 시 자연히 이뤄진다(별도 citest 태그를 추가로 만들지 않음 — 태그 정리가
사람 몫으로 남아 있어 부채를 늘리지 않는다).

---

## Critical Issues

### CR-01: `MedianFilter.isFull()` 의 참(true) 경로가 어디서도 검증되지 않는다 — 전 경보 무발령 회귀가 전 테스트를 통과

**File:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeIsolationTest.kt:124`
(관련 프로덕션: `app/src/main/java/com/wf11/safealert/03_service/BleService.kt:1524`,
`app/src/main/java/com/wf11/safealert/02_ble/MedianFilter.kt:47`)

**Issue:**
저장소 전체에서 `isFull` 에 대한 어서션은 line 124 의 `assertFalse` 단 하나다. 즉
"clearAll 직후 false" 만 검증하고, **"윈도우가 찬 뒤 true"** 는 어디에서도 검증하지 않는다.

프로덕션에서 이 함수는 경보 발령 게이트다:

```kotlin
// BleService.kt:1524
val warmingUp = !medianFilter.isFull(deviceId)
```

**구체적 실패 시나리오:**
누군가 `MedianFilter.kt:47` 을 `buffers[deviceId]?.size ?: 0 > windowSize`(경계 오프바이원)
로 바꾸거나, 리팩터링 중 `windowSize` 를 잘못 참조해 항상 false 를 반환하게 만든다고 하자.

1. 11개 테스트 전부 그린 — `assertFalse` 는 여전히 통과하고, median **출력값** 골든은
   `isFull` 을 경유하지 않으므로 전혀 흔들리지 않는다.
2. CI 게이트 통과 → 태그 push → Release + Firebase 갱신 → 전 현장 단말 자동 업데이트.
3. 현장에서 `warmingUp` 이 **영구히 true** 가 되어 지게차가 1m 앞에 와도 경보가
   단 한 번도 발령되지 않는다. 미발령은 SafeAlert 에서 최악 등급의 안전 결함이다.

골든 회귀 하네스를 도입한 목적 자체가 "안전 경로의 조용한 회귀 차단"인데,
가장 치명적인 단일 불린 게이트가 커버리지 구멍으로 남아 있다.

**Fix:** 격리 테스트에 양성 경로 어서션을 추가한다(`DEFAULT_WINDOW = 3` 기준).

```kotlin
@Test
fun isFull_transitionsAtWindowSize() {
    val mf = MedianFilter()
    assertFalse("isFull/n=0", mf.isFull(DEVICE_01))
    mf.push(DEVICE_01, INPUT_A[0])
    assertFalse("isFull/n=1", mf.isFull(DEVICE_01))
    mf.push(DEVICE_01, INPUT_A[1])
    assertFalse("isFull/n=2", mf.isFull(DEVICE_01))
    mf.push(DEVICE_01, INPUT_A[2])
    assertTrue("isFull/n=3 (window boundary)", mf.isFull(DEVICE_01))
    mf.push(DEVICE_01, INPUT_A[3])
    assertTrue("isFull/n=4 (stays full)", mf.isFull(DEVICE_01))
    // 기기 격리: device02 는 여전히 비어 있어야 한다
    assertFalse("isFull/other device untouched", mf.isFull(DEVICE_02))
}
```

추가로 `clear(DEVICE_01)` 직후 `isFull` 이 다시 false 로 떨어지는 것도 함께 검증하면
WR-09 도 동시에 닫힌다.

---

### CR-02: citest Firebase 가드가 대소문자 구분 부분문자열 거부목록 — fail-open 으로 프로덕션 업데이트 채널 오염

**File:** `.github/workflows/release.yml` (`Update Firebase Realtime DB` 스텝의 `if:` 조건)

```yaml
- name: Update Firebase Realtime DB
  if: ${{ !contains(github.ref_name, 'citest') }}
```

**Issue:**
이 가드는 (a) 대소문자를 구분하고, (b) **거부목록(denylist)** 이며, (c) 매칭에 실패하면
"프로덕션 갱신 실행" 쪽으로 떨어진다. 세 성질이 겹쳐 **fail-open** 이다. 보호해야 할 대상은
Firebase `wf11/version` — 전 현장 단말의 자동 업데이트 권위 채널이다.

**구체적 실패 시나리오:**
CI 를 검증하려고 다음 중 어떤 태그든 push 한다.

- `v1.1.71-CITest` / `v1.1.71-CItest` (대소문자 변형)
- `v1.1.71-ci-test` / `v1.1.71-ci_test` (구분자 삽입)
- `v1.1.71-test`, `v1.1.71-dryrun`, `v1.1.71-rc1` (다른 명명)
- `v1.1.71-citset` (단순 오타)

가드가 통과되어 다음이 실행된다:

```
curl -fsSX PATCH ".../wf11/version.json?auth=***"   # latest / apk_url / force_update 갱신
```

그 즉시 현장 단말 전체가 **테스트용 debug APK** 를 최신 버전으로 인식하고 업데이트
프롬프트를 띄운다. `force_update` 값에 따라서는 강제 업데이트까지 발생한다. 되돌리려면
사람이 Firebase 를 수동 원복해야 하고, 그 사이 갱신된 단말은 검증되지 않은 빌드로 안전
경보를 수행한다. 롤백 창이 존재하지 않는 단방향 오염이다.

**Fix:** 거부목록을 **허용목록(fail-closed)** 으로 뒤집는다. 정식 릴리스 태그 형태
(`vMAJOR.MINOR.PATCH`, 접미사 없음)일 때만 Firebase 를 건드린다.

```yaml
- name: Update Firebase Realtime DB
  if: ${{ startsWith(github.ref_name, 'v') && !contains(github.ref_name, '-') }}
```

더 엄격하게는 정규식 게이트를 별도 스텝으로 두고 출력 플래그를 소비한다.

```yaml
- name: Classify tag
  id: tagcheck
  run: |
    if [[ "${{ github.ref_name }}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      echo "production=true" >> "$GITHUB_OUTPUT"
    else
      echo "production=false" >> "$GITHUB_OUTPUT"
    fi
# ...
- name: Update Firebase Realtime DB
  if: ${{ steps.tagcheck.outputs.production == 'true' }}
```

이렇게 하면 새로운 테스트 태그 명명 규칙을 발명해도 자동으로 안전 측으로 떨어진다.

---

### CR-03: 테스트 0건 실행과 전건 통과를 CI 가 구분하지 못한다 — 게이트가 영구 no-op 으로 퇴화 가능

**File:** `.github/workflows/release.yml` (`Run unit tests (golden RSSI cascade)` /
`Upload test reports` 스텝), `app/build.gradle` (`testOptions` 블록)

**Issue:**
게이트의 존재 가치는 "실패를 잡는다"가 아니라 "실제로 돌았다 + 실패를 잡는다"이다.
현재 구성은 후자의 전제를 어디서도 확인하지 않는다. 네 가지가 동시에 겹친다.

1. `./gradlew testDebugUnitTest` 는 발견된 테스트가 0건이면 태스크를 `NO-SOURCE` 로
   처리하고 **exit code 0** 을 반환한다.
2. Gradle 8.6(`gradle/wrapper/gradle-wrapper.properties` = `gradle-8.6-bin.zip`)은
   `failOnNoDiscoveredTests` 를 지원하지 않는다 — 상위 버전 전용 안전장치가 없다.
3. `app/build.gradle` 의 `testLogging { events 'failed' }` 는 **실패 이벤트만** 출력하므로
   0건 실행 시 로그가 완전히 비어 성공 실행과 육안 구분이 되지 않는다.
4. `actions/upload-artifact` 는 기본 `if-no-files-found: warn` 이라 리포트가 하나도 없어도
   경고만 남기고 초록색으로 끝난다.

**구체적 실패 시나리오:**
아래 중 무엇이든 일어나면 게이트는 조용히 죽는다.

- 리팩터링 중 테스트 파일을 `app/src/test/...` 밖(예: `src/testDebug/`, 다른 모듈)으로 이동
- 패키지/디렉터리 오타로 소스셋에서 이탈
- 빌드 변형 이름 변경으로 `testDebugUnitTest` 가 존재하지 않는 태스크가 되어
  (Gradle 설정에 따라) 아무 것도 안 하고 통과
- `testImplementation 'junit:junit:4.13.2'`(`app/build.gradle:79`) 제거 → 컴파일 실패는
  잡히지만, 테스트 클래스만 통째로 제외되는 변경은 잡히지 않음

이후 모든 릴리스가 "게이트 통과"라는 초록 배지를 달고 나가지만 실제로 검증된 것은 없다.
누구도 알아채지 못한 채 Kalman 상수 회귀가 현장에 배포된다. 안전 게이트의 **조용한 실효**는
게이트 부재보다 더 위험하다(있다고 믿기 때문).

**Fix:** 실행 건수를 실증한다. 세 겹 모두 적용을 권장한다.

(1) `app/build.gradle` — 통과/스킵 카운트를 로그에 남기고, 0건이면 실패시킨다.

```groovy
testOptions {
    unitTests.all {
        testLogging {
            events 'passed', 'skipped', 'failed'
            exceptionFormat 'full'
        }
        afterSuite { desc, result ->
            if (!desc.parent) {
                logger.lifecycle("Test result: ${result.resultType} " +
                        "(${result.testCount} tests, ${result.successfulTestCount} passed, " +
                        "${result.failedTestCount} failed, ${result.skippedTestCount} skipped)")
                if (result.testCount == 0) {
                    throw new GradleException("No unit tests were discovered — golden gate is a no-op.")
                }
            }
        }
    }
}
```

(2) `.github/workflows/release.yml` — 리포트 부재를 실패로 승격.

```yaml
- name: Upload test reports
  if: always()
  uses: actions/upload-artifact@v7
  with:
    name: unit-test-reports
    if-no-files-found: error
    path: |
      app/build/reports/tests/testDebugUnitTest/
      app/build/test-results/testDebugUnitTest/
    retention-days: 14
```

(3) 테스트 스텝 직후 최소 건수 검증 스텝 추가.

```yaml
- name: Assert minimum test count
  run: |
    n=$(grep -ho 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml \
        | grep -o '[0-9]*' | paste -sd+ - | bc)
    echo "discovered tests: ${n:-0}"
    test "${n:-0}" -ge 11
```

---

## Warnings

### WR-01: `assertCascade` 가 `expectedMedian.indices` 만 순회 — 배열 길이 불일치가 조용히 커버리지를 깎는다

**File:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` (`assertCascade` 루프)

**Issue:** 루프 상한이 `expectedMedian` 하나에만 묶여 있고, 실제 출력 길이·다른 기대 배열
길이와의 일치를 검증하지 않는다.

**실패 시나리오:** 골든 재동결 중 실수로 `EXPECTED_APPROACH_COLD_MEDIAN` 에서 뒤 5개
원소를 누락하면(20 → 15), 테스트는 15프레임만 비교하고 **그린**이 된다. 접근 시나리오
후반부(경보 임계에 실제로 도달하는 구간)의 prefilter/Kalman 회귀가 영구히 무검출 상태가
된다. 반대로 입력 배열이 길어져도 초과분은 비교되지 않는다.

**Fix:** 루프 앞에 길이 계약을 명시한다.

```kotlin
val n = input.size
assertEquals("$scenario/$startState expected-median length", n, expectedMedian.size)
assertEquals("$scenario/$startState expected-prefilter length", n, expectedPrefilter.size)
assertEquals("$scenario/$startState expected-kalman length", n, expectedKalman.size)
assertEquals("$scenario/$startState actual-median length", n, median.size)
for (i in 0 until n) { /* ... */ }
```

---

### WR-02: Kalman **속도(vel)** 출력이 전혀 검증되지 않는다 — `kfVel` 회귀가 골든을 그대로 통과

**File:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` (`runCascade` 내
`val (est, _) = kf.update(preFiltered, imuQScale = 1.0)`)

**Issue:** 구조 분해에서 두 번째 성분을 `_` 로 버린다. 그러나 `kfVel` 은 프로덕션에서
DANGER 승격, TTC 예비 판정, urgentBypass(`kfVel >= 2.0`), CROSSING 데드밴드
(`kfVel >= 1.0`), 이탈 운동학 거부권 등 **경보 발령/해제 로직 다수의 1차 입력**이다.
RSSI 추정치만 동결해 두면 상태 공간의 절반이 무방비다.

**실패 시나리오:** Kalman 갱신에서 `kf.vel = pv + kV * inn` 의 `kV` 부호가 뒤집히거나
`pVV` 갱신식이 손상돼도 `est`(rssi)는 거의 동일하게 유지될 수 있다 — 8개 테스트 전부 통과.
현장에서는 접근 중인 지게차의 `kfVel` 이 음수로 나와 urgentBypass 가 발동하지 않고
코너 돌진 경보가 지연되거나, 반대로 정지 상태에서 CROSSING 리셋이 안 걸려 사이렌이
꺼지지 않는다. 둘 다 v1.1.48 에서 이미 한 번 겪은 증상 계열이다.

**Fix:** 속도를 4번째 스테이지로 승격해 함께 동결한다.

```kotlin
private fun runCascade(...): Quadruple<IntArray, IntArray, DoubleArray, DoubleArray> {
    // ...
    val (est, vel) = kf.update(preFiltered, imuQScale = 1.0)
    kalmanOut[i] = est
    velOut[i] = vel
}
// assertCascade 에 추가
assertEquals("$scenario/$startState frame=$i stage=kalmanVel", expectedVel[i], vel[i], 1e-9)
```

---

### WR-03: `dt.coerceIn(0.05, 2.0)` 클램프 경계를 어떤 테스트도 실행하지 않는다 (변이 8/8 생존)

**File:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt`
(`FRAME_DT_MS = 120L` 고정), 대상: `KalmanFilter.kt` 의 `dt` 계산

**Issue:** 모든 프레임 간격이 정확히 120ms 라 `dt` 는 항상 0.12 — 하한 0.05 와 상한 2.0
어느 쪽도 스치지 않는다. 위 변이 매트릭스에서 클램프를 `(0.001, 60.0)` 으로 완전히
무력화해도 **8개 테스트 전부 생존**했다. 시간 seam 을 주입한 phase 의 핵심 성과물인데,
정작 seam 으로만 검증 가능한 유일한 로직이 미검증이다.

**실패 시나리오:** 화면 꺼짐 상태에서 스캔이 배칭되면 프레임 간격이 수 초로 벌어진다
(v1.1.47 에서 다룬 실제 조건). 클램프 상한이 사라지면 `dt = 30.0` 에서
`Q = q * dt^4 / 4 = 0.15 * 810000 / 4 ≈ 30375` 로 공분산이 폭주하고, 칼만 게인이 1 에
포화해 필터가 사실상 raw 측정치를 그대로 뱉는다. median 3-tap 만 남은 상태로 NLOS
스파이크가 그대로 통과 → 오경보. CI 는 아무 것도 감지하지 못한다.

**Fix:** 시간 seam 을 활용한 dt 경계 전용 테스트를 추가한다(골든 동결 불필요, 성질 검증).

```kotlin
@Test
fun dtClamp_upperBoundHolds() {
    var now = 1_000_000L
    val kf = KalmanFilter(nowMs = { now })
    kf.update(-80, imuQScale = 1.0)          // 초기화
    now += 30_000L                            // 30초 갭 (배칭 시나리오)
    val (bigGap, _) = kf.update(-60, imuQScale = 1.0)

    var now2 = 1_000_000L
    val kf2 = KalmanFilter(nowMs = { now2 })
    kf2.update(-80, imuQScale = 1.0)
    now2 += 2_000L                            // 정확히 상한 2.0s
    val (atCap, _) = kf2.update(-60, imuQScale = 1.0)

    assertEquals("dtClamp/upper: 30s must behave identically to 2s cap", atCap, bigGap, 1e-9)
}
```

하한(0.05)도 같은 방식으로 10ms vs 50ms 동치를 검증한다.

---

### WR-04: FAST/SMOOTH preset 분기 미실행 + 프로덕션은 골든의 기준인 기본 NORMAL 을 **한 번도 쓰지 않는다**

**File:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt`
(`KalmanFilter(nowMs = { fakeNow })` — preset 기본값 사용)
프로덕션: `BleService.kt:450`, `BleService.kt:1473` 부근(`updatePreset(...)`)

**Issue:** 골든은 preset=NORMAL(q=0.15, R=5.0) 한 조합만 동결한다(D-05). 그런데 프로덕션
호출부 두 곳 모두 `DevSettings.kalmanPreset` 을 명시 전달하고, 승격 시
`KALMAN_PRESET_FAST` 로 `updatePreset()` 한다 — **기본 인자 경로는 프로덕션에서 죽은
경로**다. 변이 매트릭스에서 q 를 FAST 값 0.50 으로 바꿔도 4/8 테스트가 생존한다.

**실패 시나리오:** FAST 프리셋의 q 또는 R 상수가 오타로 바뀌면(예: `KALMAN_R_FAST` 2.0 →
20.0) 골든은 전건 통과한다. 현장에서는 promoteFast 승격 직후 필터가 과도하게 둔감해져
접근 중인 지게차의 거리 추정이 지연되고, 15m 경고 반경을 지나쳐 8m DANGER 에서야
발령된다. 지게차 1.7m/s 기준 약 4초의 경보 지연이다.

**Fix:** preset 축을 골든 매트릭스에 추가하거나(테스트 8 → 12개), 최소한 FAST/SMOOTH 의
상대적 응답성만이라도 성질 검증한다.

```kotlin
@Test
fun presets_orderedByResponsiveness() {
    // 동일 스텝 입력에 대해 FAST 가 NORMAL 보다, NORMAL 이 SMOOTH 보다 빠르게 추종해야 한다
    val fast   = stepResponse(DevSettings.KALMAN_PRESET_FAST)
    val normal = stepResponse(DevSettings.KALMAN_PRESET_NORMAL)
    val smooth = stepResponse(DevSettings.KALMAN_PRESET_SMOOTH)
    assertTrue("preset order fast>normal", fast > normal)
    assertTrue("preset order normal>smooth", normal > smooth)
}
```

---

### WR-05: impulse / stationary 시나리오의 prefilter·Kalman 골든이 **상수 배열** — 4개 테스트가 Kalman 전 파라미터에 무감각

**File:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt`
(`EXPECTED_IMPULSE_*_PREFILTER` / `_KALMAN`, `EXPECTED_STATIONARY_*_PREFILTER` / `_KALMAN`)

**Issue:** 재계산 결과 impulse 의 prefilter·Kalman 은 20프레임 전부 `-78`/`-78.0`,
stationary 는 전부 `-80`/`-80.0` 이다. median 3-tap 이 임펄스와 미세 흔들림을 완전히
흡수해 버려 Kalman 의 혁신항(innovation)이 프레임 1 이후 항등 0 이 된다. 혁신이 0 이면
칼만 게인·공분산·q·R·dt 가 무엇이든 출력이 변하지 않는다.

수치로: 총 480개 어서션(8테스트 × 20프레임 × 3단) 중 **160개(33%)** 가 단일 반복값 비교다.
변이 매트릭스에서 `R` 2.0 변이, `q` 0.50 변이, cold/warm `pRR` 변이 모두 이 4개 테스트를
그대로 통과했다.

**실패 시나리오:** Kalman 공분산 갱신식(`pRR = (1-kR)*pRRP` 등) 어딘가가 손상돼도
impulse/stationary 테스트는 영원히 그린이다. 회귀 검출은 approach/departure 2개 시나리오에
전적으로 의존하는데, 커버리지 지표(테스트 8개 통과)는 실제 검출력의 2배로 과대 보고된다.

**Fix:** median 이 흡수하지 못하는 입력을 추가한다 — 연속 2프레임 임펄스(3-tap median
관통), 또는 계단형 급변 후 정체.

```kotlin
/** 2프레임 연속 스파이크 — 3-tap median 을 관통해 Kalman 단까지 도달한다. */
val INPUT_TWIN_SPIKE = intArrayOf(
    -78, -77, -78, -45, -44, -78, -77, -79, -78, -78,
    -105, -104, -77, -78, -79, -78, -77, -78, -79, -78
)
```

이 시나리오를 D-09 record-then-freeze 절차로 동결하면 Kalman 파라미터 변이 검출력이
8/8 로 회복된다.

---

### WR-06: warm 시작 상태(`injectWarmup`)가 impulse/stationary 에서 cold 와 **완전히 동일** — warm 경로 자체가 사실상 미검증

**File:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt`
(`kf.injectWarmup(rssiVal = input[0], initVel = 0.0)` 및 WARM 별칭 배열들)

**Issue:** 위 WR-05 와 같은 이유로, impulse/stationary 에서는 warm 과 cold 의 20프레임
출력이 비트 단위로 동일하다. 즉 `injectWarmup()` 호출을 통째로 지워도 4개 테스트가
통과한다. 변이 매트릭스에서 warm 초기 `pRR` 을 25.0 → 5.0(cold 값)으로 바꿔도 6/8 이
생존했다 — warm 경로의 실질 검출력은 approach/departure 2개뿐이다.

`injectWarmup` 은 v1.1.29 에서 "재시작 세션편차" 를 잡으려고 도입된, 실기 문제 대응
로직이다. 그 회귀를 잡겠다는 것이 이 phase 의 목적 중 하나인데 커버리지가 얇다.

**실패 시나리오:** `injectWarmup` 의 `pRR = 25.0` 초기화가 누락되거나 `initVel` 이 무시되게
바뀌면 approach/departure 는 잡지만, 리팩터링이 warm 경로를 조건부로만 손상시키면
(예: 특정 preset 에서만) 전건 통과한다. 현장에서는 앱 재시작 직후 필터가 과신(과소
공분산)해 초기 수 초간 실제 접근을 따라가지 못한다 — 재시작 직후가 가장 위험한 구간이다.

**Fix:** WR-05 의 신규 시나리오를 추가하면 자동 해소된다. 추가로 warm/cold 가 실제로
달라야 하는 시나리오에서 "다름"을 명시 어서션하면 별칭 실수도 함께 막을 수 있다.

```kotlin
assertFalse("approach: warm and cold must diverge in early frames",
    EXPECTED_APPROACH_COLD_KALMAN.contentEquals(EXPECTED_APPROACH_WARM_KALMAN))
```

---

### WR-07: D-Boost / fallBoost 분기가 하드코딩으로 봉쇄됨 — `RssiPreFilter` 비대칭 EMA 의 절반이 미검증

**File:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt`
(`rssiPreFilter.push(deviceId, medianValue, prevVel = 0.0, fallBoost = false)`)

**Issue:** `prevVel = 0.0` 고정으로 `VEL_DBOOST_DBM = 2.0` 임계를 절대 넘지 않아
`ALPHA_DBOOST = 0.4` 분기가 죽고, `fallBoost = false` 고정으로 `FALL_BOOST_ALPHA = 0.4`
분기도 죽는다. D-05 가 단순화를 결정한 것은 이해하나, 그 결과 **prefilter 의 4개 알파
경로 중 2개가 커버되지 않는다**는 사실은 기록되어야 한다. 변이 매트릭스에서
`ALPHA_FALL` 0.12 → 0.05, `WARMUP_SYMMETRIC_PUSHES` 10 → 0 이 각각 6/8 생존한 것도
같은 맥락이다.

**실패 시나리오:** `ALPHA_DBOOST` 가 0.4 → 0.04 로 바뀌면(오타 한 글자) 골든 전건 통과.
현장에서는 접근 속도가 붙은 지게차에 대한 D-Boost 가속이 사라져 prefilter 가 10배 느리게
따라가고, 경고 발령이 수 초 늦는다. D-Boost 는 애초에 "다가오는 지게차 경보지연"
(v1.1.21)을 잡으려고 넣은 로직이라 회귀 시 그 문제가 그대로 재발한다.

**Fix:** D-05 경계를 유지하되, prefilter 단독 유닛 테스트를 별도 파일로 추가해 두 분기를
직접 덮는다(캐스케이드 골든과 결합하지 않으므로 D-05 위반이 아니다).

```kotlin
@Test
fun preFilter_dBoostEngagesAboveVelocityThreshold() {
    val a = RssiPreFilter(); val b = RssiPreFilter()
    // 동일 입력, prevVel 만 임계 위/아래
    val slow = drive(a, prevVel = 1.9)   // < VEL_DBOOST_DBM
    val fast = drive(b, prevVel = 2.5)   // >= VEL_DBOOST_DBM
    assertTrue("dBoost must accelerate tracking", fast tracksFasterThan slow)
}
```

---

### WR-08: 하네스가 프로덕션 배선을 재현한다는 in-code 주장이 사실과 다르다

**File:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt:122-124` (KDoc),
`.planning/phases/01-ci/01-RESEARCH.md:322` ("matches production wiring exactly")

**Issue:** KDoc 은 하네스가 `BleService.kt:1473-1519` 의 배선을 재현한다고 적었으나, 실제
프로덕션 배선과 다음이 다르다.

| 항목 | 프로덕션 (`BleService.kt`) | 하네스 |
|---|---|---|
| `prevVel` | `kf.estimatedVel` (1-스텝 피드백 루프) | `0.0` 고정 |
| `imuQScale` | `ImuFusion.adaptiveQFactor` | `1.0` 고정 |
| preset | `DevSettings.kalmanPreset` + `updatePreset()` 매 프레임 | 기본 NORMAL, 갱신 없음 |
| `fallBoost` | `shadowBoost` | `false` 고정 |
| `pEmaFilter` | 4단으로 존재 | 제외 |

특히 `prevVel` 은 프로덕션에서 **피드백 루프**를 형성한다 — 하네스는 이 루프를 끊은
개루프 시스템이다. 두 시스템의 동역학은 근본적으로 다르다.

이것은 D-05 가 의도적으로 결정한 경계이므로 설계 결함이 아니다. **결함은 주석이 그
경계를 감춘다는 점**이다.

**실패 시나리오:** 6개월 뒤 다른 사람(또는 에이전트)이 KDoc 을 읽고 "골든이 프로덕션
배선을 정확히 커버한다"고 믿는다. `prevVel` 피드백 루프나 `pEmaFilter` 를 손대면서
"골든이 그린이니 안전하다"고 판단해 배포한다. 실제로는 하네스가 그 경로를 전혀 보지
않으므로 회귀가 현장에 도달한다. 잘못된 주석은 커버리지 구멍보다 위험하다 — 구멍의
존재 자체를 은폐하기 때문이다.

**Fix:** KDoc 을 D-05 경계 명시로 교체한다.

```kotlin
/**
 * 이 하네스는 BleService.kt:1473-1519 배선의 **단순화된 부분집합**이다 (D-05).
 * 프로덕션과 의도적으로 다른 점:
 *   - prevVel = 0.0 고정 (프로덕션의 kf.estimatedVel 피드백 루프를 끊음)
 *   - imuQScale = 1.0 고정 (ImuFusion.adaptiveQFactor 미반영)
 *   - preset = NORMAL 고정 (updatePreset() 승격 경로 미반영)
 *   - fallBoost = false 고정, pEmaFilter 제외
 * 위 경로들의 회귀는 이 파일이 잡지 못한다.
 */
```

`01-RESEARCH.md:322` 의 "matches production wiring exactly" 도 정정해야 한다.

---

### WR-09: `clear(deviceId)` 의 양성 의미가 검증되지 않는다 — no-op `clear()` 도 전건 통과

**File:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeIsolationTest.kt:91-98`

**Issue:** `selectiveClear_onlyAffectsTargetDevice` 는 device02 를 clear 한 뒤
**device01 이 영향받지 않음**만 어서션한다(96-97행). device02 가 실제로 초기화되었는지는
전혀 확인하지 않는다.

**실패 시나리오:** `MedianFilter.clear()` / `RssiPreFilter.clear()` 를 빈 메서드로 만들어도
11개 테스트 전부 통과한다(격리 어서션은 device01 만 보므로 오히려 더 확실히 통과).
프로덕션에서 기기 소실 시 필터 상태가 남고, 같은 MAC 이 재등장하면 **수 분 전의 낡은
RSSI 이력**이 median 윈도우에 섞여 첫 프레임부터 잘못된 거리를 산출한다. v1.1.50 에서
잡았던 "좀비 실측" 과 동형의 문제다.

**Fix:** clear 대상 기기의 상태 초기화를 직접 어서션한다.

```kotlin
if (i == clearAtIndex) {
    medianFilter.clear(DEVICE_02)
    rssiPreFilter.clear(DEVICE_02)
    assertFalse("isolation/afterClear device02 must be reset", medianFilter.isFull(DEVICE_02))
    assertTrue("isolation/afterClear device01 must stay full", medianFilter.isFull(DEVICE_01))
    // 다음 push 가 콜드스타트 값(=raw)과 같아야 한다
    assertEquals("isolation/afterClear device02 cold restart",
        INPUT_B[i + 1], medianFilter.push(DEVICE_02, INPUT_B[i + 1]))
}
```

---

### WR-10: citest 가드가 Firebase 스텝만 보호 — 테스트 태그가 공개 Release 를 발행하고 다음 정식 릴리스 노트를 잠식한다

**File:** `.github/workflows/release.yml` (`Create GitHub Release` 스텝,
`softprops/action-gh-release@v3`, `generate_release_notes: true`)

**Issue:** `if: ${{ !contains(github.ref_name, 'citest') }}` 는 Firebase 스텝에만 붙어 있다.
`Create GitHub Release` 에는 가드가 없어 citest 태그에서도 **공개 Release 와 debug APK
자산이 그대로 발행**된다.

**실패 시나리오:**
1. `v1.1.71-citest` push → GitHub Releases 페이지에 공개 릴리스 + APK 게시.
   Firebase 는 막히지만, 릴리스 페이지 URL 을 직접 아는 사용자는 테스트 APK 를 받는다.
2. 더 은밀한 2차 피해: `generate_release_notes: true` 는 **직전 릴리스 이후의 커밋**으로
   노트를 만든다. citest 릴리스가 기준점이 되어 다음 정식 릴리스 `v1.1.71` 의 자동 생성
   노트가 citest 이후 커밋만 담게 되고, 그 사이 실제 변경 이력이 릴리스 노트에서
   통째로 사라진다. 안전 앱의 변경 이력 추적성이 조용히 깨진다.

**Fix:** CR-02 에서 도입한 `steps.tagcheck.outputs.production` 플래그를 Release 스텝에도
적용하거나, 최소한 prerelease 로 표시해 노트 기준점에서 제외한다.

```yaml
- name: Create GitHub Release
  uses: softprops/action-gh-release@v3
  with:
    prerelease: ${{ steps.tagcheck.outputs.production != 'true' }}
    generate_release_notes: ${{ steps.tagcheck.outputs.production == 'true' }}
```

---

### WR-11: 게이트가 태그 push 에서만 동작 — master 회귀가 태그 시점까지 무성으로 누적된다

**File:** `.github/workflows/release.yml` (`on: push: tags: ['v*']`)

**Issue:** D-13 은 "PR 워크플로 불필요(단일 master, PR 병합 0건)"를 근거로 게이트를
`release.yml` 에만 두기로 결정했다. 그 근거는 **PR 트리거**에 대해서는 타당하지만,
`push: branches: [master]` 트리거는 이 저장소에서 실제로 발화한다 — 모든 작업이 master
직접 커밋이기 때문이다. D-13 의 논거가 이 대안을 다루지 않는다.

**실패 시나리오:** 개발자가 Kalman 상수를 조정하는 커밋 5개를 master 에 직접 push 한다.
CI 는 한 번도 돌지 않는다. 며칠 뒤 릴리스 태그를 붙이는 순간 골든이 빨개지는데, 이때는
어느 커밋이 원인인지 이분 탐색해야 하고, 릴리스 일정 압박 속에서 "일단 골든을 재동결"
하는 유혹이 커진다. 그러면 회귀가 새 기준선으로 승격되어 게이트가 무력화된다.
피드백 루프를 짧게 두는 것이 record-then-freeze 방식의 전제다.

**Fix:** 태그 게이트를 유지한 채, 저렴한 master push 트리거를 추가한다(테스트만, 빌드·배포
없음 — 러너 비용은 수십 초).

```yaml
# .github/workflows/test.yml (신규)
on:
  push:
    branches: [master]
    paths:
      - 'app/src/**'
      - 'app/build.gradle'
      - 'gradle/**'
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - run: chmod +x ./gradlew
      - run: ./gradlew testDebugUnitTest --no-daemon --console=plain
```

---

### WR-12: D-03 가 "불변"으로 못박은 공개 계약(`updateCount` / `estimatedVel` / `isInitialized` / `reset()` / `updatePreset()`)에 계약 테스트가 하나도 없다

**File:** `app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt` (해당 멤버들),
테스트 부재

**Issue:** D-03 은 이 phase 에서 `KalmanFilter` 의 상수·`updateCnt`·`reset()`·
`updatePreset()`·게터를 변경하지 않기로 결정했다. 그런데 "변경하지 않기로 했다"는 결정을
**강제하는 장치**가 없다. 이후 phase 에서 누구든 자유롭게 바꿀 수 있고, 골든 8개는
`update()` 의 rssi 출력만 보므로 아무 것도 감지하지 못한다.

**실패 시나리오:** `reset()` 이 `updateCnt` 를 0 으로 되돌리지 않도록 바뀌면, 프로덕션에서
`updateCount` 기반 워밍업 판정이 영구히 "이미 워밍업 완료" 로 남는다. 재연결 직후
필터가 워밍업 없이 곧바로 판정에 참여해 초기 몇 프레임의 튀는 값이 그대로 경보로
이어진다. 골든은 전건 그린.

**Fix:** D-03 불변 계약을 실행 가능한 테스트로 고정한다.

```kotlin
@Test
fun reset_restoresColdStartContract() {
    var now = 1_000_000L
    val kf = KalmanFilter(nowMs = { now })
    repeat(5) { now += 120L; kf.update(-80 + it, imuQScale = 1.0) }
    assertTrue("contract/isInitialized before reset", kf.isInitialized)
    assertEquals("contract/updateCount before reset", 5, kf.updateCount)

    kf.reset()
    assertFalse("contract/isInitialized after reset", kf.isInitialized)
    assertEquals("contract/updateCount after reset", 0, kf.updateCount)
    assertEquals("contract/estimatedVel after reset", 0.0, kf.estimatedVel, 1e-9)
}
```

---

## Info

### IN-01: 격리 테스트 3번이 캐스케이드 배선을 따르지 않는다 (raw 입력을 prefilter 에 직접 투입)

**File:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeIsolationTest.kt:117-118`

```kotlin
medianFilter.push(DEVICE_02, INPUT_B[i])
rssiPreFilter.push(DEVICE_02, INPUT_B[i], prevVel = 0.0, fallBoost = false)  // ← m2 가 아님
```

같은 파일 65-66행, 88-89행은 median 출력(`m2`)을 prefilter 에 넘기는데 여기만 raw
`INPUT_B[i]` 를 넘긴다. 이 테스트의 목적(`clearAll` 후 device01 재현)에는 영향이 없으나,
"오염원 device02 도 프로덕션과 같은 방식으로 구동한다" 는 전제를 깨뜨려 향후 이 테스트를
확장할 때 혼란을 만든다.

**Fix:** `val m2 = medianFilter.push(DEVICE_02, INPUT_B[i])` 로 받아 `m2` 를 넘긴다.

---

### IN-02: companion 의 `IntArray` / `DoubleArray` 가 가변 공유 상태이고 WARM 배열이 COLD 배열의 별칭이다

**File:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` (companion object)

Kotlin 의 `IntArray` / `DoubleArray` 는 `val` 로 선언해도 **원소가 가변**이다. 게다가 7개
WARM 상수가 대응 COLD 상수를 그대로 가리키는 별칭이다(`EXPECTED_APPROACH_WARM_MEDIAN`
→ `EXPECTED_APPROACH_COLD_MEDIAN` 등). 어느 테스트가 실수로 기대 배열에 쓰기를 하면
같은 JVM 안의 다른 테스트가 오염된 값을 보며, 별칭 때문에 cold/warm 이 동시에 깨진다.
현재 코드에는 쓰기가 없어 실제 버그는 아니지만, 실행 순서 의존 결함의 잠재 진입점이다.

**Fix:** `List<Int>` / `List<Double>`(불변) 로 바꾸거나, 별칭 대신 각 상수를 독립 선언한다.
`listOf(...)` 는 원소 비교 어서션과도 잘 맞는다.

---

### IN-03: `EXPECTED_IMPULSE_WARM_MEDIAN` 만 전개 선언 — 별칭 규칙이 비일관

**File:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` (companion object)

7개 WARM 상수가 별칭인데 `EXPECTED_IMPULSE_WARM_MEDIAN` 하나만 20개 리터럴로 전개돼 있다.
값은 COLD 와 동일하다(재계산으로 확인). 재동결 시 두 곳을 따로 고쳐야 하므로 한쪽만
갱신되는 사고가 나기 쉽다.

**Fix:** 다른 6개와 동일하게 별칭으로 통일하거나, IN-02 를 따라 전부 독립 선언으로 통일한다.
어느 쪽이든 **하나의 규칙**을 적용한다.

---

### IN-04: `runCascade` 의 `deviceId` 파라미터가 죽은 코드

**File:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` (`runCascade` 시그니처)

`deviceId: String = DEVICE_ID` 는 8개 호출부 전부 기본값을 쓴다. 기기별 분기를 검증하려는
의도였다면 미완이고, 아니라면 불필요한 표면적이다.

**Fix:** 제거하고 함수 내부에서 `DEVICE_ID` 를 직접 쓰거나, 실제로 다른 deviceId 를 쓰는
테스트를 추가해 의도를 살린다.

---

### IN-05: `1e-9` 매직 넘버가 어서션마다 반복되고 근거 주석이 없다

**File:** `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` (`assertCascade` 내)

허용오차 선택 근거(부동소수 누적오차 흡수 vs 회귀 검출력)가 어디에도 기록돼 있지 않아,
나중에 테스트가 불안정해 보일 때 근거 없이 완화될 위험이 있다.

**Fix:**

```kotlin
/** Kalman double 비교 허용오차. 20프레임 전파 후 Math.pow 1-ulp 편차 누적 상한(<1e-15)의
 *  약 10^6 배 여유이면서, 의미 있는 회귀(>=1e-6 dBm)는 반드시 검출하는 값. */
private const val KALMAN_DELTA = 1e-9
```

---

### IN-06: 새 seam 에 `@VisibleForTesting` 표기가 없다

**File:** `app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt:29`
(`private val nowMs: () -> Long = { System.currentTimeMillis() }`)

`private` 이라 외부 오용 위험은 없으나, 이 파라미터가 **테스트 결정성 전용 확장점**이라는
의도가 코드에 드러나지 않는다. D-03 이 이 클래스를 동결 대상으로 지정한 만큼, 이후
리팩터링에서 "쓰이지 않는 것 같은 기본 인자"로 오인돼 제거될 수 있다.

**Fix:** KDoc 한 줄 추가.

```kotlin
/** 테스트 결정성용 시간 seam(phase 01). 프로덕션은 항상 기본값을 사용한다 — 제거 금지. */
private val nowMs: () -> Long = { System.currentTimeMillis() },
```

---

### IN-07: `testLogging { events 'failed' }` 만으로는 pass/skip 카운트가 보이지 않는다

**File:** `app/build.gradle` (`testOptions.unitTests.all.testLogging`)

성공 실행의 CI 로그가 완전히 비어 있어, 사람이 로그를 봐도 "몇 개가 돌았는지" 를 알 수
없다. CR-03 의 무성 실효를 육안으로도 못 잡게 만드는 보조 요인이다.

**Fix:** CR-03 의 수정안에 포함(`events 'passed', 'skipped', 'failed'` + `afterSuite` 요약).

---

### IN-08: GitHub Actions 가 가변 major 태그로 참조된다 (`contents: write` 권한 보유 서드파티 포함)

**File:** `.github/workflows/release.yml` (`actions/upload-artifact@v7`,
`softprops/action-gh-release@v3`, `actions/checkout@v4`, `actions/setup-java@v4`)

major 태그는 가변 참조라 업스트림에서 언제든 다른 커밋을 가리키게 바뀔 수 있다. 특히
`softprops/action-gh-release` 는 서드파티이면서 `contents: write` 권한으로 실행되고,
같은 워크플로 안에 `secrets.FIREBASE_DB_SECRET` 이 존재한다. 업스트림 계정 탈취 시
릴리스 자산 변조와 시크릿 유출이 동시에 가능하다.

**Fix:** 최소한 서드파티 액션은 커밋 SHA 로 고정한다.

```yaml
- uses: softprops/action-gh-release@<full-40-char-sha>  # v3.x
```

Dependabot 의 `github-actions` 에코시스템을 켜면 SHA 고정 상태를 유지하면서 갱신 PR 을
자동으로 받을 수 있다.

---

### IN-09: `System.currentTimeMillis()` 의 비단조성이 `dt` 클램프에 가려져 있다 (기록용, D-03 로 변경 금지)

**File:** `app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt:29` (기본 seam)

`System.currentTimeMillis()` 는 NTP 동기화나 사용자 시각 변경으로 **역행**할 수 있다.
역행 시 `now - lastTsMs` 가 음수가 되지만 `coerceIn(0.05, 2.0)` 이 0.05 로 잘라내므로
현재는 발산하지 않는다 — 즉 안전은 우연이 아니라 클램프 덕분이다. 정석은
`SystemClock.elapsedRealtime()` 이지만, D-03 이 이 클래스의 동작 변경을 금지했으므로
이번 phase 에서 바꾸지 않는 것이 옳다.

**Fix (차기 phase 제안):** seam 이 이미 뚫려 있으므로 프로덕션 기본값만
`{ SystemClock.elapsedRealtime() }` 로 교체하면 된다. 단 이는 D-03 범위 밖이며 골든
재동결 필요 여부 검토가 선행되어야 한다. 이번 phase 에서는 조치하지 않는다.

---

_Reviewed: 2026-08-24T08:08:47Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
