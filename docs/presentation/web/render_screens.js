// 앱 화면 목업을 PNG 로 굽는다. build_page.js 로 safealert-screens.html 을 만든 뒤 실행한다.
//   node render_screens.js
// 산출물
//   web/screens.png              — 동작·설정 화면 가로 배치 (그대로 슬라이드에 붙일 수 있는 한 장)
//   assets/screen_running.png    — 동작 화면 단독 (build_deck.js 가 쓴다)
//   assets/screen_settings.png   — 설정 화면 단독 (build_deck.js 가 쓴다)
const { chromium } = require("/opt/node22/lib/node_modules/playwright");
const path = require("path");
const fs = require("fs");

const WEB = __dirname;
const ASSETS = path.join(WEB, "..", "assets");
const PAGE = path.join(WEB, "safealert-screens.html");

(async () => {
  if (!fs.existsSync(PAGE)) throw new Error("safealert-screens.html 이 없다. 먼저 node build_page.js 를 실행한다");
  fs.mkdirSync(ASSETS, { recursive: true });

  const browser = await chromium.launch({ executablePath: "/opt/pw-browsers/chromium" });
  // DPR 3 — 슬라이드에서 5인치 남짓으로 배치해도 픽셀이 뭉개지지 않는 배율이다.
  const page = await browser.newPage({ viewport: { width: 1000, height: 900 }, deviceScaleFactor: 3 });
  await page.goto("file://" + PAGE, { waitUntil: "load" });
  await page.waitForTimeout(500);

  const shots = [
    [".screens", path.join(WEB, "screens.png")],
    ["figure:nth-of-type(1) .phone", path.join(ASSETS, "screen_running.png")],
    ["figure:nth-of-type(2) .phone", path.join(ASSETS, "screen_settings.png")],
  ];
  for (const [sel, out] of shots) {
    const el = page.locator(sel);
    const box = await el.boundingBox();
    if (!box) throw new Error(`${sel} 을 찾지 못했다`);
    await el.screenshot({ path: out });
    console.log(path.basename(out), Math.round(box.width) + "x" + Math.round(box.height), "css px");
  }
  await browser.close();
})();
