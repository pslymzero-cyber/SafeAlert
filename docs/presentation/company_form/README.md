# 사내 폼 보고 자료

매니저 지시(2026.08)에 맞춘 7장짜리 보고본이다. 수치·코드 시각화 중심이던 기존 덱
(`../SafeAlert_Executive_Brief_v1.1.70.pptx`)과 달리, **왜 만들었나 · 기대효과 ·
장단점 · 피드백 · 시연**만 담고 사내 표준 폼(LFP 지게차 자료)을 그대로 쓴다.

| 장 | 내용 |
|----|------|
| 1 | 표지 |
| 2 | Content |
| 3 | 1. 개발 배경 — 현장 문제 / 왜 앱으로 만들었나 |
| 4 | 2. 기대효과 |
| 5 | 3. 장단점 |
| 6 | 4. 사용자 피드백 — 오더십 리뷰 3차 반영 / 현장 요구 |
| 7 | 5. 시연 — 동작·설정 화면 + 재생되는 시연 영상 |

## 재생성

```bash
# 사내 표준 폼 원본(LFP 지게차 .pptx)을 base 로 만들어 둔 뒤 실행한다.
# 원본은 사외비 문서라 저장소에 넣지 않는다 — 로컬에 두고 아래 절차를 따른다.
python3 -c "import zipfile; zipfile.ZipFile('LFP.pptx').extractall('work')"
python3 ../../../.claude/skills/pptx/scripts/add_slide.py work/ slide4.xml --after slide4.xml   # 4회
#   → ppt/presentation.xml 의 <p:sldIdLst> 를 표지·목차·본문 5장만 남기도록 편집
python3 ../../../.claude/skills/pptx/scripts/clean.py work/
(cd work && zip -qXr ../base.pptx .)
python3 build_company_deck.py            # base.pptx → SafeAlert_보고_2026.08.pptx
```

`build_company_deck.py` 는 템플릿 문단의 `<a:pPr>`·`<a:rPr>` 을 복제해 새 문단을 만든다.
글자만 갈아 끼우는 방식이라 사내 폼의 서식(글자 크기·줄간격·①/→ 위계)이 어긋나지 않는다.

## 재료

| 산출물 | 만드는 곳 |
|--------|-----------|
| `assets/screen_running.png` · `screen_settings.png` | `../web/render_screens.js` |
| `web/safealert-sim.mp4` · `sim-poster.jpg` | `../web/record_demo.js` |

## 내용 근거

| 항목 | 출처 |
|------|------|
| 현장 문제(렉 높이 · 전파 흡수 · 단독 작업 · 혼재 동선) | 기존 덱 4장, 사용자 확인 사항 |
| 판정 반경 15/8m · 5/3m, FC 대비 2배 | `06_utils/DevSettings.kt:626-648`, 사용자 수정본 8장 |
| 오더십 리뷰 1·2·3차 | 커밋 `v1.1.60` · `v1.1.61` · `v1.1.62` 본문 |
| 보호 끊김 알림 승격 | 커밋 `v1.1.64` |
| 이탈 후 재발령 억제 | 커밋 `v1.1.51` |
| 알림 본문 탭 = 무음 | 커밋 `v1.1.68` |
| 엣지 부착 사이드바 | 커밋 `v1.1.69` · `v1.1.70` |
| 20대 이상 화면 반응 저하 | `.claude/CLAUDE.md` Performance 제약 |

## 확인 사항

- 표지의 조직명은 참조 자료(`Flex Fulfillment Dynamic Ops / Waterflex`)를 그대로 뒀다.
  보고 주체가 다르면 표지 두 번째 줄만 고치면 된다.
- 사내 폼이라 모든 장에 `Coupang Fulfillment Services Confidential and Proprietary`
  표기가 들어간다. 저장소 공개 범위를 확인하고 보관할 것.

## 표현 장치 — CouSolve Idea Contest 에서 가져온 것

폼은 사내 표준(LFP)을 그대로 쓰되, 슬라이드 안쪽 표현은 `2026 CouSolve Idea Contest` 자료의
장치로 바꿨다. 그 자료의 7단계 프레임워크(Problem Statement → Tradeoffs → … → Feedback Loop)를
그대로 따르지는 않는다 — 지금 목차에 맞는 장치만 골라 옮겼다. 코드는 `_devices.py`.

| 장 | 바꾼 것 | 가져온 장치 |
|----|---------|-------------|
| 1. 개발 배경 | ①②③ 나열 → 회색 라벨 + `AS-IS / Pain Point` · `Data / Issue` | Problem Statement |
| 2. 기대효과 | ①~⑥ 나열 → `구분 / As-is / To-be` 3열 표 | GOAL 의 As-is·To-be |
| 3. 장단점 | 장점 5 · 단점 4 나열 → `A vs. B → 택한 것` + 화살표 + 판단 근거 3행, 그 아래 `남는 한계` | Tradeoffs |
| 4. 사용자 피드백 | 나열 → `차수 / 반영 내용 / 결과` 표 + `항목 / Before / After` 표 | Feedback Loop |
| 5. 시연 | 목록 → `신호 수신 → 거리 추정 → 등급 판정 → 3중 경보` 흐름 한 줄 (아래에 기술 요소) | Solution |
| 1장 하단 | — | 번호 매긴 한 줄 요약 + 이탤릭 결론 문장 |

장단점을 트레이드오프로 바꾼 것이 가장 큰 변화다. 단점을 결함 목록으로 늘어놓는 대신
**무엇을 얻으려고 무엇을 내줬는지**로 적으면, 같은 사실이 변명이 아니라 설계 판단으로 읽힌다.
반대로 트레이드오프가 아닌 것(미설치 기기 비감지 · 밀집 시 지연)은 `남는 한계`로 따로 뺐다.

## 삽화

각 본문 장 오른쪽에 이해를 돕는 그림을 하나씩 둔다. `render_figures.js` 가 굽는다.

| 장 | 그림 | 내용 |
|----|------|------|
| 1. 개발 배경 | `fig_aisle.jpg` | 렉 사이 통로 사진. 앱 배경 사진 원본(`bg_epj.jpg`)을 어둡게 하지 않고 가로로 자른 것 |
| 2. 기대효과 | `fig_pairs.png` | 단말 3 · 5 · 10대의 상호 감지쌍 (3 · 10 · 45) |
| 3. 장단점 | `fig_radius.png` | 역할 조합별 판정 반경 — 실제 비례 축척 (9px = 1m) |
| 4. 사용자 피드백 | `fig_zone.png` | 세이프존 억제 개념도 |

도식 원본은 `figures.html` 이다. 흰 배경 사내 폼에 맞춰 밝은 색으로 그렸다 —
어두운 덱(`../assets/`)의 자산은 페이드가 들어가 있어 흰 배경에서 탁하다.

```bash
node render_figures.js      # → company_form/assets/fig_*.png|jpg
```

## 시연 영상의 경보음

헤드리스 크로미움은 화면만 녹화하고 소리는 담지 못한다. 그래서 `record_demo.js` 는
시뮬레이터가 경보음을 낸 **시각**을 받아 적어 두고(`AudioContext.createOscillator` 후킹),
녹화가 끝난 뒤 같은 음을 그 자리에 얹는다.

- 음은 `sim/sim.js` 의 `beep()` 을 그대로 옮겼다 — square 파형, 위험 1180Hz · 경고 760Hz,
  10ms 상승 후 150ms 지수 감쇠, 180ms 정지. 발령 간격도 위험 320ms · 경고 900ms 로 같다
- 녹화 시작 지연은 `(닫은 시각 − 기준 시각) − 영상 길이` 로 재서 보정한다
- 소리 크기만 슬라이드쇼에서 들리도록 정규화했다 (원래 gain 0.08)

**이 소리는 시뮬레이터의 경보음이지 실제 단말 녹음이 아니다.** 앱은 안드로이드
`ToneGenerator` 의 CDMA 톤(경고 `TONE_CDMA_ALERT_CALL_GUARD`, 위험
`TONE_CDMA_EMERGENCY_RINGBACK`)을 쓰므로 음색이 다르다. 실제 소리를 넣으려면
단말 화면 녹화본이 필요하다. (`res/raw/alert_warning.wav` · `alert_danger.wav` 는
서로 바이트까지 같은 미사용 파일이라 쓰지 않았다.)
