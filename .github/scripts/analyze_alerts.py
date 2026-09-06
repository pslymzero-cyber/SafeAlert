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
import argparse, json, sys
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


def aggregate(alerts, days=0):
    dates = sorted(k for k in alerts if k.isdigit() and len(k) == 8)
    if days:
        dates = dates[-days:]
    per_day, per_hour, per_dow = defaultdict(Counter), defaultdict(Counter), defaultdict(Counter)
    pairs, devices, rssi = Counter(), set(), defaultdict(list)
    total = Counter()
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
        "top_pairs": pairs.most_common(5),
        "per_day": {d: dict(c) for d, c in sorted(per_day.items())},
        "per_hour": {h: dict(c) for h, c in sorted(per_hour.items())},
        "per_dow": {k: dict(v) for k, v in per_dow.items()},
        "rssi_median": {lv: sorted(v)[len(v) // 2] for lv, v in rssi.items() if v},
    }
    out.update(derive(events, rssi, out["per_day"]))
    return out


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


def markdown(a, label):
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

    L += [f"> {CAVEAT}", ""]
    return "\n".join(L)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("path")
    p.add_argument("--days", type=int, default=0, help="최근 N일만 (0=전체)")
    p.add_argument("--out", help="집계 결과 JSON 경로")
    p.add_argument("--md", help="마크다운 요약 경로")
    p.add_argument("--label", default="집계", help="마크다운 제목에 쓸 사업장 이름")
    p.add_argument("--append", action="store_true", help="--md 파일에 이어 쓴다")
    p.add_argument("--no-ids", action="store_true",
                   help="기기 ID 를 결과에 넣지 않는다 (공개 저장소용)")
    args = p.parse_args()

    a = aggregate(load(args.path), args.days)
    if args.no_ids:
        a.pop("top_pairs", None)

    if args.md:
        with open(args.md, "a" if args.append else "w", encoding="utf-8") as f:
            f.write(markdown(a, args.label) + "\n")
    if args.out:
        json.dump({**a, "주의": CAVEAT}, open(args.out, "w", encoding="utf-8"),
                  ensure_ascii=False, indent=2)

    if not a["n_days"]:
        print(f"{args.label}: 집계할 데이터 없음", file=sys.stderr)
        return
    print(f"{args.label}  {a['dates'][0]}~{a['dates'][-1]} ({a['n_days']}일)  "
          f"위험 {a['danger']:,} / 경고 {a['warning']:,}  "
          f"단말 {a['devices']}대 · 기기쌍 {a['pairs']}쌍")


if __name__ == "__main__":
    main()
