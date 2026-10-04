
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

INSTALL_DIR="$HOME/.local/share/raofflineproxy"
UNIT_DIR="$HOME/.config/systemd/user"
UNIT_FILE="$UNIT_DIR/raofflineproxy.service"
BACKUP_DIR="$HOME/RAOfflineProxy-backup"

RA_CFG="$HOME/.var/app/org.libretro.RetroArch/config/retroarch/retroarch.cfg"
DOLPHIN_CFG="$HOME/.var/app/org.DolphinEmu.dolphin-emu/config/dolphin-emu/RetroAchievements.ini"

echo "Installing RAOfflineProxy for SteamOS..."

# Check prerequisites.
command -v python3 >/dev/null || {
    echo "Error: Python 3 is required."
    exit 1
}

command -v systemctl >/dev/null || {
    echo "Error: systemd is required."
    exit 1
}

for cfg in "$RA_CFG" "$DOLPHIN_CFG"; do
    if [[ ! -f "$cfg" ]]; then
        echo "Error: emulator config not found: $cfg"
        exit 1
    fi
done

# Don't overwrite an existing managed installation.
if [[ -e "$INSTALL_DIR" ]]; then
    echo "Error: installation directory already exists:"
    echo "$INSTALL_DIR"
    exit 1
fi

# Don't replace an unrelated systemd service.
if [[ -e "$UNIT_FILE" ]] &&
   ! grep -q '^Description=RAOfflineProxy for Steam Deck and EmuDeck$' "$UNIT_FILE"; then
    echo "Error: an unrecognised service already exists."
    exit 1
fi

# Check that the application imports successfully.
(
    cd "$SOURCE_DIR"
    python3 -m raofflineproxy.main --help >/dev/null
)

# Stage the installation before stopping anything.
mkdir -p "$HOME/.local/share"
STAGING="$(mktemp -d "$HOME/.local/share/.raofflineproxy-stage.XXXXXX")"
trap 'if [[ -d "$STAGING" ]]; then rm -rf -- "$STAGING"; fi' EXIT

cp -a "$SOURCE_DIR" "$STAGING/linux"
touch "$STAGING/.installed-by-steamos-script"

# Stop the existing service and save its definition.
mkdir -p "$BACKUP_DIR"

if [[ -e "$UNIT_FILE" ]]; then
    cp -n "$UNIT_FILE" "$BACKUP_DIR/raofflineproxy.service.pre-installer"
    systemctl --user stop raofflineproxy.service
fi

# Refuse to start if something still occupies the proxy port.
if ss -ltnH '( sport = :8080 )' | grep -q .; then
    echo "Error: port 8080 is already in use."
    exit 1
fi

# Save the emulator settings before applying the new patch.
STAMP="$(date +%Y%m%d-%H%M%S)"
cp "$RA_CFG" "$BACKUP_DIR/retroarch.$STAMP.cfg"
cp "$DOLPHIN_CFG" "$BACKUP_DIR/RetroAchievements.$STAMP.ini"

# Install the application and service.
mv "$STAGING" "$INSTALL_DIR"

mkdir -p "$UNIT_DIR"
install -m 644 "$SCRIPT_DIR/raofflineproxy.service" "$UNIT_FILE"

systemctl --user daemon-reload
systemctl --user enable --now raofflineproxy.service

# Verify that the service started.
if ! systemctl --user is-active --quiet raofflineproxy.service; then
    echo "Error: the service failed to start."
    echo "Inspect it with:"
    echo "journalctl --user -u raofflineproxy.service -n 50"
    exit 1
fi

echo "Installation complete."
echo "RAOfflineProxy is running."
