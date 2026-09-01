#!/usr/bin/env bash
# 경보 이력을 내려받아 바로 집계한다. **사내 PC 에서 실행한다.**
#
# 이 저장소의 원격 세션은 사내 네트워크 정책으로 Firebase 로 나가지 못한다
# (egress gateway 가 CONNECT 에 403). 그래서 이 스크립트는 로컬 전용이다.
#
#   ./fetch_alerts.sh            # 기본 루트 wf11
#   ./fetch_alerts.sh <루트>     # 기기 개발자 설정에서 바꿨다면 그 값
set -euo pipefail

ROOT="${1:-wf11}"
HERE="$(cd "$(dirname "$0")" && pwd)"
GS="$HERE/../../../app/google-services.json"

[ -f "$GS" ] || { echo "google-services.json 이 없다: $GS" >&2; exit 1; }
URL="$(python3 - "$GS" <<'PY'
import json, sys
print(json.load(open(sys.argv[1]))["project_info"].get("firebase_url") or "")
PY
)"
if [ -z "$URL" ]; then
  cat >&2 <<'MSG'
google-services.json 에 firebase_url 이 없다 — 이 파일로는 Realtime Database 주소를 알 수 없다.
(앱도 이 값에만 의존한다: FirebaseManager.kt:14 · 하드코딩된 주소 없음)

  Firebase 콘솔 → ⚙️ 프로젝트 설정 → google-services.json 다시 받기 → app/ 에 덮어쓰기
  https://console.firebase.google.com/project/safealert-98d7e/settings/general
MSG
  exit 1
fi
echo "DB   : $URL"
echo "루트 : $ROOT"

hit() {  # 경로 → 파일. 권한 오류를 사람이 읽을 수 있게 바꿔 준다.
  local q="$1" out="$2"
  local code
  code=$(curl -sS -o "$out" -w '%{http_code}' "$URL/$ROOT/alerts.json$q")
  if [ "$code" != "200" ]; then
    echo "HTTP $code" >&2
    head -c 300 "$out" >&2; echo >&2
    if [ "$code" = "401" ] || [ "$code" = "403" ]; then
      cat >&2 <<'MSG'

읽기 규칙이 닫혀 있다. 둘 중 하나로 간다.
  1) Firebase CLI  —  firebase login && \
       firebase database:get /<루트>/alerts --project safealert-98d7e -o alerts.json
  2) 콘솔에서 alerts 노드 ⋮ → JSON 내보내기
MSG
    fi
    exit 1
  fi
}

echo "→ 날짜 목록"
hit "?shallow=true" shallow.json
python3 - <<'PY'
import json
d = json.load(open("shallow.json")) or {}
k = sorted(d)
print(f"   {len(k)}일치   {k[0] if k else '-'} ~ {k[-1] if k else '-'}")
if not k:
    print("   비어 있다 — 루트 이름(개발자 설정 firebaseRoot)과 autoSaveAlerts 를 확인할 것.")
PY

echo "→ 전체 내려받기"
hit "" alerts.json
ls -lh alerts.json
echo
python3 "$HERE/analyze_alerts.py" alerts.json
