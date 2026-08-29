# 경영진 보고 자료

`SafeAlert_Executive_Brief_v1.1.70.pptx` — SafeAlert 소개·확산 검토용 보고 덱 (16장, 한국어).

## 구성

| 장 | 내용 |
|----|------|
| 1 | 표지 |
| 2 | 한 장 요약 |
| 3 | 현장의 사각지대 (문제 정의) |
| 4 | 해결 방식 — 기기 간 직접 감지 |
| 5 | 핵심 기능 6종 |
| 6 | 역할 조합별 판정 반경 |
| 7 | 동작 원리 — 감지에서 경보까지 (코드 시각화) |
| 8 | 1바이트 프로토콜 비트 레이아웃 (코드 시각화) |
| 9 | 시스템 구조 — 6계층·파일 수·줄 수 (코드 시각화) |
| 10 | 코드 규모 진단 — 상위 6개 파일 (코드 시각화) |
| 11 | 신뢰성 확보 로드맵 5단계 |
| 12 | 품질 관리 체계 — CI 회귀 게이트 |
| 13 | 확산 시 이점 — 감지 링크 수 n(n-1)/2 |
| 14 | 도입 시나리오 (제안) |
| 15 | 한계와 리스크 |
| 16 | 요청 사항 |

각 장에 발표자 노트가 들어 있다.

## 수치 출처

덱의 모든 수치는 저장소 실측값이며 추정치를 쓰지 않았다.

| 수치 | 출처 |
|------|------|
| v1.1.70 / versionCode 126 | `app/build.gradle:15-16` |
| 27개 파일 11,636줄, 계층별 파일·줄 수 | `app/src/main/java/com/wf11/safealert/**` |
| `BleService` 3,899줄 | `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` |
| 경고 15m / 위험 8m, 그 외 5m / 3m | `06_utils/DevSettings.kt:626-648` |
| 1바이트 2-2-2-2 비트 레이아웃 | `02_ble/BleConstants.kt` |
| 필터 캐스케이드·주기 | `MedianFilter`(3샘플) → `RssiPreFilter` → `KalmanFilter`, `BleService.kt:1473-1511` |
| 단위 테스트 17건 / 488줄, CI 차단 게이트 | `app/src/test/**`, `.github/workflows/release.yml:44-85` |
| 로드맵 5단계·진행률 | `.planning/ROADMAP.md`, `.planning/STATE.md` |
| 한계·리스크 항목 | `.planning/PROJECT.md` Constraints, `.planning/codebase/CONCERNS.md` |

## 재생성

```bash
npm install pptxgenjs
node docs/presentation/build_deck.js docs/presentation/SafeAlert_Executive_Brief_v1.1.70.pptx
```

## 갱신이 필요한 시점

- **Phase 2 종료 후** — 11장 로드맵 상태(2단계 완료), 12장 테스트 건수
- **Phase 3 종료 후** — 9·10장 코드 시각화(`BleService` 분해 결과로 파일 구성·줄 수가 바뀐다).
  Phase 3 의 수용 게이트가 "동작 보존"이므로 기능·목적·확산 이점(2~8·13~16장)은 바뀌지 않는다
- **Phase 5 종료 후** — 13장 하단 주석(다수 기기 밀집 성능 한계), 15장 해당 리스크 행
