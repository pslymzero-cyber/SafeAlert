/*
 * SafeAlert lone-worker SOS mail
 * When the app has recorded an SOS or its resolution on the server and notifies this web app,
 * the script checks the server record itself and sends one mail in a fixed format.
 * It also sends an HTML body that lays out the same content as the plain text in a table.
 *
 * Setup (screen labels are written 'English' ('한국어'))
 *  1. Sign in with a Google account used only for alerts. Mail is sent from this account.
 *     Once this account's daily mail quota (100 recipients for a regular Google account, 1,500 for Workspace) runs out,
 *     resolution mails can be blocked too until the next day (the SOS mail is sent first).
 *  2. script.google.com → 'New project' ('새 프로젝트'). Click the name 'Untitled project' ('제목 없는 프로젝트') at the top
 *     and rename it 'SafeAlert 구조 요청 메일'; this name appears on the permission screens in step 7.
 *  3. Delete the default code, paste in this whole file and save (disk icon or Ctrl+S).
 *  4. Gear icon ('톱니') on the left → 'Project Settings' ('프로젝트 설정') → set 'Time zone' ('시간대')
 *     to (GMT+09:00) Seoul ('서울').
 *  5. Further down the same page, under 'Script Properties' ('스크립트 속성'), add each entry below with
 *     'Add script property' ('스크립트 속성 추가'): name in 'Property' ('속성'), value in 'Value' ('값').
 *     Then press 'Save script properties' ('스크립트 속성 저장'). The script reads its settings and the secret from here.
 *     - FIREBASE_DB_URL    : the address at the top of the 'Realtime Database' page in
 *                            the Firebase console (https://….firebaseio.com or similar)
 *     - FIREBASE_DB_SECRET : Firebase console gear icon ('톱니') → 'Project settings' ('프로젝트 설정') >
 *                            'Service accounts' ('서비스 계정') > 'Database secrets' ('데이터베이스 비밀번호')
 *     - ALLOWED_DOMAINS    : domains that may receive the mail; empty means coupangfs.com. Separate entries with commas,
 *                            semicolons, spaces or newlines; a leading '@' and letter case are ignored.
 *                            An exact address (me@example.com) also works.
 *     - DAILY_MAX          : (optional) cap on SOS mails sent per site (root + center) in the last 24 hours. Default 50, range 1–60.
 *                            Resolution mails are neither counted nor blocked.
 *     Properties starting with 'S|' are the script's own sent log; leave them alone (SOS entries are kept 7 days and
 *     resolution entries 24 hours, then deleted automatically; beyond 3,000 in total the oldest go first, and a deleted SOS entry
 *     only stops its resolution mail. A leftover SENT_LOG property can be deleted).
 *  6. Deploy: this gives the script the web address the app calls; without it no mail is sent.
 *     'Deploy' ('배포') at the top right > 'New deployment' ('새 배포') > gear beside 'Select type' ('유형 선택') > 'Web app' ('웹 앱').
 *     'Execute as' ('다음 사용자 인증 정보로 실행'): 'Me' ('나'), so mail goes out from the alert account.
 *     'Who has access' ('액세스 권한이 있는 사용자'): 'Anyone' ('모든 사용자'), because the app calls it without a Google sign-in;
 *     the script checks the server record before it sends anything. Then press 'Deploy' ('배포').
 *  7. The first deployment asks for permission: 'Authorize access' ('액세스 승인') → choose the alert account.
 *     Google then warns 'Google hasn't verified this app' ('Google에서 확인하지 않은 앱'); it shows this for any script
 *     it has not reviewed, including your own. Do not press 'Back to safety' ('안전한 환경으로 돌아가기'). Press 'Advanced' ('고급')
 *     → 'Go to SafeAlert 구조 요청 메일 (unsafe)' ('SafeAlert 구조 요청 메일(으)로 이동(안전하지 않음)').
 *     The permission screen lists sending mail and connecting to an external service (Firebase); the script needs both.
 *     If it shows checkboxes, tick 'Select all' ('모두 선택'). Then press 'Allow' ('허용') or 'Continue' ('계속').
 *     The deployment then shows the 'Web app' ('웹 앱') URL, https://script.google.com/macros/s/…/exec.
 *     Press 'Copy' ('복사') next to it, then 'Done' ('완료').
 *  8. Save that URL as a GitHub secret (GitHub's screens are in English only): repository 'Settings' > 'Secrets and variables'
 *     > 'Actions' > 'New repository secret', name SA_SOS_MAIL_URL, value the URL → 'Add secret'.
 *     The next release build puts the URL into the app; without it the app's mail feature stays off.
 *  9. After changing the code: 'Deploy' ('배포') > 'Manage deployments' ('배포 관리') > pencil 'Edit' ('수정') >
 *     'Version' ('버전'): 'New version' ('새 버전') → 'Deploy' ('배포'). The URL stays the same
 *     (running 'New deployment' ('새 배포') again changes the URL; SA_SOS_MAIL_URL must then be updated and the app rebuilt).
 * Secrets go only in the script properties, never in this file or the repository.
 */

var SITE_RE = /^[A-Za-z0-9_-]{1,32}$/;
var SC_RE = /^[A-Z0-9_-]{1,12}$/;
var ID_RE = /^[A-Za-z0-9_-]{1,40}$/;
var TO_RE = /^[A-Za-z0-9%+_-]+(\.[A-Za-z0-9%+_-]+)*@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$/;
var DB_URL_RE = /^https:\/\/[A-Za-z0-9.-]+\/?$/;
var DAY_MS = 24 * 3600 * 1000;
// SOS mail records are kept 7 days so resolution mails can be checked against them. With a regular
// account that is at most about 700 records (about 100KB, within the 500KB store limit).
var KEEP_MS = 7 * DAY_MS;
// Sent records are capped at 3,000 in total (about 370KB even with long keys, within the 500KB store). Past that the oldest are deleted,
// which only stops the resolution mail for those records. SOS mails are never blocked by this.
var MAX_SENT = 3000;
var FRESH_MS = 2 * 3600 * 1000;
var RATE_PER_MIN = 30;
var DEFAULT_CAP = 50;
var MAX_CAP = 60;
var SENT = 'S|';
var TAIL = '이 메일은 SafeAlert가 자동으로 보냈습니다. 회신하지 마십시오.';
// Equipment code → English name. Must match the app's PitType.kt (SosMailScriptParityTest compares them).
var PIT_NAMES = { CB: 'Counterbalance', RT: 'Reach Truck', HR: 'High Reach', OP: 'Order Picker',
  ST: 'Stacker', TT: 'Tow Tractor', EP: 'Electric Pallet Jack', WK: 'Walkie Stacker' };

function doPost(e) {
  try {
    return out(handle((e && e.parameter) || {}));
  } catch (err) {
    console.error('sos-mail: ' + String(err && err.message).replace(/auth=[^&\s]*/g, 'auth=***'));
    return out('error');
  }
}

function handle(p) {
  // ponytail: counted outside the lock, so a burst of concurrent requests can slightly
  // exceed the per-minute limit. Move it inside the lock if it must be exact.
  var cache = CacheService.getScriptCache();
  var rateKey = 'rate:' + Math.floor(Date.now() / 60000);
  var hits = parseInt(cache.get(rateKey), 10) || 0;
  if (hits >= RATE_PER_MIN) return 'busy';
  cache.put(rateKey, String(hits + 1), 120);

  var site = str(p.site), sc = str(p.sc), id = str(p.id), event = str(p.event), to = str(p.to);
  if (!SITE_RE.test(site) || !SC_RE.test(sc) || !ID_RE.test(id)) return 'bad_request';
  if (event !== 'sos' && event !== 'resolved') return 'bad_request';
  if (to.length > 254 || !TO_RE.test(to)) return 'bad_request';

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) return 'busy';
  try {
    var store = PropertiesService.getScriptProperties();
    var all = store.getProperties();
    var cfg = function (k) { return str(all[k]).trim(); };
    var lower = to.toLowerCase();
    if (!allowed(lower, cfg('ALLOWED_DOMAINS'))) return 'not_allowed';
    var dbUrl = cfg('FIREBASE_DB_URL');
    var secret = cfg('FIREBASE_DB_SECRET');
    if (!DB_URL_RE.test(dbUrl) || !secret) return 'error';

    var now = Date.now();
    var sent = {};
    Object.keys(all).forEach(function (k) {
      if (k.indexOf(SENT) !== 0) return;
      var at = Number(all[k]);
      var keep = k.indexOf(SENT + 'sos|') === 0 ? KEEP_MS : DAY_MS;
      if (!(now - at < keep)) store.deleteProperty(k);
      else sent[k] = at;
    });
    var keys = Object.keys(sent).sort(function (a, b) {
      return sent[a] - sent[b] || (a < b ? -1 : a > b ? 1 : 0);
    });
    for (var i = 0; i < keys.length - MAX_SENT; i++) {
      store.deleteProperty(keys[i]);
      delete sent[keys[i]];
    }

    var tag = addrTag(lower);
    var key = SENT + [event, site, sc, id, tag].join('|');
    if (sent[key]) return 'dup';
    if (event === 'resolved') {
      if (!sent[SENT + ['sos', site, sc, id, tag].join('|')]) return 'not_ready';
    } else {
      var prefix = SENT + 'sos|' + site + '|' + sc + '|';
      var used = Object.keys(sent).filter(function (k) {
        return k.indexOf(prefix) === 0 && now - sent[k] < DAY_MS;
      }).length;
      if (used >= cap(cfg('DAILY_MAX'))) return 'quota';
    }
    if (MailApp.getRemainingDailyQuota() < 1) return 'quota';

    var url = dbUrl.replace(/\/$/, '') + '/' + site + '/sos/' + sc + '/' + id +
      '.json?auth=' + encodeURIComponent(secret);
    var res = UrlFetchApp.fetch(url, { muteHttpExceptions: true });
    if (res.getResponseCode() !== 200) return 'error';
    var rec = JSON.parse(res.getContentText());
    if (rec === null || typeof rec !== 'object') return 'not_ready';

    if (event === 'sos') {
      if (rec.status !== 'active' && rec.status !== 'resolved') return 'not_ready';
    } else if (rec.status !== 'resolved' || typeof rec.resolvedAt !== 'number') {
      return 'not_ready';
    }
    var at = event === 'sos' ? rec.createdAt : rec.resolvedAt;
    if (typeof at !== 'number' || now - at > FRESH_MS) return 'stale';

    var m = buildMail(event, sc, id, rec, p.stillMin);
    // Record the send first: if saving fails nothing is sent, and if sending fails the record is deleted.
    store.setProperty(key, String(now));
    try {
      MailApp.sendEmail({ to: to, subject: m.subject, body: m.body, htmlBody: m.html, name: 'SafeAlert' });
    } catch (err) {
      store.deleteProperty(key);
      throw err;
    }
    return 'sent';
  } finally {
    lock.releaseLock();
  }
}

/** DAILY_MAX value → integer in 1..MAX_CAP (DEFAULT_CAP when not a number). */
function cap(v) {
  var n = parseInt(v, 10);
  if (isNaN(n)) n = DEFAULT_CAP;
  return Math.min(MAX_CAP, Math.max(1, n));
}

/** First 6 bytes (12 hex chars) of the SHA-256 of the lowercased address, so property keys never hold the raw address. */
function addrTag(lower) {
  var d = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, lower, Utilities.Charset.UTF_8);
  var s = '';
  for (var i = 0; i < 6; i++) s += ('0' + ((d[i] + 256) % 256).toString(16)).slice(-2);
  return s;
}

/** A domain entry allows addresses ending in '@' + domain; an entry containing '@' allows only that exact address. */
function allowed(to, list) {
  var items = String(list || '').split(/[\s,;]+/)
    .map(function (s) { return s.trim().toLowerCase().replace(/^@+/, ''); })
    .filter(function (s) { return s.length > 0; });
  if (items.length === 0) items = ['coupangfs.com'];
  return items.some(function (d) {
    if (d.indexOf('@') >= 0) return to === d;
    var tail = '@' + d;
    return to.length > tail.length && to.slice(-tail.length) === tail;
  });
}

/** Site code plus floor / process when the record has them (WF11-1F-OB). Only [A-Z0-9]{1,4} values are appended. */
function siteLabel(sc, rec) {
  ['floor', 'proc'].forEach(function (k) {
    if (typeof rec[k] === 'string' && /^[A-Z0-9]{1,4}$/.test(rec[k])) sc += '-' + rec[k];
  });
  return sc;
}

function buildMail(event, sc, id, rec, stillMin) {
  sc = siteLabel(sc, rec);   // Subject, header and site cell all show WF11-1F-OB
  var name = oneLine(rec.name);
  var role = roleName(rec.role, name);
  var no = id.slice(-6);
  var who = esc(sc) + ' · ' + esc(name) + ' (' + esc(role) + ')';
  if (event === 'resolved') {
    var sec = Math.floor((rec.resolvedAt - rec.createdAt) / 1000);
    if (!(sec > 0)) sec = 0;
    return {
      subject: '[SafeAlert 해제] ' + sc + ' ' + name + ' - 구조 요청 해제 (기록 ' + no + ')',
      html: mailHtml('#2e7d32', '구조 요청 해제', who, '작업자가 [괜찮아요]로 해제했습니다.', [
        ['해제 시각', esc(fmt(rec.resolvedAt, 'HH:mm:ss')) + ' (서버 기록)', true],
        ['걸린 시간', '구조 요청 기록 후 ' + Math.floor(sec / 60) + '분 ' + (sec % 60) + '초'],
        ['사업장', siteCell('#2e7d32', sc, rec)],
        ['상태', badge('#e8f5e9', '#2e7d32', '해제됨')],
        ['기록 번호', esc(no)]
      ]),
      body: name + '(' + role + ') 작업자가 서버 기록 ' + fmt(rec.resolvedAt, 'HH:mm:ss') +
        '에 [괜찮아요]로 구조 요청을 해제했습니다. (구조 요청 기록 후 ' + Math.floor(sec / 60) + '분 ' + (sec % 60) + '초)' +
        '\n기록 번호: ' + no + '\n\n' + TAIL
    };
  }
  var fall = rec.trigger === 'fall';
  var n = stillMinutes(stillMin);
  var cause = fall ? '넘어짐 감지 후 응답 없음'
    : (n ? n + '분 동안 움직임 없음 후 응답 없음' : '움직임 없음 후 응답 없음');
  var beacon = oneLine(rec.beacon) || '알 수 없음';
  var done = rec.status === 'resolved' && typeof rec.resolvedAt === 'number';
  var auto = rec.reason === 'auto';   // ended by the app's one-hour limit, not by the worker (no resolve mail follows)
  var ended = auto ? '응답 없이 자동 종료' : '해제됨';
  var state = done
    ? '상태: ' + ended + ' (' + fmt(rec.resolvedAt, 'HH:mm:ss') + ')'
    : '상태: 구조 요청 중 (작업자가 [괜찮아요]를 누르면 해제 메일이 갑니다)';
  return {
    subject: '[SafeAlert 구조 요청] ' + sc + ' ' + name + (fall ? ' - 넘어짐 감지' : ' - 움직임 없음') +
      ' (기록 ' + no + ')',
    html: mailHtml('#c62828', '구조 요청', who, '즉시 작업자 상태를 확인해 주십시오.', [
      ['원인', esc(cause), true],
      ['서버 기록 시각', esc(fmt(rec.createdAt, 'yyyy-MM-dd HH:mm:ss')) + ' (한국 시간)'],
      ['사업장', siteCell('#c62828', sc, rec)],
      ['상태', done ? badge(auto ? '#fff3e0' : '#e8f5e9', auto ? '#e65100' : '#2e7d32',
          ended + ' (' + esc(fmt(rec.resolvedAt, 'HH:mm:ss')) + ')')
        : badge('#fdecea', '#c62828', '구조 요청 중') +
          '<br><span style="font-size:12px;color:#666666;">작업자가 [괜찮아요]를 누르면 해제 메일이 갑니다.</span>'],
      ['기록 번호', esc(no)]
    ]),
    body: [
      'SafeAlert 단독 작업자 구조 요청이 발생했습니다. 즉시 작업자 상태를 확인해 주십시오.',
      '',
      '기록 번호: ' + no,
      '센터: ' + sc,
      '작업자: ' + name + ' (' + role + ')',
      '원인: ' + cause,
      '서버 기록 시각: ' + fmt(rec.createdAt, 'yyyy-MM-dd HH:mm:ss') + '(한국 시간)',
      '마지막 위치: ' + beacon,
      state,
      '',
      TAIL
    ].join('\n')
  };
}

/**
 * Role label: "보행자" for a walker; if the name is an equipment ID (same format as PitType.parse), the
 * equipment's English name; otherwise "지게차", "EPJ" or "알 수 없음". Same rule as the app's sosRoleLabel.
 */
function roleName(r, name) {
  if (r === 'WALKER') return '보행자';
  var m = /^([A-Z]{2})-([0-9]{2})$/.exec(str(name).trim().toUpperCase());
  if (m && m[2] !== '00' && PIT_NAMES[m[1]]) return PIT_NAMES[m[1]];
  if (r === 'FORKLIFT') return '지게차';
  if (r === 'EPJ') return 'EPJ';
  return '알 수 없음';
}

/** An integer 1–30 is returned as is; anything else gives 0 (wording without a number). */
function stillMinutes(v) {
  var s = str(v);
  if (!/^[0-9]{1,2}$/.test(s)) return 0;
  var n = parseInt(s, 10);
  return n >= 1 && n <= 30 ? n : 0;
}

/**
 * Replaces control, line-separator, zero-width and bidi-control characters and the
 * BOM with spaces, then trims and keeps the first 64 characters.
 */
function oneLine(v) {
  return str(v)
    .replace(/[\u0000-\u001f\u007f-\u009f\u200B-\u200F\u2028-\u202E\u2060-\u206F\uFEFF]+/g, ' ')
    .trim().slice(0, 64);
}

/** Escapes the five HTML special characters (& < > " '). */
function esc(v) {
  var map = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  return str(v).replace(/[&<>"']/g, function (c) { return map[c]; });
}

/** Colored text box for the '상태' (status) and '사업장' (site) cells. */
function badge(bg, fg, html) {
  return '<span style="background:' + bg + ';color:' + fg + ';font-weight:bold;padding:2px 8px;">' + html + '</span>';
}

/** The '사업장' (site) cell: the center in a box the same color as the header band, plus the beacon name when known. */
function siteCell(color, sc, rec) {
  var beacon = oneLine(rec.beacon);
  return badge(color, '#ffffff', esc(sc)) + (beacon ? ' · ' + esc(beacon) : '');
}

/**
 * Mail body with a colored band and a table (tables and inline styles only, centered, max 600px). Strings are inserted as is,
 * so wrap changing values in esc before passing them in. rows is a list of [label, value html, bold].
 */
function mailHtml(color, title, who, lead, rows) {
  var cells = rows.map(function (r, i) {
    var line = i < rows.length - 1 ? 'border-bottom:1px solid #eeeeee;' : '';
    return '<tr><td style="' + (i === 0 ? 'width:110px;' : '') + 'padding:9px 0;color:#666666;' + line +
      'vertical-align:top;">' + r[0] + '</td><td style="padding:9px 0;' + (r[2] ? 'font-weight:bold;' : '') + line +
      '">' + r[1] + '</td></tr>';
  }).join('');
  return '<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr><td align="center">' +
    '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;' +
    'max-width:600px;background:#ffffff;font-family:\'Malgun Gothic\',\'Apple SD Gothic Neo\',Arial,sans-serif;">' +
    '<tr><td style="background:' + color + ';color:#ffffff;padding:16px 20px;">' +
    '<div style="font-size:13px;">SafeAlert 단독 작업자</div>' +
    '<div style="font-size:22px;font-weight:bold;margin-top:2px;">' + title + '</div>' +
    '<div style="font-size:15px;margin-top:4px;">' + who + '</div></td></tr>' +
    '<tr><td style="padding:16px 20px 4px;font-size:15px;font-weight:bold;color:' + color + ';">' + lead + '</td></tr>' +
    '<tr><td style="padding:6px 20px 16px;"><table role="presentation" width="100%" cellpadding="0" cellspacing="0"' +
    ' style="border-collapse:collapse;font-size:14px;">' + cells + '</table></td></tr>' +
    '<tr><td style="padding:10px 20px;background:#f5f6f8;color:#888888;font-size:12px;">' + TAIL + '</td></tr>' +
    '</table></td></tr></table>';
}

function fmt(ms, pattern) {
  return Utilities.formatDate(new Date(ms), 'Asia/Seoul', pattern);
}

function str(v) {
  return v === undefined || v === null ? '' : String(v);
}

function out(code) {
  return ContentService
    .createTextOutput(JSON.stringify({ ok: code === 'sent' || code === 'dup', code: code }))
    .setMimeType(ContentService.MimeType.JSON);
}
