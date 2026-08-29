// SafeAlert 경영진 보고 덱 생성기
// 데이터 출처: 저장소 실측 (app/build.gradle, app/src/**, .planning/**, .github/workflows/release.yml)
const P = require("pptxgenjs");

const C = {
  ink:   "0F1C26",  // 심야 차콜네이비 (다크 배경)
  ink2:  "1B2E3C",  // 다크 위 카드
  ink3:  "27414F",  // 다크 위 보조
  paper: "FFFFFF",
  mist:  "F1F5F7",  // 라이트 카드
  mist2: "E4EBEF",
  line:  "D3DDE3",
  body:  "31454F",
  muted: "6C7C88",
  amber: "F0A31A",  // 세이프티 앰버 (경고 / 강조)
  amberD:"B9760A",
  red:   "D24A3E",  // 위험
  teal:  "1E8A6E",  // 안전
  steel: "3D6E8C",
};
const HEAD = "Malgun Gothic";
const BODY = "Malgun Gothic";
const W = 13.333, H = 7.5, M = 0.62;

const pres = new P();
pres.layout = "LAYOUT_WIDE";
pres.author = "SafeAlert";
pres.title = "SafeAlert 경영진 보고";

let pageNo = 0;
function shadow(o) { return Object.assign({ type: "outer", angle: 90, blur: 10, offset: 0.05, opacity: 0.10, color: "000000" }, o || {}); }

function newSlide(dark) {
  const s = pres.addSlide();
  s.background = { color: dark ? C.ink : C.paper };
  return s;
}
function footer(s, dark) {
  pageNo += 1;
  s.addText("SafeAlert · 경영진 보고 · v1.1.70", {
    x: M, y: H - 0.46, w: 6, h: 0.28, fontSize: 9, color: dark ? "5D7382" : "9AA8B2",
    fontFace: BODY, isTextBox: true, margin: 0, valign: "middle",
  });
  s.addText(String(pageNo), {
    x: W - M - 1, y: H - 0.46, w: 1, h: 0.28, fontSize: 9, color: dark ? "5D7382" : "9AA8B2",
    fontFace: BODY, isTextBox: true, margin: 0, align: "right", valign: "middle",
  });
}
function title(s, t, sub, dark) {
  s.addText(t, { x: M, y: 0.44, w: W - M * 2, h: 0.62, fontSize: 30, bold: true,
    color: dark ? C.paper : C.ink, fontFace: HEAD, isTextBox: true, margin: 0, valign: "middle" });
  if (sub) s.addText(sub, { x: M, y: 1.08, w: W - M * 2, h: 0.36, fontSize: 13.5,
    color: dark ? "9CB4C2" : C.muted, fontFace: BODY, isTextBox: true, margin: 0, valign: "middle" });
}
function card(s, x, y, w, h, o) {
  o = o || {};
  const opt = { x, y, w, h, fill: { color: o.fill || C.mist }, rectRadius: 0.08, shadow: shadow(o.shadow) };
  if (o.line) opt.line = { color: o.line, width: 1 };
  s.addShape(pres.ShapeType.roundRect, opt);
}
function chip(s, x, y, w, h, text, fill, color, size) {
  s.addShape(pres.ShapeType.roundRect, { x, y, w, h, fill: { color: fill }, rectRadius: 0.5 });
  s.addText(text, { x, y, w, h, fontSize: size || 11, bold: true, color, fontFace: BODY,
    align: "center", valign: "middle", isTextBox: true, margin: 0 });
}
function glyph(s, x, y, d, g, fill, color) {
  s.addShape(pres.ShapeType.ellipse, { x, y, w: d, h: d, fill: { color: fill } });
  s.addText(g, { x, y, w: d, h: d, fontSize: 15, bold: true, color, fontFace: BODY,
    align: "center", valign: "middle", isTextBox: true, margin: 0 });
}
function txt(s, t, o) { s.addText(t, Object.assign({ fontFace: BODY, isTextBox: true, margin: 0 }, o)); }

/* ══ 1. 표지 ══ */
{
  const s = newSlide(true);
  // 동심원 모티프 (큰 것부터 그려 윤곽선만 남긴다)
  const cx = 10.35, cy = 3.85;
  [[4.9, "1E3644"], [3.7, "24404F"], [2.6, "2C5061"], [1.6, C.amberD]].forEach(([d, col]) => {
    s.addShape(pres.ShapeType.ellipse, { x: cx - d / 2, y: cy - d / 2, w: d, h: d,
      fill: { color: C.ink }, line: { color: col, width: 1.25 } });
  });
  s.addShape(pres.ShapeType.ellipse, { x: cx - 0.17, y: cy - 0.17, w: 0.34, h: 0.34, fill: { color: C.amber } });
  chip(s, cx - 2.6 / 2 + 0.02, cy - 2.6 / 2 - 0.17, 0.86, 0.34, "8m", C.red, C.paper, 10);
  chip(s, cx + 1.6, cy + 1.55, 1.0, 0.34, "15m", C.amber, C.ink, 10);

  txt(s, "물류 현장 근접 경보 시스템", { x: M, y: 2.12, w: 7.4, h: 0.36, fontSize: 15, color: C.amber, bold: true });
  txt(s, "SafeAlert", { x: M, y: 2.5, w: 7.4, h: 1.15, fontSize: 66, bold: true, color: C.paper, fontFace: HEAD });
  txt(s, "지게차 · EPJ · 보행자가 서로를 감지한다.\n앱이 설치된 스마트폰끼리 직접 신호를 주고받아, 위험 거리에 들어오면 즉시 알린다.",
    { x: M, y: 3.78, w: 7.3, h: 0.95, fontSize: 14.5, color: "B9CBD6", lineSpacing: 24 });
  s.addShape(pres.ShapeType.line, { x: M, y: 5.0, w: 3.2, h: 0, line: { color: C.ink3, width: 1 } });
  txt(s, "현재 버전 v1.1.70  ·  3개월간 70회 이상 릴리스  ·  현장 운영 중",
    { x: M, y: 5.16, w: 7.3, h: 0.32, fontSize: 12, color: "8EA6B4" });
  txt(s, "2026. 08.", { x: M, y: 5.52, w: 7.3, h: 0.3, fontSize: 12, color: "6E8796" });
  s.addNotes("보고 목적: 현재 현장에서 운영 중인 SafeAlert의 기능과 목적, 그리고 확산 시 얻는 이점을 공유하고 파일럿 확대 여부에 대한 의사결정을 요청한다.");
}

/* ══ 2. 한 장 요약 ══ */
{
  const s = newSlide(false);
  title(s, "한 장 요약", "무엇을 하는가 · 왜 하는가 · 확산하면 무엇이 달라지는가", false);
  const stats = [
    ["70+", "릴리스", "3개월간 현장 운영", C.steel],
    ["0", "추가 인프라", "앵커·게이트웨이·배선 불필요", C.teal],
    ["3단계", "경보 등급", "안전 · 경고 · 위험", C.amber],
    ["1/5", "구조 개선 진행", "신뢰성 로드맵 1단계 완료", C.red],
  ];
  const cw = 2.94, gap = 0.19;
  stats.forEach(([big, lbl, sub, col], i) => {
    const x = M + i * (cw + gap);
    card(s, x, 1.66, cw, 1.56, { fill: C.mist });
    txt(s, big, { x: x + 0.26, y: 1.82, w: cw - 0.5, h: 0.62, fontSize: 34, bold: true, color: col, fontFace: HEAD });
    txt(s, lbl, { x: x + 0.26, y: 2.44, w: cw - 0.5, h: 0.28, fontSize: 12.5, bold: true, color: C.ink });
    txt(s, sub, { x: x + 0.26, y: 2.72, w: cw - 0.5, h: 0.36, fontSize: 10.5, color: C.muted });
  });
  const rows = [
    ["무엇을 하는가", "앱이 설치된 기기끼리 블루투스 신호를 직접 주고받아, 신호 세기로 추정한 거리가 위험 구간에 들어오면 소리·진동·전체화면으로 알린다."],
    ["왜 하는가", "3m 철제 렉 사이 통로와 단독 작업 구간에서 지게차·EPJ·보행자가 서로를 육안으로 확인하기 어렵다. 사각지대를 사람의 주의력이 아니라 시스템이 메운다."],
    ["지금 하는 일", "신규 기능 추가가 아니라 '같은 상황에서 같은 판정'을 보장하는 구조 개선이다. 5단계 로드맵 중 1단계(테스트·CI 회귀 게이트) 완료."],
    ["확산하면", "단말이 늘수록 감지 범위가 인프라 비용 증가 없이 넓어진다. 설치 대수 n대 → 상호 감지쌍 n(n-1)/2."],
  ];
  let y = 3.46;
  rows.forEach(([k, v], i) => {
    card(s, M, y, W - M * 2, 0.79, { fill: i % 2 ? C.paper : C.mist, line: i % 2 ? C.line : null, shadow: { opacity: 0.05 } });
    txt(s, k, { x: M + 0.28, y: y + 0.05, w: 2.1, h: 0.69, fontSize: 13, bold: true, color: C.amberD, valign: "middle" });
    txt(s, v, { x: M + 2.42, y: y + 0.05, w: W - M * 2 - 2.72, h: 0.69, fontSize: 12.5, color: C.body, valign: "middle", lineSpacing: 17 });
    y += 0.86;
  });
  footer(s, false);
  s.addNotes("네 개의 숫자로 현황을 먼저 고정한다. 70회 릴리스는 실험이 아니라 이미 현장에서 도는 물건이라는 뜻이고, 1/5는 지금 하는 일이 기능 추가가 아니라 신뢰성 확보라는 뜻이다.");
}

/* ══ 3. 문제 정의 ══ */
{
  const s = newSlide(false);
  title(s, "현장의 사각지대", "왜 사람의 주의력만으로는 부족한가", false);
  const items = [
    ["◈", "막힌 시야", "3m 높이 철제 렉 사이 통로. 교차·곡선 구간에서 지게차와 보행자가 서로를 볼 수 없다."],
    ["▣", "전파·소음 환경", "적재된 생수 파렛트가 2.4GHz 흡수벽으로 작용한다. 장비 소음 속에서 경적 인지도 늦다."],
    ["●", "단독 작업 구간", "렉 사이에서 혼자 작업하는 구간이 존재한다. 이탈 감지가 안전상 필수다."],
    ["⇄", "혼재 동선", "지게차·리치·오더피커·EPJ·보행자가 같은 통로를 공유한다. 역할마다 위험 거리가 다르다."],
  ];
  let y = 1.72;
  items.forEach(([g, k, v]) => {
    glyph(s, M, y + 0.09, 0.52, g, C.ink, C.amber);
    txt(s, k, { x: M + 0.74, y: y, w: 6.0, h: 0.3, fontSize: 14.5, bold: true, color: C.ink });
    txt(s, v, { x: M + 0.74, y: y + 0.32, w: 6.0, h: 0.62, fontSize: 12, color: C.body, lineSpacing: 17 });
    y += 1.16;
  });
  // 우측: 렉 통로 도식
  const bx = 7.55, bw = W - M - bx;
  card(s, bx, 1.66, bw, 4.24, { fill: C.mist });
  txt(s, "렉 사이 통로 — 상호 육안 확인 불가", { x: bx + 0.3, y: 1.86, w: bw - 0.6, h: 0.3, fontSize: 11.5, bold: true, color: C.muted });
  [0, 1, 2].forEach((i) => {
    s.addShape(pres.ShapeType.rect, { x: bx + 0.42, y: 2.34 + i * 1.16, w: 1.7, h: 0.86, fill: { color: C.mist2 }, line: { color: C.line, width: 1 } });
    s.addShape(pres.ShapeType.rect, { x: bx + 3.55, y: 2.34 + i * 1.16, w: 1.7, h: 0.86, fill: { color: C.mist2 }, line: { color: C.line, width: 1 } });
  });
  txt(s, "적재 렉", { x: bx + 0.42, y: 2.55, w: 1.7, h: 0.3, fontSize: 10, color: C.muted, align: "center" });
  txt(s, "적재 렉", { x: bx + 3.55, y: 2.55, w: 1.7, h: 0.3, fontSize: 10, color: C.muted, align: "center" });
  chip(s, bx + 2.28, 2.42, 1.12, 0.42, "지게차", C.amber, C.ink, 10.5);
  chip(s, bx + 2.28, 5.06, 1.12, 0.42, "보행자", C.steel, C.paper, 10.5);
  s.addShape(pres.ShapeType.line, { x: bx + 2.84, y: 2.9, w: 0, h: 2.12, line: { color: C.red, width: 1.5, dashType: "dash" } });
  chip(s, bx + 2.3, 3.78, 1.1, 0.36, "시야 차단", C.red, C.paper, 9.5);
  footer(s, false);
  s.addNotes("이 네 가지는 추정이 아니라 현장 조건이다. 특히 생수 파렛트의 전파 흡수는 신호 기반 거리 추정의 난이도를 직접 끌어올리는 요인이고, 뒤에 나올 3단 신호 정제와 사업장별 보정이 여기서 나왔다.");
}

/* ══ 4. 해결 방식 ══ */
{
  const s = newSlide(false);
  title(s, "해결 방식 — 기기끼리 서로를 본다", "고정 설비를 까는 대신, 움직이는 주체가 서로를 감지한다", false);
  const cw = 5.98;
  card(s, M, 1.72, cw, 3.3, { fill: C.mist });
  txt(s, "일반적인 구역 감지 방식", { x: M + 0.32, y: 1.94, w: cw - 0.64, h: 0.32, fontSize: 15, bold: true, color: C.muted });
  txt(s, [
    { text: "구역마다 앵커·게이트웨이 설치와 배선 공사가 필요하다", options: { bullet: true, breakLine: true } },
    { text: "서버·네트워크가 끊기면 감지도 함께 멈춘다", options: { bullet: true, breakLine: true } },
    { text: "'구역 진입' 단위 판정 — 누가 누구에게 가까운지는 모른다", options: { bullet: true, breakLine: true } },
    { text: "라인을 바꾸거나 센터를 늘리면 다시 공사한다", options: { bullet: true } },
  ], { x: M + 0.34, y: 2.36, w: cw - 0.7, h: 2.52, fontSize: 12.5, color: C.body, valign: "middle", paraSpaceAfter: 10, isTextBox: true, fontFace: BODY, margin: 0 });

  const x2 = M + cw + 0.13;
  card(s, x2, 1.72, cw, 3.3, { fill: C.ink });
  txt(s, "SafeAlert 방식", { x: x2 + 0.32, y: 1.94, w: cw - 0.64, h: 0.32, fontSize: 15, bold: true, color: C.amber });
  txt(s, [
    { text: "설치는 앱 하나. 고정 설비·배선·서버 증설이 없다", options: { bullet: true, breakLine: true } },
    { text: "기기 간 직접 통신 — 네트워크가 없어도 경보는 동작한다", options: { bullet: true, breakLine: true } },
    { text: "개체 대 개체 상대 거리 판정 — 역할 조합별로 기준이 다르다", options: { bullet: true, breakLine: true } },
    { text: "확장 = 설치. 현장이 바뀌어도 재공사가 없다", options: { bullet: true } },
  ], { x: x2 + 0.34, y: 2.36, w: cw - 0.7, h: 2.52, fontSize: 12.5, color: "CFDDE5", valign: "middle", paraSpaceAfter: 10, isTextBox: true, fontFace: BODY, margin: 0 });

  const chips = [["인프라 투자 0", C.teal], ["네트워크 없이도 경보 동작", C.steel], ["현장 구버전 기기와 100% 호환", C.amberD]];
  let cx = M;
  chips.forEach(([t, col]) => {
    const w = 3.98;
    s.addShape(pres.ShapeType.roundRect, { x: cx, y: 5.28, w, h: 0.62, fill: { color: C.paper }, line: { color: col, width: 1.25 }, rectRadius: 0.3 });
    txt(s, t, { x: cx, y: 5.28, w, h: 0.62, fontSize: 12.5, bold: true, color: col, align: "center", valign: "middle" });
    cx += w + 0.11;
  });
  footer(s, false);
  s.addNotes("핵심 차별점은 '고정 설비가 없다'는 것이다. 클라우드는 설정 공유와 앱 자동 업데이트에만 쓰이고, 경보 판정 자체는 단말 안에서 끝난다. 통신이 끊긴 창고에서도 경보는 그대로 동작한다.");
}

/* ══ 5. 핵심 기능 ══ */
{
  const s = newSlide(false);
  title(s, "핵심 기능", "현재 버전 v1.1.70에서 실제로 동작하는 기능", false);
  const feats = [
    ["◎", "역할 기반 차등 반경", "지게차·EPJ·보행자 조합별로 경고·위험 거리를 다르게 적용한다", C.amber],
    ["▲", "3단계 경보", "안전 / 경고 / 위험. 등급에 따라 소리·진동·전체화면 표시가 달라진다", C.red],
    ["⇄", "양방향 협력 알림", "한쪽이 먼저 감지하면 상대 기기도 함께 울린다. 한쪽 신호가 약해도 놓치지 않는다", C.teal],
    ["◀", "후진·하역 특수 경보", "상대가 후진 또는 하역·고소작업 중이면 근접 시 즉시 최고 등급으로 격상한다", C.red],
    ["↻", "모션·회전 인식", "단말 센서로 정지·주행·급정거와 좌·우 회전을 판별해 경보 판정에 반영한다", C.steel],
    ["◈", "보조 측위 수단", "UWB 정밀 거리, 존 비콘, 사업장별 보정값 클라우드 공유가 주 판정을 보완한다", C.steel],
  ];
  const cw = 3.98, ch = 1.86, gx = 0.13, gy = 0.16;
  feats.forEach(([g, k, v, col], i) => {
    const x = M + (i % 3) * (cw + gx), y = 1.72 + Math.floor(i / 3) * (ch + gy);
    card(s, x, y, cw, ch, { fill: C.mist });
    glyph(s, x + 0.28, y + 0.26, 0.5, g, col, C.paper);
    txt(s, k, { x: x + 0.28, y: y + 0.86, w: cw - 0.56, h: 0.3, fontSize: 14, bold: true, color: C.ink });
    txt(s, v, { x: x + 0.28, y: y + 1.17, w: cw - 0.56, h: 0.56, fontSize: 11.5, color: C.body, lineSpacing: 16 });
  });
  txt(s, "모든 기능은 단말 한 대 안에서 완결된다. 클라우드가 죽어도, UWB가 없어도 블루투스 근접 경보는 그대로 동작한다.",
    { x: M, y: 5.82, w: W - M * 2, h: 0.34, fontSize: 12, italic: true, color: C.amberD });
  footer(s, false);
  s.addNotes("여섯 가지 중 세 개(양방향 협력 알림, 후진·하역 특수 경보, 모션·회전 인식)는 시판 제품에서 잘 보이지 않는 항목이다. 현장 요구에서 나온 기능이라는 점을 강조한다.");
}

/* ══ 6. 판정 반경 ══ */
{
  const s = newSlide(false);
  title(s, "역할 조합별 판정 반경", "같은 거리라도 상대가 지게차인지 보행자인지에 따라 다르게 판정한다", false);
  function rings(cx, cy, outer, ratio) {
    s.addShape(pres.ShapeType.ellipse, { x: cx - outer / 2, y: cy - outer / 2, w: outer, h: outer,
      fill: { color: "FDF3DE" }, line: { color: C.amber, width: 1.5 } });
    const inner = outer * ratio;
    s.addShape(pres.ShapeType.ellipse, { x: cx - inner / 2, y: cy - inner / 2, w: inner, h: inner,
      fill: { color: "FAE2DE" }, line: { color: C.red, width: 1.5 } });
    s.addShape(pres.ShapeType.ellipse, { x: cx - 0.19, y: cy - 0.19, w: 0.38, h: 0.38, fill: { color: C.ink } });
  }
  function combo(x, headline, sub2, outer, ratio, warn, danger) {
    card(s, x, 1.72, 5.98, 3.5, { fill: C.mist });
    txt(s, headline, { x: x + 0.32, y: 1.9, w: 5.34, h: 0.3, fontSize: 14, bold: true, color: C.ink });
    txt(s, sub2, { x: x + 0.32, y: 2.2, w: 5.34, h: 0.28, fontSize: 11, color: C.muted });
    rings(x + 2.99, 3.44, outer, ratio);
    chip(s, x + 1.24, 4.6, 1.74, 0.44, warn, C.amber, C.ink, 12);
    chip(s, x + 3.04, 4.6, 1.74, 0.44, danger, C.red, C.paper, 12);
  }
  combo(M, "지게차가 포함된 조합", "지게차 ↔ 보행자 · EPJ · 지게차", 1.9, 8 / 15, "경고 15m", "위험 8m");
  combo(M + 6.11, "그 외 조합", "보행자 ↔ 보행자 · EPJ ↔ 보행자 · EPJ ↔ EPJ", 1.3, 3 / 5, "경고 5m", "위험 3m");
  card(s, M, 5.4, W - M * 2, 0.92, { fill: C.paper, line: C.line, shadow: { opacity: 0.05 } });
  txt(s, "판정 값은 현장에서 확정된 기준이며, 설정 화면에서 사업장별로 조정할 수 있다. 무거운 장비일수록 제동거리가 길기 때문에 지게차 조합의 반경이 크다. (도식은 비례 축척이 아니다)",
    { x: M + 0.3, y: 5.4, w: W - M * 2 - 0.6, h: 0.92, fontSize: 12.5, color: C.body, valign: "middle", lineSpacing: 18 });
  footer(s, false);
  s.addNotes("이 값은 임의로 정한 것이 아니라 현장에서 확인된 값이다. 현재 개선 작업의 목표도 '값을 바꾸는 것'이 아니라 '값대로 정확히 동작하게 하는 것'이다.");
}

/* ══ 7. 동작 원리 (코드 시각화 1) ══ */
{
  const s = newSlide(false);
  title(s, "동작 원리 — 감지에서 경보까지", "신호 한 번이 경보가 되기까지 거치는 5단계 (괄호 안은 실제 구성 요소)", false);
  const steps = [
    ["01", "광고 · 스캔", "모든 기기가 동시에 송신자이자 수신자다. 1초 주기 연속 스캔, 화면이 꺼져도 유지된다.", "BleAdvertiser / BleScanner"],
    ["02", "신호 정제 3단", "튐값 제거 → 이상치 차단 → 평활화. 약 120ms마다 갱신한다.", "MedianFilter → RssiPreFilter → KalmanFilter"],
    ["03", "거리 · 접근 추정", "역할쌍 보정, 상호 신호 대칭화, UWB 정밀 거리로 보완한다.", "역할쌍 오프셋 · 에코 RSSI · UwbRanger"],
    ["04", "경보 판정", "접근 속도와 상대의 상태를 함께 보고 등급을 올리거나 내린다.", "BleService 경보 상태머신"],
    ["05", "경보 출력", "등급에 맞춰 소리·진동·전체화면을 동시에 낸다.", "AlertSoundPlayer · VibrationHelper · OverlayManager"],
  ];
  const cw = 2.36, gx = 0.11;
  steps.forEach(([n, k, v, comp], i) => {
    const x = M + i * (cw + gx);
    card(s, x, 1.74, cw, 3.34, { fill: i === 3 ? C.ink : C.mist });
    const dark = i === 3;
    txt(s, n, { x: x + 0.24, y: 1.9, w: 1, h: 0.34, fontSize: 15, bold: true, color: dark ? C.amber : C.amberD, fontFace: HEAD });
    txt(s, k, { x: x + 0.24, y: 2.28, w: cw - 0.48, h: 0.6, fontSize: 14, bold: true, color: dark ? C.paper : C.ink, lineSpacing: 18 });
    txt(s, v, { x: x + 0.24, y: 2.92, w: cw - 0.48, h: 1.3, fontSize: 11.5, color: dark ? "C4D5DE" : C.body, lineSpacing: 16 });
    txt(s, comp, { x: x + 0.24, y: 4.28, w: cw - 0.48, h: 0.68, fontSize: 9.5, color: dark ? "89A3B1" : C.muted, lineSpacing: 13 });
    if (i < 4) s.addShape(pres.ShapeType.line, { x: x + cw + 0.005, y: 3.41, w: gx - 0.01, h: 0,
      line: { color: C.amber, width: 2, endArrowType: "triangle" } });
  });
  const tm = [["연속 스캔", "1초 주기"], ["판정 갱신", "약 120ms"], ["화면 갱신", "800ms"], ["경보 반응", "즉시"]];
  let tx = M;
  tm.forEach(([k, v]) => {
    const w = 2.94;
    card(s, tx, 5.24, w, 0.72, { fill: C.paper, line: C.line, shadow: { opacity: 0.04 } });
    txt(s, k, { x: tx + 0.28, y: 5.24, w: w - 0.56, h: 0.72, fontSize: 11.5, color: C.muted, valign: "middle" });
    txt(s, v, { x: tx + 0.28, y: 5.24, w: w - 0.62, h: 0.72, fontSize: 12.5, bold: true, color: C.ink, valign: "middle", align: "right" });
    tx += w + 0.11;
  });
  footer(s, false);
  s.addNotes("3단 신호 정제가 이 시스템의 심장이다. 블루투스 신호 세기는 그대로 쓰면 초당 수 dB씩 튀기 때문에, 정제 없이는 경보가 깜빡인다. 04단계(경보 판정)가 현재 개선 작업의 주 대상이다.");
}

/* ══ 8. 프로토콜 (코드 시각화 2) ══ */
{
  const s = newSlide(true);
  title(s, "1바이트 프로토콜", "블루투스 광고 패킷 단 1바이트에 역할·상태·회전·위험도를 모두 담는다", true);
  const fields = [
    ["CAT", "역할", ["00 보행자", "01 EPJ", "10 지게차·리치", "11 예약"], C.amber],
    ["STATE", "동적 상태", ["00 정지·일반", "01 전진·주행", "10 후진 ⚑", "11 하역·작업 ⚑"], C.red],
    ["TURN", "회전 방향", ["00 직진", "01 좌회전", "10 우회전", "11 예약"], C.steel],
    ["RISK", "자기 위험도", ["00 안전", "01 경고 감지", "10 위험 감지", "11 예약"], C.teal],
  ];
  const cw = 2.98, gx = 0.14;
  fields.forEach(([f, ko, vals, col], i) => {
    const x = M + i * (cw + gx);
    // 비트 셀 2개
    [0, 1].forEach((b) => {
      s.addShape(pres.ShapeType.rect, { x: x + b * (cw / 2 - 0.05) + (b ? 0.1 : 0), y: 1.72, w: cw / 2 - 0.05, h: 0.6,
        fill: { color: C.ink2 }, line: { color: col, width: 1.25 } });
      txt(s, "bit " + (7 - i * 2 - b), { x: x + b * (cw / 2 - 0.05) + (b ? 0.1 : 0), y: 1.72, w: cw / 2 - 0.05, h: 0.6,
        fontSize: 10.5, color: "8CA5B3", align: "center", valign: "middle" });
    });
    txt(s, f, { x, y: 2.44, w: cw, h: 0.34, fontSize: 15, bold: true, color: col, align: "center", fontFace: HEAD });
    txt(s, ko, { x, y: 2.76, w: cw, h: 0.28, fontSize: 11, color: "9CB4C2", align: "center" });
    card(s, x, 3.12, cw, 1.86, { fill: C.ink2, shadow: { opacity: 0.2 } });
    vals.forEach((v, j) => {
      txt(s, v, { x: x + 0.26, y: 3.28 + j * 0.42, w: cw - 0.52, h: 0.36, fontSize: 11.5,
        color: v.indexOf("⚑") >= 0 ? C.amber : "C9DAE3", valign: "middle" });
    });
  });
  txt(s, "⚑ 특수경보 트리거 — 상대가 후진·하역 중이면 근접 즉시 최고 등급", { x: M, y: 5.08, w: 7.5, h: 0.3, fontSize: 10.5, color: "8CA5B3" });
  const why = [
    ["왜 1바이트인가", "광고 패킷 용량이 극히 작다. 필요한 정보를 비트 단위로 눌러 담아야 1초 주기 연속 방송이 가능하다."],
    ["왜 못 바꾸는가", "현장에 이미 배포된 구버전 단말과 통신해야 한다. 배치가 바뀌면 구버전과 신버전이 서로를 오인한다."],
    ["호환 설계", "보행자 평상 상태 = 0x00. 페이로드를 싣지 않는 일반 비콘의 기본값과 자연 일치한다."],
  ];
  let y = 5.48;
  why.forEach(([k, v], i) => {
    if (i === 0) y = 5.48;
    txt(s, k, { x: M, y, w: 2.3, h: 0.3, fontSize: 11.5, bold: true, color: C.amber, valign: "middle" });
    txt(s, v, { x: M + 2.4, y, w: W - M * 2 - 2.4, h: 0.3, fontSize: 11.5, color: "B4C8D2", valign: "middle" });
    y += 0.34;
  });
  footer(s, true);
  s.addNotes("경영진에게 전할 함의는 하나다 — 이 1바이트 레이아웃은 현장 호환성 때문에 바꿀 수 없는 제약이며, 앞으로의 모든 개선은 이 제약 안에서 이뤄진다. 동시에 이 설계 덕분에 신버전 배포가 구버전 단말을 무력화하지 않는다.");
}

/* ══ 9. 시스템 구조 (코드 시각화 3) ══ */
{
  const s = newSlide(false);
  title(s, "시스템 구조", "27개 파일 11,636줄 · 6개 계층 · 순환 참조 없음 (2026.08 실측)", false);
  const layers = [
    ["05_ui", "화면 · 역할 선택 · 설정", "5개 파일", "2,521줄", C.steel],
    ["03_service", "경보 판정 · 상태 관리 · 생명주기", "3개 파일", "4,133줄", C.red],
    ["02_ble", "블루투스 송수신 · 신호 정제", "7개 파일", "1,712줄", C.amber],
    ["06_utils", "보정 · UWB · 비콘 · 오버레이 · 설정", "7개 파일", "3,040줄", C.amber],
    ["01_model", "데이터 모델", "2개 파일", "32줄", C.muted],
  ];
  let y = 1.74;
  const lw = 8.9;
  layers.forEach(([id, ko, f, l, col], i) => {
    const h = 0.78;
    card(s, M, y, lw, h, { fill: i === 1 ? C.ink : C.mist });
    const dark = i === 1;
    s.addShape(pres.ShapeType.ellipse, { x: M + 0.26, y: y + h / 2 - 0.11, w: 0.22, h: 0.22, fill: { color: col } });
    txt(s, id, { x: M + 0.62, y, w: 1.7, h, fontSize: 13, bold: true, color: dark ? C.paper : C.ink, valign: "middle", fontFace: HEAD });
    txt(s, ko, { x: M + 2.34, y, w: 4.4, h, fontSize: 12, color: dark ? "C0D3DC" : C.body, valign: "middle" });
    txt(s, f, { x: M + 6.7, y, w: 1.1, h, fontSize: 11, color: dark ? "8CA5B3" : C.muted, valign: "middle", align: "right" });
    txt(s, l, { x: M + 7.85, y, w: 0.85, h, fontSize: 12, bold: true, color: dark ? C.amber : C.ink, valign: "middle", align: "right" });
    if (i < layers.length - 1) s.addShape(pres.ShapeType.line, { x: M + lw / 2, y: y + h, w: 0, h: 0.14,
      line: { color: C.line, width: 1.5, endArrowType: "triangle" } });
    y += h + 0.14;
  });
  const bx = M + lw + 0.16, bw = W - M - bx;
  card(s, bx, 1.74, bw, 2.4, { fill: C.mist });
  txt(s, "04_firebase", { x: bx + 0.28, y: 1.92, w: bw - 0.56, h: 0.3, fontSize: 13, bold: true, color: C.ink, fontFace: HEAD });
  txt(s, "설정·프로필 공유, 배포 권위\n2개 파일 · 181줄", { x: bx + 0.28, y: 2.24, w: bw - 0.56, h: 0.6, fontSize: 11.5, color: C.body, lineSpacing: 16 });
  txt(s, "경보 판정 경로에서 분리되어 있다.\n클라우드가 끊겨도 경보는 동작한다.", { x: bx + 0.28, y: 2.94, w: bw - 0.56, h: 0.8, fontSize: 11, color: C.muted, lineSpacing: 15, italic: true });
  card(s, bx, 4.3, bw, 1.7, { fill: C.ink });
  txt(s, "테스트", { x: bx + 0.28, y: 4.46, w: bw - 0.56, h: 0.3, fontSize: 13, bold: true, color: C.amber, fontFace: HEAD });
  txt(s, "JVM 단위 테스트 17건 · 488줄\n실기기 없이 CI에서 자동 실행", { x: bx + 0.28, y: 4.78, w: bw - 0.56, h: 0.66, fontSize: 11.5, color: "C0D3DC", lineSpacing: 16 });
  txt(s, "2026.08 신설 (로드맵 1단계)", { x: bx + 0.28, y: 5.5, w: bw - 0.56, h: 0.3, fontSize: 10.5, color: "89A3B1" });
  footer(s, false);
  s.addNotes("계층이 위에서 아래로만 의존한다 — 순환 참조가 없다는 것은 한 계층을 고쳐도 다른 계층이 연쇄적으로 깨지지 않는다는 뜻이다. 문제는 계층 구조가 아니라 03_service 한 파일에 책임이 몰려 있다는 점이며, 다음 장에서 다룬다.");
}

/* ══ 10. 코드 규모 진단 (코드 시각화 4) ══ */
{
  const s = newSlide(false);
  title(s, "개선이 필요한 지점", "파일 하나에 경보·거리·보정·생명주기가 모두 들어 있다", false);
  s.addChart(pres.ChartType.bar, [{
    name: "코드 줄 수",
    labels: ["OverlayManager", "BleAdvertiser", "DevSettings", "UwbRanger", "MainActivity", "BleService\n(경보 판정 전체)"],
    values: [602, 632, 752, 850, 942, 3899],
  }], {
    x: M, y: 1.74, w: 7.5, h: 3.9,
    barDir: "bar", chartColors: [C.steel, C.steel, C.steel, C.steel, C.steel, C.red],
    showTitle: true, title: "상위 6개 파일의 코드 줄 수 (전체 27개 파일 11,636줄)",
    titleFontSize: 12, titleColor: C.muted, titleFontFace: BODY,
    showValue: true, dataLabelPosition: "outEnd", dataLabelFontSize: 10.5, dataLabelColor: C.body, dataLabelFontFace: BODY,
    catAxisLabelColor: C.body, catAxisLabelFontSize: 10.5, catAxisLabelFontFace: BODY,
    valAxisLabelColor: C.muted, valAxisLabelFontSize: 9, valAxisHidden: true,
    valGridLine: { style: "none" }, catGridLine: { style: "none" },
    showLegend: false, barGapWidthPct: 45, valAxisMaxVal: 4600,
  });
  const bx = M + 7.68, bw = W - M - bx;
  card(s, bx, 1.74, bw, 1.86, { fill: C.ink });
  txt(s, "무엇이 문제인가", { x: bx + 0.28, y: 1.92, w: bw - 0.56, h: 0.3, fontSize: 13.5, bold: true, color: C.amber, fontFace: HEAD });
  txt(s, "경보 등급 판정, UWB 거리 관리, 보정값 계산, 서비스 생명주기가 한 파일 안에 섞여 있다. 한 곳을 고치면 다른 증상이 따라 나온다.",
    { x: bx + 0.28, y: 2.26, w: bw - 0.56, h: 1.2, fontSize: 11.5, color: "C4D5DE", lineSpacing: 17 });
  card(s, bx, 3.74, bw, 1.98, { fill: C.mist });
  txt(s, "어떻게 푸는가", { x: bx + 0.28, y: 3.92, w: bw - 0.56, h: 0.3, fontSize: 13.5, bold: true, color: C.ink, fontFace: HEAD });
  ["경보 판정 컴포넌트", "UWB 거리 컴포넌트", "보정 컴포넌트"].forEach((t, i) => {
    txt(s, "→  " + t, { x: bx + 0.28, y: 4.24 + i * 0.36, w: bw - 0.56, h: 0.32, fontSize: 12, color: C.body, valign: "middle" });
  });
  txt(s, "세 컴포넌트로 분리 (로드맵 3단계)", { x: bx + 0.28, y: 5.36, w: bw - 0.56, h: 0.3, fontSize: 10.5, italic: true, color: C.muted });
  txt(s, "분해의 수용 기준은 '동작이 하나도 달라지지 않는 것'이다. 기능을 바꾸는 작업이 아니라, 같은 동작을 검증 가능한 형태로 다시 세우는 작업이다.",
    { x: M, y: 5.82, w: W - M * 2, h: 0.34, fontSize: 12, italic: true, color: C.amberD });
  footer(s, false);
  s.addNotes("숫자 하나만 기억하면 된다 — 3,899줄. 두 번째로 큰 파일의 네 배다. 과거 같은 계열의 수정이 세 번 반복된 원인이 여기에 있다. 분해 후에도 앱 동작은 동일해야 하며, 그것이 수용 기준이다.");
}

/* ══ 11. 신뢰성 로드맵 ══ */
{
  const s = newSlide(true);
  title(s, "신뢰성 확보 로드맵", "'같은 상황에서 같은 판정' — 5단계, 매 단계마다 출하 가능한 상태로 끝난다", true);
  const phases = [
    ["1", "테스트 · CI 회귀 게이트", "회귀를 현장이 아니라 빌드에서 먼저 잡는다", "완료", C.teal],
    ["2", "경보 경로 기준값 고정", "경보 격상·해제 전 경로를 기대값에 고정, 저속 접근 미탐지 수정", "예정", C.amber],
    ["3", "핵심 로직 분해", "경보·거리·보정 세 컴포넌트로 분리, 동작 보존 증명", "예정", C.amber],
    ["4", "기기 상태 단일화", "흩어진 상태를 하나로 통합, 장시간 구동 안정성 확보", "예정", C.steel],
    ["5", "판정 처리 분리", "다수 기기 현장에서 화면 지연 없이 동작", "예정", C.steel],
  ];
  const cw = 2.36, gx = 0.11;
  phases.forEach(([n, k, v, st, col], i) => {
    const x = M + i * (cw + gx);
    const done = st === "완료";
    card(s, x, 1.86, cw, 3.0, { fill: done ? C.ink2 : C.ink2, shadow: { opacity: 0.2 } });
    s.addShape(pres.ShapeType.ellipse, { x: x + cw / 2 - 0.28, y: 1.6, w: 0.56, h: 0.56,
      fill: { color: done ? C.teal : C.ink3 }, line: { color: done ? C.teal : C.ink3, width: 1 } });
    txt(s, n, { x: x + cw / 2 - 0.28, y: 1.6, w: 0.56, h: 0.56, fontSize: 16, bold: true,
      color: done ? C.paper : "8CA5B3", align: "center", valign: "middle", fontFace: HEAD });
    txt(s, k, { x: x + 0.24, y: 2.36, w: cw - 0.48, h: 0.72, fontSize: 13.5, bold: true, color: C.paper, lineSpacing: 18 });
    txt(s, v, { x: x + 0.24, y: 3.12, w: cw - 0.48, h: 1.2, fontSize: 11.5, color: "B4C8D2", lineSpacing: 16 });
    chip(s, x + 0.24, 4.4, 1.0, 0.34, st, done ? C.teal : C.ink3, done ? C.paper : "9CB4C2", 10);
    if (i < 4) s.addShape(pres.ShapeType.line, { x: x + cw + 0.005, y: 3.36, w: gx - 0.01, h: 0,
      line: { color: C.ink3, width: 2, endArrowType: "triangle" } });
  });
  card(s, M, 5.1, W - M * 2, 0.92, { fill: C.ink2, shadow: { opacity: 0.2 } });
  txt(s, "1단계 완료 성과", { x: M + 0.3, y: 5.1, w: 2.2, h: 0.92, fontSize: 12.5, bold: true, color: C.amber, valign: "middle" });
  txt(s, "단위 테스트 17건 신설 · 빌드마다 자동 실행 · 실패 시 릴리스 자동 차단 · 실기기 없이 회귀 판별 가능",
    { x: M + 2.6, y: 5.1, w: W - M * 2 - 2.9, h: 0.92, fontSize: 12.5, color: "C4D5DE", valign: "middle" });
  footer(s, true);
  s.addNotes("중요한 설계 원칙: 모든 단계가 출하 가능한 상태로 끝난다. 앱이 반쯤 분해된 채 검증 불가 상태로 오래 머무는 것을 명시적으로 금지했다. 현장 검증 사이클이 병목이기 때문이다.");
}

/* ══ 12. 품질 게이트 ══ */
{
  const s = newSlide(false);
  title(s, "품질 관리 체계", "1단계에서 새로 만든 것 — 회귀를 현장이 아니라 빌드에서 잡는 장치", false);
  const flow = [
    ["코드 변경", "개발자가 수정을 올린다", C.steel],
    ["자동 빌드", "GitHub Actions가 즉시 빌드한다", C.steel],
    ["기준값 검증", "단위 테스트 17건이 신호 처리 결과를 기대값과 대조한다", C.amber],
    ["통과 / 차단", "하나라도 어긋나면 릴리스가 차단된다", C.red],
    ["현장 배포", "통과 시에만 앱이 자동 업데이트 대상이 된다", C.teal],
  ];
  const cw = 2.36, gx = 0.11;
  flow.forEach(([k, v, col], i) => {
    const x = M + i * (cw + gx);
    card(s, x, 1.76, cw, 1.94, { fill: C.mist });
    s.addShape(pres.ShapeType.ellipse, { x: x + 0.24, y: 1.96, w: 0.34, h: 0.34, fill: { color: col } });
    txt(s, k, { x: x + 0.68, y: 1.94, w: cw - 0.9, h: 0.38, fontSize: 13, bold: true, color: C.ink, valign: "middle" });
    txt(s, v, { x: x + 0.24, y: 2.46, w: cw - 0.48, h: 1.1, fontSize: 11.5, color: C.body, lineSpacing: 16 });
    if (i < 4) s.addShape(pres.ShapeType.line, { x: x + cw + 0.005, y: 2.73, w: gx - 0.01, h: 0,
      line: { color: C.amber, width: 2, endArrowType: "triangle" } });
  });
  card(s, M, 3.96, 5.98, 1.96, { fill: C.mist });
  txt(s, "이전", { x: M + 0.32, y: 4.14, w: 5.3, h: 0.3, fontSize: 12.5, bold: true, color: C.muted });
  txt(s, "회귀를 사용자가 현장에서 발견했다.", { x: M + 0.32, y: 4.48, w: 5.3, h: 0.36, fontSize: 15, bold: true, color: C.ink });
  txt(s, "증상이 보고되면 그때부터 원인을 찾았고, 확인 사이클이 현장 가용 시간에 묶여 있었다.",
    { x: M + 0.32, y: 4.9, w: 5.3, h: 0.8, fontSize: 11.5, color: C.body, lineSpacing: 16 });
  card(s, M + 6.11, 3.96, 5.98, 1.96, { fill: C.ink });
  txt(s, "이후", { x: M + 6.43, y: 4.14, w: 5.3, h: 0.3, fontSize: 12.5, bold: true, color: C.amber });
  txt(s, "회귀를 빌드가 먼저 발견한다.", { x: M + 6.43, y: 4.48, w: 5.3, h: 0.36, fontSize: 15, bold: true, color: C.paper });
  txt(s, "테스트가 실제로 실행됐는지까지 검증한다. 테스트가 조용히 사라져 통과처럼 보이는 상황을 차단했다.",
    { x: M + 6.43, y: 4.9, w: 5.3, h: 0.8, fontSize: 11.5, color: "C4D5DE", lineSpacing: 16 });
  footer(s, false);
  s.addNotes("경영진 관점에서 이 장의 의미: 확산의 전제 조건이 갖춰지기 시작했다는 것. 단말이 늘면 문제 보고도 늘어나는데, 회귀를 자동으로 잡는 장치 없이는 대수를 늘릴수록 관리 비용이 비선형으로 커진다.");
}

/* ══ 13. 확산 시 이점 ══ */
{
  const s = newSlide(false);
  title(s, "확산 시 이점", "설치 대수가 늘수록 감지 범위가 인프라 비용 없이 넓어진다", false);
  s.addChart(pres.ChartType.bar, [{
    name: "상호 감지 링크 수",
    labels: ["2대", "5대", "10대", "20대", "30대"],
    values: [1, 10, 45, 190, 435],
  }], {
    x: M, y: 1.76, w: 6.0, h: 3.44,
    chartColors: [C.amber], showTitle: true, title: "설치 대수와 상호 감지 링크 수  n(n-1)/2",
    titleFontSize: 12, titleColor: C.muted, titleFontFace: BODY,
    showValue: true, dataLabelPosition: "outEnd", dataLabelFontSize: 10.5, dataLabelColor: C.body, dataLabelFontFace: BODY,
    catAxisLabelColor: C.body, catAxisLabelFontSize: 11, catAxisLabelFontFace: BODY,
    valAxisHidden: true, valGridLine: { style: "none" }, catGridLine: { style: "none" },
    showLegend: false, barGapWidthPct: 55, valAxisMaxVal: 520,
  });
  const bx = M + 6.2, bw = W - M - bx;
  const gains = [
    ["◎", "한계비용 구조", "단말 한 대 추가 = 앱 설치 한 건. 앵커·배선·서버 증설이 없다"],
    ["⇄", "커버리지 자기 증식", "대수가 늘수록 감지쌍이 제곱으로 늘어난다. 사각지대가 자연히 줄어든다"],
    ["◈", "이식성", "센터나 라인이 바뀌어도 재공사가 없다. 사업장별 보정값만 공유하면 된다"],
    ["▣", "운영 데이터 축적", "경보 이력이 쌓이면 위험 구간·시간대를 근거로 분석할 수 있다"],
  ];
  let y = 1.76;
  gains.forEach(([g, k, v]) => {
    card(s, bx, y, bw, 0.9, { fill: C.mist, shadow: { opacity: 0.05 } });
    glyph(s, bx + 0.24, y + 0.21, 0.48, g, C.ink, C.amber);
    txt(s, k, { x: bx + 0.86, y: y + 0.12, w: bw - 1.1, h: 0.3, fontSize: 13, bold: true, color: C.ink });
    txt(s, v, { x: bx + 0.86, y: y + 0.43, w: bw - 1.1, h: 0.4, fontSize: 11, color: C.body, lineSpacing: 15 });
    y += 0.98;
  });
  card(s, M, 5.72, W - M * 2, 0.74, { fill: C.paper, line: C.amber, shadow: { opacity: 0.04 } });
  txt(s, "단, 단말 한 대가 동시에 추적할 수 있는 대수에는 현재 한계가 있다. 20대 이상 밀집 환경의 화면 지연은 로드맵 5단계에서 해소한다.",
    { x: M + 0.3, y: 5.72, w: W - M * 2 - 0.6, h: 0.74, fontSize: 12, color: C.body, valign: "middle" });
  footer(s, false);
  s.addNotes("차트는 이론적 감지쌍 수이며 실제 동시 추적 성능과는 다르다. 그 격차를 정직하게 밝히는 것이 아래 주석이고, 로드맵 5단계가 그것을 다룬다. 비용 항목은 사내 단말 정책에 따라 달라지므로 별도 산정이 필요하다.");
}

/* ══ 14. 도입 시나리오 ══ */
{
  const s = newSlide(false);
  title(s, "도입 시나리오 (제안)", "각 단계는 다음 단계로 넘어갈 판단 근거를 남기고 끝난다", false);
  const steps = [
    ["STEP 1", "파일럿", "1개 센터, 위험 동선 1개 구간", ["대상 장비·인원에 앱 설치", "역할·반경 현장 확정", "오경보·미탐지 사례 수집"], "판단 근거: 경보가 뜰 때 뜨고 꺼질 때 꺼지는가", C.amber],
    ["STEP 2", "센터 단위 확대", "동일 사업장 전 라인", ["사업장별 보정값 공유 적용", "장시간 연속 구동 안정성 확인", "다수 기기 밀집 구간 성능 확인"], "판단 근거: 대수가 늘어도 판정이 흔들리지 않는가", C.steel],
    ["STEP 3", "표준화", "전 센터 · 운영 규칙 편입", ["단말 소지·충전 운영 규칙 수립", "신규 입사자 온보딩 절차 포함", "경보 이력 기반 정기 리뷰"], "판단 근거: 운영 규칙 없이도 유지되는가", C.teal],
  ];
  const cw = 4.0, gx = 0.11;
  steps.forEach(([n, k, scope, items, basis, col], i) => {
    const x = M + i * (cw + gx);
    card(s, x, 1.74, cw, 4.16, { fill: i === 0 ? C.ink : C.mist });
    const dark = i === 0;
    chip(s, x + 0.26, 1.96, 1.14, 0.34, n, col, dark ? C.ink : C.paper, 10);
    txt(s, k, { x: x + 0.26, y: 2.42, w: cw - 0.52, h: 0.38, fontSize: 18, bold: true, color: dark ? C.paper : C.ink, fontFace: HEAD });
    txt(s, scope, { x: x + 0.26, y: 2.82, w: cw - 0.52, h: 0.32, fontSize: 11.5, color: dark ? "9CB4C2" : C.muted });
    items.forEach((t, j) => {
      s.addShape(pres.ShapeType.ellipse, { x: x + 0.3, y: 3.34 + j * 0.52 + 0.09, w: 0.13, h: 0.13, fill: { color: col } });
      txt(s, t, { x: x + 0.56, y: 3.34 + j * 0.52, w: cw - 0.84, h: 0.46, fontSize: 11.5, color: dark ? "C4D5DE" : C.body, lineSpacing: 15 });
    });
    s.addShape(pres.ShapeType.line, { x: x + 0.26, y: 5.06, w: cw - 0.52, h: 0, line: { color: dark ? C.ink3 : C.line, width: 1 } });
    txt(s, basis, { x: x + 0.26, y: 5.16, w: cw - 0.52, h: 0.6, fontSize: 11, italic: true, color: dark ? C.amber : C.amberD, lineSpacing: 15 });
    if (i < 2) s.addShape(pres.ShapeType.line, { x: x + cw + 0.005, y: 3.82, w: gx - 0.01, h: 0,
      line: { color: C.amber, width: 2, endArrowType: "triangle" } });
  });
  footer(s, false);
  s.addNotes("일정은 의도적으로 적지 않았다. 각 단계의 종료 조건이 '기간'이 아니라 '판단 근거 확보'이기 때문이다. 파일럿 기간은 현장 가용 시간에 따라 함께 정하면 된다.");
}

/* ══ 15. 리스크 ══ */
{
  const s = newSlide(false);
  title(s, "한계와 리스크", "확산 결정 전에 알고 있어야 할 항목 — 숨기지 않고 함께 본다", false);
  const rows = [
    ["신호 기반 거리 추정 오차", "구조물·적재물이 전파를 가리면 거리가 실제와 어긋난다", "3단 신호 정제 · 역할쌍 보정 · 상호 신호 대칭화. 재현성 확보 작업이 로드맵 2~4단계", C.amber],
    ["단말 소지·전원 의존", "앱이 설치된 단말을 소지하지 않으면 감지되지 않는다", "운영 규칙으로 보완 필요. 기술로 해결되는 항목이 아니다", C.red],
    ["다수 기기 밀집 성능", "20대 이상이 한 구역에 모이면 화면 반응이 느려진다", "로드맵 5단계에서 판정 처리를 분리해 해소", C.amber],
    ["UWB 정밀 측위 의존성", "사용 중인 UWB 라이브러리가 정식 출시 전 버전이다", "주 판정은 블루투스. UWB는 보조 수단으로 한정해 영향 차단", C.steel],
    ["보안 조치 미완", "릴리스 빌드 난독화 미적용, 경보 이력 평문 저장", "확산 전 조치 필요 항목. 사내 보안 기준 확인 요청", C.red],
  ];
  const hy = 1.74;
  txt(s, "항목", { x: M + 0.3, y: hy, w: 3.2, h: 0.34, fontSize: 11, bold: true, color: C.muted });
  txt(s, "무엇이 문제인가", { x: M + 3.6, y: hy, w: 4.0, h: 0.34, fontSize: 11, bold: true, color: C.muted });
  txt(s, "대응", { x: M + 7.7, y: hy, w: 4.3, h: 0.34, fontSize: 11, bold: true, color: C.muted });
  let y = hy + 0.4;
  rows.forEach(([k, p, a, col], i) => {
    card(s, M, y, W - M * 2, 0.82, { fill: i % 2 ? C.paper : C.mist, line: i % 2 ? C.line : null, shadow: { opacity: 0.04 } });
    s.addShape(pres.ShapeType.ellipse, { x: M + 0.28, y: y + 0.35, w: 0.13, h: 0.13, fill: { color: col } });
    txt(s, k, { x: M + 0.54, y, w: 2.96, h: 0.82, fontSize: 12.5, bold: true, color: C.ink, valign: "middle", lineSpacing: 16 });
    txt(s, p, { x: M + 3.6, y, w: 4.0, h: 0.82, fontSize: 11.5, color: C.body, valign: "middle", lineSpacing: 16 });
    txt(s, a, { x: M + 7.7, y, w: 4.31, h: 0.82, fontSize: 11.5, color: C.body, valign: "middle", lineSpacing: 16 });
    y += 0.9;
  });
  footer(s, false);
  s.addNotes("마지막 항목(보안)은 파일럿 단계에서는 영향이 작지만 전사 확산 전에는 반드시 처리해야 한다. 의사결정 시점에 함께 검토를 요청하는 항목이다.");
}

/* ══ 16. 요청 사항 ══ */
{
  const s = newSlide(true);
  const cx = 11.6, cy = 5.6;
  [[5.2, "182D3A"], [3.9, "1E3644"], [2.6, "24404F"]].forEach(([d, col]) => {
    s.addShape(pres.ShapeType.ellipse, { x: cx - d / 2, y: cy - d / 2, w: d, h: d, fill: { color: C.ink }, line: { color: col, width: 1.25 } });
  });
  title(s, "요청 사항", "다음 단계로 넘어가기 위해 결정이 필요한 세 가지", true);
  const asks = [
    ["01", "파일럿 대상 지정", "센터 1곳과 위험 동선 1개 구간, 참여 장비·인원 범위를 정해 주십시오."],
    ["02", "현장 검증 시간 확보", "실기 검증이 유일한 회귀 확인 수단입니다. 정기적인 검증 슬롯이 필요합니다."],
    ["03", "단말 운영 정책", "앱 설치 대상 단말, 소지·충전 규칙, 자동 업데이트 허용 여부에 대한 방침이 필요합니다."],
  ];
  let y = 1.86;
  asks.forEach(([n, k, v]) => {
    card(s, M, y, 9.1, 1.16, { fill: C.ink2, shadow: { opacity: 0.2 } });
    txt(s, n, { x: M + 0.3, y, w: 0.72, h: 1.16, fontSize: 20, bold: true, color: C.amber, valign: "middle", fontFace: HEAD });
    txt(s, k, { x: M + 1.14, y: y + 0.2, w: 7.6, h: 0.34, fontSize: 15, bold: true, color: C.paper });
    txt(s, v, { x: M + 1.14, y: y + 0.56, w: 7.6, h: 0.44, fontSize: 12, color: "B4C8D2" });
    y += 1.28;
  });
  s.addShape(pres.ShapeType.line, { x: M, y: 5.78, w: 5.4, h: 0, line: { color: C.ink3, width: 1 } });
  txt(s, "경보가 떠야 할 때 뜨고, 꺼져야 할 때 꺼진다.\n확산의 전제는 대수가 아니라 신뢰성입니다.",
    { x: M, y: 5.94, w: 9.1, h: 0.8, fontSize: 14, bold: true, color: C.amber, lineSpacing: 22 });
  footer(s, true);
  s.addNotes("세 가지 요청은 모두 기술이 아니라 운영 결정이다. 개발 측에서 준비 가능한 부분은 로드맵으로 진행 중이며, 파일럿 확대 여부만 결정되면 즉시 착수할 수 있다.");
}

const out = process.argv[2] || "SafeAlert_Executive_Brief_v1.1.70.pptx";
pres.writeFile({ fileName: out }).then(() => console.log("wrote", out));
