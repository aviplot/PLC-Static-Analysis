#!/usr/bin/env bash
# Builds plca when needed and runs it.
#   ./start.sh                         analyse the bundled demo project (plant) with the console report
#   ./start.sh analyze <path> [opts]   any plca command line, e.g. --format html --output report.html
#   ./start.sh rules                   list the rules
#   PLCA_REBUILD=1 ./start.sh ...      force a rebuild
# Re-run under bash when started as `sh script`.
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
plca="$root/modules/plc-cli/build/install/plca/bin/plca"

if [[ -z "${JAVA_HOME:-}" && -d /usr/lib/jvm/java-21-openjdk-amd64 ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
fi

# installDist only when the install is missing or older than a source or build file.
if [[ "${PLCA_REBUILD:-}" == 1 || ! -x "$plca" ]] ||
  [[ -n "$(find "$root/modules" "$root/build-logic" "$root/gradle" "$root"/*.gradle.kts "$root/gradle.properties" \
    -newer "$plca" -type f -not -path '*/build/*' -print -quit)" ]]; then
  echo "== building plca" >&2
  (cd "$root" && ./gradlew --quiet :plc-cli:installDist)
  touch "$plca"
fi

if [[ $# -eq 0 ]]; then
  set -- analyze "$root/modules/plc-semantic/src/test/resources/projects/plant" --fail-on never
fi
exec "$plca" "$@"
