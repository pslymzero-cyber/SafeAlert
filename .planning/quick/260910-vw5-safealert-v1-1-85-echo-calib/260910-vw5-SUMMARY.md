---
quick_id: 260910-vw5
slug: safealert-v1-1-85-echo-calib
date: 2026-09-10
status: complete
---

# SUMMARY — v1.1.85 echo_calib 를 사업장 노드 밖(전역)으로 이동

## 변경

| 커밋 | 내용 | 파일 |
|---|---|---|
| `b2d20a3` | `siteNode("echo_calib")` → `db.child("echo_calib")` + `site` 라벨 + 레거시 1단계 폴백, versionCode 140→141 / versionName 1.1.84→1.1.85 | `04_firebase/FirebaseManager.kt`, `app/build.gradle` |

- `uploadEchoCalib`: 경로에서 사업장 세그먼트를 뺐다. 대신 레코드 맵에
  `"site" to DevSettings.siteCode` 를 넣어 사업장을 라벨로만 남긴다 — `saveAlert` 와 같은 패턴이다.
- `downloadEchoCalibAll`: 같은 경로 변경 + **1단계 파서 폴백**. `model` 필드가 없는 자식은
  구버전이 쓴 `echo_calib/<사업장>/<기기ID>` 의 사업장 세그먼트로 보고 한 단계 내려가
  손자를 기기 노드로 파싱한다. 롤아웃 기간 중 구버전 기기 데이터를 흡수한다.
- 파싱 로직은 새 `private fun parseEchoNode(c: DataSnapshot): EchoCalibNode?` 로 뽑아
  두 계층 형태가 같은 코드를 타게 했다.

## 왜

에코보정 잔차는 양방향 diff(`내가 잰 상대 RSSI − 상대가 잰 나의 RSSI`)라 경로손실이
상쇄되고 `β_B − β_A` 만 남는다. 즉 장소가 아니라 기기/기종의 속성이다.
그런데 `siteNode("echo_calib")` 이 이 전사 상수를 사업장별로 쪼개고 있었다.

실제 실패 모드는 "신규 센터의 빈 풀" 이 아니라 **고아가 된 피어 참조**다.
전출된 기기는 자기 peers 맵 전체를 새 센터 노드에 올리지만, `aggregateEchoPriors` 의
`modelById[peerId] ?: continue` 가 그 행들을 전부 버린다 — 피어의 자기 노드가
옛 사업장 경로에 남아 같이 내려받은 풀 안에 없기 때문이다. 신규 센터는 백지 단말기가
아니라 기존 단말기를 전출시켜 채우므로 이 폐기가 곧 수개월치 이력의 소실이었다.
경로를 합치면 그 폐기가 사라진다.

## 검증

`./gradlew test` — BUILD SUCCESSFUL, 61건 실행 / 실패 0 (CI 하한 42건 충족).
컴파일 경고 3건은 기존 미사용 파라미터로 이번 변경과 무관하다.

## 손대지 않은 것

`03_service/AlertStateMachine.kt`, `03_service/BleService.kt`, `02_ble/*`,
`database.rules.json`. `alerts` 는 지금처럼 `siteNode` 아래 사업장별로 유지한다 —
경보는 사업장 단위 기록이 맞다. 변경 후 `siteNode` 가 감싸는 데이터는 `alerts` 하나뿐이다.
태그 `v1.1.85` 미생성·미푸시.

## 다음 (이번 범위 밖)

1. 전 기기 v1.1.85 갱신 후 레거시 `echo_calib/<사업장>/` 서브트리 정리.
   폴백이 있어 급하지 않다 — 정리 전까지 양쪽이 같이 읽힌다.
2. v1.1.84 의 남은 사람 단계(실기 비행기모드 경보 확인) 후 `v1.1.84` 태그 푸시.

## 워크플로 편차

단일 파일 3개 지점 규모(2파일 24삽입/15삭제)와 저장소 컨텍스트 규칙(대용량 파일 통독 금지)을
고려해 gsd-planner/gsd-executor 서브에이전트 대신 오케스트레이터가 직접 계획·실행했다.
원자 커밋·PLAN/SUMMARY·STATE 갱신 보장은 그대로 유지.
