# 경영진 보고 자료

`SafeAlert_Executive_Brief_v1.1.70.pptx` — SafeAlert 소개·확산 검토용 보고 덱 (21장, 한국어, 장별 발표자 노트 포함).

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
| 3 챕터 01 | 현장 사진 (와이드 배너) | `drawable-nodpi/bg_forklift.jpg` |
| 6 제품 화면 | 메인 화면 배경 + 재구성한 UI | `drawable/bg_main.png` + `layout/activity_main.xml` |
| 7·8·9·18 | 역할·기능 아이콘 11종 | `drawable/ic_*.xml` (VectorDrawable → PNG) |
| 17 챕터 03 | 현장 사진 (와이드 배너) | `drawable-nodpi/bg_walker.jpg` |
| 21 요청 사항 | 현장 사진 | `drawable/bg_main.png` |

## 구성

3개 챕터 · 챕터마다 전면 이미지(또는 강조색) 구분 장을 둔다.

| 장 | 내용 |
|----|------|
| 1 | 표지 |
| 2 | 한 장 요약 |
| **3** | **챕터 01 — 현장과 제품** |
| 4 | 현장의 사각지대 |
| 5 | 해결 방식 — 기기 간 직접 감지 |
| 6 | 제품 화면 — 작업자가 보는 것 |
| 7 | 핵심 기능 6종 |
| 8 | 역할 조합별 판정 반경 |
| 9 | 세이프존 · 자동 뮤트 (오경보 대책) |
| **10** | **챕터 02 — 어떻게 동작하는가** |
| 11 | 동작 원리 5단계 (코드 시각화) |
| 12 | 1바이트 프로토콜 비트 레이아웃 (코드 시각화) |
| 13 | 시스템 구조 — 6계층·파일 수·줄 수 (코드 시각화) |
| 14 | 코드 규모 진단 — 상위 6개 파일 (코드 시각화) |
| 15 | 신뢰성 확보 로드맵 5단계 |
| 16 | 품질 관리 체계 — CI 회귀 게이트 |
| **17** | **챕터 03 — 확산과 결정** |
| 18 | 확산 시 이점 — 감지 링크 수 n(n-1)/2 |
| 19 | 도입 시나리오 (제안) |
| 20 | 한계와 리스크 |
| 21 | 요청 사항 |

## 타이포 스케일

경영진 보고용으로 크기 대비를 크게 벌린다. 장마다 시선이 먼저 닿는 요소를 하나만 둔다.

| 요소 | 크기 |
|------|------|
| 초대형 수치 (3,899 / 435) | 62–78pt |
| 장 제목 | 40pt bold |
| 챕터 문장 | 38pt bold |
| 카드 제목 | 15–22pt bold |
| 본문 | 12.5–14pt |
| 키커 · 캡션 | 10–11pt |

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
| 세이프존 3중 억제 · 존 비콘 | `BleService.kt:243-245, 574-581, 1100-1183`, `BleAdvertiser.kt:170, 300-303, 426-435` |
| 존 프로필(zoneMute · 진입 세기) | `06_utils/BeaconRegistry.kt:33-51` |
| 5초 체류 자동 뮤트 | `BleService.kt:222, 251, 563-569` (v1.1.61) |
| 화면 사이드바 · 조용한 실패 제거 | `06_utils/OverlayManager.kt:26-53` (v1.1.70), v1.1.64 상시 알림 |

## 재생성

```bash
npm install pptxgenjs sharp
node docs/presentation/build_assets.js     # 앱 리소스 → docs/presentation/assets/ (gitignore)
node docs/presentation/build_deck.js docs/presentation/SafeAlert_Executive_Brief_v1.1.70.pptx
```

## 갱신이 필요한 시점

- **앱 팔레트 변경 시** — `build_deck.js` 상단 `C` 토큰을 `colors.xml` 에 다시 맞춘다
- **Phase 2 종료 후** — 15장 로드맵 상태(2단계 완료), 16장 테스트 건수
- **Phase 3 종료 후** — 13·14장 코드 시각화(`BleService` 분해로 파일 구성·줄 수가 바뀐다).
  Phase 3 의 수용 게이트가 "동작 보존"이므로 기능·목적·확산 이점 장은 바뀌지 않는다
- **Phase 5 종료 후** — 18장 하단 주석(다수 기기 밀집 성능 한계), 20장 해당 행

## 웹페이지 버전

`web/safealert-brief.html` — 같은 내용을 스크롤형 랜딩페이지로 낸 것. 아티팩트로 게시되어 링크로 공유할 수 있다.

| 파일 | 역할 |
|------|------|
| `web/index.template.html` | 페이지 본문. 이미지 자리는 `__ASSET_*__` 자리표시자 |
| `web/build_page.js` | 앱 리소스에서 자산을 만들어 data URI 로 인라인 (외부 요청 0건) |
| `web/safealert-brief.html` | 빌드 결과 (약 0.43MB, 단일 파일) |

```bash
npm install sharp
node docs/presentation/web/build_page.js docs/presentation/web/safealert-brief.html
```

### 설계 메모

- **팔레트**: `.pptx` 와 동일하게 앱 `values/colors.xml` 토큰. 앱에 `values-night/` 가 없는 것과 같은 이유로 페이지도 단일 다크로 확정하고 배경·전경색을 명시적으로 칠한다
- **서체**: 웹폰트를 쓰지 않는다. 한글 웹폰트는 서브셋이 어긋나면 글리프가 통째로 깨지고(실제로 빌드 중 재현됨), 사내 열람 환경에서는 시스템 고딕이 가장 안정적이다. 개성은 크기·굵기·자간으로 만든다
- **줄바꿈**: `word-break: keep-all` — 한글을 음절이 아니라 어절 단위로 끊는다
- **판정 반경 도식**: 15m / 8m / 5m / 3m 를 실제 비례 축척으로 그린 SVG. 장식이 아니라 계측 도면이다
- **모션**: IntersectionObserver 진입 효과와 링·막대 드로잉만. `prefers-reduced-motion` 을 존중한다
