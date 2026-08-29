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
