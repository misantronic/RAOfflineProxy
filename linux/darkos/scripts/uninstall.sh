#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="/home/ark/raofflineproxy"
TOOLS_DIR="/roms/tools"
BUNDLE_DIR="/home/ark/raofflineproxy-darkos-bundle"
CONFIG_DIR="/home/ark/.config/raofflineproxy"
AUTOSTART_UNIT="/etc/systemd/system/raofflineproxy.service"
FB_WIDTH=0
FB_HEIGHT=0

if [ -r /sys/class/graphics/fb0/virtual_size ]; then
  IFS=, read -r FB_WIDTH FB_HEIGHT < /sys/class/graphics/fb0/virtual_size || true
fi

mkdir -p "${BASE_DIR}"

if [ -x "${BASE_DIR}/bin/raofflineproxy" ]; then
  "${BASE_DIR}/bin/raofflineproxy" stop-proxy || true
  # remove-boot-hook uses passwordless sudo internally (same as install.sh);
  # falls back to a plain attempt here in case that binary is already gone.
  "${BASE_DIR}/bin/raofflineproxy" remove-boot-hook || true
fi

sudo -n systemctl disable --now $(basename "${AUTOSTART_UNIT}") >/dev/null 2>&1 || true
sudo -n rm -f "${AUTOSTART_UNIT}" >/dev/null 2>&1 || rm -f "${AUTOSTART_UNIT}" >/dev/null 2>&1 || true
sudo -n systemctl daemon-reload >/dev/null 2>&1 || true

if command -v pkill >/dev/null 2>&1; then
  pkill -f 'raofflineproxy.main' >/dev/null 2>&1 || true
fi

cat > "${BASE_DIR}/ui-state.txt" <<'EOF'
RAOfflineProxy Uninstall

dArkOS bundle removed.
EOF

if [ -x "${BASE_DIR}/bin/raofflineproxy" ]; then
  "${BASE_DIR}/bin/raofflineproxy" text-image --output "${BASE_DIR}/ui-state.bmp" --text "$(cat "${BASE_DIR}/ui-state.txt")" --image-width "${FB_WIDTH}" --image-height "${FB_HEIGHT}" --font-scale 2 >/dev/null 2>&1 || true

  if command -v fbv >/dev/null 2>&1; then
    /bin/sh -c '
      fbv -i -c -u "$1" >/dev/null 2>&1 &
      viewer_pid=$!
      sleep 6
      kill "$viewer_pid" >/dev/null 2>&1 || true
    ' sh "${BASE_DIR}/ui-state.bmp" >/dev/null 2>&1 || true
  fi
fi

rm -f "${TOOLS_DIR}/RAOfflineProxy.sh"

nohup /bin/sh -c '
  sleep 2
  rm -rf "$1"
  rm -rf "$2"
  rm -rf "$3"
' sh "${CONFIG_DIR}" "${BASE_DIR}" "${BUNDLE_DIR}" >/dev/null 2>&1 &

echo "RAOfflineProxy dArkOS bundle removed."
