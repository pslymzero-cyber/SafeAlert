# LFP 사내 폼(표지·목차·본문 레이아웃)을 그대로 쓰고 내용만 SafeAlert 로 갈아 끼운다.
# 본문 장은 CouSolve Idea Contest 자료의 표현 장치(_devices.py)로 바꿔 그렸다 —
# 회색 라벨 · As-is/To-be 표 · Tradeoffs 화살표 · Before/After 표 · 하단 이탤릭 결론.
# 폼 자체(흰 배경 · 상단 색 바 · 로고 · CFS 쪽번호)는 사내 표준 그대로 둔다.
import copy, os
from pptx import Presentation
from pptx.util import Inches, Emu, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
from pptx.enum.shapes import MSO_SHAPE

import _devices as D

SRC, OUT = "base.pptx", "SafeAlert_보고_2026.08.pptx"
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
A = lambda n: f"{HERE}/assets/{n}"

prs = Presentation(SRC)
NS = "{http://schemas.openxmlformats.org/drawingml/2006/main}"


def body_of(slide):
    for sh in slide.shapes:
        if sh.has_text_frame and not sh.is_placeholder:
            return sh
    raise SystemExit("본문 텍스트박스를 찾지 못했다")


def title_of(slide):
    for sh in slide.shapes:
        if sh.is_placeholder and sh.has_text_frame and "번호" not in sh.name:
            return sh
    raise SystemExit("제목 자리표시자를 찾지 못했다")


def set_text(shape, text):
    tf = shape.text_frame
    p = tf.paragraphs[0]
    for r in p.runs[1:]:
        r._r.getparent().remove(r._r)
    if p.runs:
        p.runs[0].text = text
    else:
        p.add_run().text = text


def write_body(slide, lines):
    """lines: [(레벨, 글자)] — 0='1)' 소제목, 1='① 항목', 2='→ 부연'. 목차 장에서만 쓴다."""
    tf = body_of(slide).text_frame
    paras = tf.paragraphs
    proto = [copy.deepcopy(paras[0]._p), copy.deepcopy(paras[1]._p), copy.deepcopy(paras[2]._p)]
    txBody = paras[0]._p.getparent()
    for p in list(txBody.findall(NS + "p")):
        txBody.remove(p)
    for lvl, text in lines:
        p = copy.deepcopy(proto[lvl])
        runs = p.findall(NS + "r")
        for r in runs[1:]:
            p.remove(r)
        if not runs:
            p.append(copy.deepcopy(proto[lvl].findall(NS + "r")[0]))
            runs = p.findall(NS + "r")
        t = runs[0].find(NS + "t")
        t.text = text
        t.set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
        txBody.append(p)


def picture(slide, name, x, y, w, cap=None, ratio=None):
    img = A(name)
    h = w / ratio
    slide.shapes.add_picture(img, Inches(x), Inches(y), Inches(w), Inches(h))
    if cap:
        D.text(slide, x, y + h + 0.1, w, 0.3, [(cap, {"size": 10, "color": D.INK2})],
               align=PP_ALIGN.CENTER)
    return y + h


# ── 1. 표지 ──────────────────────────────────────────────────
s = prs.slides[0]
for sh in s.shapes:
    if sh.has_text_frame and "LFP" in sh.text_frame.text:
        set_text(sh, "SafeAlert  근접 경보 앱")
    elif sh.has_text_frame and "2026.08" in sh.text_frame.text:
        p = sh.text_frame.paragraphs[0]
        for r in p.runs[1:]:
            r._r.getparent().remove(r._r)
        p.runs[0].text = "2026.08 |  Flex Fulfillment Dynamic Ops  Waterflex"

# ── 2. Content ──────────────────────────────────────────────
write_body(prs.slides[1], [
    (0, "한 장 요약"),
    (0, " "),
    (0, "1. 개발 배경"),
    (1, "  1) 문제의 배경 · 현재 상황     2) 왜 앱으로 만들었나"),
    (0, "2. 기대효과"),
    (1, "  1) As-is / To-be"),
    (0, "3. 비용"),
    (1, "  1) I-PAS 대비 비교     2) 확산 규모 환산"),
    (0, "4. 장단점"),
    (1, "  1) Tradeoffs     2) 남는 한계"),
    (0, "5. 사용자 피드백"),
    (1, "  1) 오더십 리뷰 반영     2) 현장 요구 반영 (Before / After)"),
    (0, "6. 시연"),
    (1, "  1) 동작 화면 · 설정 화면     2) 시연 영상 · 판정 시뮬레이터"),
    (0, "7. 진행 단계 (Phase 1 ~ 5)"),
    (0, "8. Andon — 현장 · 배포 중 중단 기준"),
    (0, " "),
    (0, "9. 요청 사항"),
    (1, "  정식 과제 등록과 리소스 배정"),
])

# ── 3. 한 장 요약 ───────────────────────────────────────────
# 리더십이 "무엇을 어필하는지 모르겠다" 고 했다. 결론과 요청을 첫 장에 박는다.
s = prs.slides[2]
set_text(title_of(s), "한 장 요약")
D.clear_body(body_of(s))
D.text(s, 0.7, 1.12, 11.93, 0.6,
       [("사각지대를 사람의 주의력에 맡기지 않는다", {"bold": True, "size": 27})])
D.text(s, 0.7, 1.80, 11.93, 0.36,
       [("3m 렉 사이에서 서로 보이지 않는 구간을, 이미 지급된 단말끼리 신호를 주고받아 메운다", {"size": 13, "color": D.INK2})])
FACTS = [
    ("3개월 · 70회+", "현장에 배포하며 다듬은 기간"),
    ("추가 하드웨어 0", "I-PAS 1세트 약 54만원 대비"),
    ("리뷰 3차 · 요구 4건", "오더십과 현장 지적 반영 완료"),
    ("로드맵 1 / 5", "신뢰성 확보 단계 · 전 단계 출하 가능"),
]
for i, (big, cap) in enumerate(FACTS):
    x = 0.85 + i * 3.0
    D.text(s, x, 2.42, 2.9, 0.42, [(big, {"bold": True, "size": 18, "color": D.RED})])
    D.text(s, x, 2.88, 2.9, 0.34, [(cap, {"size": 11, "color": D.INK2})])

box = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.7), Inches(3.62), Inches(11.93), Inches(2.62))
box.fill.solid(); box.fill.fore_color.rgb = RGBColor(0xF7, 0xF8, 0xF9)
box.line.color.rgb = D.LINE; box.line.width = Pt(1); box.shadow.inherit = False
box.text_frame.text = ""
D.text(s, 1.05, 3.88, 11.2, 0.42,
       [("요청  —  ", {"bold": True, "size": 15, "color": D.RED}),
        ("SafeAlert 를 정식 과제로 등록하고, 유지 · 검증에 필요한 리소스를 배정해 주십시오", {"bold": True, "size": 15})])
for i, t in enumerate([
    "①  유지보수 주체 지정 — 지금은 개인 작업물이라 승계 경로가 없다",
    "②  개발 시간 배정 — Phase 2~5 는 기능 추가가 아니라 판정 재현성 확보다",
    "③  현장 검증 슬롯 — 실기 검증이 유일한 회귀 확인 수단, 단계당 확인 1건",
    "④  보안 검토 — 릴리스 난독화 미적용 · 경보 이력 평문 저장 (확산 전 선행)",
]):
    D.text(s, 1.15, 4.45 + i * 0.38, 11.0, 0.36, [(t, {"size": 12})])
D.text(s, 1.05, 6.42, 11.2, 0.34,
       [("결정이 필요한 시점 — Phase 2 착수 전.  그때까지는 현재 버전이 현장에서 그대로 운영된다.",
         {"size": 11.5, "color": D.INK2, "italic": True})])


# ── 4. 개발 배경 ────────────────────────────────────────────
s = prs.slides[3]
set_text(title_of(s), "1. 개발 배경")
D.clear_body(body_of(s))
picture(s, "fig_aisle.jpg", 8.62, 1.12, 4.28, "3m 렉 사이 통로 — 교차 구간에서 서로 보이지 않는다", 1200 / 750)

D.pill(s, 0.7, 1.12, "문제의 배경")
D.kv(s, 0.95, 1.64, 7.5, [
    ("AS-IS", "3m 높이 철제 렉 사이 통로에서 지게차와 보행자가 서로를 볼 수 없음"),
    ("Pain Point", "적재 파렛트가 2.4GHz 전파를 흡수하고, 장비 소음으로 경적 인지도 늦음"),
])
D.pill(s, 0.7, 2.52, "현재 상황")
D.kv(s, 0.95, 3.04, 7.5, [
    ("Data", "지게차 · 리치 · EPJ · 보행자가 같은 통로를 공유. 렉 사이 단독 작업 구간도 있음"),
    ("Issue", "사각지대를 사람의 주의력에만 맡기고 있어, 보이지 않는 구간은 대책이 없음"),
])
D.pill(s, 0.7, 4.02, "왜 앱으로 만들었나", w=2.05)
for i, t in enumerate([
    "①  앵커 · 배선 · 서버 같은 고정 설비 없이 지급된 단말만으로 동작 — 센터 공사 불필요",
    "②  움직이는 주체끼리 직접 감지 — 설치 대수가 늘수록 감지 범위도 함께 넓어짐",
    "③  v1.0.1 부터 3개월간 70회 이상 배포하며 현장에서 계속 다듬음",
]):
    D.text(s, 0.95, 4.54 + i * 0.35, 11.9, 0.34, [(t, {})], size=12)
D.headline(s, 5.72, ["사각지대 해소", "인프라 없는 확산", "오경보 억제", "조용한 실패 제거"])
D.quote(s, 6.32, "현장 안전은 아직 ‘보고 나서 피하기’에 기대고 있고, 보이지 않는 구간을 메우는 장치가 없습니다.")

# ── 5. 기대효과 ─────────────────────────────────────────────
s = prs.slides[4]
set_text(title_of(s), "2. 기대효과")
D.clear_body(body_of(s))
D.pill(s, 0.7, 1.12, "As-is / To-be", w=1.75)
D.table(s, 0.72, 1.60, 12.1,
        ["구분", "As-is", "To-be"],
        [["감지", "사람의 주의력에만 의존", "소리 · 진동 · 화면 3중 경보로 보완"],
         ["도입", "안전 설비를 깔려면 센터 공사가 필요", "앱 설치만으로 즉시 적용"],
         ["확산", "장비를 늘려도 감지 범위는 그대로", "단말이 늘수록 감지쌍이 배로 (3대 3쌍 → 10대 45쌍)"],
         ["센터 차이", "센터마다 다른 조건에 대응 못 함", "판정 반경 · 신호 임계를 설정으로 조정"],
         ["후진 · 하역", "접근을 육안으로만 확인", "거리와 무관하게 즉시 최고 등급으로 경보"],
         ["경보 피로", "잦으면 작업자가 꺼버림", "세이프존 · 자동 음소거로 울릴 곳에서만"]],
        col_w=[1.55, 4.85, 5.70], row_h=0.55, size=11.5)
picture(s, "fig_pairs.png", 4.66, 5.32, 4.0, None, 728 / 278)

# ── 6. 비용 ────────────────────────────────────────────────
# 포지셔닝은 '우선 보완'이다. 대체 절감액을 주장하지 않는다 —
# I-PAS 가 덮지 않는 구간을 추가 하드웨어 없이 덮는 값으로 적는다.
s = prs.slides[5]
set_text(title_of(s), "3. 비용")
D.clear_body(body_of(s))
D.pill(s, 0.7, 1.12, "비교 기준", w=1.4)
D.text(s, 2.25, 1.15, 10.5, 0.3,
       [("지금 요청은 대체가 아니다. I-PAS 가 덮지 않는 구간을 추가 하드웨어 없이 덮는 값이다.",
         {"size": 12, "color": D.INK2})])
D.table(s, 0.72, 1.62, 12.1,
        ["항목", "I-PAS (현행)", "SafeAlert"],
        [["장비 · 비콘 · 거치대", "1세트 약 54만원 (추정)", "없음 — 지급된 단말에 앱 설치"],
         ["설치 · 배선", "장비별 장착 작업 필요", "없음"],
         ["대상 확대 시", "대수에 비례해 추가 (54만원 × n)", "0 — 앱 설치만"],
         ["덮는 대상", "장착한 장비", "앱을 넣은 모든 단말 (보행자 · EPJ 포함)"],
         ["유지보수", "하드웨어 고장 · 배터리 교체", "앱 업데이트 (자동 배포)"]],
        col_w=[2.60, 4.60, 4.90], row_h=0.52, size=11.5)
D.pill(s, 0.7, 4.80, "확산 규모 환산", w=1.9)
for i, (n, won) in enumerate([("10대", "540만원"), ("30대", "1,620만원"), ("50대", "2,700만원")]):
    x = 1.4 + i * 3.2
    D.text(s, x, 5.24, 3.0, 0.38, [(n + "   ", {"size": 13, "color": D.INK2}),
                                   (won, {"bold": True, "size": 19, "color": D.RED})])
D.text(s, 11.0, 5.26, 1.9, 0.5,
       [("같은 범위를\n하드웨어로 덮을 때", {"size": 11, "color": D.INK3})])
for i, t in enumerate([
    "·  54만원은 확인이 필요한 추정 단가다. 실제 절감액이 아니라, 하드웨어로 같은 범위를 덮을 때의 금액 규모다.",
    "·  앱이 추가 비용 0인 것은 단말이 이미 지급돼 있기 때문이다. 미지급 인원에게는 BLE 태그 지급이 필요하다.",
    "·  대체 여부는 이번 요청 범위가 아니다 — 로드맵 완료와 현장 데이터 확보 후 재논의한다.",
]):
    D.text(s, 0.95, 5.90 + i * 0.34, 11.9, 0.32, [(t, {"size": 11.5, "color": D.INK2})])


# ── 7. 장단점 ───────────────────────────────────────────────
s = prs.slides[6]
set_text(title_of(s), "4. 장단점")
D.clear_body(body_of(s))
D.pill(s, 0.7, 1.12, "Tradeoffs", w=1.35)
D.text(s, 2.25, 1.15, 10.4, 0.3,
       [("넷 다 의도한 선택이다. 무엇을 얻으려고 무엇을 내줬는지로 적는다.", {"size": 12, "color": D.INK2})])
TRADE = [
    ("고정 설비 vs. 앱만으로", "앱 설치만으로 동작 → 즉시 적용 우선",
     "앵커 · 배선 · 서버가 없어 센터 공사 없이 시작한다.\n대신 정밀 측위는 포기하고 신호 세기 추정을 받아들였다."),
    ("정밀도 vs. 어디서나 동작", "BLE 신호 세기 主 판정 → 현장 적용성 우선",
     "적재물 · 차체가 전파를 가리면 거리 오차가 난다. 센터별 보정값으로\n완화하되 완전 제거는 불가. UWB 는 지원 단말에서 보조로만 쓴다."),
    ("민감한 경보 vs. 경보 피로", "억제 장치를 넣음 → 끄지 않게 만드는 것 우선",
     "세이프존 · 5초 체류 자동 음소거를 둔다. 억제 구간에서 놓칠 위험은\n화면 표시와 기록을 그대로 유지해 상쇄한다."),
    ("전 기종 지원 vs. 백그라운드 상시 동작", "안드로이드 전용 → 화면이 꺼져도 도는 쪽 우선",
     "iOS 는 백그라운드에서 역할 · 상태를 실은 광고를 보내지 못한다.\n대응 — iOS 인원에게 BLE 태그를 지급한다. 등록 비콘 스캔 경로가 이미 있다."),
]
for i, (vs, pick, why) in enumerate(TRADE):
    y = 1.52 + i * 0.92
    D.text(s, 0.85, y, 4.80, 0.85,
           [[(vs, {"bold": True, "size": 12.5})],
            [(pick, {"size": 11.5, "color": D.RED, "bold": True})]], space=2)
    D.arrow(s, 5.76, y + 0.12, 0.78, 0.36)
    D.text(s, 6.72, y + 0.02, 6.15, 0.85, [(why, {"size": 11.5, "color": D.INK2})])
D.pill(s, 0.7, 5.30, "남는 한계", w=1.35)
for i, t in enumerate([
    "①  앱이 설치된 기기끼리만 감지 — 미설치 인원 · 장비는 보이지 않음",
    "②  기기가 밀집하면 화면 반응이 느려짐 (20대 이상)",
    "③  안드로이드 8.0 이상에서만 설치된다",
]):
    D.text(s, 0.95, 5.76 + i * 0.34, 7.9, 0.32, [(t, {})], size=11.5)
picture(s, "fig_radius.png", 9.60, 4.96, 2.70, None, 648 / 358)

# ── 8. 사용자 피드백 ────────────────────────────────────────
s = prs.slides[7]
set_text(title_of(s), "5. 사용자 피드백")
D.clear_body(body_of(s))
D.pill(s, 0.7, 1.12, "오더십 리뷰 반영", w=2.0)
D.table(s, 0.72, 1.60, 8.3,
        ["차수", "반영 내용", "결과"],
        [["1차", "역할 전환 버튼 추가 · EPJ 역할 버튼 숨김 · 스플래시 교체", "앱 재설치 없이 화면에서 역할 전환"],
         ["2차", "같은 기기 · 같은 등급에 5초 이상 머물면 소리 · 진동 자동 음소거", "표시 · 기록은 유지, 등급 오르면 즉시 재발령"],
         ["3차", "세이프존 — 휴게실 · 충전 구역에 비콘을 두면 경보 억제", "존 안의 기기는 상대에게도 안전으로 인식"]],
        col_w=[0.85, 4.35, 3.10], row_h=0.62, size=11)
picture(s, "fig_zone.png", 9.28, 1.52, 3.48, None, 668 / 288)

D.pill(s, 0.7, 4.02, "현장 요구 반영", w=1.85)
D.table(s, 0.72, 4.50, 12.1,
        ["항목", "Before", "After"],
        [["보호 상태", "끊겨도 작업자가 알 수 없음", "감지가 멈추면 상시 알림에 이상 표시"],
         ["이탈 후", "멀어졌는데 다시 울림", "경고 범위 재발령 억제"],
         ["알림 끄기", "단계가 번거로움", "알림 본문을 누르면 즉시 무음"],
         ["경보 화면", "눈에 잘 들어오지 않음", "화면 가장자리에 붙는 사이드바"]],
        col_w=[1.75, 4.65, 5.70], row_h=0.44, size=11.5)

# ── 9. 시연 ─────────────────────────────────────────────────
s = prs.slides[8]
set_text(title_of(s), "6. 시연")
D.clear_body(body_of(s))
D.flow(s, 0.7, 1.02, 12.2,
       ["신호 수신", "거리 추정", "등급 판정", "3중 경보"],
       ["BLE 광고", "3단 필터", "상태머신", "소리 · 진동 · 화면"])

ph_h = 3.80
ph_w = ph_h * 300 / 624
for k, (png, cap) in enumerate([("screen_running", "동작 화면"), ("screen_settings", "설정 화면")]):
    x = 1.15 + k * (ph_w + 0.5)
    D.text(s, x - 0.25, 2.12, ph_w + 0.5, 0.3, [(cap, {"bold": True, "size": 12})], align=PP_ALIGN.CENTER)
    s.shapes.add_picture(f"{REPO}/assets/{png}.png", Inches(x), Inches(2.48), Inches(ph_w), Inches(ph_h))

vw, vx, vy = 6.35, 6.42, 2.32
vh = vw * 868 / 1544
D.text(s, vx, vy - 0.38, vw, 0.32,
       [("시연 영상 — 슬라이드쇼에서 재생 (경보음 포함)", {"bold": True, "size": 12})], align=PP_ALIGN.CENTER)
s.shapes.add_movie(f"{REPO}/web/safealert-sim.mp4", Inches(vx), Inches(vy), Inches(vw), Inches(vh),
                   poster_frame_image=f"{REPO}/web/sim-poster.jpg", mime_type="video/mp4")
D.text(s, vx, vy + vh + 0.18, vw, 0.32,
       [("판정 시뮬레이터 — web/safealert-simulator.html 에서 직접 조작", {"size": 11, "color": D.INK2})],
       align=PP_ALIGN.CENTER)

# ── 10. 진행 단계 (Phase 1~5) ────────────────────────────────
s = prs.slides[9]
set_text(title_of(s), "7. 진행 단계")
D.clear_body(body_of(s))
D.pill(s, 0.7, 1.12, "Phase 1 ~ 5", w=1.6)
D.text(s, 2.45, 1.15, 10.2, 0.3,
       [("새 기능을 붙이는 작업이 아니다. 판정이 흔들리는 구조적 원인을 걷어낸다.", {"size": 12, "color": D.INK2})])
D.table(s, 0.72, 1.62, 12.1,
        ["단계", "무엇을 하는가", "출하 시 현장에서 확인할 것", "상태"],
        [["1", "테스트 하네스와 CI 회귀 게이트", "설치 · 경보 동작이 이전과 같은가", "완료"],
         ["2", "안전 크리티컬 경로 골든 테스트", "천천히 접근하는 지게차에 경고가 뜨는가", "예정"],
         ["3", "BleService 분해", "달라진 게 없는가 (동작 보존)", "예정"],
         ["4", "기기 상태 단일화", "2시간 이상 구동 후에도 느려지지 않는가", "예정"],
         ["5", "판정 워커 분리", "20대 이상에서 화면이 끊기지 않는가", "예정"]],
        col_w=[0.75, 4.15, 5.90, 1.30], row_h=0.52, size=11.5,
        aligns=[PP_ALIGN.CENTER, PP_ALIGN.LEFT, PP_ALIGN.LEFT, PP_ALIGN.CENTER])
D.pill(s, 0.7, 4.72, "이 순서인 이유", w=1.6)
for i, t in enumerate([
    "①  측정 수단이 먼저다 — 테스트 0건 상태에서 안전 로직을 분해하면, 회귀를 현장에서 발견하는 방식이 그대로 남는다",
    "②  단계마다 단독 배포한다 — 변경을 섞어 내보내면 현장에서 회귀가 나도 어느 변경 탓인지 특정할 수 없다",
]):
    D.text(s, 0.95, 5.22 + i * 0.42, 11.9, 0.40, [(t, {})], size=11.5)
D.headline(s, 6.22, ["완료 1 / 5", "전 단계 출하 가능", "현장 확인은 단계당 1건"])

# ── 11. Andon ───────────────────────────────────────────────
# 안돈은 개발 게이트가 아니라 '현장에서 라인을 세우는 기준'이다.
# 그래서 (가) 운영 중 실상황 (나) 업데이트·수정 중 상황 둘로 나눠 적는다.
s = prs.slides[10]
set_text(title_of(s), "8. Andon — 이럴 때 멈춘다")
D.clear_body(body_of(s))
D.pill(s, 0.7, 1.12, "현장 운영 중", w=1.65)
D.pill(s, 6.95, 1.12, "업데이트 · 수정 중", w=2.15)

FIELD = [
    ("미발령", "접근했는데 경보가 뜨지 않은 사례 1건이라도", True),
    ("오발령 지속", "정지 중 계속 울림 · 이탈 후 다시 울림이 반복", True),
    ("감지 중단", "상시 알림이 ‘이상’ 으로 바뀌거나 사라짐", False),
    ("경보 피로", "무음으로 두거나 앱을 꺼 둔 단말이 늘어남", False),
]
DEPLOY = [
    ("버전 혼재", "구버전 단말이 신버전을 감지하지 못함", True),
    ("보호 공백", "작업 시간 중 일괄 업데이트 금지 — 교대 전환 때만", False),
    ("서비스 미기동", "업데이트 · 역할 전환 후 백그라운드 실행 미복구", False),
    ("회귀 재발", "이전에 고친 증상이 다시 관측됨", True),
]
for i, ((k1, v1, u1), (k2, v2, u2)) in enumerate(zip(FIELD, DEPLOY)):
    y = 1.62 + i * 0.84
    for x, w, (k, v, urgent) in ((0.9, 5.5, (k1, v1, u1)), (7.15, 5.6, (k2, v2, u2))):
        D.text(s, x, y, w, 0.78,
               [[(k, {"bold": True, "size": 12.5, "color": D.RED if urgent else D.INK}),
                 ("   즉시 중단" if urgent else "   관측 시 판단", {"size": 10, "color": D.INK3})],
                [(v, {"size": 11.5, "color": D.INK2})]], space=2)

D.pill(s, 0.7, 4.98, "공통 조치", w=1.4)
ACTION = [
    ("즉시 롤백", "직전 태그 APK 로 되돌린다 — 현장 재설치 없이 배포된다"),
    ("선행 확인", "전 단말 배포 전 1대에서 먼저 확인한다"),
    ("빌드 차단", "골든 테스트가 실패하면 릴리스가 자동 차단된다 (동작 중)"),
    ("원인 귀속", "단계를 단독 배포하므로 어느 변경 탓인지 특정된다"),
    ("재개 조건", "실패한 상황을 테스트로 고정한 뒤에만 다음 단계로"),
]
for i, (k, v) in enumerate(ACTION):
    col, row = divmod(i, 3)
    D.text(s, 0.95 + col * 6.25, 5.44 + row * 0.35, 5.9, 0.33,
           [(k + "  —  ", {"bold": True, "size": 11.5}), (v, {"size": 11.5, "color": D.INK2})])
D.quote(s, 6.56, "멈출 기준을 먼저 정해 두는 것이, 멈추지 않고 밀어붙이는 것보다 빠릅니다.", x=0.7, w=8.4)


# ── 12. 요청 사항 ──────────────────────────────────────────
s = prs.slides[11]
set_text(title_of(s), "9. 요청 사항")
D.clear_body(body_of(s))
D.pill(s, 0.7, 1.12, "요청", w=1.1)
D.text(s, 2.0, 1.15, 10.8, 0.36,
       [("SafeAlert 를 정식 과제로 등록하고, 유지 · 검증에 필요한 리소스를 배정해 주십시오.", {"bold": True, "size": 14})])
D.table(s, 0.72, 1.72, 12.1,
        ["무엇을", "왜 필요한가", "없으면"],
        [["정식 과제 등록", "지금은 개인 작업물이다. 유지보수 주체가 지정돼 있지 않다", "만든 사람이 빠지면 멈춘다"],
         ["개발 시간 배정", "Phase 2~5 는 기능 추가가 아니라 판정 재현성 확보다", "고친 증상이 다시 돌아온다"],
         ["현장 검증 슬롯", "실기 검증이 유일한 회귀 확인 수단 — 단계당 확인 항목 1건", "검증 없이 배포하거나 배포가 멈춘다"],
         ["보안 검토", "릴리스 빌드 난독화 미적용 · 경보 이력 평문 저장", "확산할수록 리스크가 커진다"]],
        col_w=[2.30, 5.70, 4.10], row_h=0.60, size=11.5)
D.pill(s, 0.7, 4.92, "지금 상태", w=1.4)
for i, t in enumerate([
    "①  v1.1.70 이 현장에서 운영 중이다 — 결정이 늦어도 보호가 멈추지는 않는다",
    "②  다만 신뢰성 로드맵은 1 / 5 에서 멈춰 있고, 남은 네 단계가 재발 고리를 끊는 작업이다",
]):
    D.text(s, 0.95, 5.40 + i * 0.38, 11.9, 0.36, [(t, {"size": 12})])
D.quote(s, 6.34, "지금 필요한 것은 새 기능이 아니라, 이미 도는 것을 믿을 수 있게 만드는 시간입니다.", x=0.7, w=8.4)


prs.save(OUT)
print("wrote", OUT)
