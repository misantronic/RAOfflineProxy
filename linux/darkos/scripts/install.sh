#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="/home/ark/raofflineproxy"
APP_DIR="${BASE_DIR}/app"
BIN_DIR="${BASE_DIR}/bin"
LIB_DIR="${BASE_DIR}/lib"
TOOLS_DIR="/roms/tools"
INSTALL_SCRIPT="${TOOLS_DIR}/RAOfflineProxy Install.sh"
OLD_BIN="${BASE_DIR}/bin/raofflineproxy"
UPDATE_STATUS_FILE="/home/ark/.config/raofflineproxy/update_status.json"
AUTOSTART_UNIT="/etc/systemd/system/raofflineproxy-autostart.service"
FB_WIDTH=0
FB_HEIGHT=0
WAS_RUNNING=0
RESTARTED=0

if [ -r /sys/class/graphics/fb0/virtual_size ]; then
  IFS=, read -r FB_WIDTH FB_HEIGHT < /sys/class/graphics/fb0/virtual_size || true
fi

mkdir -p "${APP_DIR}"
mkdir -p "${BIN_DIR}"
mkdir -p "${LIB_DIR}"
mkdir -p "${TOOLS_DIR}"

if [ -x "${OLD_BIN}" ]; then
  if status_output="$(${OLD_BIN} status 2>/dev/null)" && printf '%s' "${status_output}" | grep -q 'running: yes'; then
    WAS_RUNNING=1
  fi
  ${OLD_BIN} stop-proxy >/dev/null 2>&1 || true
fi

cp -r "${SCRIPT_DIR}/app/"* "${APP_DIR}/"
cp -r "${SCRIPT_DIR}/lib/"* "${LIB_DIR}/"

cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy" "${BIN_DIR}/raofflineproxy"
cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy-uninstall" "${BIN_DIR}/raofflineproxy-uninstall"

rm -f "${UPDATE_STATUS_FILE}"

chmod +x "${BIN_DIR}/raofflineproxy"
chmod +x "${BIN_DIR}/raofflineproxy-uninstall"

# ES Tools-menu scripts run unprivileged on dArkOS, so we rely on
# passwordless sudo for the device user (the same mechanism dArkOS's own
# Tools scripts use, e.g. "Enable Remote Services.sh" calling
# `sudo systemctl ...` non-interactively). `sudo -n` fails fast instead of
# hanging on a password prompt if that assumption doesn't hold here.
if ! /usr/bin/python3 -c "import pygame" >/dev/null 2>&1; then
  echo "RAOfflineProxy will try to refresh the Debian package list and install python3-pygame; it might take a few minutes."
  if command -v apt >/dev/null 2>&1 && sudo -n apt update >/dev/null 2>&1 && sudo -n apt install -y python3-pygame >/dev/null 2>&1; then
    echo "python3-pygame installed."
  else
    echo "pygame is not installed for python3 -- the RAOfflineProxy menu needs it."
    echo "Run: sudo apt install -y python3-pygame"
  fi
fi

# Installs and enables the systemd boot-reconcile unit once; toggling
# autostart afterwards only flips a config flag (no sudo needed at runtime).
"${BIN_DIR}/raofflineproxy" ensure-boot-hook >/dev/null 2>&1 || true
if [ -f "${AUTOSTART_UNIT}" ]; then
  echo "Autostart unit installed (${AUTOSTART_UNIT})."
else
  echo "Could not install the autostart unit -- needs passwordless sudo for $(whoami)."
  echo "Enable autostart from the menu once sudo access is available."
fi

if [ "${WAS_RUNNING}" -eq 1 ]; then
  if "${BIN_DIR}/raofflineproxy" start-proxy >/dev/null 2>&1; then
    RESTARTED=1
  fi
fi

cat > "${TOOLS_DIR}/RAOfflineProxy.sh" <<'EOF'
#!/bin/sh
exec /home/ark/raofflineproxy/bin/raofflineproxy menu
EOF
chmod +x "${TOOLS_DIR}/RAOfflineProxy.sh"

rm -f "${INSTALL_SCRIPT}"

cat > "${BASE_DIR}/ui-state.txt" <<'EOF'
RAOfflineProxy Install

dArkOS bundle installed.
Please Update Gamelists.
EOF

if [ "${RESTARTED}" -eq 1 ]; then
  cat >> "${BASE_DIR}/ui-state.txt" <<'EOF'

Proxy restarted to apply update.
EOF
fi

"${BIN_DIR}/raofflineproxy" text-image --output "${BASE_DIR}/ui-state.bmp" --text "$(cat "${BASE_DIR}/ui-state.txt")" --image-width "${FB_WIDTH}" --image-height "${FB_HEIGHT}" --font-scale 2 >/dev/null 2>&1 || true

if command -v fbv >/dev/null 2>&1; then
  /bin/sh -c '
    fbv -i -c -u "$1" >/dev/null 2>&1 &
    viewer_pid=$!
      sleep 6
    kill "$viewer_pid" >/dev/null 2>&1 || true
  ' sh "${BASE_DIR}/ui-state.bmp" >/dev/null 2>&1 || true
fi

echo "RAOfflineProxy dArkOS bundle installed."
if [ "${RESTARTED}" -eq 1 ]; then
  echo "Proxy restarted to apply update."
fi
echo "Please Update Gamelists."
