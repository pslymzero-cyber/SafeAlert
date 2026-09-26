---
task_id: 260910-se6
slug: safealert-v1-1-84-anonymous-auth-echo-ca
date: 2026-09-10
status: complete
---

# SUMMARY — SafeAlert v1.1.84 익명 로그인 + echo_calib 앱 버전 필드

## 변경

| 커밋 | 내용 | 파일 |
|---|---|---|
| `6daffec` | firebase-auth-ktx 의존성 + 앱 시작 시 익명 로그인 | `app/build.gradle`, `04_firebase/FirebaseConfig.kt` |
| `f2365a9` | echo_calib 업로드에 `"ver"` 필드 | `04_firebase/FirebaseManager.kt` |
| `85e31ed` | versionCode 139→140, versionName 1.1.83→1.1.84 | `app/build.gradle` |

- `FirebaseConfig.init()` 이 `setPersistenceEnabled` 직후 `signInAnonymously()` 를 호출.
  `FirebaseAuth.currentUser` 가 있으면 즉시 반환(UID 재사용). 호출부(`SafeAlertApp.onCreate`) 무수정.
- 실패 경로 전부 로그만: `try/catch` 로 동기 예외를, `addOnFailureListener` 로 비동기 실패를 흡수.
  호출부로 예외가 나가지 않고 블로킹하지 않는다 → BLE 스캔·광고·경보·진동 무영향.
- `uploadEchoCalib` 맵에 `"ver" to BuildConfig.VERSION_NAME` 1개. echo_calib 은 노드 전체를
  1시간마다 덮어쓰므로 항상 현재 설치본 값이다.

## 검증

`./gradlew test` — BUILD SUCCESSFUL, 61건 실행(CI 하한 42건 충족).

## 손대지 않은 것

`database.rules.json`, `03_service/AlertStateMachine.kt`, `03_service/BleService.kt`, `02_ble/*`.
태그 `v1.1.84` 미생성·미푸시.

## 다음 (이번 범위 밖)

1. **배포 전 사람 작업** — Firebase 콘솔 → Authentication → Sign-in method → 익명 사용 설정.
   꺼져 있으면 모든 익명 로그인이 실패한다.
2. 실기: 비행기 모드로 앱 시작 → 경보 정상 동작 확인.
3. v1.1.84 배포 후 3개 센터 기기가 전부 갱신됐는지 echo_calib `ver` 로 확인.
4. 그 뒤 별도 릴리스에서 `database.rules.json` 에 `auth != null` 추가.
   `version` 노드는 열어 둔다 — 업데이트 확인이 로그인보다 먼저 일어날 수 있다.

## 워크플로 편차

Task 규모(4개 지점, 총 34줄 삽입)와 저장소 컨텍스트 규칙(대용량 파일 통독 금지)을 고려해
gsd-planner/gsd-executor 서브에이전트 대신 오케스트레이터가 직접 계획·실행했다.
원자 커밋·PLAN/SUMMARY·STATE 갱신 보장은 그대로 유지.
