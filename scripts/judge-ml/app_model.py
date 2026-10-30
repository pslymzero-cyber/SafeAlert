"""Train the two small on-device models the SafeAlert app ships in app/src/main/assets/ai/.

    python app_model.py train <export.json>   judge records -> assets/ai/prox.json, fall.json + report
    python app_model.py prior                 placeholder models (path-loss prior, impact prior)
    python app_model.py selftest              synthetic data end to end

prox: BLE values of one peer (latest frame + last 10 s) -> log10(distance m), labelled by the UWB distance
      in `uwb` records (judge and measure). fall: impact candidate summary -> fall probability, labelled by the
      experiment session (test:fall / test:no) or the fall question answer.
The model JSON format and the inputs must match AiJudge.kt (SPEC_ai.md sections 1-2): standardize with
mean/std (std 0 -> 1, missing -> fill), then dense layers h = act(W h + b), W as [out][in].
"""
import argparse
import json
import math
import sys
import tempfile
import warnings
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import judge_ml as jm  # noqa: E402  (find_judge / flatten — the same export reader as the analysis)

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent.parent / "app" / "src" / "main" / "assets" / "ai"
PROX_IN = ["rssi", "med", "pre", "kf", "ema", "avg1s", "kf_vel",
           "seq_mean", "seq_std", "seq_min", "seq_max", "seq_slope", "seq_vel"]
FALL_IN = ["ff_ms", "peak_g", "imp_g", "deg", "post_n", "post_act", "pre_ok"]
CLAMP = (0.3, 30.0)
FRESH_MS = 2000          # rows whose BLE frame is older than this are not the same moment as the UWB distance
PRIOR_A, PRIOR_N = -59.0, 2.2
MIN_PROX, MIN_FALL = 100, 8   # fewer rows (or fewer of either fall class) -> that model is not replaced


# ── forward pass (mirror of AiJudge.kt) ──────────────────────────────────

def act(z, a):
    if a == "relu":
        return np.maximum(z, 0.0)
    if a == "tanh":
        return np.tanh(z)
    if a == "sigmoid":
        return 1.0 / (1.0 + np.exp(-z))
    if a == "linear":
        return z
    raise ValueError(a)


def run(model, x):
    x = np.asarray(x, dtype=float)
    x = np.where(np.isnan(x), np.asarray(model["fill"], float), x)
    std = np.asarray(model["std"], float)
    h = (x - np.asarray(model["mean"], float)) / np.where(std == 0, 1.0, std)
    for layer in model["layers"]:
        h = act(np.asarray(layer["w"], float) @ h + np.asarray(layer["b"], float), layer["act"])
    return float(h[0])


def meters(model, x):
    return float(np.clip(10.0 ** run(model, x), *model.get("clamp", CLAMP)))


# ── inputs (same definitions as AiJudge.proxFeatures / fall) ─────────────

def seq_stats(seq, ema, kf_vel):
    e, v = [], []
    for part in str(seq).split(";") if isinstance(seq, str) and seq else []:
        a = part.split(",")
        if len(a) == 2:
            try:
                e.append(float(a[0]))
                v.append(round(float(a[1]), 1))
            except ValueError:
                pass
    if not e:
        return [ema, 0.0, ema, ema, 0.0, kf_vel]
    e = np.asarray(e)
    slope = (e[-1] - e[0]) / (len(e) - 1) if len(e) >= 2 else 0.0
    return [float(e.mean()), float(e.std()), float(e.min()), float(e.max()), float(slope), float(np.mean(v))]


def prox_x(r):
    base = [jm.fnum(r.get(c)) for c in ("rssi", "med", "pre", "kf", "ema", "avg1s", "kf_vel")]
    base = [np.nan if b is None else b for b in base]
    return base + seq_stats(r.get("seq"), base[4], 0.0 if np.isnan(base[6]) else base[6])


def fall_x(r):
    pre = r.get("pre_ok")
    pre = np.nan if pre is None or (isinstance(pre, float) and np.isnan(pre)) else (1.0 if jm.yes(pre) else 0.0)
    out = [jm.fnum(r.get(c)) for c in ("ff_ms", "peak_g", "imp_g", "deg", "post_n", "post_act")]
    return [np.nan if v is None else v for v in out] + [pre]


# ── datasets ─────────────────────────────────────────────────────────────

def load(export):
    judge = jm.find_judge(json.loads(Path(export).read_text(encoding="utf-8")))
    if not judge:
        sys.exit("내보낸 JSON 에 judge 노드가 없습니다. Firebase 콘솔에서 wf11/judge 를 내보내세요.")
    df = jm.flatten(judge)
    if df.empty:
        sys.exit("judge 노드에 기록이 없습니다.")
    return jm.need(df, ["k", "t", "dev", "sess", "uwb_m", "frame_age_ms", "imp_t", "truth", "warn_m", "dang_m",
                        "eff_w", "eff_d", "ema", "peer_role", "role"])


def prox_rows(df):
    p = df[df["k"] == "uwb"].copy()
    p["uwb_m"] = pd.to_numeric(p["uwb_m"], errors="coerce")
    age = pd.to_numeric(p["frame_age_ms"], errors="coerce")
    p = p[(p["uwb_m"] > 0) & (age.isna() | (age <= FRESH_MS)) & pd.to_numeric(p["ema"], errors="coerce").notna()]
    p = p.sort_values("t").reset_index(drop=True)
    X = np.array([prox_x(r) for r in p.to_dict("records")], dtype=float) if len(p) else np.zeros((0, len(PROX_IN)))
    y = np.log10(np.clip(p["uwb_m"].to_numpy(float), *CLAMP)) if len(p) else np.zeros(0)
    return p, X, y


def fall_rows(df):
    c = df[df["k"] == "cand"].copy()
    # One trial can leave several candidates (the fall, then getting up / picking the phone up): in an experiment
    # session only the first candidate of each 15 s cluster on a phone carries the session label
    c["imp_t"] = pd.to_numeric(c["imp_t"], errors="coerce")
    c = c.sort_values(["dev", "imp_t"])
    gap = c.groupby("dev")["imp_t"].diff()
    first = gap.isna() | (gap > 15_000)
    c = c[first | ~c["sess"].astype(str).str.startswith("test:")]
    sess = c["sess"].astype(str)
    lab = pd.Series(np.nan, index=c.index)
    lab[sess.str.startswith("test:fall")] = 1.0
    lab[sess.str.startswith("test:no")] = 0.0
    tr = df[(df["k"] == "truth") & df["imp_t"].notna()][["dev", "imp_t", "truth"]].dropna()
    if len(tr) and len(c):
        keyed = {(str(d), str(i)): t for d, i, t in tr.itertuples(index=False)}
        ans = [keyed.get((str(d), str(i))) for d, i in zip(c["dev"], c["imp_t"])]
        ans = pd.Series([1.0 if a == "fall" else 0.0 if a == "no" else np.nan for a in ans], index=c.index)
        lab = lab.fillna(ans)
    c = c[lab.notna()].copy()
    c["label"] = lab[lab.notna()]
    X = np.array([fall_x(r) for r in c.to_dict("records")], dtype=float) if len(c) else np.zeros((0, len(FALL_IN)))
    return c, X, c["label"].to_numpy(float) if len(c) else np.zeros(0)


# ── export ───────────────────────────────────────────────────────────────

def r8(a):
    return np.round(np.asarray(a, float), 8).tolist()


def model_json(kind, inputs, fill, mean, std, layers, note, extra):
    m = {"v": 1, "kind": kind, "made": datetime.now().strftime("%Y-%m-%dT%H:%M:%S"), "note": note,
         "inputs": inputs, "fill": r8(fill), "mean": r8(mean), "std": r8(std), "layers": layers}
    m.update(extra)
    return m


def add_checks(m, X, n=3):
    rows = [x for x in X if not np.isnan(x).any()][:n]
    m["checks"] = [{"x": r8(x), "y": round(run(m, x), 7)} for x in rows]


def save(m, out):
    out.mkdir(parents=True, exist_ok=True)
    p = out / f"{m['kind']}.json"
    p.write_text(json.dumps(m, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    return p


def prior_models():
    w = [0.0] * len(PROX_IN)
    w[PROX_IN.index("ema")] = round(-1.0 / (10 * PRIOR_N), 7)
    prox = {"v": 1, "kind": "prox", "made": "prior-20261030", "note": "prior: path-loss A=-59 n=2.2 (학습 전 자리표시)",
            "inputs": PROX_IN, "fill": [0] * 13, "mean": [0] * 13, "std": [1] * 13,
            "layers": [{"w": [w], "b": [round(PRIOR_A / (10 * PRIOR_N), 7)], "act": "linear"}],
            "out": "log10_m", "clamp": list(CLAMP),
            "checks": [{"x": [-60, -60, -60, -60, -59, -60, 0, -59, 0, -59, -59, 0, 0], "y": -0.0000027},
                       {"x": [-80, -80, -80, -80, -80, -80, 0, -80, 0, -80, -80, 0, 0], "y": 0.9545418}]}
    fall = {"v": 1, "kind": "fall", "made": "prior-20261030", "note": "prior: peak_g · deg · ff_ms (학습 전 자리표시)",
            "inputs": FALL_IN, "fill": [0] * 7, "mean": [0] * 7, "std": [1] * 7,
            "layers": [{"w": [[0.004, 1.2, 0, 0.03, 0, 0, 0]], "b": [-5.0], "act": "sigmoid"}], "out": "p",
            "checks": [{"x": [300, 3.0, 2.8, 60, 10, 1, 1], "y": 0.8320184},
                       {"x": [0, 1.5, 1.5, 0, 10, 6, 1], "y": 0.0391657}]}
    return prox, fall


# ── training ─────────────────────────────────────────────────────────────

def level(m, warn, dang):
    return np.where(m <= dang, 2, np.where(m <= warn, 1, 0))


def blocks(t, size_ms=300_000):   # 5 min: one 2-3 min hold at a distance stays inside one fold
    t = pd.to_numeric(pd.Series(t), errors="coerce").fillna(0).to_numpy(float)
    return (t // size_ms).astype(np.int64)


def train_prox(p, X, y):
    from sklearn.linear_model import Ridge
    from sklearn.model_selection import GroupKFold
    from sklearn.neural_network import MLPRegressor

    fill = np.nanmedian(X, axis=0)
    fill = np.where(np.isnan(fill), 0.0, fill)
    Xf = np.where(np.isnan(X), fill, X)
    mean, std = Xf.mean(axis=0), Xf.std(axis=0)
    std = np.where(std < 1e-9, 1.0, std)
    Z = (Xf - mean) / std
    groups = blocks(p["t"])
    folds = GroupKFold(n_splits=min(5, len(np.unique(groups)))) if len(np.unique(groups)) >= 2 else None

    def mlp():
        return MLPRegressor(hidden_layer_sizes=(16,), activation="relu", alpha=1e-3, max_iter=3000, random_state=0)

    cands = {"ridge": lambda: Ridge(alpha=1.0), "mlp": mlp}
    prior_m = np.clip(10 ** ((PRIOR_A - Xf[:, PROX_IN.index("ema")]) / (10 * PRIOR_N)), *CLAMP)
    truth_m = 10 ** y
    cv = {}
    for name, make in cands.items():
        pred = np.full(len(y), np.nan)
        if folds is None:
            continue
        for tr, te in folds.split(Z, y, groups):
            mdl = make().fit(Z[tr], y[tr])
            pred[te] = mdl.predict(Z[te])
        cv[name] = np.clip(10 ** pred, *CLAMP)
    err = {k: float(np.median(np.abs(v - truth_m))) for k, v in cv.items()}
    err["prior"] = float(np.median(np.abs(prior_m - truth_m)))
    best = min(cv, key=lambda k: err[k]) if cv else "ridge"
    if best == "mlp" and err["mlp"] > err.get("ridge", 1e9) * 0.95:
        best = "ridge"   # the simpler model unless the network is clearly better
    mdl = cands[best]().fit(Z, y)
    if best == "ridge":
        layers = [{"w": [r8(mdl.coef_)], "b": r8([mdl.intercept_]), "act": "linear"}]
    else:
        layers = [{"w": r8(mdl.coefs_[0].T), "b": r8(mdl.intercepts_[0]), "act": "relu"},
                  {"w": r8(mdl.coefs_[1].T), "b": r8(mdl.intercepts_[1]), "act": "linear"}]
    # Level agreement with the UWB level (the record's own warn/danger distances) — BLE rule vs AI (cross-validated)
    warn = pd.to_numeric(p["warn_m"], errors="coerce").to_numpy(float)
    dang = pd.to_numeric(p["dang_m"], errors="coerce").to_numpy(float)
    ok = ~np.isnan(warn) & ~np.isnan(dang)
    lv = {}
    if ok.any():
        uwb_lv = level(truth_m[ok], warn[ok], dang[ok])
        e, w_, d_ = (pd.to_numeric(p[c], errors="coerce").to_numpy(float)[ok] for c in ("ema", "eff_w", "eff_d"))
        rule_lv = np.where(e >= d_, 2, np.where(e >= w_, 1, 0))
        rule_ok = ~np.isnan(e) & ~np.isnan(w_) & ~np.isnan(d_)
        if rule_ok.any():   # both on the same rows so the two numbers compare
            lv["rule"] = float(np.mean(rule_lv[rule_ok] == uwb_lv[rule_ok]))
            if best in cv:
                ai_lv = level(cv[best][ok], warn[ok], dang[ok])
                lv["ai"] = float(np.mean(ai_lv[rule_ok] == uwb_lv[rule_ok]))
    note = (f"학습 {len(y)}건 · {best} · 교차검증 거리 오차 중앙값 {err[best]:.2f}m (경로손실 식 {err['prior']:.2f}m)"
            if best in err and best in cv else f"학습 {len(y)}건 · {best} · 기록이 5분이 안 돼 교차검증 못 함")
    m = model_json("prox", PROX_IN, fill, mean, std, layers, note,
                   {"out": "log10_m", "clamp": list(CLAMP), "n": int(len(y)),
                    "cv": {"median_abs_m": {k: round(v, 3) for k, v in err.items()}, "level_agree_uwb": {k: round(v, 3) for k, v in lv.items()},
                           "chosen": best}})
    add_checks(m, X)
    for chk in m["checks"]:   # the written (rounded) model must reproduce sklearn on the same rows
        z = (np.asarray(chk["x"]) - mean) / std
        assert abs(run(m, chk["x"]) - float(mdl.predict(z.reshape(1, -1))[0])) < 1e-4
    return m


def train_fall(c, X, y):
    from sklearn.linear_model import LogisticRegression
    from sklearn.model_selection import StratifiedKFold

    fill = np.nanmedian(X, axis=0)
    fill = np.where(np.isnan(fill), 0.0, fill)
    Xf = np.where(np.isnan(X), fill, X)
    mean, std = Xf.mean(axis=0), Xf.std(axis=0)
    std = np.where(std < 1e-9, 1.0, std)
    Z = (Xf - mean) / std

    def make():
        return LogisticRegression(C=1.0, class_weight="balanced", max_iter=2000)

    k = int(min(5, np.bincount(y.astype(int)).min()))
    pred = np.full(len(y), np.nan)
    if k >= 2:
        for tr, te in StratifiedKFold(n_splits=k, shuffle=True, random_state=0).split(Z, y):
            pred[te] = make().fit(Z[tr], y[tr]).predict_proba(Z[te])[:, 1]
    mdl = make().fit(Z, y)
    rule = c["pass"].map(jm.yes).to_numpy(bool) if "pass" in c else np.zeros(len(y), bool)
    stat = {}
    if not np.isnan(pred).all():
        ai = pred >= 0.5
        stat["ai"] = {"acc": round(float(np.mean(ai == (y == 1))), 3), "recall": round(float(np.mean(ai[y == 1])), 3),
                      "spec": round(float(np.mean(~ai[y == 0])), 3)}
    stat["rule"] = {"acc": round(float(np.mean(rule == (y == 1))), 3), "recall": round(float(np.mean(rule[y == 1])), 3),
                    "spec": round(float(np.mean(~rule[y == 0])), 3)}
    note = (f"학습 {len(y)}건(넘어짐 {int(y.sum())} · 아님 {int((y == 0).sum())}) · 로지스틱 · "
            f"교차검증 정확도 {stat.get('ai', {}).get('acc', float('nan')):.2f} (지금 규칙 {stat['rule']['acc']:.2f})")
    m = model_json("fall", FALL_IN, fill, mean, std,
                   [{"w": [r8(mdl.coef_[0])], "b": r8(mdl.intercept_), "act": "sigmoid"}], note,
                   {"out": "p", "n": int(len(y)), "cv": stat})
    add_checks(m, X)
    for chk in m["checks"]:
        z = (np.asarray(chk["x"]) - mean) / std
        assert abs(run(m, chk["x"]) - float(mdl.predict_proba(z.reshape(1, -1))[0, 1])) < 1e-4
    return m


def train(export, out):
    df = load(export)
    p, Xp, yp = prox_rows(df)
    c, Xf, yf = fall_rows(df)
    done = []
    if len(yp) >= MIN_PROX:
        done.append(save(train_prox(p, Xp, yp), out))
    else:
        print(f"접근: UWB 거리가 붙은 기록 {len(yp)}건 — {MIN_PROX}건이 안 돼 모델을 바꾸지 않습니다.")
    counts = np.bincount(yf.astype(int), minlength=2) if len(yf) else np.zeros(2, int)
    if counts.min() >= MIN_FALL:
        done.append(save(train_fall(c, Xf, yf), out))
    else:
        print(f"넘어짐: 넘어짐 {counts[1]}건 · 아님 {counts[0]}건 — 둘 다 {MIN_FALL}건이 안 돼 모델을 바꾸지 않습니다.")
    for f in done:
        m = json.loads(f.read_text(encoding="utf-8"))
        print(f"{f.name}: {m['note']}")
        print("  " + json.dumps(m.get("cv", {}), ensure_ascii=False))
    return done


# ── selftest ─────────────────────────────────────────────────────────────

def fake_export(rng):
    prox, fall = {}, {}
    t0 = 1_761_800_000_000
    for i in range(1500):
        d = float(rng.uniform(0.5, 12.0))
        base = -59.0 - 22.0 * math.log10(d) + rng.normal(0, 3.0)
        ring = [int(round(base + rng.normal(0, 2.0))) for _ in range(10)]
        ema = ring[-1]
        prox[f"p{i}"] = {"v": 1, "k": "uwb", "t": t0 + i * 1000, "dev": "A", "uid": "u", "sess": "test:uwb:s1",
                         "rssi": ema + int(rng.integers(-3, 4)), "med": ema, "pre": ema, "kf": ema, "kf_vel": 0.1,
                         "ema": ema, "avg1s": ema, "eff_w": -78, "eff_d": -65,
                         "seq": ";".join(f"{e},0.1" for e in ring), "uwb_m": round(d, 2), "use": "measure",
                         "frame_age_ms": int(rng.integers(0, 1500)), "warn_m": 5.0, "dang_m": 3.0}
    for i in range(40):
        f = i % 2 == 0
        fall[f"c{i}"] = {"v": 1, "k": "cand", "t": t0 + i * 20000, "dev": "A", "uid": "u",
                         "sess": "test:fall:m1" if f else "test:no:m1", "imp_t": t0 + i * 20000 - 12000,
                         "ff_ms": int(rng.integers(150, 400) if f else rng.integers(0, 120)),
                         "peak_g": round(float(rng.uniform(2.8, 4.5) if f else rng.uniform(2.5, 3.2)), 2),
                         "imp_g": 2.5, "deg": round(float(rng.uniform(45, 90) if f else rng.uniform(0, 30)), 1),
                         "post_n": 10, "post_act": int(rng.integers(0, 2) if f else rng.integers(3, 8)),
                         "pre_ok": True, "pass": f}
    return {"wf11": {"judge": {"prox": {"WF11": {"20261030": prox}}, "fall": {"WF11": {"20261030": fall}}}}}


def selftest():
    rng = np.random.default_rng(0)
    for m in prior_models():
        for chk in m["checks"]:
            assert abs(run(m, chk["x"]) - chk["y"]) < 1e-4, (m["kind"], chk)
    with tempfile.TemporaryDirectory() as tmp:
        exp = Path(tmp) / "export.json"
        exp.write_text(json.dumps(fake_export(rng)), encoding="utf-8")
        out = Path(tmp) / "ai"
        files = train(exp, out)
        assert {f.name for f in files} == {"prox.json", "fall.json"}, files
        prox = json.loads((out / "prox.json").read_text(encoding="utf-8"))
        fall = json.loads((out / "fall.json").read_text(encoding="utf-8"))
        assert prox["cv"]["median_abs_m"][prox["cv"]["chosen"]] < 1.5, prox["cv"]
        assert fall["cv"]["ai"]["acc"] >= 0.8, fall["cv"]
        for m in (prox, fall):
            assert len(m["inputs"]) == len(m["mean"]) == len(m["std"]) == len(m["fill"])
            for chk in m["checks"]:
                assert abs(run(m, chk["x"]) - chk["y"]) < 1e-4
        x = prox_x({"rssi": -70, "med": -70, "pre": -70, "kf": -70, "ema": -70, "avg1s": -70, "kf_vel": 0.0,
                    "seq": "-72,0.1;-70,0.2;-68,0.3"})
        assert np.allclose(x[7:], [-70.0, float(np.std([-72, -70, -68])), -72.0, -68.0, 2.0, 0.2]), x
    print("selftest ok")


def main():
    warnings.filterwarnings("ignore", module="sklearn")   # small data: convergence notes are noise here
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("train", help="내보낸 JSON -> assets/ai/prox.json, fall.json")
    t.add_argument("export")
    t.add_argument("--out", default=str(ASSETS))
    pr = sub.add_parser("prior", help="학습 전 자리표시 모델을 씁니다")
    pr.add_argument("--out", default=str(ASSETS))
    sub.add_parser("selftest")
    a = ap.parse_args()
    if a.cmd == "train":
        train(a.export, Path(a.out))
    elif a.cmd == "prior":
        for m in prior_models():
            print(save(m, Path(a.out)))
    else:
        selftest()


if __name__ == "__main__":
    main()
