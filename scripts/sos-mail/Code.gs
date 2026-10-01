/*
 * SafeAlert 단독 작업자 구조 요청 메일 (v1.2.2)
 * 앱이 구조 요청·해제를 서버에 기록한 뒤 이 웹 앱에 알리면, 서버 기록을 직접 확인하고 정해진 양식의 메일을 한 번 보낸다.
 *
 * 설치 순서
 *  1. 알림 전용 구글 계정으로 로그인한다. 메일은 이 계정 이름으로 나간다.
 *     이 계정의 하루 메일 한도(일반 구글 계정 받는 사람 100명, Workspace 1,500명)가 차면 다음 날까지
 *     해제 메일도 막힐 수 있다(구조 요청 메일을 먼저 보낸다).
 *  2. script.google.com → 새 프로젝트. 이름은 'SafeAlert 구조 요청 메일'.
 *  3. 기본 코드를 지우고 이 파일 전체를 붙여넣은 뒤 저장한다.
 *  4. 왼쪽 톱니(프로젝트 설정) → 시간대를 (GMT+09:00) 서울로.
 *  5. 같은 화면 아래 '스크립트 속성'에 추가한다.
 *     - FIREBASE_DB_URL    : 파이어베이스 콘솔 Realtime Database 화면 맨 위 주소 (https://….firebaseio.com 등)
 *     - FIREBASE_DB_SECRET : 파이어베이스 프로젝트 설정 > 서비스 계정 > 데이터베이스 비밀번호
 *     - ALLOWED_DOMAINS    : 메일을 받을 수 있는 도메인. 비우면 coupangfs.com. 쉼표·세미콜론·공백·줄바꿈 어느 것으로
 *                            나눠도 되고 앞의 '@' 와 대소문자는 무시한다. 정확한 주소(me@example.com)도 넣을 수 있다.
 *     - DAILY_MAX (선택)   : 사업장(루트+센터)마다 최근 24시간 동안 보낸 구조 요청 메일 수 상한. 기본 50, 1~60.
 *                            해제 메일은 세지 않고 막지도 않는다.
 *     'S|' 로 시작하는 속성은 스크립트가 쓰는 보낸 기록이라 손대지 않는다(구조 요청 7일·해제 24시간 보관 뒤
 *     자동 삭제. 합쳐 3,000건이 넘으면 오래된 것부터 지운다(지워진 구조 요청은 해제 메일만 못 나간다).
 *     예전 SENT_LOG 속성이 있으면 지워도 된다).
 *  6. 배포 > 새 배포 > 유형 '웹 앱', 실행 계정 '나', 액세스 '모든 사용자' → 배포.
 *  7. 권한 창에서 계정 선택 → '고급' → 이동 → 허용 (메일 보내기·외부 서비스 연결).
 *  8. 나온 웹 앱 주소를 GitHub 저장소 Settings > Secrets and variables > Actions 의 비밀값 SA_SOS_MAIL_URL 로 저장한다.
 *  9. 코드를 고친 뒤에는 배포 > 배포 관리 > 연필 > 버전 '새 버전' → 배포. 주소가 그대로 유지된다
 *     ('새 배포'를 다시 하면 주소가 바뀌어 앱을 다시 빌드해야 한다).
 * 비밀값은 스크립트 속성에만 넣는다. 이 파일과 저장소에는 적지 않는다.
 */

var SITE_RE = /^[A-Za-z0-9_-]{1,32}$/;
var SC_RE = /^[A-Z0-9_-]{1,12}$/;
var ID_RE = /^[A-Za-z0-9_-]{1,40}$/;
var TO_RE = /^[A-Za-z0-9%+_-]+(\.[A-Za-z0-9%+_-]+)*@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$/;
var DB_URL_RE = /^https:\/\/[A-Za-z0-9.-]+\/?$/;
var DAY_MS = 24 * 3600 * 1000;
// 구조 요청 메일 기록은 해제 메일 확인용으로 7일 보관. 일반 계정은 많아야 700건 안팎(약 100KB, 저장소 500KB 한도 안).
var KEEP_MS = 7 * DAY_MS;
// 보낸 기록은 합쳐 많아야 3,000건(긴 키도 약 370KB, 저장소 500KB 안). 넘으면 오래된 것부터 지워
// 그 기록의 해제 메일만 못 나간다. 구조 요청 메일은 막지 않는다.
var MAX_SENT = 3000;
var FRESH_MS = 2 * 3600 * 1000;
var RATE_PER_MIN = 30;
var DEFAULT_CAP = 50;
var MAX_CAP = 60;
var SENT = 'S|';
var TAIL = '이 메일은 SafeAlert가 자동으로 보냈습니다. 회신하지 마십시오.';

function doPost(e) {
  try {
    return out(handle((e && e.parameter) || {}));
  } catch (err) {
    console.error('sos-mail: ' + String(err && err.message).replace(/auth=[^&\s]*/g, 'auth=***'));
    return out('error');
  }
}

function handle(p) {
  // ponytail: 잠금 없이 세므로 동시 요청이 몰리면 분당 수가 조금 넘을 수 있다. 정확히 막아야 하면 잠금 안으로.
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
    // 보낼 기록을 먼저 남긴다. 저장이 안 되면 보내지 않고, 보내기가 실패하면 기록을 지운다.
    store.setProperty(key, String(now));
    try {
      MailApp.sendEmail({ to: to, subject: m.subject, body: m.body, name: 'SafeAlert' });
    } catch (err) {
      store.deleteProperty(key);
      throw err;
    }
    return 'sent';
  } finally {
    lock.releaseLock();
  }
}

/** DAILY_MAX 값 → 1~MAX_CAP 정수(숫자가 아니면 DEFAULT_CAP). */
function cap(v) {
  var n = parseInt(v, 10);
  if (isNaN(n)) n = DEFAULT_CAP;
  return Math.min(MAX_CAP, Math.max(1, n));
}

/** 소문자 주소의 SHA-256 앞 6바이트(16진 12자). 속성 키에 주소 원문을 남기지 않는다. */
function addrTag(lower) {
  var d = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, lower, Utilities.Charset.UTF_8);
  var s = '';
  for (var i = 0; i < 6; i++) s += ('0' + ((d[i] + 256) % 256).toString(16)).slice(-2);
  return s;
}

/** 도메인 항목은 '@' + 도메인으로 끝날 때, '@' 가 든 항목은 주소가 완전히 같을 때만 허용. */
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

function buildMail(event, sc, id, rec, stillMin) {
  var name = oneLine(rec.name);
  var role = roleName(rec.role);
  var no = id.slice(-6);
  if (event === 'resolved') {
    var sec = Math.floor((rec.resolvedAt - rec.createdAt) / 1000);
    if (!(sec > 0)) sec = 0;
    return {
      subject: '[SafeAlert 해제] ' + sc + ' ' + name + ' - 구조 요청 해제 (기록 ' + no + ')',
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
  var state = rec.status === 'resolved' && typeof rec.resolvedAt === 'number'
    ? '상태: 해제됨 (' + fmt(rec.resolvedAt, 'HH:mm:ss') + ')'
    : '상태: 구조 요청 중 (작업자가 [괜찮아요]를 누르면 해제 메일이 갑니다)';
  return {
    subject: '[SafeAlert 구조 요청] ' + sc + ' ' + name + (fall ? ' - 넘어짐 감지' : ' - 움직임 없음') +
      ' (기록 ' + no + ')',
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

function roleName(r) {
  if (r === 'WALKER') return '보행자';
  if (r === 'FORKLIFT') return '지게차';
  if (r === 'EPJ') return 'EPJ';
  return '알 수 없음';
}

/** 1~30 정수면 그 값, 아니면 0(숫자 없는 문구). */
function stillMinutes(v) {
  var s = str(v);
  if (!/^[0-9]{1,2}$/.test(s)) return 0;
  var n = parseInt(s, 10);
  return n >= 1 && n <= 30 ? n : 0;
}

/** 제어 문자·줄 구분·폭 0·방향 제어·BOM 을 공백으로 바꿔 한 줄로. */
function oneLine(v) {
  return str(v)
    .replace(/[\u0000-\u001f\u007f-\u009f\u200B-\u200F\u2028-\u202E\u2060-\u206F\uFEFF]+/g, ' ')
    .trim().slice(0, 64);
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
