/*
 * SafeAlert 관제 — read-only live view of one site
 * Shows active SOS requests, SOS resolved in the last 24 hours, devices monitoring now (and lost contact),
 * and collision alerts by ID, filtered by floor / process. It only reads the Realtime Database with the database
 * secret kept in Script Properties; it cannot change or switch off anything.
 *
 * Setup (screen labels are written 'English' ('한국어'))
 *  1. Sign in with the alert account (the account that runs the SOS mail script).
 *  2. script.google.com → 'New project' ('새 프로젝트'). Click 'Untitled project' ('제목 없는 프로젝트') and rename it
 *     'SafeAlert 관제'; the permission screens in step 7 show this name.
 *  3. Replace the default code of Code.gs with this whole file and save (Ctrl+S).
 *     Then '+' beside 'Files' ('파일') → 'HTML' → name it exactly Index → replace its content with Index.html → save.
 *  4. Gear ('톱니') → 'Project Settings' ('프로젝트 설정') → 'Time zone' ('시간대') = (GMT+09:00) Seoul ('서울').
 *  5. Same page, 'Script Properties' ('스크립트 속성') → 'Add script property' ('스크립트 속성 추가') for each entry,
 *     then 'Save script properties' ('스크립트 속성 저장'):
 *     - FIREBASE_DB_URL    : same value as in the SOS mail script
 *     - FIREBASE_DB_SECRET : same value as in the SOS mail script
 *     - ADMIN_EMAILS       : Google accounts that may open this page, comma separated. Include your own account,
 *                            otherwise the page refuses you too.
 *     - SITES              : site codes to show, comma separated (e.g. WF11). The first one opens by default.
 *     - FIREBASE_ROOT      : (optional) database root; empty means wf11.
 *  6. 'Deploy' ('배포') → 'New deployment' ('새 배포') → gear beside 'Select type' ('유형 선택') → 'Web app' ('웹 앱').
 *     One admin (the alert account only): 'Execute as' ('다음 사용자 인증 정보로 실행') = 'Me' ('나'),
 *       'Who has access' ('액세스 권한이 있는 사용자') = 'Only myself' ('나만').
 *     Several admins: 'Execute as' = 'User accessing the web app' ('웹 앱에 액세스하는 사용자'),
 *       'Who has access' = 'Anyone with Google account' ('Google 계정이 있는 모든 사용자'), and list every admin in
 *       ADMIN_EMAILS. Each admin approves the permission screen once.
 *  7. The permission screen lists connecting to an external service (Firebase). Google warns
 *     'Google hasn't verified this app' ('Google에서 확인하지 않은 앱') for any script it has not reviewed:
 *     'Advanced' ('고급') → 'Go to SafeAlert 관제 (unsafe)' ('SafeAlert 관제(으)로 이동(안전하지 않음)').
 *  8. Open the web app URL ('웹 앱' URL). Append ?sc=WF11 to open a site directly. Bookmark it.
 * To update later: paste the new files, save, then 'Deploy' → 'Manage deployments' ('배포 관리') → pencil ('수정') →
 * 'Version' ('버전') = 'New version' ('새 버전') → 'Deploy'. 'New deployment' would give a new URL.
 */

var ALIVE_MS = 15 * 60 * 1000;        // A session writes 'last' every 5 min; 15 min without one = contact lost
var WINDOW_MS = 24 * 3600 * 1000;     // SOS and sessions are read for the last 24 hours
var SOS_RELEASE_MS = 3600 * 1000;     // The app releases an unanswered SOS one hour after the server got it
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

/** Called by the page every refresh. Returns plain data only; nothing is written anywhere. */
function getSnapshot(sc) {
  var cfg = config_();
  if (!allowed_(cfg)) return { ok: false, error: 'denied', viewer: viewer_() };
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
  var urls = [
    // sos/$sc has ".indexOn": ["createdAt"]
    base + 'sos/' + sc + '.json?' + auth + '&orderBy=' + q_('"createdAt"') + '&startAt=' + (now - WINDOW_MS),
    // Session keys are push keys, so a key range is a time range and needs no index
    base + 'hb/' + sc + '.json?' + auth + '&orderBy=' + q_('"$key"') + '&startAt=' + q_('"' + pushPrefix_(now - WINDOW_MS) + '"'),
    // Alert records sit under the sending phone's local date (KST); read today, and yesterday just after midnight
    base + 'alerts/' + sc + '/' + day_(now) + '.json?' + auth
  ];
  if (Number(Utilities.formatDate(new Date(now), TZ, 'H')) < 1) {
    urls.push(base + 'alerts/' + sc + '/' + day_(now - 3600 * 1000) + '.json?' + auth);
  }
  var res;
  try {
    res = UrlFetchApp.fetchAll(urls.map(function (u) { return { url: u, muteHttpExceptions: true }; }));
  } catch (err) {
    return { ok: false, error: 'fetch' };   // the URL holds the secret, so the message is not passed on
  }
  for (var i = 0; i < res.length; i++) {
    if (res[i].getResponseCode() !== 200) return { ok: false, error: 'http', code: res[i].getResponseCode() };
  }
  var json = res.map(function (r) { return JSON.parse(r.getContentText() || 'null'); });
  var alerts = [];
  for (var j = 2; j < json.length; j++) alerts = alerts.concat(alerts_(json[j], sc));
  alerts.sort(function (a, b) { return b.t - a.t; });

  return {
    ok: true,
    sc: sc,
    sites: sites,
    now: now,
    viewer: viewer_(),
    sos: sos_(json[0], now),
    sessions: sessions_(json[1], now),
    alerts: alerts.slice(0, MAX_ALERTS),
    alertTotal: alerts.length
  };
}

function sos_(node, now) {
  var out = [];
  each_(node, function (key, r) {
    if (typeof r.createdAt !== 'number') return;
    var active = r.status === 'active';
    out.push({
      key: key,
      name: str_(r.name),
      who: roleName_(str_(r.role), str_(r.name)),
      trigger: r.trigger === 'fall' ? '넘어짐' : r.trigger === 'still' ? '움직임 없음' : '알 수 없음',
      beacon: str_(r.beacon),
      floor: code_(r.floor),
      proc: code_(r.proc),
      createdAt: r.createdAt,
      active: active,
      // Still active an hour after it was created: the writer's phone may be gone (the app normally releases it by then)
      stale: active && now - r.createdAt > SOS_RELEASE_MS + 5 * 60 * 1000,
      resolvedAt: typeof r.resolvedAt === 'number' ? r.resolvedAt : 0,
      auto: r.reason === 'auto'
    });
  });
  out.sort(function (a, b) { return (b.active - a.active) || (b.createdAt - a.createdAt); });
  return out;
}

function sessions_(node, now) {
  var out = [];
  each_(node, function (key, s) {
    if (typeof s.last !== 'number' || typeof s.start !== 'number') return;
    if (typeof s.end === 'number') return;   // stopped normally
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
