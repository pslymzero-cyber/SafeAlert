#!/usr/bin/env python3
"""hb_digest.py regression tests. Standard library assert only - no pytest/unittest.

Run directly: python .github/scripts/test_hb_digest.py
"""
import json, os, subprocess, sys, tempfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
HB_PY = os.path.join(SCRIPT_DIR, "hb_digest.py")
sys.path.insert(0, SCRIPT_DIR)
import hb_digest  # noqa: E402  (__main__ guard)

MIN = 60_000
FROM = hb_digest.from_ms("20260901")
NOW = FROM + 10 * 24 * 3600 * 1000


def sess(start, last, end=None, g=None, **extra):
    s = {"uid": "UIDMARK", "role": "WALKER", "start": start, "last": last}
    if end is not None:
        s["end"] = end
    if g is not None:
        s["g"] = g
    s.update(extra)
    return s


def gap(minutes, at=NOW - 3600 * 1000):
    return {"from": at, "to": at + minutes * MIN}


def test_from_filter():
    data = {"WF11": {"a": sess(FROM - 1, NOW), "b": sess(FROM, NOW, end=NOW)}}
    s = hb_digest.summarize(data, FROM, NOW)
    assert s["WF11"]["sessions"] == 1, s
    assert s["WF11"]["ended"] == 1, s


def test_center_counts_buckets_and_total():
    data = {
        "WF12": {"x": sess(FROM, NOW - MIN, end=NOW - MIN)},
        "WF11": {
            "a": sess(FROM, NOW - 2 * MIN, end=NOW - 2 * MIN, g=[gap(16), gap(45)]),
            "b": sess(FROM, NOW - 15 * MIN - 1, g=[gap(90), gap(150)]),
            "c": sess(FROM, NOW - 15 * MIN),
        },
    }
    s = hb_digest.summarize(data, FROM, NOW)
    c = s["WF11"]
    assert (c["sessions"], c["ended"], c["restart"], c["lost"], c["live"], c["gaps"]) == (3, 1, 0, 1, 1, 4), c
    assert c["b"] == [1, 1, 1, 1], c
    lines = hb_digest.render(s)
    assert lines[0] == hb_digest.TITLE
    rows = [l for l in lines if l.startswith("| ")]
    assert rows[0] == hb_digest.HEAD
    assert rows[1] == "| WF11 | 3 | 1 | 0 | 1 | 1 | 4 | 1 | 1 | 1 | 1 |", rows
    assert rows[2] == "| WF12 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |", rows
    assert rows[3] == "| " + hb_digest.TOTAL + " | 4 | 2 | 0 | 1 | 1 | 4 | 1 | 1 | 1 | 1 |", rows
    assert lines[1 + lines.index(rows[3]) + 1] == hb_digest.READ, lines


def test_from_is_kst_midnight():
    import datetime
    kst = datetime.timezone(datetime.timedelta(hours=9))
    assert FROM == int(datetime.datetime(2026, 9, 1, tzinfo=kst).timestamp() * 1000), FROM
    data = {"WF11": {"a": sess(FROM + 7 * 60 * MIN, NOW, end=NOW), "b": sess(FROM - MIN, NOW, end=NOW)}}
    s = hb_digest.summarize(data, FROM, NOW)
    assert s["WF11"]["sessions"] == 1, s


def counts(data, now=NOW):
    c = hb_digest.summarize(data, FROM, now)["WF11"]
    return c["sessions"], c["ended"], c["restart"], c["lost"], c["live"]


def test_restart_pairs_same_uid_same_center():
    t0 = FROM + 3600 * 1000
    old = sess(t0, t0 + 237 * MIN, uid="u1")
    new = sess(t0 + 241 * MIN, t0 + 300 * MIN, end=t0 + 300 * MIN, uid="u1")
    assert counts({"WF11": {"KEYMARK": old, "n": new}}) == (2, 1, 1, 0, 0)
    late = dict(new, start=t0 + 237 * MIN + 16 * MIN)
    assert counts({"WF11": {"o": old, "n": late}}) == (2, 1, 0, 1, 0)
    other = dict(new, uid="u2", start=t0 + 238 * MIN)
    assert counts({"WF11": {"o": old, "n": other}}) == (2, 1, 0, 1, 0)
    elsewhere = hb_digest.summarize({"WF11": {"o": old}, "WF12": {"n": new}}, FROM, NOW)
    assert elsewhere["WF11"]["lost"] == 1 and elsewhere["WF11"]["restart"] == 0, elsewhere
    assert elsewhere["WF12"]["sessions"] == 1, elsewhere
    # still "live" by its last refresh, but a new session already started within 15 minutes
    live_now = t0 + 241 * MIN
    assert counts({"WF11": {"o": old, "n": new}}, now=live_now) == (2, 1, 1, 0, 0)
    text = "\n".join(hb_digest.render(hb_digest.summarize({"WF11": {"KEYMARK": old, "n": new}}, FROM, NOW)))
    assert "u1" not in text and "KEYMARK" not in text, text
    assert hb_digest.READ in text, text


def test_array_and_object_gaps_match():
    g0, g2 = gap(20), gap(70)
    arr = {"WF11": {"a": sess(FROM, NOW, g=[g0, None, g2])}}
    obj = {"WF11": {"a": sess(FROM, NOW, g={"0": g0, "2": g2})}}
    a = hb_digest.summarize(arr, FROM, NOW)
    b = hb_digest.summarize(obj, FROM, NOW)
    assert a == b, (a, b)
    assert a["WF11"]["gaps"] == 2, a


def test_malformed_skipped_and_null_input():
    data = {
        "WF11": {
            "bad": sess("x", NOW),
            "flag": sess(True, NOW),
            "ok": sess(FROM, NOW, g=[{"from": "x", "to": NOW}, {"from": FROM}, gap(30)]),
        },
        "WF13": "junk",
    }
    s = hb_digest.summarize(data, FROM, NOW)
    assert list(s) == ["WF11"], s
    assert s["WF11"]["sessions"] == 1 and s["WF11"]["gaps"] == 1, s
    assert s["WF11"]["b"] == [0, 1, 0, 0], s
    for empty in (None, {}, {"WF11": {"a": sess(FROM - 1, NOW)}}):
        lines = hb_digest.render(hb_digest.summarize(empty, FROM, NOW))
        assert hb_digest.EMPTY in lines, lines
        assert not any(l.startswith("| ") for l in lines), lines


def test_no_identifiers_in_output():
    data = {"WF11": {"KEYMARK": sess(FROM, NOW, name="NAMEMARK", bleId="BLEMARK", g=[gap(20)])}}
    text = "\n".join(hb_digest.render(hb_digest.summarize(data, FROM, NOW)))
    for mark in ("UIDMARK", "KEYMARK", "NAMEMARK", "BLEMARK"):
        assert mark not in text, mark


def test_cli_appends_to_markdown():
    data = {"WF11": {"KEYMARK": sess(FROM, NOW - MIN, end=NOW - MIN)}}
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "hb.json")
        md = os.path.join(d, "ALERTS.md")
        with open(src, "w", encoding="utf-8") as f:
            json.dump(data, f)
        with open(md, "w", encoding="utf-8") as f:
            f.write("## WF11\n\n")
        subprocess.run([sys.executable, HB_PY, src, "--md", md, "--from", "20260901", "--now", str(NOW)], check=True)
        with open(md, encoding="utf-8") as f:
            text = f.read()
    assert text.startswith("## WF11\n\n" + hb_digest.TITLE + "\n"), text
    assert "| WF11 | 1 | 1 | 0 | 0 | 0 |" in text, text
    assert "KEYMARK" not in text and "UIDMARK" not in text


if __name__ == "__main__":
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_") and callable(f)]
    fails = []
    for name, fn in tests:
        try:
            fn()
        except AssertionError as e:
            fails.append(name)
            print(f"FAIL {name}: {e}")
        else:
            print(f"PASS {name}")
    if fails:
        print(f"{len(fails)}/{len(tests)} failed: {', '.join(fails)}")
        sys.exit(1)
    print(f"{len(tests)}/{len(tests)} passed")
    print("OK")
