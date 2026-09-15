---
phase: quick-260914-lqs
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - .claude/CLAUDE.md
autonomous: true
requirements:
  - QUICK-260914-lqs

estimate:
  tokens: 15000
  raw_tokens: 15000
  tasks: 1
  confidence: low

must_haves:
  truths:
    - ".claude/CLAUDE.md 의 '전체 읽기 금지 파일' 표 데이터 행이 context-budget 실측값 15행과 정확히 일치한다"
    - "표 헤더 2행(| 파일 | 토큰 |, |---|---|)과 표 밖의 모든 줄은 바이트 단위로 그대로다"
    - "커밋에는 표 hunk 만 들어가고, 작업 트리의 다른 미커밋 변경(같은 파일의 그래프 조회 섹션 포함)은 미커밋 상태로 남는다"
    - "커밋 메시지에 공동저자 트레일러·모델/AI 문구가 없고, push·tag 는 하지 않는다"
  artifacts:
    - path: ".claude/CLAUDE.md"
      provides: "갱신된 읽기 금지 표 (8-22행)"
      contains: "| 03_service/AlertStateMachine.kt | 49,553 |"
  key_links:
    - from: "git index"
      to: ".claude/CLAUDE.md 표 hunk"
      via: "git apply --cached --unidiff-zero (표 hunk 만 담은 patch)"
      pattern: "@@ -(8|9|1[0-7])"
---

<objective>
context-budget 실측 결과(BYTES_PER_TOKEN=3.29, 임계 10,000 토큰, 추적 파일 합계 약 703,802 토큰, 임계 이상 13개, .planning/ 합계 240,439)로 SafeAlert `.claude/CLAUDE.md` 의 "### 전체 읽기 금지 파일 (Read 도구 사용 금지)" 표를 갱신하고, 그 표 hunk 하나만 커밋한다.

Purpose: 읽기 금지 표가 실제 파일 크기와 어긋나 있어(누락 5개, 수치 노후) 컨텍스트 예산 규칙이 제대로 작동하지 않는다.
Output: 갱신된 `.claude/CLAUDE.md` 표 + 표 hunk 만 담은 로컬 커밋 1개.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
작업 디렉터리: C:/Users/pslym/Downloads/SafeAlert (git 저장소 루트)
대상 파일: .claude/CLAUDE.md — 1-40행만 읽는다. 표는 5-17행(헤더 6-7행, 데이터 8-17행).
.planning/ 탐색 금지. STATE.md·ROADMAP·코드 파일 읽기 금지. 스크립트 재실행 불필요(실측 완료, 수치는 아래 task 에 확정).
</context>

<tasks>

<task type="auto">
  <name>Task 1: 읽기 금지 표 데이터 행 교체 후 표 hunk 만 커밋</name>
  <files>.claude/CLAUDE.md</files>
  <read_first>
    - C:/Users/pslym/Downloads/SafeAlert/.claude/CLAUDE.md (1-40행만)
  </read_first>
  <action>
1) 사전 점검: SafeAlert 루트에서 `git diff --cached --name-only` 결과가 비어 있어야 한다. 이미 스테이징된 파일이 있으면 건드리지 말고(unstage 금지) 중단 후 보고한다.

2) 편집: `.claude/CLAUDE.md` 1-40행을 Read 한 뒤 Edit 로 8-17행(데이터 10행)만 아래 15행으로 교체한다. 6행 `| 파일 | 토큰 |`, 7행 `|---|---|`, 5행 제목, 18행 빈 줄 이후는 손대지 않는다. 짧은 경로 표기 유지. docs/ARCHITECTURE.md 행은 다른 곳에 sed 구간 안내가 있으므로 4,200 그대로 둔다. 교체 후 데이터 행은 정확히 이 순서다:
   | 03_service/AlertStateMachine.kt | 49,553 |
   | 03_service/BleService.kt | 41,188 |
   | PROGRESS.md | 33,861 |
   | res/layout/activity_dev_settings.xml | 20,240 |
   | 05_ui/MainActivity.kt | 18,578 |
   | 06_utils/DevSettings.kt | 17,163 |
   | test/ble/LowSpeedApproachRegressionTest.kt | 16,144 |
   | 06_utils/UwbRanger.kt | 14,932 |
   | res/layout/activity_main.xml | 13,308 |
   | 05_ui/DevSettingsActivity.kt | 12,406 |
   | 02_ble/BleAdvertiser.kt | 12,358 |
   | test/ble/AlertCascadeGoldenTest.kt | 12,107 |
   | 02_ble/BleScanner.kt | 10,219 |
   | docs/ARCHITECTURE.md | 4,200 |
   | .planning/** (전체 240,439) | 전량 |
   범위 밖(수정 금지): 표 외 모든 줄. 특히 37-38행의 노후 versionCode/토큰 수치와, 28-33행의 미커밋 그래프 조회 섹션("코드 위치는 그래프를 먼저 본다")은 그대로 둔다.

3) 표 hunk 만 스테이징: 세션 scratchpad 디렉터리에 `git diff -U0 .claude/CLAUDE.md` 출력을 full.patch 로 저장한다. 여기서 파일 헤더 4줄(diff --git / index / --- / +++)과, hunk 헤더 `@@ -N` 의 N(구 파일 시작행)이 8 이상 17 이하인 hunk 들만 남긴 table.patch 를 만든다(bare `python` 으로 줄 단위 필터; 바이트 보존 위해 바이너리 모드로 읽고 쓴다). docs/ARCHITECTURE.md 행이 양쪽 동일해서 표 hunk 가 2개(-8,8 과 -17,1 부근)로 쪼개질 수 있으니 둘 다 포함한다. 28행 부근 hunk 는 제외한다. 그 다음 `git apply --cached --unidiff-zero table.patch` 로 인덱스에만 적용한다. 작업 트리 파일은 건드리지 않는다.

4) 스테이징 검증: `git diff --cached --name-only` 가 `.claude/CLAUDE.md` 한 줄뿐인지, `git diff --cached` 에 표 행 변경만 있고 28-33행 섹션 문구가 전혀 없는지 확인한다. 어긋나면 `git restore --staged .claude/CLAUDE.md` 로 인덱스만 되돌리고 3)을 다시 한다.

5) 커밋: 메시지를 scratchpad 의 commit_msg.txt 에 BOM 없는 UTF-8 로 쓴다. 형식은 제목 1줄, 빈 줄, "- " 글머리 목록. 예시 내용: 제목 "docs: context-budget 실측으로 읽기 금지 표 갱신", 글머리 "- 임계 10,000 토큰 이상 13개 파일로 표 재작성(UwbRanger·DevSettingsActivity·BleScanner·테스트 2개 추가)", "- 기존 행 토큰 수치 실측값으로 갱신, .planning 합계 240,439 반영". 공동저자 트레일러와 모델·AI 관련 문구는 넣지 않는다. `git commit -F <scratchpad>/commit_msg.txt` 로 커밋한다. push 금지, tag 금지. 버전 상수 증가 없음(문서 전용).
  </action>
  <verify>
    <automated>cd C:/Users/pslym/Downloads/SafeAlert && sed -n '5,22p' .claude/CLAUDE.md && test "$(sed -n '8,22p' .claude/CLAUDE.md | grep -c '^| ')" = 15 && test -z "$(sed -n '23p' .claude/CLAUDE.md)" && grep -c '| 03_service/AlertStateMachine.kt | 49,553 |' .claude/CLAUDE.md && grep -c '| .planning/\*\* (전체 240,439) | 전량 |' .claude/CLAUDE.md && test "$(grep -c '233,287' .claude/CLAUDE.md)" = 0 && test "$(git show --name-only --format= HEAD)" = ".claude/CLAUDE.md" && test "$(git show HEAD | grep -ci graphify)" = 0 && test "$(git log -1 --format=%B | grep -ciE 'co-authored|anthropic')" = 0 && grep -c 'graphify query' .claude/CLAUDE.md && test -z "$(git tag --points-at HEAD)" && git status -sb | head -1</automated>
  </verify>
  <done>
    - `sed -n '5,22p' .claude/CLAUDE.md` 가 제목·헤더 2행·신규 데이터 15행을 순서대로 보여준다
    - HEAD 커밋의 변경 파일은 `.claude/CLAUDE.md` 하나이고, 그 diff 에는 표 행만 있다(그래프 조회 섹션 없음)
    - 작업 트리의 `.claude/CLAUDE.md` 에 그래프 조회 섹션이 여전히 남아 있다(미커밋 유지)
    - 커밋 메시지에 공동저자 트레일러·모델/AI 문구 없음, HEAD 에 tag 없음, push 안 함(`git status -sb` 가 ahead 표시)
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| 작업 트리 → git index | 무관한 미커밋 변경이 다수 있어, 부분 스테이징이 틀리면 의도치 않은 내용이 커밋에 섞인다 |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-lqs-01 | Tampering | git index / 커밋 | medium | mitigate | 사전 `git diff --cached` 비어 있음 확인, 구 파일 8-17행 hunk 만 담은 patch 를 `git apply --cached --unidiff-zero`, 커밋 전 `--cached` name-only·내용 검사, 커밋 후 `git show HEAD` 로 재확인 |
| T-lqs-02 | Information Disclosure | 커밋 push | low | mitigate | push·tag 금지, verify 에서 tag 부재와 ahead 상태 확인 |
| T-lqs-03 | Tampering | .claude/CLAUDE.md 표 외 줄 | low | mitigate | Edit 는 8-17행 블록만 교체, 37-38행·28-33행 불변을 verify 에서 확인 |
</threat_model>

<verification>
- 표 데이터 15행이 task_facts 목록과 행 순서·수치까지 일치
- HEAD 커밋 = `.claude/CLAUDE.md` 표 hunk 만
- 작업 트리의 다른 미커밋 변경은 그대로 남음
</verification>

<success_criteria>
- `.claude/CLAUDE.md` 읽기 금지 표가 실측값(13개 파일 + docs/ARCHITECTURE.md + .planning 240,439)으로 갱신됨
- 로컬 커밋 1개, 표 hunk 만 포함, 공동저자 트레일러 없음, push·tag 없음
</success_criteria>

<output>
Create `.planning/quick/260914-lqs-context-budget-claude-md/260914-lqs-SUMMARY.md` when done
</output>
