// Offline check for Code.gs: runs it on fake Apps Script globals. No real mail, no network.
// Usage: node scripts/sos-mail/check.js
'use strict';
const fs = require('fs');
const path = require('path'), vm = require('vm'), assert = require('assert');

const src = fs.readFileSync(path.join(__dirname, 'Code.gs'), 'utf8');
const NOW = Date.now();
const SECRET = 'se/cr+t';
let scenes = 0;

function make(o) {
  o = o || {};
  const w = {
    props: Object.assign({ FIREBASE_DB_URL: 'https://db.example.com/', FIREBASE_DB_SECRET: SECRET }, o.props || {}),
    db: o.db || {}, status: 200, lockOk: true, quota: 100,
    fetched: [], mails: [], zones: [], locked: 0, released: 0
  };
  for (const k of Object.keys(w.props)) if (w.props[k] === null) delete w.props[k];
  const props = { getProperty: (k) => (k in w.props ? w.props[k] : null), setProperty: (k, v) => { w.props[k] = String(v); } };
  const ctx = {
    PropertiesService: { getScriptProperties: () => props },
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
        const m = url.match(/\/sos\/[^/]+\/([^.]+)\.json/);
        const rec = m ? w.db[m[1]] : undefined;
        return { getResponseCode: () => w.status, getContentText: () => JSON.stringify(rec === undefined ? null : rec) };
      }
    },
    MailApp: { sendEmail: (m) => { w.mails.push(m); }, getRemainingDailyQuota: () => w.quota },
    ContentService: {
      MimeType: { JSON: 'json' },
      createTextOutput: (s) => { const t = { mime: null, getContent: () => s }; t.setMimeType = (m) => { t.mime = m; return t; }; return t; }
    },
    Utilities: { formatDate: (d, tz, p) => { w.zones.push(tz); return 'T(' + p + ')'; } },
    console: { error: () => {}, log: () => {} }
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
  return { w, post };
}

const still = { name: 'Kim', role: 'WALKER', trigger: 'still', createdAt: NOW - 60000, status: 'active', uid: 'u' };
const fallDone = { name: 'Lee', role: 'FORKLIFT', trigger: 'fall', beacon: 'Rack\nA', createdAt: NOW - 200000,
  resolvedAt: NOW - 200000 + 125000, status: 'resolved', uid: 'u' };

// bad_request
let t = make({ db: { k1: still } });
for (const p of [{ sc: 'wf11' }, { id: 'a.b' }, { id: 'a/b' }, { site: 'a.b' }, { event: 'x' }, { to: 'a@b' }, { to: 'a b@x.com' }]) {
  assert.strictEqual(t.post(p), 'bad_request', JSON.stringify(p));
}
// not_allowed (default domain list)
for (const to of ['x@gmail.com', 'x@evilcoupangfs.com', 'x@coupangfs.com.evil.com']) {
  assert.strictEqual(t.post({ to }), 'not_allowed', to);
}
assert.strictEqual(t.w.fetched.length, 0);
assert.strictEqual(t.w.mails.length, 0);

// exact address in ALLOWED_DOMAINS
t = make({ db: { k1: still }, props: { ALLOWED_DOMAINS: ' coupangfs.com , Me@Gmail.com ' } });
assert.strictEqual(t.post({ to: 'you@gmail.com' }), 'not_allowed');
assert.strictEqual(t.post({ to: 'me@gmail.com' }), 'sent');

// not_ready
t = make({ db: { k1: still } });
assert.strictEqual(t.post({ id: 'k9' }), 'not_ready');
assert.strictEqual(t.post({ event: 'resolved' }), 'not_ready');

// sent: sos still
assert.strictEqual(t.post({}), 'sent');
let m = t.w.mails[0];
assert.strictEqual(m.to, 'wfspt@coupangfs.com');
assert.strictEqual(m.name, 'SafeAlert');
assert.ok(m.subject.startsWith('[SafeAlert '));
assert.ok(m.subject.includes('WF11') && m.subject.includes('Kim'));
assert.ok(m.body.includes('3\uBD84 \uB3D9\uC548 \uC6C0\uC9C1\uC784 \uC5C6\uC74C'));
assert.ok(m.body.includes('\uBCF4\uD589\uC790'));
assert.ok(m.body.includes('\uC54C \uC218 \uC5C6\uC74C'));
assert.ok(t.w.zones.length > 0 && t.w.zones.every((z) => z === 'Asia/Seoul'));
const url = t.w.fetched[t.w.fetched.length - 1];
assert.ok(url.startsWith('https://db.example.com/wf11/sos/WF11/k1.json?auth='));
assert.ok(url.endsWith(encodeURIComponent(SECRET)) && !url.endsWith(SECRET));

// same request again: dup, no new mail
assert.strictEqual(t.post({}), 'dup');
assert.strictEqual(t.w.mails.length, 1);

// stillMin outside 1..30: cause without a number
t.w.db.k3 = Object.assign({}, still);
assert.strictEqual(t.post({ id: 'k3', stillMin: '0' }), 'sent');
assert.ok(t.w.mails[1].body.includes(': \uC6C0\uC9C1\uC784 \uC5C6\uC74C \uD6C4 \uC751\uB2F5 \uC5C6\uC74C'));

// fall sos + resolved: one-line beacon, elapsed 2m 5s
t.w.db.k2 = fallDone;
assert.strictEqual(t.post({ id: 'k2' }), 'sent');
m = t.w.mails[2];
assert.ok(m.subject.includes('\uB118\uC5B4\uC9D0 \uAC10\uC9C0'));
assert.ok(m.body.includes('Rack A') && !/[\r\n]/.test(m.subject));
assert.strictEqual(t.post({ id: 'k2', event: 'resolved' }), 'sent');
m = t.w.mails[3];
assert.ok(m.subject.includes('Lee'));
assert.ok(m.body.includes('2\uBD84 5\uCD08'));
assert.strictEqual(t.w.locked, t.w.released);

// stale
t = make({ db: { k1: Object.assign({}, still, { createdAt: NOW - 3 * 3600 * 1000 }) } });
assert.strictEqual(t.post({}), 'stale');

// quota: DAILY_MAX 1, or MailApp quota exhausted
t = make({ db: { k1: still, k2: fallDone }, props: { DAILY_MAX: '1' } });
assert.strictEqual(t.post({}), 'sent');
assert.strictEqual(t.post({ id: 'k2' }), 'quota');
t = make({ db: { k1: still } });
t.w.quota = 0;
assert.strictEqual(t.post({}), 'quota');
assert.strictEqual(t.w.mails.length, 0);

// error: secret missing, DB 401
t = make({ db: { k1: still }, props: { FIREBASE_DB_SECRET: null } });
assert.strictEqual(t.post({}), 'error');
t = make({ db: { k1: still } });
t.w.status = 401;
assert.strictEqual(t.post({}), 'error');

// busy
t = make({ db: { k1: still } });
t.w.lockOk = false;
assert.strictEqual(t.post({}), 'busy');
assert.strictEqual(t.w.mails.length, 0);

console.log('sos-mail check OK (' + scenes + ' scenes)');
