# Roadmap: SafeAlert

## Overview

이 로드맵은 새 기능을 붙이지 않는다. `BLE RSSI 근접 판정이 같은 상황에서 같은 결과를 낸다`는 Core Value 를 성립시키기 위해, 재발 고리를 만드는 구조 자체를 걷어낸다.

순서는 하나의 제약이 결정한다 — **측정 수단이 먼저다.** 유닛·통합 테스트 0건 상태에서 3,899줄 안전 크리티컬 로직을 분해하면, 지금의 "회귀를 사용자가 실기에서 발견하는" 방식이 리팩터링 도중에도 그대로 남는다. 그래서 Phase 1~2 가 골든 테스트와 CI 게이트로 현재 동작을 기대값에 못 박고, Phase 3~4 가 그 안전망 위에서 `BleService` 분해와 상태 단일화를 수행하며, Phase 5 가 판정 연산을 메인 스레드에서 떼어낸다.

전 단계가 MVP 모드다. **모든 Phase 는 앱이 출하 가능하고 현장에서 검증 가능한 상태로 끝난다.** 실기 검증이 유일한 회귀 확인 수단이고 그 사이클이 사용자 현장 가용 시간에 묶여 있으므로, 앱이 반쯤 분해된 채 검증 불가 상태로 오래 머무는 것이 이 프로젝트의 실패 모드다. 각 Phase 의 `출하 상태` 항목이 그 Phase 종료 시점에 무엇이 배포되고 현장에서 무엇을 확인하는지를 명시한다.

버전 정책은 기존 관례를 따른다 — 기능 추가 시 `versionName` patch +0.0.1 · 커밋 · 태그 · 푸시, 버그·단순 수정은 버전 유지.

## Phases

**Phase Numbering:**

- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: 테스트 하네스와 CI 회귀 게이트** - 필터 캐스케이드를 기대값으로 고정하고, 회귀를 실기가 아니라 CI 가 먼저 잡게 한다
- [x] **Phase 2: 안전 크리티컬 경로 골든 테스트** - 경보 격상·해제 전 경로와 UWB 세션 전환을 기대값에 못 박고, 안전망으로 저속 접근 미도달 버그를 잡는다
- [x] **Phase 3: BleService 분해** - 경보 판정·UWB 거리·캘리브레이션을 세 컴포넌트로 분리하고 동작 보존을 증명한다
- [ ] **Phase 4: 기기 상태 단일화** - 40여 개 분산 Map 을 `DeviceTrackingState` 로 통합해 좀비 엔트리를 구조적으로 차단한다
- [ ] **Phase 5: 판정 워커 분리** - `processAlert` 를 BLE 스캔 콜백에서 떼어내 다수 기기 현장에서 UI 가 끊기지 않게 한다

## Phase Details

### Phase 1: 테스트 하네스와 CI 회귀 게이트

**Goal**: 회귀가 현장이 아니라 CI 에서 먼저 드러나고, 3단 RSSI 필터 캐스케이드의 결정성이 기대값으로 고정된다
**Mode:** mvp
**Depends on**: Nothing (first phase)
**Requirements**: TEST-03, TEST-04, CI-01, CI-02
**Success Criteria** (what must be TRUE):

  1. 유지보수자가 실기기·에뮬레이터 없이 JVM 유닛 테스트를 실행해 통과 결과를 얻는다 (TEST-04)
  2. 동일 RSSI 입력 시퀀스를 `MedianFilter`(3샘플) → `RssiPreFilter` → `KalmanFilter` 에 흘리면 항상 동일 출력이 나오고, 필터 상수나 로직이 바뀌면 테스트가 실패한다 (TEST-03)
  3. GitHub Actions 빌드가 테스트를 자동 실행하고, 테스트가 실패하면 APK 릴리스가 차단된다 (CI-01)
  4. 유지보수자가 CI 실행 결과 아티팩트만 열어 어떤 테스트가 어떤 기대값에서 깨졌는지 실기 없이 판별한다 (CI-02)

**Plans:** 2/2 plans executed

Plans:
**Wave 1**

- [x] 01-01-PLAN.md — 시간 시임 + 3단 RSSI 캐스케이드 골든 테스트(4시나리오 × 2시작상태) + Gradle 실패 로깅 + `release.yml` 테스트 게이트·리포트 아티팩트 배선

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 01-02-PLAN.md — 레드 트라이얼로 게이트 차단 실증, 프로덕션 diff 감사, 실제 태그 push 로 CI-01/CI-02 종단 확인

**출하 상태**: 앱 동작 무변경. 프로덕션 코드 변경은 `KalmanFilter` 생성자에 시간 시임 기본인자 1건을 추가하는 것뿐이고, 기본값이 현행 경로(`System.currentTimeMillis()`)라 배포되는 APK 는 v1.1.70 과 동일하게 동작한다. 현장 검증 항목은 "설치·경보 동작이 이전과 같은가" 뿐이다

### Phase 2: 안전 크리티컬 경로 골든 테스트

**Goal**: 경보 격상·해제 전 경로와 UWB 세션 전환의 현재 동작이 기대값으로 고정되고, 그 안전망이 저속 접근 WARNING 미도달 버그를 실제로 잡아낸다
**Mode:** mvp
**Depends on**: Phase 1
**Requirements**: TEST-01, TEST-02, BUG-02
**Success Criteria** (what must be TRUE):

  1. 고정 RSSI 시퀀스를 재생하면 SAFE→WARNING→DANGER 격상과 역방향 해제의 등급·전이 시점이 기대값과 일치하고, 판정 상수를 건드리면 테스트가 실패한다 (TEST-01)
  2. UWB 세션 6대 이하 범위에서 Case A/B 전환·좀비 워치독 발화·재연결 경로가 기대값과 일치한다 (TEST-02)
  3. 약한 콜드스타트 RSSI 에서 저속으로 접근하는 시퀀스가 WARNING 등급에 도달하며, 이 시나리오가 골든 테스트에 회귀 케이스로 남는다 (BUG-02)
  4. 사용자가 현장에서 지게차가 천천히 다가올 때 경고가 뜨는 것을 확인한다 (BUG-02)

**Plans:** 4/4 plans executed

Plans:
**Wave 1**

- [x] 02-01-PLAN.md — Robolectric 공급망 차단 체크포인트 + `ServiceController.get()` 무-`onCreate()` 하네스와 `processAlert` 시간 시임(트레이서) + 골든용 `DevSettings` 고정

**Wave 2** *(blocked on Wave 1 completion; 02-02 ∥ 02-03 병렬)*

- [x] 02-02-PLAN.md — 격상 SAFE→WARNING→DANGER · 역방향 해제 캐스케이드 record-then-freeze 골든 + 판정 상수 레드 트라이얼 (TEST-01)
- [x] 02-03-PLAN.md — UWB Case A/B 전환 · 실측 신선도 경계 · 좀비 DANGER 부재 · 6대 이하 다기기 비오염 골든 (TEST-02)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 02-04-PLAN.md — 저속 접근 시퀀스를 버그가 살아 있는 상태로 골든 동결 → 원인 규명·수정·재동결 + CI 필수 테스트 목록 등록 + 현장 확인 (BUG-02)

**출하 상태**: 현장 관측 가능한 수정 1건(저속 접근 WARNING 도달)이 포함된 APK 를 배포한다. 나머지는 테스트 추가이므로 다른 동작은 불변. 현장 검증은 "천천히 접근하는 지게차에 경고가 뜨는가" 단일 항목
**Note**: TEST-02 는 UWB 세션 6대 이하로 의도적으로 한정한다. 6대 초과 플립은 BUG-03 으로 v2 이월 — 수정 없이 그 경로를 기대값으로 고정하면 CI 가 상시 빨간색이 되거나 버그를 스펙으로 승격시키게 된다. BUG-02 를 이 Phase 에 둔 이유는 두 가지 — (a) 현장에 나가 있는 미탐지(missed alert) 이므로 분해를 기다릴 이유가 없고, (b) Phase 3 의 REFACTOR-04 "기대값 불변" 게이트가 버그가 아니라 올바른 동작을 보존하게 된다

### Phase 3: BleService 분해

**Goal**: `BleService` 의 경보 판정·UWB 거리·캘리브레이션이 세 컴포넌트로 분리되고, 분해 전후 동작이 동일함이 테스트로 증명된다
**Mode:** mvp
**Depends on**: Phase 2
**Requirements**: REFACTOR-01, REFACTOR-02, REFACTOR-03, REFACTOR-04
**Success Criteria** (what must be TRUE):

  1. 경보 등급 판정이 `AlertStateMachine` 단독 호출로 검증되며, `BleService` 인스턴스 없이 JVM 테스트에서 구동된다 (REFACTOR-01)
  2. UWB 세션 수명주기와 거리 표본 신선도 판정이 `UwbDistanceManager` 에서 처리된다 (REFACTOR-02)
  3. UWB 캘리브레이션·에코 RSSI 보정·역할쌍 오프셋 조회가 `CalibrationEngine` 을 거친다 (REFACTOR-03)
  4. TEST-01~03 의 기대값이 분해 전후로 한 건도 바뀌지 않는다 (REFACTOR-04)
  5. 사용자가 현장에서 분해 전 버전과 구분되는 경보 동작 차이를 관측하지 못한다 (REFACTOR-04)

**Plans**: TBD
**출하 상태**: 동작 보존이 목표이므로 배포 APK 는 Phase 2 와 동일하게 동작해야 한다. REFACTOR-04 가 이 Phase 의 수용 게이트이며 별도 후속 Phase 가 아니다 — 기대값이 하나라도 바뀌면 분해가 통과하지 않은 것으로 본다. 현장 검증은 "달라진 게 없는가" 확인
**Note**: PERF-01(메인 스레드 이탈)은 REFACTOR-01 과 같이 하면 값싸지만 별개다 — 클래스를 추출하는 것만으로 실행 스레드가 바뀌지 않는다. 스레딩 변경을 이 Phase 에 섞으면 REFACTOR-04 게이트가 흐려지고 현장 회귀의 원인 귀속이 불가능해지므로 Phase 5 로 분리했다

### Phase 4: 기기 상태 단일화

**Goal**: 기기별 추적 상태가 단일 데이터 클래스로 통합되어 좀비 엔트리가 구조적으로 남을 수 없고, 유지보수자가 그것을 실시간으로 확인한다
**Mode:** mvp
**Depends on**: Phase 3
**Requirements**: STATE-01, STATE-02, STATE-03, BUG-01
**Success Criteria** (what must be TRUE):

  1. 기기별 추적 상태가 `DeviceTrackingState` 하나로 조회되며, 판정 경로에 분산 mutable Map 직접 접근이 남아 있지 않다 (STATE-01)
  2. 기기 진입(최초 관측)·갱신·소멸(소실/헬스체크)이 단일 경로를 거치고, 소멸 직후 해당 기기의 잔여 엔트리가 0 이다 (STATE-02)
  3. 유지보수자가 개발자 설정에서 추적 기기 수·상태 엔트리 수·정리 이벤트를 실시간으로 확인한다 (STATE-03)
  4. 기기가 수 시간 드나든 뒤에도 상태 엔트리 수가 추적 기기 수를 따라가고 단조 증가하지 않는다 (BUG-01)
  5. 사용자가 2시간 이상 연속 구동 후에도 앱이 느려지지 않고 경보음이 끊기지 않는 것을 확인한다 (BUG-01)

**진행**: T1(제거 경로 일원화 — `DeviceStateRegistry`)·T2(STATE-03 계기)·T3(테스트·문서) 완료. Success Criteria 2·3·4 충족(단위 테스트 51건 통과, 골든 24건 포함 = 판정 불변). 미충족 = 1(STATE-01 `DeviceTrackingState` 통합 — 착수 여부 사용자 판단 대기)·5(2시간 연속 구동 실기 검증 — 사용자 지시로 보류). 상세 = PROGRESS.md
**Plans**: TBD
**출하 상태**: `DeviceTrackingState` 통합 + `filterPreserveMap` 누수 제거 + 개발자 설정 상태 계기가 포함된 APK 를 배포한다. STATE-03 계기가 이 Phase 의 현장 검증 수단이다 — 2시간 이상 구동 후 엔트리 수를 눈으로 읽어 STATE-02 성립 여부를 확인한다
**Note**: BUG-01(`onDeviceLost` ↔ `healthCheck` 비원자 정리)을 이 Phase 에 둔 것은 STATE-02 의 단일 경로화가 그 버그의 구조적 원인을 제거하기 때문이다. 별도 증상 대응으로 처리하면 v1.1.28 / v1.1.43 / v1.1.50 과 같은 계열의 반복이 된다

### Phase 5: 판정 워커 분리

**Goal**: 판정 연산이 BLE 스캔 콜백(메인 스레드)에서 벗어나 전용 워커에서 실행되어, 기기가 많은 현장에서도 UI 가 끊기지 않는다
**Mode:** mvp
**Depends on**: Phase 4
**Requirements**: PERF-01
**Success Criteria** (what must be TRUE):

  1. `processAlert` 가 스캔 콜백 스레드가 아니라 전용 워커에서 실행된다 (PERF-01)
  2. 기기 20대 이상이 범위에 있을 때 UI 프레임 드랍이 관측되지 않는다 (PERF-01)
  3. 워커 이전 후에도 TEST-01~03 기대값이 그대로 통과한다 (PERF-01 동작 보존)
  4. 사용자가 다수 기기 현장에서 화면 조작과 경보 반응이 지연 없이 이뤄지는 것을 확인한다 (PERF-01)

**Plans**: TBD
**출하 상태**: 스레딩 변경 단독으로 배포한다. 요구사항 1건짜리 Phase 인 것은 의도다 — 동시성 변경을 다른 변경과 섞어 내보내면 현장에서 회귀가 관측됐을 때 원인 귀속이 불가능해진다. 현장 검증은 20대 이상 환경에서의 UI 반응성

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. 테스트 하네스와 CI 회귀 게이트 | 2/2 | In Progress|  |
| 2. 안전 크리티컬 경로 골든 테스트 | 4/4 | In Progress|  |
| 3. BleService 분해 | 0/TBD | Not started | - |
| 4. 기기 상태 단일화 | 0/TBD | Not started | - |
| 5. 판정 워커 분리 | 0/TBD | Not started | - |

## Coverage

v1 요구사항 16건 전부가 정확히 하나의 Phase 에 매핑된다. 고아 요구사항 없음, 중복 없음.

| Phase | Requirements | 건수 |
|-------|--------------|------|
| 1 | TEST-03, TEST-04, CI-01, CI-02 | 4 |
| 2 | TEST-01, TEST-02, BUG-02 | 3 |
| 3 | REFACTOR-01, REFACTOR-02, REFACTOR-03, REFACTOR-04 | 4 |
| 4 | STATE-01, STATE-02, STATE-03, BUG-01 | 4 |
| 5 | PERF-01 | 1 |
| **합계** | | **16 / 16** |

v2 이월: BUG-03 (UWB 6대 초과 플립). Out of Scope 표의 항목은 어느 Phase 에도 진입하지 않는다.

---
*Roadmap created: 2026-08-24*
