# Phase 1: 테스트 하네스와 CI 회귀 게이트 - Context

**Gathered:** 2026-08-24
**Status:** Ready for planning

<domain>
## Phase Boundary

RSSI 필터 캐스케이드(`MedianFilter` → `RssiPreFilter` → `KalmanFilter`)를 실기기 없이 JVM 유닛 테스트로 재생 가능하게 만들고, 그 골든 테스트를 GitHub Actions 릴리스 빌드의 차단 게이트로 건다. 다루는 요구사항은 TEST-03, TEST-04, CI-01, CI-02.

이 Phase 는 **측정 수단만** 세운다. 경보 등급(SAFE/WARNING/DANGER) 격상·해제 골든은 Phase 2, `BleService` 분해는 Phase 3. 현재 동작을 기대값에 못 박는 것이 목적이므로 버그 수정도 하지 않는다 (BUG-02 는 Phase 2).

</domain>

<decisions>
## Implementation Decisions

### 시간 시임 (Time Seam)

- **D-01:** `KalmanFilter` 의 벽시계 의존을 생성자 주입 시임으로 끊는다 — `KalmanFilter(preset, private val nowMs: () -> Long = { System.currentTimeMillis() })`. 기본인자가 현행 동작이라 호출부는 0줄 변경이고, `update`(78행)·`injectWarmup`(143행) 두 경로를 한 번에 커버한다.
- **D-02:** 골든 시퀀스의 dt = **120ms 정속 단일 프로필**. 근거는 `BleService.kt:682` `UWB_MEAS_FRESH_MS` 주석의 "정상 주기 ~120ms". 가변 dt 프로필은 필요해지면 나중에 추가한다.
- **D-03:** Phase 1 이 프로덕션 코드에 손대는 범위 = **시임 1건뿐**. 가시성·필드·기존 시그니처 무변경. 리팩터링·버그 수정 금지.
- **D-04:** "배포 APK 는 v1.1.70 과 동일 동작" 담보 = **diff 리뷰**. 실기 스모크 테스트는 하지 않는다 (현재 ADB 연결 기기 0대).

### 골든 픽스처 경계

- **D-05:** 골든이 고정하는 경계 = **순수 3단 직렬** `Median(N=3) → RssiPreFilter → Kalman`. `prevVel` 은 `0.0` 고정, `adaptiveQFactor` 대신 `1.0`, 프리셋은 `KALMAN_PRESET_NORMAL` 고정, `pEmaFilter`(표시용)는 제외. 실제 `BleService` 는 `prevVel` 1-step 피드백 폐루프지만, 폐루프째 고정하면 Phase 3 분해 때 기대값이 함께 무너진다. — **Reversibility:** costly — 경계를 바꾸면 전 시나리오 기대값을 재산출해야 하고, Phase 2·3 이 이 기준선 위에 쌓인다
- **D-06:** 시작 상태 **2세트** — 콜드 스타트 골든 1세트 + `injectWarmup` 경유 골든 1세트. 공분산 초기값이 pRR 5.0 vs 25.0 으로 갈려 같은 입력에도 출력이 달라진다. 입력 시퀀스는 공유하고 진입 경로만 다르게 한다.
- **D-07:** **단일 deviceId** 골든. 기기 간 상태 교차오염 검증은 별도 격리 테스트 1건으로 분리한다.
- **D-08:** 칼만 `Double` 비교 허용오차 **1e-9**. `MedianFilter`·`RssiPreFilter` 는 `Int` 반환이라 정확 일치로 비교한다.

### 골든 기대값 출처

- **D-09:** 기대값 산출 = **record-then-freeze** (현행 코드를 1회 실행해 출력을 동결). 현 시점의 버그도 함께 동결된다는 사실을 테스트 파일 헤더 주석에 명시한다. — **Reversibility:** costly — 동결된 값이 곧 회귀 기준선이라, 뒤집으려면 전 시나리오 재산출과 재리뷰가 필요하다
- **D-10:** 입력 RSSI 수열 = **합성 시나리오 수설계**. 접근·이탈·임펄스 튐·정지 4종. 실기 로그 캡처는 쓰지 않는다 (기기 0대 + 재현 불가).
- **D-11:** 기대값 저장 = **테스트 소스 인라인 Kotlin 배열 상수**. 리소스 파일·JSON 파서를 도입하지 않는다. 의존성 0, 파서 코드 0, PR diff 에 숫자가 직접 노출된다.
- **D-12:** 재동결 = **수동 갱신 + diff 리뷰 필수**. `-PupdateGolden` 같은 자동 재기록 경로를 만들지 않는다. record-then-freeze 의 최대 위험이 "깨지면 무심코 기대값을 덮어써 회귀를 승인하는 것"이기 때문이다.

### CI 게이트 위치

- **D-13:** 게이트 트리거 = **`release.yml` 안에만**. 별도 PR/브랜치 워크플로를 신설하지 않는다. 근거: `master` 단일 브랜치, `git log --merges` 빈 출력 = PR 머지 이력 0건이라 PR 트리거는 이 저장소에서 발화하지 않는다.
- **D-14:** 위치 = **같은 job `release`, `Restore google-services.json` 뒤 · `Build debug APK` 앞**. 이 자리면 테스트 실패가 Build · GitHub Release · Firebase PATCH 셋 전부를 차단한다. `google-services.json` 뒤여야 하는 것은 플러그인 구성 요구 때문의 하드 제약이다.
- **D-15:** 커맨드 = **`./gradlew testDebugUnitTest --no-daemon --console=plain`**. flavor 가 없고 buildTypes 가 debug/release 2종뿐이라 `assembleDebug` 와 정확히 같은 변형이다. `test` 는 release 까지 중복 실행하고, `check` 는 기준이 정의되지 않은 lint 를 게이트에 묶는다.
- **D-16:** 테스트 실패로 릴리스가 나가지 못한 태그 = **수동 삭제 후 재푸시**. CI 가 원격 태그를 지우는 스텝을 넣지 않는다 — 저장소 이력을 파괴적으로 건드리지 않는다.

### CI 진단 아티팩트

- **D-17:** 아티팩트 = `app/build/reports/tests/testDebugUnitTest`(HTML) + `app/build/test-results/testDebugUnitTest`(JUnit XML) **통째 업로드**.
- **D-18:** 업로드 조건 = **`if: always()`**. 게이트가 Build 앞이라 테스트 실패 시 job 이 그 자리에서 죽는다 — 조건을 걸지 않으면 정작 실패했을 때 업로드가 스킵된다. 성공 런의 리포트도 기준선으로 남는다.
- **D-19:** 골든 assert 실패 메시지 규약 = **"시나리오명 + 프레임 인덱스 + 단계명"**. 예: `approach/coldStart frame=17 stage=kalman`. 기본 `assertEquals` 메시지는 `expected:<-70.123> but was:<-70.124>` 만 남겨 어느 시나리오 어느 프레임인지 알 수 없고, 그러면 CI-02 가 사실상 미충족이 된다. D-11(인라인 배열)을 택했으므로 배열 인덱스가 곧 프레임 번호다.
- **D-20:** `app/build.gradle` 에 `testOptions { unitTests.all { testLogging { events 'failed'; exceptionFormat 'full' } } }` 추가. 아티팩트 zip 을 받지 않고 Actions 로그만으로 원인을 판별할 수 있게 한다. APK 산출물에 영향이 없어 D-03 과 충돌하지 않는다.

### 상위 문서 정정 (사용자 승인)

- **D-21:** 다음 3곳을 실제 코드에 맞춰 정정한다.
  - `.planning/ROADMAP.md` Phase 1 "출하 상태" — "앱 프로덕션 코드 무변경" → "동작 무변경(기본인자로 현행 경로 유지). 코드는 `KalmanFilter` 생성자 기본인자 1건 추가"
  - `.planning/ROADMAP.md` Phase 1 성공 기준 2 — 캐스케이드 순서 오기 `RssiPreFilter → MedianFilter(5샘플) → KalmanFilter` → `MedianFilter(3샘플) → RssiPreFilter → KalmanFilter`
  - `.planning/REQUIREMENTS.md` TEST-03 — 같은 오기 정정

### Claude's Discretion

- 테스트 클래스·파일 이름, 시나리오 상수명, 시나리오당 프레임 수
- 아티팩트 이름·보존기간, `actions/upload-artifact` 버전
- D-07 격리 테스트의 구체 시나리오 구성
- 합성 시나리오 4종의 실제 RSSI 수치 (D-10 은 종류만 확정)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### 프로젝트 계획

- `.planning/ROADMAP.md` — Phase 1 목표·성공 기준·출하 상태 (D-21 정정 반영본을 읽을 것)
- `.planning/REQUIREMENTS.md` — TEST-03, TEST-04, CI-01, CI-02 원문 (D-21 정정 반영본)
- `.planning/PROJECT.md` — 프로젝트 제약·핵심 가치
- `.planning/STATE.md` — 진행 상태
- `.planning/codebase/TESTING.md` — 기존 테스트 현황 매핑 (유닛·통합 테스트 0건)

### 골든 대상 코드

- `app/src/main/java/com/wf11/safealert/02_ble/MedianFilter.kt` — `DEFAULT_WINDOW = 3` (22행). 캐스케이드 1단
- `app/src/main/java/com/wf11/safealert/02_ble/RssiPreFilter.kt` — 비대칭 EMA + D-Boost. `push` 반환형 `Int`
- `app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt` — 시임 투입 대상. 벽시계 2곳 (78행 `update`, 143행 `injectWarmup`), 초기 공분산 분기
- `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` — 1473~1519행 = 실제 캐스케이드 호출 순서. 682행 = 120ms 주기 근거. 2306행 = 단계별 디버그 로그(반올림 때문에 기대값 소스로는 부적합)
- `app/src/main/java/com/wf11/safealert/06_utils/DevSettings.kt` — 프리셋·킬스위치 정의

### CI

- `.github/workflows/release.yml` — 단일 job `release`. `Verify keystore fingerprint` 가 기존 "실패 시 exit 1" 게이트 선례. 스텝 순서가 곧 게이트 실효 범위를 결정
- `app/build.gradle` — flavor 없음, buildTypes debug/release 2종(31-35행), `testImplementation 'junit:junit:4.13.2'` 선언됨, `testOptions`·lint 블록 없음 (74줄)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets

- **3개 필터 클래스가 이미 별도 파일로 분리된 순수 계산 클래스** — Android 프레임워크 의존이 없어 JVM 유닛 테스트 대상으로 그대로 쓸 수 있다. TEST-04("Android 프레임워크·실기기 없이 실행")가 성립하는 근거
- **`Verify keystore fingerprint` 스텝** — 이미 "실패 시 즉시 `exit 1`" 게이트 패턴. 테스트 게이트도 같은 형태로 붙인다
- **`testImplementation 'junit:junit:4.13.2'`** — 이미 선언되어 있어 신규 테스트 의존성 추가가 불필요

### Established Patterns

- **실제 캐스케이드 순서** (`BleService.kt` 1473 → 1507 → 1511 → 1519): `medianFilter.push` → `rssiPreFilter.push(medianValue, prevVel)` → `kf.update(preFiltered, ImuFusion.adaptiveQFactor)` → `pEmaFilter.push(kalmanRssi)`. 단순 직렬이 아니라 `prevVel` 1-step 피드백 폐루프다
- **문서-코드 불일치** — REQUIREMENTS TEST-03 과 ROADMAP 성공기준 2 의 `RssiPreFilter → MedianFilter(5샘플)` 은 순서와 윈도우 크기 둘 다 틀렸다. 실제는 Median 선행, `DEFAULT_WINDOW = 3` (`MedianFilter.kt:22`). D-21 로 정정
- **초기화 경로 2종의 공분산 상이** — 콜드 스타트 pRR=5.0/pVV=5.0, `injectWarmup` pRR=25.0/pVV=5.0 (v1.1.29 에서 1.0→25.0, 1.0→5.0). D-06 이 두 경로를 모두 고정하는 이유
- **`RssiPreFilter.push` 가 `Int` 반환** (내부 EMA 는 `Double`) — 이 지점에서 양자화되어 칼만 입력이 정수가 된다. D-08 의 정밀도 분기 근거
- **`release.yml` 은 단일 job, 전 스텝 순차** — Firebase PATCH 가 앱 자동 업데이트 팝업의 권위이므로, 게이트가 Build 앞이면 Build·Release·Firebase 셋 전부가 막힌다
- **`master` 단일 브랜치, PR 머지 이력 0건** — 전부 직커밋. 브랜치/PR 워크플로는 이 저장소 관행에서 발화하지 않는다 (D-13 근거)

### Integration Points

- `app/src/test/java/...` — **신규 디렉터리** (현재 `app/src/test`·`app/src/androidTest` 둘 다 없음)
- `KalmanFilter` 생성자 — 이 Phase 의 **유일한 프로덕션 접점** (D-01)
- `release.yml` 의 `Restore google-services.json` 과 `Build debug APK` **사이** — 테스트 스텝 + 아티팩트 업로드 스텝 삽입 지점
- `app/build.gradle` — `testOptions` 블록 신규 추가 (D-20)

### 제약 (계획 시 반드시 반영)

- google-services 플러그인 구성 요구 때문에 테스트 스텝은 **반드시** `Restore google-services.json` 뒤여야 한다
- 게이트가 Build 앞이라 테스트 실패 시 job 이 그 자리에서 죽는다 → 아티팩트 업로드에 `if: always()` 필수 (D-18)
- **현재 ADB 연결 기기 0대** → 실기 캡처·실기 스모크에 의존하는 접근은 전면 블로킹 (D-04·D-10 의 근거)
- `KalmanFilter` 내부가 `dt.pow(4)` → `Math.pow` 를 호출한다. JDK 구현 차이로 최하위 비트가 갈릴 수 있어 1e-9 허용오차를 채택했다 (D-08)
- `BleService.kt` 2306행 디버그 로그가 raw → med → pre → kf → pEma 전 단계를 찍지만 `kf=%.1f` 반올림이라 **기대값 소스로는 부적합**. raw(`Int`)만 입력 시퀀스 소스로 쓸 수 있다

</code_context>

<specifics>
## Specific Ideas

- **합성 시나리오 4종** (D-10): 접근(단조 상승) · 이탈(단조 하강) · 임펄스 튐(정상 수열에 이상치 1~2개 삽입 — `Median(N=3)` 의 존재 이유를 직접 겨냥) · 정지(잡음만 있는 평탄 구간)
- **assert 실패 메시지 형식 예**: `approach/coldStart frame=17 stage=kalman`
- **testLogging 설정 형태**: `testOptions { unitTests.all { testLogging { events 'failed'; exceptionFormat 'full' } } }`
- **시임 시그니처 형태**: `class KalmanFilter(preset: ..., private val nowMs: () -> Long = { System.currentTimeMillis() })`

</specifics>

<deferred>
## Deferred Ideas

- **가변 dt 프로필 골든**(불규칙 스캔 주기) — 120ms 정속으로 시작하고, 필요해지면 Phase 2 이후 추가
- **`pEmaFilter`(표시용 EMA) 골든** — 판정에 개입하지 않아 이번 범위 밖
- **`prevVel` 피드백 폐루프 전체를 고정하는 통합 골든** — 경보 전 경로를 다루는 Phase 2 범위
- **6대 초과 UWB 세션 플립 경로** — BUG-03, v2 (버그를 스펙으로 승격시키지 않기 위해 의도적 제외)
- **`.planning/graphs/` 커밋 여부** — 매 빌드 ~1.9MB 갱신. GSD 범위 밖 저장소 위생 결정, 사용자 판단 대기
- **`/gsd-profile-user`** — `.claude/CLAUDE.md` Developer Profile 이 `placeholder_added` 상태

</deferred>

---

*Phase: 1-테스트 하네스와 CI 회귀 게이트*
*Context gathered: 2026-08-24*
