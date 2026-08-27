# Phase 2: 안전 크리티컬 경로 골든 테스트 - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-27
**Phase:** 02-golden
**Areas discussed:** processAlert 시임, 골든 관측면, BUG-02 순서, UWB TEST-02 범위

---

## processAlert 시임

### processAlert 를 JVM 유닛 테스트에서 어떻게 호출할 것인가

| Option | Selected |
|--------|----------|
| Robolectric 도입 | V |
| processAlert 를 순수 함수로 추출 |  |
| 계측 테스트(androidTest)로 이동 |  |

**User's choice:** Robolectric 도입
**Notes:** BleService 는 LifecycleService 라 android.jar stub 때문에 순수 JVM 에서 인스턴스화 불가. 순수 함수 추출은 1,138줄 함수를 Phase 2 에서 건드리는 것이라 Phase 3(분해) 범위를 앞당긴다. 계측 테스트는 CI 에 에뮬레이터가 필요하다. (프로덕션 영향: 0줄 (app/build.gradle 테스트 의존성 추가만))

### 골든 테스트에서 BleService 인스턴스를 어느 수명주기까지 올릴까

| Option | Selected |
|--------|----------|
| .get() — onCreate 미실행 | V |
| .create() — onCreate 실행 |  |

**User's choice:** .get() — onCreate 미실행
**Notes:** processAlert 구간에 lateinit var 0건, 상태맵 17종은 전부 필드 초기화자로 생성되므로 onCreate 없이 안전하다. onCreate 는 registerReceiver 4건·알림채널·loadEchoPriors(Firebase)·ImuFusion.init 를 실행해 테스트를 오염시킨다. (프로덕션 영향: 0줄)

### 골든이 재현 가능하려면 processAlert 의 벽시계(now)를 어떻게 고정할까

| Option | Selected |
|--------|----------|
| nowMs 기본인자 시임 1건 | V |
| Robolectric ShadowSystemClock 만으로 |  |
| 정적 시간 홀더 도입 |  |

**User's choice:** nowMs 기본인자 시임 1건
**Notes:** Robolectric 은 System.currentTimeMillis() 를 제어하지 못한다(JVM 네이티브). 실측 결과 processAlert 의 벽시계 제어점은 1422행 val now 단 1곳이며 함수 전체(스로틀·streak·dwell)가 이 지역변수를 쓴다. elapsedRealtime 5회는 전부 KF_VEL_SEED_TTL_MS 자기 차분이라 ShadowSystemClock 으로 이미 통제된다. Phase 1 D-01(KalmanFilter 의 nowMs 기본인자)과 동일 패턴. (프로덕션 영향: 시그니처 1건 + 1512행 1줄(D-2H). 호출부(BleService.kt:1115) 무변경, 런타임 동작 변화 0)

### 골든이 읽는 DevSettings 31개 값은 어디서 오게 할까

| Option | Selected |
|--------|----------|
| 31개 전부 명시 | V |
| init 만 — 기본값 의존 |  |
| 명시 + 기본값 드리프트 감시 |  |

**User's choice:** 31개 전부 명시
**Notes:** DevSettings 프로퍼티는 var 87 / val 1 이라 31개 명시가 물리적으로 가능하다(유일한 val beaconGainDbm 은 파생값이라 beaconGainPercent 로 간접 세팅). 골든이 자족적이어서 파일만 읽어도 어떤 구성의 동작을 고정했는지 알 수 있고, 기본값 변경에 면역이다. (프로덕션 영향: 0줄)

---

## 골든 관측면

### 골든이 매 프레임 무엇을 기록할까

| Option | Selected |
|--------|----------|
| 판정 결과 + 중간 판정 상태 | V |
| 판정 결과만 |  |
| 결과 + 상태 + 필터 수치 |  |

**User's choice:** 판정 결과 + 중간 판정 상태
**Notes:** BUG-02(저속 접근 미도달)가 회귀하면 '판정 결과만' 관측면은 SAFE 로 남았다는 사실까지만 알려주고 원인(streak 미달인지 게이트 차단인지)은 숨긴다. 반대로 필터 수치(medianValue·avgRssi)까지 넣는 것은 Phase 1 RssiCascadeTest 가 median·prefilter·kalman 3단을 이미 프레임 단위로 고정한 영역이라 같은 값을 두 테스트가 중복 동결하게 된다. (프로덕션 영향: 0줄. kfVel 은 KalmanFilter 의 public 게터(estimatedVel)로 읽고, 맵 5종은 ReflectionHelpers 로 꺼낸다)

### 관측치를 어느 밀도로 기록할까

| Option | Selected |
|--------|----------|
| 매 프레임 전체 | V |
| 상태가 바뀐 프레임만 |  |
| 시나리오 종단 상태만 |  |

**User's choice:** 매 프레임 전체
**Notes:** Phase 2 의 목적이 '격상·해제 타이밍을 못 박기'다. 종단 상태만 보면 '결국 올라가긴 했지만 5초 늦었다'가 통과하고, 전이 프레임만 보면 전이 판정 자체가 망가진 회귀가 기록에서 사라진다. Phase 1 RssiCascadeTest 가 이미 20프레임 전체 시계열 패턴이라 형태도 일관된다. (프로덕션 영향: 0줄)

### alertState 의 Long 진입시각을 골든에 어떻게 넣을까

| Option | Selected |
|--------|----------|
| t0 기준 상대 오프셋 | V |
| 레벨만, 시각은 무시 |  |
| 절대 ms 그대로 |  |

**User's choice:** t0 기준 상대 오프셋
**Notes:** 레벨 진입 시각은 dwell 뮤트 계산의 근거라 동결 가치가 있다(같은 레벨 재진입 때 시각이 리셋되면 dwell 뮤트가 영영 안 걸리는 류의 회귀). 절대 ms 를 박으면 시나리오 시작 시각 상수를 한 번만 바꿔도 모든 골든이 깨진다. (프로덕션 영향: 0줄 (테스트가 시임 시계를 소유하므로 t0 를 안다))

### BleService.kt:1512 의 두 번째 System.currentTimeMillis()(lastApproachAtMs 기록)를 nowMs 로 통일할까

| Option | Selected |
|--------|----------|
| nowMs 로 통일 | V |
| 그대로 둔다 |  |

**User's choice:** nowMs 로 통일
**Notes:** '시임 = processAlert 안의 벽시계 전부'라는 규칙이 예외 없이 성립해야, 나중에 lastApproachAtMs 를 읽는 로직이 이 함수 안으로 들어와도 골든이 조용히 깨지지 않는다. 기본인자가 System.currentTimeMillis() 이므로 런타임 동작 변화는 0이다. (프로덕션 영향: 1줄 (D-2C 의 시그니처 1건에 포함되는 후속 변경))

---

## BUG-02 순서

### BUG-02 수정과 골든 기록의 순서를 어떻게 잡을까

| Option | Selected |
|--------|----------|
| 골든 먼저 → 수정 → 재동결 | V |
| 수정 먼저 → 골든 1회 기록 |  |
| 저속 시나리오만 분리해 실패 기대로 기록 |  |

**User's choice:** 골든 먼저 → 수정 → 재동결
**Notes:** 수정 커밋의 골든 diff 가 곧 '무엇이 어떻게 달라졌는가'의 증거가 된다. 저속 시나리오 외의 값이 함께 움직였는지도 같은 diff 에서 드러나므로 D-3D(불변 게이트)의 판정 근거가 자동으로 생긴다. Phase 1 D-09 record-then-freeze 원칙과도 형태가 같다. (프로덕션 영향: 0줄 (1단계). 2단계 수정 범위는 D-3B 규명 결과에 달림)

### BUG-02 의 근본 원인을 어디서 특정할까

| Option | Selected |
|--------|----------|
| 골든이 규명한다 | V |
| plan 단계 research 가 먼저 규명 |  |
| 지금 discuss 에서 규명 |  |

**User's choice:** 골든이 규명한다
**Notes:** PROJECT.md:77 의 진단(injectWarmup:1454 프리셋 최소값 포화)이 코드 실측과 맞지 않는다 — injectWarmup 본체(KalmanFilter.kt:139-148)는 pRR=25.0·pRV=0.0·pVV=5.0 상수만 세팅하고 프리셋을 참조하지도, 최소값 클램프를 걸지도 않는다. 프리셋이 정하는 값은 q(0.50/0.15/0.05)·R(2.0/5.0/10.0) 뿐이다. 코드 정독만으로는 재현 조건을 못 좁힐 위험이 있고, D-2E 관측면(trackingState·dangerStreak·warningStreak·fastApproachStreak·kfVel)이 정확히 어느 단계에서 막히는가를 프레임 단위로 드러내도록 설계돼 있다. (프로덕션 영향: 0줄)

### 저속 접근 시나리오의 입력 RSSI 시퀀스를 어떻게 만들까

| Option | Selected |
|--------|----------|
| 합성 시퀀스 — 인라인 상수 | V |
| 실기 로그에서 추출 |  |
| Phase 1 시퀀스의 저속 변형 |  |

**User's choice:** 합성 시퀀스 — 인라인 상수
**Notes:** Phase 1 RssiCascadeTest 와 같은 intArrayOf 인라인 방식이라 형태가 일관되고 자족적이다. 결정적이라 재현 가능하고, 기울기를 바꿔가며 어느 접근 속도부터 WARNING 이 안 뜨는가라는 재현 하한을 테스트 안에서 탐색할 수 있다. 실기 로그는 해당 구간이 찍힌 로그를 먼저 확보해야 하고(현재 있는지 미확인) 프레임 단위 raw RSSI 복원 작업이 붙는다. (프로덕션 영향: 0줄)

### BUG-02 수정이 저속 외의 기존 골든 값까지 움직이면 어떻게 할까

| Option | Selected |
|--------|----------|
| 기존 골든 불변을 수정 범위 게이트로 | V |
| 영향받은 골든 재동결 허용 |  |

**User's choice:** 기존 골든 불변을 수정 범위 게이트로
**Notes:** 안전 크리티컬 앱에서 저속 경로 수정이 고속·이탈 경로 판정을 바꾸면 그것 자체가 회귀 신호다. 골든이 깨지는 것을 재동결하면 되는 일이 아니라 수정 범위가 넓다는 경보로 취급하면, 사람이 매번 판단할 필요 없이 CI 가 범위를 강제한다. (프로덕션 영향: 0줄)

---

## UWB TEST-02 범위

### TEST-02 의 '좀비 워치독 발화'가 현재 코드에 없다. 무엇을 골든화할까

**User's choice:** 현행 신선도 게이트로 해석
**Notes:** 좀비 차단이라는 기능적 목적은 v1.1.50 freshUwbDistM 통일까지 살아있다. 사라진 것은 '세션을 철거한다'는 구현 수단이지 '낡은 실측으로 판정하지 않는다'는 보장이 아니다. 골든이 고정해야 할 것은 후자다. (프로덕션 영향: 0줄)

### 골든 테스트에서 uwbRanger 를 어떻게 꽂을까

**User's choice:** 진짜 UwbRanger + initSession 미호출
**Notes:** UwbManager 는 생성자가 아니라 initSession() 안에서만 만들어진다(UwbRanger.kt:137 필드 null 초기화, :185 createInstance). 생성만 하면 UWB 하드웨어·권한을 전혀 건드리지 않는다. uwbDistances 는 public ConcurrentHashMap(UwbRanger.kt:123)이라 테스트가 실측을 직접 넣을 수 있다. 인터페이스 추출도 mockk 도입도 필요 없다. (프로덕션 영향: 0줄)

### UWB 신선도 판정의 System.currentTimeMillis()(freshUwbDistM:2569)를 Robolectric 이 제어하지 못한다. 낡은 표본 상태를 어떻게 만들까

**User's choice:** 과거 오프셋 시각 주입
**Notes:** 판정이 보는 것은 (now - sampleAt) 차이뿐이다. sampleAt 을 과거로 밀면 시계를 제어하지 않고도 낡은 표본이 만들어진다. 신선 케이스는 sampleAt = System.currentTimeMillis() 를 넣으면 자연히 통과한다 — 테스트 1회 실행은 1s 미만이다. (프로덕션 영향: 0줄)

### 재연결 경로(onDeviceLost → REJOIN_DELAY_MS 250ms 재시작)를 어디까지 검증할까

**User's choice:** BleService 경계까지만
**Notes:** 250ms 뒤 재시작이 실제 세션을 다시 여는지는 안드로이드 UWB 스택이 있어야 확인된다. 유닛 테스트가 고정할 수 있는 것은 '스케줄이 걸렸다'는 사실뿐이고, 그 사실은 회귀를 잡는 힘이 약한 반면 코루틴 테스트 디스패처 도입 비용은 실재한다. (프로덕션 영향: 0줄)

---

## Claude's Discretion

없음 — 16개 결정 모두 사용자가 옵션을 직접 선택. plan 단계 위임 항목(D-3C 기울기·프레임 수, D-4B 코루틴 미시작 확인, D-4C 신선 마진)만 planner 재량.

## Deferred Ideas

- 명시값 == 현행 DevSettings 기본값 을 검사하는 드리프트 감시 테스트 (영역 1 Q4 옵션 3). Phase 2 범위(TEST-01·TEST-02·BUG-02) 밖의 새 감시 기능이고 MIN_TOTAL 상향이 1건 더 늘어난다. 기본값 변경이 곧 안전 동작 변경이었던 이력(v1.1.46 반경 슬라이더, v1.1.49 게이트 재도입)이 있어 가치는 있으나 Phase 2 에서는 하지 않는다.
- medianValue·avgRssi 를 관측면에 추가 (영역 2 Q1 옵션 3). Phase 1 RssiCascadeTest 가 같은 값을 이미 프레임 단위로 동결하고 있어 중복이다. RssiCascadeTest 가 걷히거나 필터 단이 재배치되면 재검토한다.
