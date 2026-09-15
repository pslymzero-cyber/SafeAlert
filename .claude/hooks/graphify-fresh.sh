#!/usr/bin/env bash
# graphify 조회 직전에 그래프를 갱신한다.
#
# 왜: 세션이 파일을 한 번 고치면 그 아래 모든 심볼의 줄 번호가 밀린다.
#     낡은 줄 번호는 정보가 없는 것보다 나쁘다 — 엉뚱한 구간을 읽고
#     엉뚱한 곳을 고치게 된다. 실측으로 40줄 삽입 후 explain 이 L3 을
#     계속 가리켰고, update 한 번에 L43 으로 맞았다.
#
# 비용: 변경 없으면 약 1.2초, 있으면 약 2.5초. 조회할 때만 낸다.

# 전역 훅(~/.claude/hooks/)이 깔려 있으면 그쪽이 처리한다.
[ -f "$HOME/.claude/hooks/graphify-fresh.sh" ] && \
  grep -q graphify-fresh "$HOME/.claude/settings.json" 2>/dev/null && exit 0

j=$(tr -d '\n')

# 읽기 계열 조회에만 붙인다. update/extract/label 자신에게는 붙이지 않는다.
case "$j" in
  *"graphify explain"*|*"graphify query"*|*"graphify path"*|\
  *"graphify affected"*|*"graphify god-nodes"*) ;;
  *) exit 0 ;;
esac

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0
[ -f graphify-out/graph.json ] || exit 0
command -v graphify >/dev/null 2>&1 || exit 0
graphify update . >/dev/null 2>&1
exit 0
