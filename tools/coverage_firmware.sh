#!/usr/bin/env bash
# Host tests of the portable DPLS core with a line-coverage gate.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${DPLS_COVERAGE_BUILD:-$ROOT/firmware/build-cov}"
THRESHOLD="${DPLS_COVERAGE_THRESHOLD:-80}"

cmake -S "$ROOT/firmware" -B "$BUILD" -DCMAKE_BUILD_TYPE=Debug -DENABLE_COVERAGE=ON
cmake --build "$BUILD"
find "$BUILD" -name '*.gcda' -delete
ctest --test-dir "$BUILD" --output-on-failure
python3 "$ROOT/tools/coverage_firmware.py" --build-dir "$BUILD" --threshold "$THRESHOLD"
