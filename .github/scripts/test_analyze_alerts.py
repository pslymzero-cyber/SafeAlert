#!/usr/bin/env python3
"""Regression tests for analyze_alerts.py. Plain stdlib asserts only — no pytest/unittest.

Run directly with python .github/scripts/test_analyze_alerts.py.
"""
import json, os, subprocess, sys, tempfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ANALYZE_PY = os.path.join(SCRIPT_DIR, "analyze_alerts.py")
sys.path.insert(0, SCRIPT_DIR)
import analyze_alerts  # noqa: E402  (the module has a __main__ guard, so importing it is safe)
import uwb_probe  # noqa: E402

T0 = 1756739400000


def _rec(level, ts=T0, device="SAFEALERT_DEVICE_A", walker="B", site=None, prefixed=True, rssi=-60):
    """One record as saveAlert writes it: deviceId = the peer's scanner fullId, walkerId = the recorder's myId.
    With a center code, 'site' holds it and (from the withSite build on) both IDs are written as "<code>-<id>"."""
    r = {"alertLevel": level, "timestamp": ts, "deviceId": device, "walkerId": walker, "rssi": rssi}
    if site is not None:
        r["site"] = site
        if site and prefixed:
            r.update(deviceId=f"{site}-{device}", walkerId=f"{site}-{walker}")
    return r


def D(ts=T0):
    return _rec("DANGER", ts)


def W(ts=T0):
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
            capture_output=True, text=True, encoding="utf-8",
            env=dict(os.environ, PYTHONIOENCODING="utf-8"),
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


CB, SA = "SAFEALERT_DEVICE_CB-01", "SAFEALERT_WALKER_SA-1A2B3C4D"   # peer fullIds as the scanner builds them


def test_center_prefixed_mutual_encounter_pairs():
    alerts = {"WF11": {"20260901": {"u1": _rec("WARNING", T0, device=SA, walker="CB-01", site="WF11"),
                                    "u2": _rec("WARNING", T0 + 5000, device=CB, walker="SA-1A2B3C4D", site="WF11")}}}
    a = analyze_alerts.aggregate(alerts)
    assert (a["devices"], a["pairs"]) == (2, 1), (a["devices"], a["pairs"])
    assert (a["encounters"], a["both_rate"]) == (1, 100.0), (a["encounters"], a["both_rate"])
    assert a["recorders"] == ["CB-01", "SA-1A2B3C4D"], a["recorders"]


def test_center_prefixed_recorders_match_echo_keys():
    alerts = {"WF11": {"20260901": {"u1": _rec("DANGER", device=SA, walker="CB-01", site="WF11")}}}
    echo = {"CB-01": {"model": "SM-A", "ts": T0}, "SA-1A2B3C4D": {"model": "SM-B", "ts": T0}}
    up = analyze_alerts.uptime(echo, analyze_alerts.aggregate(alerts)["recorders"], now_s=T0 / 1000)
    assert (up["recorded"], up["unregistered"], up["rate"]) == (1, 0, 50.0), up


def test_record_shapes_of_one_center_reduce_to_one_id():
    alerts = {"20260901": {"u1": _rec("WARNING", device=CB, walker="SA-1A2B3C4D"),             # before center codes
                           "u2": _rec("WARNING", device=CB, walker="SA-1A2B3C4D", site="")},   # empty code
              "WF11": {"20260901": {
                  "u3": _rec("WARNING", device=CB, walker="SA-1A2B3C4D", site="WF11", prefixed=False),
                  "u4": _rec("WARNING", device=CB, walker="SA-1A2B3C4D", site="WF11")}}}
    a = analyze_alerts.aggregate(alerts)
    assert (a["devices"], a["pairs"]) == (2, 1), (a["devices"], a["pairs"])
    assert a["recorders"] == ["SA-1A2B3C4D"], a["recorders"]


def test_hyphenated_center_code():
    alerts = {"WF-11": {"20260901": {"u1": _rec("WARNING", device=CB, walker="SA-1A2B3C4D", site="WF-11")}}}
    a = analyze_alerts.aggregate(alerts)
    assert (a["devices"], a["recorders"]) == (2, ["SA-1A2B3C4D"]), (a["devices"], a["recorders"])


def test_unprefixed_id_that_starts_with_the_code_is_kept():
    # Center code "SA": a record with the code in 'site' but unprefixed IDs, one of which starts with "SA-".
    alerts = {"SA": {"20260901": {
        "u1": _rec("WARNING", device=CB, walker="SA-1A2B3C4D", site="SA", prefixed=False),
        "u2": _rec("WARNING", T0 + 5000, device=CB, walker="SA-1A2B3C4D", site="SA")}}}
    a = analyze_alerts.aggregate(alerts)
    assert (a["devices"], a["recorders"]) == (2, ["SA-1A2B3C4D"]), (a["devices"], a["recorders"])


def test_stray_second_code_does_not_split_devices():
    alerts = {"20260901": {"u1": _rec("WARNING", device=CB, walker="SA-1A2B3C4D")},
              "WF11": {"20260901": {"u2": _rec("WARNING", T0 + 3000, device=SA, walker="CB-01", site="WF11")}},
              "TEST": {"20260902": {"u3": _rec("WARNING", T0 + 86_400_000, device="SAFEALERT_DEVICE_X",
                                               walker="Y", site="TEST")}}}
    a = analyze_alerts.aggregate(alerts)
    assert (a["devices"], a["pairs"]) == (4, 2), (a["devices"], a["pairs"])
    assert (a["encounters"], a["both_rate"]) == (2, 50.0), (a["encounters"], a["both_rate"])


def test_rssi_zero_is_not_a_reading():
    a = analyze_alerts.aggregate({"20260901": {"u1": _rec("DANGER", rssi=0), "u2": _rec("DANGER", T0 + 1000)}})
    s = a["rssi_dist"]["DANGER"]
    assert (s["n"], s["p90"]) == (1, -60), s


def _probe(curves):
    """{pairKey: [(distM, rssi), ...]} → a uwb_probe node, 3 samples per point (MIN_BIN_N)."""
    return {"20260901": {f"{key}-{i}-{k}": {"distM": d, "rssi": q, "pairKey": key}
                         for key, pts in curves.items() for i, (d, q) in enumerate(pts) for k in range(3)}}


# Walker-forklift: danger line -65 - 6 = -71 dBm, crossed halfway between the -70 and -72 bins (1.25 m, 1.75 m).
# Walker-walker: no offset, danger -65 dBm, crossed halfway between the -64 and -66 bins (1.75 m, 2.25 m).
CURVES = {"FORKLIFT×WALKER": [(0.25, -60), (0.75, -65), (1.25, -70), (1.75, -72), (2.25, -80)],
          "WALKER×WALKER": [(0.25, -55), (0.75, -60), (1.25, -62), (1.75, -64), (2.25, -66), (2.75, -70)]}


def test_probe_converts_each_pair_on_its_own_curve_and_threshold():
    pb = uwb_probe.probe_bins(_probe(CURVES))
    fw, ww = pb["per_pair"]["FORKLIFT×WALKER"], pb["per_pair"]["WALKER×WALKER"]
    assert (fw["n"], fw["bias"], ww["n"], ww["bias"]) == (15, 6, 18, 0), (fw, ww)
    assert fw["cross"] == {"경고": None, "위험": 1.5}, fw["cross"]
    assert ww["cross"] == {"경고": None, "위험": 2.0}, ww["cross"]
    md = "\n".join(uwb_probe.probe_md(pb))
    assert "위험 -71dBm → 약 **1.5m**" in md and "경고 -84dBm → 환산 안 됨" in md, md
    assert "위험 -65dBm → 약 **2.0m**" in md, md


def test_markdown_includes_threshold_note_and_probe_section():
    a = analyze_alerts.aggregate({"20260901": {"u1": D(), "u2": W(T0 + 1000)}})
    md = analyze_alerts.markdown(a, "WF11", pb=uwb_probe.probe_bins(_probe(CURVES)))
    assert uwb_probe.threshold_note() in md, md
    assert "### UWB 실거리 대비 RSSI" in md and "위험 -71dBm → 약 **1.5m**" in md, md


def test_threshold_note_states_app_defaults_and_offset_range():
    note = uwb_probe.threshold_note()
    assert "경고 -78dBm · 위험 -65dBm" in note and "-2~+8dB" in note and "+3dB" in note, note


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
