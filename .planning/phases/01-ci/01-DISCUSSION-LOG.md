# Phase 1: 테스트 하네스와 CI 회귀 게이트 - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-24
**Phase:** 1-테스트 하네스와 CI 회귀 게이트
**Areas discussed:** 시간 시임 방식, 골든 기대값 출처, CI 게이트 위치, CI 진단 아티팩트

---

## 시간 시임 방식

### Q1. `KalmanFilter` 의 벽시계 의존을 어떤 형태의 시임으로 끊을까?

| Option | Description | Selected |
|--------|-------------|----------|
| 생성자 `timeSource` 주입 | `nowMs: () -> Long = { System.currentTimeMillis() }` 기본인자. 호출부 0줄 변경, `update`·`injectWarmup` 동시 커버 | ✓ |
| `update()` 파라미터로 `nowMs` 전달 | 시그니처 변경이 호출부로 전파. `injectWarmup` 은 별도 처리 필요 | |
| 전역 `Clock` 싱글턴 | 테스트 간 상태 공유 위험. 병렬 실행 시 오염 | |
| Robolectric 으로 시계 조작 | TEST-04("Android 프레임워크 없이")를 정면으로 위반 | |

**User's choice:** 생성자 `timeSource` 주입 (권장)
**Notes:** 벽시계 의존이 78행 `update`, 143행 `injectWarmup` 두 곳이라 생성자 주입이 한 번에 덮는다. 기본인자가 곧 현행 동작이라 프로덕션 diff 가 1줄에 그친다는 점이 결정적.

### Q2. 주입한 `timeSource` 로 골든 시퀀스의 dt 를 어떻게 구성할까?

| Option | Description | Selected |
|--------|-------------|----------|
| 120ms 정속 단일 프로필 | `BleService.kt:682` 주석의 "정상 주기 ~120ms" 를 그대로 채택. 기대값 배열이 프레임 인덱스와 1:1 | ✓ |
| 다중 dt 프로필(정속 + 불규칙) | 커버리지는 넓지만 첫 골든부터 기대값 세트가 배로 늘어남 | |
| 실측 로그의 실제 타임스탬프 재생 | ADB 기기 0대라 캡처 자체가 불가 | |

**User's choice:** 120ms 정속 단일 프로필 (권장)
**Notes:** 가변 dt 는 deferred 로 넘김.

### Q3. Phase 1 이 프로덕션 코드에 손대는 범위를 어디까지 허용할까?

| Option | Description | Selected |
|--------|-------------|----------|
| `timeSource` 시임 1건만 | 테스트 가능성 확보에 필요한 최소치. 나머지는 전부 후속 Phase | ✓ |
| 시임 + 가시성 완화(`internal` 노출) | 테스트 접근은 쉬워지나 캡슐화가 먼저 무너짐 | |
| 시임 + 눈에 띄는 버그 즉시 수정 | 기준선을 세우기 전에 기준선이 움직임. BUG-02 는 Phase 2 소관 | |

**User's choice:** `timeSource` 시임 1건만 (권장)
**Notes:** "현재 동작을 못 박는 것"이 목적이므로 버그도 그대로 동결한다.

### Q4. 시임 도입 후 "배포 APK 는 v1.1.70 과 동일 동작" 을 무엇으로 담보할까?

| Option | Description | Selected |
|--------|-------------|----------|
| diff 리뷰로 충분 | 기본인자 1줄이라 육안 검증 가능. 추가 비용 0 | ✓ |
| 실기 스모크 테스트 병행 | 현재 ADB 연결 기기 0대라 실행 불가 | |
| APK 바이트 비교 | 빌드 타임스탬프·서명 때문에 애초에 불일치. 신호가 아님 | |

**User's choice:** diff 리뷰로 충분 (권장)

---

## 골든 픽스처 경계 (시간 시임 심화)

사용자가 "질문 더" 를 선택해 같은 영역에서 4문항을 추가로 진행했다.

### Q5. 골든이 고정할 "3단 캐스케이드" 의 경계를 어디로 잡을까?

| Option | Description | Selected |
|--------|-------------|----------|
| 순수 3단 직렬 | `prevVel=0.0`, `adaptiveQFactor` 대신 `1.0`, 프리셋 고정. `pEmaFilter` 제외 | ✓ |
| 실제 폐루프 그대로(`prevVel` 피드백 포함) | 현실에 가깝지만 `BleService` 배선까지 기대값에 묶여 Phase 3 분해 시 함께 붕괴 | |
| `pEmaFilter` 까지 4단 포함 | 표시용 필터라 판정에 미개입. 기준선을 넓히기만 함 | |

**User's choice:** 순수 3단 직렬 (권장)
**Notes:** Reversibility costly 로 기록. 경계를 바꾸면 전 시나리오 기대값 재산출이 필요하고 Phase 2·3 이 이 위에 쌓인다.

### Q6. 골든 시퀀스의 시작 상태를 어느 초기화 경로로 잡을까?

| Option | Description | Selected |
|--------|-------------|----------|
| 두 경로 모두 | 콜드 스타트(pRR 5.0/pVV 5.0) + `injectWarmup`(pRR 25.0/pVV 5.0). 입력 시퀀스 공유, 진입만 상이 | ✓ |
| 콜드 스타트만 | v1.1.29 에서 도입된 워밍업 경로가 무검증으로 남음 | |
| `injectWarmup` 만 | 앱 최초 관측 경로가 무검증으로 남음 | |

**User's choice:** 두 경로 모두 (권장)
**Notes:** 초기 공분산이 5.0 vs 25.0 으로 갈려 같은 입력에도 출력이 달라진다는 정찰 사실이 근거.

### Q7. 골든 픽스처에 기기 간 상태 교차오염 검증을 포함할까?

| Option | Description | Selected |
|--------|-------------|----------|
| 단일 ID + 격리 테스트 별도 | 골든은 수치 회귀, 격리는 구조 회귀. 깨졌을 때 원인이 분리됨 | ✓ |
| 다중 ID 를 골든 시퀀스에 섞음 | 기대값 배열이 기기별로 얽혀 실패 원인 판별이 어려워짐 | |
| 이번 Phase 에서 제외 | 상태 누수(BUG-01 계열)가 이 프로젝트의 재발 고리 중 하나 | |

**User's choice:** 단일 ID + 격리 테스트 별도 (권장)

### Q8. 칼만 출력(`Double`) 의 골든 비교를 어떤 정밀도로 할까?

| Option | Description | Selected |
|--------|-------------|----------|
| 허용오차 `1e-9` | `dt.pow(4)` → `Math.pow` 의 JDK 구현차 흡수. 의미 있는 회귀는 이보다 훨씬 큼 | ✓ |
| 비트 단위 정확 일치 | JDK/플랫폼이 바뀌면 로직 변경 없이도 빨간불 | |
| 소수 2자리 반올림 비교 | 필터 상수 변화를 놓칠 수 있음 | |

**User's choice:** 허용오차 `1e-9` (권장)
**Notes:** `MedianFilter`·`RssiPreFilter` 는 `Int` 반환이라 정확 일치로 비교한다.

---

## 상위 문서 정합성 (영역 전환 시 발견)

### Q9. ROADMAP 의 "Phase 1 = 프로덕션 코드 무변경" 계약과 `timeSource` 시임 투입을 어떻게 맞출까?

| Option | Description | Selected |
|--------|-------------|----------|
| ROADMAP 문구 정정 | "동작 무변경 + 기본인자 1건" 으로 실제와 맞춤. 계약이 거짓말이 되지 않게 함 | ✓ |
| 시임을 포기하고 무변경 유지 | 벽시계 의존이 남아 골든 재현성 자체가 성립 불가 | |
| 문구를 그대로 두고 예외로 처리 | 문서가 코드와 어긋난 채 남음 — 이미 캐스케이드 순서 오기가 같은 방식으로 방치돼 있었음 | |

**User's choice:** ROADMAP 문구 정정 (권장)
**Notes:** 정찰 중 발견한 캐스케이드 순서·윈도우 크기 오기(REQUIREMENTS TEST-03, ROADMAP 성공기준 2)도 같은 커밋에서 함께 정정하기로 함 → D-21.

---

## 골든 기대값 출처

### Q10. 골든 기대값(expected) 을 무엇으로 산출할까?

| Option | Description | Selected |
|--------|-------------|----------|
| 현행 코드 1회 실행 동결 (record-then-freeze) | "지금 동작"이 곧 기준선. 회귀 탐지 목적에 정확히 부합 | ✓ |
| 수식으로 이론값 손계산 | 칼만+비대칭EMA 조합을 손으로 재현하는 비용이 과대하고, 오히려 구현과 어긋날 위험 | |
| 참조 구현(Python 등) 병행 작성 | 두 번째 구현을 유지보수해야 함. 불일치 시 어느 쪽이 옳은지 새 논쟁 발생 | |

**User's choice:** 현행 코드 1회 실행 동결 (권장)
**Notes:** Reversibility costly. 현 시점의 버그도 함께 동결된다는 사실을 테스트 파일 헤더 주석에 명시하기로 함.

### Q11. 골든에 흘릴 입력 RSSI 수열은 어디서 가져올까?

| Option | Description | Selected |
|--------|-------------|----------|
| 합성 시나리오 수설계 | 접근·이탈·임펄스 튐·정지 4종. 각 필터의 존재 이유를 겨냥해 설계 가능 | ✓ |
| 실기 로그 캡처 | ADB 기기 0대. `BleService.kt:2306` 로그도 `kf=%.1f` 반올림이라 부적합 | |
| 난수 생성 | 시드를 고정해도 어느 구간이 무엇을 검증하는지 설명 불가 | |

**User's choice:** 합성 시나리오 수설계 (권장)

### Q12. 동결한 골든 기대값을 어떤 형태로 저장할까?

| Option | Description | Selected |
|--------|-------------|----------|
| 테스트 소스 인라인 배열 | 의존성 0, 파서 코드 0. PR diff 에 숫자가 직접 노출됨 | ✓ |
| 리소스 파일(JSON/CSV) + 로더 | 파서 코드가 곧 테스트 대상 밖 신규 코드. 골든이 깨졌을 때 파서 의심까지 추가됨 | |
| 스냅샷 라이브러리 도입 | 신규 의존성. 자동 재기록이 D-12 의 위험과 정면 충돌 | |

**User's choice:** 테스트 소스 인라인 배열 (권장)

### Q13. 의도적 로직 변경으로 골든이 깨졌을 때 기대값을 어떻게 갱신할까?

| Option | Description | Selected |
|--------|-------------|----------|
| 수동 갱신 + diff 리뷰 필수 | 기대값 변경이 반드시 사람의 눈을 거침. record-then-freeze 의 최대 위험을 직접 차단 | ✓ |
| `-PupdateGolden` 플래그로 자동 재기록 | 편하지만 "깨지면 덮어쓰기"가 습관이 되어 회귀를 무심코 승인하게 됨 | |
| 실패 시 자동 재기록 후 경고만 | 게이트가 게이트가 아니게 됨 | |

**User's choice:** 수동 갱신 + diff 리뷰 필수 (권장)

---

## CI 게이트 위치

### Q14. 골든 테스트를 어느 트리거에 붙일까?

| Option | Description | Selected |
|--------|-------------|----------|
| `release.yml` 안에만 | 유일하게 실제로 발화하는 워크플로. 릴리스 = 위험 순간이므로 게이트 가치가 최대 | ✓ |
| PR 트리거 워크플로 신설 | `master` 단일 브랜치, `git log --merges` 빈 출력 = PR 이력 0건. 발화하지 않음 | |
| push 전체 트리거 | 직커밋이 잦아 CI 분당 소모만 늘고 차단 효과는 릴리스와 동일 | |

**User's choice:** `release.yml` 안에만 (권장)

### Q15. `release.yml` 안에서 테스트를 어느 위치에 놓을까?

| Option | Description | Selected |
|--------|-------------|----------|
| 같은 job, Build 앞 | Build·GitHub Release·Firebase PATCH 셋 전부 차단. `Restore google-services.json` 뒤여야 함 | ✓ |
| 별도 job + `needs:` | 격리는 되나 Gradle 캐시·SDK 셋업을 통째로 두 번 수행 | |
| Build 뒤, Release 앞 | APK 는 이미 만들어짐. 낭비된 빌드 시간만큼 신호가 늦음 | |

**User's choice:** 같은 job, Build 앞 (권장)
**Notes:** `Verify keystore fingerprint` 가 이미 같은 job 안의 "실패 시 exit 1" 선례.

### Q16. CI 에서 테스트를 어느 범위로 돌릴까?

| Option | Description | Selected |
|--------|-------------|----------|
| `testDebugUnitTest` | flavor 없음 + buildTypes 2종이라 `assembleDebug` 와 정확히 같은 변형 | ✓ |
| `test` (전 변형) | release 변형까지 중복 실행. 같은 코드를 두 번 검증 | |
| `check` | 기준이 정의되지 않은 lint 를 게이트에 묶음. 무관한 실패로 릴리스가 막힘 | |

**User's choice:** `testDebugUnitTest` (권장)
**Notes:** `--no-daemon --console=plain` 부착.

### Q17. 태그를 push 했는데 테스트가 실패해 릴리스가 안 나갔을 때, 원격에 남은 태그를 어떻게 할까?

| Option | Description | Selected |
|--------|-------------|----------|
| 수동 삭제 후 재푸시 | CI 가 저장소 이력을 파괴적으로 건드리지 않음. 실패가 사람 눈에 남음 | ✓ |
| CI 가 실패 시 태그 자동 삭제 | 워크플로에 원격 이력 파괴 권한을 부여. 오작동 시 피해가 큼 | |
| 태그를 그대로 두고 다음 버전으로 진행 | 버전 번호에 구멍이 생기고, 어떤 태그가 출시본인지 불명확해짐 | |

**User's choice:** 수동 삭제 후 재푸시 (권장)

---

## CI 진단 아티팩트

### Q18. CI 아티팩트로 무엇을 보존할까?

| Option | Description | Selected |
|--------|-------------|----------|
| HTML 리포트 + JUnit XML | 사람이 읽는 경로와 기계가 읽는 경로 양쪽 확보. 둘 다 Gradle 이 이미 생성 | ✓ |
| JUnit XML 만 | 원시 XML 을 눈으로 읽어야 함 | |
| 콘솔 로그만 | Actions 로그 보존기간에 종속되고 스택트레이스가 잘림 | |

**User's choice:** HTML 리포트 + JUnit XML (권장)

### Q19. 아티팩트 업로드를 언제 수행할까?

| Option | Description | Selected |
|--------|-------------|----------|
| `if: always()` | 게이트가 Build 앞이라 실패 시 job 이 그 자리에서 죽음 — 조건 없으면 정작 실패 때 스킵됨 | ✓ |
| `if: failure()` | 성공 런의 기준선 리포트가 남지 않음 | |
| 조건 없음(기본) | 실패 시 후속 스텝이 실행되지 않아 업로드가 건너뛰어짐 | |

**User's choice:** `if: always()` (권장)

### Q20. 골든이 깨졌을 때 assert 실패 메시지에 무엇까지 담을까?

| Option | Description | Selected |
|--------|-------------|----------|
| 시나리오명 + 프레임 인덱스 + 단계명 | 예 `approach/coldStart frame=17 stage=kalman`. 어디서 갈라졌는지 즉시 특정 | ✓ |
| 기본 `assertEquals` 메시지 | `expected:<-70.123> but was:<-70.124>` 뿐 — 어느 시나리오 어느 프레임인지 불명. CI-02 사실상 미충족 | |
| 전체 배열 덤프 | 정보량은 많으나 어느 지점이 첫 이탈인지 사람이 다시 찾아야 함 | |

**User's choice:** 시나리오명 + 프레임 인덱스 + 단계명 (권장)
**Notes:** D-11(인라인 배열)을 택했으므로 배열 인덱스가 곧 프레임 번호가 된다.

### Q21. 실패 상세를 CI 콘솔 로그에도 직접 찍을까?

| Option | Description | Selected |
|--------|-------------|----------|
| `testLogging` 추가 | `events 'failed'` + `exceptionFormat 'full'`. 아티팩트 zip 없이 Actions 로그만으로 판별 가능 | ✓ |
| 아티팩트만으로 충분 | 매번 zip 을 내려받아 여는 왕복이 생김 | |
| 커스텀 리포터 작성 | 유지보수 대상 신규 코드. 이득 대비 과대 | |

**User's choice:** `testLogging` 추가 (권장)
**Notes:** `testOptions` 블록은 APK 산출물에 영향이 없어 D-03(프로덕션 변경 최소화)과 충돌하지 않음을 확인하고 채택.

---

## Claude's Discretion

사용자가 명시적으로 위임했거나 결정 범위 밖으로 남긴 항목:

- 테스트 클래스·파일 이름, 시나리오 상수명, 시나리오당 프레임 수
- 아티팩트 이름·보존기간, `actions/upload-artifact` 버전
- 기기 간 격리 테스트(D-07)의 구체 시나리오 구성
- 합성 시나리오 4종의 실제 RSSI 수치 (종류만 확정)

## Deferred Ideas

- 가변 dt 프로필 골든 (불규칙 스캔 주기)
- `pEmaFilter`(표시용 EMA) 골든
- `prevVel` 피드백 폐루프 전체를 고정하는 통합 골든 → Phase 2
- 6대 초과 UWB 세션 플립 경로 → BUG-03, v2
- `.planning/graphs/` 커밋 여부 (매 빌드 ~1.9MB 갱신) — 저장소 위생 결정, 사용자 판단 대기
- `/gsd-profile-user` 로 `.claude/CLAUDE.md` Developer Profile placeholder 채우기

---

*Discussion completed: 2026-08-24*
*Decisions: 21 (D-01 ~ D-21) — see 01-CONTEXT.md*
