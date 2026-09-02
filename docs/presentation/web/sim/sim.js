  /* SafeAlert 근접 판정 시연
     판정 규칙은 앱 코드 그대로:
       역할쌍 반경    DevSettings.DEFAULT_UWB_* (지게차 포함 15/8m, 그 외 5/3m)
       특수경보       BleService: 상대 STATE=후진·하역 + 위험 임계 이상 → 즉시 최고 등급
       TTC 선발령     BleService: 경고 등급 + 접근 중 + TTC ≤ ttcThresholdSec(기본 3.0s)
                      → 위험거리 닿기 전에 먼저 발령. 뮤트를 뚫고 재알림한다
       협력 격상      v1.1.14: 상대가 송출한 RISK > 내 등급이고, 내 신호도 경고권
                      (coopSlackDb 8dB 완화 포함) 안이면 상대 레벨까지 격상. 격하는 없다
       체류 뮤트      BleService.DWELL_MUTE_MS = 5000ms (소리·진동만, 표시·기록은 유지)
       세이프존       존 진입 시 소리·진동 정지 + 주변 감지 차단 + 상대에게 SAFE 선언
     전파 감쇠와 3단 필터는 시연을 위해 단순화했다. */
  (function () {
    var PX_PER_M = 11, CX = 230, CY = 230, MAX_M = 20;
    var TTC_SEC = 3.0;          // DevSettings.DEFAULT_TTC_THRESHOLD_SEC
    var COOP_SLACK = 1.35;      // coopSlackDb(8dB) 를 거리 여유로 환산한 근사
    var WEAK_TX = 1.45;         // 폰별 TX/RX 비대칭 — 내 쪽 측정이 그만큼 멀게 잡힌다
    var MIN_VEL = 0.25;         // TTC 산출 최소 접근속도 (m/s)

    var ROLE = {
      FORKLIFT: { name: '지게차', id: 'FORK-07', icon: 'IC_FORKLIFT', speed: 3.0 },
      WALKER:   { name: '보행자', id: 'WALK-03', icon: 'IC_WALKER',   speed: 1.4 }
    };
    var ICONS = { IC_FORKLIFT: '__ASSET_IC_FORKLIFT__', IC_WALKER: '__ASSET_IC_WALKER__' };
    var STATE_LABEL = { IDLE: '정지', FORWARD: '전진 주행', REVERSE: '후진', LOADING: '하역 작업' };
    var LV = [{ k: '안전', c: 'var(--safe)' }, { k: '경고', c: 'var(--warn)' }, { k: '위험', c: 'var(--danger)' }];

    var st = { running: true, mine: 'FORKLIFT', peer: 'WALKER', pstate: 'IDLE',
               inZone: false, sound: false, weak: false, auto: false,
               d: 14, ang: -0.45, vel: 0, level: 0, since: Date.now() };
    var lastD = st.d, lastT = performance.now();

    var $ = function (id) { return document.getElementById(id); };
    var plan = $('plan'), peerG = $('peer'), slider = $('slider');

    function radii() {
      var fork = st.mine === 'FORKLIFT' || st.peer === 'FORKLIFT';
      return fork ? { w: 15, d: 8 } : { w: 5, d: 3 };
    }
    function rssiFor(m) { return Math.round(-38 - 22 * Math.log10(Math.max(m, 0.5))); }
    function lvOf(m, r) { return m <= r.d ? 2 : m <= r.w ? 1 : 0; }

    // 거리 변화에서 접근 속도를 뽑는다. 드래그·슬라이더·자동 접근 모두 같은 경로를 탄다.
    function trackVel(now) {
      var dt = (now - lastT) / 1000;
      if (dt >= 0.06) {
        var v = (lastD - st.d) / dt;                       // + 면 접근
        st.vel = st.vel * 0.5 + v * 0.5;                   // 가벼운 평활
        lastD = st.d; lastT = now;
      }
    }

    function judge() {
      var r = radii();
      var myD = st.d * (st.weak ? WEAK_TX : 1);            // 내 쪽 측정 거리
      var myLv = lvOf(myD, r), peerLv = lvOf(st.d, r);     // 상대는 정상 측정
      var why = null;

      if (!st.running || st.inZone) return { lv: 0, peerLv: st.inZone ? 0 : peerLv, why: null, myD: myD, r: r, ttc: null };

      // 특수경보 — 접근 속도·방향 조건을 따지지 않고 즉시 최고 등급
      if ((st.pstate === 'REVERSE' || st.pstate === 'LOADING') && myD <= r.d) { myLv = 2; why = 'special'; }

      // TTC 선발령 — 경고 등급 + 접근 중 + 충돌 예상 3초 이내면 위험거리 전에 먼저 발령
      var ttc = null;
      if (st.vel > MIN_VEL && myD > r.d) ttc = (myD - r.d) / st.vel;
      if (!why && myLv === 1 && ttc !== null && ttc <= TTC_SEC) { myLv = 2; why = 'ttc'; }

      // 협력 격상 — 상대가 먼저 감지해 송출한 레벨까지 끌어올린다(격상 전용)
      if (peerLv > myLv && myD <= r.w * COOP_SLACK) { myLv = peerLv; why = why || 'coop'; }

      return { lv: myLv, peerLv: peerLv, why: why, myD: myD, r: r, ttc: ttc };
    }

    var actx = null, beepAt = 0;
    function beep(level) {
      if (!st.sound || level === 0) return;
      var now = Date.now(), gap = level === 2 ? 320 : 900;
      if (now - beepAt < gap) return;
      beepAt = now;
      try {
        actx = actx || new (window.AudioContext || window.webkitAudioContext)();
        var o = actx.createOscillator(), g = actx.createGain();
        o.type = 'square';
        o.frequency.value = level === 2 ? 1180 : 760;
        g.gain.setValueAtTime(0.0001, actx.currentTime);
        g.gain.exponentialRampToValueAtTime(0.08, actx.currentTime + 0.01);
        g.gain.exponentialRampToValueAtTime(0.0001, actx.currentTime + 0.16);
        o.connect(g); g.connect(actx.destination);
        o.start(); o.stop(actx.currentTime + 0.18);
      } catch (e) { /* 오디오 불가 환경은 조용히 넘어간다 */ }
    }

    var WHY = {
      special: ['후진 · 하역 특수경보', 'var(--danger)'],
      ttc:     ['TTC 선발령 · 위험거리 전에 발령', 'var(--danger)'],
      coop:    ['협력 격상 · 상대가 먼저 감지', 'var(--accent)']
    };

    function render() {
      trackVel(performance.now());
      var j = judge(), r = j.r, lv = j.lv;

      if (lv !== st.level) { st.level = lv; st.since = Date.now(); }
      // 5초 체류 자동 뮤트. 특수경보와 TTC 선발령은 뮤트를 뚫는다.
      var dwell = lv > 0 && !j.why && (Date.now() - st.since > 5000);

      // 도면
      $('ringWarn').setAttribute('r', r.w * PX_PER_M);
      $('ringDanger').setAttribute('r', r.d * PX_PER_M);
      $('tickWarn').textContent = r.w + 'm';
      $('tickDanger').textContent = r.d + 'm';
      $('tickWarn').setAttribute('x', CX + r.w * PX_PER_M - 14);
      $('tickDanger').setAttribute('x', CX + r.d * PX_PER_M - 12);
      peerG.setAttribute('transform', 'translate(' +
        (CX + Math.cos(st.ang) * st.d * PX_PER_M).toFixed(1) + ',' +
        (CY + Math.sin(st.ang) * st.d * PX_PER_M).toFixed(1) + ')');
      peerG.setAttribute('aria-valuenow', st.d.toFixed(1));
      peerG.setAttribute('aria-valuetext', st.d.toFixed(1) + ' 미터, ' + LV[lv].k);
      $('peerIcon').setAttribute('href', ICONS[ROLE[st.peer].icon]);
      $('peerZone').textContent = st.inZone ? '내가 안전 선언 중' : '';

      // 판독
      $('outDist').textContent = st.d.toFixed(1) + ' m';
      $('outVel').textContent = (st.vel > 0.05 ? st.vel.toFixed(1) : '0.0') + ' m/s';
      $('outTtc').textContent = j.ttc !== null ? j.ttc.toFixed(1) + ' s' : '-';
      $('outTtc').style.color = (j.ttc !== null && j.ttc <= TTC_SEC) ? 'var(--danger)' : 'var(--ink)';
      $('outRssi').textContent = rssiFor(j.myD) + ' dBm';
      $('outLevel').textContent = LV[lv].k;
      $('outLevel').style.color = LV[lv].c;
      $('outPeerLevel').textContent = LV[j.peerLv].k;
      $('outPeerLevel').style.color = LV[j.peerLv].c;

      var tag = $('whyTag');
      var label = !st.running ? ['서비스 중지됨', 'var(--ink-3)']
        : st.inZone ? ['세이프존 · 3중 억제', 'var(--safe)']
        : j.why ? WHY[j.why]
        : dwell ? ['5초 체류 · 소리 자동 뮤트', 'var(--warn)'] : null;
      tag.classList.toggle('show', !!label);
      if (label) {
        tag.textContent = label[0];
        tag.style.color = label[1];
        tag.style.background = 'color-mix(in srgb, ' + label[1] + ' 14%, transparent)';
        tag.style.border = '1px solid ' + label[1];
      }

      // 폰: 내 장비
      var mine = ROLE[st.mine];
      $('myIcon').src = ICONS[mine.icon];
      $('myRoleName').textContent = mine.name;
      $('myId').textContent = mine.id;
      $('myRadius').textContent = '경고 ' + r.w + 'm · 위험 ' + r.d + 'm' + (st.weak ? ' · 신호 약함' : '');
      $('phone').classList.toggle('off', !st.running);
      $('runDot').style.background = st.running ? 'var(--safe)' : 'var(--ink-4)';
      $('runText').textContent = !st.running ? '중지됨' : st.inZone ? '세이프존 · 경보 억제 중' : '백그라운드 실행 중';
      $('btnStop').textContent = st.running ? '중지' : '시작';
      $('btnStop').style.background = st.running ? 'var(--danger)' : 'var(--safe)';

      // 폰: 수신 타겟
      var body = $('targetBody');
      if (!st.running) {
        body.innerHTML = '<div class="ui-meta" style="margin-top:10px">감지 중지</div>';
      } else if (st.inZone) {
        body.innerHTML = '<div class="ui-meta" style="margin-top:10px">존 안에서는 존 비콘 신호만 받습니다</div>';
      } else {
        var c = LV[lv].c, extra = '';
        if (j.why === 'special') extra = STATE_LABEL[st.pstate] + ' · 특수경보';
        else if (j.why === 'ttc') extra = '접근 ' + st.vel.toFixed(1) + ' m/s · TTC ' + j.ttc.toFixed(1) + 's 선발령';
        else if (j.why === 'coop') extra = '상대 송출 수신 · 협력 격상';
        else if (dwell) extra = '5초 체류 · 소리만 자동 뮤트';
        else extra = STATE_LABEL[st.pstate];
        body.innerHTML =
          '<div class="ui-row" style="margin-top:10px">' +
          '<span style="width:7px;height:7px;border-radius:2px;background:' + c + ';display:inline-block"></span>' +
          '<div><div class="ui-name">' + ROLE[st.peer].name + '</div>' +
          '<div class="ui-meta">' + rssiFor(j.myD) + ' dBm, ' + j.myD.toFixed(1) + ' m</div></div>' +
          '<span class="pill" style="background:' + c + ';color:var(--on-accent)">' + LV[lv].k + '</span></div>' +
          '<div class="ui-meta" style="margin-top:8px;color:' +
            (j.why ? c : dwell ? 'var(--warn)' : 'var(--ink-3)') + '">' + extra + '</div>';
      }

      // 출력 3종
      var audible = lv > 0 && !dwell && st.running && !st.inZone;
      [['indSound', audible], ['indVib', audible], ['indOv', lv > 0 && st.running && !st.inZone]]
        .forEach(function (pair) {
          var el = $(pair[0]);
          el.classList.toggle('on', pair[1]);
          el.style.background = pair[1] ? LV[lv].c : 'var(--surface-2)';
        });
      $('overlay').classList.toggle('show', lv > 0 && st.running && !st.inZone);
      $('ovDot').style.background = LV[lv].c;
      $('ovName').textContent = ROLE[st.peer].name;
      $('ovMeta').textContent = j.myD.toFixed(1) + ' m · ' + LV[lv].k;
      $('zoneBadge').classList.toggle('show', st.inZone && st.running);

      // 상대 단말
      var pl = j.peerLv;
      $('peerPanelIcon').src = ICONS[ROLE[st.peer].icon];
      $('peerPanelName').textContent = '상대 단말 · ' + ROLE[st.peer].name;
      $('peerPanelLvl').textContent = LV[pl].k;
      $('peerPanelLvl').style.background = LV[pl].c;
      $('peerPanelLvl').style.color = 'var(--on-accent)';
      $('peerPanelTx').textContent = ['00 안전', '01 경고 감지', '10 위험 감지'][pl];
      $('peerPanelMsg').textContent = st.inZone
        ? '내가 세이프존 안이라 상대는 나를 안전으로 인식합니다.'
        : j.why === 'coop'
          ? '상대가 먼저 감지했습니다. 내 신호도 경고권 안이라 상대 레벨까지 함께 올립니다.'
          : (st.weak && pl > lv)
            ? '상대는 이미 감지했지만 내 신호가 아직 경고권 밖입니다. 더 가까워지면 협력 격상이 걸립니다.'
            : '양쪽이 각자 판정하고, 서로의 위험도를 광고에 실어 보냅니다.';

      if (audible) { beep(lv); if (navigator.vibrate) navigator.vibrate(lv === 2 ? 60 : 30); }
      if (document.activeElement !== slider) slider.value = st.d.toFixed(1);
    }

    // ── 자동 접근: 상대가 자기 속도로 다가온다 ─────────────────────────
    var raf = null, prevT = 0;
    function step(t) {
      if (!st.auto) return;
      if (!prevT) prevT = t;
      var dt = Math.min((t - prevT) / 1000, 0.1); prevT = t;
      st.d = Math.max(0.6, st.d - ROLE[st.peer].speed * dt);
      render();
      if (st.d <= 0.6) { stopAuto(); return; }
      raf = requestAnimationFrame(step);
    }
    function startAuto() {
      st.auto = true; prevT = 0;
      $('btnRun').setAttribute('aria-pressed', 'true');
      $('btnRun').textContent = '접근 정지';
      raf = requestAnimationFrame(step);
    }
    function stopAuto() {
      st.auto = false; st.vel = 0;
      if (raf) cancelAnimationFrame(raf);
      $('btnRun').setAttribute('aria-pressed', 'false');
      $('btnRun').textContent = '접근 시작';
      render();
    }

    // ── 입력 ────────────────────────────────────────────────────────
    function pointTo(evt) {
      var pt = plan.createSVGPoint();
      pt.x = evt.clientX; pt.y = evt.clientY;
      var loc = pt.matrixTransform(plan.getScreenCTM().inverse());
      var dx = loc.x - CX, dy = loc.y - CY;
      st.ang = Math.atan2(dy, dx);
      st.d = Math.min(MAX_M, Math.max(0.5, Math.hypot(dx, dy) / PX_PER_M));
      render();
    }
    var dragging = false;
    plan.addEventListener('pointerdown', function (e) { stopAuto(); dragging = true; plan.setPointerCapture(e.pointerId); pointTo(e); });
    plan.addEventListener('pointermove', function (e) { if (dragging) pointTo(e); });
    plan.addEventListener('pointerup', function () { dragging = false; st.vel = 0; render(); });
    plan.addEventListener('pointercancel', function () { dragging = false; });
    peerG.addEventListener('keydown', function (e) {
      var stp = e.shiftKey ? 2 : 0.5, used = true;
      if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') st.d = Math.max(0.5, st.d - stp);
      else if (e.key === 'ArrowRight' || e.key === 'ArrowUp') st.d = Math.min(MAX_M, st.d + stp);
      else used = false;
      if (used) { e.preventDefault(); stopAuto(); render(); }
    });
    slider.addEventListener('input', function () {
      var v = parseFloat(slider.value);   // stopAuto() 가 render() 로 슬라이더를 되돌리므로 먼저 읽는다
      stopAuto(); st.d = v;
      st.vel = 0; lastD = st.d; lastT = performance.now();   // 슬라이더는 순간 이동이라 속도로 치지 않는다
      render();
    });

    function bindGroup(id, key) {
      var g = $(id);
      g.addEventListener('click', function (e) {
        var b = e.target.closest('.chipbtn'); if (!b) return;
        st[key] = b.dataset.v;
        Array.prototype.forEach.call(g.querySelectorAll('.chipbtn'), function (x) {
          x.setAttribute('aria-pressed', String(x === b));
        });
        st.since = Date.now();
        render();
      });
    }
    bindGroup('ctlMine', 'mine'); bindGroup('ctlPeer', 'peer'); bindGroup('ctlState', 'pstate');

    function toggle(id, key, onText, offText, after) {
      $(id).addEventListener('click', function () {
        st[key] = !st[key];
        this.setAttribute('aria-pressed', String(st[key]));
        this.textContent = st[key] ? onText : offText;
        if (after) after();
        render();
      });
    }
    toggle('btnZone', 'inZone', '세이프존 이탈', '세이프존 진입');
    toggle('btnWeak', 'weak', '신호 약함 해제', '내 단말 신호 약함');
    $('btnSound').addEventListener('click', function () {
      st.sound = !st.sound;
      this.setAttribute('aria-pressed', String(st.sound));
      this.textContent = st.sound ? '경보음 끄기' : '경보음 켜기';
      if (st.sound) beep(2);
    });
    $('btnRun').addEventListener('click', function () {
      if (st.auto) { stopAuto(); return; }
      if (st.d < 6) { st.d = radii().w * 1.25; }        // 너무 가까우면 뒤로 물려 시작
      startAuto();
    });
    $('btnRole').addEventListener('click', function () {
      var order = ['FORKLIFT', 'WALKER'];              // EPJ 는 앱 UI 비노출 (판정 로직은 유지)
      st.mine = order[(order.indexOf(st.mine) + 1) % order.length];
      Array.prototype.forEach.call($('ctlMine').querySelectorAll('.chipbtn'), function (x) {
        x.setAttribute('aria-pressed', String(x.dataset.v === st.mine));
      });
      render();
    });
    $('btnStop').addEventListener('click', function () { st.running = !st.running; st.since = Date.now(); stopAuto(); render(); });

    // 체류 뮤트는 시간 경과로 걸린다. 정지 상태에서도 주기적으로 다시 그린다.
    setInterval(function () { if (!st.auto && st.running && !st.inZone && st.level > 0) render(); }, 500);
    render();
  })();
