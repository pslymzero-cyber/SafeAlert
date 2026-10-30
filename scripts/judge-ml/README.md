# 판정 데이터 분석 (judge_ml.py)

회사 AI API 가 Firebase `wf11/judge` 에 쌓인 측정값을 읽고, 1차로 분류한다. 사람이 다시 분류하면 그 정답 예시로 학습하고, 판단한다.

- 학습 데이터와 실제 데이터는 맨 처음에 날짜로 나눈다.
- 실제 데이터는 마지막 평가에만 쓴다.
- 기록 형식: `docs/judge-data/README.md`

## 준비

- Python 3.10 이상 (anthropic 1.x 가 3.10 이상을 요구한다)
- 설치: `pip install -r requirements.txt` (pandas, numpy, scikit-learn, anthropic)
- 점검: `python judge_ml.py selftest`
  - 가짜 데이터와 대역 AI 로 전 과정을 돈다. 돈이 들지 않는다.
  - 마지막 줄에 `selftest 통과` 가 나와야 한다.

## AI API 설정 (환경 변수)

키는 코드나 파일에 적지 않는다. 환경 변수로만 넣는다.
- PowerShell: `$env:JUDGE_AI_KEY = "..."`
- bash: `export JUDGE_AI_KEY=...`

| 변수 | 뜻 | 기본 |
|---|---|---|
| `JUDGE_AI_STYLE` | `anthropic`(Claude API 키 또는 Anthropic 형식 게이트웨이) · `bedrock`(AWS Bedrock) · `openai`(OpenAI 호환 /chat/completions) | `anthropic` |
| `JUDGE_AI_MODEL` | 모델 이름 | `claude-opus-5-5` (bedrock 은 `anthropic.claude-opus-5-5`) |
| `JUDGE_AI_KEY` | API 키 | — |
| `JUDGE_AI_URL` | 회사 게이트웨이 주소. `openai` 형식은 꼭 넣는다(…/v1 까지) | — |
| `JUDGE_AI_AUTH` | 키를 보내는 방식: `x-api-key` · `bearer` | `x-api-key` |
| `JUDGE_AI_HEADERS` | 회사가 요구하는 추가 헤더(JSON). 예: `{"X-Team": "wf11"}` | — |
| `JUDGE_AI_REGION` | bedrock 리전. AWS 자격 증명은 AWS 기본 방식(프로필 · 환경 변수)으로 | — |
| `JUDGE_AI_EFFORT` | Claude 생각 깊이: `low` · `medium` · `high` | `medium` |
| `JUDGE_AI_BATCH` | 한 번에 묻는 기록 수 | 20 |
| `JUDGE_AI_FALLBACK` | `off` 면 거절 시 서버 대체 모델(fallbacks)을 끈다. 회사 게이트웨이가 beta 요청을 거부할 때만 쓴다 | `on` |
| `JUDGE_AI_SCHEMA` | `off` 면 답 형식 강제(JSON 스키마)를 끈다. 게이트웨이가 거부할 때만 쓴다 | `on` |
| `JUDGE_AI_PRICE` | 단가 `입력,출력[,캐시 읽기]` (100만 토큰당 달러). Claude API 키가 아니면 넣어야 비용 상한이 걸린다 | Claude 모델 표 |

Claude API 키(`anthropic`)로 부르면, 답을 거절당한 요청은 서버가 다른 모델로 한 번 더 돌린다(`fallbacks: "default"`).

## 비용

- **상한:** 모든 AI 명령은 `--max-usd` (기본 5달러)에서 멈춘다. 다시 돌리면 남은 것부터 이어서 묻는다.
- **미리 보기:** `--dry-run` 으로 건수와 대략 비용을 먼저 본다. `--limit` 으로 건수를 줄인다.
- **기록:** 쓴 토큰과 비용은 `data/ai_usage.csv` 에 쌓인다.
- **대략적인 비용:** claude-opus-5-5, 20건씩, 생각 medium 으로 부르면 기록 1건에 0.3센트 안팎이다.
  - 낙상 후보 1,000건 ≈ 3달러
  - 접근 기록 10,000건 ≈ 30달러
  - 실제 비용은 `ai_usage.csv` 로 확인한다.
- **비용 줄이기:**
  - 지시문과 예시는 캐시되어 두 번째 호출부터 싸진다.
  - 생각 깊이를 낮추거나(`JUDGE_AI_EFFORT=low`) 더 싼 모델로 바꾸면 비용이 준다. 바꾸기 전과 후에 `ai-judge --on train` 정확도를 견줘 본다.

## 순서

| # | 명령 | 하는 일 | 사람이 할 일 |
|---|---|---|---|
| 1 | `python judge_ml.py load judge.json` | 측정값을 표로 만든다 | Firebase 콘솔에서 `wf11/judge` 를 JSON 으로 내보낸다(⋮ → JSON 내보내기) |
| 2 | `python judge_ml.py split` | 날짜로 학습(`data/train`) · 실제(`data/real`)를 나눈다. 처음은 마지막 30% 날짜가 실제 | 기준일을 정하려면 `--real-from 20261124` |
| 2-1 | `python judge_ml.py dist-check` | 거리 답 점검(학습 데이터): 경보 뒤 몇 초 만에 눌렀는지별로 사람 답과 그 순간 UWB 거리가 얼마나 맞는지, 답한 거리 구간별 누른 순간 신호 세기(ema), 경보 순간 → 누른 순간 신호 변화 → `data/dist_check.md` | 오래 걸린 답의 정확도가 떨어지는지, 경보 뒤 신호가 얼마나 움직이는지 본다 |
| 2-2 | `python judge_ml.py rssi-check` | 신호별 거리 편차(학습 데이터, UWB 거리 기준) → `data/rssi_check.md`. 내용: 가리지 않은 기록으로 맞춘 '신호와 거리' 식, 신호 5dB 칸마다 실제 거리의 가운데 · 범위, UWB 로는 위험 거리인데 앱 문턱으로는 안전인 건수, 같은 거리보다 신호가 10dB 이상 약한 '가려짐 의심'의 구역(층 · 공정 · 존)별 비율 | 가려짐이 많은 구역이 렉 · 파렛트 구역인지 현장과 맞춰 본다 |
| 3 | `python judge_ml.py ai-check --kind fall` | AI 연결을 기록 3건으로 점검한다. 비용이 표시된다 | |
| 4 | `python judge_ml.py ai-label` | AI 가 학습 데이터의 낙상 후보를 1차 분류한다. 앱 안에서 답을 받은 것은 묻지 않는다 | 먼저 `--dry-run` |
| 5 | `python judge_ml.py label --by ai` | 1차 분류 결과로 확인표를 만든다: `data/review_fall.csv` · `data/review_prox.csv`. 접근은 UWB 거리가 정답이라 AI 를 쓰지 않는다 | |
| 6 | — | | 확인표의 `label_final` 칸을 채운다(아래) |
| 7 | `python judge_ml.py relabel` | 사람 답을 반영한다. 1차 분류와 사람이 같은 비율을 낸다 → `data/relabel_*.md` | 낮으면 `ai_rules_fall.md` 를 고치고 4번부터 |
| 8 | `python judge_ml.py learn --kind fall` (또 `prox`) | AI 가 배울 정답 예시를 만든다(종류마다 6건) | |
| 9 | `python judge_ml.py ai-judge --kind fall --on train` (또 `prox`) | 예시를 받은 AI 가 학습 데이터를 판단해 사람 정답과 견준다. 예시로 준 기록은 뺀다 → `data/ai_train_*.md` | 낮으면 지시문 · 예시를 고치고 8번부터 |
| 10 | `python judge_ml.py real-sheet` | 실제 데이터 낙상 정답표를 만든다 | 현장에서 확인한 사실로 채운다(아래) |
| 11 | `python judge_ml.py ai-judge --kind fall --on real` (또 `prox`) | AI 가 실제 데이터를 판단한다. 한 번만 하게 막혀 있다 | |
| 12 | `python judge_ml.py eval --kind fall` (또 `prox`) | 실제 데이터에서 AI 와 지금 앱 판정을 정답과 견준다 → `data/report_*.md` | |

`python judge_ml.py train --kind fall` 은 비교용 기본 모델을 만든다(선택, 돈이 들지 않는다). 있으면 12번 표에 함께 나온다.

### 확인표 채우기 (6번)

- 엑셀로 열어도 된다. 저장할 때는 **CSV UTF-8** 로 저장한다.
- `label_final` 값:
  - 낙상: `1` 넘어짐, `0` 아님
  - 접근: `0` 안전, `1` 경고, `2` 위험
  - 모르면 비운다.
- `ai_why` 는 AI 가 그렇게 본 이유다. 참고만 하고, 정답은 현장 사실로 정한다.
- `audit` 이 1 인 줄은 표본이다.
  - 1차 답 · 측정값 · 앱 판정이 가려져 있다. 현장 사실로만 매긴다.
  - 이 줄들로 AI · 규칙의 정확도를 정직하게 잰다.
- 한 번 답한 줄은 `data/human_*.csv` 에 쌓여 다시 묻지 않는다. 확인표에서 답을 지우고 relabel 하면 저장된 답도 지워진다.

### 실제 정답표 (10번)

- 측정값과 판정 없이 시각과 장소만 있다.
- 작업자 · 관리자 확인, 사고 기록 같은 현장 사실로만 채운다.

## 정답(라벨)은 어디서 오나

### 낙상

정답은 세 곳에서 온다.
1. 앱이 확인 창 · 구조 요청 뒤 물은 답("실제로 넘어지셨나요?")
2. 실험 세션 `sess=test:fall:<이름>` · `test:no:<이름>`
3. 사람 확인표

'괜찮아요'나 구조자 확인 같은 앱 반응은 정답으로 쓰지 않는다. "응답했다"는 뜻일 뿐 "안 넘어졌다"는 뜻이 아니다.

### 접근

정답은 세 곳에서 온다. 셋 다 신호 세기(RSSI)와 따로 얻은 값이라 정답이 된다.
1. **UWB 거리** (`uwb` 기록, 그리고 거리 답에 붙은 누른 순간의 UWB 거리)
   - `use` 가 `judge` 면 판정에 쓰인 거리, `measure` 면 판정에 안 쓰고 기록만 한 거리(측정용 UWB, 렉 · 파렛트 뒤처럼 신호가 약해진 구간)다. 거리 답에서는 `uwb_use` 다.
   - `frame_age_ms` 가 2초를 넘는 기록은 신호 값이 그 거리와 같은 순간이 아니라서 `rssi-check` 에서 뺀다.
2. **사람의 거리 답** (`dist` 기록)
   - 위험 경보가 울리는 동안 사이드바의 "지금 상대와 거리는?"에 누른 답이다. 앱은 경보 뒤 30초까지만 묻는다.
   - 한 기록에 함께 있는 값:
     - 누른 순간의 신호 값(`rssi` · `ema` · `seq` 직전 10초 흐름 등)과 UWB 거리
     - 경보가 울린 순간의 값(`start_rssi` · `start_ema` · `start_uwb_m`)
     - 칸이 보인 시각(`shown_ms`)과 누른 시각(`after_ms`, 둘 다 경보 시작부터의 ms)
   - 거리 구간을 단계로 바꾼다.
     - 보통 짝: 1m 안 · 1~3m = 위험, 3~5m = 경고, 5m 넘음 = 안전
     - 지게차가 낀 짝: 5m 안쪽 답 = 위험, '5m 넘음'은 문턱(8m · 15m)을 가르지 못해 쓰지 않는다
   - 누르기까지 걸린 시간(`after_ms`)으로 믿음을 정한다. 30초 안은 높음이다. 그보다 늦은 답은 앱이 받지 않지만, 시계가 어긋난 기록에 대비해 2분 안 = 중간, 그 뒤 = 낮음(평가 정답에서 뺌) 규칙을 남겨 두었다.
   - `dist-check` 는 누른 시간별 정확도, 답한 구간별 신호 세기, 경보 순간 → 누른 순간 신호 변화를 표로 낸다.
3. **거리 실험** `sess=test:3m:<이름>`

셋 다 없는 `level` 기록은 정답 없이 남는다. 분포나 사례를 볼 때 쓴다.

## 확증편향을 막는 장치

- **나누기가 첫 단계다.** 1차 분류 · 재분류 · 예시 · 학습은 `data/train` 만 쓴다.
- **AI 에게는 측정값만 준다.** 정답과 앱 판정은 주지 않는다. 그래서 AI 가 앱을 베끼지 못한다.
- **예시로 준 기록은 학습 점검에서 뺀다.** 외운 것을 맞힌 것으로 세지 않는다.
- **실제 데이터는 AI 판단을 한 번만 받는다** (`--force` 없이는 다시 안 함). 평가 횟수도 `split.json` 에 센다.
- **표본 줄과 실제 정답표는 결과를 보지 않고 매긴다.**
- **교차검증은 폰 단위로 나눈다** (비교용 기본 모델).

## 손댈 곳

| 이름 | 무엇 |
|---|---|
| `ai_rules_fall.md` · `ai_rules_prox.md` | AI 지시문(판정 기준 · 답 형식). 고치면 다음 AI 호출부터 반영되고, 바뀐 지시문으로는 처음부터 다시 묻는다 |
| `rule_fall` · `rule_prox` | 스크립트 안의 규칙. AI 를 안 쓸 때의 1차 분류와 접근 정답 |
| `call_anthropic` · `call_openai` | AI 호출. 회사 API 가 두 형식 모두 아니면 이 함수 하나만 고친다 |
| `DIST_LEVEL` · `DIST_FRESH_MS` | 사람 거리 답 → 단계, 답한 시간에 따른 믿음 |
| `PRICES` | 모델 단가 |

## 파일 (data/)

| 파일 | 내용 |
|---|---|
| `fall.csv` · `prox.csv` | load 결과 |
| `train/` · `real/` · `split.json` | 나눈 데이터, 기준일, 실제 파일 해시, 평가 횟수 |
| `train/fall_ai_v1.csv` | AI 1차 분류 |
| `review_*.csv` · `human_*.csv` | 확인표, 사람 답 누적 |
| `ai_examples_*.jsonl` | AI 가 배우는 정답 예시 |
| `train/*_ai_judge.csv` · `real/*_ai_judge.csv` | AI 판단 |
| `ai_usage.csv` | AI 호출마다의 토큰 · 비용 |
| `relabel_*.md` · `ai_train_*.md` · `report_*.md` | 결과 보고 |

## 앱에 넣는 작은 모델 (app_model.py)

폰 안 'AI 판단 표시'(개발자 설정, 기본 꺼짐)가 쓰는 모델 두 개를 만든다. 앱 코드는 그대로 두고 `app/src/main/assets/ai/` 의 파일만 바뀐다.

```
python app_model.py train <내보낸 judge.json>
python app_model.py prior
python app_model.py compare <내보낸 judge.json> [--no-ai | --dry-run]
python app_model.py selftest
```

- `prox.json`: 블루투스 값(지금 프레임 + 직전 10초) → 거리. 정답 = `uwb` 기록의 UWB 거리(판정용 · 측정용).
- `fall.json`: 충격 후보 요약 → 넘어짐 확률. 정답 = 실험 세션 `test:fall:…` / `test:no:…`(한 폰에서 15초 안 첫 후보만), 또는 넘어짐 질문 답.
- 기록이 모자라면(접근 100건 · 넘어짐 각 8건 미만) 그 모델은 바꾸지 않는다. `prior` 는 학습 전 자리표시로 되돌린다.
- 결과의 `cv` 는 5분 묶음 · 층화 교차검증 값이다. 모델 파일을 바꾸면 앱 테스트(AiJudgeTest)가 계산 일치를 확인하고, 앱에 반영하려면 새 버전을 배포한다.
- `compare`: 같은 기록에서 **지금 규칙 · 폰 모델 · 회사 AI** 를 정답(접근 = UWB 거리 단계, 넘어짐 = 실험 세션 · 질문 답)과 견준다. 결과는 `data/compare/compare_report.md`(표) · `compare_prox.csv` · `compare_fall.csv`. 회사 AI 는 위 AI 설정(환경 변수)과 `ai_rules_*.md` 를 그대로 쓰고, 접근은 단계마다 `--per-level`(기본 30)건만 묻는다. 비용 상한 `--max-usd`(접근 · 넘어짐 각각), 먼저 `--dry-run` 으로 건수 · 비용을 본다. 회사 AI 없이 보려면 `--no-ai`.

## 자동 내보내기 (회사 PC 에서 주소로 받기)

GitHub Actions '판정 데이터 내보내기'(`.github/workflows/judge-export.yml`)가 매일 03:30(한국 시간)에, 또는 Actions 화면에서 **Run workflow** 를 누를 때 `wf11/judge` 를 읽어 암호화한 파일을 아래 고정 주소에 올린다. 저장소가 공개라서 파일은 늘 암호화되고, 키 없이는 열 수 없다.

`https://github.com/pslymzero-cyber/SafeAlert/releases/download/judge-data/judge.json.gz.enc`

**처음 한 번 — 개인 PC (PowerShell)**
1. 키를 만들어 GitHub 비밀값으로 넣고, 회사로 가져갈 파일에도 적는다(화면에는 나오지 않는다).

   ```
   $k = python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
   $k | gh secret set JUDGE_EXPORT_KEY --repo pslymzero-cyber/SafeAlert
   Set-Content -Path "$env:USERPROFILE\judge_export_key.txt" -Value $k -NoNewline
   ```

2. GitHub Actions 에서 '판정 데이터 내보내기' → **Run workflow** 로 한 번 돌려 주소에 파일이 생기는지 본다.

**회사 PC**
1. 키 파일의 내용을 환경 변수로 넣는다(새 터미널부터 적용). 키는 Claude 에게 보여 주지 않는다.

   ```
   setx JUDGE_EXPORT_KEY "<키 파일 내용>"
   ```

2. `pip install -r requirements.txt`(cryptography 가 새로 들어감)
3. 받기: `python judge_ml.py fetch` → 이 폴더에 `judge.json` 이 생긴다. 그다음은 지금처럼 `load judge.json` · `python app_model.py compare judge.json` 등.

- 매번 어제 · 오늘과 새 날짜만 Firebase 에서 다시 읽고, 그 전 날짜는 지난번 파일에서 그대로 가져온다. Firebase 에서 지운 날도 파일에는 남는다.
- 회사망이 github.com 을 막으면 `HTTPS_PROXY` 환경 변수를 넣거나 IT 에 허용을 요청한다.
