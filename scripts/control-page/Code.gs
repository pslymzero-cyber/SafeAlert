/*
 * 'SafeAlert 관제': read-only live view of the sites in SITES
 * Shows the active SOS requests of every site in SITES (full-screen alarm + siren until acknowledged in the browser) and,
 * for the chosen site, SOS that ended in the last 24 hours, devices monitoring now (and lost contact), and collision
 * alerts by device ID, filtered by floor / process.
 * The SOS lists come live from Firebase: the page signs in anonymously, as the app does, and listens to them (the rules
 * let any signed-in client read SOS). Devices and alerts are read by this script with the database secret kept in
 * Script Properties. Nothing is written anywhere; the page cannot change or switch off anything.
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
 *     - FIREBASE_WEB_API_KEY : (recommended) Firebase console → gear 'Project settings' ('프로젝트 설정') → 'General'
 *                            ('일반') → 'Web API Key' ('웹 API 키'). With it SOS reach the page within seconds; without
 *                            it, or if Google refuses the key (for example a key restricted to Android apps), the page
 *                            reads SOS through this script every 30 seconds and says why at the top.
 *     Why: the secret stays on Google's side and never reaches the page (the web API key is not a secret: the app
 *     carries it too). After it: the page can read the database.
 *  6. Deploy: this gives the page its web address.
 *     'Deploy' ('배포') at the top right > 'New deployment' ('새 배포') > gear beside 'Select type' ('유형 선택') > 'Web app'
 *     ('웹 앱').
 *     One admin: 'Execute as' ('다음 사용자 인증 정보로 실행') = 'Me' ('나'),
 *       'Who has access' ('액세스 권한이 있는 사용자') = 'Only myself' ('나만').
 *     Several admins (recommended): 'Execute as' = 'User accessing the web app' ('웹 앱에 액세스하는 사용자'),
 *       'Who has access' = 'Anyone with Google account' ('Google 계정이 있는 모든 사용자'), and list every admin in
 *       ADMIN_EMAILS (anyone not listed is refused by the page).
 *     Why: the first choice shares one quota; the second gives each viewer their own. Then press 'Deploy' ('배포').
 *  7. The first deployment asks for permission: 'Authorize access' ('액세스 승인') → choose the account.
 *     Google then warns 'Google hasn't verified this app' ('Google에서 확인하지 않은 앱'); it shows this for any script
 *     it has not reviewed, including your own. Do not press 'Back to safety' ('안전한 환경으로 돌아가기'). Press 'Advanced'
 *     ('고급') → 'Go to SafeAlert 관제 (unsafe)' ('SafeAlert 관제(으)로 이동(안전하지 않음)').
 *     The permission screen lists connecting to an external service (Firebase) and seeing your email address (the
 *     ADMIN_EMAILS check); the page needs both. If it shows checkboxes, tick 'Select all' ('모두 선택'). Then press
 *     'Allow' ('허용') or 'Continue' ('계속'). With 'User accessing the web app' every admin sees this once, on first opening.
 *     After it: the deployment shows the 'Web app' ('웹 앱') URL, https://script.google.com/macros/s/…/exec.
 *  8. Press 'Copy' ('복사') next to that URL, then 'Done' ('완료'). Open the URL in the control-room browser (add ?sc=WF11
 *     to open a site directly), bookmark it, and press '경보음 켜기' on the page once.
 *     Why: that URL is the control page (the script editor is not), and browsers keep a page silent until a click.
 *     After it: the bookmarked page opens for the accounts in ADMIN_EMAILS; others see a refusal.
 *
 * Updating later: paste the new files, save, then 'Deploy' ('배포') → 'Manage deployments' ('배포 관리') → pencil 'Edit'
 * ('수정') → 'Version' ('버전'): 'New version' ('새 버전') → 'Deploy' ('배포'). Then reload every open control-page tab.
 * Why: this keeps the same URL ('New deployment' would make a new one and the bookmark would stay on the old one), and a
 * tab opened before the update keeps running the old page against the new script.
 *
 * Live SOS use none of the daily read limit (quota) of UrlFetch. If the page says the limit is used up, wait: it
 * recovers within 24 hours. The reads of this script are shared by all open tabs and viewers through the script cache:
 * devices and alerts of a site for 100 seconds (yesterday's alerts for 10 minutes) and, only while the live connection
 * is down, the SOS list of each site for 20 seconds (longer with 4 sites or more, so that all sites together stay under
 * about 13,000 reads a day).
 */

var ALIVE_MS = 15 * 60 * 1000;        // A session writes 'last' every 5 min; 15 min without one = contact lost
var WINDOW_MS = 24 * 3600 * 1000;     // SOS, sessions and alerts are read for the last 24 hours
var HOUR_MS = 3600 * 1000;
var SOS_RELEASE_MS = 3600 * 1000;     // The app releases an unanswered SOS one hour after the server got it
var SOS_MIN_TTL_S = 20;               // without live SOS: one read of a site's list serves every tab this long (seconds)
var SOS_DAILY_READS = 13000;          // SOS reads of all sites together stay under this a day (UrlFetch quota: 20,000)
var FIELD_TTL_S = 100;                // sessions + alerts of a site are shared this long: under the page's 2-minute poll
var YDAY_TTL_S = 600;                 // yesterday's alerts: phones that were offline still upload into it after midnight
var NO_INDEX_TTL_S = 1800;            // how long to remember that the rules lack an index (until a release deploys them)
var MAX_ALERTS = 400;                 // newest alerts of the last 24 hours sent to the page (cut says there were more)
var CACHE_MAX_BYTES = 90 * 1000;      // CacheService refuses a value over 100 KB
var PUSH_CHARS = '-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz';
var DB_URL_RE = /^https:\/\/[A-Za-z0-9.-]+\/?$/;
var SC_RE = /^[A-Z0-9_-]{1,12}$/;
var API_KEY_RE = /^[A-Za-z0-9_-]{20,64}$/;
var ROOT_RE = /^[A-Za-z0-9_-]{1,32}$/;
var CODE_RE = /^[A-Z0-9]{1,4}$/;      // Floor / process code; same as the app's SiteScope.CODE_PATTERN
var TZ = 'Asia/Seoul';
// Equipment code → English name; same table as the SOS mail script (and the app's PitType.kt).
var PIT_NAMES = { CB: 'Counterbalance', RT: 'Reach Truck', HR: 'High Reach', OP: 'Order Picker',
  ST: 'Stacker', TT: 'Tow Tractor', EP: 'Electric Pallet Jack', WK: 'Walkie Stacker' };

function doGet(e) {
  var cfg = config_();
  var t = HtmlService.createTemplateFromFile('Index');
  t.denied = !allowed_(cfg);
  t.viewer = viewer_();
  var sites = sites_(cfg), sc = String((e && e.parameter && e.parameter.sc) || '').toUpperCase();
  t.initialSc = sites.indexOf(sc) >= 0 ? sc : sites[0] || '';   // only a site in SITES reaches the page
  // What the page needs to listen to the SOS lists itself. None of it is secret: the app carries the same values.
  var key = cfg.get('FIREBASE_WEB_API_KEY'), dbUrl = cfg.get('FIREBASE_DB_URL'), root = cfg.get('FIREBASE_ROOT') || 'wf11';
  t.fb = JSON.stringify({
    apiKey: API_KEY_RE.test(key) ? key : '',
    dbUrl: DB_URL_RE.test(dbUrl) ? dbUrl.replace(/\/$/, '') : '',
    root: ROOT_RE.test(root) ? root : '',
    sites: sites,
    pit: PIT_NAMES,
    code: CODE_RE.source,
    releaseMs: SOS_RELEASE_MS
  }).replace(/</g, '\\u003c');   // nothing in it can close the page's script tag
  return t.evaluate()
    .setTitle('SafeAlert 관제')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

/**
 * SOS of every site in SITES while the page's live connection is down (the page asks every 30 seconds then). Returns
 * plain data only; nothing is written anywhere. Errors are fixed codes: the URLs hold the secret, so exception text is
 * never passed on. lists[site] = { at: when it was read, recs: the records as the database holds them }; a site that
 * could not be read is in `failed` with the reason; only when no site could be read is the whole answer an error.
 */
function getSos() {
  var c = setup_();
  if (c.error) return { ok: false, error: c.error };
  var at = Date.now(), ttl = sosTtl_(c.sites.length), lists = {}, urls = {};
  c.sites.forEach(function (s) {
    var hit = cacheGet_(c.cache, 'sos|' + c.base + s);
    if (hit) lists[s] = hit;
    else urls[s] = c.read('sos/' + s, '&orderBy=' + q_('"createdAt"') + '&startAt=' + (at - WINDOW_MS));   // sos/$sc has ".indexOn": ["createdAt"]
  });
  var got = {}, thrown = '';
  try { got = fetchAll_(urls); } catch (err) { thrown = failure_(err); }
  var failed = [];
  Object.keys(urls).forEach(function (s) {
    var node = ok_(got[s]);
    if (node === undefined) {
      var code = got[s] ? got[s].getResponseCode() : 0;
      failed.push({ sc: s, why: thrown || (code && code !== 200 ? 'http' : 'fetch'), code: code });
      return;
    }
    lists[s] = { at: at, recs: sosRecs_(node) };
    putNewer_(c.cache, 'sos|' + c.base + s, lists[s], ttl);
  });
  if (failed.length === c.sites.length) return { ok: false, error: failed[0].why, code: failed[0].code };
  return { ok: true, sites: c.sites, now: Date.now(), lists: lists, failed: failed };
}

/** The page's periodic check that its viewer is still in ADMIN_EMAILS (live SOS never pass through this script). */
function ping() { return allowed_(config_()) ? { ok: true } : { ok: false, error: 'denied' }; }

/**
 * Sessions and collision alerts of one site for the last 24 hours. Called by a visible page every 2 minutes and when the
 * site is switched; shared through the cache by every tab and viewer for FIELD_TTL_S.
 */
function getField(sc) {
  var c = setup_();
  if (c.error) return { ok: false, error: c.error };
  sc = String(sc || '').toUpperCase();
  if (c.sites.indexOf(sc) < 0) sc = c.sites[0];
  var key = 'field|' + c.base + sc, part = cacheGet_(c.cache, key);
  if (!part) {
    part = readField_(c, sc, Date.now());
    if (part.error) return { ok: false, error: part.error, code: part.code };
    putNewer_(c.cache, key, part, FIELD_TTL_S);
  }
  var now = Date.now();
  part.sessions.forEach(function (s) { s.alive = now - s.last <= ALIVE_MS; });   // from this call's clock, also when cached
  return { ok: true, sc: sc, now: now, at: part.at, sessions: part.sessions, alerts: part.alerts, cut: part.cut };
}

/** Reads the sessions and alerts of a site; { error, code } when a read failed. */
function readField_(c, sc, now) {
  var ns = c.base + sc, ydayKey = 'yday|' + ns + '|' + day_(now - WINDOW_MS);
  var whole = !!cacheGet_(c.cache, 'noidx|' + ns), yday = cacheGet_(c.cache, ydayKey);
  var urls = function (whole) {
    // Session keys are push keys: without the index the key range stands for the start time (a session started over
    // 24 hours ago is missed then). Alert records sit under the sending phone's Korean date; only the last 24 hours
    // are asked for, and nothing stamped more than an hour ahead (a phone with a wrong clock).
    var alertQ = whole ? '' : '&orderBy=' + q_('"timestamp"') + '&startAt=' + (now - WINDOW_MS) + '&endAt=' + (now + HOUR_MS) +
      '&limitToLast=' + MAX_ALERTS;
    var u = {
      hb: whole ? c.read('hb/' + sc, '&orderBy=' + q_('"$key"') + '&startAt=' + q_('"' + pushPrefix_(now - WINDOW_MS) + '"'))
        : c.read('hb/' + sc, '&orderBy=' + q_('"last"') + '&startAt=' + (now - WINDOW_MS)),   // hb/$sc has ".indexOn": ["last"]
      today: c.read('alerts/' + sc + '/' + day_(now), alertQ)   // alerts/$sc/$day has ".indexOn": ["timestamp"]
    };
    if (!yday) u.yday = c.read('alerts/' + sc + '/' + day_(now - WINDOW_MS), alertQ);
    return u;
  };
  var got;
  try {
    got = fetchAll_(urls(whole));
    // Rules without the indexes answer 400 "Index not defined" until a release deploys them: read whole nodes then, and
    // remember that for a while
    if (!whole && Object.keys(got).some(function (n) { return noIndex_(got[n]); })) {
      whole = true;
      got = fetchAll_(urls(true));
      cachePut_(c.cache, 'noidx|' + ns, '1', NO_INDEX_TTL_S);
    }
  } catch (err) {
    return { error: failure_(err) };
  }
  var nodes = {}, names = Object.keys(got);
  for (var i = 0; i < names.length; i++) {
    var r = got[names[i]];
    nodes[names[i]] = ok_(r);
    if (nodes[names[i]] === undefined) return { error: r.getResponseCode() !== 200 ? 'http' : 'fetch', code: r.getResponseCode() };
  }
  // A query that returned MAX_ALERTS records may have left older ones of the 24 hours out
  var full = function (node) { return !whole && Object.keys(node || {}).length >= MAX_ALERTS; };
  var inWindow = function (a) { return a.t >= now - WINDOW_MS && a.t <= now + HOUR_MS; };
  var newestFirst = function (a, b) { return b.t - a.t; };
  if (!yday) {
    var ys = alerts_(nodes.yday, sc).filter(inWindow).sort(newestFirst);
    yday = { list: ys.slice(0, MAX_ALERTS), full: full(nodes.yday) || ys.length > MAX_ALERTS };
    yday.oldest = yday.list.length ? yday.list[yday.list.length - 1].t : 0;
    cacheJson_(c.cache, ydayKey, yday, YDAY_TTL_S);
  }
  var alerts = alerts_(nodes.today, sc).concat(yday.list).filter(inWindow).sort(newestFirst);   // yesterday's part may be minutes old
  var part = { at: now, sessions: sessions_(nodes.hb), alerts: alerts.slice(0, MAX_ALERTS),
               // yesterday's left-out records only matter while the 24 hours still reach past the oldest one kept
               cut: alerts.length > MAX_ALERTS || full(nodes.today) || (yday.full && now - WINDOW_MS < yday.oldest) };
  // ponytail: a site with hundreds of open sessions could pass the cache limit; the oldest alerts go first, flagged
  while (bytes_(JSON.stringify(part)) > CACHE_MAX_BYTES && part.alerts.length) {
    part.alerts = part.alerts.slice(0, Math.floor(part.alerts.length * 0.8));
    part.cut = true;
  }
  return part;
}

/** The parsed body of a 200 answer (null for an empty node), or undefined when the read failed. */
function ok_(r) {
  if (!r || r.getResponseCode() !== 200) return undefined;
  try { return JSON.parse(r.getContentText() || 'null'); } catch (e) { return undefined; }
}

/** Fetch errors never pass their text on: it can carry the URL (the secret). Only the daily limit is told apart. */
function failure_(err) { return /too many times/i.test(String(err && err.message)) ? 'quota' : 'fetch'; }

function noIndex_(r) { return !!r && r.getResponseCode() === 400 && /Index not defined/.test(r.getContentText() || ''); }

/** The SOS records of a site as the database holds them, cut to the fields the page reads (the page names them). */
function sosRecs_(node) {
  var out = {};
  each_(node, function (key, r) {
    if (typeof r.createdAt !== 'number') return;
    out[key] = { name: str_(r.name), role: str_(r.role), trigger: str_(r.trigger), beacon: str_(r.beacon),
                 floor: str_(r.floor), proc: str_(r.proc), createdAt: r.createdAt, status: str_(r.status),
                 resolvedAt: typeof r.resolvedAt === 'number' ? r.resolvedAt : 0, reason: str_(r.reason) };
  });
  return out;
}

/**
 * One row per monitoring session still open. A phone that restarted without stopping leaves an un-ended session behind:
 * per uid only the session written last counts. 'last' is refreshed every 5 minutes by the live session, while 'start'
 * can carry the phone's own clock right after a restart. uid is used here only and never sent to the page. Sessions
 * without a uid are kept as they are. alive is set by getField.
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

// ── helpers ───────────────────────────────────────────────

function config_() {
  var all = PropertiesService.getScriptProperties().getProperties();
  return { get: function (k) { return String(all[k] == null ? '' : all[k]).trim(); } };
}

/** The viewer check, then everything a read needs: { error } when refused or not set up. */
function setup_() {
  var cfg = config_();
  if (!allowed_(cfg)) return { error: 'denied' };   // before any cache read
  var dbUrl = cfg.get('FIREBASE_DB_URL');
  var secret = cfg.get('FIREBASE_DB_SECRET');
  var root = cfg.get('FIREBASE_ROOT') || 'wf11';
  var sites = sites_(cfg);
  if (!DB_URL_RE.test(dbUrl) || !secret || !ROOT_RE.test(root) || !sites.length) return { error: 'setup' };
  var base = dbUrl.replace(/\/$/, '') + '/' + root + '/';   // holds no secret: also the cache namespace
  var auth = 'auth=' + encodeURIComponent(secret);
  return { sites: sites, base: base, cache: cache_(), read: function (path, query) { return base + path + '.json?' + auth + (query || ''); } };
}

/** SITES as valid site codes; a site listed twice counts once. */
function sites_(cfg) {
  return list_(cfg.get('SITES').toUpperCase()).filter(function (s, i, all) { return SC_RE.test(s) && all.indexOf(s) === i; });
}

function viewer_() {
  return String(Session.getActiveUser().getEmail() || '').toLowerCase();
}

/** Only accounts listed in ADMIN_EMAILS. An unknown viewer (blank email) is always refused. */
function allowed_(cfg) {
  var who = viewer_();
  return !!who && list_(cfg.get('ADMIN_EMAILS').toLowerCase()).indexOf(who) >= 0;
}

/** One batch of reads by name. Throws like UrlFetchApp.fetchAll (network failure, daily limit). */
function fetchAll_(urls) {
  var names = Object.keys(urls), got = {};
  if (!names.length) return got;
  var res = UrlFetchApp.fetchAll(names.map(function (n) { return { url: urls[n], muteHttpExceptions: true }; }));
  names.forEach(function (n, i) { got[n] = res[i]; });
  return got;
}

/** The SOS list of a site is kept 20 s, longer with many sites, so their reads stay under SOS_DAILY_READS a day. */
function sosTtl_(n) { return Math.max(SOS_MIN_TTL_S, Math.ceil(86400 * n / SOS_DAILY_READS)); }

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

function cacheJson_(cache, key, obj, ttlSeconds) {
  var json = JSON.stringify(obj);
  if (bytes_(json) <= CACHE_MAX_BYTES) cachePut_(cache, key, json, ttlSeconds);
}

/**
 * Shares a part read at part.at for what is left of its cache life, unless a newer part is already there: a slow call
 * that finished late never hides what a faster call read after it, and no part is served longer than its life after
 * it was read.
 */
function putNewer_(cache, key, part, ttlSeconds) {
  var left = Math.floor(ttlSeconds - (Date.now() - part.at) / 1000);
  if (left < 1) return;
  var cur = cacheGet_(cache, key);
  if (cur && cur.at >= part.at) return;
  cacheJson_(cache, key, part, left);
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
