---
phase: 01-ci
verified: 2026-08-24T17:10:00Z
status: human_needed
score: 4/4 must-haves verified
verdict: GOAL_ACHIEVED
behavior_unverified: 0
overrides_applied: 0
human_verification:
  - test: "v1.1.70 과 동일 서명으로 빌드된 debug APK 를 실기에 덮어쓰기 설치하고, 보행자↔지게차 접근/이탈 경보가 이전과 동일하게 발령·해제되는지 확인"
    expected: "설치·경보 동작이 v1.1.70 과 구별되지 않는다 (프로덕션 diff 는 KalmanFilter 생성자 기본 인자 시임 1건뿐)"
    why_human: "ADB 연결 기기 0대 — 실기 스모크는 코드 검사로 대체 불가. 단 이 항목은 Phase 1 의 4개 Success Criteria 밖의 출하 side-condition 이며, ROADMAP 자신이 '현장 검증 항목은 설치·경보 동작이 이전과 같은가 뿐'으로 이미 UAT 에 배정한 건이다. P-09 에 따라 검증 불가를 검증한 것처럼 기록하지 않는다."
---

# Phase 1: 테스트 하네스와 CI 회귀 게이트 — 검증 보고서

**Phase Goal:** 회귀가 현장이 아니라 CI 에서 먼저 드러나고, 3단 RSSI 필터 캐스케이드의 결정성이 기대값으로 고정된다
**Verified:** 2026-08-24
**Status:** human_needed (4개 SC 전부 달성 + SC 밖 실기 스모크 1건 잔여)
**Re-verification:** No — initial verification
**검증 방식:** goal-backward. SUMMARY 주장은 증거로 취급하지 않고, 파일·라인·git diff·워크플로 스텝 순서를 직접 읽어 판정했다.

---

## Success Criteria 판정

### SC1 — 실기·에뮬레이터 없이 JVM 유닛 테스트 실행·통과 (TEST-04) → **달성**

| 증거 | 위치 |
|------|------|
| JVM 소스셋에 테스트 2개 파일 존재 (246줄 / 133줄) | `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt`, `RssiCascadeIsolationTest.kt` |
| JUnit4 단독 의존 — Robolectric·androidTest 없음 | `app/build.gradle:79` `testImplementation 'junit:junit:4.13.2'` |
| 피검 필터 3종이 Android 프레임워크 비의존 | `02_ble/MedianFilter.kt`(import 0건), `02_ble/RssiPreFilter.kt`(`kotlin.math.roundToInt` 뿐), `02_ble/KalmanFilter.kt:3-4`(`DevSettings`, `kotlin.math.pow`) |
| 실제 실행 산출물 — 11 테스트 0 실패 | `app/build/test-results/testDebugUnitTest/TEST-...RssiCascadeTest.xml` (`tests="8" failures="0"`), `...IsolationTest.xml` (`tests="3" failures="0"`) |

근거: `./gradlew testDebugUnitTest` 는 `app/src/test`(JVM) 소스셋만 컴파일·실행하며, 여기에 계측 테스트가 섞여 있지 않다. 로컬 `--rerun-tasks` BUILD SUCCESSFUL 이 기기 없이 재현됐고 결과 XML 이 디스크에 실재한다.

### SC2 — 동일 입력 → 동일 출력이 고정되고, 상수·로직이 바뀌면 실패 (TEST-03) → **달성**

**최우선 확인 항목(tautology 여부): 통과.** 기대값은 구현을 재계산한 값이 아니라 **하드코딩된 동결 리터럴**이다.

| 확인 | 증거 |
|------|------|
| 기대값이 리터럴 배열로 소스에 박혀 있음 | `RssiCascadeTest.kt:44-121` — `EXPECTED_*_MEDIAN/PREFILTER/KALMAN` 8쌍 × 3스테이지, Kalman 은 17자리 double 리터럴 (예: `-83.44452861835482`) |
| 기대값이 실행 중 구현으로부터 재계산되는 경로 없음 | `runCascade()`(:130-156) 는 actual 만 만들고, `assertCascade()`(:158-172) 는 companion 상수와만 비교 |
| 3스테이지 전부 검증 (최종 출력만 보지 않음) | `:169` median(Int 정확일치) · `:170` prefilter(Int 정확일치) · `:171` kalman(delta `1e-9`) — 프레임 20개 전부 |
| 4시나리오 × 2시작상태 = 8 테스트 실재 | `:174-243` `approach/departure/impulse/stationary` × `coldStart/warmStart`. XML 의 `tests="8"` 과 일치 |
| 자동 기대값 갱신 경로 부재 (P-02) | `app/src/test`·`app/build.gradle` 에 `updateGolden`/`getenv`/`hasProperty` 0건 (주석 언급 1건 제외) |
| Double 비교에 delta 존재 (P-04) | `:171` 3-인자 오버로드 `1e-9` |
| 감도 실증 | 커밋 `3ee1f4a` 가 기대값 1개를 `1e-6` 만큼(`-83.44452861835482`→`-83.44452761835482`) 변조 → CI run `32701255911` 실패. `9068696` 로 원복, 현재 파일에 원값 복귀 확인 |
| 캐스케이드 배선이 프로덕션과 동형 | `:145-152` `median.push → preFilter.push(prevVel=0.0, fallBoost=false) → kf.update(imuQScale=1.0)` — BleService.kt:1473-1519 재현. 표시용 `pEmaFilter` 는 경계 밖(P-05 준수) |
| dt 가 클램프 밖으로 새지 않음 | `FRAME_DT_MS=120L`(:31) vs `KalmanFilter.kt:95` `coerceIn(0.05, 2.0)` — 0.12s 는 구간 내부. 가짜 클록 `1_000_000L` 기점 전진(:131,:146) |
| 기기 간 상태 격리 (D-07) | `RssiCascadeIsolationTest.kt:55/78/107` 3 테스트 — 인터리브 push·선택 clear·clearAll 후 재생이 solo 베이스라인과 일치 |

**감도의 한계(정보 — 결함 아님):** `impulse`·`stationary` 는 prefilter 출력이 상수(-78 / -80)로 평탄해져 Kalman 골든이 전 프레임 동일값이다(`:90-93`, `:107-119`). 따라서 이 두 시나리오는 Kalman 파라미터(q/R/pVV) 변경을 잡지 못한다 — Kalman 회귀 탐지력은 전적으로 `approach`·`departure`(전 정밀도 double 40개)에 실려 있다. SC2 문언은 approach/departure 만으로 충족되므로 판정에는 영향 없으나, Phase 2 에서 시나리오를 늘릴 때 고려할 사항이다.

### SC3 — CI 가 테스트를 자동 실행하고, 실패 시 APK 릴리스 차단 (CI-01) → **달성**

게이트가 **APK 빌드보다 앞**에 있다 — Goal 문장의 "현장이 아니라 CI 에서 먼저" 성립 조건:

| release.yml 라인 | 스텝 |
|---|---|
| 25 | Restore google-services.json |
| 41 | Grant execute permission |
| **44-45** | **Run unit tests (golden RSSI cascade)** — `./gradlew testDebugUnitTest --no-daemon --console=plain` |
| 47-48 | Upload test reports (`if: always()`) |
| 58 | Extract version |
| **62** | **Build debug APK** |
| 65 / 68 / 75 | Rename APK / Create GitHub Release & Upload APK / Update Firebase Realtime DB |

- 테스트 스텝이 `Build debug APK`(62)·`Create GitHub Release`(68)·`Update Firebase`(75) 전부보다 앞선다.
- **실패 삼킴 없음(P-01):** `continue-on-error`·`|| true`·`set +e`·`exit 0` 모두 0건. `if:` 는 아티팩트 업로드의 `always()`(48)와 Firebase 의 citest 가드(76)뿐 — 테스트 스텝 자체엔 무조건부.
- **원격 태그 파괴 스텝 없음(P-07):** 워크플로에 `git tag -d`/`push --delete` 0건.
- 종단 실증: 레드 태그 `v0.0.1-citest2` → run `32701255911` failure, `Extract version`/`Build debug APK`/`Rename APK`/`Create GitHub Release`/`Update Firebase` 5스텝 skipped, `gh release view` → release not found. 그린 `v0.0.1-citest1` → run `32700966688` success 13/13.

### SC4 — CI 아티팩트만으로 어떤 테스트가 어떤 기대값에서 깨졌는지 실기 없이 판별 (CI-02) → **달성**

| 증거 | 위치 |
|------|------|
| 실패 메시지 4요소 규약 (시나리오/시작상태/프레임/스테이지) | `RssiCascadeTest.kt:169-171` `"$scenario/$startState frame=$i stage=median\|prefilter\|kalman"` |
| 실패 시 콘솔에 전체 스택·기대/실제 출력 | `app/build.gradle:51-57` `testOptions { unitTests.all { testLogging { events 'failed'; exceptionFormat 'full' } } }` |
| job 이 죽어도 리포트 업로드 | `.github/workflows/release.yml:47-56` `if: always()` + `upload-artifact@v7`, path = HTML 리포트 + JUnit XML, retention 14일 |
| 실제 레드 아티팩트가 4요소를 지목 | `TEST-com.wf11.safealert.ble.RssiCascadeTest.xml`: `approach/coldStart frame=10 stage=kalman expected:<-83.44452761835483> but was:<-83.44452861835482>` |

---

## 프로덕션 diff — 런타임 동작 불변 (코드 수준)

`git show 0f1fd50 -- .../KalmanFilter.kt` 전량 검토 결과, 변경은 3종뿐이며 모두 시임의 기계적 귀결이다:

1. `KalmanFilter.kt:27-30` 생성자에 `private val nowMs: () -> Long = { System.currentTimeMillis() }` 기본 인자 추가
2. `:81,:91,:95,:96` 지역변수 `nowMs` → `now` 리네임 + `System.currentTimeMillis()` → `nowMs()`
3. `:146` `injectWarmup` 의 `System.currentTimeMillis()` → `nowMs()`

- 수치 상수(`pVV=5.0`, `coerceIn(0.05,2.0)`, preset q/R), `updateCnt++` 위치, `reset()`, `updatePreset()`, 공개 게터 **무변경** — P-03 준수.
- 호출부 2곳 모두 인자 1개(preset)만 전달 → 기본값 적용: `BleService.kt:450`, `BleService.kt:1454`. 호출부 변경 0건.
- 따라서 프로덕션 경로의 시각원(source of time)은 `System.currentTimeMillis()` 로 동일하다 — **코드 수준에서 런타임 동작 불변이 참**이다. 실기 설치 스모크는 아래 human 항목으로 남긴다(P-09: 불가한 검증을 한 것처럼 기록하지 않음).

---

## 금지사항(prohibitions) 코드 대조

| ID | 규칙 | 코드 대조 결과 |
|----|------|---------------|
| P-01 | 테스트 스텝 실패 삼킴 금지 | 준수 — `continue-on-error`/`\|\| true` 0건 |
| P-02 | 기대값 자동 갱신 경로 금지 | 준수 — `updateGolden`/env 스위치 0건 |
| P-03 | Kalman 수치·구조 무변경 | 준수 — diff 전량 확인 |
| P-04 | delta 없는 Double assertEquals 금지 | 준수 — `:171` delta 1e-9 |
| P-05 | pEmaFilter·prevVel 폐루프 제외 | 준수 — `prevVel = 0.0` 고정, pEma 미사용 |
| P-06 | 입력은 합성 수기 설계 | 준수 — `:33-41` 리터럴 4종, 캡처 파일 없음 |
| P-07 | CI 에서 원격 태그 삭제 금지 | 준수 — 워크플로 0건 |
| P-08 | 변조 기대값 커밋 잔존 금지 | 준수 — `3ee1f4a` 변조 → `9068696` 원복, 현재 파일 원값. `git status --porcelain` 에 `app/` 변경 0건 |
| P-09 | 실기 스모크 위장 금지 | 준수 — SUMMARY·본 보고서 모두 미검증으로 명시 |

*판정 등급: 전 항목 judgment-tier. 코드 대조로 반증 시도했고 위반 증거를 찾지 못했다 — 사람 최종 확인 권장(비권위 판정).*

## Requirements Coverage

| ID | 상태 | 증거 |
|----|------|------|
| TEST-03 | ✓ SATISFIED | SC2 |
| TEST-04 | ✓ SATISFIED | SC1 |
| CI-01 | ✓ SATISFIED | SC3 |
| CI-02 | ✓ SATISFIED | SC4 |

고아 요구사항 없음 — `REQUIREMENTS.md:84-87` 매핑 4건이 두 PLAN 의 `requirements` 필드에 모두 청구되어 있다.

## Anti-Patterns

변경 파일 4종(`RssiCascadeTest.kt`, `RssiCascadeIsolationTest.kt`, `app/build.gradle`, `release.yml`, `KalmanFilter.kt`)에 `TBD/FIXME/XXX/TODO/HACK/PLACEHOLDER` **0건**.

## 정보성 관찰 (결함 아님 — 판정에 영향 없음)

1. **`unitTests.returnDefaultValues` 미설정 + `KalmanFilter` → `DevSettings`(`06_utils/DevSettings.kt:3-4` `android.content.Context/SharedPreferences`) 의존.** 현재는 `DevSettings` object 의 `<clinit>` 이 Android API 를 호출하지 않아 JVM 에서 통과한다. 장래 `DevSettings` 가 초기화 시점에 Android API 를 부르면 골든 테스트가 "not mocked" 로 무너진다 — Phase 3 분해 시 유의.
2. **`01-VALIDATION.md` frontmatter 가 `status: draft` / `nyquist_compliant: false`.** validate-phase 가 이 문서를 `validated` 로 승격하지 않았다. 문서 생애주기 문제이며 ROADMAP SC 와 무관.
3. **ROADMAP Phase 1 은 `Mode: mvp` 이나 Goal 이 User Story 형식이 아니다.** 따라서 MVP-mode 의 User Flow Coverage 표 대신 4개 Success Criteria 대조로 검증했다.
4. `impulse`/`stationary` 시나리오의 Kalman 감도 한계 — SC2 항목 참조.

## Gaps

없음. 4개 Success Criteria 전부 코드·워크플로·실행 산출물로 뒷받침된다.

## Human Verification Required

### 1. 실기 설치 스모크 (SC 밖 출하 side-condition)

**Test:** v1.1.70 이 설치된 실기에 이번 빌드 APK 를 덮어쓰기 설치하고 접근/이탈 경보를 재현한다.
**Expected:** 경보 발령·해제 타이밍이 v1.1.70 과 구별되지 않는다.
**Why human:** ADB 연결 기기 0대. 코드 수준 근거(호출부 0곳 변경·기본값 = 기존 시각원)는 위에서 확정했으나, 실기 동작은 원리상 grep 으로 볼 수 없다. ROADMAP 자신이 이 건을 유일한 현장 검증 항목으로 이미 배정해 두었다.

---

## Phase 전체 판정

**GOAL_ACHIEVED** — 4/4 Success Criteria 달성. 골든 기대값은 재계산 tautology 가 아니라 동결 리터럴이고, 3스테이지 × 4시나리오 × 2시작상태가 실재하며, CI 게이트가 APK 빌드 앞에 위치해 레드 실행에서 실제로 릴리스를 막았다. 잔여는 SC 밖 실기 스모크 1건(UAT).

---

_Verified: 2026-08-24_
_Verifier: Claude (gsd-verifier)_
