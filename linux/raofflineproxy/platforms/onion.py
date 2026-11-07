from __future__ import annotations

from pathlib import Path

from . import miyoo, spruce
from .base import Platform

APP_DIR = Path("/mnt/SDCARD/App/RAOfflineProxy")
# Onion's own version marker, used to break a tie when both firmwares have left traces on
# the card. See spruce.SprucePlatform.detect().
VERSION_FILE = Path("/mnt/SDCARD/.tmp_update/onionVersion/version.txt")

STARTUP_SCRIPT = Path("/mnt/SDCARD/.tmp_update/startup/raofflineproxy.sh")


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


PLATFORM = OnionPlatform()
