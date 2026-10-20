#!/usr/bin/env bash
# Rebuilds everything the workbench and the CLI run from: the browser app bundle, then plca (its plc-web jar packs the bundle).
#   scripts/rebuild.sh                 rebuild; restart a running scripts/web.sh afterwards to serve the new build
#   scripts/rebuild.sh --test          also run the unit and Playwright tests of the web app
# Re-run under bash when started as `sh script`.
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
web="$root/editors/web"
plca="$root/modules/plc-cli/build/install/plca/bin/plca"

if [[ -z "${JAVA_HOME:-}" && -d /usr/lib/jvm/java-21-openjdk-amd64 ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
fi

if command -v npm >/dev/null; then
  echo "== bundling the web app" >&2
  (cd "$root" && ./gradlew --quiet :editors:web:bundle)
  touch "$web/dist"
else
  echo "== npm not found: keeping the existing web app bundle" >&2
fi

echo "== building plca" >&2
(cd "$root" && ./gradlew --quiet :plc-cli:installDist)
touch "$plca"

if [[ "${1:-}" == --test ]]; then
  echo "== testing the web app" >&2
  (cd "$web" && npm run --silent test:unit && npm run --silent test:e2e)
fi

if pgrep -f "plca.* web" >/dev/null; then
  echo "== done; a plca web server is running: restart it (scripts/web.sh) to serve the new build" >&2
else
  echo "== done" >&2
fi
