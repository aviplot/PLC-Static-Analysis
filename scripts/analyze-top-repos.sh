#!/usr/bin/env bash
# Runs ./start.sh analyze on the largest harvested repositories and saves one report per repository.
#   scripts/analyze-top-repos.sh [count] [format]    count: default 10; format: console (default), json, sarif, html
#   OUT=dir scripts/analyze-top-repos.sh             output directory (default: build/top-repos)
#   PLCA_OPTS=-Xmx16g scripts/analyze-top-repos.sh   JVM options (the default heap is a quarter of RAM)
# Size is the PLC code kept per repository (sum of size_bytes in corpus/MANIFEST.csv).
# Reports quote third-party code, much of it copyleft or unlicensed: they stay under the git-ignored build/.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
count="${1:-50}"
format="${2:-console}"
out="${OUT:-$root/build/top-repos}"
case "$format" in
  console) ext=txt ;;
  json | sarif | html) ext="$format" ;;
  *) echo "unknown format: $format" >&2; exit 2 ;;
esac

if [[ ! -f "$root/corpus/MANIFEST.csv" ]]; then
  echo "no corpus/MANIFEST.csv; run tools/corpus-harvester first" >&2
  exit 2
fi
mkdir -p "$out"

# "<bytes> <host> <owner/repo>" for the largest repositories, largest first.
mapfile -t top < <(python3 -I - "$root/corpus/MANIFEST.csv" "$count" <<'EOF'
import collections, csv, sys
from urllib.parse import urlparse

sizes = collections.Counter()
with open(sys.argv[1], newline="") as f:
    for row in csv.DictReader(f):
        sizes[row["repo_url"]] += int(row["size_bytes"])
for url, size in sizes.most_common(int(sys.argv[2])):
    u = urlparse(url)
    print(size, u.hostname.split(".")[0], u.path.strip("/"))
EOF
)

"$root/start.sh" --version >/dev/null # builds plca once, up front
summary="$out/SUMMARY.md"
{
  echo "# plca on the top ${#top[@]} repositories by size"
  echo
  echo "$(date -Iseconds), $("$root/start.sh" --version)"
  echo
  echo "| # | Repository | PLC code | Exit | Time | Result |"
  echo "|---|---|---|---|---|---|"
} >"$summary"

i=0
for line in "${top[@]}"; do
  read -r size host repo <<<"$line"
  i=$((i + 1))
  dir="$root/corpus/external/$host/$repo"
  report="$out/$(printf '%02d' "$i")-${host}-${repo//\//__}.$ext"
  mb="$(awk -v b="$size" 'BEGIN { printf "%.1f MB", b / 1048576 }')"
  if [[ ! -d "$dir" ]]; then
    echo "[$i/${#top[@]}] $host/$repo: not in corpus/external, skipped" >&2
    echo "| $i | $host/$repo | $mb | – | – | missing from corpus/external |" >>"$summary"
    continue
  fi
  echo "[$i/${#top[@]}] $host/$repo ($mb)" >&2
  start=$SECONDS
  # Exit 1 only means findings; keep going on any exit code.
  code=0
  "$root/start.sh" analyze "$dir" --format "$format" --output "$report" --color never 2>"$report.log" || code=$?
  # An uncaught JVM error also exits with 1; tell it apart from "findings" by the log.
  if grep -q '^Exception in thread' "$report.log"; then
    code=3
    rm -f "$report"
  fi
  [[ -s "$report.log" ]] || rm -f "$report.log"
  case "$code" in
    0) result="no findings at or above error" ;;
    1) result="findings" ;;
    2) result="usage error (see .log)" ;;
    *) result="crashed: $(grep -m1 -oE '[A-Za-z.]*(Error|Exception)(: .*)?$' "$report.log" || echo "see .log")" ;;
  esac
  [[ "$format" == console && -f "$report" && "$code" -le 1 ]] && result="$(grep -E '^[0-9]+ findings? ' "$report" | head -1 || echo "$result")"
  echo "| $i | [$host/$repo](https://$host.com/$repo) | $mb | $code | $((SECONDS - start)) s | $result |" >>"$summary"
done

echo "reports in $out, summary in $summary" >&2
