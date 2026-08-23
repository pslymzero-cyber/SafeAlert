# SafeAlert

## What This Is

블루투스 신호를 앱이 설치된 기기끼리 주고받아, 신호 세기로 추정한 거리가 위험 구간에 들어오면 알림을 주는 Android 근접 경보 앱이다. 물류 현장에서 지게차·EPJ(전동 파렛트 잭)·보행자가 서로를 감지하는 것이 실사용 맥락이며, 역할 조합에 따라 경고·위험 반경이 다르게 적용된다.

v1.0.1부터 v1.1.70까지 3개월간 70회 이상 릴리스하며 실제로 동작해 왔다. 이번 작업은 새 기능을 붙이는 것이 아니라, **판정이 흔들리는 구조적 원인**을 걷어내는 것이다.

## Core Value

**BLE RSSI 근접 판정이 같은 상황에서 같은 결과를 낸다.** 경보가 떠야 할 때 뜨고, 꺼져야 할 때 꺼지며, 한 번 고친 증상이 다시 돌아오지 않는다. UWB·iBeacon·Firebase가 전부 실패해도 이것만은 동작해야 한다.

## Requirements

### Validated

기존 코드가 이미 수행하는 능력. 잠금 상태이며 이번 작업에서 제거·축소 대상이 아니다.

- ✓ BLE 스캔·광고 양방향 통신 (`BleScanner` / `BleAdvertiser` / `BleScanCallback`) — existing
- ✓ 1바이트 비트팩 프로토콜 (2-2-2-2: CAT / STATE / TURN / RISK, `BleConstants.encodePayload`·`decodePayload`) — existing
- ✓ 3단 RSSI 필터 캐스케이드 (`RssiPreFilter` → `MedianFilter` 5샘플 창 → `KalmanFilter` ~120ms 주기) — existing
- ✓ SAFE / WARNING / DANGER 3단 경보 상태머신 (`BleService` 내부) — existing
- ✓ 역할 기반 판정 — 지게차 / 보행자 / EPJ 역할쌍별 차등 반경·오프셋 (`pairKey` 세그먼트 보정) — existing
- ✓ UWB 정밀 거리 측정 (`UwbRanger`, `androidx.core.uwb` alpha09, 멀티캐스트 최대 6대, 0x9ABC OOB 광고) — existing
- ✓ UWB / RSSI 배타 판정 — Case A(UWB 링크 실가동 = UWB 권위) / Case B(RSSI 폴백) — existing
- ✓ UWB 캘리브레이션 저장·학습 (`UwbCalibrator`, 사업장별 기준선) — existing
- ✓ 에코 RSSI 캘리브레이션 (v1.1.54+, echo diff 통계) — existing
- ✓ IMU 섀도우 융합 — 정지·전진 모션 상태 판별 (`ImuFusion`, v1.1.40) — existing
- ✓ Firebase Realtime DB 기기 프로필·설정 동기화 및 비콘 세트 기기 간 공유 (`FirebaseManager`, 최대 200) — existing
- ✓ iBeacon 스캔·프로필 저장 (`BeaconRegistry`, SharedPreferences) — existing
- ✓ 경보 출력 3종 — 사운드(`AlertSoundPlayer`) / 진동(`VibrationHelper`) / 시스템 오버레이(`OverlayManager`) — existing
- ✓ 포그라운드 서비스 + 지속 알림, 화면 꺼짐 상태 배칭 0ms·WakeLock 선획득 — existing
- ✓ UI 상태 전파 — 스냅샷 브로드캐스트 + 800ms 폴링 폴백 — existing
- ✓ 개발자 설정 화면 (`DevSettings`, 7탭 게이트) 및 BLE 설정 슬라이더 즉시 반영 — existing
- ✓ Firebase 권위 자동 업데이트 (`UpdateManager`) + GitHub Actions CI 빌드 — existing

### Active

이번 작업의 범위. 출하 전까지는 가설이다.

- [ ] **경보 타이밍·거리 판정의 재현성 확보** — 같은 접근 상황에서 같은 시점에 같은 등급이 나온다. 현재 세 증상이 동시에 관측됨: (1) 알림 타이밍이 어긋남 (2) 거리가 실제와 안 맞음 (3) 고칠수록 같은 증상이 재발
- [ ] **`BleService.kt` 분해** — 3,899줄 / 80+ 함수 단일 클래스가 스캔·경보 상태·UWB·오디오·오버레이·Firebase·캘리브레이션·존·생명주기를 전부 담당. 분리안: `AlertStateMachine` / `UwbDistanceManager` / `CalibrationEngine` (기술부채 1순위)
- [ ] **분산 상태 통합** — 기기 상태가 40개 이상 mutable Map(`BleService.kt:384-655`)에 흩어져 있고 정리 시점이 제각각 → 좀비 상태가 경보 오작동으로 직결. 통합안: `DeviceTrackingState` 데이터 클래스 (기술부채 2순위)
- [ ] **리팩터링 안전망 테스트** — 유닛·통합 테스트 0건 상태에서 안전 크리티컬 로직을 분해하지 않는다. 분해 **선행 조건**으로 골든 테스트 고정: ① 경보 격상·해제 전체 경로 ② UWB 세션 상태머신 (사이렌 플랩 진원지) (기술부채 3순위)

### Out of Scope

경계와 그 이유. 이유가 유효한 동안 재진입하지 않는다.

- **판정 반경 값 변경** (지게차 경고 15m / 위험 8m, 그 외 5m / 3m) — 사용자가 현행 값이 맞다고 확인. "값이 아니라 값대로 동작하지 않는 게 문제"
- **UWB 제거·비활성화** — 사용자 명시: "모두 유지. 보조 수단으로서 기능". UWB는 정밀 측위 보조로 존치
- **iBeacon 스캔 제거** — 동일. 보조 수단으로 존치
- **Firebase 프로필 공유 제거** — 동일. 보조 수단으로 존치
- **UWB를 主 판정으로 승격** — BLE RSSI가 主 판정 권위. 역할 위계를 뒤집는 변경은 이번 범위 밖
- **신규 기능 추가** — 사용자가 핵심 범위를 "이게 전부다"로 확정. 이번 작업은 재발 고리 차단에 한정

## Context

**현재 상태**
- 버전 `1.1.70` / versionCode 126 (`app/build.gradle`). 3개월간 70회 이상 릴리스
- 코드베이스 맵: `.planning/codebase/` 7종 (커밋 `54ee9d1`)
- 기술부채 감사 31건 원문: `.planning/codebase/CONCERNS.md` — 기술부채 3 · 버그 3 · 보안 4 · 성능 3 · 취약 영역 4 · 확장 한계 3 · 의존성 3 · 결여 기능 3 · 테스트 공백 5

**아키텍처 요약**
- 레이어드: UI(`05_ui/`) → Service(`03_service/`) → BLE·Utils(`02_ble/`, `06_utils/`) → Model. 순환 참조 없음
- `BleService`가 경보 상태의 단일 진실 원천. `@Volatile` 정적 필드로 UI에 노출
- 판정 주기 ~120ms (Kalman 사이클), UI 폴링 800ms

**현장 조건**
- 3m 높이 철제 렉 사이 단독 작업 구간 존재
- 생수 파렛트가 2.4GHz 흡수벽으로 작용 → RSSI 급감·NLOS 잔차 발생
- 렉 이탈 감지가 안전상 필수 — 속도 양방향 판정과 사업장별 보정이 이 조건에서 나옴

**알려진 버그**
- UWB 세션 6대 초과 시 플립 → 사이렌 1~2초 끊김 (우회: `uwbExclusiveJudgeEnabled` off)
- `onDeviceLost:1127` ↔ `healthCheck:3143` 비원자 정리 → `filterPreserveMap` 누수 (2시간 이상 구동 시 힙 증가)
- `injectWarmup:1454` 프리셋 최소값 포화 → 저속 접근 시 WARNING 미도달

**재발 고리의 구조적 근거**
- 좀비 상태(정리 시점이 제각각인 40여 개 맵)가 "거리가 안 맞음"을 생산
- 격리 테스트 불가능한 3,899줄이 "고칠수록 재발"을 생산
- v1.1.28 / v1.1.43 / v1.1.50이 전부 같은 좀비 상태 계열 수정 — 증상만 잡고 원인은 남은 이력

**회귀 검출 방식**
- 현재 회귀를 CI가 아니라 사용자가 실기에서 발견. 자동 검증 수단 없음
- 배포 권위는 Firebase. GitHub Actions가 빌드하고 Firebase가 갱신 팝업을 결정

## Constraints

- **Tech stack**: Kotlin / Android — `minSdk 26`, `targetSdk 34`, `compileSdk 34`, JDK 17, viewBinding — 기존 코드베이스 전제
- **Dependencies**: `androidx.core.uwb:1.0.0-alpha09` 프리릴리스에 경보 로직의 30~40%가 의존 — API 파괴 변경 리스크 상존
- **Compatibility**: 1바이트 비트팩 BLE 프로토콜 — 현장에 배포된 구버전 기기와 통신해야 하므로 페이로드 레이아웃 변경 불가
- **Performance**: `processAlert`(약 1,000줄, `BleService.kt:1406-2554`)가 스캔 콜백 = 메인 스레드에서 실행 — 20대 이상에서 프레임 드랍, 50대 초과 시 GC 200ms+
- **Security**: `minifyEnabled false` (`app/build.gradle:36`) — 릴리스 빌드 난독화 없음. Firebase 경보 로그 평문 저장
- **Platform**: 포그라운드 서비스 + 지속 알림 필수 (Android 정책). 화면 꺼짐 상태에서도 스캔 유지 필요
- **Timeline**: 실기 검증이 유일한 회귀 확인 수단 — 검증 사이클이 사용자 현장 가용 시간에 묶임
- **Process**: 기능 추가 시 `versionName` patch +0.0.1 · 커밋 · 태그 · 푸시. 버그·단순 수정은 버전 유지

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| BLE RSSI를 主 판정 권위로 유지, UWB·iBeacon·Firebase는 보조 수단으로 전부 존치 | 사용자 명시: "모두 유지. 보조 수단으로서 기능". 보조 수단이 전부 실패해도 RSSI 단독으로 경보가 성립해야 함 | — Pending |
| 판정 반경 현행 확정 (지게차 15m/8m, 그 외 5m/3m) | 값 자체는 현장에서 맞다고 확인됨. 문제는 값이 아니라 값대로 동작하지 않는 것 | — Pending |
| 증상 대응이 아니라 기술부채 1·2순위 구조 정리를 본체 작업으로 채택 | 사용자 선택: "기술 부채 1순위와 2순위 정리". 같은 계열 수정이 v1.1.28·43·50에서 반복된 이력이 증상 대응의 한계를 보여줌 | — Pending |
| 테스트(3순위)를 별도 과제가 아니라 리팩터링 **선행 안전망**으로 편입 | 안전 크리티컬 3,899줄을 회귀 검증 수단 없이 분해하면 지금의 "실기에서 회귀 발견" 방식이 리팩터링 중에도 그대로 남음 | — Pending |
| 신규 기능 추가는 이번 범위에서 제외 | 사용자가 핵심 범위를 "이게 전부다"로 확정 | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-08-23 after initialization*
