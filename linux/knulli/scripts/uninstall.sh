#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="/userdata/system/raofflineproxy"
TOOLS_DIR="/userdata/roms/tools"
BUNDLE_DIR="/userdata/system/raofflineproxy-knulli-bundle"

if [ -x "${BASE_DIR}/bin/raofflineproxy-stop" ]; then
  "${BASE_DIR}/bin/raofflineproxy-stop" || true
fi

rm -f "${TOOLS_DIR}/RAOfflineProxy Start.sh"
rm -f "${TOOLS_DIR}/RAOfflineProxy Stop.sh"
rm -f "${TOOLS_DIR}/RAOfflineProxy Uninstall.sh"
rm -f "/userdata/system/scripts/RAOfflineProxy_game_hook.sh"

rm -rf "${BASE_DIR}"
rm -rf "${BUNDLE_DIR}"

echo "RAOfflineProxy KNULLI bundle removed."
