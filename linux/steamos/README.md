
# SteamOS / EmuDeck support

Experimental support for running RAOfflineProxy on Steam Deck
with EmuDeck's standalone RetroArch and Dolphin Flatpaks.

The proxy starts automatically through a systemd user service
and works in both Desktop Mode and Gaming Mode.

## Requirements

- SteamOS with Python 3 and systemd
- EmuDeck's RetroArch and Dolphin Flatpaks
- RetroAchievements configured in both emulators
- Casual (Softcore) achievements enabled

Both emulator configuration files must already exist.
Hardcore achievements are not supported.

## Installation

Clone RAOfflineProxy and run the installer from the
repository root:

```bash
git clone https://github.com/misantronic/RAOfflineProxy.git
cd RAOfflineProxy
./linux/steamos/install.sh
```

Close both emulators before installing. The installer backs up
their configurations and enables a systemd user service.

Check the service with:

```bash
systemctl --user status raofflineproxy.service
```

## Usage

Start a game while online to cache its achievement data.

Once cached, the game can be played offline. Achievement
unlocks are queued locally and submitted when connectivity
returns.

The proxy starts automatically with the Steam Deck's user
session, including Gaming Mode.

## Troubleshooting

Inspect the service logs with:

```bash
journalctl --user -u raofflineproxy.service -n 50
```

Restart the service with:

```bash
systemctl --user restart raofflineproxy.service
```

If EmuDeck resets an emulator's configuration, restart the
service with both emulators closed to reapply the settings.

## Uninstallation

Synchronise pending achievements and close both emulators.

From the original source repository, run:

```bash
./linux/steamos/uninstall.sh
```

The uninstaller removes the installed application and its
systemd service. Cached games and achievement data are
preserved in ~/.config/raofflineproxy.

## Limitations

- This integration is experimental.
- Only the separate RetroArch and Dolphin Flatpaks have been tested.
- Both emulators must be installed before running the installer.
- In-place upgrades are not yet supported.
- The uninstaller and clean-install paths require further testing.
