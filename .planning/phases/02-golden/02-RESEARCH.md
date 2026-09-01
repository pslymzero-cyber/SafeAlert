# Phase 2: golden (안전 크리티컬 경로 골든 테스트) - Research

**Researched:** 2026-08-27
**Domain:** Android JVM 유닛 테스트(Robolectric) 기반 안전 크리티컬 상태머신 골든 테스트, UWB 세션 시뮬레이션
**Confidence:** MEDIUM

<user_constraints>
## User Constraints (from CONTEXT.md)

> 02-CONTEXT.md 전문을 이번 세션 앞부분에서 verbatim 으로 읽었음. 아래는 그 Decisions / Claude's Discretion / Deferred Ideas 섹션의 축약 인용이 아니라 원문 그대로의 재현이다. 원본 파일은 `.planning/phases/02-golden/02-CONTEXT.md`.

### Locked Decisions

- **D-2A**: Robolectric 채택 — `BleService`(`LifecycleService`)를 실기기·에뮬레이터 없이 JVM 에서 인스턴스화. `app/build.gradle` 에 신규 추가 필요(현재 없음).
- **D-2B**: `ServiceController.get()` 만 사용하고 `.create()` 는 호출하지 않는다 — `onCreate()` 의 실제 부작용(포그라운드 알림 등록, BLE 스캐너 시작 등)을 트리거하지 않고 판정 로직 진입점(`processAlert` 등)에만 접근하는 것이 목표.
- **D-2C**: `processAlert` 의 단일 벽시계 호출점(`val now`, BleService.kt:1422)을 Phase 1 의 `nowMs` 기본 인자 시임 패턴(KalmanFilter.kt:29 와 동일 형태)으로 통일한다.
- **D-2D**: `OverlayManager` 등 안드로이드 프레임워크 종속 부작용은 `canDrawOverlays` 게이트(OverlayManager.kt:117-145)를 테스트 환경에서 자연히 false 로 떨어뜨려 무력화한다 — 별도 모킹 프레임워크 도입 금지.
- **D-2E**: 관찰 표면은 리플렉션(`ReflectionHelpers`)으로 `trackingStateMap`/`dangerContactStreakMap` 등 내부 맵 상태를 직접 읽거나, `BROADCAST_ALERT` 브로드캐스트 인텐트(`ShadowApplication.getBroadcastIntents()`)를 관찰한다.
- **D-2F**: 골든 값은 record-then-freeze — 현재 구현이 실제로 산출하는 값을 1회 기록해 고정한다. 재기록은 항상 수동.
- **D-2G**: (건너뜀/CONTEXT.md 원문 내 명시적 D-2G 항목 없음 — D-2F 다음이 D-2H 로 이어짐. 세션 앞부분에서 verbatim 확인.)
- **D-2H**: `processAlert` 의 두 번째 벽시계 호출점(`lastApproachAtMs`, BleService.kt:1512)도 D-2C 와 동일한 `nowMs` 시임으로 통일한다.
- **D-3A**: BUG-02(저속 접근 WARNING 미도달)의 회귀 테스트도 동일한 record-then-freeze 원칙을 따르되, 수정 후 기대값이 "WARNING 도달"로 바뀌는 것 자체가 이번 Phase 의 성공 기준이다.
- **D-3B**: BUG-02 의 근본 원인은 **미확정**으로 명시적으로 잠금(locked). `PROJECT.md:77` 의 기존 진단("`injectWarmup` 프리셋 최소값 포화")은 `KalmanFilter.kt:139-147` 의 `injectWarmup` 실제 본문에 프리셋 참조도 최소값 클램프도 없다는 사실로 사실무근임이 이미 확인됨 — 리서치 단계에서 특정 근본 원인을 단정하지 말 것.
- **D-4A**: "좀비 워치독"은 v1.1.43/44 의 철거(teardown) 기반 메커니즘이 아니라 v1.1.46 에서 이를 폐지하고 남은 **신선도 게이트**(freshness gate, `UWB_MEAS_FRESH_MS`)를 가리키는 것으로 재해석한다 — TEST-02 범위는 신선도 게이트 동작이지 철거 매커니즘이 아니다.
- **D-4D**: UWB 세션 골든 테스트 범위는 `MULTICAST_MAX = 6`(UwbRanger.kt:77) 이하로 한정한다 — 6대 초과 플립 경로(BUG-03)는 v2 이월, 이번 범위 밖.

### Claude's Discretion

**없음(none)** — 아래 3개 plan-단계 세부사항만 재량:
- **D-3C**: 저속 접근 회귀 시퀀스의 기울기(slope)·프레임 수는 계획 단계에서 결정.
- **D-4B**: `uwbRanger` 코루틴이 실제로 시작되지 않았음을 확인하는 방법(리플렉션 주입 후 상태 검사 등)은 계획 단계에서 결정.
- **D-4C**: "신선(fresh)" 판정의 여유 마진(margin)은 계획 단계에서 결정.

### Deferred Ideas (OUT OF SCOPE)

- BUG-03(UWB 세션 6대 초과 시 플립으로 인한 사이렌 1~2초 끊김) — v2 이월. 우회책(`uwbExclusiveJudgeEnabled` off)이 이미 존재하며 UWB 는 보조 수단이므로 主 판정(RSSI)에 영향 없음.
- 판정 반경 값 변경(지게차 15m/8m, 그 외 5m/3m) — 사용자가 현행 값이 맞다고 확인. "값이 아니라 값대로 동작하지 않는 게 문제".
- UWB/iBeacon/Firebase 프로필 공유 제거 — 모두 보조 수단으로 존치.
- UWB 를 主 판정으로 승격 — BLE RSSI 가 主 판정 권위, 역할 위계를 뒤집는 변경은 범위 밖.
- 신규 기능 추가, 1바이트 비트팩 페이로드 레이아웃 변경.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| TEST-01 | 고정 RSSI 시퀀스 재생으로 SAFE→WARNING→DANGER 격상·역방향 해제의 등급·전이 시점을 기대값과 비교 (경보 전체 경로 골든 테스트) | Robolectric `ServiceController.get()`(D-2B) + `nowMs` 시임 통일(D-2C/D-2H) + 리플렉션/브로드캐스트 관찰(D-2E) + record-then-freeze(D-2F) 패턴 — 아래 Architecture Patterns, Code Examples 섹션 |
| TEST-02 | UWB 세션 6대 이하 범위에서 Case A/B 전환·신선도 게이트 발화·재연결 경로를 기대값과 비교 | `MULTICAST_MAX=6` 범위 고정(D-4D), 신선도 게이트 재해석(D-4A), `UwbRanger` 코루틴 미시작 확인 기법(D-4B, plan 단계 재량) — 아래 Architecture Patterns §UWB 세션 시뮬레이션, Common Pitfalls §좀비 워치독 재해석 오류 |
| BUG-02 | `injectWarmup` 프리셋 최소값 포화 해소 — 저속 접근 시에도 WARNING 등급 도달 | 근본 원인 미확정 유지(D-3B), record-then-freeze 로 회귀 시퀀스 고정 후 수정으로 기대값 전환(D-3A/D-3C) — 아래 Common Pitfalls §BUG-02 근본원인 미확정, Assumptions Log |
</phase_requirements>

## Project Constraints (from CLAUDE.md)

`.claude/CLAUDE.md`(SafeAlert 루트)에서 추출한, 이번 Phase 계획이 위반해서는 안 되는 지시:

- **Tech stack**: Kotlin/Android, `minSdk 26`/`targetSdk 34`/`compileSdk 34`, JDK 17, viewBinding — 기존 코드베이스 전제. 신규 테스트 코드도 이 스택 위에서 동작해야 함.
- **Dependencies**: `androidx.core.uwb:1.0.0-alpha09` 프리릴리스에 경보 로직의 30~40%가 의존 — 테스트가 이 프리릴리스 API 의 실동작(실제 세션 개설)에 의존하면 안 됨(JVM 유닛 테스트 환경에는 실제 UWB 하드웨어가 없음 — UwbRanger 자체는 인스턴스화하되 실제 세션 개설 없이 상태만 검증하는 것이 D-4B 의 취지).
- **Compatibility**: 1바이트 비트팩 BLE 프로토콜 레이아웃 변경 금지 — 골든 테스트가 페이로드 인코딩을 다루더라도 레이아웃 자체를 바꾸는 방향으로 계획해서는 안 됨.
- **Performance**: `processAlert`(약 1,000줄, `BleService.kt:1406-2554`)가 스캔 콜백(메인 스레드)에서 실행 — Phase 2 는 이 함수를 다른 스레드로 옮기는 리팩터(PERF-01, Phase 5 소관)를 하지 않는다. 시임 추가는 "동작을 바꾸지 않는 최소한의 기계적 시임"(STATE.md Blockers/Concerns)만 허용.
- **Security**: `minifyEnabled false`(`app/build.gradle:36`) — 릴리스 빌드 난독화 없음. 이번 Phase 는 릴리스 빌드 설정을 건드리지 않음(테스트 전용 변경).
- **Platform**: 포그라운드 서비스 + 지속 알림 필수. D-2B 의 `.create()` 미호출이 바로 이 제약을 우회하는 장치 — 포그라운드 서비스 등록 등 프레임워크 부작용을 트리거하지 않고 판정 로직에만 접근.
- **Process**: 기능 추가 시 `versionName` patch +0.0.1 · 커밋 · 태그 · 푸시. 버그·단순 수정은 버전 유지. BUG-02 수정은 사용자 현장 확인 전까지 "버그 수정"으로 취급 — 버전 정책은 실행 단계에서 프로젝트 관례(patch 증가, 사용자 요청 시 커밋)를 따른다.
- **GSD Workflow Enforcement**: 파일 변경 작업은 `/gsd-execute-phase` 등 GSD 진입점을 통해야 함 — 이 문서는 계획 이전 리서치 산출물이므로 해당 없음, 후속 실행 단계에서 적용.
- **코드 스타일**: 로깅은 `companion object { const val TAG = "ClassName" }` + `Log.d/Log.e` 패턴, 주석은 한국어, `runCatching { }.getOrDefault()` 방어적 파싱 패턴 — 신규 테스트 헬퍼 코드도 이 컨벤션을 따라야 함.

## Summary

Phase 2 는 `BleService`(3,899줄 단일 클래스) 안에 있는 안전 크리티컬 판정 경로(`processAlert`)와 UWB 세션 수명주기를, **분해하지 않은 채로** JVM 유닛 테스트로 고정하는 작업이다. 핵심 기술은 Robolectric — Android 프레임워크 클래스를 실기기·에뮬레이터 없이 JVM 에서 흉내 내는 표준 테스트 프레임워크다. `[VERIFIED: Maven Central 레지스트리 검색]` 현재 최신 안정 버전은 **4.15.1**(2025-06-21 게시, 타임스탬프 1750543813443) — 이 버전을 이번 Phase 의 표준으로 채택한다. WebSearch 합성 결과 중 "4.16 이 최신"이라는 주장이 있었으나 Maven Central 레지스트리 자체 버전 목록(4.15.1, 4.15, 4.15-beta-1, 4.14.1, 4.14, 4.14-beta-1, 4.13, …)에서 4.16 은 확인되지 않았다 — 4.15.1 을 사용하고 4.16 주장은 미확인으로 폐기한다.

CONTEXT.md 의 16개 잠금 결정(D-2A~D-4D)이 이미 아키텍처를 사실상 확정했다: `ServiceController.get()`(`.create()` 미호출)로 부작용 없이 `BleService` 인스턴스를 얻고, 리플렉션(`ReflectionHelpers`)으로 내부 상태 맵을 직접 읽거나 `ShadowApplication.getBroadcastIntents()`로 `BROADCAST_ALERT` 를 관찰하며, `nowMs` 기본 인자 시임(Phase 1 에서 확립된 패턴)으로 벽시계를 제어 가능하게 만든다. 이 패턴들에 대한 CITED 등급(WebSearch 합성, 원출처 미독립확인) 근거는 확보했으나, `.create()` 를 생략했을 때 `onCreate()` 가 정말 호출되지 않는다는 D-2B 의 핵심 전제는 이번 세션에서 Robolectric 원본 소스(`ServiceController.java` — 404)나 테스트 파일(`ServiceControllerTest.java` — 특정 테스트 미발견)로 독립 검증하지 못했다. **CITED 등급으로 유지하고, 계획/실행 단계에서 실제 트라이얼 테스트 1회로 확인할 것을 권고한다.**

BUG-02 는 근본 원인이 명시적으로 미확정 상태로 잠겨 있다(D-3B) — `PROJECT.md:77` 의 기존 진단("`injectWarmup` 프리셋 최소값 포화")은 `KalmanFilter.kt:139-147` 실제 본문 확인 결과 사실무근으로 드러났다. 이 리서치는 근본 원인을 추정하지 않으며, record-then-freeze 로 현재(버그 있는) 동작을 먼저 고정한 뒤 수정으로 기대값이 바뀌는 것 자체를 성공 기준으로 삼는 D-3A 의 접근을 지지한다.

**Primary recommendation:** `org.robolectric:robolectric:4.15.1` 을 `testImplementation` 으로 추가하고, `ServiceController.of(BleService(), intent).get()`(`.create()` 생략) + `ReflectionHelpers` + `ShadowApplication.getBroadcastIntents()` + `nowMs` 시임 조합으로 `processAlert` 와 UWB 세션 상태머신을 화이트박스 관찰하는 골든 테스트를 작성한다. 새 로직을 만들지 말고 Phase 1 의 `runCascade`/`assertCascade` 셰어드 헬퍼 패턴(STATE.md Decisions 기록)을 재사용한다.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| 경보 등급 판정 (`processAlert`) | API/Backend 등가 계층 (`BleService`, 안드로이드 프레임워크 외부에서 재생 가능한 순수 판정 로직) | Browser/Client 등가 없음 — 단일 프로세스 앱이므로 서버 계층 부재 | `processAlert` 는 BLE 스캔 콜백에서 트리거되지만 판정 자체는 입력(RSSI 시퀀스)→출력(등급) 순수 변환에 가까움 — Robolectric 이 프레임워크 계층을 흉내 내는 이유가 바로 이 판정 로직을 프레임워크 의존 없이 재생하기 위함 |
| UWB 세션 수명주기 (`UwbRanger`) | API/Backend 등가 (`UwbRanger`, `06_utils/`) | Database/Storage 등가 없음(세션 상태는 메모리 `ConcurrentHashMap`) | 세션 개설·철회·재연결은 하드웨어 종속 부작용을 가지므로 실기기 없이는 실제 세션이 열리지 않음 — D-4B 재량 항목이 바로 "코루틴 미시작 확인"인 이유 |
| 관찰/검증 표면 (리플렉션, 브로드캐스트) | 테스트 하네스 계층(Robolectric Shadow) | — | `BleService` 프로덕션 코드를 변경하지 않고 외부에서 내부 상태를 읽는 우회 경로 — 프로덕션 아키텍처 계층이 아니라 테스트 전용 계층 |
| 골든 값 저장/비교 | 테스트 코드(JVM 유닛 테스트) | CI(GitHub Actions) | record-then-freeze 값은 테스트 소스에 상수로 박히고, CI 는 이를 회귀 게이트로만 소비 — Phase 1 에서 이미 확립된 `release.yml` `MIN_TOTAL` 플로어 메커니즘 재사용 |
| BLE 페이로드 인코딩(bitpack) | BLE/Protocol 계층(`BleConstants`, `02_ble/`) | — | 이번 Phase 범위 밖(레이아웃 변경 금지) — 골든 테스트가 다루더라도 인코딩 로직 자체는 불변 |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `org.robolectric:robolectric` | 4.15.1 `[VERIFIED: Maven Central 레지스트리 검색, 타임스탬프 1750543813443]` | Android 프레임워크 클래스를 JVM 에서 시뮬레이션 — 실기기/에뮬레이터 없이 `LifecycleService` 인스턴스화 | Android 유닛 테스트에서 프레임워크 의존 코드를 실기기 없이 테스트하는 사실상 표준. D-2A 의 명시적 채택 대상 |
| `junit:junit` | 4.13.2 `[VERIFIED: app/build.gradle:79]` (기존 확인, 인용: `testImplementation 'junit:junit:4.13.2'`) | 테스트 러너/어서션 | 이미 프로젝트에 존재 — 신규 추가 불필요 |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `androidx.test.ext:junit` | 1.1.5 `[VERIFIED: app/build.gradle:80]` (인용: `androidTestImplementation 'androidx.test.ext:junit:1.1.5'`) | `AndroidJUnit4` 러너, Robolectric 의 `@RunWith(AndroidJUnit4::class)` 와 함께 사용 가능 | 이미 `androidTestImplementation` 스코프로 존재(계측 테스트용) — Robolectric 은 `testImplementation` 스코프(JVM 유닛 테스트)이므로, 계획 단계에서 동일 버전(1.1.5)을 `testImplementation` 스코프에도 추가할지 결정 필요(순수 JUnit4 `@RunWith(RobolectricTestRunner::class)` 만으로도 충분할 수 있음 — Ponytail 관점: 이미 있는 걸 재사용, 새 버전 조사 불필요) |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Robolectric | Espresso + 실기기/에뮬레이터 계측 테스트 | CI 에서 에뮬레이터 구동 비용·속도 저하, TEST-04("Android 프레임워크·실기기 없이 JVM 유닛 테스트로 실행") 요구사항과 정면 충돌 — 채택 불가, D-2A 로 이미 결정됨 |
| 리플렉션 기반 관찰(`ReflectionHelpers`) | Mockito/MockK 로 `BleService` 부분 모킹 | D-2E 가 리플렉션을 명시적으로 지정 — 모킹 프레임워크는 신규 의존성 추가이자 "판정 로직을 실제로 실행하고 관찰"하는 골든 테스트의 취지(record-then-freeze)와 어긋남. Claude's Discretion 없음(잠금) |

**Installation:**
```
// app/build.gradle dependencies 블록에 추가
testImplementation 'org.robolectric:robolectric:4.15.1'
```

**Version verification:** `org.robolectric:robolectric` 은 이번 세션에 Maven Central Solr 검색 API(`search.maven.org/solrsearch/select?q=g:org.robolectric+AND+a:robolectric&core=gav`)로 직접 조회하여 4.15.1(2025-06-21)이 최신임을 확인함. `androidx.test.ext:junit` 은 동일한 API 조회가 2회 모두 빈 결과(`numFound:0`, 원인 미상 — group/artifact ID 가 Maven Central Solr 인덱스에서 이 그룹에 한해 다르게 토큰화되는 것으로 추정, 확정 못함)를 반환해 레지스트리로 재확인하지 못했으나, 이미 `app/build.gradle:80` 에 pin 되어 있는 1.1.5 를 그대로 재사용하는 것으로 대체함(신규 미확인 버전 도입보다 안전).

## Package Legitimacy Audit

> `gsd-tools package-legitimacy check` 는 `--ecosystem npm|pypi|crates` 만 지원하며 Maven 생태계는 지원하지 않는다(이번 세션에 `--ecosystem maven` 실행 시 사용법 오류 반환, 확인됨). 아래는 Maven Central 레지스트리 직접 조회를 대체 근거로 사용한 감사다.

| Package | Registry | Age | Downloads | Source Repo | Verdict | Disposition |
|---------|----------|-----|-----------|-------------|---------|-------------|
| `org.robolectric:robolectric` | Maven Central | 4.9.2(2022-12) 부터 4.15.1(2025-06)까지 연속 게시 이력 확인 — 최소 수년 이상 | 다운로드 수 미노출(Maven Central Solr API 는 다운로드 카운트 미제공) | `github.com/robolectric/robolectric` `[ASSUMED: 훈련 지식 — 이번 세션에 직접 방문 확인은 ServiceController.java 개별 파일에 한해 404, 저장소 자체 존재는 미재확인]` | OK (대체 근거: 다수 버전의 연속 게시 이력 + 널리 알려진 Android 테스트 표준 라이브러리) | Approved |
| `androidx.test.ext:junit` | Google Maven / Maven Central | 이미 `app/build.gradle:80` 에 1.1.5 로 pin 되어 실사용 중 | — | AndroidX 공식(`androidx.test`) | OK (기존 pin 재사용, 신규 조사 불필요) | Approved — 버전 변경 없이 재사용 |
| `junit:junit` | Maven Central | 이미 `app/build.gradle:79` 에 4.13.2 로 pin 되어 실사용 중 | — | JUnit4 공식 | OK (기존 pin 재사용) | Approved — 변경 없음 |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

*`org.robolectric:robolectric` 은 훈련 지식으로 알려진 패키지명이며, `package-legitimacy check` 가 Maven 생태계를 지원하지 않아 seam 의 OK 검증을 받지 못했다. Maven Central 레지스트리에 4.9.2→4.15.1 의 연속 버전 이력이 존재한다는 사실 자체는 `[VERIFIED: Maven Central 레지스트리]`이나, 이것이 슬롭스쿼팅 여부까지 배제하지는 않는다 — 계획 단계에서 `checkpoint:human-verify` 를 이 신규 의존성 추가 태스크 앞에 배치할 것을 권고한다.*

## Architecture Patterns

### System Architecture Diagram

```
[고정 RSSI 시퀀스 / UWB 세션 이벤트 시퀀스]  (테스트 입력, 코드로 하드코딩)
            │
            ▼
[Robolectric JVM]  ── ServiceController.of(BleService(), intent).get()  (※ .create() 미호출 — D-2B)
            │            └─ onCreate() 트리거 안 됨 → 포그라운드 알림·실제 BLE 스캐너 시작 없음
            ▼
[BleService 인스턴스] ── nowMs 시임으로 벽시계 주입 (D-2C: BleService.kt:1422, D-2H: BleService.kt:1512)
            │         ── ReflectionHelpers.setField 로 uwbRanger 등 내부 필드 주입 (D-4B)
            ▼
[processAlert(...) 직접 호출]  ← 시퀀스의 각 RSSI 표본을 순차 투입
            │
            ├──▶ [내부 상태 맵]  (trackingStateMap, dangerContactStreakMap 등)
            │         │
            │         ▼ ReflectionHelpers.getField 로 읽기 (D-2E)
            │    [테스트 어서션 — 기대 등급/상태와 비교]
            │
            └──▶ [BROADCAST_ALERT sendBroadcast(...)]
                      │
                      ▼ shadowOf(context).getBroadcastIntents() 로 관찰 (D-2E)
                 [테스트 어서션 — 기대 인텐트 페이로드와 비교]

[OverlayManager 등 프레임워크 부작용] ── canDrawOverlays 게이트가 테스트 환경에서 false로 자연 무력화 (D-2D)
                                          → 별도 모킹 없이 조용히 스킵됨

[UWB 세션 별도 경로]
[UwbRanger 인스턴스] ── 코루틴 미시작 상태로 유지 (D-4B, plan 단계 확인 기법 결정)
            │
            ▼
[uwbDistances 맵 직접 조작]  (실제 세션 개설 없이 표본 주입 — MULTICAST_MAX=6 이하로 한정, D-4D)
            │
            ▼
[신선도 게이트 판정: freshUwbDistM, UWB_MEAS_FRESH_MS]  (D-4A — 철거 워치독 아님, 신선도 게이트로 재해석)
            │
            ▼
[Case A(UWB 권위) / Case B(RSSI 폴백) 전환 어서션]
```

### Recommended Project Structure

```
app/src/test/java/com/wf11/safealert/
├── ble/
│   ├── RssiCascadeTest.kt          # 기존(Phase 1, TEST-03) — 참고용, 수정 없음
│   ├── AlertCascadeGoldenTest.kt   # 신규(TEST-01) — SAFE→WARNING→DANGER 격상/해제 골든
│   ├── LowSpeedApproachRegressionTest.kt  # 신규(BUG-02) — 저속 접근 회귀 케이스
│   └── UwbSessionGoldenTest.kt     # 신규(TEST-02) — Case A/B, 신선도 게이트, 재연결
└── support/
    └── BleServiceTestHarness.kt    # 신규 — ServiceController 생성, ReflectionHelpers 래퍼,
                                     #        runCascade/assertCascade 셰어드 헬퍼(Phase 1 패턴 재사용)
```

### Pattern 1: 부작용 없는 서비스 인스턴스화 (D-2B)

**What:** `ServiceController.of(service, intent).get()` 로 `LifecycleService` 인스턴스를 얻되 `.create()` 는 호출하지 않는다.
**When to use:** `processAlert` 등 판정 로직 진입점에만 접근하고 `onCreate()` 의 프레임워크 부작용(포그라운드 알림 등록, 실제 BLE 스캐너 시작)은 원치 않을 때.
**Example:**
```kotlin
// Source: WebSearch 합성(Robolectric ServiceController 사용 패턴) [CITED] — .create() 생략 시
// onCreate() 미호출이라는 핵심 전제는 원본 소스(ServiceController.java, 404)로
// 이번 세션에 독립 검증하지 못함. 계획/실행 단계에서 트라이얼 테스트로 재확인 권고.
val controller = Robolectric.buildService(BleService::class.java, intent)
val service: BleService = controller.get()   // .create() 호출 안 함
// service.processAlert(...) 등 직접 호출로 진입
```

### Pattern 2: 리플렉션 기반 내부 상태 관찰 (D-2E)

**What:** `ReflectionHelpers.getField` 로 `private`/`internal` 맵 필드를 직접 읽는다.
**When to use:** `BleService` 프로덕션 코드에 테스트 전용 getter 를 추가하지 않고 골든 값을 관찰할 때.
**Example:**
```kotlin
// Source: WebSearch 합성(Robolectric ReflectionHelpers 사용 패턴) [CITED]
val trackingMap: Map<String, Any> = ReflectionHelpers.getField(service, "trackingStateMap")
// 필드명이 실제와 다르면 RuntimeException 발생(필드명 오류를 조용히 삼키지 않음)
```

### Pattern 3: 브로드캐스트 인텐트 관찰 (D-2E)

**What:** `shadowOf(context).getBroadcastIntents()` 로 `sendBroadcast()` 호출 이력을 리스트로 얻는다.
**When to use:** `BROADCAST_ALERT` 발신 시점·페이로드를 어서션할 때(6개 호출부: DANGER 1760/2299/2517, SAFE 2092/2229, WARNING 2539 — 이전 세션에 Read 로 확인).
**Example:**
```kotlin
// Source: WebSearch 합성(Robolectric ShadowApplication 사용 패턴) [CITED]
val intents: List<Intent> = shadowOf(ApplicationProvider.getApplicationContext<Context>()).broadcastIntents
val alertIntents = intents.filter { it.action == "BROADCAST_ALERT" }
```

### Pattern 4: `nowMs` 시임을 통한 벽시계 제어 (D-2C/D-2H, Phase 1 패턴 확장)

**What:** `System.currentTimeMillis()` 직접 호출 대신 기본 인자로 주입 가능한 람다를 사용한다.
**When to use:** `processAlert` 의 시간 기반 분기(예: TTC, streak, 신선도 게이트)를 결정론적으로 재생할 때.
**Example:**
```kotlin
// Source: KalmanFilter.kt:29 (이전 세션 Read 로 확인, verbatim)
// private val nowMs: () -> Long = { System.currentTimeMillis() },
// D-2C/D-2H 가 요구하는 것: processAlert 의 두 벽시계 호출점(BleService.kt:1422, :1512)을
// 동일한 형태의 nowMs 기본 인자로 통일 — 테스트에서 nowMs = { fakeClock }으로 주입 가능해짐
```

### Anti-Patterns to Avoid

- **`.create()` 호출**: D-2B 를 위반 — 포그라운드 서비스 등록, 실제 BLE 스캐너 시작 등 원치 않는 부작용이 트리거됨. 계획 단계 태스크가 실수로 `.create()` 를 포함하지 않도록 명시적으로 검증할 것.
- **Mockito/MockK 로 `BleService` 부분 모킹**: D-2E 가 리플렉션을 명시적으로 지정 — 모킹은 신규 의존성이자 잠금 결정 위반.
- **`processAlert` 를 별도 클래스로 추출해서 테스트**: REFACTOR-01(로직 분리)은 Phase 3 소관. Phase 2 는 "동작을 바꾸지 않는 최소한의 기계적 시임"(STATE.md)만 허용 — 로직 이동은 범위 밖.
- **UWB 실제 세션 개설 시도**: 하드웨어 종속 부작용이라 JVM 에서 재현 불가 — `uwbDistances` 맵에 표본을 직접 주입하고 코루틴은 미시작 상태로 유지(D-4B).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Android 프레임워크 클래스 시뮬레이션 | 자체 페이크 `Context`/`Service` 스텁 | Robolectric | `LifecycleService`, `SharedPreferences`, `BroadcastReceiver` 등 다수 프레임워크 클래스의 상호작용을 정확히 흉내 내는 것은 방대한 표면적 — 자체 스텁은 edge case 를 반드시 놓침 |
| 벽시계 결정론화 | `Thread.sleep()` 기반 시간 경과 시뮬레이션 | `nowMs` 기본 인자 시임(Phase 1 패턴) + `ShadowSystemClock` | `Thread.sleep()` 은 테스트를 느리게 만들고 CI 에서 타이밍 불안정(flaky)을 유발 — 시임은 즉시·결정론적 |
| private 필드 접근 | `BleService` 에 테스트 전용 public getter 30개+ 추가 | `ReflectionHelpers` | 프로덕션 API 표면을 오염시키지 않고 테스트에서만 접근 — D-2E 의 명시적 선택 |

**Key insight:** 이번 Phase 는 "테스트하기 쉽게 리팩터링"이 아니라 "현재 형태 그대로 관찰"이 목표다(REFACTOR-01~04 는 Phase 3). 프로덕션 코드 변경은 `nowMs` 시임 2곳(D-2C/D-2H)으로 최소화되어야 하며, 이것조차 Phase 1 에서 이미 확립된 동일 패턴의 확장이므로 신규 설계 결정이 아니다.

## Runtime State Inventory

N/A — Phase 2 는 rename/refactor/migration 이 아니다. 리네이밍이나 문자열 치환이 없으므로 이 섹션은 생략한다.

## Common Pitfalls

### Pitfall 1: `.create()` 를 실수로 호출

**What goes wrong:** `Robolectric.buildService(...).create().get()` 형태로 작성하면 `onCreate()` 가 트리거되어 포그라운드 알림 등록·실제 BLE 스캐너 초기화 시도 등 부작용이 발생, 테스트가 예측 불가능해지거나 크래시.
**Why it happens:** Robolectric 공식 예제·튜토리얼 다수가 `.create().get()` 체이닝을 기본 패턴으로 제시(WebSearch 확인) — D-2B 는 이 관용구에서 의도적으로 `.create()` 를 뺀 것.
**How to avoid:** 계획 단계 태스크 설명에 "`.create()` 호출 금지"를 명시적으로 박아 넣고, 코드 리뷰/검증 단계에서 grep 으로 확인.
**Warning signs:** 테스트 실행 중 `NotificationManager` 관련 예외, 또는 `BleScanner` 초기화 로그가 테스트 출력에 나타남.

### Pitfall 2: 좀비 워치독 재해석 오류 (D-4A)

**What goes wrong:** TEST-02 를 계획하면서 v1.1.43/44 의 철거(teardown) 기반 워치독 메커니즘을 되살리려 하거나, 이미 폐지된 코드 경로를 기준으로 기대값을 설계.
**Why it happens:** "좀비 워치독"이라는 용어 자체가 이전 버전들(v1.1.43~45)의 메커니즘을 연상시키지만, v1.1.46 에서 이를 명시적으로 폐지하고 신선도 게이트(freshness gate)로 대체했다(이전 세션 Read 로 BleService.kt:2545-2553 확인).
**How to avoid:** TEST-02 의 "좀비 워치독 발화" 범위를 `UWB_MEAS_FRESH_MS` 기반 신선도 판정으로 한정 — 철거/재개설 사이클을 재현하려 하지 않는다.
**Warning signs:** 계획에 `onDeviceLost` 강제 트리거나 세션 강제 철거 태스크가 등장하면 D-4A 위반 가능성.

### Pitfall 3: BUG-02 근본원인 조기 확정

**What goes wrong:** 계획 단계에서 "`injectWarmup` 프리셋 최소값 포화가 원인이므로 X 를 수정한다"는 식으로 특정 근본 원인을 전제하고 태스크를 설계.
**Why it happens:** `PROJECT.md:77` 에 이미 진단이 문서화되어 있어 직관적으로 신뢰하기 쉽다.
**How to avoid:** D-3B 가 이 진단을 명시적으로 사실무근이라 확인했으므로(`injectWarmup` 실제 본문에 프리셋 참조·최소값 클램프 모두 없음), 계획은 "record-then-freeze 로 현재 동작 고정 → 저속 접근 시퀀스가 WARNING 미도달임을 회귀 테스트로 캡처 → 수정 후 기대값이 WARNING 도달로 바뀌는 것을 성공 기준으로 채택" 순서로 설계하고, 원인 조사 자체를 계획의 태스크로 포함시킬 것.
**Warning signs:** 계획서에 "근본 원인: ..." 이라는 확정 문구가 리서치 근거 없이 등장.

### Pitfall 4: androidx.test.ext:junit 스코프 혼동

**What goes wrong:** 이미 `androidTestImplementation 'androidx.test.ext:junit:1.1.5'`(app/build.gradle:80)가 있다고 해서 JVM 유닛 테스트(`src/test/`)에서 바로 `AndroidJUnit4` 를 import 하려다 컴파일 실패.
**Why it happens:** `androidTestImplementation` 은 계측 테스트(`src/androidTest/`) 전용 스코프 — `src/test/`(JVM 유닛 테스트)에서는 별도로 `testImplementation` 스코프에 추가해야 클래스패스에 올라옴.
**How to avoid:** Robolectric 테스트에 `AndroidJUnit4` 러너가 필요하면 `testImplementation 'androidx.test.ext:junit:1.1.5'` 를 별도로 추가(같은 버전 재사용). 순수 `@RunWith(RobolectricTestRunner::class)` 만 쓴다면 이 추가 자체가 불필요할 수 있음 — 계획 단계에서 실제 러너 선택에 따라 결정.
**Warning signs:** `Unresolved reference: AndroidJUnit4` 컴파일 에러가 `src/test/` 파일에서 발생.

## Code Examples

### 골든 테스트 스켈레톤 (TEST-01, Phase 1 셰어드 헬퍼 패턴 확장)

```kotlin
// Source: 패턴 종합 — Robolectric 사용법은 WebSearch 합성 [CITED],
// nowMs 시임은 KalmanFilter.kt:29 [VERIFIED, 이전 세션 Read]에서 확장,
// Phase 1의 runCascade/assertCascade 셰어드 헬퍼 관례는 STATE.md Decisions 기록 [VERIFIED: STATE.md:75]
// (인용: "Task 2 골든 8테스트는 shared-helper(runCascade/assertCascade)로 DRY 유지")
@RunWith(RobolectricTestRunner::class)
class AlertCascadeGoldenTest {
    @Test
    fun `SAFE to WARNING to DANGER escalation matches frozen expected values`() {
        val fakeClock = FakeClock(startMs = 0L)
        val service = Robolectric.buildService(BleService::class.java).get()  // .create() 없음 — D-2B
        // nowMs 시임 주입은 D-2C/D-2H 구현 후 가능 — processAlert 호출 시 fakeClock.nowMs 전달
        // ... RSSI 시퀀스 투입, ReflectionHelpers.getField 로 등급 관찰, 기대값과 비교
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| 좀비 워치독(철거 기반, v1.1.43/44) | 신선도 게이트(freshness gate, v1.1.46) | v1.1.46 | TEST-02 의 "좀비 워치독 발화" 범위가 철거 사이클이 아닌 신선도 판정으로 재정의됨(D-4A) |
| RSSI 시작 게이트(-80/-90dBm, v1.1.32/33) | 게이트 철폐, UWB 상시 페어(v1.1.45) → RSSI 기본+UWB 게이트 재도입(v1.1.49) | v1.1.45→v1.1.49 | UWB 세션 성립 조건이 여러 차례 뒤집힘 — TEST-02 골든 값은 **현재(v1.1.70) 배포 상태**를 record-then-freeze 로 고정해야 하며, 과거 버전의 동작을 기준으로 삼지 않는다 |

**Deprecated/outdated:**
- 철거 기반 좀비 워치독(v1.1.43~45): v1.1.46 에서 폐지, 신선도 게이트로 대체(D-4A 가 재해석 근거).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `ServiceController.get()`(`.create()` 미호출) 시 `onCreate()` 가 실제로 호출되지 않는다 | Architecture Patterns §Pattern 1, D-2B 근거 | D-2B 의 핵심 전제 — 틀리면 포그라운드 알림 등록 등 원치 않는 부작용이 모든 골든 테스트에서 발생, 테스트 설계 전체 재작업 필요. 계획/실행 단계에서 트라이얼 테스트 1회로 반드시 확인 권고 |
| A2 | `org.robolectric:robolectric` 이 슬롭스쿼팅이 아닌 정당한 패키지다 | Package Legitimacy Audit | `package-legitimacy check` 가 Maven 생태계 미지원으로 seam 의 OK 검증을 받지 못함 — 낮은 확률이나 계획 단계 `checkpoint:human-verify` 권고로 완화 |
| A3 | `github.com/robolectric/robolectric` 가 실제 소스 저장소다 | Package Legitimacy Audit | 이번 세션에 저장소 루트 자체는 재방문 확인하지 못함(개별 파일 경로만 404) — 훈련 지식 기반 |
| A4 | ShadowSystemClock/ReflectionHelpers/ShadowApplication 의 API 사용법(메서드 시그니처, 동작)이 WebSearch 합성 그대로다 | Architecture Patterns §Pattern 2~4 | 원출처(robolectric.org javadoc, GitHub 소스)로 독립 검증하지 않음 — 실제 API 명이나 동작이 미묘하게 다르면 계획 단계 코드 스켈레톤이 컴파일 실패할 수 있음. 실행 단계에서 실제 컴파일로 즉시 드러남(낮은 리스크) |
| A5 | `testImplementation 'androidx.test.ext:junit:1.1.5'` 를 추가하면 JVM 유닛 테스트에서 정상 동작한다 | Pitfall 4, Standard Stack §Supporting | 버전 자체는 `[VERIFIED: app/build.gradle:80]`(계측 테스트 스코프)이나 `testImplementation` 스코프에서의 호환성은 미검증 |

**If this table is empty:** N/A — 위 5건 존재.

## Open Questions (RESOLVED)

> 4건 전부 계획 단계(02-01~02-04 작성·리비전)에서 해소됨. 각 항목의 **Resolution** 이 해소 근거다.

1. **[RESOLVED]** **`.create()` 미호출 시 `onCreate()` 스킵이 실제로 보장되는가?**
   - What we know: WebSearch 합성 다수가 이 패턴을 관용구로 제시(`.create().get()` 이 `onCreate()` 트리거라는 대비 설명).
   - What's unclear: Robolectric 원본 소스(`ServiceController.java`)나 공식 테스트에서 직접 확인하지 못함(404 / 특정 테스트 미발견).
   - **Resolution:** 권고대로 트라이얼 게이트가 계획에 배치됨 — **02-01-PLAN.md Task 2 (a-2) `assumptionA1_getWithoutCreate_staysInitialized()`**. `.create()` 없이 `get()` 만 호출한 인스턴스에서 `onCreate()` 부작용(포그라운드 알림 등록 등)이 없음을 실행 단계 첫 자동 검증으로 확인하며, 실패 시 후속 태스크가 진행되지 않는 게이트다. 가정 A1 은 문서 추정이 아니라 실측 대상으로 이관 완료.

2. **[RESOLVED]** **`processAlert` 라인 범위 불일치.**
   - What we know: 세 문서가 각기 다른 종료 라인(2543/2544/2554)을 제시했다.
   - **Resolution:** 소스 재확인 결과 **`03_service/BleService.kt` :1406-2543 이 정답**이다 — :1406 시그니처, :2543 닫는 중괄호, 본문 1,138줄(파일 총 3,899줄). :2554 는 다음 함수 `uwbJudgeModeExclusive` 의 시작 라인을 종료 라인으로 오기한 것이고, 2544 는 요약 인용 오차다. 02-CONTEXT.md · 02-01-PLAN.md 가 이미 1406-2543 을 사용 중이라 계획 수정 불필요. 함께 검증한 인접 참조도 전부 정확: :1422 `val now`, :1512 `lastApproachAtMs`, :1115 호출부, :2554-2560 `uwbJudgeModeExclusive`, :2566-2570 `freshUwbDistM`, :3610 `sendAlertBroadcast`, `KalmanFilter.kt` :29 `nowMs` · :63-68 게터 · :139-148 `injectWarmup`.

3. **[RESOLVED]** **02-CONTEXT.md `canonical_refs` 의 경로 접두어 불일치.**
   - What we know: `.claude/CLAUDE.md` Component Responsibility 테이블이 실제 패키지 위치를 명시.
   - **Resolution:** 실제 디스크 경로 기준으로 phase 문서 전체를 통일했다. 02-CONTEXT.md 정정 3건 — `02_ble/BleService.kt` → **`03_service/BleService.kt`**, `06_utils/KalmanFilter.kt` → **`02_ble/KalmanFilter.kt`**, `05_uwb/UwbRanger.kt` → **`06_utils/UwbRanger.kt`**. 계획 쪽 잔여 오기도 함께 정정 — 02-04-PLAN.md 의 `06_utils/KalmanFilter.kt`, 02-03/02-04-PLAN.md 의 `ble/support/BleServiceTestHarness.kt`(산출 경로는 02-01 이 선언한 `support/BleServiceTestHarness.kt`), 그리고 `UwbSessionGoldenTest.kt` 경로를 소유 계획(02-03)이 선언한 `ble/` 로 통일(본 문서 트리·Wave 0 Gaps, 02-VALIDATION.md, 02-PATTERNS.md). `06_utils/DevSettings.kt` · `06_utils/OverlayManager.kt` 는 원래부터 정확했다. 패키지명도 검증함: `com.wf11.safealert.service` / `.ble` / `.utils`. 현재 phase 문서가 참조하는 모든 `app/src/main` 경로가 디스크에 실재한다(일괄 자동 확인).

4. **[RESOLVED]** **`androidx.test.ext:junit` 버전을 Maven Central 레지스트리로 재확인하지 못함.**
   - What we know: `app/build.gradle:80` 에 1.1.5 로 이미 pin.
   - **Resolution:** 신규 조사 불필요로 확정. 계획은 이 아티팩트를 **추가하지 않는다** — 02-01-PLAN.md Task 1 (a) 가 `@RunWith(RobolectricTestRunner::class)` 만 쓰므로 불필요하다고 명시했고, 기존 `androidTestImplementation 'androidx.test.ext:junit:1.1.5'` pin 은 손대지 않은 채 그대로 남는다. 신규 버전 도입 0건.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 17 | Robolectric 실행, Kotlin 컴파일 | `[ASSUMED]` (프로젝트 표준으로 명시, 이번 세션 로컬 `java -version` 미실행) | 17 (Temurin 또는 동등) | 없음 — JDK 17 은 `compileSdk 34`/AGP 8.3.2 요구사항, 계획 단계에서 로컬 확인 필요 |
| Gradle 8.3.2(wrapper) | 빌드/테스트 실행 | `[ASSUMED]` (app/build.gradle 에서 확인된 AGP 버전 기준 추정, wrapper 파일 자체는 미확인) | 8.3.2 | 없음 |
| Maven Central / Google Maven 접근(신규 의존성 다운로드) | `org.robolectric:robolectric:4.15.1` 최초 빌드 시 다운로드 | `[ASSUMED]` (이번 세션 WebFetch 로 search.maven.org 접근은 성공했으나, 로컬 Gradle 빌드 환경의 실제 네트워크/프록시 설정은 미확인) | — | 오프라인 환경이면 최초 `./gradlew build` 시 의존성 다운로드 실패 — 계획 단계에서 최초 1회 온라인 빌드 필요성 명시 권고 |

**Missing dependencies with no fallback:**
- 없음 — 위 3건 모두 표준 개발 환경에 통상 존재하는 도구이며, 미존재 시 명백한 오류 메시지로 즉시 드러남(빌드 실패, 낮은 리스크).

**Missing dependencies with fallback:**
- 없음.

## Validation Architecture

> `.planning/config.json` 의 `workflow.nyquist_validation` 은 `true`(명시) — 이번 섹션 필수 포함.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 4.13.2(기존) + Robolectric 4.15.1(신규, 이번 Phase 에서 추가) |
| Config file | `app/build.gradle`(신규 `testImplementation` 라인 추가 필요, 현재 Robolectric 관련 config 없음) |
| Quick run command | `./gradlew testDebugUnitTest --tests "com.wf11.safealert.ble.AlertCascadeGoldenTest"` (신규 테스트 클래스명은 계획 단계에서 확정) |
| Full suite command | `./gradlew testDebugUnitTest` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| TEST-01 | 고정 RSSI 시퀀스 재생 → SAFE→WARNING→DANGER 격상·역방향 해제 등급/시점 기대값 비교 | unit(Robolectric) | `./gradlew testDebugUnitTest --tests "*.AlertCascadeGoldenTest"` | ❌ Wave 0 — 신규 작성 |
| TEST-02 | UWB 6대 이하 세션 Case A/B 전환·신선도 게이트·재연결 경로 기대값 비교 | unit(Robolectric) | `./gradlew testDebugUnitTest --tests "*.UwbSessionGoldenTest"` | ❌ Wave 0 — 신규 작성 |
| BUG-02 | 저속 접근 시퀀스가 WARNING 등급에 도달(수정 후), 회귀 케이스로 고정 | unit(Robolectric) | `./gradlew testDebugUnitTest --tests "*.LowSpeedApproachRegressionTest"` | ❌ Wave 0 — 신규 작성 |

### Sampling Rate

- **Per task commit:** `./gradlew testDebugUnitTest --tests "<해당 신규 테스트 클래스>"` (빠른 국소 확인)
- **Per wave merge:** `./gradlew testDebugUnitTest` (전체 유닛 테스트 스위트)
- **Phase gate:** 전체 스위트 그린 상태에서 `/gsd-verify-work` 진행. CI(`release.yml:65` `MIN_TOTAL=17`)의 최소 테스트 수 플로어를 이번 Phase 가 추가하는 신규 테스트 수만큼 상향 조정 필요(계획 단계 태스크로 명시 권고 — TEST-01/TEST-02/BUG-02 각각 최소 1개 이상 테스트 클래스 추가하므로 `MIN_TOTAL` 을 최소 그만큼 올려야 CI 가 실효성을 가짐).

### Wave 0 Gaps

- [ ] `app/src/test/java/com/wf11/safealert/ble/AlertCascadeGoldenTest.kt` — TEST-01 커버
- [ ] `app/src/test/java/com/wf11/safealert/ble/UwbSessionGoldenTest.kt` — TEST-02 커버
- [ ] `app/src/test/java/com/wf11/safealert/ble/LowSpeedApproachRegressionTest.kt` — BUG-02 커버
- [ ] `app/src/test/java/com/wf11/safealert/support/BleServiceTestHarness.kt` — ServiceController 생성·리플렉션 래퍼·Phase 1 스타일 shared-helper(runCascade/assertCascade 확장)
- [ ] Framework install: `app/build.gradle` 에 `testImplementation 'org.robolectric:robolectric:4.15.1'` 추가 — 현재 없음
- [ ] `.github/workflows/release.yml:65` `MIN_TOTAL=17` 상향 — 신규 테스트 수만큼

## Security Domain

> `.planning/config.json` 의 `workflow.security_enforcement`: `true`, `security_asvs_level`: `1`, `security_block_on`: `"high"` — 이번 섹션 필수 포함.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | 이번 Phase 는 인증 로직을 다루지 않음(테스트 추가 + `nowMs` 시임만) |
| V3 Session Management | no | 앱 세션 관리 변경 없음. UWB "세션"은 인증 세션이 아니라 UWB 하드웨어 레인징 세션(무관한 용어 중복) |
| V4 Access Control | no | 접근 제어 로직 변경 없음 |
| V5 Input Validation | yes(간접) | 테스트 입력(RSSI 시퀀스, UWB 거리 표본)은 하드코딩된 신뢰 가능한 테스트 데이터 — 프로덕션 입력 검증 경로 자체는 변경하지 않으므로 신규 리스크 없음. 기존 `runCatching { }.getOrDefault()` 패턴(프로젝트 컨벤션) 유지 |
| V6 Cryptography | no | 암호화 관련 변경 없음. 참고: 프로젝트 자체가 `minifyEnabled false`(난독화 없음), Firebase 로그 평문 저장이 기존 알려진 리스크이나 이번 Phase 범위 밖 |

### Known Threat Patterns for {stack}

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| 리플렉션(`ReflectionHelpers`)으로 private 필드 접근이 테스트 코드에만 국한되지 않고 프로덕션 빌드에 섞여 들어감 | Tampering(의도치 않은 상태 변경) | 리플렉션 코드는 `src/test/` 디렉터리에만 존재해야 하며 `src/main/`에 유입되지 않도록 계획 단계에서 파일 배치를 명시 — `minifyEnabled false` 환경에서는 리플렉션이 릴리스 빌드에서도 필드명을 그대로 노출하므로, 테스트 전용 코드가 프로덕션 APK 에 포함되지 않게 스코프(`testImplementation`/`src/test/`)를 엄격히 지킬 것 |
| CI 아티팩트(테스트 리포트)에 RSSI/UWB 원시 좌표 등 민감할 수 있는 시퀀스 데이터가 남음 | Information Disclosure | 이번 Phase 의 테스트 데이터는 하드코딩된 합성 시퀀스(record-then-freeze 로 고정된 상수)이며 실제 사용자 위치 데이터가 아님 — CI-02(기존, Phase 1 완료)의 테스트 리포트 아티팩트 보존과 상충 없음. 계획 단계에서 실제 현장 로그를 그대로 골든 값으로 복사하지 않도록 주의(합성/익명화된 시퀀스만 사용) |
| 신규 의존성(`org.robolectric:robolectric`)의 공급망 리스크 | Tampering(빌드 시점 악성 코드 주입) | Package Legitimacy Audit 섹션의 `checkpoint:human-verify` 권고를 계획 단계에서 태스크로 반영 |

## Sources

### Primary (HIGH confidence)

- 없음 — 이번 Phase 리서치에서 HIGH 등급(직접 Read 로 확인한 in-repo 값 제외, 외부 소스 한정) 근거는 확보하지 못함. 아래 Secondary/Tertiary 참고.

### Secondary (MEDIUM confidence)

- Maven Central 레지스트리 검색 API(`search.maven.org/solrsearch/select?q=g:org.robolectric+AND+a:robolectric&core=gav`) — `org.robolectric:robolectric` 버전 이력 조회(4.15.1 이 최신, 2025-06-21). `[VERIFIED: Maven Central 레지스트리]`
- `app/build.gradle`(이전 세션 Read, 이번 세션 Grep 재확인 line 79-81) — `junit:junit:4.13.2`, `androidx.test.ext:junit:1.1.5`, `androidx.test.espresso:espresso-core:3.5.1` pin 확인. `[VERIFIED: app/build.gradle:79-81]`
- WebSearch 합성 — Robolectric `ServiceController`/`ShadowSystemClock`/`ReflectionHelpers`/`ShadowApplication` 사용 패턴(원출처 미독립확인, `research-store put` 으로 confidence MEDIUM 캐싱됨). `[CITED]`

### Tertiary (LOW confidence)

- WebSearch 합성 — Robolectric gradle 표준 설정 관용구(`testOptions { unitTests { includeAndroidResources = true } }` 등). `[CITED, LOW]`
- WebFetch 시도 2건 실패/불확정 — `ServiceController.java` 원본 소스(404), `ServiceControllerTest.java`(특정 테스트 미발견) — D-2B 핵심 전제(`.create()` 미호출 시 `onCreate()` 스킵)를 원출처로 검증하지 못함, Open Question #1 참조.

## Metadata

**Confidence breakdown:**
- Standard Stack: MEDIUM — Robolectric 버전은 Maven Central 레지스트리로 직접 검증(사실상 HIGH 급 신뢰이나 패키지 정당성 자체가 seam 의 OK 검증을 받지 못해 전체 등급은 MEDIUM 으로 보수적 책정), `androidx.test.ext:junit`/`junit`은 기존 in-repo pin 재사용(VERIFIED)
- Architecture: MEDIUM — CONTEXT.md 의 16개 잠금 결정이 아키텍처를 사실상 확정했으나, 핵심 메커니즘(D-2B `.create()` 미호출→`onCreate()` 스킵)이 CITED 등급에 머무름
- Pitfalls: MEDIUM — D-4A(좀비 워치독 재해석), D-3B(BUG-02 원인 미확정)는 이전 세션 Read 로 코드 근거 확보(VERIFIED급), 나머지는 WebSearch 합성(CITED)

**Research date:** 2026-08-27
**Valid until:** 2026-09-26 (30일 — Robolectric 은 안정적 릴리스 주기, 프로젝트 자체 배포 속도(3개월 70+ 릴리스)에 비해 상대적으로 느리게 변화하는 도메인이나, `androidx.core.uwb` 프리릴리스 의존성 특성상 계획 착수 시 재확인 권고)
