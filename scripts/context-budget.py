#!/usr/bin/env python3
"""Recomputes the context budget table: the source numbers for the "files never to read whole" table
(전체 읽기 금지 파일) in .claude/CLAUDE.md.

Hand-maintained counts always go stale. A session that trusts a stale table to stay within budget
can burn its whole budget on a single file, so a stale guard turns into a trap.

This script is read-only: it prints the table and writes no files. Its rows paste into
.claude/CLAUDE.md as they are; GSD never updates that table, which sits outside its marker blocks.

Usage:
    python3 scripts/context-budget.py                # default threshold 10,000 tokens
    python3 scripts/context-budget.py -t 5000        # change the threshold
    python3 scripts/context-budget.py --self-check   # self-check
"""
import argparse
import glob
import os
import re
import subprocess
import sys

# Token estimate factor (bytes per token): LoneWorkerLogic.kt + BleAdvertiser.kt as checked out on
# Windows (CRLF, 75,610 bytes) over the 35,461 tokens the Read tool reported for them, line-number
# prefixes included. About ±10% for code with English comments; an LF checkout runs a little low.
# ponytail: one factor for every file. Korean text is denser (the same two files with Korean comments
# measured 1.88), so the PROGRESS rows run low; weight by non-ASCII share if they must be exact.
# Re-measure after a model or tokenizer change, or when the mix of languages or file types changes.
BYTES_PER_TOKEN = 2.13

# Local-only files the table lists although git never sees them (.git/info/exclude)
LOCAL = "PROGRESS*.md"
# Listed whatever its size: .claude/CLAUDE.md sends readers to line ranges of it
ALWAYS_LISTED = {"docs/ARCHITECTURE.md"}

# Binaries and generated files, where counting tokens is meaningless
SKIP_EXT = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".wav", ".mp3", ".ogg",
            ".jar", ".zip", ".apk", ".keystore", ".jks", ".p12", ".pptx",
            ".docx", ".pdf", ".ttf", ".otf", ".ico"}


def tokens(path):
    return os.path.getsize(path) / BYTES_PER_TOKEN


def short(path):
    # The table's short forms: 03_service/X.kt, test/ble/X.kt, res/layout/x.xml
    return re.sub(r"^app/src/(main/)?|java/com/wf11/safealert/", "", path)


def tracked_files():
    out = subprocess.run(["git", "ls-files", "-z"],
                         capture_output=True, check=True).stdout
    for raw in out.split(b"\0"):
        if not raw:
            continue
        p = raw.decode("utf-8", "surrogateescape")
        if os.path.splitext(p)[1].lower() in SKIP_EXT:
            continue
        if os.path.exists(p):
            yield p


def report(threshold):
    files = set(tracked_files()) | set(glob.glob(LOCAL))
    rows = sorted(((tokens(p), p) for p in files), reverse=True)
    shown = [(t, p) for t, p in rows if t >= threshold or p in ALWAYS_LISTED]

    print("| 파일 | 토큰 |")
    print("|---|---|")
    for t, p in shown:
        print(f"| {short(p)} | {t:,.0f} |")
    # .planning/** is untracked here, so git ls-files never lists it and it gets a fixed row
    print("| .planning/** | 전량 |")

    print(f"\n대상 파일 전체 ≈ {sum(t for t, _ in rows):,.0f} 토큰 "
          f"({len(shown)}개 표시, 임계 {threshold:,})", file=sys.stderr)


def self_check():
    assert tokens(__file__) > 0, "자기 자신의 크기를 못 잰다"
    fs = list(tracked_files())
    assert fs, "git 추적 파일이 하나도 안 잡힌다"
    assert not any(os.path.splitext(p)[1].lower() in SKIP_EXT for p in fs), \
        "바이너리가 걸러지지 않았다"
    for full, want in [("app/src/main/java/com/wf11/safealert/03_service/BleService.kt", "03_service/BleService.kt"),
                       ("app/src/test/java/com/wf11/safealert/ble/A.kt", "test/ble/A.kt"),
                       ("app/src/main/res/layout/a.xml", "res/layout/a.xml"),
                       (".github/scripts/analyze_alerts.py", ".github/scripts/analyze_alerts.py")]:
        assert short(full) == want, f"짧은 경로 변환 오류: {full} → {short(full)}"
    print("self-check OK")


if __name__ == "__main__":
    # The Korean cells must reach a pipe or file as UTF-8; a Windows pipe defaults to the ANSI code page
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    # git ls-files and every path here are relative to the repo root
    os.chdir(subprocess.run(["git", "rev-parse", "--show-toplevel"],
                            capture_output=True, check=True, text=True).stdout.strip())
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("-t", "--threshold", type=int, default=10_000,
                    help="표에 넣을 최소 토큰 (기본 10000)")
    ap.add_argument("--self-check", action="store_true")
    a = ap.parse_args()
    self_check() if a.self_check else report(a.threshold)
