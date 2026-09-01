# CouSolve Report 양식(7단계)에 SafeAlert 내용을 채운다.
#   python3 build_cousolve_deck.py <CouSolve_Report_양식.pptx>
#
# 양식 원본은 사내 문서라 저장소에 넣지 않는다 — 로컬 경로를 인자로 넘긴다.
# 양식의 12장 구성이 그대로 7단계라 장을 늘리거나 줄이지 않았다.
#   1 표지 / 2 1-1 Problem Situation / 3 1-2 Target / 4 1-3 Fishbone
#   5 1-4 Cause Analysis / 6 1-5 5Why / 7 2 Tradeoffs / 8 3 Benchmark
#   9 4 Solution / 10 5 Metrics / 11 6 Andon / 12 7 Feedback Loop
#
# 숫자 원칙 — 없는 데이터는 만들지 않는다.
# 사고 건수 · 셧다운 건수 · 절감 M/H 는 확보돼 있지 않다. 그 자리에는 '미확보' 라고 적는다.
# 금액은 절감액이 아니라 같은 범위를 하드웨어로 덮을 때의 환산액이다.
import os, shutil, sys
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR

import _form as F

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(os.path.dirname(HERE), "company_form", "assets")
OUT = os.path.join(HERE, "SafeAlert_CouSolve_2026.08.pptx")

if len(sys.argv) < 2:
    raise SystemExit("사용법: python3 build_cousolve_deck.py <CouSolve_Report_양식.pptx>")
SRC = os.path.abspath(sys.argv[1])
BASE = os.path.join(HERE, "base_cousolve.pptx")
shutil.copyfile(SRC, BASE)
prs = Presentation(BASE)
assert len(prs.slides) == 12, f"양식이 12장이어야 한다 (지금 {len(prs.slides)}장)"


THEME = "SafeAlert  —  지급된 단말끼리 BLE 로 서로를 감지해, I-PAS 가 덮지 못하는 접근 조합을 경보한다"


def theme_bar(slide):
    """양식 상단의 빈 Theme 바(1.71, 0.87)에 주제를 한 줄 넣는다."""
    for sh in slide.shapes:
        if sh.left is None or not sh.has_text_frame:
            continue
        if abs(sh.left / 914400 - 1.71) < 0.02 and abs(sh.top / 914400 - 0.87) < 0.02:
            tf = sh.text_frame
            tf.word_wrap = True
            p = tf.paragraphs[0]
            p.alignment = PP_ALIGN.LEFT
            for r in list(p.runs):
                r._r.getparent().remove(r._r)
            F._run(p, "  " + THEME, 10.5, False, F.INK2)
            return sh
    return None


def named(slide, *names):
    for sh in slide.shapes:
        if sh.name in names:
            return sh
    raise SystemExit(f"도형을 찾지 못했다: {names}")


def tables(slide):
    return [sh.table for sh in slide.shapes if sh.has_table]


def pic(slide, name, x, y, w, ratio, cap=None, cap_size=8.5):
    h = w / ratio
    slide.shapes.add_picture(os.path.join(ASSETS, name), Inches(x), Inches(y),
                             Inches(w), Inches(h))
    if cap:
        F.text(slide, x, y + h + 0.06, w, 0.24, [(cap, {"color": F.INK3})],
               size=cap_size, align=PP_ALIGN.CENTER)
    return y + h


# ── 1. 표지 ─────────────────────────────────────────────────
s = prs.slides[0]
sh = named(s, "직사각형 1")
tf = sh.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.alignment = PP_ALIGN.CENTER
for r in list(p.runs):
    r._r.getparent().remove(r._r)
F._run(p, "SafeAlert — 사각지대를 사람의 주의력에 맡기지 않는다", 20, True, F.GREEN)
p2 = tf.add_paragraph()
p2.alignment = PP_ALIGN.CENTER
F._run(p2, "지급된 단말끼리 BLE 로 서로를 감지해, I-PAS 가 덮지 못하는 접근 조합을 메운다",
       11, False, F.INK2)
t = tables(s)[0]
F.cell(t.cell(0, 1), [("WF11  Waterflex", {})], 10, PP_ALIGN.CENTER)
F.cell(t.cell(0, 3), [("임효성 (Ian)", {})], 10, PP_ALIGN.CENTER)

for _s in list(prs.slides)[1:]:
    theme_bar(_s)

# ── 2. 1-1 Problem Situation ────────────────────────────────
s = prs.slides[1]
t = tables(s)[0]
def kvrow(items):
    out = []
    for k, v in items:
        out.append([("· ", {"color": F.INK3}), (k + " : ", {"bold": True}), (v, {})])
    return out

ROWS = [
    kvrow([("AS-IS", "존과 로케이션 사이 교차 구간에서 PIT 와 보행자가 서로를 보지 못한 채 접근"),
           ("Pain Point", "적재 파렛트가 시야를 가리고, 장비 소음에 경적이 묻혀 인지가 늦다")]),
    kvrow([("Target", "접근을 사전에 감지해 사람에게 알린다 — 태그 유무와 무관하게"),
           ("Vision", "I-PAS 가 3m 에서 장비를 세우기 전에, 15m · 8m 에서 사람이 먼저 안다")]),
    kvrow([("Data", "I-PAS 태그를 단 장비 · 인원만 덮인다. 보행자끼리 · EPJ · 미장착 장비는 대상 밖"),
           ("Issue", "그 구간에는 감지도 알림도 없어 위험 상황을 확인할 방법 자체가 없다")]),
    [[("덮이는 것은 태그를 산 만큼이다. 태그 없는 조합은 주의력에만 맡겨져 있고, "
       "대상이 늘 때마다 태그를 그만큼 더 사야 한다.", {})]],
    [[("Cost : ", {"bold": True}),
      ("같은 범위를 하드웨어로 덮으면 센터 1곳 ", {}), ("3,310,000원", {"bold": True, "color": F.RED}),
      (" · 17개 센터 ", {}), ("56,270,000원", {"bold": True, "color": F.RED}), (".", {})],
     [("미확보 : ", {"bold": True, "color": F.RED}),
      ("사고 건수 · 셧다운 발생 건수는 데이터가 없다 — 감지 수단이 없어 집계된 적이 없다.",
       {"color": F.RED})]],
    [[("WF11 현장 확인 (VC OB 제외) · 단가는 사용자 제공 2026.08 기준 · "
       "판정 반경은 DevSettings.kt 실측 설정값.", {})]],
]
for i, v in enumerate(ROWS):
    F.cell(t.cell(i, 1), v, 9.5, PP_ALIGN.LEFT, space=1.3)

pic(s, "fig_aisle.jpg", 7.83, 2.42, 4.70, 1200 / 750,
    "존과 로케이션 사이 통로 — 교차 구간에서 서로 보이지 않는다")
F.table(s, 7.83, 5.66, 4.70,
        ["접근 조합", "I-PAS", "SafeAlert"],
        [["PIT ↔ 보행자 (태그 착용)", "○", "○"],
         ["보행자 ↔ 보행자", "✕", "○"],
         ["EPJ ↔ 보행자", "✕", "○"],
         ["미장착 장비 ↔ 보행자", "✕", "○"]],
        col_w=[2.70, 1.00, 1.00], row_h=0.26, head_h=0.27, size=8.5, head_size=8.5,
        aligns=[PP_ALIGN.LEFT, PP_ALIGN.CENTER, PP_ALIGN.CENTER],
        colors=[F.INK, F.RED, F.GREEN])

# ── 3. 1-2 Target ───────────────────────────────────────────
s = prs.slides[2]
t = tables(s)[0]
TARGET = [
    [[("I-PAS 가 덮지 못하는 접근 조합에서, PIT · EPJ · 보행자가 서로에게 다가오는 것을 "
       "사전에 감지해 경보한다.", {"bold": True})],
     [("장비를 세우는 일은 하지 않는다 — 3m 자동 셧다운은 I-PAS EOD 가 그대로 맡는다.",
       {"color": F.INK2})]],
    [[("이미 지급된 단말에 앱을 설치해 BLE 로 서로를 감지한다. 앵커 · 배선 · 서버가 없다.", {})],
     [("신호 수신  →  거리 추정  →  등급 판정  →  3중 경보", {"bold": True})],
     [("BLE 광고   →   3단 필터   →   상태머신   →   소리 · 진동 · 화면",
       {"italic": True, "color": F.INK3, "size": 9})],
     [("판정 반경 — 지게차가 낀 조합 경고 15m / 위험 8m,  그 밖의 조합 경고 5m / 위험 3m.",
       {"color": F.INK2})]],
    [[("추가 하드웨어 ", {}), ("0원", {"bold": True, "color": F.RED}),
      ("으로 센터 1곳 3,310,000원 범위를 덮는다. 17개 센터 확산 시 56,270,000원 규모.", {})],
     [("절감액이 아니라 같은 범위를 하드웨어로 살 때의 환산액이다. 앱은 그 구매를 만들지 않는다.",
       {"color": F.INK2})]],
    [[("Phase 1 (테스트 하네스 · CI 회귀 게이트) 완료. Phase 2 (골든 테스트 + PDA 이식) 착수 "
       "결정이 필요한 시점이다.", {})],
     [("Phase 3~5 는 순차. 결정이 늦어도 v1.1.70 이 현장에서 계속 돈다 — 보호가 멈추지는 않는다.",
       {"color": F.INK2})]],
]
for i, v in enumerate(TARGET):
    F.cell(t.cell(i, 1), v, 9.5, PP_ALIGN.LEFT, space=1.3)

# ── 4. 1-3 Fishbone Diagram ─────────────────────────────────
# 양식의 뼈대는 그룹 도형이라 건드리지 않고, 가시선 위에 글자만 얹는다.
# 그룹 좌표계(chOff/chExt) → 슬라이드 좌표 변환은 아래 두 줄이 전부다.
s = prs.slides[3]
GX, GY, SX, SY = 0.8382, 2.2092, 0.94937, 0.97129
CHX, CHY = 0.5633, 1.7510
to_x = lambda cx: GX + (cx - CHX) * SX
to_y = lambda cy: GY + (cy - CHY) * SY
BONE_W = 2.27 * SX

BONES = [
    # (뼈 왼쪽 끝 child x, child y, 항목 3개)
    (1.82, [2.70, 3.16, 3.62], ["육안 · 경적에만 의존", "위험 상황 기록 체계 없음",
                                "안전 설비가 장비 단위로만 설계"]),          # Method
    (5.21, [2.70, 3.16, 3.62], ["적재 파렛트에 시야 가림", "장비 소음에 경적이 묻힘",
                                "단독 작업 구간 상호 확인 불가"]),           # Man
    (1.82, [4.81, 5.27, 5.73], ["I-PAS 는 태그 대상만 감지", "EOD 는 3m 에서만 개입",
                                "EPJ · 미장착 장비는 대상 밖"]),             # Machine
    (5.21, [4.81, 5.27, 5.73], ["태그 1세트 855,000원", "보행자 태그 = 상시 인원분",
                                "비콘 · 거치대 설치가 별도"]),               # Material
]
for cx, cys, items in BONES:
    for cy, it in zip(cys, items):
        F.text(s, to_x(cx) + 0.04, to_y(cy) - 0.235, BONE_W - 0.06, 0.22,
               [(it, {})], size=8.5, color=F.INK2)

F.text(s, to_x(10.14) + 0.10, to_y(3.30) + 0.28, 2.354 - 0.20, 1.30,
       [("사각지대에서 서로를 보지 못한 채 접근한다", {"bold": True, "color": F.GREEN})],
       size=10.5, align=PP_ALIGN.CENTER)
F.text(s, to_x(10.14) + 0.10, to_y(3.30) + 1.00, 2.354 - 0.20, 0.70,
       [("Machine · Material 이 같은 뿌리를 가리킨다 — "
         "감지 주체가 별도 하드웨어에 묶여 있다", {"color": F.GREEN2})],
       size=8.5, align=PP_ALIGN.CENTER, space=1.25)

# ── 5. 1-4 Cause Analysis ───────────────────────────────────
s = prs.slides[4]
t = tables(s)[0]
CAUSES = [
    ("적재 파렛트가 시야를 가린다", "WF11 통로 현장 확인", "확인됨 — 교차 구간 상호 시야 불가"),
    ("장비 소음으로 경적 인지가 늦다", "현장 청취 · 작업자 진술", "확인됨"),
    ("I-PAS 는 태그 장착 대상만 덮는다", "I-PAS 구성 확인 (차량 태그 · EOD · 보행자 태그)",
     "확인됨 — 보행자끼리 · EPJ 는 대상 밖"),
    ("대상이 늘면 태그를 그만큼 더 산다", "단가 확인 (1세트 855,000원, 2026.08)",
     "확인됨 — 확산이 곧 구매"),
    ("위험 상황이 집계되지 않는다", "안전 시스템 유무 확인",
     [[("확인됨 — ", {}), ("사고 · 셧다운 건수 데이터 없음", {"color": F.RED})]]),
    ("경보가 잦으면 작업자가 꺼버린다", "리더십 리뷰 2차 피드백",
     "확인됨 → 자동 음소거 · 세이프존으로 대응"),
]
for i, row in enumerate(CAUSES, start=1):
    for j, v in enumerate(row):
        F.cell(t.cell(i, j), v if not isinstance(v, str) else [(v, {})],
               9.5, PP_ALIGN.LEFT if j == 0 else PP_ALIGN.CENTER, space=1.15)
F.cell(t.cell(7, 0),
       [[("확인 방법은 현장 확인 · 단가 대조 · 배포 이력 세 가지뿐이다. ", {}),
         ("계측 데이터로 검증한 항목은 없다 — 감지 수단이 없어 측정된 적이 없기 때문이다.",
          {"color": F.RED})]],
       9, PP_ALIGN.LEFT)

# ── 6. 1-5 5Why ─────────────────────────────────────────────
s = prs.slides[5]
t = tables(s)[0]
F.cell(t.cell(0, 0),
       [[("* Most likely causes :   ", {"color": F.INK2}),
         ("접근을 사전에 인지할 수단이 태그를 장착한 대상에만 있다", {"bold": True})]],
       10, PP_ALIGN.LEFT)
WHY = [
    (1, "왜 사각지대에서 위험한가?", "서로를 보지 못한 채 접근하기 때문"),
    (3, "왜 보지 못하나?", "적재 파렛트가 시야를 가리고, 장비 소음에 경적이 묻힌다"),
    (5, "왜 보완 수단이 없나?", "I-PAS 는 태그를 단 장비 · 인원만 감지한다"),
    (7, "왜 전부 태그를 달지 못하나?", "사람 · 장비가 늘 때마다 1세트 855,000원을 더 사야 한다"),
    (9, "왜 그 방식뿐인가?",
     "감지 주체를 하드웨어에 두었다. 이미 지급된 단말이 감지 주체가 될 수 있다는 전제를 쓰지 않았다"),
]
for r, q, ans in WHY:
    F.cell(t.cell(r, 1), [(q, {"color": F.GREEN2, "bold": True})], 10, PP_ALIGN.LEFT)
    F.cell(t.cell(r + 1, 1), [[("→   ", {"color": F.INK3}), (ans, {})]], 9.5, PP_ALIGN.LEFT)
F.cell(t.cell(11, 0),
       [[("* Root Cause :   ", {"color": F.INK2}),
         ("감지 수단이 별도 하드웨어에 묶여 있다", {"bold": True, "color": F.RED}),
         ("  —  산 만큼만 덮이고, 태그 없는 조합은 영구히 빈다.", {"bold": True})]],
       10, PP_ALIGN.LEFT)

# ── 7. 2. Tradeoffs ─────────────────────────────────────────
# 계약사 자료의 'A vs. B → 우선한 쪽' 표기를 그대로 쓴다.
# 위 구역은 무엇을 포기했는지, 아래 구역은 포기한 쪽을 무엇으로 메웠는지다.
s = prs.slides[6]
F.versus(s, 2.25, 2.28, 10.35, [
    ("감지 민감도  vs.  경보 피로", [("민감도를 택했다", {"bold": True}),
      ("  —  넓게 울리되, 울릴 곳을 좁힌다", {"color": F.INK2})]),
    ("감지 주기  vs.  배터리 · 발열", [("1교대 구동을 상한으로 둔다", {"bold": True}),
      ("  —  주기를 그 안에서만 촘촘히", {"color": F.INK2})]),
    ("배포 속도  vs.  판정 재현성", [("재현성을 택했다", {"bold": True}),
      ("  —  3개월 70회에서 게이트 통과 방식으로", {"color": F.INK2})]),
    ("앱 경보  vs.  물리 정지", [("물리 정지는 포기했다", {"bold": True, "color": F.RED}),
      ("  —  I-PAS EOD 를 그대로 남긴다", {"color": F.INK2})]),
    ("개인 폰 임시 설치  vs.  지속 가능성", [("지속 가능성을 택했다", {"bold": True}),
      ("  —  업무 단말로 옮긴다", {"color": F.INK2})]),
], size=9.5, gap=0.345)

F.zone(s, 2.25, 4.20, 10.35, [
    (None, [
        [("자동 음소거", {"bold": True, "color": F.GREEN2}),
         ("   같은 기기 · 같은 등급에 5초 이상 머물면 소리 · 진동을 끈다. 표시 · 기록은 유지하고, "
          "등급이 오르면 즉시 재발령", {}), ("  (v1.1.61)", {"color": F.INK3})],
        [("세이프존", {"bold": True, "color": F.GREEN2}),
         ("   휴게실 · 충전 구역에 비콘을 두면 그 안에서는 경보를 억제한다. 존 안의 기기는 "
          "상대에게도 안전으로 보인다", {}), ("  (v1.1.62)", {"color": F.INK3})],
        [("이탈 재발령 억제", {"bold": True, "color": F.GREEN2}),
         ("   멀어진 뒤 경고 범위에서 다시 울리지 않게 했다", {}), ("  (v1.1.51)", {"color": F.INK3})],
        [("CI 회귀 게이트", {"bold": True, "color": F.GREEN2}),
         ("   고친 증상을 테스트로 고정한다. 골든 테스트가 실패하면 릴리스가 자동 차단된다", {}),
         ("  (Phase 1 완료)", {"color": F.INK3})],
        [("계층 분리", {"bold": True, "color": F.GREEN2}),
         ("   I-PAS 를 걷어내지 않는다. 3m 자동 셧다운은 그대로 두고 그 앞단(15m · 8m)만 앱이 맡는다",
          {})],
        [("PDA 이식", {"bold": True, "color": F.GREEN2}),
         ("   Phase 2 에서 업무 단말로 옮긴다. 개인 폰 의존과 배터리 부담이 함께 없어진다", {}),
         ("  (예정)", {"color": F.INK3})],
    ]),
], gap=0.375)
F.quote(s, 6.66, "완벽한 판정보다 현장이 계속 켜 두는 경보를 택했다. 꺼 둔 단말은 보호가 0 이다.",
        x=2.25, w=10.35)

# ── 8. 3. Benchmark ─────────────────────────────────────────
s = prs.slides[7]
F.text(s, 2.25, 2.28, 10.35, 0.28,
       [("현행 장비 ", {}), ("I-PAS", {"bold": True, "color": F.GREEN2}),
        ("  —  차량 태그 525,000원 · EOD (3m 자동 셧다운) 220,000원 · 보행자 태그 110,000원. "
         "일반 WF 센터는 PIT 4대 · 상시 3명 기준으로 센터 1곳 3,310,000원.", {})], size=9.5)
F.table(s, 2.25, 2.62, 10.35,
        ["구분", "I-PAS", "SafeAlert"],
        [["동작", "3m 내 감지 시 자동 셧다운 — 최후 개입", "15m 경고 · 8m 위험 경보. 장비 정지는 못 한다"],
         ["덮는 대상", "태그를 단 장비와 사람", "앱을 넣은 모든 단말 (보행자끼리 · EPJ 포함)"],
         ["대상 확대", "사람 · 장비가 늘면 태그를 그만큼 더 산다", "앱 설치만 — 추가 구매 없음"],
         ["유지보수", "하드웨어 고장 · 배터리 교체", "앱 업데이트 (자동 배포)"]],
        col_w=[1.35, 4.50, 4.50], row_h=0.32, head_h=0.30, size=9.5, head_size=9.5)
F.text(s, 2.25, 4.28, 10.35, 0.28,
       [("같은 대상을 두 방식으로 덮었을 때", {"bold": True, "color": F.GREEN2}),
        ("   —   아래 막대는 도입 센터 수별 하드웨어 환산 누계 (만원)", {"color": F.INK3})], size=9.5)
F.lines(s, 2.35, 4.62, 10.25, [
    [("·  하드웨어 — 센터 1곳 ", {}), ("3,310,000원", {"bold": True}),
     ("  (차량 태그 210만 + EOD 88만 + 보행자 태그 33만)", {})],
    [("·  앱 — 추가 구매 ", {}), ("0원", {"bold": True}),
     (". 대상이 늘어도 금액이 늘지 않는다. 단말이 늘수록 감지쌍은 배로 (3대 3쌍 → 10대 45쌍)", {})],
    [("·  다만 EOD 의 3m 자동 셧다운은 대체하지 못한다. 금액 비교는 감지 · 경보 범위에 한한다.",
      {"color": F.RED})],
    [("·  세이프존 비콘과 iOS 인원 보완 태그(11만원/개)는 대상 확정 전이라 산입하지 않았다.",
      {"color": F.INK2})],
], size=9.5, gap=0.28)
F.chart(s, 3.20, 5.76, 8.40, 1.34,
        ["센터 1곳", "5개 센터", "10개 센터", "17개 센터 (전체)"],
        [331, 1655, 3310, 5627], label_size=10, cat_size=9)

# ── 9. 4. Solution ──────────────────────────────────────────
s = prs.slides[8]
left = [t for t in tables(s) if len(t.columns) == 1][0]
plan = [t for t in tables(s) if len(t.columns) == 4][0]
F.cell(left.cell(1, 0),
       [[("지급 단말에 SafeAlert 를 설치해 BLE 로 상호 감지한다.", {"bold": True})],
        [("지게차가 낀 조합 경고 15m / 위험 8m, 그 밖의 조합 5m / 3m. 소리 · 진동 · 화면 3중 경보. "
          "v1.1.70 이 WF11 · WF21 · WF25 에서 운영 중.", {"color": F.INK2})]],
       9.5, PP_ALIGN.LEFT, MSO_ANCHOR.TOP, space=1.25)
F.cell(left.cell(3, 0),
       [("추가 하드웨어 없이 I-PAS 가 비워 둔 조합을 지금 덮는다. 대상이 늘어도 구매가 없다.", {})],
       9.5, PP_ALIGN.LEFT, MSO_ANCHOR.TOP, space=1.2)
F.cell(left.cell(5, 0),
       [[("신뢰성 로드맵 Phase 1~5 + Phase 2 PDA 이식.", {"bold": True})],
        [("새 기능을 붙이는 작업이 아니다. 판정이 흔들리는 구조적 원인을 걷어내고, "
          "개인 폰 임시 설치를 업무 단말로 옮긴다.", {"color": F.INK2})]],
       9.5, PP_ALIGN.LEFT, MSO_ANCHOR.TOP, space=1.25)
F.cell(left.cell(7, 0),
       [("판정 재현성이 확보돼야 확산할 수 있다. PDA 로 옮기면 개인 폰 의존과 배터리 부담이 함께 없어진다.",
         {})], 9.5, PP_ALIGN.LEFT, MSO_ANCHOR.TOP, space=1.2)

PLAN = [
    ("Phase 1  테스트 하네스 · CI 회귀 게이트", "개발", "완료", "완료"),
    ("Phase 2  안전 경로 골든 테스트 + PDA 이식", "개발 · 현장", "리소스 배정 후", "예정"),
    ("Phase 3  BleService 분해", "개발", "Phase 2 후", "예정"),
    ("Phase 4  기기 상태 단일화", "개발", "Phase 3 후", "예정"),
    ("Phase 5  판정 워커 분리", "개발", "Phase 4 후", "예정"),
    ("정식 과제 등록 · 유지보수 주체 지정", "리더십", "결정 필요", "요청"),
    ("현장 검증 슬롯 (단계당 확인 1건)", "현장", "단계별", "요청"),
    ("보안 검토 — 난독화 미적용 · 이력 평문 저장", "보안", "확산 전", "요청"),
    ("서베이 회수 · 집계 (WF11 · WF21 · WF25)", "현장", "진행 중", "진행"),
    ("17개 센터 확산", "리더십 · 현장", "Phase 2 완료 후", "미정"),
]
STCOL = {"완료": F.GREEN, "예정": F.INK2, "요청": F.RED, "진행": F.GREEN2, "미정": F.INK3}
for i, (what, who, when, st) in enumerate(PLAN, start=2):
    F.cell(plan.cell(i, 0), [(what, {})], 9, PP_ALIGN.LEFT, margin=0.06)
    F.cell(plan.cell(i, 1), [(who, {})], 9, PP_ALIGN.CENTER, margin=0.03)
    F.cell(plan.cell(i, 2), [(when, {})], 9, PP_ALIGN.CENTER, margin=0.03)
    F.cell(plan.cell(i, 3), [(st, {"bold": True, "color": STCOL[st]})], 9, PP_ALIGN.CENTER, margin=0.03)

# ── 10. 5. Metrics ──────────────────────────────────────────
# 계약사 자료의 '핵심 KPI 카드 + 측정 방식' 표기.
# 넷 다 목표가 0건이다 — 사고 건수 데이터가 없어 앱 동작으로만 정의했다.
s = prs.slides[9]
BLUE = RGBColor(0x1F, 0x5C, 0x8B)
KPI = [
    ("KPI 1", "미발령", BLUE, "경고 · 위험 범위 진입 후\n경보 미발생", "0 건"),
    ("KPI 2", "오발령", F.RED, "정지 중 반복 경보 ·\n이탈 후 재발령", "0 건"),
    ("KPI 3", "감지 중단", RGBColor(0xB9, 0x77, 0x0B), "상시 알림 ‘이상’ 전환 ·\n서비스 소실", "0 건"),
    ("KPI 4", "회귀 재발", F.GREEN, "이전에 고친 증상이\n다시 관측", "0 건"),
]
for i, (tag, name, col, desc, goal) in enumerate(KPI):
    x = 2.35 + i * 2.56
    d1, d2 = desc.split("\n")
    F.kpi_card(s, x, 2.26, 2.42, 1.62, tag, name,
               [([(d1, {"color": F.INK2})], 8.5),
                ([(d2, {"color": F.INK2})], 8.5),
                ([("목표  ", {"color": F.INK3}), (goal, {"bold": True, "color": col, "size": 13})], 9)],
               col)
F.lines(s, 2.35, 3.98, 10.25, [
    [("보조 지표  ", {"bold": True, "color": F.GREEN2}),
     ("1교대 연속 구동 시 배터리 · 발열이 작업 시간을 버티는가  ·  서베이 종합 만족도 "
      "(10점 척도, WF11 · WF21 · WF25 — 회수 전)", {})],
], size=9.5, gap=0.28)

F.lines(s, 2.35, 4.32, 10.25, [
    [("·  경보 이력 — Firebase 에 등급 · 거리 · 시각이 기록된다. "
      "KPI 1 · 2 는 이 기록과 현장 진술을 맞춰 판정한다.", {})],
    [("·  현장 서베이 — 10점 척도(10 매우 만족 ~ 1 매우 미흡). 센터별 응답자 수와 테스트 기간을 "
      "함께 적어야 표본 크기를 판단할 수 있다.", {})],
    [("·  실기 검증 — 단계당 확인 항목 1건. 전 단말 배포 전 1대에서 먼저 확인한다.", {})],
    [("·  CI 골든 테스트 — 안전 크리티컬 경로가 실패하면 릴리스가 자동 차단된다 "
      "(Phase 1 에서 동작 중). KPI 4 는 여기서 걸린다.", {})],
    [("·  지표는 앱 동작 기준으로만 정의했다. ", {}),
     ("사고 건수 · 셧다운 건수는 데이터가 없어 지표로 쓰지 않는다.", {"color": F.RED})],
], size=9.5, gap=0.315)
F.quote(s, 6.22, "고쳤다는 말 대신 0건을 세기로 했다. 세는 방법이 없으면 지표로 쓰지 않는다.",
        x=2.25, w=10.35)

# ── 11. 6. Andon ────────────────────────────────────────────
# 개발 게이트가 아니라 현장에서 실제로 벌어지는 상황과, 업데이트 · 수정 중 상황이다.
s = prs.slides[10]
STOP, WATCH = "즉시 중단", "관측 시 판단"
SCEN = [
    ("현장 운영 중", [
        ("미발령", STOP, "접근했는데 경보가 뜨지 않은 사례 1건이라도"),
        ("오발령 지속", STOP, "정지 중 계속 울림 · 이탈 후 다시 울림이 반복"),
        ("감지 중단", WATCH, "상시 알림이 ‘이상’ 으로 바뀌거나 사라짐"),
        ("경보 피로", WATCH, "무음으로 두거나 앱을 꺼 둔 단말이 늘어남"),
    ]),
    ("업데이트 · 수정 중", [
        ("버전 혼재", STOP, "구버전 단말이 신버전을 감지하지 못함"),
        ("회귀 재발", STOP, "이전에 고친 증상이 다시 관측됨"),
        ("보호 공백", WATCH, "작업 시간 중 일괄 업데이트 — 교대 전환 때만 배포"),
        ("서비스 미기동", WATCH, "업데이트 · 역할 전환 후 백그라운드 실행 미복구"),
    ]),
]
for ci, (head, items) in enumerate(SCEN):
    cx = 2.25 + ci * 5.20
    F.text(s, cx, 2.26, 5.00, 0.26,
           [("▪ ", {"color": F.GREEN2, "bold": True}), (head, {"bold": True, "color": F.GREEN2})],
           size=10)
    for i, (name, tag, desc) in enumerate(items):
        y = 2.58 + i * 0.40
        F.text(s, cx + 0.10, y, 4.85, 0.22,
               [(name, {"bold": True}), ("   " + tag, {"bold": True, "size": 8.5,
                                                       "color": F.RED if tag == STOP else F.INK2})],
               size=9.5)
        F.text(s, cx + 0.10, y + 0.19, 4.85, 0.21, [(desc, {"color": F.INK2})], size=8.5)

ROLL = [
    ("즉시 롤백", "직전 태그 APK 로 되돌린다 — 현장 재설치 없이 배포된다"),
    ("선행 확인", "전 단말 배포 전 1대에서 먼저 확인한다"),
    ("빌드 차단", "골든 테스트가 실패하면 릴리스가 자동 차단된다 (동작 중)"),
    ("원인 귀속", "단계를 단독 배포하므로 어느 변경 탓인지 특정된다"),
    ("재개 조건", "실패한 상황을 테스트로 고정한 뒤에만 다음 단계로"),
]
for i, (k, v) in enumerate(ROLL):
    F.text(s, 2.25, 4.24 + i * 0.34, 10.35, 0.30,
           [(k, {"bold": True, "color": F.GREEN2}), ("   —   ", {"color": F.INK3}), (v, {})],
           size=9.5)
F.text(s, 2.25, 6.06, 10.35, 0.28,
       [("멈출 기준을 먼저 정해 두는 것이, 멈추지 않고 밀어붙이는 것보다 빠르다.",
         {"italic": True, "color": F.INK2})], size=9.5)

# ── 12. 7. Feedback Loop ────────────────────────────────────
s = prs.slides[11]
F.text(s, 0.85, 2.16, 5.80, 0.26,
       [("▪ ", {"color": F.GREEN2, "bold": True}), ("리더십 리뷰 반영", {"bold": True, "color": F.GREEN2})],
       size=10)
F.table(s, 0.85, 2.48, 5.75,
        ["차수", "반영 내용", "결과"],
        [["1차", "역할 전환 버튼 · EPJ 역할 숨김 · 스플래시 교체", "재설치 없이 화면에서 역할 전환"],
         ["2차", "같은 기기 · 같은 등급 5초 이상이면 자동 음소거", "표시 · 기록 유지, 등급 오르면 재발령"],
         ["3차", "세이프존 — 휴게실 · 충전 구역 비콘으로 경보 억제", "존 안의 기기는 상대에게도 안전"]],
        col_w=[0.60, 2.95, 2.20], row_h=0.44, head_h=0.28, size=8.5, head_size=8.5,
        aligns=[PP_ALIGN.CENTER, PP_ALIGN.LEFT, PP_ALIGN.LEFT])
F.text(s, 6.90, 2.16, 5.80, 0.26,
       [("▪ ", {"color": F.GREEN2, "bold": True}), ("현장 요구 반영", {"bold": True, "color": F.GREEN2})],
       size=10)
F.table(s, 6.90, 2.48, 5.75,
        ["항목", "Before", "After"],
        [["보호 상태", "끊겨도 작업자가 알 수 없음", "감지가 멈추면 상시 알림에 이상 표시"],
         ["이탈 후", "멀어졌는데 다시 울림", "경고 범위 재발령 억제"],
         ["알림 끄기", "단계가 번거로움", "알림 본문을 누르면 즉시 무음"],
         ["경보 화면", "눈에 잘 들어오지 않음", "화면 가장자리에 붙는 사이드바"]],
        col_w=[1.05, 2.30, 2.40], row_h=0.32, head_h=0.28, size=8.5, head_size=8.5)
F.text(s, 0.85, 4.62, 11.70, 0.26,
       [("반영은 코드에 남아 있다  —  ", {"bold": True, "color": F.GREEN2}),
        ("리뷰 1·2·3차 v1.1.60 · v1.1.61 · v1.1.62,  보호 끊김 v1.1.64,  이탈 재발령 v1.1.51,  "
         "알림 본문 탭 v1.1.68,  사이드바 v1.1.69 · v1.1.70", {})], size=9)
F.text(s, 0.85, 4.96, 11.70, 0.60,
       [("피드백이 들어오면 그 주에 배포했다. 3개월 70회 이상. 문제는 속도가 아니라 재현성이었다  —  "
         "빨리 고친 증상이 되돌아왔고, 그래서 다음 작업은 새 기능이 아니라 테스트와 CI 게이트다.", {})],
       size=9.5, space=1.3)

F.lines(s, 0.85, 6.46, 5.70, [
    "역할 전환 · 자동 음소거 · 세이프존으로 경보 피로를 억제했다",
    "보호 끊김과 서비스 미기동이 상시 알림에 드러난다",
    "Phase 1 완료 — 고친 증상은 테스트로 고정돼 빌드에서 걸린다",
], size=8.5, gap=0.215)
F.lines(s, 6.94, 6.46, 5.70, [
    "WF11 · WF21 · WF25 서베이 회수 · 집계 (10점 척도)",
    "Phase 2 PDA 이식 — 개인 폰 임시 설치를 업무 단말로",
    "정식 과제 등록 · 리소스 배정이 선행되어야 17개 센터로 확산",
], size=8.5, gap=0.215)

prs.save(OUT)
print("wrote", OUT)
