from __future__ import annotations

from pathlib import Path

from .base import Platform

MARKER_PATH = Path("/opt/muos/script/archive")

INIT_DIR = Path("/run/muos/storage/init")
APP_DIR = Path("/run/muos/storage/application/RAOfflineProxy")
RETROARCH_CFG = Path("/opt/muos/share/info/config/retroarch.cfg")
USER_INIT_CONFIG = Path("/opt/muos/config/settings/advanced/user_init")
STARTUP_SCRIPT = INIT_DIR / "raofflineproxy.sh"


class MuosPlatform(Platform):
    id = "muos"
    name = "muOS"

    def detect(self) -> bool:
        return MARKER_PATH.exists()

    def startup_script(self) -> Path | None:
        return STARTUP_SCRIPT

    def config_dir(self) -> Path | None:
        return APP_DIR / "data"

    def retroarch_cfg_candidates(self, config_dir: Path) -> tuple[Path, ...]:
        return (RETROARCH_CFG,)


PLATFORM = MuosPlatform()
