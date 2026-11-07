from __future__ import annotations

from pathlib import Path

from . import miyoo, spruce
from .base import Platform, existing_dir

APP_DIR = Path("/mnt/SDCARD/App/RAOfflineProxy")
# Onion's own version marker, used to break a tie when both firmwares have left traces on
# the card. See spruce.SprucePlatform.detect().
VERSION_FILE = Path("/mnt/SDCARD/.tmp_update/onionVersion/version.txt")

STARTUP_SCRIPT = Path("/mnt/SDCARD/.tmp_update/startup/raofflineproxy.sh")
AUTOSTART_LAUNCHER = APP_DIR / "autostart-launch.sh"


def boot_hook_script() -> str:
    return "\n".join(
        [
            "#!/bin/sh",
            "set -eu",
            "",
            f"APP_DIR={APP_DIR}",
            'if [ -x "$APP_DIR/autostart-launch.sh" ]; then',
            '  sh "$APP_DIR/autostart-launch.sh"',
            "fi",
            "",
        ]
    )


class OnionPlatform(Platform):
    id = "onion"
    name = "OnionOS"

    def detect(self) -> bool:
        # spruceOS reuses Onion's /mnt/SDCARD/App layout, so the app directory alone does not
        # identify Onion: without the spruce exclusion every Onion-only branch (most visibly
        # the OnionOS version gate) would also fire on spruce.
        return APP_DIR.exists() and not spruce.PLATFORM.detect()

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
        startup_script.parent.mkdir(parents=True, exist_ok=True)
        startup_script.write_text(boot_hook_script(), encoding="utf-8")

    def remove_boot_hook(self, startup_script: Path) -> None:
        startup_script.unlink()

    def boot_hook_installed(self, startup_script: Path) -> bool:
        return True


PLATFORM = OnionPlatform()
