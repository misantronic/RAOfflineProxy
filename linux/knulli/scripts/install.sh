#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="/userdata/system/raofflineproxy"
APP_DIR="${BASE_DIR}/app"
BIN_DIR="${BASE_DIR}/bin"
TOOLS_DIR="/userdata/roms/tools"
INSTALL_SCRIPT="${TOOLS_DIR}/RAOfflineProxy Install.sh"
FB_WIDTH=0
FB_HEIGHT=0

if [ -r /sys/class/graphics/fb0/virtual_size ]; then
  IFS=, read -r FB_WIDTH FB_HEIGHT < /sys/class/graphics/fb0/virtual_size || true
fi

mkdir -p "${APP_DIR}"
mkdir -p "${BIN_DIR}"
mkdir -p "${TOOLS_DIR}"

cp -r "${SCRIPT_DIR}/app/"* "${APP_DIR}/"

cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy" "${BIN_DIR}/raofflineproxy"
cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy-ui" "${BIN_DIR}/raofflineproxy-ui"
cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy-start" "${BIN_DIR}/raofflineproxy-start"
cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy-stop" "${BIN_DIR}/raofflineproxy-stop"
cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy-dashboard" "${BIN_DIR}/raofflineproxy-dashboard"
cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy-uninstall" "${BIN_DIR}/raofflineproxy-uninstall"

chmod +x "${BIN_DIR}/raofflineproxy"
chmod +x "${BIN_DIR}/raofflineproxy-ui"
chmod +x "${BIN_DIR}/raofflineproxy-start"
chmod +x "${BIN_DIR}/raofflineproxy-stop"
chmod +x "${BIN_DIR}/raofflineproxy-dashboard"
chmod +x "${BIN_DIR}/raofflineproxy-uninstall"

cat > "${TOOLS_DIR}/RAOfflineProxy Status.sh" <<'EOF'
#!/bin/sh
exec /userdata/system/raofflineproxy/bin/raofflineproxy-dashboard
EOF

cat > "${TOOLS_DIR}/RAOfflineProxy Start.sh" <<'EOF'
#!/bin/sh
exec /userdata/system/raofflineproxy/bin/raofflineproxy-ui start
EOF

cat > "${TOOLS_DIR}/RAOfflineProxy Stop.sh" <<'EOF'
#!/bin/sh
exec /userdata/system/raofflineproxy/bin/raofflineproxy-ui stop
EOF

cat > "${TOOLS_DIR}/RAOfflineProxy Uninstall.sh" <<'EOF'
#!/bin/sh
exec /userdata/system/raofflineproxy/bin/raofflineproxy-uninstall
EOF

chmod +x "${TOOLS_DIR}/RAOfflineProxy Start.sh"
chmod +x "${TOOLS_DIR}/RAOfflineProxy Stop.sh"
chmod +x "${TOOLS_DIR}/RAOfflineProxy Status.sh"
chmod +x "${TOOLS_DIR}/RAOfflineProxy Uninstall.sh"

rm -f "${INSTALL_SCRIPT}"

cat > "${BASE_DIR}/ui-state.txt" <<'EOF'
RAOfflineProxy Install

KNULLI bundle installed.
Restart EmulationStation to refresh Tools entries.

Saved to:
  /userdata/system/raofflineproxy/ui-state.txt
EOF

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
echo "Restart EmulationStation to refresh Tools entries."
