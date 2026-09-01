---
status: pending
phase: 02-golden
source: .planning/phases/02-golden/02-VERIFICATION.md (human_verification)
started: 2026-08-28
updated: 2026-08-28
note: BUG-02(저속 접근 WARNING 미도달) 현장 관측. 코드측 4/4 VERIFIED, 실기 1건만 미수행.
revision: 1
---

# Phase 2 UAT — BUG-02 현장 관측

## Current Test

- number: O1
- name: 저속 접근 시 WARNING 발령
- expected: 보행자 기기에 경고 표시
- awaiting: 실기 수행 (지게차 1대 + 보행자 1대)

## 근거

02-VERIFICATION.md 의 human_verification 원문:

> test: 지게차 1대 + 보행자 1대 실기로, 지게차가 걷는 속도보다 느리게 경고 반경 밖에서 안으로
> 접근할 때 보행자 기기에 경고가 뜨는지, 평소 속도 접근과 경고 거리 차이가 크지 않은지,
> 지게차가 멀어지면 경보가 정상 해제되는지 확인
> expected: 4항목(접근 시 WARNING 표시 / 평소 속도와 큰 차이 없음 / 이탈 시 정상 해제) 모두 성립
> why_human: 실기·현장 환경이 필요해 코드 검토로 판별 불가

검증 대상 픽스 (02-04 에서 커밋됨):
- `BleService.kt:507` `WARNING_DEPART_RATE_DBM_PER_SEC = 3.0` — 이탈 속도가 이 값 이상일 때만 streak 리셋
- `BleService.kt:485` `warningMissRefMap` — 미도달 참조 유지

## Setup

### 준비물

1. 안드로이드 기기 2대 — 동일 빌드 설치 (versionName 1.1.70 / versionCode 126, `app/build.gradle:15-16`)
2. 지게차 1대 (실차)
3. 직선 통로 25m 이상, 양끝 시야 확보
4. 바닥 마커 (테이프·콘) 2m 간격 13개: 0 / 2 / 4 / ... / 24m
5. 스톱워치 (구간 통과 시간 → 속도 산출)
6. 보행자 기기용 USB 케이블 + PC (adb logcat)
7. 3인칭 촬영 카메라 1대 (선택, 권장 — 보행자 기기 화면과 통로가 한 프레임에 들어오게)

### 안전

- 보행자는 지게차 진행선에서 옆으로 1m 이상 비켜 선 위치의 0m 마커에 정지해 있는다.
- 지게차 운전자는 보행자 위치를 사전에 육안 확인하고, 어떤 회차에서도 보행자 쪽으로 조향하지 않는다.
- 경보음이 울려도 운전자는 계획된 코스를 유지한다 (경보 반응 훈련이 아니라 발령 거리 측정이다).
- 보행자는 경보 발령 시각을 기록만 하고 이동하지 않는다.

### 기기 역할 설정

| 기기 | 역할 선택 카드 | 내부 값 | 소스 |
|------|----------------|---------|------|
| 지게차 탑재 기기 | "지게차" | DEVICE / CAT_FORKLIFT | MainActivity.kt:263 |
| 보행자 소지 기기 | "보행자" | WALKER / CAT_WALKER | MainActivity.kt:261 |

두 기기 모두 앱 실행 후 감시 시작 상태(홈 화면에 "주변 감지 기기 ...", MainActivity.kt:423/448)를 확인한다.

### 로깅 준비 (보행자 기기 1대만)

1. 보행자 기기에서 설정 카드 진입 -> PIN 3자리 `368` 입력 (MainActivity.kt:915) -> 개발자 설정
2. 개발자 설정에서 `상세 로그(verbose)` 스위치 ON (DevSettingsActivity.kt:193)
   - OFF 상태면 `BleService.kt:1332` / `BleService.kt:2338` 게이트가 프레임 캐스케이드 로그를 전부 막는다.
3. PC 에서:

```bash
adb logcat -c && adb logcat -v time -s BleService:D
```

4. 회차 시작 직전 콘솔에 회차 번호를 남기기 위해 시각을 메모한다 (예: "O1-1 시작 14:22:05").

### 판정 경로 구분 (중요)

BUG-02 픽스는 RSSI 판정 경로(Case B)의 warningStreak 로직이다. 회차 로그에
`UWB 경고 발생`(BleService.kt:2849) / `UWB 위험 발생`(BleService.kt:2835) 이 찍혔다면
그 회차는 UWB 판정(Case A)으로 발령된 것이므로 **BUG-02 표본에서 제외**하고 비고에 "Case A" 로 기록한다.
`경고 발생:`(:2573) 또는 `[v1.1.22 C] med 즉시 격상 WARNING`(:1921) 만 찍힌 회차가 유효 표본이다.

## Observation Points

| 관측점 | 위치 | 읽는 값 |
|--------|------|---------|
| 경고 표시 | 보행자 기기 화면 목록 행 | `경고  <이름>  <거리 또는 dBm>` (MainActivity.kt:445-460) |
| 위험 표시 | 보행자 기기 화면 목록 행 | `위험  <이름>  <거리 또는 dBm>` |
| WARNING 발령 (canonical) | logcat | `경고 발생: <id> (<name>) avgRssi=.. state=.. vel=..dBm/s` (BleService.kt:2573) |
| WARNING 발령 (즉시 격상 경로) | logcat | `[v1.1.22 C] med 즉시 격상 WARNING: ... warningStreak=.. med=.. kfVel=..` (BleService.kt:1921) |
| DANGER 발령 | logcat | `위험 발생: ...` (BleService.kt:2551) |
| 경보 해제 | logcat | `이탈 경보 해제: <id> (<recedingMs>ms 연속 이탈)` (BleService.kt:2273) |
| UWB 판정 여부 | logcat | `UWB 경고 발생` / `UWB 위험 발생` (BleService.kt:2849 / :2835) |
| 프레임 캐스케이드 | logcat (verbose) | `RSSI raw=.. -> med=.. -> pre=.. -> kf=.. -> pEma=..` (BleService.kt:2338-2339) |
| 임계값 기준 | 코드 상수 | WARNING -75dBm / DANGER -55dBm (BleConstants.kt:36-37, 개발자 설정으로 변경 가능 -> 변경했다면 값 기록) |

## 공통 측정 방법

1. 보행자는 0m 마커에 정지, 지게차는 24m 마커 밖에서 출발한다.
2. 지게차가 20m -> 4m 구간을 통과하는 시간을 스톱워치로 잰다. 속도 = 16m / 통과시간.
3. 보행자 기기에 경고가 뜨는 순간(화면 `경고` 표시 + 경보음)의 지게차 앞바퀴 위치를 마커로 읽는다.
   마커 사이면 근접 마커 +- 1m 로 기록한다.
4. 같은 순간의 화면 표시값(거리 문자열 또는 dBm)과 logcat 시각·avgRssi 를 함께 남긴다.
   -> 물리 거리 / 화면 표시 / logcat 3중 기록.
5. 지게차는 보행자를 지나쳐 반대편 24m 마커까지 계속 주행한다 (O4 이탈 관측 겸용).
6. 회차 사이 30초 이상 간격을 두어 이전 회차 상태가 완전히 해제된 뒤 시작한다.

## Tests

### 1. O1 — 저속 접근 시 WARNING 발령

expected: 저속(보행 속도 미만, 목표 0.5 m/s 이하) 접근 3회 모두 보행자 기기에 WARNING 표시
why_human: 실차 저속 주행의 RSSI 변화율은 시뮬레이션으로 재현 불가
method: 공통 측정 방법. 16m 구간 통과시간이 32초 이상이면 0.5 m/s 이하다. 20m 마커 진입 전에 목표 속도에 도달해 있어야 한다.
판정: 3/3 발령 = PASS. 1회라도 미발령 = FAIL (BUG-02 재개봉)

| 회차 | 구간 통과시간(s) | 산출 속도(m/s) | WARNING 발령 | 비고(Case A 여부) |
|------|------------------|----------------|--------------|-------------------|
| O1-1 |                  |                |              |                   |
| O1-2 |                  |                |              |                   |
| O1-3 |                  |                |              |                   |

result: [pending]

### 2. O2 — 저속 접근 시 경고 발생 거리

expected: 수치 기록 (판정 항목 아님, O3 의 입력값)
why_human: 발령 거리는 현장 RF 환경에 종속되어 코드로 산출 불가
method: O1 각 회차의 발령 순간 마커 위치를 그대로 옮겨 적는다.
판정: 3회 전부 기록되면 완료. 기록 누락 = blocked

| 회차 | 물리 거리(m) | 화면 표시값 | logcat 시각 | avgRssi(dBm) |
|------|--------------|-------------|-------------|--------------|
| O1-1 |              |             |             |              |
| O1-2 |              |             |             |              |
| O1-3 |              |             |             |              |

- 저속 평균 발령 거리 D_slow = ______ m
result: [pending]

### 3. O3 — 평소 속도 접근과의 차이

expected: 저속 평균 발령 거리가 평소 속도 평균과 크게 다르지 않을 것
why_human: 두 속도 조건의 실측 비교가 BUG-02 재발 여부의 유일한 현장 지표
method: 공통 측정 방법을 통상 주행 속도(지게차 평상시 이동 속도, 감속 없이)로 3회 반복
판정:
  - `D_slow >= D_normal * 0.7` **그리고** `|D_normal - D_slow| <= 3.0m` = PASS
  - 둘 중 하나라도 미달 = FAIL (BUG-02 재개봉)
  - `D_slow > D_normal` (저속이 더 멀리서 발령)은 문제 아님 = PASS
  - 참고: 유효 표본(Case B)이 조건당 2회 미만이면 판정 불가 -> blocked 로 기록하고 재수행

| 회차 | 구간 통과시간(s) | 산출 속도(m/s) | 물리 거리(m) | avgRssi(dBm) | 비고 |
|------|------------------|----------------|--------------|--------------|------|
| O3-1 |                  |                |              |              |      |
| O3-2 |                  |                |              |              |      |
| O3-3 |                  |                |              |              |      |

- 평소 속도 평균 발령 거리 D_normal = ______ m
- 비율 D_slow / D_normal = ______
- 절대 차이 |D_normal - D_slow| = ______ m
result: [pending]

### 4. O4 — 이탈 시 경보 해제

expected: 지게차가 멀어지면 보행자 기기의 경보가 정상 해제
why_human: 이탈 판정은 실제 RSSI 하강 궤적에 의존
method: O1 / O3 각 회차의 통과 후 구간을 그대로 사용한다. 지게차가 반대편 24m 마커에 도달해 정지한
  뒤부터 경보음 정지 + 화면 목록에서 `경고`/`위험` 표기 소멸까지의 시간을 잰다.
  logcat 의 `이탈 경보 해제` 줄과 괄호 안 `recedingMs` 값을 기록한다.
- 판정:
  - 6회차(O1 3회 + O3 3회) 전부 해제 = PASS
  - 정지 후 60초 경과해도 미해제인 회차가 1회라도 있으면 FAIL
  - 해제 소요 시간은 기록만 하고 판정에 쓰지 않는다 (참고 지표)

| 회차 | 해제 여부 | 정지->해제 소요(s) | logcat recedingMs | 비고 |
|------|-----------|--------------------|-------------------|------|
| O1-1 |           |                    |                   |      |
| O1-2 |           |                    |                   |      |
| O1-3 |           |                    |                   |      |
| O3-1 |           |                    |                   |      |
| O3-2 |           |                    |                   |      |
| O3-3 |           |                    |                   |      |

result: [pending]

## 최종 판정

- 4항목(O1 / O2 / O3 / O4) 전부 PASS = Phase 2 human_verification 해소
  -> 02-VERIFICATION.md 의 human_verification 을 비우고 `status: passed` 로 갱신
- O1 또는 O3 이 FAIL = BUG-02 재개봉. 이 문서의 기록을 근거로 신규 디버그 사이클을 연다
  (해당 회차의 logcat 원문을 함께 보존할 것)

## Summary

- total: 4
- passed: 0
- issues: 0
- pending: 4
- skipped: 0
- blocked: 0

## Gaps

- (없음 — 실기 수행 후 갱신)

## 환경 기록란 (실기 시 채움)

- 수행 일시:
- 장소 / 통로 특성 (철제 렉, 파렛트 적재 등 2.4GHz 흡수체 유무):
- 사용 기기 모델 2종:
- 앱 versionName / versionCode:
- WARNING / DANGER 임계값 (기본 -75 / -55 에서 변경했다면 실제값):
- UWB 관련 설정 상태 (개발자 설정에서 확인한 값):
- 촬영 영상 파일명 (있으면):
