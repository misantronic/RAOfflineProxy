#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="/userdata/system/raofflineproxy"
TOOLS_DIR="/userdata/roms/tools"
BUNDLE_DIR="/userdata/system/raofflineproxy-knulli-bundle"
FB_WIDTH=0
FB_HEIGHT=0

if [ -r /sys/class/graphics/fb0/virtual_size ]; then
  IFS=, read -r FB_WIDTH FB_HEIGHT < /sys/class/graphics/fb0/virtual_size || true
fi

mkdir -p "${BASE_DIR}"

cat > "${BASE_DIR}/ui-state.txt" <<'EOF'
RAOfflineProxy Uninstall

KNULLI bundle removed.

Saved to:
  /userdata/system/raofflineproxy/ui-state.txt
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

if [ -x "${BASE_DIR}/bin/raofflineproxy-stop" ]; then
  "${BASE_DIR}/bin/raofflineproxy-stop" || true
fi

rm -f "${TOOLS_DIR}/RAOfflineProxy Start.sh"
rm -f "${TOOLS_DIR}/RAOfflineProxy Stop.sh"
rm -f "${TOOLS_DIR}/RAOfflineProxy UI.sh"
rm -f "${TOOLS_DIR}/RAOfflineProxy Status.sh"
rm -f "${TOOLS_DIR}/RAOfflineProxy Uninstall.sh"
rm -f "/userdata/system/scripts/RAOfflineProxy_game_hook.sh"

rm -rf "${BASE_DIR}"
rm -rf "${BUNDLE_DIR}"

echo "RAOfflineProxy KNULLI bundle removed."
