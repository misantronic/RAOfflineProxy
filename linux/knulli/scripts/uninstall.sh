#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="/userdata/system/raofflineproxy"
TOOLS_DIR="/userdata/roms/tools"

if [ -x "${BASE_DIR}/bin/raofflineproxy-stop" ]; then
  "${BASE_DIR}/bin/raofflineproxy-stop" || true
fi

rm -f "${TOOLS_DIR}/RAOfflineProxy Start.sh"
rm -f "${TOOLS_DIR}/RAOfflineProxy Stop.sh"
rm -f "${TOOLS_DIR}/RAOfflineProxy Status.sh"

rm -rf "${BASE_DIR}"

echo "RAOfflineProxy KNULLI bundle removed."
