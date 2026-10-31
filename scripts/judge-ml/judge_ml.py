#!/usr/bin/env python3
"""SafeAlert judgment-data analysis: an AI API reads the measured values, gives first-pass labels,
learns from human-checked answers and judges - with training data and real data kept apart.

Input: a JSON export from the Firebase console - the wf11/judge node or the whole database.
Record layout: docs/judge-data/README.md. Work files go to ./data (--data to change).

    python judge_ml.py load export.json             records -> data/fall.csv, data/prox.csv
    python judge_ml.py split [--real-from 20261124] by date: data/train/, data/real/ (real is locked)
    python judge_ml.py ai-check --kind fall         one small call: is the AI API set up? (cost shown)
    python judge_ml.py ai-label                     AI first-pass labels for train falls
    python judge_ml.py label [--by ai]              first-pass labels -> data/review_<kind>.csv for people
      (people fill label_final in data/review_<kind>.csv)
    python judge_ml.py relabel                      -> data/train/<kind>_v2.csv + agreement per source
    python judge_ml.py learn --kind fall            human-checked examples the AI learns from
    python judge_ml.py ai-judge --kind fall --on train   AI vs people on train (examples left out)
    python judge_ml.py real-sheet                   blank truth sheet for real falls
    python judge_ml.py ai-judge --kind fall --on real    AI judges real data (once)
    python judge_ml.py eval --kind fall             real data: AI and the current app rule vs the truth
    python judge_ml.py train --kind fall            (optional) a plain local model to compare with
    python judge_ml.py selftest                     the whole pipeline on synthetic data, stand-in AI

The split comes first so that rules, relabeling, examples and training only ever see train. Real
data gets its truth without looking at rule, AI or model output and is used for the final
comparison only: tuning on the data you evaluate on is how confirmation bias gets in.

AI settings are environment variables (README): JUDGE_AI_STYLE anthropic | bedrock | openai,
JUDGE_AI_MODEL, JUDGE_AI_KEY, JUDGE_AI_URL ... Every AI command stops at --max-usd.
"""
import argparse
import hashlib
import json
import math
import os
import random
import re
import sys
import tempfile
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd

UWB_PAIR = (5.0, 3.0)    # (warn, danger) m - DevSettings DEFAULT_UWB_PAIR_WARN/DANGER_METERS
UWB_FORK = (15.0, 8.0)   # forklift pairs - DevSettings DEFAULT_UWB_FORKLIFT_WARN/DANGER_METERS
WATCH_MS = 300_000       # LoneWorkerLogic.ACCIDENT_WATCH_MS: one accident suspicion
# A person's answer after a danger alert (k=dist): metre bucket -> level by pair type. A missing bucket straddles
# a threshold for that pair (gt5 = the single "over 5 m" answer before 1.2.27: for forklift pairs, which warn at
# 15 m and danger at 8 m, it says nothing, so it is not listed there).
DIST_LEVEL = {False: {"lt1": 2, "1to3": 2, "3to5": 1, "5to8": 0, "8to12": 0, "12to15": 0, "gt15": 0, "gt5": 0},
              True: {"lt1": 2, "1to3": 2, "3to5": 2, "5to8": 2, "8to12": 1, "12to15": 1, "gt15": 0}}
DIST_FRESH_MS = (30_000, 120_000)   # tapped within 30 s -> high (the app asks only that long), 2 min -> mid, later -> low
ROLES = {"WALKER": 0, "EPJ": 1, "FORKLIFT": 2}   # BleConstants.categoryName; anything else -> -1
ACC_X = ["acc_min_g", "acc_max_g", "acc_pre_std", "acc_post_std", "acc_post_tilt"]
FEATS = {
    "fall": ["ff_ms", "peak_g", "imp_g", "deg", "post_n", "post_act", "pre_ok"] + ACC_X,
    "prox": ["rssi", "med", "pre", "kf", "kf_vel", "ema", "avg1s",
             "off_pl", "off_bc", "off_gg", "echo", "eff_w", "eff_d", "role_c", "peer_c"],
}
# What the AI may read: measured values only. Truth (truth, uwb_m, warn_m, dang_m, label_*) and the app's own
# decisions (pass, why, take, opened, sos, close_why, level, prev, rule_level) stay out so it cannot copy them.
AI_FIELDS = {
    "fall": ["id", "role", "ff_ms", "peak_g", "imp_g", "deg", "post_n", "post_act"] + ACC_X
            + ["acc_mag5", "zone_in", "charging"],
    "prox": ["id", "role", "peer_role", "rssi", "med", "pre", "kf", "kf_vel", "ema", "avg1s",
             "off_pl", "off_bc", "off_gg", "echo", "eff_w", "eff_d", "seq"],
}
CLASSES = {"fall": [0, 1], "prox": [0, 1, 2]}
NAMES = {"fall": {0: "0 아님", 1: "1 넘어짐"}, "prox": {0: "0 안전", 1: "1 경고", 2: "2 위험"}}
ANSWER = {"fall": "label", "prox": "level"}
TEXT = {c: str for c in ("id", "dev", "day", "sess", "why", "close_why", "truth", "peer", "take", "role",
                         "peer_role", "rule", "conf", "label_final", "memo", "ai_conf", "ai_why", "sys",
                         "acc_mag5", "seq", "floor", "proc", "zone", "use", "uwb_use", "ans", "k")}
REVIEW = {
    "fall": ["id", "dev", "time", "zone", "ff_ms", "peak_g", "deg", "post_act", "pass", "opened", "sos", "close_why"],
    "prox": ["id", "dev", "peer", "time", "role", "peer_role", "uwb_m", "ans", "after_ms", "ema", "eff_w", "eff_d",
             "rule_level"],
}
# USD per million tokens (input, output, cache read) - Anthropic first-party list prices, 2026-10.
# Bedrock and other providers bill differently: set JUDGE_AI_PRICE="in,out,cache_read" for them.
PRICES = {
    "claude-opus-5-5": (4.0, 20.0, 0.20),
    "claude-sonnet-5-5": (2.0, 10.0, 0.20),
    "claude-haiku-5-5": (0.10, 0.50, 0.01),
    "claude-fable-5-1": (10.0, 50.0, 0.25),
}
KST = pd.Timedelta(hours=9)


# ── small helpers ─────────────────────────────────────────────────────────

def read(p):
    """A CSV written here (UTF-8 with BOM) or re-saved by Excel (may be CP949); missing -> empty."""
    p = Path(p)
    if not p.exists():
        return pd.DataFrame()
    for enc in ("utf-8-sig", "cp949"):
        try:
            return pd.read_csv(p, dtype=TEXT, encoding=enc)
        except UnicodeDecodeError:
            continue
        except pd.errors.EmptyDataError:
            return pd.DataFrame()
    sys.exit(f"{p}: 글자를 읽지 못했습니다. 엑셀에서 'CSV UTF-8' 형식으로 다시 저장하세요.")


def write(df, p):
    p = Path(p)
    p.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(p, index=False, encoding="utf-8-sig")   # BOM so Excel shows Korean
    return p


def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest() if Path(p).exists() else ""


def yes(v):
    return str(v).strip().lower() in ("true", "1", "1.0")


def flag(s):
    """Boolean-ish column -> 1.0 / 0.0, blanks stay NaN."""
    return s.map(lambda v: np.nan if pd.isna(v) else float(yes(v)))


def fnum(v):
    """float, or None for blanks and junk."""
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return None if math.isnan(f) else f


def need(df, names):
    """Copy with the named columns present (NaN when absent)."""
    df = df.copy()
    for c in names:
        if c not in df:
            df[c] = np.nan
    return df


def kst(t):
    return (pd.to_datetime(pd.to_numeric(t, errors="coerce"), unit="ms") + KST).dt.strftime("%Y-%m-%d %H:%M:%S")


def items(x):
    """Firebase exports a node with dense integer keys as a list."""
    if isinstance(x, dict):
        return x.items()
    if isinstance(x, list):
        return ((str(i), v) for i, v in enumerate(x) if v is not None)
    return ()


# ── load ─────────────────────────────────────────────────────────────────

def find_judge(o):
    """The judge node, whether the export is that node itself or the whole database."""
    if not isinstance(o, dict):
        return None
    if isinstance(o.get("judge"), dict):
        return o["judge"]
    if o and set(o) <= {"fall", "prox"}:
        return o
    for v in o.values():
        hit = find_judge(v)
        if hit is not None:
            return hit
    return None


def flatten(judge):
    """judge/<fall|prox>/<site>/<yyyyMMdd>/<id> -> one row per record."""
    rows = []
    for grp, sites in items(judge):
        for _, days in items(sites):
            for day, recs in items(days):
                for rid, r in items(recs):
                    if isinstance(r, dict):
                        rows.append({**r, "grp": grp, "day": str(day), "id": rid})
    return pd.DataFrame(rows)


def acc_features(acc, t0, dt, imp_t):
    """Features from the raw window: "x,y,z;x,y,z;..." in milli-g from acc_t0 (same clock as imp_t), one
    sample every acc_dt ms (20 if absent). acc_mag5 = magnitude in G at 5 Hz, for the AI to read the shape."""
    out = dict.fromkeys(ACC_X + ["acc_mag5"], np.nan)
    t0, imp_t, dt = fnum(t0), fnum(imp_t), fnum(dt) or 20.0
    if not isinstance(acc, str) or not acc or t0 is None or imp_t is None:
        return out
    try:
        a = np.array([[float(v) for v in s.split(",")] for s in acc.split(";") if s], dtype=float) / 1000.0
    except ValueError:
        return out
    if a.ndim != 2 or a.shape[1] != 3:
        return out
    t = t0 + np.arange(len(a)) * dt
    mag = np.linalg.norm(a, axis=1)

    def win(lo, hi):
        return (t >= imp_t + lo) & (t < imp_t + hi)

    # Same windows as MotionAnalyzer: free fall within 1 s before impact, posture 2-12 s after it
    pre, ff, hit, post = win(-3000, -1000), win(-1000, 0), win(0, 1000), win(2000, 12000)
    if ff.any():
        out["acc_min_g"] = mag[ff].min()
    if hit.any():
        out["acc_max_g"] = mag[hit].max()
    if pre.sum() > 1:
        out["acc_pre_std"] = mag[pre].std()
    if post.sum() > 1:
        out["acc_post_std"] = mag[post].std()
    if pre.any() and post.any():
        u, v = a[pre].mean(axis=0), a[post].mean(axis=0)
        n = np.linalg.norm(u) * np.linalg.norm(v)
        if n > 0:
            out["acc_post_tilt"] = math.degrees(math.acos(max(-1.0, min(1.0, float(u @ v) / n))))
    step = max(1, int(round(200.0 / dt)))
    out["acc_mag5"] = ",".join(f"{m:.1f}" for m in mag[::step])
    return out


TAKE = ["take", "zone_in", "charging", "plug_ago_ms", "unplug_ago_ms"]
EPISODE = ["from", "to", "trig", "why", "truth"]


def build_fall(df):
    """One row per impact candidate (k=cand) with the app's take (k=fall, joined on dev + imp_t), the
    outcome of the check episode (k=mode) its suspicion led to, and answers given afterwards (k=truth)."""
    keys = ["dev", "imp_t"]
    df = need(df, TAKE + EPISODE + ["acc", "acc_t0", "acc_dt"])
    out = df[df["k"] == "cand"].drop(columns=TAKE + ["from", "to", "trig", "truth"])   # keeps cand's own why
    if out.empty:
        return out
    took = df[(df["k"] == "fall") & df["imp_t"].notna()]   # a blank key would match every other blank key
    out = out.merge(took[keys + TAKE].drop_duplicates(keys), on=keys, how="left")
    ep = df[df["k"].isin(["mode", "truth"]) & df["imp_t"].notna()].sort_values("t")
    if len(ep):
        ep = ep.assign(opened=(ep["to"] == "CHECKING") & (ep["trig"] == "fall"), sos=ep["to"] == "SOS",
                       close_why=ep["why"].where((ep["k"] == "mode") & (ep["to"] == "WATCHING")))
        res = ep.groupby(keys).agg(opened=("opened", "max"), sos=("sos", "max"),
                                   close_why=("close_why", "last"), truth=("truth", "last"))
        # An episode carries the last impact of its suspicion (impactAt); every impact the app took within
        # WATCH_MS before it belongs to that suspicion and shares the outcome
        took_in = out[(out["take"] == "watch") & out["imp_t"].notna()][keys].sort_values("imp_t").reset_index()
        got = pd.merge_asof(took_in, res.reset_index().sort_values("imp_t"), on="imp_t", by="dev",
                            direction="forward", tolerance=float(WATCH_MS))
        out = out.join(got.set_index("index")[["opened", "sos", "close_why", "truth"]])
    out = need(out, ["opened", "sos", "close_why", "truth"])
    for c in ("pre_ok", "pass", "zone_in", "charging", "opened", "sos"):
        if c in out:
            out[c] = flag(out[c])
    feats = pd.DataFrame([acc_features(a, t0, dt, it) for a, t0, dt, it
                          in zip(out["acc"], out["acc_t0"], out["acc_dt"], out["imp_t"])], index=out.index)
    return pd.concat([out.drop(columns=["acc"]), feats], axis=1)   # the raw window stays in the export JSON


def build_prox(df):
    """Proximity records (k=level | k=uwb) with role codes and the app's plain threshold level."""
    p = need(df[df["k"].isin(["level", "uwb", "dist"])], ["role", "peer_role", "ema", "eff_w", "eff_d"])
    if p.empty:
        return p
    p["role_c"] = p["role"].map(ROLES).fillna(-1)
    p["peer_c"] = p["peer_role"].map(ROLES).fillna(-1)
    e, w, d = (pd.to_numeric(p[c], errors="coerce") for c in ("ema", "eff_w", "eff_d"))
    # calcLevelWithHysteresis without its hysteresis and the gates around it: pEma against the offset thresholds
    # The BLE level is judged for every frame, so it holds for use=measure UWB samples too (only their UWB
    # distance stays out of the app). A sample with frame_age_ms carries no frame: ema empty -> no level.
    p["rule_level"] = np.where(e.isna() | w.isna() | d.isna(), np.nan, np.select([e >= d, e >= w], [2.0, 1.0], 0.0))
    return p


def load(export, data):
    judge = find_judge(json.loads(Path(export).read_text(encoding="utf-8")))
    if not judge:
        sys.exit("내보낸 JSON 에 judge 노드가 없습니다. Firebase 콘솔에서 wf11/judge 를 내보내세요.")
    df = flatten(judge)
    if df.empty:
        sys.exit("judge 노드에 기록이 없습니다.")
    df = need(df, ["k", "t", "dev", "imp_t", "sess"])
    for c in ("t", "imp_t"):
        df[c] = pd.to_numeric(df[c], errors="coerce")
    df["dev"] = df["dev"].astype(str)
    fall, prox = build_fall(df[df["grp"] == "fall"]), build_prox(df[df["grp"] == "prox"])
    write(fall, data / "fall.csv")
    write(prox, data / "prox.csv")
    print(f"낙상 후보 {len(fall)}건, 접근 기록 {len(prox)}건 -> {data}")


# ── split ────────────────────────────────────────────────────────────────

def split(data, real_from=None, ratio=0.3):
    """Days before the cut are train, the cut and later are real. The cut is kept in split.json and
    reused, so newly loaded days land in real until a new cut is given on purpose."""
    man_p = data / "split.json"
    man = json.loads(man_p.read_text(encoding="utf-8")) if man_p.exists() else {}
    frames = {k: read(data / f"{k}.csv") for k in CLASSES}
    days = sorted({d for f in frames.values() if not f.empty for d in f["day"].dropna()})
    cut = real_from or man.get("cut")
    if not cut:
        if len(days) < 2:
            sys.exit("날짜가 하루뿐이라 나눌 수 없습니다. 더 모으거나 --real-from 으로 실제 데이터 시작일을 주세요.")
        cut = days[max(1, int(len(days) * (1 - ratio)))]
    rows, hashes = {}, {}
    for k, f in frames.items():
        for stale in (data / "train" / f"{k}_v1.csv", data / "train" / f"{k}_v2.csv"):
            stale.unlink(missing_ok=True)   # built from the old split; label / relabel make them again
        if f.empty:   # nothing of this kind now: no leftovers from an earlier export either
            for p in (data / "train" / f"{k}.csv", data / "real" / f"{k}.csv"):
                p.unlink(missing_ok=True)
            continue
        tr, real = f[f["day"] < cut], f[f["day"] >= cut]
        write(tr, data / "train" / f"{k}.csv")
        hashes[k] = sha(write(real, data / "real" / f"{k}.csv"))
        rows[k] = {"train": len(tr), "real": len(real)}
    runs = man.get("eval_runs", {}) if man.get("cut") == cut else {}
    man_p.write_text(json.dumps({"cut": cut, "made": datetime.now().isoformat(timespec="seconds"), "rows": rows,
                                 "sha256": hashes, "eval_runs": runs}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"실제 데이터 = {cut} 부터. " + ", ".join(f"{k}: 학습 {v['train']} / 실제 {v['real']}" for k, v in rows.items()))


# ── AI API ───────────────────────────────────────────────────────────────

class AiError(Exception):
    pass


def ai_conf():
    """Settings read at call time from the environment (keys are never printed)."""
    e = os.environ.get
    style = (e("JUDGE_AI_STYLE") or "anthropic").strip().lower()
    model = (e("JUDGE_AI_MODEL") or {"anthropic": "claude-opus-5-5",
                                     "bedrock": "anthropic.claude-opus-5-5"}.get(style, "")).strip()
    try:
        headers = json.loads(e("JUDGE_AI_HEADERS") or "{}")
    except ValueError:
        raise AiError("JUDGE_AI_HEADERS 는 JSON 이어야 합니다. 예: {\"X-Team\": \"wf11\"}")
    return {"style": style, "model": model, "key": e("JUDGE_AI_KEY") or "", "url": e("JUDGE_AI_URL") or "",
            "auth": (e("JUDGE_AI_AUTH") or "x-api-key").lower(), "region": e("JUDGE_AI_REGION") or "",
            "effort": e("JUDGE_AI_EFFORT") or "medium", "schema": (e("JUDGE_AI_SCHEMA") or "on").lower() != "off",
            "fallback": (e("JUDGE_AI_FALLBACK") or "on").lower() != "off",   # off for gateways that reject the beta
            "batch": max(1, int(e("JUDGE_AI_BATCH") or 20)), "headers": headers, "price": e("JUDGE_AI_PRICE") or ""}


def price_of(c):
    """(input, output, cache read) USD per MTok, or None when unknown."""
    if c["price"]:
        try:
            p = [float(v) for v in c["price"].split(",")]
            return p[0], p[1], p[2] if len(p) > 2 else p[0] * 0.1
        except (ValueError, IndexError):
            raise AiError("JUDGE_AI_PRICE 는 '입력,출력[,캐시읽기]' (100만 토큰당 달러) 형식입니다")
    return PRICES.get(c["model"].removeprefix("anthropic.")) if c["style"] == "anthropic" else None


def usd(u, p):
    """Cost of one call; cache writes bill at 1.25x input (5-minute cache)."""
    if not p:
        return 0.0
    return (u["in"] * p[0] + u["cache_write"] * p[0] * 1.25 + u["cache_read"] * p[2] + u["out"] * p[1]) / 1e6


def answer_schema(kind):
    key, vals = ANSWER[kind], [str(c) for c in CLASSES[kind]] + ["unknown"]
    item = {"type": "object", "additionalProperties": False, "required": ["id", key, "conf", "why"],
            "properties": {"id": {"type": "string"}, key: {"type": "string", "enum": vals},
                           "conf": {"type": "string", "enum": ["high", "low"]}, "why": {"type": "string"}}}
    return {"type": "object", "additionalProperties": False, "required": ["answers"],
            "properties": {"answers": {"type": "array", "items": item}}}


def call_anthropic(c, system, user, kind):
    try:
        import anthropic
    except ImportError:
        raise AiError("anthropic 패키지가 없습니다: pip install anthropic")
    if c["style"] == "bedrock":
        client = anthropic.AnthropicBedrockMantle(aws_region=c["region"] or None)
    else:
        kw = {"base_url": c["url"]} if c["url"] else {}
        if c["key"]:
            kw["auth_token" if c["auth"] == "bearer" else "api_key"] = c["key"]
        if c["headers"]:
            kw["default_headers"] = c["headers"]
        client = anthropic.Anthropic(**kw)
    out_cfg = {"effort": c["effort"]}
    if c["schema"]:
        out_cfg["format"] = {"type": "json_schema", "schema": answer_schema(kind)}
    params = dict(model=c["model"], max_tokens=16000, output_config=out_cfg,
                  # Rules + examples are the same on every call of a run: cache them
                  system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
                  messages=[{"role": "user", "content": user}])
    try:
        if c["style"] == "anthropic" and c["fallback"]:   # a declined request is re-run on another model server-side
            r = client.beta.messages.create(betas=["server-side-fallback-2026-07-01"], fallbacks="default", **params)
        else:
            r = client.messages.create(**params)
    except anthropic.APIStatusError as e:
        raise AiError(f"AI API 오류 {e.status_code}: {e.message}")
    except anthropic.APIConnectionError:
        raise AiError("AI API 에 연결하지 못했습니다(주소 · 네트워크 · 프록시 확인)")
    u = r.usage
    usage = {"in": u.input_tokens or 0, "out": u.output_tokens or 0,
             "cache_read": getattr(u, "cache_read_input_tokens", 0) or 0,
             "cache_write": getattr(u, "cache_creation_input_tokens", 0) or 0}
    if r.stop_reason == "refusal":
        return "", usage
    if r.stop_reason == "max_tokens":
        raise AiError("답이 max_tokens 에서 잘렸습니다. JUDGE_AI_BATCH 를 줄이세요")
    return next((b.text for b in r.content if b.type == "text"), ""), usage


def call_openai(c, system, user):
    """OpenAI-compatible /chat/completions (non-Claude providers or company gateways)."""
    if not c["url"]:
        raise AiError("JUDGE_AI_URL 이 필요합니다(예: https://<회사 주소>/v1)")
    headers = {"Content-Type": "application/json", **c["headers"]}
    if c["key"]:
        headers["Authorization"] = f"Bearer {c['key']}"
    body = {"model": c["model"], "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}
    req = urllib.request.Request(c["url"].rstrip("/") + "/chat/completions", data=json.dumps(body).encode("utf-8"),
                                 headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=600) as resp:
            r = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise AiError(f"AI API 오류 {e.code}: {e.read()[:300].decode('utf-8', 'replace')}")
    except urllib.error.URLError as e:
        raise AiError(f"AI API 에 연결하지 못했습니다: {e.reason}")
    u = r.get("usage") or {}
    cached = (u.get("prompt_tokens_details") or {}).get("cached_tokens", 0) or 0
    return (r["choices"][0]["message"].get("content") or ""), {
        "in": (u.get("prompt_tokens") or 0) - cached, "out": u.get("completion_tokens") or 0,
        "cache_read": cached, "cache_write": 0}


def fake_ai(user, kind):
    """Stand-in AI for selftest: plain thresholds on the same inputs a real model gets."""
    out = []
    for line in user.splitlines()[1:]:
        r = json.loads(line)
        if kind == "fall":
            deg, act, peak = fnum(r.get("deg")), fnum(r.get("post_act")), fnum(r.get("peak_g")) or 0
            if deg is not None and deg >= 60 and act is not None and act <= 1 and peak >= 3.5:
                v, conf = "1", "high"
            elif (act is not None and act >= 3) or (deg is not None and deg < 30):
                v, conf = "0", "high"
            else:
                v, conf = "unknown", "low"
        else:
            e, w, d = (fnum(r.get(k)) for k in ("ema", "eff_w", "eff_d"))
            v = "unknown" if None in (e, w, d) else "2" if e >= d else "1" if e >= w else "0"
            conf = "high"
        out.append({"id": r["id"], ANSWER[kind]: v, "conf": conf, "why": "selftest"})
    return json.dumps({"answers": out}, ensure_ascii=False)


def ai_call(system, user, kind):
    c = ai_conf()
    if c["style"] == "fake":
        return fake_ai(user, kind), {"in": len(user) // 4, "out": 40 * user.count("\n"),
                                     "cache_read": len(system) // 4, "cache_write": 0}
    if c["style"] in ("anthropic", "bedrock"):
        return call_anthropic(c, system, user, kind)
    if c["style"] == "openai":
        return call_openai(c, system, user)
    raise AiError(f"JUDGE_AI_STYLE={c['style']}: anthropic · bedrock · openai 중 하나여야 합니다")


def ai_input(kind, r):
    """One record as the AI sees it: measured values only, short numbers."""
    d = {}
    for f in AI_FIELDS[kind]:
        v = r.get(f)
        if v is None or (isinstance(v, float) and math.isnan(v)) or (isinstance(v, str) and not v):
            continue
        if isinstance(v, (bool, np.bool_)):
            v = int(v)
        elif isinstance(v, (int, float, np.integer, np.floating)):
            v = round(float(v), 2)
            v = int(v) if v.is_integer() else v
        d[f] = v
    return d


def ai_system(kind, data):
    """Rules file + the examples learn() wrote. Fixed order, so the prefix caches across calls."""
    text = Path(__file__).with_name(f"ai_rules_{kind}.md").read_text(encoding="utf-8")
    ex = data / f"ai_examples_{kind}.jsonl"
    if ex.exists() and ex.read_text(encoding="utf-8").strip():
        text += "\n\n## 예시 (사람이 확인한 정답, 입력 → 답)\n\n" + ex.read_text(encoding="utf-8")
    return text


def ai_user(kind, rows):
    n = len(rows)
    return f"아래 기록 {n}건을 지시문대로 판단해 answers 에 {n}개 모두 답하라. 한 줄 = 기록 한 건.\n" + "\n".join(
        json.dumps(ai_input(kind, r), ensure_ascii=False) for r in rows)


def parse_answers(text, kind, ids):
    """{id: (label | None, conf, why)} from {"answers": [...]} (or a bare array); None if unreadable."""
    s, e = text.find("{"), text.rfind("}")
    try:
        got = json.loads(text[s:e + 1])["answers"] if s >= 0 else None
    except (ValueError, KeyError, TypeError):
        got = None
    if got is None:
        try:
            got = json.loads(text[text.find("["):text.rfind("]") + 1])
        except ValueError:
            return None
    out, valid = {}, {str(c) for c in CLASSES[kind]}
    for it in got if isinstance(got, list) else []:
        if not isinstance(it, dict) or str(it.get("id")) not in ids:
            continue
        v = str(it.get(ANSWER[kind], "")).strip()
        conf = it.get("conf") if it.get("conf") in ("high", "low") else "low"
        out[str(it["id"])] = (int(v) if v in valid else None, conf, str(it.get("why", ""))[:200])
    return out


def run_ai(kind, df, data, out_p, cmd, max_usd, limit=0, dry=False):
    """AI answers for df's rows, batch by batch, saved after every batch so a stop keeps what was paid for.
    Rows already answered under the same rules + examples (sys hash) are not asked again."""
    c, system = ai_conf(), ai_system(kind, data)
    key = hashlib.sha256(system.encode("utf-8")).hexdigest()[:12]
    old = read(out_p)
    keep = old[old["sys"] == key] if "sys" in old else old.iloc[0:0]
    todo = df[~df["id"].isin(set(keep["id"]) if "id" in keep else set())]
    if limit:
        todo = todo.head(limit)
    recs = todo.to_dict("records")
    batches = [recs[i:i + c["batch"]] for i in range(0, len(recs), c["batch"])]
    p = price_of(c) if c["style"] != "fake" else None
    if dry:
        chars = len(system) * len(batches) + sum(len(ai_user(kind, b)) for b in batches)
        est = f", 대략 ${chars / 2.5 * (p[0] if p else 0) / 1e6 + len(recs) * 150 * (p[1] if p else 0) / 1e6:.2f}" if p else ""
        print(f"{kind}: 물을 기록 {len(recs)}건, 호출 {len(batches)}번, 보낼 글자 약 {chars:,}자{est}"
              f" (모델 {c['model'] or '?'}, 캐시 · 생각 토큰에 따라 달라짐)")
        return keep
    if p is None and c["style"] != "fake":
        print(f"주의: {c['model'] or c['style']} 단가를 몰라 --max-usd 를 지킬 수 없습니다. JUDGE_AI_PRICE 를 넣거나 --limit 을 쓰세요.")
    spent, rows, failed, missing = 0.0, [], 0, 0
    log_p = data / "ai_usage.csv"
    for b in batches:
        ids, user, got = {r["id"] for r in b}, ai_user(kind, b), None
        for _ in range(2):   # a broken answer is asked once more, then the batch is left empty
            text, u = ai_call(system, user, kind)
            cost = usd(u, p)
            spent += cost
            log = read(log_p)
            write(pd.concat([log, pd.DataFrame([{"at": datetime.now().isoformat(timespec="seconds"), "cmd": cmd,
                                                 "kind": kind, "model": c["model"], **u, "usd": round(cost, 5)}])],
                            ignore_index=True), log_p)
            got = parse_answers(text, kind, ids)
            if got is not None:
                break
        if got is None:
            failed += len(ids)
        else:
            missing += len(ids - set(got))
            rows += [{"id": i, "ai_label": got[i][0], "ai_conf": got[i][1], "ai_why": got[i][2], "sys": key}
                     for i in (r["id"] for r in b) if i in got]
            write(pd.concat([keep, pd.DataFrame(rows)], ignore_index=True), out_p)
        if p and max_usd and spent >= max_usd:
            print(f"--max-usd {max_usd} 에 닿아 멈춥니다. 다시 돌리면 남은 것부터 이어서 묻습니다.")
            break
    print(f"{kind}: AI 답 {len(rows)}건, 형식 오류 {failed}건, 빠진 답 {missing}건, 이번 비용 약 ${spent:.3f}"
          f" (누적 기록 {log_p})")
    return read(out_p)


def ai_check(data, kind, max_usd):
    tr = read(data / "train" / f"{kind}.csv")
    if tr.empty:
        tr = pd.DataFrame([{"id": "check-1", "role": "WALKER", "ff_ms": 300, "peak_g": 4.4, "imp_g": 2.5, "deg": 75,
                            "post_n": 500, "post_act": 0}] if kind == "fall" else
                          [{"id": "check-1", "role": "WALKER", "peer_role": "FORKLIFT", "rssi": -60, "ema": -61.5,
                            "kf_vel": 1.2, "eff_w": -86, "eff_d": -73}])
    c, system, b = ai_conf(), ai_system(kind, data), tr.head(3).to_dict("records")
    text, u = ai_call(system, ai_user(kind, b), kind)
    got = parse_answers(text, kind, {r["id"] for r in b})
    print(f"모델 {c['model']} ({c['style']}) — 토큰 입력 {u['in']} · 캐시 읽기 {u['cache_read']} · 캐시 쓰기 {u['cache_write']}"
          f" · 출력 {u['out']}, 비용 약 ${usd(u, price_of(c) if c['style'] != 'fake' else None):.4f}")
    if got is None:
        sys.exit(f"답을 읽지 못했습니다. 받은 글 앞부분: {text[:300]!r}")
    for i, (lab, conf, why) in got.items():
        print(f"  {i}: {ANSWER[kind]}={lab} ({conf}) {why}")
    print("AI 연결 정상." if len(got) == len(b) else f"주의: {len(b)}건 중 {len(got)}건만 답했습니다.")


def ai_label(data, max_usd, limit, dry):
    """AI first-pass labels for train falls that have no in-app answer or test-session label yet."""
    tr = read(data / "train" / "fall.csv")
    if tr.empty:
        sys.exit("학습 데이터가 없습니다 (load, split 먼저).")
    none = pd.Series(False, index=tr.index)
    known = tr["truth"].isin(["fall", "no"]) if "truth" in tr else none
    test = tr["sess"].fillna("").str.match(r"test:(fall|no):") if "sess" in tr else none
    run_ai("fall", tr[~(known | test)], data, data / "train" / "fall_ai_v1.csv", "ai-label", max_usd, limit, dry)


def learn(data, kind, per_class=6, seed=0):
    """Examples the AI learns from: human answers first, then high-confidence labels, even per class."""
    v2 = read(data / "train" / f"{kind}_v2.csv")
    if v2.empty or "label_v2" not in v2:
        sys.exit("재분류한 학습 데이터가 없습니다 (label, 사람 확인, relabel 먼저).")
    human_ids = set(read(data / f"human_{kind}.csv").get("id", pd.Series(dtype=str)))
    v2 = v2.assign(lab=pd.to_numeric(v2["label_v2"], errors="coerce"), by_human=v2["id"].isin(human_ids))
    picks = []
    for c in CLASSES[kind]:
        g = v2[v2["lab"] == c].sort_values(["by_human", "id"], ascending=[False, True])
        g = pd.concat([g[g["by_human"]].sample(frac=1, random_state=seed), g[~g["by_human"]]]).head(per_class)
        picks.append(g)
    ex = pd.concat(picks).sort_values("id")
    lines = [json.dumps({"input": ai_input(kind, r), "answer": {ANSWER[kind]: str(int(r["lab"]))}}, ensure_ascii=False)
             for r in ex.to_dict("records")]
    (data / f"ai_examples_{kind}.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
    counts = ex["lab"].astype(int).value_counts().to_dict()
    print(f"{kind}: 예시 {len(ex)}건 (" + ", ".join(f"{NAMES[kind][c]} {counts.get(c, 0)}" for c in CLASSES[kind])
          + f") -> {data / f'ai_examples_{kind}.jsonl'}")


def example_ids(data, kind):
    p = data / f"ai_examples_{kind}.jsonl"
    if not p.exists():
        return set()
    return {json.loads(l)["input"].get("id") for l in p.read_text(encoding="utf-8").splitlines() if l.strip()}


def ai_judge(data, kind, on, max_usd, limit, dry, force):
    if on == "train":
        v2 = read(data / "train" / f"{kind}_v2.csv")
        if v2.empty:
            sys.exit("재분류한 학습 데이터가 없습니다 (relabel 먼저).")
        scored = pd.to_numeric(v2["label_v2"], errors="coerce").isin(CLASSES[kind])   # only rows that can be scored
        rows = v2[scored & ~v2["id"].isin(example_ids(data, kind))]   # examples would only check memory
        got = run_ai(kind, rows, data, data / "train" / f"{kind}_ai_judge.csv", "ai-judge train", max_usd, limit, dry)
        if dry or got.empty:
            return
        lab = pd.to_numeric(rows.set_index("id")["label_v2"], errors="coerce")
        pred = pd.to_numeric(got.set_index("id")["ai_label"], errors="coerce").reindex(lab.index)
        ok = lab.isin(CLASSES[kind]) & pred.notna()
        if ok.any():
            text, _ = report(f"{kind} AI 판단 vs 학습 데이터 정답 (예시로 준 {len(example_ids(data, kind))}건 제외)",
                             {"AI": pred[ok].astype(int)}, lab[ok].astype(int), kind)
            (data / f"ai_train_{kind}.md").write_text(text, encoding="utf-8")
            print(text)
        return
    out_p = data / "real" / f"{kind}_ai_judge.csv"
    if out_p.exists() and not force and not dry:
        sys.exit(f"{out_p} 가 이미 있습니다. 실제 데이터를 AI 에게 다시 보이면 평가가 학습에 새어 들 수 있어 막습니다"
                 " (그래도 하려면 --force).")
    real = read(data / "real" / f"{kind}.csv")
    if kind == "prox" and not real.empty:   # only records with a UWB / test distance can be scored: don't pay for the rest
        real = real[[prox_truth(r) is not None for r in real.to_dict("records")]]
    if real.empty:
        sys.exit("실제 데이터가 없습니다 (split 먼저. 접근은 UWB 거리나 거리 실험 기록이 있어야 합니다).")
    run_ai(kind, real, data, out_p, "ai-judge real", max_usd, limit, dry)


# ── rule labels -> human relabeling ─────────────────────────────────────

def rule_fall(r):
    """First-pass fall label -> (label, rule, confidence) when no AI answer is used. A rule whose agreement
    with people is low in relabel_fall.md is fixed here and `label` runs again. high and mid labels are
    trained on without review; low ones go to people."""
    truth = str(r.get("truth"))
    if truth in ("fall", "no"):                     # answered in the app after the episode closed
        return int(truth == "fall"), "truth", "high"
    sess = str(r.get("sess"))
    if sess.startswith("test:fall:"):
        return 1, "session", "high"
    if sess.startswith("test:no:"):
        return 0, "session", "high"
    why = str(r.get("close_why"))
    if yes(r.get("sos")) and why in ("rescuer", "sos_cancel", "auto_1h"):
        return 1, "sos_no_answer", "low"            # no answer for 60 s - or a phone left on the floor
    if yes(r.get("opened")) and why == "walk":
        return 0, "walked_off", "low"
    peak, limit, deg = fnum(r.get("peak_g")), fnum(r.get("imp_g")), fnum(r.get("deg"))
    if not yes(r.get("pass")) and peak and limit and peak < 1.2 * limit and deg is not None and deg < 30:
        return 0, "weak_flat", "mid"                # barely over the impact line, no tip-over
    return None, "none", "low"


def fork_pair(role, peer_role):
    return "FORKLIFT" in (str(role), str(peer_role))


def band(dist, warn, danger):
    return 2 if dist <= danger else 1 if dist <= warn else 0


def rule_prox(r):
    """Proximity truth that does not come from RSSI, best first: the UWB distance (k=uwb, or k=dist with the UWB
    distance of that alert), a person's distance answer after a danger alert (k=dist; the sooner the answer, the
    surer), or a known test distance (sess "test:<meters>m:<name>"). Records with none stay unlabeled."""
    fork = fork_pair(r.get("role"), r.get("peer_role"))
    warn, danger = UWB_FORK if fork else UWB_PAIR
    dist = fnum(r.get("uwb_m"))
    if dist is not None:
        return band(dist, fnum(r.get("warn_m")) or warn, fnum(r.get("dang_m")) or danger), "uwb", "high"
    ans = str(r.get("ans"))
    if r.get("k") == "dist" and ans in DIST_LEVEL[fork]:
        after = fnum(r.get("after_ms"))
        conf = "low" if after is None or after > DIST_FRESH_MS[1] else "high" if after <= DIST_FRESH_MS[0] else "mid"
        return DIST_LEVEL[fork][ans], "human_dist", conf
    hit = re.match(r"test:([0-9.]+)m:", str(r.get("sess")))
    if hit and fnum(hit.group(1)) is not None:
        return band(float(hit.group(1)), warn, danger), "session", "high"
    return None, "none", ""


def prox_truth(r):
    """The proximity truth used for scoring: late human answers (low) are left out."""
    lab, _, conf = rule_prox(r)
    return None if conf == "low" else lab


def dist_bucket(m):
    return ("lt1" if m < 1 else "1to3" if m < 3 else "3to5" if m < 5 else "5to8" if m < 8
            else "8to12" if m < 12 else "12to15" if m < 15 else "gt15")


def dist_check(data):
    """Train data only: people's distance answers given during a danger alert (k=dist, values of the tap moment).
    How well they match the UWB distance of that moment by how soon after the alert they tapped, where each answered
    bucket sits in signal (ema at the tap), and how the signal moved from the alert start to the tap."""
    p = read(data / "train" / "prox.csv")
    d = p[p["k"] == "dist"] if "k" in p else p.iloc[0:0]
    if d.empty:
        sys.exit("학습 데이터에 거리 답(dist) 기록이 없습니다.")
    d = need(d, ["uwb_m", "start_ema"])
    d = d.assign(after_s=pd.to_numeric(d["after_ms"], errors="coerce") / 1000,
                 ema_n=pd.to_numeric(d["ema"], errors="coerce"), start_n=pd.to_numeric(d["start_ema"], errors="coerce"),
                 uwb=pd.to_numeric(d["uwb_m"], errors="coerce"),
                 fork=[fork_pair(a, b) for a, b in zip(d["role"], d["peer_role"])])
    d["late"] = pd.cut(d["after_s"], [-1, 5, 10, 20, 30, float("inf")],
                       labels=["5초 안", "5~10초", "10~20초", "20~30초", "30초 넘음"])
    names = {"lt1": "1m 안", "1to3": "1~3m", "3to5": "3~5m", "5to8": "5~8m", "8to12": "8~12m", "12to15": "12~15m",
             "gt15": "15m 넘음", "gt5": "5m 넘음(1.2.27 전)", "unknown": "모름"}
    lines = ["# 거리 답 점검 (학습 데이터)", "", f"거리 답 {len(d)}건, 그중 누른 순간 UWB 거리도 있는 것 {int(d['uwb'].notna().sum())}건", "",
             "## 누른 시간별: 사람 답 vs 누른 순간의 UWB 거리", "", "| 경보 뒤 누른 시간 | 건수 | 같은 구간 | 한 구간 차이 |", "|---|---:|---:|---:|"]
    order = ["lt1", "1to3", "3to5", "5to8", "8to12", "12to15", "gt15"]
    u = d[d["uwb"].notna() & d["ans"].isin(order)]
    for late, g in u.groupby("late", observed=False):
        if g.empty:
            lines.append(f"| {late} | 0 | - | - |")
            continue
        gap = (g["ans"].map(order.index) - g["uwb"].map(lambda m: order.index(dist_bucket(m)))).abs()
        lines.append(f"| {late} | {len(g)} | {(gap == 0).mean():.0%} | {(gap == 1).mean():.0%} |")
    lines += ["", "## 답한 구간별 누른 순간 신호 세기 (ema, dBm)", "", "| 짝 | 답 | 건수 | 가운데 | 중간 50% |", "|---|---|---:|---:|---|"]
    for fork, gf in d.groupby("fork"):
        for a in order + ["gt5", "unknown"]:
            g = gf[gf["ans"] == a]["ema_n"].dropna()
            if len(g):
                lines.append(f"| {'지게차가 낀 짝' if fork else '그 밖'} | {names[a]} | {len(g)} | {g.median():.0f} |"
                             f" {g.quantile(0.25):.0f} ~ {g.quantile(0.75):.0f} |")
    lines += ["", "## 경보 순간 → 누른 순간 신호 변화 (답한 구간별 가운데 값)", "",
              "| 답 | 건수 | 경보 순간 ema | 누른 순간 ema | 변화 | 누르기까지 |", "|---|---:|---:|---:|---:|---:|"]
    for a in order + ["gt5", "unknown"]:
        g = d[(d["ans"] == a) & d["ema_n"].notna() & d["start_n"].notna()]
        if len(g):
            lines.append(f"| {names[a]} | {len(g)} | {g['start_n'].median():.0f} | {g['ema_n'].median():.0f} |"
                         f" {(g['ema_n'] - g['start_n']).median():+.0f} dB | {g['after_s'].median():.0f}초 |")
    lines += ["", "- 같은 구간 비율이 누르기까지 오래 걸릴수록 떨어지면, 그 시간대 답은 믿음을 낮춰 봅니다.",
              "- 구간별 신호 세기는 지금 앱 문턱(eff_w · eff_d)과 견주어 문턱이 몇 m 쯤인지 가늠하는 데 씁니다.",
              "- 신호 변화는 경보가 울린 뒤 몇 초 동안 신호가 얼마나 움직이는지(다가옴 · 멀어짐)를 배우는 데 씁니다."]
    (data / "dist_check.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


def rssi_check(data):
    """Train data only: signal (ema) against the UWB-measured distance - how far the distance spreads for one
    signal level, where the signal reads weaker than the distance says (racks, pallets: 'blocked?'), by zone,
    and how often the plain threshold rule calls a UWB-danger distance safe."""
    p = read(data / "train" / "prox.csv")
    u = need(p, ["uwb_m", "floor", "proc", "zone", "ema", "rssi", "use", "frame_age_ms"])
    u["use"] = u["use"].fillna(need(u, ["uwb_use"])["uwb_use"])   # a distance answer carries it as uwb_use
    # Signal = the judged ema (rssi if a record has no ema); a frame more than 2 s old no longer matches the distance
    e = pd.to_numeric(u["ema"], errors="coerce").fillna(pd.to_numeric(u["rssi"], errors="coerce"))
    u = u.assign(d=pd.to_numeric(u["uwb_m"], errors="coerce"), e=e)
    u = u[(u["d"] > 0.1) & u["e"].notna()]
    stale = pd.to_numeric(u["frame_age_ms"], errors="coerce") > 2000
    u = u[~stale]
    if len(u) < 10:
        sys.exit(f"UWB 거리가 있는 학습 기록이 {len(u)}건뿐입니다(10건 이상 필요).")
    u = u.assign(fork=[fork_pair(a, b) for a, b in zip(u["role"], u["peer_role"])])
    u["truth"] = [rule_prox(r)[0] for r in u.to_dict("records")]
    lines = ["# 신호별 거리 편차 (학습 데이터, UWB 거리 기준)", "",
             f"UWB 거리가 있는 기록 {len(u)}건 (판정용 {int((u['use'] == 'judge').sum())} · 측정용 {int((u['use'] == 'measure').sum())}"
             f" · 구분 없음 {int(u['use'].isna().sum())}), 신호가 2초 넘게 지난 기록 {int(stale.sum())}건은 뺌", ""]
    # Clear-path fit per pair type: ema = A + B·log10(d) (B = -10·n). Fit once, drop what reads 6 dB or more
    # weaker than that line (likely blocked), fit again - so blocked samples don't drag the reference down.
    fits = {}
    for fork, g in u.groupby("fork"):
        x, y = np.log10(g["d"].to_numpy()), g["e"].to_numpy()
        if len(g) >= 10 and np.ptp(x) >= 0.3:   # distances must span 2x or more, or the slope is noise
            b, a = np.polyfit(x, y, 1)
            clear = (y - (a + b * x)) > -6
            if clear.sum() >= 10:
                b, a = np.polyfit(x[clear], y[clear], 1)
            fits[fork] = (a, b, float(np.std((y - (a + b * x))[clear])))
    lines += ["## 신호와 거리의 관계 (가리지 않은 기록으로 맞춘 식)", "",
              "| 짝 | 1m 에서 신호 | 거리 10배마다 | 흩어짐(표준편차) |", "|---|---:|---:|---:|"]
    for fork, (a, b, s) in fits.items():
        lines.append(f"| {'지게차가 낀 짝' if fork else '그 밖'} | {a:.0f} dBm | {b:.0f} dB | ±{s:.1f} dB |")
    edges = list(range(-100, -24, 5))
    u["bin"] = pd.cut(u["e"], bins=[-np.inf] + edges + [np.inf],
                      labels=["-100 이하"] + [f"{lo}~{lo + 5}" for lo in edges[:-1]] + ["-25 넘음"])
    lines += ["", "## 신호 칸별 실제 거리 (UWB)", "",
              "| 신호(ema) | 건수 | 거리 가운데 | 중간 50% | UWB 로 위험 | 그중 앱 문턱은 안전 |", "|---|---:|---:|---|---:|---:|"]
    for b_, g in u.groupby("bin", observed=True):
        danger = g["truth"] == 2
        missed = danger & (pd.to_numeric(g["rule_level"], errors="coerce") == 0)
        lines.append(f"| {b_} | {len(g)} | {g['d'].median():.1f}m | {g['d'].quantile(0.25):.1f} ~ {g['d'].quantile(0.75):.1f}m"
                     f" | {danger.mean():.0%} | {int(missed.sum())}건 |")
    # Blocked?: the signal is 10 dB or more weaker than the fit expects for the UWB distance
    u["resid"] = [e - (fits[f][0] + fits[f][1] * math.log10(d)) if f in fits else np.nan
                  for e, d, f in zip(u["e"], u["d"], u["fork"])]
    u["blocked"] = u["resid"] <= -10
    u["place"] = [" · ".join(str(v) for v in (fl, pr, zo) if isinstance(v, str) and v) or "(구역 없음)"
                  for fl, pr, zo in zip(u["floor"], u["proc"], u["zone"])]
    lines += ["", "## 가려짐 의심 (같은 거리보다 신호가 10dB 이상 약함): 구역별", "",
              "| 구역(층 · 공정 · 존) | 건수 | 가려짐 의심 | 신호 차이 가운데 |", "|---|---:|---:|---:|"]
    by = u.groupby("place").agg(n=("blocked", "size"), share=("blocked", "mean"), mid=("resid", "median"))
    for place, r in by.sort_values(["share", "n"], ascending=False).iterrows():
        lines.append(f"| {place} | {int(r['n'])} | {r['share']:.0%} | {r['mid']:+.0f} dB |")
    lines += ["", "- 흩어짐이 클수록 신호 하나로 거리를 정하기 어렵다는 뜻입니다.",
              "- '그중 앱 문턱은 안전'은 UWB 로는 위험 거리인데 지금 신호 문턱으로는 경보가 안 나는 건수입니다.",
              "- 가려짐 의심이 많은 구역이 렉 · 파렛트처럼 신호를 먹는 곳입니다. 앱이 렉 안인지 몰라도 구역으로 찾습니다."]
    (data / "rssi_check.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


def human(data, kind):
    """Every answer people gave so far (id -> label_final, memo, audit), kept across label/relabel rounds."""
    return read(data / f"human_{kind}.csv")


def label(data, by="rules", audit=0.1, seed=0):
    for kind, rule in (("fall", rule_fall), ("prox", rule_prox)):
        tr = read(data / "train" / f"{kind}.csv")
        if tr.empty:
            print(f"{kind}: 학습 데이터가 없습니다 (load, split 먼저).")
            continue
        sheet_p, known = data / f"review_{kind}.csv", need(human(data, kind), ["id", "label_final"])
        old = read(sheet_p)
        if "label_final" in old:
            filled = old[old["label_final"].notna()]
            kept = set(zip(known["id"], known["label_final"]))
            if not all(pair in kept for pair in zip(filled["id"], filled["label_final"])):
                sys.exit(f"{sheet_p} 에 아직 반영하지 않은 답이 있습니다. relabel 을 먼저 돌리세요.")
        labels, rules, confs = zip(*[rule(r) for r in tr.to_dict("records")])
        # Fixed dtypes, so AI answers can be written over them below
        tr["label_v1"] = pd.Series(labels, index=tr.index, dtype="float64")
        tr["rule"] = pd.Series(rules, index=tr.index, dtype=object)
        tr["conf"] = pd.Series(confs, index=tr.index, dtype=object)
        tr["ai_why"] = pd.Series([None] * len(tr), index=tr.index, dtype=object)
        if by == "ai" and kind == "fall":   # proximity truth is UWB: no first-pass guess to make there
            ai = read(data / "train" / "fall_ai_v1.csv")
            if ai.empty:
                sys.exit("AI 1차 분류가 없습니다 (ai-label 먼저).")
            ai = ai.drop_duplicates("id", keep="last").set_index("id")
            take = tr["id"].isin(ai.index) & (tr["rule"] != "truth") & (tr["rule"] != "session")
            ids = tr.loc[take, "id"]
            tr.loc[take, "label_v1"] = pd.to_numeric(ids.map(ai["ai_label"]), errors="coerce").values
            tr.loc[take, "conf"] = ids.map(ai["ai_conf"]).fillna("low").values
            tr.loc[take, "rule"] = "ai"
            tr.loc[take, "ai_why"] = ids.map(ai["ai_why"]).values
        lab = pd.to_numeric(tr["label_v1"], errors="coerce")
        done = tr["id"].isin(known["id"])   # people already answered these in an earlier round
        if kind == "fall":
            ask = ((tr["conf"] == "low") | lab.isna()) & ~done
        else:   # UWB and plain RSSI two levels apart (NLOS, or a bad UWB sample?), or a late distance answer
            far = (lab - pd.to_numeric(tr["rule_level"], errors="coerce")).abs() == 2
            ask = lab.notna() & (far | (tr["conf"] == "low")) & ~done
            # k=uwb comes once a second per peer: ask the first of each phone · peer · minute, not all of them
            minute = pd.to_numeric(tr["t"], errors="coerce") // 60_000
            again = (tr["k"] == "uwb") & ask & tr.assign(m=minute)[ask].duplicated(["dev", "peer", "m"]) \
                .reindex(tr.index, fill_value=False)
            ask &= ~again
        rest = list(tr.index[lab.notna() & ~ask & ~done])
        pick = pd.Series(False, index=tr.index)
        share = audit if kind == "fall" else 0   # proximity truth is a UWB measurement: nothing to re-judge blind
        pick[random.Random(seed).sample(rest, int(len(rest) * share))] = True
        tr["audit"] = pick.astype(int)
        write(tr, data / "train" / f"{kind}_v1.csv")
        rows = tr[ask | pick].assign(time=lambda f: kst(f["t"]))
        sheet = need(rows, REVIEW[kind])[REVIEW[kind]]
        sheet = sheet.assign(rule=rows["rule"], rule_label=lab[rows.index], ai_why=rows["ai_why"],
                             audit=rows["audit"], label_final="", memo="")
        # Audit rows are answered from field facts only: hide the first-pass answer, its source and what it reads
        for c in ["rule", "rule_label", "ai_why"] + REVIEW[kind][4:]:
            sheet[c] = sheet[c].where(rows["audit"] == 0)
        write(sheet, sheet_p)
        print(f"{kind}: 1차 분류로 정함 {int((lab.notna() & ~ask & ~done).sum())}건, 이미 사람이 답함 {int(done.sum())}건,"
              f" 사람 확인 {int(ask.sum())}건 + 표본 {int(pick.sum())}건 -> {sheet_p}")


def relabel(data):
    for kind, classes in CLASSES.items():
        cols = ["id", "label_final", "memo", "audit"]   # audit = answered blind, from field facts
        sheet, known = read(data / f"review_{kind}.csv"), need(human(data, kind), cols)
        if "label_final" in sheet and "id" in sheet:
            # The sheet decides for its own rows: a changed answer replaces the stored one, a cleared one removes it
            mine = sheet.reindex(columns=cols)
            known = pd.concat([known[~known["id"].isin(mine["id"])], mine[mine["label_final"].notna()]],
                              ignore_index=True)
            write(known, data / f"human_{kind}.csv")
        v1 = read(data / "train" / f"{kind}_v1.csv")
        if v1.empty:   # answers are kept above; label builds v1 from the current split
            continue
        k = known.assign(label_final=pd.to_numeric(known["label_final"], errors="coerce"))
        said_by_id = k[k["label_final"].isin(classes)].set_index("id")["label_final"]
        blind_ids = set(k.loc[pd.to_numeric(k["audit"], errors="coerce") == 1, "id"])
        rule_lab = pd.to_numeric(v1["label_v1"], errors="coerce")
        said = v1["id"].map(said_by_id)
        v1["label_v2"] = said.combine_first(rule_lab.where(v1["conf"].isin(["high", "mid"])))
        write(v1, data / "train" / f"{kind}_v2.csv")
        seen = said.notna() & rule_lab.notna()
        lines = [f"# {kind} 재분류", "",
                 f"사람이 정한 {int(said.notna().sum())}건, 학습에 쓸 정답 {int(v1['label_v2'].notna().sum())}건", "",
                 "| 1차 분류 | 사람이 본 건 | 사람과 같음 | 그중 표본 | 표본 일치 |", "|---|---:|---:|---:|---:|"]
        for name, g in v1[seen].groupby("rule"):
            same = rule_lab[g.index] == said[g.index]
            blind = g["id"].isin(blind_ids)
            lines.append(f"| {name} | {len(g)} | {same.mean():.0%} | {int(blind.sum())} | "
                         + (f"{same[blind].mean():.0%}" if blind.any() else "-") + " |")
        lines += ["", "1차 분류 = ai(AI 1차 분류) 또는 규칙 이름. 표본 = 1차 답 · 측정값 · 앱 판정을 가리고 현장 사실로 매긴 것(낙상만).",
                  "일치가 낮으면 ai_rules_*.md(AI) 또는 rule_fall · rule_prox(규칙)를 고치고 1차 분류부터 다시 합니다."]
        (data / f"relabel_{kind}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
        print("\n".join(lines))


# ── compare ──────────────────────────────────────────────────────────────

def report(title, preds, y, kind):
    from sklearn.metrics import confusion_matrix, precision_recall_fscore_support
    classes, names = CLASSES[kind], NAMES[kind]
    out = [f"# {title}", "", "정답 " + ", ".join(f"{names[c]} {int((y == c).sum())}건" for c in classes), ""]
    f1s = {}
    for name, p in preds.items():
        pr, rc, f1, _ = precision_recall_fscore_support(y, p, labels=classes, zero_division=0)
        cm = confusion_matrix(y, p, labels=classes)
        f1s[name] = f1
        out += [f"## {name}", "", "| 정답 | 정밀도 | 재현율 | F1 |", "|---|---:|---:|---:|"]
        out += [f"| {names[c]} | {pr[i]:.2f} | {rc[i]:.2f} | {f1[i]:.2f} |" for i, c in enumerate(classes)]
        out += ["", "| 정답 \\ 판정 | " + " | ".join(names[c] for c in classes) + " |",
                "|---" + "|---:" * len(classes) + "|"]
        out += [f"| {names[c]} | " + " | ".join(str(v) for v in cm[i]) + " |" for i, c in enumerate(classes)]
        out.append("")
    return "\n".join(out), f1s


def train(data, kind):
    import joblib
    from sklearn.ensemble import HistGradientBoostingClassifier
    from sklearn.model_selection import GroupKFold, StratifiedKFold, cross_val_predict
    df = read(data / "train" / f"{kind}_v2.csv")
    if df.empty or "label_v2" not in df:
        sys.exit("재분류한 학습 데이터가 없습니다 (label, 사람 확인, relabel 먼저).")
    df = df[pd.to_numeric(df["label_v2"], errors="coerce").isin(CLASSES[kind])]
    y = df["label_v2"].astype(float).astype(int)
    if len(df) < 20 or y.nunique() < 2:
        sys.exit(f"정답 있는 학습 데이터 {len(df)}건, 종류 {y.nunique()}개: 20건 이상, 두 종류 이상이 필요합니다.")
    X = need(df, FEATS[kind])[FEATS[kind]].apply(pd.to_numeric, errors="coerce")
    model = HistGradientBoostingClassifier(class_weight="balanced", random_state=0)   # takes NaN as is
    n_dev = df["dev"].nunique()
    if n_dev >= 2:   # a phone is in train or test, never both: same-phone habits would flatter the score
        cv, groups = GroupKFold(n_splits=min(5, n_dev)), df["dev"]
    else:
        print("폰이 한 대뿐이라 교차검증이 같은 폰 안에서 나뉩니다. 실제보다 좋게 나옵니다.")
        cv, groups = StratifiedKFold(n_splits=max(2, min(5, int(y.value_counts().min()))), shuffle=True,
                                     random_state=0), None
    try:
        pred = cross_val_predict(model, X, y, groups=groups, cv=cv)
    except ValueError as e:
        sys.exit(f"교차검증 실패: {e}\n폰마다 정답 종류가 한쪽으로 쏠렸을 수 있습니다. 데이터를 더 모으세요.")
    model.fit(X, y)
    joblib.dump({"model": model, "features": FEATS[kind], "kind": kind, "rows": len(df),
                 "made": datetime.now().isoformat(timespec="seconds")}, data / f"model_{kind}.joblib")
    text, _ = report(f"{kind} 비교용 기본 모델: 교차검증 (폰 단위로 나눔)", {"기본 모델": pred}, y, kind)
    (data / f"train_{kind}.md").write_text(text, encoding="utf-8")
    print(text)


def real_sheet(data):
    real = read(data / "real" / "fall.csv")
    if real.empty:
        sys.exit("실제 데이터(낙상)가 없습니다 (split 먼저).")
    p = data / "real" / "fall_truth.csv"
    old = read(p)
    new = need(real[~real["id"].isin(old["id"])] if "id" in old else real, ["site", "floor", "proc", "zone"])
    sheet = pd.DataFrame({"id": new["id"], "dev": new["dev"], "time": kst(new["t"]), "site": new["site"],
                          "floor": new["floor"], "proc": new["proc"], "zone": new["zone"],
                          "label_final": "", "memo": ""})
    write(pd.concat([old, sheet], ignore_index=True) if len(old) else sheet, p)
    print(f"{p}: 새 줄 {len(sheet)}건 (있던 {len(old)}건은 그대로). 측정값과 판정은 보지 않고"
          " 현장에서 확인한 사실로만 채웁니다: 1 넘어짐, 0 아님, 모르면 비움.")


def evaluate(data, kind):
    man_p, real_p = data / "split.json", data / "real" / f"{kind}.csv"
    if not man_p.exists():
        sys.exit("split 을 먼저 하세요.")
    real = read(real_p)
    if kind == "fall":
        truth = real["truth"].map({"fall": 1.0, "no": 0.0}) if "truth" in real else pd.Series(np.nan, index=real.index)
        sheet = read(data / "real" / "fall_truth.csv")
        if "label_final" in sheet:
            said = pd.to_numeric(sheet.set_index("id")["label_final"], errors="coerce")
            truth = real["id"].map(said).combine_first(truth)
        apps = {"앱 확인 창 열림(opened)": flag(need(real, ["opened"])["opened"]).fillna(0),
                "앱 후보 통과(pass)": flag(need(real, ["pass"])["pass"]).fillna(0)}
    else:
        truth = pd.Series([prox_truth(r) for r in real.to_dict("records")], index=real.index, dtype=float)
        apps = {"앱 문턱값(rule_level)": pd.to_numeric(need(real, ["rule_level"])["rule_level"], errors="coerce")}
    keep = truth.isin(CLASSES[kind])
    if not keep.any():
        sys.exit("실제 데이터에 정답이 없습니다. 낙상은 real-sheet 표의 label_final 을 채우고,"
                 " 접근은 UWB 거리나 거리 실험(sess) 기록이 있어야 합니다.")
    preds, notes = {}, []
    ai = read(data / "real" / f"{kind}_ai_judge.csv")
    if "ai_label" in ai:
        ai_pred = real["id"].map(pd.to_numeric(ai.drop_duplicates("id", keep="last").set_index("id")["ai_label"],
                                               errors="coerce"))
        preds["AI"] = ai_pred
        notes.append(f"AI 가 모른다고 했거나 답이 없는 {int((ai_pred[keep].isna()).sum())}건은 틀린 것으로 셉니다.")
    model_p = data / f"model_{kind}.joblib"
    if model_p.exists():
        import joblib
        bundle = joblib.load(model_p)   # pickle: load only model files you made
        X = need(real, bundle["features"])[bundle["features"]].apply(pd.to_numeric, errors="coerce")
        preds["비교용 기본 모델"] = pd.Series(bundle["model"].predict(X), index=real.index)
    if not preds:
        sys.exit("비교할 판단이 없습니다 (ai-judge --on real 또는 train 먼저).")
    man = json.loads(man_p.read_text(encoding="utf-8"))
    if sha(real_p) != man.get("sha256", {}).get(kind):
        print("주의: 실제 데이터 파일이 split 뒤에 바뀌었습니다.")
    runs = man.setdefault("eval_runs", {})
    n = runs.get(kind, 0) + 1
    y = truth[keep].astype(int)
    preds.update(apps)
    preds = {name: s[keep].fillna(-1).astype(int) for name, s in preds.items()}
    text, f1s = report(f"{kind} 실제 데이터 평가 (실제 = {man['cut']} 부터, {n}번째)", preds, y, kind)
    text += "\n".join(notes) + ("\n" if notes else "")
    runs[kind] = n   # counted only once the evaluation has actually run
    man_p.write_text(json.dumps(man, ensure_ascii=False, indent=2), encoding="utf-8")
    (data / f"report_{kind}.md").write_text(text, encoding="utf-8")
    print(text)
    if n > 1:
        print(f"주의: 같은 실제 데이터로 {n}번째 평가입니다. 앞선 결과를 보고 지시문 · 예시 · 규칙을 고쳤다면"
              " 이 데이터는 이미 학습에 새어 든 것입니다. 다음 평가는 새로 모은 날짜로 하세요.")
    return f1s


# ── self-check on synthetic data ─────────────────────────────────────────

def fake_acc(rnd, fell):
    """15 s at 50 Hz from 3 s before impact, milli-g."""
    rows = []
    for i in range(750):
        ms = i * 20 - 3000
        if ms < -400:
            v = (0, 0, 1000)
        elif ms < 0:
            v = (0, 0, 150) if fell else (0, 0, 800)
        elif ms < 100:
            v = (0, 0, 4200) if fell else (0, 0, 2700)
        else:
            v = (1000, 0, 0) if fell else (0, 0, 1000)
        rows.append(",".join(str(c + rnd.randint(-30, 30)) for c in v))
    return ";".join(rows)


def fake_export(seed=7):
    """4 phones x 6 days. _truth is the hidden answer the self-check plays people with."""
    rnd = random.Random(seed)
    judge, n = {"fall": {"WF11": {}}, "prox": {"WF11": {}}}, [0]

    def put(grp, day, rec):
        n[0] += 1
        judge[grp]["WF11"].setdefault(day, {})[f"r{n[0]:05d}"] = rec

    for d in range(6):
        day, base = f"202611{3 + d:02d}", int(datetime(2026, 11, 3 + d, 9).timestamp() * 1000)
        for dev in ("P1", "P2", "P3", "P4"):
            com = {"v": 1, "dev": dev, "role": "WALKER", "site": "WF11", "app": "test", "sess": "field"}
            for i in range(10):
                t, fell = base + i * 600_000 + rnd.randint(0, 999), rnd.random() < 0.3
                deg, act = rnd.gauss(80 if fell else 25, 15), rnd.randint(0, 1) if fell else rnd.randint(1, 5)
                ok = act < 3 and deg >= 45
                rec = {**com, "k": "cand", "t": t + 12_000, "imp_t": t, "imp_g": 2.5, "post_n": 500,
                       "ff_ms": max(0, round(rnd.gauss(330 if fell else 90, 70))), "deg": round(deg, 1),
                       "peak_g": round(rnd.gauss(4.6 if fell else 3.0, 0.7), 2), "post_act": act, "pre_ok": True,
                       "pass": ok, "why": "" if ok else ("post_active" if act >= 3 else "posture"), "_truth": int(fell)}
                if i % 3 == 0:
                    rec.update(acc=fake_acc(rnd, fell), acc_t0=t - 3000, acc_dt=20)
                put("fall", day, rec)
                if not ok:
                    continue
                put("fall", day, {**com, "k": "fall", "t": t + 12_000, "imp_t": t, "take": "watch",
                                  "zone_in": False, "charging": False})
                if not (fell or rnd.random() < 0.3):
                    continue
                ep = {**com, "k": "mode", "trig": "fall", "imp_t": t}
                put("fall", day, {**ep, "t": t + 42_000, "from": "WATCHING", "to": "CHECKING"})
                end = rnd.choice(["rescuer", "ack"] if fell else ["ack", "walk"])
                last = "CHECKING"
                if end == "rescuer":
                    put("fall", day, {**ep, "t": t + 102_000, "from": "CHECKING", "to": "SOS"})
                    last = "SOS"
                put("fall", day, {**ep, "t": t + 130_000, "from": last, "to": "WATCHING", "why": end})
                if rnd.random() < 0.4:   # answered the in-app question afterwards
                    put("fall", day, {**com, "k": "truth", "t": t + 140_000, "imp_t": t,
                                      "truth": "fall" if fell else "no", "by": "self"})
            for i in range(25):
                dist = rnd.uniform(0.5, 12.0)
                blocked = i % 4 == 0   # behind a rack: same distance, signal 12 dB weaker
                ema = -45 - 25 * math.log10(dist) + rnd.gauss(0, 3) - (12 if blocked else 0)
                put("prox", day, {**com, "k": "uwb", "t": base + i * 60_000, "peer": f"Q{i % 5}",
                                  "floor": "1F", "zone": "R1" if blocked else "A1",
                                  "use": "measure" if blocked else "judge", "frame_age_ms": 3500 if i == 24 else 120,
                                  "peer_role": "WALKER", "rssi": round(ema + rnd.gauss(0, 3)), "med": round(ema),
                                  "pre": ema, "kf": ema, "kf_vel": rnd.gauss(0, 0.5), "ema": ema,
                                  "avg1s": ema + rnd.gauss(0, 2), "off_pl": 2, "off_bc": 0, "off_gg": 0, "echo": 0,
                                  "eff_w": -64.5, "eff_d": -59.0, "uwb_m": round(dist + rnd.gauss(0, 0.1), 2),
                                  "warn_m": 5.0, "dang_m": 3.0, "level": 1, "seq": "-70.1,0.2;-69.0,0.4"})
            for i in range(5):
                put("prox", day, {**com, "k": "level", "t": base + i * 90_000, "peer": "Q9", "peer_role": "FORKLIFT",
                                  "prev": 0, "level": 1, "ema": -70.0, "eff_w": -72.0, "eff_d": -60.0})
            for i in range(6):   # distance answers tapped during a danger alert: values of the tap moment + alert start
                dist, after = rnd.uniform(0.5, 18.0), rnd.choice([3_000, 8_000, 15_000, 25_000])
                ans = dist_bucket(dist) if rnd.random() > after / 100_000 else rnd.choice(["lt1", "1to3", "3to5", "5to8", "8to12", "12to15", "gt15"])
                if i == 5:
                    ans = "gt5"   # one old-code answer a day: still read (non-forklift pairs: danger-free), never offered any more
                ema = -45 - 25 * math.log10(dist) + rnd.gauss(0, 3)
                start = ema - rnd.uniform(0, 6)   # the peer kept closing in after the alert started
                rec = {**com, "k": "dist", "t": base + i * 120_000 + after, "peer": f"Q{i % 5}", "peer_role": "WALKER",
                       "alert_t": base + i * 120_000, "shown_ms": 0, "ans": ans, "after_ms": after,
                       "rssi": round(ema), "med": round(ema), "pre": ema, "kf": ema, "kf_vel": 0.5, "ema": ema,
                       "avg1s": ema, "off_pl": 2, "off_bc": 0, "off_gg": 0, "echo": 0, "eff_w": -64.5, "eff_d": -59.0,
                       "seq": "-62.0,0.4;-60.1,0.6", "frame_age_ms": 90, "start_rssi": round(start), "start_ema": start,
                       "start_frame_age_ms": 110}
                if i % 2 == 0:
                    rec.update(uwb_m=round(dist, 2), uwb_use="judge")
                put("prox", day, rec)
    return {"wf11": {"judge": judge, "alerts": {}}}


def selftest():
    saved = os.environ.get("JUDGE_AI_STYLE")
    os.environ["JUDGE_AI_STYLE"] = "fake"
    try:
        with tempfile.TemporaryDirectory() as tmp:
            data, exp = Path(tmp) / "data", Path(tmp) / "export.json"
            exp.write_text(json.dumps(fake_export()), encoding="utf-8")
            load(exp, data)
            fall = read(data / "fall.csv")
            assert fall["why"].isin(["posture", "post_active"]).any()   # cand's reject reason survives the joins
            assert fall["truth"].isin(["fall", "no"]).any()             # in-app answers (k=truth) reach the rows
            assert fall["acc_mag5"].notna().any()
            split(data)
            dist_check(data)
            txt = (data / "dist_check.md").read_text(encoding="utf-8")
            assert "5초 안" in txt and "경보 순간 → 누른 순간" in txt, txt[-400:]
            rssi_check(data)
            blocked_rows = [l for l in (data / "rssi_check.md").read_text(encoding="utf-8").splitlines() if "1F · R1" in l]
            assert blocked_rows and int(blocked_rows[0].split("|")[3].strip().rstrip("%")) >= 50, blocked_rows
            assert any(rule_prox(r)[1] == "human_dist" for r in read(data / "train" / "prox.csv").to_dict("records"))
            # distance answer codes: levels by pair type, buckets, old gt5 read for walkers only
            for ans, walk, fork in (("3to5", 1, 2), ("5to8", 0, 2), ("8to12", 0, 1), ("12to15", 0, 1), ("gt15", 0, 0), ("gt5", 0, None)):
                for pair, want in (("WALKER", walk), ("FORKLIFT", fork)):
                    got = rule_prox({"k": "dist", "ans": ans, "role": "WALKER", "peer_role": pair, "after_ms": 3_000})[0]
                    assert got == want, (ans, pair, got, want)
            assert [dist_bucket(m) for m in (0.5, 2, 4.9, 7.9, 11, 14.9, 15.0)] == ["lt1", "1to3", "3to5", "5to8", "8to12", "12to15", "gt15"]
            for kind in CLASSES:   # the AI never sees truth or the app's decisions
                row = read(data / "train" / f"{kind}.csv").to_dict("records")[0]
                assert not set(ai_input(kind, row)) & {"pass", "opened", "sos", "close_why", "truth", "uwb_m",
                                                       "level", "rule_level", "take", "_truth"}
            ai_label(data, max_usd=0, limit=0, dry=False)
            label(data, by="ai")
            sheet = read(data / "review_fall.csv")
            assert sheet.loc[sheet["audit"] == 1, ["rule", "rule_label", "ai_why", "pass", "opened"]].isna().all().all()
            for kind in CLASSES:   # people answer: the hidden truth for falls, the UWB band for proximity
                v1 = read(data / "train" / f"{kind}_v1.csv").set_index("id")
                ans = v1["_truth"] if kind == "fall" else v1["label_v1"]
                s = read(data / f"review_{kind}.csv")
                write(s.assign(label_final=s["id"].map(ans)), data / f"review_{kind}.csv")
            relabel(data)
            sheet = read(data / "review_fall.csv")
            gone = sheet["id"].iloc[0]   # an answer cleared on the sheet must leave the store too
            write(sheet.assign(label_final=sheet["label_final"].where(sheet["id"] != gone)), data / "review_fall.csv")
            relabel(data)
            assert gone not in set(read(data / "human_fall.csv")["id"])
            first = read(data / "train" / "fall_v2.csv")["label_v2"].notna().sum()
            label(data, by="ai")   # a second round keeps every earlier answer
            relabel(data)
            assert read(data / "train" / "fall_v2.csv")["label_v2"].notna().sum() >= first
            for kind in CLASSES:
                learn(data, kind)
                ai_judge(data, kind, "train", max_usd=0, limit=0, dry=False, force=False)
                train(data, kind)
            assert "## 예시" in ai_system("fall", data)
            real_sheet(data)
            real = read(data / "real" / "fall.csv").set_index("id")
            sheet = read(data / "real" / "fall_truth.csv")
            write(sheet.assign(label_final=sheet["id"].map(real["_truth"])), data / "real" / "fall_truth.csv")
            for kind in CLASSES:
                ai_judge(data, kind, "real", max_usd=0, limit=0, dry=False, force=False)
            f_fall, f_prox = evaluate(data, "fall"), evaluate(data, "prox")
            assert f_fall["AI"][1] >= 0.7, f_fall
            assert f_prox["AI"].mean() >= 0.5, f_prox
            assert json.loads((data / "split.json").read_text(encoding="utf-8"))["eval_runs"] == {"fall": 1, "prox": 1}
            assert len(read(data / "ai_usage.csv")) > 0
    finally:
        if saved is None:
            os.environ.pop("JUDGE_AI_STYLE", None)
        else:
            os.environ["JUDGE_AI_STYLE"] = saved
    selftest_export()
    print("\nselftest 통과")


# ── export / fetch: judge records at one fixed, encrypted address ──────────
# The repository is public, so the export is encrypted (Fernet = AES-128-CBC + HMAC) with JUDGE_EXPORT_KEY.
# export runs in GitHub Actions (judge-export.yml) with the admin secret; fetch runs on the company PC with the key only.

EXPORT_URL = "https://github.com/pslymzero-cyber/SafeAlert/releases/download/judge-data/judge.json.gz.enc"


def _fernet():
    key = os.environ.get("JUDGE_EXPORT_KEY", "").strip()
    if not key:
        sys.exit("JUDGE_EXPORT_KEY 환경 변수가 없습니다. README '자동 내보내기'대로 키를 넣으세요.")
    try:
        from cryptography.fernet import Fernet
    except ImportError:
        sys.exit("cryptography 가 없습니다. pip install -r requirements.txt 를 다시 실행하세요.")
    try:
        return Fernet(key.encode())
    except ValueError:
        sys.exit("JUDGE_EXPORT_KEY 형식이 맞지 않습니다. 키를 앞뒤 공백 없이 그대로 넣었는지 확인하세요.")


def seal(obj):
    import gzip
    return _fernet().encrypt(gzip.compress(json.dumps(obj, ensure_ascii=False).encode("utf-8")))


def unseal(blob):
    """None when the blob was sealed with another key."""
    import gzip
    from cryptography.fernet import InvalidToken
    try:
        return json.loads(gzip.decompress(_fernet().decrypt(blob)).decode("utf-8"))
    except InvalidToken:
        return None


def collect(fb, prev, since=None, root="wf11"):
    """The judge node read day by day (each read stays far under the 256 MB response limit), merged into prev (changed
    in place). Days already in prev are read again from `since` on (yyyyMMdd of the previous export, so normally
    yesterday and today, which may still be growing; a missed run is caught up); without `since` every day is read.
    Days deleted from Firebase stay in the export."""
    judge, fetched = prev if prev is not None else {}, 0
    for kind in sorted(fb(f"{root}/judge", True) or {}):
        for sc in sorted(fb(f"{root}/judge/{kind}", True) or {}):
            for day in sorted(fb(f"{root}/judge/{kind}/{sc}", True) or {}):
                if day in judge.get(kind, {}).get(sc, {}) and since and day < since:
                    continue
                judge.setdefault(kind, {}).setdefault(sc, {})[day] = fb(f"{root}/judge/{kind}/{sc}/{day}", False) or {}
                fetched += 1
    return judge, fetched


def fb_reader(db, key):
    import urllib.parse

    def get(path, shallow):
        q = {"auth": key, **({"shallow": "true"} if shallow else {})}
        url = f"{db.rstrip('/')}/{path}.json?{urllib.parse.urlencode(q)}"
        try:
            with urllib.request.urlopen(url, timeout=180) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:   # never print the URL: it carries the secret
            sys.exit(f"Firebase 읽기 실패: {path} (HTTP {e.code})")
        except urllib.error.URLError as e:
            sys.exit(f"Firebase 연결 실패: {path} ({e.reason})")
    return get


def count_records(judge):
    return sum(len(d) for k in judge.values() for s in k.values() for d in s.values() if isinstance(d, (dict, list)))


def export(out, prev_path=None):
    db, key = os.environ.get("DB", ""), os.environ.get("KEY", "")
    if not db or not key:
        sys.exit("DB · KEY 환경 변수(저장소 시크릿 FIREBASE_DB_URL · FIREBASE_DB_SECRET)가 없습니다.")
    from datetime import timedelta, timezone
    kst = timezone(timedelta(hours=9))
    old = unseal(Path(prev_path).read_bytes()) if prev_path and Path(prev_path).exists() else {}
    if old is None:   # the key was replaced: start over (days already deleted from Firebase drop out)
        print("지난번 파일을 이 키로 풀지 못해 처음부터 다시 읽습니다 (키를 바꿨다면 정상).")
        old = {}
    since = (old.get("exported_at") or "")[:10].replace("-", "") or None
    judge, fetched = collect(fb_reader(db, key), old.get("judge", {}), since)
    blob = seal({"judge": judge, "exported_at": datetime.now(kst).isoformat(timespec="seconds")})
    Path(out).write_bytes(blob)
    print(f"판정 기록 {count_records(judge)}건 (이번에 읽은 날 {fetched}개) · {len(blob):,}바이트 -> {out}")


def fetch(url, out):
    import ssl
    # Python 3.13+ adds strict X.509 checks that reject company HTTPS-inspection CAs browsers accept ("Basic Constraints
    #   of CA cert not marked critical"). Only those are turned off: the chain and host name are still verified, and a
    #   changed file would not decrypt anyway.
    ctx = ssl.create_default_context()
    ctx.verify_flags &= ~ssl.VERIFY_X509_STRICT
    req = urllib.request.Request(url, headers={"User-Agent": "safealert-judge-ml"})
    try:
        with urllib.request.urlopen(req, timeout=600, context=ctx) as r:
            blob = r.read()
    except urllib.error.HTTPError as e:
        sys.exit(f"받기 실패 (HTTP {e.code}). 아직 내보낸 적이 없거나 주소가 다릅니다: {url}")
    except urllib.error.URLError as e:
        sys.exit(f"받기 실패 ({e.reason}).\n브라우저나 PowerShell 로 받은 뒤 그 파일로 다시 실행하세요:\n"
                 f"  Invoke-WebRequest {url} -OutFile judge.json.gz.enc -UseBasicParsing\n"
                 f"  python judge_ml.py fetch --url file:///<judge.json.gz.enc 의 전체 경로, / 로>")
    data = unseal(blob)
    if data is None:
        sys.exit("암호를 풀지 못했습니다. JUDGE_EXPORT_KEY 가 개인 PC 에서 만든 키와 같은지 확인하세요.")
    Path(out).write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    print(f"판정 기록 {count_records(data.get('judge', {}))}건 (내보낸 때 {data.get('exported_at', '?')}) -> {out}")


def selftest_export():
    try:
        from cryptography.fernet import Fernet
    except ImportError:
        print("cryptography 없음: 내보내기 점검은 건너뜀")
        return
    saved = os.environ.get("JUDGE_EXPORT_KEY")
    os.environ["JUDGE_EXPORT_KEY"] = Fernet.generate_key().decode()
    try:
        tree = {"fall": {"WF11": {d: {f"id{d}": {"k": "cand"}} for d in ("20261028", "20261029", "20261030")}}}

        def fb(path, shallow):
            node = tree
            for part in path.split("/")[2:]:   # wf11/judge/<kind>/<sc>/<day>
                node = node.get(part, {})
            return {k: True for k in node} if shallow else node

        prev = {"fall": {"WF11": {"20261028": {"old": {"k": "kept"}}, "20261029": {"old": {"k": "stale"}}}}}
        judge, fetched = collect(fb, prev, "20261029")                  # the previous export ran on the 29th
        day = judge["fall"]["WF11"]
        assert day["20261028"] == {"old": {"k": "kept"}}, day           # a day finished before that export is not read again
        assert day["20261029"] == {"id20261029": {"k": "cand"}}, day    # the export's own day and later are read again
        assert fetched == 2 and count_records(judge) == 3, (fetched, judge)
        assert collect(fb, judge, "20261028")[1] == 3                     # a daily run was missed: its day is caught up
        blob = seal({"judge": judge})
        assert unseal(blob) == {"judge": judge}
        os.environ["JUDGE_EXPORT_KEY"] = Fernet.generate_key().decode()
        assert unseal(blob) is None                                       # another key: export starts over, fetch stops
    finally:
        if saved is None:
            os.environ.pop("JUDGE_EXPORT_KEY", None)
        else:
            os.environ["JUDGE_EXPORT_KEY"] = saved
    print("내보내기 · 받기 점검 통과")


def main(argv=None):
    for s in (sys.stdout, sys.stderr):   # a console without Korean shows '?' instead of crashing
        getattr(s, "reconfigure", lambda **_: None)(errors="replace")
    ap = argparse.ArgumentParser(description="SafeAlert 판정 데이터 분석 (docs/judge-data/README.md)")
    ap.add_argument("--data", default="data", help="작업 폴더 (기본 ./data)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("load", help="내보낸 JSON -> fall.csv, prox.csv").add_argument("export")
    s = sub.add_parser("split", help="날짜로 학습 / 실제 나누기")
    s.add_argument("--real-from", help="실제 데이터 시작일 yyyyMMdd (기본: 앞서 정한 날, 처음이면 마지막 30%% 날짜)")
    s.add_argument("--ratio", type=float, default=0.3, help="처음 나눌 때 실제 데이터로 둘 날짜 비율")

    def ai_opts(p):
        p.add_argument("--max-usd", type=float, default=5.0, help="이번 실행의 비용 상한(달러, 기본 5)")
        p.add_argument("--limit", type=int, default=0, help="물을 기록 수 상한(0 = 전부)")
        p.add_argument("--dry-run", action="store_true", help="부르지 않고 건수 · 대략 비용만 보기")

    s = sub.add_parser("ai-check", help="AI API 연결 점검(기록 3건)")
    s.add_argument("--kind", choices=list(CLASSES), default="fall")
    s.add_argument("--max-usd", type=float, default=1.0)
    ai_opts(sub.add_parser("ai-label", help="학습 데이터 낙상을 AI 가 1차 분류"))
    s = sub.add_parser("label", help="1차 분류 -> 사람 확인표")
    s.add_argument("--by", choices=["rules", "ai"], default="rules", help="낙상 1차 분류를 규칙으로 할지 AI 답으로 할지")
    s.add_argument("--audit", type=float, default=0.1, help="낙상 확인표에서 가리고 다시 볼 표본 비율")
    sub.add_parser("relabel", help="사람이 채운 확인표 반영 -> v2")
    s = sub.add_parser("learn", help="AI 가 배울 정답 예시 만들기")
    s.add_argument("--kind", choices=list(CLASSES), required=True)
    s.add_argument("--per-class", type=int, default=6, help="종류마다 예시 수")
    s = sub.add_parser("ai-judge", help="AI 판단: 학습 데이터(점검) 또는 실제 데이터(평가용)")
    s.add_argument("--kind", choices=list(CLASSES), required=True)
    s.add_argument("--on", choices=["train", "real"], required=True)
    s.add_argument("--force", action="store_true", help="실제 데이터를 다시 판단(평가가 새어 들 수 있음)")
    ai_opts(s)
    for name in ("train", "eval"):
        sub.add_parser(name).add_argument("--kind", choices=list(CLASSES), required=True)
    sub.add_parser("real-sheet", help="실제 데이터 낙상 정답표")
    sub.add_parser("dist-check", help="거리 답 점검: 답한 시간별 UWB 일치, 답한 구간별 신호 세기")
    sub.add_parser("rssi-check", help="신호별 거리 편차: UWB 거리 기준, 가려짐 의심 구역, 앱 문턱이 놓치는 건")
    sub.add_parser("selftest", help="가짜 데이터 · 대역 AI 로 전 과정 점검")
    s = sub.add_parser("export", help="(GitHub Actions 용) Firebase 판정 기록 -> 암호화 파일")
    s.add_argument("--out", default="judge.json.gz.enc")
    s.add_argument("--prev", help="지난번 내보낸 파일(있으면 합치고 지난번 내보낸 날부터만 다시 읽음)")
    s = sub.add_parser("fetch", help="고정 주소에서 판정 기록을 받아 풀기 -> judge.json")
    s.add_argument("--url", default=EXPORT_URL)
    s.add_argument("--out", default="judge.json")
    a = ap.parse_args(argv)
    data = Path(a.data)
    try:
        if a.cmd == "load":
            load(a.export, data)
        elif a.cmd == "split":
            if a.real_from and not re.fullmatch(r"\d{8}", a.real_from):
                ap.error("--real-from 은 yyyyMMdd 형식입니다")
            split(data, a.real_from, a.ratio)
        elif a.cmd == "ai-check":
            ai_check(data, a.kind, a.max_usd)
        elif a.cmd == "ai-label":
            ai_label(data, a.max_usd, a.limit, a.dry_run)
        elif a.cmd == "label":
            label(data, a.by, a.audit)
        elif a.cmd == "relabel":
            relabel(data)
        elif a.cmd == "learn":
            learn(data, a.kind, a.per_class)
        elif a.cmd == "ai-judge":
            ai_judge(data, a.kind, a.on, a.max_usd, a.limit, a.dry_run, a.force)
        elif a.cmd == "train":
            train(data, a.kind)
        elif a.cmd == "real-sheet":
            real_sheet(data)
        elif a.cmd == "dist-check":
            dist_check(data)
        elif a.cmd == "rssi-check":
            rssi_check(data)
        elif a.cmd == "eval":
            evaluate(data, a.kind)
        elif a.cmd == "export":
            export(a.out, a.prev)
        elif a.cmd == "fetch":
            fetch(a.url, a.out)
        else:
            selftest()
    except AiError as e:
        sys.exit(f"AI 오류: {e}")


if __name__ == "__main__":
    main()
