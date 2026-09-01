# Phase 2: 안전 크리티컬 경로 골든 테스트 - Context

**Gathered:** 2026-08-27
**Status:** Ready for planning

<domain>
## Phase Boundary

경보 격상·해제 전 경로와 UWB 세션 전환의 현재 동작을 기대값으로 고정하고, 그 안전망이 저속 접근 WARNING 미도달 버그(BUG-02)를 실제로 잡아낸다. Requirements: TEST-01, TEST-02, BUG-02. Mode: mvp.

- TEST-01: 고정 RSSI 시퀀스 재생 시 SAFE→WARNING→DANGER 격상과 역방향 해제의 등급·전이 시점이 기대값과 일치하고, 판정 상수를 건드리면 테스트가 실패한다.
- TEST-02: UWB 세션 6대 이하 범위에서 Case A/B 전환·신선도 게이트·재연결 경로가 기대값과 일치한다. 6대 초과 플립은 BUG-03 으로 v2 이월(수정 없이 고정하면 CI 상시 빨간색 또는 버그의 스펙 승격).
- BUG-02: 약한 콜드스타트 RSSI 에서 저속 접근하는 시퀀스가 WARNING 에 도달하며 골든에 회귀 케이스로 남는다. 현장에서 지게차가 천천히 다가올 때 경고가 뜨는 것을 사용자가 확인한다.

출하: 현장 관측 가능한 수정 1건(저속 접근 WARNING 도달)이 포함된 APK 를 배포한다. 나머지는 테스트 추가이므로 다른 동작은 불변.

</domain>

<decisions>
## Implementation Decisions

### processAlert 시임 (테스트 하네스)

- **D-2A: Robolectric 도입** — processAlert 를 JVM 유닛 테스트에서 호출하는 방법. BleService 는 LifecycleService 라 android.jar stub 때문에 순수 JVM 에서 인스턴스화 불가. 순수 함수 추출은 1,138줄 함수를 Phase 2 에서 건드리는 것이라 Phase 3(분해) 범위를 앞당기고, 계측 테스트는 CI 에 에뮬레이터가 필요하다. 프로덕션 영향 0줄(app/build.gradle 테스트 의존성 추가만).
- **D-2B: ServiceController.get() — onCreate 미실행** — processAlert 구간에 lateinit var 0건, 상태맵 17종은 전부 필드 초기화자로 생성되므로 onCreate 없이 안전하다. onCreate 는 registerReceiver 4건·알림채널·loadEchoPriors(Firebase)·ImuFusion.init 를 실행해 테스트를 오염시킨다. uwbRanger/bleScanner 는 nullable 이라 null 로 남는다 — Case A 골든은 D-4B 로 주입.
- **D-2C: nowMs 기본인자 시임 1건** — Robolectric 은 System.currentTimeMillis() 를 제어하지 못한다(JVM 네이티브). 실측 결과 processAlert 의 벽시계 제어점은 1422행 `val now` 단 1곳이며 함수 전체(스로틀·streak·dwell)가 이 지역변수를 쓴다. elapsedRealtime 5회는 전부 KF_VEL_SEED_TTL_MS 자기 차분이라 ShadowSystemClock 으로 이미 통제된다. Phase 1 D-01(KalmanFilter nowMs 기본인자)과 동일 패턴. 프로덕션 영향: 시그니처 1건 + 1512행 1줄(D-2H). 호출부(BleService.kt:1115) 무변경, 런타임 동작 변화 0.
- **D-2D: DevSettings 31개 값 전부 명시** — DevSettings 프로퍼티는 var 87 / val 1 이라 31개 명시가 물리적으로 가능(유일한 val beaconGainDbm 은 beaconGainPercent 로 간접 세팅). 골든이 자족적이어서 파일만 읽어도 어떤 구성의 동작을 고정했는지 알 수 있고, 기본값 변경에 면역. 부작용 무해화: vibrationEnabled=false·soundEnabled=false 가 진동/소리 12회를, autoSaveAlerts=false 가 FirebaseManager.saveAlert 2회를 차단, canDrawOverlays() 기본 false 가 오버레이 9회를 조기 return — 부작용 27회 중 23회가 프로덕션 0줄로 무해화되고, 남는 6회 sendAlertBroadcast 는 관측 대상(D-2E). Known gap: 출하 기본값이 바뀌어도 골든은 그대로 통과한다(드리프트 감시는 deferred).

### 골든 관측면

- **D-2E: 판정 결과 + 중간 판정 상태** — BUG-02 회귀 시 '판정 결과만' 관측면은 SAFE 로 남았다는 사실까지만 알려주고 원인(streak 미달인지 게이트 차단인지)은 숨긴다. 반대로 필터 수치(medianValue·avgRssi)는 Phase 1 RssiCascadeTest 가 이미 프레임 단위로 동결한 영역이라 중복(deferred). 관측면:
  - 판정 결과(외부 계약): alertState[deviceId] — Pair(레벨, 진입시각), 부재 시 null. ShadowApplication.getBroadcastIntents() 의 BROADCAST_ALERT — 발생 순서·EXTRA_ALERT_LEVEL·EXTRA_ID·EXTRA_DISPLAY_NAME (processAlert 구간 호출 6곳: DANGER 1760/2299/2517, SAFE 2092/2229, WARNING 2539).
  - 중간 판정 상태: trackingStateMap·dangerContactStreakMap·warningContactStreakMap·fastApproachStreakMap (ReflectionHelpers), kalmanFilters[deviceId].estimatedVel (public 게터, 비교 델타 1e-9).
  - 프로덕션 영향 0줄. Known gap: Phase 3 분해에서 상태맵 재배치 시 리플렉션 접근도 같이 수정 — 판정 결과 2종은 외부 계약이라 면역.
- **D-2F: 매 프레임 전체 기록** — Phase 2 의 목적이 격상·해제 타이밍을 못 박기다. 종단 상태만 보면 '결국 올라가긴 했지만 5초 늦었다'가 통과하고, 전이 프레임만 보면 전이 판정 자체가 망가진 회귀가 기록에서 사라진다. Phase 1 RssiCascadeTest 의 20프레임 전체 시계열 패턴과 형태 일관.
- **D-2G: alertState 진입시각 = t0 기준 상대 오프셋** — 레벨 진입 시각은 dwell 뮤트 계산의 근거라 동결 가치가 있다(재진입 시 시각 리셋으로 dwell 뮤트가 영영 안 걸리는 류의 회귀). 절대 ms 는 시나리오 시작 시각 상수를 바꾸면 모든 골든이 깨진다. 기록 규칙: t0 = 시나리오 첫 프레임에 시임 시계가 반환한 값, 기록값 = (진입시각 - t0) ms, alertState 부재 프레임은 null.
- **D-2H — BleService.kt:1512 의 두 번째 System.currentTimeMillis()(lastApproachAtMs 기록)도 nowMs 로 통일** — '시임 = processAlert 안의 벽시계 전부' 규칙이 예외 없이 성립해야 나중에 lastApproachAtMs 를 읽는 로직이 함수 안으로 들어와도 골든이 조용히 깨지지 않는다. 기본인자가 System.currentTimeMillis() 이므로 런타임 동작 변화 0. lastApproachAtMs 자체는 관측면에 넣지 않는다 — 읽기가 isDangerPresent 절전 게이트뿐이라 processAlert 판정에 되먹임되지 않는다.

### BUG-02 순서

- **D-3A: 골든 먼저 → 수정 → 재동결** — 수정 커밋의 골든 diff 가 곧 '무엇이 어떻게 달라졌는가'의 증거. 저속 시나리오 외의 값이 함께 움직였는지도 같은 diff 에서 드러나 D-3D 판정 근거가 자동으로 생긴다. Phase 1 D-09 record-then-freeze 와 동형. 순서: (1) 저속 접근 골든을 현재(버그 살아있는) 동작으로 기록·커밋 (2) BUG-02 수정 (3) 저속 골든 재동결 — diff 가 수정 효과 (4) 다른 골든이 함께 움직였으면 D-3D 로 판정. 수용 비용: 중간 커밋 1개가 버그 동작을 기대값으로 담은 채 저장소에 남는다.
- **D-3B: 근본 원인은 골든이 규명한다** — PROJECT.md:77 의 진단(injectWarmup:1454 프리셋 최소값 포화)이 코드 실측과 불일치: injectWarmup 본체(KalmanFilter.kt:139-148)는 pRR=25.0·pRV=0.0·pVV=5.0 상수만 세팅하고 프리셋을 참조하지도 최소값 클램프를 걸지도 않는다(프리셋이 정하는 값은 q·R 뿐). **BUG-02 근본 원인은 현재 미확정 — PROJECT.md:77 문구는 가설로만 취급한다.** 1단계 골든의 프레임 표가 plan 단계 research 의 입력이 된다. research 는 원인을 처음부터 찾는 게 아니라 표가 가리키는 지점을 확인하고 수정안을 도출한다.
- **D-3C: 합성 시퀀스 — 인라인 상수** — 시나리오 형태: 약한 콜드스타트(-95dBm 근방) 시작 + 프레임당 약 0.1~0.3dBm 상승. 현장 지게차 0.5m/s 가 15m→8m 로 접근하는 약 14초 ≈ 117프레임(FRAME_DT_MS=120) 구간에 대응. 정확한 기울기·프레임 수는 plan 단계에서 확정. Known gap: 합성 시퀀스가 실제 현장 버그를 재현하지 못할 가능성 — 재현 실패 시 기울기·시작값을 조정해 하한을 탐색한다.
- **D-3D: 기존 골든 불변 = 수정 범위 게이트** — BUG-02 수정 후 저속 접근 골든 외의 골든이 한 값이라도 바뀌면, 재동결하지 말고 수정안을 다시 좁힌다. Escape hatch: 원인이 공유 경로(예: 칼만 초기 공분산)라 국소화가 물리적으로 불가능한 경우에만 예외 — 그때는 재동결 전에 사용자에게 왜 국소화가 불가능한지, 어느 값이 왜 움직였는지 보고하고 판단을 받는다.

### UWB TEST-02 범위

- **D-4A: '좀비 워치독' = 현행 신선도 게이트로 해석** — UWB_LINK_ZOMBIE_MS 심볼은 BleService·UwbRanger 양쪽 grep 0건. v1.1.43/44 의 무표본 철거는 마지널 신호 페어에서 철거(1s)→재합류(250ms)→재철거 플랩과 컨트롤러 철거 시 세션 연쇄 붕괴를 일으켜 v1.1.46 이 폐지했다(BleService.kt:2545-2553 에 사유 기록). 좀비 차단이라는 기능적 목적은 v1.1.50 freshUwbDistM 통일까지 살아있다 — 사라진 것은 '세션을 철거한다'는 수단이지 '낡은 실측으로 판정하지 않는다'는 보장이 아니고, 골든이 고정할 것은 후자다. 골든화 범위: (a) 표본이 UWB_MEAS_FRESH_MS(1000ms) 넘게 끊기면 Case A → Case B 강등 (b) freshUwbDistM 이 null 을 돌려 RSSI 폴백 (c) 낡은 근거리 실측(예: 2m)이 남아있어도 좀비 DANGER 미발생. 프로덕션 0줄. Follow-up: REQUIREMENTS.md TEST-02 각주 — 철거 워치독은 v1.1.46 폐지, v1 골든은 그 자리를 대신하는 신선도 게이트를 덮는다.
- **D-4B: 진짜 UwbRanger + initSession 미호출** — UwbManager 는 생성자가 아니라 initSession() 안에서만 생성된다(UwbRanger.kt:137 null 초기화, :185 createInstance). 생성만 하면 UWB 하드웨어·권한을 전혀 건드리지 않는다. 주입: ReflectionHelpers.setField(service, "uwbRanger", UwbRanger(...)) 후 public 맵 uwbDistances(UwbRanger.kt:123)에 실측 직접 주입, uwbSampleAtMsMap 은 ReflectionHelpers 로 세팅. 기각: 인터페이스 추출(필드 타입 변경 필요, 이득 없음)·mockk(final class 인라인 에이전트 필요, 의존성 대비 이득 없음). 프로덕션 0줄. Known gap: UwbRanger 생성자가 CoroutineScope 를 받는다 — initSession() 미호출 시 코루틴이 시작되지 않아야 하며, 이 사실은 plan 단계 task 에서 확인한다.
- **D-4C: 낡은 표본 = 과거 오프셋 시각 주입** — freshUwbDistM(BleService.kt:2566-2570)의 System.currentTimeMillis() 는 Robolectric 이 제어 불가하지만 판정이 보는 것은 (now - sampleAt) 차이뿐이다. 낡음: uwbSampleAtMsMap[id] = 현재 - 2000L → Case A 불성립 → RSSI 폴백. 신선: uwbSampleAtMsMap[id] = 현재 → Case A 성립 → judgeUwbOnly (테스트 1회 실행 1s 미만). 기각: nowMs 시임을 freshUwbDistM·uwbJudgeModeExclusive 에도 넣는 안 — 프로덕션 2건 시그니처 변경 대비 얻는 게 없다. Known gap: 실행이 극단적으로 느려져 신선 케이스가 1s 를 넘으면 플레이키 — 마진이 필요하면 plan 단계에서 sampleAt 미래 오프셋을 검토한다.
- **D-4D: 재연결 경로는 BleService 경계까지만** — BLE 타임아웃 경로(BleService.kt:1171-1173)에서 uwbRanger.onDeviceLost(id) 호출·uwbSampleAtMsMap 항목 제거·다음 판정의 Case B 강등까지 검증. 제외: UwbRanger 내부 scheduleRestartLocked(REJOIN_DELAY_MS=250L, 호출 7곳: UwbRanger.kt:233,405,476,499,601,804,811)의 코루틴 재시작 — 실제 세션 재개설은 안드로이드 UWB 스택이 있어야 확인되고, '스케줄이 걸렸다'는 사실만으로는 회귀를 잡는 힘이 약한 반면 코루틴 테스트 디스패처 도입 비용은 실재한다. 프로덕션 0줄.

### Claude's Discretion

없음 — 논의된 16개 결정 모두 사용자가 옵션을 직접 선택했다. 위 결정의 "plan 단계에서 확정/확인" 표기 항목(D-3C 기울기·프레임 수, D-4B 코루틴 미시작 확인, D-4C 신선 마진)만 planner 재량이다.

</decisions>

<specifics>
## Specific Ideas

- 골든 형식은 Phase 1 RssiCascadeTest 를 승계한다: 인라인 intArrayOf/doubleArrayOf 상수 + 프레임 시계열 + 프레임·단계 라벨 붙은 assertEquals + Kalman 비교 델타 1e-9. Android 무접촉(하네스 제외).
- record-then-freeze: 골든 기대값은 현재 동작을 1회 기록해 동결하고, 재동결은 수동으로만 한다(Phase 1 D-09).

</specifics>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase 1 선례·CI 게이트
- `.planning/phases/01-ci/01-CONTEXT.md` — D-01 KalmanFilter nowMs 기본인자 시임(D-2C 의 선례). D-03 은 Phase 1 한정 문구라 Phase 2 의 새 시임은 별도 승인 사항.
- `app/src/test/java/com/wf11/safealert/ble/RssiCascadeTest.kt` — Phase 1 골든 패턴(record-then-freeze, 수동 재동결만). 형식 상세는 specifics 참조.
- `.github/workflows/release.yml` :65 — MIN_TOTAL=17. 골든을 추가하면 같이 올려야 한다.

### processAlert·관측면
- `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` :1406-2543 — processAlert 본체 1,138줄. :1422 벽시계 유일 제어점(val now), :1512 lastApproachAtMs(D-2H), :1115 호출부.
- `BleService.kt` :2042 — 'broadcastDeviceList() 가 alertState 전체를 한 번에 송출(단일 진실 공급원)' 주석. alertState 를 판정 결과 관측면으로 삼는 근거.
- `BleService.kt` :3610 — sendAlertBroadcast 정의(EXTRA_ID·EXTRA_ALERT_LEVEL·EXTRA_DISPLAY_NAME).
- `app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt` :63-68 — estimatedRssi/estimatedVel/isInitialized/updateCount public 게터. kfVel 은 리플렉션 없이 읽힌다.
- `app/src/main/java/com/wf11/safealert/06_utils/OverlayManager.kt` :141 — showSidebar 첫 줄 canDrawOverlays 게이트(D-2D 부작용 무해화 근거).
- `app/src/main/java/com/wf11/safealert/06_utils/DevSettings.kt` :63-66 — lateinit prefs / init(context).

### 칼만·BUG-02
- `KalmanFilter.kt` :139-148 — injectWarmup 본체. 프리셋 미참조·클램프 없음(D-3B 진단 불일치 근거).
- `KalmanFilter.kt` :26-27,44-58 — 프리셋 FAST q=0.50/R=2.0, NORMAL q=0.15/R=5.0, SMOOTH q=0.05/R=10.0.
- `BleService.kt` :1454 — KalmanFilter(DevSettings.kalmanPreset).apply { injectWarmup(inputRssi, seedVel) }.
- `.planning/PROJECT.md` :77 — BUG-02 진단 원문(가설로 취급). `.planning/ROADMAP.md` :55-75 — Phase 2 정의·성공기준 4.

### UWB Case A/B
- `BleService.kt` :2554-2560 — uwbJudgeModeExclusive. Case A 성립 4조건.
- `BleService.kt` :2566-2570 — freshUwbDistM. System.currentTimeMillis() 직접 호출(시임 없음) — D-4C 근거.
- `BleService.kt` :680,682 — uwbSampleAtMsMap(private), UWB_MEAS_FRESH_MS = 1_000L.
- `app/src/main/java/com/wf11/safealert/06_utils/UwbRanger.kt` :123,137,185 — uwbDistances public ConcurrentHashMap / uwbManager private var null / createInstance 는 initSession() 안에서만 — D-4B 근거.
- `UwbRanger.kt` companion — MULTICAST_MAX = 6(TEST-02 6대 이하 한정의 코드 근거), REJOIN_DELAY_MS = 250L.
- `BleService.kt` :2545-2553 — v1.1.46 이 철거 워치독(UWB_LINK_ZOMBIE_MS)을 폐지한 사유 — D-4A 근거.
- `BleService.kt` :1171-1173 — BLE 타임아웃 경로: uwbRanger?.onDeviceLost(id) + uwbSampleAtMsMap.remove(id) — D-4D 범위 경계.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- RssiCascadeTest.kt: 골든 테스트 형식의 원형 — 새 골든은 이 형식을 그대로 승계한다.
- KalmanFilter public 게터(estimatedVel 등): kfVel 관측을 리플렉션 없이 해결.
- UwbRanger.uwbDistances (public ConcurrentHashMap): Case A 실측 주입 통로 — 인터페이스 추출·mockk 불필요.
- DevSettings var 87개: 골든 하네스가 31개 값을 전부 명시 세팅 가능(유일한 val beaconGainDbm 은 beaconGainPercent 로 간접).

### Established Patterns
- nowMs 기본인자 시임(Phase 1 D-01): 기본값 System.currentTimeMillis() 로 런타임 동작 변화 0을 보장하는 시계 주입 — D-2C/D-2H 가 동일 패턴.
- record-then-freeze(Phase 1 D-09): 현재 동작 1회 기록·동결, 수동 재동결만 — D-3A 의 골든 먼저 → 수정 → 재동결이 동형.
- 프로덕션 0줄 원칙: 16개 결정 중 프로덕션 코드 변경은 D-2C+D-2H 의 시그니처 1건+1줄이 전부. 나머지는 전부 테스트 측(리플렉션·주입·설정)에서 해결.

### Integration Points
- app/build.gradle: Robolectric 테스트 의존성 추가(D-2A) — 프로덕션 의존성 무변경.
- .github/workflows/release.yml:65: 골든 추가 수만큼 MIN_TOTAL=17 상향.
- .planning/REQUIREMENTS.md: TEST-02 각주 추가(D-4A follow-up).

</code_context>

<deferred>
## Deferred Ideas

- 명시값 == 현행 DevSettings 기본값 을 검사하는 드리프트 감시 테스트(영역 1 Q4 옵션 3). Phase 2 범위(TEST-01·TEST-02·BUG-02) 밖의 새 감시 기능이고 MIN_TOTAL 상향이 1건 더 늘어난다. 기본값 변경이 곧 안전 동작 변경이었던 이력(v1.1.46 반경 슬라이더, v1.1.49 게이트 재도입)이 있어 가치는 있으나 Phase 2 에서는 하지 않는다.
- medianValue·avgRssi 를 관측면에 추가(영역 2 Q1 옵션 3). Phase 1 RssiCascadeTest 가 같은 값을 이미 프레임 단위로 동결하고 있어 중복이다. RssiCascadeTest 가 걷히거나 필터 단이 재배치되면 재검토한다.

</deferred>

---

*Phase: 02-golden*
*Context gathered: 2026-08-27*
