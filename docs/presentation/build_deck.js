// SafeAlert 경영진 보고 덱 (v3)
// 팔레트: 앱 고정 다크(values/colors.xml, v1.1.65). 이미지·아이콘: app/src/main/res 실제 자산.
const P = require("pptxgenjs");
const path = require("path");
const A = (n) => path.join(__dirname, "assets", n + ".png");
const fs = require("fs");
// 동영상 표지(첫 화면). addMedia 의 cover 는 base64 데이터 URI 만 받는다.
const POSTER = "data:image/jpeg;base64," + fs.readFileSync(path.join(__dirname, "web", "sim-poster.jpg")).toString("base64");

const C = {
  bg: "0B1220", surface: "1A2233", alt: "161D2B", veil: "141B2B", tintA: "12202E", tintS: "0F1E1A",
  stroke: "56627A", hair: "232D42",
  t1: "F1F5F9", t2: "C3CDDC", t3: "8B98AC",
  accent: "7DD3FC", dim: "60A5FA", safe: "4ADE80", warn: "FCD34D", danger: "FB7185", onAcc: "0B1220",
};
const HEAD = "Malgun Gothic", BODY = "Malgun Gothic";
const W = 13.333, H = 7.5, M = 0.7;

const pres = new P();
pres.layout = "LAYOUT_WIDE";
pres.author = "SafeAlert";
pres.title = "SafeAlert 경영진 보고";

let page = 0;
const sh = (o) => Object.assign({ type: "outer", angle: 90, blur: 16, offset: 0.07, opacity: 0.38, color: "000000" }, o || {});
const txt = (s, t, o) => s.addText(t, Object.assign({ fontFace: BODY, isTextBox: true, margin: 0 }, o));

function slide(bg) {
  const s = pres.addSlide();
  s.background = { color: bg || C.bg };
  return s;
}
function card(s, x, y, w, h, o) {
  o = o || {};
  const opt = { x, y, w, h, fill: { color: o.fill || C.surface }, rectRadius: o.radius === undefined ? 0.11 : o.radius, shadow: sh(o.shadow) };
  if (o.line) opt.line = { color: o.line, width: o.lineW || 1.25 };
  s.addShape(pres.ShapeType.roundRect, opt);
}
function sq(s, x, y, color, d) { s.addShape(pres.ShapeType.rect, { x, y, w: d || 0.13, h: d || 0.13, fill: { color } }); }
function chip(s, x, y, w, h, text, fill, color, size, outline) {
  const o = { x, y, w, h, fill: { color: fill }, rectRadius: 0.5 };
  if (outline) o.line = { color: outline, width: 1 };
  s.addShape(pres.ShapeType.roundRect, o);
  txt(s, text, { x, y, w, h, fontSize: size || 12, bold: true, color, align: "center", valign: "middle" });
}
function iconTile(s, x, y, d, img, fill) {
  s.addShape(pres.ShapeType.roundRect, { x, y, w: d, h: d, fill: { color: fill || C.alt }, rectRadius: 0.24 });
  s.addImage({ path: A(img), x: x + d * 0.21, y: y + d * 0.21, w: d * 0.58, h: d * 0.58 });
}
// 제목 블록 — 키커(작고 넓은 자간) + 대형 제목 + 부제
function head(s, kicker, t, sub, kColor) {
  if (kicker) txt(s, kicker, { x: M, y: 0.44, w: 8, h: 0.26, fontSize: 11, bold: true, color: kColor || C.accent, charSpacing: 3 });
  txt(s, t, { x: M, y: 0.74, w: W - M * 2, h: 0.78, fontSize: 40, bold: true, color: C.t1, fontFace: HEAD, valign: "middle" });
  if (sub) txt(s, sub, { x: M, y: 1.54, w: W - M * 2, h: 0.34, fontSize: 14, color: C.t3, valign: "middle" });
}
function footer(s, light) {
  page += 1;
  const col = light ? "3C5A6B" : "56637C";
  txt(s, "SafeAlert  ·  경영진 보고  ·  v1.1.70", { x: M, y: H - 0.44, w: 6, h: 0.26, fontSize: 9, color: col, valign: "middle" });
  txt(s, String(page), { x: W - M - 1, y: H - 0.44, w: 1, h: 0.26, fontSize: 9, color: col, align: "right", valign: "middle" });
}
// 강조 스트립 — 한 줄짜리 핵심 문장
function strip(s, y, text, color, h) {
  card(s, M, y, W - M * 2, h || 0.86, { fill: C.tintA, line: color, lineW: 1.25 });
  txt(s, text, { x: M + 0.4, y, w: W - M * 2 - 0.8, h: h || 0.86, fontSize: 16, bold: true, color, valign: "middle" });
}

/* ══════ 1. 표지 ══════ */
{
  const s = slide();
  s.addImage({ path: A("hero_splash"), x: 6.55, y: 0, w: 6.783, h: 7.5 });
  txt(s, "물류 현장 근접 경보 시스템", { x: M, y: 2.1, w: 5.6, h: 0.32, fontSize: 13, bold: true, color: C.accent, charSpacing: 3 });
  txt(s, "SafeAlert", { x: M, y: 2.46, w: 6.0, h: 1.2, fontSize: 64, bold: true, color: C.t1, fontFace: HEAD });
  s.addShape(pres.ShapeType.line, { x: M, y: 3.86, w: 1.6, h: 0, line: { color: C.accent, width: 3 } });
  txt(s, "지게차와 보행자가\n서로를 감지한다.", { x: M, y: 4.12, w: 5.6, h: 1.0, fontSize: 23, bold: true, color: C.t2, lineSpacing: 34 });
  txt(s, "v1.1.70   ·   3개월간 70회 이상 릴리스   ·   현장 운영 중", { x: M, y: 5.6, w: 5.7, h: 0.3, fontSize: 12, color: C.t3 });
  txt(s, "2026. 08.", { x: M, y: 5.94, w: 5.7, h: 0.3, fontSize: 12, color: "56637C" });
  s.addNotes("보고 목적: 현장에서 운영 중인 SafeAlert 의 기능과 목적, 확산 시 이점을 공유하고 파일럿 확대 여부에 대한 결정을 요청한다. 표지는 앱 스플래시 화면 원본이다.");
}

/* ══════ 2. 한 장 요약 ══════ */
{
  const s = slide();
  head(s, "요약", "한 장 요약", null);
  const stats = [["70+", "릴리스", "3개월간 현장 운영", C.accent], ["0", "추가 인프라", "앵커 · 배선 · 서버 없음", C.safe],
                 ["3", "경보 등급", "안전 · 경고 · 위험", C.warn], ["1 / 5", "구조 개선", "신뢰성 로드맵 진행", C.danger]];
  const cw = (W - M * 2) / 4;
  s.addShape(pres.ShapeType.line, { x: M, y: 1.68, w: W - M * 2, h: 0, line: { color: C.hair, width: 1 } });
  stats.forEach(([big, lbl, sub, col], i) => {
    const x = M + i * cw, pad = i ? 0.44 : 0;
    if (i > 0) s.addShape(pres.ShapeType.line, { x, y: 1.88, w: 0, h: 1.46, line: { color: C.hair, width: 1 } });
    txt(s, big, { x: x + pad, y: 1.86, w: cw - pad - 0.2, h: 1.0, fontSize: 58, bold: true, color: col, fontFace: HEAD });
    txt(s, lbl, { x: x + pad, y: 2.86, w: cw - pad - 0.2, h: 0.3, fontSize: 15, bold: true, color: C.t1 });
    txt(s, sub, { x: x + pad, y: 3.16, w: cw - pad - 0.2, h: 0.3, fontSize: 11.5, color: C.t3 });
  });
  s.addShape(pres.ShapeType.line, { x: M, y: 3.54, w: W - M * 2, h: 0, line: { color: C.hair, width: 1 } });
  const rows = [["무엇을 하는가", "앱이 설치된 기기끼리 신호를 직접 주고받아, 위험 거리에 들어오면 소리 · 진동 · 화면으로 알린다", C.accent],
                ["왜 하는가", "3m 렉 사이 통로와 단독 작업 구간에서 서로를 육안으로 확인할 수 없다. 사각지대를 시스템이 메운다", C.accent],
                ["지금 하는 일", "신규 기능이 아니라 '같은 상황에서 같은 판정'을 보장하는 구조 개선. 5단계 중 1단계 완료", C.warn],
                ["확산하면", "단말이 늘수록 감지 범위가 인프라 비용 없이 넓어진다. n대 → 상호 감지쌍 n(n-1)/2", C.safe]];
  let y = 3.78;
  rows.forEach(([k, v, col]) => {
    card(s, M, y, W - M * 2, 0.72, { fill: C.alt, shadow: { opacity: 0.22 } });
    sq(s, M + 0.34, y + 0.3, col);
    txt(s, k, { x: M + 0.58, y, w: 2.1, h: 0.72, fontSize: 15, bold: true, color: col, valign: "middle" });
    txt(s, v, { x: M + 2.86, y, w: W - M * 2 - 3.2, h: 0.72, fontSize: 14, color: C.t2, valign: "middle" });
    y += 0.8;
  });
  footer(s);
  s.addNotes("네 개의 숫자로 현황을 고정한다. 70회 릴리스는 실험이 아니라 이미 현장에서 도는 물건이라는 뜻이고, 1/5 는 지금 하는 일이 기능 추가가 아니라 신뢰성 확보라는 뜻이다.");
}

/* ══════ 3. 챕터 01 ══════ */
{
  const s = slide();
  s.addImage({ path: A("wide_problem"), x: 0, y: 0, w: W, h: H });
  txt(s, "01", { x: M, y: 2.0, w: 3, h: 0.9, fontSize: 64, bold: true, color: C.accent, fontFace: HEAD });
  txt(s, "현장과 제품", { x: M, y: 3.0, w: 6, h: 0.4, fontSize: 14, bold: true, color: C.t2, charSpacing: 4 });
  txt(s, "지게차는 3m 렉 사이에서\n사람을 보지 못한다.", { x: M, y: 3.62, w: 8.6, h: 1.6, fontSize: 38, bold: true, color: C.t1, fontFace: HEAD, lineSpacing: 54 });
  txt(s, "사각지대 · 전파 흡수 · 단독 작업 · 혼재 동선", { x: M, y: 5.4, w: 8, h: 0.34, fontSize: 15, color: C.t3 });
  s.addNotes("챕터 전환. 여기서부터 현장 조건과 제품이 어떻게 대응하는지를 다룬다.");
}

/* ══════ 4. 현장의 사각지대 ══════ */
{
  const s = slide();
  head(s, "문제", "현장의 사각지대", "왜 사람의 주의력만으로는 부족한가");
  const items = [["막힌 시야", "3m 높이 철제 렉 사이 통로.\n교차 · 곡선 구간에서 서로를 볼 수 없다.", C.danger],
                 ["전파 · 소음", "적재된 생수 파렛트가 2.4GHz 흡수벽.\n장비 소음 속에서 경적 인지도 늦다.", C.warn],
                 ["단독 작업", "렉 사이 혼자 작업하는 구간이 있다.\n이탈 감지가 안전상 필수다.", C.danger],
                 ["혼재 동선", "지게차 · 리치 · EPJ · 보행자가\n같은 통로를 공유한다.", C.accent]];
  const cw = 2.98, gx = 0.16;
  items.forEach(([k, v, col], i) => {
    const x = M + i * (cw + gx);
    card(s, x, 2.12, cw, 2.5);
    sq(s, x + 0.34, 2.44, col, 0.16);
    txt(s, k, { x: x + 0.34, y: 2.74, w: cw - 0.68, h: 0.4, fontSize: 20, bold: true, color: C.t1, fontFace: HEAD });
    txt(s, v, { x: x + 0.34, y: 3.24, w: cw - 0.68, h: 1.1, fontSize: 13, color: C.t2, lineSpacing: 20 });
  });
  strip(s, 5.0, "사각지대를 사람의 주의력이 아니라 시스템이 메운다.", C.accent, 1.0);
  footer(s);
  s.addNotes("네 가지는 추정이 아니라 현장 조건이다. 특히 생수 파렛트의 전파 흡수는 신호 기반 거리 추정의 난이도를 직접 끌어올리는 요인이고, 뒤에 나올 3단 신호 정제와 사업장별 보정이 여기서 나왔다.");
}

/* ══════ 5. 해결 방식 ══════ */
{
  const s = slide();
  head(s, "해결 방식", "기기끼리 서로를 본다", "고정 설비를 까는 대신, 움직이는 주체가 서로를 감지한다");
  const cw = 5.86;
  card(s, M, 2.12, cw, 3.1, { fill: C.alt });
  txt(s, "일반적인 구역 감지", { x: M + 0.36, y: 2.36, w: cw - 0.72, h: 0.36, fontSize: 17, bold: true, color: C.t3 });
  ["앵커 · 게이트웨이 설치와 배선 공사", "서버 · 네트워크가 끊기면 감지도 정지", "'구역 진입' 단위 — 누가 가까운지는 모름", "라인을 바꾸면 다시 공사"].forEach((t, i) => {
    sq(s, M + 0.36, 2.98 + i * 0.54 + 0.06, "5A6880", 0.11);
    txt(s, t, { x: M + 0.62, y: 2.98 + i * 0.54, w: cw - 1.0, h: 0.34, fontSize: 14, color: C.t3, valign: "middle" });
  });
  const x2 = M + cw + 0.2;
  card(s, x2, 2.12, cw, 3.1, { fill: C.tintA, line: C.accent, lineW: 1.5 });
  txt(s, "SafeAlert", { x: x2 + 0.36, y: 2.36, w: cw - 0.72, h: 0.36, fontSize: 17, bold: true, color: C.accent });
  ["설치는 앱 하나 — 고정 설비 · 배선 없음", "기기 간 직접 통신 — 네트워크 없이 동작", "개체 대 개체 거리 — 역할별 기준 적용", "확장 = 설치. 재공사 없음"].forEach((t, i) => {
    sq(s, x2 + 0.36, 2.98 + i * 0.54 + 0.06, C.accent, 0.11);
    txt(s, t, { x: x2 + 0.62, y: 2.98 + i * 0.54, w: cw - 1.0, h: 0.34, fontSize: 14, bold: true, color: C.t1, valign: "middle" });
  });
  const ch = [["ic_antenna", "인프라 투자 0", C.safe], ["ic_share_out", "네트워크 없이 경보 동작", C.accent], ["ic_receive_in", "구버전 기기와 100% 호환", C.warn]];
  let cx = M;
  ch.forEach(([ic, t, col]) => {
    const w = 3.98;
    card(s, cx, 5.4, w, 0.86, { fill: C.alt, shadow: { opacity: 0.2 } });
    iconTile(s, cx + 0.24, 5.55, 0.56, ic, C.surface);
    txt(s, t, { x: cx + 0.94, y: 5.4, w: w - 1.2, h: 0.86, fontSize: 14, bold: true, color: col, valign: "middle" });
    cx += w + 0.13;
  });
  footer(s);
  s.addNotes("핵심 차별점은 고정 설비가 없다는 것. 클라우드는 설정 공유와 앱 자동 업데이트에만 쓰이고 경보 판정은 단말 안에서 끝난다. 통신이 끊긴 창고에서도 경보는 동작한다.");
}

/* ══════ 6. 제품 화면 ══════ */
{
  const s = slide();
  head(s, "제품", "작업자가 보는 화면", "역할을 고르면 나머지는 백그라운드에서 동작한다");
  const px = M + 0.3, py = 2.1, pw = 2.62, ph = 4.5;
  s.addShape(pres.ShapeType.roundRect, { x: px - 0.07, y: py - 0.07, w: pw + 0.14, h: ph + 0.14, fill: { color: "000000" }, rectRadius: 0.16, shadow: sh({ blur: 24, opacity: 0.55 }) });
  s.addImage({ path: A("phone_bg"), x: px, y: py, w: pw, h: ph });
  s.addShape(pres.ShapeType.ellipse, { x: px + 0.1, y: py + 0.17, w: 0.08, h: 0.08, fill: { color: C.accent } });
  txt(s, "SafeAlert", { x: px + 0.2, y: py + 0.1, w: 1.4, h: 0.22, fontSize: 9, bold: true, color: C.t1, valign: "middle" });
  const cy = py + 0.4, cw2 = pw - 0.24;
  s.addShape(pres.ShapeType.roundRect, { x: px + 0.12, y: cy, w: cw2, h: 3.16, fill: { color: C.veil }, rectRadius: 0.08, line: { color: C.hair, width: 0.75 } });
  s.addShape(pres.ShapeType.ellipse, { x: px + 0.24, y: cy + 0.15, w: 0.08, h: 0.08, fill: { color: C.safe } });
  txt(s, "백그라운드 실행 중", { x: px + 0.38, y: cy + 0.07, w: 1.6, h: 0.24, fontSize: 8.5, bold: true, color: C.t1, valign: "middle" });
  txt(s, "02:14:06", { x: px + 0.12, y: cy + 0.07, w: cw2 - 0.16, h: 0.24, fontSize: 8, color: C.t3, align: "right", valign: "middle" });
  sq(s, px + 0.24, cy + 0.44, C.dim, 0.09);
  txt(s, "내 장비 (Local)", { x: px + 0.4, y: cy + 0.39, w: 1.6, h: 0.22, fontSize: 8, bold: true, color: C.dim, valign: "middle" });
  s.addImage({ path: A("ic_forklift"), x: px + 0.24, y: cy + 0.68, w: 0.4, h: 0.4 });
  txt(s, "지게차", { x: px + 0.72, y: cy + 0.7, w: 1.1, h: 0.22, fontSize: 10.5, bold: true, color: C.t1, valign: "middle" });
  txt(s, "FORK-07", { x: px + 0.72, y: cy + 0.9, w: 1.1, h: 0.2, fontSize: 8, color: C.t3, valign: "middle" });
  chip(s, px + cw2 - 0.52, cy + 0.74, 0.56, 0.26, "TEST", C.alt, C.accent, 7.5, C.stroke);
  txt(s, "상태: 전진 · 주행  ·  6 km/h", { x: px + 0.24, y: cy + 1.18, w: cw2 - 0.24, h: 0.22, fontSize: 8, color: C.t2, valign: "middle" });
  sq(s, px + 0.24, cy + 1.55, C.danger, 0.09);
  txt(s, "수신 타겟 (Target)", { x: px + 0.4, y: cy + 1.5, w: 1.7, h: 0.22, fontSize: 8, bold: true, color: C.danger, valign: "middle" });
  [["보행자 A", "-56 dBm  ·  3.4 m", "위험", C.danger], ["보행자 B", "-71 dBm  ·  9.8 m", "경고", C.warn], ["보행자 C", "-84 dBm  ·  18 m", "안전", C.safe]].forEach(([n, d, lv, col], i) => {
    const ry = cy + 1.82 + i * 0.44;
    s.addShape(pres.ShapeType.roundRect, { x: px + 0.2, y: ry, w: cw2 - 0.16, h: 0.38, fill: { color: C.surface }, rectRadius: 0.05 });
    sq(s, px + 0.28, ry + 0.14, col, 0.09);
    txt(s, n, { x: px + 0.44, y: ry + 0.02, w: 0.9, h: 0.18, fontSize: 8, bold: true, color: C.t1, valign: "middle" });
    txt(s, d, { x: px + 0.44, y: ry + 0.19, w: 1.2, h: 0.17, fontSize: 7, color: C.t3, valign: "middle" });
    chip(s, px + cw2 - 0.52, ry + 0.08, 0.52, 0.23, lv, col, C.onAcc, 7.5);
  });
  chip(s, px + 0.12, py + 3.74, cw2 / 2 - 0.04, 0.36, "역할 전환", C.alt, C.t2, 8.5, C.stroke);
  chip(s, px + 0.2 + cw2 / 2, py + 3.74, cw2 / 2 - 0.04, 0.36, "중지", C.danger, C.onAcc, 8.5);
  txt(s, "메인 화면 (v1.1.70)", { x: px - 0.07, y: py + ph + 0.14, w: pw + 0.14, h: 0.26, fontSize: 10, color: C.t3, align: "center" });

  const notes = [["역할 선택이 전부", "지게차와 보행자 중 하나만 고르면 그 조합의 경고 · 위험 거리가 자동 적용된다.", C.accent],
                 ["감지 목록 실시간", "주변 기기의 신호 세기 · 추정 거리 · 등급을 보여준다. 등급 색은 앱 전체에서 같은 의미다.", C.warn],
                 ["화면을 꺼도 동작", "상시 스캔한다. 감지가 멈추면 그 사실 자체를 알림으로 띄운다 — 조용한 실패가 없다.", C.safe],
                 ["경보는 3중 출력", "소리 · 진동 · 화면 가장자리 사이드바가 동시에 뜬다. 소리 하나에 의존하지 않는다.", C.danger]];
  const nx = px + pw + 0.9, nw = W - M - nx;
  let ny = 2.12;
  notes.forEach(([k, v, col]) => {
    card(s, nx, ny, nw, 1.06, { fill: C.alt, shadow: { opacity: 0.22 } });
    sq(s, nx + 0.34, ny + 0.26, col);
    txt(s, k, { x: nx + 0.6, y: ny + 0.16, w: nw - 0.94, h: 0.34, fontSize: 17, bold: true, color: C.t1, fontFace: HEAD });
    txt(s, v, { x: nx + 0.6, y: ny + 0.56, w: nw - 0.94, h: 0.4, fontSize: 13, color: C.t2 });
    ny += 1.14;
  });
  footer(s);
  s.addNotes("화면은 v1.1.70 메인 화면 구성 그대로다. 강조점은 작업자가 조작할 것이 거의 없다는 것 — 역할만 고르면 나머지는 백그라운드에서 돈다. 현장 교육 부담이 작다.");
}

/* ══════ 7. 핵심 기능 ══════ */
{
  const s = slide();
  head(s, "기능", "핵심 기능", "v1.1.70 에서 실제로 동작하는 기능");
  const GW = (W - M * 2 - 0.17) / 3;            // 6열 그리드의 2열 폭
  const cell = (x, y, w, h, ic, k, v, col, wide) => {
    card(s, x, y, w, h);
    iconTile(s, x + 0.3, y + 0.26, 0.62, ic);
    sq(s, x + 1.08, y + 0.46, col, 0.16);
    txt(s, k, { x: x + 0.3, y: y + 1.0, w: w - 0.6, h: 0.32, fontSize: 17, bold: true, color: C.t1, fontFace: HEAD });
    txt(s, v, { x: x + 0.3, y: y + 1.36, w: w - 0.6, h: wide ? 0.4 : 0.62, fontSize: 12.5, color: C.t2, lineSpacing: 17 });
  };
  const w2 = GW, w4 = GW * 2 + 0.17, ch = 1.9, g = 0.17;
  cell(M, 2.1, w4, ch, "ic_forklift", "역할 기반 차등 반경",
    "지게차와 보행자 조합별로 경고와 위험 거리를 다르게 적용한다. 무거운 장비일수록 넓게 잡는다.", C.warn, true);
  cell(M + w4 + g, 2.1, w2, ch, "ic_bell", "3단계 경보",
    "안전 / 경고 / 위험. 등급에 따라 출력이 달라진다.", C.danger);
  cell(M, 2.1 + ch + g, w2, ch, "ic_receive_in", "후진 · 하역 특수경보",
    "상대가 후진 · 고소작업 중이면 즉시 최고 등급.", C.danger);
  cell(M + w2 + g, 2.1 + ch + g, w4, ch, "ic_share_out", "양방향 협력 알림",
    "한쪽이 먼저 감지하면 상대 기기도 함께 울린다. 한쪽 신호가 약해서 놓칠 상황을 상대가 메운다.", C.safe, true);
  const yy = 2.1 + (ch + g) * 2;
  card(s, M, yy, W - M * 2, 1.24);
  iconTile(s, M + 0.3, yy + 0.31, 0.62, "ic_signal_bars");
  sq(s, M + 1.08, yy + 0.5, C.accent, 0.16);
  txt(s, "모션 · 회전 인식", { x: M + 1.34, y: yy + 0.24, w: 4.0, h: 0.34, fontSize: 17, bold: true, color: C.t1, fontFace: HEAD });
  txt(s, "단말 센서로 정지 · 주행 · 급정거와 좌우 회전을 판별해 판정에 반영한다. 상대가 회전해 들어오는 상황을 거리만으로 판단하지 않는다.",
    { x: M + 1.34, y: yy + 0.62, w: W - M * 2 - 1.7, h: 0.44, fontSize: 12.5, color: C.t2 });
  strip(s, 6.32, "클라우드가 죽어도, UWB 가 없어도 블루투스 근접 경보는 그대로 동작한다.", C.accent, 0.62);
  footer(s);
  s.addNotes("여섯 중 넷(양방향 협력 알림, 후진·하역 특수경보, 모션·회전 인식, 세이프존)은 시판 제품에서 잘 보이지 않는 항목이다. 전부 현장 요구에서 나왔다.");
}

/* ══════ 8. 판정 반경 ══════ */
{
  const s = slide();
  head(s, "판정 기준", "역할 조합별 판정 반경", "아래 도식은 실제 비례 축척이다. 지게차 조합의 경고 반경이 보행자끼리의 세 배다");
  // 축척 1m = 0.115in. 15m=1.725 / 8m=0.92 / 5m=0.575 / 3m=0.345 (반지름)
  const K = 0.115, cx = M + 2.5, cy = 3.9;
  const ring = (m, color, dash, w) => s.addShape(pres.ShapeType.ellipse, {
    x: cx - m * K, y: cy - m * K, w: m * K * 2, h: m * K * 2,
    fill: { color: C.bg }, line: Object.assign({ color, width: w || 1.5 }, dash ? { dashType: "dash" } : {}),
  });
  ring(15, C.warn, true);
  ring(8, C.danger, false, 1.75);
  ring(5, C.warn, true, 1.1);
  ring(3, C.danger, false, 1.25);
  s.addShape(pres.ShapeType.ellipse, { x: cx - 0.08, y: cy - 0.08, w: 0.16, h: 0.16, fill: { color: C.accent } });
  s.addShape(pres.ShapeType.line, { x: cx, y: cy, w: 15 * K + 0.24, h: 0, line: { color: C.hair, width: 1 } });
  // 눈금은 위아래로 엇갈려 배치한다. 3m·5m 이 축 위에서 겹치기 때문이다.
  [[3, "3m", 1], [5, "5m", -1], [8, "8m", 1], [15, "15m", -1]].forEach(([m, t, dir]) => {
    s.addShape(pres.ShapeType.line, { x: cx + m * K, y: cy - 0.05, w: 0, h: 0.1, line: { color: C.stroke, width: 1 } });
    txt(s, t, { x: cx + m * K - 0.3, y: dir > 0 ? cy + 0.1 : cy - 0.36, w: 0.6, h: 0.26,
      fontSize: 10, color: C.t3, align: "center", fontFace: HEAD });
  });
  iconTile(s, cx - 0.3, cy + 0.52, 0.6, "ic_walker", C.surface);
  iconTile(s, cx + 15 * K - 0.3, cy - 1.24, 0.6, "ic_forklift", C.surface);

  const bx = M + 5.6, bw = W - M - bx;
  const pair = (y, kicker, sub2, wm, dm) => {
    txt(s, kicker, { x: bx, y, w: bw, h: 0.28, fontSize: 11, bold: true, color: C.t3, charSpacing: 2 });
    txt(s, sub2, { x: bx, y: y + 0.3, w: bw, h: 0.28, fontSize: 12, color: C.t3 });
    txt(s, wm, { x: bx, y: y + 0.64, w: 1.9, h: 0.62, fontSize: 38, bold: true, color: C.warn, fontFace: HEAD });
    txt(s, "경고", { x: bx, y: y + 1.24, w: 1.9, h: 0.26, fontSize: 12, color: C.t3 });
    txt(s, dm, { x: bx + 2.0, y: y + 0.64, w: 1.9, h: 0.62, fontSize: 38, bold: true, color: C.danger, fontFace: HEAD });
    txt(s, "위험", { x: bx + 2.0, y: y + 1.24, w: 1.9, h: 0.26, fontSize: 12, color: C.t3 });
  };
  pair(2.18, "지게차가 포함된 조합", "지게차 ↔ 보행자 · 지게차", "15m", "8m");
  s.addShape(pres.ShapeType.line, { x: bx, y: 3.78, w: bw, h: 0, line: { color: C.hair, width: 1 } });
  pair(3.96, "보행자끼리", "보행자 ↔ 보행자", "5m", "3m");
  card(s, M, 5.72, W - M * 2, 0.86, { fill: C.surface });
  txt(s, "현장에서 확정된 값이며 사업장별로 조정할 수 있다. 지금 개선하는 것은 값이 아니라, 값대로 정확히 동작하게 만드는 일이다.",
    { x: M + 0.36, y: 5.72, w: W - M * 2 - 0.72, h: 0.86, fontSize: 13, color: C.t2, valign: "middle" });
  footer(s);
  s.addNotes("도식은 실제 비례 축척이다. 지게차 조합의 경고 반경(15m)이 보행자끼리의 경고 반경(5m)보다 세 배 넓다는 것이 한눈에 보인다. 값 자체는 현장에서 확인된 것이고, 개선 목표는 값대로 동작하게 만드는 것이다.");
}

/* ══════ 8.5 설정 ══════ */
{
  const s = slide();
  head(s, "운영", "현장마다 다른 조건을 설정으로 맞춘다", "앱 설정 화면에 실제로 들어 있는 항목이다. 센터마다 렉 높이도 적재물도 다르다");
  const groups = [
    ["판정 반경", "역할쌍별 경고와 위험 거리", [["지게차 쌍 경고", "15 m"], ["지게차 쌍 위험", "8 m"], ["그 외 쌍 경고", "5 m"], ["그 외 쌍 위험", "3 m"]],
     "현재 값이며 사업장별로 조정한다", C.warn],
    ["경보 기본", "감도와 출력", [["필터 강도", "3단"], ["경고 신호세기", "조정 가능"], ["위험 신호세기", "조정 가능"], ["경보 볼륨", "0~100%"]],
     "소음 라인은 볼륨을, 반사 구간은 필터를 올린다", C.danger],
    ["사업장 프로파일", "한 번 맞추면 공유된다", [["사업장 코드", "보정 프로파일"], ["에코편차 자동보정", "상호 대칭화"], ["비콘 수신 강도", "0~100%"], ["조기경보 오프셋", "역할쌍별 dB"]],
     "같은 코드의 단말이 보정값을 함께 쓴다", C.accent],
    ["보조 수단", "켜고 끌 수 있다", [["UWB 정밀 거리", "선택"], ["거리 표시 방식", "3종"], ["스캔 · 광고", "1000 / 200ms"], ["진동 · 경보음", "개별 설정"]],
     "전부 꺼도 블루투스 경보는 동작한다", C.safe],
  ];
  const cw = (W - M * 2 - 0.17 * 3) / 4;
  groups.forEach(([kicker, title, rows, note, col], i) => {
    const x = M + i * (cw + 0.17);
    card(s, x, 2.12, cw, 3.72);
    txt(s, kicker, { x: x + 0.28, y: 2.32, w: cw - 0.56, h: 0.26, fontSize: 10.5, bold: true, color: col, charSpacing: 2 });
    txt(s, title, { x: x + 0.28, y: 2.62, w: cw - 0.56, h: 0.56, fontSize: 15, bold: true, color: C.t1, fontFace: HEAD, lineSpacing: 20 });
    rows.forEach(([k, v], j) => {
      const ry = 3.3 + j * 0.5;
      if (j) s.addShape(pres.ShapeType.line, { x: x + 0.28, y: ry - 0.06, w: cw - 0.56, h: 0, line: { color: C.hair, width: 1 } });
      txt(s, k, { x: x + 0.28, y: ry, w: cw - 0.56, h: 0.36, fontSize: 11.5, color: C.t3, valign: "middle" });
      txt(s, v, { x: x + 0.28, y: ry, w: cw - 0.56, h: 0.36, fontSize: 11.5, bold: true, color: C.t1, valign: "middle", align: "right" });
    });
    txt(s, note, { x: x + 0.28, y: 5.36, w: cw - 0.56, h: 0.4, fontSize: 11, color: C.t3, lineSpacing: 15 });
  });
  strip(s, 6.04, "코드를 고치지 않고 현장에서 맞춘다. 세부 항목은 개발자 설정에 잠겨 있어 실수로 바뀌지 않는다.", C.accent, 0.66);
  footer(s);
  s.addNotes("확산 관점에서 중요한 장이다. 센터마다 조건이 다른데 코드를 고쳐야 한다면 확산이 곧 개발 부하가 된다. 사업장 코드로 보정 프로파일을 공유하는 구조라 한 곳에서 맞춘 값을 같은 코드의 단말이 함께 쓴다.");
}

/* ══════ 9. 세이프존 · 오경보 억제 ══════ */
{
  const s = slide();
  head(s, "오경보 대책", "세이프존 · 자동 뮤트", "울릴 필요 없는 곳에서는 울리지 않는다 — 안전 시스템이 실패하는 흔한 경로는 고장이 아니라 '작업자가 꺼버리는 것'이다");
  // 좌: 도식
  const dx = M, dw = 5.5;
  card(s, dx, 2.16, dw, 3.34, { fill: C.alt });
  s.addShape(pres.ShapeType.roundRect, { x: dx + 0.34, y: 2.46, w: dw - 0.68, h: 1.6, fill: { color: C.tintS }, line: { color: C.safe, width: 1.5, dashType: "dash" }, rectRadius: 0.1 });
  txt(s, "세이프존 (무음구역)", { x: dx + 0.56, y: 2.58, w: 3.0, h: 0.28, fontSize: 12, bold: true, color: C.safe });
  iconTile(s, dx + 0.56, 2.92, 0.6, "ic_antenna", C.surface);
  txt(s, "존 비콘", { x: dx + 0.46, y: 3.56, w: 0.8, h: 0.24, fontSize: 10, color: C.t3, align: "center" });
  s.addShape(pres.ShapeType.roundRect, { x: dx + 1.56, y: 2.9, w: 3.06, h: 0.94, fill: { color: C.surface }, rectRadius: 0.08 });
  txt(s, "내 단말 — 소리 · 진동 정지", { x: dx + 1.76, y: 3.0, w: 2.7, h: 0.26, fontSize: 12, bold: true, color: C.t1, valign: "middle" });
  txt(s, "주변 감지 차단 + 상대에게 '안전' 선언", { x: dx + 1.76, y: 3.3, w: 2.7, h: 0.44, fontSize: 11, color: C.t3, lineSpacing: 15 });
  s.addShape(pres.ShapeType.line, { x: dx + 1.3, y: 3.36, w: 0.2, h: 0, line: { color: C.safe, width: 2, endArrowType: "triangle" } });
  txt(s, "휴게실 · 충전 구역 · 집품 스테이션처럼 장비가 다니지 않는 구역에 비콘을 둔다.", { x: dx + 0.34, y: 4.18, w: dw - 0.68, h: 0.26, fontSize: 11, italic: true, color: C.t3 });
  iconTile(s, dx + 0.56, 4.58, 0.62, "ic_forklift", C.surface);
  txt(s, "밖의 지게차는 나를 '안전'으로 인식한다 — 서로 울리지 않는다.", { x: dx + 1.4, y: 4.58, w: dw - 1.86, h: 0.62, fontSize: 13, color: C.t2, valign: "middle", lineSpacing: 18 });
  // 우: 메커니즘 3
  const nx = dx + dw + 0.24, nw = W - M - nx;
  const mech = [["존 진입 시 3중 억제", "내 소리 · 진동 정지 · 주변 감지 차단 · 상대에게 안전 선언까지 한 번에. 진입 기준 세기도 설정할 수 있다.", C.safe],
                ["5초 체류 자동 뮤트", "같은 등급이 계속되면 그 기기의 소리만 멈춘다. 화면 표시와 기록은 유지되고, 등급이 오르면 즉시 다시 울린다.", C.warn],
                ["구버전과도 호환", "존 비트를 모르는 구버전 단말도 '안전' 등급은 해석한다. 신버전이 구버전을 무력화하지 않는다.", C.accent]];
  let y = 2.16;
  mech.forEach(([k, v, col]) => {
    card(s, nx, y, nw, 1.06, { fill: C.surface });
    sq(s, nx + 0.34, y + 0.26, col);
    txt(s, k, { x: nx + 0.6, y: y + 0.16, w: nw - 0.94, h: 0.34, fontSize: 17, bold: true, color: C.t1, fontFace: HEAD });
    txt(s, v, { x: nx + 0.6, y: y + 0.56, w: nw - 0.94, h: 0.42, fontSize: 12.5, color: C.t2, lineSpacing: 17 });
    y += 1.14;
  });
  strip(s, 5.66, "오경보가 잦으면 작업자가 시스템을 끈다. 끄지 않게 만드는 것이 안전의 전제다.", C.safe, 0.82);
  footer(s);
  s.addNotes("세이프존은 v1.1.62 에 들어가 v1.1.65~66 에서 '전면 억제'로 강화됐고, 체류 자동 뮤트는 v1.1.61 이다. 두 기능 모두 알람 피로(alarm fatigue) 대책이며, 확산 국면에서 가장 중요한 수용성 요소다.");
}

/* ══════ 9.5 앱 화면 2종 ══════ */
{
  const s = slide();
  head(s, "화면", "동작 화면과 설정 화면", null);
  const cw = 4.3, gap = 0.55, cx0 = (W - cw * 2 - gap) / 2, cy0 = 1.8, ch = 5.1;
  const ph = 4.25, pw = ph * (300 / 624);   // render_screens.js 가 굽는 목업의 실제 종횡비
  [["동작 화면", "screen_running"], ["설정 화면", "screen_settings"]].forEach(([label, img], i) => {
    const x = cx0 + i * (cw + gap);
    card(s, x, cy0, cw, ch, { fill: C.alt, shadow: { opacity: 0.24 } });
    const ix = x + (cw - pw) / 2;
    s.addShape(pres.ShapeType.roundRect, { x: ix - 0.05, y: 2.0, w: pw + 0.1, h: ph + 0.1, fill: { color: "000000" }, rectRadius: 0.15, shadow: sh({ blur: 26, opacity: 0.6 }) });
    s.addImage({ path: A(img), x: ix, y: 2.05, w: pw, h: ph });
    txt(s, label, { x, y: cy0 + ch - 0.62, w: cw, h: 0.32, fontSize: 14, bold: true, color: C.t2, align: "center", valign: "middle" });
  });
  footer(s);
  s.addNotes("설명 없이 화면만 보여주는 슬라이드다. 왼쪽은 위험 판정이 뜬 순간의 메인 화면, 오른쪽은 현장별 조건을 맞추는 BLE 설정 화면이다. 두 이미지는 web/render_screens.js 로 앱 화면 목업에서 굽는다.");
}

/* ══════ 9.6 시뮬레이터 데모 ══════ */
{
  const s = slide();
  head(s, "시연", "판정 시뮬레이터", "재생하면 TTC 선발령 · 협력 격상 · 후진 특수경보 · 세이프존 억제가 차례로 재현된다");
  const vw = 8.8, vh = vw * (948 / 1686), vx = (W - vw) / 2, vy = 1.95;   // record_demo.js 산출물의 16:9
  s.addShape(pres.ShapeType.roundRect, { x: vx - 0.06, y: vy - 0.06, w: vw + 0.12, h: vh + 0.12, fill: { color: "000000" }, rectRadius: 0.14, shadow: sh({ blur: 26, opacity: 0.55 }) });
  // 슬라이드쇼에서 재생되는 실제 동영상이다. 표지는 TTC 선제경보가 뜬 프레임(5초 지점).
  s.addMedia({ type: "video", path: path.join(__dirname, "web", "safealert-sim.mp4"), cover: POSTER, x: vx, y: vy, w: vw, h: vh });
  footer(s);
  txt(s, "직접 조작:  web/safealert-simulator.html", { x: W - M - 5.5, y: H - 0.44, w: 4.5, h: 0.26, fontSize: 9, color: C.t3, align: "right", valign: "middle" });
  s.addNotes("영상은 웹 시뮬레이터를 그대로 녹화한 것이다(26초, 무음). 파워포인트 슬라이드쇼에서 재생 버튼을 누르면 재생된다 — 인터넷 연결이 없어도 파일 안에 들어 있다. 실시간 조작이 필요하면 옆의 웹 시뮬레이터를 브라우저로 연다.");
}

/* ══════ 10. 챕터 02 ══════ */
{
  const s = slide(C.accent);
  txt(s, "02", { x: M, y: 2.0, w: 3, h: 0.9, fontSize: 64, bold: true, color: "0B1220", fontFace: HEAD });
  txt(s, "어떻게 동작하는가", { x: M, y: 3.0, w: 6, h: 0.4, fontSize: 14, bold: true, color: "134256", charSpacing: 4 });
  txt(s, "판정이 흔들리면\n아무도 그 경보를 믿지 않는다.", { x: M, y: 3.62, w: 9.6, h: 1.6, fontSize: 38, bold: true, color: "071019", fontFace: HEAD, lineSpacing: 54 });
  txt(s, "감지 파이프라인 · 프로토콜 · 시스템 구조 · 품질 게이트", { x: M, y: 5.4, w: 9, h: 0.34, fontSize: 15, color: "134256" });
  footer(s, true);
  s.addNotes("챕터 전환. 기술 파트로 들어간다. 경영진에게는 세부 구현이 아니라 '왜 신뢰할 수 있는가'의 근거로 제시한다.");
}

/* ══════ 11. 동작 원리 ══════ */
{
  const s = slide();
  head(s, "동작 원리", "감지에서 경보까지", "신호 한 번이 경보가 되기까지 거치는 5단계 (회색 글씨는 실제 구성 요소)");
  const steps = [["01", "광고 · 스캔", "모든 기기가 송신자이자 수신자.\n1초 주기 연속 스캔.", "BleAdvertiser / BleScanner", C.accent],
                 ["02", "신호 정제 3단", "튐값 제거 → 이상치 차단 →\n평활화. 약 120ms 마다 갱신.", "MedianFilter → RssiPreFilter\n→ KalmanFilter", C.accent],
                 ["03", "거리 · 접근 추정", "역할쌍 보정, 상호 신호 대칭화,\nUWB 정밀 거리로 보완.", "에코 RSSI · UwbRanger", C.accent],
                 ["04", "경보 판정", "접근 속도와 상대 상태를 보고\n등급을 올리거나 내린다.", "BleService 경보 상태머신", C.warn],
                 ["05", "경보 출력", "소리 · 진동 · 화면 사이드바를\n동시에 낸다.", "AlertSoundPlayer ·\nOverlayManager", C.danger]];
  const cw = 2.3, gx = 0.14;
  steps.forEach(([n, k, v, comp, col], i) => {
    const x = M + i * (cw + gx), hi = i === 3;
    card(s, x, 2.14, cw, 3.2, { fill: hi ? C.tintA : C.alt, line: hi ? C.warn : null });
    txt(s, n, { x: x + 0.26, y: 2.32, w: 1, h: 0.4, fontSize: 22, bold: true, color: col, fontFace: HEAD });
    txt(s, k, { x: x + 0.26, y: 2.78, w: cw - 0.52, h: 0.6, fontSize: 15.5, bold: true, color: C.t1, lineSpacing: 20, fontFace: HEAD });
    txt(s, v, { x: x + 0.26, y: 3.44, w: cw - 0.52, h: 1.0, fontSize: 12, color: C.t2, lineSpacing: 17 });
    txt(s, comp, { x: x + 0.26, y: 4.58, w: cw - 0.52, h: 0.6, fontSize: 9.5, color: "6B7A90", lineSpacing: 13 });
    if (i < 4) s.addShape(pres.ShapeType.line, { x: x + cw + 0.01, y: 3.74, w: gx - 0.02, h: 0, line: { color: C.accent, width: 2, endArrowType: "triangle" } });
  });
  const tm = [["연속 스캔", "1초"], ["판정 갱신", "120ms"], ["화면 갱신", "800ms"], ["경보 반응", "즉시"]];
  let tx = M;
  tm.forEach(([k, v]) => {
    const w = 2.94;
    card(s, tx, 5.52, w, 0.8, { fill: C.alt, shadow: { opacity: 0.2 } });
    txt(s, k, { x: tx + 0.3, y: 5.52, w: w - 0.6, h: 0.8, fontSize: 12, color: C.t3, valign: "middle" });
    txt(s, v, { x: tx + 0.3, y: 5.52, w: w - 0.66, h: 0.8, fontSize: 17, bold: true, color: C.t1, valign: "middle", align: "right", fontFace: HEAD });
    tx += w + 0.13;
  });
  footer(s);
  s.addNotes("3단 신호 정제가 이 시스템의 심장이다. 블루투스 신호 세기는 그대로 쓰면 초당 수 dB 씩 튀기 때문에 정제 없이는 경보가 깜빡인다. 04단계가 현재 개선 작업의 주 대상이다.");
}

/* ══════ 12. 1바이트 프로토콜 ══════ */
{
  const s = slide();
  head(s, "프로토콜", "1바이트에 모든 것을 담는다", "블루투스 광고 패킷 단 1바이트에 역할 · 상태 · 회전 · 위험도를 싣는다");
  const fields = [["CAT", "역할", ["00  보행자", "01  EPJ (UI 비노출)", "10  지게차 · 리치", "11  예약"], C.accent],
                  ["STATE", "동적 상태", ["00  정지 · 일반", "01  전진 · 주행", "10  후진  ▲", "11  하역 · 작업  ▲"], C.danger],
                  ["TURN", "회전 방향", ["00  직진", "01  좌회전", "10  우회전", "11  예약"], C.dim],
                  ["RISK", "자기 위험도", ["00  안전", "01  경고 감지", "10  위험 감지", "11  예약"], C.safe]];
  const cw = 2.94, gx = 0.16;
  fields.forEach(([f, ko, vals, col], i) => {
    const x = M + i * (cw + gx);
    [0, 1].forEach((b) => {
      const bx = x + b * (cw / 2 + 0.05);
      s.addShape(pres.ShapeType.roundRect, { x: bx, y: 2.14, w: cw / 2 - 0.05, h: 0.62, fill: { color: C.alt }, line: { color: col, width: 1.25 }, rectRadius: 0.05 });
      txt(s, "bit " + (7 - i * 2 - b), { x: bx, y: 2.14, w: cw / 2 - 0.05, h: 0.62, fontSize: 10.5, color: C.t3, align: "center", valign: "middle" });
    });
    txt(s, f, { x, y: 2.88, w: cw, h: 0.4, fontSize: 20, bold: true, color: col, align: "center", fontFace: HEAD });
    txt(s, ko, { x, y: 3.26, w: cw, h: 0.28, fontSize: 12, color: C.t3, align: "center" });
    card(s, x, 3.62, cw, 1.9, { fill: C.surface });
    vals.forEach((v, j) => txt(s, v, { x: x + 0.28, y: 3.78 + j * 0.43, w: cw - 0.56, h: 0.36, fontSize: 12.5, color: v.indexOf("▲") >= 0 ? C.warn : C.t2, valign: "middle" }));
  });
  txt(s, "▲ 특수경보 트리거 — 상대가 후진 · 하역 중이면 근접 즉시 최고 등급", { x: M, y: 5.62, w: 7.6, h: 0.3, fontSize: 11, color: C.t3 });
  const why = [["왜 1바이트인가", "광고 패킷 용량이 극히 작다. 비트 단위로 눌러 담아야 1초 주기 연속 방송이 가능하다."],
               ["왜 못 바꾸는가", "현장에 배포된 구버전 단말과 통신해야 한다. 배치가 바뀌면 서로를 오인한다."]];
  let y = 6.02;
  why.forEach(([k, v]) => {
    sq(s, M, y + 0.06, C.accent, 0.12);
    txt(s, k, { x: M + 0.26, y, w: 2.4, h: 0.3, fontSize: 12.5, bold: true, color: C.accent, valign: "middle" });
    txt(s, v, { x: M + 2.8, y, w: W - M * 2 - 2.8, h: 0.3, fontSize: 12.5, color: C.t2, valign: "middle" });
    y += 0.36;
  });
  footer(s);
  s.addNotes("경영진에게 전할 함의: 이 레이아웃은 현장 호환성 때문에 바꿀 수 없는 제약이며, 모든 개선은 이 제약 안에서 이뤄진다. 동시에 이 설계 덕분에 신버전 배포가 구버전 단말을 무력화하지 않는다.");
}

/* ══════ 13. 시스템 구조 ══════ */
{
  const s = slide();
  head(s, "코드 구조", "시스템 구조", "27개 파일 11,636줄 · 6개 계층 · 순환 참조 없음 (2026.08 실측)");
  const layers = [["05_ui", "화면 · 역할 선택 · 설정", "5개 파일", "2,521", C.dim],
                  ["03_service", "경보 판정 · 상태 관리 · 생명주기", "3개 파일", "4,133", C.danger],
                  ["02_ble", "블루투스 송수신 · 신호 정제", "7개 파일", "1,712", C.accent],
                  ["06_utils", "보정 · UWB · 비콘 · 오버레이 · 설정", "7개 파일", "3,040", C.warn],
                  ["01_model", "데이터 모델", "2개 파일", "32", C.t3]];
  let y = 2.14, lw = 8.4;
  layers.forEach(([id, ko, f, l, col], i) => {
    const h = 0.8, hi = i === 1;
    card(s, M, y, lw, h, { fill: hi ? C.tintA : C.alt, line: hi ? C.danger : null });
    sq(s, M + 0.3, y + h / 2 - 0.07, col, 0.14);
    txt(s, id, { x: M + 0.64, y, w: 1.8, h, fontSize: 15, bold: true, color: C.t1, valign: "middle", fontFace: HEAD });
    txt(s, ko, { x: M + 2.44, y, w: 4.1, h, fontSize: 13, color: C.t2, valign: "middle" });
    txt(s, f, { x: M + 6.1, y, w: 1.1, h, fontSize: 11.5, color: C.t3, valign: "middle", align: "right" });
    txt(s, l, { x: M + 7.25, y, w: 0.9, h, fontSize: 17, bold: true, color: hi ? C.danger : C.t1, valign: "middle", align: "right", fontFace: HEAD });
    if (i < layers.length - 1) s.addShape(pres.ShapeType.line, { x: M + lw / 2, y: y + h, w: 0, h: 0.14, line: { color: C.hair, width: 1.5, endArrowType: "triangle" } });
    y += h + 0.14;
  });
  const bx = M + lw + 0.24, bw = W - M - bx;
  card(s, bx, 2.14, bw, 2.44, { fill: C.alt });
  txt(s, "04_firebase", { x: bx + 0.32, y: 2.32, w: bw - 0.64, h: 0.32, fontSize: 15, bold: true, color: C.t1, fontFace: HEAD });
  txt(s, "설정 · 프로필 공유", { x: bx + 0.32, y: 2.7, w: bw - 0.64, h: 0.28, fontSize: 12.5, color: C.t2 });
  txt(s, "2개 파일 · 181줄", { x: bx + 0.32, y: 2.98, w: bw - 0.64, h: 0.28, fontSize: 12.5, color: C.t2 });
  txt(s, "경보 판정 경로와 분리되어 있다.\n클라우드가 끊겨도 경보는 동작한다.", { x: bx + 0.32, y: 3.44, w: bw - 0.64, h: 0.9, fontSize: 11.5, color: C.t3, lineSpacing: 18, italic: true });
  card(s, bx, 4.76, bw, 1.72, { fill: C.tintS, line: C.safe });
  txt(s, "테스트", { x: bx + 0.32, y: 4.94, w: bw - 0.64, h: 0.32, fontSize: 15, bold: true, color: C.safe, fontFace: HEAD });
  txt(s, "단위 테스트 17건 · 488줄", { x: bx + 0.32, y: 5.32, w: bw - 0.64, h: 0.28, fontSize: 12.5, color: C.t2 });
  txt(s, "실기기 없이 CI 에서 자동 실행", { x: bx + 0.32, y: 5.6, w: bw - 0.64, h: 0.28, fontSize: 12.5, color: C.t2 });
  txt(s, "2026.08 신설 (로드맵 1단계)", { x: bx + 0.32, y: 6.02, w: bw - 0.64, h: 0.3, fontSize: 11, color: C.t3 });
  footer(s);
  s.addNotes("계층이 위에서 아래로만 의존한다 — 순환 참조가 없다는 것은 한 계층을 고쳐도 다른 계층이 연쇄적으로 깨지지 않는다는 뜻이다. 문제는 03_service 한 파일에 책임이 몰려 있다는 점이며 다음 장에서 다룬다.");
}

/* ══════ 14. 코드 규모 진단 ══════ */
{
  const s = slide();
  head(s, "기술 부채", "개선이 필요한 지점", "파일 하나에 경보 · 거리 · 보정 · 생명주기가 모두 들어 있다");
  s.addText([{ text: "3,899", options: { fontSize: 62, bold: true, color: C.danger, fontFace: HEAD } },
             { text: " 줄", options: { fontSize: 20, bold: true, color: C.danger, fontFace: HEAD } }],
    { x: M, y: 2.32, w: 3.6, h: 1.0, isTextBox: true, margin: 0, valign: "middle" });
  txt(s, "BleService.kt 한 파일", { x: M, y: 3.36, w: 3.6, h: 0.32, fontSize: 16, bold: true, color: C.t1 });
  txt(s, "두 번째로 큰 파일의 4배.\n전체 코드의 3분의 1이 여기 모여 있다.", { x: M, y: 3.74, w: 3.5, h: 0.8, fontSize: 13, color: C.t2, lineSpacing: 19 });
  s.addChart(pres.ChartType.bar, [{ name: "코드 줄 수", labels: ["OverlayManager", "BleAdvertiser", "DevSettings", "UwbRanger", "MainActivity", "BleService"], values: [602, 632, 752, 850, 942, 3899] }], {
    x: M + 3.9, y: 2.0, w: 5.0, h: 3.7, barDir: "bar",
    chartColors: [C.dim, C.dim, C.dim, C.dim, C.dim, C.danger],
    showTitle: true, title: "상위 6개 파일 (전체 27개 파일 11,636줄)", titleFontSize: 11.5, titleColor: C.t3, titleFontFace: BODY,
    showValue: true, dataLabelPosition: "outEnd", dataLabelFontSize: 10.5, dataLabelColor: C.t2, dataLabelFontFace: BODY,
    catAxisLabelColor: C.t2, catAxisLabelFontSize: 10.5, catAxisLabelFontFace: BODY,
    valAxisHidden: true, valGridLine: { style: "none" }, catGridLine: { style: "none" },
    showLegend: false, barGapWidthPct: 45, valAxisMaxVal: 4800, plotArea: { fill: { color: C.bg } }, chartArea: { fill: { color: C.bg } },
  });
  const bx = M + 9.1, bw = W - M - bx;
  card(s, bx, 2.1, bw, 1.7, { fill: C.tintA, line: C.danger });
  txt(s, "무엇이 문제인가", { x: bx + 0.3, y: 2.28, w: bw - 0.6, h: 0.32, fontSize: 15, bold: true, color: C.danger, fontFace: HEAD });
  txt(s, "한 곳을 고치면 다른 증상이 따라 나온다. 같은 계열 수정이 세 번 반복됐다.", { x: bx + 0.3, y: 2.66, w: bw - 0.6, h: 1.0, fontSize: 12.5, color: C.t2, lineSpacing: 18 });
  card(s, bx, 3.94, bw, 1.76, { fill: C.alt });
  txt(s, "어떻게 푸는가", { x: bx + 0.3, y: 4.12, w: bw - 0.6, h: 0.32, fontSize: 15, bold: true, color: C.t1, fontFace: HEAD });
  ["경보 판정 컴포넌트", "UWB 거리 컴포넌트", "보정 컴포넌트"].forEach((t, i) => {
    sq(s, bx + 0.32, 4.52 + i * 0.34 + 0.08, C.accent, 0.11);
    txt(s, t, { x: bx + 0.58, y: 4.52 + i * 0.34, w: bw - 0.88, h: 0.3, fontSize: 12.5, color: C.t2, valign: "middle" });
  });
  strip(s, 5.98, "분해의 수용 기준은 '동작이 하나도 달라지지 않는 것'이다. 기능을 바꾸는 작업이 아니다.", C.accent, 0.8);
  footer(s);
  s.addNotes("숫자 하나만 기억하면 된다 — 3,899줄. 과거 같은 계열의 수정이 세 번 반복된 원인이 여기에 있다. 분해 후에도 앱 동작은 동일해야 하며 그것이 수용 기준이다.");
}

/* ══════ 15. 로드맵 ══════ */
{
  const s = slide();
  head(s, "계획", "신뢰성 확보 로드맵", "5단계 · 매 단계마다 출하 가능한 상태로 끝난다");
  const ph = [["1", "테스트 · CI 게이트", "회귀를 현장이 아니라 빌드에서 먼저 잡는다", "완료", true],
              ["2", "경보 경로 기준값 고정", "격상 · 해제 전 경로를 기대값에 고정, 저속 접근 미탐지 수정", "예정", false],
              ["3", "핵심 로직 분해", "경보 · 거리 · 보정 세 컴포넌트로 분리, 동작 보존 증명", "예정", false],
              ["4", "기기 상태 단일화", "흩어진 상태를 하나로 통합, 장시간 구동 안정성 확보", "예정", false],
              ["5", "판정 처리 분리", "다수 기기 현장에서 화면 지연 없이 동작", "예정", false]];
  const cw = 2.3, gx = 0.14;
  ph.forEach(([n, k, v, st, done], i) => {
    const x = M + i * (cw + gx);
    card(s, x, 2.44, cw, 2.9, { fill: done ? C.tintS : C.alt, line: done ? C.safe : null });
    s.addShape(pres.ShapeType.ellipse, { x: x + cw / 2 - 0.3, y: 2.14, w: 0.6, h: 0.6, fill: { color: done ? C.safe : C.alt }, line: { color: done ? C.safe : C.stroke, width: 1 } });
    txt(s, n, { x: x + cw / 2 - 0.3, y: 2.14, w: 0.6, h: 0.6, fontSize: 19, bold: true, color: done ? C.onAcc : C.t3, align: "center", valign: "middle", fontFace: HEAD });
    txt(s, k, { x: x + 0.26, y: 2.94, w: cw - 0.52, h: 0.72, fontSize: 15, bold: true, color: C.t1, lineSpacing: 20, fontFace: HEAD });
    txt(s, v, { x: x + 0.26, y: 3.72, w: cw - 0.52, h: 1.1, fontSize: 12, color: C.t2, lineSpacing: 17 });
    chip(s, x + 0.26, 4.88, 0.98, 0.34, st, done ? C.safe : C.alt, done ? C.onAcc : C.t3, 10.5, done ? null : C.stroke);
    if (i < 4) s.addShape(pres.ShapeType.line, { x: x + cw + 0.01, y: 3.9, w: gx - 0.02, h: 0, line: { color: C.hair, width: 2, endArrowType: "triangle" } });
  });
  card(s, M, 5.6, W - M * 2, 0.88, { fill: C.surface });
  sq(s, M + 0.34, 5.97, C.safe);
  txt(s, "1단계 완료", { x: M + 0.58, y: 5.6, w: 1.9, h: 0.88, fontSize: 14, bold: true, color: C.safe, valign: "middle" });
  txt(s, "단위 테스트 17건 신설  ·  빌드마다 자동 실행  ·  실패 시 릴리스 자동 차단  ·  실기기 없이 회귀 판별", { x: M + 2.6, y: 5.6, w: W - M * 2 - 2.9, h: 0.88, fontSize: 13.5, color: C.t2, valign: "middle" });
  footer(s);
  s.addNotes("설계 원칙: 모든 단계가 출하 가능한 상태로 끝난다. 앱이 반쯤 분해된 채 검증 불가 상태로 오래 머무는 것을 명시적으로 금지했다. 현장 검증 사이클이 병목이기 때문이다.");
}

/* ══════ 16. 품질 게이트 ══════ */
{
  const s = slide();
  head(s, "품질", "회귀를 현장이 아니라 빌드에서 잡는다", "1단계에서 새로 만든 장치");
  const flow = [["코드 변경", "개발자가 수정을 올린다", C.dim], ["자동 빌드", "GitHub Actions 가 즉시 빌드", C.dim],
                ["기준값 검증", "단위 테스트 17건이 신호 처리 결과를 기대값과 대조", C.accent],
                ["통과 / 차단", "하나라도 어긋나면 릴리스 차단", C.danger],
                ["현장 배포", "통과 시에만 자동 업데이트 대상", C.safe]];
  const cw = 2.3, gx = 0.14;
  flow.forEach(([k, v, col], i) => {
    const x = M + i * (cw + gx);
    card(s, x, 2.14, cw, 1.96, { fill: C.alt });
    sq(s, x + 0.26, 2.42, col, 0.15);
    txt(s, k, { x: x + 0.26, y: 2.66, w: cw - 0.52, h: 0.32, fontSize: 15.5, bold: true, color: C.t1, fontFace: HEAD });
    txt(s, v, { x: x + 0.26, y: 3.06, w: cw - 0.52, h: 0.86, fontSize: 12, color: C.t2, lineSpacing: 17 });
    if (i < 4) s.addShape(pres.ShapeType.line, { x: x + cw + 0.01, y: 3.1, w: gx - 0.02, h: 0, line: { color: C.accent, width: 2, endArrowType: "triangle" } });
  });
  card(s, M, 4.36, 5.86, 1.9, { fill: C.alt });
  txt(s, "이전", { x: M + 0.36, y: 4.56, w: 5.1, h: 0.3, fontSize: 12.5, bold: true, color: C.t3, charSpacing: 2 });
  txt(s, "회귀를 사용자가\n현장에서 발견했다.", { x: M + 0.36, y: 4.9, w: 5.1, h: 0.8, fontSize: 20, bold: true, color: C.t2, fontFace: HEAD, lineSpacing: 28 });
  txt(s, "확인 사이클이 현장 가용 시간에 묶여 있었다.", { x: M + 0.36, y: 5.76, w: 5.1, h: 0.32, fontSize: 12, color: C.t3 });
  card(s, M + 6.06, 4.36, 5.86, 1.9, { fill: C.tintS, line: C.safe });
  txt(s, "이후", { x: M + 6.42, y: 4.56, w: 5.1, h: 0.3, fontSize: 12.5, bold: true, color: C.safe, charSpacing: 2 });
  txt(s, "회귀를 빌드가\n먼저 발견한다.", { x: M + 6.42, y: 4.9, w: 5.1, h: 0.8, fontSize: 20, bold: true, color: C.t1, fontFace: HEAD, lineSpacing: 28 });
  txt(s, "테스트가 실제로 실행됐는지까지 검증한다.", { x: M + 6.42, y: 5.76, w: 5.1, h: 0.32, fontSize: 12, color: C.t2 });
  footer(s);
  s.addNotes("경영진 관점의 의미: 확산의 전제 조건이 갖춰지기 시작했다. 단말이 늘면 문제 보고도 늘어나는데, 회귀를 자동으로 잡는 장치 없이는 대수를 늘릴수록 관리 비용이 비선형으로 커진다.");
}

/* ══════ 17. 챕터 03 ══════ */
{
  const s = slide();
  s.addImage({ path: A("wide_scale"), x: 0, y: 0, w: W, h: H });
  txt(s, "03", { x: M, y: 2.0, w: 3, h: 0.9, fontSize: 64, bold: true, color: C.accent, fontFace: HEAD });
  txt(s, "확산과 결정", { x: M, y: 3.0, w: 6, h: 0.4, fontSize: 14, bold: true, color: C.t2, charSpacing: 4 });
  txt(s, "한 대를 더 설치할 때마다\n감지 범위가 스스로 넓어진다.", { x: M, y: 3.62, w: 9.8, h: 1.6, fontSize: 38, bold: true, color: C.t1, fontFace: HEAD, lineSpacing: 54 });
  txt(s, "확산 이점 · 도입 시나리오 · 한계 · 요청 사항", { x: M, y: 5.4, w: 8, h: 0.34, fontSize: 15, color: C.t3 });
  s.addNotes("챕터 전환. 여기서부터 확산 판단에 필요한 정보를 다룬다.");
}

/* ══════ 18. 확산 시 이점 ══════ */
{
  const s = slide();
  head(s, "확산", "설치 대수가 곧 감지 범위", "인프라를 늘리지 않고 커버리지만 늘어난다");
  s.addText([{ text: "435", options: { fontSize: 78, bold: true, color: C.accent, fontFace: HEAD } },
             { text: " 쌍", options: { fontSize: 22, bold: true, color: C.accent, fontFace: HEAD } }],
    { x: M, y: 2.2, w: 3.5, h: 1.2, isTextBox: true, margin: 0, valign: "middle" });
  txt(s, "30대 설치 시 상호 감지 링크 수", { x: M, y: 3.46, w: 3.6, h: 0.32, fontSize: 14, bold: true, color: C.t1 });
  txt(s, "n대 → n(n-1)/2.\n대수가 늘면 감지쌍은 제곱으로 늘어난다.", { x: M, y: 3.84, w: 3.5, h: 0.7, fontSize: 12.5, color: C.t2, lineSpacing: 18 });
  s.addChart(pres.ChartType.bar, [{ name: "상호 감지 링크 수", labels: ["2대", "5대", "10대", "20대", "30대"], values: [1, 10, 45, 190, 435] }], {
    x: M + 3.7, y: 2.1, w: 4.3, h: 3.4, chartColors: [C.accent],
    showValue: true, dataLabelPosition: "outEnd", dataLabelFontSize: 10.5, dataLabelColor: C.t2, dataLabelFontFace: BODY,
    catAxisLabelColor: C.t2, catAxisLabelFontSize: 11, catAxisLabelFontFace: BODY,
    valAxisHidden: true, valGridLine: { style: "none" }, catGridLine: { style: "none" },
    showLegend: false, showTitle: false, barGapWidthPct: 55, valAxisMaxVal: 540, plotArea: { fill: { color: C.bg } }, chartArea: { fill: { color: C.bg } },
  });
  const bx = M + 8.2, bw = W - M - bx;
  const gains = [["ic_bulb", "한계비용 구조", "단말 추가 = 앱 설치 한 건", C.safe],
                 ["ic_receive_in", "이식성", "센터가 바뀌어도 재공사 없음", C.accent],
                 ["ic_search", "운영 데이터", "위험 구간 · 시간대 분석 가능", C.warn]];
  let y = 2.16;
  gains.forEach(([ic, k, v, col]) => {
    card(s, bx, y, bw, 1.08, { fill: C.alt });
    iconTile(s, bx + 0.28, y + 0.24, 0.58, ic, C.surface);
    txt(s, k, { x: bx + 1.0, y: y + 0.22, w: bw - 1.3, h: 0.3, fontSize: 15, bold: true, color: col, fontFace: HEAD });
    txt(s, v, { x: bx + 1.0, y: y + 0.56, w: bw - 1.3, h: 0.34, fontSize: 12, color: C.t2 });
    y += 1.16;
  });
  card(s, M, 5.72, W - M * 2, 0.8, { fill: C.surface, line: C.warn });
  txt(s, "단, 단말 한 대의 동시 추적 대수에는 현재 한계가 있다. 20대 이상 밀집 환경의 화면 지연은 로드맵 5단계에서 해소한다.",
    { x: M + 0.36, y: 5.72, w: W - M * 2 - 0.72, h: 0.8, fontSize: 13, color: C.t2, valign: "middle" });
  footer(s);
  s.addNotes("차트는 이론적 감지쌍 수이며 실제 동시 추적 성능과는 다르다. 그 격차를 정직하게 밝히는 것이 아래 주석이고 로드맵 5단계가 그것을 다룬다. 비용은 사내 단말 정책에 따라 달라져 별도 산정이 필요하다.");
}

/* ══════ 19. 도입 시나리오 ══════ */
{
  const s = slide();
  head(s, "도입", "3단계 시나리오 (제안)", "각 단계는 다음 단계로 넘어갈 판단 근거를 남기고 끝난다");
  const steps = [["STEP 1", "파일럿", "1개 센터 · 위험 동선 1개 구간", ["대상 장비 · 인원에 앱 설치", "역할 · 반경 현장 확정", "세이프존 위치 지정", "오경보 · 미탐지 사례 수집"], "경보가 뜰 때 뜨고 꺼질 때 꺼지는가", C.accent, true],
                 ["STEP 2", "센터 단위 확대", "동일 사업장 전 라인", ["사업장별 보정값 공유 적용", "장시간 연속 구동 안정성", "다수 기기 밀집 구간 성능", "무음구역 운영 규칙 정착"], "대수가 늘어도 판정이 흔들리지 않는가", C.warn, false],
                 ["STEP 3", "표준화", "전 센터 · 운영 규칙 편입", ["단말 소지 · 충전 규칙 수립", "신규 입사자 온보딩 포함", "경보 이력 기반 정기 리뷰", "보안 조치 완료"], "운영 규칙 없이도 유지되는가", C.safe, false]];
  const cw = 3.94, gx = 0.17;
  steps.forEach(([n, k, scope, items, basis, col, hi], i) => {
    const x = M + i * (cw + gx);
    card(s, x, 2.12, cw, 4.34, { fill: hi ? C.tintA : C.alt, line: hi ? C.accent : null });
    chip(s, x + 0.3, 2.34, 1.16, 0.34, n, col, C.onAcc, 10.5);
    txt(s, k, { x: x + 0.3, y: 2.8, w: cw - 0.6, h: 0.44, fontSize: 22, bold: true, color: C.t1, fontFace: HEAD });
    txt(s, scope, { x: x + 0.3, y: 3.24, w: cw - 0.6, h: 0.3, fontSize: 12, color: C.t3 });
    items.forEach((t, j) => {
      sq(s, x + 0.32, 3.7 + j * 0.42 + 0.08, col, 0.11);
      txt(s, t, { x: x + 0.58, y: 3.7 + j * 0.42, w: cw - 0.88, h: 0.34, fontSize: 12.5, color: C.t2, valign: "middle" });
    });
    s.addShape(pres.ShapeType.line, { x: x + 0.3, y: 5.46, w: cw - 0.6, h: 0, line: { color: C.hair, width: 1 } });
    txt(s, "판단 근거", { x: x + 0.3, y: 5.56, w: cw - 0.6, h: 0.24, fontSize: 10, color: C.t3, charSpacing: 2 });
    txt(s, basis, { x: x + 0.3, y: 5.8, w: cw - 0.6, h: 0.5, fontSize: 12.5, bold: true, color: col, lineSpacing: 17 });
    if (i < 2) s.addShape(pres.ShapeType.line, { x: x + cw + 0.01, y: 4.1, w: gx - 0.02, h: 0, line: { color: C.accent, width: 2, endArrowType: "triangle" } });
  });
  footer(s);
  s.addNotes("일정은 의도적으로 적지 않았다. 각 단계의 종료 조건이 기간이 아니라 판단 근거 확보이기 때문이다. 파일럿 기간은 현장 가용 시간에 따라 함께 정하면 된다.");
}

/* ══════ 20. 한계와 리스크 ══════ */
{
  const s = slide();
  head(s, "리스크", "한계와 리스크", "확산 결정 전에 알고 있어야 할 항목 — 숨기지 않고 함께 본다");
  const rows = [["신호 기반 거리 오차", "구조물 · 적재물이 전파를 가리면 거리가 어긋난다", "3단 정제 · 역할쌍 보정 · 상호 대칭화. 재현성 확보가 로드맵 2~4단계", C.warn],
                ["단말 소지 · 전원 의존", "단말을 소지하지 않으면 감지되지 않는다", "운영 규칙으로 보완 필요. 기술로 해결되는 항목이 아니다", C.danger],
                ["다수 기기 밀집 성능", "20대 이상이 모이면 화면 반응이 느려진다", "로드맵 5단계에서 판정 처리를 분리해 해소", C.warn],
                ["UWB 의존성", "사용 중인 UWB 라이브러리가 정식 출시 전 버전", "주 판정은 블루투스. UWB 는 보조 수단으로 한정", C.dim],
                ["보안 조치 미완", "릴리스 빌드 난독화 미적용, 경보 이력 평문 저장", "확산 전 조치 필요. 사내 보안 기준 확인 요청", C.danger]];
  txt(s, "항목", { x: M + 0.6, y: 2.12, w: 3.2, h: 0.3, fontSize: 11, bold: true, color: C.t3, charSpacing: 2 });
  txt(s, "무엇이 문제인가", { x: M + 3.9, y: 2.12, w: 4.0, h: 0.3, fontSize: 11, bold: true, color: C.t3, charSpacing: 2 });
  txt(s, "대응", { x: M + 7.85, y: 2.12, w: 3.9, h: 0.3, fontSize: 11, bold: true, color: C.t3, charSpacing: 2 });
  let y = 2.5;
  rows.forEach(([k, p, a, col], i) => {
    card(s, M, y, W - M * 2, 0.84, { fill: i % 2 ? C.alt : C.surface, shadow: { opacity: 0.18 } });
    sq(s, M + 0.32, y + 0.36, col);
    txt(s, k, { x: M + 0.6, y, w: 3.2, h: 0.84, fontSize: 14, bold: true, color: C.t1, valign: "middle" });
    txt(s, p, { x: M + 3.9, y, w: 3.85, h: 0.84, fontSize: 12.5, color: C.t2, valign: "middle", lineSpacing: 17 });
    txt(s, a, { x: M + 7.85, y, w: 3.72, h: 0.84, fontSize: 12.5, color: C.t2, valign: "middle", lineSpacing: 17 });
    y += 0.92;
  });
  footer(s);
  s.addNotes("마지막 항목(보안)은 파일럿 단계에서는 영향이 작지만 전사 확산 전에는 반드시 처리해야 한다. 의사결정 시점에 함께 검토를 요청하는 항목이다.");
}

/* ══════ 21. 요청 사항 ══════ */
{
  const s = slide();
  s.addImage({ path: A("closing"), x: 0, y: 0, w: W, h: 4.25 });
  txt(s, "결정 요청", { x: M, y: 0.44, w: 8, h: 0.26, fontSize: 11, bold: true, color: C.accent, charSpacing: 3 });
  txt(s, "요청 사항", { x: M, y: 0.74, w: W - M * 2, h: 0.78, fontSize: 40, bold: true, color: C.t1, fontFace: HEAD, valign: "middle" });
  txt(s, "다음 단계로 넘어가기 위해 결정이 필요한 세 가지", { x: M, y: 1.54, w: W - M * 2, h: 0.34, fontSize: 14, color: C.t2, valign: "middle" });
  const asks = [["01", "파일럿 대상 지정", "센터 1곳과 위험 동선 1개 구간, 참여 장비 · 인원 범위", C.accent],
                ["02", "현장 검증 시간 확보", "실기 검증이 유일한 회귀 확인 수단입니다. 정기 슬롯이 필요합니다", C.warn],
                ["03", "단말 운영 정책", "설치 대상 단말, 소지 · 충전 규칙, 자동 업데이트 허용 여부", C.safe]];
  const cw = 3.94, gx = 0.17;
  asks.forEach(([n, k, v, col], i) => {
    const x = M + i * (cw + gx);
    card(s, x, 2.2, cw, 2.0, { fill: C.surface, shadow: { blur: 22, opacity: 0.5 } });
    txt(s, n, { x: x + 0.32, y: 2.4, w: 1, h: 0.5, fontSize: 26, bold: true, color: col, fontFace: HEAD });
    txt(s, k, { x: x + 0.32, y: 2.98, w: cw - 0.64, h: 0.36, fontSize: 18, bold: true, color: C.t1, fontFace: HEAD });
    txt(s, v, { x: x + 0.32, y: 3.42, w: cw - 0.64, h: 0.8, fontSize: 12.5, color: C.t2, lineSpacing: 18 });
  });
  s.addShape(pres.ShapeType.line, { x: M, y: 5.06, w: 1.6, h: 0, line: { color: C.accent, width: 3 } });
  txt(s, "경보가 떠야 할 때 뜨고, 꺼져야 할 때 꺼진다.", { x: M, y: 5.3, w: 10.5, h: 0.5, fontSize: 26, bold: true, color: C.t1, fontFace: HEAD });
  txt(s, "확산의 전제는 대수가 아니라 신뢰성입니다.", { x: M, y: 5.84, w: 10.5, h: 0.5, fontSize: 26, bold: true, color: C.accent, fontFace: HEAD });
  txt(s, "SafeAlert  ·  v1.1.70  ·  2026. 08.", { x: M, y: 6.52, w: 9.5, h: 0.3, fontSize: 11, color: C.t3 });
  s.addNotes("세 가지 요청은 모두 기술이 아니라 운영 결정이다. 개발 측 준비는 로드맵으로 진행 중이며 파일럿 확대 여부만 결정되면 즉시 착수할 수 있다.");
}

const out = process.argv[2] || "SafeAlert_Executive_Brief_v1.1.70.pptx";
pres.writeFile({ fileName: out }).then(() => console.log("wrote", out));
