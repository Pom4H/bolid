#!/usr/bin/env bash
# PB-03F-Kit bench: jumper/analog ties for the same CLI as tools/dpls_board.sh.
# Live TUI must already be running, except --once which spawns cli.ts --once.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/tools/dpls-lab"
if [[ ! -d node_modules ]]; then
  bun install
fi
if [[ -x "$ROOT/firmware/build/dpls_simulator" ]]; then
  export DPLS_SIMULATOR="${DPLS_SIMULATOR:-$ROOT/firmware/build/dpls_simulator}"
fi
exec bun run bench.ts "$@"
