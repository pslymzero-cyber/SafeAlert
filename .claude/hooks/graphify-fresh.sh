#!/usr/bin/env bash
# Refreshes the graph right before a graphify query.
#
# Why: once a session edits a file, the line numbers of every symbol below the edit shift.
#      A stale line number is worse than none — you read the wrong range
#      and edit the wrong place. Measured: after inserting 40 lines, explain kept
#      pointing at L3; a single update corrected it to L43.
#
# Cost: about 1.2 s with no changes, about 2.5 s with changes. Paid only when querying.

# If the global hook (~/.claude/hooks/) is set up, it handles this instead.
[ -f "$HOME/.claude/hooks/graphify-fresh.sh" ] && \
  grep -q graphify-fresh "$HOME/.claude/settings.json" 2>/dev/null && exit 0

j=$(tr -d '\n')

# Runs only for read-type queries, never for update/extract/label themselves.
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
