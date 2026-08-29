# 경영진 보고 자료

`SafeAlert_Executive_Brief_v1.1.70.pptx` — SafeAlert 소개·확산 검토용 보고 덱 (17장, 한국어, 장별 발표자 노트 포함).

## 디자인 시스템

**새로 만들지 않았다.** 앱이 v1.1.65 "다크 UI 통일"에서 확정한 고정 다크 팔레트(`app/src/main/res/values/colors.xml`)를 그대로 쓴다.

| 역할 | 토큰 | 값 |
|------|------|-----|
| 배경 | `sa_bg` | `#0B1220` |
| 카드 | `sa_surface` / `sa_surface_alt` | `#1A2233` / `#161D2B` |
| 글자 3단 | `sa_text_primary / secondary / tertiary` | `#F1F5F9` / `#C3CDDC` / `#8B98AC` |
| 강조 | `sa_accent` / `sa_accent_dim` | `#7DD3FC` / `#60A5FA` |
| 상태 | `sa_safe` / `sa_warning` / `sa_danger` | `#4ADE80` / `#FCD34D` / `#FB7185` |

반복 모티프도 앱에서 가져왔다 — 섹션 헤더 앞의 작은 정사각 불릿(`shape_bullet_square`)과 라운드 서페이스 카드.

## 사용한 실제 자산

`build_assets.js` 가 앱 리소스에서 직접 추출한다 (추출물은 커밋하지 않는다).

| 슬라이드 | 자산 | 원본 |
|----------|------|------|
| 1 표지 | 스플래시 아트 (로고 포함) | `drawable/bg_splash.png` |
| 3 사각지대 | 현장 사진 | `drawable-nodpi/bg_epj.jpg` |
| 5 제품 화면 | 메인 화면 배경 + 재구성한 UI | `drawable/bg_main.png` + `layout/activity_main.xml` |
| 6·7·14 | 역할·기능 아이콘 11종 | `drawable/ic_*.xml` (VectorDrawable → PNG) |
| 17 요청 사항 | 현장 사진 | `drawable/bg_main.png` |

## 구성

| 장 | 내용 |
|----|------|
| 1 | 표지 |
| 2 | 한 장 요약 |
| 3 | 현장의 사각지대 (문제 정의) |
| 4 | 해결 방식 — 기기 간 직접 감지 |
| 5 | 제품 화면 — 작업자가 실제로 보는 것 |
| 6 | 핵심 기능 6종 |
| 7 | 역할 조합별 판정 반경 |
| 8 | 동작 원리 — 감지에서 경보까지 (코드 시각화) |
| 9 | 1바이트 프로토콜 비트 레이아웃 (코드 시각화) |
| 10 | 시스템 구조 — 6계층·파일 수·줄 수 (코드 시각화) |
| 11 | 코드 규모 진단 — 상위 6개 파일 (코드 시각화) |
| 12 | 신뢰성 확보 로드맵 5단계 |
| 13 | 품질 관리 체계 — CI 회귀 게이트 |
| 14 | 확산 시 이점 — 감지 링크 수 n(n-1)/2 |
| 15 | 도입 시나리오 (제안) |
| 16 | 한계와 리스크 |
| 17 | 요청 사항 |

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
| 제품 화면 구성 | `app/src/main/res/layout/activity_main.xml` |

## 재생성

```bash
npm install pptxgenjs sharp
node docs/presentation/build_assets.js     # 앱 리소스 → docs/presentation/assets/ (gitignore)
node docs/presentation/build_deck.js docs/presentation/SafeAlert_Executive_Brief_v1.1.70.pptx
```

## 갱신이 필요한 시점

- **앱 팔레트 변경 시** — `build_deck.js` 상단 `C` 토큰을 `colors.xml` 에 다시 맞춘다
- **Phase 2 종료 후** — 12장 로드맵 상태(2단계 완료), 13장 테스트 건수
- **Phase 3 종료 후** — 10·11장 코드 시각화(`BleService` 분해로 파일 구성·줄 수가 바뀐다).
  Phase 3 의 수용 게이트가 "동작 보존"이므로 기능·목적·확산 이점 장은 바뀌지 않는다
- **Phase 5 종료 후** — 14장 하단 주석(다수 기기 밀집 성능 한계), 16장 해당 행
