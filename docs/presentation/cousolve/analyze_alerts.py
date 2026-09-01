#!/usr/bin/env python3
"""Firebase 경보 이력을 집계한다.

두 곳에서 쓴다.
  · 로컬  — `fetch_alerts.sh` 가 Firebase CLI 로 받은 JSON 을 넘긴다
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
    for d in dates:
        for rec in (alerts.get(d) or {}).values():
            if not isinstance(rec, dict):
                continue
            lv = rec.get("alertLevel", "?")
            total[lv] += 1
            per_day[d][lv] += 1
            a, b = str(rec.get("deviceId", "?")), str(rec.get("walkerId", "?"))
            devices.update((a, b))
            pairs[tuple(sorted((a, b)))] += 1
            if isinstance(rec.get("rssi"), int):
                rssi[lv].append(rec["rssi"])
            ts = rec.get("timestamp")
            if isinstance(ts, (int, float)):
                t = datetime.fromtimestamp(ts / 1000, KST)
                per_hour[t.hour][lv] += 1
                per_dow["월화수목금토일"[t.weekday()]][lv] += 1
    n = len(dates) or 1
    return {
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
    L += ["### 일자별", "", "| 날짜 | 경고 | 위험 |", "|------|-----:|-----:|"]
    for d, c in list(a["per_day"].items())[-14:]:
        L.append(f"| {d} | {c.get('WARNING', 0):,} | {c.get('DANGER', 0):,} |")
    L += ["", f"> {CAVEAT}", ""]
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
