---
status: awaiting_human_verify
trigger: "비콘 경보가 보행자 PDA에도 울리는 문제 — walker 게이트 비콘 면제 재검토"
created: 2026-09-13
updated: 2026-09-13
---

# walker 게이트 비콘 면제 재검토

## Symptoms
- expected: 보행자 모드 PDA 는 비콘(BEA_) 경보를 받지 않는다 (현장 규칙: 보행자끼리 안 울림)
- actual: 보행자 모드 PDA 도 비콘 경보를 받는다
- reproduction: 보행자 모드 단말 + 비콘 1개 접근

## Evidence
- BleScanner.kt 202·225·241: 비콘 fullId = WALKER_PREFIX + "BEA_..." (iBeacon·ServiceUUID·MAC 3경로)
- 면제 하드코딩 3곳: BleService.onDeviceDetected(v1.1.58 fix1) · BleService.onUwbAddressReceived · AlertStateMachine.judgeUwbOnly
- 3곳 모두 첫 조건이 myMode == "WALKER" (ASM 은 fx.myMode) → 지게차·EPJ 모드는 게이트 미통과, 면제 제거가 장비측 비콘 수신에 무영향

## Root Cause
v1.1.58 fix1 에서 비콘을 walker 게이트에서 무조건 면제 → WALKER_PREFIX 로 들어오는 방문자 비콘이 보행자 모드에서도 경보. 장비 부착 비콘도 있어 일괄 차단 불가 → UUID 프로파일 단위 구분 필요.

## Fix (v1.1.89 / versionCode 145)
- BeaconProfile.visitorBeacon: Boolean = true (기본 true = 기존 등록분 방문자용)
- BeaconRegistry: getAll·parseProfiles optBoolean("visitorBeacon", true) / exportToJson put / findProfileByFullId 추출(getRssiOffsetForFullId 가 호출) / isVisitorBeacon(미등록 true)
- 게이트 3곳 (BleService.onDeviceDetected·onUwbAddressReceived, AlertStateMachine.judgeUwbOnly): !(deviceId.contains("BEA_") && !BeaconRegistry.isVisitorBeacon(deviceId))
- BeaconManagerActivity: 등록 다이얼로그 2곳 체크박스 "방문자용 (보행자 단말에는 경보 안 함)" 기본 체크, 목록 행 방문자용/장비용 표기 (수정 다이얼로그는 원래 없음, 위젯은 코드 생성이라 XML 무변경)
- 폐기: 전역 토글 DevSettings.walkerDetectsBeacon 안 → git checkout 원복 (백업 scratchpad/walkerDetectsBeacon-toggle.patch)
- 진리표(walkerDetectsWalker=false, 보행자 모드): 보행자 PDA 차단 / 방문자·미등록 비콘 차단 / 장비 비콘 통과. 지게차·EPJ 는 게이트 조건 myMode=="WALKER" 불충족 → 무영향

## Verification
- grep: !deviceId.contains("BEA_") 0건, walkerDetectsBeacon 0건 — 완료
- assembleDebug BUILD SUCCESSFUL — 완료
- 실기(사용자 대기): ① 보행자+방문자용 비콘=무경보 ② 지게차+같은 비콘=경보 ③ 보행자+체크해제 비콘=경보 ④ 구버전 등록 프로파일이 방문자용으로 표시
