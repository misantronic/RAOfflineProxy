
#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR="$HOME/.local/share/raofflineproxy"
UNIT_FILE="$HOME/.config/systemd/user/raofflineproxy.service"

# Only remove installations created by our installer.
if [[ ! -f "$INSTALL_DIR/.installed-by-steamos-script" ]]; then
    echo "No managed RAOfflineProxy installation found."
    exit 1
fi

# Refuse to remove an unrelated systemd service.
if [[ -e "$UNIT_FILE" ]] &&
   ! grep -q '^WorkingDirectory=%h/.local/share/raofflineproxy/linux$' "$UNIT_FILE"; then
    echo "Error: unrecognised service. Nothing removed."
    exit 1
fi

# Stop the service and revert emulator configurations.
if systemctl --user is-active --quiet raofflineproxy.service; then
    systemctl --user disable --now raofflineproxy.service
else
    systemctl --user disable raofflineproxy.service 2>/dev/null || true
    (
        cd "$INSTALL_DIR/linux"
        python3 -m raofflineproxy.main stop-proxy
    )
fi

# Remove our service definition.
rm -f "$UNIT_FILE"
systemctl --user daemon-reload

# Remove only the installed program.
rm -rf -- "$INSTALL_DIR"

echo "RAOfflineProxy uninstalled."
echo "Cached games and achievement data have been preserved."
echo "Your development repository has not been modified."
