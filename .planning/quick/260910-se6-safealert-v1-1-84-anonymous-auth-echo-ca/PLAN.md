---
task_id: 260910-se6
slug: safealert-v1-1-84-anonymous-auth-echo-ca
date: 2026-09-10
mode: quick
---

# SafeAlert v1.1.84 — 익명 로그인 도입 + echo_calib 앱 버전 필드

## 목표

DB 규칙 잠금(`auth != null`)의 **1단계: 앱 코드만**. 익명 인증을 먼저 배포하고,
현장 기기가 전부 갱신된 것을 확인한 뒤 별도 릴리스에서 `database.rules.json` 을 잠근다.

## 범위 밖 (이번에 하지 않는다)

- `database.rules.json` 수정 — 구버전(v1.1.83 이하) 현장 기기의 로깅이 끊긴다
- `03_service/AlertStateMachine.kt`, `03_service/BleService.kt` 판정 로직
- `02_ble/*` BLE 페이로드 (1바이트 비트팩 프로토콜, 구버전 호환 필수)
- 태그 `v1.1.84` 푸시 — 배포 트리거이며 사람 작업(Firebase 콘솔 익명 로그인 활성화)이 먼저

## 불변 조건

**로그인 실패가 앱 동작을 막으면 안 된다.** 경보 판정은 BLE 전용이고 서버와 무관하다.
네트워크가 없거나 익명 로그인이 실패해도 스캔·광고·경보·진동은 그대로 돈다.
서버 기록만 실패하는 것이 정상 거동. → 로그인 코드는 전부 try/catch + 실패 리스너 로그만,
호출부에 예외를 던지지 않고 어떤 것도 블로킹하지 않는다.

## 작업

### Task 1 — firebase-auth-ktx 의존성
`app/build.gradle` dependencies, Firebase 블록에 BOM 관리 버전으로 추가.

### Task 2 — 앱 시작 시 익명 로그인
`04_firebase/FirebaseConfig.kt` `init()` — `setPersistenceEnabled` 직후.
`FirebaseAuth.currentUser` 가 있으면 재사용, 없을 때만 `signInAnonymously()`.
호출부(`SafeAlertApp.onCreate`)는 무수정 — 이미 `FirebaseConfig.init()` 을 부른다.

### Task 3 — echo_calib 앱 버전 필드
`04_firebase/FirebaseManager.kt` `uploadEchoCalib` 의 맵에 `"ver" to BuildConfig.VERSION_NAME` 1개 추가.
echo_calib 은 1시간마다 전체 덮어쓰기 → 서버에서 구버전 잔존 기기를 한눈에 식별(3단계 롤아웃 검증용).

### Task 4 — 버전 상향
`app/build.gradle` versionCode 139 → 140, versionName "1.1.83" → "1.1.84".

## 검증

`./gradlew test` (CI 가 골든 테스트 6종 · 최소 42건 실행 강제).
실기: 비행기 모드로 앱 시작 → 경보 정상 동작 확인 (사람 작업).

## 배포 전 사람 작업

Firebase 콘솔 → Authentication → Sign-in method → **익명(Anonymous) 사용 설정**.
꺼져 있으면 모든 익명 로그인이 실패한다.
