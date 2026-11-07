from __future__ import annotations

from pathlib import Path

from . import miyoo
from .base import Platform

MARKER_DIR = Path("/mnt/SDCARD/.allium")
# Allium packages apps as .pak directories under Apps/, not Onion's App/ layout, but runs
# on the same Miyoo Mini firmware base (same /mnt/SDCARD/.tmp_update/updater boot chain).
APP_DIR = Path("/mnt/SDCARD/Apps/RAOfflineProxy.pak")

STARTUP_SCRIPT = Path("/mnt/SDCARD/.tmp_update/updater")


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


PLATFORM = AlliumPlatform()
