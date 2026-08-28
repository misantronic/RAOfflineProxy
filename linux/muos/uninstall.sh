#!/bin/sh
# Uninstalls RAOfflineProxy from muOS.
# Called via os.execv from the SDL menu — Python has already exited before this runs.
set -eu

APP_DIR="/run/muos/storage/application/RAOfflineProxy"
INIT_SCRIPT="/run/muos/storage/init/raofflineproxy.sh"

# Revert the retroarch.cfg patch and stop the service before removing anything,
# so an uninstall while patched never strands RetroArch on the dead local port.
if [ -x "$APP_DIR/launch.sh" ]; then
    "$APP_DIR/launch.sh" stop-proxy >/dev/null 2>&1 || true
fi

# Remove autostart init script (boot hook)
rm -f "$INIT_SCRIPT"

# Remove icon from all theme dirs. BusyBox find has no -delete, so the icons
# would survive uninstall on device with the error swallowed by the redirect.
find /run/muos/storage/theme -name "raofflineproxy.png" -exec rm -f {} + 2>/dev/null || true

# Remove the app directory (includes this script, but it's already in memory)
rm -rf "$APP_DIR"

# Restart the frontend on the Applications menu — mirrors restart_muos_frontend() in Python,
# which is skipped because os.execv() bypasses the finally block.
setsid -f /opt/muos/script/mux/frontend.sh appmenu
exit 0
