from __future__ import annotations

import sys
from pathlib import Path

from .base import Platform


class WindowsPlatform(Platform):
    """The raofflineproxy.windows package is imported only inside these hooks, so it never
    loads on the other devices."""

    id = "windows"
    name = "Windows"

    def detect(self) -> bool:
        return sys.platform == "win32"

    def config_dir(self) -> Path | None:
        from ..windows import paths

        return paths.config_dir()

    def retroarch_cfg_candidates(self, config_dir: Path) -> tuple[Path, ...]:
        from ..windows import paths

        # The placeholder comes last, so it's used only when no RetroArch exists.
        return (*paths.retroarch_cfg_candidates(), paths.retroarch_placeholder(config_dir))

    def ppsspp_ini(self) -> Path | None:
        from ..windows import paths

        return paths.ppsspp_ini()

    def dolphin_config_dir(self) -> Path | None:
        from ..windows import paths

        return paths.dolphin_config_dir()

    def pcsx2_ini(self) -> Path | None:
        from ..windows import paths

        return paths.pcsx2_ini()

    def duckstation_ini(self) -> Path | None:
        from ..windows import paths

        return paths.duckstation_ini()

    def flycast_cfg(self) -> Path | None:
        from ..windows import paths

        return paths.flycast_cfg()


PLATFORM = WindowsPlatform()
