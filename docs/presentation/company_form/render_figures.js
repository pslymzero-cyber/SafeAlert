// 사내 폼 보고본에 넣는 삽화를 굽는다.
//   node render_figures.js
// 산출물 — assets/fig_*.png (덱 빌드가 이 파일들을 읽는다)
//   fig_radius · fig_pairs · fig_zone : figures.html 의 도식 (흰 배경, 사내 폼에 맞춘 밝은 색)
//   fig_aisle.jpg                    : 앱 배경 사진 원본을 어둡게 하지 않고 가로로 자른 것 (사진이라 JPEG)
const { chromium } = require("/opt/node22/lib/node_modules/playwright");
const sharp = require("sharp");
const path = require("path");
const fs = require("fs");

const HERE = __dirname;
const OUT = path.join(HERE, "assets");
const PHOTO = path.join(HERE, "..", "..", "..", "app", "src", "main", "res", "drawable-nodpi", "bg_epj.jpg");

(async () => {
  fs.mkdirSync(OUT, { recursive: true });

  // 사진 — 어두운 덱용 자산은 페이드가 들어가 있어 흰 배경에서 탁하다. 원본에서 다시 자른다.
  const meta = await sharp(PHOTO).metadata();
  const cropH = Math.round(meta.width / 1.6);
  await sharp(PHOTO)
    .extract({ left: 0, top: Math.max(0, Math.round((meta.height - cropH) * 0.78)), width: meta.width, height: Math.min(cropH, meta.height) })
    .resize(1200)
    .jpeg({ quality: 86 })
    .toFile(path.join(OUT, "fig_aisle.jpg"));

  const browser = await chromium.launch({ executablePath: "/opt/pw-browsers/chromium" });
  const page = await browser.newPage({ viewport: { width: 900, height: 700 }, deviceScaleFactor: 3 });
  await page.goto("file://" + path.join(HERE, "figures.html"), { waitUntil: "load" });
  await page.waitForTimeout(400);
  for (const id of ["radius", "pairs", "zone"]) {
    const el = page.locator("#" + id);
    const box = await el.boundingBox();
    if (!box) throw new Error(`#${id} 를 찾지 못했다`);
    await el.screenshot({ path: path.join(OUT, `fig_${id}.png`) });
    console.log(`fig_${id}.png`, Math.round(box.width) + "x" + Math.round(box.height), "css px");
  }
  await browser.close();
  const m = await sharp(path.join(OUT, "fig_aisle.jpg")).metadata();
  console.log("fig_aisle.jpg", m.width + "x" + m.height);
})();
