#!/usr/bin/env bash
# Static analysis of project-owned portable firmware (not the vendor SDK).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPORT="${DPLS_CPPCHECK_REPORT:-$ROOT/tmp/cppcheck.txt}"

if ! command -v cppcheck >/dev/null 2>&1; then
    echo "cppcheck is required (apt install cppcheck / brew install cppcheck)" >&2
    exit 2
fi

mkdir -p "$(dirname "$REPORT")"

cppcheck \
    --quiet \
    --std=c99 \
    --language=c \
    --enable=warning,performance,portability \
    --error-exitcode=1 \
    --inline-suppr \
    --suppress=missingIncludeSystem \
    --suppress=assertWithSideEffect \
    --suppress=unmatchedSuppression \
    -I "$ROOT/firmware/include" \
    -I "$ROOT/firmware/sim" \
    "$ROOT/firmware/src" \
    "$ROOT/firmware/include" \
    "$ROOT/firmware/sim" \
    "$ROOT/firmware/tests" \
    2> >(tee "$REPORT" >&2)
