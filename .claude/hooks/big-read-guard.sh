#!/usr/bin/env bash
# Rejects calls that read a large file whole and says where to look instead.
#
# Why: two or three large files fill the context window in one turn and set off a chain of auto-compactions.
#      The "do not read" note in CLAUDE.md is only advice; it cannot enforce anything.
#
# It judges by size, not by a list, so no per-project table needs maintaining.
# Its message always suggests grep, sed and a ranged Read; when graphify-out/graph.json exists it lists graphify commands first.
#
# Read            only range reads with limit <= MAX_LINES pass. offset alone means the default 2000 lines, so it is blocked.
# Bash/PowerShell printing a large file with cat/type/more/less/Get-Content and no pipe or redirect is blocked.
# ponytail: the shell check splits on spaces, so it misses paths with spaces,
# head -n 5000 and sed -n '1,$p'. Add them if they actually leak.

# If the global hook (~/.claude/hooks/) is set up, it handles this instead, so the same
# message does not show twice.
[ -f "$HOME/.claude/hooks/big-read-guard.sh" ] && \
  grep -q big-read-guard "$HOME/.claude/settings.json" 2>/dev/null && exit 0

MAX_BYTES=${CLAUDE_READ_MAX_BYTES:-30000}
MAX_LINES=${CLAUDE_READ_MAX_LINES:-200}

j=$(tr -d '\n')

block() {  # $1=path $2=bytes $3=reason
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

jstr() {  # JSON string field value. \\ becomes / (Windows paths), \n becomes ; (multi-line commands)
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
    case "$seg" in *'|'*|*'>'*) continue ;; esac   # filtered through a pipe or redirect: let it pass
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
case "$p" in *.png|*.jpg|*.jpeg|*.gif|*.webp|*.bmp|*.pdf) exit 0 ;; esac   # image/PDF bytes are not tokens
sz=$(wc -c 2>/dev/null < "$p") || exit 0
[ "${sz:-0}" -le "$MAX_BYTES" ] && exit 0

lim=$(printf '%s' "$j" | sed -n 's/.*"limit"[[:space:]]*:[[:space:]]*"\{0,1\}\([0-9][0-9]*\).*/\1/p')
[ -n "$lim" ] && [ "$lim" -le "$MAX_LINES" ] && exit 0

if [ -n "$lim" ]; then
  block "$p" "$sz" "limit ${lim}줄은 상한 ${MAX_LINES}줄을 넘는다."
else
  block "$p" "$sz" "limit 이 없으면 전체(또는 기본 2000줄)를 읽는다."
fi
