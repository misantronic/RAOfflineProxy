#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="/userdata/system/raofflineproxy"
APP_DIR="${BASE_DIR}/app"
BIN_DIR="${BASE_DIR}/bin"
TOOLS_DIR="/userdata/roms/tools"
INSTALL_SCRIPT="${TOOLS_DIR}/RAOfflineProxy Install.sh"
OLD_BIN="${BASE_DIR}/bin/raofflineproxy"
FB_WIDTH=0
FB_HEIGHT=0
WAS_RUNNING=0
RESTARTED=0

if [ -r /sys/class/graphics/fb0/virtual_size ]; then
  IFS=, read -r FB_WIDTH FB_HEIGHT < /sys/class/graphics/fb0/virtual_size || true
fi

mkdir -p "${APP_DIR}"
mkdir -p "${BIN_DIR}"
mkdir -p "${TOOLS_DIR}"

if [ -x "${OLD_BIN}" ]; then
  if status_output="$(${OLD_BIN} status 2>/dev/null)" && printf '%s' "${status_output}" | grep -q 'running: yes'; then
    WAS_RUNNING=1
  fi
  ${OLD_BIN} stop-proxy >/dev/null 2>&1 || true
fi

cp -r "${SCRIPT_DIR}/app/"* "${APP_DIR}/"

cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy" "${BIN_DIR}/raofflineproxy"
cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy-uninstall" "${BIN_DIR}/raofflineproxy-uninstall"

chmod +x "${BIN_DIR}/raofflineproxy"
chmod +x "${BIN_DIR}/raofflineproxy-uninstall"

if [ "${WAS_RUNNING}" -eq 1 ]; then
  if "${BIN_DIR}/raofflineproxy" start-proxy >/dev/null 2>&1; then
    RESTARTED=1
  fi
fi

cat > "${TOOLS_DIR}/RAOfflineProxy Menu.sh" <<'EOF'
#!/bin/sh
exec /userdata/system/raofflineproxy/bin/raofflineproxy menu
EOF
chmod +x "${TOOLS_DIR}/RAOfflineProxy Menu.sh"

rm -f "${INSTALL_SCRIPT}"

cat > "${BASE_DIR}/ui-state.txt" <<'EOF'
RAOfflineProxy Install

KNULLI bundle installed.
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

echo "RAOfflineProxy KNULLI bundle installed."
if [ "${RESTARTED}" -eq 1 ]; then
  echo "Proxy restarted to apply update."
fi
echo "Please Update Gamelists."
