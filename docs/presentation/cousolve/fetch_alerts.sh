#!/usr/bin/env bash
# 경보 이력을 내려받아 바로 집계한다. **사내 PC 에서 실행한다.**
#
#   ./fetch_alerts.sh            # 기본 루트 wf11
#   ./fetch_alerts.sh <루트>     # 기기 개발자 설정에서 바꿨다면 그 값
#
# 두 가지를 알고 시작한다.
#
# 1) v1.1.71 의 database.rules.json 이 alerts 읽기를 막았다.
#    루트가 ".read": false 이고 alerts 에는 .read 가 없어 그대로 상속된다.
#    → REST(curl)로는 소유자든 아니든 읽히지 않는다. 401/403 이 정상이다.
#    → 규칙을 우회하는 경로는 둘뿐이다: Firebase CLI(소유자 로그인) 또는 콘솔.
#    이 스크립트는 CLI 를 쓴다.
#
# 2) 이 저장소의 원격 세션(Claude Code)은 사내 네트워크 정책으로 Firebase 에
#    나가지 못한다(egress gateway 가 CONNECT 에 403). 그래서 로컬 전용이다.
set -euo pipefail

ROOT="${1:-wf11}"
HERE="$(cd "$(dirname "$0")" && pwd)"
GS="$HERE/../../../app/google-services.json"

command -v firebase >/dev/null || {
  cat >&2 <<'MSG'
Firebase CLI 가 없다. alerts 는 REST 로 읽히지 않으므로 CLI 가 필요하다.

  npm i -g firebase-tools
  firebase login          # 프로젝트 소유자 계정 — 이 로그인이 DB 규칙을 우회한다

CLI 를 못 쓰는 상황이면 콘솔에서 내려받는다:
  https://console.firebase.google.com/project/safealert-98d7e/database
  → 데이터 탭 → <루트>/alerts → ⋮ → JSON 내보내기 → analyze_alerts.py 에 넘긴다
MSG
  exit 1
}

echo "루트 : $ROOT   (프로젝트 safealert-98d7e)"
echo "→ 날짜 목록"
firebase database:get "/$ROOT/alerts" --project safealert-98d7e --shallow -o shallow.json \
  || { echo "읽기 실패 — firebase login 상태와 루트 이름을 확인할 것." >&2; exit 1; }
python3 - <<'PY'
import json
d = json.load(open("shallow.json")) or {}
k = sorted(d)
print(f"   {len(k)}일치   {k[0] if k else '-'} ~ {k[-1] if k else '-'}")
if not k:
    print("   비어 있다 — 기기 개발자 설정의 firebaseRoot 와 autoSaveAlerts 를 확인할 것.")
PY

echo "→ 전체 내려받기"
firebase database:get "/$ROOT/alerts" --project safealert-98d7e -o alerts.json
ls -lh alerts.json

echo
python3 "$HERE/analyze_alerts.py" alerts.json
