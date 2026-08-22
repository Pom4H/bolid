#!/usr/bin/env bash
# Fetch the complete PHY62XX SDK 3.1.2 at the commit pinned in
# firmware/sdk/phy6252-sdk.env. The fetched vendor SDK stays outside version
# control; the product target is maintained under firmware/targets/phy6252.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/firmware/sdk/phy6252-sdk.env"
SDK_DIR="$ROOT/$PHY6252_SDK_DIR"
VENDOR_MAIN_REL="example/ble_peripheral/simpleBlePeripheral/main.c"

patch_product_boot_path() {
    local vendor_main="$SDK_DIR/$VENDOR_MAIN_REL"
    python3 - "$vendor_main" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8-sig")
needle = """    if(hal_gpio_read(P20)==1)
    {
        extern  uint8_t        g_dtmManualConfig;
        g_dtmManualConfig = RF_PHY_DTM_MANUL_ALL;
        rf_phy_direct_test();
    }
"""
replacement = """    /* Test-DPLS rev.2 owns P20 as the PORT1 ADC input. The vendor sample uses
     * the same pin as a hidden high-level strap into rf_phy_direct_test(), which
     * would make a legitimate DPLS voltage at boot enter factory DTM forever.
     * Production therefore has no GPIO-triggered DTM path. */
"""
marker = "Test-DPLS rev.2 owns P20 as the PORT1 ADC input"

if marker in text:
    if needle in text:
        raise SystemExit("error: PHY6252 vendor main contains both patched and original P20 DTM blocks")
elif text.count(needle) == 1:
    text = text.replace(needle, replacement)
    path.write_text(text, encoding="utf-8")
else:
    raise SystemExit(
        "error: pinned PHY6252 SDK P20 DTM block changed; review the vendor main before building"
    )

patched = path.read_text(encoding="utf-8")
if "if(hal_gpio_read(P20)==1)" in patched or "rf_phy_direct_test();" in patched:
    raise SystemExit("error: production PHY6252 main still exposes the vendor P20 DTM boot strap")
PY

    # The pinned SDK checkout is intentionally modified in exactly one reviewed
    # vendor sample file. Any other tracked drift must fail the build.
    local dirty
    dirty="$(git -C "$SDK_DIR" diff --name-only)"
    if [ "$dirty" != "$VENDOR_MAIN_REL" ]; then
        echo "Unexpected tracked PHY6252 SDK drift after product patch:" >&2
        printf '%s\n' "$dirty" >&2
        exit 1
    fi
    git -C "$SDK_DIR" diff --check
    echo "PHY62XX product patch: P20 DTM strap disabled"
}

if [ -d "$SDK_DIR/.git" ]; then
    current="$(git -C "$SDK_DIR" rev-parse HEAD)"
    if [ "$current" = "$PHY6252_SDK_COMMIT" ]; then
        echo "PHY62XX SDK 3.1.2 already present: $current"
    else
        echo "SDK checkout is at $current, expected $PHY6252_SDK_COMMIT; replacing it" >&2
        rm -rf "$SDK_DIR"
    fi
elif [ -e "$SDK_DIR" ]; then
    echo "Removing non-git SDK directory: $SDK_DIR" >&2
    rm -rf "$SDK_DIR"
fi

if [ ! -d "$SDK_DIR/.git" ]; then
    mkdir -p "$(dirname "$SDK_DIR")"
    git init -q "$SDK_DIR"
    git -C "$SDK_DIR" remote add origin "$PHY6252_SDK_URL"
    git -C "$SDK_DIR" fetch --depth 1 origin "$PHY6252_SDK_COMMIT"
    git -C "$SDK_DIR" checkout -q --detach FETCH_HEAD
fi

actual="$(git -C "$SDK_DIR" rev-parse HEAD)"
if [ "$actual" != "$PHY6252_SDK_COMMIT" ]; then
    echo "SDK integrity check failed: got $actual" >&2
    exit 1
fi

patch_product_boot_path

echo "Fetched full PHY62XX SDK 3.1.2 at $actual"