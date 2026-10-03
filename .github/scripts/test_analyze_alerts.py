#!/usr/bin/env python3
"""Regression tests for analyze_alerts.py. Plain stdlib asserts only — no pytest/unittest.

Run directly with python .github/scripts/test_analyze_alerts.py.
"""
import json, os, subprocess, sys, tempfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ANALYZE_PY = os.path.join(SCRIPT_DIR, "analyze_alerts.py")
sys.path.insert(0, SCRIPT_DIR)
import analyze_alerts  # noqa: E402  (the module has a __main__ guard, so importing it is safe)


def _rec(level, ts=1756739400000):
    return {
        "alertLevel": level,
        "timestamp": ts,
        "deviceId": "SAFEALERT_DEVICE_A",
        "walkerId": "B",
        "rssi": -60,
    }


def D(ts=1756739400000):
    return _rec("DANGER", ts)


def W(ts=1756739400000):
    return _rec("WARNING", ts)


def test_root_date_layer_unchanged():
    alerts = {"20260901": {"u1": D()}}
    a = analyze_alerts.aggregate(alerts)
    assert a["dates"] == ["20260901"], a["dates"]
    assert a["danger"] == 1, a["danger"]


def test_site_layer_string_code():
    alerts = {"WF11": {"20260901": {"u1": D()}}}
    a = analyze_alerts.aggregate(alerts)
    assert a["dates"] == ["20260901"], a["dates"]
    assert a["danger"] == 1, a["danger"]


def test_numeric_site_code_not_date():
    alerts = {"12345678": {"20260901": {"u1": D()}, "20260902": {"u2": W()}}}
    a = analyze_alerts.aggregate(alerts)
    assert a["dates"] == ["20260901", "20260902"], a["dates"]
    assert "12345678" not in a["dates"]
    assert a["danger"] == 1, a["danger"]
    assert a["warning"] == 1, a["warning"]


def test_merge_root_and_sites_same_date():
    alerts = {
        "20260901": {"u1": D()},
        "WF11": {"20260901": {"u2": W()}},
        "A1": {"20260901": {"u3": D()}},
    }
    a = analyze_alerts.aggregate(alerts)
    assert a["dates"] == ["20260901"], a["dates"]
    assert a["per_day"]["20260901"] == {"DANGER": 2, "WARNING": 1}, a["per_day"]


def test_since_cuts_site_layer():
    alerts = {"WF11": {"20260801": {"u1": D()}, "20260902": {"u2": W()}}}
    a = analyze_alerts.aggregate(alerts, since="20260901")
    assert a["dates"] == ["20260902"], a["dates"]
    assert a["danger"] == 0, a["danger"]
    assert a["warning"] == 1, a["warning"]


def test_cli_from_like_workflow():
    alerts = {
        "20260901": {"u1": D()},
        "WF11": {"20260901": {"u2": W()}, "20260801": {"u4": W()}},
        "A1": {"20260901": {"u3": D()}},
    }
    with tempfile.TemporaryDirectory() as td:
        in_path = os.path.join(td, "alerts.json")
        out_path = os.path.join(td, "result.json")
        json.dump({"alerts": alerts}, open(in_path, "w", encoding="utf-8"))
        proc = subprocess.run(
            [sys.executable, ANALYZE_PY, in_path,
             "--from", "20260901", "--no-ids", "--out", out_path],
            capture_output=True, text=True,
        )
        assert proc.returncode == 0, proc.stderr
        result = json.load(open(out_path, encoding="utf-8"))
        assert result["dates"] == ["20260901"], result["dates"]
        assert result["danger"] == 2, result["danger"]
        assert result["warning"] == 1, result["warning"]


def test_uptime_skips_nodes_without_model():
    now_ms = 1756739400000
    echo = {
        "devA": {"model": "SM-A", "ts": now_ms},
        "WF11": {"devB": {"model": "SM-B", "ts": now_ms}},
    }
    up = analyze_alerts.uptime(echo, ["devA"], now_s=now_ms / 1000)
    assert up["registered"] == 1, up["registered"]
    assert up["fresh"] == 1, up["fresh"]
    assert up["stale"] == 0, up["stale"]
    assert up["silent"] == 0, up["silent"]
    assert up["unregistered"] == 0, up["unregistered"]
    assert up["rate"] == 100.0, up["rate"]
    assert up["models"] == [("SM-A", 1)], up["models"]


if __name__ == "__main__":
    fails = []
    tests = sorted(
        (name, fn) for name, fn in globals().items()
        if name.startswith("test_") and callable(fn)
    )
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
