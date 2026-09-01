#!/usr/bin/env python3
"""Firebase 경보 이력을 집계해 자료에 넣을 수치를 뽑는다.

이 저장소의 세션에서는 Firebase 로 나가는 요청이 차단돼 직접 내려받지 못한다.
콘솔에서 내보낸 JSON 을 인자로 넘기면 된다.

    Firebase 콘솔 → Realtime Database → wf11/alerts → ⋮ → JSON 내보내기
    python3 analyze_alerts.py alerts.json [--days 28] [--out alerts_summary.json]

받는 모양 (FirebaseManager.kt:15-29 가 쓰는 그대로):
    { "20260901": { "<uuid>": {timestamp, deviceId, walkerId, rssi, alertLevel}, ... }, ... }
    루트가 wf11 전체여도 되고 (alerts 를 알아서 찾는다), alerts 노드만이어도 된다.

주의 — 건수의 의미:
    같은 기기에 대해 1분 1회로 스로틀돼 있다 (BleService.kt:2529).
    따라서 1건 = 경보 1회가 아니라 '해당 분(分)에 그 기기와 조우' 다.
    중복이 걷힌 값이라 '접근 조우 횟수' 에 가깝고, 그래서 아차사고 대리지표로 쓸 수 있다.
    사고 건수가 아니다. 자료에 옮길 때 이 문장을 같이 옮긴다.
"""
import argparse, json, sys
from collections import Counter, defaultdict
from datetime import datetime


def find_alerts(doc):
    if isinstance(doc, dict) and "alerts" in doc and isinstance(doc["alerts"], dict):
        return doc["alerts"]
    return doc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--days", type=int, default=0, help="최근 N일만 (0=전체)")
    ap.add_argument("--out", default="alerts_summary.json")
    a = ap.parse_args()

    alerts = find_alerts(json.load(open(a.path, encoding="utf-8")))
    days = sorted(k for k in alerts if k.isdigit() and len(k) == 8)
    if not days:
        sys.exit("일자 노드(yyyyMMdd)를 찾지 못했다. 내보낸 범위를 확인할 것.")
    if a.days:
        days = days[-a.days:]

    per_day = defaultdict(Counter)      # 날짜 → 등급별 건수
    per_hour = defaultdict(Counter)     # 시각 → 등급별 건수
    per_dow = defaultdict(Counter)      # 요일 → 등급별 건수
    pairs = Counter()                   # (walker, device) 조우 쌍
    devices, walkers = set(), set()
    rssi_by_level = defaultdict(list)
    total = Counter()

    for d in days:
        for rec in (alerts[d] or {}).values():
            if not isinstance(rec, dict):
                continue
            lv = rec.get("alertLevel", "?")
            total[lv] += 1
            per_day[d][lv] += 1
            dev, wlk = rec.get("deviceId", "?"), rec.get("walkerId", "?")
            devices.add(dev); walkers.add(wlk)
            pairs[tuple(sorted((str(wlk), str(dev))))] += 1
            if isinstance(rec.get("rssi"), int):
                rssi_by_level[lv].append(rec["rssi"])
            ts = rec.get("timestamp")
            if isinstance(ts, (int, float)):
                t = datetime.fromtimestamp(ts / 1000)
                per_hour[t.hour][lv] += 1
                per_dow["월화수목금토일"[t.weekday()]][lv] += 1

    n_days = len(days)
    danger, warning = total.get("DANGER", 0), total.get("WARNING", 0)
    out = {
        "기간": f"{days[0]} ~ {days[-1]}  ({n_days}일)",
        "총 조우": danger + warning,
        "위험(DANGER)": danger,
        "경고(WARNING)": warning,
        "일평균 위험": round(danger / n_days, 1) if n_days else 0,
        "일평균 경고": round(warning / n_days, 1) if n_days else 0,
        "관측 단말 수": len(devices | walkers),
        "조우한 기기쌍 수": len(pairs),
        "상위 조우쌍": [{"쌍": " ↔ ".join(k), "건수": v} for k, v in pairs.most_common(5)],
        "시간대별": {str(h): dict(c) for h, c in sorted(per_hour.items())},
        "요일별": {k: dict(v) for k, v in per_dow.items()},
        "일자별": {d: dict(c) for d, c in sorted(per_day.items())},
        "RSSI 중앙값": {lv: sorted(v)[len(v) // 2] for lv, v in rssi_by_level.items() if v},
        "주의": "1건 = 경보 1회가 아니라 해당 분(分)의 조우. 기기당 1분 1회 스로틀(BleService.kt:2529). "
                "사고 건수가 아니다.",
    }
    json.dump(out, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    print(f"기간            {out['기간']}")
    print(f"위험 / 경고     {danger:,} / {warning:,}   (일평균 {out['일평균 위험']} / {out['일평균 경고']})")
    print(f"관측 단말       {out['관측 단말 수']}대   ·   조우 기기쌍 {out['조우한 기기쌍 수']}쌍")
    if per_hour:
        peak = max(per_hour.items(), key=lambda kv: sum(kv[1].values()))
        print(f"최다 시간대     {peak[0]:02d}시  {sum(peak[1].values()):,}건")
    print(f"\n→ {a.out}")
    print("   1건 = 해당 분(分)의 조우. 사고 건수가 아니다.")


if __name__ == "__main__":
    main()
