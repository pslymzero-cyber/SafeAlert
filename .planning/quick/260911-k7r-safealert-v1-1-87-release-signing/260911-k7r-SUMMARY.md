---
quick_id: 260911-k7r
slug: safealert-v1-1-87-release-signing
date: 2026-09-11
status: complete
---

# SUMMARY: v1.1.87 릴리스 서명, 업데이트 무결성, 표시 이름 규칙 (Phase B, B8까지)

범위는 PLAN의 Phase B, B1부터 B8까지다. 커밋·태그(v1.1.87)·푸시는 2026-09-11 사용자 지시로 진행했다(코드 커밋 abd158a). Phase D(규칙 잠금)는 제외했다.

## 변경

| 항목 | 파일 | 내용 |
|---|---|---|
| 버전 | `app/build.gradle` | versionCode 142 → 143, versionName 1.1.87 |
| 서명 | `app/build.gradle` | `signingConfigs.release`: `SA_RELEASE_*` 환경변수 4개로만 받고 PKCS12 사용. env가 없으면 release를 unsigned로 산출 |
| R8 | `app/build.gradle` | release `minifyEnabled true`. shrinkResources는 넣지 않음 |
| CI 서명 | `.github/workflows/release.yml` | debug keystore 복원 제거 → `RELEASE_KEYSTORE` Secret을 `env:`로 받아 `$RUNNER_TEMP/release.p12`에 복원. assembleRelease 단계에도 env로만 전달 |
| CI 게이트 | `.github/workflows/release.yml` | `app-release.apk` mv(서명이 빠지면 실패) → apksigner 지문 대조(`5e7936fa…0df4`, 불일치 시 exit 1) → sha256sum |
| CI 해시 배포 | `.github/workflows/release.yml` | `/version.json`과 `/wf11/version.json` PATCH 페이로드에 `apk_sha256` 추가 |
| 업데이트 무결성 | `06_utils/UpdateManager.kt`, `05_ui/MainActivity.kt` | `apkSha256` 필드와 `sha256Hex`·`hashMatches` 추가. 해시가 비어 있거나 다르면 설치하지 않음(fail-closed) |
| 백업 차단 | `AndroidManifest.xml` | `allowBackup="false"` |
| 표시 이름 | `04_firebase/FirebaseManager.kt` | `DEVICE_ID_MAX_BYTES = 15`, `isValidDeviceId`(Firebase 키 금지문자·제어문자 거부, UTF-8 15바이트 이하), `utf8PrefixLen` |
| 표시 이름 UI | `05_ui/MainActivity.kt`, `activity_main.xml` | 힌트를 '이름 또는 직번 (선택)'에서 'nick name 또는 공정 (선택)'으로 변경. UTF-8 15바이트 InputFilter(한글 5자, 영문 15자) 추가, 저장 시 검증 Toast |
| BLE | `02_ble/BleAdvertiser.kt`, `03_service/BleService.kt` | ID 절단 `take(14)` → `take(15)` (legacy 31B 중 29B 사용) |
| 규칙 (E) | `database.rules.json` | 최상위 `echo_calib` 규칙 추가 |
| 키 반입 차단 | `.gitignore` | `*.jks`, `*.p12`, `*.keystore` |
| 테스트 | `test/.../firebase/DeviceIdValidationTest.kt`, `test/.../support/UpdateHashTest.kt` | 신규 5건 |

## 계획 대비 추가·변경 (사용자 승인)

- 사용자 요청: 힌트 문구 변경과 한글 5자 제한.
- 입력 규칙 확정: 한글 5자, 영문 15자, 혼합 입력은 UTF-8 15바이트 상한.
- BLE ID 상한을 14바이트에서 15바이트로 올림.
- E(`echo_calib` 규칙)를 지금 배포하는 것으로 승인.
- 15바이트를 넘는 기존 이름은 마이그레이션하지 않는다. 저장 시 Toast를 띄우고 되돌린다.

## 검증 (B8)

- `./gradlew :app:testDebugUnitTest`: 72건 전부 통과. DeviceIdValidationTest 3/3, UpdateHashTest 2/2.
- `./gradlew assembleRelease` (env 없음): BUILD SUCCESSFUL. `minifyReleaseWithR8` 통과, `app-release-unsigned.apk` 6.4MB 산출.
- `AndroidManifest.xml:51` `allowBackup="false"` 확인.
- `database.rules.json`: JSON 파싱 OK, `echo_calib` 규칙 존재.
- `release.yml`: YAML 파싱 OK (16 steps).

## 발견 사항

- 로컬 Windows에서는 Kotlin 컴파일이 소스를 MS949로 읽어, 테스트 소스의 한글·이모지 리터럴이 깨졌다. 첫 실행에서 2건 실패한 원인이 이것이다.
  - 조치: 테스트 리터럴을 `\u` 이스케이프로 바꿔 파일을 ASCII 전용으로 만들었다.
  - CI(ubuntu, UTF-8)는 영향이 없다.
  - 로컬에서 빌드한 APK는 main 소스의 한글 문자열 리터럴이 깨졌을 수 있다. 배포 APK는 CI에서만 만든다.

## 남은 순서 (사용자)

1. Firebase 콘솔에서 E 규칙을 게시한다. 그다음 `/echo_calib.json?shallow=true`가 200인지 확인한다.
2. Phase A 잔여 작업을 마친다: 익명 인증 활성 확인, A4 11대 대조표, keystore 오프라인 백업 2곳 이상.
3. 커밋·태그(v1.1.87)·푸시는 사용자 지시가 있을 때만 한다. `database.rules.json`은 태그 전에 커밋에 포함한다(CI가 규칙을 PUT하므로).
4. 첫 릴리스 서명 배포에서는 기기마다 1회 재설치가 필요하다(debug 서명에서 release 서명으로 바뀌므로).
5. `docs/security-review/`는 커밋하지 않는다.
