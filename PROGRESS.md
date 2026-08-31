# PROGRESS — SafeAlert

최종 갱신: 2026-08-31 / 작성자: claude

---

## 현재 위치

- 작업 디렉터리: `C:\Users\pslym\Downloads\SafeAlert`
- 브랜치: `master`, HEAD = `849eb05`
- 진행 단계: **Phase 3 (03-refactor) 마감 완료 — 코드·문서·커밋 전부 종료**
- 다음 행동: **사용자 지시 대기**. Phase 4 (기기 상태 단일화) 계획은 아직 착수 안 함.

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
5. ~~백업 위치: `%LOCALAPPDATA%\Temp\claude\BleService.kt.*bak`~~ **전량 소실**. 원본은 `git show 849eb05^:app/src/main/java/com/wf11/safealert/03_service/BleService.kt` 로 복원 가능

### 작업 방식 제약 (사용자 지시)
- GSD 스킬 재호출 금지, agent 스폰 금지 — 오케스트레이터가 직접 수행
- `BleService.kt` 전체 읽기 금지. `grep -n` 으로 심볼 경계만 특정
- 브랜치 생성·전환 금지 / `--no-verify` 금지 / **커밋은 사용자가 요청할 때만**
- Read 는 offset·limit 200줄 이내, 셸 출력은 `| head -50` 등으로 제한
- 줄 번호는 쓰기 전 `grep -n` 으로 재확인
- 한국어 출력, 파일에 이모지 금지
- `jq` 없음 (`node -e` 사용)

---

## 남은 순서

1. **[완료]** Phase 3 커밋 마감 — 단일 커밋 `849eb05`. T1/T2/T3 3분할은 불가로 판정 (아래 미해결 이슈 참조)
2. **[대기]** Phase 4 (기기 상태 단일화) — 40여 개 분산 Map 을 `DeviceTrackingState` 로 통합. 사용자 지시 전까지 착수 금지
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
SafeAlert 프로젝트 이어서 작업한다. 먼저 C:\Users\pslym\Downloads\SafeAlert\PROGRESS.md 를 읽고 현재 상태를 파악해라.

요약: Phase 3 (BleService 분해 리팩터링) 전부 마감. 코드 T1/T2/T3 + SUMMARY/ROADMAP/STATE 문서 갱신 + 단일 커밋 849eb05 완료. 테스트 43건 통과. 다음은 Phase 4 (기기 상태 단일화) 이나 아직 착수 금지.

지켜야 할 제약:
- 커밋은 내가 명시 요청할 때만. 브랜치 생성·전환 금지, --no-verify 금지
- GSD 스킬 재호출 금지, agent 스폰 금지 — 직접 수행
- BleService.kt 전체 읽기 금지. grep -n 으로 심볼 경계만 특정
- 판정 로직·반경 값(15/8/5/3m)·기대값 배열 변경 금지
- UwbRanger.kt / UwbCalibrator.kt 수정 금지
- .gitignore, .planning/phases/01-ci/01-UAT.md, 01-VALIDATION.md, 루트의 pptx/docx/pdf/png 잔여물은 손대지 말 것
- 한국어 출력, 파일에 이모지 금지
- 각 태스크 종료 시 ./gradlew testDebugUnitTest 실행 -> 결과 보고 -> 멈춤 -> 내 확인 후 다음

다음 할 일은 내가 지시한다. PROGRESS.md 읽고 대기해라.
```
