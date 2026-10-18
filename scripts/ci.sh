#!/usr/bin/env bash
# Local CI (no hosted CI yet): what a pipeline would run before merge.
#   scripts/ci.sh          full build on JDK 21, tests again on JDK 25, harvester tests
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
  ./gradlew :frontend-st:corpusCheck
else
  echo "== corpus check skipped (no local corpus; run tools/corpus-harvester)"
fi

echo "== corpus-harvester unit tests"
(cd tools/corpus-harvester && python3 -m unittest discover -s tests -q)

echo "== CI OK"
