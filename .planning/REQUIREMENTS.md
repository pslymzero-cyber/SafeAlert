# Requirements: SafeAlert

**Defined:** 2026-08-24
**Core Value:** BLE RSSI 근접 판정이 같은 상황에서 같은 결과를 낸다. 경보가 떠야 할 때 뜨고, 꺼져야 할 때 꺼지며, 한 번 고친 증상이 다시 돌아오지 않는다.

## v1 Requirements

Requirements for initial release. Each maps to roadmap phases.

### 테스트 안전망 (TEST)

리팩터링 **선행 조건**. 안전 크리티컬 로직을 분해하기 전에 현재 동작을 기대값으로 고정한다.

- [ ] **TEST-01**: 유지보수자는 고정 RSSI 시퀀스를 재생해 SAFE→WARNING→DANGER 격상과 역방향 해제의 등급·전이 시점을 기대값과 비교할 수 있다 (경보 전체 경로 골든 테스트)
- [ ] **TEST-02**: 유지보수자는 UWB 세션 **6대 이하** 범위에서 Case A/B 전환·좀비 워치독 발화·재연결 경로를 기대값과 비교할 수 있다 (6대 초과 플립 경로는 v1 대상 외 — BUG-03 참조)
- [x] **TEST-03**: 유지보수자는 MedianFilter(3샘플) → RssiPreFilter → KalmanFilter 3단 캐스케이드가 동일 입력 시퀀스에 동일 출력을 내는지 확인할 수 있다
- [x] **TEST-04**: 위 테스트들은 Android 프레임워크·실기기 없이 JVM 유닛 테스트로 실행된다 (분해 대상 로직이 순수 함수로 격리 가능해야 성립)

### CI 회귀 게이트 (CI)

현재 회귀를 CI가 아니라 사용자가 실기에서 발견한다. 측정 수단 없이는 재현성을 주장할 수 없다.

- [x] **CI-01**: 골든 테스트가 GitHub Actions 빌드에서 자동 실행되고, 실패 시 빌드가 차단된다
- [x] **CI-02**: 유지보수자는 CI 실행 결과에서 어떤 테스트가 왜 깨졌는지 실기 없이 판별할 수 있다 (테스트 리포트 아티팩트 보존)

### 구조 분해 (REFACTOR)

`BleService.kt` 3,899줄 / 80+ 함수 단일 클래스 해체. 기술부채 1순위.

- [ ] **REFACTOR-01**: 경보 등급 판정 로직이 `AlertStateMachine` 으로 분리되어 `BleService` 없이 단독 호출·검증된다
- [ ] **REFACTOR-02**: UWB 세션 수명주기와 거리 표본 신선도 판정이 `UwbDistanceManager` 로 분리된다
- [ ] **REFACTOR-03**: UWB 캘리브레이션·에코 RSSI 보정·역할쌍 오프셋이 `CalibrationEngine` 으로 분리된다
- [ ] **REFACTOR-04**: 분해 전후로 TEST-01~03 의 기대값이 변하지 않는다 (동작 보존 증명)

### 상태 통합 (STATE)

기기 상태가 40개 이상 mutable Map(`BleService.kt:384-655`)에 흩어져 정리 시점이 제각각. 좀비 상태가 경보 오작동으로 직결. 기술부채 2순위.

- [ ] **STATE-01**: 기기별 추적 상태가 `DeviceTrackingState` 단일 데이터 클래스로 통합되어 분산 Map 접근이 제거된다
- [ ] **STATE-02**: 기기 상태의 진입(최초 관측)·갱신·소멸(소실/헬스체크) 이 단일 경로를 거치며, 부분 정리로 잔여 엔트리가 남지 않는다
- [ ] **STATE-03**: 유지보수자는 개발자 설정에서 추적 기기 수·상태 엔트리 수·정리 이벤트를 실시간으로 확인할 수 있다 (좀비 상태 조기 발견 수단)

### 성능 (PERF)

- [ ] **PERF-01**: `processAlert` 가 BLE 스캔 콜백(메인 스레드)에서 벗어나 전용 워커에서 실행되며, 기기 20대 이상에서 UI 프레임 드랍이 발생하지 않는다

### 알려진 버그 (BUG)

- [ ] **BUG-01**: `onDeviceLost` ↔ `healthCheck` 비원자 정리로 인한 `filterPreserveMap` 누수가 제거되어, 2시간 이상 연속 구동에서 힙이 단조 증가하지 않는다
- [ ] **BUG-02**: `injectWarmup` 프리셋 최소값 포화가 해소되어, 저속 접근 시에도 WARNING 등급에 도달한다

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### 알려진 버그 (BUG)

- **BUG-03**: UWB 세션 6대 초과 시 플립으로 인한 사이렌 1~2초 끊김 해소
  - v1 제외 근거: 사용자 스코핑에서 미선택. 현재 우회책(`uwbExclusiveJudgeEnabled` off)이 존재하고, UWB 는 보조 수단이라 主 판정(RSSI)에 영향이 없음
  - TEST-02 를 6대 이하로 한정한 이유가 이것 — 수정 없이 6대 초과 경로를 기대값으로 고정하면 CI 가 끝내 빨간색이 되거나, 버그를 스펙으로 승격시키게 됨

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| 판정 반경 값 변경 (지게차 15m/8m, 그 외 5m/3m) | 사용자가 현행 값이 맞다고 확인. "값이 아니라 값대로 동작하지 않는 게 문제" |
| UWB 제거·비활성화 | 사용자 명시 "모두 유지. 보조 수단으로서 기능". 정밀 측위 보조로 존치 |
| iBeacon 스캔 제거 | 동일. 보조 수단으로 존치 |
| Firebase 프로필 공유 제거 | 동일. 보조 수단으로 존치 |
| UWB 를 主 판정으로 승격 | BLE RSSI 가 主 판정 권위. 역할 위계를 뒤집는 변경은 이번 범위 밖 |
| 신규 기능 추가 | 사용자가 핵심 범위를 "이게 전부다"로 확정. 이번 작업은 재발 고리 차단에 한정 |
| 1바이트 비트팩 페이로드 레이아웃 변경 | 현장 배포된 구버전 기기와 통신해야 함 (Constraints) |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| TEST-01 | Phase 2 | Pending |
| TEST-02 | Phase 2 | Pending |
| TEST-03 | Phase 1 | Complete |
| TEST-04 | Phase 1 | Complete |
| CI-01 | Phase 1 | Complete |
| CI-02 | Phase 1 | Complete |
| REFACTOR-01 | Phase 3 | Pending |
| REFACTOR-02 | Phase 3 | Pending |
| REFACTOR-03 | Phase 3 | Pending |
| REFACTOR-04 | Phase 3 | Pending |
| STATE-01 | Phase 4 | Pending |
| STATE-02 | Phase 4 | Pending |
| STATE-03 | Phase 4 | Pending |
| PERF-01 | Phase 5 | Pending |
| BUG-01 | Phase 4 | Pending |
| BUG-02 | Phase 2 | Pending |

**Coverage:**

- v1 requirements: 16 total
- Mapped to phases: 16
- Unmapped: 0

Phase 별 묶음:

| Phase | 이름 | Requirements |
|-------|------|--------------|
| 1 | 테스트 하네스와 CI 회귀 게이트 | TEST-03, TEST-04, CI-01, CI-02 |
| 2 | 안전 크리티컬 경로 골든 테스트 | TEST-01, TEST-02, BUG-02 |
| 3 | BleService 분해 | REFACTOR-01, REFACTOR-02, REFACTOR-03, REFACTOR-04 |
| 4 | 기기 상태 단일화 | STATE-01, STATE-02, STATE-03, BUG-01 |
| 5 | 판정 워커 분리 | PERF-01 |

---
*Requirements defined: 2026-08-24*
*Last updated: 2026-08-24 after Phase 1 context discussion (TEST-03 캐스케이드 순서·윈도우 크기 오기 정정 — D-21)*
