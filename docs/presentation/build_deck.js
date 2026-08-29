// SafeAlert 경영진 보고 덱
// 디자인 토큰: 앱 고정 다크 팔레트(app/src/main/res/values/colors.xml, v1.1.65 "다크 UI 통일")를 그대로 쓴다.
// 이미지·아이콘: app/src/main/res 실제 자산을 assets.js 로 추출한 것.
const P = require("pptxgenjs");
const path = require("path");
const A = (n) => path.join(__dirname, "assets", n + ".png");

// ── 앱 팔레트 (colors.xml 원본값) ──────────────────────────────────
const C = {
  bg:      "0B1220",  // sa_bg
  surface: "1A2233",  // sa_surface
  alt:     "161D2B",  // sa_surface_alt
  veil:    "141B2B",  // sa_surface_veil (불투명 근사)
  stroke:  "56627A",  // sa_stroke
  hair:    "232D42",  // 헤어라인(divider 근사)
  t1:      "F1F5F9",  // sa_text_primary
  t2:      "C3CDDC",  // sa_text_secondary
  t3:      "8B98AC",  // sa_text_tertiary
  accent:  "7DD3FC",  // sa_accent
  dim:     "60A5FA",  // sa_accent_dim
  safe:    "4ADE80",  // sa_safe
  warn:    "FCD34D",  // sa_warning
  danger:  "FB7185",  // sa_danger
  onAcc:   "0B1220",  // sa_on_accent
};
const HEAD = "Malgun Gothic", BODY = "Malgun Gothic";
const W = 13.333, H = 7.5, M = 0.62;

const pres = new P();
pres.layout = "LAYOUT_WIDE";
pres.author = "SafeAlert";
pres.title = "SafeAlert 경영진 보고";

let page = 0;
const sh = (o) => Object.assign({ type: "outer", angle: 90, blur: 14, offset: 0.06, opacity: 0.35, color: "000000" }, o || {});
const txt = (s, t, o) => s.addText(t, Object.assign({ fontFace: BODY, isTextBox: true, margin: 0 }, o));

function slide() {
  const s = pres.addSlide();
  s.background = { color: C.bg };
  return s;
}
function card(s, x, y, w, h, o) {
  o = o || {};
  const opt = { x, y, w, h, fill: { color: o.fill || C.surface }, rectRadius: o.radius === undefined ? 0.1 : o.radius, shadow: sh(o.shadow) };
  if (o.line) opt.line = { color: o.line, width: o.lineW || 1 };
  s.addShape(pres.ShapeType.roundRect, opt);
}
// 앱의 섹션 헤더 모티프: 작은 정사각 불릿 + 라벨 (shape_bullet_square)
function sectionLabel(s, x, y, text, color, size) {
  s.addShape(pres.ShapeType.rect, { x, y: y + 0.07, w: 0.11, h: 0.11, fill: { color } });
  txt(s, text, { x: x + 0.22, y, w: 5.2, h: 0.26, fontSize: size || 11.5, bold: true, color, valign: "middle" });
}
function chip(s, x, y, w, h, text, fill, color, size, outline) {
  const o = { x, y, w, h, fill: { color: fill }, rectRadius: 0.5 };
  if (outline) o.line = { color: outline, width: 1 };
  s.addShape(pres.ShapeType.roundRect, o);
  txt(s, text, { x, y, w, h, fontSize: size || 11, bold: true, color, align: "center", valign: "middle" });
}
function iconTile(s, x, y, d, img, fill) {
  s.addShape(pres.ShapeType.roundRect, { x, y, w: d, h: d, fill: { color: fill || C.alt }, rectRadius: 0.24 });
  s.addImage({ path: A(img), x: x + d * 0.2, y: y + d * 0.2, w: d * 0.6, h: d * 0.6 });
}
function title(s, t, sub) {
  txt(s, t, { x: M, y: 0.46, w: W - M * 2, h: 0.6, fontSize: 29, bold: true, color: C.t1, fontFace: HEAD, valign: "middle" });
  if (sub) txt(s, sub, { x: M, y: 1.08, w: W - M * 2, h: 0.34, fontSize: 13, color: C.t3, valign: "middle" });
}
function footer(s) {
  page += 1;
  txt(s, "SafeAlert  ·  경영진 보고  ·  v1.1.70", { x: M, y: H - 0.46, w: 6, h: 0.26, fontSize: 9, color: "5A6880", valign: "middle" });
  txt(s, String(page), { x: W - M - 1, y: H - 0.46, w: 1, h: 0.26, fontSize: 9, color: "5A6880", align: "right", valign: "middle" });
}

/* ═══ 1. 표지 ═══════════════════════════════════════════════════ */
{
  const s = slide();
  s.addImage({ path: A("hero_splash"), x: 6.55, y: 0, w: 6.783, h: 7.5 });
  txt(s, "물류 현장 근접 경보 시스템", { x: M, y: 2.28, w: 6.0, h: 0.34, fontSize: 15, bold: true, color: C.accent });
  txt(s, "SafeAlert", { x: M, y: 2.66, w: 6.0, h: 1.2, fontSize: 68, bold: true, color: C.t1, fontFace: HEAD });
  txt(s, "지게차 · EPJ · 보행자가 서로를 감지한다.", { x: M, y: 3.94, w: 5.9, h: 0.36, fontSize: 16, color: C.t2 });
  txt(s, "앱이 설치된 스마트폰끼리 직접 신호를 주고받아, 위험 거리에 들어오면 즉시 알린다.",
    { x: M, y: 4.3, w: 5.6, h: 0.6, fontSize: 13.5, color: C.t3, lineSpacing: 21 });
  s.addShape(pres.ShapeType.line, { x: M, y: 5.14, w: 2.8, h: 0, line: { color: C.hair, width: 1 } });
  txt(s, "v1.1.70  ·  3개월간 70회 이상 릴리스  ·  현장 운영 중", { x: M, y: 5.3, w: 5.9, h: 0.3, fontSize: 12, color: C.t3 });
  txt(s, "2026. 08.", { x: M, y: 5.64, w: 5.9, h: 0.3, fontSize: 12, color: "5A6880" });
  s.addNotes("보고 목적: 현재 현장에서 운영 중인 SafeAlert 의 기능과 목적, 확산 시 얻는 이점을 공유하고 파일럿 확대 여부에 대한 결정을 요청한다. 표지 이미지는 앱 스플래시 화면 원본이다.");
}

/* ═══ 2. 한 장 요약 ══════════════════════════════════════════════ */
{
  const s = slide();
  title(s, "한 장 요약", "무엇을 하는가 · 왜 하는가 · 확산하면 무엇이 달라지는가");
  const stats = [["70+", "릴리스", "3개월간 현장 운영", C.accent], ["0", "추가 인프라", "앵커·게이트웨이·배선 불필요", C.safe],
                 ["3단계", "경보 등급", "안전 · 경고 · 위험", C.warn], ["1 / 5", "구조 개선 진행", "신뢰성 로드맵 1단계 완료", C.danger]];
  const cw = 2.94, gp = 0.19;
  stats.forEach(([big, lbl, sub, col], i) => {
    const x = M + i * (cw + gp);
    card(s, x, 1.64, cw, 1.6);
    txt(s, big, { x: x + 0.26, y: 1.8, w: cw - 0.5, h: 0.64, fontSize: 34, bold: true, color: col, fontFace: HEAD });
    txt(s, lbl, { x: x + 0.26, y: 2.44, w: cw - 0.5, h: 0.28, fontSize: 12.5, bold: true, color: C.t1 });
    txt(s, sub, { x: x + 0.26, y: 2.73, w: cw - 0.5, h: 0.36, fontSize: 10.5, color: C.t3 });
  });
  const rows = [
    ["무엇을 하는가", "앱이 설치된 기기끼리 블루투스 신호를 직접 주고받아, 신호 세기로 추정한 거리가 위험 구간에 들어오면 소리·진동·전체화면으로 알린다.", C.accent],
    ["왜 하는가", "3m 철제 렉 사이 통로와 단독 작업 구간에서 지게차·EPJ·보행자가 서로를 육안으로 확인하기 어렵다. 사각지대를 사람의 주의력이 아니라 시스템이 메운다.", C.accent],
    ["지금 하는 일", "신규 기능 추가가 아니라 '같은 상황에서 같은 판정'을 보장하는 구조 개선이다. 5단계 로드맵 중 1단계(테스트·CI 회귀 게이트) 완료.", C.warn],
    ["확산하면", "단말이 늘수록 감지 범위가 인프라 비용 증가 없이 넓어진다. 설치 대수 n대 → 상호 감지쌍 n(n-1)/2.", C.safe],
  ];
  let y = 3.44;
  rows.forEach(([k, v, col]) => {
    card(s, M, y, W - M * 2, 0.8, { fill: C.alt, shadow: { opacity: 0.22 } });
    sectionLabel(s, M + 0.3, y + 0.27, k, col, 12);
    txt(s, v, { x: M + 2.5, y: y + 0.06, w: W - M * 2 - 2.8, h: 0.68, fontSize: 12.5, color: C.t2, valign: "middle", lineSpacing: 17 });
    y += 0.88;
  });
  footer(s);
  s.addNotes("네 개의 숫자로 현황을 고정한다. 70회 릴리스는 실험이 아니라 이미 현장에서 도는 물건이라는 뜻이고, 1/5 는 지금 하는 일이 기능 추가가 아니라 신뢰성 확보라는 뜻이다.");
}

/* ═══ 3. 문제 정의 ═══════════════════════════════════════════════ */
{
  const s = slide();
  title(s, "현장의 사각지대", "왜 사람의 주의력만으로는 부족한가");
  const items = [
    ["막힌 시야", "3m 높이 철제 렉 사이 통로. 교차·곡선 구간에서 지게차와 보행자가 서로를 볼 수 없다.", C.danger],
    ["전파·소음 환경", "적재된 생수 파렛트가 2.4GHz 흡수벽으로 작용한다. 장비 소음 속에서 경적 인지도 늦다.", C.warn],
    ["단독 작업 구간", "렉 사이에서 혼자 작업하는 구간이 존재한다. 이탈 감지가 안전상 필수다.", C.danger],
    ["혼재 동선", "지게차·리치·오더피커·EPJ·보행자가 같은 통로를 공유한다. 역할마다 위험 거리가 다르다.", C.accent],
  ];
  let y = 1.66;
  items.forEach(([k, v, col]) => {
    card(s, M, y, 7.42, 1.06, { fill: C.surface, shadow: { opacity: 0.22 } });
    sectionLabel(s, M + 0.3, y + 0.2, k, col, 13.5);
    txt(s, v, { x: M + 0.52, y: y + 0.52, w: 6.66, h: 0.42, fontSize: 11.5, color: C.t2, lineSpacing: 16 });
    y += 1.14;
  });
  const bx = 8.28, bw = W - M - bx;
  s.addImage({ path: A("aisle"), x: bx, y: 1.66, w: bw, h: 4.24 });
  chip(s, bx + 0.34, 4.98, 1.9, 0.44, "시야 차단 구간", C.danger, C.onAcc, 11);
  txt(s, "실제 적용 현장 — 렉 사이 통로", { x: bx, y: 5.98, w: bw, h: 0.3, fontSize: 10.5, color: C.t3, align: "center" });
  footer(s);
  s.addNotes("네 가지는 추정이 아니라 현장 조건이다. 특히 생수 파렛트의 전파 흡수는 신호 기반 거리 추정의 난이도를 직접 끌어올리는 요인이고, 뒤에 나올 3단 신호 정제와 사업장별 보정이 여기서 나왔다.");
}

/* ═══ 4. 해결 방식 ═══════════════════════════════════════════════ */
{
  const s = slide();
  title(s, "해결 방식 — 기기끼리 서로를 본다", "고정 설비를 까는 대신, 움직이는 주체가 서로를 감지한다");
  const cw = 5.98;
  card(s, M, 1.7, cw, 3.34, { fill: C.alt });
  txt(s, "일반적인 구역 감지 방식", { x: M + 0.32, y: 1.92, w: cw - 0.64, h: 0.32, fontSize: 15, bold: true, color: C.t3 });
  [["구역마다 앵커·게이트웨이 설치와 배선 공사가 필요하다"], ["서버·네트워크가 끊기면 감지도 함께 멈춘다"],
   ["'구역 진입' 단위 판정 — 누가 누구에게 가까운지는 모른다"], ["라인을 바꾸거나 센터를 늘리면 다시 공사한다"]].forEach(([t], i) => {
    s.addShape(pres.ShapeType.rect, { x: M + 0.34, y: 2.52 + i * 0.56 + 0.11, w: 0.1, h: 0.1, fill: { color: "5A6880" } });
    txt(s, t, { x: M + 0.58, y: 2.52 + i * 0.56, w: cw - 0.92, h: 0.34, fontSize: 12.5, color: C.t3, valign: "middle" });
  });
  const x2 = M + cw + 0.13;
  card(s, x2, 1.7, cw, 3.34, { fill: C.surface, line: C.accent, lineW: 1.25 });
  txt(s, "SafeAlert 방식", { x: x2 + 0.32, y: 1.92, w: cw - 0.64, h: 0.32, fontSize: 15, bold: true, color: C.accent });
  [["설치는 앱 하나. 고정 설비·배선·서버 증설이 없다"], ["기기 간 직접 통신 — 네트워크가 없어도 경보는 동작한다"],
   ["개체 대 개체 상대 거리 판정 — 역할 조합별로 기준이 다르다"], ["확장 = 설치. 현장이 바뀌어도 재공사가 없다"]].forEach(([t], i) => {
    s.addShape(pres.ShapeType.rect, { x: x2 + 0.34, y: 2.52 + i * 0.56 + 0.11, w: 0.1, h: 0.1, fill: { color: C.accent } });
    txt(s, t, { x: x2 + 0.58, y: 2.52 + i * 0.56, w: cw - 0.92, h: 0.34, fontSize: 12.5, color: C.t1, valign: "middle" });
  });
  const chips = [["ic_antenna", "인프라 투자 0", C.safe], ["ic_share_out", "네트워크 없이도 경보 동작", C.accent], ["ic_receive_in", "현장 구버전 기기와 100% 호환", C.warn]];
  let cx = M;
  chips.forEach(([ic, t, col]) => {
    const w = 3.98;
    card(s, cx, 5.28, w, 0.74, { fill: C.alt, shadow: { opacity: 0.2 } });
    s.addImage({ path: A(ic), x: cx + 0.28, y: 5.45, w: 0.4, h: 0.4 });
    txt(s, t, { x: cx + 0.82, y: 5.28, w: w - 1.1, h: 0.74, fontSize: 12.5, bold: true, color: col, valign: "middle" });
    cx += w + 0.11;
  });
  footer(s);
  s.addNotes("핵심 차별점은 고정 설비가 없다는 것이다. 클라우드는 설정 공유와 앱 자동 업데이트에만 쓰이고, 경보 판정 자체는 단말 안에서 끝난다. 통신이 끊긴 창고에서도 경보는 그대로 동작한다.");
}

/* ═══ 5. 제품 화면 ═══════════════════════════════════════════════ */
{
  const s = slide();
  title(s, "제품 화면", "작업자가 실제로 보는 것 — 역할을 고르면 나머지는 백그라운드에서 동작한다");
  // 폰 프레임
  const px = M + 0.5, py = 1.66, pw = 2.62, ph = 4.9;
  s.addShape(pres.ShapeType.roundRect, { x: px - 0.07, y: py - 0.07, w: pw + 0.14, h: ph + 0.14, fill: { color: "000000" }, rectRadius: 0.16, shadow: sh({ blur: 22, opacity: 0.5 }) });
  s.addImage({ path: A("phone_bg"), x: px, y: py, w: pw, h: ph });
  // 상단 바
  txt(s, "SafeAlert", { x: px + 0.2, y: py + 0.12, w: 1.4, h: 0.22, fontSize: 9, bold: true, color: C.t1, valign: "middle" });
  s.addShape(pres.ShapeType.ellipse, { x: px + 0.1, y: py + 0.19, w: 0.08, h: 0.08, fill: { color: C.accent } });
  // 상태 카드
  const cy = py + 0.44, cw2 = pw - 0.24;
  s.addShape(pres.ShapeType.roundRect, { x: px + 0.12, y: cy, w: cw2, h: 3.22, fill: { color: C.veil }, rectRadius: 0.08, line: { color: C.hair, width: 0.75 } });
  s.addShape(pres.ShapeType.ellipse, { x: px + 0.24, y: cy + 0.16, w: 0.08, h: 0.08, fill: { color: C.safe } });
  txt(s, "백그라운드 실행 중", { x: px + 0.38, y: cy + 0.08, w: 1.6, h: 0.24, fontSize: 8.5, bold: true, color: C.t1, valign: "middle" });
  txt(s, "02:14:06", { x: px + 0.12, y: cy + 0.08, w: cw2 - 0.16, h: 0.24, fontSize: 8, color: C.t3, align: "right", valign: "middle" });
  sectionLabel(s, px + 0.24, cy + 0.42, "내 장비 (Local)", C.dim, 8);
  s.addImage({ path: A("ic_forklift"), x: px + 0.24, y: cy + 0.72, w: 0.4, h: 0.4 });
  txt(s, "지게차", { x: px + 0.72, y: cy + 0.74, w: 1.1, h: 0.22, fontSize: 10.5, bold: true, color: C.t1, valign: "middle" });
  txt(s, "FORK-07", { x: px + 0.72, y: cy + 0.94, w: 1.1, h: 0.2, fontSize: 8, color: C.t3, valign: "middle" });
  chip(s, px + cw2 - 0.52, cy + 0.78, 0.56, 0.26, "TEST", C.alt, C.accent, 7.5, C.stroke);
  txt(s, "상태: 전진·주행  ·  속도: 6 km/h", { x: px + 0.24, y: cy + 1.22, w: cw2 - 0.24, h: 0.22, fontSize: 8, color: C.t2, valign: "middle" });
  sectionLabel(s, px + 0.24, cy + 1.56, "수신 타겟 (Target)", C.danger, 8);
  const rows = [["보행자 A", "-56 dBm  ·  3.4 m", "위험", C.danger], ["EPJ B", "-71 dBm  ·  9.8 m", "경고", C.warn], ["보행자 C", "-84 dBm  ·  18 m", "안전", C.safe]];
  rows.forEach(([n, d, lv, col], i) => {
    const ry = cy + 1.86 + i * 0.46;
    s.addShape(pres.ShapeType.roundRect, { x: px + 0.2, y: ry, w: cw2 - 0.16, h: 0.4, fill: { color: C.surface }, rectRadius: 0.05 });
    s.addShape(pres.ShapeType.rect, { x: px + 0.28, y: ry + 0.15, w: 0.09, h: 0.09, fill: { color: col } });
    txt(s, n, { x: px + 0.44, y: ry + 0.03, w: 0.9, h: 0.18, fontSize: 8, bold: true, color: C.t1, valign: "middle" });
    txt(s, d, { x: px + 0.44, y: ry + 0.2, w: 1.2, h: 0.17, fontSize: 7, color: C.t3, valign: "middle" });
    chip(s, px + cw2 - 0.52, ry + 0.09, 0.52, 0.24, lv, col, C.onAcc, 7.5);
  });
  chip(s, px + 0.12, py + 3.94, cw2 / 2 - 0.04, 0.36, "역할 전환", C.alt, C.t2, 8.5, C.stroke);
  chip(s, px + 0.2 + cw2 / 2, py + 3.94, cw2 / 2 - 0.04, 0.36, "중지", C.danger, C.onAcc, 8.5);
  txt(s, "메인 화면 (v1.1.70)", { x: px - 0.07, y: py + ph + 0.16, w: pw + 0.14, h: 0.26, fontSize: 10, color: C.t3, align: "center" });

  const notes = [
    ["역할 선택이 전부", "보행자 · EPJ · 지게차 중 하나를 고르면 그 조합에 맞는 경고·위험 거리가 자동 적용된다. 작업자가 설정할 것이 없다.", C.accent],
    ["감지 목록 실시간 표시", "주변 기기마다 신호 세기·추정 거리·등급을 보여준다. 등급 색은 앱 전체에서 같은 의미로 쓰인다.", C.warn],
    ["화면을 꺼도 동작", "포그라운드 서비스로 상시 스캔한다. 화면이 꺼진 상태에서도 경보가 뜨고, 상시 알림으로 동작 여부를 확인할 수 있다.", C.safe],
    ["경보는 3중 출력", "소리 · 진동 · 화면 가장자리 사이드바가 동시에 뜬다. 장비 소음 환경에서 소리 하나에 의존하지 않는다.", C.danger],
  ];
  const nx = px + pw + 0.9, nw = W - M - nx;
  let ny = 1.7;
  notes.forEach(([k, v, col]) => {
    card(s, nx, ny, nw, 1.06, { fill: C.alt, shadow: { opacity: 0.22 } });
    sectionLabel(s, nx + 0.3, ny + 0.2, k, col, 13);
    txt(s, v, { x: nx + 0.52, y: ny + 0.52, w: nw - 0.86, h: 0.44, fontSize: 11.5, color: C.t2, lineSpacing: 16 });
    ny += 1.14;
  });
  footer(s);
  s.addNotes("화면은 v1.1.70 메인 화면 구성 그대로다. 강조점은 '작업자가 조작할 것이 거의 없다'는 것 — 역할만 고르면 나머지는 백그라운드에서 돈다. 현장 교육 부담이 작다는 뜻이다.");
}

/* ═══ 6. 핵심 기능 ═══════════════════════════════════════════════ */
{
  const s = slide();
  title(s, "핵심 기능", "현재 버전 v1.1.70 에서 실제로 동작하는 기능");
  const feats = [
    ["ic_forklift", "역할 기반 차등 반경", "지게차·EPJ·보행자 조합별로 경고·위험 거리를 다르게 적용한다", C.warn],
    ["ic_bell", "3단계 경보", "안전 / 경고 / 위험. 등급에 따라 소리·진동·화면 표시가 달라진다", C.danger],
    ["ic_share_out", "양방향 협력 알림", "한쪽이 먼저 감지하면 상대 기기도 함께 울린다. 한쪽 신호가 약해도 놓치지 않는다", C.safe],
    ["ic_receive_in", "후진·하역 특수 경보", "상대가 후진 또는 하역·고소작업 중이면 근접 시 즉시 최고 등급으로 격상한다", C.danger],
    ["ic_signal_bars", "모션·회전 인식", "단말 센서로 정지·주행·급정거와 좌·우 회전을 판별해 경보 판정에 반영한다", C.accent],
    ["ic_antenna", "보조 측위 수단", "UWB 정밀 거리, 존 비콘, 사업장별 보정값 공유가 주 판정을 보완한다", C.accent],
  ];
  const cw = 3.98, ch = 1.88, gx = 0.13, gy = 0.16;
  feats.forEach(([ic, k, v, col], i) => {
    const x = M + (i % 3) * (cw + gx), y = 1.68 + Math.floor(i / 3) * (ch + gy);
    card(s, x, y, cw, ch);
    iconTile(s, x + 0.28, y + 0.26, 0.62, ic);
    sectionLabel(s, x + 0.28, y + 1.02, k, col, 14);
    txt(s, v, { x: x + 0.5, y: y + 1.34, w: cw - 0.78, h: 0.5, fontSize: 11.5, color: C.t2, lineSpacing: 16 });
  });
  txt(s, "모든 기능은 단말 한 대 안에서 완결된다. 클라우드가 죽어도, UWB 가 없어도 블루투스 근접 경보는 그대로 동작한다.",
    { x: M, y: 5.82, w: W - M * 2, h: 0.34, fontSize: 12, italic: true, color: C.accent });
  footer(s);
  s.addNotes("여섯 가지 중 세 개(양방향 협력 알림, 후진·하역 특수 경보, 모션·회전 인식)는 시판 제품에서 잘 보이지 않는 항목이다. 현장 요구에서 나온 기능이라는 점을 강조한다.");
}

/* ═══ 7. 판정 반경 ═══════════════════════════════════════════════ */
{
  const s = slide();
  title(s, "역할 조합별 판정 반경", "같은 거리라도 상대가 지게차인지 보행자인지에 따라 다르게 판정한다");
  function rings(cx, cy, outer, ratio) {
    s.addShape(pres.ShapeType.ellipse, { x: cx - outer / 2, y: cy - outer / 2, w: outer, h: outer, fill: { color: "141C2A" }, line: { color: C.warn, width: 1.5 } });
    const inner = outer * ratio;
    s.addShape(pres.ShapeType.ellipse, { x: cx - inner / 2, y: cy - inner / 2, w: inner, h: inner, fill: { color: "1F2637" }, line: { color: C.danger, width: 1.5 } });
    s.addShape(pres.ShapeType.ellipse, { x: cx - 0.17, y: cy - 0.17, w: 0.34, h: 0.34, fill: { color: C.accent } });
  }
  function combo(x, headline, sub, imgs, outer, ratio, warn, danger) {
    card(s, x, 1.66, 5.98, 3.56, { fill: C.alt });
    txt(s, headline, { x: x + 0.32, y: 1.86, w: 5.34, h: 0.3, fontSize: 14.5, bold: true, color: C.t1 });
    txt(s, sub, { x: x + 0.32, y: 2.16, w: 5.34, h: 0.28, fontSize: 11, color: C.t3 });
    imgs.forEach((im, i) => iconTile(s, x + 0.34 + i * 0.72, 2.62, 0.6, im, C.surface));
    rings(x + 3.86, 3.52, outer, ratio);
    chip(s, x + 0.32, 4.66, 1.7, 0.42, warn, C.warn, C.onAcc, 12);
    chip(s, x + 2.1, 4.66, 1.7, 0.42, danger, C.danger, C.onAcc, 12);
  }
  combo(M, "지게차가 포함된 조합", "지게차 ↔ 보행자 · EPJ · 지게차", ["ic_forklift", "ic_walker", "ic_epj"], 1.94, 8 / 15, "경고 15m", "위험 8m");
  combo(M + 6.11, "그 외 조합", "보행자 ↔ 보행자 · EPJ ↔ 보행자 · EPJ ↔ EPJ", ["ic_walker", "ic_epj"], 1.32, 3 / 5, "경고 5m", "위험 3m");
  card(s, M, 5.42, W - M * 2, 0.9, { fill: C.surface });
  txt(s, "판정 값은 현장에서 확정된 기준이며, 설정에서 사업장별로 조정할 수 있다. 무거운 장비일수록 제동거리가 길기 때문에 지게차 조합의 반경이 크다. (도식은 비례 축척이 아니다)",
    { x: M + 0.3, y: 5.42, w: W - M * 2 - 0.6, h: 0.9, fontSize: 12.5, color: C.t2, valign: "middle", lineSpacing: 18 });
  footer(s);
  s.addNotes("이 값은 임의로 정한 것이 아니라 현장에서 확인된 값이다. 현재 개선 작업의 목표도 값을 바꾸는 것이 아니라 값대로 정확히 동작하게 하는 것이다.");
}

/* ═══ 8. 동작 원리 ═══════════════════════════════════════════════ */
{
  const s = slide();
  title(s, "동작 원리 — 감지에서 경보까지", "신호 한 번이 경보가 되기까지 거치는 5단계 (아래 회색 글씨는 실제 구성 요소)");
  const steps = [
    ["01", "광고 · 스캔", "모든 기기가 동시에 송신자이자 수신자다. 1초 주기 연속 스캔, 화면이 꺼져도 유지된다.", "BleAdvertiser / BleScanner", C.accent],
    ["02", "신호 정제 3단", "튐값 제거 → 이상치 차단 → 평활화. 약 120ms 마다 갱신한다.", "MedianFilter → RssiPreFilter → KalmanFilter", C.accent],
    ["03", "거리 · 접근 추정", "역할쌍 보정, 상호 신호 대칭화, UWB 정밀 거리로 보완한다.", "역할쌍 오프셋 · 에코 RSSI · UwbRanger", C.accent],
    ["04", "경보 판정", "접근 속도와 상대의 상태를 함께 보고 등급을 올리거나 내린다.", "BleService 경보 상태머신", C.warn],
    ["05", "경보 출력", "등급에 맞춰 소리 · 진동 · 화면 사이드바를 동시에 낸다.", "AlertSoundPlayer · VibrationHelper · OverlayManager", C.danger],
  ];
  const cw = 2.36, gx = 0.11;
  steps.forEach(([n, k, v, comp, col], i) => {
    const x = M + i * (cw + gx), hi = i === 3;
    card(s, x, 1.72, cw, 3.36, { fill: hi ? C.surface : C.alt, line: hi ? C.warn : null });
    txt(s, n, { x: x + 0.24, y: 1.88, w: 1, h: 0.34, fontSize: 15, bold: true, color: col, fontFace: HEAD });
    txt(s, k, { x: x + 0.24, y: 2.26, w: cw - 0.48, h: 0.6, fontSize: 14, bold: true, color: C.t1, lineSpacing: 18 });
    txt(s, v, { x: x + 0.24, y: 2.9, w: cw - 0.48, h: 1.32, fontSize: 11.5, color: C.t2, lineSpacing: 16 });
    txt(s, comp, { x: x + 0.24, y: 4.28, w: cw - 0.48, h: 0.68, fontSize: 9.5, color: "6B7A90", lineSpacing: 13 });
    if (i < 4) s.addShape(pres.ShapeType.line, { x: x + cw + 0.005, y: 3.4, w: gx - 0.01, h: 0, line: { color: C.accent, width: 2, endArrowType: "triangle" } });
  });
  const tm = [["연속 스캔", "1초 주기"], ["판정 갱신", "약 120ms"], ["화면 갱신", "800ms"], ["경보 반응", "즉시"]];
  let tx = M;
  tm.forEach(([k, v]) => {
    const w = 2.94;
    card(s, tx, 5.24, w, 0.72, { fill: C.alt, shadow: { opacity: 0.2 } });
    txt(s, k, { x: tx + 0.28, y: 5.24, w: w - 0.56, h: 0.72, fontSize: 11.5, color: C.t3, valign: "middle" });
    txt(s, v, { x: tx + 0.28, y: 5.24, w: w - 0.62, h: 0.72, fontSize: 12.5, bold: true, color: C.t1, valign: "middle", align: "right" });
    tx += w + 0.11;
  });
  footer(s);
  s.addNotes("3단 신호 정제가 이 시스템의 심장이다. 블루투스 신호 세기는 그대로 쓰면 초당 수 dB 씩 튀기 때문에 정제 없이는 경보가 깜빡인다. 04단계(경보 판정)가 현재 개선 작업의 주 대상이다.");
}

/* ═══ 9. 1바이트 프로토콜 ════════════════════════════════════════ */
{
  const s = slide();
  title(s, "1바이트 프로토콜", "블루투스 광고 패킷 단 1바이트에 역할 · 상태 · 회전 · 위험도를 모두 담는다");
  const fields = [
    ["CAT", "역할", ["00  보행자", "01  EPJ", "10  지게차 · 리치", "11  예약"], C.accent],
    ["STATE", "동적 상태", ["00  정지 · 일반", "01  전진 · 주행", "10  후진  ▲", "11  하역 · 작업  ▲"], C.danger],
    ["TURN", "회전 방향", ["00  직진", "01  좌회전", "10  우회전", "11  예약"], C.dim],
    ["RISK", "자기 위험도", ["00  안전", "01  경고 감지", "10  위험 감지", "11  예약"], C.safe],
  ];
  const cw = 2.98, gx = 0.14;
  fields.forEach(([f, ko, vals, col], i) => {
    const x = M + i * (cw + gx);
    [0, 1].forEach((b) => {
      const bx = x + b * (cw / 2 + 0.05);
      s.addShape(pres.ShapeType.roundRect, { x: bx, y: 1.72, w: cw / 2 - 0.05, h: 0.6, fill: { color: C.alt }, line: { color: col, width: 1.25 }, rectRadius: 0.05 });
      txt(s, "bit " + (7 - i * 2 - b), { x: bx, y: 1.72, w: cw / 2 - 0.05, h: 0.6, fontSize: 10.5, color: C.t3, align: "center", valign: "middle" });
    });
    txt(s, f, { x, y: 2.44, w: cw, h: 0.34, fontSize: 15, bold: true, color: col, align: "center", fontFace: HEAD });
    txt(s, ko, { x, y: 2.76, w: cw, h: 0.28, fontSize: 11, color: C.t3, align: "center" });
    card(s, x, 3.12, cw, 1.86, { fill: C.surface });
    vals.forEach((v, j) => txt(s, v, { x: x + 0.26, y: 3.28 + j * 0.42, w: cw - 0.52, h: 0.36, fontSize: 11.5,
      color: v.indexOf("▲") >= 0 ? C.warn : C.t2, valign: "middle" }));
  });
  txt(s, "▲ 특수경보 트리거 — 상대가 후진 · 하역 중이면 근접 즉시 최고 등급", { x: M, y: 5.08, w: 7.6, h: 0.3, fontSize: 10.5, color: C.t3 });
  const why = [["왜 1바이트인가", "광고 패킷 용량이 극히 작다. 필요한 정보를 비트 단위로 눌러 담아야 1초 주기 연속 방송이 가능하다."],
               ["왜 못 바꾸는가", "현장에 이미 배포된 구버전 단말과 통신해야 한다. 배치가 바뀌면 구버전과 신버전이 서로를 오인한다."],
               ["호환 설계", "보행자 평상 상태 = 0x00. 페이로드를 싣지 않는 일반 비콘의 기본값과 자연 일치한다."]];
  let y = 5.48;
  why.forEach(([k, v]) => {
    sectionLabel(s, M, y, k, C.accent, 11.5);
    txt(s, v, { x: M + 2.4, y, w: W - M * 2 - 2.4, h: 0.28, fontSize: 11.5, color: C.t2, valign: "middle" });
    y += 0.34;
  });
  footer(s);
  s.addNotes("경영진에게 전할 함의는 하나다 — 이 레이아웃은 현장 호환성 때문에 바꿀 수 없는 제약이며, 앞으로의 모든 개선은 이 제약 안에서 이뤄진다. 동시에 이 설계 덕분에 신버전 배포가 구버전 단말을 무력화하지 않는다.");
}

/* ═══ 10. 시스템 구조 ════════════════════════════════════════════ */
{
  const s = slide();
  title(s, "시스템 구조", "27개 파일 11,636줄 · 6개 계층 · 순환 참조 없음 (2026.08 실측)");
  const layers = [["05_ui", "화면 · 역할 선택 · 설정", "5개 파일", "2,521줄", C.dim],
                  ["03_service", "경보 판정 · 상태 관리 · 생명주기", "3개 파일", "4,133줄", C.danger],
                  ["02_ble", "블루투스 송수신 · 신호 정제", "7개 파일", "1,712줄", C.accent],
                  ["06_utils", "보정 · UWB · 비콘 · 오버레이 · 설정", "7개 파일", "3,040줄", C.warn],
                  ["01_model", "데이터 모델", "2개 파일", "32줄", C.t3]];
  let y = 1.72, lw = 8.9;
  layers.forEach(([id, ko, f, l, col], i) => {
    const h = 0.78, hi = i === 1;
    card(s, M, y, lw, h, { fill: hi ? C.surface : C.alt, line: hi ? C.danger : null });
    s.addShape(pres.ShapeType.rect, { x: M + 0.26, y: y + h / 2 - 0.06, w: 0.12, h: 0.12, fill: { color: col } });
    txt(s, id, { x: M + 0.6, y, w: 1.7, h, fontSize: 13, bold: true, color: C.t1, valign: "middle", fontFace: HEAD });
    txt(s, ko, { x: M + 2.34, y, w: 4.4, h, fontSize: 12, color: C.t2, valign: "middle" });
    txt(s, f, { x: M + 6.7, y, w: 1.1, h, fontSize: 11, color: C.t3, valign: "middle", align: "right" });
    txt(s, l, { x: M + 7.85, y, w: 0.85, h, fontSize: 12, bold: true, color: hi ? C.danger : C.t1, valign: "middle", align: "right" });
    if (i < layers.length - 1) s.addShape(pres.ShapeType.line, { x: M + lw / 2, y: y + h, w: 0, h: 0.14, line: { color: C.hair, width: 1.5, endArrowType: "triangle" } });
    y += h + 0.14;
  });
  const bx = M + lw + 0.16, bw = W - M - bx;
  card(s, bx, 1.72, bw, 2.4, { fill: C.alt });
  txt(s, "04_firebase", { x: bx + 0.28, y: 1.9, w: bw - 0.56, h: 0.3, fontSize: 13, bold: true, color: C.t1, fontFace: HEAD });
  txt(s, "설정 · 프로필 공유, 배포 권위\n2개 파일 · 181줄", { x: bx + 0.28, y: 2.22, w: bw - 0.56, h: 0.6, fontSize: 11.5, color: C.t2, lineSpacing: 16 });
  txt(s, "경보 판정 경로에서 분리되어 있다.\n클라우드가 끊겨도 경보는 동작한다.", { x: bx + 0.28, y: 2.94, w: bw - 0.56, h: 0.8, fontSize: 11, color: C.t3, lineSpacing: 15, italic: true });
  card(s, bx, 4.3, bw, 1.7, { fill: C.surface, line: C.safe });
  txt(s, "테스트", { x: bx + 0.28, y: 4.46, w: bw - 0.56, h: 0.3, fontSize: 13, bold: true, color: C.safe, fontFace: HEAD });
  txt(s, "JVM 단위 테스트 17건 · 488줄\n실기기 없이 CI 에서 자동 실행", { x: bx + 0.28, y: 4.78, w: bw - 0.56, h: 0.66, fontSize: 11.5, color: C.t2, lineSpacing: 16 });
  txt(s, "2026.08 신설 (로드맵 1단계)", { x: bx + 0.28, y: 5.5, w: bw - 0.56, h: 0.3, fontSize: 10.5, color: C.t3 });
  footer(s);
  s.addNotes("계층이 위에서 아래로만 의존한다 — 순환 참조가 없다는 것은 한 계층을 고쳐도 다른 계층이 연쇄적으로 깨지지 않는다는 뜻이다. 문제는 계층 구조가 아니라 03_service 한 파일에 책임이 몰려 있다는 점이며, 다음 장에서 다룬다.");
}

/* ═══ 11. 코드 규모 진단 ═════════════════════════════════════════ */
{
  const s = slide();
  title(s, "개선이 필요한 지점", "파일 하나에 경보 · 거리 · 보정 · 생명주기가 모두 들어 있다");
  s.addChart(pres.ChartType.bar, [{
    name: "코드 줄 수",
    labels: ["OverlayManager", "BleAdvertiser", "DevSettings", "UwbRanger", "MainActivity", "BleService\n(경보 판정 전체)"],
    values: [602, 632, 752, 850, 942, 3899],
  }], {
    x: M, y: 1.74, w: 7.5, h: 3.9, barDir: "bar",
    chartColors: [C.dim, C.dim, C.dim, C.dim, C.dim, C.danger],
    showTitle: true, title: "상위 6개 파일의 코드 줄 수 (전체 27개 파일 11,636줄)",
    titleFontSize: 12, titleColor: C.t3, titleFontFace: BODY,
    showValue: true, dataLabelPosition: "outEnd", dataLabelFontSize: 10.5, dataLabelColor: C.t2, dataLabelFontFace: BODY,
    catAxisLabelColor: C.t2, catAxisLabelFontSize: 10.5, catAxisLabelFontFace: BODY,
    valAxisHidden: true, valGridLine: { style: "none" }, catGridLine: { style: "none" },
    showLegend: false, barGapWidthPct: 45, valAxisMaxVal: 4600, plotArea: { fill: { color: C.bg } }, chartArea: { fill: { color: C.bg } },
  });
  const bx = M + 7.68, bw = W - M - bx;
  card(s, bx, 1.74, bw, 1.86, { fill: C.surface, line: C.danger });
  txt(s, "무엇이 문제인가", { x: bx + 0.28, y: 1.92, w: bw - 0.56, h: 0.3, fontSize: 13.5, bold: true, color: C.danger, fontFace: HEAD });
  txt(s, "경보 등급 판정, UWB 거리 관리, 보정값 계산, 서비스 생명주기가 한 파일 안에 섞여 있다. 한 곳을 고치면 다른 증상이 따라 나온다.",
    { x: bx + 0.28, y: 2.26, w: bw - 0.56, h: 1.2, fontSize: 11.5, color: C.t2, lineSpacing: 17 });
  card(s, bx, 3.74, bw, 1.98, { fill: C.alt });
  txt(s, "어떻게 푸는가", { x: bx + 0.28, y: 3.92, w: bw - 0.56, h: 0.3, fontSize: 13.5, bold: true, color: C.t1, fontFace: HEAD });
  ["경보 판정 컴포넌트", "UWB 거리 컴포넌트", "보정 컴포넌트"].forEach((t, i) => {
    s.addShape(pres.ShapeType.rect, { x: bx + 0.3, y: 4.24 + i * 0.36 + 0.11, w: 0.1, h: 0.1, fill: { color: C.accent } });
    txt(s, t, { x: bx + 0.54, y: 4.24 + i * 0.36, w: bw - 0.82, h: 0.32, fontSize: 12, color: C.t2, valign: "middle" });
  });
  txt(s, "세 컴포넌트로 분리 (로드맵 3단계)", { x: bx + 0.28, y: 5.36, w: bw - 0.56, h: 0.3, fontSize: 10.5, italic: true, color: C.t3 });
  txt(s, "분해의 수용 기준은 '동작이 하나도 달라지지 않는 것'이다. 기능을 바꾸는 작업이 아니라, 같은 동작을 검증 가능한 형태로 다시 세우는 작업이다.",
    { x: M, y: 5.82, w: W - M * 2, h: 0.34, fontSize: 12, italic: true, color: C.accent });
  footer(s);
  s.addNotes("숫자 하나만 기억하면 된다 — 3,899줄. 두 번째로 큰 파일의 네 배다. 과거 같은 계열의 수정이 세 번 반복된 원인이 여기에 있다. 분해 후에도 앱 동작은 동일해야 하며, 그것이 수용 기준이다.");
}

/* ═══ 12. 로드맵 ═════════════════════════════════════════════════ */
{
  const s = slide();
  title(s, "신뢰성 확보 로드맵", "'같은 상황에서 같은 판정' — 5단계, 매 단계마다 출하 가능한 상태로 끝난다");
  const ph = [["1", "테스트 · CI 회귀 게이트", "회귀를 현장이 아니라 빌드에서 먼저 잡는다", "완료", true],
              ["2", "경보 경로 기준값 고정", "경보 격상 · 해제 전 경로를 기대값에 고정, 저속 접근 미탐지 수정", "예정", false],
              ["3", "핵심 로직 분해", "경보 · 거리 · 보정 세 컴포넌트로 분리, 동작 보존 증명", "예정", false],
              ["4", "기기 상태 단일화", "흩어진 상태를 하나로 통합, 장시간 구동 안정성 확보", "예정", false],
              ["5", "판정 처리 분리", "다수 기기 현장에서 화면 지연 없이 동작", "예정", false]];
  const cw = 2.36, gx = 0.11;
  ph.forEach(([n, k, v, st, done], i) => {
    const x = M + i * (cw + gx);
    card(s, x, 1.86, cw, 3.02, { fill: done ? C.surface : C.alt, line: done ? C.safe : null });
    s.addShape(pres.ShapeType.ellipse, { x: x + cw / 2 - 0.28, y: 1.58, w: 0.56, h: 0.56, fill: { color: done ? C.safe : C.alt }, line: { color: done ? C.safe : C.stroke, width: 1 } });
    txt(s, n, { x: x + cw / 2 - 0.28, y: 1.58, w: 0.56, h: 0.56, fontSize: 16, bold: true, color: done ? C.onAcc : C.t3, align: "center", valign: "middle", fontFace: HEAD });
    txt(s, k, { x: x + 0.24, y: 2.36, w: cw - 0.48, h: 0.72, fontSize: 13.5, bold: true, color: C.t1, lineSpacing: 18 });
    txt(s, v, { x: x + 0.24, y: 3.12, w: cw - 0.48, h: 1.2, fontSize: 11.5, color: C.t2, lineSpacing: 16 });
    chip(s, x + 0.24, 4.4, 1.0, 0.34, st, done ? C.safe : C.alt, done ? C.onAcc : C.t3, 10, done ? null : C.stroke);
    if (i < 4) s.addShape(pres.ShapeType.line, { x: x + cw + 0.005, y: 3.36, w: gx - 0.01, h: 0, line: { color: C.hair, width: 2, endArrowType: "triangle" } });
  });
  card(s, M, 5.12, W - M * 2, 0.9, { fill: C.surface });
  sectionLabel(s, M + 0.3, 5.44, "1단계 완료 성과", C.safe, 12.5);
  txt(s, "단위 테스트 17건 신설  ·  빌드마다 자동 실행  ·  실패 시 릴리스 자동 차단  ·  실기기 없이 회귀 판별 가능",
    { x: M + 2.6, y: 5.12, w: W - M * 2 - 2.9, h: 0.9, fontSize: 12.5, color: C.t2, valign: "middle" });
  footer(s);
  s.addNotes("중요한 설계 원칙: 모든 단계가 출하 가능한 상태로 끝난다. 앱이 반쯤 분해된 채 검증 불가 상태로 오래 머무는 것을 명시적으로 금지했다. 현장 검증 사이클이 병목이기 때문이다.");
}

/* ═══ 13. 품질 게이트 ════════════════════════════════════════════ */
{
  const s = slide();
  title(s, "품질 관리 체계", "1단계에서 새로 만든 것 — 회귀를 현장이 아니라 빌드에서 잡는 장치");
  const flow = [["코드 변경", "개발자가 수정을 올린다", C.dim], ["자동 빌드", "GitHub Actions 가 즉시 빌드한다", C.dim],
                ["기준값 검증", "단위 테스트 17건이 신호 처리 결과를 기대값과 대조한다", C.accent],
                ["통과 / 차단", "하나라도 어긋나면 릴리스가 차단된다", C.danger],
                ["현장 배포", "통과 시에만 앱이 자동 업데이트 대상이 된다", C.safe]];
  const cw = 2.36, gx = 0.11;
  flow.forEach(([k, v, col], i) => {
    const x = M + i * (cw + gx);
    card(s, x, 1.74, cw, 1.98, { fill: C.alt });
    s.addShape(pres.ShapeType.rect, { x: x + 0.24, y: 1.99, w: 0.13, h: 0.13, fill: { color: col } });
    txt(s, k, { x: x + 0.5, y: 1.92, w: cw - 0.74, h: 0.28, fontSize: 13, bold: true, color: C.t1, valign: "middle" });
    txt(s, v, { x: x + 0.24, y: 2.42, w: cw - 0.48, h: 1.14, fontSize: 11.5, color: C.t2, lineSpacing: 16 });
    if (i < 4) s.addShape(pres.ShapeType.line, { x: x + cw + 0.005, y: 2.72, w: gx - 0.01, h: 0, line: { color: C.accent, width: 2, endArrowType: "triangle" } });
  });
  card(s, M, 3.98, 5.98, 1.94, { fill: C.alt });
  txt(s, "이전", { x: M + 0.32, y: 4.16, w: 5.3, h: 0.3, fontSize: 12.5, bold: true, color: C.t3 });
  txt(s, "회귀를 사용자가 현장에서 발견했다.", { x: M + 0.32, y: 4.48, w: 5.3, h: 0.36, fontSize: 15, bold: true, color: C.t2 });
  txt(s, "증상이 보고되면 그때부터 원인을 찾았고, 확인 사이클이 현장 가용 시간에 묶여 있었다.", { x: M + 0.32, y: 4.9, w: 5.3, h: 0.8, fontSize: 11.5, color: C.t3, lineSpacing: 16 });
  card(s, M + 6.11, 3.98, 5.98, 1.94, { fill: C.surface, line: C.safe });
  txt(s, "이후", { x: M + 6.43, y: 4.16, w: 5.3, h: 0.3, fontSize: 12.5, bold: true, color: C.safe });
  txt(s, "회귀를 빌드가 먼저 발견한다.", { x: M + 6.43, y: 4.48, w: 5.3, h: 0.36, fontSize: 15, bold: true, color: C.t1 });
  txt(s, "테스트가 실제로 실행됐는지까지 검증한다. 테스트가 조용히 사라져 통과처럼 보이는 상황을 차단했다.", { x: M + 6.43, y: 4.9, w: 5.3, h: 0.8, fontSize: 11.5, color: C.t2, lineSpacing: 16 });
  footer(s);
  s.addNotes("경영진 관점의 의미: 확산의 전제 조건이 갖춰지기 시작했다. 단말이 늘면 문제 보고도 늘어나는데, 회귀를 자동으로 잡는 장치 없이는 대수를 늘릴수록 관리 비용이 비선형으로 커진다.");
}

/* ═══ 14. 확산 시 이점 ═══════════════════════════════════════════ */
{
  const s = slide();
  title(s, "확산 시 이점", "설치 대수가 늘수록 감지 범위가 인프라 비용 없이 넓어진다");
  s.addChart(pres.ChartType.bar, [{ name: "상호 감지 링크 수", labels: ["2대", "5대", "10대", "20대", "30대"], values: [1, 10, 45, 190, 435] }], {
    x: M, y: 1.76, w: 6.0, h: 3.44, chartColors: [C.accent],
    showTitle: true, title: "설치 대수와 상호 감지 링크 수   n(n-1)/2", titleFontSize: 12, titleColor: C.t3, titleFontFace: BODY,
    showValue: true, dataLabelPosition: "outEnd", dataLabelFontSize: 10.5, dataLabelColor: C.t2, dataLabelFontFace: BODY,
    catAxisLabelColor: C.t2, catAxisLabelFontSize: 11, catAxisLabelFontFace: BODY,
    valAxisHidden: true, valGridLine: { style: "none" }, catGridLine: { style: "none" },
    showLegend: false, barGapWidthPct: 55, valAxisMaxVal: 520, plotArea: { fill: { color: C.bg } }, chartArea: { fill: { color: C.bg } },
  });
  const bx = M + 6.2, bw = W - M - bx;
  const gains = [["ic_bulb", "한계비용 구조", "단말 한 대 추가 = 앱 설치 한 건. 앵커·배선·서버 증설이 없다", C.safe],
                 ["ic_share_out", "커버리지 자기 증식", "대수가 늘수록 감지쌍이 제곱으로 늘어난다. 사각지대가 자연히 줄어든다", C.accent],
                 ["ic_receive_in", "이식성", "센터나 라인이 바뀌어도 재공사가 없다. 사업장별 보정값만 공유하면 된다", C.accent],
                 ["ic_search", "운영 데이터 축적", "경보 이력이 쌓이면 위험 구간·시간대를 근거로 분석할 수 있다", C.warn]];
  let y = 1.76;
  gains.forEach(([ic, k, v, col]) => {
    card(s, bx, y, bw, 0.9, { fill: C.alt, shadow: { opacity: 0.2 } });
    iconTile(s, bx + 0.24, y + 0.19, 0.52, ic, C.surface);
    txt(s, k, { x: bx + 0.92, y: y + 0.12, w: bw - 1.16, h: 0.3, fontSize: 13, bold: true, color: col });
    txt(s, v, { x: bx + 0.92, y: y + 0.43, w: bw - 1.16, h: 0.4, fontSize: 11, color: C.t2, lineSpacing: 15 });
    y += 0.98;
  });
  card(s, M, 5.72, W - M * 2, 0.74, { fill: C.surface, line: C.warn });
  txt(s, "단, 단말 한 대가 동시에 추적할 수 있는 대수에는 현재 한계가 있다. 20대 이상 밀집 환경의 화면 지연은 로드맵 5단계에서 해소한다.",
    { x: M + 0.3, y: 5.72, w: W - M * 2 - 0.6, h: 0.74, fontSize: 12, color: C.t2, valign: "middle" });
  footer(s);
  s.addNotes("차트는 이론적 감지쌍 수이며 실제 동시 추적 성능과는 다르다. 그 격차를 정직하게 밝히는 것이 아래 주석이고, 로드맵 5단계가 그것을 다룬다. 비용 항목은 사내 단말 정책에 따라 달라지므로 별도 산정이 필요하다.");
}

/* ═══ 15. 도입 시나리오 ══════════════════════════════════════════ */
{
  const s = slide();
  title(s, "도입 시나리오 (제안)", "각 단계는 다음 단계로 넘어갈 판단 근거를 남기고 끝난다");
  const steps = [["STEP 1", "파일럿", "1개 센터, 위험 동선 1개 구간", ["대상 장비 · 인원에 앱 설치", "역할 · 반경 현장 확정", "오경보 · 미탐지 사례 수집"], "판단 근거 :  경보가 뜰 때 뜨고 꺼질 때 꺼지는가", C.accent, true],
                 ["STEP 2", "센터 단위 확대", "동일 사업장 전 라인", ["사업장별 보정값 공유 적용", "장시간 연속 구동 안정성 확인", "다수 기기 밀집 구간 성능 확인"], "판단 근거 :  대수가 늘어도 판정이 흔들리지 않는가", C.warn, false],
                 ["STEP 3", "표준화", "전 센터 · 운영 규칙 편입", ["단말 소지 · 충전 운영 규칙 수립", "신규 입사자 온보딩 절차 포함", "경보 이력 기반 정기 리뷰"], "판단 근거 :  운영 규칙 없이도 유지되는가", C.safe, false]];
  const cw = 4.0, gx = 0.11;
  steps.forEach(([n, k, scope, items, basis, col, hi], i) => {
    const x = M + i * (cw + gx);
    card(s, x, 1.74, cw, 4.16, { fill: hi ? C.surface : C.alt, line: hi ? C.accent : null });
    chip(s, x + 0.26, 1.96, 1.14, 0.34, n, col, C.onAcc, 10);
    txt(s, k, { x: x + 0.26, y: 2.42, w: cw - 0.52, h: 0.38, fontSize: 18, bold: true, color: C.t1, fontFace: HEAD });
    txt(s, scope, { x: x + 0.26, y: 2.82, w: cw - 0.52, h: 0.32, fontSize: 11.5, color: C.t3 });
    items.forEach((t, j) => {
      s.addShape(pres.ShapeType.rect, { x: x + 0.3, y: 3.34 + j * 0.52 + 0.11, w: 0.11, h: 0.11, fill: { color: col } });
      txt(s, t, { x: x + 0.56, y: 3.34 + j * 0.52, w: cw - 0.84, h: 0.46, fontSize: 11.5, color: C.t2, lineSpacing: 15 });
    });
    s.addShape(pres.ShapeType.line, { x: x + 0.26, y: 5.06, w: cw - 0.52, h: 0, line: { color: C.hair, width: 1 } });
    txt(s, basis, { x: x + 0.26, y: 5.16, w: cw - 0.52, h: 0.6, fontSize: 11, italic: true, color: col, lineSpacing: 15 });
    if (i < 2) s.addShape(pres.ShapeType.line, { x: x + cw + 0.005, y: 3.82, w: gx - 0.01, h: 0, line: { color: C.accent, width: 2, endArrowType: "triangle" } });
  });
  footer(s);
  s.addNotes("일정은 의도적으로 적지 않았다. 각 단계의 종료 조건이 기간이 아니라 판단 근거 확보이기 때문이다. 파일럿 기간은 현장 가용 시간에 따라 함께 정하면 된다.");
}

/* ═══ 16. 한계와 리스크 ══════════════════════════════════════════ */
{
  const s = slide();
  title(s, "한계와 리스크", "확산 결정 전에 알고 있어야 할 항목 — 숨기지 않고 함께 본다");
  const rows = [["신호 기반 거리 추정 오차", "구조물 · 적재물이 전파를 가리면 거리가 실제와 어긋난다", "3단 신호 정제 · 역할쌍 보정 · 상호 신호 대칭화. 재현성 확보가 로드맵 2~4단계", C.warn],
                ["단말 소지 · 전원 의존", "앱이 설치된 단말을 소지하지 않으면 감지되지 않는다", "운영 규칙으로 보완 필요. 기술로 해결되는 항목이 아니다", C.danger],
                ["다수 기기 밀집 성능", "20대 이상이 한 구역에 모이면 화면 반응이 느려진다", "로드맵 5단계에서 판정 처리를 분리해 해소", C.warn],
                ["UWB 정밀 측위 의존성", "사용 중인 UWB 라이브러리가 정식 출시 전 버전이다", "주 판정은 블루투스. UWB 는 보조 수단으로 한정해 영향 차단", C.dim],
                ["보안 조치 미완", "릴리스 빌드 난독화 미적용, 경보 이력 평문 저장", "확산 전 조치 필요 항목. 사내 보안 기준 확인 요청", C.danger]];
  txt(s, "항목", { x: M + 0.54, y: 1.74, w: 3.2, h: 0.3, fontSize: 11, bold: true, color: C.t3 });
  txt(s, "무엇이 문제인가", { x: M + 3.6, y: 1.74, w: 4.0, h: 0.3, fontSize: 11, bold: true, color: C.t3 });
  txt(s, "대응", { x: M + 7.7, y: 1.74, w: 4.3, h: 0.3, fontSize: 11, bold: true, color: C.t3 });
  let y = 2.14;
  rows.forEach(([k, p, a, col], i) => {
    card(s, M, y, W - M * 2, 0.82, { fill: i % 2 ? C.alt : C.surface, shadow: { opacity: 0.18 } });
    s.addShape(pres.ShapeType.rect, { x: M + 0.28, y: y + 0.35, w: 0.12, h: 0.12, fill: { color: col } });
    txt(s, k, { x: M + 0.54, y, w: 2.96, h: 0.82, fontSize: 12.5, bold: true, color: C.t1, valign: "middle", lineSpacing: 16 });
    txt(s, p, { x: M + 3.6, y, w: 4.0, h: 0.82, fontSize: 11.5, color: C.t2, valign: "middle", lineSpacing: 16 });
    txt(s, a, { x: M + 7.7, y, w: 4.31, h: 0.82, fontSize: 11.5, color: C.t2, valign: "middle", lineSpacing: 16 });
    y += 0.9;
  });
  footer(s);
  s.addNotes("마지막 항목(보안)은 파일럿 단계에서는 영향이 작지만 전사 확산 전에는 반드시 처리해야 한다. 의사결정 시점에 함께 검토를 요청하는 항목이다.");
}

/* ═══ 17. 요청 사항 ══════════════════════════════════════════════ */
{
  const s = slide();
  s.addImage({ path: A("closing"), x: 0, y: 0, w: W, h: 4.25 });
  txt(s, "요청 사항", { x: M, y: 0.46, w: W - M * 2, h: 0.6, fontSize: 29, bold: true, color: C.t1, fontFace: HEAD, valign: "middle" });
  txt(s, "다음 단계로 넘어가기 위해 결정이 필요한 세 가지", { x: M, y: 1.08, w: W - M * 2, h: 0.34, fontSize: 13, color: C.t2, valign: "middle" });
  const asks = [["01", "파일럿 대상 지정", "센터 1곳과 위험 동선 1개 구간, 참여 장비 · 인원 범위를 정해 주십시오.", C.accent],
                ["02", "현장 검증 시간 확보", "실기 검증이 유일한 회귀 확인 수단입니다. 정기적인 검증 슬롯이 필요합니다.", C.warn],
                ["03", "단말 운영 정책", "앱 설치 대상 단말, 소지 · 충전 규칙, 자동 업데이트 허용 여부에 대한 방침이 필요합니다.", C.safe]];
  const cw = 3.98, gx = 0.13;
  asks.forEach(([n, k, v, col], i) => {
    const x = M + i * (cw + gx);
    card(s, x, 2.06, cw, 2.1, { fill: C.surface, shadow: { blur: 20, opacity: 0.45 } });
    txt(s, n, { x: x + 0.3, y: 2.26, w: 1, h: 0.44, fontSize: 22, bold: true, color: col, fontFace: HEAD });
    txt(s, k, { x: x + 0.3, y: 2.78, w: cw - 0.6, h: 0.34, fontSize: 15, bold: true, color: C.t1 });
    txt(s, v, { x: x + 0.3, y: 3.16, w: cw - 0.6, h: 0.8, fontSize: 11.5, color: C.t2, lineSpacing: 16 });
  });
  s.addShape(pres.ShapeType.line, { x: M, y: 5.0, w: 3.0, h: 0, line: { color: C.hair, width: 1 } });
  txt(s, "경보가 떠야 할 때 뜨고, 꺼져야 할 때 꺼진다.", { x: M, y: 5.2, w: 9.5, h: 0.44, fontSize: 20, bold: true, color: C.t1, fontFace: HEAD });
  txt(s, "확산의 전제는 대수가 아니라 신뢰성입니다.", { x: M, y: 5.66, w: 9.5, h: 0.44, fontSize: 20, bold: true, color: C.accent, fontFace: HEAD });
  txt(s, "SafeAlert  ·  v1.1.70  ·  2026. 08.", { x: M, y: 6.3, w: 9.5, h: 0.3, fontSize: 11, color: C.t3 });
  s.addNotes("세 가지 요청은 모두 기술이 아니라 운영 결정이다. 개발 측에서 준비 가능한 부분은 로드맵으로 진행 중이며, 파일럿 확대 여부만 결정되면 즉시 착수할 수 있다.");
}

const out = process.argv[2] || "SafeAlert_Executive_Brief_v1.1.70.pptx";
pres.writeFile({ fileName: out }).then(() => console.log("wrote", out));
