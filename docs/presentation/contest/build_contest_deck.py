# CouSolve Idea Contest 제출 양식(16장)에 SafeAlert 내용을 채운다.
#   python3 build_contest_deck.py <CouSolve_Contest_양식.pptx>
#
# 양식 원본은 사내 문서라 저장소에 넣지 않는다 — 로컬 경로를 인자로 넘긴다.
# 양식의 3 · 4장은 도형이 하나도 없는 빈 장(상단 탭도 없다)이라 걷어낸다. 남는 14장:
#   1 표지 / 2 01.결론 / 3 01.Summary / 4 Summary 자유 / 5 02.Problem
#   6 02.5Why / 7 03.Tradeoffs / 8 04.Benchmark / 9 05.Solution
#   10 Solution 표 / 11 Solution 자유 / 12 06.Metrics / 13 07.Andon / 14 08.Feedback
#
# 숫자 원칙 — 실측이 없는 칸은 비우지 않고 추정치를 넣되 **밑줄**로 표시한다.
# 자료 전체에서 밑줄은 '실측 아님' 한 가지 뜻만 갖는다. 가정값은 하나뿐이고
# 나머지는 거기서 계산된다 (12장 산출 근거표).
import os, shutil, sys
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR

import _contest as C

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(os.path.dirname(HERE), "company_form", "assets")
OUT = os.path.join(HERE, "SafeAlert_CouSolve_Contest.pptx")

# ── 추정치 ──────────────────────────────────────────────────
PAIRS, SHIFT_H, RATE = 21, 9, 0.5      # 감지 쌍 · 1교대 · 쌍당 시간당 조우(유일한 가정값)
_h = lambda x: int(x + 0.5)
WARN = _h(PAIRS * SHIFT_H * RATE)      # 21 × 9 × 0.5 = 94.5 → 95
RATIO = round((8 / 15) ** 2, 2)        # 반경 비의 제곱 0.284 → 0.28
DANGER = _h(WARN * RATIO)              # 95 × 0.28 = 26.6 → 27
EST = {"u": True}
ESTB = {"u": True, "bold": True}

if len(sys.argv) < 2:
    raise SystemExit("사용법: python3 build_contest_deck.py <CouSolve_Contest_양식.pptx>")
BASE = os.path.join(HERE, "base_contest.pptx")
shutil.copyfile(os.path.abspath(sys.argv[1]), BASE)
prs = Presentation(BASE)
assert len(prs.slides) == 16, f"양식이 16장이어야 한다 (지금 {len(prs.slides)}장)"

# 빈 3 · 4장 제거 — 상단 탭도 없는 백지라 제출본에 그대로 두면 결번처럼 보인다
lst = prs.slides._sldIdLst
for i in (3, 2):
    rId = lst[i].rId
    prs.part.drop_rel(rId)
    del lst[i]
S = prs.slides


def pic(slide, name, x, y, w, ratio, cap=None):
    h = w / ratio
    slide.shapes.add_picture(os.path.join(ASSETS, name), Inches(x), Inches(y),
                             Inches(w), Inches(h))
    if cap:
        C.text(slide, x, y + h + 0.05, w, 0.22, [[(cap, {"size": 8, "color": C.INK3})]],
               align=PP_ALIGN.CENTER)
    return y + h


# ── 1. 표지 ─────────────────────────────────────────────────
s = S[0]
C.fill(C.named(s, "TextBox 1"),
       [[("지게차 · EPJ · 보행자 사각지대 접근 경보", {"bold": True, "size": 21, "color": C.NAVY})],
        [("— 지급된 단말끼리 BLE 로 서로를 감지하는 앱 (SafeAlert)", {"size": 12, "color": C.INK2})]],
       margin=0.02, space=1.25)
C.fill(C.at(s, 1.74, 6.47), [[("WF11", {"bold": True, "size": 12})]],
       align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE, margin=0.05)
C.fill(C.at(s, 5.84, 6.47), [[("Ian", {"bold": True, "size": 12})]],
       align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE, margin=0.05)

# ── 2. 01. 결론 ─────────────────────────────────────────────
s = S[1]
CONC = [
    (2.11, "I-PAS 가 못 보는 조합을, 이미 지급된 단말이 본다",
     "보행자끼리 · EPJ · 미장착 장비 — 태그를 사지 않고 덮는다.  추가 하드웨어 0원."),
    (3.63, "제안이 아니라 이미 돌고 있다",
     "v1.0.1 → v1.1.70, 3개월 70회+ 배포.  WF11 · WF21 · WF25 에서 실사용 중."),
    (5.19, "태그는 인원에 비례해 늘고, 앱은 제곱으로 는다",
     "현장 20명 기준 태그 80쌍 vs 앱 276쌍.  대상이 늘어도 추가 구매가 없다."),
]
for i, (y, head, sub) in enumerate(CONC, start=1):
    C.fill(C.at(s, 1.85, y),
           [[(f"{i}.  ", {"bold": True, "size": 15, "color": C.INK3}),
             (head, {"bold": True, "size": 15, "color": C.NAVY})],
            [(sub, {"size": 11, "color": C.INK2})]],
           anchor=MSO_ANCHOR.MIDDLE, margin=0.30, space=1.35, gap=4)

# ── 3. 01. Summary ──────────────────────────────────────────
s = S[2]
C.fill(C.body_of(s, "Background"),
       [[("I-PAS 는 태그를 단 장비 · 인원만 덮는다. 보행자끼리 · EPJ · 미장착 장비 구간에는 "
          "감지도 알림도 없어, 적재 파렛트에 시야가 가리고 소음에 경적이 묻히는 교차 구간이 "
          "주의력에만 맡겨져 있다.", {"size": 10.5})]],
       margin=0.20, space=1.3, top=C.TOPCHIP)
C.fill(C.body_of(s, "Solution"),
       [[("이미 지급된 단말에 앱을 설치해 BLE 로 서로를 감지한다.", {"bold": True, "size": 11.5})],
        [("앵커 · 배선 · 서버가 없다. 착용할 태그도 없다.", {"size": 10, "color": C.INK2})],
        [("신호 수신  →  거리 추정  →  등급 판정  →  3중 경보",
          {"bold": True, "size": 11, "color": C.NAVY})],
        [("BLE 광고  →  3단 필터  →  상태머신  →  소리 · 진동 · 화면",
          {"italic": True, "size": 9, "color": C.INK3})],
        [("판정 반경 — 지게차가 낀 조합 경고 15m / 위험 8m,  그 밖의 조합 5m / 3m.",
          {"size": 10})],
        [("장비 정지는 하지 않는다. 3m 자동 셧다운은 I-PAS EOD 가 그대로 맡는다.",
          {"size": 10, "color": C.RED})]],
       margin=0.20, space=1.3, gap=5, top=C.TOPCHIP)

r = C.body_of(s, "Result")
C.fill(r, [[("", {})]], margin=0.02)
RX, RY, RW = 6.30, 2.38, 6.27
C.text(s, RX + 0.24, RY + 0.52, RW - 0.48, 0.24,
       [[("덮이는 접근 조합 — 현장 인원이 늘 때", {"bold": True, "size": 10.5, "color": C.NAVY})]])
PEOPLE = [3, 5, 10, 20]
C.chart2(s, RX + 0.14, RY + 0.80, RW - 0.28, 1.92,
         [f"{p}명" for p in PEOPLE],
         [("태그 방식 (차량 4대 · 인원당 110,000원)", [4 * p for p in PEOPLE]),
          ("SafeAlert (앱 설치 · 추가 0원)", [(4 + p) * (3 + p) // 2 for p in PEOPLE])],
         label_size=8.5, cat_size=8.5)
C.text(s, RX + 0.24, RY + 2.86, RW - 0.48, 0.24,
       [[("1교대(9시간) 접근 조우 추정", {"bold": True, "size": 10.5, "color": C.NAVY}),
         ("   밑줄 = 추정치", {"size": 8.5, "color": C.INK3, "u": True})]])
for i, (lab, val, col) in enumerate([("경고 반경 15m", f"{WARN}회", C.AMBER),
                                     ("위험 반경 8m", f"{DANGER}회", C.RED)]):
    C.kpi(s, RX + 0.20 + i * 2.98, RY + 3.14, 2.82, 0.98,
          "", lab, "Firebase 경보 이력 집계 예정", val, col)
C.text(s, RX + 0.24, RY + 4.24, RW - 0.48, 0.22,
       [[("산출 근거 12장 · 가정값은 ‘쌍당 시간당 0.5회’ 하나뿐이다", {"size": 8, "color": C.INK3})]])

# ── 4. Summary 자유 양식 — 화면과 판정 반경 ─────────────────
s = S[3]
C.fill(C.at(s, 0.78, 2.37), [[("", {})]], margin=0.02)
C.text(s, 1.00, 2.56, 11.4, 0.26,
       [[("무엇이 어떻게 보이는가", {"bold": True, "size": 13, "color": C.NAVY}),
         ("     상시 알림 · 경보 화면 · 판정 반경", {"size": 10.5, "color": C.INK2})]])
pic(s, "fig_radius.png", 1.05, 2.98, 3.80, 648 / 358,
    "역할 조합별 판정 반경 — 실제 비례 축척")
pic(s, "fig_pairs.png", 5.30, 3.52, 3.55, 728 / 278,
    "단말이 늘수록 감지쌍은 제곱으로 (3대 3쌍 → 10대 45쌍)")
pic(s, "fig_zone.png", 9.30, 3.52, 3.05, 668 / 288,
    "세이프존 — 존 안에서는 경보를 억제한다")
C.lines(s, 1.05, 5.62, 11.3, [
    [("·  ", {"color": C.INK3}), ("경보는 소리 · 진동 · 화면 세 갈래로 동시에 난다", {"bold": True}),
     ("  —  소음 · 장갑 · 시야 어느 하나가 막혀도 나머지가 전달된다", {"color": C.INK2})],
    [("·  ", {"color": C.INK3}), ("같은 기기 · 같은 등급에 5초 이상 머물면 자동 음소거", {"bold": True}),
     ("  —  표시 · 기록은 유지하고, 등급이 오르면 즉시 재발령 (v1.1.61)", {"color": C.INK2})],
    [("·  ", {"color": C.INK3}), ("휴게실 · 충전 구역에는 세이프존 비콘", {"bold": True}),
     ("  —  존 안의 기기는 상대에게도 안전으로 보인다 (v1.1.62)", {"color": C.INK2})],
    [("·  ", {"color": C.INK3}), ("감지가 멈추면 상시 알림에 이상 표시", {"bold": True}),
     ("  —  꺼진 줄 모르고 쓰는 상황을 없앤다 (v1.1.64)", {"color": C.INK2})],
], size=10, gap=0.31)

# ── 5. 02. Problem Statement ────────────────────────────────
s = S[4]
C.fill(C.body_of(s, "문제의 배경"),
       [[("AS-IS", {"bold": True, "color": C.NAVY}), (" :  ", {"color": C.INK3}),
         ("존과 로케이션 사이 교차 구간에서 PIT 와 보행자가 서로를 보지 못한 채 접근한다.", {})],
        [("Pain Point", {"bold": True, "color": C.NAVY}), (" :  ", {"color": C.INK3}),
         ("적재 파렛트가 시야를 가리고, 장비 소음에 경적이 묻혀 인지가 늦다.", {})],
        [("현행 I-PAS 는 3m 안에서 장비를 자동 셧다운한다. 그 앞 구간, 그리고 태그가 없는 "
          "조합에는 감지 수단이 없다.", {"color": C.INK2})]],
       size=10.5, margin=0.20, space=1.35, gap=5, top=C.TOPCHIP)
C.fill(C.body_of(s, "표준/목표"),
       [[("Target", {"bold": True, "color": C.NAVY}), (" :  ", {"color": C.INK3}),
         ("접근을 사전에 감지해 사람에게 알린다 — 태그 유무와 무관하게.", {})],
        [("Vision", {"bold": True, "color": C.NAVY}), (" :  ", {"color": C.INK3}),
         ("장비가 3m 에서 멈추기 전에, 15m · 8m 에서 사람이 먼저 안다.", {})]],
       size=10, margin=0.18, space=1.3, top=C.TOPCHIP)
C.fill(C.body_of(s, "현재 상황"),
       [[("Data", {"bold": True, "color": C.NAVY}), (" :  ", {"color": C.INK3}),
         ("덮이는 접근 조합은 12쌍 (차량 4대 × 상시 3명). 태그를 산 만큼만 는다.", {})],
        [("Issue", {"bold": True, "color": C.NAVY}), (" :  ", {"color": C.INK3}),
         ("보행자끼리 · EPJ · 미장착 장비는 대상 밖 — 위험을 확인할 방법 자체가 없다.", {})]],
       size=10, margin=0.18, space=1.3, top=C.TOPCHIP)

why = C.body_of(s, "문제를 해결해야 하는 이유")
C.fill(why, [[("", {})]], margin=0.02)
WX, WY, WW = 0.77, 4.88, 11.80
C.text(s, WX + 0.28, WY + 0.44, WW - 0.56, 0.30,
       [[("1. 사각지대 해소", {"bold": True, "size": 12.5, "color": C.NAVY}),
         ("        2. 인프라 없는 확산", {"bold": True, "size": 12.5, "color": C.NAVY}),
         ("        3. 오경보 억제", {"bold": True, "size": 12.5, "color": C.NAVY}),
         ("        4. 조용한 실패 제거", {"bold": True, "size": 12.5, "color": C.NAVY})]],
       align=PP_ALIGN.CENTER)
C.text(s, WX + 0.28, WY + 0.86, WW - 0.56, 0.28,
       [[("1교대(9시간) 기준 경고 반경 진입 ", {"size": 11}),
         (f"약 {WARN}회", {"bold": True, "size": 11, "color": C.RED, "u": True}),
         (", 그중 위험 반경까지 ", {"size": 11}),
         (f"약 {DANGER}회", {"bold": True, "size": 11, "color": C.RED, "u": True}),
         ("  (밑줄 = 추정치, 산출 근거 12장).  사고 건수는 감지 수단이 없어 집계된 적이 없다.",
          {"size": 10, "color": C.INK2})]],
       align=PP_ALIGN.CENTER)
C.text(s, WX + 0.28, WY + 1.28, WW - 0.56, 0.28,
       [[("“지금 이 구간은 ", {"italic": True, "size": 10.5, "color": C.INK2}),
         ("설비가 아니라 사람의 주의력", {"italic": True, "bold": True, "size": 10.5}),
         ("이 지키고 있습니다. 주의력은 교대 끝에 떨어집니다.”",
          {"italic": True, "size": 10.5, "color": C.INK2})]],
       align=PP_ALIGN.CENTER)

# ── 6. 02. Problem Statement _ 5Why ─────────────────────────
s = S[5]
C.fill(C.named(s, "TextBox 17"),
       [[("Most likely Causes :  ", {"size": 10.5, "color": C.INK2}),
         ("감지 대상이 태그를 단 장비와 태그를 받은 인원으로만 정의돼 있다",
          {"bold": True, "size": 10.5})]], margin=0.0)
C.named(s, "TextBox 17").width = Inches(11.0)
C.fill(C.named(s, "TextBox 18"),
       [[("Root Causes :  ", {"size": 10.5, "color": C.INK2}),
         ("감지 대상 범위가 안전 요구가 아니라 태그 구매 수량으로 결정되고 있다",
          {"bold": True, "size": 10.5, "color": C.RED}),
         ("   (외부 상용 제품도 같은 구조 — 8장)", {"size": 9, "color": C.INK3})]], margin=0.0)
C.named(s, "TextBox 18").width = Inches(11.0)

t = [sh.table for sh in s.shapes if sh.has_table][0]
WHY = [
    ("왜 사각지대에서 위험한가?", "서로를 보지 못한 채 접근하기 때문이다."),
    ("왜 보지 못하는가?", "적재 파렛트가 시야를 가리고, 장비 소음에 경적이 묻히기 때문이다."),
    ("왜 그 구간에 보완 수단이 없는가?",
     "안전 설비를 장비 단위로 도입해 왔기 때문이다 — 차량에 리더를 달고, 그 차량이 감지한다."),
    ("왜 장비 단위인가?",
     "감지 대상이 ‘차량’ 으로 정의돼 있고, 사람은 태그를 받은 만큼만 대상이 되기 때문이다."),
    ("왜 인원 전원이 대상이 아닌가?",
     "인원 수만큼 태그를 사야 하는 구조라, 대상을 상시 인원 3명으로 한정했기 때문이다."),
]
for i, (q, a) in enumerate(WHY):
    C.cell(t.cell(i * 2, 1), [[(q, {"bold": True, "color": C.NAVY})]], 11, PP_ALIGN.LEFT)
    C.cell(t.cell(i * 2 + 1, 1),
           [[("→  ", {"color": C.INK3}), (a, {})]], 10.5, PP_ALIGN.LEFT)

# ── 7. 03. Tradeoffs ────────────────────────────────────────
# 양식이 3쌍짜리다. 왼쪽은 무엇을 포기했는지, 오른쪽은 포기한 쪽을 무엇으로 메웠는지.
s = S[6]
TRADE = [
    (2.37, [[("감지 민감도  vs.  경보 피로", {"bold": True, "size": 12})],
            [("넓게 울리면 작업자가 꺼버린다. 꺼 둔 단말은 보호가 0 이다.",
              {"size": 10, "color": C.INK2})]],
     [[("민감도를 택하고, 울릴 곳을 좁혔다", {"bold": True, "size": 12, "color": C.NAVY})],
      [("같은 기기 · 같은 등급 5초 이상이면 자동 음소거 (v1.1.61) · 휴게실 · 충전 구역 "
        "세이프존 (v1.1.62) · 이탈 후 재발령 억제 (v1.1.51)", {"size": 9.5, "color": C.INK2})]]),
    (3.96, [[("앱 경보  vs.  물리적 정지", {"bold": True, "size": 12})],
            [("경보는 사람이 반응해야 효과가 난다. 통제 위계상 하위다.",
              {"size": 10, "color": C.INK2})]],
     [[("물리적 정지는 포기했다", {"bold": True, "size": 12, "color": C.RED})],
      [("자동 정지(공학적 제어)가 상위, 경보(관리적 제어)가 하위다. 상위를 대체하지 않는다 — "
        "I-PAS EOD 를 그대로 두고, 상위가 닿지 않는 조합에만 하위를 새로 넣는다.",
        {"size": 9.5, "color": C.INK2})]]),
    (5.56, [[("배포 속도  vs.  판정 재현성", {"bold": True, "size": 12})],
            [("3개월 70회 배포로 즉시 대응했지만, 고친 증상이 되돌아왔다.",
              {"size": 10, "color": C.INK2})]],
     [[("재현성을 택하고, 속도를 늦췄다", {"bold": True, "size": 12, "color": C.NAVY})],
      [("고친 증상을 테스트로 고정한다. 안전 크리티컬 경로 골든 테스트가 실패하면 릴리스가 "
        "자동 차단된다 (Phase 1 완료).", {"size": 9.5, "color": C.INK2})]]),
]
for y, left, right in TRADE:
    C.fill(C.at(s, 0.77, y), left, anchor=MSO_ANCHOR.MIDDLE, margin=0.22, space=1.3, gap=3)
    C.fill(C.at(s, 7.02, y), right, anchor=MSO_ANCHOR.MIDDLE, margin=0.22, space=1.3, gap=3)

# ── 8. 04. Benchmark ────────────────────────────────────────
# 양식이 요구하는 건 '다른 회사는 어떻게 해결했는가' 다. 외부 상용 제품을 먼저 놓고
# 그 안에 현행 I-PAS 를 둔다. 사내 장비 비교만 넣으면 양식 미준수다.
s = S[7]
C.fill(C.body_of(s, "대상"),
       [[("지게차 ↔ 보행자 근접 경보는 이미 상용 제품군이 있다. 현행 I-PAS 도 그중 하나다.",
          {"bold": True, "size": 11})],
        [("감지 거리 10~25m 는 SafeAlert 의 경고 15m 와 같은 범위 — 반경 설정이 업계 관행에서 "
          "벗어나 있지 않다.", {"size": 10, "color": C.INK2})]],
       margin=0.22, anchor=MSO_ANCHOR.MIDDLE, space=1.3, top=C.TOPCHIP)
C.fill(C.body_of(s, "내용"), [[("", {})]], margin=0.02, top=C.TOPCHIP)
BX, BY, BW = 0.76, 3.48, 11.80
C.table(s, BX + 0.24, BY + 0.44, BW - 0.48,
        ["시스템", "방식", "감지 거리", "착용물", "차량 개입"],
        [["ZoneSafe", "RFID", "차량 주위 360° 최대 10m", "태그 필요", "경보"],
         ["ELOKON ELOshield", "UWB", "경고 / 보호 구역 분리 · 비가시선 감지", "태그 필요",
          "경보 + 자동 감속"],
         ["Pozyx RTLS", "UWB", "차량 주위 360° 최대 25m", "태그 필요", "경보"],
         ["I-PAS  (현행)", "태그", "3m", "태그 필요", "경보 + 자동 셧다운"],
         [[[("SafeAlert", {"bold": True, "color": C.TEAL})]], "BLE", "경고 15m / 위험 8m",
          [[("없음 — 지급 단말", {"bold": True, "color": C.TEAL})]], "경보만"]],
        col_w=[2.45, 1.05, 4.05, 2.05, 1.72], row_h=0.275, head_h=0.28, size=9.5, head_size=9.5,
        aligns=[PP_ALIGN.LEFT, PP_ALIGN.CENTER, PP_ALIGN.LEFT, PP_ALIGN.CENTER, PP_ALIGN.CENTER])
C.text(s, BX + 0.28, BY + 2.20, BW - 0.56, 0.26,
       [[("시사점", {"bold": True, "size": 11, "color": C.NAVY})]])
C.lines(s, BX + 0.34, BY + 2.50, BW - 0.68, [
    [("·  외부 제품은 예외 없이 ", {}), ("‘차량에 리더, 사람에 태그’", {"bold": True}),
     (" 구조다. 대상이 늘면 태그를 산다 — I-PAS 와 같다.", {})],
    [("·  상위 제품은 자동 감속까지 간다. 그 자리는 I-PAS EOD 가 이미 맡고 있고, "
      "SafeAlert 는 그 앞단만 맡는다.", {"color": C.RED})],
    [("·  다른 점은 ", {}), ("착용물의 유무 하나", {"bold": True}),
     ("다. 대상 확대 비용의 차이가 전부 거기서 나온다 (3장 막대).", {})],
    [("·  벤더 공개 자료 기준 (2026.09 조회). ‘아차사고 감소 · ROI 3~6개월’ 은 벤더 주장이라 "
      "인용하지 않는다.", {"color": C.INK3, "size": 9})],
], size=10, gap=0.245)

# ── 9. 05. Solution ─────────────────────────────────────────
s = S[8]
C.fill(C.body_of(s, "Short term"),
       [[("지급 단말에 SafeAlert 를 설치해 BLE 로 상호 감지한다",
          {"bold": True, "size": 12, "color": C.NAVY})],
        [("·  지게차가 낀 조합 경고 15m / 위험 8m, 그 밖의 조합 5m / 3m", {})],
        [("·  소리 · 진동 · 화면 3중 경보 — 소음 · 장갑 · 시야 중 하나가 막혀도 전달된다", {})],
        [("·  1바이트 비트팩 페이로드로 역할 · 상태 · 방향 · 위험도를 함께 보낸다", {})],
        [("·  자동 음소거 · 세이프존으로 울릴 곳에서만 울린다", {})],
        [("v1.1.70 이 WF11 · WF21 · WF25 에서 운영 중이다.",
          {"bold": True, "size": 10.5, "color": C.TEAL})],
        [("추가 하드웨어 없이 I-PAS 가 비워 둔 조합을 지금 덮는다. 대상이 늘어도 구매가 없다.",
          {"size": 10, "color": C.INK2})]],
       size=10.5, margin=0.22, space=1.32, gap=4, top=C.TOPCHIP)
C.fill(C.body_of(s, "Long term"),
       [[("신뢰성 로드맵 Phase 1~5 + PDA 이식",
          {"bold": True, "size": 12, "color": C.NAVY})],
        [("·  새 기능을 붙이는 작업이 아니다. 판정이 흔들리는 구조적 원인을 걷어낸다", {})],
        [("·  Phase 1 완료 — 테스트 하네스와 CI 회귀 게이트 (동작 중)", {})],
        [("·  Phase 2 — 안전 크리티컬 경로 골든 테스트 + PDA 이식", {})],
        [("·  Phase 3~5 — BleService 분해 · 기기 상태 단일화 · 판정 워커 분리", {})],
        [("PDA 이식이 개인 폰 임시 설치를 끝낸다.",
          {"bold": True, "size": 10.5, "color": C.TEAL})],
        [("업무 단말이라 배포 · 회수 경로가 이미 있고, 개인 폰 의존과 배터리 부담이 함께 없어진다.",
          {"size": 10, "color": C.INK2})]],
       size=10.5, margin=0.22, space=1.32, gap=4, top=C.TOPCHIP)
C.fill(C.at(s, 0.77, 5.96),
       [[("기대 효과  —  ", {"bold": True, "size": 12, "color": C.NAVY}),
         ("I-PAS 가 3m 에서 장비를 세우기 전에, 15m · 8m 에서 사람이 먼저 안다. "
          "태그를 사지 않고 덮는 조합이 늘어난다.", {"bold": True, "size": 12})],
        [("“정확한 100% 판정이 아니라, 피할 시간을 만드는 것이 핵심입니다.”",
          {"italic": True, "size": 10, "color": C.INK2})]],
       anchor=MSO_ANCHOR.MIDDLE, align=PP_ALIGN.CENTER, margin=0.20, space=1.3, gap=3)

# ── 10. 05. Solution — 실행 계획 ────────────────────────────
s = S[9]
t = [sh.table for sh in s.shapes if sh.has_table][0]
PLAN = [
    ("Phase 1  테스트 하네스 · CI 회귀 게이트", "제안자", "완료", "완료"),
    ("v1.0.1 ~ v1.1.70 현장 배포 · 3개 센터 실사용", "제안자 · 현장", "진행 중", "진행"),
    ("경보 이력 4주 집계 — 추정치를 실측으로 대체", "제안자", "9월 중", "진행"),
    ("서베이 회수 · 집계 (WF11 · WF21 · WF25)", "제안자 · 현장", "9월 중", "진행"),
    ("Phase 2  안전 경로 골든 테스트 + PDA 이식", "제안자 · IT", "10월", "예정"),
    ("Phase 3~5  분해 · 상태 단일화 · 워커 분리", "제안자", "Phase 2 후", "예정"),
    ("실사용 테스트 승인 절차 정리 · 확대 승인", "안전 · 운영", "확산 전", "협조"),
    ("보안 검토 — 난독화 미적용 · 이력 평문 저장", "보안", "확산 전", "협조"),
    ("정식 과제 등록 · 유지보수 주체 지정", "운영 / 기획", "확산 전", "협조"),
    ("17개 센터 확산", "운영 · 현장", "Phase 2 완료 후", "예정"),
]
STCOL = {"완료": C.TEAL, "진행": C.TEAL, "예정": C.INK2, "협조": C.AMBER}
for i, (what, who, when, st) in enumerate(PLAN, start=1):
    C.cell(t.cell(i, 0), [[(what, {})]], 10.5, PP_ALIGN.LEFT, margin=0.14)
    C.cell(t.cell(i, 1), [[(who, {})]], 10.5, PP_ALIGN.CENTER)
    C.cell(t.cell(i, 2), [[(when, {})]], 10.5, PP_ALIGN.CENTER)
    C.cell(t.cell(i, 3), [[(st, {"bold": True, "color": STCOL[st]})]], 10.5, PP_ALIGN.CENTER)

# ── 11. 05. Solution 자유 양식 — 로드맵 ─────────────────────
s = S[10]
C.fill(C.at(s, 0.78, 2.37), [[("", {})]], margin=0.02)
C.text(s, 1.00, 2.56, 11.4, 0.26,
       [[("신뢰성 로드맵 Phase 1 ~ 5", {"bold": True, "size": 13, "color": C.NAVY}),
         ("     새 기능이 아니라, 판정이 흔들리는 구조적 원인을 걷어내는 작업",
          {"size": 10.5, "color": C.INK2})]])
C.table(s, 1.00, 2.98, 11.35,
        ["단계", "무엇을 하는가", "출하 시 현장에서 확인할 것", "상태"],
        [["1", "테스트 하네스와 CI 회귀 게이트", "설치 · 경보 동작이 이전과 같은가", "완료"],
         ["2", "안전 크리티컬 경로 골든 테스트 + PDA 이식", "천천히 접근하는 지게차에 경고가 뜨는가", "예정"],
         ["3", "BleService 분해 (약 1,000줄 판정 로직)", "달라진 게 없는가 — 동작 보존", "예정"],
         ["4", "기기 상태 단일화", "2시간 이상 구동 후에도 느려지지 않는가", "예정"],
         ["5", "판정 워커 분리 (스캔 콜백 = 메인 스레드 해소)", "20대 이상에서 화면이 끊기지 않는가", "예정"]],
        col_w=[0.75, 4.40, 4.90, 1.30], row_h=0.36, head_h=0.32, size=10, head_size=10,
        aligns=[PP_ALIGN.CENTER, PP_ALIGN.LEFT, PP_ALIGN.LEFT, PP_ALIGN.CENTER])
C.text(s, 1.05, 5.20, 11.3, 0.26,
       [[("이 순서인 이유", {"bold": True, "size": 11, "color": C.NAVY})]])
C.lines(s, 1.10, 5.50, 11.2, [
    [("①  측정 수단이 먼저다", {"bold": True}),
     ("  —  테스트 0건 상태에서 안전 로직을 분해하면, 회귀를 현장에서 발견하는 방식이 그대로 남는다.",
      {"color": C.INK2})],
    [("②  단계마다 단독 배포한다", {"bold": True}),
     ("  —  변경을 섞어 내보내면 현장에서 회귀가 나도 어느 변경 탓인지 특정할 수 없다.",
      {"color": C.INK2})],
    [("③  전 단계가 출하 가능하다", {"bold": True}),
     ("  —  로드맵이 멈춰도 v1.1.70 이 현장에서 그대로 돌아 보호가 끊기지 않는다.",
      {"color": C.INK2})],
], size=10, gap=0.32)

# ── 12. 06. Metrics ─────────────────────────────────────────
# 「미발령」은 뺐다 — 정의상 관측되지 않아 지표로 성립하지 않는다.
s = S[11]
C.fill(C.body_of(s, "성공 지표"), [[("", {})]], margin=0.02, top=C.TOPCHIP)
MX, MY, MW = 0.77, 2.37, 5.84
C.text(s, MX + 0.24, MY + 0.44, MW - 0.48, 0.24,
       [[("네 지표 모두 목표를 숫자로 둔다", {"bold": True, "size": 10.5, "color": C.NAVY}),
         ("   밑줄 = 추정치", {"size": 8.5, "color": C.INK3, "u": True})]])
KPI = [
    ("KPI 1", "접근 조우", "경고 반경 15m 진입", f"교대당 {WARN}회", C.AMBER),
    ("KPI 2", "위험 조우", "위험 반경 8m 진입", f"교대당 {DANGER}회", C.RED),
    ("KPI 3", "오발령", "정지 중 반복 · 이탈 후 재발령", "교대당 3회 미만", C.INK2),
    ("KPI 4", "체감 만족도", "경보 타이밍 · 거리 정확도", "7.0 / 10", C.TEAL),
]
for i, (tag, name, desc, val, col) in enumerate(KPI):
    C.kpi(s, MX + 0.20, MY + 0.72 + i * 0.86, MW - 0.40, 0.80, tag, name, desc, val, col)
C.text(s, MX + 0.24, MY + 4.14, MW - 0.48, 0.44,
       [[("「미발령 0건」은 지표에서 뺐다", {"bold": True, "size": 9, "color": C.RED}),
         ("  —  미발령은 정의상 관측되지 않는다. 서베이의 ‘경보가 늦었다’ 응답으로 간접 관측한다.",
          {"size": 9, "color": C.INK2})]], space=1.25)

C.fill(C.body_of(s, "지표 확보 방법"), [[("", {})]], margin=0.02, top=C.TOPCHIP)
NX = 6.74
C.text(s, NX + 0.24, MY + 0.44, MW - 0.48, 0.24,
       [[("산출 근거 — 가정값은 하나뿐이다", {"bold": True, "size": 10.5, "color": C.NAVY})]])
C.table(s, NX + 0.20, MY + 0.78, MW - 0.40,
        ["항목", "값", "근거"],
        [["감지 쌍", "21쌍", "차량 4 + 상시 3 = 7대, C(7,2) — 실측"],
         ["쌍당 경고 조우", [[("0.5회 / 시간", EST)]],
          [[("보수적 가정 — ", {}), ("유일한 가정값", {"bold": True, "color": C.RED})]]],
         ["경고 조우", [[(f"교대당 {WARN}회", ESTB)]], f"21 × {SHIFT_H}시간 × 0.5 = 94.5"],
         ["위험 전이율", [[(f"{RATIO}", EST)]], "반경 비의 제곱 (8m / 15m)²"],
         ["위험 조우", [[(f"교대당 {DANGER}회", ESTB)]], f"{WARN} × {RATIO} = 26.6"],
         ["오발령", [[("교대당 3회 미만", EST)]], "자동 음소거 · 이탈 억제 적용 후"],
         ["체감 만족도", [[("7.0 / 10", EST)]], "요구 4건 전량 반영 · 3개 센터 사용 지속"]],
        col_w=[1.32, 1.28, 2.84], row_h=0.27, head_h=0.27, size=8.5, head_size=8.5,
        aligns=[PP_ALIGN.LEFT, PP_ALIGN.CENTER, PP_ALIGN.LEFT])
C.lines(s, NX + 0.24, MY + 2.96, MW - 0.48, [
    [("·  경보 이력은 이미 쌓이고 있다 — ", {}),
     ("alerts/{yyyyMMdd} = timestamp · deviceId · walkerId · rssi · alertLevel",
      {"bold": True, "size": 8.5}),
     ("  (FirebaseManager.kt:15-29)", {"size": 8.5, "color": C.INK3})],
    [("·  기기당 1분 1회 스로틀(BleService.kt:2529) 이라 1건 = 조우 1분이다. 중복이 걷힌 값이라 "
      "아차사고 대리지표로 쓴다 — 사고 건수가 아니다.", {})],
    [("·  4주 집계 한 번이면 가정값이 실측으로 바뀌고, 위 표의 나머지가 전부 따라 교체된다.", {})],
    [("·  회귀 재발은 CI 골든 테스트가 잡는다 — 실패 시 릴리스가 자동 차단된다 (Phase 1, 동작 중).",
      {})],
    [("·  사고 건수 · 셧다운 건수는 회사 데이터로 존재하지 않아 지표에서 제외했다.",
      {"color": C.RED})],
], size=9, gap=0.335)

# ── 13. 07. Andon ───────────────────────────────────────────
s = S[12]
C.fill(C.body_of(s, "중단 지표"), [[("", {})]], margin=0.02, top=C.TOPCHIP)
AX = 0.77
C.text(s, AX + 0.24, MY + 0.42, MW - 0.48, 0.24,
       [[("현장 운영 중", {"bold": True, "size": 10.5, "color": C.NAVY})]])
STOP, WATCH = "즉시 중단", "관측 시 판단"
FIELD = [("미발령", STOP, "접근했는데 경보가 뜨지 않은 사례 1건이라도"),
         ("오발령 지속", STOP, "정지 중 계속 울림 · 이탈 후 다시 울림이 반복"),
         ("감지 중단", WATCH, "상시 알림이 ‘이상’ 으로 바뀌거나 사라짐"),
         ("경보 피로", WATCH, "무음으로 두거나 앱을 꺼 둔 단말이 늘어남")]
UPD = [("버전 혼재", STOP, "구버전 단말이 신버전을 감지하지 못함"),
       ("회귀 재발", STOP, "이전에 고친 증상이 다시 관측됨"),
       ("보호 공백", WATCH, "작업 시간 중 일괄 업데이트 — 교대 전환 때만 배포"),
       ("서비스 미기동", WATCH, "업데이트 · 역할 전환 후 백그라운드 실행 미복구")]
y = MY + 0.72
for name, tag, desc in FIELD:
    C.text(s, AX + 0.32, y, MW - 0.60, 0.22,
           [[(name, {"bold": True, "size": 10}),
             ("   " + tag, {"bold": True, "size": 8.5, "color": C.RED if tag == STOP else C.INK2})]])
    C.text(s, AX + 0.32, y + 0.19, MW - 0.60, 0.20, [[(desc, {"size": 8.5, "color": C.INK2})]])
    y += 0.42
C.text(s, AX + 0.24, y + 0.10, MW - 0.48, 0.24,
       [[("업데이트 · 수정 중", {"bold": True, "size": 10.5, "color": C.NAVY})]])
y += 0.40
for name, tag, desc in UPD:
    C.text(s, AX + 0.32, y, MW - 0.60, 0.22,
           [[(name, {"bold": True, "size": 10}),
             ("   " + tag, {"bold": True, "size": 8.5, "color": C.RED if tag == STOP else C.INK2})]])
    C.text(s, AX + 0.32, y + 0.19, MW - 0.60, 0.20, [[(desc, {"size": 8.5, "color": C.INK2})]])
    y += 0.42

C.fill(C.body_of(s, "지표 확보 방법"), [[("", {})]], margin=0.02, top=C.TOPCHIP)
C.text(s, NX + 0.24, MY + 0.42, MW - 0.48, 0.24,
       [[("무엇으로 관측하는가", {"bold": True, "size": 10.5, "color": C.NAVY})]])
C.lines(s, NX + 0.30, MY + 0.72, MW - 0.56, [
    [("·  미발령 · 오발령 — 작업자 신고와 Firebase 경보 이력을 맞춰 판정한다.", {})],
    [("·  감지 중단 · 서비스 미기동 — 상시 알림이 스스로 이상 상태를 드러낸다 (v1.1.64).", {})],
    [("·  버전 혼재 — 배포 후 전 단말 버전 확인. 1바이트 프로토콜은 구버전과 호환된다.", {})],
    [("·  회귀 재발 — CI 골든 테스트가 잡는다. 실패 시 릴리스 자동 차단.", {})],
    [("·  경보 피로 — 서베이와 무음 설정 비율로 본다.", {})],
], size=9, gap=0.325)
C.text(s, NX + 0.24, MY + 2.46, MW - 0.48, 0.24,
       [[("Rollback 기준", {"bold": True, "size": 10.5, "color": C.NAVY})]])
for i, (k, v) in enumerate([
    ("즉시 롤백", "직전 태그 APK 로 되돌린다 — 현장 재설치 없이 배포된다"),
    ("선행 확인", "전 단말 배포 전 1대에서 먼저 확인한다"),
    ("빌드 차단", "골든 테스트가 실패하면 릴리스가 자동 차단된다 (동작 중)"),
    ("원인 귀속", "단계를 단독 배포하므로 어느 변경 탓인지 특정된다"),
    ("재개 조건", "실패한 상황을 테스트로 고정한 뒤에만 다음 단계로"),
]):
    C.text(s, NX + 0.30, MY + 2.78 + i * 0.36, MW - 0.56, 0.34,
           [[(k, {"bold": True, "size": 9.5, "color": C.NAVY})],
            [(v, {"size": 8.5, "color": C.INK2})]], space=1.2)
# 인용 한 줄을 더 넣을 자리가 없다 — Rollback 다섯 항목이 상자를 꽉 채운다

# ── 14. 08. Feedback Loop ───────────────────────────────────
s = S[13]
C.fill(C.body_of(s, "모니터링 방법"), [[("", {})]], margin=0.02, top=C.TOPCHIP)
FX, FY, FW = 0.77, 2.38, 11.80
C.text(s, FX + 0.28, FY + 0.42, FW - 0.56, 0.24,
       [[("피드백은 이미 한 바퀴 돌았다 — 지적이 코드에 남아 있다",
          {"bold": True, "size": 11.5, "color": C.NAVY})]])
C.table(s, FX + 0.28, FY + 0.74, 5.55,
        ["차수", "반영 내용", "결과"],
        [["1차", "역할 전환 버튼 · EPJ 역할 숨김", "재설치 없이 화면에서 역할 전환"],
         ["2차", "같은 등급 5초 이상이면 자동 음소거", "등급 오르면 즉시 재발령"],
         ["3차", "세이프존 — 비콘으로 경보 억제", "존 안은 상대에게도 안전"]],
        col_w=[0.58, 2.87, 2.10], row_h=0.32, head_h=0.27, size=8.5, head_size=8.5,
        aligns=[PP_ALIGN.CENTER, PP_ALIGN.LEFT, PP_ALIGN.LEFT])
C.table(s, FX + 6.20, FY + 0.74, 5.60,
        ["항목", "Before", "After"],
        [["보호 상태", "끊겨도 알 수 없음", "상시 알림에 이상 표시"],
         ["이탈 후", "멀어졌는데 다시 울림", "경고 범위 재발령 억제"],
         ["알림 끄기", "단계가 번거로움", "알림 본문을 누르면 즉시 무음"]],
        col_w=[1.05, 2.20, 2.35], row_h=0.32, head_h=0.27, size=8.5, head_size=8.5)
C.text(s, FX + 0.28, FY + 2.06, FW - 0.56, 0.24,
       [[("반영 이력은 커밋에 남아 있다  —  ", {"bold": True, "size": 9, "color": C.NAVY}),
         ("리뷰 1·2·3차 v1.1.60 · v1.1.61 · v1.1.62,  보호 끊김 v1.1.64,  이탈 재발령 v1.1.51,  "
          "알림 본문 탭 v1.1.68,  사이드바 v1.1.69 · v1.1.70", {"size": 9})]])
C.fill(C.body_of(s, "확산 계획"),
       [[("① 실측 확보", {"bold": True, "color": C.NAVY}),
         ("  경보 이력 4주 집계 · WF11 · WF21 · WF25 서베이 회수 — 추정치를 실측으로 교체한다.", {}),
         ("        ② PDA 이식", {"bold": True, "color": C.NAVY}),
         ("  Phase 2 에서 업무 단말로 옮겨 개인 폰 임시 설치를 끝낸다.", {})],
        [("③ 확산", {"bold": True, "color": C.NAVY}),
         ("  정식 과제 등록 후 17개 센터로 — 추가 하드웨어 구매 없이 앱 배포만으로 확산한다. "
          "확산 전에 보안 검토(난독화 · 이력 평문 저장)를 선행한다.", {})]],
       size=10.5, margin=0.24, anchor=MSO_ANCHOR.MIDDLE, space=1.35, gap=5, top=C.TOPCHIP)

prs.save(OUT)
print("wrote", OUT, f"({len(prs.slides)}장)")
