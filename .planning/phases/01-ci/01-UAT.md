---
status: pending
phase: 01-ci
source: [01-VERIFICATION.md]
started: 2026-08-24T09:05:00Z
updated: 2026-08-25T00:00:00Z
note: >-
  Phase 01 은 사용자 결정으로 이미 마감됐다(blocking-human 체크포인트 승인 + "지금 수정 후 마감").
  이 파일은 phase 를 되돌리기 위한 것이 아니라, SC 밖 실기 스모크 1건이 유실되지 않게
  추적 아티팩트로 남기는 것이다.
revision: >-
  2026-08-25 절차 정정. 초판은 (a) 기기 1대를 전제했고 (b) 판정 근거로 `adb devices` 를 들었으며
  (c) 합격 기준이 "v1.1.70 과 구별되지 않는다"는 눈대중이었다. SafeAlert 는 BLE advertise/scan
  브로드캐스트만 쓰므로 GATT 연결도 페어링도 없다 — "기기가 연결됐다"는 상태는 코드에 존재하지
  않는다. `adb devices` 는 폰-PC USB 축이라 폰-폰 BLE 수신 여부와 직교한다. 아래 절차는 코드에
  이미 있는 관측점(화면 감지 목록 + logcat 캐스케이드 로그)으로 합격 기준을 기계 판독 가능하게
  바꾼 것이다. 테스트 범위 자체는 초판과 동일하다.
---

## Current Test

number: 1
name: v1.1.70 동일 서명 debug APK 실기 덮어쓰기 설치 + BLE 수신·경보 동작 확인
expected: |
  설치가 성공하고(서명 동일 → 덮어쓰기 가능), 두 기기가 서로의 광고를 수신하며,
  Kalman 시임이 무결하다(vel 필드가 0 고정도 폭주도 아님). 프로덕션 diff 는
  KalmanFilter 생성자 기본 인자 시임 1건뿐이므로 런타임 동작 변화가 관측되면 그 자체가 회귀다.
awaiting: |
  기기 2대 (서로 다른 역할: 보행자 + 지게차). 그중 관측용 1대만 USB(ADB) 연결.
  나머지 1대는 ADB 불필요 — 광고 송출원 역할만 한다.

## Setup

1. 두 기기 모두에 동일 APK 설치 — `-r` 덮어쓰기가 성공하는 것 자체가 서명 동일성 증거다.
   `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. 두 기기의 역할을 서로 다르게 지정한다 (보행자 / 지게차).
3. 관측용 기기에서 개발자설정 7탭 게이트 진입 → `switchVerbose` ON.
   (DevSettingsActivity.kt:193 → DevSettings.logVerbose. 이 스위치가 꺼져 있으면
    BleService.kt:2305 게이트가 프레임 로그를 통째로 막아 아래 판정이 전부 불가능하다.)
4. 관측용 기기 1대만 USB 연결 후: `adb logcat -c; adb logcat -s BleService:D BleScanner:D`

## Observation Points

| 관측점 | 위치 | 읽는 값 |
|--------|------|---------|
| 화면 감지 목록 | MainActivity.kt:445 / :423 | `주변 감지 기기 N건` (N>=1) vs `주변 감지 기기 없음 · 감시 중` |
| 프레임 캐스케이드 | BleService.kt:2306 | `RSSI raw= → med= → pre= → kf= → pEma= vel= state= stable=` |
| 신호 소실 | BleScanner.kt:281 | `신호 소실: <deviceId>` |
| 경보 발령 | BleService.kt:2540 | `경고 발생: <deviceId> ... vel=` |
| 경보 해제 | BleService.kt:2240 | `이탈 경보 해제: <deviceId> (<ms>ms 연속 이탈)` |

## Tests

### 1. BLE 수신 성립

expected: 화면 헤더가 `주변 감지 기기 N건` (N>=1) 이고, logcat 에 상대 deviceId 의 `RSSI raw=` 줄이 연속 유입된다.
why_human: 실기 BLE 전파는 코드 검사로 대체 불가. P-09 에 따라 검증 불가를 검증한 것처럼 기록하지 않는다.
note: 이것이 "연결됐다"의 유일한 조작적 정의다 — 페어링 상태가 아니라 스캔 콜백 유입 여부다.
result: [pending]

### 2. Kalman 시임 무결성 (이번 변경의 핵심 지표)

expected: |
  캐스케이드 로그의 `vel=` 필드가 접근 시 양수 / 이탈 시 음수로 수 dBm/s 범위를 오간다.
  불합격 신호는 두 가지뿐이며 둘 다 기계 판독 가능하다:
    - `vel=0.00dBm/s` 로 고정 (시계가 멈춰 dt 가 죽음)
    - 비정상 폭주 (dt 가 하한 0.05s 로 상시 클램프됨)
why_human: dt 는 실시간 프레임 간격에서만 생기므로 JVM 골든 테스트(시임 주입 = 고정 시계)로는 재현되지 않는다.
rationale: |
  변경된 시계 소스는 KalmanFilter.update() 의 dt 계산에만 쓰이고, dt 는
  coerceIn(0.05, 2.0) 로 물려 있다. 따라서 시임 파손은 반드시 vel 에 드러난다.
code_evidence: KalmanFilter.kt:27-30 기본 인자 = System.currentTimeMillis(), 호출부 BleService.kt:450 / :1454 변경 0곳 (nowMs 미전달 → 기본 인자 적용)
result: [pending]

### 3. 접근/이탈 경보 왕복

expected: 접근 시 `경고 발생` 로그 + 화면/소리 발령, 이탈 시 `이탈 경보 해제` 로그 + 정지. 로그와 실제 소리가 정합한다.
why_human: 오디오·오버레이 출력은 logcat 만으로 확정 불가 — 사람이 함께 듣고 봐야 한다.
scope: Phase 1 의 Success Criteria 4건 **밖**. ROADMAP 이 이미 현장 검증 항목으로 별도 배정한 건.
result: [pending]

## Summary

total: 3
passed: 0
issues: 0
pending: 3
skipped: 0
blocked: 0

## Gaps

없음 — Success Criteria 4건은 전부 자동 검증으로 달성됐다(01-VERIFICATION.md GOAL_ACHIEVED 4/4).
이 UAT 는 SC 밖 출하 side-condition 하나만 남기며, 초판의 항목 1건을 관측 가능한 3건으로 분해한 것이다.
