/*
 * SafeAlert 관제 — read-only live view of one site
 * Shows active SOS requests (full-screen alert + siren until acknowledged in the browser), SOS resolved in the last
 * 24 hours, devices monitoring now (and lost contact), and collision alerts by ID, filtered by floor / process.
 * It only reads the Realtime Database with the database secret kept in Script Properties; it cannot change or switch
 * off anything.
 *
 * Setup. Screen labels are written '한국어(English)'. Every step says why, what Google may warn, and what you have after it.
 *
 *  1. Sign in to Google with an account that is NOT the SOS mail script's account (safealertfs@gmail.com).
 *     Why: this page reads Firebase through UrlFetch, which has a daily quota per account (a consumer account: about
 *     20,000 calls a day). With 'Execute as' = 'User accessing the web app' (step 6) every viewer's own quota is used, so
 *     the mail account's quota stays free for SOS mails. Do not list the mail account in ADMIN_EMAILS.
 *     Warning: none. After it: you are signed in with the viewing account.
 *  2. Open script.google.com → '새 프로젝트(New project)'. Click '제목 없는 프로젝트(Untitled project)' and rename it
 *     'SafeAlert 관제'.
 *     Why: the permission screens of step 7 show this name, so a rename lets you recognise it.
 *     After it: an empty project named SafeAlert 관제.
 *  3. Replace the default code of Code.gs with this whole file and save (Ctrl+S). Then '파일(Files)' '+' →
 *     'HTML' → name it exactly Index → replace its content with Index.html → save.
 *     Why: Code.gs reads the database, Index is the page it serves; the name Index is what Code.gs asks for.
 *     After it: two files, Code.gs and Index.html.
 *  4. Gear '프로젝트 설정(Project Settings)' → '시간대(Time zone)' = (GMT+09:00) 서울(Seoul).
 *     Why: alert records sit under the sending phone's Korean date.
 *     After it: the project clock is Korean time.
 *  5. Same page, '스크립트 속성(Script Properties)' → '스크립트 속성 추가(Add script property)' for each entry, then
 *     '스크립트 속성 저장(Save script properties)':
 *     - FIREBASE_DB_URL    : same value as in the SOS mail script
 *     - FIREBASE_DB_SECRET : same value as in the SOS mail script
 *     - ADMIN_EMAILS       : Google accounts that may open this page, comma separated. Include your own account,
 *                            otherwise the page refuses you too.
 *     - SITES              : site codes to show, comma separated (e.g. WF11). The first one opens by default.
 *     - FIREBASE_ROOT      : (optional) database root; empty means wf11.
 *     Why: the secret stays on Google's side and never reaches the page. After it: the page can read the database.
 *  6. Top right '배포(Deploy)' > '새 배포(New deployment)' → gear beside '유형 선택(Select type)' → '웹 앱(Web app)'.
 *     One admin: '다음 사용자 인증 정보로 실행(Execute as)' = '나(Me)',
 *       '액세스 권한이 있는 사용자(Who has access)' = '나만(Only myself)'.
 *     Several admins (recommended): '다음 사용자 인증 정보로 실행(Execute as)' = '웹 앱에 액세스하는 사용자(User accessing
 *       the web app)', '액세스 권한이 있는 사용자(Who has access)' = 'Google 계정이 있는 모든 사용자(Anyone with Google
 *       account)', and list every admin in ADMIN_EMAILS (anyone not listed is refused by the page).
 *     Why: the first choice shares one quota; the second gives each viewer their own.
 *     Warning: Google asks for permission on the next step. After it: a deployment exists, no URL used yet.
 *  7. '배포(Deploy)' → '액세스 승인(Authorize access)' → choose the account. Google shows
 *     'Google에서 확인하지 않은 앱(Google hasn't verified this app)': '고급(Advanced)' →
 *     'SafeAlert 관제(으)로 이동(안전하지 않음)(Go to SafeAlert 관제 (unsafe))' → '허용(Allow)'.
 *     Why: the script reads Firebase with the secret, and a personal script Google has not reviewed always shows this
 *     warning. Each admin approves it once.
 *     After it: the deployment is live.
 *  8. Copy the '웹 앱(Web app)' URL ending in /exec. Add ?sc=WF11 to open a site directly. Bookmark it.
 *     After it: the bookmarked page opens for the accounts in ADMIN_EMAILS; others see a refusal.
 *
 * Updating later: paste the new files, save, then '배포(Deploy)' > '배포 관리(Manage deployments)' > pencil '수정(Edit)' >
 * '버전(Version)': '새 버전(New version)' > '배포(Deploy)'.
 * Why: this keeps the same URL ('새 배포(New deployment)' would make a new one and the bookmark would stay old).
 *
 * If the page says it used its daily read limit (quota), wait: it recovers within 24 hours. Reads are cached
 * (SOS 20 seconds, the rest 2 minutes) so a few open pages stay far below the limit.
 */

var ALIVE_MS = 15 * 60 * 1000;        // A session writes 'last' every 5 min; 15 min without one = contact lost
var WINDOW_MS = 24 * 3600 * 1000;     // SOS and sessions are read for the last 24 hours
var SOS_RELEASE_MS = 3600 * 1000;     // The app releases an unanswered SOS one hour after the server got it
var SOS_TTL_S = 20;                   // The SOS part is cached this long (seconds)
var SLOW_TTL_S = 120;                 // Sessions + alerts are cached this long
var FALLBACK_TTL_S = 21600;           // How long to remember that hb has no 'last' index (CacheService maximum, 6 h)
var MAX_ALERTS = 500;                 // Newest collision alert records sent to the page
var PUSH_CHARS = '-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz';
var DB_URL_RE = /^https:\/\/[A-Za-z0-9.-]+\/?$/;
var SC_RE = /^[A-Z0-9_-]{1,12}$/;
var ROOT_RE = /^[A-Za-z0-9_-]{1,32}$/;
var CODE_RE = /^[A-Z0-9]{1,4}$/;      // Floor / process code; same as the app's SiteScope.CODE_PATTERN
var TZ = 'Asia/Seoul';
// Equipment code → English name; same table as the SOS mail script (and the app's PitType.kt).
var PIT_NAMES = { CB: 'Counterbalance', RT: 'Reach Truck', HR: 'High Reach', OP: 'Order Picker',
  ST: 'Stacker', TT: 'Tow Tractor', EP: 'Electric Pallet Jack', WK: 'Walkie Stacker' };

function doGet(e) {
  var cfg = config_();
  var sc = String((e && e.parameter && e.parameter.sc) || '').toUpperCase();
  var t = HtmlService.createTemplateFromFile('Index');
  t.initialSc = SC_RE.test(sc) ? sc : '';   // only [A-Z0-9_-] reaches the page
  t.denied = !allowed_(cfg);
  t.viewer = viewer_();
  return t.evaluate()
    .setTitle('SafeAlert 관제')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

/**
 * Called by the page every refresh. Returns plain data only; nothing is written anywhere. Errors are fixed codes: the
 * URLs hold the secret, so exception text is never passed on.
 */
function getSnapshot(sc) {
  var cfg = config_();
  if (!allowed_(cfg)) return { ok: false, error: 'denied', viewer: viewer_() };   // before any cache read
  var dbUrl = cfg.get('FIREBASE_DB_URL');
  var secret = cfg.get('FIREBASE_DB_SECRET');
  var root = cfg.get('FIREBASE_ROOT') || 'wf11';
  var sites = list_(cfg.get('SITES').toUpperCase()).filter(function (s) { return SC_RE.test(s); });
  if (!DB_URL_RE.test(dbUrl) || !secret || !ROOT_RE.test(root) || !sites.length) {
    return { ok: false, error: 'setup' };
  }
  sc = String(sc || '').toUpperCase();
  if (sites.indexOf(sc) < 0) sc = sites[0];

  var now = Date.now();
  var base = dbUrl.replace(/\/$/, '') + '/' + root + '/';
  var auth = 'auth=' + encodeURIComponent(secret);
  var cache = cache_();
  var kSos = 'sos:' + root + ':' + sc, kSlow = 'slow:' + root + ':' + sc, kFb = 'hbfb:' + root + ':' + sc;
  var sosPart = cacheGet_(cache, kSos);
  var slowPart = cacheGet_(cache, kSlow);
  var fallback = !!cacheGet_(cache, kFb);

  var sosUrl = base + 'sos/' + sc + '.json?' + auth + '&orderBy=' + q_('"createdAt"') + '&startAt=' + (now - WINDOW_MS);   // sos/$sc has ".indexOn": ["createdAt"]
  var hbIdx = base + 'hb/' + sc + '.json?' + auth + '&orderBy=' + q_('"last"') + '&startAt=' + (now - WINDOW_MS);          // hb/$sc has ".indexOn": ["last"]
  // Session keys are push keys, so a key range is a time range and needs no index (used until the rules are deployed)
  var hbKey = base + 'hb/' + sc + '.json?' + auth + '&orderBy=' + q_('"$key"') + '&startAt=' + q_('"' + pushPrefix_(now - WINDOW_MS) + '"');
  // Alert records sit under the sending phone's local date (KST): always read yesterday and today
  var dayUrl = function (ms) { return base + 'alerts/' + sc + '/' + day_(ms) + '.json?' + auth; };

  var reqs = [];
  if (!sosPart) reqs.push({ url: sosUrl, muteHttpExceptions: true });
  if (!slowPart) {
    reqs.push({ url: fallback ? hbKey : hbIdx, muteHttpExceptions: true });
    reqs.push({ url: dayUrl(now - 24 * 3600 * 1000), muteHttpExceptions: true });
    reqs.push({ url: dayUrl(now), muteHttpExceptions: true });
  }
  var res = [];
  try {
    if (reqs.length) res = UrlFetchApp.fetchAll(reqs);
    var at = 0;
    var sosRes = sosPart ? null : res[at++];
    var hbRes = null, d1Res = null, d2Res = null;
    if (!slowPart) {
      hbRes = res[at++]; d1Res = res[at++]; d2Res = res[at++];
      // Rules without the hb 'last' index answer 400 "Index not defined": use the key range and remember that for a while
      if (!fallback && hbRes.getResponseCode() !== 200 && /Index not defined/.test(hbRes.getContentText() || '')) {
        hbRes = UrlFetchApp.fetch(hbKey, { muteHttpExceptions: true });
        cachePut_(cache, kFb, '1', FALLBACK_TTL_S);
      }
    }
  } catch (err) {
    // Never pass err on: its text can carry the URL (the secret). Only the daily-limit message is told apart.
    return { ok: false, error: /too many times/i.test(String(err && err.message)) ? 'quota' : 'fetch' };
  }
  var all = [sosRes, hbRes, d1Res, d2Res];
  for (var i = 0; i < all.length; i++) {
    if (all[i] && all[i].getResponseCode() !== 200) return { ok: false, error: 'http', code: all[i].getResponseCode() };
  }
  var parse = function (r) { return JSON.parse(r.getContentText() || 'null'); };

  if (!sosPart) {
    sosPart = { sos: sos_(parse(sosRes), now) };
    cachePut_(cache, kSos, JSON.stringify(sosPart), SOS_TTL_S);
  }
  if (!slowPart) {
    var alerts = alerts_(parse(d1Res), sc).concat(alerts_(parse(d2Res), sc))
      .filter(function (a) { return a.t >= now - WINDOW_MS; });
    alerts.sort(function (a, b) { return b.t - a.t; });
    slowPart = { sessions: sessions_(parse(hbRes), now), alerts: alerts.slice(0, MAX_ALERTS), alertTotal: alerts.length };
    cachePut_(cache, kSlow, JSON.stringify(slowPart), SLOW_TTL_S);
  }

  // Time-dependent fields are recomputed here, so a cached part never shows an old stale / alive value
  sosPart.sos.forEach(function (s) { s.stale = isStale_(s, now); });
  slowPart.sessions.forEach(function (s) { s.alive = now - s.last <= ALIVE_MS; });

  return {
    ok: true,
    sc: sc,
    sites: sites,
    now: now,
    viewer: viewer_(),
    sos: sosPart.sos,
    sessions: slowPart.sessions,
    alerts: slowPart.alerts,
    alertTotal: slowPart.alertTotal
  };
}

// Still active an hour after it was created: the writer's phone may be gone (the app normally releases it by then)
function isStale_(s, now) { return !!s.active && now - s.createdAt > SOS_RELEASE_MS + 5 * 60 * 1000; }

function sos_(node, now) {
  var out = [];
  each_(node, function (key, r) {
    if (typeof r.createdAt !== 'number') return;
    var s = {
      key: key,
      name: str_(r.name),
      who: roleName_(str_(r.role), str_(r.name)),
      trigger: r.trigger === 'fall' ? '넘어짐' : r.trigger === 'still' ? '움직임 없음' : '알 수 없음',
      beacon: str_(r.beacon),
      floor: code_(r.floor),
      proc: code_(r.proc),
      createdAt: r.createdAt,
      active: r.status === 'active',
      resolvedAt: typeof r.resolvedAt === 'number' ? r.resolvedAt : 0,
      auto: r.reason === 'auto'
    };
    s.stale = isStale_(s, now);
    out.push(s);
  });
  out.sort(function (a, b) { return (b.active - a.active) || (b.createdAt - a.createdAt); });
  return out;
}

/**
 * One row per monitoring session still open. A phone that restarted without stopping leaves an older open session behind:
 * per uid only the newest session counts (ended ones included), so one phone counts once. uid is used here only and is
 * never sent to the page. Sessions without a uid are kept as they are.
 */
function sessions_(node, now) {
  var newest = {};
  each_(node, function (key, s) {
    if (typeof s.uid === 'string' && typeof s.start === 'number' && !(newest[s.uid] >= s.start)) newest[s.uid] = s.start;
  });
  var out = [];
  each_(node, function (key, s) {
    if (typeof s.last !== 'number' || typeof s.start !== 'number') return;
    if (typeof s.end === 'number') return;   // stopped normally
    if (typeof s.uid === 'string' && s.start < newest[s.uid]) return;   // an older leftover of the same phone
    out.push({
      role: str_(s.role),
      floor: code_(s.floor),
      proc: code_(s.proc),
      start: s.start,
      last: s.last,
      alive: now - s.last <= ALIVE_MS
    });
  });
  out.sort(function (a, b) { return b.last - a.last; });
  return out;
}

function alerts_(node, sc) {
  var out = [];
  var prefix = sc + '-';
  var strip = function (id) { id = str_(id); return id.indexOf(prefix) === 0 ? id.slice(prefix.length) : id; };
  each_(node, function (key, a) {
    if (typeof a.timestamp !== 'number') return;
    out.push({
      t: a.timestamp,
      me: strip(a.walkerId),        // the phone that recorded the alert
      peer: strip(a.deviceId),      // the device it was alerted about
      level: a.alertLevel === 'DANGER' ? 'DANGER' : 'WARNING',
      myRole: str_(a.myRole),
      peerRole: str_(a.peerRole),
      floor: code_(a.floor),
      proc: code_(a.proc)
    });
  });
  return out;
}

// ── helpers ───────────────────────────────────────────────

function config_() {
  var all = PropertiesService.getScriptProperties().getProperties();
  return { get: function (k) { return String(all[k] == null ? '' : all[k]).trim(); } };
}

function viewer_() {
  return String(Session.getActiveUser().getEmail() || '').toLowerCase();
}

/** Only accounts listed in ADMIN_EMAILS. An unknown viewer (blank email) is always refused. */
function allowed_(cfg) {
  var who = viewer_();
  return !!who && list_(cfg.get('ADMIN_EMAILS').toLowerCase()).indexOf(who) >= 0;
}

/** The script cache, or null when it is unavailable. It holds processed data only, never the secret or a URL. */
function cache_() {
  try { return CacheService.getScriptCache(); } catch (e) { return null; }
}

function cacheGet_(cache, key) {
  try {
    var v = cache && cache.get(key);
    return v ? JSON.parse(v) : null;
  } catch (e) { return null; }
}

// ponytail: CacheService refuses a value over 100 KB; then the part is simply not cached (put fails, ignored).
//   Upgrade path if 500 alerts ever exceed it: split the alerts over several keys.
function cachePut_(cache, key, value, ttlSeconds) {
  try { if (cache) cache.put(key, value, ttlSeconds); } catch (e) { /* caching is optional */ }
}

function list_(s) {
  return String(s).split(/[\s,;]+/).map(function (x) { return x.trim(); }).filter(function (x) { return x; });
}

function each_(node, fn) {
  if (!node || typeof node !== 'object') return;
  Object.keys(node).forEach(function (k) {
    var v = node[k];
    if (v && typeof v === 'object') fn(k, v);
  });
}

function str_(v) { return typeof v === 'string' ? v.slice(0, 64) : ''; }

function code_(v) {
  var s = typeof v === 'string' ? v.trim().toUpperCase() : '';
  return CODE_RE.test(s) ? s : '';
}

function q_(s) { return encodeURIComponent(s); }

function day_(ms) { return Utilities.formatDate(new Date(ms), TZ, 'yyyyMMdd'); }

/** First 8 characters of a Firebase push key for the given time; keys from that time on sort at or after it. */
function pushPrefix_(ms) {
  var s = '';
  for (var i = 0; i < 8; i++) {
    s = PUSH_CHARS.charAt(ms % 64) + s;
    ms = Math.floor(ms / 64);
  }
  return s;
}

/** Same rule as the SOS mail: walker, a known equipment's English name, otherwise 지게차 / EPJ / 알 수 없음. */
function roleName_(r, name) {
  if (r === 'WALKER') return '보행자';
  var m = /^([A-Z]{2})-([0-9]{2})$/.exec(name.trim().toUpperCase());
  if (m && m[2] !== '00' && PIT_NAMES[m[1]]) return PIT_NAMES[m[1]];
  if (r === 'FORKLIFT') return '지게차';
  if (r === 'EPJ') return 'EPJ';
  return '알 수 없음';
}
