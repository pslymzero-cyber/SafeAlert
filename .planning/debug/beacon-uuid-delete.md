---
slug: beacon-uuid-delete
status: resolved
trigger: "비콘 UUID 삭제 버그 — 비콘 관리에서 UUID를 삭제해도 반영되지 않는다."
created: 2026-09-03
updated: 2026-09-03
phase_link: Phase 04 (기기 상태 단일화) — 이 버그가 좀비 엔트리의 실증 사례
---

# Debug: beacon-uuid-delete

## Symptoms

- **Expected**: 비콘 관리에서 UUID 삭제 → 영구 저장 + 목록 제거 + 이후 스캔/알림에서 미사용.
- **Actual**: 삭제해도 서비스가 계속 인식. 저장·목록은 정상인데 런타임이 옛 상태를 유지.
- **Reproduction**: 비콘 관리 진입 → UUID 삭제 → 해당 비콘 경보/표시 잔존.

## Evidence

- `06_utils/BeaconRegistry.kt:37` `getAll()` — 매 호출마다 prefs 재파싱. **인메모리 캐시 자체가 없다.**
- `06_utils/BeaconRegistry.kt:85` `remove()` → `:99 save()` → `putString().apply()`.
  `apply()` 는 in-memory prefs 맵을 동기 갱신하고 디스크 쓰기만 비동기다. 커밋 누락 아님.
- `05_ui/BeaconManagerActivity.kt:504` `BeaconRegistry.remove(p.uuid); refreshProfiles()` — UI 즉시 재조회.
- `02_ble/BleScanner.kt:303` `buildFilters()` — 유일 호출부가 `:377` `startScanInternal()` 뿐.
  HW ScanFilter 는 **스캔 시작 시점 스냅샷**이며 레지스트리 변경 시 재빌드되지 않는다.
- 레지스트리 변경 통지 수단이 코드베이스 전역에 **존재하지 않았다**
  (브로드캐스트는 `ACTION_STATE_CHANGED`·`ACTION_DOWNLOAD_COMPLETE` 둘뿐).
- `03_service/AlertStateMachine.kt` — 기기별 `remove(deviceId)` 약 100곳이 **전부 상태전이 분기 내부**.
  전이는 신규 표본으로만 계산되므로 UUID 를 지워 표본이 끊기면 정리 코드가 영원히 실행되지 않는다.
- 표본 비의존 유일 정리 경로 `02_ble/BleScanner.kt:263-279` TTL 스윕은
  `// [v1.1.47] UWB 실측이 계속 흐르는 기기는 소실 유예` 로 UWB 세션 보유 기기를 무기한 면제한다.

## Eliminated

- **SharedPreferences 커밋 누락** — 반증. `apply()` in-memory 동기 반영.
- **인메모리 캐시 무효화 실패** — 해당 없음. 캐시 미존재(`getAll` 매번 재파싱).
- **UI 리스트 미갱신** — 반증. `refreshProfiles()` 즉시 호출.
- **존 비콘 삭제 시 `myZoneInside` 영구 true** — 반증. `BleService.kt:1163-1188` 이 3초 유예 후 자가 복구.
- **Firebase 공유 병합이 삭제분을 되살림** — 반증. `mergeProfiles` 호출부는 사용자 2단 확인 다이얼로그뿐.

## Resolution

- **root_cause**: 사용자가 지목한 3층(저장·캐시·UI)은 전부 정상이었다.
  끊긴 곳은 **4번째 층 — 소비자 측 무효화**. 레지스트리 변경이 소비자에게 통보되지 않아
  (a) HW ScanFilter 가 옛 스냅샷을 유지하고,
  (b) 삭제된 기기의 표본이 끊기면서 상태전이 기반 정리가 영영 돌지 않아 상태맵에 좀비 엔트리가 잔류했다.
  이는 Phase 04 가 겨냥한 "분산 상태맵 + 표본 의존 정리" 구조의 직접 실증이다.

- **fix** (최소 범위, 신규 추상화 없음):
  1. `06_utils/BeaconRegistry.kt` — `var onChanged: (() -> Unit)?` 추가, `save()` 말미에서 호출.
     add·remove·mergeProfiles 가 전부 `save()` 를 경유하므로 여기가 유일 통지 지점이다.
  2. `02_ble/BleScanner.kt` `startScanning()` — 기존 public 함수 재사용으로 배선:
     `BeaconRegistry.onChanged = { handler.post { forceLoseAll(); restartScan() } }`
     `forceLoseAll()` 이 정상 `onDeviceLost` 경로로 상태맵·UWB 세션을 일괄 정리해 UWB 유예를 우회하고,
     `restartScan()` 이 `buildFilters()` 를 재실행해 HW 필터를 갱신한다.
  3. `02_ble/BleScanner.kt` `stopScanning()` — `BeaconRegistry.onChanged = null` (누수 방지).

- **verification**: `./gradlew.bat :app:compileDebugKotlin` 통과(경고는 SDK XML 버전 안내뿐).
  Robolectric 시뮬레이션 통과(`BeaconRegistryChangeSimulationTest`, 2/2, 실기 불요):
  - s1 UUID 삭제 -> `lost=[SAFEALERT_EPJ_0002, SAFEALERT_FORK_0001, SAFEALERT_WALKER_BEA_AAAAAAAA]`,
    detected 3->0, `containsUuid=false`, 스캔에러 0 (좀비 엔트리 잔류 없음).
    TTL 스윕은 벽시계라 가상 루퍼로 발화하지 않으므로 소실 통지는 `forceLoseAll()` 뿐이다.
  - s2 UUID 등록(대시 없는 32-hex) -> `normUuid=11223344-5566-7788-9900-AABBCCDDEEFF`,
    필터 1->2 (iBeacon 0->1), `manufacturerId=0x004C` / `data=[0x02,0x15]+UUID16` 일치, activeScans 1건 유지.
  로그: `app/build/sim_registry_s1_delete.log` · `app/build/sim_registry_s2_add.log`.

- **files_changed**:
  - `app/src/main/java/com/wf11/safealert/06_utils/BeaconRegistry.kt`
  - `app/src/main/java/com/wf11/safealert/02_ble/BleScanner.kt`
  - 버전 미변경: `versionCode 128` / `versionName 1.1.72` 유지 (버그 수정).

## Phase 04 Scope Evidence

`grep -nE "(mutableMapOf|mutableSetOf|HashMap|ArrayDeque|LinkedHashMap)"` 결과 13파일 66선언.
그중 Phase 04 핵심 대상은 AlertStateMachine 31 + BleService 9 = **40** — 로드맵 "40여 개"와 일치.
필드 한정 시 60(함수 지역변수 6 제외), 코드베이스 전체는 66.
`forceLoseAll()` 문서주석의 "27종 상태맵" 표현이 규모를 독립 교차검증한다.
