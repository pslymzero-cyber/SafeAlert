# PROGRESS — SafeAlert

최종 갱신: 2026-08-31 / 작성자: claude

---

## 현재 위치

- 작업 디렉터리: `C:\Users\pslym\Downloads\SafeAlert`
- 브랜치: `master`, HEAD = `84faba6` (보안 A + 업데이트 채널 공용화, 태그 v1.1.71 push 완료)
- 진행 단계: **Phase 4 T1+T2+T3 완료** — T1 제거 경로 일원화 + T2 STATE-03 계기 + T3 테스트·문서 마감. 테스트 **51건 통과**(기존 43 + 신규 8). REQUIREMENTS STATE-02·STATE-03·BUG-01 = Complete, **STATE-01만 Pending**
- 다음 행동: **사용자 질문 4건 답변 전달 + 증상 기기 세션 로그 확인** — 아래 「v1.1.71 출시 후 현장 증상 조사」 절 참조. 실기 검증(2시간 연속 구동)은 사용자 지시로 보류. 커밋은 사용자 명시 요청 시에만

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

### Phase 4 T1: 기기 상태 제거 경로 일원화 (BUG-01) — 완료 (커밋 39a74fb)

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

### Phase 4 T2: STATE-03 계기 (개발자 설정 실시간 진단) — 완료 (커밋 39a74fb)

- **목표** REQUIREMENTS.md STATE-03 — 추적 기기 수·상태 엔트리 수·정리 이벤트를 실시간으로 확인해 잔존(좌비) 상태를 조기 발견. Phase 4 현장 검증 수단
- **`DeviceStateRegistry.kt`** (97 → 112줄) — `sizeOf(name): Int?` 1개 + `companion object { @Volatile @JvmStatic var live }` 추가. T1 이 이미 만든 `slotCount()`/`entryCount()`/`purgeCount` 재사용(집계 로직 신규 0줄)
- **`BleService.kt`** 2줄 — `onCreate` 등록 블록 끝에 `DeviceStateRegistry.live = asm.registry`, `onDestroy` 첫 줄에 `= null`(서비스 소유 람다 보유 → 누수 방지)
- **`activity_dev_settings.xml`** — 「기기 상태 계기」 접힘 카드(`sec_state_*` / `tv_state_diag`)를 「앱 정보」 카드 앞에 삽입. 기존 `SA.Card`/`SA.SectionHeader`/`SA.Mono`/`SA.Hint` 스타일 재사용
- **`DevSettingsActivity.kt`** — `bindSection` 1줄 + 기존 `uwbDiagPoller`(1200ms)에 `refreshStateDiag()` 1줄 + 빌더 1개. 폴러 신규 생성 없음
- **표시** `추적 N대 · 엔트리 M개 / 슬롯 K개 · 정리 P회` — `live == null` 이면 `서비스 정지 — 계기 없음`. 추적 수 = `sizeOf("alertState")`
- **판독법** 추적 0대가 오래 이어지는데 엔트리가 줄지 않으면 잔존 상태(BUG-01 재발) 의심
- 읽기 전용 경로만 추가 — 판정 경로(`processAlert` 등) 0줄 수정. `UwbRanger.kt`/`UwbCalibrator.kt` 무수정
- 검증: `./gradlew testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL, **tests=43 failures=0 errors=0 skipped=0** (캐시 히트 아님, 32 tasks executed)

### Phase 4 T3: 테스트·문서 마감 — 완료 (커밋 a09b16e)

- **목표** 사용자 지시 「미뤄둔 작업 진행. 단 실기 테스트만 보류」 — 실기 검증으로만 달성 가능하던 항목을 단위 검증으로 대체하고 문서에 확정 반영
- **`DeviceStateRegistryTest.kt`** (신규 117줄, 안드로이드 의존 0) — 6건. 웸/콜드/TTL/`clearAll` 4분기와 잔여 0, 200사이클 누적 없음, 중복 슬롯명 거부
- **`AlertStateMachineJvmTest.kt`** — 실배선 2건 추가. `registryPurge_leavesNoResidueForDevice`(실제 판정으로 상태를 채운 뒤 purge → 기저선 복귀), `repeatedDeviceChurn_doesNotGrowState`(100회 진입/소멸 → 단조 증가 없음). 등록 누락 슬롯이 있으면 잔여로 드러나는 구조
- **검증** `./gradlew testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL (32 tasks executed, 캐시 히트 아님), **8클래스 51건 / failures=0 errors=0 skipped=0**. 그중 골든 벡터 24건(AlertCascadeGolden 8 + UwbSessionGolden 16) 통과 = **판정 동작 1비트 불변 확인**
- **`.planning/REQUIREMENTS.md`** — STATE-02·STATE-03·BUG-01 체크 + 근거 하위항목, 매핑 표 3행 Pending → Complete. **STATE-01 은 Pending 유지**. BUG-01 에는 「2시간 이상 연속 구동 실기 힙 관측은 보류. 단위 검증이 그 자리를 대신한다」를 명기
- **`activity_dev_settings.xml:474`** — 오타 `펌침/접힘` → `펼침/접힘` 수정(접근성 문자열만, 기능 무영향)
- 프로덕션 Kotlin 코드 0줄 수정. 변경 = 테스트 2파일 + XML 1줄 + 문서

---

---

### 보안 A + 업데이트 채널 공용화 (v1.1.71) — 완료 (커밋 84faba6, 태그 v1.1.71 push 완료)

- **목표** 사용자 지시 「1 2번으로 진행. 사업장 코드는 그냥 두자」 — Firebase RTDB 무인증 개방 차단 + 자동 업데이트 채널을 사업장 코드와 분리
- **발견한 결함** 앱은 `{firebaseRoot}/version` 을 읽는데 CI 는 `/wf11/version` 에만 썼다 → `wf11` 이 아닌 사업장 코드를 넣은 기기는 자동 업데이트를 영구히 못 받는다. 보안 항목보다 우선 처리
- **`UpdateManager.kt:30`** — `DevSettings.firebaseRoot` 경유 제거, 사업장 루트 밖 공용 `/version` 단독 참조. 이 파일의 `firebaseRoot` 참조는 이 1곳뿐이었고, 다른 5곳(FirebaseManager·DevSettingsActivity·DevSettings)은 사업장별 유지 = 무수정
- **`database.rules.json`** (신규) — 루트 `.read/.write false` 기본 차단. `version` 을 `$site` 와 형제인 명명 노드로 두어 와일드카드 매칭에서 제외(명명 키는 `$wildcard` 에 안 잡힘). `version` 읽기만 허용·쓰기 차단 = `apk_url` 변조로 임의 APK 자동설치되는 급소 봉쇄. `alerts` 는 `!data.exists()` 로 신규 추가만, `beacon_share`·`echo_calib` 는 현행 유지(앱에 `FirebaseAuth` 0건). **사업장 코드 형식 제약은 사용자 지시로 넣지 않음** — `$site` 완전 개방
- **`release.yml`** — (a) `Update Firebase Realtime DB` 에 공용 `/version.json` PATCH 추가, 옛 `/wf11/version.json` 은 v1.1.70 이하 현장 기기 호환용으로 존치(전 기기 갱신 확인 후 삭제) (b) `Deploy DB security rules` 스텝 신설 — `curl PUT --data-binary @database.rules.json` 로 `.settings/rules.json` 덮어쓰기. 둘 다 `is_release == 'true'` 게이트
- **「코드 0줄로 잠긴다」 근거** legacy database secret(`?auth=`)은 규칙을 우회하는 admin 권한이라, 규칙을 잠근 뒤에도 CI PATCH 는 계속 통과한다
- **버전** `versionCode 126 → 127`, `versionName 1.1.70 → 1.1.71`. 결함 수정이지만 배포되어야 효과가 있고 배포 = 릴리스 태그 = 버전 증가 (사용자 확인 완료)
- **검증** `./gradlew testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL (32 tasks executed), **51건 / failures=0**. YAML·JSON 파싱 각각 통과
- **미검증** DB **쓰기** 개방 여부는 실측하지 않았다(부작용 우려로 write 프로브 생략). 읽기 개방은 무인증 GET 200 으로 실측 완료

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
2. **[진행 중]** Phase 4 (기기 상태 단일화) — **T1·T2·T3 완료** (테스트 51건 통과). REQUIREMENTS 기준 STATE-02·STATE-03·BUG-01 = Complete
   - 남은 것 ① **STATE-01**(`DeviceTrackingState` 통합) — 착수 여부 사용자 판단 대기. 아래 「STATE-01 판단 제기」
   - 남은 것 ② 실기 검증(2시간 연속 구동 후 엔트리 수 판독) — **사용자 지시로 보류**
   - 미커밋 잔여: `.planning/REQUIREMENTS.md`, `PROGRESS.md`, `activity_dev_settings.xml`, `AlertStateMachineJvmTest.kt`, 신규 `DeviceStateRegistryTest.kt`. 커밋 시 `.gitignore`·`01-UAT.md`·`01-VALIDATION.md` 는 **제외**(사용자 지정 무수정 파일)
3. **[완료]** 보안 A 커밋 + 태그 `v1.1.71` + push — 커밋 `a09b16e`(Phase 4 T3) / `84faba6`(보안 A), 태그 `v1.1.71` push 완료 2026-09-01. CI 결과(APK 릴리스 / `Deploy DB security rules` 성공 여부) 확인 대기
4. PERF-01(스캔 콜백 = 메인 스레드에서 `processAlert` 실행 -> 20대 이상 프레임 드랍)은 **Phase 5** 예정

---

## STATE-01 판단 제기 (사용자 결정 필요)

- **목표는 이미 달성됐다.** STATE-01 과 T1 레지스트리는 같은 결과(좀비 엔트리 구조적 불가)에 대한 두 가지 해법이고, T1 이 그 결과를 이미 냈다(제거 경로 1곳 + 등록 누락을 드러내는 테스트)
- `DeviceTrackingState` 데이터 클래스가 **추가로 주는 것**은 「새 맵이 몰래 생기지 않는다」는 컴파일 타임 강제 하나다
- **대가**: 경보 판정 핫패스 약 **250 사이트** 재작성. 실기 검증이 보류된 상태에서 안전 앱 판정부를 그 규모로 손대는 것은 실질 위험
- **완화 요인**: 골든 회귀 테스트 1,883줄(AlertCascadeGolden 621 + LowSpeedApproachRegression 708 + UwbSessionGolden 554)이 판정 1비트 변화를 잡는다
- **착수 시 설계 방향(미착수)**: 맵 값 타입을 nullable 필드로 1:1 보존하면 기계적 치환 가능 — `rushFrameMap[id]` → `t.rushFrame`, `.remove(id)` → `t.rushFrame = null`, `.containsKey(id)` → `t.rushFrame != null`
  - 함정 ① 반복 패턴(`.keys`/`.filterValues{}`/`.entries`)은 통합 맵에서 null 필터 필요
  - 함정 ② Set 2종(`mutedDevices`, `timeGateWaiveSet`)은 Boolean 필드로
  - 함정 ③ **별칭 체인 보존 요구와 백킹 맵 소멸이 정면 충돌** — `BleService.kt` 438~475 의 `private val rushFrameMap = asm.rushFrameMap` 이 컴파일 불가해진다. 맵 삭제 전에 리플렉션 테스트 경로부터 재설계해야 함
- **결론**: 사용자가 전량 수행을 재확인하면 그대로 진행한다. 재확인 없이는 착수하지 않는다

---

## v1.1.71 출시 후 현장 증상 조사 (진행 중, 답변 미전달)

사용자가 v1.1.71 배포 후 현장에서 겪은 증상 4건을 물었고, **조사는 끝났으나 답변을 전달하기 전에 사용자가 중단을 지시**했다. 다음 세션의 첫 행동은 이 절을 근거로 답변을 완결해 전달하는 것이다.

### 사용자 질문 원문

1. 이번 패치로 판정에 변경이 있나?
2. 왜 알림이 늦어졌지?
3. 스쳐지나고 난 후에도 알림이 울리고 있는데?
4. 왜 다시 원복이 된거지?

코드 수정 요청이 아니라 **원인 설명 요청**이다. 판정 코드는 손대지 않는다.

### 내 오답과 정정 (반드시 다음 세션 답변에 포함)

- `git show --stat 84faba6` / `a09b16e` 두 커밋만 보고 **"v1.1.71 은 판정을 안 바꿨다"** 고 표까지 만들어 사용자에게 단정 전달했다. **틀렸다.**
- 실제로는 `v1.1.70..v1.1.71` 사이에 약 70커밋이 있고, 그중 `f5fe81f` 가 판정부를 37줄 바꿨다(주석에 `[v1.1.71 D-3B BUG-02]` 명시).
- 원인: 릴리스 범위를 태그 구간 로그로 확인하지 않고 마지막 2커밋만 봤다.
- **재발 방지 규칙: 릴리스 영향 범위는 반드시 `git log --oneline <이전태그>..<이번태그>` 로 확인한다. HEAD 근처 커밋 몇 개로 릴리스를 판단하지 않는다.**

### 실측 기록 (전부 확보 완료 — 재조사 불필요)

**실측 (1) v1.1.48 두 픽스 생존 확인** — `grep -n "urgentBypass\|kfVel >= 1\.0\|kfVel >= 2\.0\|CROSSING" AlertStateMachine.kt`

    190:  enum class TrackingState { APPROACHING, CROSSING, DEPARTING }
    194:  internal val crossingStartMap   = mutableMapOf<String, Long>()
    208:  internal val CROSSING_CONFIRM_MS = 1500L
    507:  trackingStateMap[deviceId] = TrackingState.CROSSING
    517:  kfVel >= 1.0 ->                       (CROSSING 리셋 데드밴드 = v1.1.48 픽스 2)
    523:  now - (crossingStartMap[deviceId] ?: now) >= CROSSING_CONFIRM_MS ->
    874:  val urgentBypass = kfVel >= 2.0 || medianValue >= effDanger   (v1.1.48 픽스 1)
    875:  if (!urgentBypass && gateRssi < effWarning && !alertState.containsKey(deviceId))
    1249: (v1.1.56 U2) 추적맵(상태·CROSSING·DEPARTING 앵커) 조기삭제 방지
    1663: (v1.1.62) || fx.myZoneInside — 존 비콘 접촉 중엔 무조건 가청 억제

**실측 (2) `f5fe81f` 의 판정 변경 전문** — `AlertStateMachine.kt` 793~816 (`sed -n '793,822p'`)

```kotlin
val dangerStreak = if (inDangerRaw) (dangerContactStreakMap[deviceId] ?: 0) + 1 else 0
dangerContactStreakMap[deviceId] = dangerStreak
// [v1.1.71 D-3B BUG-02] WARNING streak 만 미달 시 변화율(dBm/s) 기준 즉시 리셋 여부를 가른다.
val inWarningRaw = medianValue >= effWarning
val (prevMedianForWarning, prevAtMsForWarning) = warningMissRefMap[deviceId] ?: (medianValue to now)
val warningStreak = when {
    inWarningRaw -> (warningContactStreakMap[deviceId] ?: 0) + 1
    else -> {
        val dtSec = (now - prevAtMsForWarning).coerceAtLeast(1L) / 1000.0
        val rateDbmPerSec = (medianValue - prevMedianForWarning) / dtSec
        if (rateDbmPerSec <= -WARNING_DEPART_RATE_DBM_PER_SEC) 0 else (warningContactStreakMap[deviceId] ?: 0)
    }
}
warningContactStreakMap[deviceId] = warningStreak
warningMissRefMap[deviceId] = medianValue to now
```

`WARNING_DEPART_RATE_DBM_PER_SEC = 3.0` (206행). **접근/이탈 방향 구분이 없다.**

**실측 (3) `warningStreak` / `dangerStreak` 전 사용처** — `grep -n`

    793/794:  dangerStreak  계산·저장
    807/815:  warningStreak 계산·저장
    1069:  if (!isDepartingNow && stableLevel < LEVEL_DANGER  && dangerStreak  >= 2)
    1072:  } else if (!isDepartingNow && stableLevel < LEVEL_WARNING && warningStreak >= 2)
    1512:  val fastDangerContact = !isDepartingNow && dangerStreak >= 2 && stableLevel >= LEVEL_DANGER
    1516:  (!isDepartingNow && warningStreak >= 2 && stableLevel >= LEVEL_WARNING)

**해석**: streak 는 **격상·발령 경로에만** 쓰이고 해제 경로에는 안 쓰인다. 네 사용처 모두 `!isDepartingNow` 가드가 걸려 있다. 따라서 `f5fe81f` 의 정확한 영향은 "경보가 안 꺼진다"가 아니라 **이탈 확정 전 구간의 경보 재점화 문턱 저하**다.

**실측 (4) v1.1.50 픽스 생존 확인** — `grep -n "freshUwbDistM"`

    AlertStateMachine.kt:756   uwbDist.freshUwbDistM(deviceId)?.let { UwbCalibrator.onSample(...) }   (학습 게이트)
    AlertStateMachine.kt:1095  val uwbD     = uwbDist.freshUwbDistM(deviceId)   (승격)
    AlertStateMachine.kt:1145  val uwbNowD  = uwbDist.freshUwbDistM(deviceId)   (이탈)
    AlertStateMachine.kt:1185  val uwbPrimD = uwbDist.freshUwbDistM(deviceId)   (주 권위)
    UwbDistanceManager.kt:42   fun freshUwbDistM(deviceId: String): Float?

**실측 (5) v1.1.70..v1.1.71 중 판정 파일을 건드린 커밋 4건** — `git log --oneline v1.1.70..v1.1.71`

| 커밋 | 내용 | 규모 |
|---|---|---|
| `39a74fb` | 기기 상태 제거 경로 일원화 + STATE-03 계기 | BleService 115줄, AlertStateMachine 53줄, DeviceStateRegistry 112줄 신규 |
| `849eb05` | BleService 분해 (AlertStateMachine + UwbDistanceManager + CalibrationEngine) | BleService 2156줄 재작성 |
| `f5fe81f` | WARNING streak 하강률 게이트 (BUG-02 저속 접근 미탐지 대응) | BleService 37줄, 회귀테스트 409줄 |
| `7bdbdad` | processAlert 에 nowMs 주입 seam 추가 | 3줄, 동작 동일 선언 |

`git describe --tags --contains` 로 4건 모두 **v1.1.71 이 첫 출시**임을 확인했다.

### 질문별 결론

- **질문 1 — 확답 준비 완료.** v1.1.71 은 **판정을 바꿨다.** 위 표 4커밋. 특히 `f5fe81f` 가 실질 판정 변경이다.
- **질문 3 — 원인 확정 수준.** `f5fe81f` 의 `warningStreak` 하강률 게이트(805~816). `medianValue >= effWarning` 미달 프레임에서 하강률이 -3.0 dBm/s 보다 완만하면 streak 를 **0 으로 끊지 않고 보존**한다. 실내 반사·차폐로 신호가 완만히 흔들리면 이탈 확정 전 구간에서 재점화 문턱이 낮아져 경보가 되살아난다.
- **질문 4 — 가설 기각 완료.** "분해 리팩터에서 v1.1.48~50 픽스가 이관 누락됐다"는 가설은 실측 (1)·(4) 로 **완전 기각**. `urgentBypass`·CROSSING 데드밴드·`freshUwbDistM` 3블록·학습 게이트 전부 생존. "원복" 체감의 실제 정체는 질문 3 과 같은 메커니즘일 가능성이 높다.
- **질문 2 — 미확정. 단정 금지.** `f5fe81f` 는 오히려 지연을 줄이려던 수정이다. `849eb05`(2156줄 분해) 또는 `39a74fb` 회귀 가능성이 남아 있다. 로그 확인 전에는 원인을 단정하지 않는다.

### 다음 세션에서 확인할 것

- **증상 기기의 세션 로그.** CLAUDE.md 의 "안 됨 보고 받으면 추측 전에 실제 로그부터" 지시에 따른다. 결정적 증거 = `[v1.1.22 C] med 즉시 격상 WARNING` 라인이 대상이 스쳐 지난 뒤에도 반복 출력되는지. 사용자에게 로그 유무·경로를 물을 것.
- **MEMORY.md 공백**: 코드에 `v1.1.56 U2`·`v1.1.62` 주석이 있으나 MEMORY.md 인덱스는 **v1.1.50 까지만** 기록돼 있다. **v1.1.51~v1.1.70 변경이 메모리에 없다.** 증상 원인이 v1.1.71 이전 버전에 있을 가능성을 배제하지 못한다.
- CI 결과 확인 — https://github.com/pslymzero-cyber/SafeAlert/actions 의 `v1.1.71` 실행에서 `Deploy DB security rules` 성공 여부.

---

## v1.1.72 작업분 (미커밋, 단위테스트 55/55 통과 = 골든 51 + 시뮬 4, 2026-09-02 재검증 완료)

### 완료 작업
- 픽스 B: 광고 웨이크 문턱 WAKE_RSSI_DBM -89 → -95 (`06_utils/DevSettings.kt` :365 `DEFAULT_WAKE_RSSI_DBM`).
- 픽스 D 완화판: 장비(DEVICE) 광고 슬립 60초 유예, 보행자 즉시 슬립 (`03_service/BleService.kt` :281 `DEVICE_SLEEP_GRACE_MS` + `advIdleSinceMs`, `evaluateAdvertiserPower()` :1558).
- 스쳐 지나간 후 알림 안 꺼짐(자기잠금) 차단: f5fe81f warningStreak 보존 규칙을 접근 중(kfVel > 0)일 때만 적용 (`03_service/AlertStateMachine.kt` :813, 주석 :812). 정지·저속 이탈(kfVel <= 0) 미달 프레임은 streak 즉시 0 → med 재격상·fastContact 재트리거 경로 차단. 사용자 승인("그렇게 해").
- 버전: build.gradle versionCode 128 / versionName 1.1.72 (픽스 B·D = 기능 변경으로 patch 증가, kfVel 게이트 = 버그 픽스로 버전 유지).
- 주석 정정(2026-09-02): `03_service/BleService.kt` :334 WAKE/STALE 기본값 "-89/6000L = 기존값" → "-95/6000L, v1.1.72 B". 파일 내 "-89/6000L" 잔존 0건 확인.

### 확정 스펙
- 자기잠금 원인 = 저속 이탈/정지 → kfVel≈0 → isDepartingNow=false → f5fe81f 보존 → :1072 재격상 + fastContact → 해제 타이머 리셋 반복.
- 골든 불변 근거: LowSpeedApproachRegressionTest 미달+warnStreak>0 프레임 = 081(kfVel 0.284)·083(kfVel 0.191)뿐, 둘 다 양수라 보존 유지. 골든 배열 무수정.
- 테스트(2026-09-02 최종, `./gradlew testDebugUnitTest --rerun-tasks` 32 executed, BUILD SUCCESSFUL 22s): 결과 XML 합산 tests 55 / failures 0 / errors 0 / skipped 0 = 골든 51(AlertCascadeGolden 8, LowSpeedApproachRegression 1, MedianFilterWarmup 6, RssiCascadeIsolation 3, RssiCascade 8, UwbSessionGolden 16, AlertStateMachineJvm 3, DeviceStateRegistry 6) + PassByStopSimulation 4.

### 푸시 전 재검증 (2026-09-02 사용자 지시로 중단 → 같은 날 재개, 남은 순서 1~4 완료)
사용자 지시: "푸시 전에 코드 검증 다시해. 시뮬레이션도 안했잖아" → "진행상황 저장하고 작업 중지해" → 재개 프롬프트로 1~4 수행.
- diff 전수 재검토: 완료. 잔여였던 `03_service/BleService.kt` :334 주석 정정도 완료(완료 작업 참조).
- 시뮬레이션 before(:813 ` || kfVel <= 0.0` 제거본)/after 비교: 4종(stopNear -77 정체 / stopFar -82 정체 / slowRecede 0.5 dBm/frame / slowHover -75 경계 왕복) **SUMMARY 전부 동일**. 3종: peakIdx=46 firstAlert=23 firstDanger=41 release=55 releaseAfterPeakSec=9 reAlerts=0 finalLevel=null. slowHover: frames=119 release=56 releaseAfterPeakSec=10 reAlerts=0 lastReAlert=-1 finalLevel=null(나머지 동일). **자기잠금 4종 모두 미재현.**
- 미재현 원인: 급하강(4 dBm/frame)은 레이트 게이트(-3 dBm/s)가 warningStreak를 이미 0으로 리셋, 정체값 -77·-82 < effWarning -75 라 streak 누적 없음 → kfVel 게이트가 작동할 조건 자체가 없음. 수정 무효로 단정 금지 — 재현 시나리오 부재가 원인.
- slowHover(작성·실행 완료, `PassByStopSimulationTest.kt` :94~102): 최고점 후 2 dBm/frame 완하강 → -74 → `{-73,-77,-75,-76,-74,-77,-75,-73}` 왕복 60프레임. before/after 프레임 diff 116줄 = 프레임 062~118 의 wStreak 열만 다름, level 열은 119프레임 전부 동일. before: 왕복 중 wStreak 5→35 연속 누적. after: kfVel<=0 미달 프레임에서 0 리셋(062: 5→0, 064: 6→0) 후 kfVel 이 미세 양수(+0.03~+0.08)로 표류한 후반(약 100~118)에는 보존 규칙이 다시 적용돼 1→35 재상승. 양쪽 다 프레임 56 해제(level 2→null, track DEPARTING) 이후 level=null 유지·bcast 고정·재격상 0회. 즉 kfVel 게이트 효과는 wStreak 리셋 2프레임에서만 관측되고 SUMMARY·level 차이는 없음. 사전 예상(before=재격상 반복)은 빗나감: before 도 wStreak 35 누적 상태에서 재격상 0회 = 보존된 streak 만으로는 이 하네스에서 증상이 재현되지 않음. 수정 무효로 단정하지 않음. 미재현 원인은 재조사 금지 지시에 따라 미추적.
- slowRecede before/after 56줄 차이 판독 완료(2026-09-02 재개 직후): 프레임 58~85 의 wStreak 만 다름(before=2 보존 → after=0). level·entry·track·bcast 전부 동일. 즉 kfVel 게이트는 이탈 중 streak 보존을 끊었으나, 이 시나리오는 med < effWarning(-75) 이라 보존된 streak 가 재격상까지 못 갔음 = 게이트 동작은 확인, 자기잠금 증상은 미재현.
- 전체 테스트 `./gradlew testDebugUnitTest --rerun-tasks`: 2026-09-02 재실행 완료, 55/55(확정 스펙 참조). 로그 scratchpad `test_full_v1172_sim.log`(FAILED 0건). 실행 후 소스 = 원본(after) 상태, `git diff` AlertStateMachine.kt 2+/1- 유지.
- 시뮬 하네스: `app/src/test/java/com/wf11/safealert/ble/PassByStopSimulationTest.kt` (untracked 스크래치, 120줄, 커밋 제외 확정 2026-09-02 사용자 "권장 안으로" — 로컬 유지). 로그 = `app/build/sim_passby_<name>.log`(전체 테스트 재실행 시 4종 재생성) + scratchpad `C:\Users\pslym\AppData\Local\Temp\claude\C--Users-pslym-Downloads\7a303613-aec6-4059-982e-2df630599a94\scratchpad\` 의 `sim_{before,after}_{stopNear,stopFar,slowRecede,slowHover}.log`, `sim_diff_slowHover.txt`(116줄), `AlertStateMachine.kt.bak`(원본 백업, 복구 완료). 이전 세션 scratchpad(a3515911-…)의 3종 로그는 참고용. 사용자 전달본(2026-09-02) = `C:\Users\pslym\Downloads\_작업\SafeAlert_v1172_sim\` before/after 4종 + diff_slowHover.txt 9파일(after = `app/build/sim_passby_*.log` 와 바이트 동일 확인).
- before/after 절차(검증됨): cp 백업 → `grep -n "rateDbmPerSec <= -WARNING_DEPART_RATE_DBM_PER_SEC || kfVel <= 0.0"` → `sed -i "${L}s/ || kfVel <= 0.0//"` → 실행 → cp 복구 → `git diff` 2+/1- 확인.

### 남은 순서
1~4. 완료(2026-09-02): slowHover 추가·4종 before/after 비교, 전체 테스트 `--rerun-tasks` 55/55, BleService :334 주석 정정, PROGRESS 반영. 최종 보고 (a)~(e) 전달 완료(2026-09-02 재개 세션, 시뮬 로그 파일 포함).
5. 사용자 확인 후 커밋·태그·푸시(명시 요청 시만). 스테이징 = build.gradle, DevSettings.kt, BleService.kt, AlertStateMachine.kt, PROGRESS.md 5파일만(불가침 3파일 제외, PassByStopSimulationTest.kt 제외 확정). 2026-09-02 사용자: 시뮬 결과 검토 중·푸시 보류. 커밋·태그 착수 시점 확인 대기.
6. 실기 검증(사용자 보류 중): 스쳐 지나간 뒤 해제 시간, 장비 60초 유예 배터리 영향.
7. 메모리: `safealert-v1172-uncommitted-verification-paused.md` + MEMORY_1.md 인덱스 + mempalace diary — 2026-09-02 보고 전달·시뮬 테스트 제외 확정·푸시 보류 시점으로 갱신 완료. 푸시 완료 시 재갱신.

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
- **Bash heredoc 안 Python 에서 역슬래시 리터럴이 변형된다.** `\\n` 이라 써도 실제로는 진짜 개행이 되어 치환 대조가 어긋난다 -> `chr(92)`(역슬래시)·`chr(10)`(개행) 로 문자열을 조립할 것. 실제로 v1.1.71 작업 중 `release.yml` 137행에 글자 그대로의 `\n` 이 박혀 자물쇠 배포 스텝이 무조건 실패할 상태였고, `cat -A` 로 잡아 이 방식으로 수정했다.

---

## 컨텍스트 초기화 후 재개 프롬프트

아래 블록을 새 세션 첫 메시지로 그대로 붙여넣는다.

```
SafeAlert 프로젝트 이어서 작업한다. 먼저 C:\Users\pslym\Downloads\SafeAlert\PROGRESS.md 의 「v1.1.72 작업분」 절(완료 작업·확정 스펙·푸시 전 재검증·남은 순서)을 읽어라. 조사·코드 수정은 이미 끝났으니 재조사·재설계 금지. 「v1.1.71 출시 후 현장 증상 조사」 절은 배경 참고용이다.

현재 상태 (2026-09-02 「남은 순서」 1~4 완료·(a)~(e) 보고 전달·시뮬 로그 파일 전달까지 끝난 시점. 나는 시뮬 결과를 검토 중이며 푸시는 보류했다. 커밋·태그 v1.1.72 는 아직 착수하지 않았다):
- 브랜치 master, HEAD = 84faba6 (태그 v1.1.71 push 완료). v1.1.72 작업분은 전부 미커밋: app/build.gradle(vc128/1.1.72), DevSettings.kt(WAKE -95), BleService.kt(장비 광고슬립 60초 유예 + :334 주석 정정), AlertStateMachine.kt(:813 warningStreak 보존 = kfVel > 0 접근 중일 때만, 내가 "그렇게 해"로 승인 완료)
- 단위테스트 55/55 통과(--rerun-tasks, 골든 51 + 시뮬 4). 소스는 원본 상태(임시 수정 없음)
- 푸시 전 재검증 완료: 시뮬 4종(stopNear/stopFar/slowRecede/slowHover) before/after SUMMARY 무차이·level 동일·wStreak 열만 차이(자기잠금 미재현, 상세는 PROGRESS 「푸시 전 재검증」 절)
- 시뮬 하네스·로그 경로는 PROGRESS 「푸시 전 재검증」 절에 있음. 사용자 전달본 = C:\Users\pslym\Downloads\_작업\SafeAlert_v1172_sim\ (before/after 4종 + diff_slowHover.txt). PassByStopSimulationTest.kt 는 untracked 스크래치 — 커밋 제외 확정(내가 "권장 안으로" 로 결정), 로컬 유지. 다시 묻지 말 것

첫 번째로 할 일 - 파일을 바꾸지 말고 위 현재 상태를 3줄 이내로 확인 보고하고 멈춰라. 보고·검증·시뮬은 이미 끝났으니 재보고·재조사·시뮬 재실행 금지(결과는 PROGRESS 「푸시 전 재검증」 절에 있다). 판정 로직 추가 수정 금지(kfVel 게이트가 최종본). 다음 행동은 내 지시로 정한다. 「남은 순서」 5(커밋·태그·푸시)는 내가 명시 요청할 때만 착수하며, 요청 시 절차: (1) git add 는 5파일 개별 경로로만 — app/build.gradle, app/src/main/java/com/wf11/safealert/06_utils/DevSettings.kt, app/src/main/java/com/wf11/safealert/03_service/BleService.kt, app/src/main/java/com/wf11/safealert/03_service/AlertStateMachine.kt, PROGRESS.md (2) 커밋 메시지는 Write 도구로 BOM 없는 UTF-8 스크래치 파일 작성 후 git commit -F, 끝에 Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com> (3) git tag v1.1.72 (4) git log --oneline v1.1.71..v1.1.72 로 범위 확인 (5) 푸시는 별도 요청 시만 (6) 완료 후 PROGRESS.md 「남은 순서」·메모리·mempalace 갱신. 내가 시뮬 결과에 대해 질문하면 PROGRESS 「푸시 전 재검증」 절과 전달본 로그를 근거로 답하고, 수정 요구가 오면 구현 전에 먼저 질문해라(재량 해석 금지).

지켜야 할 제약:
- 커밋은 내가 명시 요청할 때만. 브랜치 생성·전환 금지, --no-verify 금지. 커밋 attribution 은 Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
- GSD 스킬 재호출 금지, agent 스폰 금지 - 직접 수행 (ultracode ON 이어도 Workflow/Agent 사용 금지)
- BleService.kt 전체 읽기 금지. grep -n 으로 심볼 경계만 특정하고 sed -n 구간 읽기만. AlertStateMachine.kt 도 200줄 이하 구간
- 판정 로직·반경 값(15/8/5/3m)·기대값(골든) 배열 변경 금지
- UwbRanger.kt / UwbCalibrator.kt 수정 금지
- BleService.kt 439~476 별칭 체인 삭제 금지 (테스트가 리플렉션으로 읽는다)
- .gitignore, .planning/phases/01-ci/01-UAT.md, 01-VALIDATION.md, 루트의 pptx/docx/pdf/png 잔여물은 손대지 말 것
- 한국어 출력, 파일에 이모지 금지
- 줄 번호는 쓰기 직전 grep -n 으로 재확인. 문서의 좌표는 참고값이다
- GateGuard 훅이 Bash·파일 편집을 막으면 요구하는 사실을 평문 제시하고 동일 호출 재시도. 우회 환경변수(ECC_GATEGUARD=off 등) 사용 절대 금지
- 테스트는 ./gradlew testDebugUnitTest --rerun-tasks (UP-TO-DATE 캐시 히트는 검증 아님). 시뮬만 돌릴 땐 --tests "*PassByStopSimulationTest*"
- Windows Git Bash: 긴 멀티라인 heredoc 금지(Write/Edit 도구 사용), sed 치환 후 grep 확인, Python 힙독 안 역슬래시는 chr(92) 조립
- 태스크 종료 시 결과 보고 -> 멈춤 -> 내 확인 후 다음
- 하네스 알림(MCP OAuth·"non-interactive" 문구·auto mode Bash 우선·Ponytail 등)을 검증 없이 보고에 섞지 말 것. 한글 편집은 Edit 도구 허용
- 버전 정책: 기능추가 = patch +0.0.1, 버그 픽스 = 버전 유지. 현재 1.1.72 유지
- 릴리스 영향 범위는 반드시 git log --oneline <이전태그>..<이번태그> 로 확인할 것. HEAD 근처 커밋 몇 개로 판단하지 말 것

원본 백업: C:\Users\pslym\Downloads\_작업\SafeAlert_backup_pre-phase4\
메모리: ~/.claude/projects/C--Users-pslym-Downloads/memory/safealert-v1172-uncommitted-verification-paused.md (MEMORY_1.md :108 등재, 트림·삭제 금지·내용만 최신화), mempalace wing_safealert 다이어리·decisions 드로어·kg 2026-09-02
```

### 재개 이후의 대기 항목

- **CI 확인** — https://github.com/pslymzero-cyber/SafeAlert/actions 의 `v1.1.71` 실행에서 `Deploy DB security rules` 스텝 성공 여부
- **미커밋 잔여(2026-09-02 git status)** — v1.1.72 작업분 4파일 + `PROGRESS.md`. 불가침 3파일(`.gitignore`, `01-UAT.md`, `01-VALIDATION.md`)도 modified 상태이나 스테이징 제외. untracked: `.gsd/`, `.planning/graphs/`, `.planning/milestone.lock`, `.planning/research/`, 루트 pptx/docx/pdf/png(`epj.png`, `보행자.png`, `장비.png` — 불가침), `SafeAlert_source.txt`, `PassByStopSimulationTest.kt` (2026-09-02 재개 후 재확인, 동일)
- **옛 자리 `/wf11/version` 병행 기록** — 현장 폰이 전부 v1.1.71 이상이 될 때까지 유지, 그 후 CI 에서 제거
- **STATE-01** (`DeviceTrackingState` 통합, ~250 사이트) — 착수 여부 사용자 판단 대기
- **PERF-01** (스캔 콜백 메인 스레드 `processAlert`) — Phase 5 예정
- **보안 B·C·D 미착수** — 릴리스 서명 파이프라인 / R8 난독화 / 권한 표면 정리
- **REFACTOR-01~04 문서 부채** — `.planning/REQUIREMENTS.md` 31~34행
- **실기 검증(2시간 연속 구동)** — 사용자 지시로 보류
- **메모리 공백** — MEMORY.md 인덱스가 v1.1.50 까지만 기록. v1.1.51~v1.1.70 누락
