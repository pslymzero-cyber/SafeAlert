// SafeAlert 경영진 보고 웹페이지 빌더
// index.template.html 의 __ASSET_*__ 자리표시자를 앱 리소스에서 만든 data URI 로 치환한다.
const fs = require("fs");
const path = require("path");
const sharp = require("sharp");

const ROOT = path.join(__dirname, "..", "..", "..");
const RES = path.join(ROOT, "app", "src", "main", "res");
const D = path.join(RES, "drawable");
const N = path.join(RES, "drawable-nodpi");
const BG = "#0B1220";

// colors.xml 토큰 (아이콘이 @color/… 로 참조한다)
const COLORS = (() => {
  const xml = fs.readFileSync(path.join(RES, "values", "colors.xml"), "utf8");
  const map = {}; const re = /<color name="([^"]+)">(#[0-9A-Fa-f]+)<\/color>/g; let m;
  while ((m = re.exec(xml))) map[m[1]] = m[2];
  return map;
})();
const resolveColor = (v) => (!v ? null : v.startsWith("@color/") ? COLORS[v.slice(7)] || null : v);

function vdToSvg(file) {
  const xml = fs.readFileSync(file, "utf8");
  const vw = (xml.match(/android:viewportWidth="([\d.]+)"/) || [, "24"])[1];
  const vh = (xml.match(/android:viewportHeight="([\d.]+)"/) || [, "24"])[1];
  const out = [];
  const re = /<path\b([\s\S]*?)\/>/g; let m;
  while ((m = re.exec(xml))) {
    const a = m[1];
    const d = (a.match(/android:pathData="([\s\S]*?)"/) || [])[1];
    if (!d) continue;
    const fill = resolveColor((a.match(/android:fillColor="([^"]+)"/) || [, null])[1]) || "none";
    const alpha = (a.match(/android:fillAlpha="([\d.]+)"/) || [, "1"])[1];
    const rule = (a.match(/android:fillType="(\w+)"/) || [, "nonZero"])[1];
    let f = fill, op = alpha;
    if (/^#[0-9A-Fa-f]{8}$/.test(fill)) { op = String((parseInt(fill.slice(1, 3), 16) / 255) * parseFloat(alpha)); f = "#" + fill.slice(3); }
    out.push(`<path d="${d.replace(/\s+/g, " ").trim()}" fill="${f}" fill-opacity="${op}" fill-rule="${rule === "evenOdd" ? "evenodd" : "nonzero"}"/>`);
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${vw} ${vh}" width="${vw}" height="${vh}">${out.join("")}</svg>`;
}

const dataUri = (buf, mime) => `data:${mime};base64,${buf.toString("base64")}`;

async function icon(name, px = 128) {
  const buf = await sharp(Buffer.from(vdToSvg(path.join(D, name + ".xml"))))
    .resize(px, px, { fit: "contain", background: { r: 0, g: 0, b: 0, alpha: 0 } }).png({ compressionLevel: 9 }).toBuffer();
  return dataUri(buf, "image/png");
}

async function photo(src, w, h, o = {}) {
  let img = sharp(src).resize(w, h, { fit: "cover", position: o.position || "centre" });
  if (o.scrim) {
    img = sharp(await img.png().toBuffer()).composite([{
      input: Buffer.from(`<svg width="${w}" height="${h}"><defs><linearGradient id="g" x1="0" y1="${o.vertical ? 0 : 0}" x2="${o.vertical ? 0 : 1}" y2="${o.vertical ? 1 : 0}">
        <stop offset="0" stop-color="${BG}" stop-opacity="${o.from ?? 0.92}"/><stop offset="1" stop-color="${BG}" stop-opacity="${o.to ?? 0.35}"/></linearGradient></defs>
        <rect width="${w}" height="${h}" fill="url(#g)"/></svg>`), blend: "over",
    }]);
  }
  return dataUri(await img.jpeg({ quality: o.q || 80, mozjpeg: true }).toBuffer(), "image/jpeg");
}

(async () => {
  const A = {};
  A.SPLASH = dataUri(await sharp(path.join(D, "bg_splash.png"))
    .extract({ left: 0, top: 300, width: 704, height: 880 }).resize(704, 880)
    .jpeg({ quality: 84, mozjpeg: true }).toBuffer(), "image/jpeg");
  A.FLOOR   = await photo(path.join(N, "bg_forklift.jpg"), 1500, 720, { scrim: true, from: 0.9, to: 0.42, q: 76 });
  A.AISLE   = await photo(path.join(N, "bg_epj.jpg"), 620, 780, { q: 78 });
  A.PHONE   = await photo(path.join(D, "bg_main.png"), 460, 960, { q: 78 });
  A.CLOSING = await photo(path.join(D, "bg_main.png"), 1500, 620, { position: "top", scrim: true, vertical: true, from: 0.72, to: 0.97, q: 74 });

  for (const [key, file] of [["IC_FORKLIFT", "ic_forklift"], ["IC_WALKER", "ic_walker"], ["IC_EPJ", "ic_epj"],
                             ["IC_BELL", "ic_bell"], ["IC_ANTENNA", "ic_antenna"], ["IC_SIGNAL", "ic_signal_bars"],
                             ["IC_MUTE", "ic_mute"], ["IC_BULB", "ic_bulb"], ["IC_OUT", "ic_share_out"],
                             ["IC_IN", "ic_receive_in"], ["IC_SEARCH", "ic_search"]]) {
    A[key] = await icon(file);
  }

  let html = fs.readFileSync(path.join(__dirname, "index.template.html"), "utf8");
  const missing = [];
  html = html.replace(/__ASSET_([A-Z_]+)__/g, (_, k) => { if (!A[k]) { missing.push(k); return ""; } return A[k]; });
  if (missing.length) throw new Error("자리표시자에 대응하는 자산 없음: " + [...new Set(missing)].join(", "));

  const out = process.argv[2] || path.join(__dirname, "safealert-brief.html");
  fs.writeFileSync(out, html);
  console.log(`wrote ${out}  (${(Buffer.byteLength(html) / 1024 / 1024).toFixed(2)} MB, 자산 ${Object.keys(A).length}종)`);
})();
