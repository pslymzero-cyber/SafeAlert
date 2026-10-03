"""RSSI vs. UWB-measured distance: works the app's alert thresholds back to meters, per role pair.

Alert thresholds are in dBm, but the site asks in meters. uwb_probe holds raw samples pairing the UWB-measured
distance with the RSSI of the same frame (AlertStateMachine.uploadUwbProbe); each sample carries its role pair
(CalibrationEngine.uwbPairKeyFor, e.g. "FORKLIFT×WALKER"). Samples accumulate only in sessions with
'UWB 실측 표본 업로드' on in developer settings (off by default).
"""
from collections import Counter, defaultdict

BIN_M = 0.5          # distance bin width
MIN_BIN_N = 3        # don't trust the median of a bin with fewer samples than this
# App defaults (DevSettings DEFAULT_*); ScriptRulesParityTest pins every number below to the app.
THRESHOLDS = (("경고", -78), ("위험", -65))
# Role-pair offset per probe pairKey (AlertStateMachine.computePayloadRiskOffset). A positive offset lowers the
#   threshold by that many dB (effective = threshold - offset), so the alert fires at a weaker signal, farther away.
PAIR_BIAS_DB = {"WALKER×WALKER": 0, "EPJ×WALKER": 2, "FORKLIFT×WALKER": 6,
                "EPJ×EPJ": -2, "EPJ×FORKLIFT": 8, "FORKLIFT×FORKLIFT": 8}
FORWARD_BIAS_DB = 3  # extra offset while the peer moves forward toward this device
ROLE_NAMES = {"WALKER": "보행자", "FORKLIFT": "지게차", "EPJ": "EPJ"}


def _pct(v, p):
    s = sorted(v)
    return s[max(0, min(len(s) - 1, round(p / 100 * (len(s) - 1))))] if s else None


def _bins(samples):
    """[(distM, rssi)] → distance bins with RSSI percentiles."""
    by_bin = defaultdict(list)
    for d, q in samples:
        by_bin[int(d / BIN_M)].append(q)
    return [{"lo": round(k * BIN_M, 1), "hi": round((k + 1) * BIN_M, 1), "mid": (k + 0.5) * BIN_M,
             "n": len(v), "p10": _pct(v, 10), "p50": _pct(v, 50), "p90": _pct(v, 90)}
            for k, v in sorted(by_bin.items())]


def _cross(bins, thr):
    """Finds the distance where the median RSSI crosses the threshold, by linear interpolation between neighboring bins.
    So that a single bin dipped by noise is not read as a crossing, a crossing counts only when the next bin
    is also below the threshold (a crossing into the last bin has nothing after it to check, so it is accepted as is)."""
    pts = [(b["mid"], b["p50"]) for b in bins if b["n"] >= MIN_BIN_N]
    for i, ((d0, r0), (d1, r1)) in enumerate(zip(pts, pts[1:])):
        if (r0 - thr) * (r1 - thr) > 0 or r0 == r1:
            continue
        if i + 2 < len(pts) and pts[i + 2][1] > thr:
            continue                                   # bounces right back = noise
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
            if isinstance(d, (int, float)) and isinstance(q, int) and not isinstance(q, bool) and d > 0:
                recs.append((float(d), q, str(r.get("pairKey") or "?")))
    if not recs:
        return {}
    # Each pair is converted on its own curve at its own effective thresholds: shielding differs by pair,
    #   which is what the offsets correct, so a curve pooled across pairs would mix those differences.
    per_pair = {}
    for key, off in PAIR_BIAS_DB.items():
        sub = [(d, q) for d, q, k in recs if k == key]
        if sub:
            pb = _bins(sub)
            per_pair[key] = {"n": len(sub), "bias": off,
                             "cross": {nm: _cross(pb, thr - off) for nm, thr in THRESHOLDS}}
    return {
        "n": len(recs),
        "days": len({d for d in (probe or {}) if isinstance(d, str) and d.isdigit()}),
        "bins": _bins([(d, q) for d, q, _ in recs]),
        "pairs": Counter(k for _, _, k in recs).most_common(6),
        "per_pair": per_pair,
    }


def threshold_note():
    """Caption under the RSSI distribution: the app's default thresholds and the offsets added to them."""
    (wn, wt), (dn, dt) = THRESHOLDS
    lo, hi = min(PAIR_BIAS_DB.values()), max(PAIR_BIAS_DB.values())
    return (f"> 설정 임계(앱 기본값)는 {wn} {wt}dBm · {dn} {dt}dBm 이다. 여기에 역할쌍 보정 {lo:+d}~{hi:+d}dB 가 붙고, "
            f"상대가 앞으로 다가오면 {FORWARD_BIAS_DB:+d}dB 가 더 붙는다. 보정이 + 면 그만큼 약한 신호(먼 거리)에서 "
            "먼저 울린다. 위 실측 분포가 그 임계와 얼마나 맞는지가 판정 정확도의 1차 지표다 "
            "(UWB 거리로 울린 경보도 그 순간의 RSSI 로 함께 들어 있다).")


def probe_md(pb):
    """Markdown lines for the UWB distance section of the digest."""
    L = ["### UWB 실거리 대비 RSSI (임계의 미터 환산)", "",
         f"UWB 가 잰 실거리와 같은 프레임의 RSSI 표본 **{pb['n']:,}건** "
         f"({pb['days']}일). 임계가 실제로 몇 m 인지를 재는 유일한 근거다. 아래 표는 모든 역할쌍을 합친 곡선이다.", "",
         "| 거리 | 표본 | P10 | 중앙값 | P90 |", "|------|-----:|----:|------:|----:|"]
    for b in pb["bins"]:
        mark = "" if b["n"] >= MIN_BIN_N else " ·표본부족"
        L.append(f"| {b['lo']}~{b['hi']}m | {b['n']:,}{mark} | {b['p10']} | **{b['p50']}** | {b['p90']} |")
    L += ["", "> 역할쌍마다 앱 기본 임계에서 그 쌍의 보정을 뺀 선을 그 쌍의 표본 곡선으로 환산한다 "
              f"(상대가 앞으로 다가오면 {FORWARD_BIAS_DB}dB 더 먼 선에서 울린다. 기기별 에코 보정·비콘 보정과 "
              "개발자 설정에서 바꾼 값은 빠져 있다)."]
    for key, p in sorted(pb.get("per_pair", {}).items(), key=lambda kv: -kv[1]["n"]):
        parts = [f"{nm} {thr - p['bias']}dBm → " + (f"약 **{p['cross'][nm]}m**" if p["cross"][nm] is not None
                                                     else "환산 안 됨")
                 for nm, thr in THRESHOLDS]
        names = "↔".join(ROLE_NAMES.get(t, t) for t in key.split("×"))
        L.append(f"> - {names} (보정 {p['bias']:+d}dB · 표본 {p['n']:,}건): " + " · ".join(parts))
    L += ["", f"> 환산 안 됨 = 그 쌍의 표본이 그 신호 세기 거리대에 모자라다(구간마다 {MIN_BIN_N}건 이상 필요).", ""]
    if pb.get("pairs"):
        L += ["> 역할쌍별 표본 — " + " · ".join(f"{k} {c:,}" for k, c in pb["pairs"]), ""]
    return L
