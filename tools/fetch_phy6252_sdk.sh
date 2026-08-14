#!/usr/bin/env bash
# Fetch the complete PHY62XX SDK 3.1.2 at the commit pinned in
# firmware/sdk/phy6252-sdk.env. The fetched vendor SDK stays outside version
# control; the product target is maintained under firmware/targets/phy6252.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/firmware/sdk/phy6252-sdk.env"
SDK_DIR="$ROOT/$PHY6252_SDK_DIR"

if [ -d "$SDK_DIR/.git" ]; then
    current="$(git -C "$SDK_DIR" rev-parse HEAD)"
    if [ "$current" = "$PHY6252_SDK_COMMIT" ]; then
        echo "PHY62XX SDK 3.1.2 already present: $current"
        exit 0
    fi
    echo "SDK checkout is at $current, expected $PHY6252_SDK_COMMIT; replacing it" >&2
    rm -rf "$SDK_DIR"
elif [ -e "$SDK_DIR" ]; then
    echo "Removing non-git SDK directory: $SDK_DIR" >&2
    rm -rf "$SDK_DIR"
fi

mkdir -p "$(dirname "$SDK_DIR")"
git init -q "$SDK_DIR"
git -C "$SDK_DIR" remote add origin "$PHY6252_SDK_URL"
git -C "$SDK_DIR" fetch --depth 1 origin "$PHY6252_SDK_COMMIT"
git -C "$SDK_DIR" checkout -q --detach FETCH_HEAD

actual="$(git -C "$SDK_DIR" rev-parse HEAD)"
if [ "$actual" != "$PHY6252_SDK_COMMIT" ]; then
    echo "SDK integrity check failed: got $actual" >&2
    exit 1
fi

echo "Fetched full PHY62XX SDK 3.1.2 at $actual"
