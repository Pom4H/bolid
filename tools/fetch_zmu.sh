#!/usr/bin/env bash
# Clone and build zmu-cortex-m0 (https://github.com/jjkt/zmu) for device-free E2E.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${1:-$ROOT/tmp/zmu}"
REPO_URL="${ZMU_REPO_URL:-https://github.com/jjkt/zmu.git}"

if ! command -v cargo >/dev/null 2>&1; then
    echo "cargo not found; install Rust from https://rustup.rs/" >&2
    exit 2
fi

if [[ ! -d "$DEST/.git" ]]; then
    mkdir -p "$(dirname "$DEST")"
    git clone --depth 1 "$REPO_URL" "$DEST"
else
    git -C "$DEST" fetch --depth 1 origin
    git -C "$DEST" reset --hard FETCH_HEAD
fi

(
    cd "$DEST"
    cargo build --release --no-default-features --features cortex-m0 --bin zmu-cortex-m0
)

BIN="$DEST/target/release/zmu-cortex-m0"
test -x "$BIN"
echo "$BIN"
