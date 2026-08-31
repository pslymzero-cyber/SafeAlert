# PROGRESS — SafeAlert

최종 갱신: 2026-08-31 / 작성자: claude

---

## 현재 위치

- 작업 디렉터리: `C:\Users\pslym\Downloads\SafeAlert`
- 브랜치: `master`, HEAD = `0402989` (코드 `849eb05` + 문서 `0402989`)
- 진행 단계: **Phase 4 T1+T2 구현 완료 (미커밋)** — T1 제거 경로 일원화 + T2 STATE-03 계기 노출. 테스트 43건 통과
- 다음 행동: 사용자 확인 후 현장 검증(2시간 구동 후 엔트리 수 판독) → T3~T5 (커밋은 사용자 명시 요청 시에만)

---

## 완료 작업

### Phase 3: BleService 분해 리팩터링 (REFACTOR-01 ~ 04)

`BleService.kt` 단일 파일(3,674줄)에서 판정 로직을 **1자도 바꾸지 않고 이동만** 하여 3개 클래스로 추출했다.

| Task | 산출물 | 결과 |
|------|--------|------|
| T1 | `CalibrationEngine.kt` (268줄) | 완료 |
| T2 | `UwbDistanceManager.kt` (47줄) | 완료 |
| T3 | `AlertStateMachine.kt` (1,903줄) + `AlertStateMachineJvmTest.kt` (121줄) | 완료, 테스트 43건 통과 |
| 문서 | `.planning/phases/03-refactor/03-01-SUMMARY.md` (171줄) | 완료 |
| 커밋 | `849eb05` (8파일, +2,479 / -2,048) | 완료, `--no-verify` 미사용 |

**검증 결과**
- `./gradlew testDebugUnitTest` → `BUILD SUCCESSFUL`, `tests=43 skipped=0 failures=0 errors=0`
- XML 리포트 7개 mtime 전부 갱신 확인 (up-to-date 스킵 아님)
- 기존 골든 테스트 6파일 + 하네스 **diff 0줄** (바이트 단위 무변화)

**규모 변화**
```
BleService.kt        3,674 -> 2,020줄  (-1,654 / -45%)
AlertStateMachine.kt      0 -> 1,903줄
CalibrationEngine.kt      0 ->   268줄
UwbDistanceManager.kt     0 ->    47줄
AlertStateMachineJvmTest  0 ->   121줄

git diff --stat -- app/
 4 files changed, 140 insertions(+), 2048 deletions(-)
```

### Phase 4 T1: 기기 상태 제거 경로 일원화 (BUG-01) — 구현 완료, 미커밋

- **신설** `03_service/DeviceStateRegistry.kt` (97줄) — immediate/deferred/teardown 3그룹 슬롯 + `purge(deviceId, cold)` / `purgeDeferred(deviceId)` / `clearAll()` / `slotCount()` / `entryCount()` / `purgeCount`. 중복 등록은 `require` 로 차단
- **등록** ASM `init` 33건 (상태맵 29 + `uwbDist` 3 immediate + `kalmanFilters` deferred + `filterPreserveMap` teardown), `BleService.onCreate` 9건 (`oneSecBuffer`·`wakeRssiMap`·dwell 3맵·`echoDiffLive` immediate, 필터 3종 deferred)
- **치환** 3경로 → 레지스트리 단일 경로
  - `onDeviceLost` 48줄 → 13줄. `asm.registry.purge(deviceId, cold = lastRssi == null)`. 스냅샷 읽기·`uwbRanger?.onDeviceLost` 는 purge 앞(원본 순서 보존)
  - healthCheck TTL prune 5줄 → 1줄. `asm.registry.purgeDeferred(id)` + `timeGateWaiveSet.remove(id)` 명시 유지(immediate 소속)
  - `stopAll` 35줄 → 7줄. `broadcastDeviceList(force)` 뒤에 `registry.clearAll()`(broadcast 가 `pendingDisplayMap` 을 읽으므로 앞 7개 clear 는 원본 유지), `persistEchoAll(myId)` 는 clearAll 앞. 중복 `wakeRssiMap.clear()` 1줄 제거
- **유지** `clearDwellMute` 함수 (본문 = dwell 3맵 remove, registry 와 동치지만 fx override 경유 호출부가 있어 존치)
- **미등록 유지** `localSnapshot`/`lastLocalSnapshot`, zone 4맵 + `myZoneInside`, 핸들러·리시버·플래그 정리
- 판정 로직·반경 값·기대값 배열 무수정. `BleService.kt` 2,020 → 1,968줄
- 검증: `./gradlew testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL, **tests=43 failures=0 errors=0 skipped=0** (7개 XML 집계, 캐시 히트 아님)

### Phase 4 T2: STATE-03 계기 (개발자 설정 실시간 진단) — 구현 완료, 미커밋

- **목표** REQUIREMENTS.md STATE-03 — 추적 기기 수·상태 엔트리 수·정리 이벤트를 실시간으로 확인해 잔존(좌비) 상태를 조기 발견. Phase 4 현장 검증 수단
- **`DeviceStateRegistry.kt`** (97 → 112줄) — `sizeOf(name): Int?` 1개 + `companion object { @Volatile @JvmStatic var live }` 추가. T1 이 이미 만든 `slotCount()`/`entryCount()`/`purgeCount` 재사용(집계 로직 신규 0줄)
- **`BleService.kt`** 2줄 — `onCreate` 등록 블록 끝에 `DeviceStateRegistry.live = asm.registry`, `onDestroy` 첫 줄에 `= null`(서비스 소유 람다 보유 → 누수 방지)
- **`activity_dev_settings.xml`** — 「기기 상태 계기」 접힘 카드(`sec_state_*` / `tv_state_diag`)를 「앱 정보」 카드 앞에 삽입. 기존 `SA.Card`/`SA.SectionHeader`/`SA.Mono`/`SA.Hint` 스타일 재사용
- **`DevSettingsActivity.kt`** — `bindSection` 1줄 + 기존 `uwbDiagPoller`(1200ms)에 `refreshStateDiag()` 1줄 + 빌더 1개. 폴러 신규 생성 없음
- **표시** `추적 N대 · 엔트리 M개 / 슬롯 K개 · 정리 P회` — `live == null` 이면 `서비스 정지 — 계기 없음`. 추적 수 = `sizeOf("alertState")`
- **판독법** 추적 0대가 오래 이어지는데 엔트리가 줄지 않으면 잔존 상태(BUG-01 재발) 의심
- 읽기 전용 경로만 추가 — 판정 경로(`processAlert` 등) 0줄 수정. `UwbRanger.kt`/`UwbCalibrator.kt` 무수정
- 검증: `./gradlew testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL, **tests=43 failures=0 errors=0 skipped=0** (캐시 히트 아님, 32 tasks executed)

---

## 수정 파일·심볼

**신규 (커밋 `849eb05` 에 포함)**
- `app/src/main/java/com/wf11/safealert/03_service/AlertStateMachine.kt`
  - `class AlertStateMachine(fx: Effects, uwbDist: UwbDistanceManager)`
  - `interface Effects` — 35멤버, 서비스 부작용 역주입 이음매 (Android Context 무의존화)
  - `internal val alertState` — 테스트에서 직접 assert 가능
  - `fun processAlert(...)`, `fun judgeUwbOnly(deviceId, uwbD, now)`
- `app/src/main/java/com/wf11/safealert/03_service/UwbDistanceManager.kt`
  - `class UwbDistanceManager(ranger: () -> UwbRanger?)`, 맵 3종, `UWB_MEAS_FRESH_MS = 1_000L`
- `app/src/main/java/com/wf11/safealert/03_service/CalibrationEngine.kt`
- `app/src/test/java/com/wf11/safealert/service/AlertStateMachineJvmTest.kt` — 순수 JVM 테스트
- `.planning/phases/03-refactor/03-01-SUMMARY.md`

**수정 (커밋 `849eb05` 에 포함)**
- `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` — 추출부 제거 + 위임
- `app/src/main/java/com/wf11/safealert/05_ui/BleSettingsActivity.kt` — `CalibrationEngine` 참조 14곳
- `app/src/main/java/com/wf11/safealert/SafeAlertApp.kt` — +2줄
- `app/build.gradle` — +3줄, `testOptions { unitTests.returnDefaultValues = true }`

**손대지 말 것 (이전 세션 잔여물, 이번 작업과 무관)**
- `.gitignore`, `.planning/phases/01-ci/01-UAT.md`, `.planning/phases/01-ci/01-VALIDATION.md`
- 루트 산출물: `SafeAlert_*.pptx/docx/pdf/txt`, `epj.png`, `보행자.png`, `장비.png`

---

## 확정 스펙 (불변 조건)

1. `processAlert` 이름·시그니처 유지, 본문만 1줄 위임 -> 기존 테스트 6파일·하네스 diff 0줄
2. 판정 로직·반경 값(15/8/5/3m)·기대값 배열 **변경 금지, 이동만**
3. 스레딩 변경 금지 (신규 스레드·큐·디스패처 0)
4. `UwbRanger.kt` / `UwbCalibrator.kt` **수정 금지**
5. **백업 재확보 (2026-08-31)**: `C:\Users\pslym\Downloads\_작업\SafeAlert_backup_pre-phase4\`
   - `current_03_service\` — Phase 3 완료 시점 4파일 (BleService / AlertStateMachine / CalibrationEngine / UwbDistanceManager)
   - `pre_refactor_c14d2cc\BleService.kt` — 분해 전 원본 3,674줄 (`849eb05^` 추출)
   - 이전 temp `.bak` 3종(`.t3bak`/`.t2bak`/`.bak`)은 **소실 확정**. git 이 유일 이력이었고, 이제 실물 사본도 확보

### 작업 방식 제약 (사용자 지시)
- GSD 스킬 재호출 금지, agent 스폰 금지 — 오케스트레이터가 직접 수행
- `BleService.kt` 전체 읽기 금지. `grep -n` 으로 심볼 경계만 특정
- 브랜치 생성·전환 금지 / `--no-verify` 금지 / **커밋은 사용자가 요청할 때만**
- Read 는 offset·limit 200줄 이내, 셸 출력은 `| head -50` 등으로 제한
- 줄 번호는 쓰기 전 `grep -n` 으로 재확인
- 한국어 출력, 파일에 이모지 금지
- `jq` 없음 (`node -e` 사용)

---

## Phase 4 T1 설계 (확정 — 코드 0줄, 이 절 그대로 구현)

**C안 승인 (사용자: "추천으로 진행해")** — T1(레지스트리 + 단일 purge 경로) + T2(STATE-03 계기)를 먼저 출하해 BUG-01 을 현장 검증하고, 계기가 깨끗하면 그때 T3~T5(`DeviceTrackingState` 필드 이관) 착수.

**BUG-01 구조**: 상태 제거가 3갈래 비협조 경로(`onDeviceLost` / healthCheck TTL prune / `stopAll`)로 분산 + 최대 15s 지연 창 동안 39개 중 7개만 정리되는 "반쪽 상태". `.remove(` 157곳 산재 -> 맵 추가할 때마다 누수 재생산(v1.1.35 `forwardBiasLatchMap` stopAll 누락이 선례).

### 신설 파일

`app/src/main/java/com/wf11/safealert/03_service/DeviceStateRegistry.kt` (패키지 선언은 `com.wf11.safealert.service` — 디렉터리명과 불일치가 정상)

슬롯 3그룹 + `clearAll()` + `slotCount()` / `entryCount()` / `purgeCount`(T2 계기 대비) + 중복 등록 `require`.

| 그룹 | 개수 | 시점 | 대상 |
|------|------|------|------|
| immediate | 38 | `onDeviceLost` 즉시 purge + `clearAll` | ASM 29 + BleService 5 + Uwb 3 + `CalibrationEngine.echoDiffLive`(커스텀 람다) |
| deferred | 4 | cold-clear · TTL 만료 시 | `rssiPreFilter` / `medianFilter` / `pEmaFilter` / `kalmanFilters` |
| teardown | 1 | `clearAll` 전용 (기기별 purge 제외) | `filterPreserveMap` |

- immediate ASM 29: alertState, rushFrameMap, dangerContactStreakMap, warningContactStreakMap, warningMissRefMap, lastKfVelMap, timeGateWaiveSet, shadowFusionMap, trackingStateMap, crossingStartMap, departingStartMap, wasStationaryMap, recedingStartMap, recedeRefMap, recedePeakMap, deviceRssiMap, approachStreakStartMap, fastApproachStreakMap, forwardBiasLatchMap, mutedDevices, peerInZoneMap, suddenLabelMap, deviceCategoryMap, deviceStateMap, deviceTurnMap, reverseRssiHist, reversePrepUntil, firebaseLastSaveMap, pendingDisplayMap
- immediate BleService 5: oneSecBuffer, wakeRssiMap, dwellLevelMap, dwellSinceMap, dwellMutedLevelsMap
- `echoDiffLive` 는 단순 remove 아님: `remove(id)?.let { CalibrationEngine.persistEchoEntry(id, it) }`
- `kalmanFilters` 는 `[id]?.reset()` 후 `remove(id)`
- 검산: ASM 선언 31 = immediate 29 + kalmanFilters 1 + filterPreserveMap 1

**`filterPreserveMap` 을 기기별 purge 에서 뺀 이유 (결정적)**: `onDeviceLost` 호출부가 `BleScanner.kt:280`(개별 타임아웃)·`:295`(일괄 forEach) 2곳이라 중복 발화를 배제할 수 없다. 1차 소실이 `filterPreserveMap[id]` 를 세팅한 뒤 2차 소실이 오면 `deviceRssiMap` 이 이미 비어 `lastRssi == null` -> cold 분기 -> purge 가 이걸 포함하면 **v1.1.58 fix4 웜 필터 보존이 파괴**(= 금지된 판정 동작 변경). 누수 위험은 없다 — healthCheck TTL prune 이 자기 자신을 스캔해 30s 후 무조건 제거.

### 순서 함정 (어기면 동작 변경)

1. `val lastRssi = deviceRssiMap[deviceId]` 스냅샷 읽기는 purge 보다 **반드시 선행**
2. `alertState.isEmpty()` 검사는 purge **이후** (원본과 동일)
3. `stopAll` 의 `CalibrationEngine.persistEchoAll(myId)` 는 `echoDiffLive.clear()` **보다 먼저**
4. healthCheck TTL prune 안의 `timeGateWaiveSet.remove(id)` 는 immediate 소속이라 deferred purge 에 안 들어감 -> prune 안에 명시 1줄로 남긴다

### 배선 함정

1. 필터 3종(`RssiPreFilter`/`MedianFilter`/`PEmaFilter`)은 **size 접근자 없음** -> 엔트리 집계는 맵 슬롯만, 필터는 purge/clear 전용 슬롯으로 등록
2. `AlertStateMachine` 에 `init {}` 블록 **없음** -> 415행(`wasStationaryMap`, 마지막 맵) 다음 416 공백에 신설. 모든 맵 선언 뒤여야 초기화 순서 안전
3. `BleService.kt:439~476` 별칭 체인 31개 + uwb 3개는 테스트 `ReflectionHelpers.getField` 가 백킹 필드를 읽는다 -> **삭제 금지**

### 미등록 유지 (stopAll 나열식 그대로 둘 것)

`localSnapshot` / `lastLocalSnapshot`, `zoneSampleMap` / `zoneEnterRssiMap` / `zoneLastSeenMap` / `zoneInsideMap`(키가 deviceId 아닌 beaconKey), `myZoneInside`, 각종 핸들러 정리.

### 구현 순서

1. `DeviceStateRegistry.kt` 신설
2. `AlertStateMachine` 416행 부근 `init {}` — 자체 31맵 + `uwbDist` 3맵 등록
3. `BleService.onCreate()`(526) — 자기 5맵 + 필터 3종 + `echoDiffLive` 등록
4. `onDeviceLost`(805~871) 나열식 제거 -> `registry.purge(deviceId, cold=...)` 치환 (순서 함정 1·2 준수)
5. healthCheck prune(1263~1295) -> `registry.purgeDeferred(id)` + `timeGateWaiveSet.remove(id)` 1줄
6. `stopAll`(1746~1810) 나열식 clear -> `registry.clearAll()` (순서 함정 3 준수)
7. `./gradlew testDebugUnitTest --rerun-tasks` -> 43건 통과 확인
8. 결과 보고 후 **정지**. T2 는 사용자 확인 뒤

### 좌표 (쓰기 직전 `grep -n` 재확인 필수)

```
BleService.kt   181/186/191 필터3   302 oneSecBuffer   338 wakeRssiMap
                352-354 dwell 3종   360-363 zone 4종(등록 제외)
                393 uwbDist   396-436 asm+Effects   420 clearDwellMute 위임
                439-476 별칭 체인(유지)   526 onCreate   805 onDeviceLost
                1134 clearDwellMute   1263-1295 healthCheck prune
                1746 stopAll   1834 onDestroy
AlertStateMachine.kt  94~415 맵 31개 선언   125 timeGateWaiveSet(유일 Set)
                      415 wasStationaryMap(마지막)   540 filterPreserveMap 소비 경로
BleScanner.kt   280 개별 타임아웃 onDeviceLost   295 일괄 forEach onDeviceLost
```

---

## 남은 순서

1. **[완료]** Phase 3 커밋 마감 — 단일 커밋 `849eb05`. T1/T2/T3 3분할은 불가로 판정 (아래 미해결 이슈 참조)
2. **[진행 중]** Phase 4 (기기 상태 단일화) — **T1·T2 구현 완료·미커밋** (테스트 43건 통과). 다음은 사용자 확인 후 현장 검증 → T3~T5
3. PERF-01(스캔 콜백 = 메인 스레드에서 `processAlert` 실행 -> 20대 이상 프레임 드랍)은 **Phase 5** 예정

---

## 미해결 이슈

- ~~커밋 0건~~ **해소** — `849eb05` 1건. SUMMARY `actuals.commits: 1`, Task Commits 표 3행 전부 해시 기입 완료.
- **T1/T2/T3 3커밋 분할 불가 (확정)** — 백업 스냅샷(`.t3bak`/`.t2bak`/`.bak`) 전량 소실. diff hunk `@@ -690,7 +393,84 @@` 하나가 T2 소유 필드 제거와 T3 Effects 배선 84줄을 동시에 담아 줄 단위 분리 시 T1·T2 중간 커밋이 컴파일 불가 -> bisect 가치 소멸. 단일 커밋으로 확정.
- **STATE.md 드리프트 정정** — `current_phase` 02 -> 03, `completed_phases` 0 -> 3, `total/completed_plans` 6 -> 7. ROADMAP Phase 2 체크박스도 미체크였으나 02-01~04 전부 완료라 체크 처리.
- **`03-01-PLAN.md` 경로 오기** — `files_modified` 의 테스트 경로가 `.../ble/AlertStateMachineJvmTest.kt` 로 적혀 있으나 실제는 `.../service/`. SUMMARY 의 Deviations 1번에 기록됨.
- 빌드·테스트 실패는 없음.

### 도구 함정 (재발 방지)
- Windows Git Bash 에서 **긴 멀티라인 heredoc 파싱 실패** (`unexpected EOF while looking for matching`). 긴 마크다운은 heredoc 대신 Write 도구 사용.
- GateGuard(Fact-Forcing Gate)가 Write 신규 파일 생성을 차단 -> 4가지 사실(호출 지점 / 중복 없음 / 데이터 형식 / 사용자 지시 원문) 제시 후 동일 호출 재시도로 통과. 우회 환경변수 사용 금지.
- Windows Python 은 `/tmp` 를 `C:\tmp` 로 해석. Python 힙독에서 비-ASCII print 금지 (cp949 `UnicodeEncodeError`).
- `sed` 치환 후 반드시 `grep` 으로 확인.

---

## 컨텍스트 초기화 후 재개 프롬프트

아래를 그대로 붙여넣어 이어서 작업한다.

```
SafeAlert 프로젝트 이어서 작업한다. 먼저 C:\Users\pslym\Downloads\SafeAlert\PROGRESS.md 를 읽어라.
특히 "Phase 4 T1 설계 (확정)" 절이 이번에 구현할 내용 전부다. 설계는 이미 확정됐으니 재설계·재조사 금지.

상태: Phase 3 마감 완료(커밋 849eb05, 테스트 43건 통과). Phase 4 는 C안 승인 완료 + T1 설계 확정 + 애플리케이션 코드 0줄.

지금 할 일 = Phase 4 T1: PROGRESS.md 의 "Phase 4 T1 설계 (확정)" 절에 적힌 구현 순서 1~8 을 그대로 수행.
DeviceStateRegistry.kt 신설 -> AlertStateMachine init 등록 -> BleService.onCreate 등록 ->
onDeviceLost / healthCheck prune / stopAll 을 레지스트리 호출로 치환 -> 테스트 -> 보고 후 정지.

지켜야 할 제약:
- 커밋은 내가 명시 요청할 때만. 브랜치 생성·전환 금지, --no-verify 금지
- GSD 스킬 재호출 금지, agent 스폰 금지 — 직접 수행
- BleService.kt 전체 읽기 금지. grep -n 으로 심볼 경계만 특정
- 판정 로직·반경 값(15/8/5/3m)·기대값 배열 변경 금지. 이번 T1 은 "제거 경로 일원화"만이고 판정 동작은 1비트도 안 바뀌어야 한다
- UwbRanger.kt / UwbCalibrator.kt 수정 금지
- BleService.kt 439~476 별칭 체인 삭제 금지 (테스트가 리플렉션으로 읽는다)
- .gitignore, .planning/phases/01-ci/01-UAT.md, 01-VALIDATION.md, 루트의 pptx/docx/pdf/png 잔여물은 손대지 말 것
- 한국어 출력, 파일에 이모지 금지
- 줄 번호는 쓰기 직전 grep -n 으로 재확인. 설계 절의 좌표는 참고값이다
- GateGuard 훅이 첫 Bash·파일 편집을 막으면 요구하는 사실을 평문 제시하고 동일 호출 재시도. 우회 환경변수(ECC_GATEGUARD=off 등) 사용 절대 금지
- 테스트는 ./gradlew testDebugUnitTest --rerun-tasks (UP-TO-DATE 캐시 히트는 검증 아님)
- 태스크 종료 시 결과 보고 -> 멈춤 -> 내 확인 후 다음

원본 백업: C:\Users\pslym\Downloads\_작업\SafeAlert_backup_pre-phase4\
```
