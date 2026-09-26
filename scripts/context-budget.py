#!/usr/bin/env python3
"""컨텍스트 예산표 재계산 — .claude/CLAUDE.md 「전체 읽기 금지 파일」 표의 원본 수치.

손으로 세면 반드시 낡는다. 실측 사례: 표에 PROGRESS.md 가 14,051 토큰으로
적혀 있었으나 실제는 32,894 였다(2.3배). 예산을 지키려고 표를 믿은 세션이
한 파일로 예산 전체를 날린다 — 방어선이 낡으면 함정이 된다.

이 스크립트는 읽기 전용이다. 수치를 표 형식으로 출력만 하고 파일은 쓰지 않는다.
CLAUDE.md 반영은 GSD 가 한다(config.json 의 claude_md_path 가 그 파일의 주인).

사용:
    python3 scripts/context-budget.py                # 기본 임계 10,000 토큰
    python3 scripts/context-budget.py -t 5000        # 임계 변경
    python3 scripts/context-budget.py --self-check   # 자체 검사
"""
import argparse
import os
import subprocess
import sys

# 토큰 추정 계수. 이 저장소의 AlertStateMachine.kt 로 보정했다:
# 157,667 바이트 / 47,948 토큰(CLAUDE.md 실측치) = 3.29 바이트/토큰.
# 한글 주석이 섞인 Kotlin·Markdown 기준이며 ±10% 오차를 전제로 쓴다.
BYTES_PER_TOKEN = 3.29

# 토큰을 세는 의미가 없는 바이너리·생성물
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

    # .planning/** 은 개별 행이 아니라 한 덩어리로 다룬다 — CLAUDE.md 표와 같은 취급
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
    # 보정 근거가 흔들리면 계수를 다시 잡아야 한다
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
