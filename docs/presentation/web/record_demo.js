// 시뮬레이터 시연 영상을 굽는다. build_page.js 로 safealert-simulator.html 을 만든 뒤 실행한다.
//   node record_demo.js
// 산출물
//   web/safealert-sim.mp4   — 파워포인트에 삽입되는 H.264 영상 (경보음 포함)
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

// 뷰포트는 넉넉히 잡고, 녹화 뒤 시뮬레이터 패널 위치를 재서 그만큼 잘라낸다.
// 레이아웃이 바뀌어도 상수를 고칠 일이 없다.
const W = 1440, H = 900, MARGIN = 28;
const BG = "0x0B1220";   // 잘라낸 뒤 16:9 로 채우는 색 — 슬라이드 배경과 같다
const POSTER_AT = "5";   // TTC 선발령이 떠 있는 지점(초)


// sim.js 의 beep() 을 오프라인에서 그대로 재현한다.
//   square 파형 · 위험 1180Hz / 경고 760Hz · 10ms 상승 후 150ms 지수 감쇠 · 180ms 에서 정지
// 헤드리스 크로미움이 오디오를 녹화하지 못하므로, 소리가 난 시각만 받아 여기서 다시 만든다.
function writeBeepTrack(marks, durSec, outPath) {
  const RATE = 44100, PEAK = 0.5;
  const n = Math.ceil(durSec * RATE);
  const buf = new Float32Array(n);
  for (const m of marks) {
    const start = Math.round(m.t * RATE);
    if (start < 0 || start >= n) continue;
    const len = Math.min(Math.round(0.18 * RATE), n - start);
    for (let i = 0; i < len; i++) {
      const t = i / RATE;
      // 0.0001 → 0.08 (10ms) → 0.0001 (다음 150ms), 둘 다 지수 곡선
      const g = t < 0.01
        ? 0.0001 * Math.pow(800, t / 0.01)
        : 0.08 * Math.pow(0.00125, Math.min((t - 0.01) / 0.15, 1));
      buf[start + i] += Math.sign(Math.sin(2 * Math.PI * m.f * t)) * g;
    }
  }
  let peak = 0;
  for (let i = 0; i < n; i++) peak = Math.max(peak, Math.abs(buf[i]));
  const k = peak > 0 ? PEAK / peak : 0;   // 슬라이드쇼에서 들리도록 정규화한다

  const data = Buffer.alloc(n * 2);
  for (let i = 0; i < n; i++) data.writeInt16LE(Math.max(-32767, Math.min(32767, Math.round(buf[i] * k * 32767))), i * 2);
  const head = Buffer.alloc(44);
  head.write("RIFF", 0); head.writeUInt32LE(36 + data.length, 4); head.write("WAVE", 8);
  head.write("fmt ", 12); head.writeUInt32LE(16, 16); head.writeUInt16LE(1, 20); head.writeUInt16LE(1, 22);
  head.writeUInt32LE(RATE, 24); head.writeUInt32LE(RATE * 2, 28); head.writeUInt16LE(2, 32); head.writeUInt16LE(16, 34);
  head.write("data", 36); head.writeUInt32LE(data.length, 40);
  fs.writeFileSync(outPath, Buffer.concat([head, data]));
}

(async () => {
  if (!fs.existsSync(PAGE)) throw new Error("safealert-simulator.html 이 없다. 먼저 node build_page.js 를 실행한다");
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "safealert-vid-"));

  const browser = await chromium.launch({ executablePath: "/opt/pw-browsers/chromium" });
  const t0 = Date.now();   // 녹화 시작 기준. 경보음 시각을 이 지점 기준 오프셋으로 잡는다.
  const ctx = await browser.newContext({
    viewport: { width: W, height: H }, deviceScaleFactor: 1,
    recordVideo: { dir, size: { width: W, height: H } },
  });
  // 헤드리스 크로미움은 오디오를 녹화하지 않는다. 그래서 소리를 직접 담는 대신,
  // 시뮬레이터가 경보음을 낸 시각을 받아 적어 두고 나중에 같은 음을 그 자리에 얹는다.
  await ctx.addInitScript(() => {
    window.__beeps = [];
    const orig = AudioContext.prototype.createOscillator;
    AudioContext.prototype.createOscillator = function () {
      const osc = orig.call(this);
      const start = osc.start.bind(osc);
      osc.start = function (...a) {
        window.__beeps.push({ t: Date.now(), f: osc.frequency.value });
        return start(...a);
      };
      return osc;
    };
  });
  const p = await ctx.newPage();
  await p.goto("file://" + PAGE, { waitUntil: "load" });
  // 이 페이지에는 시뮬레이터 말고 아무것도 없다. 여백만 지우고 패널 위치를 잰다.
  await p.evaluate(() => { document.body.style.padding = "0"; });
  await p.waitForTimeout(400);
  const box = await p.evaluate(() => {
    const b = document.querySelector(".sim").getBoundingClientRect();
    return { x: b.x, y: b.y, w: b.width, h: b.height };
  });

  const wait = (ms) => p.waitForTimeout(ms);
  const setD = (v) => p.evaluate((v) => {
    const s = document.getElementById("slider");
    s.value = v;
    s.dispatchEvent(new Event("input"));
  }, v);

  await p.click("#btnSound");   // 시뮬레이터 경보음 ON (기본값은 OFF 다)
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

  const beeps = await p.evaluate(() => window.__beeps);
  const video = p.video();
  await ctx.close();
  const tEnd = Date.now();
  await browser.close();
  const webm = await video.path();

  // 짝수 픽셀만 받는 코덱이라 자르는 폭·높이·좌표를 모두 짝수로 맞춘다.
  const ev = (n) => 2 * Math.round(n / 2);
  const cw = Math.min(ev(box.w + MARGIN * 2), W);
  const ch = Math.min(ev(box.h + MARGIN * 2), H);
  const cx = ev(Math.max(0, Math.min(box.x - MARGIN, W - cw)));
  const cy = ev(Math.max(0, Math.min(box.y - MARGIN, H - ch)));
  const padW = ev(ch * 16 / 9);   // 16:9 로 좌우를 앱 배경색으로 채운다

  const mute = path.join(dir, "mute.mp4");
  execFileSync("ffmpeg", ["-v", "error", "-y", "-i", webm,
    "-vf", `crop=${cw}:${ch}:${cx}:${cy},pad=${padW}:${ch}:(ow-iw)/2:0:${BG}`,
    "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-crf", "23", "-r", "30",
    "-movflags", "+faststart", "-an", mute]);

  const dur = Number(execFileSync("ffprobe", ["-v", "error", "-show_entries", "format=duration",
    "-of", "default=nw=1:nk=1", mute]).toString().trim());
  // 녹화는 컨텍스트를 만든 뒤 조금 늦게 시작한다. 그 지연만큼 경보음 시각을 당긴다.
  const lag = (tEnd - t0) / 1000 - dur;
  const wav = path.join(dir, "beeps.wav");
  writeBeepTrack(beeps.map((b) => ({ t: (b.t - t0) / 1000 - lag, f: b.f })), dur, wav);

  execFileSync("ffmpeg", ["-v", "error", "-y", "-i", mute, "-i", wav,
    "-c:v", "copy", "-c:a", "aac", "-b:a", "96k", "-shortest",
    "-movflags", "+faststart", MP4]);
  execFileSync("ffmpeg", ["-v", "error", "-y", "-ss", POSTER_AT, "-i", MP4, "-frames:v", "1", "-q:v", "3", POSTER]);
  fs.rmSync(dir, { recursive: true, force: true });

  const mb = (f) => (fs.statSync(f).size / 1048576).toFixed(2) + " MB";
  console.log("safealert-sim.mp4", mb(MP4), `${padW}x${ch}`, `경보음 ${beeps.length}회`);
  console.log("sim-poster.jpg", mb(POSTER));
})();
