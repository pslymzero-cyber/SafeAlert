# LFP 사내 폼(표지·목차·본문 레이아웃)을 그대로 쓰고 내용만 SafeAlert 로 갈아 끼운다.
# 본문 장은 CouSolve Idea Contest 자료의 표현 장치(_devices.py)로 바꿔 그렸다 —
# 회색 라벨 · As-is/To-be 표 · Tradeoffs 화살표 · Before/After 표 · 하단 이탤릭 결론.
# 폼 자체(흰 배경 · 상단 색 바 · 로고 · CFS 쪽번호)는 사내 표준 그대로 둔다.
import copy, os
from pptx import Presentation
from pptx.util import Inches, Emu, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN

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
    (0, "1. 개발 배경"),
    (1, "  1) 문제의 배경 · 현재 상황"),
    (1, "  2) 왜 앱으로 만들었나"),
    (0, " "),
    (0, "2. 기대효과"),
    (1, "  1) As-is / To-be"),
    (0, " "),
    (0, "3. 장단점"),
    (1, "  1) Tradeoffs — 무엇을 얻고 무엇을 내줬나"),
    (1, "  2) 남는 한계"),
    (0, " "),
    (0, "4. 사용자 피드백"),
    (1, "  1) 오더십 리뷰 반영"),
    (1, "  2) 현장 요구 반영 (Before / After)"),
    (0, " "),
    (0, "5. 시연"),
    (1, "  1) 동작 화면 · 설정 화면"),
    (1, "  2) 시연 영상 · 판정 시뮬레이터"),
    (0, " "),
    (0, "6. 진행 단계 (Phase 1 ~ 5)"),
    (0, " "),
    (0, "7. Andon — 중단 · 롤백 기준"),
])

# ── 3. 개발 배경 ────────────────────────────────────────────
s = prs.slides[2]
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

# ── 4. 기대효과 ─────────────────────────────────────────────
s = prs.slides[3]
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

# ── 5. 장단점 ───────────────────────────────────────────────
s = prs.slides[4]
set_text(title_of(s), "3. 장단점")
D.clear_body(body_of(s))
D.pill(s, 0.7, 1.12, "Tradeoffs", w=1.35)
TRADE = [
    ("고정 설비 vs. 앱만으로", "앱 설치만으로 동작 → 즉시 적용 우선",
     "앵커 · 배선 · 서버가 없어 센터 공사 없이 시작한다.\n대신 정밀 측위는 포기하고 신호 세기 추정을 받아들였다."),
    ("정밀도 vs. 어디서나 동작", "BLE 신호 세기 主 판정 → 현장 적용성 우선",
     "적재물 · 차체가 전파를 가리면 거리 오차가 난다. 센터별 보정값으로\n완화하되 완전 제거는 불가. UWB 는 지원 단말에서 보조로만 쓴다."),
    ("민감한 경보 vs. 경보 피로", "억제 장치를 넣음 → 끄지 않게 만드는 것 우선",
     "세이프존 · 5초 체류 자동 음소거를 둔다. 억제 구간에서 놓칠 위험은\n화면 표시와 기록을 그대로 유지해 상쇄한다."),
]
for i, (vs, pick, why) in enumerate(TRADE):
    y = 1.62 + i * 1.10
    D.text(s, 0.85, y, 4.55, 0.9,
           [[(vs, {"bold": True, "size": 12.5})],
            [(pick, {"size": 11.5, "color": D.RED, "bold": True})]], space=3)
    D.arrow(s, 5.62, y + 0.14, 0.85, 0.38)
    D.text(s, 6.72, y + 0.02, 6.15, 0.9, [(why, {"size": 11.5, "color": D.INK2})])
D.pill(s, 0.7, 4.98, "남는 한계", w=1.35)
for i, t in enumerate([
    "①  앱이 설치된 기기끼리만 감지 — 미설치 인원 · 장비는 보이지 않음",
    "②  기기가 밀집하면 화면 반응이 느려짐 (20대 이상)",
]):
    D.text(s, 0.95, 5.48 + i * 0.36, 7.9, 0.34, [(t, {})], size=12)
picture(s, "fig_radius.png", 9.35, 4.78, 3.1, None, 648 / 358)

# ── 6. 사용자 피드백 ────────────────────────────────────────
s = prs.slides[5]
set_text(title_of(s), "4. 사용자 피드백")
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

# ── 7. 시연 ─────────────────────────────────────────────────
s = prs.slides[6]
set_text(title_of(s), "5. 시연")
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

# ── 8. 진행 단계 (Phase 1~5) ────────────────────────────────
s = prs.slides[7]
set_text(title_of(s), "6. 진행 단계")
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

# ── 9. Andon ───────────────────────────────────────────────
s = prs.slides[8]
set_text(title_of(s), "7. Andon — 중단 · 롤백 기준")
D.clear_body(body_of(s))
D.pill(s, 0.7, 1.12, "Andon 기준", w=1.5)
D.pill(s, 6.95, 1.12, "조치 방법", w=1.5)
ANDON = [
    ("동작 보존 실패",
     "분해 전후로 골든 테스트 기대값이 한 건이라도 바뀌면" + "\n" + "그 단계는 통과가 아니다 (Phase 3 수용 게이트)"),
    ("현장 회귀 재발",
     "이전에 고친 증상(미발령 · 상시 경보 · 사이렌 반복)이" + "\n" + "다시 관측되면 그 단계를 중단한다"),
    ("CI 게이트 적색",
     "골든 테스트가 실패하면 APK 릴리스가 자동 차단된다" + "\n" + "— 이미 동작 중인 장치다"),
    ("UWB 의존 파손",
     "프리릴리스 UWB API 의 파괴 변경으로 경로가 깨지면" + "\n" + "UWB 를 끄고 BLE 단독으로 내린다"),
]
ACTION = [
    ("즉시 롤백", "직전 태그 APK 로 되돌린다. 자동 업데이트 경로가 있어" + "\n" + "현장 재설치 없이 배포된다"),
    ("원인 귀속", "단계를 단독 배포하기 때문에 회귀가 나면 어느 변경" + "\n" + "탓인지 특정된다"),
    ("재개 조건", "실패한 상황을 테스트로 고정한 뒤에만 다음 단계로" + "\n" + "넘어간다 — 같은 증상이 두 번 돌아오지 않게"),
    ("현장 부담", "확인 항목은 단계당 1건으로 제한한다. 검증 사이클이" + "\n" + "현장 가용 시간에 묶여 있다"),
]
for i, ((k1, v1), (k2, v2)) in enumerate(zip(ANDON, ACTION)):
    y = 1.66 + i * 1.24
    D.text(s, 0.9, y, 5.6, 1.1,
           [[(k1, {"bold": True, "size": 13, "color": D.RED})],
            [(v1, {"size": 11.5, "color": D.INK2})]], space=3)
    D.text(s, 7.15, y, 5.6, 1.1,
           [[(k2, {"bold": True, "size": 13})],
            [(v2, {"size": 11.5, "color": D.INK2})]], space=3)
D.quote(s, 6.62, "멈출 기준을 먼저 정해 두는 것이, 멈추지 않고 밀어붙이는 것보다 빠릅니다.", x=0.7, w=8.4)


prs.save(OUT)
print("wrote", OUT)
