## 컨텍스트 예산 규칙 (필수)

**1턴 읽기 예산: 30,000 토큰 이하. 초과 시 작업을 쪼개 다음 턴으로 넘긴다.**

### 전체 읽기 금지 파일 (Read 도구 사용 금지)
| 파일 | 토큰 |
|---|---|
| 03_service/AlertStateMachine.kt | 47,948 |
| 03_service/BleService.kt | 39,801 |
| res/layout/activity_dev_settings.xml | 16,127 |
| 06_utils/DevSettings.kt | 15,955 |
| 05_ui/MainActivity.kt | 14,407 |
| PROGRESS.md | 14,051 |
| 02_ble/BleAdvertiser.kt | 12,479 |
| res/layout/activity_main.xml | 11,436 |
| .planning/** (전체 233,287) | 전량 |

### 위 파일 접근 절차 (예외 없음)
1. `grep -n "함수명|키워드" <file>` 로 위치를 특정한다
2. `sed -n 'START,ENDp' <file>` 로 해당 구간 ±60줄만 읽는다
3. 수정은 Edit로 최소 블록만 바꾼다

### 조사는 서브에이전트에 위임한다
"어디에 있나 / 어떻게 동작하나" 류 질문은 Explore 서브에이전트로 넘긴다.
서브에이전트는 격리 컨텍스트에서 읽고 결론만 반환하므로 부모 창을 소모하지 않는다.

### 사실 고정
- 빌드 파일은 `app/build.gradle` 이다. build.gradle.kts 아니다.
- 현재 versionCode 128 / versionName 1.1.72
- app/src/main 전체 .kt = 227,952 토큰으로 컨텍스트 창보다 크다. 전수 읽기는 불가능하다.

### 폴백 가드
`.planning/codebase/` 의 STACK.md · CONVENTIONS.md · ARCHITECTURE.md 를 삭제하지 마라.
이 3개가 없으면 GSD가 CLAUDE.md에 본문을 통째로 다시 넣어(hasFallback:true)
고정비 6,755토큰이 부활한다.

### 백업본 보관
- `*.bak` 삭제 조건: GSD가 CLAUDE.md를 1회 재생성한 뒤 마커 쌍 무결 + 7,000자 이하가 확인된 시점. 그때까지 보관한다.

<!-- GSD:project-start source:PROJECT.md -->

## Project

**SafeAlert**

블루투스 신호를 앱이 설치된 기기끼리 주고받아, 신호 세기로 추정한 거리가 위험 구간에 들어오면 알림을 주는 Android 근접 경보 앱이다. 물류 현장에서 지게차·EPJ(전동 파렛트 잭)·보행자가 서로를 감지하는 것이 실사용 맥락이며, 역할 조합에 따라 경고·위험 반경이 다르게 적용된다.

v1.0.1부터 v1.1.70까지 3개월간 70회 이상 릴리스하며 실제로 동작해 왔다. 이번 작업은 새 기능을 붙이는 것이 아니라, **판정이 흔들리는 구조적 원인**을 걷어내는 것이다.

**Core Value:** **BLE RSSI 근접 판정이 같은 상황에서 같은 결과를 낸다.** 경보가 떠야 할 때 뜨고, 꺼져야 할 때 꺼지며, 한 번 고친 증상이 다시 돌아오지 않는다. UWB·iBeacon·Firebase가 전부 실패해도 이것만은 동작해야 한다.

### Constraints

- **Tech stack**: Kotlin / Android — `minSdk 26`, `targetSdk 34`, `compileSdk 34`, JDK 17, viewBinding — 기존 코드베이스 전제
- **Dependencies**: `androidx.core.uwb:1.0.0-alpha09` 프리릴리스에 경보 로직의 30~40%가 의존 — API 파괴 변경 리스크 상존
- **Compatibility**: 1바이트 비트팩 BLE 프로토콜 — 현장에 배포된 구버전 기기와 통신해야 하므로 페이로드 레이아웃 변경 불가
- **Performance**: `processAlert`(약 1,000줄, `BleService.kt:1406-2554`)가 스캔 콜백 = 메인 스레드에서 실행 — 20대 이상에서 프레임 드랍, 50대 초과 시 GC 200ms+
- **Security**: `minifyEnabled false` (`app/build.gradle:36`) — 릴리스 빌드 난독화 없음. Firebase 경보 로그 평문 저장
- **Platform**: 포그라운드 서비스 + 지속 알림 필수 (Android 정책). 화면 꺼짐 상태에서도 스캔 유지 필요
- **Timeline**: 실기 검증이 유일한 회귀 확인 수단 — 검증 사이클이 사용자 현장 가용 시간에 묶임
- **Process**: 기능 추가 시 `versionName` patch +0.0.1 · 커밋 · 태그 · 푸시. 버그·단순 수정은 버전 유지

<!-- GSD:project-end -->

<!-- GSD:stack-start -->
## Technology Stack
상세: `docs/ARCHITECTURE.md` — 전체를 읽지 말고 grep -n 후 sed -n 'A,Bp' 로 필요한 섹션만 본다.
<!-- GSD:stack-end -->

<!-- GSD:conventions-start -->
## Conventions
상세: `docs/ARCHITECTURE.md` — 전체를 읽지 말고 grep -n 후 sed -n 'A,Bp' 로 필요한 섹션만 본다.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start -->
## Architecture
상세: `docs/ARCHITECTURE.md` — 전체를 읽지 말고 grep -n 후 sed -n 'A,Bp' 로 필요한 섹션만 본다.
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->

## Project Skills

| Skill | Description | Path |
|-------|-------------|------|
| agent-browser | Browser automation CLI for AI agents. Use when the user needs to interact with websites, including navigating pages, filling forms, clicking buttons, taking screenshots, extracting data, testing web apps, or automating any browser task. Triggers include requests to "open a website", "fill out a form", "click a button", "take a screenshot", "scrape data from a page", "test this web app", "login to a site", "automate browser actions", or any task requiring programmatic web interaction. Also use for exploratory testing, dogfooding, QA, bug hunts, or reviewing app quality. Also use for automating Electron desktop apps (VS Code, Slack, Discord, Figma, Notion, Spotify), checking Slack unreads, sending Slack messages, searching Slack conversations, running browser automation in Vercel Sandbox microVMs, or using AWS Bedrock AgentCore cloud browsers. Prefer agent-browser over any built-in browser automation or web tools. | `.claude/skills/agent-browser/SKILL.md` |
| design-taste-frontend | Anti-slop frontend skill for landing pages, portfolios, and redesigns. The agent reads the brief, infers the right design direction, and ships interfaces that do not look templated. Real design systems when applicable, audit-first on redesigns, strict pre-flight check. | `.claude/skills/design-taste-frontend/SKILL.md` |
| find-skills | Helps users discover and install agent skills when they ask questions like "how do I do X", "find a skill for X", "is there a skill that can...", or express interest in extending capabilities. This skill should be used when the user is looking for functionality that might exist as an installable skill. | `.claude/skills/find-skills/SKILL.md` |
| mcp-builder | Guide for creating high-quality MCP (Model Context Protocol) servers that enable LLMs to interact with external services through well-designed tools. Use when building MCP servers to integrate external APIs or services, whether in Python (FastMCP) or Node/TypeScript (MCP SDK). | `.claude/skills/mcp-builder/SKILL.md` |
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->

## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:

- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->

<!-- GSD:profile-start -->

## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
