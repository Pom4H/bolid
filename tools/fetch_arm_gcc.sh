#!/usr/bin/env bash
# Download the same Arm GNU bare-metal toolchain used by CI.
# Prints the toolchain bin directory to stdout; progress goes to stderr.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${ARM_GNU_TOOLCHAIN_VERSION:-13.2.rel1}"
OS="$(uname -s)"
ARCH="$(uname -m)"

case "$OS/$ARCH" in
    Darwin/arm64)  HOST="darwin-arm64" ;;
    Darwin/x86_64) HOST="darwin-x86_64" ;;
    Linux/x86_64)  HOST="x86_64" ;;
    *)
        echo "Unsupported host for automatic Arm GNU Toolchain setup: $OS/$ARCH" >&2
        echo "Install Arm GNU Toolchain $VERSION for arm-none-eabi and put its bin directory first in PATH." >&2
        exit 2
        ;;
esac

ARCHIVE="arm-gnu-toolchain-${VERSION}-${HOST}-arm-none-eabi.tar.xz"
BASE="https://developer.arm.com/-/media/Files/downloads/gnu/${VERSION}/binrel"
DEST="$ROOT/.toolchains/arm-gnu-toolchain-${VERSION}-${HOST}-arm-none-eabi"

if [ -x "$DEST/bin/arm-none-eabi-gcc" ]; then
    printf '%s\n' "$DEST/bin"
    exit 0
fi

command -v curl >/dev/null 2>&1 || { echo "curl not found" >&2; exit 1; }
command -v tar >/dev/null 2>&1 || { echo "tar not found" >&2; exit 1; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/dpls-arm-gcc.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$ROOT/.toolchains" "$WORK/unpack"
echo "Downloading Arm GNU Toolchain $VERSION for $OS/$ARCH..." >&2
curl -fL --retry 5 --retry-all-errors --connect-timeout 20 \
    "$BASE/$ARCHIVE" -o "$WORK/$ARCHIVE"
curl -fL --retry 5 --retry-all-errors --connect-timeout 20 \
    "$BASE/$ARCHIVE.sha256asc" -o "$WORK/$ARCHIVE.sha256asc"

EXPECTED="$(grep -Eo '[0-9a-fA-F]{64}' "$WORK/$ARCHIVE.sha256asc" | head -n 1 | tr 'A-F' 'a-f')"
if [ -z "$EXPECTED" ]; then
    echo "Could not parse SHA-256 from Arm checksum file" >&2
    exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL="$(sha256sum "$WORK/$ARCHIVE" | awk '{print $1}')"
else
    ACTUAL="$(shasum -a 256 "$WORK/$ARCHIVE" | awk '{print $1}')"
fi
ACTUAL="$(printf '%s' "$ACTUAL" | tr 'A-F' 'a-f')"
if [ "$ACTUAL" != "$EXPECTED" ]; then
    echo "Arm GNU Toolchain SHA-256 mismatch" >&2
    echo "expected: $EXPECTED" >&2
    echo "actual:   $ACTUAL" >&2
    exit 1
fi

tar -xJf "$WORK/$ARCHIVE" -C "$WORK/unpack" --strip-components=1
rm -rf "$DEST"
mv "$WORK/unpack" "$DEST"

"$DEST/bin/arm-none-eabi-gcc" --version >&2
printf '%s\n' "$DEST/bin"
