#!/bin/sh
# Uninstalls RAOfflineProxy from muOS.
# Called via os.execv from the SDL menu — Python has already exited before this runs.
set -eu

APP_DIR="/run/muos/storage/application/RAOfflineProxy"
INIT_SCRIPT="/run/muos/storage/init/raofflineproxy.sh"

# Remove autostart init script
rm -f "$INIT_SCRIPT"

# Remove icon from all theme dirs
find /run/muos/storage/theme -name "raofflineproxy.png" -delete 2>/dev/null || true

# Remove the app directory (includes this script, but it's already in memory)
rm -rf "$APP_DIR"

# Restart the frontend on the Applications menu — mirrors restart_muos_frontend() in Python,
# which is skipped because os.execv() bypasses the finally block.
setsid -f /opt/muos/script/mux/frontend.sh appmenu
exit 0
