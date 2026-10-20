#!/usr/bin/env bash
# Builds the web workbench when needed and starts it: one process, the server with the browser app in its jar.
#   scripts/web.sh                     http://127.0.0.1:8080/ and opens the browser
#   scripts/web.sh --port 9000         any `plca web` options (--host, --port, --token, --keep-uploads, --work-dir)
#   PLCA_NO_OPEN=1 scripts/web.sh      do not open the browser
#   PLCA_REBUILD=1 scripts/web.sh      force a rebuild of the app and plca
#   PLCA_OPTS=-Xmx8g scripts/web.sh    JVM options
# Re-run under bash when started as `sh script`.
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
web="$root/editors/web"
dist="$web/dist"
plca="$root/modules/plc-cli/build/install/plca/bin/plca"

if [[ -z "${JAVA_HOME:-}" && -d /usr/lib/jvm/java-21-openjdk-amd64 ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
fi

# The browser app: bundle only when dist/ is missing or older than the app's sources.
if [[ "${PLCA_REBUILD:-}" == 1 || ! -d "$dist" ]] ||
  [[ -n "$(find "$web/src" "$web/scripts" "$web/package-lock.json" -newer "$dist" -type f -print -quit)" ]]; then
  if command -v npm >/dev/null; then
    echo "== bundling the web app" >&2
    (cd "$root" && ./gradlew --quiet :editors:web:bundle)
    touch "$dist"
  elif [[ -d "$dist" ]]; then
    echo "== npm not found: serving the existing (older) web app bundle" >&2
  else
    echo "== npm not found: the server starts, but its page only says how to build the app" >&2
  fi
fi

# plca: installDist when missing or older than a source, build file or the app bundle (packed into the plc-web jar).
newer_paths=("$root/modules" "$root/build-logic" "$root/gradle" "$root"/*.gradle.kts "$root/gradle.properties")
[[ -d "$dist" ]] && newer_paths+=("$dist")
if [[ "${PLCA_REBUILD:-}" == 1 || ! -x "$plca" ]] ||
  [[ -n "$(find "${newer_paths[@]}" -newer "$plca" -type f -not -path '*/build/*' -print -quit)" ]]; then
  echo "== building plca" >&2
  (cd "$root" && ./gradlew --quiet :plc-cli:installDist)
  touch "$plca"
fi

open=(--open)
[[ "${PLCA_NO_OPEN:-}" == 1 ]] && open=()
exec "$plca" web "${open[@]}" "$@"
