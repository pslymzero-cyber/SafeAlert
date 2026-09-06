#!/usr/bin/env python3
"""Firebase 경보 이력을 집계한다.

두 곳에서 쓴다.
  · 로컬  — Firebase CLI 로 받은 JSON 을 넘긴다
  · CI    — `.github/workflows/alert-digest.yml` 이 사업장별로 받아 넘기고,
            `--md` 로 마크다운 요약을 만들어 저장소에 커밋한다 (폰에서 GitHub 앱으로 본다)

    python3 analyze_alerts.py alerts.json [--days 28] [--out summary.json]
    python3 analyze_alerts.py alerts.json --label WF11 --md DIGEST.md [--append] [--no-ids]

받는 모양 (FirebaseManager.kt 의 saveAlert 이 쓰는 그대로):
    { "20260901": { "<uuid>": {timestamp, deviceId, walkerId, rssi, alertLevel}, ... }, ... }
    루트가 사업장 노드 전체여도 되고(alerts 를 알아서 찾는다), alerts 노드만이어도 된다.

건수의 의미 — 같은 기기에 대해 1분 1회로 스로틀돼 있다 (BleService.kt).
따라서 1건 = 경보 1회가 아니라 '해당 분(分)에 그 기기와 가까워졌다' 다.
중복이 걷힌 값이라 위험했던 순간의 대용 지표로 쓸 수 있다. 사고 건수가 아니다.
"""
import argparse, json, os, sys
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone

# 시각은 현장 시간(KST)으로 읽는다. datetime.fromtimestamp() 는 실행 환경의
# 로컬 시간을 쓰는데, GitHub Actions 러너는 UTC 라 시간대별 그래프가 9시간
# 어긋난다. tzdata 가 없는 환경을 대비해 고정 +9 로 물러선다.
try:
    from zoneinfo import ZoneInfo
    KST = ZoneInfo("Asia/Seoul")
except Exception:                                  # pragma: no cover
    KST = timezone(timedelta(hours=9))

CAVEAT = ("1건 = 경보 1회가 아니라 가까워진 1분이다 (같은 상대는 1분에 한 번만 기록된다). "
          "사고 건수가 아니라 위험했던 순간의 대용 지표다.")


# 같은 단말이 두 형태로 기록된다 — saveAlert 의 deviceId 는 스캐너가 만든 fullId
#   (BleConstants.DEVICE_PREFIX/WALKER_PREFIX + 상대 ID) 이고, walkerId 는 접두사 없는
#   자기 myId 다. 접두사를 떼지 않으면 한 대가 두 대로 세어지고, 같은 조우를 양쪽이
#   기록해도 짝이 맞지 않는다.
ID_PREFIXES = ("SAFEALERT_DEVICE_", "SAFEALERT_WALKER_")


def _norm(v):
    s = str(v if v is not None else "?")
    for p in ID_PREFIXES:
        if s.startswith(p):
            return s[len(p):]
    return s


def load(path):
    doc = json.load(open(path, encoding="utf-8"))
    if isinstance(doc, dict) and isinstance(doc.get("alerts"), dict):
        return doc["alerts"]
    return doc or {}


def aggregate(alerts, days=0, since=""):
    dates = sorted(k for k in alerts if k.isdigit() and len(k) == 8)
    if since:
        dates = [d for d in dates if d >= since]
    if days:
        dates = dates[-days:]
    per_day, per_hour, per_dow = defaultdict(Counter), defaultdict(Counter), defaultdict(Counter)
    pairs, devices, rssi = Counter(), set(), defaultdict(list)
    total, roles = Counter(), Counter()
    events = []                                    # 파생 지표용 원시 이벤트
    for d in dates:
        for rec in (alerts.get(d) or {}).values():
            if not isinstance(rec, dict):
                continue
            lv = rec.get("alertLevel", "?")
            total[lv] += 1
            per_day[d][lv] += 1
            a, b = _norm(rec.get("deviceId")), _norm(rec.get("walkerId"))
            devices.update((a, b))
            pairs[tuple(sorted((a, b)))] += 1
            if isinstance(rec.get("rssi"), int):
                rssi[lv].append(rec["rssi"])
            # v1.1.72 부터 기록된다. 이전 레코드에는 없으므로 그때는 집계에서 빠진다.
            mine, peer = rec.get("myRole"), rec.get("peerRole")
            if mine and peer and "UNKNOWN" not in (mine, peer):
                roles[tuple(sorted((str(mine), str(peer))))] += 1
            ts = rec.get("timestamp")
            if isinstance(ts, (int, float)):
                t = datetime.fromtimestamp(ts / 1000, KST)
                per_hour[t.hour][lv] += 1
                per_dow["월화수목금토일"[t.weekday()]][lv] += 1
                events.append((ts / 1000.0, b, a, lv))   # (초, 기록자, 상대, 등급) - 둘 다 정규화됨
    n = len(dates) or 1
    out = {
        "dates": dates, "n_days": len(dates),
        "danger": total.get("DANGER", 0), "warning": total.get("WARNING", 0),
        "danger_avg": round(total.get("DANGER", 0) / n, 1),
        "warning_avg": round(total.get("WARNING", 0) / n, 1),
        "devices": len(devices), "pairs": len(pairs),
        # 실동률 분자 — 상대로만 잡힌 기기는 빼고, 스스로 기록을 남긴 기기만 센다.
        "recorders": sorted({b for _, b, _, _ in events}),
        "top_pairs": pairs.most_common(5),
        "per_day": {d: dict(c) for d, c in sorted(per_day.items())},
        "per_hour": {h: dict(c) for h, c in sorted(per_hour.items())},
        "per_dow": {k: dict(v) for k, v in per_dow.items()},
        "rssi_median": {lv: sorted(v)[len(v) // 2] for lv, v in rssi.items() if v},
        "role_pairs": roles.most_common(),
    }
    out.update(derive(events, rssi, out["per_day"]))
    return out


def load_node(path):
    """alerts 가 아닌 노드(echo_calib · uwb_probe)를 그대로 읽는다. 없으면 빈 dict."""
    if not path or not os.path.exists(path):
        return {}
    try:
        return json.load(open(path, encoding="utf-8")) or {}
    except (OSError, ValueError):
        return {}


# ── 단말 가동 ────────────────────────────────────────────────
#   '경보가 없었다' 와 '앱이 꺼져 있었다' 를 가른다.
#   echo_calib/<기기ID> = {model, ts, peers} 는 앱이 도는 동안 1시간마다 덮어써진다
#   (CalibrationEngine.maybeUploadEchoCalib · ECHO_FB_UPLOAD_INTERVAL_MS = 3_600_000).
#   그 ts 가 곧 기기별 마지막 생존 신호라 하트비트를 따로 만들 필요가 없다.
#   한계 둘 — 노드는 덮어쓰기라 과거 이력이 없어 '지금 몇 대가 살아 있나' 만 나오고,
#   에코 상대가 하나도 없으면 업로드를 건너뛰므로(peers.isEmpty) 하루 종일 혼자였던
#   기기는 안 잡힌다. 즉 이 값은 실동 대수의 하한이다.
FRESH_H = 24         # 이 시간 안에 스탬프가 찍혔으면 '가동 중'
STALE_D = 7          # 이 기간을 넘으면 '멈춘 것으로 본다'


def uptime(echo, recorders, now_s=None):
    if not isinstance(echo, dict) or not echo:
        return {}
    now = now_s if now_s is not None else datetime.now(KST).timestamp()
    rec = set(recorders or ())
    fresh, week, stale, models = [], [], [], Counter()
    for key, node in echo.items():
        if not isinstance(node, dict):
            continue
        dev = _norm(key)
        models[str(node.get("model") or "?")] += 1
        ts = node.get("ts")
        age_h = (now - ts / 1000.0) / 3600.0 if isinstance(ts, (int, float)) else None
        if age_h is None or age_h > STALE_D * 24:
            stale.append(dev)
        elif age_h <= FRESH_H:
            fresh.append(dev)
        else:
            week.append(dev)
    reg = {_norm(k) for k in echo}
    silent = sorted(reg - rec)                 # 등록돼 있는데 기간 중 기록이 하나도 없는 기기
    return {
        "registered": len(reg),
        "fresh": len(fresh), "week": len(week), "stale": len(stale),
        "recorded": len(reg & rec),
        "silent": len(silent),
        "unregistered": len(rec - reg),        # 기록은 남겼는데 echo 노드가 없는 기기
        "rate": round(len(reg & rec) / len(reg) * 100, 1) if reg else None,
        "models": models.most_common(6),
    }


# ── UWB 실거리 대비 RSSI ──────────────────────────────────────
#   경보 임계는 dBm 인데 현장이 묻는 것은 미터다. uwb_probe 는 UWB 가 잰 실거리와
#   같은 프레임의 RSSI 를 짝지어 둔 원표본이라(AlertStateMachine.uploadUwbProbe),
#   여기서 "경고 -75dBm · 위험 -55dBm 이 실제 몇 m 인가" 를 역산한다.
#   개발자 설정의 'UWB 실측 표본 업로드' 가 켜진 세션에서만 쌓인다 — 기본 OFF.
BIN_M = 0.5          # 거리 구간 폭
MIN_BIN_N = 3        # 이 미만인 구간은 중앙값을 믿지 않는다
THRESHOLDS = (("경고", -75), ("위험", -55))


def _cross(bins, thr):
    """중앙값 RSSI 가 임계를 지나는 거리를 이웃 구간 사이 선형보간으로 찾는다.
    잡음으로 한 구간만 튀어 내려간 곳을 임계 통과로 읽지 않도록, 다음 구간도
    임계 아래에 있을 때만 인정한다(마지막 구간은 확인할 다음이 없어 그대로 본다)."""
    pts = [(b["mid"], b["p50"]) for b in bins if b["n"] >= MIN_BIN_N]
    for i, ((d0, r0), (d1, r1)) in enumerate(zip(pts, pts[1:])):
        if (r0 - thr) * (r1 - thr) > 0 or r0 == r1:
            continue
        if i + 2 < len(pts) and pts[i + 2][1] > thr:
            continue                                   # 곧바로 되돌아옴 = 잡음
        return round(d0 + (d1 - d0) * (r0 - thr) / (r0 - r1), 1)
    return None


def probe_bins(probe, since=""):
    recs = []
    for day, items in (probe or {}).items():
        if not (isinstance(day, str) and day.isdigit() and len(day) == 8):
            continue
        if since and day < since:
            continue
        for r in (items or {}).values():
            if not isinstance(r, dict):
                continue
            d, q = r.get("distM"), r.get("rssi")
            if isinstance(d, (int, float)) and isinstance(q, int) and d > 0:
                recs.append((float(d), q, str(r.get("pairKey") or "?")))
    if not recs:
        return {}
    by_bin = defaultdict(list)
    for d, q, _ in recs:
        by_bin[int(d / BIN_M)].append(q)
    bins = []
    for k in sorted(by_bin):
        v = sorted(by_bin[k])
        bins.append({"lo": round(k * BIN_M, 1), "hi": round((k + 1) * BIN_M, 1),
                     "mid": round((k + 0.5) * BIN_M, 1), "n": len(v),
                     "p10": _pct(v, 10), "p50": _pct(v, 50), "p90": _pct(v, 90)})
    return {
        "n": len(recs),
        "days": len({d for d in (probe or {}) if isinstance(d, str) and d.isdigit()}),
        "bins": bins,
        "pairs": Counter(k for _, _, k in recs).most_common(6),
        "cross": {nm: _cross(bins, thr) for nm, thr in THRESHOLDS},
    }


# ── 파생 지표 ────────────────────────────────────────────────
#   집계값(건수·평균)만으로는 답할 수 없는 세 가지를 원시 이벤트에서 뽑는다.
#     · 경고 선행률   = 위험이 뜨기 전에 경고가 먼저 떴는가 (회피 시간 확보의 직접 증거)
#     · 양측 검출률   = 같은 조우를 양쪽 단말이 모두 기록했는가 (편측 미검출 탐지)
#     · RSSI 분포     = 어느 신호 세기에서 경보가 났는가 (성능 사양의 실측 근거)
#   쓰로틀(같은 상대 1분 1회, 등급 공용)이 있으므로 선행률은 하한값이다.
PRECEDE_S = 180      # 위험 앞 이 시간 안의 경고를 '선행'으로 본다
PAIR_S = 90          # 같은 조우로 묶는 시간 창
GAP_S = 150          # 조우가 끊겼다고 보는 간격


def _pct(v, p):
    s = sorted(v)
    return s[max(0, min(len(s) - 1, round(p / 100 * (len(s) - 1))))] if s else None


def derive(events, rssi, per_day):
    events.sort()
    directed, undirected = defaultdict(list), defaultdict(list)
    for ts, me, other, lv in events:
        directed[(me, other)].append((ts, lv))
        undirected[tuple(sorted((me, other)))].append((ts, me))

    # 경고 선행률
    led = tot_d = 0
    gaps = []
    for seq in directed.values():
        for i, (ts, lv) in enumerate(seq):
            if not lv.startswith("D"):
                continue
            tot_d += 1
            prev = [t for t, l in seq[:i] if l.startswith("W") and 0 < ts - t <= PRECEDE_S]
            if prev:
                led += 1
                gaps.append(ts - prev[-1])

    # 양측 동시 검출률 + 조우 지속시간
    both = one = 0
    dur = []
    for seq in undirected.values():
        used = [False] * len(seq)
        for i, (ts, me) in enumerate(seq):
            if used[i]:
                continue
            grp, used[i] = {me}, True
            for j in range(i + 1, len(seq)):
                if used[j] or seq[j][0] - ts > PAIR_S:
                    break
                grp.add(seq[j][1])
                used[j] = True
            both, one = (both + 1, one) if len(grp) >= 2 else (both, one + 1)
        st = pv = None
        for ts, _ in seq:
            if st is None:
                st = pv = ts
            elif ts - pv <= GAP_S:
                pv = ts
            else:
                dur.append(pv - st)
                st = pv = ts
        if st is not None:
            dur.append(pv - st)

    # 일별 위험/경고 비 — 합산 한 값이 가리는 편차를 드러낸다
    ratios = [c.get("DANGER", 0) / c["WARNING"] for c in per_day.values() if c.get("WARNING")]
    enc = both + one
    return {
        "precede_rate": round(led / tot_d * 100, 1) if tot_d else None,
        "precede_median_s": round(_pct(gaps, 50)) if gaps else None,
        "both_rate": round(both / enc * 100, 1) if enc else None,
        "encounters": enc,
        "dwell_median_s": round(_pct(dur, 50)) if dur else None,
        "dwell_p90_s": round(_pct(dur, 90)) if dur else None,
        "rssi_dist": {lv: {"n": len(v), "p10": _pct(v, 10), "p50": _pct(v, 50), "p90": _pct(v, 90)}
                      for lv, v in rssi.items() if v},
        "ratio_min": round(min(ratios), 2) if ratios else None,
        "ratio_med": round(_pct(ratios, 50), 2) if ratios else None,
        "ratio_max": round(max(ratios), 2) if ratios else None,
        "ratio_over1": sum(1 for r in ratios if r > 1) if ratios else 0,
        "ratio_days": len(ratios),
    }


def _bar(v, top, width=18):
    return "█" * max(1, round(v / top * width)) if v else ""


def markdown(a, label, up=None, pb=None):
    L = [f"## {label}", ""]
    if not a["n_days"]:
        L += ["> 집계할 데이터가 없다. 기기 개발자 설정의 `firebaseRoot` 와 "
              "`autoSaveAlerts` 를 확인할 것.", ""]
        return "\n".join(L)
    L += [
        "| 항목 | 값 |",
        "|------|----|",
        f"| 기간 | {a['dates'][0]} ~ {a['dates'][-1]} ({a['n_days']}일) |",
        f"| 위험 경보 | **{a['danger']:,}건**  (일평균 {a['danger_avg']}) |",
        f"| 경고 경보 | **{a['warning']:,}건**  (일평균 {a['warning_avg']}) |",
        f"| 관측 단말 | {a['devices']}대 |",
        f"| 서로 가까워진 기기쌍 | {a['pairs']}쌍 |",
        "",
    ]
    if up:
        L += ["### 단말 가동", "",
              "`echo_calib` 노드의 마지막 스탬프다. 앱이 도는 동안 1시간마다 덮어써지므로 "
              "'경보가 없었다' 와 '앱이 꺼져 있었다' 가 여기서 갈린다.", "",
              "| 항목 | 값 |", "|------|----|",
              f"| 등록 단말 | {up['registered']}대 |",
              f"| 최근 {FRESH_H}시간 내 생존 | {up['fresh']}대 |",
              f"| {FRESH_H}시간~{STALE_D}일 | {up['week']}대 |",
              f"| {STALE_D}일 초과 · 스탬프 없음 | {up['stale']}대 |",
              f"| 기간 중 경보를 남긴 단말 | {up['recorded']}대 |"]
        if up.get("rate") is not None:
            L.append(f"| 실동률 (기록 단말 / 등록 단말) | **{up['rate']}%** |")
        if up.get("unregistered"):
            L.append(f"| 기록은 있으나 등록 노드 없음 | {up['unregistered']}대 |")
        L += ["", "> 하한값이다. 에코 상대를 하나도 못 만난 기기는 업로드를 건너뛰고, "
                  "노드는 덮어쓰기라 과거 이력이 남지 않는다. 등록 대수도 MDM 배포 대수가 "
                  "아니라 '한 번이라도 올라온 대수' 다.", ""]
    if a["per_hour"]:
        tot = {h: sum(c.values()) for h, c in a["per_hour"].items()}
        top = max(tot.values())
        L += ["### 시간대별 (KST)", "", "```"]
        for h in sorted(tot):
            L.append(f"{h:02d}시  {_bar(tot[h], top):<18} {tot[h]:>5,}")
        L += ["```", ""]
    if a["per_dow"]:
        tot = {k: sum(v.values()) for k, v in a["per_dow"].items()}
        top = max(tot.values())
        L += ["### 요일별 (KST)", "", "```"]
        for k in "월화수목금토일":
            if k in tot:
                L.append(f"{k}   {_bar(tot[k], top):<18} {tot[k]:>5,}")
        L += ["```", ""]
    L += ["### 일자별", "", "| 날짜 | 경고 | 위험 | 비 |", "|------|-----:|-----:|----:|"]
    for d, c in list(a["per_day"].items())[-14:]:
        w, g = c.get("WARNING", 0), c.get("DANGER", 0)
        L.append(f"| {d} | {w:,} | {g:,} | {g/w:.2f} |" if w else f"| {d} | {w:,} | {g:,} | - |")
    L += [""]

    # ── 파생 지표 ──────────────────────────────────────────
    L += ["### 파생 지표", "",
          "집계 건수만으로는 답할 수 없는 값들이다. 원시 레코드에서 계산한다.", "",
          "| 지표 | 값 | 뜻 |", "|------|----|----|"]
    if a.get("precede_rate") is not None:
        g = f" · 중앙값 {a['precede_median_s']}초 전" if a.get("precede_median_s") else ""
        L.append(f"| 경고 선행률 | **{a['precede_rate']}%**{g} | "
                 f"위험 전 {PRECEDE_S}초 안에 같은 상대 경고가 먼저 뜬 비율 = 회피 시간 확보의 직접 증거. "
                 "쓰로틀 때문에 하한값이다 |")
    if a.get("both_rate") is not None:
        L.append(f"| 양측 동시 검출률 | **{a['both_rate']}%** (조우 {a['encounters']:,}건) | "
                 "같은 조우를 양쪽 단말이 모두 기록한 비율. 나머지는 한쪽이 놓친 것 = 편측 미검출 |")
    if a.get("dwell_median_s") is not None:
        L.append(f"| 조우 지속시간 | 중앙값 {a['dwell_median_s']}초 · P90 {a['dwell_p90_s']}초 | "
                 "길수록 위험 카운트가 분 단위로 쌓인다. 비가 1을 넘는 이유 |")
    if a.get("ratio_med") is not None:
        L.append(f"| 일별 위험/경고 비 | {a['ratio_min']} ~ {a['ratio_max']} "
                 f"(중앙값 {a['ratio_med']} · {a['ratio_days']}일 중 {a['ratio_over1']}일이 1 초과) | "
                 "합산 한 값이 가리는 편차. 기준선을 한 숫자로 대표시키면 안 되는 근거 |")
    L += [""]
    if a.get("rssi_dist"):
        L += ["### 경보 발생 시 RSSI (성능 사양의 실측 근거)", "",
              "| 등급 | 건수 | P10 | 중앙값 | P90 |", "|------|-----:|----:|------:|----:|"]
        for lv in ("WARNING", "DANGER"):
            s = a["rssi_dist"].get(lv)
            if s:
                nm = {"WARNING": "경고", "DANGER": "위험"}[lv]
                L.append(f"| {nm} | {s['n']:,} | {s['p10']} | **{s['p50']}** | {s['p90']} | ")
        L += ["", "> 설정 임계는 경고 -75dBm · 위험 -55dBm 이고 역할쌍 보정 +0~8dB 가 붙는다. "
                  "위 실측 분포가 그 임계와 얼마나 맞는지가 판정 정확도의 1차 지표다.", ""]

    if pb:
        L += ["### UWB 실거리 대비 RSSI (임계의 미터 환산)", "",
              f"UWB 가 잰 실거리와 같은 프레임의 RSSI 표본 **{pb['n']:,}건** "
              f"({pb['days']}일). 임계가 실제로 몇 m 인지를 재는 유일한 근거다.", "",
              "| 거리 | 표본 | P10 | 중앙값 | P90 |", "|------|-----:|----:|------:|----:|"]
        for b_ in pb["bins"]:
            mark = "" if b_["n"] >= MIN_BIN_N else " ·표본부족"
            L.append(f"| {b_['lo']}~{b_['hi']}m | {b_['n']:,}{mark} | {b_['p10']} | "
                     f"**{b_['p50']}** | {b_['p90']} |")
        L += [""]
        hit = [f"{nm} {thr}dBm → 약 **{pb['cross'][nm]}m**"
               for nm, thr in THRESHOLDS if pb["cross"].get(nm) is not None]
        miss = [f"{nm} {thr}dBm" for nm, thr in THRESHOLDS if pb["cross"].get(nm) is None]
        if hit:
            L += ["> 중앙값 곡선이 임계를 지나는 지점 — " + " · ".join(hit), ""]
        if miss:
            L += ["> " + " · ".join(miss) + " 은 표본 구간 밖이라 환산되지 않았다. "
                  "그 거리대에서 표본을 더 받아야 한다.", ""]
        if pb.get("pairs"):
            L += ["> 역할쌍별 표본 — " +
                  " · ".join(f"{k} {c:,}" for k, c in pb["pairs"]), ""]

    if a.get("role_pairs"):
        tot = sum(c for _, c in a["role_pairs"])
        L += ["### 역할쌍별 접근 (v1.1.72 이후 기록분)", "",
              "| 역할쌍 | 건수 | 비중 |", "|--------|-----:|-----:|"]
        NM = {"WALKER": "보행자", "FORKLIFT": "지게차", "EPJ": "EPJ"}
        for (x, y), c in a["role_pairs"]:
            L.append(f"| {NM.get(x, x)} ↔ {NM.get(y, y)} | {c:,} | {c/tot*100:.1f}% |")
        L += ["", "> 지게차가 낀 조합이 이 앱이 겨냥한 위험이다. 그 비중이 곧 표본의 적합성이다.", ""]
    else:
        L += ["> 역할쌍 집계는 v1.1.72 이후 기록부터 나온다 (그 전 레코드에는 역할 필드가 없다).", ""]

    L += [f"> {CAVEAT}", ""]
    return "\n".join(L)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("path")
    p.add_argument("--days", type=int, default=0, help="최근 N일만 (0=전체)")
    p.add_argument("--from", dest="since", default="",
                   help="이 날짜(YYYYMMDD)부터만 - 발표 자료용으로 기간을 고정할 때")
    p.add_argument("--out", help="집계 결과 JSON 경로")
    p.add_argument("--md", help="마크다운 요약 경로")
    p.add_argument("--label", default="집계", help="마크다운 제목에 쓸 사업장 이름")
    p.add_argument("--append", action="store_true", help="--md 파일에 이어 쓴다")
    p.add_argument("--no-ids", action="store_true",
                   help="기기 ID 를 결과에 넣지 않는다 (공개 저장소용)")
    p.add_argument("--echo", help="echo_calib 노드 JSON - 단말 가동/실동률 계산용")
    p.add_argument("--probe", help="uwb_probe 노드 JSON - UWB 실거리 대비 RSSI 계산용")
    args = p.parse_args()

    a = aggregate(load(args.path), args.days, args.since)
    up = uptime(load_node(args.echo), a.get("recorders")) if args.echo else None
    pb = probe_bins(load_node(args.probe), args.since) if args.probe else None
    if args.no_ids:
        a.pop("top_pairs", None)
    a.pop("recorders", None)                       # 기기 ID 라 결과물에 남기지 않는다

    if args.md:
        with open(args.md, "a" if args.append else "w", encoding="utf-8") as f:
            f.write(markdown(a, args.label, up, pb) + "\n")
    if args.out:
        json.dump({**a, "uptime": up, "uwb_probe": pb, "주의": CAVEAT},
                  open(args.out, "w", encoding="utf-8"),
                  ensure_ascii=False, indent=2)

    if not a["n_days"]:
        print(f"{args.label}: 집계할 데이터 없음", file=sys.stderr)
        return
    print(f"{args.label}  {a['dates'][0]}~{a['dates'][-1]} ({a['n_days']}일)  "
          f"위험 {a['danger']:,} / 경고 {a['warning']:,}  "
          f"단말 {a['devices']}대 · 기기쌍 {a['pairs']}쌍")


if __name__ == "__main__":
    main()
