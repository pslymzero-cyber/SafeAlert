# SafeAlert — CouSolve Report 판

사내 **CouSolve Report 양식**(7단계)에 SafeAlert 내용을 채운 덱이다.
`../company_form/` 의 LFP 판과 같은 내용을 다른 골격에 옮긴 것이며, 둘은 서로 독립이다.

| 장 | 단계 | 내용 |
|----|------|------|
| 1 | 표지 | Theme · 소속 · 성명 |
| 2 | 1-1 Problem Situation | 문제 배경 / 표준 / 현재 상황 / 차이 / 정도 / 근거 + GAP (현장 사진 + 커버리지 표) |
| 3 | 1-2 Target | 무엇을 / 어떻게 / 얼마나 / 언제까지 |
| 4 | 1-3 Fishbone | 4M — Method · Man · Machine · Material, Effect 상자에 뿌리 |
| 5 | 1-4 Cause Analysis | 원인 6개 × 확인 방법 × 결과 + 확인 한계 주석 |
| 6 | 1-5 5Why | 질문 / → 답 5쌍 + Root Cause |
| 7 | 2 Tradeoffs | 긴장 관계 5개 / 상쇄 방안 6개 (버전 태그 명시) |
| 8 | 3 Benchmark | I-PAS 비교표 + 확산 누계 막대 |
| 9 | 4 Solution | Short/Long term Countermeasure + Why Recommend + Implementation Plan 10행 |
| 10 | 5 Metrics | 성공 지표 6개 / 지표 확보 방법 5개 |
| 11 | 6 Andon | 실패 시나리오 (현장 운영 중 / 업데이트 · 수정 중) / Rollback 기준 |
| 12 | 7 Feedback Loop | 리더십 리뷰 · 현장 요구 반영 + 개선 효과 / 확산 계획 |

양식이 정확히 12장이라 장을 늘리거나 줄이지 않았다. 상단 단계 칩 · 둥근 컨테이너 ·
초록 라벨 · 표 틀은 양식 것을 그대로 두고 글자만 채운다.

## 표기 기준 — 2026 CouSolve Idea Contest 제출본

빈 양식만 보고 채우면 칸은 맞아도 말투가 다르다. 같은 작성자가 이 양식을 실제로 어떻게 채웠는지
(`2026 CouSolve Idea Contest — 간선 차량 입고 스케줄 준수율 향상`, WF11 / Willy · Ian · Dev)를
기준으로 삼았다. 그 자료에서 가져온 표기는 `_form.py` 아래쪽에 모여 있다.

| 표기 | 쓰는 곳 | 함수 |
|------|---------|------|
| `AS-IS` / `Pain Point`, `Target` / `Vision`, `Data` / `Issue` — 굵은 머리말 + 내용 | 1-1 | `kv` (셀 안에서는 `kvrow`) |
| `A vs. B`  ➡  우선한 쪽 | 2 Tradeoffs | `versus` · `arrow` |
| 단계 흐름 — 윗줄 굵게 / 아랫줄 기술 요소 이탤릭 | 1-2 `어떻게` | 본문에 직접 |
| 색 머리띠 KPI 카드 (정의 + 목표) | 5 Metrics | `kpi_card` |
| 장 맨 아래 이탤릭 한 줄 | 2 · 5 · 6 | `quote` |

원본에는 `문제를 해결해야 하는 이유` 번호 띠, 벤치마크 5단계 흐름 상자, `기대 효과` 큰 화살표도
있으나 이 양식에는 그 자리가 없어 옮기지 않았다. Benchmark 의 흐름 상자 자리에는 I-PAS 비교표와
확산 누계 막대를 넣었다 — 비교 대상이 타사 프로세스가 아니라 현행 장비이기 때문이다.

## 재생성

```bash
# 양식 원본(CouSolve Report .pptx)은 사내 문서라 저장소에 넣지 않는다. 로컬 경로를 넘긴다.
python3 build_cousolve_deck.py <CouSolve_Report_양식.pptx>
#   → base_cousolve.pptx (양식 사본, gitignore) → SafeAlert_CouSolve_2026.08.pptx
```

그림은 `../company_form/assets/` 를 그대로 쓴다 (`fig_aisle.jpg`).

## 양식을 다룰 때 걸린 것

- **병합 칸** — 5Why 의 첫 행(`* Most likely causes`)과 끝 행(`* Root Cause`), Cause Analysis 의
  마지막 행, Implementation Plan 의 머리행은 `gridSpan` 으로 묶여 있다. 2열째에 쓰면 보이지 않으므로
  **0열에 이어 쓴다.**
- **5Why 는 Why 하나에 두 줄** — 라벨 칸이 `rowSpan=2` 다. 위 줄에 질문, 아래 줄(` →` 이 찍혀 있는 줄)에 답.
- **어골도는 그룹 도형** — 뼈대를 건드리지 않고 가시선 위에 글자만 얹는다. 그룹 좌표계(`chOff`/`chExt`)
  → 슬라이드 좌표 변환은 `build_cousolve_deck.py` 의 `to_x` / `to_y` 두 줄이 전부다.
- **Theme 바** — 모든 본문 장 (1.71, 0.87) 위치의 빈 바에 주제 한 줄을 넣는다 (`theme_bar`).
- **글꼴** — 양식이 쓰는 `에스코어 드림 4 Regular` / `5 Medium` 을 그대로 쓴다. 이 저장소의 렌더 확인
  환경에는 없어 대체 글꼴로 보이지만, 사내 PC 에서는 양식과 같게 나온다.

## 숫자 원칙

없는 데이터는 만들지 않았다.

- **사고 건수 · 셧다운 발생 건수 · 절감 M/H 는 확보돼 있지 않다.** 감지 수단이 없어 집계된 적이 없다.
  1-1 `정도`, 1-4 주석, 5 Metrics 에 그대로 적었다. 이 문장이 빠지면 다른 숫자까지 의심받는다.
- 금액은 **절감액이 아니라 같은 범위를 하드웨어로 덮을 때의 환산액**이다. 1-2 `얼마나`, 3 Benchmark,
  8장 막대 캡션에 명시했다.
- **EOD 의 3m 자동 셧다운은 SafeAlert 가 대체하지 못한다.** 1-1 `표준`, 1-2 `무엇을`, 2 Tradeoffs,
  3 Benchmark 에 네 번 적혀 있다. 대체가 아니라 앞단이라는 것이 이 덱의 포지셔닝이다.
- 단가는 사용자 제공 (2026.08) — 차량 태그 525,000 · EOD 220,000 · 보행자 태그 110,000원.
  일반 WF 센터 PIT 4대 · 상시 3명 → 센터 1곳 3,310,000원, 17개 센터 56,270,000원.
- 반영 이력은 커밋에 대응한다 — 리뷰 1·2·3차 `v1.1.60` `v1.1.61` `v1.1.62`, 보호 끊김 `v1.1.64`,
  이탈 재발령 `v1.1.51`, 알림 본문 탭 `v1.1.68`, 사이드바 `v1.1.69` `v1.1.70`.

## 확인 사항

- WF11 · WF21 · WF25 서베이는 회수 전이다. 4 Solution 의 Implementation Plan 에 `진행` 으로만 두었고,
  5 Metrics 의 종합 만족도에는 `(회수 전)` 을 붙였다. 결과 수치를 채우기 전에 만족도를 말하지 않는다.
- 양식 원본과 `base_cousolve.pptx` 는 커밋하지 않는다 (`.gitignore`).
