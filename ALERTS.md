# 경보 이력 집계

> **아직 한 번도 돌지 않았다.** 이 자리는 자동으로 채워진다.

`.github/workflows/alert-digest.yml` 이 매주 월요일 06:00 KST 에 Firebase 의
경보 기록을 읽어 이 파일을 통째로 다시 쓴다. 폰에서는 GitHub 앱으로 이
저장소를 열면 파일 목록 아래 이 내용이 바로 보인다 — 경로를 타고 들어갈 필요가 없다.

원본 기록은 올리지 않는다. 집계값만 남기고 기기 ID 는 빼고 쓴다 (`--no-ids`).
이 저장소는 공개다.

---

## 처음 한 번만 할 일

**1. 저장소 시크릿 두 개**
Settings → Secrets and variables → Actions → New repository secret

| 이름 | 값 |
|------|----|
| `FIREBASE_DB_URL` | Realtime Database URL (`https://<프로젝트>.firebaseio.com`) |
| `FIREBASE_DB_SECRET` | Firebase 콘솔 → 프로젝트 설정 → 서비스 계정 → 데이터베이스 비밀 |

v1.1.71 에서 `database.rules.json` 의 루트 `.read` 를 `false` 로 잠갔다.
`alerts` 는 별도 규칙이 없어 이 값을 물려받으므로 일반 경로로는 읽히지 않는다.
위 데이터베이스 비밀만 규칙을 우회한다. 값은 실행 로그에 찍히지 않는다.

**2. 한 번 수동 실행**
Actions → **Alert digest** → Run workflow

다음 주부터는 알아서 돈다. 시크릿이 없는 상태로 돌려도 실패하지 않고,
무엇이 없어서 못 했는지를 이 파일에 적어 둔다.

---

## 숫자를 읽을 때

1건 = 경보 1회가 아니다. 같은 상대에 대해 1분에 한 번만 기록되므로
**가까워진 1분**이다. 사고 건수가 아니라 위험했던 순간의 대용 지표다.
