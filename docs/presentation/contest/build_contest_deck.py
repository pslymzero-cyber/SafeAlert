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
LATEST, LATEST_DATE = "v1.1.71", "2026.09.01"   # github 릴리스 기준
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
       [[("지게차와 보행자가 서로 다가오면 미리 알려주는 앱",
          {"bold": True, "size": 20, "color": C.NAVY})],
        [("— 이미 나눠준 업무용 스마트폰끼리 신호를 주고받습니다 (SafeAlert)",
          {"size": 12, "color": C.INK2})]],
       margin=0.02, space=1.25)
C.fill(C.at(s, 1.74, 6.47), [[("WF11", {"bold": True, "size": 12})]],
       align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE, margin=0.05)
C.fill(C.at(s, 5.84, 6.47), [[("Ian", {"bold": True, "size": 12})]],
       align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE, margin=0.05)

# ── 2. 01. 결론 ─────────────────────────────────────────────
s = S[1]
CONC = [
    (2.11, "문제", "우리 센터에는 지게차 접근을 알려주는 장치가 하나도 없습니다",
     [[("시야는 적재 파렛트에 막히고 경적은 장비 소음에 묻힙니다. "
        "지금은 작업자의 주의력이 사실상 유일한 안전장치입니다.", {})]]),
    (3.63, "해결", "이미 나눠준 스마트폰끼리 서로를 감지하게 만들었습니다",
     [[("앱만 깔면 15m 에서 경고, 8m 에서 위험 경보가 소리 · 진동 · 화면으로 울립니다. "
        "같은 기능을 장비로 갖추면 센터당 331만원이 들지만, ", {}),
       ("이 앱은 추가 비용이 없습니다", {"bold": True, "color": C.TEAL}), (".", {})]]),
    (5.19, "확인", "계획이 아니라, 지금 돌아가고 있는 앱입니다",
     [[("WF11 · WF21 · WF25 세 개 센터에서 실사용 중, 3개월간 71번 배포했습니다.", {})],
      [("코드 · 배포 이력  ", {"size": 10, "color": C.INK3}),
       ("github.com/pslymzero-cyber/SafeAlert", {"bold": True, "color": C.NAVY, "size": 11.5}),
       ("      최신 버전 ", {"size": 10, "color": C.INK2}),
       (f"{LATEST}", {"bold": True, "size": 10.5, "color": C.TEAL}),
       (f"  ({LATEST_DATE} 배포)", {"size": 10, "color": C.INK2})]]),
]
for i, (y, tag, head, sub) in enumerate(CONC, start=1):
    C.fill(C.at(s, 1.85, y),
           [[(f"{tag}", {"bold": True, "size": 11, "color": C.INK3}),
             ("      ", {"size": 11}),
             (head, {"bold": True, "size": 15.5, "color": C.NAVY})]] + sub,
           size=11, anchor=MSO_ANCHOR.MIDDLE, margin=0.32, space=1.35, gap=5)

# ── 3. 01. Summary ──────────────────────────────────────────
s = S[2]
C.fill(C.body_of(s, "Background"),
       [[("우리 센터에는 지게차와 보행자가 서로 다가오는 것을 알려주는 장치가 없다. "
          "존과 로케이션 사이 교차 구간은 적재 파렛트에 시야가 막히고 장비 소음에 경적이 묻혀, "
          "서로를 보지 못한 채 가까워진다.", {"size": 10.5})]],
       margin=0.20, space=1.3, top=C.TOPCHIP)
C.fill(C.body_of(s, "Solution"),
       [[("이미 나눠준 스마트폰에 앱을 깔면, 폰끼리 신호를 주고받아 서로를 감지한다.",
          {"bold": True, "size": 11.5})],
        [("따로 살 장비도, 몸에 달 태그도, 센터에 설치할 것도 없다.",
          {"size": 10, "color": C.INK2})],
        [("신호 받기  →  거리 계산  →  위험도 판단  →  소리 · 진동 · 화면 경보",
          {"bold": True, "size": 10.5, "color": C.NAVY})],
        [("경보 거리 — 지게차가 끼면 경고 15m · 위험 8m,  사람끼리는 경고 5m · 위험 3m.",
          {"size": 10})],
        [("장비를 자동으로 멈추지는 못한다. 사람에게 알리는 데까지가 이 앱의 역할이다.",
          {"size": 10, "color": C.RED})]],
       margin=0.20, space=1.3, gap=5, top=C.TOPCHIP)

r = C.body_of(s, "Result")
C.fill(r, [[("", {})]], margin=0.02)
RX, RY, RW = 6.30, 2.38, 6.27
C.text(s, RX + 0.24, RY + 0.50, RW - 0.48, 0.24,
       [[("서로 감지되는 경우의 수 — 현장 인원이 늘어날수록",
          {"bold": True, "size": 10.5, "color": C.NAVY})]])
PEOPLE = [3, 5, 10, 20]
C.chart2(s, RX + 0.14, RY + 0.78, RW - 0.28, 1.92,
         [f"{p}명" for p in PEOPLE],
         [("장비를 산다면 (태그 착용자만 · 1인당 11만원)", [4 * p for p in PEOPLE]),
          ("앱을 깐다면 (모든 폰끼리 · 추가 0원)", [(4 + p) * (3 + p) // 2 for p in PEOPLE])],
         label_size=8.5, cat_size=8.5)
C.text(s, RX + 0.24, RY + 2.80, RW - 0.48, 0.22,
       [[("지게차 4대 기준. 장비 방식은 태그를 산 사람만 감지되지만, 앱은 폰을 가진 모두가 "
          "서로 감지된다.", {"size": 8.5, "color": C.INK2})]])
C.text(s, RX + 0.24, RY + 3.10, RW - 0.48, 0.24,
       [[("1교대(9시간) 동안 몇 번이나 가까워지는가",
          {"bold": True, "size": 10.5, "color": C.NAVY}),
         ("   밑줄 = 추정치", {"size": 8.5, "color": C.INK3, "u": True})]])
for i, (lab, val, col) in enumerate([("경고 거리 15m 안", f"{WARN}회", C.AMBER),
                                     ("위험 거리 8m 안", f"{DANGER}회", C.RED)]):
    C.kpi(s, RX + 0.20 + i * 2.98, RY + 3.40, 2.82, 0.92,
          "", lab, "앱 경보 기록으로 실측 예정", val, col)
C.text(s, RX + 0.24, RY + 4.42, RW - 0.48, 0.22,
       [[("산출 근거는 12장. 가정한 값은 ‘한 짝이 한 시간에 0.5번 가까워진다’ 하나뿐이다.",
          {"size": 8, "color": C.INK3})]])

# ── 4. Summary 자유 양식 — 화면과 판정 반경 ─────────────────
s = S[3]
C.fill(C.at(s, 0.78, 2.37), [[("", {})]], margin=0.02)
C.text(s, 1.00, 2.56, 11.4, 0.26,
       [[("어떻게 동작하는가", {"bold": True, "size": 13, "color": C.NAVY}),
         ("     경보 거리 · 감지되는 경우의 수 · 안전 구역", {"size": 10.5, "color": C.INK2})]])
pic(s, "fig_radius.png", 1.05, 2.98, 3.80, 648 / 358,
    "경보가 울리는 거리 — 실제 비례로 그린 것")
pic(s, "fig_pairs.png", 5.30, 3.52, 3.55, 728 / 278,
    "폰이 늘수록 서로 감지되는 경우의 수는 제곱으로 (3대 3가지 → 10대 45가지)")
pic(s, "fig_zone.png", 9.30, 3.52, 3.05, 668 / 288,
    "안전 구역 안에서는 경보가 울리지 않는다")
C.lines(s, 1.05, 5.62, 11.3, [
    [("·  ", {"color": C.INK3}), ("경보는 소리 · 진동 · 화면 세 가지로 동시에 울린다", {"bold": True}),
     ("  —  소음 때문에 못 듣거나, 장갑 때문에 진동을 놓치거나, 화면을 안 봐도 "
      "나머지 하나는 전달된다", {"color": C.INK2})],
    [("·  ", {"color": C.INK3}), ("같은 상대와 같은 등급이 5초 넘게 이어지면 소리를 끈다", {"bold": True}),
     ("  —  화면 표시와 기록은 그대로 두고, 더 가까워지면 즉시 다시 울린다 (v1.1.61)",
      {"color": C.INK2})],
    [("·  ", {"color": C.INK3}), ("휴게실 · 충전 구역은 ‘안전 구역’ 으로 지정한다", {"bold": True}),
     ("  —  그 안에 있는 사람은 경보를 받지도, 남에게 울리게 하지도 않는다 (v1.1.62)",
      {"color": C.INK2})],
    [("·  ", {"color": C.INK3}), ("감지가 멈추면 알림 표시줄에 ‘이상’ 이 뜬다", {"bold": True}),
     ("  —  앱이 꺼진 줄 모르고 일하는 상황을 막는다 (v1.1.64)", {"color": C.INK2})],
], size=10, gap=0.31)

# ── 5. 02. Problem Statement ────────────────────────────────
s = S[4]
C.fill(C.body_of(s, "문제의 배경"),
       [[("AS-IS", {"bold": True, "color": C.NAVY}), (" :  ", {"color": C.INK3}),
         ("우리 센터에는 지게차와 보행자의 접근을 감지하는 장치가 설치돼 있지 않다.", {})],
        [("Pain Point", {"bold": True, "color": C.NAVY}), (" :  ", {"color": C.INK3}),
         ("존과 로케이션 사이 교차 구간에서 적재 파렛트가 시야를 가리고, "
          "장비 소음에 경적이 묻혀 서로를 알아채는 것이 늦다.", {})],
        [("접근을 알려주는 수단이 없으니, 위험했던 순간이 몇 번이었는지조차 기록되지 않는다.",
          {"color": C.INK2})]],
       size=10.5, margin=0.20, space=1.35, gap=5, top=C.TOPCHIP)
C.fill(C.body_of(s, "표준/목표"),
       [[("Target", {"bold": True, "color": C.NAVY}), (" :  ", {"color": C.INK3}),
         ("서로 다가오는 것을 부딪히기 전에 감지해 사람에게 알린다.", {})],
        [("Vision", {"bold": True, "color": C.NAVY}), (" :  ", {"color": C.INK3}),
         ("현장에 있는 모든 사람과 장비가 서로의 접근을 알 수 있는 상태.", {})]],
       size=10, margin=0.18, space=1.3, top=C.TOPCHIP)
C.fill(C.body_of(s, "현재 상황"),
       [[("Data", {"bold": True, "color": C.NAVY}), (" :  ", {"color": C.INK3}),
         ("현재 서로를 감지하는 장비는 ", {}), ("0대", {"bold": True, "color": C.RED}),
         (". 지게차 4대와 상시 인원 3명 모두 감지 대상이 아니다.", {})],
        [("Issue", {"bold": True, "color": C.NAVY}), (" :  ", {"color": C.INK3}),
         ("감지 수단이 없으니 사고도 아차사고도 집계된 적이 없다.", {})]],
       size=10, margin=0.18, space=1.3, top=C.TOPCHIP)

why = C.body_of(s, "문제를 해결해야 하는 이유")
C.fill(why, [[("", {})]], margin=0.02)
WX, WY, WW = 0.77, 4.88, 11.80
C.text(s, WX + 0.28, WY + 0.44, WW - 0.56, 0.30,
       [[("1. 부딪히기 전에 알린다", {"bold": True, "size": 12.5, "color": C.NAVY}),
         ("      2. 장비를 사지 않는다", {"bold": True, "size": 12.5, "color": C.NAVY}),
         ("      3. 쓸데없이 울리지 않는다", {"bold": True, "size": 12.5, "color": C.NAVY}),
         ("      4. 꺼져도 바로 안다", {"bold": True, "size": 12.5, "color": C.NAVY})]],
       align=PP_ALIGN.CENTER)
C.text(s, WX + 0.28, WY + 0.88, WW - 0.56, 0.28,
       [[("1교대(9시간) 동안 15m 안까지 가까워지는 횟수 ", {"size": 11.5}),
         (f"약 {WARN}회", {"bold": True, "size": 11.5, "color": C.RED, "u": True}),
         (",  그중 8m 안까지 ", {"size": 11.5}),
         (f"약 {DANGER}회", {"bold": True, "size": 11.5, "color": C.RED, "u": True})]],
       align=PP_ALIGN.CENTER)
C.text(s, WX + 0.28, WY + 1.18, WW - 0.56, 0.26,
       [[("밑줄은 추정치다 (산출 근거 12장). 감지 수단이 없어 실제로 세어 본 적이 없다.",
          {"size": 9.5, "color": C.INK2})]], align=PP_ALIGN.CENTER)
C.text(s, WX + 0.28, WY + 1.50, WW - 0.56, 0.28,
       [[("“지금 이 구간을 지키는 것은 설비가 아니라 ", {"italic": True, "size": 10.5, "color": C.INK2}),
         ("작업자의 주의력", {"italic": True, "bold": True, "size": 10.5}),
         (" 하나뿐입니다. 그리고 주의력은 교대 끝으로 갈수록 떨어집니다.”",
          {"italic": True, "size": 10.5, "color": C.INK2})]],
       align=PP_ALIGN.CENTER)

# ── 6. 02. Problem Statement _ 5Why ─────────────────────────
s = S[5]
C.fill(C.named(s, "TextBox 17"),
       [[("Most likely Causes :  ", {"size": 10.5, "color": C.INK2}),
         ("지게차와 보행자가 서로 다가오는 것을 알려주는 장치가 설치돼 있지 않다",
          {"bold": True, "size": 10.5})]], margin=0.0)
C.named(s, "TextBox 17").width = Inches(11.0)
C.fill(C.named(s, "TextBox 18"),
       [[("Root Causes :  ", {"size": 10.5, "color": C.INK2}),
         ("접근 감지를 ‘장비를 사는 일’ 로만 봐 왔고, 예산이 서지 않으면 대책이 0 인 상태가 유지됐다",
          {"bold": True, "size": 10.5, "color": C.RED}),
         ("   (시중 제품이 모두 태그 방식이라 — 8장)", {"size": 9, "color": C.INK3})]], margin=0.0)
C.named(s, "TextBox 18").width = Inches(11.0)

t = [sh.table for sh in s.shapes if sh.has_table][0]
WHY = [
    ("왜 교차 구간에서 위험한가?", "서로를 보지 못한 채 가까워지기 때문이다."),
    ("왜 보지 못하는가?", "적재 파렛트가 시야를 가리고, 장비 소음에 경적이 묻히기 때문이다."),
    ("왜 눈과 경적에만 의존하는가?", "접근을 대신 알려주는 장치가 설치돼 있지 않기 때문이다."),
    ("왜 설치되지 않았는가?",
     "시중 감지 장비는 차량과 사람 모두에게 태그를 달아야 하고, 대상이 늘 때마다 태그를 더 사야 한다."),
    ("왜 그 방법뿐이라고 보았는가?",
     "접근 감지를 ‘장비를 사는 일’ 로만 봤기 때문이다. 이미 나눠준 업무용 스마트폰이 "
     "같은 일을 할 수 있다는 것을 검토한 적이 없다."),
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
    (5.56, [[("빨리 고치기  vs.  같은 결과 내기", {"bold": True, "size": 12})],
            [("3개월에 71번 고쳤지만, 고쳤던 문제가 다시 나타났다.",
              {"size": 10, "color": C.INK2})]],
     [[("속도를 늦추고, 같은 결과를 택했다", {"bold": True, "size": 12, "color": C.NAVY})],
      [("고친 문제마다 자동 검사를 붙여 둔다. 안전과 직결된 검사가 하나라도 실패하면 "
        "새 버전이 아예 나가지 않는다 (1단계 완료, 현재 동작 중).",
        {"size": 9.5, "color": C.INK2})]]),
]
for y, left, right in TRADE:
    C.fill(C.at(s, 0.77, y), left, anchor=MSO_ANCHOR.MIDDLE, margin=0.22, space=1.3, gap=3)
    C.fill(C.at(s, 7.02, y), right, anchor=MSO_ANCHOR.MIDDLE, margin=0.22, space=1.3, gap=3)

# ── 8. 04. Benchmark ────────────────────────────────────────
# 우리 센터에는 감지 장비가 없다. I-PAS 는 사내 다른 조직이 쓰는 사례이므로 여기에 놓는다.
# 도입 비용도 여기서 비교한다 — 우리가 안 쓰는 것을 '절감액' 이라 부를 수는 없기 때문이다.
s = prs.slides[7]
C.fill(C.body_of(s, "대상"),
       [[("지게차 ↔ 보행자 접근 경보 장비는 시중에 이미 여러 제품이 있고, "
          "사내 다른 조직도 I-PAS 를 도입해 쓰고 있다. 우리 센터에는 없다.",
          {"bold": True, "size": 11})],
        [("감지 거리 10~25m 는 이 앱의 경고 거리 15m 와 같은 범위 — 거리 설정이 업계 관행에서 "
          "벗어나 있지 않다.", {"size": 10, "color": C.INK2})],
        [("제품 정보는 벤더 공개 자료 기준 (2026.09 조회). 벤더가 주장하는 효과 수치는 "
          "인용하지 않았다.", {"size": 8.5, "color": C.INK3})]],
       margin=0.22, anchor=MSO_ANCHOR.MIDDLE, space=1.3, top=C.TOPCHIP)
C.fill(C.body_of(s, "내용"), [[("", {})]], margin=0.02)
BX, BY, BW = 0.76, 3.48, 11.80
C.table(s, BX + 0.24, BY + 0.42, BW - 0.48,
        ["제품 · 사례", "방식", "감지 거리", "몸에 다는 것", "장비 정지", "센터 1곳 도입 비용"],
        [["ZoneSafe", "RFID", "차량 주위 360° 최대 10m", "태그 필요", "없음 (알림만)", "공개 단가 없음"],
         ["ELOKON ELOshield", "UWB", "경고 / 정지 구역 분리 · 가려져도 감지", "태그 필요",
          "자동 감속", "공개 단가 없음"],
         ["Pozyx RTLS", "UWB", "차량 주위 360° 최대 25m", "태그 필요", "없음 (알림만)", "공개 단가 없음"],
         [[[("I-PAS  (사내 타 조직 도입)", {"bold": True})]], "태그", "3m", "태그 필요",
          "자동 정지", [[("331만원", {"bold": True, "color": C.AMBER})]]],
         [[[("SafeAlert  (이 제안)", {"bold": True, "color": C.TEAL})]], "BLE",
          "경고 15m / 위험 8m",
          [[("없음 — 지급된 폰", {"bold": True, "color": C.TEAL})]], "없음 (알림만)",
          [[("0원", {"bold": True, "color": C.TEAL})]]],
        ],
        col_w=[2.55, 0.85, 3.35, 1.55, 1.30, 1.72], row_h=0.275, head_h=0.28,
        size=9.5, head_size=9)
C.text(s, BX + 0.28, BY + 2.10, BW - 0.56, 0.24,
       [[("331만원 산출  —  ", {"bold": True, "size": 9, "color": C.NAVY}),
         ("차량 태그 52.5만 × 지게차 4대  +  자동정지장치 22만 × 4대  +  보행자 태그 11만 × 상시 3명. "
          "단가는 2026.08 기준이며, 사람이 늘면 1인당 11만원이 더 붙는다.",
          {"size": 9, "color": C.INK2})]])
C.text(s, BX + 0.28, BY + 2.42, BW - 0.56, 0.26,
       [[("시사점", {"bold": True, "size": 11, "color": C.NAVY})]])
C.lines(s, BX + 0.34, BY + 2.70, BW - 0.68, [
    [("·  시중 제품은 예외 없이 ", {}), ("‘차량에 수신기, 사람에 태그’", {"bold": True}),
     (" 구조다. 사람이 늘면 태그를 더 산다.", {})],
    [("·  우리 센터 규모(지게차 4대 · 상시 3명)로 장비를 갖추면 331만원이다. "
      "이 앱은 폰이 이미 있으니 0원이다.", {})],
    [("·  대신 앱은 장비를 자동으로 세우지 못한다. 알리는 데까지가 한계다.", {"color": C.RED})],
], size=10, gap=0.26)

# ── 9. 05. Solution ─────────────────────────────────────────
s = S[8]
C.fill(C.body_of(s, "Short term"),
       [[("업무용 스마트폰에 앱을 깔아, 폰끼리 서로를 감지하게 한다",
          {"bold": True, "size": 12, "color": C.NAVY})],
        [("·  지게차가 끼면 경고 15m · 위험 8m,  사람끼리는 경고 5m · 위험 3m", {})],
        [("·  소리 · 진동 · 화면으로 동시에 알린다 — 하나가 막혀도 나머지가 전달된다", {})],
        [("·  상대가 지게차인지 사람인지, 서 있는지 움직이는지, 후진 중인지까지 함께 보낸다", {})],
        [("·  안전 구역과 자동 소리 끄기로, 울릴 필요 없는 곳에서는 울리지 않는다", {})],
        [(f"지금 {LATEST} 이 WF11 · WF21 · WF25 에서 돌고 있다.",
          {"bold": True, "size": 10.5, "color": C.TEAL})],
        [("아무 감지 수단도 없던 상태에서, 장비를 사지 않고 감지를 시작한 것이다. "
          "사람이 늘어도 추가로 살 것이 없다.", {"size": 10, "color": C.INK2})]],
       size=10.5, margin=0.22, space=1.32, gap=4, top=C.TOPCHIP)
C.fill(C.body_of(s, "Long term"),
       [[("같은 상황에서 항상 같게 동작하도록 다듬고, 업무용 PDA 로 옮긴다",
          {"bold": True, "size": 12, "color": C.NAVY})],
        [("·  새 기능을 붙이는 작업이 아니다. 결과가 흔들리는 원인을 걷어내는 작업이다", {})],
        [("·  1단계 완료 — 자동 검사 체계를 붙였다 (지금 동작 중)", {})],
        [("·  2단계 — 안전과 직결된 동작을 자동 검사로 고정하고, 업무용 PDA 로 옮긴다", {})],
        [("·  3~5단계 — 경보 계산 코드를 나누고, 오래 켜 둬도 느려지지 않게 만든다", {})],
        [("PDA 로 옮기면 개인 폰에 임시로 깔아 둔 상태가 끝난다.",
          {"bold": True, "size": 10.5, "color": C.TEAL})],
        [("업무용 단말이라 배포와 회수 경로가 이미 있고, 개인 폰 배터리를 쓰는 문제도 없어진다.",
          {"size": 10, "color": C.INK2})]],
       size=10.5, margin=0.22, space=1.32, gap=4, top=C.TOPCHIP)
C.fill(C.at(s, 0.77, 5.96),
       [[("기대 효과  —  ", {"bold": True, "size": 12, "color": C.NAVY}),
         ("감지 수단이 0 이던 현장에, 장비 구매 없이 15m · 8m 경보가 생긴다. "
          "사람이 늘어도 비용이 늘지 않는다.", {"bold": True, "size": 12})],
        [("“100% 정확한 감지가 목표가 아닙니다. 피할 시간을 만드는 것이 목표입니다.”",
          {"italic": True, "size": 10, "color": C.INK2})]],
       anchor=MSO_ANCHOR.MIDDLE, align=PP_ALIGN.CENTER, margin=0.20, space=1.3, gap=3)

# ── 10. 05. Solution — 실행 계획 ────────────────────────────
s = S[9]
t = [sh.table for sh in s.shapes if sh.has_table][0]
PLAN = [
    ("1단계  자동 검사 체계 구축", "제안자", "완료", "완료"),
    (f"v1.0.1 ~ {LATEST} 현장 배포 · 3개 센터 실사용", "제안자 · 현장", "진행 중", "진행"),
    ("앱 경보 기록 4주 집계 — 추정치를 실제 숫자로 교체", "제안자", "9월 중", "진행"),
    ("서베이 회수 · 집계 (WF11 · WF21 · WF25)", "제안자 · 현장", "9월 중", "진행"),
    ("2단계  안전 동작 자동 검사 + 업무용 PDA 로 이전", "제안자 · IT", "10월", "예정"),
    ("3~5단계  경보 계산 코드 정리 · 장시간 안정성", "제안자", "2단계 후", "예정"),
    ("현장 사용 승인 절차 정리 · 확대 승인", "안전 · 운영", "확산 전", "협조"),
    ("보안 조치 — 릴리스 서명 키 발급 · 업데이트 APK 검증", "제안자 · 보안", "확산 전", "협조"),
    ("정식 과제 등록 · 유지보수 주체 지정", "운영 / 기획", "확산 전", "협조"),
    ("17개 센터 확산", "운영 · 현장", "2단계 완료 후", "예정"),
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
       [[("앞으로의 개선 계획  1 ~ 5단계", {"bold": True, "size": 13, "color": C.NAVY}),
         ("     새 기능을 붙이는 게 아니라, 결과가 흔들리는 원인을 걷어내는 작업",
          {"size": 10.5, "color": C.INK2})]])
C.table(s, 1.00, 2.98, 11.35,
        ["단계", "무엇을 하는가", "출하 시 현장에서 확인할 것", "상태"],
        [["1", "고친 문제마다 자동 검사를 붙인다", "설치와 경보가 이전과 똑같이 되는가", "완료"],
         ["2", "안전 동작을 자동 검사로 고정 + 업무용 PDA 로 이전", "천천히 다가오는 지게차에 경고가 뜨는가", "예정"],
         ["3", "한 파일에 몰린 경보 계산 코드를 나눈다", "나누기 전과 동작이 똑같은가", "예정"],
         ["4", "기기 상태 관리 방식을 하나로 통일한다", "2시간 넘게 켜 둬도 느려지지 않는가", "예정"],
         ["5", "경보 계산을 화면과 분리해 따로 돌린다", "폰 20대 이상에서 화면이 끊기지 않는가", "예정"]],
        col_w=[0.75, 4.40, 4.90, 1.30], row_h=0.36, head_h=0.32, size=10, head_size=10,
        aligns=[PP_ALIGN.CENTER, PP_ALIGN.LEFT, PP_ALIGN.LEFT, PP_ALIGN.CENTER])
C.text(s, 1.05, 5.20, 11.3, 0.26,
       [[("이 순서인 이유", {"bold": True, "size": 11, "color": C.NAVY})]])
C.lines(s, 1.10, 5.50, 11.2, [
    [("①  재는 방법을 먼저 만든다", {"bold": True}),
     ("  —  검사가 하나도 없는 상태에서 안전 코드를 건드리면, 문제를 현장에서 발견하게 된다.",
      {"color": C.INK2})],
    [("②  단계마다 따로 배포한다", {"bold": True}),
     ("  —  여러 변경을 한꺼번에 내보내면, 문제가 생겨도 어느 것 때문인지 알 수 없다.",
      {"color": C.INK2})],
    [("③  어느 단계에서 멈춰도 쓸 수 있다", {"bold": True}),
     ("  —  개선이 중간에 멈춰도 지금 버전이 현장에서 그대로 돌아 경보가 끊기지 않는다.",
      {"color": C.INK2})],
], size=10, gap=0.32)

# ── 12. 06. Metrics ─────────────────────────────────────────
# 「미발령」은 뺐다 — 정의상 관측되지 않아 지표로 성립하지 않는다.
s = S[11]
C.fill(C.body_of(s, "성공 지표"), [[("", {})]], margin=0.02, top=C.TOPCHIP)
MX, MY, MW = 0.77, 2.37, 5.84
C.text(s, MX + 0.24, MY + 0.44, MW - 0.48, 0.24,
       [[("네 가지 모두 숫자로 재기로 했다", {"bold": True, "size": 10.5, "color": C.NAVY}),
         ("   밑줄 = 추정치", {"size": 8.5, "color": C.INK3, "u": True})]])
KPI = [
    ("지표 1", "가까워진 횟수", "15m 안까지 접근", f"1교대 {WARN}회", C.AMBER),
    ("지표 2", "위험하게 가까워진 횟수", "8m 안까지 접근", f"1교대 {DANGER}회", C.RED),
    ("지표 3", "쓸데없이 울린 횟수", "서 있는데 계속 울림 · 멀어졌는데 다시 울림",
     "1교대 3회 미만", C.INK2),
    ("지표 4", "작업자 만족도", "울리는 시점과 거리가 맞는가", "10점 중 7.0점", C.TEAL),
]
for i, (tag, name, desc, val, col) in enumerate(KPI):
    C.kpi(s, MX + 0.20, MY + 0.72 + i * 0.86, MW - 0.40, 0.80, tag, name, desc, val, col)
C.text(s, MX + 0.24, MY + 4.14, MW - 0.48, 0.44,
       [[("‘울렸어야 하는데 안 울린 횟수’ 는 지표에서 뺐다", {"bold": True, "size": 9, "color": C.RED}),
         ("  —  아무 일도 일어나지 않은 것이라 셀 방법이 없다. "
          "작업자 설문의 ‘경보가 늦었다’ 응답으로 대신 본다.",
          {"size": 9, "color": C.INK2})]], space=1.25)

C.fill(C.body_of(s, "지표 확보 방법"), [[("", {})]], margin=0.02, top=C.TOPCHIP)
NX = 6.74
C.text(s, NX + 0.24, MY + 0.44, MW - 0.48, 0.24,
       [[("이 숫자는 이렇게 나왔다 — 가정한 값은 하나뿐",
          {"bold": True, "size": 10.5, "color": C.NAVY})]])
C.table(s, NX + 0.20, MY + 0.78, MW - 0.40,
        ["항목", "값", "근거"],
        [["서로 감지되는 짝", "21가지", "지게차 4 + 사람 3 = 7대의 짝 수"],
         ["짝당 시간당 접근", [[("0.5회", EST)]],
          [[("보수적으로 잡은 값 — ", {}), ("유일한 가정", {"bold": True, "color": C.RED})]]],
         ["1교대 15m 접근", [[(f"{WARN}회", ESTB)]], f"21가지 × {SHIFT_H}시간 × 0.5 = 94.5"],
         ["8m 까지 갈 비율", [[(f"{RATIO}", EST)]], "거리 비의 제곱 (8m ÷ 15m)²"],
         ["1교대 8m 접근", [[(f"{DANGER}회", ESTB)]], f"{WARN} × {RATIO} = 26.6"],
         ["쓸데없이 울린 횟수", [[("1교대 3회 미만", EST)]], "자동 소리 끄기 · 재발령 억제 후"],
         ["작업자 만족도", [[("10점 중 7.0점", EST)]], "요구 4건 반영 · 3개 센터 사용 중"]],
        col_w=[1.32, 1.10, 3.02], row_h=0.27, head_h=0.27, size=8.5, head_size=8.5,
        aligns=[PP_ALIGN.LEFT, PP_ALIGN.CENTER, PP_ALIGN.LEFT])
C.lines(s, NX + 0.24, MY + 3.04, MW - 0.48, [
    [("·  ", {}), ("앱이 경보를 울릴 때마다 시각 · 상대 · 거리 · 등급이 서버에 기록되고 있다",
      {"bold": True}), (" — 따로 만들 것이 없다.", {})],
    [("·  같은 상대는 1분에 한 번만 기록되므로 1건 = 가까워진 1분이다. "
      "사고 건수가 아니라 위험했던 순간의 대용 지표로 쓴다.", {})],
    [("·  4주만 모으면 가정한 값이 실제 숫자로 바뀌고, 위 표의 나머지가 전부 따라 바뀐다.", {})],
    [("·  사고 건수는 회사 데이터로 존재하지 않아 지표에 넣지 않았다.", {"color": C.RED})],
], size=9, gap=0.34)

# ── 13. 07. Andon ───────────────────────────────────────────
s = S[12]
C.fill(C.body_of(s, "중단 지표"), [[("", {})]], margin=0.02, top=C.TOPCHIP)
AX = 0.77
C.text(s, AX + 0.24, MY + 0.42, MW - 0.48, 0.24,
       [[("현장 운영 중", {"bold": True, "size": 10.5, "color": C.NAVY})]])
STOP, WATCH = "즉시 중단", "관측 시 판단"
FIELD = [("경보가 안 울렸다", STOP, "가까워졌는데 경보가 뜨지 않은 사례 1건이라도"),
         ("쓸데없이 계속 울린다", STOP, "서 있는데 계속 울림 · 멀어졌는데 다시 울림이 반복"),
         ("감지가 멈췄다", WATCH, "알림 표시줄이 ‘이상’ 으로 바뀌거나 사라짐"),
         ("작업자가 꺼 둔다", WATCH, "소리를 끄거나 앱을 꺼 둔 폰이 늘어남")]
UPD = [("옛 버전과 안 통한다", STOP, "옛 버전 폰이 새 버전 폰을 감지하지 못함"),
       ("고쳤던 문제가 재발", STOP, "이전에 고친 증상이 다시 나타남"),
       ("업데이트 중 무방비", WATCH, "작업 시간 중 일괄 업데이트 금지 — 교대 전환 때만 배포"),
       ("업데이트 후 안 켜진다", WATCH, "업데이트나 역할 변경 뒤 앱이 다시 켜지지 않음")]
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
    [("·  경보 누락 · 오작동 — 작업자 신고와 서버에 남은 경보 기록을 대조한다.", {})],
    [("·  감지 멈춤 · 앱 안 켜짐 — 알림 표시줄이 스스로 ‘이상’ 을 띄운다 (v1.1.64).", {})],
    [("·  버전 섞임 — 배포 뒤 모든 폰의 버전을 확인한다. 신호 형식은 옛 버전과도 통하게 만들었다.", {})],
    [("·  재발 — 자동 검사가 잡는다. 검사가 실패하면 새 버전이 나가지 않는다.", {})],
    [("·  꺼 두는 사람 — 작업자 설문과 소리 끔 설정 비율로 본다.", {})],
], size=9, gap=0.325)
C.text(s, NX + 0.24, MY + 2.46, MW - 0.48, 0.24,
       [[("문제가 생기면 이렇게 되돌린다", {"bold": True, "size": 10.5, "color": C.NAVY})]])
for i, (k, v) in enumerate([
    ("즉시 되돌리기", "직전 버전으로 되돌린다 — 현장에서 다시 설치할 필요가 없다"),
    ("먼저 1대에서", "모든 폰에 배포하기 전에 1대에서 먼저 확인한다"),
    ("배포 차단", "자동 검사가 실패하면 새 버전이 아예 나가지 않는다 (동작 중)"),
    ("원인 특정", "단계를 하나씩 따로 배포하므로 어느 변경 탓인지 알 수 있다"),
    ("재개 조건", "실패한 상황을 자동 검사로 만들어 둔 뒤에만 다음 단계로"),
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
         ["2차", "같은 등급이 5초 넘으면 소리 끄기", "더 가까워지면 즉시 다시 울림"],
         ["3차", "휴게실 · 충전 구역을 안전 구역으로", "그 안에서는 서로 울리지 않음"]],
        col_w=[0.58, 2.87, 2.10], row_h=0.32, head_h=0.27, size=8.5, head_size=8.5,
        aligns=[PP_ALIGN.CENTER, PP_ALIGN.LEFT, PP_ALIGN.LEFT])
C.table(s, FX + 6.20, FY + 0.74, 5.60,
        ["항목", "Before", "After"],
        [["앱 상태", "꺼져도 알 수 없음", "알림 표시줄에 ‘이상’ 표시"],
         ["멀어진 뒤", "멀어졌는데 다시 울림", "경고 거리에서는 다시 울리지 않음"],
         ["알림 끄기", "단계가 번거로움", "알림 본문을 누르면 즉시 무음"]],
        col_w=[1.05, 2.20, 2.35], row_h=0.32, head_h=0.27, size=8.5, head_size=8.5)
C.text(s, FX + 0.28, FY + 2.06, FW - 0.56, 0.24,
       [[("반영 이력은 커밋에 남아 있다  —  ", {"bold": True, "size": 9, "color": C.NAVY}),
         ("리뷰 1·2·3차 v1.1.60 · v1.1.61 · v1.1.62,  앱 상태 표시 v1.1.64,  재발령 억제 v1.1.51,  "
          "알림 눌러 끄기 v1.1.68,  화면 가장자리 경보 v1.1.69 · v1.1.70", {"size": 9})]])
C.fill(C.body_of(s, "확산 계획"),
       [[("① 실제 숫자 확보", {"bold": True, "color": C.NAVY}),
         ("  앱 경보 기록 4주 집계 · WF11 · WF21 · WF25 작업자 설문 회수 — 추정치를 실제 숫자로 바꾼다.",
          {})],
        [("② 업무용 PDA 로 이전", {"bold": True, "color": C.NAVY}),
         ("  2단계에서 개인 폰에 임시로 깔아 둔 상태를 끝낸다.", {})],
        [("③ 확산", {"bold": True, "color": C.NAVY}),
         ("  정식 과제로 등록한 뒤 17개 센터로 — 장비를 사지 않고 앱 배포만으로 늘린다. "
          "확산 전에 보안 검토를 먼저 받는다.", {})]],
       size=10.5, margin=0.24, anchor=MSO_ANCHOR.MIDDLE, space=1.35, gap=5, top=C.TOPCHIP)

prs.save(OUT)
print("wrote", OUT, f"({len(prs.slides)}장)")
