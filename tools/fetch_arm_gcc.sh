#!/usr/bin/env bash
# Download the same Arm GNU bare-metal toolchain used by CI.
# Prints the toolchain bin directory to stdout; progress goes to stderr.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${ARM_GNU_TOOLCHAIN_VERSION:-13.2.rel1}"
OS="$(uname -s)"
ARCH="$(uname -m)"

case "$OS/$ARCH" in
    Darwin/arm64)
        HOST="darwin-arm64"
        EXPECTED_SHA256="39c44f8af42695b7b871df42e346c09fee670ea8dfc11f17083e296ea2b0d279"
        ;;
    Darwin/x86_64)
        HOST="darwin-x86_64"
        EXPECTED_SHA256="075faa4f3e8eb45e59144858202351a28706f54a6ec17eedd88c9fb9412372cc"
        ;;
    Linux/x86_64)
        HOST="x86_64"
        EXPECTED_SHA256="6cd1bbc1d9ae57312bcd169ae283153a9572bd6a8e4eeae2fedfbc33b115fdbb"
        ;;
    *)
        echo "Unsupported host for automatic Arm GNU Toolchain setup: $OS/$ARCH" >&2
        echo "Install Arm GNU Toolchain $VERSION for arm-none-eabi and put its bin directory first in PATH." >&2
        exit 2
        ;;
esac

ARCHIVE="arm-gnu-toolchain-${VERSION}-${HOST}-arm-none-eabi.tar.xz"
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

URLS=(
    "https://armkeil.blob.core.windows.net/developer/Files/downloads/gnu/${VERSION}/binrel/${ARCHIVE}"
    "https://developer.arm.com/-/media/Files/downloads/gnu/${VERSION}/binrel/${ARCHIVE}"
)

DOWNLOADED=0
for url in "${URLS[@]}"; do
    echo "Trying: $url" >&2
    if curl -fL --retry 3 --retry-all-errors --connect-timeout 15 \
        "$url" -o "$WORK/$ARCHIVE"; then
        DOWNLOADED=1
        break
    fi
    rm -f "$WORK/$ARCHIVE"
done

if [ "$DOWNLOADED" -ne 1 ]; then
    echo "Could not download Arm GNU Toolchain from any official Arm endpoint." >&2
    echo "Check DNS/network access to armkeil.blob.core.windows.net and developer.arm.com." >&2
    exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL="$(sha256sum "$WORK/$ARCHIVE" | awk '{print $1}')"
else
    ACTUAL="$(shasum -a 256 "$WORK/$ARCHIVE" | awk '{print $1}')"
fi
ACTUAL="$(printf '%s' "$ACTUAL" | tr 'A-F' 'a-f')"
if [ "$ACTUAL" != "$EXPECTED_SHA256" ]; then
    echo "Arm GNU Toolchain SHA-256 mismatch" >&2
    echo "expected: $EXPECTED_SHA256" >&2
    echo "actual:   $ACTUAL" >&2
    exit 1
fi

tar -xJf "$WORK/$ARCHIVE" -C "$WORK/unpack" --strip-components=1
rm -rf "$DEST"
mv "$WORK/unpack" "$DEST"

"$DEST/bin/arm-none-eabi-gcc" --version >&2
printf '%s\n' "$DEST/bin"
