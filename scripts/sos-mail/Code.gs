/*
 * SafeAlert 단독 작업자 구조 요청 메일 (v1.2.2)
 * 앱이 구조 요청·해제를 서버에 기록한 뒤 이 웹 앱에 알리면, 서버 기록을 직접 확인하고 정해진 양식의 메일을 한 번 보낸다.
 *
 * 설치 순서
 *  1. 알림 전용 구글 계정으로 로그인한다. 메일은 이 계정 이름으로 나간다.
 *  2. script.google.com → 새 프로젝트. 이름은 'SafeAlert 구조 요청 메일'.
 *  3. 기본 코드를 지우고 이 파일 전체를 붙여넣은 뒤 저장한다.
 *  4. 왼쪽 톱니(프로젝트 설정) → 시간대를 (GMT+09:00) 서울로.
 *  5. 같은 화면 아래 '스크립트 속성'에 추가한다.
 *     - FIREBASE_DB_URL    : 파이어베이스 콘솔 Realtime Database 화면 맨 위 주소 (https://….firebaseio.com 등)
 *     - FIREBASE_DB_SECRET : 파이어베이스 프로젝트 설정 > 서비스 계정 > 데이터베이스 비밀번호
 *     - ALLOWED_DOMAINS    : 메일을 받을 수 있는 도메인. 기본 coupangfs.com. 쉼표로 여러 개, 정확한 주소도 넣을 수 있다.
 *     - DAILY_MAX (선택)   : 24시간 동안 보낼 최대 통수. 기본 50, 최대 100.
 *     SENT_LOG 속성은 스크립트가 보낸 기록을 적는 칸이라 손대지 않는다.
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
var TO_RE = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$/;
var DAY_MS = 24 * 3600 * 1000;
var FRESH_MS = 2 * 3600 * 1000;
var TAIL = '이 메일은 SafeAlert가 자동으로 보냈습니다. 회신하지 마십시오.';

function doPost(e) {
  try {
    return out(handle((e && e.parameter) || {}));
  } catch (err) {
    console.error('sos-mail: ' + (err && err.message));
    return out('error');
  }
}

function handle(p) {
  var site = str(p.site), sc = str(p.sc), id = str(p.id), event = str(p.event), to = str(p.to);
  if (!SITE_RE.test(site) || !SC_RE.test(sc) || !ID_RE.test(id)) return 'bad_request';
  if (event !== 'sos' && event !== 'resolved') return 'bad_request';
  if (to.length > 254 || !TO_RE.test(to)) return 'bad_request';

  var props = PropertiesService.getScriptProperties();
  if (!allowed(to.toLowerCase(), props.getProperty('ALLOWED_DOMAINS'))) return 'not_allowed';
  var dbUrl = str(props.getProperty('FIREBASE_DB_URL'));
  var secret = str(props.getProperty('FIREBASE_DB_SECRET'));
  if (!dbUrl || !secret) return 'error';

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) return 'busy';
  try {
    var now = Date.now();
    var log = readLog(props, now);
    var logKey = [event, site, sc, id].join(':');
    if (log[logKey]) return 'dup';

    var url = dbUrl.replace(/\/+$/, '') + '/' + site + '/sos/' + sc + '/' + id +
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

    var max = parseInt(props.getProperty('DAILY_MAX'), 10);
    if (isNaN(max)) max = 50;
    max = Math.min(100, Math.max(1, max));
    if (Object.keys(log).length >= max || MailApp.getRemainingDailyQuota() < 1) return 'quota';

    var m = buildMail(event, sc, rec, p.stillMin);
    MailApp.sendEmail({ to: to, subject: m.subject, body: m.body, name: 'SafeAlert' });
    log[logKey] = now;
    props.setProperty('SENT_LOG', JSON.stringify(log));
    return 'sent';
  } finally {
    lock.releaseLock();
  }
}

/** 보낸 기록(키 → 보낸 시각). 24시간 지난 항목은 버린다. */
function readLog(props, now) {
  var log = {};
  try {
    log = JSON.parse(props.getProperty('SENT_LOG') || '{}') || {};
  } catch (err) {
    log = {};
  }
  var keep = {};
  Object.keys(log).forEach(function (k) {
    if (typeof log[k] === 'number' && now - log[k] < DAY_MS) keep[k] = log[k];
  });
  return keep;
}

/** 도메인 항목은 '@' + 도메인으로 끝날 때, '@' 가 든 항목은 주소가 완전히 같을 때만 허용. */
function allowed(to, list) {
  var items = String(list || '').split(',')
    .map(function (s) { return s.trim().toLowerCase(); })
    .filter(function (s) { return s.length > 0; });
  if (items.length === 0) items = ['coupangfs.com'];
  return items.some(function (d) {
    if (d.indexOf('@') >= 0) return to === d;
    var tail = '@' + d;
    return to.length > tail.length && to.slice(-tail.length) === tail;
  });
}

function buildMail(event, sc, rec, stillMin) {
  var name = oneLine(rec.name);
  var role = roleName(rec.role);
  if (event === 'resolved') {
    var sec = Math.floor((rec.resolvedAt - rec.createdAt) / 1000);
    if (!(sec > 0)) sec = 0;
    return {
      subject: '[SafeAlert 해제] ' + sc + ' ' + name + ' - 구조 요청 해제',
      body: name + '(' + role + ') 작업자가 ' + fmt(rec.resolvedAt, 'HH:mm:ss') +
        '에 [괜찮아요]로 구조 요청을 해제했습니다. (발생 후 ' + Math.floor(sec / 60) + '분 ' + (sec % 60) + '초)' +
        '\n\n' + TAIL
    };
  }
  var fall = rec.trigger === 'fall';
  var n = stillMinutes(stillMin);
  var cause = fall ? '넘어짐 감지 후 응답 없음'
    : (n ? n + '분 동안 움직임 없음 후 응답 없음' : '움직임 없음 후 응답 없음');
  var beacon = oneLine(rec.beacon) || '알 수 없음';
  return {
    subject: '[SafeAlert 구조 요청] ' + sc + ' ' + name + (fall ? ' - 넘어짐 감지' : ' - 움직임 없음'),
    body: [
      'SafeAlert 단독 작업자 구조 요청이 발생했습니다. 즉시 작업자 상태를 확인해 주십시오.',
      '',
      '센터: ' + sc,
      '작업자: ' + name + ' (' + role + ')',
      '원인: ' + cause,
      '발생 시각: ' + fmt(rec.createdAt, 'yyyy-MM-dd HH:mm:ss') + '(한국 시간)',
      '마지막 위치: ' + beacon,
      '상태: 구조 요청 중 (작업자가 [괜찮아요]를 누르면 해제 메일이 갑니다)',
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

function oneLine(v) {
  return str(v).replace(/[\u0000-\u001f\u007f]+/g, ' ').trim().slice(0, 64);
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
