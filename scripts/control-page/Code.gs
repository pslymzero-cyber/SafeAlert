/*
 * SafeAlert 관제: read-only live view of one site
 * Shows active SOS requests (full-screen alarm + siren until acknowledged in the browser), SOS that ended in the last
 * 24 hours, devices monitoring now (and lost contact), and collision alerts by device ID, filtered by floor / process.
 * It only reads the Realtime Database with the database secret kept in Script Properties; it cannot change or switch
 * off anything.
 *
 * Setup (screen labels are written 'English' ('한국어'), as in the SOS mail script). Each step says why, what Google
 * may warn, and what you have after it.
 *
 *  1. Sign in to Google with an account that is NOT the SOS mail script's account (safealertfs@gmail.com).
 *     Why: this page reads Firebase through UrlFetch, which has a daily quota per account (a consumer account: about
 *     20,000 calls a day). Sharing it with the mail script could stop SOS mails. With 'Execute as' = 'User accessing
 *     the web app' (step 6) every viewer's own quota is used, so do not list the mail account in ADMIN_EMAILS either.
 *     After it: you are signed in with the account that will own this page.
 *  2. script.google.com → 'New project' ('새 프로젝트'). Click 'Untitled project' ('제목 없는 프로젝트') at the top and
 *     rename it 'SafeAlert 관제'.
 *     Why: the permission screens of step 7 show this name. After it: an empty project named 'SafeAlert 관제'.
 *  3. Replace the code of Code.gs with this whole file and save (Ctrl+S). Then 'Files' ('파일') '+' → 'HTML', name it
 *     exactly Index, replace its content with Index.html and save.
 *     Why: Code.gs reads the database and serves Index; the name Index is what Code.gs asks for.
 *     After it: two files, Code.gs and Index.html.
 *  4. Gear 'Project Settings' ('프로젝트 설정') → 'Time zone' ('시간대') = (GMT+09:00) Seoul ('서울').
 *     Why: alert records sit under the sending phone's Korean date. After it: the project clock is Korean time.
 *  5. Same page, 'Script Properties' ('스크립트 속성') → 'Add script property' ('스크립트 속성 추가') for each entry, then
 *     'Save script properties' ('스크립트 속성 저장'):
 *     - FIREBASE_DB_URL    : same value as in the SOS mail script
 *     - FIREBASE_DB_SECRET : same value as in the SOS mail script
 *     - ADMIN_EMAILS       : Google accounts that may open this page, comma separated. Include your own account,
 *                            otherwise the page refuses you too.
 *     - SITES              : site codes to show, comma separated (e.g. WF11). The first one opens by default.
 *     - FIREBASE_ROOT      : (optional) database root; empty means wf11.
 *     Why: the secret stays on Google's side and never reaches the page. After it: the page can read the database.
 *  6. Top right 'Deploy' ('배포') → 'New deployment' ('새 배포') → gear beside 'Select type' ('유형 선택') → 'Web app'
 *     ('웹 앱').
 *     One admin: 'Execute as' ('다음 사용자 인증 정보로 실행') = 'Me' ('나'),
 *       'Who has access' ('액세스 권한이 있는 사용자') = 'Only myself' ('나만').
 *     Several admins (recommended): 'Execute as' = 'User accessing the web app' ('웹 앱에 액세스하는 사용자'),
 *       'Who has access' = 'Anyone with Google account' ('Google 계정이 있는 모든 사용자'), and list every admin in
 *       ADMIN_EMAILS (anyone not listed is refused by the page).
 *     Why: the first choice shares one quota; the second gives each viewer their own.
 *     After it: a deployment waiting for permission.
 *  7. 'Deploy' ('배포') → 'Authorize access' ('액세스 승인') → choose the account. Google shows 'Google hasn't verified
 *     this app' ('Google에서 확인하지 않은 앱'): 'Advanced' ('고급') → 'Go to SafeAlert 관제 (unsafe)'
 *     ('SafeAlert 관제(으)로 이동(안전하지 않음)') → 'Allow' ('허용').
 *     Why: the script reads Firebase with the secret, and a personal script Google has not reviewed always shows this
 *     warning. Each admin approves it once. After it: the deployment is live.
 *  8. Copy the 'Web app' ('웹 앱') URL ending in /exec. Add ?sc=WF11 to open a site directly. Bookmark it.
 *     After it: the bookmarked page opens for the accounts in ADMIN_EMAILS; others see a refusal.
 *
 * Updating later: paste the new files, save, then 'Deploy' ('배포') → 'Manage deployments' ('배포 관리') → pencil 'Edit'
 * ('수정') → 'Version' ('버전'): 'New version' ('새 버전') → 'Deploy' ('배포').
 * Why: this keeps the same URL ('New deployment' would make a new one and the bookmark would stay on the old one).
 *
 * If the page says it used its daily read limit (quota), wait: it recovers within 24 hours. Reads are cached (SOS
 * 20 seconds, the rest 2 minutes, yesterday's alerts 1 hour) and a hidden tab reads only the SOS part.
 */

var ALIVE_MS = 15 * 60 * 1000;        // A session writes 'last' every 5 min; 15 min without one = contact lost
var WINDOW_MS = 24 * 3600 * 1000;     // SOS, sessions and alerts are read for the last 24 hours
var HOUR_MS = 3600 * 1000;
var SOS_RELEASE_MS = 3600 * 1000;     // The app releases an unanswered SOS one hour after the server got it
var SOS_TTL_S = 20;                   // The SOS part is cached this long (seconds)
var SLOW_TTL_S = 120;                 // Sessions + alerts are cached this long
var DAY_TTL_S = 3600;                 // Yesterday's alert node hardly changes after midnight
var RECENT_ALERTS = 50;               // The page lists this many newest alerts
var MAX_ALERTS = 300;                 // Alerts sent to the page: the last hour (counts by ID) plus the newest 50
var CACHE_MAX_BYTES = 90 * 1000;      // CacheService refuses a value over 100 KB
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
 * URLs hold the secret, so exception text is never passed on. opt.sosOnly: a hidden tab only needs the SOS part (it
 * drives the alarm); sessions and alerts are then null and the page keeps its previous ones.
 */
function getSnapshot(sc, opt) {
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
  var sosOnly = !!(opt && opt.sosOnly);

  var now = Date.now();
  var base = dbUrl.replace(/\/$/, '') + '/' + root + '/';
  var auth = 'auth=' + encodeURIComponent(secret);
  var cache = cache_();
  // Keys carry the database host, the root and the site: a changed property never serves another database's data
  var ns = dbUrl.replace(/^https:\/\//, '').replace(/\/$/, '') + '|' + root + '|' + sc;
  var yday = day_(now - WINDOW_MS);
  var kSos = 'sos|' + ns, kSlow = 'slow|' + ns, kDay = 'day|' + ns + '|' + yday;
  var sosPart = cacheGet_(cache, kSos);
  var slowPart = sosOnly ? null : cacheGet_(cache, kSlow);
  var dayPart = sosOnly || slowPart ? null : cacheGet_(cache, kDay);

  var parse = function (r) { return JSON.parse(r.getContentText() || 'null'); };
  // Fetch errors never pass their text on: it can carry the URL (the secret). Only the daily limit is told apart.
  var failure = function (err) { return /too many times/i.test(String(err && err.message)) ? 'quota' : 'fetch'; };

  // The SOS part is read first and on its own: a failing session or alert read must never hold back a new SOS
  if (!sosPart) {
    var r;
    try {
      r = UrlFetchApp.fetch(base + 'sos/' + sc + '.json?' + auth + '&orderBy=' + q_('"createdAt"') + '&startAt=' + (now - WINDOW_MS),   // sos/$sc has ".indexOn": ["createdAt"]
        { muteHttpExceptions: true });
    } catch (err) {
      return { ok: false, error: failure(err) };
    }
    if (r.getResponseCode() !== 200) return { ok: false, error: 'http', code: r.getResponseCode() };
    sosPart = { at: now, sos: sos_(parse(r)) };
    putNewer_(cache, kSos, sosPart, SOS_TTL_S);
  }

  // Sessions and alerts: when they fail the page keeps its previous ones and says so (slowError)
  var slowError = '';
  if (!sosOnly && !slowPart) {
    var urls = {
      hb: base + 'hb/' + sc + '.json?' + auth + '&orderBy=' + q_('"last"') + '&startAt=' + (now - WINDOW_MS),   // hb/$sc has ".indexOn": ["last"]
      // Alert records sit under the sending phone's local date (KST): today always, yesterday from its own cache
      today: base + 'alerts/' + sc + '/' + day_(now) + '.json?' + auth
    };
    if (!dayPart) urls.yday = base + 'alerts/' + sc + '/' + yday + '.json?' + auth;
    var names = Object.keys(urls), got = {};
    try {
      var res = UrlFetchApp.fetchAll(names.map(function (n) { return { url: urls[n], muteHttpExceptions: true }; }));
      names.forEach(function (n, i) { got[n] = res[i]; });
      // Rules without the hb 'last' index answer 400 "Index not defined" until the release deploys them: read by key
      // range then. Session keys are push keys, so the range is their start time (a session older than 24 h is missed).
      if (got.hb.getResponseCode() === 400 && /Index not defined/.test(got.hb.getContentText() || '')) {
        got.hb = UrlFetchApp.fetch(base + 'hb/' + sc + '.json?' + auth + '&orderBy=' + q_('"$key"') +
          '&startAt=' + q_('"' + pushPrefix_(now - WINDOW_MS) + '"'), { muteHttpExceptions: true });
      }
      names.forEach(function (n) { if (got[n].getResponseCode() !== 200) slowError = 'http'; });
    } catch (err) {
      slowError = failure(err);
    }
    if (!slowError) {
      if (got.yday) {
        dayPart = { alerts: newest_(alerts_(parse(got.yday), sc)).slice(0, MAX_ALERTS) };
        cachePut_(cache, kDay, JSON.stringify(dayPart), DAY_TTL_S);
      }
      var alerts = newest_(alerts_(parse(got.today), sc).concat(dayPart.alerts))
        .filter(function (a, i) { return now - a.t <= HOUR_MS || i < RECENT_ALERTS; })
        .slice(0, MAX_ALERTS);
      slowPart = { at: now, sessions: sessions_(parse(got.hb)), alerts: alerts };
      // ponytail: a site with hundreds of sessions could still pass the cache limit; the oldest alerts go first then
      while (bytes_(JSON.stringify(slowPart)) > CACHE_MAX_BYTES && slowPart.alerts.length) {
        slowPart.alerts = slowPart.alerts.slice(0, Math.floor(slowPart.alerts.length * 0.8));
      }
      putNewer_(cache, kSlow, slowPart, SLOW_TTL_S);
    }
  }

  // Time-dependent fields are worked out here, from this call's clock, also for a cached part
  sosPart.sos.forEach(function (s) { s.stale = !!s.active && now - s.createdAt > SOS_RELEASE_MS + 5 * 60 * 1000; });
  if (slowPart) slowPart.sessions.forEach(function (s) { s.alive = now - s.last <= ALIVE_MS; });

  return {
    ok: true,
    sc: sc,
    sites: sites,
    now: Date.now(),            // the clock when the answer leaves: the page's elapsed counters do not lag by the fetch time
    viewer: viewer_(),
    sos: sosPart.sos,
    sessions: slowPart ? slowPart.sessions : null,
    alerts: slowPart ? slowPart.alerts : null,
    slowError: slowError        // '' | 'http' | 'fetch' | 'quota': sessions and alerts could not be read this time
  };
}

/** SOS records, active first. stale (still active an hour after creation) is set by getSnapshot. */
function sos_(node) {
  var out = [];
  each_(node, function (key, r) {
    if (typeof r.createdAt !== 'number') return;
    out.push({
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
    });
  });
  out.sort(function (a, b) { return (b.active - a.active) || (b.createdAt - a.createdAt); });
  return out;
}

/**
 * One row per monitoring session still open. A phone that restarted without stopping leaves an un-ended session behind:
 * per uid only the session written last counts. 'last' is refreshed every 5 minutes by the live session, while 'start'
 * can carry the phone's own clock right after a restart. uid is used here only and never sent to the page. Sessions
 * without a uid are kept as they are. alive is set by getSnapshot.
 */
function sessions_(node) {
  var latest = {};
  each_(node, function (key, s) {
    if (typeof s.uid === 'string' && typeof s.last === 'number' && !(latest[s.uid] >= s.last)) latest[s.uid] = s.last;
  });
  var out = [];
  each_(node, function (key, s) {
    if (typeof s.last !== 'number' || typeof s.start !== 'number') return;
    if (typeof s.end === 'number') return;   // stopped normally
    if (typeof s.uid === 'string' && s.last < latest[s.uid]) return;   // a leftover: the same phone wrote later
    out.push({ role: roleName_(str_(s.role), ''), floor: code_(s.floor), proc: code_(s.proc), start: s.start, last: s.last });
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
    var me = strip(a.walkerId), peer = strip(a.deviceId);
    out.push({
      t: a.timestamp,
      me: me,                       // the phone that recorded the alert
      peer: peer,                   // the device it was alerted about
      level: a.alertLevel === 'DANGER' ? 'DANGER' : 'WARNING',
      myRole: roleName_(str_(a.myRole), me),
      peerRole: roleName_(str_(a.peerRole), peer),
      floor: code_(a.floor),
      proc: code_(a.proc)
    });
  });
  return out;
}

function newest_(list) { return list.sort(function (a, b) { return b.t - a.t; }); }

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

function cachePut_(cache, key, value, ttlSeconds) {
  try { if (cache) cache.put(key, value, ttlSeconds); } catch (e) { /* caching is optional */ }
}

/**
 * Caches a part unless a call that started later already cached a newer one: a slow call must not bring back an SOS
 * another call has already seen released.
 * ponytail: get-then-put is not atomic; wrap it in LockService.getScriptLock() if two calls ever land within milliseconds.
 */
function putNewer_(cache, key, part, ttlSeconds) {
  var cur = cacheGet_(cache, key);
  if (cur && cur.at > part.at) return;
  cachePut_(cache, key, JSON.stringify(part), ttlSeconds);
}

/** UTF-8 size of a string (Korean labels take 3 bytes each). */
function bytes_(s) { return encodeURIComponent(s).replace(/%[0-9A-F]{2}/gi, '_').length; }

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
