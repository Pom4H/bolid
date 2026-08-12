#!/usr/bin/env bash
# Launcher for Тест-ДПЛС preview (mock BLE UI).
set -euo pipefail
export PATH="${HOME}/.dotnet:${PATH}"
export DISPLAY="${DISPLAY:-:1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="${ROOT}/src/TestDPLS.Preview/bin/Release/net8.0/TestDPLS.Preview"

if [[ ! -x "$APP" ]]; then
  echo "Сборка preview…"
  dotnet build "${ROOT}/src/TestDPLS.Preview/TestDPLS.Preview.csproj" -c Release
fi

exec "$APP" "$@"
