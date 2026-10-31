"""Train the two small on-device models the SafeAlert app ships in app/src/main/assets/ai/.

    python app_model.py train <export.json>   judge records -> assets/ai/prox.json, fall.json + report
    python app_model.py prior                 placeholder models (path-loss prior, impact prior)
    python app_model.py selftest              synthetic data end to end

prox: BLE values of one peer (latest frame + last 10 s) + both roles + this phone's model -> log10(distance m),
      labelled by the UWB distance in `uwb` records (judge and measure) and, for `sig` rows of a phone without UWB
      (session test:sweep:<place>:<bundle>), by the UWB distance a phone of the other bundle measured within 1 s. fall: impact candidate summary -> fall probability, labelled by the
      experiment session (test:fall / test:no) or the fall question answer.
The model JSON format and the inputs must match AiJudge.kt (SPEC_ai.md sections 1-2): standardize with
mean/std (std 0 -> 1, missing -> fill), then dense layers h = act(W h + b), W as [out][in].
"""
import argparse
import json
import math
import contextlib
import io
import os
import re
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
           "seq_mean", "seq_std", "seq_min", "seq_max", "seq_slope", "seq_vel",
           "me_epj", "me_fk", "peer_epj", "peer_fk"]   # + "mdl:<model>" columns (AiJudge.proxFeatures: same names)
FALL_IN = ["ff_ms", "peak_g", "imp_g", "deg", "post_n", "post_act", "pre_ok"]
CLAMP = (0.3, 30.0)
FRESH_MS = 2000          # rows whose BLE frame is older than this are not the same moment as the UWB distance
PRIOR_A, PRIOR_N = -59.0, 2.2
MIN_PROX, MIN_FALL = 100, 8   # fewer rows (or fewer of either fall class) -> that model is not replaced
MIN_MDL = 50                  # a phone model gets its own input column only with this many training rows
MIN_GROUP = 20                # compare: fewer rows in a pair group / phone model -> "기록 부족"
SWEEP_RE = r"^test:sweep:([^:]+):([^:]+)$"   # place, bundle
EMPTY_MDL = "기종 없음(1.2.27 전 기록)"
PAIRS = ["보행자↔EPJ·카트", "EPJ·카트끼리", "보행자끼리", "지게차 낀 짝"]


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


def prox_f(r):
    """Named inputs of one row — the same names and values as AiJudge.proxFeatures (a cart is an EPJ, a walker has both
    role flags 0). The phone model column "mdl:<model>" exists only when the row has one."""
    base = [jm.fnum(r.get(c)) for c in ("rssi", "med", "pre", "kf", "ema", "avg1s", "kf_vel")]
    base = [np.nan if b is None else b for b in base]
    vals = base + seq_stats(r.get("seq"), base[4], 0.0 if np.isnan(base[6]) else base[6])
    f = dict(zip(PROX_IN, vals))
    role, peer = str(r.get("role")), str(r.get("peer_role"))
    f.update(me_epj=float(role == "EPJ"), me_fk=float(role == "FORKLIFT"),
             peer_epj=float(peer == "EPJ"), peer_fk=float(peer == "FORKLIFT"))
    mdl = r.get("mdl")
    if isinstance(mdl, str) and mdl:
        f["mdl:" + mdl] = 1.0
    return f


def prox_x(r, inputs=None):
    """Values in the model's input order. A name the row does not have: a model column -> 0, anything else -> NaN
    (the app's vec -> fill)."""
    f = prox_f(r)
    return [f.get(n, 0.0 if n.startswith("mdl:") else np.nan) for n in (inputs or PROX_IN)]


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
                        "eff_w", "eff_d", "ema", "peer_role", "role", "peer", "mdl", "uwb_use"])


def pair_group(role, peer_role):
    """The pair kind compare reports by (a cart is an EPJ)."""
    if jm.fork_pair(role, peer_role):
        return PAIRS[3]
    kinds = {str(role), str(peer_role)}
    return PAIRS[0] if kinds == {"WALKER", "EPJ"} else PAIRS[1] if kinds == {"EPJ"} else PAIRS[2] if kinds == {"WALKER"} else "그 밖"


def peer_dev(peer):
    """A record's `peer` -> the other phone's own `dev` (app withSite = "<site>-<id>", the site code may hold '-'):
    "WF11-SAFEALERT_DEVICE_<id>" -> "WF11-<id>", "WF-11-SAFEALERT_DEVICE_<id>" -> "WF-11-<id>"."""
    return re.sub(r"-SAFEALERT_[A-Z]+_", "-", str(peer), count=1)


def bundle_truth(df):
    """`uwb` rows plus `sig` rows (src column: uwb | sig_own | bundle) with a UWB distance as the truth.
    A sig row of a phone in session test:sweep:<place>:<bundle> that has no UWB distance of its own takes the UWB
    distance measured between the same two bundles at that place — by whichever phone, nearest in time within 1 s.
    Phones of the same bundle have no truth for each other. A sig row with a uwb row of the same phone and peer within
    1 s (either side, 1000 ms inclusive) is dropped (the uwb row covers that moment); no uwb row is ever dropped."""
    a = df[df["k"].isin(["uwb", "sig"])].copy()
    for c in ("t", "uwb_m", "frame_age_ms"):
        a[c] = pd.to_numeric(a[c], errors="coerce")
    a["src"] = np.where(a["k"] == "uwb", "uwb", "sig_own")
    a["pair"] = [pair_group(r, q) for r, q in zip(a["role"], a["peer_role"])]
    if a.empty:
        return a
    sess = a["sess"].astype(str).str.extract(SWEEP_RE)
    allsw = df["sess"].astype(str).str.extract(SWEEP_RE)
    allt = pd.to_numeric(df["t"], errors="coerce")
    hit = allsw[0].notna() & allt.notna()
    if hit.any() and sess[0].notna().any():
        # bundle of each phone at each time (any record of a sweep session), then the bundle of each row's peer
        # (nearest within 60 s; none -> no bundle, no truth)
        bt = pd.DataFrame({"key": (allsw[0][hit] + "|" + df["dev"][hit].astype(str)).astype(str),
                           "t": allt[hit].astype("int64"), "bundle": allsw[1][hit].astype(str)}).sort_values("t")
        sw = sess[0].notna() & a["t"].notna()
        left = pd.DataFrame({"idx": a.index[sw], "t": a["t"][sw].astype("int64"),
                             "key": (sess[0][sw] + "|" + a["peer"][sw].map(peer_dev)).astype(str)}).sort_values("t")
        got = pd.merge_asof(left, bt, on="t", by="key", direction="nearest", tolerance=60_000)
        peerb = pd.Series(got["bundle"].to_numpy(), index=got["idx"].to_numpy())
        own, place = sess[1], sess[0]
        pk = pd.Series(np.nan, index=a.index, dtype=object)
        for i, b in peerb.items():
            if isinstance(b, str) and b != own[i]:
                pk[i] = place[i] + "|" + "|".join(sorted([own[i], b]))
        smp = a[(a["k"] == "uwb") & (a["uwb_m"] > 0) & pk.notna() & a["t"].notna()]
        want = a[(a["k"] == "sig") & ~(a["uwb_m"] > 0) & pk.notna() & a["t"].notna()]
        if len(smp) and len(want):
            truth = pd.DataFrame({"pk": pk[smp.index].astype(str).to_numpy(), "t": smp["t"].astype("int64").to_numpy(),
                                  "truth_m": smp["uwb_m"].to_numpy()}).sort_values("t")
            ask = pd.DataFrame({"idx": want.index, "t": want["t"].astype("int64").to_numpy(),
                                "pk": pk[want.index].astype(str).to_numpy()}).sort_values("t")
            got = pd.merge_asof(ask, truth, on="t", by="pk", direction="nearest", tolerance=1000).dropna(subset=["truth_m"])
            a.loc[got["idx"].to_numpy(), "uwb_m"] = got["truth_m"].to_numpy()
            a.loc[got["idx"].to_numpy(), "src"] = "bundle"
    dp = a["dev"].astype(str) + "|" + a["peer"].astype(str)
    s, u = a[(a["k"] == "sig") & a["t"].notna()], a[(a["k"] == "uwb") & a["t"].notna()]
    if len(s) and len(u):
        ask = pd.DataFrame({"idx": s.index, "t": s["t"].astype("int64").to_numpy(), "dp": dp[s.index].to_numpy()}).sort_values("t")
        cover = pd.DataFrame({"t": u["t"].astype("int64").to_numpy(), "dp": dp[u.index].to_numpy(), "u": 1}).sort_values("t")
        near = pd.merge_asof(ask, cover, on="t", by="dp", direction="nearest", tolerance=1000)
        a = a.drop(index=near.loc[near["u"].notna(), "idx"].to_numpy())
    return a


def prox_matrix(p, inputs):
    X = np.array([prox_x(r, inputs) for r in p.to_dict("records")], dtype=float) if len(p) else np.zeros((0, len(inputs)))
    y = np.log10(np.clip(p["uwb_m"].to_numpy(float), *CLAMP)) if len(p) else np.zeros(0)
    return X, y


def prox_rows(df, inputs=None):
    p = bundle_truth(df)
    age = pd.to_numeric(p["frame_age_ms"], errors="coerce")
    p = p[(p["uwb_m"] > 0) & (age.isna() | (age <= FRESH_MS)) & pd.to_numeric(p["ema"], errors="coerce").notna()]
    p = p.sort_values("t").reset_index(drop=True)
    return (p,) + prox_matrix(p, inputs or PROX_IN)


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
    w = [0.0] * len(PROX_IN)   # the role / model inputs start at weight 0
    w[PROX_IN.index("ema")] = round(-1.0 / (10 * PRIOR_N), 7)
    prox = {"v": 1, "kind": "prox", "made": "prior-20261031", "note": "prior: path-loss A=-59 n=2.2 (학습 전 자리표시)",
            "inputs": PROX_IN, "fill": [0] * len(PROX_IN), "mean": [0] * len(PROX_IN), "std": [1] * len(PROX_IN),
            "layers": [{"w": [w], "b": [round(PRIOR_A / (10 * PRIOR_N), 7)], "act": "linear"}],
            "out": "log10_m", "clamp": list(CLAMP),
            "checks": [{"x": [-60, -60, -60, -60, -59, -60, 0, -59, 0, -59, -59, 0, 0, 0, 0, 0, 0], "y": -0.0000027},
                       {"x": [-80, -80, -80, -80, -80, -80, 0, -80, 0, -80, -80, 0, 0, 0, 0, 0, 0], "y": 0.9545418}]}
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


def train_prox(p, X, y, inputs):
    from sklearn.linear_model import Ridge
    from sklearn.model_selection import GroupKFold
    from sklearn.neural_network import MLPRegressor

    fill = np.nanmedian(X, axis=0)
    fill = np.where(np.isnan(fill), 0.0, fill)
    fill[[i for i, n in enumerate(inputs) if n.startswith("mdl:")]] = 0.0   # a model the network has no column for -> 0
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
    m = model_json("prox", inputs, fill, mean, std, layers, note,
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


HELD_NOTE = "기록 시각 1분 묶음 셋 중 하나(t // 60000 % 3 == 2)"


def held(t):
    """Rows kept out of training so `compare` can score the phone model on records it never saw.
    Interleaved 1-minute blocks, because an experiment walks through the distances in order."""
    return (pd.to_numeric(pd.Series(t), errors="coerce").fillna(0).to_numpy(float) // 60_000).astype(np.int64) % 3 == 2


def coverage(p, c):
    """What the labels cover, before training: UWB distances (a model trained only up close says little about the far
    levels) and each fall experiment session per phone (first candidate of each 15 s cluster), so a trial the phone did not
    record and a session name left on after the experiment both show."""
    if len(p):
        cut = pd.cut(p["uwb_m"].astype(float), [0, 1, 3, 5, 8, 12, 15, np.inf],
                     labels=["~1m", "1~3m", "3~5m", "5~8m", "8~12m", "12~15m", "15m~"])
        print("접근 기록 거리별(UWB): " + " · ".join(f"{k} {v}" for k, v in cut.value_counts(sort=False).items()))
        for g in PAIRS + ["그 밖"]:   # per pair kind: how many rows are far (past 3 m) and near each distance that decides a level
            d = p[p["pair"] == g]["uwb_m"].astype(float)
            if len(d) or g != "그 밖":
                near = " · ".join(f"{x}m 근처 {int(((d - x).abs() <= 1).sum())}" for x in (3, 5, 8, 15))
                print(f"  {g}: 전체 {len(d)} · 3m 밖 {int((d > 3).sum())} · {near}")
        n = p["src"].value_counts()
        print(f"  sig 정답: 자기 UWB {int(n.get('sig_own', 0))}건 · 묶음 {int(n.get('bundle', 0))}건")
    for (s, d), g in c[c["sess"].astype(str).str.startswith("test:")].groupby(["sess", "dev"]):
        ms, at = pd.to_numeric(g["t"], errors="coerce"), jm.kst(g["t"])
        print(f"실험 {s} · 폰 {d} · 시도 {len(g)}건 · {at.min()[5:16]} ~ {at.max()[5:16]}"
              + (" — 3시간 넘게 이어짐: 실험 뒤 세션 이름을 비웠는지 확인" if ms.max() - ms.min() > 3 * 3_600_000 else ""))


def train(export, out):
    df = load(export)
    p, _, _ = prox_rows(df)
    c, Xf, yf = fall_rows(df)
    coverage(p, c)
    hp = held(p["t"]) if len(p) else np.zeros(0, bool)
    hf = held(c["t"]) if len(c) else np.zeros(0, bool)
    p = p[~hp].reset_index(drop=True)
    c, Xf, yf = c[~hf], Xf[~hf], yf[~hf]
    seen = p["mdl"].map(lambda v: v if isinstance(v, str) else "").value_counts()
    inputs = PROX_IN + [f"mdl:{m}" for m in sorted(seen.index) if m and seen[m] >= MIN_MDL]   # only phone models with enough rows
    Xp, yp = prox_matrix(p, inputs)
    done = []
    if len(yp) >= MIN_PROX:
        done.append(save(dict(train_prox(p, Xp, yp, inputs), held=HELD_NOTE, n_held=int(hp.sum())), out))
    else:
        print(f"접근: UWB 거리가 붙은 기록 {len(yp)}건 — {MIN_PROX}건이 안 돼 모델을 바꾸지 않습니다.")
    counts = np.bincount(yf.astype(int), minlength=2) if len(yf) else np.zeros(2, int)
    if counts.min() >= MIN_FALL:
        done.append(save(dict(train_fall(c, Xf, yf), held=HELD_NOTE, n_held=int(hf.sum())), out))
    else:
        print(f"넘어짐: 넘어짐 {counts[1]}건 · 아님 {counts[0]}건 — 둘 다 {MIN_FALL}건이 안 돼 모델을 바꾸지 않습니다.")
    for f in done:
        m = json.loads(f.read_text(encoding="utf-8"))
        print(f"{f.name}: {m['note']}")
        print("  " + json.dumps(m.get("cv", {}), ensure_ascii=False))
    return done


# ── compare: the current rule · the phone model · the company AI on the same records ─

def scores(truth, pred, kind):
    """Agreement with the truth on the rows the method answered. catch = the top class (danger / fall) kept as
    such, false = a safe / no-fall row called danger / fall."""
    t, p = np.asarray(truth, float), np.asarray(pred, float)
    ok = ~np.isnan(t) & ~np.isnan(p)
    if not ok.any():
        return None
    t, p, hi = t[ok].astype(int), p[ok].astype(int), 2 if kind == "prox" else 1
    out = {"n": int(ok.sum()), "acc": float(np.mean(t == p))}
    if (t == hi).any():
        out["catch"] = float(np.mean(p[t == hi] == hi))
    if (t == 0).any():
        out["false"] = float(np.mean(p[t == 0] == hi))
    return out


def model_file(models, kind):
    f = models / f"{kind}.json"
    return json.loads(f.read_text(encoding="utf-8")) if f.exists() else None


def ai_answers(kind, rows, out, max_usd, dry):
    """Company AI levels for rows (judge_ml.run_ai: rules file, batches, cost cap, resume), id -> label."""
    ans = jm.run_ai(kind, rows, out, out / f"ai_{kind}.csv", "compare", max_usd, 0, dry)
    if ans.empty or "ai_label" not in ans:
        return {}
    return dict(zip(ans["id"].astype(str), pd.to_numeric(ans["ai_label"], errors="coerce")))


def cross_dist(m, lv, thr, step=0.5, min_n=5):
    """Where a method's level stops reaching thr: UWB distance in 0.5 m cells, the share of rows at level >= thr per cell
    (cells with fewer than 5 rows dropped), nearest first; the first drop from >= 50% to < 50% is placed by straight
    interpolation between the two cell centres. None when there is no such drop."""
    # ponytail: first crossing only, so a noisy curve that dips and recovers reads early; upgrade = a logistic fit
    m, lv = np.asarray(m, float), np.asarray(lv, float)
    ok = ~np.isnan(m) & ~np.isnan(lv)
    m, lv = m[ok], lv[ok]
    cell = np.floor(m / step).astype(int)
    pts = [((k + 0.5) * step, float(np.mean(lv[cell == k] >= thr))) for k in np.unique(cell) if (cell == k).sum() >= min_n]
    for (xa, fa), (xb, fb) in zip(pts, pts[1:]):
        if fa >= 0.5 > fb:
            return xa + (fa - 0.5) / (fa - fb) * (xb - xa)
    return None


def group_report(d, has_phone):
    """compare tables per pair kind and per receiving phone model: counts, agreement, the distance where the current
    rule (BLE dB) and the phone model switch to warning / danger, next to the set distances."""
    pct = lambda v: "-" if v is None else f"{v * 100:.0f}%"
    far = lambda v: "기록 부족" if v is None else f"{v:.1f}m"
    d = d.assign(mdl_g=d["mdl"].map(lambda v: v if isinstance(v, str) and v else EMPTY_MDL))
    head = "| {} | 방법 | 건수 | 맞힘 | 위험을 위험으로 | 안전을 위험으로 | 경고로 바뀌는 거리 | 위험으로 바뀌는 거리 | 설정 거리(경고 · 위험) |"
    lines = []
    for title, col, label, names in (
            ("접근 — 짝별", "pair", "짝", PAIRS + (["그 밖"] if (d["pair"] == "그 밖").any() else [])),
            ("접근 — 받는 기종별", "mdl_g", "받는 기종", sorted(d["mdl_g"].unique()))):
        lines += [f"## {title}", "", head.format(label), "|---|---|---:|---:|---:|---:|---:|---:|---|"]
        for name in names:
            g = d[d[col] == name]
            if len(g) < MIN_GROUP:
                lines.append(f"| {name} | 기록 부족({len(g)}건) | | | | | | | |")
                continue
            setting = f"{g['warn_m'].median():g} · {g['dang_m'].median():g}m"
            for method, col_m in (("지금 규칙", "rule"), ("폰 모델", "phone")):
                sc = scores(g["truth"], g[col_m], "prox") if col_m == "rule" or has_phone else None
                if not sc:
                    lines.append(f"| {name} | {method} | 0 | - | - | - | - | - | {setting} |")
                    continue
                lv = g[col_m].to_numpy(float)
                lines.append(f"| {name} | {method} | {sc['n']} | {pct(sc['acc'])} | {pct(sc.get('catch'))} | {pct(sc.get('false'))} |"
                             f" {far(cross_dist(g['uwb_m'], lv, 1))} | {far(cross_dist(g['uwb_m'], lv, 2))} | {setting} |")
        lines.append("")
    lines += ["- 바뀌는 거리 = UWB 거리 0.5m 칸마다 그 방법이 경고(위험) 이상으로 본 비율이 처음 50% 아래로 내려가는 곳. 설정 거리에 가까울수록 좋다.",
              f"- 기록이 {MIN_GROUP}건 미만인 짝 · 기종은 비교하지 않는다.", ""]
    return lines


def compare(export, out, models, per_level=30, max_usd=5.0, dry=False, no_ai=False):
    df = load(export)
    out.mkdir(parents=True, exist_ok=True)
    pm, fm = model_file(models, "prox"), model_file(models, "fall")
    res = {}

    p, Xp, _ = prox_rows(df, pm["inputs"] if pm else PROX_IN)
    groups = None
    warn = pd.to_numeric(p["warn_m"], errors="coerce").to_numpy(float)
    dang = pd.to_numeric(p["dang_m"], errors="coerce").to_numpy(float)
    ok = ~np.isnan(warn) & ~np.isnan(dang)
    p, Xp, warn, dang = p[ok].reset_index(drop=True), Xp[ok], warn[ok], dang[ok]
    if len(p):
        p["truth"] = level(p["uwb_m"].to_numpy(float), warn, dang)
        e, w, d = (pd.to_numeric(p[c], errors="coerce").to_numpy(float) for c in ("ema", "eff_w", "eff_d"))
        p["rule"] = np.where(np.isnan(e) | np.isnan(w) | np.isnan(d), np.nan, np.where(e >= d, 2, np.where(e >= w, 1, 0)))
        p["phone_m"] = [meters(pm, x) for x in Xp] if pm else np.nan
        p["phone"] = level(p["phone_m"].to_numpy(float), warn, dang) if pm else np.nan
        if pm and pm.get("held"):
            p = p[held(p["t"])]   # only records the phone model never trained on
        whole = {"rule": scores(p["truth"], p["rule"], "prox"), "phone": scores(p["truth"], p["phone"], "prox")}
        groups = p.copy()   # every held row, for the per-pair / per-model tables
        # The same rows for all three: per_level per UWB level, so the paid AI sees each level evenly
        p = pd.concat([g.sample(min(len(g), per_level), random_state=0) for _, g in p.groupby("truth")])
        p["ai"] = np.nan if no_ai else p["id"].astype(str).map(ai_answers("prox", p, out, max_usd, dry))
        res["prox"] = (p, whole)
        jm.write(p[["id", "t", "dev", "peer", "pair", "mdl", "src", "uwb_m", "warn_m", "dang_m", "truth", "rule", "phone_m", "phone", "ai"]],
                 out / "compare_prox.csv")

    c, Xf, yf = fall_rows(df)
    if len(c):
        c["truth"] = yf.astype(int)
        c["rule"] = c["pass"].map(jm.yes).astype(float)
        c["phone_p"] = [run(fm, x) for x in Xf] if fm else np.nan
        c["phone"] = (c["phone_p"] >= 0.5).astype(float) if fm else np.nan
        if fm and fm.get("held"):
            c = c[held(c["t"])].copy()   # only candidates the phone model never trained on
        c["ai"] = np.nan
        if not no_ai:
            g = df[df["grp"] == "fall"].copy()   # the analysis AI's own inputs (acc shape etc.), as judge_ml.load builds them
            g["t"], g["imp_t"], g["dev"] = pd.to_numeric(g["t"], errors="coerce"), pd.to_numeric(g["imp_t"], errors="coerce"), g["dev"].astype(str)
            feats = jm.build_fall(g)
            got = ai_answers("fall", feats[feats["id"].astype(str).isin(set(c["id"].astype(str)))], out, max_usd, dry)
            c["ai"] = c["id"].astype(str).map(got)
        res["fall"] = (c, {})
        jm.write(c[["id", "t", "dev", "sess", "imp_t", "truth", "rule", "phone_p", "phone", "ai"]], out / "compare_fall.csv")

    title = {"prox": ("접근 — 정답 = UWB 로 잰 거리의 단계", "위험을 위험으로", "안전을 위험으로"),
             "fall": ("넘어짐 — 정답 = 실험 세션(넘어짐 / 아님) · 넘어짐 질문 답", "넘어짐을 넘어짐으로", "아님을 넘어짐으로")}
    pct = lambda v: "-" if v is None else f"{v * 100:.0f}%"
    lines = ["# 같은 기록에서 지금 규칙 · 폰 모델 · 회사 AI 비교", "", f"- 기록 파일: {Path(export).name}",
             f"- 폰 모델: prox {pm['made'] if pm else '없음'} · fall {fm['made'] if fm else '없음'}",
             "- 회사 AI: " + ("부르지 않음(--no-ai)" if no_ai else "부르지 않음(--dry-run)" if dry
                             else "ai_rules 지시문, 예시 없이(처음 보는 상태)"),
             "- 평가 기록: " + (f"폰 모델 학습에 쓰지 않은 기록만({HELD_NOTE}). 같은 실험의 다른 시간이라 현장보다 좋게 나올 수 있음"
                              if (pm and pm.get("held")) or (fm and fm.get("held"))
                              else "전부 — 폰 모델이 이 기록으로 학습했다면 폰 모델 수치가 부풀려짐"), ""]
    for kind, (d, whole) in res.items():
        t, a, b = title[kind]
        lines += [f"## {t}", "", f"| 방법 | 건수 | 맞힘 | {a} | {b} |", "|---|---:|---:|---:|---:|"]
        rule = "지금 규칙(블루투스 신호)" if kind == "prox" else "지금 규칙"
        for name, col in ((rule, "rule"), ("폰 모델", "phone"), ("회사 AI", "ai")):
            s = scores(d["truth"], d[col], kind)
            lines.append(f"| {name} | {s['n'] if s else 0} | {pct(s and s['acc'])} | {pct(s and s.get('catch'))} | {pct(s and s.get('false'))} |"
                         if s else f"| {name} | 0 | - | - | - |")
        if whole:
            lines += ["", "전체 기록(표본 전): " + " · ".join(
                f"{n} 맞힘 {pct(s and s['acc'])}({s['n'] if s else 0}건)" for n, s in (("지금 규칙", whole["rule"]), ("폰 모델", whole["phone"])))]
        lines.append("")
        if kind == "prox" and groups is not None:
            lines += group_report(groups, pm is not None)
    rep = out / "compare_report.md"
    rep.write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))
    print(f"-> {rep}")
    return rep


# ── selftest ─────────────────────────────────────────────────────────────

def fake_frame(rng, d):
    """BLE values of one frame at distance d (path loss + noise)."""
    base = -59.0 - 22.0 * math.log10(d) + rng.normal(0, 3.0)
    ring = [int(round(base + rng.normal(0, 2.0))) for _ in range(10)]
    ema = ring[-1]
    return {"rssi": ema + int(rng.integers(-3, 4)), "med": ema, "pre": ema, "kf": ema, "kf_vel": 0.1,
            "ema": ema, "avg1s": ema, "eff_w": -78, "eff_d": -65, "seq": ";".join(f"{e},0.1" for e in ring)}


SWEEP_T1 = 1_761_800_000_000 + 3_000_000   # the sweep scene starts here (after the field-like rows)


def fake_export(rng):
    prox, fall = {}, {}
    t0 = 1_761_800_000_000
    pairs = [("WALKER", "EPJ"), ("EPJ", "EPJ"), ("WALKER", "WALKER"), ("EPJ", "FORKLIFT")]
    for i in range(1500):
        d = float(rng.uniform(0.5, 12.0))
        role, peer_role = pairs[i % 4]
        fk = peer_role == "FORKLIFT"
        mdl = "lg X" if i % 75 == 0 else "samsung SM-A" if i % 2 == 0 else "google Pixel"   # lg X stays under MIN_MDL
        prox[f"p{i}"] = {"v": 1, "k": "uwb", "t": t0 + i * 1000, "dev": "A", "uid": "u", "sess": "test:uwb:s1",
                         **fake_frame(rng, d), "uwb_m": round(d, 2), "use": "measure", "mdl": mdl,
                         "role": role, "peer_role": peer_role, "peer": f"WF11-SAFEALERT_DEVICE_P{i % 5}",
                         "frame_age_ms": int(rng.integers(0, 1500)), "warn_m": 15.0 if fk else 5.0, "dang_m": 8.0 if fk else 3.0}
    # Sweep scene (real id forms): A = UWB phone, B = phone without UWB carried with A (bundle a), C = UWB phone (bundle b).
    # A measures C except s 20-29, where C measures A; B's sig rows get the pair's UWB distance whoever measured it.
    def rec(k, dev, sess, peer, role, prole, s, d, mdl=None, **more):
        r = {"v": 1, "k": k, "t": SWEEP_T1 + s * 1000, "dev": dev, "uid": "u", "sess": sess, "role": role, "peer_role": prole,
             "peer": peer, "warn_m": 5.0, "dang_m": 3.0, **fake_frame(rng, d), **more}
        if mdl:
            r["mdl"] = mdl
        return r
    cs, as_ = "WF11-SAFEALERT_DEVICE_C", "WF11-SAFEALERT_DEVICE_A"
    for s in range(60):
        d = round(2.0 + 0.1 * s, 2)
        if 20 <= s < 30:
            prox[f"uC{s}"] = rec("uwb", "WF11-C", "test:sweep:hall:b", as_, "EPJ", "WALKER", s, d, uwb_m=d, use="measure", frame_age_ms=100)
        else:
            prox[f"uA{s}"] = rec("uwb", "WF11-A", "test:sweep:hall:a", cs, "WALKER", "EPJ", s, d, uwb_m=d, use="judge", frame_age_ms=100)
        prox[f"sA{s}"] = rec("sig", "WF11-A", "test:sweep:hall:a", cs, "WALKER", "EPJ", s, d, frame_age_ms=0)
        prox[f"sB{s}"] = rec("sig", "WF11-B", "test:sweep:hall:a", cs, "WALKER", "EPJ", s, d, "samsung SM-A", frame_age_ms=0)
    for s in range(70, 75):   # nobody measured: no truth
        prox[f"sB{s}"] = rec("sig", "WF11-B", "test:sweep:hall:a", cs, "WALKER", "EPJ", s, 5.0, "samsung SM-A", frame_age_ms=0)
    for s in range(10):   # B next to A (same bundle): no truth
        prox[f"sBA{s}"] = rec("sig", "WF11-B", "test:sweep:hall:a", as_, "WALKER", "WALKER", s, 1.0, "samsung SM-A", frame_age_ms=0)
    for s in range(40, 45):   # a sig row that carries its own UWB distance
        prox[f"sC{s}"] = rec("sig", "WF11-C", "test:sweep:hall:b", as_, "EPJ", "WALKER", s, 6.0, uwb_m=6.0, uwb_use="judge", frame_age_ms=0)
    for i in range(40):
        f = i % 2 == 0
        fall[f"c{i}"] = {"v": 1, "k": "cand", "t": t0 + i * 20000, "dev": "A", "uid": "u",
                         "sess": "test:fall:m1" if f else "test:no:m1", "imp_t": t0 + i * 20000 - 12000,
                         "ff_ms": int(rng.integers(150, 400) if f else rng.integers(0, 120)),
                         "peak_g": round(float(rng.uniform(2.8, 4.5) if f else rng.uniform(2.5, 3.2)), 2),
                         "imp_g": 2.5, "deg": round(float(rng.uniform(45, 90) if f else rng.uniform(0, 30)), 1),
                         "post_n": 10, "post_act": int(rng.integers(0, 2) if f else rng.integers(3, 8)),
                         "pre_ok": True, "pass": f}
    # site code with '-' (WF-11, place dock): F (bundle a, no UWB) and E get D's UWB distance to E
    for s in range(45, 50):
        prox[f"uD{s}"] = rec("uwb", "WF-11-D", "test:sweep:dock:a", "WF-11-SAFEALERT_DEVICE_E", "WALKER", "EPJ", s, 3.0, uwb_m=3.0, use="judge", frame_age_ms=100)
        prox[f"sF{s}"] = rec("sig", "WF-11-F", "test:sweep:dock:a", "WF-11-SAFEALERT_DEVICE_E", "WALKER", "EPJ", s, 3.0, frame_age_ms=0)
        prox[f"sE{s}"] = rec("sig", "WF-11-E", "test:sweep:dock:b", "WF-11-SAFEALERT_DEVICE_D", "EPJ", "WALKER", s, 3.0, frame_age_ms=0)
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
        shown = io.StringIO()
        with contextlib.redirect_stdout(shown):
            files = train(exp, out)
        print(shown.getvalue(), end="")
        assert {f.name for f in files} == {"prox.json", "fall.json"}, files
        cov = shown.getvalue()
        assert "지게차 낀 짝: 전체" in cov and "보행자끼리: 전체" in cov and "sig 정답: 자기 UWB 5건 · 묶음 78건" in cov, cov
        # Bundle truth: B's sig rows (and A's where C measured) get the pair's UWB distance; same bundle / unmeasured get none;
        # a sig row within 1 s of a uwb row of the same phone and peer is dropped; no uwb row is lost
        p_all, _, _ = prox_rows(load(exp))
        assert p_all["src"].value_counts().to_dict() == {"uwb": 1565, "bundle": 78, "sig_own": 5}, p_all["src"].value_counts()
        bun = p_all[p_all["src"] == "bundle"]
        hb = bun[bun["dev"].str.startswith("WF11-")]
        assert set(hb["dev"]) == {"WF11-A", "WF11-B"} and (hb["peer"] == "WF11-SAFEALERT_DEVICE_C").all(), hb[["dev", "peer"]]
        wb = bun[bun["dev"].str.startswith("WF-11-")]   # a site code with '-' still finds the peer's bundle
        assert set(wb["dev"]) == {"WF-11-E", "WF-11-F"} and len(wb) == 10 and np.allclose(wb["uwb_m"], 3.0), wb[["dev", "uwb_m"]]
        bb = bun[bun["dev"] == "WF11-B"]
        assert len(bb) == 60 and np.allclose(bb["uwb_m"], 2.0 + 0.1 * ((bb["t"] - SWEEP_T1) / 1000)), bb[["t", "uwb_m"]]
        assert ((p_all["dev"] == "WF11-A") & (p_all["k"] == "sig")).sum() == 8   # the other 52 sit within 1 s of a uwb row
        assert not ((p_all["dev"] == "WF11-B") & (p_all["peer"] == "WF11-SAFEALERT_DEVICE_A")).any()
        assert not (p_all["t"] > SWEEP_T1 + 65_000).any()
        prox = json.loads((out / "prox.json").read_text(encoding="utf-8"))
        fall = json.loads((out / "fall.json").read_text(encoding="utf-8"))
        assert prox["cv"]["median_abs_m"][prox["cv"]["chosen"]] < 1.5, prox["cv"]
        assert fall["cv"]["ai"]["acc"] >= 0.8, fall["cv"]
        assert len(PROX_IN) == 17 and prox["inputs"][:17] == PROX_IN
        assert set(prox["inputs"][17:]) == {"mdl:google Pixel", "mdl:samsung SM-A"}, prox["inputs"]   # lg X is under MIN_MDL
        assert all(prox["fill"][i] == 0 for i in range(17, len(prox["inputs"]))), prox["fill"]
        assert set(prox_f(p_all.to_dict("records")[0])) - {"mdl:lg X", "mdl:samsung SM-A", "mdl:google Pixel"} == set(PROX_IN)
        for m in (prox, fall):
            assert len(m["inputs"]) == len(m["mean"]) == len(m["std"]) == len(m["fill"])
            for chk in m["checks"]:
                assert abs(run(m, chk["x"]) - chk["y"]) < 1e-4
        x = prox_x({"rssi": -70, "med": -70, "pre": -70, "kf": -70, "ema": -70, "avg1s": -70, "kf_vel": 0.0,
                    "seq": "-72,0.1;-70,0.2;-68,0.3"})
        assert np.allclose(x[7:13], [-70.0, float(np.std([-72, -70, -68])), -72.0, -68.0, 2.0, 0.2]), x
        assert x[13:] == [0.0, 0.0, 0.0, 0.0], x   # no roles: walker both ways
        r = {"role": "EPJ", "peer_role": "FORKLIFT", "mdl": "a b", "ema": -70}
        assert prox_x(r, PROX_IN + ["mdl:a b", "mdl:z"])[13:] == [1.0, 0.0, 0.0, 1.0, 1.0, 0.0]
        assert np.isnan(prox_x({}, ["rssi"])[0]) and prox_x({}, ["mdl:z"]) == [0.0]
        assert cross_dist([1, 2, 3] * 6, [0] * 18, 2, step=1.0, min_n=3) is None   # never at the level: no drop to place
        near = np.repeat([0.75, 1.25, 1.75, 2.25], 6)   # danger-share 100%, 100%, 0%, 0%: crosses between cells 1.25 and 1.75
        assert abs(cross_dist(near, np.repeat([2, 2, 0, 0], 6), 2) - 1.5) < 1e-9
        before = os.environ.get("JUDGE_AI_STYLE")
        os.environ["JUDGE_AI_STYLE"] = "fake"   # judge_ml's offline stand-in: no network, no cost
        try:
            rep = compare(exp, Path(tmp) / "cmp", out, per_level=10)
        finally:
            if before is None:
                os.environ.pop("JUDGE_AI_STYLE", None)
            else:
                os.environ["JUDGE_AI_STYLE"] = before
        text = rep.read_text(encoding="utf-8")
        assert "| 회사 AI |" in text and "| 폰 모델 |" in text and "| 지금 규칙 |" in text and "학습에 쓰지 않은" in text, text
        assert "## 접근 — 짝별" in text and "## 접근 — 받는 기종별" in text and "바뀌는 거리" in text, text
        assert all(f"| {g} | 지금 규칙 |" in text for g in PAIRS) and "| lg X | 기록 부족(" in text, text
        cp = jm.read(Path(tmp) / "cmp" / "compare_prox.csv")
        assert 0 < len(cp) <= 30 and cp["phone"].notna().all() and pd.to_numeric(cp["ai"], errors="coerce").notna().any(), cp
        cf = jm.read(Path(tmp) / "cmp" / "compare_fall.csv")
        assert 0 < len(cf) < 40 and pd.to_numeric(cf["ai"], errors="coerce").notna().any(), cf
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
    cp = sub.add_parser("compare", help="같은 기록에서 지금 규칙 · 폰 모델 · 회사 AI 비교표")
    cp.add_argument("export")
    cp.add_argument("--out", default=str(HERE / "data" / "compare"))
    cp.add_argument("--models", default=str(ASSETS), help="폰 모델 폴더(prox.json · fall.json)")
    cp.add_argument("--per-level", type=int, default=30, help="접근 단계(안전 · 경고 · 위험)마다 비교할 기록 수")
    cp.add_argument("--max-usd", type=float, default=5.0, help="회사 AI 비용 상한(접근 · 넘어짐 각각, 달러)")
    cp.add_argument("--dry-run", action="store_true", help="회사 AI 를 부르지 않고 건수 · 대략 비용만 보기")
    cp.add_argument("--no-ai", action="store_true", help="회사 AI 없이 지금 규칙 · 폰 모델만")
    sub.add_parser("selftest")
    a = ap.parse_args()
    if a.cmd == "train":
        train(a.export, Path(a.out))
    elif a.cmd == "compare":
        compare(a.export, Path(a.out), Path(a.models), a.per_level, a.max_usd, a.dry_run, a.no_ai)
    elif a.cmd == "prior":
        for m in prior_models():
            print(save(m, Path(a.out)))
    else:
        selftest()


if __name__ == "__main__":
    main()
