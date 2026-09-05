## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## task-observer

This project uses the task-observer skill (rebelytics/one-skill-to-rule-them-all),
installed at user scope in `~/.claude/skills/task-observer/`.

The observation-log workspace is pinned as an absolute path in each developer's own
`~/.claude/CLAUDE.md` activation block, not here — the path differs per machine, and a
log anchored inside an ephemeral checkout is torn down with it (see the skill's
`references/environments.md`).

If the skill is not installed, ignore this section.
