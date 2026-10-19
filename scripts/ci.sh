#!/usr/bin/env bash
# Local CI (no hosted CI yet): what a pipeline would run before merge.
#   scripts/ci.sh          full build on JDK 21, tests again on JDK 25, harvester tests, VS Code extension
#   PLCA_E2E=1 scripts/ci.sh   also the extension's smoke test in VS Code (opens a window; use xvfb-run headless)
# Requires JDK 21 and 25 installed where Gradle can detect them (e.g. /usr/lib/jvm).
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -z "${JAVA_HOME:-}" && -d /usr/lib/jvm/java-21-openjdk-amd64 ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
fi

echo "== build (compile, tests, Spotless, Checkstyle) on JDK 21"
./gradlew build

echo "== tests on JDK 25"
./gradlew test -Pplca.testJdk=25

if [[ -f corpus/MANIFEST.csv ]]; then
  echo "== corpus check (accept rate vs baseline, SLL fallback rate)"
  ./gradlew :plc-devtools:corpusCheck
else
  echo "== corpus check skipped (no local corpus; run tools/corpus-harvester)"
fi

echo "== corpus-harvester unit tests"
(cd tools/corpus-harvester && python3 -m unittest discover -s tests -q)

if command -v npm >/dev/null; then
  echo "== VS Code extension: typecheck, grammar tests, package"
  ./gradlew :editors:vscode:grammarTest :editors:vscode:package
  if [[ -n "${PLCA_E2E:-}" ]]; then
    echo "== VS Code extension: smoke test in VS Code"
    ./gradlew :editors:vscode:e2eTest
  fi
else
  echo "== VS Code extension skipped (no npm)"
fi

echo "== CI OK"
