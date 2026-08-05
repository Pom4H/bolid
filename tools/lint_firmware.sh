#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="${DPLS_LINT_BUILD_DIR:-${repo_root}/Firmware/build-lint}"
report_file="${DPLS_CPPCHECK_REPORT:-${repo_root}/tmp/cppcheck.txt}"

if ! command -v cppcheck >/dev/null 2>&1; then
    echo "cppcheck is required (Ubuntu/Debian: sudo apt-get install cppcheck)" >&2
    exit 2
fi

mkdir -p "$(dirname "${report_file}")"

# Analyse only project-owned portable firmware. The PHY62XX SDK is third-party
# code and is deliberately excluded: its legacy headers produce a large amount
# of diagnostics that we cannot safely fix or maintain.
set +e
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
    "${repo_root}/Firmware/tests" \
    2> >(tee "${report_file}" >&2)
cppcheck_status=$?
set -e

if ((cppcheck_status != 0)); then
    exit "${cppcheck_status}"
fi

cmake -S "${repo_root}/Firmware" -B "${build_dir}" -DCMAKE_BUILD_TYPE=Debug
cmake --build "${build_dir}"
ctest --test-dir "${build_dir}" --output-on-failure
