#!/usr/bin/env bash
# Packs every module's test results and reports into one archive, rooted at the repository.
#
# The job that runs the tests and the job that reads them are different jobs, so the results cross as an
# artifact. An archive keeps the paths the collector globs for; a glob upload would root them at whatever
# the matched files happened to share. A job that wrote nothing still produces an archive, empty, and the
# collector reports the missing suites as red.
set -euo pipefail

archive="$1"
mkdir -p "$(dirname "$archive")"
find . \( -path ./.git -o -path ./.gradle \) -prune -o -type d \
  \( -path '*/build/test-results' -o -path '*/build/reports' -o -path '*/build/outputs/androidTest-results' \) \
  -print0 | tar --null -czf "$archive" -T -
echo "packed $(tar -tzf "$archive" | wc -l | tr -d ' ') entries into $archive"
