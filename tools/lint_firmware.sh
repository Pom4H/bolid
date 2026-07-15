#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="${DPLS_LINT_BUILD_DIR:-${repo_root}/Firmware/build-lint}"

if ! command -v cppcheck >/dev/null 2>&1; then
    echo "cppcheck is required (Ubuntu/Debian: sudo apt-get install cppcheck)" >&2
    exit 2
fi

cmake -S "${repo_root}/Firmware" -B "${build_dir}" -DCMAKE_BUILD_TYPE=Debug
cmake --build "${build_dir}"
ctest --test-dir "${build_dir}" --output-on-failure

# Analyse only project-owned portable firmware. The PHY62XX SDK is third-party
# code and is deliberately excluded: its legacy headers produce a large amount
# of diagnostics that we cannot safely fix or maintain.
cppcheck \
    --quiet \
    --std=c99 \
    --language=c \
    --enable=warning,performance,portability \
    --error-exitcode=1 \
    --inline-suppr \
    --suppress=missingIncludeSystem \
    -I "${repo_root}/Firmware/include" \
    "${repo_root}/Firmware/src" \
    "${repo_root}/Firmware/tests"
