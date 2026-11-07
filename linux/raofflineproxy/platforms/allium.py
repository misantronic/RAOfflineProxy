from __future__ import annotations

from pathlib import Path

from . import boot_hooks, miyoo
from .base import Platform, existing_dir

MARKER_DIR = Path("/mnt/SDCARD/.allium")
# Allium packages apps as .pak directories under Apps/, not Onion's App/ layout, but runs
# on the same Miyoo Mini firmware base (same /mnt/SDCARD/.tmp_update/updater boot chain).
APP_DIR = Path("/mnt/SDCARD/Apps/RAOfflineProxy.pak")

STARTUP_SCRIPT = Path("/mnt/SDCARD/.tmp_update/updater")
AUTOSTART_LAUNCHER = APP_DIR / "autostart-launch.sh"
# The drop file Allium's own updater looks for before handing off to ota-update.sh. Our
# boot hook sits above that check, so it has to test the same path to stay out of the way.
OTA_ARCHIVE = Path("/mnt/SDCARD/allium-ota.zip")


def boot_hook_block() -> str:
    # The pending-OTA guard matters because this block sits above the updater's own OTA
    # check: on an update boot, ota-update.sh extracts the release over the whole card and
    # then reboots, so without the guard the service would be opening its SQLite database
    # on that card mid-extract and seconds before a forced reboot.
    return boot_hooks.background_block(AUTOSTART_LAUNCHER, unless_file=OTA_ARCHIVE)


class AlliumPlatform(Platform):
    id = "allium"
    name = "Allium"

    def detect(self) -> bool:
        return MARKER_DIR.is_dir()

    def startup_script(self) -> Path | None:
        return STARTUP_SCRIPT

    def config_dir(self) -> Path | None:
        return APP_DIR / "data"

    def retroarch_cfg_candidates(self, config_dir: Path) -> tuple[Path, ...]:
        return miyoo.RETROARCH_CFG_CANDIDATES


    def default_rom_root(self) -> Path | None:
        return existing_dir(miyoo.ROMS_ROOT)

    def autostart_launcher(self, config_data: dict) -> str:
        return str(AUTOSTART_LAUNCHER)

    def install_boot_hook(self, startup_script: Path, config_data: dict) -> None:
        # Same file and mechanism as spruce's: the script ends by exec'ing alliumd, which
        # never returns, so appending would leave the hook dead code.
        boot_hooks.prepend_block(startup_script, boot_hook_block(), "allium")


PLATFORM = AlliumPlatform()
