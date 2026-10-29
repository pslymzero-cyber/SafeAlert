# 판정 데이터 기록 안내: 낙상 · 접근 경보

앱은 낙상 판정과 접근 경보 판정이 일어날 때마다 입력값 · 중간값 · 결과를 Firebase `wf11/judge` 에 한 건씩 남긴다.
학습용 정답을 얻으려고 사람에게 두 가지를 묻는다.
- 넘어짐 질문: 실제로 넘어졌는지
- 거리 질문: 경보가 울릴 때 상대와 거리가 대략 얼마인지

분석은 `scripts/judge-ml/` 에서 한다(그 폴더 README).

## 1. 지킬 것

- **판정은 바뀌지 않는다.**
  - 기록은 판정이 끝난 값을 복사만 한다.
  - 기록이 실패하면 조용히 넘어간다(`runCatching`).
  - 판정 코드(`AlertStateMachine` 의 판정, `UwbRanger` 의 판정 경로, 경보 · 소리 · 확인)는 이 기능 때문에 바뀌지 않았다.
- **측정용 UWB 거리**(아래 6절)는 판정 · 화면 거리 · UWB 학습 · `uwb_probe` 어디에도 쓰지 않는다. 기록에만 남는다.
- **보내기는 잠금 밖에서 한다.** 판정 스레드를 붙잡지 않는다.
- **내보낸 데이터와 분석 결과는 저장소에 넣지 않는다.**
  - 폰 ID(보행자는 쿠코드), 사업장 · 층 · 공정 · 존, `uid` 가 들어 있다.
  - `.gitignore` 에 `scripts/judge-ml/data/` 가 있다.

## 2. 개발자 설정

| 줄 | 키 | 기본 | 뜻 |
|---|---|---|---|
| 판정 데이터 기록 | `judge_log_enabled` | 켜짐 | 모든 판정 기록과 두 질문의 스위치. 끄면 질문도 뜨지 않는다 |
| 원시 가속도 함께 기록 | `judge_log_acc` | 켜짐 | 넘어짐 후보마다 충격 3초 전 ~ 12초 뒤 가속도(`acc`, 약 10KB) |
| 실험 세션 이름 | `judge_log_session` | `field` | 기록의 `sess`. 실험 때만 바꾼다(5절 표) |
| UWB 강제 활성화 | `uwb_force` | 켜짐 | UWB 토글이 꺼져 있어도 UWB 폰은 세션을 연다. 시작 기준(-80dBm)은 그대로라 판정은 같다. 저장된 값이 있는 폰은 그 값 그대로 |
| 측정용 UWB | `uwb_measure_enabled` | 켜짐 | 6절. 판정 데이터 기록이 꺼져 있으면 같이 꺼진다 |
| 측정 유지 기준(dBm) | `uwb_measure_gate_dbm` | -95 | 측정용으로 유지하는 가장 약한 신호(-100 ~ -80) |

## 3. 기록하는 자리

줄 번호는 이 문서를 쓴 커밋 기준이다. 코드가 바뀌면 함수 이름으로 `grep` 해서 찾는다.

| 기록 | 자리 | 언제 |
|---|---|---|
| 낙상 `cand` (F1) | `03_service/LoneWorkerSensors.kt:92` → `JudgeLog.fallCand` | 충격 후보마다(통과 · 탈락 모두) |
| 낙상 `fall` (F2) | `03_service/LoneWorkerMonitor.kt:102` → `JudgeLog.fallTake` | `onAccident` 에 FALL 이 들어올 때 |
| 낙상 `mode` (F3) | `03_service/LoneWorkerMonitor.kt:104` → `JudgeLog.mode` | 감시 상태가 바뀔 때(WATCHING · CHECKING · SOS) |
| 낙상 `truth` (F4) | `05_ui/JudgeQuestion.kt:78`(`JudgeAnswerReceiver`) → `JudgeLog.truth` | 넘어짐 질문 버튼을 눌렀을 때 |
| 접근 프레임 (P1, 메모리만) | `03_service/AlertStateMachine.kt:947` → `JudgeLog.frame` | 신호 프레임마다. 기록하지 않고 상대별 최근 값만 들고 있다 |
| 접근 `level` (P2) | `03_service/BleService.kt:1056`(processAlert 감싸기), `:1200`(judgeUwbOnly 감싸기) | 경보 단계가 바뀔 때, 같은 상대 2초에 1건 |
| 접근 `uwb` (P3) | `03_service/BleService.kt:1201`(판정용, `use=judge`), `:1184` `onUwbMeasureSample`(측정용, `use=measure`) | UWB 표본, 같은 상대 1초에 1건 |
| 접근 `dist` (P4) | `03_service/BleService.kt:684`(`OverlayManager.onAskAnswer`) → `JudgeLog.dist`, 경보 순간 값은 `:1214` → `JudgeLog.markDanger` | 거리 질문 버튼을 눌렀을 때 |

## 4. 두 질문

### 넘어짐 질문 (알림)

- **언제 묻나:** 넘어짐 확인 창이나 넘어짐 구조 요청이 이 폰에서 끝나면 알림이 뜬다.
- **묻는 말:** "방금 실제로 넘어지셨나요?" 구조자 확인으로 해제했으면 "작업자가 실제로 넘어졌나요?"로 묻는다.
- **답:** 버튼 3개(넘어졌어요 · 아니요 · 모르겠어요). 앱을 열지 않고 `truth` 로 기록된다.
- **알림이 사라지는 때:** 30분 뒤
- **묻지 않는 경우:**
  - 움직임 없음(무동작) 창
  - 감시 종료로 닫힌 창(`off`)
  - 재시작 뒤 복원한 구조 요청(`restore`)

### 거리 질문 (경보 사이드바)

- **언제 띄우나:** 위험 경보가 울리기 시작하면 경보 사이드바의 위험 기기 목록 아래에 "지금 상대와 거리는?" 칸이 뜬다.
  - 칸에는 상대 이름, 경보 시각("14:32:05 울림"), 1초마다 늘어나는 "N초 지남"이 보인다.
  - UWB 로 잰 거리는 답을 흐리지 않게 보여 주지 않는다.
- **답:** 1m 안 · 1~3m · 3~5m · 5m 넘음 · 모르겠어요
  - 누르면 '기록했어요'가 1초 보이고 닫힌다.
  - 경보 · 소리 · 진동 · 행 탭(30초 확인) · 끝까지 밀기(전체 확인)는 그대로다.
- **묻는 규칙** (`03_service/JudgeAsk.kt`)
  - 위험(DANGER) 경보가 시작될 때만 묻는다. 경고는 묻지 않는다.
  - 질문은 한 번에 하나다. 떠 있는 동안 다른 상대의 위험은 묻지 않는다(줄 세우지 않음).
  - 같은 상대는 30분에 한 번, 한 폰에서 한 시간에 4번까지다. 두 횟수 모두 칸이 처음 보일 때 센다.
  - 경보 시작부터 30초가 지나면 닫힌다. 그 뒤 답은 받지 않는다. 30초 안이면 경보가 먼저 끝나도 칸은 남는다.
- **칸이 안 보이는 경우:**
  - 장비 폰(지게차 · EPJ, 카트 제외)은 멈춰 있을 때만 보인다. 경보 뒤 30초 안에 멈추면 그때 보인다.
  - 사이드바가 화면에 없거나('다른 앱 위에 표시' 권한 없음) 화면이 꺼져 있으면 보이지 않는 것으로 친다.
  - 한 번도 안 보이면 묻지 않은 것이다. 횟수에 넣지 않고 기록도 없다.

## 5. 기록 형식

**경로**: `wf11/judge/<fall|prox>/<사업장 코드>/<yyyyMMdd>/<push id>`
- 사업장 코드가 `^[A-Z0-9_-]{1,12}$` 가 아니면 `NOSITE`
- 날짜는 기록 시각(폰 시계) 기준
- 값이 없는 필드는 빼고 쓴다(null 을 쓰지 않는다)

### 공통 (모든 기록)

| 필드 | 형 | 뜻 |
|---|---|---|
| `v` | 수 | 형식 버전 |
| `k` | 글 | 종류. 낙상은 `cand` · `fall` · `mode` · `truth`, 접근은 `level` · `uwb` · `dist` |
| `t` | 수 | 기록 시각 ms (벽시계) |
| `dev` | 글 | 이 폰 ID. 사업장 접두어를 붙인 `alerts` 와 같은 표기 |
| `role` | 글 | 내 역할 이름(`WALKER` · `EPJ` · `FORKLIFT` …). 카트 작업자는 `EPJ` |
| `site` · `floor` · `proc` · `zone` | 글 | 지금 설정 |
| `app` | 글 | versionName |
| `sess` | 글 | 실험 세션(아래 표). 평소는 `field` |
| `uid` | 글 | `auth.uid`. 규칙이 확인한다 |

`sess` 형식:

| 상황 | 형식 | 예 |
|---|---|---|
| 평소 | `field` | `field` |
| 낙상 실험 (넘어짐) | `test:fall:<이름>` | `test:fall:mat1` |
| 낙상 실험 (넘어짐 아님) | `test:no:<이름>` | `test:no:desk-drop` |
| 접근 거리 실험 | `test:<미터>m:<이름>` | `test:3m:aisle2` |

### 낙상 `cand` — F1

| 필드 | 뜻 |
|---|---|
| `imp_t` | 충격 시각. 판정 시간축 값이라 벽시계가 아니다. `fall` · `mode` · `truth` 와 잇는 열쇠 |
| `ff_ms` | 충격 전 1초 안에서 0.5G 미만이었던 시간 |
| `peak_g` | 충격 1초 창의 최대 G |
| `imp_g` | 이 폰의 충격 기준 G(센서 범위 보정 뒤) |
| `deg` | 충격 전후 자세 변화 각도(모르면 뺌) |
| `post_n` · `post_act` | 2~12초 창의 표본 수 · 활동 창 수 |
| `pre_ok` | 충격 전 자세를 알았는지 |
| `pass` | 후보 결정 통과(FALL) 여부 |
| `why` | 탈락 이유: `post_none` · `post_active` · `posture`. 통과면 `""` |
| `acc` · `acc_t0` · `acc_dt` | (원시 가속도 켜짐) 50Hz `"x,y,z;…"`(mg 정수), 첫 표본 시각(`imp_t` 와 같은 시간축), 표본 간격 ms |

### 낙상 `fall` — F2

| 필드 | 뜻 |
|---|---|
| `imp_t` | `trigMs` |
| `take` | `watch`(의심을 열었다) · `shut`(`fallShut`) · `dropped`(`fallDropped`) |
| `zone_in` · `charging` | 충격 때 세이프존 안 · 충전 중 |
| `plug_ago_ms` · `unplug_ago_ms` | 마지막으로 꽂은 · 뽑은 뒤 지난 시간(없으면 뺌) |

### 낙상 `mode` — F3

| 필드 | 뜻 |
|---|---|
| `from` · `to` | `WATCHING` · `CHECKING` · `SOS` |
| `trig` | 그 창을 연 계기 `fall` · `still`. 닫힐 때는 닫히기 전 값 |
| `why` | 닫힌 이유(`to` 가 `WATCHING`): `ack` · `walk` · `charger` · `rescuer` · `sos_cancel` · `auto_1h` · `off`. 복원한 구조 요청은 `restore` |
| `since_ms` | 이전 상태에 머문 시간. `restore` 는 0 |
| `imp_t` | `trig` 가 `fall` 이면 그 충격 시각 |

### 낙상 `truth` — F4 (넘어짐 질문 답)

| 필드 | 뜻 |
|---|---|
| `imp_t` | 그 넘어짐의 충격 시각 |
| `truth` | `fall` · `no` · `unknown` |
| `by` | `self`(본인) · `rescuer`(구조자 확인으로 해제한 경우) |
| `after_ms` | 창이 닫힌 뒤 답할 때까지 걸린 시간 |
| `close_why` | 창이 닫힌 이유(`mode` 의 `why`) |

### 접근 `level` — P2

| 필드 | 뜻 |
|---|---|
| `peer` · `peer_role` | 상대 ID(사업장 접두어) · 역할 이름 |
| `prev` · `level` | 바뀌기 전 · 뒤 단계. `0` 안전 · `1` 경고 · `2` 위험 |
| `rssi` `med` `pre` `kf` `kf_vel` `ema` `avg1s` | P1 마지막 프레임 값(아래 대응표) |
| `off_pl` `off_bc` `off_gg` `echo` | 오프셋 |
| `eff_w` · `eff_d` | 경고 · 위험 문턱(`effWarning` · `effDanger`) |
| `seq` | 최근 10초의 1초 요약 `"ema,kf_vel;…"`, 오래된 것부터 |

P1 값과 코드 변수:

| 필드 | 코드 변수 |
|---|---|
| `rssi` | `inputRssi` |
| `med` | `medianValue` |
| `pre` | `preFiltered` |
| `kf` · `kf_vel` | `kalmanRssi` · `kfVel` |
| `ema` | `pEma` |
| `avg1s` | `avg1sec` |
| `off_pl` · `off_bc` · `off_gg` · `echo` | `payloadOffset` · `beaconOffset` · `beaconGlobalGain` · `echoCalDb` |

### 접근 `uwb` — P3

| 필드 | 뜻 |
|---|---|
| `uwb_m` | 측정 거리 m |
| `use` | `judge`(판정에 쓰인 표본) · `measure`(측정용 UWB, 기록만) |
| `warn_m` · `dang_m` | 이 짝의 UWB 경고 · 위험 거리 |
| `level` | 판정 뒤 단계(`measure` 는 그 순간의 단계) |
| `frame_age_ms` | 같이 실린 P1 값(`rssi` …)이 몇 ms 전 프레임인지. 2초를 넘으면 신호와 거리가 같은 순간이 아니다 |
| `peer` … `seq` | `level` 과 같은 P1 값 |

### 접근 `dist` — P4 (거리 질문 답)

| 필드 | 뜻 |
|---|---|
| `ans` | `lt1` · `1to3` · `3to5` · `gt5` · `unknown` |
| `alert_t` | 위험 경보 시작 시각(벽시계) |
| `shown_ms` | 칸이 처음 보인 시각 − `alert_t`. 장비 폰은 0보다 클 수 있다 |
| `after_ms` | 누른 시각 − `alert_t` (30초 안) |
| `rssi` … `seq` · `frame_age_ms` | **누른 순간** 그 상대의 P1 값 |
| `uwb_m` · `uwb_use` | 누른 순간 1초 안에 잰 UWB 거리가 있을 때 |
| `start_rssi` · `start_ema` · `start_frame_age_ms` · `start_uwb_m` | 위험 경보가 시작된 순간의 값(`start_uwb_m` 은 있을 때만) |

예:

```json
{"v":1,"k":"dist","t":1793000507000,"dev":"WF11-A1234567","role":"WALKER","site":"WF11","app":"1.2.21","sess":"field","uid":"…",
 "alert_t":1793000500000,"shown_ms":0,"after_ms":7000,"ans":"1to3","peer":"WF11-FL-03","peer_role":"FORKLIFT",
 "rssi":-58,"ema":-58.9,"kf_vel":1.2,"eff_w":-87,"eff_d":-74,"seq":"-66.0,0.9;-61.2,1.4;…","frame_age_ms":80,
 "uwb_m":2.41,"uwb_use":"judge","start_rssi":-63,"start_ema":-62.6,"start_frame_age_ms":110}
```

## 6. 측정용 UWB

- **세션을 여는 때:** UWB 세션은 지금처럼 신호가 -80dBm 보다 강한 상대와만 연다.
- **세션을 유지하는 때:** 이미 열린 세션은 상대가 렉 · 파렛트 뒤로 가서 신호가 약해져도 측정 유지 기준(기본 -95)까지 유지한다.
  - 측정용으로 넘어가는 시점은 예전 코드가 세션을 닫던 시점과 같다.
  - 그 거리는 `uwb` 기록에 `use=measure` 로만 남는다.
- **미리 내려놓기:** 측정 중에 세션 밖 상대가 -85 보다 강해지면 측정을 내려놓는다. 그 상대의 판정 세션이 늦지 않게 하려는 것이다.
- **남는 지연(사용자 승인):** 새 상대가 -85 ~ -80 을 거치지 않고 한 번에 -80 위로 뛰는 경우가 있다(렉 모퉁이에서 불쑥 나온 지게차 등).
  - 그 상대의 UWB 세션은 예전보다 약 0.7~1.6초(+세션 준비 시간) 늦게 열린다.
  - 그동안은 UWB 없는 폰처럼 블루투스 신호로 판정한다.
- **블루투스 광고 꺼짐(300ms):** 상대 한 명 기준으로 예전보다 늘지 않는다.

## 7. 저장 (Firebase)

`database.rules.json` 의 `$site` 아래 `judge`:

```json
"judge": {
  "$kind": {
    "$sc": {
      "$day": {
        "$id": {
          ".write": "auth != null && !data.exists() && newData.exists() && $site.matches(/^[A-Za-z0-9_-]{1,32}$/) && ($kind === 'fall' || $kind === 'prox') && $sc.matches(/^[A-Z0-9_-]{1,12}$/) && $day.matches(/^[0-9]{8}$/)",
          ".validate": "newData.hasChildren(['v','k','t','dev','uid']) && newData.child('uid').val() === auth.uid",
          "acc": { ".validate": "newData.isString() && newData.val().length <= 20000" },
          "seq": { ".validate": "newData.isString() && newData.val().length <= 2000" }
        }
      }
    }
  }
}
```

- **읽기:** 읽기 규칙이 없어 앱은 이 경로를 읽지 못한다. 데이터는 Firebase 콘솔 → Realtime Database 에서 `wf11/judge` 를 JSON 으로 내보낸다.
- **배포:**
  - 규칙은 태그를 푸시할 때 CI(`.github/workflows/release.yml`)가 올린다.
  - 규칙이 올라가기 전에 쓴 기록은 거부된다. 경보 판정은 그대로 동작한다.
  - 콘솔에서 직접 올릴 때는 **파일 전체**를 붙여 넣는다. 일부만 붙이면 나머지 규칙이 지워진다.
- **양 (추정):**

  | 기록 | 양 |
  |---|---|
  | `cand` | 충격 후보마다 1건(원시 가속도 켜면 건당 약 10KB) |
  | `level` | 단계가 바뀔 때, 같은 상대 2초에 1건까지 |
  | `uwb` | UWB 짝마다 1초에 1건(측정용 유지 중 포함), 건당 약 0.5KB |
  | `dist` | 한 폰에서 한 시간에 4건까지 |
  | `truth` · `mode` · `fall` | 사건마다 |

## 8. 분석

`scripts/judge-ml/README.md` 를 본다. 순서는 아래와 같다.
1. **불러오기 · 나누기:** 내보낸 JSON 을 불러오고(`load`), 날짜로 학습 / 실제를 나눈다(`split`).
2. **점검:** 거리 답 점검(`dist-check`), 신호-거리 점검(`rssi-check`).
3. **1차 분류:** AI 1차 분류(`ai-label`) 또는 규칙 1차 분류(`label`).
4. **재분류 · 재학습:** 사람 확인표 → 재분류(`relabel`) → 예시 고르기(`learn`) · 학습 데이터 판단(`ai-judge --on train`).
5. **실제 데이터 평가:** 정답표(`real-sheet`) → `ai-judge --on real` → `eval`. AI 와 지금 앱 판정을 비교한다.

정답은 측정값과 따로 얻은 값만 쓴다.
- **낙상:** `truth` 답, 낙상 실험 세션, 현장 확인표
- **접근:** UWB 거리, `dist` 답, 거리 실험 세션

## 9. 실기에서 볼 것

- **거리 질문 칸:**
  - 위험 경보 때 칸이 뜨는지, 버튼 터치가 행 탭(30초 확인) · 손잡이 끌기로 새지 않는지
  - 30초 뒤 닫히는지
  - 장비 폰은 멈췄을 때만 뜨는지
- **넘어짐 질문 알림:** 잠금 화면에서도 누를 수 있는지, 감시를 멈춘 뒤 눌러도 기록되는지
- **측정용 UWB(폰 두 대 이상):**
  - -80 아래로 내려가도 세션이 유지되는지, `use=measure` 기록이 생기는지
  - 다른 상대가 가까이 오면 내려놓는지
