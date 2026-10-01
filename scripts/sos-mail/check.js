// Offline check for Code.gs: runs it on fake Apps Script globals. No real mail, no network.
// Usage: node scripts/sos-mail/check.js
'use strict';
const fs = require('fs');
const path = require('path'), vm = require('vm'), assert = require('assert'), crypto = require('crypto');

const src = fs.readFileSync(path.join(__dirname, 'Code.gs'), 'utf8');
const NOW = Date.now();
const SECRET = 'se/cr+t';
const HOUR = 3600 * 1000;
let scenes = 0;

function make(o) {
  o = o || {};
  const w = {
    props: Object.assign({ FIREBASE_DB_URL: 'https://db.example.com/', FIREBASE_DB_SECRET: SECRET }, o.props || {}),
    db: o.db || {}, status: 200, lockOk: true, quota: 100, cache: {},
    fetched: [], mails: [], zones: [], errors: [], locked: 0, released: 0, reads: 0,
    setFail: false, sendFail: false, fetchFail: false
  };
  for (const k of Object.keys(w.props)) if (w.props[k] === null) delete w.props[k];
  const props = {
    getProperties: () => { w.reads++; return Object.assign({}, w.props); },
    getProperty: () => { throw new Error('single property read'); },
    setProperty: (k, v) => { if (w.setFail) throw new Error('store full'); w.props[k] = String(v); },
    deleteProperty: (k) => { delete w.props[k]; }
  };
  const ctx = {
    Date: Object.assign(function (ms) { return new Date(ms); }, { now: () => NOW }), // fixed clock: no minute rollover
    PropertiesService: { getScriptProperties: () => props },
    CacheService: {
      getScriptCache: () => ({
        get: (k) => (k in w.cache ? w.cache[k] : null),
        put: (k, v, s) => { assert.ok(s > 0); w.cache[k] = String(v); }
      })
    },
    LockService: {
      getScriptLock: () => ({
        tryLock: () => { if (w.lockOk) w.locked++; return w.lockOk; },
        releaseLock: () => { w.released++; }
      })
    },
    UrlFetchApp: {
      fetch: (url, opt) => {
        assert.strictEqual(opt.muteHttpExceptions, true);
        w.fetched.push(url);
        if (w.fetchFail) throw new Error('Address unavailable: ' + url);
        const m = url.match(/\/sos\/[^/]+\/([^.]+)\.json/);
        const rec = m ? w.db[m[1]] : undefined;
        return { getResponseCode: () => w.status, getContentText: () => JSON.stringify(rec === undefined ? null : rec) };
      }
    },
    MailApp: {
      sendEmail: (m) => { if (w.sendFail) throw new Error('send failed'); w.mails.push(m); },
      getRemainingDailyQuota: () => w.quota
    },
    ContentService: {
      MimeType: { JSON: 'json' },
      createTextOutput: (s) => { const t = { mime: null, getContent: () => s }; t.setMimeType = (m) => { t.mime = m; return t; }; return t; }
    },
    Utilities: {
      formatDate: (d, tz, p) => { w.zones.push(tz); return 'T(' + p + ')'; },
      DigestAlgorithm: { SHA_256: 'sha256' },
      Charset: { UTF_8: 'utf8' },
      computeDigest: (alg, s, cs) => {
        assert.strictEqual(alg, 'sha256');
        assert.strictEqual(cs, 'utf8');
        return Array.from(crypto.createHash('sha256').update(s, 'utf8').digest()).map((b) => (b > 127 ? b - 256 : b));
      }
    },
    console: { error: (s) => { w.errors.push(String(s)); }, log: () => {} }
  };
  vm.createContext(ctx);
  vm.runInContext(src, ctx);
  const post = (p) => {
    const base = { site: 'wf11', sc: 'WF11', id: 'k1', event: 'sos', to: 'wfspt@coupangfs.com', stillMin: '3' };
    const out = ctx.doPost({ parameter: Object.assign(base, p) });
    assert.strictEqual(out.mime, 'json');
    const r = JSON.parse(out.getContent());
    assert.strictEqual(r.ok, r.code === 'sent' || r.code === 'dup');
    assert.deepStrictEqual(Object.keys(r).sort(), ['code', 'ok']);
    scenes++;
    return r.code;
  };
  const sentKeys = () => Object.keys(w.props).filter((k) => k.indexOf('S|') === 0);
  return { w, post, sentKeys };
}

const still = { name: 'Kim', role: 'WALKER', trigger: 'still', createdAt: NOW - 60000, status: 'active', uid: 'u' };
const fallDone = { name: 'Lee', role: 'FORKLIFT', trigger: 'fall', beacon: 'Rack\nA', createdAt: NOW - 200000,
  resolvedAt: NOW - 200000 + 125000, status: 'resolved', uid: 'u' };
const NO = '\uAE30\uB85D ';             // record
const NO_LINE = '\uAE30\uB85D \uBC88\uD638: ';

// bad_request (address rules: no leading/trailing/double dot in the local part)
let t = make({ db: { k1: still } });
for (const p of [{ sc: 'wf11' }, { id: 'a.b' }, { id: 'a/b' }, { site: 'a.b' }, { event: 'x' }, { to: 'a@b' }, { to: 'a b@x.com' },
  { to: '.a@coupangfs.com' }, { to: 'a.@coupangfs.com' }, { to: 'a..b@coupangfs.com' }]) {
  assert.strictEqual(t.post(p), 'bad_request', JSON.stringify(p));
}
assert.strictEqual(t.w.reads, 0);
assert.strictEqual(t.post({ to: 'a.b@coupangfs.com' }), 'sent');
// not_allowed (default domain list)
for (const to of ['x@gmail.com', 'x@evilcoupangfs.com', 'x@coupangfs.com.evil.com']) {
  assert.strictEqual(t.post({ to }), 'not_allowed', to);
}
assert.strictEqual(t.w.fetched.length, 1);
assert.strictEqual(t.w.mails.length, 1);

// exact address in ALLOWED_DOMAINS
t = make({ db: { k1: still }, props: { ALLOWED_DOMAINS: ' coupangfs.com , Me@Gmail.com ' } });
assert.strictEqual(t.post({ to: 'you@gmail.com' }), 'not_allowed');
assert.strictEqual(t.post({ to: 'me@gmail.com' }), 'sent');

// list separators: comma, semicolon, spaces, newline; leading '@' and case ignored
t = make({ db: { k1: still }, props: { ALLOWED_DOMAINS: ' @CoupangFS.com ; me@Gmail.com\nfoo.com' } });
assert.strictEqual(t.post({ to: 'x@coupangfs.com' }), 'sent');
assert.strictEqual(t.post({ to: 'me@gmail.com' }), 'sent');
assert.strictEqual(t.post({ to: 'y@foo.com' }), 'sent');
assert.strictEqual(t.post({ to: 'you@gmail.com' }), 'not_allowed');

// not_ready: no record; resolved before any sos mail (no DB read)
t = make({ db: { k1: still } });
assert.strictEqual(t.post({ id: 'k9' }), 'not_ready');
assert.strictEqual(t.post({ event: 'resolved' }), 'not_ready');
assert.strictEqual(t.w.fetched.length, 1);

// sent: sos still, one property read per request
let reads = t.w.reads;
assert.strictEqual(t.post({}), 'sent');
assert.strictEqual(t.w.reads - reads, 1);
let m = t.w.mails[0];
assert.strictEqual(m.to, 'wfspt@coupangfs.com');
assert.strictEqual(m.name, 'SafeAlert');
assert.ok(m.subject.startsWith('[SafeAlert '));
assert.ok(m.subject.includes('WF11') && m.subject.includes('Kim'));
assert.ok(m.subject.endsWith('(' + NO + 'k1)'));
assert.ok(m.body.includes(NO_LINE + 'k1\n'));
assert.ok(m.body.includes('\uC11C\uBC84 \uAE30\uB85D \uC2DC\uAC01: '));
assert.ok(m.body.includes('3\uBD84 \uB3D9\uC548 \uC6C0\uC9C1\uC784 \uC5C6\uC74C'));
assert.ok(m.body.includes('\uBCF4\uD589\uC790'));
assert.ok(m.body.includes('\uC54C \uC218 \uC5C6\uC74C'));
assert.ok(!m.body.includes('\uD574\uC81C\uB428'));
assert.ok(t.w.zones.length > 0 && t.w.zones.every((z) => z === 'Asia/Seoul'));
const url = t.w.fetched[t.w.fetched.length - 1];
assert.ok(url.startsWith('https://db.example.com/wf11/sos/WF11/k1.json?auth='));
assert.ok(url.endsWith(encodeURIComponent(SECRET)) && !url.endsWith(SECRET));
assert.ok(t.sentKeys().length === 1 && t.sentKeys().every((k) => k.indexOf('@') < 0 && /\|[0-9a-f]{12}$/.test(k)));

// same request again: dup, no new mail; same address in other case: dup
assert.strictEqual(t.post({}), 'dup');
assert.strictEqual(t.post({ to: 'WFSPT@CoupangFS.com' }), 'dup');
assert.strictEqual(t.w.mails.length, 1);
// same record, another allowed address: its own mail
assert.strictEqual(t.post({ to: 'boss@coupangfs.com' }), 'sent');
assert.strictEqual(t.w.mails.length, 2);
assert.strictEqual(t.w.mails[1].to, 'boss@coupangfs.com');

// stillMin outside 1..30: cause without a number
t.w.db.k3 = Object.assign({}, still);
assert.strictEqual(t.post({ id: 'k3', stillMin: '0' }), 'sent');
assert.ok(t.w.mails[2].body.includes(': \uC6C0\uC9C1\uC784 \uC5C6\uC74C \uD6C4 \uC751\uB2F5 \uC5C6\uC74C'));

// fall sos already resolved: status line says resolved; then resolved mail with the same record number
const LONG = 'abcdefghijKLMNOP';
t.w.db[LONG] = fallDone;
assert.strictEqual(t.post({ id: LONG, to: 'other@coupangfs.com' }), 'sent');
assert.strictEqual(t.post({ id: LONG, event: 'resolved' }), 'not_ready'); // sos mail went to another address
assert.strictEqual(t.post({ id: LONG }), 'sent');
m = t.w.mails[4];
assert.ok(m.subject.includes('\uB118\uC5B4\uC9D0 \uAC10\uC9C0'));
assert.ok(m.subject.endsWith('(' + NO + 'KLMNOP)'));
assert.ok(m.body.includes('Rack A') && !/[\r\n]/.test(m.subject));
assert.ok(m.body.includes('\uD574\uC81C\uB428 (T(HH:mm:ss))'));
assert.strictEqual(t.post({ id: LONG, event: 'resolved' }), 'sent');
m = t.w.mails[5];
assert.ok(m.subject.includes('Lee'));
assert.ok(m.subject.endsWith('(' + NO + 'KLMNOP)'));
assert.ok(m.body.includes(NO_LINE + 'KLMNOP'));
assert.ok(m.body.includes('\uAD6C\uC870 \uC694\uCCAD \uAE30\uB85D \uD6C4 2\uBD84 5\uCD08'));
assert.strictEqual(t.post({ id: LONG, event: 'resolved' }), 'dup');
assert.strictEqual(t.w.locked, t.w.released);

// stale
t = make({ db: { k1: Object.assign({}, still, { createdAt: NOW - 3 * HOUR }) } });
assert.strictEqual(t.post({}), 'stale');

// quota per site+center for sos mails only; resolved mails are not capped
t = make({ db: { k1: fallDone, k2: still, k3: still }, props: { DAILY_MAX: '1' } });
assert.strictEqual(t.post({}), 'sent');
assert.strictEqual(t.post({ id: 'k2' }), 'quota');
assert.strictEqual(t.post({ id: 'k3', sc: 'WF12' }), 'sent');
assert.strictEqual(t.post({ id: 'k2', site: 'wf12' }), 'sent');
assert.strictEqual(t.post({ event: 'resolved' }), 'sent');
assert.strictEqual(t.w.mails.length, 4);
// DAILY_MAX above the ceiling stops at 60
t = make({ db: {}, props: { DAILY_MAX: '1000' } });
for (let i = 0; i <= 60; i++) {
  t.w.db['s' + i] = still;
  t.w.cache = {};
  assert.strictEqual(t.post({ id: 's' + i }), i < 60 ? 'sent' : 'quota', 'cap at ' + i);
}
// MailApp quota exhausted
t = make({ db: { k1: still } });
t.w.quota = 0;
assert.strictEqual(t.post({}), 'quota');
assert.strictEqual(t.w.mails.length, 0);

// error: secret missing, DB 401, DB address without scheme; spaces around settings are trimmed
t = make({ db: { k1: still }, props: { FIREBASE_DB_SECRET: null } });
assert.strictEqual(t.post({}), 'error');
t = make({ db: { k1: still } });
t.w.status = 401;
assert.strictEqual(t.post({}), 'error');
t = make({ db: { k1: still }, props: { FIREBASE_DB_URL: 'db.example.com' } });
assert.strictEqual(t.post({}), 'error');
assert.strictEqual(t.w.fetched.length, 0);
t = make({ db: { k1: still }, props: { FIREBASE_DB_URL: ' https://db.example.com/ ', FIREBASE_DB_SECRET: ' ' + SECRET + ' ' } });
assert.strictEqual(t.post({}), 'sent');
assert.ok(t.w.fetched[0].startsWith('https://db.example.com/wf11/'));
assert.ok(t.w.fetched[0].endsWith('auth=' + encodeURIComponent(SECRET)));

// fetch exception: logged without the secret
t = make({ db: { k1: still } });
t.w.fetchFail = true;
assert.strictEqual(t.post({}), 'error');
assert.strictEqual(t.w.errors.length, 1);
assert.ok(t.w.errors[0].includes('auth=***'));
assert.ok(t.w.errors.every((e) => !e.includes(SECRET) && !e.includes(encodeURIComponent(SECRET))));
assert.strictEqual(t.sentKeys().length, 0);

// reserve before send: send failure leaves no record and a retry sends once; store failure sends nothing
t = make({ db: { k1: still } });
t.w.sendFail = true;
assert.strictEqual(t.post({}), 'error');
assert.strictEqual(t.sentKeys().length, 0);
t.w.sendFail = false;
assert.strictEqual(t.post({}), 'sent');
assert.strictEqual(t.post({}), 'dup');
assert.strictEqual(t.w.mails.length, 1);
t = make({ db: { k1: still } });
t.w.setFail = true;
assert.strictEqual(t.post({}), 'error');
assert.strictEqual(t.w.mails.length, 0);

// hidden characters in names never reach the mail
t = make({ db: { k1: Object.assign({}, still, { name: 'K\u2028i\u202Em\u200B\uFEFF\u0085X', beacon: 'R\u2029\u2066B' }) } });
assert.strictEqual(t.post({}), 'sent');
m = t.w.mails[0];
for (const ch of ['\u2028', '\u2029', '\u202E', '\u200B', '\uFEFF', '\u0085', '\u2066']) {
  assert.ok(!m.subject.includes(ch) && !m.body.includes(ch), 'char ' + ch.charCodeAt(0).toString(16));
}

// sent records older than 24 hours are removed; fresh ones stay
const OLD = 'S|sos|wf11|WF11|old|aaaaaaaaaaaa', FRESH = 'S|sos|wf11|WF11|new|bbbbbbbbbbbb';
t = make({ db: { k1: still }, props: { [OLD]: String(NOW - 25 * HOUR), [FRESH]: String(NOW - HOUR) } });
assert.strictEqual(t.post({}), 'sent');
assert.ok(!(OLD in t.w.props) && FRESH in t.w.props);

// busy: lock not taken
t = make({ db: { k1: still } });
t.w.lockOk = false;
assert.strictEqual(t.post({}), 'busy');
assert.strictEqual(t.w.mails.length, 0);

// busy: more than 30 requests in a minute, before settings or DB are touched
t = make({ db: { k1: still } });
for (let i = 0; i < 30; i++) assert.notStrictEqual(t.post({}), 'busy', 'request ' + i);
reads = t.w.reads;
const fetched = t.w.fetched.length, locked = t.w.locked;
assert.strictEqual(t.post({}), 'busy');
assert.strictEqual(t.w.reads, reads);
assert.strictEqual(t.w.fetched.length, fetched);
assert.strictEqual(t.w.locked, locked);
assert.strictEqual(t.w.mails.length, 1);

console.log('sos-mail check OK (' + scenes + ' scenes)');
