#!/usr/bin/env python3
"""단독 작업자 '살아 있음' 세션(hb 노드) → ALERTS.md 의 '연락 끊김 (기록만)' 표. 표준 라이브러리만.

python .github/scripts/hb_digest.py hb.json --md ALERTS.md --from 20260901
입력 = {센터 코드: {세션 키: {uid, role, start, last, end?, g?: {n: {from, to}}}}} (밀리초).
식별자(uid·세션 키·이름)는 출력하지 않고 센터별 숫자만 남긴다. 메일·알림은 없다.
"""
import argparse
import datetime
import json
import sys
import time

GAP_MS = 15 * 60_000  # 마지막 갱신이 이보다 오래면 '끊긴 채 끝남'(앱의 끊김 기준과 같다)
BUCKETS = [(30, "15~30분"), (60, "30~60분"), (120, "1~2시간"), (None, "2시간+")]  # 분 상한
TITLE = "### 연락 끊김 (기록만)"
NOTE = "> 단독 작업 감시 중 단말이 5분마다 남긴 살아 있음 기록. 메일·알림 없음. 이름·기기 식별자는 집계하지 않는다."
EMPTY = "> 기간 안 기록 없음."
TOTAL = "합계"
HEAD = ("| 센터 | 세션 | 정상 종료 | 끊긴 채 끝남 | 진행 중 | 끊김 구간 | "
        + " | ".join(label for _, label in BUCKETS) + " |")
SEP = "|------|" + "-----:|" * (5 + len(BUCKETS))
FIELDS = ["sessions", "ended", "lost", "live", "gaps"]


def _num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def _gaps(g):
    # REST 는 0..n 키를 배열로 준다(빈 자리 null). 객체로 와도 같게 센다.
    if isinstance(g, list):
        return [x for x in g if x is not None]
    if isinstance(g, dict):
        return list(g.values())
    return []


def _bucket(ms):
    m = ms / 60_000
    for i, (hi, _) in enumerate(BUCKETS):
        if hi is None or m < hi:
            return i


def _empty():
    c = {k: 0 for k in FIELDS}
    c["b"] = [0] * len(BUCKETS)
    return c


def summarize(data, from_ms, now_ms):
    """센터 코드 → 수치. from_ms 이전에 시작한 세션과 형식이 틀린 세션·구간은 건너뛴다."""
    out = {}
    if not isinstance(data, dict):
        return out
    for sc, sessions in data.items():
        if not isinstance(sessions, dict):
            continue
        for s in sessions.values():
            if not isinstance(s, dict):
                continue
            start, last, end = s.get("start"), s.get("last"), s.get("end")
            if not _num(start) or not _num(last) or start < from_ms:
                continue
            c = out.setdefault(sc, _empty())
            c["sessions"] += 1
            if _num(end):
                c["ended"] += 1
            elif now_ms - last > GAP_MS:
                c["lost"] += 1
            else:
                c["live"] += 1
            for x in _gaps(s.get("g")):
                if not isinstance(x, dict) or not _num(x.get("from")) or not _num(x.get("to")):
                    continue
                c["gaps"] += 1
                c["b"][_bucket(x["to"] - x["from"])] += 1
    return out


def _row(label, c):
    return "| " + " | ".join([label] + [str(c[k]) for k in FIELDS] + [str(n) for n in c["b"]]) + " |"


def render(summary):
    L = [TITLE, "", NOTE, ""]
    if not summary:
        return L + [EMPTY, ""]
    L += [HEAD, SEP]
    tot = _empty()
    for sc in sorted(summary):
        c = summary[sc]
        L.append(_row(sc, c))
        for k in FIELDS:
            tot[k] += c[k]
        tot["b"] = [a + b for a, b in zip(tot["b"], c["b"])]
    L += [_row(TOTAL, tot), ""]
    return L


def from_ms(yyyymmdd):
    d = datetime.datetime.strptime(yyyymmdd, "%Y%m%d").replace(tzinfo=datetime.timezone.utc)
    return int(d.timestamp() * 1000)


def main(argv=None):
    ap = argparse.ArgumentParser(description="hb 노드 → 연락 끊김 표")
    ap.add_argument("json")
    ap.add_argument("--md", help="덧붙일 마크다운 파일(없으면 표준 출력)")
    ap.add_argument("--from", dest="frm", default="19700101", help="YYYYMMDD(UTC) 이후 시작한 세션만")
    ap.add_argument("--now", type=int, help="기준 시각(밀리초, 테스트용)")
    a = ap.parse_args(argv)
    # ponytail: hb 노드를 통째로 받는다. 세션이 많아지면 start 기준 키 범위로 잘라 받기.
    with open(a.json, encoding="utf-8") as f:
        data = json.load(f)
    now = a.now if a.now is not None else int(time.time() * 1000)
    text = "\n".join(render(summarize(data, from_ms(a.frm), now))) + "\n"
    if a.md:
        with open(a.md, "a", encoding="utf-8") as f:
            f.write(text)
    else:
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
