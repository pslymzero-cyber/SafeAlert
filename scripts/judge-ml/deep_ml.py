"""회사 PC 딥러닝 비교 — 넘어짐은 원본 가속도 파형 1차원 CNN, 거리는 블루투스 값 + 직전 10초 신경망 (참고용 보고서)

  python deep_ml.py fall <judge.json> [--fall-dev ID끝,…] [--marks 파일] [--out out_deep] [--epochs N] [--seed S]
  python deep_ml.py prox <judge.json> [--days YYYY-MM-DD,…] [--out out_deep] [--epochs N] [--seed S]
  python deep_ml.py selftest

앱 코드는 건드리지 않고 app_model(읽기 · 정답 · 지금 규칙 · Ridge/MLP)을 그대로 가져다 쓴다. 앱에 탑재하지 않는다.
필요: python -m pip install torch --index-url https://download.pytorch.org/whl/cpu  (회사 망 SSL 오류면 --use-feature=truststore 를 붙여 다시)
"""
import argparse
import json
import random
import sys
import tempfile
import warnings
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import app_model as am  # noqa: E402
import judge_ml as jm  # noqa: E402

HERE = Path(__file__).resolve().parent
SR, T_LO, T_HI = 50, -3.0, 12.0          # 파형 격자: 50Hz, 충격 3초 전 ~ 12초 뒤
N_T = int((T_HI - T_LO) * SR)            # 750칸
LEVELS = {False: (5.0, 3.0), True: (15.0, 8.0)}   # (경고 m, 위험 m): 지게차 아닌 조합 / 지게차 조합
SEQ_N = 10
INSTALL = "torch 가 없습니다. 설치: python -m pip install torch --index-url https://download.pytorch.org/whl/cpu\n회사 망 SSL 오류면 --use-feature=truststore 를 붙여 다시 실행하세요."


def need_torch():
    try:
        import torch
    except ImportError:
        sys.exit(INSTALL)   # 문자열이면 종료 코드 1
    torch.set_num_threads(max(1, min(4, torch.get_num_threads())))
    return torch


pct = lambda a, b: "-" if b == 0 else f"{a}/{b} ({a * 100 / b:.0f}%)"


# ── 넘어짐: 파형 ──────────────────────────────────────────────────────────

def wave(r):
    """한 후보의 가속도 -> (3, 750) g. 시각 = acc_t0 + i·acc_dt − imp_t, [−3, +12)초를 50Hz 로 선형 보간(범위 밖 0). 없으면 None."""
    acc, t0, dt, imp = r.get("acc"), jm.fnum(r.get("acc_t0")), jm.fnum(r.get("acc_dt")) or 20.0, jm.fnum(r.get("imp_t"))
    if not isinstance(acc, str) or not acc or t0 is None or imp is None:
        return None
    try:
        a = np.array([[float(v) for v in s.split(",")] for s in acc.split(";") if s], dtype=float) / 1000.0
    except ValueError:
        return None
    if a.ndim != 2 or a.shape[1] != 3 or len(a) < 2:
        return None
    t = (t0 + np.arange(len(a)) * dt - imp) / 1000.0
    grid = T_LO + np.arange(N_T) / SR
    return np.stack([np.interp(grid, t, a[:, i], left=0.0, right=0.0) for i in range(3)]).astype(np.float32)


def chans(w):
    """(3, T) 또는 (n, 3, T) -> x · y · z · |a| 네 채널."""
    return np.concatenate([w, np.linalg.norm(w, axis=-2, keepdims=True)], axis=-2).astype(np.float32)


def rot3(rng):
    q, r = np.linalg.qr(rng.normal(size=(3, 3)))
    q = q * np.sign(np.diag(r))
    if np.linalg.det(q) < 0:
        q[:, 0] = -q[:, 0]
    return q


def augment(w, rng):
    """학습 때만: 3축 무작위 회전(폰 방향) · 크기 0.9~1.1배 · 시간 ±0.2초 · 잡음 0.01g."""
    w = (rot3(rng) @ w) * rng.uniform(0.9, 1.1)
    s = int(rng.integers(-SR // 5, SR // 5 + 1))
    if s:
        z = np.zeros_like(w)
        if s > 0:
            z[:, s:] = w[:, :-s]
        else:
            z[:, :s] = w[:, -s:]
        w = z
    return w + rng.normal(0, 0.01, w.shape)


def fall_net(torch):
    nn = torch.nn

    class FallNet(nn.Module):
        def __init__(self):
            super().__init__()
            self.f = nn.Sequential(nn.Conv1d(4, 16, 7, padding=3), nn.ReLU(), nn.MaxPool1d(4),
                                   nn.Conv1d(16, 32, 5, padding=2), nn.ReLU(), nn.MaxPool1d(4),
                                   nn.Conv1d(32, 32, 5, padding=2), nn.ReLU())
            self.o = nn.Linear(64, 1)

        def forward(self, x):
            h = self.f(x)
            return self.o(torch.cat([h.mean(2), h.amax(2)], 1)).squeeze(1)   # 전역 평균 + 최대

    return FallNet()


def fit_fall(torch, W, y, epochs, seed, batch=32):
    torch.manual_seed(seed)
    rng = np.random.default_rng(seed)
    net = fall_net(torch)
    opt = torch.optim.Adam(net.parameters(), lr=3e-3, weight_decay=1e-4)
    pos, neg = np.where(y == 1)[0], np.where(y == 0)[0]
    reps = max(1, round(len(neg) / max(1, len(pos))))   # 넘어짐 표본 반복: 한 에폭에 넘어짐 · 아님이 비슷한 수
    net.train()
    for _ in range(epochs):
        idx = rng.permutation(np.concatenate([neg, np.tile(pos, reps)]))
        for b in range(0, len(idx), batch):
            bi = idx[b:b + batch]
            xb = torch.from_numpy(np.stack([chans(augment(W[i], rng)) for i in bi]))
            loss = torch.nn.functional.binary_cross_entropy_with_logits(net(xb), torch.from_numpy(y[bi].astype(np.float32)))
            opt.zero_grad()
            loss.backward()
            opt.step()
    return net


def predict_fall(torch, net, W):
    net.eval()
    with torch.no_grad():
        return torch.sigmoid(net(torch.from_numpy(chans(W)))).numpy()


def marks_acts(path):
    """표시 파일 한 줄마다 (기기 끝, 시작 ms, 끝 ms, 뒤에 적은 설명). 설명이 없으면 건너뜀."""
    out = []
    for raw in Path(path).read_text(encoding="utf-8-sig").splitlines():
        code, _, note = raw.partition("#")
        if not code.strip() or not note.strip():
            continue
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "m.txt"
            f.write_text(code + "\n", encoding="utf-8")
            got = am.read_marks(f)
        if got:
            out.append((got[0][0], got[0][1], got[0][2], note.strip()))
    return out


def fall(export, out, devs=None, marks=None, epochs=80, seed=0):
    torch = need_torch()
    df = am.load(export)
    c, _, _ = am.fall_rows(df, marks)
    if devs:
        c = c[c["dev"].astype(str).map(lambda d: any(d.endswith(x) for x in devs))]
    waves = [wave(r) for r in c.to_dict("records")]
    ok = np.array([w is not None for w in waves])
    c = c[ok].reset_index(drop=True)
    if not len(c):
        sys.exit("가속도 파형이 있는 후보가 없습니다(--fall-dev · 표시 파일 확인).")
    W = np.stack([w for w in waves if w is not None])
    y = c["label"].to_numpy(float)
    pos, neg = np.where(y == 1)[0], np.where(y == 0)[0]
    k = len(pos)
    if k < 2 or len(neg) < k:
        sys.exit(f"교차검증에 넘어짐 2건 이상, 아님은 넘어짐 수 이상이 필요합니다(넘어짐 {k} · 아님 {len(neg)}).")
    print(f"넘어짐 후보 {len(c)}건 (넘어짐 {k} · 아님 {len(neg)}, 파형 없는 {int((~ok).sum())}건 제외) — {k}겹 교차검증")
    rng = np.random.default_rng(seed)
    parts = np.array_split(rng.permutation(neg), k)
    prob = np.full(len(y), np.nan)
    for i in range(k):   # 넘어짐 하나씩 빼기: 겹 i = 넘어짐 i + 아님 1/k
        te = np.concatenate([[pos[i]], parts[i]]).astype(int)
        tr = np.setdiff1d(np.arange(len(y)), te)
        prob[te] = predict_fall(torch, fit_fall(torch, W[tr], y[tr], epochs, seed + i), W[te])
        print(f"  겹 {i + 1}/{k} 끝")
    thr_all = float(prob[pos].min())   # 넘어짐을 모두 잡는 가장 높은 기준
    rule = c["pass"].map(jm.yes).to_numpy(bool)
    acts = np.array(["(설명 없음)"] * len(c), dtype=object)
    if marks:
        for who, a, b, note in marks_acts(marks):
            hit = c["dev"].astype(str).str.endswith(who).to_numpy() & c["t"].between(a, b).to_numpy()
            acts[hit & (acts == "(설명 없음)")] = note
    methods = [("지금 규칙(cand pass)", rule), ("신경망 기준 0.5", prob >= 0.5), (f"신경망 모두 잡는 기준 {thr_all:.3g}", prob >= thr_all)]
    lines = ["# 넘어짐 — 원본 가속도 파형 1차원 CNN (참고용)", "",
             f"- 후보 {len(c)}건: 넘어짐 {k} · 아님 {len(neg)}" + (f" · 기기 {len(devs)}대만(--fall-dev)" if devs else "") + f" · seed {seed} · 에폭 {epochs}",
             f"- 평가: 넘어짐 하나씩 빼는 {k}겹 교차검증 — 학습에 쓰지 않은 기록의 확률로만 점수",
             f"- **넘어짐 {k}건뿐이라 참고용이다.** 건수가 이만큼 적으면 몇 건 차이로 결론이 뒤집힌다.", "",
             "| 방법 | 넘어짐을 넘어짐으로 | 아님을 넘어짐으로 |", "|---|---:|---:|"]
    for name, m in methods:
        lines.append(f"| {name} | {pct(int(m[pos].sum()), k)} | {pct(int(m[neg].sum()), len(neg))} |")
    lines += ["", "## 아님 — 설명(act)별 오경보 건수", "", "| act | 건수 | " + " | ".join(n for n, _ in methods) + " |", "|---|---:|" + "---:|" * len(methods)]
    for a in sorted(set(acts[neg])):
        g = neg[acts[neg] == a]
        lines.append(f"| {a} | {len(g)} | " + " | ".join(f"{int(m[g].sum())}" for _, m in methods) + " |")
    out.mkdir(parents=True, exist_ok=True)
    net = fit_fall(torch, W, y, epochs, seed)
    torch.save({"state": net.state_dict(), "arch": "fall_cnn v1 (4ch x 750, 50Hz, -3..+12s)", "thr_all": thr_all}, out / "fall_cnn.pt")
    lines += ["", f"- 전체 기록으로 한 번 학습한 모델: fall_cnn.pt (파라미터 {sum(p.numel() for p in net.parameters())}개)",
              "- 앱에 탑재하지 않는다(앱 실행 부분은 dense 층만이라 파형 CNN 은 실행 부분을 새로 만들어야 한다)."]
    rep = out / "fall_report.md"
    rep.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"-> {rep}")
    return rep


# ── 거리 ──────────────────────────────────────────────────────────────────

def seq_array(r):
    """seq "ema,vel;…" 마지막 10칸 -> (2, 10). 모자라면 앞을 첫 칸으로 채우고, 없으면 ema · 0."""
    e, v = [], []
    for part in str(r.get("seq") or "").split(";"):
        a = part.split(",")
        if len(a) == 2:
            try:
                e.append(float(a[0]))
                v.append(float(a[1]))
            except ValueError:
                pass
    if not e:
        e, v = [jm.fnum(r.get("ema")) or 0.0], [0.0]
    e, v = e[-SEQ_N:], v[-SEQ_N:]
    pad = SEQ_N - len(e)
    return np.array([[e[0]] * pad + e, [v[0]] * pad + v], dtype=np.float32)


def prox_net(torch):
    nn = torch.nn

    class ProxNet(nn.Module):
        def __init__(self, n_static):
            super().__init__()
            self.c = nn.Sequential(nn.Conv1d(2, 8, 3, padding=1), nn.ReLU(), nn.Conv1d(8, 8, 3, padding=1), nn.ReLU())
            self.h = nn.Sequential(nn.Linear(n_static + 16, 32), nn.ReLU(), nn.Linear(32, 1))

        def forward(self, s, x):
            h = self.c(s)
            return self.h(torch.cat([x, h.mean(2), h[:, :, -1]], 1)).squeeze(1)

    return ProxNet


def norm_static(Xtr, Xte_list):
    fill = np.nanmedian(Xtr, axis=0)
    fill = np.where(np.isnan(fill), 0.0, fill)
    f = lambda X: np.where(np.isnan(X), fill, X)
    mean, std = f(Xtr).mean(0), f(Xtr).std(0)
    std = np.where(std < 1e-9, 1.0, std)
    return [(f(X) - mean) / std for X in [Xtr] + list(Xte_list)], (fill, mean, std)


def norm_seq(Str, Ste_list):
    m, s = Str[:, 0].mean(), Str[:, 0].std() or 1.0
    vs = Str[:, 1].std() or 1.0
    f = lambda S: np.stack([(S[:, 0] - m) / s, S[:, 1] / vs], 1).astype(np.float32)
    return [f(S) for S in [Str] + list(Ste_list)], (float(m), float(s), float(vs))


def fit_prox(torch, S, X, y, epochs, seed, batch=256):
    torch.manual_seed(seed)
    rng = np.random.default_rng(seed)
    net = prox_net(torch)(X.shape[1])
    opt = torch.optim.Adam(net.parameters(), lr=3e-3, weight_decay=1e-4)
    S, X, y = torch.from_numpy(S), torch.from_numpy(X.astype(np.float32)), torch.from_numpy(y.astype(np.float32))
    for _ in range(epochs):
        net.train()
        for bi in np.array_split(rng.permutation(len(y)), max(1, len(y) // batch)):
            loss = torch.nn.functional.smooth_l1_loss(net(S[bi], X[bi]), y[bi])
            opt.zero_grad()
            loss.backward()
            opt.step()
    net.eval()
    return net


def pred_prox(torch, net, S, X):
    with torch.no_grad():
        return net(torch.from_numpy(S), torch.from_numpy(X.astype(np.float32))).numpy()


def to_level(m, fork):
    warn = np.where(fork, LEVELS[True][0], LEVELS[False][0])
    dang = np.where(fork, LEVELS[True][1], LEVELS[False][1])
    return am.level(np.asarray(m, float), warn, dang)


def prox(export, out, days=None, epochs=40, seed=0):
    torch = need_torch()
    from sklearn.linear_model import Ridge
    from sklearn.model_selection import GroupKFold
    from sklearn.neural_network import MLPRegressor
    df = am.load(export)
    p, X, y = am.prox_rows(df)
    if days:
        kst = (pd.to_datetime(p["t"].astype("int64"), unit="ms") + jm.KST).dt.strftime("%Y-%m-%d")
        keep = kst.isin(days).to_numpy()
        p, X, y = p[keep].reset_index(drop=True), X[keep], y[keep]
    e, w, d = (pd.to_numeric(p[c], errors="coerce").to_numpy(float) for c in ("ema", "eff_w", "eff_d"))
    ok = ~np.isnan(e) & ~np.isnan(w) & ~np.isnan(d)
    p, X, y = p[ok].reset_index(drop=True), X[ok], y[ok]
    e, w, d = e[ok], w[ok], d[ok]
    if len(p) < 100:
        sys.exit(f"거리 기록이 너무 적습니다({len(p)}건).")
    S = np.stack([seq_array(r) for r in p.to_dict("records")])
    groups = am.blocks(p["t"])
    if len(np.unique(groups)) < 2:
        sys.exit("기록이 5분 묶음 하나뿐이라 교차검증을 할 수 없습니다.")
    fork = (p["pair"] == am.PAIRS[3]).to_numpy()
    truth_m = 10 ** y
    truth = to_level(truth_m, fork)
    print(f"거리 기록 {len(p)}건" + (f" ({', '.join(days)})" if days else "") + f" — 5분 묶음 {min(5, len(np.unique(groups)))}겹 교차검증")
    pred = {k: np.full(len(y), np.nan) for k in ("ridge", "mlp", "net")}
    for i, (tr, te) in enumerate(GroupKFold(n_splits=min(5, len(np.unique(groups)))).split(X, y, groups)):
        (Ztr, Zte), _ = norm_static(X[tr], [X[te]])
        (Str, Ste), _ = norm_seq(S[tr], [S[te]])
        pred["ridge"][te] = Ridge(alpha=1.0).fit(Ztr, y[tr]).predict(Zte)
        pred["mlp"][te] = MLPRegressor(hidden_layer_sizes=(16,), activation="relu", alpha=1e-3, max_iter=3000, random_state=0).fit(Ztr, y[tr]).predict(Zte)
        pred["net"][te] = pred_prox(torch, fit_prox(torch, Str, Ztr, y[tr], epochs, seed + i), Ste, Zte)
        print(f"  겹 {i + 1} 끝")
    meters = {k: np.clip(10 ** v, *am.CLAMP) for k, v in pred.items()}
    lv = {"지금 규칙(ema vs eff_w · eff_d)": np.where(e >= d, 2, np.where(e >= w, 1, 0)).astype(float)}
    lv.update({n: to_level(meters[k], fork).astype(float) for n, k in (("Ridge", "ridge"), ("MLP", "mlp"), ("신경망(1D CNN)", "net"))})
    lines = ["# 거리 — 블루투스 값 + 직전 10초 신경망 (참고용)", "",
             f"- 기록 {len(p)}건" + (f" · 날짜 {', '.join(days)}" if days else "") + f" · seed {seed} · 에폭 {epochs}",
             "- 정답 = UWB 거리의 단계(지게차 아닌 조합 경고 5m · 위험 3m, 지게차 조합 15m · 8m). 평가: 5분 묶음으로 나눈 교차검증 — 학습에 쓰지 않은 기록만",
             "- 입력: 블루투스 값 13개(app_model PROX_IN) + 직전 10칸 seq(ema · 속도). 기종 칸은 쓰지 않음(네 방법 모두 같은 입력)",
             "- 기록 안 지금 규칙은 그 기록의 eff_w · eff_d 를 쓴다.", "",
             "| 역할 조합 | 방법 | 건수 | 맞힘 | 위험을 위험으로 | 안전을 위험으로 |", "|---|---|---:|---:|---:|---:|"]
    pc = lambda v: "-" if v is None else f"{v * 100:.0f}%"
    for name in am.PAIRS + ["그 밖", "전체"]:
        sel = np.ones(len(p), bool) if name == "전체" else (p["pair"] == name).to_numpy()
        if sel.sum() < am.MIN_GROUP:
            if name != "그 밖":
                lines.append(f"| {name} | 기록 부족({int(sel.sum())}건) | | | | |")
            continue
        for m, l in lv.items():
            s = am.scores(truth[sel], l[sel], "prox")
            lines.append(f"| {name} | {m} | {s['n']} | {pc(s['acc'])} | {pc(s.get('catch'))} | {pc(s.get('false'))} |")
    lines += ["", "거리 오차 중앙값(m): " + " · ".join(f"{n} {np.median(np.abs(meters[k] - truth_m)):.2f}" for n, k in (("Ridge", "ridge"), ("MLP", "mlp"), ("신경망", "net")))]
    out.mkdir(parents=True, exist_ok=True)
    (Zall,), (fill, mean, std) = norm_static(X, [])
    (Sall,), (sm, ss, vs) = norm_seq(S, [])
    net = fit_prox(torch, Sall, Zall, y, epochs, seed)
    torch.save({"state": net.state_dict(), "inputs": am.PROX_IN, "fill": fill, "mean": mean, "std": std, "seq": (sm, ss, vs), "out": "log10_m"}, out / "prox_net.pt")
    lines += ["", f"- 전체 기록으로 한 번 학습한 모델: prox_net.pt (파라미터 {sum(q.numel() for q in net.parameters())}개). 앱에 탑재하지 않는다."]
    rep = out / "prox_report.md"
    rep.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"-> {rep}")
    return rep


# ── selftest ──────────────────────────────────────────────────────────────

def selftest():
    need_torch()
    rng = np.random.default_rng(0)
    exp_d = am.fake_export(rng)
    cands = exp_d["wf11"]["judge"]["fall"]["WF11"]["20261030"]
    for i in range(40):   # 가짜 파형: 충격 3초 전부터 50Hz 750칸
        c = cands[f"c{i}"]
        c.update(acc=jm.fake_acc(random.Random(i), i % 2 == 0), acc_t0=c["imp_t"] - 3000, acc_dt=20)
    w = wave({"acc": cands["c0"]["acc"], "acc_t0": cands["c0"]["acc_t0"], "acc_dt": 20, "imp_t": cands["c0"]["imp_t"]})
    assert w.shape == (3, N_T) and abs(float(chans(w)[3].max()) - 4.2) < 0.1 and abs(int(chans(w)[3].argmax()) - 150) <= 5, w.shape   # 충격 = 격자 150칸
    w2 = wave({"acc": cands["c0"]["acc"], "acc_t0": cands["c0"]["acc_t0"] + 5000, "acc_dt": 20, "imp_t": cands["c0"]["imp_t"]})
    assert (w2[:, :250] == 0).all() and w2[2, 300] != 0   # 자료가 +2초부터라 -3~+2초는 자료 밖 = 0
    assert wave({"acc": "", "acc_t0": 1, "imp_t": 1}) is None and wave({}) is None
    r = np.random.default_rng(1)
    q = rot3(r)
    assert np.allclose(q @ q.T, np.eye(3), atol=1e-9) and abs(np.linalg.det(q) - 1) < 1e-9
    aw = augment(w, r)
    assert aw.shape == w.shape and 0.8 * 4.2 < np.linalg.norm(aw, axis=0).max() < 1.2 * 4.2   # 회전 · 크기 · 이동 뒤에도 충격 크기가 크게 안 변함
    s = seq_array({"seq": "-70,0.1;-68,0.2", "ema": -68})
    assert s.shape == (2, SEQ_N) and s[0, 0] == -70 and s[0, -1] == -68 and s[1, -1] == np.float32(0.2)
    assert seq_array({"ema": -60}).shape == (2, SEQ_N)
    with tempfile.TemporaryDirectory() as tmp:
        exp = Path(tmp) / "export.json"
        exp.write_text(json.dumps(exp_d), encoding="utf-8")
        mk = Path(tmp) / "marks.txt"
        mk.write_text("X1234567 2025-10-30 14:00-14:00 넘어짐   # m0\nX1234567 2025-10-30 14:02-14:03 no   # 앉기\n", encoding="utf-8")
        assert [a[3] for a in marks_acts(mk)] == ["m0", "앉기"]
        out = Path(tmp) / "o"
        rep = fall(exp, out, epochs=6)
        txt = rep.read_text(encoding="utf-8")
        assert "넘어짐 20 · 아님 20" in txt and "지금 규칙(cand pass)" in txt and "참고용" in txt and (out / "fall_cnn.pt").exists(), txt
        rep = prox(exp, out, days=["2025-10-30"], epochs=3)
        txt = rep.read_text(encoding="utf-8")
        assert all(f"| 전체 | {m} |" in txt for m in ("지금 규칙(ema vs eff_w · eff_d)", "Ridge", "MLP", "신경망(1D CNN)")), txt
        assert all(f"| {g} | " in txt for g in am.PAIRS), txt
        torch = need_torch()
        ck = torch.load(out / "prox_net.pt", weights_only=False)
        assert ck["inputs"] == am.PROX_IN and len(ck["fill"]) == 13
    print("selftest ok")


def main():
    for s in (sys.stdout, sys.stderr):   # cp949 콘솔에서도 안 멈추게
        getattr(s, "reconfigure", lambda **_: None)(errors="replace")
    warnings.filterwarnings("ignore")
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fall", help="넘어짐: 파형 1D CNN 하나씩 빼기 교차검증")
    f.add_argument("export")
    f.add_argument("--fall-dev", default=None, help="넘어짐 실험 폰 ID 끝 부분들(쉼표). 없으면 전부")
    f.add_argument("--marks", default=None, help=am.MARKS_HELP)
    f.add_argument("--out", default="out_deep")
    f.add_argument("--epochs", type=int, default=80)
    f.add_argument("--seed", type=int, default=0)
    q = sub.add_parser("prox", help="거리: 신경망 vs 지금 규칙 · Ridge · MLP")
    q.add_argument("export")
    q.add_argument("--days", default=None, help="YYYY-MM-DD 쉼표(한국 시간). 없으면 전부")
    q.add_argument("--out", default="out_deep")
    q.add_argument("--epochs", type=int, default=40)
    q.add_argument("--seed", type=int, default=0)
    sub.add_parser("selftest")
    a = ap.parse_args()
    if a.cmd == "fall":
        fall(a.export, Path(a.out), [x for x in (a.fall_dev or "").split(",") if x] or None, a.marks, a.epochs, a.seed)
    elif a.cmd == "prox":
        prox(a.export, Path(a.out), [x for x in (a.days or "").split(",") if x] or None, a.epochs, a.seed)
    else:
        selftest()


if __name__ == "__main__":
    main()
