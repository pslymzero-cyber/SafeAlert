#!/usr/bin/env python3
"""Recomputes the context budget table: the source numbers for the "files never to read whole" table
(전체 읽기 금지 파일) in .claude/CLAUDE.md.

Hand-maintained counts always go stale. A session that trusts a stale table to stay within budget
can burn its whole budget on a single file, so a stale guard turns into a trap.

This script is read-only: it only prints the numbers as a table and writes no files.
GSD updates CLAUDE.md (it owns that file through claude_md_path in config.json).

Usage:
    python3 scripts/context-budget.py                # default threshold 10,000 tokens
    python3 scripts/context-budget.py -t 5000        # change the threshold
    python3 scripts/context-budget.py --self-check   # self-check
"""
import argparse
import os
import subprocess
import sys

# Token estimate factor, calibrated on this repo's AlertStateMachine.kt:
# 157,667 bytes / 47,948 tokens (measured value in CLAUDE.md) = 3.29 bytes/token.
# Based on Kotlin and Markdown mixed with Korean comments; assume a ±10% error.
BYTES_PER_TOKEN = 3.29

# Binaries and generated files, where counting tokens is meaningless
SKIP_EXT = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".wav", ".mp3", ".ogg",
            ".jar", ".zip", ".apk", ".keystore", ".jks", ".p12", ".pptx",
            ".docx", ".pdf", ".ttf", ".otf", ".ico"}


def tokens(path):
    return os.path.getsize(path) / BYTES_PER_TOKEN


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
    rows = [(tokens(p), p) for p in tracked_files()]

    # .planning/** is counted as one lump, not as individual rows — same as the CLAUDE.md table
    planning = sum(t for t, p in rows if p.startswith(".planning/"))
    rows = [(t, p) for t, p in rows if not p.startswith(".planning/")]
    rows.sort(reverse=True)

    print("| 파일 | 토큰 |")
    print("|---|---|")
    for t, p in rows:
        if t < threshold:
            break
        print(f"| {p} | {t:,.0f} |")
    print(f"| .planning/** (전체 {planning:,.0f}) | 전량 |")

    total = sum(t for t, _ in rows) + planning
    print(f"\n추적 파일 전체 ≈ {total:,.0f} 토큰 "
          f"(임계 {threshold:,} 이상 {sum(1 for t, _ in rows if t >= threshold)}개 표시)",
          file=sys.stderr)


def self_check():
    assert tokens(__file__) > 0, "자기 자신의 크기를 못 잰다"
    fs = list(tracked_files())
    assert fs, "git 추적 파일이 하나도 안 잡힌다 — 저장소 루트에서 실행했나?"
    assert not any(os.path.splitext(p)[1].lower() in SKIP_EXT for p in fs), \
        "바이너리가 걸러지지 않았다"
    # If the calibration basis drifts, the factor must be recalibrated
    ref = "app/src/main/java/com/wf11/safealert/03_service/AlertStateMachine.kt"
    if os.path.exists(ref):
        est = tokens(ref)
        assert 43_000 < est < 53_000, f"보정 기준 파일 추정치 이탈: {est:,.0f}"
    print("self-check OK")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("-t", "--threshold", type=int, default=10_000,
                    help="표에 넣을 최소 토큰 (기본 10000)")
    ap.add_argument("--self-check", action="store_true")
    a = ap.parse_args()
    self_check() if a.self_check else report(a.threshold)
