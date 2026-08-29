// 앱 실제 자산 → 슬라이드용 PNG 파이프라인
// - VectorDrawable(ic_*.xml) → SVG → PNG
// - 현장 사진 크롭·라운딩·페이드
const fs = require("fs"), path = require("path");
const sharp = require("sharp");

const RES = path.join(__dirname, "..", "..", "app", "src", "main", "res");   // 저장소 상대 경로
const OUT = path.join(__dirname, "assets");
fs.mkdirSync(OUT, { recursive: true });
const BG = "#0B1220";

// colors.xml 의 색 토큰 — 아이콘이 @color/… 로 참조한다
const COLORS = (() => {
  const xml = fs.readFileSync(path.join(RES, "values", "colors.xml"), "utf8");
  const map = {}; const re = /<color name="([^"]+)">(#[0-9A-Fa-f]+)<\/color>/g; let m;
  while ((m = re.exec(xml))) map[m[1]] = m[2];
  return map;
})();
function resolveColor(v) {
  if (!v) return null;
  if (v.startsWith("@color/")) return COLORS[v.slice(7)] || null;
  return v;
}

// ── VectorDrawable → SVG ────────────────────────────────────────────
function vdToSvg(file) {
  const xml = fs.readFileSync(file, "utf8");
  const vw = (xml.match(/android:viewportWidth="([\d.]+)"/) || [, "24"])[1];
  const vh = (xml.match(/android:viewportHeight="([\d.]+)"/) || [, "24"])[1];
  const paths = [];
  const re = /<path\b([\s\S]*?)\/>/g;
  let m;
  while ((m = re.exec(xml))) {
    const a = m[1];
    const d = (a.match(/android:pathData="([\s\S]*?)"/) || [])[1];
    if (!d) continue;
    const fill = resolveColor((a.match(/android:fillColor="([^"]+)"/) || [, null])[1]) || "none";
    const alpha = (a.match(/android:fillAlpha="([\d.]+)"/) || [, "1"])[1];
    const rule = (a.match(/android:fillType="(\w+)"/) || [, "nonZero"])[1];
    let f = fill, op = alpha;
    if (/^#[0-9A-Fa-f]{8}$/.test(fill)) {            // #AARRGGBB
      op = String((parseInt(fill.slice(1, 3), 16) / 255) * parseFloat(alpha));
      f = "#" + fill.slice(3);
    }
    paths.push(`<path d="${d.replace(/\s+/g, " ").trim()}" fill="${f}" fill-opacity="${op}" fill-rule="${rule === "evenOdd" ? "evenodd" : "nonzero"}"/>`);
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${vw} ${vh}" width="${vw}" height="${vh}">${paths.join("")}</svg>`;
}

async function icon(name, px) {
  const svg = vdToSvg(path.join(RES, "drawable", name + ".xml"));
  const out = path.join(OUT, name + ".png");
  await sharp(Buffer.from(svg)).resize(px, px, { fit: "contain", background: { r: 0, g: 0, b: 0, alpha: 0 } }).png().toFile(out);
  return out;
}

// ── 사진: 크롭 + 라운딩 ─────────────────────────────────────────────
async function photo(src, name, w, h, opts = {}) {
  const r = opts.radius === undefined ? 22 : opts.radius;
  let img = sharp(src).resize(w, h, { fit: "cover", position: opts.position || "centre" });
  if (opts.darken) {
    img = sharp(await img.png().toBuffer()).composite([{
      input: Buffer.from(`<svg width="${w}" height="${h}"><rect width="${w}" height="${h}" fill="${BG}" fill-opacity="${opts.darken}"/></svg>`),
      blend: "over",
    }]);
  }
  let buf = await img.png().toBuffer();
  if (r > 0) {
    buf = await sharp(buf).composite([{
      input: Buffer.from(`<svg width="${w}" height="${h}"><rect width="${w}" height="${h}" rx="${r}" ry="${r}" fill="#fff"/></svg>`),
      blend: "dest-in",
    }]).png().toBuffer();
  }
  if (opts.fadeLeft) {   // 좌측 가장자리를 배경색으로 녹인다
    const fade = `<svg width="${w}" height="${h}"><defs><linearGradient id="g" x1="0" x2="1">
      <stop offset="0" stop-color="${BG}" stop-opacity="1"/><stop offset="${opts.fadeLeft}" stop-color="${BG}" stop-opacity="0"/>
    </linearGradient></defs><rect width="${w}" height="${h}" fill="url(#g)"/></svg>`;
    buf = await sharp(buf).composite([{ input: Buffer.from(fade), blend: "over" }]).png().toBuffer();
  }
  if (opts.fadeBottom) {
    const fade = `<svg width="${w}" height="${h}"><defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1">
      <stop offset="${1 - opts.fadeBottom}" stop-color="${BG}" stop-opacity="0"/><stop offset="1" stop-color="${BG}" stop-opacity="1"/>
    </linearGradient></defs><rect width="${w}" height="${h}" fill="url(#g)"/></svg>`;
    buf = await sharp(buf).composite([{ input: Buffer.from(fade), blend: "over" }]).png().toBuffer();
  }
  const out = path.join(OUT, name + ".png");
  await sharp(buf).png({ quality: 90, compressionLevel: 9 }).toFile(out);
  return out;
}

// ── 브랜드 마크: 스플래시에서 로고 영역만 오려낸다 ──────────────────
async function logo() {
  const src = path.join(RES, "drawable", "bg_splash.png");   // 704x1430
  const buf = await sharp(src).extract({ left: 40, top: 430, width: 624, height: 600 }).png().toBuffer();
  const out = path.join(OUT, "brandmark.png");
  await sharp(buf).resize(624, 600).png().toFile(out);
  return out;
}

(async () => {
  const icons = [["ic_forklift", 256], ["ic_epj", 256], ["ic_walker", 256], ["ic_antenna", 256],
                 ["ic_bell", 256], ["ic_signal_bars", 256], ["ic_mute", 256], ["ic_bulb", 256],
                 ["ic_share_out", 256], ["ic_receive_in", 256], ["ic_search", 256]];
  for (const [n, p] of icons) { try { await icon(n, p); } catch (e) { console.log("skip", n, e.message); } }

  const D = path.join(RES, "drawable"), N = path.join(RES, "drawable-nodpi");
  await sharp(path.join(D, "bg_splash.png")).extract({ left: 0, top: 250, width: 704, height: 900 })
    .composite([{ input: Buffer.from(`<svg width="704" height="900"><defs><linearGradient id="g" x1="0" x2="1">
      <stop offset="0" stop-color="${BG}" stop-opacity="1"/><stop offset="0.42" stop-color="${BG}" stop-opacity="0"/></linearGradient>
      <linearGradient id="v" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${BG}" stop-opacity="0.85"/>
      <stop offset="0.18" stop-color="${BG}" stop-opacity="0"/><stop offset="0.82" stop-color="${BG}" stop-opacity="0"/>
      <stop offset="1" stop-color="${BG}" stop-opacity="0.9"/></linearGradient></defs>
      <rect width="704" height="900" fill="url(#g)"/><rect width="704" height="900" fill="url(#v)"/></svg>`), blend: "over" }])
    .png().toFile(path.join(OUT, "hero_splash.png"));
  await photo(path.join(N, "bg_forklift.jpg"), "hero_forklift", 700, 1000, { radius: 0, fadeLeft: 0.45 });
  await photo(path.join(N, "bg_forklift.jpg"), "role_forklift", 420, 420, { radius: 16, darken: 0.15 });
  await photo(path.join(N, "bg_epj.jpg"), "role_epj", 420, 420, { radius: 16, darken: 0.15 });
  await photo(path.join(N, "bg_walker.jpg"), "role_walker", 420, 420, { radius: 16, darken: 0.15 });
  await photo(path.join(N, "bg_epj.jpg"), "aisle", 620, 900, { radius: 18, darken: 0.2 });
  await photo(path.join(D, "bg_main.png"), "phone_bg", 420, 880, { radius: 0, darken: 0.35 });
  await photo(path.join(D, "bg_main.png"), "closing", 1500, 850, { radius: 0, darken: 0.72, position: "top", fadeBottom: 0.45 });
  await photo(path.join(N, "bg_walker.jpg"), "band_walker", 1500, 420, { radius: 0, darken: 0.55, position: "centre" });
  await logo();

  const list = fs.readdirSync(OUT).sort();
  console.log(list.length + " assets:", list.join(" "));
})();
