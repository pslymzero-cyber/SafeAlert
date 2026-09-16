#!/usr/bin/env bash
# 큰 파일을 통째로 읽는 호출을 거부하고, 대신 어디를 볼지 알려준다.
#
# 왜: 대형 파일 두세 개면 한 턴에 컨텍스트 창이 차고 자동 압축이 연쇄로 터진다.
#     CLAUDE.md 의 "읽지 마라" 는 권고문이라 강제력이 없다.
#
# 목록이 아니라 크기로 판정하므로 프로젝트마다 표를 관리할 필요가 없다.
# graphify 그래프가 있으면 그래프 명령으로, 없으면 grep 으로 안내한다.
#
# Read            limit 이 MAX_LINES 이하인 범위 읽기만 통과. offset 만 주면 기본 2000줄이라 막는다.
# Bash/PowerShell cat·type·more·less·Get-Content 로 큰 파일을 파이프·리다이렉트 없이 찍으면 막는다.
# ponytail: 셸 검사는 공백 분리라 공백 든 경로·head -n 5000·sed -n '1,$p' 는 못 잡는다. 실제로 새면 그때 추가.

# 전역 훅(~/.claude/hooks/)이 깔려 있으면 그쪽이 처리한다. 같은 메시지가
# 두 번 뜨는 것을 막는다.
[ -f "$HOME/.claude/hooks/big-read-guard.sh" ] && \
  grep -q big-read-guard "$HOME/.claude/settings.json" 2>/dev/null && exit 0

MAX_BYTES=${CLAUDE_READ_MAX_BYTES:-30000}
MAX_LINES=${CLAUDE_READ_MAX_LINES:-200}

j=$(tr -d '\n')

block() {  # $1=경로 $2=바이트 $3=사유
  local tok=$(($2 * 100 / 329)) bn=${1##*/}
  {
    echo "차단: $1 는 ${2}바이트(약 ${tok}토큰)다. $3"
    echo
    if [ -f "${CLAUDE_PROJECT_DIR:-.}/graphify-out/graph.json" ]; then
      echo "먼저 그래프로 어디를 볼지 정해라. 파일:줄 번호가 바로 나온다."
      echo "  graphify explain \"찾는심볼\"             한 심볼의 정의 위치와 호출 관계"
      echo "  graphify affected \"찾는심볼\" --depth 2  이걸 고치면 영향받는 호출처"
      echo "  graphify path \"A\" \"B\"                  두 심볼 사이 최단 경로"
      echo "  graphify explain \"${bn%.*}\""
      echo
      echo "그다음 나온 줄 번호만 읽어라."
    else
      echo "읽을 구간부터 좁혀라."
    fi
    echo "  grep -n \"키워드\" \"$1\""
    echo "  sed -n 'START,ENDp' \"$1\"     찾은 구간 앞뒤 60줄만"
    echo "  Read 를 쓰려면 offset 과 limit(${MAX_LINES} 이하)을 지정한다"
    echo
    echo "기준값은 ${MAX_BYTES}바이트, ${MAX_LINES}줄이다. CLAUDE_READ_MAX_BYTES, CLAUDE_READ_MAX_LINES 로 바꿀 수 있다."
  } >&2
  exit 2
}

jstr() {  # JSON 문자열 필드 값. \\ 는 / 로(Windows 경로), \n 은 ; 로(여러 줄 명령)
  printf '%s' "$j" | sed -nE "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"(([^\"\\\\]|\\\\.)*)\".*/\\1/p" |
    sed 's|\\\\|/|g; s|\\n|;|g; s|\\t| |g; s|\\"|"|g'
}

tool=$(jstr tool_name)

if [ "$tool" = Bash ] || [ "$tool" = PowerShell ]; then
  case "$j" in
    *cat\ *|*type\ *|*more\ *|*less\ *|*[Gg]et-[Cc]ontent*|*gc\ *) ;;
    *) exit 0 ;;
  esac
  shopt -s nocasematch
  base=$(jstr cwd); base=${base:-${CLAUDE_PROJECT_DIR:-.}}
  while IFS= read -r seg; do
    case "$seg" in *'|'*|*'>'*) continue ;; esac   # 파이프·리다이렉트로 걸렀으면 통과
    set -f; set -- $seg; set +f
    [ $# -gt 0 ] || continue
    c=$1; shift
    case "$c" in
      cd|Set-Location|sl)
        d=${1#[\"\']}; d=${d%[\"\']}
        case "$d" in '') ;; /*|?:/*) base=$d ;; *) base="$base/$d" ;; esac
        continue ;;
      cat|type|more|less|Get-Content|gc) ;;
      *) continue ;;
    esac
    case " $* " in *' -TotalCount '*|*' -Tail '*|*' -Head '*|*' -First '*|*' -Last '*) continue ;; esac
    for a in "$@"; do
      case "$a" in -*) continue ;; esac
      f=${a#[\"\']}; f=${f%[\"\']}
      case "$f" in /*|?:/*|~*) ;; *) f="$base/$f" ;; esac
      f=${f/#\~/$HOME}
      sz=$(wc -c 2>/dev/null < "$f") || continue
      [ "$sz" -gt "$MAX_BYTES" ] && block "$f" "$sz" "$c 로 전체를 찍으면 그대로 컨텍스트에 들어간다."
    done
  done <<< "$(printf '%s\n' "$(jstr command)" | sed -E 's/[0-9]?>(&[0-9]|\/dev\/null|\$null)//g; s/&&|\|\||;/\n/g')"
  exit 0
fi

[ "$tool" = Read ] || exit 0
p=$(jstr file_path)
[ -n "$p" ] || exit 0
shopt -s nocasematch
case "$p" in *.png|*.jpg|*.jpeg|*.gif|*.webp|*.bmp|*.pdf) exit 0 ;; esac   # 이미지·PDF 는 바이트가 토큰이 아니다
sz=$(wc -c 2>/dev/null < "$p") || exit 0
[ "${sz:-0}" -le "$MAX_BYTES" ] && exit 0

lim=$(printf '%s' "$j" | sed -n 's/.*"limit"[[:space:]]*:[[:space:]]*"\{0,1\}\([0-9][0-9]*\).*/\1/p')
[ -n "$lim" ] && [ "$lim" -le "$MAX_LINES" ] && exit 0

if [ -n "$lim" ]; then
  block "$p" "$sz" "limit ${lim}줄은 상한 ${MAX_LINES}줄을 넘는다."
else
  block "$p" "$sz" "limit 이 없으면 전체(또는 기본 2000줄)를 읽는다."
fi
