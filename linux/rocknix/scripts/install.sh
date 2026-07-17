#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="/storage/.local/share/raofflineproxy"
APP_DIR="${BASE_DIR}/app"
BIN_DIR="${BASE_DIR}/bin"
LIB_DIR="${BASE_DIR}/lib"
MODULES_DIR="/storage/.config/modules"
TOOL_SOURCE="${BASE_DIR}/RAOfflineProxy.sh"
TOOL_LAUNCHER="${MODULES_DIR}/RAOfflineProxy.sh"
OLD_BIN="${BASE_DIR}/bin/raofflineproxy"
UPDATE_STATUS_FILE="/storage/.config/raofflineproxy/update_status.json"
WAS_RUNNING=0
RESTARTED=0

if [ -x "${OLD_BIN}" ]; then
  if status_output="$(${OLD_BIN} status 2>/dev/null)" && printf '%s' "${status_output}" | grep -q 'running: yes'; then
    WAS_RUNNING=1
  fi
  ${OLD_BIN} stop-proxy >/dev/null 2>&1 || true
fi

rm -rf "${APP_DIR}" "${LIB_DIR}"
mkdir -p "${APP_DIR}"
mkdir -p "${BIN_DIR}"
mkdir -p "${LIB_DIR}"
mkdir -p "${MODULES_DIR}"

cp -r "${SCRIPT_DIR}/app/"* "${APP_DIR}/"
cp -r "${SCRIPT_DIR}/lib/"* "${LIB_DIR}/"

cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy" "${BIN_DIR}/raofflineproxy"
cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy-uninstall" "${BIN_DIR}/raofflineproxy-uninstall"

# Keep the canonical Tools launcher inside the app dir so the boot hook can
# re-add it after ROCKNIX wipes /storage/.config/modules on each boot.
cp "${SCRIPT_DIR}/scripts/tool-launcher.sh" "${TOOL_SOURCE}"
cp "${SCRIPT_DIR}/scripts/tool-launcher.sh" "${TOOL_LAUNCHER}"

rm -f "${UPDATE_STATUS_FILE}"

chmod +x "${BIN_DIR}/raofflineproxy"
chmod +x "${BIN_DIR}/raofflineproxy-uninstall"
chmod +x "${TOOL_SOURCE}"
chmod +x "${TOOL_LAUNCHER}"

# Install the boot hook so the Tools entry is re-added on every reboot. The
# proxy itself only autostarts when the user enables it (boot-reconcile gates
# on the autostart flag).
"${BIN_DIR}/raofflineproxy" ensure-boot-hook >/dev/null 2>&1 || true

if [ "${WAS_RUNNING}" -eq 1 ]; then
  if "${BIN_DIR}/raofflineproxy" start-proxy >/dev/null 2>&1; then
    RESTARTED=1
  fi
fi

# Ask the running EmulationStation to rescan so the new Tools entry appears
# without a manual "Update Gamelists". Best-effort: harmless if ES isn't
# running or its HTTP API isn't reachable.
curl -s --max-time 3 "http://127.0.0.1:1234/reloadgames" >/dev/null 2>&1 || true

echo "RAOfflineProxy ROCKNIX bundle installed."
if [ "${RESTARTED}" -eq 1 ]; then
  echo "Proxy restarted to apply update."
fi
echo "Open the Tools menu to launch RAOfflineProxy."
