from __future__ import annotations

import sys
from pathlib import Path

from .base import Platform


class WindowsPlatform(Platform):
    """The raofflineproxy.windows package is imported only inside these hooks, so it never
    loads on the other devices."""

    id = "windows"
    name = "Windows"
    support_label = "Windows"

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

    def process_backend(self):
        from ..windows import process

        return process

    def preferred_rom_root(self, config_data: dict) -> Path | None:
        # A Windows ROM library can be anywhere, so it is configured ("rom_root" in config.json).
        configured = config_data.get("rom_root")
        if configured and Path(str(configured)).is_dir():
            return Path(str(configured))
        return None

    def autostart_supported(self, startup_script: Path | None) -> bool:
        return True

    def autostart_enabled(self, config_data: dict) -> bool | None:
        from ..windows import process

        return process.autostart_enabled(config_data)

    def enable_autostart(self, config_data: dict) -> bool:
        from ..windows import process

        process.enable_autostart()
        return True

    def disable_autostart(self, config_data: dict) -> bool:
        from ..windows import process

        process.disable_autostart()
        return True

    def duckstation_ini(self) -> Path | None:
        from ..windows import paths

        return paths.duckstation_ini()

    def flycast_cfg(self) -> Path | None:
        from ..windows import paths

        return paths.flycast_cfg()


PLATFORM = WindowsPlatform()
