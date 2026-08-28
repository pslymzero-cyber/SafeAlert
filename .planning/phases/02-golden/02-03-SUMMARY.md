---
phase: 02-golden
plan: 03
subsystem: testing
tags: [android, kotlin, robolectric, uwb, golden-test, safety-critical]

# Dependency graph
requires:
  - phase: 02-golden
    provides: "02-01 의 processAlert 리플렉션 하네스(BleServiceTestHarness) 와 골든 DevSettings 프로파일"
provides:
  - "TEST-02 세 축(Case A/B 전환, 신선도 게이트=좀비 차단, 재연결 경계)을 고정한 UwbSessionGoldenTest.kt"
  - "BLE 타임아웃 UWB 정리 블록(peerUwbSeenMap/uwbSampleAtMsMap/uwbSafeStreakMap + onDeviceLost)의 재현 헬퍼(replicateBleTimeoutBoundary)"
affects: [02-04]

# Actuals (#2632)
actuals:
  tokens: 7511
  tasks: 3
  commits: 4

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "리플렉션 전용 프로덕션 접근 — app/src/main 은 0줄, private 필드/메서드는 ReflectionHelpers 로만 판독/구동(02-01/02-02 규율 계승)"
    - "record-then-freeze 프레임 시계열 직렬화 — 고정폭 1행/프레임 문자열(renderUwbFrame/renderCaseLine)로 눈으로 열 위치를 짚을 수 있게 함"
    - "프로덕션 상수 손 동기화 + 주석 — FRESH_WINDOW_MS/MAX_SESSION_DEVICES 를 리플렉션으로 따라가지 않고 값을 복제하며 어느 프로덕션 상수와 동기화해야 하는지 명시"

key-files:
  created:
    - app/src/test/java/com/wf11/safealert/ble/UwbSessionGoldenTest.kt
  modified: []

key-decisions:
  - "onDeviceLost 분기 확정: candidates 가 비어 있고 activeControllerId==null·role==Role.NONE 이라 '그 외' 분기(dropServedLocked+reconcileLocked)를 탄다. reconcileLocked 는 Robolectric 환경에서 uwbManager==null 이라 즉시 반환해 scheduleRestartLocked/coroutine 진입 자체가 없다 — 재연결 재시도 스케줄링(D-4D, out of scope)에 닿지 않고 동기 반환이 보장된다"
  - "BLE 타임아웃 UWB 정리 목록은 BleService.kt:1171-1174 직독으로 확정: uwbRanger?.onDeviceLost(id) + peerUwbSeenMap/uwbSampleAtMsMap/uwbSafeStreakMap 3개 remove. deviceCategoryMap/deviceStateMap 은 UWB 전용이 아니라 재현 대상에서 제외(기억·추정 금지 지시 준수)"
  - "3기기 인터리브 시나리오의 기기ID 를 UWBTEST01/02/03 순으로 채택 — 알파벳 정렬과 주입 순서가 자연히 일치해 맵 순회 순서에 기대지 않고도 결정론적 행 순서를 보장"
  - "상한 초과(BUG-03) 경로는 golden 으로 얼리지 않고 require() 거부만 증명 — 버그를 스펙으로 승격하지 않는다는 계획 제약 준수"

requirements-completed: [TEST-02]

coverage:
  - id: D1
    description: "Case A/B 전환 골든 — 신선/낡은/결손 표본에 따라 UWB 배타 판정과 RSSI 폴백이 정확히 전환됨을 동결"
    requirement: "TEST-02"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/wf11/safealert/ble/UwbSessionGoldenTest.kt#behavior2_nullRanger_fallsBackToCaseB,behavior3and4_freshSample_triggersCaseAEarlyReturnInProcessAlert,behavior7_missingSampleTimestamp_fallsBackToCaseB,behavior8_missingDistanceEntry_fallsBackToCaseBEvenWithFreshTimestamp,behavior9_staleSample_rssiPathDecidesLevel,behavior12_replicateBleTimeoutBoundary_demotesToCaseB,behavior13_reinjectFreshSample_returnsToCaseAOnSameFrame"
        status: pass
    human_judgment: false
  - id: D2
    description: "신선도 게이트=좀비 DANGER 차단 골든 — 신선 창 경계 3점 동결 + 낡은 근접 실측이 위험 등급을 만들지 않음을 6프레임 시계열로 증명(T-02-13)"
    requirement: "TEST-02"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/wf11/safealert/ble/UwbSessionGoldenTest.kt#behavior6_freshnessBoundary_threePoints,behavior10_staleNearDangerDistance_neverProducesZombieDanger"
        status: pass
    human_judgment: false
  - id: D3
    description: "재연결 경계 계약 + 다기기 비오염 골든 — BLE 타임아웃 UWB 정리 재현 후 강등·같은 프레임 복귀, 대상 외 기기 무영향, 3기기 인터리브 시계열, 세션 상한 이하 독립 판정 + 상한 초과 런타임 거부"
    requirement: "TEST-02"
    verification:
      - kind: unit
        ref: "app/src/test/java/com/wf11/safealert/ble/UwbSessionGoldenTest.kt#behavior14_replicateBoundary_doesNotAffectOtherDevice,behavior15_threeDeviceInterleavedTimeline,behavior16_upToSessionCap_independentJudgment,behavior16b_overSessionCap_rejectedAtRuntime"
        status: pass
    human_judgment: false

duration: 29min
completed: 2026-08-28
status: complete
---

# Phase 02 Plan 03: UWB 세션 판정 골든 테스트(신선도/재연결/다기기) Summary

**UwbSessionGoldenTest.kt 16개 테스트로 Case A/B 전환·신선도 게이트=좀비 DANGER 차단·재연결 경계+다기기 비오염을 동결, app/src/main 0줄**

## Performance

- **Duration:** 29 min (첫 커밋 f9a6417 12:41:34 → 마지막 커밋 9b6be01 13:10:32, KST)
- **Started:** 2026-08-28T12:41:34+09:00
- **Completed:** 2026-08-28T13:10:32+09:00
- **Tasks:** 3
- **Files modified:** 1

## Accomplishments
- Task 1(tracer): UwbRanger 주입 + Case A 조기분기 종단 골든(생성 무해성, null ranger 폴백, 신선표본 RSSI 미개입 확증, judgeUwbOnly 즉시승격+확증격하)
- Task 2: 신선 창 경계 3점(창-1/창/창+1), 표본시각 결손·거리엔트리 결손 2종 Case B 폴백, 낡은 표본의 RSSI 경로 위임, **낡은 근접 실측이 좀비 DANGER 를 만들지 않음(위협모델 T-02-13, 계획의 최중요 항목)**, 확증격하 카운터가 이탈 운동학 우회와 독립임을 동결
- Task 3: BLE 타임아웃 UWB 정리 블록(BleService.kt:1171-1174)을 재현하는 `replicateBleTimeoutBoundary` 헬퍼로 재연결 경계 계약(강등→같은 프레임 복귀, 대상 외 기기 무영향) 동결, 3기기 인터리브 시계열, 세션 상한 이하 독립 판정 + 상한 초과 런타임 거부(BUG-03 v2 이월)
- 16개 테스트 전부 3회 반복 실행(각기 새 JUnit XML 타임스탬프) 무플레이키 확인, app/src/main·app/src/test/.../ble/support diff 항상 0

## Task Commits

Each task was committed atomically:

1. **Task 1: UWB 주입 + Case A 조기분기 종단 골든(tracer)** - `f9a6417` (feat), 헤더 4요소 보완 - `f323d9b` (docs)
2. **Task 2: 신선도 게이트 경계 + 좀비 DANGER 차단 골든** - `ed42918` (test)
3. **Task 3: 재연결 경계 계약 + 다기기 비오염 골든** - `9b6be01` (test)

**Plan metadata:** (다음 커밋에서 기록)

_Note: Task 1 은 tracer 로 커밋 후 즉시 체크포인트 검증을 거쳤고, 오케스트레이터 승인 후 Task 2/3 로 이어짐(같은 파일에 대한 순수 추가라 test 타입 커밋만 발생, feat/refactor 단계 분리 없음)._

## Files Created/Modified
- `app/src/test/java/com/wf11/safealert/ble/UwbSessionGoldenTest.kt` - Case A/B 판정 골든 16개 테스트, 헬퍼(newRanger/injectRanger/judgeMode/callJudgeUwbOnly/replicateBleTimeoutBoundary 등), record-then-freeze 프레임 시계열 3종

## TEST-02 세 축 ↔ 테스트 함수 매핑

(02-04 가 CI 필수 클래스 목록을 갱신할 때 이 표를 근거로 쓴다.)

| TEST-02 축 | 테스트 함수 |
|---|---|
| Case A/B 전환 | `behavior2_nullRanger_fallsBackToCaseB`, `behavior3and4_freshSample_triggersCaseAEarlyReturnInProcessAlert`, `behavior7_missingSampleTimestamp_fallsBackToCaseB`, `behavior8_missingDistanceEntry_fallsBackToCaseBEvenWithFreshTimestamp`, `behavior9_staleSample_rssiPathDecidesLevel`, `behavior12_replicateBleTimeoutBoundary_demotesToCaseB`, `behavior13_reinjectFreshSample_returnsToCaseAOnSameFrame` |
| 신선도 게이트 = 좀비 차단 | `behavior6_freshnessBoundary_threePoints`, `behavior9_staleSample_rssiPathDecidesLevel`, `behavior10_staleNearDangerDistance_neverProducesZombieDanger` |
| 재연결 경계(+다기기 비오염) | `behavior12_replicateBleTimeoutBoundary_demotesToCaseB`, `behavior13_reinjectFreshSample_returnsToCaseAOnSameFrame`, `behavior14_replicateBoundary_doesNotAffectOtherDevice`, `behavior15_threeDeviceInterleavedTimeline`, `behavior16_upToSessionCap_independentJudgment`, `behavior16b_overSessionCap_rejectedAtRuntime` |

## onDeviceLost 분기 확정 (Task 3 SUMMARY 기록 의무)

`replicateBleTimeoutBoundary` 가 부르는 `UwbRanger.onDeviceLost`(UwbRanger.kt:226-245)는 이 테스트 스위트 전체에서 항상 **"그 외" 분기**(dropServedLocked+reconcileLocked, 245행)를 탄다:
- `candidates` 가 항상 비어 있어(테스트가 `onPeerUwbAddressReceived` 를 호출하지 않음) `activeControllerId` 는 null 로 남고, 첫 조건(`deviceId == activeControllerId`)이 거짓.
- `role` 은 생성 시 `Role.NONE`(142행)에서 한 번도 바뀌지 않아(승격 경로는 `initSession()` 이후에만 진입) 두 번째 조건(`role == Role.CONTROLLER && ...`)도 거짓.
- 결과적으로 `dropServedLocked(id)`(거리/운동학/서빙맵 정리) + `reconcileLocked()` 를 실행한다. `reconcileLocked()`(395행)는 Robolectric 환경에서 `uwbManager == null` 이라 396행에서 즉시 반환 — `scheduleRestartLocked`/`scope.launch` 코루틴 경로(D-4D, 명시적 범위 밖)에 절대 진입하지 않는다. 예외·행 없이 동기 반환이 코드 경로 상 보장됨을 직독으로 확인했다.

## Decisions Made
- BLE 타임아웃 UWB 정리 목록(peerUwbSeenMap/uwbSampleAtMsMap/uwbSafeStreakMap + onDeviceLost)을 BleService.kt 직독으로 확정 후 재현 — 기억·추정 배제
- 3기기 인터리브 테스트의 기기ID 는 접두사+일련번호(UWBTEST01/02/03)로 알파벳 정렬=주입 순서를 일치시켜 맵 순회 순서 의존을 원천 차단
- MAX_SESSION_DEVICES 는 리플렉션 대신 로컬 상수로 선언, UwbRanger.MULTICAST_MAX(06_utils/UwbRanger.kt:77, =6)와 손 동기화한다는 주석을 남김(FRESH_WINDOW_MS 와 동일 규율)
- 상한 초과 기기 수는 golden 시나리오로 얼리지 않고 `require()` 거부 자체만 증명 — BUG-03 을 스펙으로 승격시키지 않음

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - 문서 보완] Task 1 tracer 커밋의 골든 헤더 4요소 누락 보완**
- **Found during:** Task 1 tracer 체크포인트 검증 직전
- **Issue:** Task 1 의 `<action>` 이 요구한 골든 헤더 4요소(versionName/versionCode/commit SHA/기록 일시 + 채택 파라미터)가 최초 tracer 커밋(f9a6417)에는 없었다
- **Fix:** 별도 docs 커밋으로 헤더 KDoc 에 4요소와 채택 파라미터(T0_MS/FRESH_OFFSET_MS/FRAME_DT_MS/DEVICE_ID 접두사/역할쌍)를 추가, AlertCascadeGoldenTest.kt(02-02) 헤더 컨벤션과 형식 일치
- **Files modified:** `app/src/test/java/com/wf11/safealert/ble/UwbSessionGoldenTest.kt`
- **Verification:** 오케스트레이터가 tracer 체크포인트 재검증 시 헤더 4요소 존재를 확인하고 승인
- **Committed in:** `f323d9b`

---

**Total deviations:** 1 auto-fixed (Rule 1, 문서 보완)
**Impact on plan:** 코드 동작 변경 없음(문서 전용 추가). Task 2/3 는 편차 없이 계획대로 실행됨(모든 골든 시계열이 첫 실행에서 손계산/설계 예상과 일치).

## Issues Encountered
None - 3회 반복 실행 모두 결정론적 통과(플레이키 없음), app/src/main·app/src/test/.../ble/support diff 항상 0.

## Known Stubs
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- TEST-02 세 축이 실행 가능한 골든으로 고정되어 02-04(CI 필수 클래스 목록 갱신)가 이 SUMMARY 의 매핑 표를 바로 참조할 수 있다
- app/src/main 프로덕션 코드는 이 플랜 전체에서 0줄 변경 — UWB 판정 로직·역할쌍 반경·1바이트 비트팩·UWB 승격 정책 전부 원상 그대로

---
*Phase: 02-golden*
*Completed: 2026-08-28*

## Self-Check: PASSED
- FOUND: .planning/phases/02-golden/02-03-SUMMARY.md
- FOUND: app/src/test/java/com/wf11/safealert/ble/UwbSessionGoldenTest.kt
- FOUND commit: f9a6417
- FOUND commit: f323d9b
- FOUND commit: ed42918
- FOUND commit: 9b6be01
- FOUND commit: ffe4abb
