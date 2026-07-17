#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="/storage/.local/share/raofflineproxy"
BUNDLE_DIR="/storage/.local/share/.raofflineproxy-rocknix-bundle"
CONFIG_DIR="/storage/.config/raofflineproxy"
TOOL_LAUNCHER="/storage/.config/modules/RAOfflineProxy.sh"
AUTOSTART_SCRIPT="/storage/.config/autostart/raofflineproxy.sh"

if [ -x "${BASE_DIR}/bin/raofflineproxy" ]; then
  "${BASE_DIR}/bin/raofflineproxy" stop-proxy || true
  "${BASE_DIR}/bin/raofflineproxy" remove-boot-hook || true
fi

if command -v pkill >/dev/null 2>&1; then
  pkill -f 'raofflineproxy.main' >/dev/null 2>&1 || true
fi

rm -f "${TOOL_LAUNCHER}"
rm -f "${AUTOSTART_SCRIPT}"

# Remove app, config/cache, and this bundle synchronously. The uninstall is
# reached via os.execv from the menu, so nothing lingers to rewrite files, and
# a detached cleanup step does not survive the ROCKNIX Tools/foot terminal
# tearing down its process group (nohup only blocks SIGHUP). Removing the
# still-running bundle dir is safe on Linux: the open script fd keeps the
# inode alive until this process exits.
rm -rf "${CONFIG_DIR}"
rm -rf "${BASE_DIR}"

echo "RAOfflineProxy ROCKNIX bundle removed."

rm -rf "${BUNDLE_DIR}" || true
