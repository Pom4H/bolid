#!/usr/bin/env bash
# Backward-compatible entry point for the Keil/Arm Compiler 6 target build.
# Use tools/build_firmware_keil.sh directly when the compiler choice matters.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/tools/build_firmware_keil.sh" "$@"
