// 시뮬레이터 시연 영상을 굽는다. build_page.js 로 safealert-simulator.html 을 만든 뒤 실행한다.
//   node record_demo.js
// 산출물
//   web/safealert-sim.mp4   — 파워포인트에 삽입되는 H.264 영상 (무음)
//   web/sim-poster.jpg      — 슬라이드에 보이는 표지 프레임 (TTC 선발령 순간)
// ffmpeg 이 필요하다.
const { chromium } = require("/opt/node22/lib/node_modules/playwright");
const { execFileSync } = require("child_process");
const path = require("path");
const fs = require("fs");
const os = require("os");

const WEB = __dirname;
const PAGE = path.join(WEB, "safealert-simulator.html");
const MP4 = path.join(WEB, "safealert-sim.mp4");
const POSTER = path.join(WEB, "sim-poster.jpg");

// 시뮬레이터 패널의 자연 크기는 1084x892 다. 이보다 넓히면 폰 목업이 잘리므로
// 폭은 고정하고 사방에 여백만 둔다.
const PANEL_W = 1084, PANEL_H = 892, PAD_X = 32, PAD_Y = 28;
const W = PANEL_W + PAD_X * 2, H = PANEL_H + PAD_Y * 2;
// 16:9 로 좌우를 앱 배경색으로 채운다 — 슬라이드 배경과 같은 색이라 이음매가 보이지 않는다.
const PAD_W = 2 * Math.round(H * 16 / 9 / 2), BG = "0x0B1220";
const POSTER_AT = "5";   // TTC 선발령이 떠 있는 지점(초)

(async () => {
  if (!fs.existsSync(PAGE)) throw new Error("safealert-simulator.html 이 없다. 먼저 node build_page.js 를 실행한다");
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "safealert-vid-"));

  const browser = await chromium.launch({ executablePath: "/opt/pw-browsers/chromium" });
  const ctx = await browser.newContext({
    viewport: { width: W, height: H }, deviceScaleFactor: 1,
    recordVideo: { dir, size: { width: W, height: H } },
  });
  const p = await ctx.newPage();
  await p.goto("file://" + PAGE, { waitUntil: "load" });
  // 영상에는 시뮬레이터 패널만 담는다. 머리말 · 규칙 카드 · 주석은 감춘다.
  await p.evaluate(({ PAD_X, PAD_Y }) => {
    [".top", ".rules", ".foot", ".sim-note"].forEach((s) => {
      const e = document.querySelector(s);
      if (e) e.style.display = "none";
    });
    document.body.style.padding = PAD_Y + "px " + PAD_X + "px";
    document.querySelectorAll(".shell").forEach((e) => { e.style.maxWidth = "none"; e.style.padding = "0"; });
  }, { PAD_X, PAD_Y });

  const wait = (ms) => p.waitForTimeout(ms);
  const setD = (v) => p.evaluate((v) => {
    const s = document.getElementById("slider");
    s.value = v;
    s.dispatchEvent(new Event("input"));
  }, v);

  await wait(1600);
  // 1) 전진 주행하는 보행자가 접근 → TTC 선발령
  await p.click('#ctlState .chipbtn[data-v="FORWARD"]'); await wait(900);
  await p.click("#btnRun"); await wait(5200);
  await p.click("#btnRun"); await wait(1400);

  // 2) 내 단말 신호 약함 → 상대가 먼저 감지 → 협력 격상
  await setD(16); await wait(700);
  await p.click("#btnWeak"); await wait(1100);
  await p.click("#btnRun"); await wait(4200);
  await p.click("#btnRun"); await wait(1600);
  await p.click("#btnWeak"); await wait(600);

  // 3) 후진 특수경보
  await setD(6); await p.click('#ctlState .chipbtn[data-v="REVERSE"]'); await wait(2600);

  // 4) 세이프존 진입 → 전면 억제
  await p.click("#btnZone"); await wait(2600);
  await p.click("#btnZone"); await wait(1200);

  const video = p.video();
  await ctx.close();
  await browser.close();
  const webm = await video.path();

  execFileSync("ffmpeg", ["-v", "error", "-y", "-i", webm,
    "-vf", `pad=${PAD_W}:${H}:(ow-iw)/2:0:${BG}`,
    "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-crf", "23", "-r", "30",
    "-movflags", "+faststart", "-an", MP4]);
  execFileSync("ffmpeg", ["-v", "error", "-y", "-ss", POSTER_AT, "-i", MP4, "-frames:v", "1", "-q:v", "3", POSTER]);
  fs.rmSync(dir, { recursive: true, force: true });

  const mb = (f) => (fs.statSync(f).size / 1048576).toFixed(2) + " MB";
  console.log("safealert-sim.mp4", mb(MP4), `${PAD_W}x${H}`);
  console.log("sim-poster.jpg", mb(POSTER));
})();
