# LFP 사내 폼(표지·목차·본문 레이아웃)을 그대로 쓰고 내용만 SafeAlert 로 갈아 끼운다.
# 문단은 템플릿 문단의 <a:pPr>/<a:rPr> 을 복제해 만들어 서식이 사내 폼과 어긋나지 않게 한다.
import copy, re
from pptx import Presentation
from pptx.util import Inches, Emu

SRC, OUT = "base.pptx", "SafeAlert_보고_2026.08.pptx"
REPO = "/home/user/SafeAlert/docs/presentation"

prs = Presentation(SRC)
NS = "{http://schemas.openxmlformats.org/drawingml/2006/main}"

def body_of(slide):
    """본문 텍스트박스 — 자리표시자(제목·쪽번호)가 아닌 텍스트박스."""
    for sh in slide.shapes:
        if sh.has_text_frame and not sh.is_placeholder:
            return sh
    raise SystemExit("본문 텍스트박스를 찾지 못했다")

def title_of(slide):
    for sh in slide.shapes:
        if sh.is_placeholder and sh.has_text_frame and sh.placeholder_format.idx != 12:
            if "번호" not in sh.name:
                return sh
    raise SystemExit("제목 자리표시자를 찾지 못했다")

def set_text(shape, text):
    """단일 런 도형의 글자만 바꾼다 (서식 유지)."""
    tf = shape.text_frame
    p = tf.paragraphs[0]
    for r in p.runs[1:]:
        r._r.getparent().remove(r._r)
    if p.runs:
        p.runs[0].text = text
    else:
        p.add_run().text = text

def write_body(slide, lines):
    """lines: [(레벨, 글자)] — 레벨 0=소제목 '1)', 1='① 항목', 2='→ 부연'."""
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
        if not runs:      # 빈 줄용 문단은 런이 없다 — 하나 만들어 붙인다
            p.append(copy.deepcopy(proto[lvl].findall(NS + "r")[0]))
            runs = p.findall(NS + "r")
        t = runs[0].find(NS + "t")
        t.text = text
        t.set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
        txBody.append(p)

# ── 1. 표지 ────────────────────────────────────────────────
s = prs.slides[0]
for sh in s.shapes:
    if sh.has_text_frame and "LFP" in sh.text_frame.text:
        set_text(sh, "SafeAlert  근접 경보 앱")
    elif sh.has_text_frame and "2026.08" in sh.text_frame.text:
        p = sh.text_frame.paragraphs[0]
        for r in p.runs[1:]:
            r._r.getparent().remove(r._r)
        p.runs[0].text = "2026.08 |  Flex Fulfillment Dynamic Ops  Waterflex"

# ── 2. Content ────────────────────────────────────────────
write_body(prs.slides[1], [
    (0, "1. 개발 배경"),
    (1, "  1) 현장 문제"),
    (1, "  2) 왜 앱으로 만들었나"),
    (0, " "),
    (0, "2. 기대효과"),
    (0, " "),
    (0, "3. 장단점"),
    (1, "  1) 장점"),
    (1, "  2) 단점"),
    (0, " "),
    (0, "4. 사용자 피드백"),
    (1, "  1) 오더십 리뷰 반영"),
    (1, "  2) 현장에서 나온 요구"),
    (0, " "),
    (0, "5. 시연"),
    (1, "  1) 동작 화면 · 설정 화면"),
    (1, "  2) 첨부 — 시연 영상 · 판정 시뮬레이터"),
])

# ── 3~7. 본문 ─────────────────────────────────────────────
CONTENT = [
    ("1. 개발 배경", [
        (0, "1) 현장 문제"),
        (1, "  ① 3m 높이 철제 렉 사이 통로에서 지게차와 보행자가 서로를 볼 수 없음"),
        (2, "    → 교차 · 곡선 구간은 육안 확인 자체가 어려움"),
        (1, "  ② 적재 파렛트가 2.4GHz 전파를 흡수하고, 장비 소음으로 경적 인지도 늦음"),
        (1, "  ③ 렉 사이 단독 작업 구간이 있어 이탈 여부 확인이 필요"),
        (1, "  ④ 지게차 · 리치 · EPJ · 보행자가 같은 통로를 공유"),
        (0, " "),
        (0, "2) 왜 앱으로 만들었나"),
        (1, "  ① 앵커 · 배선 · 서버 같은 고정 설비 없이 지급된 단말만으로 동작"),
        (2, "    → 센터 공사나 별도 인프라 투자가 필요 없음"),
        (1, "  ② 움직이는 주체끼리 직접 감지 — 설치 대수가 늘수록 감지 범위도 함께 넓어짐"),
        (1, "  ③ v1.0.1 부터 3개월간 70회 이상 배포하며 현장에서 계속 다듬음"),
    ]),
    ("2. 기대효과", [
        (0, "1) 만들면서 기대한 것"),
        (1, "  ① 사각지대 접촉 위험 감소"),
        (2, "    → 육안으로 확인되지 않는 구간을 소리 · 진동 · 화면으로 보완"),
        (1, "  ② 인프라 투자 없이 즉시 적용"),
        (2, "    → 앱 설치만으로 시작, 센터 공사 불필요"),
        (1, "  ③ 확산할수록 커지는 효과"),
        (2, "    → 단말이 늘면 상호 감지쌍이 배로 늘어남 (10대 45쌍 · 20대 190쌍)"),
        (1, "  ④ 센터별 조건 대응"),
        (2, "    → 렉 높이 · 적재물이 다른 센터마다 판정 반경과 신호 임계를 설정으로 조정"),
        (1, "  ⑤ 안전 규정 준수 보조"),
        (2, "    → 후진 · 하역 중인 장비가 접근하면 즉시 최고 등급으로 경보"),
        (1, "  ⑥ 경보 피로 방지"),
        (2, "    → 울릴 필요가 없는 구역과 상황에서는 울리지 않도록 설계"),
    ]),
    ("3. 장단점", [
        (0, "1) 장점"),
        (1, "  ① 고정 설비가 필요 없음 — 앱 설치만으로 동작"),
        (1, "  ② 역할 조합별 차등 반경 — 지게차 포함 경고 15m · 위험 8m, 그 외 경고 5m · 위험 3m"),
        (2, "    → FC 대비 감지 범위를 두 배로 책정"),
        (1, "  ③ 3중 경보 — 소리 · 진동 · 화면 사이드바를 동시에 출력"),
        (1, "  ④ 화면을 꺼도 상시 동작하고, 감지가 멈추면 그 사실 자체를 알림으로 표시"),
        (1, "  ⑤ 구버전 기기와 통신 유지 — 신버전 배포가 기존 기기를 무력화하지 않음"),
        (0, " "),
        (0, "2) 단점"),
        (1, "  ① 신호 세기로 거리를 추정해 환경에 흔들림 — 적재물 · 차체가 전파를 가리면 오차 발생"),
        (2, "    → 센터별 보정값으로 완화하되 완전한 제거는 불가"),
        (1, "  ② 기기가 밀집하면 화면 반응이 느려짐 (20대 이상)"),
        (1, "  ③ 앱이 설치된 기기끼리만 감지 — 미설치 인원 · 장비는 보이지 않음"),
        (1, "  ④ 단말마다 송수신 감도가 달라 한쪽만 먼저 감지하는 경우가 있음"),
        (2, "    → 상대가 보낸 위험도를 받아 내 등급을 올리는 방식으로 보완"),
    ]),
    ("4. 사용자 피드백", [
        (0, "1) 오더십 리뷰 반영 (3차에 걸쳐 반영 완료)"),
        (1, "  ① 1차 — 역할 전환 버튼 추가 · EPJ 역할 버튼 숨김 · 스플래시 화면 교체"),
        (2, "    → 지게차 ↔ 보행자를 앱 재설치 없이 화면에서 전환"),
        (1, "  ② 2차 — 같은 기기 · 같은 등급에 5초 이상 머물면 소리 · 진동 자동 음소거"),
        (2, "    → 화면 표시와 기록은 유지하고, 등급이 오르면 즉시 다시 울림"),
        (1, "  ③ 3차 — 세이프존 도입. 휴게실 · 충전 구역에 비콘을 두면 그 구역에서 경보를 억제"),
        (2, "    → 존 안의 기기는 상대 단말에게도 안전으로 인식됨"),
        (0, " "),
        (0, "2) 현장에서 나온 요구"),
        (1, "  ① 보호가 끊긴 것을 알 수 없다 → 감지가 멈추면 상시 알림 제목을 이상 표시로 승격"),
        (1, "  ② 멀어졌는데 다시 울린다 → 이탈 후 경고 범위에서 재발령하지 않도록 수정"),
        (1, "  ③ 알림 끄기가 번거롭다 → 알림 본문을 누르면 즉시 무음, 버튼은 앱 열기로 역할 교체"),
        (1, "  ④ 경보 화면이 눈에 안 들어온다 → 화면 가장자리에 붙는 사이드바로 재설계"),
    ]),
    ("5. 시연", [
        (0, "1) 동작 화면 · 설정 화면"),
        (1, "  ① 역할만 고르면 나머지는 백그라운드에서 동작"),
        (1, "  ② 센터별 조건은 설정 화면에서 조정"),
        (0, "2) 첨부"),
        (1, "  ① 시연 영상 — 재생하면 판정 네 가지가 차례로 재현"),
        (1, "  ② 판정 시뮬레이터 — 브라우저에서 직접 조작"),
    ]),
]

for i, (title, lines) in enumerate(CONTENT):
    s = prs.slides[i + 2]
    set_text(title_of(s), title)
    write_body(s, lines)
    body_of(s).height = Inches(6.0)

# ── 5. 시연 슬라이드 — 화면 이미지 + 시연 영상 ──────────────
s = prs.slides[6]
body_of(s).width = Inches(5.2)
lbl_y, img_y, img_h = Inches(3.55), Inches(3.9), Inches(2.75)
img_w = img_h * 300 / 624          # 목업 원본 비율
for k, (png, cap) in enumerate([("screen_running", "동작 화면"), ("screen_settings", "설정 화면")]):
    x = Inches(0.95) + k * (img_w + Inches(0.45))
    s.shapes.add_picture(f"{REPO}/assets/{png}.png", x, img_y, height=img_h)
    tb = s.shapes.add_textbox(x - Inches(0.2), lbl_y, img_w + Inches(0.4), Inches(0.3))
    r = tb.text_frame.paragraphs[0].add_run(); r.text = cap
    r.font.size = Emu(127000); r.font.bold = True
    tb.text_frame.paragraphs[0].alignment = 2 - 1   # CENTER

vid_x, vid_y, vid_w = Inches(6.3), Inches(2.05), Inches(6.3)
vid_h = vid_w * 868 / 1544
s.shapes.add_movie(f"{REPO}/web/safealert-sim.mp4", vid_x, vid_y, vid_w, vid_h,
                   poster_frame_image=f"{REPO}/web/sim-poster.jpg", mime_type="video/mp4")
tb = s.shapes.add_textbox(vid_x, vid_y - Inches(0.42), vid_w, Inches(0.34))
r = tb.text_frame.paragraphs[0].add_run()
r.text = "시연 영상 — 슬라이드쇼에서 재생"
r.font.size = Emu(139700); r.font.bold = True

prs.save(OUT)
print("wrote", OUT)
