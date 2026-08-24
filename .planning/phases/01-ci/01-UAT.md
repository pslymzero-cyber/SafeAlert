---
status: pending
phase: 01-ci
source: [01-VERIFICATION.md]
started: 2026-08-24T09:05:00Z
updated: 2026-08-24T09:05:00Z
note: >-
  Phase 01 은 사용자 결정으로 이미 마감됐다(blocking-human 체크포인트 승인 + "지금 수정 후 마감").
  이 파일은 phase 를 되돌리기 위한 것이 아니라, SC 밖 실기 스모크 1건이 유실되지 않게
  추적 아티팩트로 남기는 것이다. 기기가 연결되면 아래 항목만 수행하면 된다.
---

## Current Test

number: 1
name: v1.1.70 동일 서명 debug APK 실기 덮어쓰기 설치 + 접근/이탈 경보 동작 확인
expected: |
  설치가 성공하고(서명 동일 → 덮어쓰기 가능), 보행자↔지게차 접근 시 경고·위험 발령과
  이탈 시 해제가 v1.1.70 과 구별되지 않는다. 프로덕션 diff 는 KalmanFilter 생성자
  기본 인자 시임 1건뿐이므로 런타임 동작 변화가 관측되면 그 자체가 회귀다.
awaiting: 기기 연결 (검증 시점 ADB 연결 기기 0대)

## Tests

### 1. 실기 덮어쓰기 설치 + 경보 동작 동일성

expected: 설치·경보 동작이 v1.1.70 과 구별되지 않는다 (프로덕션 diff = KalmanFilter 생성자 기본 인자 시임 1건)
why_human: ADB 연결 기기 0대 — 실기 스모크는 코드 검사로 대체 불가. P-09 에 따라 검증 불가를 검증한 것처럼 기록하지 않는다.
scope: Phase 1 의 Success Criteria 4건 **밖**. ROADMAP 이 이미 현장 검증 항목으로 별도 배정한 건.
code_evidence: KalmanFilter.kt:27-30 기본 인자 = System.currentTimeMillis(), 호출부 BleService.kt:450 / :1454 변경 0곳
result: [pending]

## Summary

total: 1
passed: 0
issues: 0
pending: 1
skipped: 0
blocked: 0

## Gaps

없음 — Success Criteria 4건은 전부 자동 검증으로 달성됐다(01-VERIFICATION.md GOAL_ACHIEVED 4/4).
이 UAT 는 SC 밖 출하 side-condition 하나만 남긴다.
