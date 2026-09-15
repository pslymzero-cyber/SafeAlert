#!/usr/bin/env bash
# Read 도구로 큰 파일을 통째로 여는 것을 막는다.
#
# 왜: 전체 읽기 한 번에 컨텍스트 창이 차면 자동 압축이 연쇄로 터지고
#     (94k -> 106k -> 90k) 세션이 일을 못 한다. CLAUDE.md 의 "읽기 금지 파일"
#     표는 권고문이라 강제력이 없어서, 여기서 실제로 거부한다.
#
# 목록 대신 파일 크기로 판정한다. 새 파일이 커져도 표를 고칠 필요가 없다.
# offset 이나 limit 을 지정한 범위 읽기는 통과시킨다.

# 전역 훅(~/.claude/hooks/)이 깔려 있으면 그쪽이 처리한다. 같은 메시지가
# 두 번 뜨는 것을 막는다.
[ -f "$HOME/.claude/hooks/big-read-guard.sh" ] && \
  grep -q big-read-guard "$HOME/.claude/settings.json" 2>/dev/null && exit 0

MAX_BYTES=${CLAUDE_READ_MAX_BYTES:-30000}

j=$(tr -d '\n')

# 범위를 지정한 읽기는 허용
case "$j" in
  *'"limit"'*|*'"offset"'*) exit 0 ;;
esac

p=$(printf '%s' "$j" | sed -n 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
[ -n "$p" ] || exit 0

# JSON 이스케이프된 Windows 경로(C:\\Users\\...)를 슬래시로 되돌린다
p=$(printf '%s' "$p" | sed 's|\\\\|/|g')

sz=$(wc -c < "$p" 2>/dev/null) || exit 0
[ "${sz:-0}" -le "$MAX_BYTES" ] && exit 0

tok=$((sz * 100 / 329))
base=${p##*/}      # 파일명
sym=${base%.*}     # 확장자를 뗀 심볼 후보

cat >&2 <<EOF
차단: $p 는 ${sz}바이트(약 ${tok}토큰)다. 전체 읽기는 컨텍스트 예산을 넘긴다.

먼저 그래프로 어디를 볼지 정해라. 파일:줄 번호가 바로 나온다.
  graphify explain "찾는심볼"            한 심볼의 정의 위치와 호출 관계
  graphify affected "찾는심볼" --depth 2  이걸 고치면 영향받는 호출처
  graphify path "A" "B"                  두 심볼 사이 최단 경로
  graphify explain "$sym"                 이 파일부터 보려면

그다음 나온 줄 번호만 읽어라.
  sed -n 'START,ENDp' "$p"     찾은 구간 앞뒤 60줄만
  grep -n "키워드" "$p"        그래프에 없는 심볼일 때만
  Read 를 꼭 쓰려면 offset 과 limit 을 지정한다

기준값은 ${MAX_BYTES}바이트다. CLAUDE_READ_MAX_BYTES 로 바꿀 수 있다.
EOF
exit 2
